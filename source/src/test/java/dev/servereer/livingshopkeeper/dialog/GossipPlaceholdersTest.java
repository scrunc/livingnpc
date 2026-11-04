package dev.servereer.livingshopkeeper.dialog;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gossip placeholder registry, tested without a server: {@link GossipCtx} is plain data, so every rule in
 * {@link GossipPlaceholders} (all tags resolve · unknown tags fail open · longest tag wins · values sanitised ·
 * caps applied) is checked against a hand-built context.
 */
class GossipPlaceholdersTest {

    /** World clock 12:00 on day 0 — noon, day, clear weather, nobody around. */
    private static final long NOON_TICKS = 6_000L;

    private static final GossipCtx.Person SPEAKER =
            GossipCtx.Person.of("rola", "Rola", "blacksmith", "gruff ex-miner", "tired");
    private static final GossipCtx.Person LISTENER =
            GossipCtx.Person.of("bert", "Bert", "fishmonger", "chatty", "cheery");

    /** The 30 gossip tags (the two legacy aliases included) plus the structural {@code <line>}. */
    private static final List<String> CATALOGUE = List.of(
            "speaker", "listener", "partner", "speaker_job", "listener_job", "speaker_trait", "listener_trait",
            "time", "daypart", "day", "weather", "space", "crowd", "players_near", "stall",
            "topic", "last_line", "beat", "relationship", "greeting_word",
            "sales_today", "last_sold", "last_buyer", "stock_low", "mine_news", "traveler_tale",
            "duel_winner", "mood_speaker", "mood_listener", "npc");

    private static GossipCtx base() {
        return base(NOON_TICKS, false, false, 0, GossipCtx.Talk.stub());
    }

    private static GossipCtx base(long fullTime, boolean thundering, boolean raining, int playersNear,
                                  GossipCtx.Talk talk) {
        return new GossipCtx(SPEAKER, LISTENER,
                GossipCtx.Scene.of("arm_plot_3", "market", fullTime, thundering, raining, playersNear,
                        GossipPlaceholders.crowdOf(playersNear, 3, 6)),
                talk,
                GossipCtx.News.of("nobody", List.of("the deep mines flooded again"),
                        List.of("a sailor swears the ocean has a floor")));
    }

    private static GossipCtx with(GossipCtx.Person speaker, GossipCtx.Person listener) {
        GossipCtx b = base();
        return new GossipCtx(speaker, listener, b.scene(), b.talk(), b.news());
    }

    private static GossipCtx withTalk(GossipCtx.Talk talk) {
        GossipCtx b = base();
        return new GossipCtx(b.speaker(), b.listener(), b.scene(), talk, b.news());
    }

    private static GossipCtx withNews(GossipCtx.News news) {
        GossipCtx b = base();
        return new GossipCtx(b.speaker(), b.listener(), b.scene(), b.talk(), news);
    }

    @Test
    void everyCataloguedTagResolves() {
        Set<String> warned = new HashSet<>();
        for (String key : CATALOGUE) {
            String filled = GossipPlaceholders.fill("<" + key + ">", base(), warned);
            assertFalse(filled.contains("<" + key + ">"),
                    "<" + key + "> should have been substituted, got: " + filled);
        }
        assertTrue(warned.isEmpty(), "no catalogued tag may be reported as unknown, warned: " + warned);
    }

    @Test
    void catalogueIsThirtyGossipTagsPlusTheStructuralLine() {
        assertEquals(31, GossipPlaceholders.keys().size());
        assertTrue(GossipPlaceholders.keys().containsAll(CATALOGUE), "catalogue drifted from the registry");
        assertTrue(GossipPlaceholders.keys().contains("line"));
    }

    @Test
    void realValuesAreSubstituted() {
        assertEquals("Rola · Bert · blacksmith · fishmonger · gruff · chatty",
                GossipPlaceholders.fill("<speaker> · <listener> · <speaker_job> · <listener_job> · "
                        + "<speaker_trait> · <listener_trait>", base()));
        assertEquals("Bert · Rola", GossipPlaceholders.fill("<partner> · <npc>", base()));
        assertEquals("tired · cheery", GossipPlaceholders.fill("<mood_speaker> · <mood_listener>", base()));
        assertEquals("1st exchange", GossipPlaceholders.fill("<beat>", base()));
        assertEquals("neighbors", GossipPlaceholders.fill("<relationship>", base()));
        assertEquals("smalltalk", GossipPlaceholders.fill("<topic>", base()));
    }

    @Test
    void longestTagWins() {
        assertEquals("blacksmith vs Rola",
                GossipPlaceholders.fill("<speaker_job> vs <speaker>", base()));
    }

