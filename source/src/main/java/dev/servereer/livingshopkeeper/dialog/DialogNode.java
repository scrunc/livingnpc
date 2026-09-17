package dev.servereer.livingshopkeeper.dialog;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A dialog node. Two flavours:
 * <ul>
 *   <li><b>Line node</b> — has {@code lines:}; a trigger fires it and the engine weight-picks one line.</li>
 *   <li><b>Menu node</b> — has {@code options:}; right-clicking opens it as a GUI of selectable replies,
 *       each of which can speak a line and {@code goto} another menu node (a conversation tree).</li>
 * </ul>
 * A node may have both. {@code cooldown} is per-player (stops approach-spam on line nodes).
 */
public final class DialogNode {

    public final String id;
    public final Set<String> triggers;
    public final Conditions when;
    public final int weight;
    public final long cooldownMs;
    public final List<DialogLine> lines;
    public final String title;              // menu title (null → use the character's name)
    public final List<Option> options;      // menu buttons (empty → not a menu)

    public DialogNode(String id, Set<String> triggers, Conditions when, int weight, long cooldownMs,
                      List<DialogLine> lines, String title, List<Option> options) {
        this.id = id;
        this.triggers = triggers;
        this.when = when == null ? Conditions.ALWAYS : when;
        this.weight = Math.max(1, weight);
        this.cooldownMs = Math.max(0, cooldownMs);
        this.lines = lines;
        this.title = title;
        this.options = options;
    }

    public boolean isMenu() {
        return !options.isEmpty();
    }

    /** One selectable reply in a menu node. */
    public static final class Option {
        public final String label;    // GUI button text
        public final Material icon;    // GUI item (null → a default)
        public final String voice;     // clip spoken when selected (optional)
        public final String line;      // subtitle shown when selected (optional)
        public final String next;      // menu node to open after (optional; null → close)

        Option(String label, Material icon, String voice, String line, String next) {
            this.label = label;
            this.icon = icon;
            this.voice = voice;
            this.line = line;
            this.next = next;
        }
    }

    static DialogNode parse(String id, ConfigurationSection s) {
        Set<String> triggers = new LinkedHashSet<>();
        for (String t : s.getStringList("triggers")) triggers.add(t.toLowerCase(Locale.ROOT));
        String single = s.getString("trigger");
        if (single != null) triggers.add(single.toLowerCase(Locale.ROOT));
        if (triggers.isEmpty()) triggers.add("interact");

        Conditions when = Conditions.parse(sectionOrMap(s, "when"));
        int weight = s.getInt("weight", 1);
        long cooldownMs = parseDuration(s.get("cooldown"), 5_000L);

        List<DialogLine> lines = new ArrayList<>();
        for (Object raw : s.getList("lines", List.of())) {
            DialogLine dl = DialogLine.parse(raw);
            if (dl != null) lines.add(dl);
        }

        String title = s.getString("title");
        List<Option> options = new ArrayList<>();
        for (Object raw : s.getList("options", List.of())) {
            Option o = parseOption(raw);
            if (o != null) options.add(o);
        }

        if (lines.isEmpty() && options.isEmpty()) return null;
        return new DialogNode(id, triggers, when, weight, cooldownMs, lines, title, options);
    }

    private static Option parseOption(Object raw) {
        Map<?, ?> m = raw instanceof Map<?, ?> mm ? mm
                : (raw instanceof ConfigurationSection cs ? cs.getValues(false) : null);
        if (m == null) return null;
        String label = str(m.get("text"));
        if (label == null) return null;
        Material icon = null;
        String ic = str(m.get("icon"));
        if (ic != null) icon = Material.matchMaterial(ic.toUpperCase(Locale.ROOT));
        return new Option(label, icon, str(m.get("say")), str(m.get("line")), str(m.get("goto")));
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static Object sectionOrMap(ConfigurationSection s, String key) {
        ConfigurationSection cs = s.getConfigurationSection(key);
        return cs != null ? cs.getValues(false) : s.get(key);
    }

    /** Parse "8s"/"2m"/"1h"/"500ms" or a bare number (seconds) into millis; {@code def} on null/garbage. */
    static long parseDuration(Object raw, long def) {
        if (raw == null) return def;
        if (raw instanceof Number n) return (long) (n.doubleValue() * 1000);
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        try {
            if (s.endsWith("ms")) return Long.parseLong(s.substring(0, s.length() - 2).trim());
            char u = s.charAt(s.length() - 1);
            double v = Double.parseDouble(s.substring(0, s.length() - 1).trim());
            return switch (u) {
                case 's' -> (long) (v * 1000);
                case 'm' -> (long) (v * 60_000);
                case 'h' -> (long) (v * 3_600_000);
                default -> (long) (Double.parseDouble(s) * 1000);
            };
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
