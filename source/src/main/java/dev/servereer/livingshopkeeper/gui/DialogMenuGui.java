package dev.servereer.livingshopkeeper.gui;

import dev.servereer.livingshopkeeper.audio.SpeechController;
import dev.servereer.livingshopkeeper.dialog.DialogEngine;
import dev.servereer.livingshopkeeper.dialog.DialogLibrary;
import dev.servereer.livingshopkeeper.dialog.DialogNode;
import dev.servereer.livingshopkeeper.dialog.DialogPack;
import dev.servereer.livingshopkeeper.dialog.DialogText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The click-to-talk conversation menu: right-clicking a character opens a GUI of selectable replies
 * (a {@link DialogNode} with {@code options:}). Picking one speaks the NPC's line (voice + subtitle) and
 * either {@code goto}s the next menu node — walking a conversation tree — or closes.
 */
public final class DialogMenuGui implements Listener {

    private final JavaPlugin plugin;
    private final SpeechController speech;
    private final DialogLibrary library;

    public DialogMenuGui(JavaPlugin plugin, SpeechController speech, DialogLibrary library) {
        this.plugin = plugin;
        this.speech = speech;
        this.library = library;
    }

    public void open(Player p, DialogEngine.Speaker sp, DialogNode node) {
        List<DialogNode.Option> opts = node.options;
        int rows = Math.max(1, Math.min(6, (opts.size() + 8) / 9));
        Component title = node.title != null
                ? DialogText.render(node.title, p.getName(), sp.name(), sp.world())
                : DialogText.name(sp.name() == null ? "Shopkeeper" : sp.name(), NamedTextColor.DARK_AQUA);
        Holder h = new Holder(sp, node);
        Inventory inv = Bukkit.createInventory(h, rows * 9, title);
        for (int i = 0; i < opts.size() && i < rows * 9; i++) {
            DialogNode.Option o = opts.get(i);
            // Button labels support MiniMessage colour/format; a <click:…> on a GUI item name has no effect
            // (you click the item, not text) — put clickable links in the option's `line:` reply instead.
            Component label = DialogText.render(o.label, p.getName(), sp.name(), sp.world())
                    .decoration(TextDecoration.ITALIC, false);
            inv.setItem(i, item(o, label));
            h.slotOpt.put(i, o);
        }
        h.inv = inv;
        p.openInventory(inv);
    }

    private static ItemStack item(DialogNode.Option o, Component label) {
        ItemStack it = new ItemStack(o.icon != null ? o.icon : Material.PAPER);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(label);
            it.setItemMeta(m);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        DialogNode.Option o = h.slotOpt.get(e.getRawSlot());
        if (o == null) return;

        // the NPC replies (voice from its position + subtitle). The reply is chat, so <click:open_url:'…'> works.
        if (o.line != null && !o.line.isBlank()) {
            p.sendMessage(DialogText.name(h.sp.name() == null ? "Shopkeeper" : h.sp.name(), NamedTextColor.GOLD)
                    .append(Component.text(": ", NamedTextColor.GRAY))
                    .append(DialogText.render(o.line, p.getName(), h.sp.name(), h.sp.world())));
        }
        if (o.voice != null && !o.voice.isBlank() && speech != null) {
            speech.speak(h.sp.key(), h.sp.world(), h.sp.x(), h.sp.y(), h.sp.z(), h.sp.character() + "/" + o.voice);
        }

        // navigate the tree
        DialogNode next = null;
        if (o.next != null) {
            DialogPack pack = library.pack(h.sp.character());
            if (pack != null) next = pack.node(o.next);
        }
        if (next != null && next.isMenu()) {
            DialogNode target = next;
            Bukkit.getScheduler().runTask(plugin, () -> open(p, h.sp, target));   // reopen next tick (safe)
        } else {
            p.closeInventory();
        }
    }

    private static final class Holder implements InventoryHolder {
        final DialogEngine.Speaker sp;
        final DialogNode node;
        final Map<Integer, DialogNode.Option> slotOpt = new HashMap<>();
        Inventory inv;

        Holder(DialogEngine.Speaker sp, DialogNode node) {
            this.sp = sp;
            this.node = node;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }
}
