package dev.servereer.livingshopkeeper.audio;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays shopkeeper voice lines as positional SVC audio at the NPC. One line per NPC at a time — a new
 * line interrupts the current one — so a shopkeeper never talks over itself. Everyone within range hears
 * it (it's a locational channel), which is exactly right for an NPC speaking out loud.
 *
 * <p>Decoupled from the NPC types: callers pass a stable {@code npcKey} + world + position, so this lives
 * cleanly in the audio layer.
 */
public final class SpeechController {

    private final AudioCore audio;   // the persistent audio-core service; null if no core plugin is installed
    private final VoiceBank bank;
    private final float distance;
    private final Map<UUID, AudioCore.Stop> active = new ConcurrentHashMap<>();

    public SpeechController(JavaPlugin plugin, AudioCore audio, VoiceBank bank, float distance) {
        this.audio = audio;
        this.bank = bank;
        this.distance = Math.max(1f, distance);
    }

    /** True once the audio core is present and SVC has handed it the server API. */
    public boolean available() {
        return audio != null && audio.available();
    }

    /** True once {@code clipId} is (or can be) decoded — cheap pre-check before choosing to speak. */
    public boolean hasClip(String clipId) {
        return bank.has(clipId);
    }

    /** True while this NPC still has an active voice stream; used to pace body-language gestures. */
    public boolean isSpeaking(UUID npcKey) {
        return active.containsKey(npcKey);
    }

    /**
     * Speak {@code clipId} at {@code (x,y,z)} in {@code world}, interrupting any line the same NPC is
     * mid-way through. No-op if voice is unavailable or the clip isn't decoded yet.
     */
    public void speak(UUID npcKey, World world, double x, double y, double z, String clipId) {
        if (world == null || audio == null || !audio.available()) return;
        VoiceClip clip = bank.get(clipId);
        if (clip == null) return;
        stop(npcKey);
        final long[] frame = {0};
        final AudioCore.Stop[] self = new AudioCore.Stop[1];
        AudioCore.Stop h = audio.start(world, x, y, z, distance,
                () -> {
                    long i = frame[0]++;
                    if (clip.exhausted(i)) return null;   // returning null ends playback
                    return clip.frameAt(i, false);
                },
                () -> active.remove(npcKey, self[0]));      // only clear if we're still the current line
        self[0] = h;
        if (h != null) active.put(npcKey, h);
    }

    public void stop(UUID npcKey) {
        AudioCore.Stop h = active.remove(npcKey);
        if (h != null) h.stop();
    }

    public void stopAll() {
        for (AudioCore.Stop h : active.values()) h.stop();
        active.clear();
    }
}
