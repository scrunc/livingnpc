package dev.servereer.livingshopkeeper.audio;

import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Picks the audio core to speak through, preferring the shared {@code PixelAudio} plugin over the
 * older {@code LivingShopkeeperVoice} sibling.
 *
 * <p><b>The string checks are load-bearing.</b> Neither core's classes are bundled here (both are
 * {@code compileOnly}), so naming {@code PixelAudio.class} on a server without that plugin throws
 * {@link NoClassDefFoundError} — and it throws on the way in, before any {@code == null} guard can
 * return. So the foreign types live behind {@link PixelAudioSource} / {@link VoiceCoreSource}, and
 * those classes are only ever touched after {@code isPluginEnabled(…)} — a plain string — says yes.
 * Same rule KOTH's {@code MorphBridge} follows, and for the same reason (ADR 0035).
 */
public final class AudioCores {

    private AudioCores() {
    }

    /** The chosen core, or null when neither plugin is installed (NPCs then run silent). */
    public static AudioCore resolve(JavaPlugin plugin) {
        PluginManager pm = plugin.getServer().getPluginManager();
        if (pm.isPluginEnabled("PixelAudio")) {
            AudioCore core = PixelAudioSource.load(plugin);
            if (core != null) return core;
        }
        if (pm.isPluginEnabled("LivingShopkeeperVoice")) {
            AudioCore core = VoiceCoreSource.load(plugin);
            if (core != null) return core;
        }
        return null;
    }

    /** Human-readable line for the startup integration report. */
    public static String describe(JavaPlugin plugin, AudioCore chosen) {
        if (chosen != null) return chosen.name() + " — NPC voice survives PlugMan reloads of this plugin";
        PluginManager pm = plugin.getServer().getPluginManager();
        if (pm.getPlugin("PixelAudio") != null || pm.getPlugin("LivingShopkeeperVoice") != null) {
            return "installed but not enabled — NPCs will be silent";
        }
        return "ABSENT — NPCs will be silent (install PixelAudio.jar)";
    }
}
