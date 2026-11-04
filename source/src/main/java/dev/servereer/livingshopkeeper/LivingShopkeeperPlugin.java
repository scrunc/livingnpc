package dev.servereer.livingshopkeeper;

import com.github.retrooper.packetevents.PacketEvents;
import dev.servereer.livingshopkeeper.audio.AudioCore;
import dev.servereer.livingshopkeeper.audio.AudioCores;
import dev.servereer.livingshopkeeper.audio.SpeechController;
import dev.servereer.livingshopkeeper.audio.VoiceBank;
import dev.servereer.livingshopkeeper.command.LskCommand;
import dev.servereer.livingshopkeeper.dialog.Conditions;
import dev.servereer.livingshopkeeper.dialog.DialogEngine;
import dev.servereer.livingshopkeeper.dialog.DialogLibrary;
import dev.servereer.livingshopkeeper.gui.DialogMenuGui;
import dev.servereer.livingshopkeeper.npc.NpcInteractListener;
import dev.servereer.livingshopkeeper.npc.CharacterManager;
import dev.servereer.livingshopkeeper.npc.SeatWandListener;
import dev.servereer.livingshopkeeper.npc.SkinCache;
import dev.servereer.livingshopkeeper.papi.LskPlaceholders;

import java.util.UUID;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * P1 bootstrap. Wires the packet-NPC subsystem: a {@link SkinCache}, the {@link CharacterManager}
 * (load defs, render/track NPCs, look-at), and the {@link NpcInteractListener} (right-click → manager).
 * PacketEvents is a hard depend from P1 on, but a defensive check keeps the plugin enabling (commands
 * still respond) even if it's somehow absent. Simple Voice Chat / Vault / PAPI remain soft — later phases.
 */
public final class LivingShopkeeperPlugin extends JavaPlugin {

    private static LivingShopkeeperPlugin instance;

    private SkinCache skinCache;
    private AudioCore audioCore;   // the persistent audio-core service (null if no core plugin is installed)
    private VoiceBank voiceBank;
    private SpeechController speech;
    private DialogLibrary dialogLibrary;
    private DialogEngine dialogEngine;
    private DialogMenuGui menuGui;
    private CharacterManager characterManager;
    private NpcInteractListener interactListener;
    private SeatWandListener seatWand;
    private dev.servereer.livingshopkeeper.shop.ShopCreationListener shopCreation;

    @Override
    public void onEnable() {
        instance = this;
        migrateLegacyDataFolder();
        saveDefaultConfig();

        this.skinCache = new SkinCache(this);

        // Voice (P2). The SVC connection lives in a separate never-reloaded core plugin — PixelAudio by
        // preference (the shared one KOTH/VideoWall/ArcadeCab/MachineConstruct also use), falling back to the
        // older LivingShopkeeperVoice sibling. Grabbing a service here — instead of registering with SVC
        // ourselves — is what lets THIS plugin be PlugMan-reloaded without muting NPCs: the core (and its SVC
        // API) stays put and we simply re-acquire the service on each enable.
        this.audioCore = AudioCores.resolve(this);
        if (audioCore == null) {
            getLogger().warning("No audio core (PixelAudio or LivingShopkeeperVoice) found — NPCs will be silent.");
        } else {
            getLogger().info("Audio core: " + audioCore.name() + ".");
        }
        // Let dialog `when: {voicechat: …}` gates query SVC through the core, without the dialog layer
        // depending on it. Re-installed on every enable, so a PlugMan reload keeps them wired.
        Conditions.VOICE_INSTALLED = p -> playerHasVoiceChat(p.getUniqueId());
        Conditions.VOICE_CONNECTED = p -> playerVoiceConnected(p.getUniqueId());
        this.voiceBank = new VoiceBank(this);
        voiceBank.preloadAsync();
        this.speech = new SpeechController(this, audioCore, voiceBank, (float) getConfig().getDouble("voice.distance", 16));

        // Dialog (P3): the YAML bank + the engine that picks a contextual line per trigger.
        this.dialogLibrary = new DialogLibrary(this);
        dialogLibrary.load();
        this.dialogEngine = new DialogEngine(this, speech, dialogLibrary);
        this.menuGui = new DialogMenuGui(this, speech, dialogLibrary);
        getServer().getPluginManager().registerEvents(menuGui, this);

        this.characterManager = new CharacterManager(this, skinCache, speech, dialogEngine, menuGui);
        characterManager.wireSpaces(new dev.servereer.livingshopkeeper.npc.SpaceManager(this));
        characterManager.wireAiChat(new dev.servereer.livingshopkeeper.ai.AiChatService(this));
        this.seatWand = new SeatWandListener(this, characterManager);
        getServer().getPluginManager().registerEvents(seatWand, this);

        // The shop subsystem (P4): grid-menu trading + chest-backed stock + the owner GUI suite.
        var shopEngine = new dev.servereer.livingshopkeeper.shop.ShopEngine();
        var shopSettings = new dev.servereer.livingshopkeeper.shop.ShopSettings(this, characterManager);
        var shopGuis = new dev.servereer.livingshopkeeper.shop.ShopGuis(
                this, characterManager, shopEngine, seatWand, shopSettings);
        characterManager.wireShop(shopEngine, shopGuis);
        shopSettings.wireGuis(shopGuis);   // lets a chat-added tag/dialogue line reopen its editor
        getServer().getPluginManager().registerEvents(shopGuis, this);
        getServer().getPluginManager().registerEvents(shopSettings, this);
        this.shopCreation = new dev.servereer.livingshopkeeper.shop.ShopCreationListener(this, characterManager);
        getServer().getPluginManager().registerEvents(shopCreation, this);

        if (getServer().getPluginManager().getPlugin("packetevents") != null) {
            interactListener = new NpcInteractListener(this, characterManager);
            // PacketListenerAbstract carries its own priority (set in its constructor), so it registers
            // through the single-arg PacketListenerCommon overload.
            PacketEvents.getAPI().getEventManager().registerListener(interactListener);
            characterManager.load();
        } else {
            getLogger().severe("PacketEvents not found — NPCs cannot render; /livingnpc create is disabled.");
        }

        PluginCommand cmd = getCommand("livingnpc");
        if (cmd != null) {
            LskCommand handler = new LskCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        } else {
            getLogger().warning("Command 'lsk' missing from plugin.yml — did resource filtering run?");
        }

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new LskPlaceholders(this, "livingnpc").register();
            // The pre-rename root, registered alongside it so %livingshopkeeper_…% in existing menus,
            // scoreboards and dialog conditions keeps resolving.
            new LskPlaceholders(this, "livingshopkeeper").register();
            getLogger().info("Registered PlaceholderAPI expansions '%livingnpc_…%' and '%livingshopkeeper_…%' (voicechat status).");
        }

