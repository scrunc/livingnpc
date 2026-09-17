package dev.servereer.livingshopkeeper.audio;

import dev.servereer.pixelaudio.AudioHandle;
import dev.servereer.pixelaudio.AudioMode;
import dev.servereer.pixelaudio.PixelAudio;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link AudioCore} backed by the shared PixelAudio plugin — the same core KOTH, VideoWall,
 * ArcadeCab and MachineConstruct speak through, so one SVC connection serves them all.
 *
 * <p><b>Every {@code dev.servereer.pixelaudio} reference in this plugin lives in this one file.</b>
 * Nothing here may be touched before {@code isPluginEnabled("PixelAudio")} — see {@link AudioCores}.
 */
final class PixelAudioSource implements AudioCore {

    private final PixelAudio core;

    private PixelAudioSource(PixelAudio core) {
        this.core = core;
    }

    /** Grab the service, or null if PixelAudio is enabled but hasn't published it. */
    static AudioCore load(JavaPlugin plugin) {
        PixelAudio core = plugin.getServer().getServicesManager().load(PixelAudio.class);
        return core == null ? null : new PixelAudioSource(core);
    }

    @Override
    public String name() {
        return "PixelAudio";
    }

    @Override
    public boolean available() {
        return core.available();
    }

    @Override
    public boolean hasVoiceChat(UUID player) {
        return core.hasVoiceChat(player);
    }

    @Override
    public boolean isVoiceConnected(UUID player) {
        return core.isVoiceConnected(player);
    }

    @Override
    public Stop start(World world, double x, double y, double z, float distance,
                      Supplier<short[]> supplier, Runnable onStopped) {
        // SPEECH, not MUSIC: VOIP-tuned encoding is the right one for a talking NPC.
        AudioHandle handle = core.startLocational(world, x, y, z, distance, AudioMode.SPEECH, supplier, onStopped);
        return handle == null ? null : handle::stop;
    }
}
