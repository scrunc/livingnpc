package dev.servereer.livingshopkeeper.api;

import dev.servereer.livingshopkeeper.LivingShopkeeperPlugin;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public entry point for other plugins (Morphic Games bots): create a skinned {@link Puppet} whose position
 * the caller drives. Callers must check {@code Bukkit.getPluginManager().isPluginEnabled("LivingNPC")} by
 * STRING before touching this class (ADR 0035), so a server without LivingNPC never links it.
 */
public final class Puppets {

    private static final Map<Integer, Puppet> BY_ID = new ConcurrentHashMap<>();
    private static boolean listening;

    private Puppets() {}

    /** True when packets can be sent (PacketEvents present and LivingNPC enabled). */
    public static boolean available() {
        return LivingShopkeeperPlugin.get() != null && Bukkit.getPluginManager().isPluginEnabled("packetevents");
    }

    /**
     * A new puppet (nothing is shown until the first {@link Puppet#sync}). {@code skin} is a Minecraft
     * username or a textures.minecraft.net URL, resolved through the NPC skin cache (disk, online players,
     * then Mojang) — the puppet re-skins itself when it arrives. {@code prefix} is a {@code &}-coloured name
     * tag prefix such as {@code "&7[BOT] "}.
     */
    public static Puppet create(String name, String prefix, NamedTextColor color, String skin) {
        LivingShopkeeperPlugin plugin = LivingShopkeeperPlugin.get();
        if (!listening && plugin != null) {
            Bukkit.getPluginManager().registerEvents(new ViewerListener(), plugin);
            listening = true;
        }
        Puppet p = new Puppet(SpigotReflectionUtil.generateEntityId(), name, prefix, color);
        BY_ID.put(p.entityId, p);
        if (plugin != null && skin != null && !skin.isBlank()) {
            var cache = plugin.skinCache();
            var hit = cache.cached(skin.trim());
            if (hit != null) p.skin(hit.value(), hit.signature());
            else cache.fetchAsync(skin.trim(), (s, err) -> {
                if (s == null) return;                                        // default skin stays
                Bukkit.getScheduler().runTask(plugin, () -> { if (!p.removed()) p.skin(s.value(), s.signature()); });
            });
        }
        return p;
    }

    /** Explicit texture value + signature (a {@code bots.yml} profile that carries its own skin). */
    public static Puppet create(String name, String prefix, NamedTextColor color, String texture, String signature) {
        Puppet p = create(name, prefix, color, (String) null);
        if (texture != null && !texture.isBlank()) p.skin(texture, signature);
        return p;
    }

    /** The puppet behind a client entity id, or null (used by the click listener). */
    public static Puppet byEntityId(int id) { return BY_ID.get(id); }

    static void unregister(Puppet p) { BY_ID.remove(p.entityId); }

    /** Plugin disable: every puppet disappears for every viewer. */
    public static void removeAll() {
        for (Puppet p : new ArrayList<>(BY_ID.values())) p.remove();
        BY_ID.clear();
    }

    /** A client that quit, respawned or changed world has dropped every entity — re-send on the next refresh. */
    private static final class ViewerListener implements Listener {
        private static void forget(UUID id) { for (Puppet p : BY_ID.values()) p.forget(id); }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent e) { forget(e.getPlayer().getUniqueId()); }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorld(PlayerChangedWorldEvent e) { forget(e.getPlayer().getUniqueId()); }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRespawn(PlayerRespawnEvent e) { forget(e.getPlayer().getUniqueId()); }
    }
}
