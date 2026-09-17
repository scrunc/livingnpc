package dev.servereer.livingshopkeeper.audio;

import dev.servereer.livingshopkeeper.voice.VoiceCore;
import dev.servereer.livingshopkeeper.voice.VoiceHandle;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link AudioCore} backed by the original {@code LivingShopkeeperVoice} sibling plugin — kept so a
 * server still running that core keeps working after this plugin moved to PixelAudio.
 *
 * <p><b>Every {@code …livingshopkeeper.voice} reference in the main plugin lives in this one file.</b>
 * Those classes ship in the companion jar, not this one, so nothing here may be touched before
 * {@code isPluginEnabled("LivingShopkeeperVoice")} — see {@link AudioCores}.
 */
final class VoiceCoreSource implements AudioCore {

    private final VoiceCore core;

    private VoiceCoreSource(VoiceCore core) {
        this.core = core;
    }

    /** Grab the service, or null if the companion is enabled but hasn't published it. */
    static AudioCore load(JavaPlugin plugin) {
        VoiceCore core = plugin.getServer().getServicesManager().load(VoiceCore.class);
        return core == null ? null : new VoiceCoreSource(core);
    }

    @Override
    public String name() {
        return "LivingShopkeeperVoice (legacy)";
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
        VoiceHandle handle = core.start(world, x, y, z, distance, supplier, onStopped);
        return handle == null ? null : handle::stop;
    }
}
