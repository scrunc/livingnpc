package dev.servereer.livingshopkeeper.papi;

import dev.servereer.livingshopkeeper.LivingShopkeeperPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * PlaceholderAPI expansion {@code %livingnpc_…%} (also registered as {@code %livingshopkeeper_…%}). Exposes whether a player has Simple Voice Chat, so
 * it can drive scoreboards, tab, menus, or dialog {@code when: {voicechat: …}} conditions.
 *
 * <ul>
 *   <li>{@code %livingnpc_voicechat%}            → {@code connected} · {@code installed} · {@code none}</li>
 *   <li>{@code %livingnpc_has_voicechat%}        → {@code yes} · {@code no}   (mod installed)</li>
 *   <li>{@code %livingnpc_voicechat_connected%}  → {@code yes} · {@code no}   (connected + not disabled)</li>
 * </ul>
 *
 * <p>Only registered when PlaceholderAPI is present (guarded in the plugin), so the {@code me.clip} types
 * are never touched on a server without it.
 */
public final class LskPlaceholders extends PlaceholderExpansion {

    private final LivingShopkeeperPlugin plugin;
    private final String identifier;

    /**
     * @param identifier the placeholder root — {@code livingnpc}, or {@code livingshopkeeper} for the
     *                   legacy copy registered alongside it so pre-rename configs keep resolving.
     */
    public LskPlaceholders(LivingShopkeeperPlugin plugin, String identifier) {
        this.plugin = plugin;
        this.identifier = identifier;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        return "servereer";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;   // survive a PlaceholderAPI reload
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (!(player instanceof Player p)) return "";
        boolean installed = plugin.playerHasVoiceChat(p.getUniqueId());
        boolean connected = plugin.playerVoiceConnected(p.getUniqueId());
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "voicechat", "voicechat_status" -> connected ? "connected" : (installed ? "installed" : "none");
            case "has_voicechat", "voicechat_installed" -> installed ? "yes" : "no";
            case "voicechat_connected" -> connected ? "yes" : "no";
            default -> null;   // unknown placeholder → PAPI leaves it untouched
        };
    }
}
