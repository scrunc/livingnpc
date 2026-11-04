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
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.illustro.picore.menu.PiButton;
import org.illustro.picore.menu.PiMenu;

import java.util.List;

/**
 * The click-to-talk conversation menu: right-clicking a character opens a GUI of selectable replies
 * (a {@link DialogNode} with {@code options:}). Picking one speaks the NPC's line (voice + subtitle) and
 * either {@code goto}s the next menu node — walking a conversation tree — or closes.
 * Powered by PiCore universal menu framework.
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

        PiMenu menu = PiMenu.create(title, rows);

        for (int i = 0; i < opts.size() && i < rows * 9; i++) {
            DialogNode.Option o = opts.get(i);
            // Button labels support MiniMessage colour/format; a <click:…> on a GUI item name has no effect
            // (you click the item, not text) — put clickable links in the option's `line:` reply instead.
            Component label = DialogText.render(o.label, p.getName(), sp.name(), sp.world())
                    .decoration(TextDecoration.ITALIC, false);
            ItemStack icon = item(o, label);

            PiButton btn = PiButton.create(icon).onClick((player, event) -> {
                // the NPC replies (voice from its position + subtitle). The reply is chat, so <click:open_url:'…'> works.
                if (o.line != null && !o.line.isBlank()) {
                    player.sendMessage(DialogText.name(sp.name() == null ? "Shopkeeper" : sp.name(), NamedTextColor.GOLD)
                            .append(Component.text(": ", NamedTextColor.GRAY))
                            .append(DialogText.render(o.line, player.getName(), sp.name(), sp.world())));
                }
                if (o.voice != null && !o.voice.isBlank() && speech != null) {
                    speech.speak(sp.key(), sp.world(), sp.x(), sp.y(), sp.z(), sp.character() + "/" + o.voice);
                }

                // navigate the tree
                DialogNode next = null;
                if (o.next != null) {
                    DialogPack pack = library.pack(sp.character());
                    if (pack != null) next = pack.node(o.next);
                }
                if (next != null && next.isMenu()) {
                    DialogNode target = next;
                    open(player, sp, target);
                } else {
                    player.closeInventory();
                }
            });

            menu.button(i, btn);
        }

        menu.open(p);
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
}
