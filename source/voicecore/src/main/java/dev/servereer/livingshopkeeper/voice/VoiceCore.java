package dev.servereer.livingshopkeeper.voice;

import org.bukkit.World;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The persistent audio core, published as a Bukkit service by the {@code LivingShopkeeperVoice} plugin.
 *
 * <p>It lives in its own plugin/classloader on purpose: Simple Voice Chat hands its server API over exactly
 * once, via an event at server startup. A plugin that PlugMan-reloads gets a fresh classloader and never
 * receives that API again — which is why hot-reloading the main plugin used to mute the NPCs. By keeping the
 * SVC connection here, in a plugin that is never reloaded, the main {@code LivingShopkeeper} plugin can be
 * PlugMan-reloaded freely and just re-acquires this service; the voice never drops.
 */
public interface VoiceCore {

    /** True once SVC has handed over its server API (false if SVC is absent or its server hasn't started). */
    boolean available();

    /** Whether {@code player} has the Simple Voice Chat mod installed (regardless of whether connected). */
    boolean hasVoiceChat(UUID player);

    /** Whether {@code player} is connected to voice chat and not disabled — i.e. can actually hear NPCs. */
    boolean isVoiceConnected(UUID player);

    /**
     * Start a locational voice stream at {@code (x,y,z)} fed by {@code supplier} (one 960-sample 20 ms mono
     * frame per call, or null to end). {@code onStopped} runs when playback ends. Returns a stop handle, or
     * null if audio can't start (SVC unavailable, bad world, etc.).
     */
    VoiceHandle start(World world, double x, double y, double z, float distance,
                      Supplier<short[]> supplier, Runnable onStopped);
}
