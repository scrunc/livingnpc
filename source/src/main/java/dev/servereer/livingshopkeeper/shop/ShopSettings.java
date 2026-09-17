package dev.servereer.livingshopkeeper.shop;

import dev.servereer.livingshopkeeper.npc.Character;
import dev.servereer.livingshopkeeper.npc.CharacterManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shop settings that need one input from the owner: a new name (chat), a skin player-name (chat), or a
 * new stock chest (click it). Started from a shop-menu button; expires after a minute; "cancel" aborts.
 */
public final class ShopSettings implements Listener {

    public enum Mode { RENAME, SKIN, CHEST, ADD_LINE }

    private record Pending(Mode mode, String shopId, long expires, String arg) {}

    private final JavaPlugin plugin;
    private final CharacterManager manager;
    private ShopGuis guis;   // wired after construction (used to reopen the line editor after a chat add)
    private final Map<UUID, Pending> pending = new HashMap<>();

    public ShopSettings(JavaPlugin plugin, CharacterManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /** Wire the GUI suite so a chat-added line can reopen its list editor. Called once by the plugin. */
    public void wireGuis(ShopGuis guis) {
        this.guis = guis;
    }

    public void begin(Player p, Character c, Mode mode) {
        pending.put(p.getUniqueId(), new Pending(mode, c.getId(), System.currentTimeMillis() + 60_000, null));
        p.closeInventory();
        switch (mode) {
            case RENAME -> p.sendMessage(Component.text("Type the new shop name in chat (& / &#hex / MiniMessage"
                    + " colors work). Type 'cancel' to abort.", NamedTextColor.GOLD));
            case SKIN -> p.sendMessage(Component.text("Type the player name whose skin the shopkeeper should wear"
                    + " ('cancel' to abort).", NamedTextColor.GOLD));
            case CHEST -> p.sendMessage(Component.text("Right-click the NEW stock chest (within "
                    + (int) plugin.getConfig().getDouble("shop.max-container-distance", 10)
                    + " blocks of the shopkeeper).", NamedTextColor.GOLD));
            case ADD_LINE -> { /* started via beginLine, which carries the list key */ }
        }
    }

    /** Start capturing one chat line to append to the list {@code key} ("tag" or a dialogue trigger). */
    public void beginLine(Player p, Character c, String key) {
        pending.put(p.getUniqueId(), new Pending(Mode.ADD_LINE, c.getId(), System.currentTimeMillis() + 60_000, key));
        p.closeInventory();
        p.sendMessage(Component.text("Type the line in chat (& / &#hex / MiniMessage colors, and <player> <npc>"
                + " <time> work). Type 'cancel' to abort.", NamedTextColor.GOLD));
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Pending pd = pending.get(e.getPlayer().getUniqueId());
        if (pd == null || pd.mode() == Mode.CHEST) return;
        if (System.currentTimeMillis() > pd.expires()) {
            pending.remove(e.getPlayer().getUniqueId());
            return;
        }
        e.setCancelled(true);
        pending.remove(e.getPlayer().getUniqueId());
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> apply(p, pd, text));
    }

    private void apply(Player p, Pending pd, String text) {
        if (text.equalsIgnoreCase("cancel") || text.isBlank()) {
            p.sendMessage(Component.text("Cancelled.", NamedTextColor.GRAY));
            return;
        }
        Character c = manager.get(pd.shopId());
        if (c == null) return;
        switch (pd.mode()) {
            case RENAME -> {
                // Security: strip command/URL clicks + insertions the owner might type; cap length.
                String clean = dev.servereer.livingshopkeeper.dialog.DialogText.sanitizeUserInput(text).trim();
                if (clean.length() > 64) clean = clean.substring(0, 64);
                manager.setDisplay(c, clean);
                p.sendMessage(Component.text("Shop renamed to ", NamedTextColor.GREEN)
                        .append(dev.servereer.livingshopkeeper.dialog.DialogText.mini(clean))
                        .append(Component.text(".", NamedTextColor.GREEN)));
            }
            case SKIN -> {
                manager.applySkin(c, text);
                p.sendMessage(Component.text("Fetching the skin of '" + text + "'…", NamedTextColor.GREEN));
            }
            case ADD_LINE -> {
                if (guis != null) guis.addLine(p, c, pd.arg(), text);
            }
            default -> { }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Pending pd = pending.get(e.getPlayer().getUniqueId());
        if (pd == null || pd.mode() != Mode.CHEST) return;
        Player p = e.getPlayer();
        if (System.currentTimeMillis() > pd.expires()) {
            pending.remove(p.getUniqueId());
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null || !(b.getState() instanceof Container)) return;
        e.setCancelled(true);
        pending.remove(p.getUniqueId());
        Character c = manager.get(pd.shopId());
        if (c == null) return;
        Location post = manager.location(c);
        double max = plugin.getConfig().getDouble("shop.max-container-distance", 10);
        if (post == null || !post.getWorld().equals(b.getWorld())
                || post.distanceSquared(b.getLocation().add(0.5, 0.5, 0.5)) > max * max) {
            p.sendMessage(Component.text("That chest is too far from the shopkeeper (max " + (int) max
                    + " blocks).", NamedTextColor.RED));
            return;
        }
        c.setContainer(new int[]{b.getX(), b.getY(), b.getZ()});
        manager.persist(c);
        p.sendMessage(Component.text("Stock chest relinked — stock and payments now flow through it.",
                NamedTextColor.GREEN));
    }
}
