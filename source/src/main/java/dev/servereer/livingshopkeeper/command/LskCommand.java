package dev.servereer.livingshopkeeper.command;

import dev.servereer.livingshopkeeper.LivingShopkeeperPlugin;
import dev.servereer.livingshopkeeper.npc.Character;
import dev.servereer.livingshopkeeper.npc.CharacterManager;
import dev.servereer.livingshopkeeper.npc.CharacterType;
import dev.servereer.livingshopkeeper.npc.ActivityPlace;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code /lsk} command — character CRUD: {@code create|remove|list|tp|skin|reload|version}.
 * {@code create} takes an optional type keyword: {@code /lsk create <id> [barker|informer] [skin]}
 * (defaults to shopkeeper). Barker waypoint/region subcommands arrive with the barker behavior (B4).
 */
public final class LskCommand implements CommandExecutor, TabCompleter {

    private static final Pattern ID_RE = Pattern.compile("[a-zA-Z0-9_-]{1,32}");
    private static final List<String> SUBCOMMANDS =
            List.of("create", "remove", "list", "tp", "skin", "name", "barker", "combat", "lobby", "tag", "action",
                    "space", "away", "back", "persona", "spar", "shopitem", "reload", "version");
    private static final List<String> COMBAT_MODES = List.of("flee", "fightback", "none", "default");
    private static final List<String> LOBBY_OPS = List.of("post", "seatwand", "seats", "clearseats");
    private static final List<String> TAG_OPS = List.of("add", "set", "remove", "clear", "list");
    private static final List<String> ACTION_OPS = List.of("add", "remove", "clear", "list");
    private static final List<String> BARKER_OPS =
            List.of("addwaypoint", "waypoints", "clearwaypoints", "home", "region", "cooldown",
                    "area", "removeplace", "places");
    private static final List<String> SPACE_OPS = List.of("create", "remove", "list", "wand", "tp", "arena");
    private static final List<String> AWAY_OPS = List.of("tag", "space", "minutes");
    private static final Set<String> TYPES = Set.of("shopkeeper", "barker", "informer", "lobby", "shop");

    private final LivingShopkeeperPlugin plugin;

