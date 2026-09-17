package dev.servereer.livingshopkeeper.move;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import org.bukkit.entity.Player;

/**
 * Movement packets for a walking packet-NPC (barker). A per-tick <b>relative</b> move + rotation is what
 * makes the client play the walk animation — it derives the walk cycle from the positional delta — while a
 * head-look keeps the head aligned with travel. Relative-move deltas must stay under ~8 blocks (the wire
 * format caps there), which is always true for a per-tick step; use {@link #teleport} for larger jumps
 * (waypoint snap, spawn correction).
 *
 * <p>Verified against packetevents 2.12.x:
 * {@code WrapperPlayServerEntityRelativeMoveAndRotation(int, double dx, double dy, double dz, float yaw,
 * float pitch, boolean onGround)}.
 */
public final class MovePackets {

    /** Largest per-step delta the relative-move packet can carry on any axis. */
    public static final double MAX_STEP = 7.9;

    private MovePackets() {}

    /** One walk step for a viewer: relative move (each |delta| &lt; {@link #MAX_STEP}) + body + head facing. */
    public static void step(Player viewer, int entityId, double dx, double dy, double dz,
                            float yaw, float pitch, boolean onGround) {
        send(viewer, new WrapperPlayServerEntityRelativeMoveAndRotation(entityId, dx, dy, dz, yaw, pitch, onGround));
        send(viewer, new WrapperPlayServerEntityHeadLook(entityId, yaw));
    }

    /** True if a move of this delta fits in a single relative-move packet (else teleport). */
    public static boolean fitsStep(double dx, double dy, double dz) {
        return Math.abs(dx) < MAX_STEP && Math.abs(dy) < MAX_STEP && Math.abs(dz) < MAX_STEP;
    }

    /** Absolute reposition for jumps &gt; ~8 blocks (waypoint snap / respawn). */
    public static void teleport(Player viewer, int entityId, double x, double y, double z,
                                float yaw, float pitch, boolean onGround) {
        send(viewer, new WrapperPlayServerEntityTeleport(entityId, new Location(x, y, z, yaw, pitch), onGround));
        send(viewer, new WrapperPlayServerEntityHeadLook(entityId, yaw));
    }

    private static void send(Player viewer, PacketWrapper<?> wrapper) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, wrapper);
        } catch (Throwable ignored) {
            // viewer disconnecting / channel closed — never let one bad send break the walk loop
        }
    }
}
