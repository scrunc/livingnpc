package dev.servereer.livingshopkeeper.npc;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Catches the client's entity-click packets (there is no Bukkit interact event for packet entities),
 * maps the target entity id back to a {@link Character}, and hands off to the manager on the main
 * thread. Right-click → dialog/menu; a punch or weapon hit → the hit-reaction system.
 *
 * <p><b>Two protocols.</b> Up to 1.21.x one {@code INTERACT_ENTITY} packet carried an action byte —
 * {@code INTERACT} (right-click), {@code ATTACK} (left-click) and {@code INTERACT_AT}, the duplicate
 * that rides along with every right-click and has to be skipped or everything fires twice.
 * <b>MC 26.1 split that packet</b>: {@code ServerboundInteractPacket} lost its action byte entirely
 * (it is now {@code entityId, hand, location, usingSecondaryAction}, and packetevents reports every
 * one of them as {@code INTERACT_AT}), and a left click became its own packet,
 * {@code ServerboundAttackPacket} → {@link PacketType.Play.Client#ATTACK}. On 26.1+ the old
 * "skip INTERACT_AT" rule therefore swallowed EVERY right-click — clicking an NPC did nothing at
 * all — and no punch was ever seen. Which protocol we are on is detected once, by name, so this
 * still links against a pre-26 packetevents (where the constant simply does not exist).
 */
public final class NpcInteractListener extends PacketListenerAbstract {

    /** Duplicate-click window on the split protocol (a main-hand + off-hand pair arrives back to back). */
    private static final long DUP_WINDOW_MS = 100;

    private final JavaPlugin plugin;
    private final CharacterManager manager;
    /** True on MC 26.1+, where interact carries no action byte and attack is a separate packet. */
    private final boolean splitProtocol;
    private final Map<UUID, long[]> lastClick = new HashMap<>();   // player → {entityId, whenMs}

    public NpcInteractListener(JavaPlugin plugin, CharacterManager manager) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.manager = manager;
        this.splitProtocol = detectSplitProtocol();
        plugin.getLogger().info("  Click protocol: " + (splitProtocol
                ? "26.1+ (interact has no action byte; separate ATTACK packet)"
                : "1.21-style (single INTERACT_ENTITY packet with an action byte)"));
    }

    /**
     * {@code ServerVersion.valueOf} rather than a direct {@code V_26_1} reference: on an older
     * packetevents that constant is absent and this must degrade to the 1.21 path, not fail to link.
     */
    private static boolean detectSplitProtocol() {
        try {
            return PacketEvents.getAPI().getServerManager().getVersion()
                    .isNewerThanOrEquals(ServerVersion.valueOf("V_26_1"));
        } catch (Throwable ignored) {
            return false;   // pre-26 packetevents (or API not ready) — old packet shape
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();

        // 26.1+: left click is its own packet. Guarded by splitProtocol so the ATTACK constant is
        // never resolved on an older packetevents that lacks it.
        if (splitProtocol && type == PacketType.Play.Client.ATTACK) {
            handle(event, new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack(event)
                    .getEntityId(), true, false);
            return;
        }
        if (type != PacketType.Play.Client.INTERACT_ENTITY) return;

        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        WrapperPlayClientInteractEntity.InteractAction action = wrapper.getAction();
        if (!splitProtocol && action == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) {
            return;   // pre-26: rides along with every INTERACT, would double-trigger
        }
        boolean attack = !splitProtocol && action == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
        // owner sneak-right-click → straight to the trade editor
        handle(event, wrapper.getEntityId(), attack, wrapper.isSneaking().orElse(false));
    }

    /** Common tail: entity id → character, then hop to the main thread for anything Bukkit. */
    private void handle(PacketReceiveEvent event, int entityId, boolean attack, boolean sneaking) {
        Character s = manager.byEntityId(entityId);
        if (s == null) return;

        Player player = event.getPlayer();
        if (player == null) return;
        // The away ghost (floating head at the post) is a pure stand-in: punching it is also "use".
        if (attack && !manager.isGhost(entityId)) {
            Bukkit.getScheduler().runTask(plugin, () -> manager.onAttack(player, s));
        } else {
            if (isDuplicate(player.getUniqueId(), entityId)) return;
            Bukkit.getScheduler().runTask(plugin, () -> manager.onInteract(player, s, sneaking));
        }
    }

    /**
     * One right click can put a main-hand and an off-hand packet on the wire back to back; on the
     * split protocol nothing in the packet tells them apart, so collapse repeats of the same
     * player+entity inside a very short window (well under a human double click).
     */
    private synchronized boolean isDuplicate(UUID player, int entityId) {
        long now = System.currentTimeMillis();
        if (lastClick.size() > 64) lastClick.values().removeIf(e -> now - e[1] > 5_000);   // quit players
        long[] prev = lastClick.get(player);
        if (prev != null && prev[0] == entityId && now - prev[1] < DUP_WINDOW_MS) return true;
        lastClick.put(player, new long[]{entityId, now});
        return false;
    }
}