    @Test
    void unknownTagsFailOpenAndWarnOnlyOnce() {
        Set<String> warned = new HashSet<>();
        String filled = GossipPlaceholders.fill("<speakerjob> and <speaker>", base(), warned);
        assertEquals("<speakerjob> and Rola", filled, "an unknown tag must be left exactly as written");
        assertEquals(Set.of("speakerjob"), warned);
        GossipPlaceholders.fill("<speakerjob> again", base(), warned);
        assertEquals(1, warned.size(), "the same unknown key must not be reported twice");
    }

    @Test
    void miniMessageAndPapiTagsAreNotMistakenForPlaceholders() {
        Set<String> warned = new HashSet<>();
        String raw = "<gold><bold>hi</bold></gold> <gradient:red:blue>x</gradient> <#ff8800>y &l z";
        assertEquals(raw, GossipPlaceholders.fill(raw, base(), warned));
        assertTrue(warned.isEmpty(), "<gold>/<bold> are MiniMessage passthrough, never 'unknown': " + warned);
        assertEquals("%player_name% is fine",
                GossipPlaceholders.fill("%player_name% is fine", base(), warned));
    }

    @Test
    void substitutedValuesAreScrubbedOfMarkup() {
        GossipCtx ctx = with(GossipCtx.Person.of("x", "<red>Evil%player_name%", "<click:run_command:'/op me'>liar",
                "<bold>grim", "content"), LISTENER);
        String filled = GossipPlaceholders.fill("<speaker>|<speaker_job>|<speaker_trait>", ctx);
        assertEquals("Evil|liar|grim", filled, "values must not smuggle MiniMessage or PAPI into a line");
    }

    @Test
    void namesAndLastLineAreCapped() {
        String longName = "A".repeat(40);
        GossipCtx ctx = with(GossipCtx.Person.of("x", longName, null, null, null), LISTENER);
        String name = GossipPlaceholders.fill("<speaker>", ctx);
        assertEquals(24, name.length());
        assertTrue(name.endsWith("…"), "a cut must be visible: " + name);

        String longLine = "b".repeat(200);
        String lastLine = GossipPlaceholders.fill("<last_line>",
                withTalk(new GossipCtx.Talk("smalltalk", longLine, "neighbors", 1, "")));
        assertEquals(60, lastLine.length());
        assertTrue(lastLine.endsWith("…"));

        // a cut may never leave half a tag behind: values are scrubbed *before* the cap is applied
        String capped = GossipPlaceholders.fill("<last_line>",
                withTalk(new GossipCtx.Talk("smalltalk", "w".repeat(30) + "<red>" + "w".repeat(100),
                        "neighbors", 1, "")));
        assertEquals(60, capped.length());
        assertFalse(capped.contains("<"), "no markup may survive the cap: " + capped);
    }

    @Test
    void missingFlavourFallsBackToDocumentedDefaults() {
        GossipCtx bare = with(GossipCtx.Person.of("x", null, null, null, null),
                GossipCtx.Person.of("y", "  ", "", "   ", null));
        assertEquals("someone trader plain content | someone trader plain content",
                GossipPlaceholders.fill("<speaker> <speaker_job> <speaker_trait> <mood_speaker> | "
                        + "<listener> <listener_job> <listener_trait> <mood_listener>", bare));
    }

    @Test
    void timeDaypartAndGreetingFollowTheWorldClock() {
        // <daypart> | <time> (the legacy wording) | <greeting_word>
        assertEquals("morning|day|Morning", dayWords(1_000));
        assertEquals("noon|day|Afternoon", dayWords(6_000));
        assertEquals("dusk|evening|Afternoon", dayWords(13_000));
        assertEquals("midnight|night|Evening", dayWords(20_000));
        assertEquals("midnight|morning|Morning", dayWords(23_000));
    }

    private static String dayWords(long ticks) {
        return GossipPlaceholders.fill("<daypart>|<time>|<greeting_word>",
                base(ticks, false, false, 0, GossipCtx.Talk.stub()));
    }

    @Test
    void dayCounterAndWeatherComeFromTheWorld() {
        GossipCtx ctx = base(24_000L * 7 + NOON_TICKS, false, false, 0, GossipCtx.Talk.stub());
        assertEquals("7", GossipPlaceholders.fill("<day>", ctx));
        assertEquals("clear", GossipPlaceholders.fill("<weather>", ctx));
        assertEquals("rain", GossipPlaceholders.fill("<weather>",
                base(NOON_TICKS, false, true, 0, GossipCtx.Talk.stub())));
        assertEquals("thunder", GossipPlaceholders.fill("<weather>",
                base(NOON_TICKS, true, true, 0, GossipCtx.Talk.stub())));
    }