    public LskCommand(LivingShopkeeperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "version" -> sender.sendMessage(msg("LivingShopkeeper v" + plugin.getPluginMeta().getVersion(), NamedTextColor.AQUA));
            case "reload" -> reload(sender);
            case "create" -> create(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "tp" -> tp(sender, args);
            case "skin" -> skin(sender, args);
            case "name" -> name(sender, args);
            case "barker" -> barker(sender, args);
            case "combat" -> combat(sender, args);
            case "lobby" -> lobby(sender, args);
            case "tag" -> tag(sender, args);
            case "action" -> action(sender, args);
            case "space" -> space(sender, args);
            case "away" -> awayCmd(sender, args);
            case "back" -> back(sender, args);
            case "persona" -> persona(sender, args);
            case "spar" -> spar(sender, args);
            case "shopitem" -> shopItem(sender, args);
            default -> sender.sendMessage(msg("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(msg("LivingShopkeeper — voiced NPC characters", NamedTextColor.AQUA));
        s.sendMessage(msg("/lsk create <id> [barker|informer] [skin]  ·  /lsk remove <id>  ·  /lsk list", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk tp <id>  ·  /lsk reload  ·  /lsk version", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk skin <id> <playerName>  |  url <texUrl|hash>  |  value <base64> [signature]", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk name <id> <MiniMessage>   (tag + chat name, e.g. <gold><b>Crier)", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk combat <id> <flee|fightback|none|default>   (reaction to being hit)", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk lobby <id> <post|seatwand|seats|clearseats>   (hub NPC: post + sit spots)", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk tag <id> <add|set|remove|clear|list>   (floating multi-line tag, MiniMessage)", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk action <id> <add|remove|clear|list>   (click actions: player:/cmd, console:, message:, sound:)", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk shopitem <player> [amount]   (item to place a player shop)  ·  /lsk create <id> shop = admin shop", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk barker <id> cooldown <seconds>   (how often it re-greets a player)", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk barker <id> area <name>   (passive dialog area: arrive_/ambient_/interact_ triggers)", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk space <create|wand|list|tp|remove>   (shared hangout areas with slab/stair seats)", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk away <id> [minutes] [space]  ·  /lsk back <id>   (outing now; ghost head minds the post)", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk away <id> tag|space|minutes …   (its off-duty tag + outing defaults)", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk persona <id> <text…>   (character description for the AI chat system)", NamedTextColor.GRAY));
        s.sendMessage(msg("/lsk spar <id> <on|off|region <wgRegion>|clearregion>   (friendly duels, mirrors the player's weapon)", NamedTextColor.GRAY));
    }

    /** /lsk persona <id> <text…|clear> — the in-character description the AI chat system speaks from. */
    private void persona(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 3) {
            s.sendMessage(msg("Usage: /lsk persona <id> <text…>   ('clear' removes; 'show' prints)", NamedTextColor.RED));
            s.sendMessage(msg("e.g. /lsk persona mira a well-travelled trinket trader, warm but always haggling", NamedTextColor.GRAY));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        String text = joinFrom(args, 2);
        if (text.equalsIgnoreCase("clear")) {
            mgr.setPersona(k, null);
            s.sendMessage(msg("Cleared '" + k.getId() + "' persona (AI uses a generic one).", NamedTextColor.GREEN));
        } else if (text.equalsIgnoreCase("show")) {
            s.sendMessage(msg("'" + k.getId() + "' persona: " + (k.persona() == null ? "(none)" : k.persona()), NamedTextColor.WHITE));
        } else {
            mgr.setPersona(k, text);
            s.sendMessage(msg("Set '" + k.getId() + "' persona.", NamedTextColor.GREEN));
        }
    }

    private static final List<String> SPAR_STYLES = List.of("balanced", "aggressive", "defensive");
    private static final List<String> SPAR_STATS = List.of("damage", "arrow-damage", "knockback",
            "attack-reach", "swing-cooldown-ms", "shot-interval-ms", "speed-multiplier",
            "idle-end-seconds", "give-up-distance");

    /** /lsk spar <id> <on|off|region|style|set|info…> — per-NPC weapon-mirroring duels. */
    private void spar(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 3) {
            s.sendMessage(msg("Usage: /lsk spar <id> <on|off|region <wgRegion>|clearregion|style <s>|set <stat> <n|clear>|info>", NamedTextColor.RED));
            s.sendMessage(msg("On: hitting the NPC starts a duel that mirrors your weapon (sword↔sword, bow↔bow…).", NamedTextColor.GRAY));
            s.sendMessage(msg("Each NPC fights with ITS OWN numbers: style balanced|aggressive|defensive + set damage/…", NamedTextColor.GRAY));
            s.sendMessage(msg("Stats: " + String.join(", ", SPAR_STATS) + " (unset = global spar.* config)", NamedTextColor.GRAY));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "style" -> {
                if (args.length < 4 || !SPAR_STYLES.contains(args[3].toLowerCase(Locale.ROOT))) {
                    s.sendMessage(msg("Usage: /lsk spar " + k.getId() + " style <balanced|aggressive|defensive>", NamedTextColor.RED));
                    s.sendMessage(msg("aggressive = faster swings/shots + relentless pressure · defensive = strikes then breaks off, keeps range", NamedTextColor.GRAY));
                    return;
                }
                mgr.setSparStyle(k, args[3]);
                s.sendMessage(msg("'" + k.getId() + "' fighting style: " + args[3].toLowerCase(Locale.ROOT) + ".", NamedTextColor.GREEN));
            }
            case "set" -> {
                if (args.length < 5 || !SPAR_STATS.contains(args[3].toLowerCase(Locale.ROOT))) {
                    s.sendMessage(msg("Usage: /lsk spar " + k.getId() + " set <stat> <number|clear>", NamedTextColor.RED));
                    s.sendMessage(msg("Stats: " + String.join(", ", SPAR_STATS), NamedTextColor.GRAY));
                    return;
                }
                String stat = args[3].toLowerCase(Locale.ROOT);
                if (args[4].equalsIgnoreCase("clear")) {
                    mgr.setSparStat(k, stat, null);
                    s.sendMessage(msg("'" + k.getId() + "' " + stat + " reset to the global config default.", NamedTextColor.GREEN));
                    return;
                }
                try {
                    double v = Double.parseDouble(args[4]);
                    mgr.setSparStat(k, stat, v);
                    s.sendMessage(msg("'" + k.getId() + "' now fights with " + stat + " = " + v + ".", NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    s.sendMessage(msg("Value must be a number (or 'clear').", NamedTextColor.RED));
                }
            }
            case "armor" -> {
                if (args.length < 4) {
                    s.sendMessage(msg("Usage: /lsk spar " + k.getId()
                            + " armor <netherite|diamond|iron|gold|chainmail|leather|none|default>", NamedTextColor.RED));
                    return;
                }
                String tier = args[3].toLowerCase(Locale.ROOT);
                mgr.setSparArmor(k, tier.equals("default") ? null : tier);
                s.sendMessage(msg("'" + k.getId() + "' duels in " + (tier.equals("default")
                        ? "the config default armor" : tier.equals("none") ? "no armor" : tier + " armor") + ".",
                        NamedTextColor.GREEN));
            }
            case "info" -> {
                s.sendMessage(msg("'" + k.getId() + "' spar: " + (k.ai("spar", false) ? "ON" : "off")
                        + " · style " + (k.sparStyle() == null ? "balanced" : k.sparStyle())
                        + " · armor " + (k.sparArmor() == null ? "(config)" : k.sparArmor())
                        + " · region " + (k.sparRegion() == null ? "(anywhere)" : k.sparRegion()), NamedTextColor.AQUA));
                for (String stat : SPAR_STATS) {
                    boolean own = k.sparStat(stat) != null;
                    s.sendMessage(msg("  " + stat + " = " + mgr.effectiveSparStat(k, stat, Double.NaN)
                            + (own ? "  (own)" : "  (config)"), own ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY));
                }
            }
            case "on" -> {
                mgr.setAi(k, "spar", true);
                s.sendMessage(msg("'" + k.getId() + "' now spars — hit it to start a duel"
                        + (k.sparRegion() != null ? " (inside region '" + k.sparRegion() + "')" : "") + ".", NamedTextColor.GREEN));
            }
            case "off" -> {
                mgr.setAi(k, "spar", false);
                s.sendMessage(msg("'" + k.getId() + "' no longer spars (normal hit reaction applies).", NamedTextColor.GREEN));
            }
            case "region" -> {
                if (args.length < 4) {
                    s.sendMessage(msg("Usage: /lsk spar " + k.getId() + " region <wgRegion>", NamedTextColor.RED));
                    return;
                }
                mgr.setSparRegion(k, args[3]);
                s.sendMessage(msg("'" + k.getId() + "' spars only when hit by a player inside '" + args[3] + "'.", NamedTextColor.GREEN));
            }
            case "clearregion" -> {
                mgr.setSparRegion(k, null);
                s.sendMessage(msg("'" + k.getId() + "' spar region cleared — it spars anywhere (when on).", NamedTextColor.GREEN));
            }
            default -> s.sendMessage(msg("Unknown: on | off | region <name> | clearregion | style <s> | set <stat> <n> | info", NamedTextColor.RED));
        }
    }

    private void reload(CommandSender s) {
        if (notAdmin(s)) return;
        CharacterManager mgr = plugin.characters();
        if (mgr != null) mgr.reload();
        else plugin.reloadConfig();
        plugin.reloadDialog();
        plugin.reloadVoice();   // additive — new/edited clips, never cuts a playing line
        s.sendMessage(msg("LivingShopkeeper reloaded (config, characters, dialog, voice).", NamedTextColor.GREEN));
    }

    private void create(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        if (!(s instanceof Player player)) {
            s.sendMessage(msg("Only a player can create a character (it uses your location).", NamedTextColor.RED));
            return;
        }
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 2 || !ID_RE.matcher(args[1]).matches()) {
            s.sendMessage(msg("Usage: /lsk create <id> [barker|informer] [skin]   (id: letters/digits/_/-)", NamedTextColor.RED));
            return;
        }
        String id = args[1];
        if (mgr.exists(id)) {
            s.sendMessage(msg("A character called '" + id + "' already exists.", NamedTextColor.RED));
            return;
        }
        // optional type keyword, then optional skin
        CharacterType type = CharacterType.SHOPKEEPER;
        int i = 2;
        if (args.length > i && TYPES.contains(args[i].toLowerCase(Locale.ROOT))) {
            type = CharacterType.from(args[i]);
            i++;
        }
        String skin = args.length > i ? args[i] : null;
        Character created = mgr.create(id, player.getLocation(), skin, type);
        if (created == null) {
            s.sendMessage(msg("Could not create '" + id + "'.", NamedTextColor.RED));
            return;
        }
        s.sendMessage(msg("Created " + type.id() + " '" + id + "' here"
                + (skin != null ? " (resolving skin '" + skin + "'…)" : "") + ".", NamedTextColor.GREEN));
        if (type == CharacterType.BARKER) {
            s.sendMessage(msg("Barker patrol/region setup lands with the barker behavior — for now it stands at its post.", NamedTextColor.GRAY));
        }
    }

    private void remove(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 2) {
            s.sendMessage(msg("Usage: /lsk remove <id>", NamedTextColor.RED));
            return;
        }
        boolean ok = mgr.remove(args[1]);
        s.sendMessage(ok
                ? msg("Removed character '" + args[1] + "'.", NamedTextColor.GREEN)
                : msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
    }

    private void list(CommandSender s) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (mgr.all().isEmpty()) {
            s.sendMessage(msg("No characters yet. Create one with /lsk create <id>.", NamedTextColor.GRAY));
            return;
        }
        s.sendMessage(msg("Characters (" + mgr.all().size() + "):", NamedTextColor.AQUA));
        for (Character k : mgr.all()) {
            String where = k.getWorld() + " " + (int) k.getX() + "," + (int) k.getY() + "," + (int) k.getZ();
            s.sendMessage(msg(" • " + k.getId() + " §8[" + k.type().id() + "]§7  " + where
                    + "  [skin " + (k.hasSkin() ? "set" : "default") + "]", NamedTextColor.WHITE));
        }
    }

    private void tp(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        if (!(s instanceof Player player)) {
            s.sendMessage(msg("Only a player can teleport.", NamedTextColor.RED));
            return;
        }
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 2) {
            s.sendMessage(msg("Usage: /lsk tp <id>", NamedTextColor.RED));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        Location loc = mgr.location(k);
        if (loc == null) {
            s.sendMessage(msg("That character's world isn't loaded.", NamedTextColor.RED));
            return;
        }
        player.teleport(loc);
        s.sendMessage(msg("Teleported to '" + k.getId() + "'.", NamedTextColor.GREEN));
    }

    private void skin(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 3) {
            s.sendMessage(msg("Usage: /lsk skin <id> <playerName>", NamedTextColor.RED));
            s.sendMessage(msg("   or: /lsk skin <id> url <textures.minecraft.net/… | hash>", NamedTextColor.GRAY));
            s.sendMessage(msg("   or: /lsk skin <id> value <base64> [signature]", NamedTextColor.GRAY));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "value" -> {   // paste a raw texture value (+ optional signature) — fully offline
                if (args.length < 4) {
                    s.sendMessage(msg("Usage: /lsk skin " + k.getId() + " value <base64> [signature]", NamedTextColor.RED));
                    return;
                }
                String value = args[3];
                String signature = args.length > 4 ? args[4] : null;
                if (!looksLikeTexture(value)) {
                    s.sendMessage(msg("That texture value looks incomplete or invalid.", NamedTextColor.RED));
                    s.sendMessage(msg("Note: a full value is ~400 chars but in-game commands cut off at ~256, so it gets", NamedTextColor.GRAY));
                    s.sendMessage(msg("truncated. Use §f/lsk skin " + k.getId() + " url <textures.minecraft.net/…>§7 or a §fplayer name§7 instead.", NamedTextColor.GRAY));
                    return;
                }
                mgr.applySkinValue(k, value, signature);
                s.sendMessage(msg("Applied pasted texture to '" + k.getId() + "'"
                        + (signature == null ? " (unsigned)." : "."), NamedTextColor.GREEN));
            }
            case "url" -> {     // build a value from a textures.minecraft.net URL — fully offline
                if (args.length < 4) {
                    s.sendMessage(msg("Usage: /lsk skin " + k.getId() + " url <textures.minecraft.net/… | hash>", NamedTextColor.RED));
                    return;
                }
                if (mgr.applySkinUrl(k, args[3])) {
                    s.sendMessage(msg("Applied texture URL to '" + k.getId() + "'.", NamedTextColor.GREEN));
                } else {
                    s.sendMessage(msg("The client only loads skins from textures.minecraft.net.", NamedTextColor.RED));
                    s.sendMessage(msg("Upload your image at mineskin.org, then: /lsk skin " + k.getId()
                            + " value <value> [signature]", NamedTextColor.GRAY));
                }
            }
            default -> {        // /lsk skin <id> <playerName> — resolve a live account (cached offline)
                mgr.applySkin(k, args[2]);
                s.sendMessage(msg("Applying skin '" + args[2] + "' to '" + k.getId() + "'…", NamedTextColor.GREEN));
            }
        }
    }

    /**
     * A pasted texture value is only good if it base64-decodes to the full profile JSON — i.e. it contains
     * the {@code SKIN} block AND its {@code url}. A value truncated by the in-game command length limit
     * decodes to JSON that cuts off before the url, and is rejected here.
     */
    private static boolean looksLikeTexture(String v) {
        if (v == null || v.length() < 32) return false;
        try {
            String json = new String(java.util.Base64.getDecoder().decode(v), java.nio.charset.StandardCharsets.UTF_8);
            return json.contains("SKIN") && json.contains("url");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void name(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 3) {
            s.sendMessage(msg("Usage: /lsk name <id> <MiniMessage>   e.g. <gold><b>Crier", NamedTextColor.RED));
            s.sendMessage(msg("Floating tag = plain text + colour of the first tag; chat = the full MiniMessage. 'reset' clears.", NamedTextColor.GRAY));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        String display = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        if (display.equalsIgnoreCase("reset") || display.equalsIgnoreCase("clear")) {
            mgr.setDisplay(k, null);
            s.sendMessage(msg("Reset '" + k.getId() + "' name to default.", NamedTextColor.GREEN));
            return;
        }
        mgr.setDisplay(k, display);
        s.sendMessage(msg("Set '" + k.getId() + "' name to: ", NamedTextColor.GREEN)
                .append(dev.servereer.livingshopkeeper.dialog.DialogText.mini(display)));
    }

    private void barker(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        if (!(s instanceof Player player)) {
            s.sendMessage(msg("Only a player can set up a barker (it uses your location).", NamedTextColor.RED));
            return;
        }
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 3) {
            s.sendMessage(msg("Usage: /lsk barker <id> <addwaypoint|waypoints|clearwaypoints|home|region <name>>", NamedTextColor.RED));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        if (k.type() != CharacterType.BARKER) {
            s.sendMessage(msg("'" + k.getId() + "' is a " + k.type().id() + ", not a barker.", NamedTextColor.RED));
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "addwaypoint" -> {
                mgr.barkerAddWaypoint(k, player.getLocation());
                s.sendMessage(msg("Added waypoint #" + k.waypoints().size() + " for '" + k.getId() + "'.", NamedTextColor.GREEN));
            }
            case "clearwaypoints" -> {
                mgr.barkerClearWaypoints(k);
                s.sendMessage(msg("Cleared waypoints for '" + k.getId() + "'.", NamedTextColor.GREEN));
            }
            case "waypoints" -> {
                if (k.waypoints().isEmpty()) {
                    s.sendMessage(msg("'" + k.getId() + "' has no waypoints — stand somewhere and /lsk barker " + k.getId() + " addwaypoint.", NamedTextColor.GRAY));
                    return;
                }
                s.sendMessage(msg("'" + k.getId() + "' waypoints (" + k.waypoints().size() + "):", NamedTextColor.AQUA));
                int i = 1;
                for (double[] wp : k.waypoints()) {
                    s.sendMessage(msg(" #" + (i++) + "  " + (int) wp[0] + "," + (int) wp[1] + "," + (int) wp[2], NamedTextColor.WHITE));
                }
            }
            case "home" -> {
                mgr.barkerSetHome(k, player.getLocation());
                s.sendMessage(msg("Set '" + k.getId() + "' home to your position.", NamedTextColor.GREEN));
            }
            case "region" -> {
                if (args.length < 4) {
                    s.sendMessage(msg("Usage: /lsk barker " + k.getId() + " region <regionName>", NamedTextColor.RED));
                    return;
                }
                mgr.barkerSetRegion(k, args[3]);
                s.sendMessage(msg("Set '" + k.getId() + "' region to '" + args[3] + "'. §8(used by the invite logic, a later phase)", NamedTextColor.GREEN));
            }
            case "cooldown" -> {
                if (args.length < 4) {
                    s.sendMessage(msg("Usage: /lsk barker " + k.getId() + " cooldown <seconds>", NamedTextColor.RED));
                    return;
                }
                double secs;
                try {
                    secs = Double.parseDouble(args[3]);
                } catch (NumberFormatException e) {
                    s.sendMessage(msg("Seconds must be a number (e.g. 5).", NamedTextColor.RED));
                    return;
                }
                if (secs < 0.5) secs = 0.5;
                mgr.barkerSetCooldown(k, secs);
                s.sendMessage(msg("'" + k.getId() + "' will re-greet each player every " + secs + "s.", NamedTextColor.GREEN));
                s.sendMessage(msg("If it still waits longer, lower the invite node's §fcooldown:§7 in its dialog/ file to match.", NamedTextColor.GRAY));
            }
            case "area" -> {
                if (args.length < 4) {
                    s.sendMessage(msg("Usage: /lsk barker " + k.getId() + " area <name>", NamedTextColor.RED));
                    return;
                }
                mgr.barkerSetPlace(k, args[3], player.getLocation(), 5, false, false, 0, 0);
                s.sendMessage(msg("Saved dialog area '" + args[3] + "'. Dialog triggers: "
                        + "arrive_" + args[3].toLowerCase(Locale.ROOT) + ", ambient_..., interact_...", NamedTextColor.GREEN));
            }
            case "visit", "sit" -> {
                s.sendMessage(msg("Scheduled visits moved to the shared-space system:", NamedTextColor.YELLOW));
                s.sendMessage(msg("  /lsk space create <name> + /lsk space wand <name> (mark slab/stair seats),", NamedTextColor.GRAY));
                s.sendMessage(msg("  then /lsk away " + k.getId() + " [minutes] [space] — or schedule it in config.yml outings.", NamedTextColor.GRAY));
            }
            case "removeplace" -> {
                if (args.length < 4) {
                    s.sendMessage(msg("Usage: /lsk barker " + k.getId() + " removeplace <name>", NamedTextColor.RED));
                    return;
                }
                s.sendMessage(msg(mgr.barkerRemovePlace(k, args[3]) ? "Removed place '" + args[3] + "'." : "No such place.",
                        NamedTextColor.GREEN));
            }
            case "places" -> {
                if (k.places().isEmpty()) { s.sendMessage(msg("No places configured.", NamedTextColor.GRAY)); return; }
                for (ActivityPlace p : k.places()) {
                    s.sendMessage(msg(" " + p.id() + " [" + (p.visit() ? (p.sit() ? "sit" : "visit") : "area")
                            + "] " + (p.visit() ? ActivityPlace.formatWorldTime(p.fromTick()) + "-" + ActivityPlace.formatWorldTime(p.untilTick()) : "passive"),
                            NamedTextColor.WHITE));
                }
            }
            default -> s.sendMessage(msg("Unknown: addwaypoint | waypoints | clearwaypoints | home | region <name> | cooldown <sec>", NamedTextColor.RED));
        }
    }

    /** /lsk combat <id> <flee|fightback|none|default> — what this character does when players hit it. */
    private void combat(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 3 || !COMBAT_MODES.contains(args[2].toLowerCase(Locale.ROOT))) {
            s.sendMessage(msg("Usage: /lsk combat <id> <flee|fightback|none|default>", NamedTextColor.RED));
            s.sendMessage(msg("What the NPC does after being hit combat.react-after-hits times: run away, hit back, or nothing. 'default' follows config.yml.", NamedTextColor.GRAY));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        mgr.setCombatReaction(k, mode.equals("default") ? null : mode);
        s.sendMessage(msg("'" + k.getId() + "' hit reaction: " + mode
                + (mode.equals("default") ? " (follows config.yml combat.reaction)" : "") + ".", NamedTextColor.GREEN));
        s.sendMessage(msg("Dialog triggers: hit_punch · hit_again · hit_weapon · flee · fightback · fight_hit · calm", NamedTextColor.GRAY));
    }

    /** /lsk lobby <id> <post|seatwand|seats|clearseats> — hub-NPC post + wand-marked sit spots. */
    private void lobby(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 3) {
            s.sendMessage(msg("Usage: /lsk lobby <id> <post|seatwand|seats|clearseats>", NamedTextColor.RED));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        if (k.type() != CharacterType.LOBBY) {
            s.sendMessage(msg("'" + k.getId() + "' is a " + k.type().id() + ", not a lobby NPC.", NamedTextColor.RED));
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "post" -> {
                if (!(s instanceof Player player)) {
                    s.sendMessage(msg("Only a player can set the post (it uses your location).", NamedTextColor.RED));
                    return;
                }
                mgr.lobbySetPost(k, player.getLocation());
                s.sendMessage(msg("Set '" + k.getId() + "' post to your position — it runs back here when a player approaches.", NamedTextColor.GREEN));
            }
            case "seatwand" -> {
                if (!(s instanceof Player player)) {
                    s.sendMessage(msg("Only a player can use the seat wand.", NamedTextColor.RED));
                    return;
                }
                plugin.seatWand().bind(player, k.getId());
                org.bukkit.Material wand = plugin.seatWand().wandItem(k);
                player.getInventory().addItem(new org.bukkit.inventory.ItemStack(wand));
                s.sendMessage(msg("Seat wand bound to '" + k.getId() + "': right-click a slab/stair with the "
                        + wand.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                        + " to add a seat, sneak-right-click to remove.", NamedTextColor.GREEN));
            }
            case "seats" -> {
                if (k.seats().isEmpty()) {
                    s.sendMessage(msg("No seats — /lsk lobby " + k.getId() + " seatwand, then right-click slabs/stairs.", NamedTextColor.GRAY));
                    return;
                }
                int i = 1;
                for (double[] seat : k.seats()) {
                    s.sendMessage(msg(" #" + (i++) + "  " + (int) Math.floor(seat[0]) + "," + (int) Math.floor(seat[1])
                            + "," + (int) Math.floor(seat[2]), NamedTextColor.WHITE));
                }
            }
            case "clearseats" -> {
                mgr.lobbyClearSeats(k);
                s.sendMessage(msg("Cleared all seats for '" + k.getId() + "'.", NamedTextColor.GREEN));
            }
            default -> s.sendMessage(msg("Unknown: post | seatwand | seats | clearseats", NamedTextColor.RED));
        }
    }

    /** /lsk tag <id> <add <text>|set <#> <text>|remove <#>|clear|list> — the floating multi-line hologram. */
    private void tag(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 3) {
            s.sendMessage(msg("Usage: /lsk tag <id> <add <text…>|set <line#> <text…>|remove <line#>|clear|list>", NamedTextColor.RED));
            s.sendMessage(msg("MiniMessage per line, e.g. <gradient:#00c6ff:#7b2ff7><b>RANKS</b></gradient>. Replaces the name tag.", NamedTextColor.GRAY));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        List<String> lines = new ArrayList<>(k.tagLines());
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 4) { s.sendMessage(msg("Give the line text.", NamedTextColor.RED)); return; }
                lines.add(joinFrom(args, 3));
                mgr.setTagLines(k, lines);
                s.sendMessage(msg("Added tag line " + lines.size() + " to '" + k.getId() + "'.", NamedTextColor.GREEN));
            }
            case "set" -> {
                Integer n = lineNo(s, args, lines.size());
                if (n == null || args.length < 5) {
                    if (n != null) s.sendMessage(msg("Give the new text.", NamedTextColor.RED));
                    return;
                }
                lines.set(n - 1, joinFrom(args, 4));
                mgr.setTagLines(k, lines);
                s.sendMessage(msg("Set tag line " + n + " of '" + k.getId() + "'.", NamedTextColor.GREEN));
            }
            case "remove" -> {
                Integer n = lineNo(s, args, lines.size());
                if (n == null) return;
                lines.remove(n - 1);
                mgr.setTagLines(k, lines);
                s.sendMessage(msg("Removed tag line " + n + " from '" + k.getId() + "'.", NamedTextColor.GREEN));
            }
            case "clear" -> {
                mgr.setTagLines(k, List.of());
                s.sendMessage(msg("Cleared '" + k.getId() + "' tag — its plain name shows again.", NamedTextColor.GREEN));
            }
            case "list" -> {
                if (lines.isEmpty()) { s.sendMessage(msg("No tag lines (plain name shown).", NamedTextColor.GRAY)); return; }
                for (int i = 0; i < lines.size(); i++) {
                    s.sendMessage(msg(" " + (i + 1) + "  ", NamedTextColor.DARK_GRAY)
                            .append(dev.servereer.livingshopkeeper.dialog.DialogText.mini(lines.get(i))));
                }
            }
            default -> s.sendMessage(msg("Unknown: add | set | remove | clear | list", NamedTextColor.RED));
        }
    }

