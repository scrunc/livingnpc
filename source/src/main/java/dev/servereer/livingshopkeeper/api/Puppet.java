package dev.servereer.livingshopkeeper.api;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.CollisionRule;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.NameTagVisibility;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.OptionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import dev.servereer.livingshopkeeper.move.MovePackets;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A skinned player body driven by ANOTHER plugin — the look of a bot whose real body (hitbox, physics, AI)
 * is a hidden server-side mob that the owner moves. Same packet route as the shop NPCs ({@code PacketNpc}):
 * an unlisted tab profile carrying the skin, then a player entity, a scoreboard team for the name tag.
 *
 * <p>The owner calls {@link #sync} every tick with the body's position; the puppet keeps its own viewer set
 * (players in the same world within {@link #RANGE} blocks), spawns itself for newcomers and despawns for
 * players who left. Left-clicks on the puppet reach {@link #onAttack} on the main thread. Main thread only.
 */
public final class Puppet {

    static final double RANGE = 80;
    private static final int META_SKIN_PARTS = 16;
    private static final byte ALL_SKIN_LAYERS = (byte) 0x7F;
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.BOOTS, EquipmentSlot.LEGGINGS, EquipmentSlot.CHEST_PLATE, EquipmentSlot.HELMET};

    final int entityId;
    private final UUID uuid = UUID.randomUUID();
    private final String profileName;
    private Component prefix;
    private NamedTextColor color;
    private String texture, signature;
    private World world;
    private double x, y, z;
    // where the CLIENTS have the puppet: relative moves are quantised to 1/4096 block, so deltas are taken
    // from this, never from the exact position (otherwise the rounding error piles up and the skin drifts
    // away from the body it follows — "hits from far away, walks through walls")
    private double cx, cy, cz;
    private float yaw, pitch;
    private boolean onGround = true;
    private boolean glowing, sprinting;
    private final ItemStack[] gear = new ItemStack[SLOTS.length];
    private final Set<UUID> viewers = new HashSet<>();
    private Consumer<Player> onAttack;
    private Consumer<Player> onInteract;
    private int syncs;
    private boolean removed;

    Puppet(int entityId, String name, String prefix, NamedTextColor color) {
        this.entityId = entityId;
        String base = name == null ? "" : name.replaceAll("[^A-Za-z0-9_]", "");
        if (base.isEmpty()) base = "Bot";
        this.profileName = base.length() > 16 ? base.substring(0, 16) : base;
        this.prefix = prefix == null || prefix.isEmpty() ? Component.empty()
                : LegacyComponentSerializer.legacyAmpersand().deserialize(prefix);
        this.color = color == null ? NamedTextColor.WHITE : color;
    }

    public int entityId() { return entityId; }
    public String name() { return profileName; }
    public boolean removed() { return removed; }

    /** Called (main thread) when a player left-clicks the puppet. */
    public void onAttack(Consumer<Player> handler) { this.onAttack = handler; }

    /** Called (main thread) when a player RIGHT-clicks the puppet (shopkeepers, menus). */
    public void onInteract(Consumer<Player> handler) { this.onInteract = handler; }

    /** Internal: the click listener hands a right-click over (main thread). */
    public void fireInteract(Player p) {
        if (!removed && onInteract != null) onInteract.accept(p);
    }

    /** Internal: the click listener hands a left-click over (main thread). */
    public void fireAttack(Player p) {
        if (!removed && onAttack != null) onAttack.accept(p);
    }

    /** Apply a skin (texture value + signature). Viewers see it after a quick re-spawn. */
    public void skin(String texture, String signature) {
        this.texture = texture;
        this.signature = signature;
        if (world == null) return;
        for (Player v : viewerPlayers()) { despawn(v); spawn(v); }
    }

    /** Move/turn to the body's current position. Call every tick. */
    public void sync(Location at, float yaw, float pitch, boolean onGround) {
        if (removed || at.getWorld() == null) return;
        boolean worldChanged = world != at.getWorld();
        if (worldChanged) {
            for (Player v : viewerPlayers()) despawn(v);
            viewers.clear();
        }
        world = at.getWorld();
        x = at.getX(); y = at.getY(); z = at.getZ();
        boolean turned = Math.abs(yaw - this.yaw) > 0.5f || Math.abs(pitch - this.pitch) > 0.5f;
        this.yaw = yaw; this.pitch = pitch; this.onGround = onGround;
        syncs++;
        if (worldChanged) { cx = x; cy = y; cz = z; }
        else {
            double qx = q(x - cx), qy = q(y - cy), qz = q(z - cz);
            boolean moved = qx != 0 || qy != 0 || qz != 0;
            boolean resync = syncs % 20 == 0 || !MovePackets.fitsStep(qx, qy, qz);
            if (resync) {
                cx = x; cy = y; cz = z;
                for (Player v : viewerPlayers()) MovePackets.teleport(v, entityId, x, y, z, yaw, pitch, onGround);
            } else if (moved || turned) {
                cx += qx; cy += qy; cz += qz;
                for (Player v : viewerPlayers()) MovePackets.step(v, entityId, qx, qy, qz, yaw, pitch, onGround);
            }
        }
        if (worldChanged || syncs % 10 == 0) refreshViewers();
    }

    /** Mirror the body's gear (main hand, off hand, armor). Only changed slots go on the wire. */
    public void equipment(ItemStack main, ItemStack off, ItemStack boots, ItemStack legs, ItemStack chest, ItemStack helmet) {
        ItemStack[] next = {main, off, boots, legs, chest, helmet};
        boolean changed = false;
        for (int i = 0; i < next.length; i++) {
            ItemStack n = next[i] == null || next[i].getType() == Material.AIR ? null : next[i];
            if (!same(gear[i], n)) { gear[i] = n == null ? null : n.clone(); changed = true; }
        }
        if (changed) for (Player v : viewerPlayers()) sendEquipment(v);
    }

    public void swing() {
        for (Player v : viewerPlayers()) send(v, new WrapperPlayServerEntityAnimation(entityId,
                WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
    }

    /** The red hurt flash + tilt. {@code yaw} = direction the hit came from (0 = straight). */
    public void hurt(float yaw) {
        for (Player v : viewerPlayers()) send(v, new WrapperPlayServerHurtAnimation(entityId, yaw));
    }

    /** Re-style the name tag (e.g. a team colour): {@code &}-coloured prefix + the name's colour. */
    public void tagStyle(String prefix, NamedTextColor color) {
        this.prefix = prefix == null || prefix.isEmpty() ? Component.empty() : LegacyComponentSerializer.legacyAmpersand().deserialize(prefix);
        this.color = color == null ? NamedTextColor.WHITE : color;
        ScoreBoardTeamInfo team = new ScoreBoardTeamInfo(Component.empty(), this.prefix, Component.empty(),
                NameTagVisibility.ALWAYS, CollisionRule.NEVER, this.color, OptionData.NONE);
        for (Player v : viewerPlayers()) send(v, new WrapperPlayServerTeams(teamName(), TeamMode.UPDATE, team));
    }

    public void glowing(boolean on) {
        if (glowing == on) return;
        glowing = on;
        for (Player v : viewerPlayers()) sendFlags(v);
    }

    public void sprinting(boolean on) {
        if (sprinting == on) return;
        sprinting = on;
        for (Player v : viewerPlayers()) sendFlags(v);
    }

    /** Gone for everyone; the puppet can't be used again. */
    public void remove() {
        if (removed) return;
        for (Player v : viewerPlayers()) despawn(v);
        viewers.clear();
        removed = true;
        Puppets.unregister(this);
    }

    /** A player who just joined/changed world near the puppet gets it on the next refresh. */
    void forget(UUID viewer) { viewers.remove(viewer); }

    // ============================ viewers ============================

    private void refreshViewers() {
        if (world == null) return;
        double r2 = RANGE * RANGE;
        Set<UUID> want = new HashSet<>();
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            double dx = l.getX() - x, dz = l.getZ() - z;
            if (dx * dx + dz * dz <= r2) want.add(p.getUniqueId());
        }
        for (UUID id : new ArrayList<>(viewers)) {
            if (want.contains(id)) continue;
            viewers.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) despawn(p);
        }
        for (UUID id : want) {
            if (!viewers.add(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) spawn(p);
        }
    }

    private List<Player> viewerPlayers() {
        List<Player> out = new ArrayList<>(viewers.size());
        for (UUID id : viewers) { Player p = Bukkit.getPlayer(id); if (p != null) out.add(p); }
        return out;
    }

    // ============================ packets ============================

    private void spawn(Player v) {
        List<TextureProperty> textures = new ArrayList<>(1);
        if (texture != null) textures.add(new TextureProperty("textures", texture, signature));
        UserProfile profile = new UserProfile(uuid, profileName, textures);
        var info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile, false, 0, GameMode.SURVIVAL, null, null);
        send(v, new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED), List.of(info)));
        ScoreBoardTeamInfo team = new ScoreBoardTeamInfo(Component.empty(), prefix, Component.empty(),
                NameTagVisibility.ALWAYS, CollisionRule.NEVER, color, OptionData.NONE);
        send(v, new WrapperPlayServerTeams(teamName(), TeamMode.CREATE, team, profileName));
        var pos = new com.github.retrooper.packetevents.protocol.world.Location(cx, cy, cz, yaw, pitch);
        send(v, new WrapperPlayServerSpawnEntity(entityId, uuid, EntityTypes.PLAYER, pos, yaw, 0, null));
        send(v, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(META_SKIN_PARTS, EntityDataTypes.BYTE, ALL_SKIN_LAYERS))));
        send(v, new WrapperPlayServerEntityHeadLook(entityId, yaw));
        if (glowing || sprinting) sendFlags(v);
        sendEquipment(v);
    }

    private void despawn(Player v) {
        send(v, new WrapperPlayServerDestroyEntities(entityId));
        send(v, new WrapperPlayServerTeams(teamName(), TeamMode.REMOVE, Optional.empty()));
        send(v, new WrapperPlayServerPlayerInfoRemove(uuid));
    }

    /** Shared Entity flags (index 0): 0x08 sprinting, 0x40 glowing — the same index the shop NPCs use. */
    private void sendFlags(Player v) {
        byte flags = (byte) ((sprinting ? 0x08 : 0) | (glowing ? 0x40 : 0));
        send(v, new WrapperPlayServerEntityMetadata(entityId, List.of(new EntityData<>(0, EntityDataTypes.BYTE, flags))));
    }

    private void sendEquipment(Player v) {
        List<Equipment> eq = new ArrayList<>(SLOTS.length);
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack it = gear[i] != null ? gear[i] : new ItemStack(Material.AIR);
            eq.add(new Equipment(SLOTS[i], SpigotConversionUtil.fromBukkitItemStack(it)));
        }
        send(v, new WrapperPlayServerEntityEquipment(entityId, eq));
    }

    /** A delta exactly as the relative-move packet carries it (1/4096 block steps). */
    private static double q(double d) { return Math.round(d * 4096.0) / 4096.0; }

    private String teamName() { return "lnpb" + Integer.toHexString(entityId); }

    private static boolean same(ItemStack a, ItemStack b) {
        if (a == null || b == null) return a == b;
        return a.getAmount() == b.getAmount() && a.isSimilar(b);
    }

    private static void send(Player viewer, PacketWrapper<?> wrapper) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, wrapper);
        } catch (Throwable ignored) {
            // viewer disconnecting — never break the owner's tick
        }
    }
}
