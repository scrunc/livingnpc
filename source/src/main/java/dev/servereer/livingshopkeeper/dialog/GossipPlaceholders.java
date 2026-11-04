package dev.servereer.livingshopkeeper.dialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The gossip placeholder registry — the one place that knows how a tag in a {@code gossip.smalltalk} line (or
 * in {@code gossip.smalltalk-format}) becomes a value.
 *
 * <p>Three properties the rest of the plugin relies on:
 * <ul>
 *   <li><b>Registry, not a wall of {@code replace()} calls.</b> Adding tag #31 is one line in
 *       {@link #defaults()} plus a doc row — no call site changes.</li>
 *   <li><b>Fail-open.</b> An unknown tag is left exactly as written and logged <b>once</b> per key per reload
 *       ({@link #clearWarnings()} is called from {@code CharacterManager#readConfig}), so a typo is visible in
 *       chat and in the log instead of silently eating a line. Resolvers never throw and never return null.</li>
 *   <li><b>Injection-safe.</b> Substituted values are scrubbed of nested MiniMessage tags and PAPI
 *       {@code %…%} tokens, then length-capped — in that order, so a cap can never expose half a tag. The
 *       structural {@code <line>} tag is exempt: it is authored markup spoken verbatim.</li>
 * </ul>
 *
 * <p>Tag matching is exact-tag (a single regex pass over {@code <name>}), which is why {@code <speaker_job>}
 * can never be broken up by an earlier {@code <speaker>} hit. Colours and formatting tags ({@code <gold>},
 * {@code <bold>}, …) are treated as pass-through and are never logged as unknown.
 *
 * <p>Pure Java: no Bukkit, no plugin, no I/O — fully unit-testable against a hand-built {@link GossipCtx}.
 */
public final class GossipPlaceholders {

    /** Resolves one tag against a beat's {@link GossipCtx}. Must never return null and never throw. */
    @FunctionalInterface
    public interface Resolver {
        String resolve(GossipCtx ctx);
    }

    /** Built-in defaults for the optional {@code gossip.placeholders} / {@code gossip.lore} config blocks. */
    public static final int DEFAULT_CROWD_BUSY = 3;
    public static final int DEFAULT_CROWD_PACKED = 6;
    public static final List<String> DEFAULT_MINE_NEWS = List.of(
            "the deep mines flooded again",
            "adventurers cleaned out the deep shafts");
    public static final List<String> DEFAULT_TRAVELER_TALES = List.of(
            "a sailor swears the ocean has a floor",
            "a pilgrim saw blue forests in the Nether");

    /** The plugin's logger (Bukkit names it after the plugin, {@code LivingNPC}), so warnings land in logs. */
    private static final Logger LOGGER = Logger.getLogger("LivingNPC");

    /** {@code <name>} — one pass, exact tag, no substring collisions ({@code <speaker_job>} beats {@code <speaker>}). */
    private static final Pattern TAG = Pattern.compile("<([A-Za-z0-9_]+)>");
    private static final Pattern MINI_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern PAPI = Pattern.compile("%[A-Za-z0-9_]+%");

    /** Tags that are never placeholders (colours, decorations, structural) → left alone, never warned about. */
    private static final Set<String> PASSTHROUGH = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "italic", "underlined", "strikethrough", "obfuscated", "reset",
            "newline", "br", "rainbow", "shadow", "small", "upper", "lower");

    /** Keys whose value is authored markup: never scrubbed, never capped (the line actually being said). */
    private static final Set<String> RAW_KEYS = Set.of("line");

    /** Per-key character caps, applied after scrubbing (see the class doc). */
    private static final Map<String, Integer> LIMITS = Map.of(
            "last_line", 60, "speaker", 24, "listener", 24, "partner", 24, "npc", 24);

    private static final Map<String, Resolver> DEFAULTS = defaults();

    /** Warn-once bookkeeping for unknown tags; cleared on every {@code /lsk reload}. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private GossipPlaceholders() {}

    /** Every registered tag, in catalogue order (A who · B where/when · C conversation · D world story). */
    public static Set<String> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(DEFAULTS.keySet()));
    }

    /**
     * The registry. 30 gossip tags (the catalogue's {@code <partner>}/{@code <npc>} are the legacy aliases of
     * {@code <listener>}/{@code <speaker>}, kept so an old {@code smalltalk-format} keeps working) plus the
     * structural {@code <line>}. Order here is the order of the {@code /lsk gossip placeholders} table.
     */
    private static Map<String, Resolver> defaults() {
        Map<String, Resolver> m = new LinkedHashMap<>();
        // A — who
        m.put("speaker", c -> c.speaker().name());
        m.put("npc", c -> c.speaker().name());                    // legacy alias: smalltalk-format used <npc>
        m.put("listener", c -> c.listener().name());
        m.put("partner", c -> c.listener().name());               // legacy alias: smalltalk used <partner>
        m.put("speaker_job", c -> c.speaker().job());
        m.put("listener_job", c -> c.listener().job());
        m.put("speaker_trait", c -> c.speaker().trait());
        m.put("listener_trait", c -> c.listener().trait());
        // B — where / when
        m.put("time", c -> c.scene().time());
        m.put("daypart", c -> c.scene().daypart());
        m.put("day", c -> Long.toString(c.scene().day()));
        m.put("weather", c -> c.scene().weather());
        m.put("space", c -> c.scene().space());
        m.put("crowd", c -> c.scene().crowd());
        m.put("players_near", c -> Integer.toString(c.scene().playersNear()));
        m.put("stall", c -> c.scene().stall());
        // C — conversation (Phase-3 stubs today; the resolvers are already reading them)
        m.put("topic", c -> c.talk().topic());
        m.put("last_line", c -> c.talk().lastLine());
        m.put("beat", c -> ordinal(c.talk().beat()));
        m.put("relationship", c -> c.talk().relationship());
        m.put("greeting_word", c -> c.scene().greeting());
        // D — world story
        m.put("sales_today", c -> Integer.toString(c.news().salesToday()));
        m.put("last_sold", c -> c.news().lastSold());
        m.put("last_buyer", c -> c.news().lastBuyer());
        m.put("stock_low", c -> c.news().stockLow());
        m.put("mine_news", c -> pick(c.news().mineNews(), DEFAULT_MINE_NEWS));
        m.put("traveler_tale", c -> pick(c.news().travelerTale(), DEFAULT_TRAVELER_TALES));
        m.put("duel_winner", c -> c.news().duelWinner());
        m.put("mood_speaker", c -> c.speaker().mood());
        m.put("mood_listener", c -> c.listener().mood());
        // structural: the line being said right now (authored markup — never scrubbed, see RAW_KEYS)
        m.put("line", c -> c.talk().line());
        return Collections.unmodifiableMap(m);
    }

    /** {@code 1st/2nd/3rd/nth exchange} — how far into the conversation this beat is (always 1 in Phase 1). */
    private static String ordinal(int beat) {
        int n = Math.max(1, beat);
        String suffix = switch (n % 100) {
            case 11, 12, 13 -> "th";
            default -> switch (n % 10) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            };
        };
        return n + suffix + " exchange";
    }

    /** A random non-blank entry of {@code pool}, else of {@code fallbackPool}, else {@code ""}. */
    private static String pick(List<String> pool, List<String> fallbackPool) {
        String v = pickOne(pool);
        if (v == null) v = pickOne(fallbackPool);
        return v == null ? "" : v;
    }

    private static String pickOne(List<String> pool) {
        if (pool == null || pool.isEmpty()) return null;
        for (int attempt = 0; attempt < 8; attempt++) {
            String v = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    // --- substitution ------------------------------------------------------------------------------

    /** Substitute every registered tag in {@code raw} against {@code ctx} (warns through the shared warn-set). */
    public static String fill(String raw, GossipCtx ctx) {
        return fill(raw, ctx, null);
    }

    /**
     * Substitute every registered tag in {@code raw} against {@code ctx}. Text with no {@code '<'}, a null
     * {@code ctx} (placeholders switched off) or a null/empty line is returned untouched. Unknown tags are left
     * literally in place and reported through {@code warnOnce} (null → the shared per-reload warn-set).
     */
    public static String fill(String raw, GossipCtx ctx, Set<String> warnOnce) {
        if (raw == null || raw.isEmpty()) return "";
        if (ctx == null || raw.indexOf('<') < 0) return raw;
        Set<String> warned = warnOnce == null ? WARNED : warnOnce;
        Matcher m = TAG.matcher(raw);
        StringBuilder out = new StringBuilder(raw.length() + 32);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            Resolver r = DEFAULTS.get(key);
            String value;
            if (r != null) {
                value = insert(key, r, ctx, warned);
            } else {
                if (!PASSTHROUGH.contains(key)) warn(warned, key, raw);
                value = m.group();                       // fail-open: leave the author's text exactly as written
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Resolve + sanitise one tag: scrub → cap → scrub again (a cap must never expose half of a tag). */
    private static String insert(String key, Resolver r, GossipCtx ctx, Set<String> warned) {
        String value;
        try {
            value = r.resolve(ctx);
        } catch (Exception e) {
            warn(warned, key, "resolver failed: " + e);
            return "";
        }
        if (value == null) return "";
        if (RAW_KEYS.contains(key)) return value;        // authored markup (the spoken line) stays verbatim
        String clean = scrub(value);
        Integer max = LIMITS.get(key);
        if (max != null) clean = scrub(truncate(clean, max));
        return clean;
    }

    /** Reset the warn-once bookkeeping — called on every config reload so a fixed typo stops nagging. */
    public static void clearWarnings() {
        WARNED.clear();
    }

    private static void warn(Set<String> warned, String key, String where) {
        if (!warned.add(key)) return;                    // once per key per reload
        LOGGER.warning("Gossip placeholder <" + key + "> is unknown — left as written in: \""
                + truncate(scrub(where), 80) + "\". Known tags are listed in GUIDE.md (gossip placeholders) "
                + "and in GossipPlaceholders.defaults().");
    }

    /** Defensive: a value (name, job, lore line, …) must never smuggle MiniMessage tags or PAPI into a line. */
    public static String scrub(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = MINI_TAG.matcher(s).replaceAll("");
        out = PAPI.matcher(out).replaceAll("");
        int newline = out.indexOf('\n');
        if (newline >= 0) out = out.replace('\n', ' ').replace('\r', ' ');
        return out;
    }

    /** Cap {@code s} at {@code max} characters, marking the cut with an ellipsis. */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (max <= 0 || s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)).trim() + "…";
    }

    /** How many players within earshot read as {@code quiet} / {@code busy} / {@code packed}. */
    public static String crowdOf(int playersNear, int busy, int packed) {
        int b = Math.max(1, busy);
        int p = Math.max(b, packed);
        if (playersNear >= p) return "packed";
        if (playersNear >= b) return "busy";
        return "quiet";
    }

    /** The {@code /lsk gossip placeholders} table: one {@code <tag> = value} row per registered tag. */
    public static List<String> table(GossipCtx ctx) {
        List<String> rows = new ArrayList<>(DEFAULTS.size());
        for (Map.Entry<String, Resolver> en : DEFAULTS.entrySet()) {
            String value = insert(en.getKey(), en.getValue(), ctx, WARNED);
            if (RAW_KEYS.contains(en.getKey()) && value.isEmpty()) value = "(the line being said)";
            rows.add(String.format(Locale.ROOT, "%-18s = %s", "<" + en.getKey() + ">", value));
        }
        return rows;
    }
}
