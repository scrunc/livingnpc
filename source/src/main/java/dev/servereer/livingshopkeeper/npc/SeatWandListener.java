package dev.servereer.livingshopkeeper.npc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The seat wand: after binding (admin: {@code /lsk lobby <id> seatwand}; shop owners: the golden-sword
 * button in their shop menu) right-clicking slabs/stairs with the wand item registers them as that
 * NPC's sit spots. Clicking an ALREADY-registered seat marks it as the <b>priority seat</b> — where a
 * shop keeper prefers to sit while attending customers. Sneak-right-click removes a seat.
 * Seat position = the block's sitting surface centre; facing = the stair's outward direction, or the
 * admin's facing for slabs.
 */
public final class SeatWandListener implements Listener {

    private final JavaPlugin plugin;
    private final CharacterManager manager;
    private final Map<UUID, String> binding = new HashMap<>();   // editor → "char:<id>" or "space:<id>"

    public SeatWandListener(JavaPlugin plugin, CharacterManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /** Bind this player's wand clicks to {@code characterId} (until rebound or they log out). */
    public void bind(Player editor, String characterId) {
        binding.put(editor.getUniqueId(), "char:" + characterId.toLowerCase(Locale.ROOT));
    }

    /** Bind this player's wand clicks to a shared hangout space instead (admin: /lsk space wand <id>). */
    public void bindSpace(Player editor, String spaceId) {
        binding.put(editor.getUniqueId(), "space:" + spaceId.toLowerCase(Locale.ROOT));
    }

    /** The wand item for this character's type (shops use their own, default golden sword). */
    public Material wandItem(Character c) {
        String key = c != null && c.type() == CharacterType.SHOP ? "shop.seat-wand" : "lobby.seat-wand";
        String def = c != null && c.type() == CharacterType.SHOP ? "GOLDEN_SWORD" : "BLAZE_ROD";
        Material m = Material.matchMaterial(plugin.getConfig().getString(key, def).toUpperCase(Locale.ROOT));
        return m == null ? Material.BLAZE_ROD : m;
    }

    /** May {@code p} edit seats of {@code c}? Admins always; shop owners their own shop. */
    static boolean mayEdit(Player p, Character c) {
        if (p.hasPermission("livingnpc.admin")) return true;
        return c.type() == CharacterType.SHOP && p.getUniqueId().equals(c.owner());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        String bound = binding.get(p.getUniqueId());
        if (bound == null) return;
        if (bound.startsWith("space:")) {
            onSpaceClick(e, p, bound.substring("space:".length()));
            return;
        }
        String id = bound.startsWith("char:") ? bound.substring("char:".length()) : bound;
        Character c = manager.get(id);
        if (c == null || !mayEdit(p, c)) {
            binding.remove(p.getUniqueId());
            return;
        }
        if (p.getInventory().getItemInMainHand().getType() != wandItem(c)) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        e.setCancelled(true);

        double[] surface = seatSurface(b);
        if (surface == null) {
            p.sendMessage(Component.text("Seats must be a slab or stair.", NamedTextColor.RED));
            return;
        }
        double sx = surface[0], y = surface[1], sz = surface[2];

        if (p.isSneaking()) {
            boolean removed = manager.lobbyRemoveSeatNear(c, b.getLocation().add(0.5, 0.5, 0.5));
            p.sendMessage(Component.text(removed ? "Removed seat for '" + c.getId() + "'." : "No seat near that block.",
                    removed ? NamedTextColor.YELLOW : NamedTextColor.RED));
            return;
        }

        int existing = c.seatIndexNear(sx, y, sz, 0.7);
        if (existing >= 0) {   // clicking a registered seat again → make it the preferred one
            manager.lobbySetPrioritySeat(c, existing);
            p.sendMessage(Component.text("Seat #" + (existing + 1) + " is now '" + c.getId()
                    + "'s PRIORITY seat — it sits there while attending.", NamedTextColor.GOLD));
            return;
        }

        double[] home = c.home();
        if (home != null) {
            double dx = home[0] - sx, dz = home[2] - sz;
            if (dx * dx + dz * dz > 16 * 16) {
                p.sendMessage(Component.text("Too far from the shopkeeper's post (max 16 blocks).", NamedTextColor.RED));
                return;
            }
        }

        manager.lobbyAddSeat(c, sx, y, sz, seatYaw(b, p));
        p.sendMessage(Component.text("Added seat #" + c.seats().size() + " for '" + c.getId()
                + "'. Click it again to make it the priority seat; sneak-click removes.", NamedTextColor.GREEN));
    }

    /** Space wand mode: the same slab/stair clicks, but seats land in the shared space (no post cap). */
    private void onSpaceClick(PlayerInteractEvent e, Player p, String spaceId) {
        if (!p.hasPermission("livingnpc.admin")) {
            binding.remove(p.getUniqueId());
            return;
        }
        SpaceManager spaces = manager.spaces();
        Space sp = spaces == null ? null : spaces.get(spaceId);
        if (sp == null) {
            binding.remove(p.getUniqueId());
            return;
        }
        Material wand = Material.matchMaterial(
                plugin.getConfig().getString("lobby.seat-wand", "BLAZE_ROD").toUpperCase(Locale.ROOT));
        if (p.getInventory().getItemInMainHand().getType() != (wand == null ? Material.BLAZE_ROD : wand)) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        e.setCancelled(true);

        double[] surface = seatSurface(b);
        if (surface == null) {
            p.sendMessage(Component.text("Seats must be a slab or stair.", NamedTextColor.RED));
            return;
        }
        if (p.isSneaking()) {
            boolean removed = sp.removeSeatNear(surface[0], surface[1], surface[2], 1.5);
            if (removed) spaces.save();
            p.sendMessage(Component.text(removed ? "Removed seat from space '" + sp.id() + "'." : "No seat near that block.",
                    removed ? NamedTextColor.YELLOW : NamedTextColor.RED));
            return;
        }
        if (sp.seatIndexNear(surface[0], surface[1], surface[2], 0.7) >= 0) {
            p.sendMessage(Component.text("That block is already a seat of '" + sp.id() + "'.", NamedTextColor.YELLOW));
            return;
        }
        sp.addSeat(surface[0], surface[1], surface[2], seatYaw(b, p));
        spaces.save();
        p.sendMessage(Component.text("Added seat #" + sp.seats().size() + " to space '" + sp.id()
                + "'. Sneak-click removes.", NamedTextColor.GREEN));
    }

    /** The sitting surface centre {x,y,z} of a slab/stair block, or null when it's neither. */
    private static double[] seatSurface(Block b) {
        boolean stair = Tag.STAIRS.isTagged(b.getType());
        boolean slab = Tag.SLABS.isTagged(b.getType());
        if (!stair && !slab) return null;
        // sitting surface: top of a bottom slab / stair step is +0.5; a top slab seats at +1.0
        double y = b.getY() + 0.5;
        if (slab && b.getBlockData() instanceof Slab s && s.getType() == Slab.Type.TOP) y = b.getY() + 1.0;
        return new double[]{b.getX() + 0.5, y, b.getZ() + 0.5};
    }

    /** Sit facing: a stair's outward direction, or the editor's own facing for slabs. */
    private static float seatYaw(Block b, Player p) {
        float yaw = p.getLocation().getYaw();
        if (Tag.STAIRS.isTagged(b.getType()) && b.getBlockData() instanceof Directional d) {
            // stairs ascend toward getFacing(); you sit facing the opposite (outward) way
            yaw = switch (d.getFacing()) {
                case NORTH -> 0f;    // face SOUTH (yaw 0 = south)
                case SOUTH -> 180f;  // face NORTH
                case WEST -> -90f;   // face EAST
                case EAST -> 90f;    // face WEST
                default -> yaw;
            };
        }
        return yaw;
    }
}
