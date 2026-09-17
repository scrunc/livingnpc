package dev.servereer.livingshopkeeper.dialog;

import dev.servereer.livingshopkeeper.audio.SpeechController;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The heart of P3: given a shopkeeper, a trigger, and the player who caused it, pick a contextual line
 * from the shopkeeper's dialog pack — weighted, condition-gated, cooldown-aware — then say it (voice via
 * {@link SpeechController}) and show it (subtitle). Replaces P2's single fixed greeting clip.
 *
 * <p>Decoupled from the NPC types via {@link Speaker} (the caller passes the mouth position + pack name),
 * so the dialog layer never imports {@code npc}.
 */
public final class DialogEngine {

    /** Minimum gap between any two lines from the same shopkeeper to one player (anti-overlap/double-fire). */
    private static final long GLOBAL_MIN_GAP_MS = 1_200L;

    /** What the engine needs to know about the speaking NPC. {@code y} is the mouth height already. */
    /** {@code character} is the NPC's id — its dialog pack AND its voice namespace ({@code <character>/<clip>}). */
    public record Speaker(UUID key, String character, String name, World world, double x, double y, double z) {}

    private final JavaPlugin plugin;
    private final SpeechController speech;
    private final DialogLibrary library;

    // per-player cooldown clocks: key = "<shopKey>" (global gap) or "<shopKey>/<nodeId>" (node cooldown)
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public DialogEngine(JavaPlugin plugin, SpeechController speech, DialogLibrary library) {
        this.plugin = plugin;
        this.speech = speech;
        this.library = library;
    }

    /**
     * Fire {@code trigger} for {@code sp} caused by {@code p}. Returns true if a line was spoken (so the
     * caller can fall back — e.g. to a grunt — when nothing matched or no pack exists).
     */
    public boolean trigger(Speaker sp, String trigger, Player p) {
        DialogPack pack = library.pack(sp.character());
        if (pack == null) return false;

        long now = System.currentTimeMillis();
        Map<String, Long> clocks = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
        String globalKey = sp.key().toString();
        if (now < clocks.getOrDefault(globalKey, 0L)) return false;   // spoke too recently

        DialogNode node = pickNode(pack.nodesFor(trigger), p, now, clocks, sp.key());
        if (node == null) return false;
        DialogLine line = pickLine(node.lines, p);
        if (line == null) return false;

        // stamp cooldowns
        clocks.put(globalKey, now + GLOBAL_MIN_GAP_MS);
        if (node.cooldownMs > 0) clocks.put(nodeKey(sp.key(), node.id), now + node.cooldownMs);

        if (line.text != null && !line.text.isBlank()) {
            subtitle(p, sp.name(), DialogText.render(line.text, p.getName(), sp.name(), sp.world()));
        }
        if (line.voice != null && !line.voice.isBlank()) {
            // voice ids are namespaced per character on disk: characters/<character>/voice/<clip>
            speech.speak(sp.key(), sp.world(), sp.x(), sp.y(), sp.z(), sp.character() + "/" + line.voice);
        }
        return true;
    }

    private DialogNode pickNode(List<DialogNode> candidates, Player p, long now, Map<String, Long> clocks, UUID shopKey) {
        int total = 0;
        DialogNode[] pool = new DialogNode[candidates.size()];
        int[] weights = new int[candidates.size()];
        int n = 0;
        for (DialogNode node : candidates) {
            if (now < clocks.getOrDefault(nodeKey(shopKey, node.id), 0L)) continue;   // node on cooldown
            if (!node.when.test(p)) continue;
            pool[n] = node;
            weights[n] = node.weight;
            total += node.weight;
            n++;
        }
        if (n == 0) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < n; i++) {
            r -= weights[i];
            if (r < 0) return pool[i];
        }
        return pool[n - 1];
    }

    private DialogLine pickLine(List<DialogLine> lines, Player p) {
        int total = 0;
        for (DialogLine l : lines) if (l.when.test(p)) total += l.weight;
        if (total == 0) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        DialogLine last = null;
        for (DialogLine l : lines) {
            if (!l.when.test(p)) continue;
            last = l;
            r -= l.weight;
            if (r < 0) return l;
        }
        return last;
    }

    private void subtitle(Player p, String npcName, Component text) {
        String mode = plugin.getConfig().getString("subtitle-mode", "chat").toLowerCase(Locale.ROOT);
        Component name = DialogText.name(npcName == null ? "Shopkeeper" : npcName, NamedTextColor.GOLD);
        if (mode.equals("action_bar")) {   // note: click links only work in "chat" mode — the action bar isn't clickable
            p.sendActionBar(name.append(Component.text("  ")).append(text));
        } else {   // "chat" (default); "bubble" falls back to chat until the hologram bubble lands
            p.sendMessage(name.append(Component.text(": ", NamedTextColor.GRAY)).append(text));
        }
    }

    private static String nodeKey(UUID shopKey, String nodeId) {
        return shopKey + "/" + nodeId;
    }

    /** Drop a player's cooldown clocks on quit (keeps the map from growing). */
    public void forget(UUID player) {
        cooldowns.remove(player);
    }

    /** Reload dialog packs from disk — called after a new character folder (with dialog) is created. */
    public void reloadLibrary() {
        library.load();
    }

    /** The right-click conversation-menu entry node for {@code p} on this character, or null if none matches. */
    public DialogNode menuEntry(String character, Player p) {
        DialogPack pack = library.pack(character);
        return pack == null ? null : pack.menuEntry(p);
    }
}
