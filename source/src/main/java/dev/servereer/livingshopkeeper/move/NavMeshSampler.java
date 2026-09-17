package dev.servereer.livingshopkeeper.move;

import org.bukkit.Material;
import org.bukkit.World;

/**
 * Block-level walkability tests for the A* navigator. A "node" is a feet block position {@code (x,y,z)}
 * that is <b>standable</b>: feet and head blocks passable, floor below solid. Cheap, approximate — good
 * enough for a barker patrolling flat-ish shop areas (owners place sensible waypoints).
 */
public final class NavMeshSampler {

    private NavMeshSampler() {}

    public static boolean passable(World w, int x, int y, int z) {
        Material m = w.getBlockAt(x, y, z).getType();
        if (!m.isSolid()) return true;   // air/plants/water/torches passable; full blocks + fences solid
        // Doors and gates count as solid but a shop NPC walks through them (packet NPC, no collision) —
        // treating them as walls froze every barker whose waypoints/places sit behind a doorway.
        return org.bukkit.Tag.DOORS.isTagged(m) || org.bukkit.Tag.FENCE_GATES.isTagged(m);
    }

    public static boolean solid(World w, int x, int y, int z) {
        return w.getBlockAt(x, y, z).getType().isSolid();
    }

    /** Standable if the NPC (2 high) fits at (x,y) with a solid floor at y-1. */
    public static boolean standable(World w, int x, int y, int z) {
        return passable(w, x, y, z) && passable(w, x, y + 1, z) && solid(w, x, y - 1, z);
    }

    /** The standable Y for column (x,z) nearest {@code targetY} (search out ±{@code range}), or MIN_VALUE. */
    public static int groundY(World w, int x, int targetY, int z, int range) {
        if (standable(w, x, targetY, z)) return targetY;
        for (int dy = 1; dy <= range; dy++) {
            if (standable(w, x, targetY - dy, z)) return targetY - dy;
            if (standable(w, x, targetY + dy, z)) return targetY + dy;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * The precise standing height for node {@code (x,y,z)}: bottom slabs and stairs under the feet put
     * the surface half a block down. Without this an NPC walks (and sits) floating half a block above
     * every slab — the grid sees the slab's block as solid and bumps the node to the block above.
     */
    public static double surfaceY(World w, int x, int y, int z) {
        org.bukkit.block.data.BlockData floor = w.getBlockAt(x, y - 1, z).getBlockData();
        if (floor instanceof org.bukkit.block.data.type.Slab s && s.getType() == org.bukkit.block.data.type.Slab.Type.BOTTOM) {
            return y - 0.5;
        }
        if (floor instanceof org.bukkit.block.data.type.Stairs st && st.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM) {
            return y - 0.5;   // approximate: the low step; the tall half reads fine either way
        }
        return y;
    }

    /**
     * Ground height to FOLLOW while walking straight at a target (the A*-failed fallback): the floor one
     * step up (a kerb/stair), the floor at the current level, or the nearest floor below within
     * {@code maxDrop}. Crucially it NEVER returns a surface more than one block above the feet — so a
     * packet NPC blocked by a wall, fence or bamboo walks THROUGH it at its own height instead of easing
     * up onto it and "fly-walking" across the top (the old ±range sampler scaled walls a block at a time).
     */
    public static double followY(World w, double x, double feetY, double z, int maxDrop) {
        int fx = (int) Math.floor(x), fz = (int) Math.floor(z);
        int base = (int) Math.floor(feetY + 0.01);
        if (standable(w, fx, base + 1, fz)) return surfaceY(w, fx, base + 1, fz);   // one step up
        if (standable(w, fx, base, fz)) return surfaceY(w, fx, base, fz);           // level ground
        for (int dy = 1; dy <= maxDrop; dy++) {
            if (standable(w, fx, base - dy, fz)) return surfaceY(w, fx, base - dy, fz);   // step/fall down
        }
        return feetY;   // blocked at head height (wall/bamboo) — hold height and walk through
    }

    /**
     * The nearest standable surface at or above {@code y} within {@code up} blocks — used to climb an NPC
     * back out of terrain it has phased into, so it never keeps walking buried below the ground. MIN_VALUE
     * if there's no standable spot in that range.
     */
    public static int surfaceAtOrAbove(World w, int x, int y, int z, int up) {
        for (int dy = 0; dy <= up; dy++) {
            if (standable(w, x, y + dy, z)) return y + dy;
        }
        return Integer.MIN_VALUE;
    }

    /** The standable Y adjacent to a step: same level, one up, or up to 3 down (a fall); MIN_VALUE if none. */
    public static int neighborY(World w, int x, int fromY, int z) {
        if (standable(w, x, fromY, z)) return fromY;
        if (standable(w, x, fromY + 1, z)) return fromY + 1;
        for (int dy = 1; dy <= 3; dy++) {
            if (standable(w, x, fromY - dy, z)) return fromY - dy;
        }
        return Integer.MIN_VALUE;
    }
}
