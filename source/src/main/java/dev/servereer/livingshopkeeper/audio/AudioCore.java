package dev.servereer.livingshopkeeper.audio;

import org.bukkit.World;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * This plugin's own view of an audio core, so the speech layer never names a foreign type.
 *
 * <p>Two cores can satisfy it: the shared {@code PixelAudio} plugin (preferred — the same core
 * KOTH, VideoWall, ArcadeCab and MachineConstruct use) and the older sibling
 * {@code LivingShopkeeperVoice} plugin, kept as a fallback for servers that still run it. Both own
 * the Simple Voice Chat connection in a plugin that is never PlugMan-reloaded, which is the whole
 * point: this plugin can reload freely and just re-acquires the service, and the voice never drops.
 *
 * @see AudioCores#resolve(org.bukkit.plugin.java.JavaPlugin)
 */
public interface AudioCore {

    /** Which core plugin is backing this — for the startup report. */
    String name();

    /** True once SVC has handed its server API to the core (false if SVC is absent or not started). */
    boolean available();

    /** Whether {@code player} has the Simple Voice Chat mod installed, connected or not. */
    boolean hasVoiceChat(UUID player);

    /** Whether {@code player} is connected to voice and not disabled — i.e. can actually hear NPCs. */
    boolean isVoiceConnected(UUID player);

    /**
     * Start a locational stream at {@code (x,y,z)} fed by {@code supplier} (one 960-sample 20 ms mono
     * frame per call, or null to end). {@code onStopped} runs when playback ends. Null if audio can't
     * start.
     */
    Stop start(World world, double x, double y, double z, float distance,
               Supplier<short[]> supplier, Runnable onStopped);

    /** Opaque stop handle for a running stream — callers never touch a core's own types. */
    interface Stop {
        void stop();
    }
}