    /** /lsk action <id> <add <action>|remove <#>|clear|list> — what clicking the NPC does. */
    private void action(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 3) {
            s.sendMessage(msg("Usage: /lsk action <id> <add <action…>|remove <#>|clear|list>", NamedTextColor.RED));
            s.sendMessage(msg("Actions: player:/warps · console:give %player% … · message:<gold>Hi · sound:entity.experience_orb.pickup", NamedTextColor.GRAY));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        List<String> actions = new ArrayList<>(k.actions());
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 4) { s.sendMessage(msg("Give the action, e.g. player:/warps", NamedTextColor.RED)); return; }
                actions.add(joinFrom(args, 3));
                mgr.setActions(k, actions);
                s.sendMessage(msg("Added action " + actions.size() + " to '" + k.getId() + "' — clicks now run it.", NamedTextColor.GREEN));
            }
            case "remove" -> {
                Integer n = lineNo(s, args, actions.size());
                if (n == null) return;
                actions.remove(n - 1);
                mgr.setActions(k, actions);
                s.sendMessage(msg("Removed action " + n + " from '" + k.getId() + "'.", NamedTextColor.GREEN));
            }
            case "clear" -> {
                mgr.setActions(k, List.of());
                s.sendMessage(msg("Cleared actions — clicks fall back to dialog/menu.", NamedTextColor.GREEN));
            }
            case "list" -> {
                if (actions.isEmpty()) { s.sendMessage(msg("No actions (clicks use dialog/menu).", NamedTextColor.GRAY)); return; }
                for (int i = 0; i < actions.size(); i++) {
                    s.sendMessage(msg(" " + (i + 1) + "  " + actions.get(i), NamedTextColor.WHITE));
                }
            }
            default -> s.sendMessage(msg("Unknown: add | remove | clear | list", NamedTextColor.RED));
        }
    }

    /** /lsk space <create|wand|list|tp|remove> — shared hangout areas any NPC can visit on an outing. */
    private void space(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        dev.servereer.livingshopkeeper.npc.SpaceManager spaces = mgr.spaces();
        if (spaces == null) {
            s.sendMessage(msg("Space system unavailable.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            s.sendMessage(msg("Usage: /lsk space <create <id>|wand <id>|list|tp <id>|remove <id>>", NamedTextColor.RED));
            s.sendMessage(msg("A space = a hangout spot NPCs visit on outings. Mark its slab/stair seats with the wand.", NamedTextColor.GRAY));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!(s instanceof Player player)) {
                    s.sendMessage(msg("Only a player can create a space (it uses your location).", NamedTextColor.RED));
                    return;
                }
                if (args.length < 3 || !ID_RE.matcher(args[2]).matches()) {
                    s.sendMessage(msg("Usage: /lsk space create <id>   (id: letters/digits/_/-)", NamedTextColor.RED));
                    return;
                }
                dev.servereer.livingshopkeeper.npc.Space sp = spaces.create(args[2], player.getLocation());
                s.sendMessage(sp == null
                        ? msg("A space called '" + args[2] + "' already exists.", NamedTextColor.RED)
                        : msg("Created space '" + sp.id() + "' here. Now /lsk space wand " + sp.id()
                                + " and right-click slabs/stairs to add its seats.", NamedTextColor.GREEN));
            }
            case "wand" -> {
                if (!(s instanceof Player player)) {
                    s.sendMessage(msg("Only a player can use the seat wand.", NamedTextColor.RED));
                    return;
                }
                if (args.length < 3 || !spaces.exists(args[2])) {
                    s.sendMessage(msg("No space called '" + (args.length < 3 ? "?" : args[2]) + "'.", NamedTextColor.RED));
                    return;
                }
                plugin.seatWand().bindSpace(player, args[2]);
                org.bukkit.Material wand = org.bukkit.Material.matchMaterial(
                        plugin.getConfig().getString("lobby.seat-wand", "BLAZE_ROD").toUpperCase(Locale.ROOT));
                if (wand == null) wand = org.bukkit.Material.BLAZE_ROD;
                player.getInventory().addItem(new org.bukkit.inventory.ItemStack(wand));
                s.sendMessage(msg("Seat wand bound to space '" + args[2].toLowerCase(Locale.ROOT)
                        + "': right-click slabs/stairs to add seats, sneak-right-click to remove.", NamedTextColor.GREEN));
            }
            case "list" -> {
                if (spaces.all().isEmpty()) {
                    s.sendMessage(msg("No spaces yet — /lsk space create <id>.", NamedTextColor.GRAY));
                    return;
                }
                s.sendMessage(msg("Spaces (" + spaces.all().size() + "):", NamedTextColor.AQUA));
                for (dev.servereer.livingshopkeeper.npc.Space sp : spaces.all()) {
                    s.sendMessage(msg(" • " + spaces.describe(sp), NamedTextColor.WHITE));
                }
            }
            case "tp" -> {
                if (!(s instanceof Player player)) {
                    s.sendMessage(msg("Only a player can teleport.", NamedTextColor.RED));
                    return;
                }
                dev.servereer.livingshopkeeper.npc.Space sp = args.length < 3 ? null : spaces.get(args[2]);
                if (sp == null) {
                    s.sendMessage(msg("No such space.", NamedTextColor.RED));
                    return;
                }
                org.bukkit.World w = org.bukkit.Bukkit.getWorld(sp.world());
                if (w == null) {
                    s.sendMessage(msg("That space's world isn't loaded.", NamedTextColor.RED));
                    return;
                }
                player.teleport(new Location(w, sp.x(), sp.y(), sp.z(), sp.yaw(), 0));
                s.sendMessage(msg("Teleported to space '" + sp.id() + "'.", NamedTextColor.GREEN));
            }
            case "remove" -> {
                boolean ok = args.length >= 3 && spaces.remove(args[2]);
                s.sendMessage(ok ? msg("Removed space '" + args[2] + "'.", NamedTextColor.GREEN)
                        : msg("No such space.", NamedTextColor.RED));
            }
            case "arena" -> {
                dev.servereer.livingshopkeeper.npc.Space sp = args.length < 3 ? null : spaces.get(args[2]);
                if (sp == null) {
                    s.sendMessage(msg("Usage: /lsk space arena <id> [on|off]", NamedTextColor.RED));
                    return;
                }
                boolean on = args.length >= 4 ? args[3].equalsIgnoreCase("on") : !sp.arena();
                sp.setArena(on);
                spaces.save();
                s.sendMessage(msg("Space '" + sp.id() + "' is " + (on ? "now an ARENA — spar-enabled visitors "
                        + "look for duels here (outings.arena-visit-chance draws fighters in)."
                        : "no longer an arena."), NamedTextColor.GREEN));
            }
            default -> s.sendMessage(msg("Unknown: create | wand | list | tp | remove | arena", NamedTextColor.RED));
        }
    }

    /** /lsk away <id> [minutes] [space] — send it out now; also its away config (tag/space/minutes). */
    private void awayCmd(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 2) {
            s.sendMessage(msg("Usage: /lsk away <id> [minutes] [space]   — send it to a space now", NamedTextColor.RED));
            s.sendMessage(msg("   or: /lsk away <id> tag <add <text…>|clear|list>   (its off-duty tag while out)", NamedTextColor.GRAY));
            s.sendMessage(msg("   or: /lsk away <id> space <spaceId|any>  ·  /lsk away <id> minutes <n>", NamedTextColor.GRAY));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        String op = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        switch (op) {
            case "tag" -> {
                List<String> lines = new ArrayList<>(k.awayTagLines());
                String tagOp = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "list";
                switch (tagOp) {
                    case "add" -> {
                        if (args.length < 5) {
                            s.sendMessage(msg("Give the line text.", NamedTextColor.RED));
                            return;
                        }
                        lines.add(joinFrom(args, 4));
                        mgr.setAwayTag(k, lines);
                        s.sendMessage(msg("Added away-tag line " + lines.size() + " to '" + k.getId()
                                + "' — shown while it's out.", NamedTextColor.GREEN));
                    }
                    case "clear" -> {
                        mgr.setAwayTag(k, List.of());
                        s.sendMessage(msg("Cleared '" + k.getId() + "' away tag — it keeps its normal tag while out.", NamedTextColor.GREEN));
                    }
                    default -> {
                        if (lines.isEmpty()) {
                            s.sendMessage(msg("No away tag (normal tag stays while out).", NamedTextColor.GRAY));
                            return;
                        }
                        for (int i = 0; i < lines.size(); i++) {
                            s.sendMessage(msg(" " + (i + 1) + "  ", NamedTextColor.DARK_GRAY)
                                    .append(dev.servereer.livingshopkeeper.dialog.DialogText.mini(lines.get(i))));
                        }
                    }
                }
            }
            case "space" -> {
                if (args.length < 4) {
                    s.sendMessage(msg("Usage: /lsk away " + k.getId() + " space <spaceId|any>", NamedTextColor.RED));
                    return;
                }
                mgr.setAwaySpace(k, args[3].equalsIgnoreCase("any") ? null : args[3]);
                s.sendMessage(msg("'" + k.getId() + "' outing space: " + args[3].toLowerCase(Locale.ROOT) + ".", NamedTextColor.GREEN));
            }
            case "minutes" -> {
                if (args.length < 4) {
                    s.sendMessage(msg("Usage: /lsk away " + k.getId() + " minutes <n>", NamedTextColor.RED));
                    return;
                }
                try {
                    double m = Double.parseDouble(args[3]);
                    mgr.setAwayMinutes(k, m <= 0 ? null : m);
                    s.sendMessage(msg("'" + k.getId() + "' default outing length: "
                            + (m <= 0 ? "config default" : m + " min") + ".", NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    s.sendMessage(msg("Minutes must be a number.", NamedTextColor.RED));
                }
            }
            default -> {   // /lsk away <id> [minutes] [space] — go now
                Double minutes = null;
                String spaceId = null;
                if (args.length >= 3) {
                    try {
                        minutes = Double.parseDouble(args[2]);
                    } catch (NumberFormatException e) {
                        spaceId = args[2];   // /lsk away <id> <space>
                    }
                }
                if (args.length >= 4 && spaceId == null) spaceId = args[3];
                String err = mgr.sendAway(k, minutes, spaceId);
                s.sendMessage(err != null ? msg(err, NamedTextColor.RED)
                        : msg("'" + k.getId() + "' is heading out"
                                + (spaceId != null ? " to '" + spaceId + "'" : "")
                                + " — a ghost head minds its post (click it to use the NPC).", NamedTextColor.GREEN));
            }
        }
    }

    /** /lsk back <id> — recall a character from its outing. */
    private void back(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        CharacterManager mgr = mgrOrWarn(s);
        if (mgr == null) return;
        if (args.length < 2) {
            s.sendMessage(msg("Usage: /lsk back <id>", NamedTextColor.RED));
            return;
        }
        Character k = mgr.get(args[1]);
        if (k == null) {
            s.sendMessage(msg("No character called '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        s.sendMessage(mgr.sendBack(k)
                ? msg("'" + k.getId() + "' is heading back to its post.", NamedTextColor.GREEN)
                : msg("'" + k.getId() + "' isn't out.", NamedTextColor.YELLOW));
    }

    /** /lsk shopitem [player] [amount] — hand out the item players use to place their own shop. */
    private void shopItem(CommandSender s, String[] args) {
        if (notAdmin(s)) return;
        Player target = args.length >= 2 ? org.bukkit.Bukkit.getPlayerExact(args[1])
                : (s instanceof Player p ? p : null);
        if (target == null) {
            s.sendMessage(msg(args.length >= 2 ? "Player '" + args[1] + "' is not online."
                    : "Usage: /lsk shopitem <player> [amount]", NamedTextColor.RED));
            return;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                s.sendMessage(msg("Amount must be a number.", NamedTextColor.RED));
                return;
            }
        }
        target.getInventory().addItem(plugin.shopCreation().creationItem(amount));
        s.sendMessage(msg("Gave " + amount + " shop creation item(s) to " + target.getName()
                + ". Flow: right-click own chest → right-click the standing spot.", NamedTextColor.GREEN));
    }

    private static String joinFrom(String[] args, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    /** Parse args[3] as a 1-based line number within {@code size}, messaging on errors. */
    private Integer lineNo(CommandSender s, String[] args, int size) {
        if (args.length < 4) { s.sendMessage(msg("Give the line number.", NamedTextColor.RED)); return null; }
        try {
            int n = Integer.parseInt(args[3]);
            if (n < 1 || n > size) { s.sendMessage(msg("Line must be 1–" + size + ".", NamedTextColor.RED)); return null; }
            return n;
        } catch (NumberFormatException e) {
            s.sendMessage(msg("Line must be a number.", NamedTextColor.RED));
            return null;
        }
    }

    // --- helpers ---

    private CharacterManager mgrOrWarn(CommandSender s) {
        CharacterManager mgr = plugin.characters();
        if (mgr == null) s.sendMessage(msg("Character system unavailable — PacketEvents is not installed.", NamedTextColor.RED));
        return mgr;
    }

    private boolean notAdmin(CommandSender s) {
        if (s.hasPermission("livingnpc.admin")) return false;
        s.sendMessage(msg("You lack livingshopkeeper.admin.", NamedTextColor.RED));
        return true;
    }

    private static Component msg(String text, NamedTextColor color) {
        return Component.text(text, color);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(x -> x.startsWith(p)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && sub.equals("space")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            return SPACE_OPS.stream().filter(x -> x.startsWith(p)).toList();
        }
        if (args.length == 3 && sub.equals("space") && !args[1].equalsIgnoreCase("create") && !args[1].equalsIgnoreCase("list")) {
            CharacterManager mgr = plugin.characters();
            if (mgr != null && mgr.spaces() != null) return mgr.spaces().ids(args[2]);
        }
        if (args.length == 3 && sub.equals("away")) {
            String p = args[2].toLowerCase(Locale.ROOT);
            return AWAY_OPS.stream().filter(x -> x.startsWith(p)).toList();
        }
        if (args.length == 4 && sub.equals("away") && args[2].equalsIgnoreCase("tag"))
            return List.of("add", "clear", "list");
        if (args.length == 4 && sub.equals("away") && args[2].equalsIgnoreCase("space")) {
            CharacterManager mgr = plugin.characters();
            List<String> ids = new ArrayList<>(List.of("any"));
            if (mgr != null && mgr.spaces() != null) ids.addAll(mgr.spaces().ids(args[3]));
            return ids;
        }
        if (args.length == 3 && sub.equals("spar"))
            return List.of("on", "off", "region", "clearregion", "style", "armor", "set", "info");
        if (args.length == 4 && sub.equals("spar") && args[2].equalsIgnoreCase("style"))
            return SPAR_STYLES;
        if (args.length == 4 && sub.equals("spar") && args[2].equalsIgnoreCase("armor"))
            return List.of("netherite", "diamond", "iron", "gold", "chainmail", "leather", "none", "default");
        if (args.length == 4 && sub.equals("spar") && args[2].equalsIgnoreCase("set"))
            return SPAR_STATS;
        if (args.length == 2 && (sub.equals("remove") || sub.equals("tp") || sub.equals("skin") || sub.equals("name")
                || sub.equals("barker") || sub.equals("combat") || sub.equals("lobby") || sub.equals("tag")
                || sub.equals("action") || sub.equals("away") || sub.equals("back")
                || sub.equals("persona") || sub.equals("spar"))) {
            CharacterManager mgr = plugin.characters();
            if (mgr != null) {
                String p = args[1].toLowerCase(Locale.ROOT);
                List<String> ids = new ArrayList<>();
                for (Character k : mgr.all()) {
                    if (k.getId().toLowerCase(Locale.ROOT).startsWith(p)) ids.add(k.getId());
                }
                return ids;
            }
        }
        if (args.length == 3 && sub.equals("create")) return List.of("barker", "informer", "lobby", "shop");
        if (args.length == 3 && sub.equals("barker")) return BARKER_OPS;
        if (args.length == 3 && sub.equals("combat")) return COMBAT_MODES;
        if (args.length == 3 && sub.equals("lobby")) return LOBBY_OPS;
        if (args.length == 3 && sub.equals("tag")) return TAG_OPS;
        if (args.length == 3 && sub.equals("action")) return ACTION_OPS;
        if (args.length == 4 && sub.equals("action") && args[2].equalsIgnoreCase("add"))
            return List.of("player:/", "console:", "message:", "sound:");
        if (args.length == 3 && sub.equals("skin")) return List.of("url", "value");
        return List.of();
    }
}
