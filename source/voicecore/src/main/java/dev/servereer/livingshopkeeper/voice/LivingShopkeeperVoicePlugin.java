package dev.servereer.livingshopkeeper.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import org.bukkit.World;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The audio-core plugin. Registers with Simple Voice Chat (holding the {@link VoicechatServerApi} handed
 * over at server start) and publishes itself as the {@link VoiceCore} Bukkit service. Because this plugin is
 * never PlugMan-reloaded, the SVC connection and this service survive reloads of the main plugin — so NPC
 * voice keeps working across hot reloads.
 */
public final class LivingShopkeeperVoicePlugin extends JavaPlugin implements VoiceCore {

    private volatile VoicechatServerApi api;

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("voicechat") == null) {
            getLogger().warning("Simple Voice Chat not installed — LivingShopkeeper NPCs will be silent.");
        } else {
            BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
            if (service != null) {
                service.registerPlugin(new Hook());
                getLogger().info("Audio core registered with Simple Voice Chat.");
            } else {
                getLogger().warning("Simple Voice Chat service unavailable — NPCs will be silent.");
            }
        }
        // Publish the core so the main plugin (and every PlugMan reload of it) can find it.
        getServer().getServicesManager().register(VoiceCore.class, this, this, ServicePriority.Normal);
        getLogger().info("LivingShopkeeper audio core enabled — main plugin can now hot-reload without muting voice.");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override
    public boolean available() {
        return api != null;
    }

    @Override
    public boolean hasVoiceChat(UUID player) {
        VoicechatServerApi a = api;
        if (a == null || player == null) return false;
        try {
            VoicechatConnection c = a.getConnectionOf(player);
            return c != null && c.isInstalled();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isVoiceConnected(UUID player) {
        VoicechatServerApi a = api;
        if (a == null || player == null) return false;
        try {
            VoicechatConnection c = a.getConnectionOf(player);
            return c != null && c.isConnected() && !c.isDisabled();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public VoiceHandle start(World world, double x, double y, double z, float distance,
                             Supplier<short[]> supplier, Runnable onStopped) {
        VoicechatServerApi a = api;
        if (a == null || world == null) return null;
        try {
            ServerLevel level = a.fromServerLevel(world);
            LocationalAudioChannel channel = a.createLocationalAudioChannel(UUID.randomUUID(), level, a.createPosition(x, y, z));
            if (channel == null) return null;
            channel.setDistance(distance);
            AudioPlayer player = a.createAudioPlayer(channel, a.createEncoder(), supplier);
            if (onStopped != null) player.setOnStopped(onStopped);
            player.startPlaying();
            return new PlayerHandle(player);
        } catch (Throwable t) {
            getLogger().warning("Could not start shopkeeper voice: " + t);
            return null;
        }
    }

    /** Wraps SVC's AudioPlayer so callers stay free of SVC types. */
    private record PlayerHandle(AudioPlayer player) implements VoiceHandle {
        @Override
        public void stop() {
            try {
                if (player.isPlaying()) player.stopPlaying();
            } catch (Throwable ignored) {
            }
        }
    }

    private final class Hook implements VoicechatPlugin {
        @Override
        public String getPluginId() {
            return "livingshopkeeper";
        }

        @Override
        public void initialize(VoicechatApi a) {
            // the server API arrives via the started event below
        }

        @Override
        public void registerEvents(EventRegistration registration) {
            registration.registerEvent(VoicechatServerStartedEvent.class, e -> api = e.getVoicechat());
        }
    }
}
