package dev.servereer.livingshopkeeper.audio;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decoded voice library. Clips are per-character: a clip id is {@code "<character>/<clip>"} and
 * resolves to {@code characters/<character>/voice/<clip>.(wav|mp3)}. Decoded PCM is cached in memory so
 * SVC's 20 ms frame pulls never touch the disk or a decoder. WAV loads via {@code javax.sound}; MP3 via
 * JLayer (see {@link VoiceClip}), so ElevenLabs {@code .mp3} exports drop in without conversion.
 */
public final class VoiceBank {

    private final JavaPlugin plugin;
    private final File charactersDir;   // plugins/LivingShopkeeper/characters/
    private final Map<String, VoiceClip> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loading = new ConcurrentHashMap<>();
    private final Map<String, Long> mtimes = new ConcurrentHashMap<>();   // clip key → file mtime last decoded

    public VoiceBank(JavaPlugin plugin) {
        this.plugin = plugin;
        this.charactersDir = new File(plugin.getDataFolder(), "characters");
        //noinspection ResultOfMethodCallIgnored
        this.charactersDir.mkdirs();
    }

    /** Decode every {@code characters/<c>/voice/*} into the cache (keyed {@code <c>/<clip>}), off-thread. */
    public void preloadAsync() {
        scanAsync(false);
    }

    /**
     * Pick up NEW or EDITED clips off-thread <em>additively</em> — decode into the cache, never clear it.
     * Used by {@code /lsk reload}: a line that's currently playing keeps its own decoded reference, so a
     * reload never cuts audio, and the SVC channel is left untouched.
     */
    public void refreshAsync() {
        scanAsync(true);
    }

    /** Walk {@code characters/<c>/voice/*}; when {@code onlyChanged}, skip clips already cached and unmodified. */
    private void scanAsync(boolean onlyChanged) {
        runAsync(() -> {
            int ok = 0;
            File[] chars = charactersDir.listFiles(File::isDirectory);
            if (chars != null) {
                for (File cdir : chars) {
                    File voice = new File(cdir, "voice");
                    File[] clips = voice.listFiles((d, n) -> {
                        String l = n.toLowerCase(Locale.ROOT);
                        return l.endsWith(".wav") || l.endsWith(".mp3");
                    });
                    if (clips == null) continue;
                    String cname = cdir.getName().toLowerCase(Locale.ROOT);
                    for (File f : clips) {
                        String clip = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
                        String key = cname + "/" + clip;
                        if (onlyChanged && cache.containsKey(key) && f.lastModified() <= mtimes.getOrDefault(key, 0L)) continue;
                        if (decodeInto(key, f)) ok++;
                    }
                }
            }
            plugin.getLogger().info("Voice bank: " + (onlyChanged ? "refreshed " : "loaded ") + ok + " clip(s).");
        });
    }

    /** The decoded clip for {@code "<character>/<clip>"}, or null (kicks off an async decode on a miss). */
    public VoiceClip get(String id) {
        if (id == null) return null;
        String key = id.toLowerCase(Locale.ROOT);
        VoiceClip hit = cache.get(key);
        if (hit != null) return hit;
        File f = fileFor(key);
        if (f != null && loading.putIfAbsent(key, Boolean.TRUE) == null) {
            runAsync(() -> { decodeInto(key, f); loading.remove(key); });
        }
        return null;
    }

    public boolean has(String id) {
        if (id == null) return false;
        String key = id.toLowerCase(Locale.ROOT);
        return cache.containsKey(key) || fileFor(key) != null;
    }

    /** Resolve {@code "<character>/<clip>"} → its WAV (preferred) or MP3 file, or null. */
    private File fileFor(String key) {
        int slash = key.indexOf('/');
        if (slash <= 0 || slash == key.length() - 1) return null;
        File voice = new File(new File(charactersDir, key.substring(0, slash)), "voice");
        String clip = key.substring(slash + 1);
        File wav = new File(voice, clip + ".wav");
        if (wav.isFile()) return wav;
        File mp3 = new File(voice, clip + ".mp3");
        return mp3.isFile() ? mp3 : null;
    }

    private boolean decodeInto(String key, File f) {
        try {
            cache.put(key, VoiceClip.load(f));
            mtimes.put(key, f.lastModified());   // so a later refresh only re-decodes when the file changes
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not decode voice clip '" + key + "': " + t.getMessage());
            return false;
        }
    }

    private void runAsync(Runnable r) {
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r);
        } catch (Throwable t) {
            r.run();   // plugin disabling — decode inline
        }
    }
}
