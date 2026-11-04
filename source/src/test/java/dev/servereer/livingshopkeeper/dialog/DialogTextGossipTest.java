package dev.servereer.livingshopkeeper.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DialogText.renderGossip} — the gossip pipeline end to end: placeholders, then legacy {@code &} codes,
 * then MiniMessage. Everything here is text-level (no server): the point is that an authored
 * {@code gossip.smalltalk} line comes out exactly as a player would read it.
 */
class DialogTextGossipTest {

    /** The same composition {@code CharacterManager.smalltalk} + {@code broadcastLine} perform per beat. */
    private static Component beat(String poolLine) {
        GossipCtx ctx = ctx();
        String line = GossipPlaceholders.fill(poolLine, ctx);
        String fmt = "<gold><npc></gold> <dark_gray>»</dark_gray> <gray><line></gray>";
        return DialogText.renderGossip(fmt, ctx.saying(line));
    }

    private static GossipCtx ctx() {
        return new GossipCtx(
                GossipCtx.Person.of("rola", "Rola", "blacksmith", "gruff ex-miner", "tired"),
                GossipCtx.Person.of("bert", "Bert", "fishmonger", "chatty", "cheery"),
                GossipCtx.Scene.of("arm_plot_3", "market", 6_000L, false, false, 4,
                        GossipPlaceholders.crowdOf(4, 3, 6)),
                GossipCtx.Talk.stub(),
                GossipCtx.News.of("nobody", List.of("the deep mines flooded again"),
                        List.of("a sailor swears the ocean has a floor")));
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void placeholdersAndMiniMessageAreResolved() {
        assertEquals("Rola » Slow trade today, Bert?",
                plain(beat("Slow trade today, <partner>?")));
        assertEquals("Rola » Slow trade today, Bert?",
                plain(beat("Slow trade today, <listener>?")));
    }

    @Test
    void aTypicalAuthoredLineReadsNaturally() {
        assertEquals("Rola » Afternoon, chatty! Sold 0 goods in this clear?",
                plain(beat("<greeting_word>, <listener_trait>! Sold <sales_today> <last_sold> in this <weather>?")));
        assertEquals("Rola » Heard the deep mines flooded again, Bert. Nobody's duelling right now.",
                plain(beat("Heard <mine_news>, <partner>. Nobody's duelling right now.")));
    }

    @Test
    void legacyAmpersandCodesSurviveAlongsidePlaceholders() {
        Component c = DialogText.renderGossip("&6<speaker> &7to &f<listener>", ctx());
        assertEquals("Rola to Bert", plain(c));
        assertTrue(plain(c).indexOf('&') < 0, "legacy codes must be folded into MiniMessage, never shown");
    }

    @Test
    void unknownTagsStayLiteralAndMarkupStillRenders() {
        assertEquals("<speakerjob> Rola", plain(DialogText.renderGossip("<speakerjob> <speaker>", ctx())));
        assertFalse(plain(DialogText.renderGossip("<bold>By order of <speaker></bold>", ctx())).isEmpty());
    }

    @Test
    void aNullLineRendersEmptyInsteadOfThrowing() {
        assertEquals("", plain(DialogText.renderGossip(null, ctx())));
        assertEquals("Rola", plain(DialogText.renderGossip("<speaker>", ctx())));
    }
}
