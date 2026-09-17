package dev.servereer.livingshopkeeper.npc;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a player-name skin to a Mojang texture property (value + signature) and caches it to disk,
 * so a shopkeeper's skin survives restarts and never needs the network twice — the offline-safe cache
 * pattern PixelProfiler uses.
 *
 * <p>Zero JSON dependency: the two Mojang responses are tiny and well-formed, and base64
 * value/signature fields contain no quotes, so a narrow regex is safe. All fetching is off the main
 * thread; the callback also fires off-thread — callers hop back to the main thread themselves.
 */
public final class SkinCache {

    private static final Pattern ID_RE = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
    private static final Pattern VALUE_RE = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SIGNATURE_RE = Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"");

    /** A resolved skin. {@code signature} may be null (unsigned / offline-mode). */
    public record Skin(String value, String signature) {}

    private static final String TEXTURE_HOST = "textures.minecraft.net";

    /**
     * Build an <em>unsigned</em> profile texture value from a {@code textures.minecraft.net} skin URL (or a
     * bare texture hash) — fully offline, no network. Returns null if the input isn't a usable skin URL.
     *
     * <p>The vanilla client only loads skin textures from {@code textures.minecraft.net}; a value pointing
     * anywhere else silently renders as the default skin, so those are rejected. Unsigned values render fine
     * on a (non-self) packet-NPC profile — the client doesn't verify the signature there.
     */
    public static String textureValueForUrl(String urlOrHash) {
        String url = normalizeTextureUrl(urlOrHash);
        if (url == null) return null;
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Canonical {@code http://textures.minecraft.net/texture/…} URL for the input, or null if unusable. */
    static String normalizeTextureUrl(String in) {
        if (in == null) return null;
        String s = in.trim();
        if (s.isEmpty()) return null;
        // bare texture hash → canonical URL
        if (s.matches("[0-9a-fA-F]{32,64}")) return "http://" + TEXTURE_HOST + "/texture/" + s.toLowerCase(Locale.ROOT);
        String noProto = s.replaceFirst("(?i)^https?://", "");
        int slash = noProto.indexOf('/');
        String host = (slash < 0 ? noProto : noProto.substring(0, slash)).toLowerCase(Locale.ROOT);
        if (!host.equals(TEXTURE_HOST)) return null;   // client only loads skins from textures.minecraft.net
        return "http://" + noProto;                    // normalize protocol to http (Mojang's convention)
    }

    private final JavaPlugin plugin;
    private final File dir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lsk-skin-fetch");
        t.setDaemon(true);
        return t;
    });

    public SkinCache(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "skins");
        //noinspection ResultOfMethodCallIgnored
        this.dir.mkdirs();
    }

    /** Disk-cached skin for {@code name}, or null — synchronous, never touches the network. */
    public Skin cached(String name) {
        File f = file(name);
        if (!f.isFile()) return null;
        YamlConfiguration yc = YamlConfiguration.loadConfiguration(f);
        String value = yc.getString("value");
        if (value == null) return null;
        return new Skin(value, yc.getString("signature"));
    }

    /**
     * Resolve {@code name} (disk cache first, then Mojang) off the main thread. {@code cb} is invoked on
     * the fetch thread with either a {@link Skin} or a non-null error — never both. On success the result
     * is written to disk before {@code cb} runs.
     */
    public void fetchAsync(String name, BiConsumer<Skin, String> cb) {
        Skin hit = cached(name);
        if (hit != null) { cb.accept(hit, null); return; }
        pool.submit(() -> {
            try {
                String uuid = getString("https://api.mojang.com/users/profiles/minecraft/" + name, ID_RE);
                if (uuid == null) { cb.accept(null, "no such player: " + name); return; }
                String body = get("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
                Matcher vm = VALUE_RE.matcher(body);
                if (!vm.find()) { cb.accept(null, "no textures for " + name); return; }
                String value = vm.group(1);
                Matcher sm = SIGNATURE_RE.matcher(body);
                String signature = sm.find() ? sm.group(1) : null;
                Skin skin = new Skin(value, signature);
                save(name, skin);
                cb.accept(skin, null);
            } catch (Throwable t) {
                cb.accept(null, String.valueOf(t.getMessage()));
            }
        });
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    private String getString(String url, Pattern first) throws Exception {
        Matcher m = first.matcher(get(url));
        return m.find() ? m.group(1) : null;
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "LivingShopkeeper")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + resp.statusCode() + " for " + url);
        return resp.body();
    }

    private void save(String name, Skin skin) {
        try {
            YamlConfiguration yc = new YamlConfiguration();
            yc.set("value", skin.value());
            if (skin.signature() != null) yc.set("signature", skin.signature());
            yc.save(file(name));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not cache skin '" + name + "': " + e.getMessage());
        }
    }

    private File file(String name) {
        return new File(dir, name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_") + ".yml");
    }
}
