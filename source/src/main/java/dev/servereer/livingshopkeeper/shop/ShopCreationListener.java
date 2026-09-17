package dev.servereer.livingshopkeeper.shop;

import dev.servereer.livingshopkeeper.npc.CharacterManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The two-click player shop creation flow (the Shopkeepers-plugin ritual, GUI-era edition):
 * hold the creation item → right-click YOUR chest (links stock) → right-click the block where the
 * shopkeeper should stand. The placer becomes the owner; the NPC wears their skin. The creation item
 * is PDC-tagged, so ordinary spawn eggs can never trigger this, and the listener respects protection
 * plugins (a claim that cancels the chest click cancels us too).
 */
public final class ShopCreationListener implements Listener {

    private static final long PENDING_MS = 60_000;

    private final JavaPlugin plugin;
    private final CharacterManager manager;
    private final NamespacedKey markerKey;
    private final Map<UUID, Object[]> pending = new HashMap<>();   // player → {Location chest, Long expiresMs}

    public ShopCreationListener(JavaPlugin plugin, CharacterManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.markerKey = new NamespacedKey(plugin, "shop-creation-item");
    }

    /** Build one creation item (PDC-tagged; the material comes from {@code shop.creation-item}). */
    public ItemStack creationItem(int amount) {
        Material m = Material.matchMaterial(
                plugin.getConfig().getString("shop.creation-item", "VILLAGER_SPAWN_EGG").toUpperCase(Locale.ROOT));
        if (m == null) m = Material.VILLAGER_SPAWN_EGG;
        ItemStack it = new ItemStack(m, Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Living Shopkeeper", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Place your own shop:"),
                line("1. Right-click YOUR chest with this"),
                line("2. Right-click where it should stand"),
                line("Then click the shopkeeper to add trades.")));
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private static Component line(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private boolean isCreationItem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!isCreationItem(held)) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        e.setCancelled(true);   // never place the egg/item itself
        if (!p.hasPermission("livingnpc.shop.create")) {
            p.sendMessage(Component.text("You may not place shops here.", NamedTextColor.RED));
            return;
        }

        if (b.getState() instanceof Container) {
            pending.put(p.getUniqueId(), new Object[]{b.getLocation(), System.currentTimeMillis() + PENDING_MS});
            p.sendMessage(Component.text("Chest linked! Now right-click the block where your shopkeeper should stand"
                    + " (within " + (int) plugin.getConfig().getDouble("shop.max-container-distance", 10)
                    + " blocks of the chest).", NamedTextColor.GREEN));
            return;
        }

        Object[] pend = pending.get(p.getUniqueId());
        if (pend == null || System.currentTimeMillis() > (Long) pend[1]) {
            pending.remove(p.getUniqueId());
            p.sendMessage(Component.text("First right-click YOUR chest with this item — that chest holds"
                    + " the shop's stock and earnings.", NamedTextColor.YELLOW));
            return;
        }
        Location chest = (Location) pend[0];
        Block standOn = b.getRelative(e.getBlockFace());
        Location spot = standOn.getLocation().add(0.5, 0, 0.5);
        if (!spot.getWorld().equals(chest.getWorld())
                || spot.distanceSquared(chest) > sq(plugin.getConfig().getDouble("shop.max-container-distance", 10))) {
            p.sendMessage(Component.text("Too far from the linked chest — stand it closer.", NamedTextColor.RED));
            return;
        }
        // shops belong on the player's own plot: an ARM plot is a WorldGuard region, and by default we
        // require actual OWNERSHIP (the ARM buyer/renter) — being trusted on someone's plot isn't enough
        if (plugin.getConfig().getBoolean("shop.require-region", true) && RegionGate.available()
                && !p.hasPermission("livingnpc.admin")) {
            boolean ownerOnly = plugin.getConfig().getBoolean("shop.require-region-owner", true);
            boolean allowed = ownerOnly ? RegionGate.ownerOfRegionAt(p, spot) : RegionGate.memberOfRegionAt(p, spot);
            if (!allowed) {
                p.sendMessage(Component.text(ownerOnly
                                ? "Shops can only be placed on a plot YOU own — buy or rent the region first."
                                : "Shops can only be placed on a plot you own or are trusted on.",
                        NamedTextColor.RED));
                return;
            }
        }
        spot.setYaw(p.getLocation().getYaw() + 180f);   // face the placer

        dev.servereer.livingshopkeeper.npc.Character shop = manager.createPlayerShop(p, spot, chest);
        if (shop == null) return;   // createPlayerShop messaged the reason
        pending.remove(p.getUniqueId());
        held.setAmount(held.getAmount() - 1);
        p.sendMessage(Component.text("Shop placed! Click your shopkeeper to open its menu and add trades."
                + " Stock lives in the linked chest.", NamedTextColor.GREEN));
    }

    private static double sq(double v) { return v * v; }
}
