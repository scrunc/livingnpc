package dev.servereer.livingshopkeeper.shop;

import dev.servereer.livingshopkeeper.npc.Character;
import dev.servereer.livingshopkeeper.npc.CharacterManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.illustro.picore.menu.PiPrompt;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shop settings that need one input from the owner: a new name (chat), a skin player-name (chat), or a
 * new stock chest (click it). Chat prompts are powered by PiPrompt; chest linking uses block interaction.
 */
public final class ShopSettings implements Listener {

    public enum Mode { RENAME, SKIN, CHEST, ADD_LINE }

    private record Pending(Mode mode, String shopId, long expires) {}

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
        switch (mode) {
            case RENAME -> {
                PiPrompt.text(plugin, p, "New Shop Name", c.display(), text -> {
                    String clean = dev.servereer.livingshopkeeper.dialog.DialogText.sanitizeUserInput(text).trim();
                    if (clean.length() > 64) clean = clean.substring(0, 64);
                    manager.setDisplay(c, clean);
                    p.sendMessage(Component.text("Shop renamed to ", NamedTextColor.GREEN)
                            .append(dev.servereer.livingshopkeeper.dialog.DialogText.mini(clean))
                            .append(Component.text(".", NamedTextColor.GREEN)));
                    if (guis != null) guis.openMain(p, c);
                }, () -> {
                    if (guis != null) guis.openMain(p, c);
                });
            }
            case SKIN -> {
                PiPrompt.text(plugin, p, "Shopkeeper Skin (Player Name)", "", text -> {
                    manager.applySkin(c, text);
                    p.sendMessage(Component.text("Fetching the skin of '" + text + "'…", NamedTextColor.GREEN));
                    if (guis != null) guis.openMain(p, c);
                }, () -> {
                    if (guis != null) guis.openMain(p, c);
                });
            }
            case CHEST -> {
                pending.put(p.getUniqueId(), new Pending(mode, c.getId(), System.currentTimeMillis() + 60_000));
                p.closeInventory();
                p.sendMessage(Component.text("Right-click the NEW stock chest (within "
                        + (int) plugin.getConfig().getDouble("shop.max-container-distance", 10)
                        + " blocks of the shopkeeper).", NamedTextColor.GOLD));
            }
            case ADD_LINE -> { /* started via beginLine */ }
        }
    }

    /** Start capturing one chat line to append to the list {@code key} ("tag" or a dialogue trigger). */
    public void beginLine(Player p, Character c, String key) {
        PiPrompt.text(plugin, p, "Add " + ("tag".equals(key) ? "Nametag" : "Dialogue") + " Line", "", text -> {
            if (guis != null) guis.addLine(p, c, key, text);
        }, () -> {
            if (guis != null) guis.openLineList(p, c, key);
        });
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
