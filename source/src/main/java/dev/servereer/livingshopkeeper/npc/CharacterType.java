package dev.servereer.livingshopkeeper.npc;

import java.util.Locale;

/**
 * The kind of NPC a {@link Character} is — decides which behavior the {@link CharacterManager} runs.
 * <ul>
 *   <li>{@link #SHOPKEEPER} — stationary; right-click → dialog/shop (P1–P3).</li>
 *   <li>{@link #BARKER} — patrols a region's waypoints and lures passers-by in (movement + invite FSM).</li>
 *   <li>{@link #INFORMER} — stationary info-giver (later).</li>
 *   <li>{@link #LOBBY} — hub NPC at a post: roams/sits nearby when idle, runs back when a player
 *       approaches its post, and clicking it (or its floating tag) fires its {@code actions}.</li>
 *   <li>{@link #SHOP} — a trading shop (the plugin's namesake): right-click opens a vanilla merchant
 *       window; player-owned shops are stocked from a linked chest, admin shops trade infinitely.
 *       Lives the lobby life around its post.</li>
 * </ul>
 */
public enum CharacterType {
    SHOPKEEPER,
    BARKER,
    INFORMER,
    LOBBY,
    SHOP;

    /** Lenient parse; unknown/blank → {@link #SHOPKEEPER}. */
    public static CharacterType from(String s) {
        if (s == null || s.isBlank()) return SHOPKEEPER;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SHOPKEEPER;
        }
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
