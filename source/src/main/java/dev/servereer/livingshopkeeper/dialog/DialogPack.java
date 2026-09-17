package dev.servereer.livingshopkeeper.dialog;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One authored dialog file ({@code dialog/&lt;name&gt;.yml}) — a set of {@link DialogNode}s under a
 * {@code nodes:} section, pre-indexed by trigger so the engine can answer "what can this shopkeeper say
 * on {@code interact}?" in O(1).
 */
public final class DialogPack {

    private final String name;
    private final Map<String, List<DialogNode>> byTrigger = new LinkedHashMap<>();
    private final Map<String, DialogNode> byId = new LinkedHashMap<>();
    private final int nodeCount;

    private DialogPack(String name, List<DialogNode> nodes) {
        this.name = name;
        this.nodeCount = nodes.size();
        for (DialogNode n : nodes) {
            byId.put(n.id.toLowerCase(Locale.ROOT), n);
            for (String t : n.triggers) {
                byTrigger.computeIfAbsent(t.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(n);
            }
        }
        // Owner-authored nodes (menu-written, id "owner_<trigger>") OVERRIDE the shipped defaults for that
        // trigger — so what a shop owner sets in the Dialogue menu is exactly what the keeper says, with no
        // uneditable built-in lines leaking through alongside it.
        for (List<DialogNode> forTrigger : byTrigger.values()) {
            boolean hasOwner = forTrigger.stream().anyMatch(n -> n.id.toLowerCase(Locale.ROOT).startsWith("owner_"));
            if (hasOwner) forTrigger.removeIf(n -> !n.id.toLowerCase(Locale.ROOT).startsWith("owner_"));
        }
    }

    public String name() { return name; }
    public int nodeCount() { return nodeCount; }

    /** Nodes registered for {@code trigger} (never null; empty if none). */
    public List<DialogNode> nodesFor(String trigger) {
        return byTrigger.getOrDefault(trigger.toLowerCase(Locale.ROOT), List.of());
    }

    /** A node by id, or null. */
    public DialogNode node(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    /**
     * The menu opened on right-click <em>for this player</em>: the first {@code menu}-triggered node whose
     * {@code when:} passes (so you can author, e.g., a voiced menu {@code when: {voicechat: connected}} and a
     * plain-text fallback menu after it). Falls back to a node literally named {@code menu}.
     */
    public DialogNode menuEntry(Player p) {
        for (DialogNode n : nodesFor("menu")) {
            if (n.isMenu() && n.when.test(p)) return n;
        }
        DialogNode byName = node("menu");
        return (byName != null && byName.isMenu() && byName.when.test(p)) ? byName : null;
    }

    /** Merge every {@code *.yml} in a character's {@code dialog/} folder into one pack named {@code name}. */
    public static DialogPack loadFolder(String name, File dir) {
        List<DialogNode> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                YamlConfiguration yc = YamlConfiguration.loadConfiguration(file);
                ConfigurationSection nodes = yc.getConfigurationSection("nodes");
                if (nodes == null) continue;
                for (String id : nodes.getKeys(false)) {
                    ConfigurationSection ns = nodes.getConfigurationSection(id);
                    if (ns == null) continue;
                    DialogNode n = DialogNode.parse(id, ns);
                    if (n != null) out.add(n);
                }
            }
        }
        return new DialogPack(name, out);
    }
}