        logIntegrations();
        getLogger().info("LivingNPC v" + getPluginMeta().getVersion() + " enabled (B0: character types + folders).");
    }

    /** Whether {@code id} has the Simple Voice Chat mod (via the audio core). False if the core is absent. */
    public boolean playerHasVoiceChat(UUID id) {
        return audioCore != null && audioCore.hasVoiceChat(id);
    }

    /** Whether {@code id} is connected to voice chat (can hear NPCs). False if the core is absent. */
    public boolean playerVoiceConnected(UUID id) {
        return audioCore != null && audioCore.isVoiceConnected(id);
    }

    /** Reload the dialog packs from disk (used by /livingnpc reload). */
    public void reloadDialog() {
        if (dialogLibrary != null) dialogLibrary.load();
    }

    /**
     * Additively refresh the voice bank from disk (used by /lsk reload) — picks up newly-added or edited
     * clips without dropping cached ones and without touching the SVC channel, so a reload never cuts a
     * line that's currently playing. Changing {@code voice.distance} still needs a restart.
     */
    public void reloadVoice() {
        if (voiceBank != null) voiceBank.refreshAsync();
    }

    @Override
    public void onDisable() {
        if (speech != null) speech.stopAll();
        try { dev.servereer.livingshopkeeper.api.Puppets.removeAll(); } catch (Throwable ignored) { }
        if (characterManager != null) characterManager.shutdown();
        if (interactListener != null && getServer().getPluginManager().getPlugin("packetevents") != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(interactListener);
            } catch (Throwable ignored) {
            }
        }
        if (skinCache != null) skinCache.shutdown();
        getLogger().info("LivingNPC disabled.");
    }

    public static LivingShopkeeperPlugin get() {
        return instance;
    }

    /** The shared skin cache (also used by {@link dev.servereer.livingshopkeeper.api.Puppets}). */
    public SkinCache skinCache() {
        return skinCache;
    }

    /** The lobby seat wand (bind an admin's wand clicks to an NPC). */
    public SeatWandListener seatWand() {
        return seatWand;
    }

    /** The player-shop creation flow (builds the tagged creation item for /lsk shopitem). */
    public dev.servereer.livingshopkeeper.shop.ShopCreationListener shopCreation() {
        return shopCreation;
    }

    /** May be null only if PacketEvents was absent at enable. */
    public CharacterManager characters() {
        return getServer().getPluginManager().getPlugin("packetevents") != null ? characterManager : null;
    }

    /**
     * Carry a pre-rename {@code plugins/LivingShopkeeper} folder over to {@code plugins/LivingNPC}.
     *
     * <p>Renaming the plugin renames its data folder with it, which would silently orphan every
     * character, dialog pack, voice clip and shop on a server that ran the old name. Copies rather than
     * moves, so the old folder stays as its own backup, and only ever runs when the new folder does not
     * exist yet — so it cannot overwrite live data on any later start.
     */
    private void migrateLegacyDataFolder() {
        java.io.File target = getDataFolder();
        if (target.exists()) return;
        java.io.File legacy = new java.io.File(target.getParentFile(), "LivingShopkeeper");
        if (!legacy.isDirectory()) return;
        try {
            java.nio.file.Path from = legacy.toPath();
            java.nio.file.Path to = target.toPath();
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(from)) {
                for (java.nio.file.Path src : walk.toList()) {
                    java.nio.file.Path dst = to.resolve(from.relativize(src).toString());
                    if (java.nio.file.Files.isDirectory(src)) java.nio.file.Files.createDirectories(dst);
                    else {
                        java.nio.file.Files.createDirectories(dst.getParent());
                        java.nio.file.Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            getLogger().info("Migrated plugins/LivingShopkeeper -> plugins/" + target.getName()
                    + " (the old folder is left in place as a backup).");
        } catch (Exception e) {
            getLogger().severe("Could not migrate the old LivingShopkeeper data folder: " + e
                    + " — copy it to plugins/" + target.getName() + " by hand before starting again.");
        }
    }

    /** Report which optional integrations are present. */
    private void logIntegrations() {
        PluginManager pm = getServer().getPluginManager();
        getLogger().info("  PacketEvents: " + (pm.getPlugin("packetevents") != null
                ? "present (NPC bodies enabled)" : "ABSENT — required for shopkeepers"));
        getLogger().info("  Audio core: " + AudioCores.describe(this, audioCore));
        getLogger().info("  Simple Voice Chat: " + (pm.getPlugin("voicechat") != null
                ? "present" : "absent — NPCs will be silent"));
        getLogger().info("  Vault: " + (pm.getPlugin("Vault") != null
                ? "present" : "absent — required for shop economy at P4"));
    }
}
