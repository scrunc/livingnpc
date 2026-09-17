package dev.servereer.livingshopkeeper.dialog;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A parsed {@code when:} block on a dialog node or line — the gate that decides whether a line is
 * eligible right now. Everything is optional; an empty block always passes. P3 supports the context we
 * have without shop/memory: time of day, weather, held item, a random chance, and a permission.
 * (Memory-based gates — first-meeting, reputation, purchases — arrive with P5.)
 */
public final class Conditions {

    public static final Conditions ALWAYS = new Conditions(null, null, null, 1.0, null, null);

    /**
     * Hooks the plugin installs at enable so {@code when: { voicechat: … }} can query Simple Voice Chat
     * without the dialog layer importing the voice plugin. Default to false (SVC treated as absent).
     */
    public static Predicate<Player> VOICE_INSTALLED = p -> false;
    public static Predicate<Player> VOICE_CONNECTED = p -> false;

    private final String time;        // day | dusk | night | dawn  (null = any)
    private final String weather;     // clear | rain | thunder     (null = any)
    private final Material holds;     // required main-hand material (null = any)
    private final double chance;      // 0..1 gate rolled each evaluation
    private final String permission;  // required permission node (null = none)
    private final String voicechat;   // connected | installed | none  (null = any)

    private Conditions(String time, String weather, Material holds, double chance, String permission, String voicechat) {
        this.time = time;
        this.weather = weather;
        this.holds = holds;
        this.chance = chance;
        this.permission = permission;
        this.voicechat = voicechat;
    }

    /** Parse a {@code when:} map (or null). Unknown/blank values are ignored, so bad config just widens the gate. */
    public static Conditions parse(Object raw) {
        if (!(raw instanceof Map<?, ?> m) || m.isEmpty()) return ALWAYS;
        String time = str(m.get("time"));
        String weather = str(m.get("weather"));
        Material holds = null;
        String holdsStr = str(m.get("holds"));
        if (holdsStr != null) holds = Material.matchMaterial(holdsStr);
        double chance = 1.0;
        Object c = m.get("chance");
        if (c instanceof Number n) chance = Math.max(0, Math.min(1, n.doubleValue()));
        String perm = str(m.get("permission"));
        String voicechat = normalizeVoice(str(m.get("voicechat")));
        return new Conditions(time, weather, holds, chance, perm, voicechat);
    }

    /** True if a player in the given moment satisfies every set condition. */
    public boolean test(Player p) {
        if (permission != null && !p.hasPermission(permission)) return false;
        if (chance < 1.0 && Math.random() >= chance) return false;
        if (holds != null && p.getInventory().getItemInMainHand().getType() != holds) return false;
        if (voicechat != null && !voiceMatches(p)) return false;
        World w = p.getWorld();
        if (weather != null && !weather.equals(weatherOf(w))) return false;
        if (time != null && !time.equals(timeBucket(w.getTime()))) return false;
        return true;
    }

    private boolean voiceMatches(Player p) {
        return switch (voicechat) {
            case "connected" -> VOICE_CONNECTED.test(p);
            case "installed" -> VOICE_INSTALLED.test(p);
            case "none" -> !VOICE_INSTALLED.test(p);
            default -> true;
        };
    }

    /** connected | installed | none — accepts a few friendly synonyms; unknown → null (gate ignored). */
    private static String normalizeVoice(String v) {
        if (v == null) return null;
        return switch (v) {
            case "connected" -> "connected";
            case "installed", "true", "yes", "on", "has" -> "installed";
            case "none", "false", "no", "off" -> "none";
            default -> null;
        };
    }

    /** day | dusk | night | dawn — coarse buckets over the 0..24000 world clock. */
    public static String timeBucket(long t) {
        t = ((t % 24000) + 24000) % 24000;
        if (t < 12000) return "day";
        if (t < 14000) return "dusk";
        if (t < 22000) return "night";
        return "dawn";
    }

    private static String weatherOf(World w) {
        if (w.isThundering()) return "thunder";
        return w.hasStorm() ? "rain" : "clear";
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s.toLowerCase(Locale.ROOT);
    }
}
