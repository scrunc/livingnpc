package dev.servereer.livingshopkeeper.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A shared hangout area ANY character can visit on an outing: a named spot in a world with
 * wand-marked slab/stair seats ({@code {x,y,z,yaw}} each). Unlike the per-character lobby seats,
 * spaces are world-level and live in {@code spaces.yml} — one plaza serves every NPC that drops by.
 * Runtime seat claims (who sits where) belong to the manager, not here.
 */
public final class Space {

    private final String id;
    private String world;
    private double x, y, z;       // centre: where seatless visitors stand around
    private float yaw;
    private boolean arena;        // spar-enabled visitors here actively look for a duel
    private final List<double[]> seats = new ArrayList<>();

    public Space(String id, String world, double x, double y, double z, float yaw) {
        this.id = cleanId(id);
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw;
    }

    public String id() { return id; }
    public String world() { return world; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float yaw() { return yaw; }

    public void setCenter(String world, double x, double y, double z, float yaw) {
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw;
    }

    public boolean arena() { return arena; }
    public void setArena(boolean arena) { this.arena = arena; }

    public List<double[]> seats() { return seats; }

    public void addSeat(double sx, double sy, double sz, float syaw) {
        seats.add(new double[]{sx, sy, sz, syaw});
    }

    public int seatIndexNear(double sx, double sy, double sz, double radius) {
        for (int i = 0; i < seats.size(); i++) {
            double[] s = seats.get(i);
            double dx = s[0] - sx, dy = s[1] - sy, dz = s[2] - sz;
            if (dx * dx + dy * dy + dz * dz <= radius * radius) return i;
        }
        return -1;
    }

    public boolean removeSeatNear(double sx, double sy, double sz, double radius) {
        int i = seatIndexNear(sx, sy, sz, radius);
        if (i < 0) return false;
        seats.remove(i);
        return true;
    }

    static String cleanId(String raw) {
        String out = raw == null ? "space" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return out.isBlank() ? "space" : out;
    }
}