    @Test
    void crowdReadsTheNearbyPlayerCount() {
        assertEquals("quiet", GossipPlaceholders.crowdOf(0, GossipPlaceholders.DEFAULT_CROWD_BUSY,
                GossipPlaceholders.DEFAULT_CROWD_PACKED));
        assertEquals("busy", GossipPlaceholders.crowdOf(3, 3, 6));
        assertEquals("busy", GossipPlaceholders.crowdOf(5, 3, 6));
        assertEquals("packed", GossipPlaceholders.crowdOf(6, 3, 6));
        assertEquals("packed", GossipPlaceholders.crowdOf(40, 3, 6));
        // nonsense thresholds must not invert: busy is clamped to >= 1 and packed to >= busy
        assertEquals("quiet", GossipPlaceholders.crowdOf(0, 0, -5));
        assertEquals("quiet", GossipPlaceholders.crowdOf(5, 9, 2), "packed can never sit below busy");
        assertEquals("packed", GossipPlaceholders.crowdOf(9, 9, 2));

        GossipCtx busy = base(NOON_TICKS, false, false, 4, GossipCtx.Talk.stub());
        assertEquals("busy|4", GossipPlaceholders.fill("<crowd>|<players_near>", busy));
    }

    @Test
    void lorePicksFromTheConfigListsAndFallsBackToTheBuiltIns() {
        String mine = GossipPlaceholders.fill("<mine_news>", base());
        assertEquals("the deep mines flooded again", mine);
        String tale = GossipPlaceholders.fill("<traveler_tale>", base());
        assertEquals("a sailor swears the ocean has a floor", tale);

        GossipCtx empty = withNews(GossipCtx.News.of("nobody", List.of(), List.of()));
        assertTrue(GossipPlaceholders.DEFAULT_MINE_NEWS.contains(GossipPlaceholders.fill("<mine_news>", empty)));
        assertTrue(GossipPlaceholders.DEFAULT_TRAVELER_TALES.contains(
                GossipPlaceholders.fill("<traveler_tale>", empty)));
    }

    @Test
    void newsDefaultsAndDuelWinnerAreAlwaysPresent() {
        GossipCtx news = withNews(new GossipCtx.News(-3, " ", null, null, null, null, null));
        assertEquals("0 goods a traveler bread nobody", GossipPlaceholders.fill(
                "<sales_today> <last_sold> <last_buyer> <stock_low> <duel_winner>", news));

        GossipCtx fighting = base(NOON_TICKS, false, false, 0, GossipCtx.Talk.stub());
        fighting = new GossipCtx(fighting.speaker(), fighting.listener(), fighting.scene(), fighting.talk(),
                GossipCtx.News.of("Steve", List.of("x"), List.of("y")));
        assertEquals("Steve", GossipPlaceholders.fill("<duel_winner>", fighting));
    }

    @Test
    void lineTagIsTheSpokenLineVerbatim() {
        GossipCtx ctx = base().saying("<red>Slow trade today</red>, <speaker>?");
        assertEquals("<gold>Rola</gold> » <red>Slow trade today</red>, <speaker>?",
                GossipPlaceholders.fill("<gold><npc></gold> » <line>", ctx),
                "<line> is authored markup: inserted once, never scrubbed and never re-scanned");
    }

    @Test
    void beatIsOrdinal() {
        assertEquals("1st exchange", beat(1));
        assertEquals("2nd exchange", beat(2));
        assertEquals("3rd exchange", beat(3));
        assertEquals("4th exchange", beat(4));
        assertEquals("11th exchange", beat(11));
        assertEquals("22nd exchange", beat(22));
        assertEquals("1st exchange", beat(0), "a missing beat must not read '0th'");
    }

    private static String beat(int beat) {
        return GossipPlaceholders.fill("<beat>",
                withTalk(new GossipCtx.Talk("smalltalk", "", "neighbors", beat, "")));
    }

    @Test
    void tableCoversEveryRegisteredKey() {
        List<String> rows = GossipPlaceholders.table(base());
        assertEquals(GossipPlaceholders.keys().size(), rows.size());
        for (String key : GossipPlaceholders.keys()) {
            assertTrue(rows.stream().anyMatch(r -> r.startsWith("<" + key + ">")),
                    "the debug table is missing <" + key + ">");
        }
        assertTrue(rows.stream().anyMatch(r -> r.startsWith("<line>")), "the empty <line> row needs a hint");
    }

    @Test
    void aMissingContextLeavesTextUntouched() {
        assertEquals("<speaker> and <crowd>", GossipPlaceholders.fill("<speaker> and <crowd>", null));
        assertEquals("", GossipPlaceholders.fill(null, base()));
        assertEquals("no tags here", GossipPlaceholders.fill("no tags here", base()));
    }

    @Test
    void theLegacyPartnerOnlyFormatStillWorks() {
        // gossip.placeholders.enabled: false → CharacterManager passes no ctx and keeps the old replace
        String raw = "Slow day today, <partner>?";
        assertNotEquals(raw, GossipPlaceholders.fill(raw, base()));
        assertTrue(raw.replace("<partner>", "Bert").contains("Bert"));
    }
}
