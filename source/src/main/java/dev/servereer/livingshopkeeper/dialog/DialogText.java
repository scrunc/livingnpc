package dev.servereer.livingshopkeeper.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.World;

/**
 * Renders authored dialog text to a rich {@link Component}: the {@code <player>}/{@code <npc>}/{@code <time>}
 * placeholders are substituted first, legacy {@code &} colour codes are folded into MiniMessage, then the
 * result is parsed as <b>MiniMessage</b> — so authors get colours, formatting, hover, and clickable links
 * ({@code <click:open_url:'https://…'>}) straight from YAML, and can mix in familiar {@code &a}/{@code &l}
 * codes and {@code &#ff8800} hex without knowing MiniMessage tag syntax.
 *
 * <p>Placeholders are replaced before parsing (player/NPC names are safe alphanumerics), so they can't
 * collide with MiniMessage's own {@code <…>} tag syntax. Malformed markup is shown literally rather than
 * throwing — a bad tag never breaks a line.
 */
public final class DialogText {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private DialogText() {}

    public static Component render(String text, String playerName, String npcName, World world) {
        if (text == null) return Component.empty();
        String filled = text
                .replace("<player>", playerName == null ? "" : playerName)
                .replace("<npc>", npcName == null ? "" : npcName)
                .replace("<time>", timeOfDay(world));
        try {
            return MM.deserialize(legacyToMini(filled));
        } catch (Exception e) {
            return Component.text(filled);   // unparseable markup → show as-is, never break the line
        }
    }

    /**
     * Render an NPC↔NPC gossip line: the {@link GossipPlaceholders} registry is substituted first (see that
     * class for the tag list and the fail-open rules), then the text goes through the exact same pipeline as
     * {@link #render} — legacy {@code &} codes folded into MiniMessage, parsed, and shown literally if the
     * author's markup is malformed. Used for {@code gossip.smalltalk} lines and {@code gossip.smalltalk-format}.
     */
    public static Component renderGossip(String raw, GossipCtx ctx) {
        return renderGossip(raw, ctx, null);
    }

    /** As {@link #renderGossip(String, GossipCtx)}, reporting unknown tags through {@code warnOnce}. */
    public static Component renderGossip(String raw, GossipCtx ctx, java.util.Set<String> warnOnce) {
        String filled = GossipPlaceholders.fill(raw, ctx, warnOnce);
        try {
            return MM.deserialize(legacyToMini(filled));
        } catch (Exception e) {
            return Component.text(filled);   // unparseable markup → show as-is, never break the line
        }
    }

    /** Parse a MiniMessage string with no placeholder substitution (used for NPC names). Never throws. */
    public static Component mini(String text) {
        if (text == null) return Component.empty();
        try {
            return MM.deserialize(legacyToMini(text));
        } catch (Exception e) {
            return Component.text(text);
        }
    }

    /**
     * Fold legacy Bukkit colour codes into MiniMessage tags so both styles can be mixed freely:
     * {@code &#rrggbb} → {@code <#rrggbb>}, {@code &a} → {@code <green>}, {@code &l} → {@code <bold>},
     * {@code &r} → {@code <reset>}, and so on. Accepts {@code &} or {@code §} as the escape char and is
     * case-insensitive. A lone {@code &} not followed by a known code is left untouched.
     */
    static String legacyToMini(String s) {
        if (s == null || (s.indexOf('&') < 0 && s.indexOf('§') < 0)) return s;
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch == '&' || ch == '§') && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '#' && i + 7 < s.length() && isHex6(s, i + 2)) {   // &#rrggbb
                    out.append("<#").append(s, i + 2, i + 8).append('>');
                    i += 7;
                    continue;
                }
                String tag = legacyTag(Character.toLowerCase(n));
                if (tag != null) {
                    out.append(tag);
                    i++;                       // consume the code char
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    /**
     * Scrub MiniMessage tags a shop owner must NOT be able to put in player-typed text — click/command
     * execution, chat insertions, and hovers — while keeping every colour/format tag ({@code <gold>},
     * {@code <bold>}, {@code <gradient>}, {@code &} codes, …). Without this an owner could set a nametag
     * or dialogue line like {@code <click:run_command:'/pay them 1000'>} that fires as whoever clicks it.
     * Case-insensitive; run on the raw user string at capture time, before it's ever stored or rendered.
     */
    public static String sanitizeUserInput(String s) {
        if (s == null) return null;
        return s.replaceAll("(?i)<\\s*/?\\s*click\\b[^>]*>", "")
                .replaceAll("(?i)<\\s*/?\\s*insertion\\b[^>]*>", "")
                .replaceAll("(?i)<\\s*/?\\s*hover\\b[^>]*>", "");
    }

    private static boolean isHex6(String s, int from) {
        for (int i = from; i < from + 6; i++) {
            char c = s.charAt(i);
            if (Character.digit(c, 16) < 0) return false;
        }
        return true;
    }

    private static String legacyTag(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }

    /** An NPC name rendered from MiniMessage, defaulting to {@code def} colour when the author set none. */
    public static Component name(String text, TextColor def) {
        return mini(text).colorIfAbsent(def);
    }

    private static String timeOfDay(World w) {
        return switch (w == null ? "day" : Conditions.timeBucket(w.getTime())) {
            case "dusk" -> "evening";
            case "night" -> "night";
            case "dawn" -> "morning";
            default -> "day";
        };
    }
}
