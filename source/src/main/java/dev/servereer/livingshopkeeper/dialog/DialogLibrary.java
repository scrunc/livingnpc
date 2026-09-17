package dev.servereer.livingshopkeeper.dialog;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds each character's dialog, hot-reloadable. Dialog is <b>per-character</b>: every
 * {@code characters/<name>/dialog/*.yml} is merged into one pack keyed by the character's id, so a
 * character's lines live in its own folder alongside its voice clips.
 */
public final class DialogLibrary {

    private final JavaPlugin plugin;
    private final File charactersDir;
    private final Map<String, DialogPack> packs = new ConcurrentHashMap<>();

    public DialogLibrary(JavaPlugin plugin) {
        this.plugin = plugin;
        this.charactersDir = new File(plugin.getDataFolder(), "characters");
        //noinspection ResultOfMethodCallIgnored
        this.charactersDir.mkdirs();
    }

    /** (Re)load every character's dialog folder. */
    public void load() {
        packs.clear();
        int nodes = 0;
        File[] chars = charactersDir.listFiles(File::isDirectory);
        if (chars != null) {
            for (File cdir : chars) {
                File dialogDir = new File(cdir, "dialog");
                if (!dialogDir.isDirectory()) continue;
                try {
                    DialogPack p = DialogPack.loadFolder(cdir.getName().toLowerCase(Locale.ROOT), dialogDir);
                    packs.put(p.name(), p);
                    nodes += p.nodeCount();
                } catch (Throwable t) {
                    plugin.getLogger().warning("Skipping dialog for '" + cdir.getName() + "': " + t.getMessage());
                }
            }
        }
        plugin.getLogger().info("Dialog: loaded " + packs.size() + " pack(s), " + nodes + " node(s).");
    }

    /** The dialog pack for a character (by id), or null. */
    public DialogPack pack(String character) {
        return character == null ? null : packs.get(character.toLowerCase(Locale.ROOT));
    }
}
