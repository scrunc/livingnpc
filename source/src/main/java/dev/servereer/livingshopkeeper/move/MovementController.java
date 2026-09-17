package dev.servereer.livingshopkeeper.move;

import dev.servereer.livingshopkeeper.npc.Character;
import org.bukkit.World;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives one character's movement, called once per move-tick. Two modes:
 * <ul>
 *   <li><b>Approach</b> — when {@link #approach} is set, walk toward that point (a player, a place), re-pathing
 *       as it moves. No dwell.</li>
 *   <li><b>Patrol</b> — otherwise, walk the character's waypoints in a loop, dwelling at each.</li>
 * </ul>
 * When A* can't find a path (door in the way, target past the node/range caps, weird terrain) the controller
 * falls back to a <b>direct ground-following walk</b> straight at the target instead of freezing in place —
 * a packet NPC has no collision, so making progress always beats standing still.
 * Returns {@code {dx,dy,dz,yaw,onGround}} to send as a relative-move, or null if it didn't move.
 */
public final class MovementController {

    private final PathNavigator nav = new PathNavigator();
    private final double speedPerTick;
    private final double jumpChancePerTick, jumpHeight, turnDegreesPerTick;
    private final int maxNodes, maxRange;

    private List<int[]> path;
    private int idx;
    private int wp = -1;
    private long pauseUntilMs;
    private double speedMultiplier = 1.0;   // >1 while fleeing / fighting

    private double[] directTarget;        // A* failed → walk straight at this {x,y,z}, following the ground
    private double[] approachTarget;      // {x,y,z} of a point to walk to, or null
    private int lastApproachTx, lastApproachTz;
    private int jumpTick;
    private static final int JUMP_TICKS = 9;
    // Max upward ground-follow per tick on the direct walk: a 1-block step takes ~3-4 ticks (smooth),
    // and it can never scale a multi-block wall — it holds height and walks through instead.
    private static final double STEP_UP_PER_TICK = 0.3;
    private double jumpOffset;
    private long nextJumpAfterMs;

    public MovementController(double speedPerTick, int maxNodes, int maxRange,
                              double jumpChancePerSecond, double jumpHeight, double turnDegreesPerTick) {
        this.speedPerTick = Math.max(0.02, speedPerTick);
        this.maxNodes = maxNodes;
        this.maxRange = maxRange;
        this.jumpChancePerTick = Math.max(0, jumpChancePerSecond) / 20.0;
        this.jumpHeight = Math.max(0.1, jumpHeight);
        this.turnDegreesPerTick = Math.max(1, turnDegreesPerTick);
    }

    /** Halt movement for {@code ms} (e.g. while addressing a passer-by). */
    public void pauseFor(long ms) {
        long until = System.currentTimeMillis() + ms;
        if (until > pauseUntilMs) pauseUntilMs = until;
    }

    /** Sprint factor: 1 = authored walk speed. Applied to every step (flee/fight-back run faster). */
    public void setSpeedMultiplier(double m) {
        this.speedMultiplier = Math.max(0.2, Math.min(3.0, m));
    }

    /** True while a route (path or direct walk) is active and not dwelling — i.e. legs are moving. */
    public boolean isMoving() {
        return (path != null || directTarget != null) && System.currentTimeMillis() >= pauseUntilMs;
    }

    /** Walk toward this world point (a player) instead of patrolling. */
    public void approach(double x, double y, double z) {
        if (approachTarget == null) { path = null; directTarget = null; }   // entering approach → re-path
        approachTarget = new double[]{x, y, z};
    }

    /** Stop approaching and resume the waypoint patrol. */
    public void clearApproach() {
        if (approachTarget != null) {
            approachTarget = null;
            path = null;
            directTarget = null;
        }
    }

    public double[] tick(Character c, World w, long dwellMs) {
        long now = System.currentTimeMillis();
        if (now < pauseUntilMs) return null;

        if (approachTarget != null) {
            int tx = (int) Math.floor(approachTarget[0]), tz = (int) Math.floor(approachTarget[2]);
            boolean exhausted = (path == null && directTarget == null) || (path != null && idx >= path.size());
            if (exhausted || tx != lastApproachTx || tz != lastApproachTz) {
                pathTo(c, w, approachTarget);
                lastApproachTx = tx;
                lastApproachTz = tz;
            }
            return step(c, w, 0, false);   // no dwell or playful hops while chasing a player
        }

        List<double[]> wps = c.waypoints();
        if (wps.isEmpty()) return null;
        if (path == null && directTarget == null) {
            wp = (wp + 1) % wps.size();
            pathTo(c, w, wps.get(wp));
        } else if (path != null && idx >= path.size()) {
            path = null;
            return null;
        }
        return step(c, w, dwellMs, true);
    }

    private double[] step(Character c, World w, long dwellMs, boolean allowPlayfulJump) {
        if (directTarget != null) return directAdvance(c, w, dwellMs);
        if (path == null || idx >= path.size()) return null;
        return advance(c, w, dwellMs, allowPlayfulJump);
    }

    private double effectiveSpeed() {
        return speedPerTick * speedMultiplier;
    }

    /** Walk one step toward {@code path.get(idx)}; dwell {@code dwellMs} on reaching the path's end. */
    private double[] advance(Character c, World w, long dwellMs, boolean allowPlayfulJump) {
        double speed = effectiveSpeed();
        int[] node = path.get(idx);
        double tx = node[0] + 0.5, tz = node[2] + 0.5;
        double ty = NavMeshSampler.surfaceY(w, node[0], node[1], node[2]);   // slab/stair tops are at −0.5
        double ox = c.getX(), visualOy = c.getY(), oy = visualOy - jumpOffset, oz = c.getZ();
        double dx = tx - ox, dz = tz - oz, dy = ty - oy;
        double distXZ = Math.hypot(dx, dz);

        double nX, nY, nZ;
        if (distXZ <= speed) {
            nX = tx; nY = ty; nZ = tz;
            idx++;
            if (idx >= path.size()) {
                if (dwellMs > 0) pauseUntilMs = System.currentTimeMillis() + dwellMs;
                path = null;
            }
        } else {
            nX = ox + dx / distXZ * speed;
            nZ = oz + dz / distXZ * speed;
            nY = oy + clamp(dy, -speed, speed);
        }

        long now = System.currentTimeMillis();
        if (allowPlayfulJump && jumpTick == 0 && path != null && idx < path.size() - 1 && now >= nextJumpAfterMs
                && ThreadLocalRandom.current().nextDouble() < jumpChancePerTick) {
            jumpTick = 1;
            nextJumpAfterMs = now + 20_000L; // never bunny-hop repeatedly
        }
        double nextJumpOffset = 0;
        if (jumpTick > 0) {
            nextJumpOffset = Math.sin(Math.PI * jumpTick / (JUMP_TICKS + 1.0)) * jumpHeight;
            if (++jumpTick > JUMP_TICKS) jumpTick = 0;
        }
        nY += nextJumpOffset;

        return emit(c, nX, nY, nZ, nextJumpOffset);
    }

    /**
     * A* couldn't help — walk straight toward {@link #directTarget}, snapping each step to the sampled
     * ground height so the NPC climbs steps and slopes instead of tunnelling or floating.
     */
    private double[] directAdvance(Character c, World w, long dwellMs) {
        double speed = effectiveSpeed();
        double tx = directTarget[0], tz = directTarget[2];
        double ox = c.getX(), visualOy = c.getY(), oy = visualOy - jumpOffset, oz = c.getZ();
        double dx = tx - ox, dz = tz - oz;
        double distXZ = Math.hypot(dx, dz);

        double nX, nY, nZ;
        if (distXZ <= Math.max(speed, 0.35)) {
            nX = tx; nZ = tz;
            // follow the floor at the destination (≤1 step up), never pop onto a wall/obstacle top
            nY = NavMeshSampler.followY(w, tx, oy, tz, 4);
            directTarget = null;
            if (dwellMs > 0) pauseUntilMs = System.currentTimeMillis() + dwellMs;
        } else {
            nX = ox + dx / distXZ * speed;
            nZ = oz + dz / distXZ * speed;
            // step DOWN freely, step UP at most ~one block gradually; a wall/fence/bamboo in the way is
            // walked THROUGH at the current height rather than scaled (the old sampler fly-walked up them)
            double gy = NavMeshSampler.followY(w, nX, oy, nZ, 4);
            // Anti-sink: if the step lands the feet INSIDE solid terrain (it phased into a hill/wall), rise
            // to the nearest standable surface above rather than tunnelling along below the ground.
            int fx = (int) Math.floor(nX), fz = (int) Math.floor(nZ), fy = (int) Math.floor(oy + 0.01);
            if (NavMeshSampler.solid(w, fx, fy, fz)) {
                int s = NavMeshSampler.surfaceAtOrAbove(w, fx, fy, fz, 4);
                if (s != Integer.MIN_VALUE) gy = NavMeshSampler.surfaceY(w, fx, s, fz);
            }
            nY = oy + clamp(gy - oy, -0.6, STEP_UP_PER_TICK);
        }
        return emit(c, nX, nY, nZ, 0);
    }

    /** Commit the step: update position/yaw and build the relative-move payload (null if we barely moved). */
    private double[] emit(Character c, double nX, double nY, double nZ, double nextJumpOffset) {
        double ox = c.getX(), visualOy = c.getY(), oz = c.getZ();
        double mx = nX - ox, my = nY - visualOy, mz = nZ - oz;
        if (Math.abs(mx) < 1e-4 && Math.abs(my) < 1e-4 && Math.abs(mz) < 1e-4) return null;
        c.setPosition(nX, nY, nZ);
        jumpOffset = nextJumpOffset;
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-mx, mz));
        float yaw = turnToward(c.movementYaw(), desiredYaw, turnDegreesPerTick);
        c.setMovementYaw(yaw);
        return new double[]{mx, my, mz, yaw, jumpTick > 0 || Math.abs(my) > 0.08 ? 0 : 1};
    }

    /**
     * Plan a route to {@code target}: A* when it can, else the direct ground-following walk. Never leaves
     * the controller stuck — the old behavior (pause 1.5s and hope) is what froze barkers at shop doors.
     */
    private void pathTo(Character c, World w, double[] target) {
        path = null;
        directTarget = null;
        int tx = (int) Math.floor(target[0]), tz = (int) Math.floor(target[2]);
        int ty = NavMeshSampler.groundY(w, tx, (int) Math.floor(target[1]), tz, 4);
        int sx = (int) Math.floor(c.getX()), sz = (int) Math.floor(c.getZ());
        int sy = NavMeshSampler.groundY(w, sx, (int) Math.floor(c.getY() - jumpOffset), sz, 4);
        if (ty != Integer.MIN_VALUE && sy != Integer.MIN_VALUE) {
            List<int[]> p = nav.findPath(w, sx, sy, sz, tx, ty, tz, maxNodes, maxRange);
            if (p != null && p.size() >= 2) {
                path = p;
                idx = 1;   // node 0 is where we already stand
                return;
            }
        }
        directTarget = new double[]{target[0], ty == Integer.MIN_VALUE ? target[1] : ty, target[2]};
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static float turnToward(float current, float target, double max) {
        float delta = ((target - current + 540f) % 360f) - 180f;
        if (Math.abs(delta) <= max) return target;
        return current + (float) Math.copySign(max, delta);
    }
}
