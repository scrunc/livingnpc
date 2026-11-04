package dev.servereer.livingshopkeeper.npc;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.CollisionRule;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.NameTagVisibility;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.OptionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Renders one {@link Character} as a player-type packet NPC to a single viewer — no real entity, so
 * no tick cost, any skin, and trivial persistence. Reuses the repo's packet conventions
 * ({@code SpigotReflectionUtil.generateEntityId}, {@code getPlayerManager().sendPacket}).
 *
 * <p>Spawn order matters: the tab-list profile (with the skin's texture property) must arrive
 * <em>before</em> the entity, or the client renders a default skin. We add the profile
 * <b>unlisted</b> ({@code listed=false}) so it never clutters the tab list — no add-then-remove dance.
 * Player-entity metadata index 17 is the displayed-skin-parts mask (all layers on).
 */
final class PacketNpc {

    private static final int META_SKIN_PARTS = 16; // Player/Avatar entity: displayed skin parts bitmask in 1.21.2+ (Avatar.DATA_PLAYER_MODE_CUSTOMISATION)
    private static final byte ALL_SKIN_LAYERS = (byte) 0x7F; // 127: cape, jacket, left sleeve, right sleeve, left pants, right pants, hat
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private PacketNpc() {}

    /** Add the profile, spawn the entity, and set the initial facing. */
    static void spawn(Player viewer, Character s) {
        List<TextureProperty> textures = new ArrayList<>(1);
        if (s.textureValue() != null) {
            textures.add(new TextureProperty("textures", s.textureValue(), s.textureSignature()));
        }
        UserProfile profile = new UserProfile(s.uuid(), profileName(s), textures);
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile, /*listed*/ false, /*latency*/ 0, GameMode.SURVIVAL, /*displayName*/ null, /*chatSession*/ null);
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                List.of(info)));

        // Put the NPC in its own scoreboard team so we own its floating name — colour it, and reclaim it
        // from whatever else was labelling it (e.g. TAB). CREATE carries the (unlisted) profile name as the
        // single team member. Balanced by a REMOVE in despawn, so the client never sees a duplicate CREATE.
        // A custom floating tag replaces the profile name entirely (visibility NEVER hides it client-side).
        ScoreBoardTeamInfo teamInfo = new ScoreBoardTeamInfo(
                Component.empty(), Component.empty(), Component.empty(),
                s.hasTag() ? NameTagVisibility.NEVER : NameTagVisibility.ALWAYS,
                CollisionRule.NEVER, teamColor(s), OptionData.NONE);
        send(viewer, new WrapperPlayServerTeams(teamName(s), TeamMode.CREATE, teamInfo, profileName(s)));

        Location pos = new Location(s.getX(), s.getY(), s.getZ(), s.yaw(), s.pitch());
        send(viewer, new WrapperPlayServerSpawnEntity(s.entityId, s.uuid(), EntityTypes.PLAYER, pos, s.yaw(), 0, null));
        send(viewer, new WrapperPlayServerEntityMetadata(s.entityId, List.of(
                new EntityData<>(META_SKIN_PARTS, EntityDataTypes.BYTE, ALL_SKIN_LAYERS))));
        spawnTags(viewer, s);

        send(viewer, new WrapperPlayServerEntityHeadLook(s.entityId, s.yaw()));
        if (s.seated) attachSeat(viewer, s);
        // The runtime crouch flag is not part of the spawn packet in all client versions.
        // CharacterManager will send the pose again on the next scan if this is active.
    }

    /** Destroy the entity, remove its name team, and drop its (unlisted) profile from the viewer's client. */
    static void despawn(Player viewer, Character s) {
        if (s.seated) send(viewer, new WrapperPlayServerDestroyEntities(s.seatEntityId));
        for (int id : s.tagEntityIds) send(viewer, new WrapperPlayServerDestroyEntities(id));
        send(viewer, new WrapperPlayServerDestroyEntities(s.entityId));
        send(viewer, new WrapperPlayServerTeams(teamName(s), TeamMode.REMOVE, Optional.empty()));
        send(viewer, new WrapperPlayServerPlayerInfoRemove(s.uuid()));
    }

    // --- floating multi-line tag ------------------------------------------------------------------
    // Classic hologram trick: one invisible, no-gravity armor stand per line whose custom name IS the
    // line. Only base-entity metadata indexes (0 flags, 2/3 custom name, 5 no-gravity) are used — the
    // same family the live seat mount already proves safe on this client (Display-entity text indexes
    // are version-fragile and one bad index hard-disconnects; see the skin-parts incident).

    private static final double TAG_SPACING = 0.28;     // per line, upward
    private static final double TAG_NAME_HEIGHT = 2.22; // where a normal armor stand renders its name

    // Live-tunable (config `tag-height` / `tag-seated-drop`, applied on /lsk reload): the lowest line's
    // height above the NPC's feet, and how much the whole stack drops while it sits (the seated model
    // rides the mount well below the standing head, so 0.45 left the tag floating in the air).
    static volatile double tagHeight = 2.05;
    static volatile double tagSeatedDrop = 1.0;

    /** Feet Y for tag stand {@code i} (line 0 = top) given the NPC's current feet Y + seated state. */
    private static double tagStandY(Character s, int i) {
        int n = s.tagEntityIds.length;
        double nameY = tagHeight + (n - 1 - i) * TAG_SPACING - (s.seated ? tagSeatedDrop : 0);
        return s.getY() + nameY - TAG_NAME_HEIGHT;
    }

    /** Spawn the hologram stack for one viewer (no-op without tag lines). */
    static void spawnTags(Player viewer, Character s) {
        List<String> lines = s.activeTagLines();
        for (int i = 0; i < s.tagEntityIds.length && i < lines.size(); i++) {
            UUID id = UUID.nameUUIDFromBytes((s.uuid() + ":tag:" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Location at = new Location(s.getX(), tagStandY(s, i), s.getZ(), 0, 0);
            send(viewer, new WrapperPlayServerSpawnEntity(s.tagEntityIds[i], id, EntityTypes.ARMOR_STAND, at, 0, 0, null));
            send(viewer, new WrapperPlayServerEntityMetadata(s.tagEntityIds[i], List.of(
                    new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20),                       // invisible
                    new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(parse(lines.get(i)))),
                    new EntityData<>(3, EntityDataTypes.BOOLEAN, true),                          // name visible
                    new EntityData<>(5, EntityDataTypes.BOOLEAN, true))));                       // no gravity
        }
    }

    /** Shift the whole tag stack by the NPC's walk delta (called alongside each relative move). */
    static void moveTags(Player viewer, Character s, double dx, double dy, double dz) {
        for (int id : s.tagEntityIds) {
            send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove(
                    id, dx, dy, dz, true));
        }
    }

    /** Absolutely reposition the tag stack (arrival snaps, resyncs, sit/stand height changes). */
    static void teleportTags(Player viewer, Character s) {
        for (int i = 0; i < s.tagEntityIds.length; i++) {
            send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport(
                    s.tagEntityIds[i], new Location(s.getX(), tagStandY(s, i), s.getZ(), 0, 0), true));
        }
    }

    // --- away ghost: a floating skinned head at the post while the NPC is out ---------------------
    // An invisible armor stand wearing the NPC's PLAYER_HEAD as its helmet, plus a tag-line hologram
    // stack above it. Same proven base-entity metadata indexes as the tag stands. The head slowly
    // spins (rotateGhost, driven by the manager's scan) so it reads as "alive but away".

    private static final double GHOST_STAND_HEAD = 1.75;   // helmet render height above stand feet

    /** Spawn the ghost head + its hologram lines at the post for one viewer. */
    static void spawnGhost(Player viewer, Character s, int headId, int[] tagIds,
                           double x, double y, double z, List<String> lines, double headHeight) {
        UUID headUuid = UUID.nameUUIDFromBytes((s.uuid() + ":ghost").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Location at = new Location(x, y + headHeight - GHOST_STAND_HEAD, z, 0, 0);
        send(viewer, new WrapperPlayServerSpawnEntity(headId, headUuid, EntityTypes.ARMOR_STAND, at, 0, 0, null));
        send(viewer, new WrapperPlayServerEntityMetadata(headId, List.of(
                new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20),   // invisible
                new EntityData<>(5, EntityDataTypes.BOOLEAN, true))));    // no gravity
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment(
                headId, List.of(new com.github.retrooper.packetevents.protocol.player.Equipment(
                        com.github.retrooper.packetevents.protocol.player.EquipmentSlot.HELMET, headItem(s)))));
        for (int i = 0; i < tagIds.length && i < lines.size(); i++) {
            UUID id = UUID.nameUUIDFromBytes((s.uuid() + ":ghosttag:" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            double nameY = headHeight + 0.45 + (tagIds.length - 1 - i) * TAG_SPACING;
            Location tagAt = new Location(x, y + nameY - TAG_NAME_HEIGHT, z, 0, 0);
            send(viewer, new WrapperPlayServerSpawnEntity(tagIds[i], id, EntityTypes.ARMOR_STAND, tagAt, 0, 0, null));
            send(viewer, new WrapperPlayServerEntityMetadata(tagIds[i], List.of(
                    new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20),
                    new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(parse(lines.get(i)))),
                    new EntityData<>(3, EntityDataTypes.BOOLEAN, true),
                    new EntityData<>(5, EntityDataTypes.BOOLEAN, true))));
        }
    }

    /** Spin the ghost head to {@code yaw} for one viewer. */
    static void rotateGhost(Player viewer, int headId, float yaw) {
        send(viewer, new WrapperPlayServerEntityRotation(headId, yaw, 0f, true));
        send(viewer, new WrapperPlayServerEntityHeadLook(headId, yaw));
    }

    static void despawnGhost(Player viewer, int headId, int[] tagIds) {
        for (int id : tagIds) send(viewer, new WrapperPlayServerDestroyEntities(id));
        send(viewer, new WrapperPlayServerDestroyEntities(headId));
    }

    /** The NPC's own face as a PLAYER_HEAD item (its cached texture; default steve head without one). */
    private static com.github.retrooper.packetevents.protocol.item.ItemStack headItem(Character s) {
        org.bukkit.inventory.ItemStack head = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PLAYER_HEAD);
        try {
            if (s.textureValue() != null
                    && head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta meta) {
                com.destroystokyo.paper.profile.PlayerProfile profile =
                        org.bukkit.Bukkit.createProfile(s.uuid(), "ghost");
                profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty(
                        "textures", s.textureValue(), s.textureSignature()));
                meta.setPlayerProfile(profile);
                head.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
            // texture application is cosmetic — a plain head is an acceptable fallback
        }
        return io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitItemStack(head);
    }

    /** Turn the NPC's body + head toward {@code yaw}/{@code pitch} for one viewer. */
    static void rotate(Player viewer, Character s, float yaw, float pitch) {
        send(viewer, new WrapperPlayServerEntityRotation(s.entityId, yaw, pitch, true));
        send(viewer, new WrapperPlayServerEntityHeadLook(s.entityId, yaw));
    }

    /** One walk step to a viewer (relative move + facing) — the client animates the walk from the delta. */
    static void move(Player viewer, Character s, double dx, double dy, double dz, float yaw, boolean onGround) {
        dev.servereer.livingshopkeeper.move.MovePackets.step(viewer, s.entityId, dx, dy, dz, yaw, 0f, onGround);
        moveTags(viewer, s, dx, dy, dz);
    }

    /** Put {@code item} in the NPC's main hand for one viewer (null/AIR clears) — the spar weapon mirror. */
    static void equipMainHand(Player viewer, Character s, org.bukkit.inventory.ItemStack item) {
        org.bukkit.inventory.ItemStack safe = item != null ? item
                : new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR);
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment(
                s.entityId, List.of(new com.github.retrooper.packetevents.protocol.player.Equipment(
                        com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND,
                        io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitItemStack(safe)))));
    }

    /**
     * Dress the NPC in a full armor set for one viewer — the spar battle kit. {@code tier} is a
     * material prefix ("iron", "diamond", "netherite", "gold"/"golden", "chainmail", "leather");
     * null/"none" strips every armor slot instead.
     */
    static void equipArmor(Player viewer, Character s, String tier) {
        String t = tier == null ? null : tier.trim().toUpperCase(Locale.ROOT);
        if ("GOLD".equals(t)) t = "GOLDEN";
        List<com.github.retrooper.packetevents.protocol.player.Equipment> eq = new ArrayList<>(4);
        String[][] slots = {
                {"HELMET", "HELMET"}, {"CHESTPLATE", "CHEST_PLATE"},
                {"LEGGINGS", "LEGGINGS"}, {"BOOTS", "BOOTS"}};
        for (String[] slot : slots) {
            org.bukkit.inventory.ItemStack piece = null;
            if (t != null && !"NONE".equals(t)) {
                org.bukkit.Material m = org.bukkit.Material.matchMaterial(t + "_" + slot[0]);
                if (m != null) piece = new org.bukkit.inventory.ItemStack(m);
            }
            eq.add(new com.github.retrooper.packetevents.protocol.player.Equipment(
                    com.github.retrooper.packetevents.protocol.player.EquipmentSlot.valueOf(slot[1]),
                    io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitItemStack(
                            piece != null ? piece : new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR))));
        }
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment(
                s.entityId, eq));
    }

    /** Swing the main arm — a hand gesture while the barker is talking. */
    static void swing(Player viewer, Character s) {
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation(
                s.entityId,
                com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
    }

    /** The red hurt flinch + tilt the client plays for a struck entity. */
    static void hurt(Player viewer, Character s) {
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation(
                s.entityId, 0f));
    }

    /** Change the fake player's crouch pose. Indexes 0 and 6 are shared Entity metadata, not player-only. */
    static void crouch(Player viewer, Character s, boolean crouching) {
        byte flags = crouching ? (byte) 0x02 : 0;
        send(viewer, new WrapperPlayServerEntityMetadata(s.entityId, List.of(
                new EntityData<>(0, EntityDataTypes.BYTE, flags),
                new EntityData<>(6, EntityDataTypes.ENTITY_POSE,
                        crouching ? EntityPose.CROUCHING : EntityPose.STANDING))));
    }

    /** How far below the sit position the seat stand spawns; config {@code seat-mount-offset} tunes it. */
    static volatile double seatMountOffset = 1.7;

    /** Mount the fake player on a client-only invisible armor stand to produce the vanilla seated pose. */
    static void attachSeat(Player viewer, Character s) {
        Location seat = new Location(s.getX(), s.getY() - seatMountOffset, s.getZ(), s.yaw(), 0);
        UUID seatUuid = UUID.nameUUIDFromBytes((s.uuid() + ":seat").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        send(viewer, new WrapperPlayServerSpawnEntity(s.seatEntityId, seatUuid, EntityTypes.ARMOR_STAND,
                seat, s.yaw(), 0, null));
        send(viewer, new WrapperPlayServerEntityMetadata(s.seatEntityId, List.of(
                new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20), // invisible
                new EntityData<>(5, EntityDataTypes.BOOLEAN, true)))); // no gravity
        send(viewer, new WrapperPlayServerSetPassengers(s.seatEntityId, new int[]{s.entityId}));
    }

    static void detachSeat(Player viewer, Character s) {
        send(viewer, new WrapperPlayServerSetPassengers(s.seatEntityId, new int[0]));
        send(viewer, new WrapperPlayServerDestroyEntities(s.seatEntityId));
        dev.servereer.livingshopkeeper.move.MovePackets.teleport(viewer, s.entityId,
                s.getX(), s.getY(), s.getZ(), s.yaw(), s.pitch(), true);
    }

    /**
     * The NPC's floating name = its (unlisted) profile username, which is the plain text of the display name
     * sanitised to a valid username (<=16 chars, letters/digits/underscore — no spaces or colour). Colour and
     * removal of any external label are handled by the scoreboard team. For a fully custom, multi-word,
     * multi-colour tag, the text-display route is the alternative (not this path).
     */
    private static String profileName(Character s) {
        String plain = PlainTextComponentSerializer.plainText().serialize(parse(s.display()));
        String base = plain.replaceAll("[^A-Za-z0-9_]", "");
        if (base.isEmpty()) base = s.getId().replaceAll("[^A-Za-z0-9_]", "");
        if (base.isEmpty()) base = "npc";
        return base.length() > 16 ? base.substring(0, 16) : base;
    }

    /** The team colour = the first colour in the display name (mapped to the nearest named colour), or white. */
    private static NamedTextColor teamColor(Character s) {
        TextColor c = firstColor(parse(s.display()));
        return c == null ? NamedTextColor.WHITE : NamedTextColor.nearestTo(c);
    }

    private static TextColor firstColor(Component c) {
        if (c.color() != null) return c.color();
        for (Component child : c.children()) {
            TextColor r = firstColor(child);
            if (r != null) return r;
        }
        return null;
    }

    /** A stable, unique, <=16-char team name for this NPC (entity ids are unique per load). */
    private static String teamName(Character s) {
        return "lsk" + Integer.toHexString(s.entityId);
    }

    private static Component parse(String mini) {
        if (mini == null) return Component.empty();
        try {
            return MM.deserialize(mini);
        } catch (Exception e) {
            return Component.text(mini);
        }
    }

    private static void send(Player viewer, PacketWrapper<?> wrapper) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, wrapper);
        } catch (Throwable ignored) {
            // viewer disconnecting / channel closed — never let one bad send break the render loop
        }
    }
}
