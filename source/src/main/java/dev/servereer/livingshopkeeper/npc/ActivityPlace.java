package dev.servereer.livingshopkeeper.npc;

import org.bukkit.Location;

import java.util.Locale;

/** A passive dialog area or a world-time destination in a character's daily routine. */
public record ActivityPlace(String id, double x, double y, double z, float yaw, float pitch,
                            double radius, boolean visit, boolean sit, int fromTick, int untilTick) {

    public ActivityPlace {
        id = cleanId(id);
        radius = Math.max(0.5, radius);
        fromTick = normalizeTick(fromTick);
        untilTick = normalizeTick(untilTick);
    }

    /** A visit with equal endpoints is active all day; passive areas are never travel destinations. */
    public boolean activeAt(long worldTime) {
        if (!visit) return false;
        int t = normalizeTick((int) (worldTime % 24_000L));
        if (fromTick == untilTick) return true;
        if (fromTick < untilTick) return t >= fromTick && t < untilTick;
        return t >= fromTick || t < untilTick;
    }

    public boolean contains(Location loc) {
        if (loc == null) return false;
        double dx = loc.getX() - x, dy = loc.getY() - y, dz = loc.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** Parse world ticks, HH:mm, or day/dusk/night/dawn. Minecraft tick 0 is 06:00. */
    public static int parseWorldTime(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("always")) return 0;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "day", "morning" -> 0;
            case "dusk", "evening" -> 12_000;
            case "night" -> 13_000;
            case "dawn" -> 23_000;
            default -> parseNumericOrClock(s);
        };
    }

    public static String formatWorldTime(int tick) {
        int totalMinutes = (normalizeTick(tick) * 60 / 1000 + 6 * 60) % (24 * 60);
        return String.format(Locale.ROOT, "%02d:%02d", totalMinutes / 60, totalMinutes % 60);
    }

    private static int parseNumericOrClock(String s) {
        try {
            if (!s.contains(":")) return normalizeTick(Integer.parseInt(s));
            String[] parts = s.split(":", 2);
            int hour = Integer.parseInt(parts[0]), minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new IllegalArgumentException();
            int minutesSinceSix = (hour * 60 + minute - 6 * 60 + 24 * 60) % (24 * 60);
            return normalizeTick((int) Math.round(minutesSinceSix * (1000.0 / 60.0)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Use HH:mm, a world tick (0-23999), or day/dusk/night/dawn.");
        }
    }

    private static int normalizeTick(int tick) {
        int n = tick % 24_000;
        return n < 0 ? n + 24_000 : n;
    }

    private static String cleanId(String raw) {
        String out = raw == null ? "place" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return out.isBlank() ? "place" : out;
    }
}
