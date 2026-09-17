package dev.servereer.livingshopkeeper.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Optional LLM-generated NPC chatter through any OpenAI-compatible endpoint (OpenRouter, Cerebras, …).
 * Config {@code ai-chat.providers}: each provider carries a URL plus MULTIPLE keys and MULTIPLE models —
 * on any failure (HTTP error, timeout, malformed reply) that key+model combo goes on cooldown and the
 * next one is tried, so one dead key or rate-limited model never silences the plaza. Disabled by default;
 * when off (or every combo is down, or the rate caps are hit) callers fall back to the static small-talk
 * pool, so this never becomes a hard dependency.
 *
 * <p>Cost/CPU discipline: fully async on the JDK HttpClient (no server-thread I/O), one small request per
 * conversation beat at most, capped globally ({@code requests-per-minute}) and per conversation
 * ({@code min-interval-seconds}), tiny {@code max-tokens}, and a short bounded transcript per conversation.
 */
public final class AiChatService {

    private record Provider(String name, String url, List<String> keys, List<String> models) {}

    private final JavaPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private volatile boolean enabled;
    private volatile List<Provider> providers = List.of();
    private volatile int maxTokens = 60;
    private volatile int timeoutSeconds = 8;
    private volatile long minIntervalMs = 20_000;
    private volatile int requestsPerMinute = 6;
    private volatile int historyLines = 6;
    private volatile String systemTemplate = "";

    private final Map<String, Long> comboCooldown = new ConcurrentHashMap<>();   // provider|key|model → retry-at
    private final Map<String, Long> convLastGen = new ConcurrentHashMap<>();     // conversation → last request
    private final Map<String, Deque<String>> history = new ConcurrentHashMap<>(); // conversation → "Name: line"
    private final Deque<Long> requestWindow = new ArrayDeque<>();                 // global rpm window

    public AiChatService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-read the {@code ai-chat} config block (called on /lsk reload via the manager's readConfig). */
    public void reload() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("ai-chat.enabled", false);
        this.maxTokens = Math.max(16, cfg.getInt("ai-chat.max-tokens", 60));
        this.timeoutSeconds = Math.max(2, cfg.getInt("ai-chat.timeout-seconds", 8));
        this.minIntervalMs = (long) (Math.max(3, cfg.getDouble("ai-chat.min-interval-seconds", 20)) * 1000);
        this.requestsPerMinute = Math.max(1, cfg.getInt("ai-chat.requests-per-minute", 6));
        this.historyLines = Math.max(0, cfg.getInt("ai-chat.history", 6));
        this.systemTemplate = cfg.getString("ai-chat.system",
                "You are <speaker>, a Minecraft villager NPC. <speaker_persona> "
                        + "You are chatting with <listener> (<listener_persona>). <context> "
                        + "Reply with exactly ONE short casual in-character line, at most 18 words. "
                        + "No quotes, no name prefix, no narration, no emojis.");
        List<Provider> list = new ArrayList<>();
        List<Map<?, ?>> raw = cfg.getMapList("ai-chat.providers");
        for (Map<?, ?> m : raw) {
            String name = str(m.get("name"), "provider");
            String url = str(m.get("url"), null);
            List<String> keys = strList(m.get("keys"));
            if (keys.isEmpty() && m.get("key") != null) keys = List.of(str(m.get("key"), ""));
            List<String> models = strList(m.get("models"));
            if (models.isEmpty() && m.get("model") != null) models = List.of(str(m.get("model"), ""));
            if (url != null && !keys.isEmpty() && !models.isEmpty()) {
                list.add(new Provider(name, url, keys, models));
            }
        }
        this.providers = list;
        if (enabled && list.isEmpty()) {
            plugin.getLogger().warning("ai-chat.enabled is true but no usable providers are configured.");
        }
    }

    public boolean enabled() {
        return enabled && !providers.isEmpty();
    }

    /** Append a said line (authored, static, or generated) to a conversation's bounded transcript. */
    public void record(String convKey, String speakerName, String line) {
        if (!enabled() || historyLines == 0) return;
        Deque<String> h = history.computeIfAbsent(convKey, k -> new ArrayDeque<>());
        synchronized (h) {
            h.addLast(speakerName + ": " + line);
            while (h.size() > historyLines) h.removeFirst();
        }
    }

    public void endConversation(String convKey) {
        history.remove(convKey);
        convLastGen.remove(convKey);
    }

    /**
     * Try to generate the speaker's next line. Returns false immediately (caller uses static small-talk)
     * when disabled or a rate cap blocks it; otherwise fires an async request and later calls
     * {@code callback} ON THE MAIN THREAD with the line, or null when every provider failed.
     */
    public boolean tryGenerate(String convKey, String speakerName, String speakerPersona,
                               String listenerName, String listenerPersona, String context,
                               Consumer<String> callback) {
        if (!enabled()) return false;
        long now = System.currentTimeMillis();
        Long last = convLastGen.get(convKey);
        if (last != null && now - last < minIntervalMs) return false;
        synchronized (requestWindow) {
            while (!requestWindow.isEmpty() && now - requestWindow.peekFirst() > 60_000) requestWindow.removeFirst();
            if (requestWindow.size() >= requestsPerMinute) return false;
            requestWindow.addLast(now);
        }
        convLastGen.put(convKey, now);

        String system = systemTemplate
                .replace("<speaker_persona>", persona(speakerPersona))
                .replace("<listener_persona>", persona(listenerPersona))
                .replace("<speaker>", speakerName)
                .replace("<listener>", listenerName)
                .replace("<context>", context == null ? "" : context);
        StringBuilder user = new StringBuilder();
        Deque<String> h = history.get(convKey);
        if (h != null) {
            synchronized (h) {
                for (String lineIn : h) user.append(lineIn).append('\n');
            }
        }
        user.append(speakerName).append(" says next:");
        attempt(candidates(), 0, system, user.toString(), line -> {
            if (line != null) record(convKey, speakerName, line);
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(line));
        });
        return true;
    }

    /** Every provider|key|model combo currently off cooldown, in config order. */
    private List<String[]> candidates() {
        long now = System.currentTimeMillis();
        List<String[]> out = new ArrayList<>();
        for (Provider p : providers) {
            for (String key : p.keys()) {
                for (String model : p.models()) {
                    if (now >= comboCooldown.getOrDefault(p.name() + "|" + key + "|" + model, 0L)) {
                        out.add(new String[]{p.name(), p.url(), key, model});
                    }
                }
            }
        }
        return out;
    }

    /** Try candidate {@code i}; on failure cool that combo down for a minute and move to the next. */
    private void attempt(List<String[]> combos, int i, String system, String user, Consumer<String> done) {
        if (i >= combos.size()) {
            done.accept(null);
            return;
        }
        String[] c = combos.get(i);
        JsonObject body = new JsonObject();
        body.addProperty("model", c[3]);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", 0.9);
        JsonArray messages = new JsonArray();
        messages.add(msg("system", system));
        messages.add(msg("user", user));
        body.add("messages", messages);

        HttpRequest req = HttpRequest.newBuilder(URI.create(c[1]))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + c[2])
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
            String line = null;
            if (err == null && resp != null && resp.statusCode() / 100 == 2) line = extract(resp.body());
            if (line != null) {
                done.accept(line);
            } else {
                comboCooldown.put(c[0] + "|" + c[2] + "|" + c[3], System.currentTimeMillis() + 60_000);
                attempt(combos, i + 1, system, user, done);
            }
        });
    }

    /** choices[0].message.content, trimmed to one clean line, or null when unusable. */
    private static String extract(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            String line = content.strip();
            int nl = line.indexOf('\n');
            if (nl > 0) line = line.substring(0, nl).strip();
            if (line.length() >= 2 && line.startsWith("\"") && line.endsWith("\"")) {
                line = line.substring(1, line.length() - 1);
            }
            if (line.length() > 160) line = line.substring(0, 157) + "…";
            return line.isBlank() ? null : line;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject msg(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static String persona(String p) {
        return p == null || p.isBlank() ? "a friendly market NPC" : p;
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null) out.add(String.valueOf(x));
        return out;
    }
}
