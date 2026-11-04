package dev.servereer.livingshopkeeper.dialog;

import java.util.List;
import java.util.Locale;

/**
 * One conversational beat's snapshot: who is talking to whom, where and when it happens, what the talk is
 * about, and what the world has to say about it. This is the data {@link GossipPlaceholders} resolves
 * {@code <speaker_job>}-style tags against.
 *
 * <p>Deliberately <b>pure data</b> — no {@code Character}, {@code World}, {@code Player}, no plugin, no I/O.
 * The caller ({@code CharacterManager#gossipCtx}) reads the live world once per beat and fills this in, which
 * keeps every resolver O(1), keeps the server thread free of lookups per candidate line, and lets the whole
 * registry be unit-tested without a server. Phase 2 (conditions) and Phase 3 (topics / sessions) extend this
 * record; the resolvers keep reading the same fields, so authored lines never have to change again.
 *
 * <p>Every field is normalised to a non-blank, non-null value by the factories below, so a resolver can never
 * NPE and a line can never show an empty gap ({@code "Trader, someone?"} rather than {@code "?"}).
 */
public record GossipCtx(Person speaker, Person listener, Scene scene, Talk talk, News news) {

    /** One of the two NPCs in the beat: who it is plus the flavour fields authored in {@code character.yml}. */
    public record Person(String id, String name, String job, String trait, String mood) {

        /**
         * @param name  plain display name (MiniMessage stripped) — capped at 24 characters when substituted
         * @param trait the free-text trait; only its first word is shown ({@code "gruff ex-miner"} → {@code "gruff"})
         */
        public static Person of(String id, String name, String job, String trait, String mood) {
            return new Person(id == null ? "" : id.toLowerCase(Locale.ROOT),
                    blankTo(name, "someone"), blankTo(job, "trader"),
                    firstWord(trait), blankTo(mood, "content"));
        }

        private static String firstWord(String s) {
            if (s == null) return "plain";
            String t = s.trim();
            if (t.isEmpty()) return "plain";
            int sp = t.indexOf(' ');
            return sp < 0 ? t : t.substring(0, sp);
        }
    }

    /** Where/when the beat happens — all derived from the world clock so no resolver needs a {@code World}. */
    public record Scene(String stall, String space, String time, String daypart, String greeting,
                        String weather, long day, int playersNear, String crowd) {

        /**
         * @param stall    the speaker's post: the WorldGuard plot at its home, else a generic label
         * @param space    the outing space id while away, {@code "road"} while walking, {@code "market"} at the stall
         * @param fullTime {@code World#getFullTime()} — ticks since world creation (day counter + clock derived)
         */
        public static Scene of(String stall, String space, long fullTime, boolean thundering, boolean storming,
                               int playersNear, String crowd) {
            long ticks = Math.floorMod(fullTime, 24_000L);
            return new Scene(blankTo(stall, "the stall"), blankTo(space, "market"),
                    timeWord(ticks), dayPart(ticks), greetingWord(ticks),
                    thundering ? "thunder" : storming ? "rain" : "clear",
                    Math.floorDiv(fullTime, 24_000L), Math.max(0, playersNear), blankTo(crowd, "quiet"));
        }

        /** {@code day | evening | night | morning} — the exact wording {@code DialogText} uses for {@code <time>}. */
        public static String timeWord(long ticks) {
            return switch (Conditions.timeBucket(ticks)) {
                case "dusk" -> "evening";
                case "night" -> "night";
                case "dawn" -> "morning";
                default -> "day";
            };
        }

        /** {@code morning | noon | dusk | midnight} — the four coarse parts of a Minecraft day. */
        public static String dayPart(long ticks) {
            long t = Math.floorMod(ticks, 24_000L);
            if (t < 6_000) return "morning";      // 06:00–12:00
            if (t < 12_000) return "noon";        // 12:00–18:00
            if (t < 18_000) return "dusk";        // 18:00–24:00
            return "midnight";                    // 00:00–06:00
        }

        /**
         * {@code Morning | Afternoon | Evening} — a one-word time-of-day greeting, so the pre-dawn hours (the
         * same ones {@code <daypart>} calls "morning") still greet as Morning rather than Evening.
         */
        public static String greetingWord(long ticks) {
            long t = Math.floorMod(ticks, 24_000L);
            if (t >= 22_000 || t < 6_000) return "Morning";
            if (t < 15_000) return "Afternoon";
            return "Evening";
        }
    }


    /**
     * What the beat's conversation is. {@code topic}, {@code lastLine}, {@code relationship} and {@code beat}
     * are Phase-3 stubs today ({@code "smalltalk"} / {@code ""} / {@code "neighbors"} / {@code 1}): the
     * resolvers already read them, so Phase 3 becomes config-only work for authors. {@code line} is the line
     * actually being said right now — it backs the structural {@code <line>} tag.
     */
    public record Talk(String topic, String lastLine, String relationship, int beat, String line) {

        private static final Talk STUB = new Talk("smalltalk", "", "neighbors", 1, "");

        /** The Phase-1 stub talk (no line yet — {@link #saying} attaches it at the point of speaking). */
        public static Talk stub() {
            return STUB;
        }

        /** This talk with the spoken line attached, so the text only has to be walked once. */
        public Talk saying(String said) {
            return new Talk(topic, lastLine, relationship, beat, said == null ? "" : said);
        }
    }

    /**
     * What the world has to say: the shop counters (Phase-1.5 stubs — {@code ShopEngine} exposes no real
     * counters yet), the lore rotation lists, and whoever is duelling within earshot.
     */
    public record News(int salesToday, String lastSold, String lastBuyer, String stockLow,
                       String duelWinner, List<String> mineNews, List<String> travelerTale) {

        public News {
            salesToday = Math.max(0, salesToday);
            lastSold = blankTo(lastSold, "goods");
            lastBuyer = blankTo(lastBuyer, "a traveler");
            stockLow = blankTo(stockLow, "bread");
            duelWinner = blankTo(duelWinner, "nobody");
            mineNews = mineNews == null ? List.of() : List.copyOf(mineNews);
            travelerTale = travelerTale == null ? List.of() : List.copyOf(travelerTale);
        }

        /** The Phase-1 news: shop values stubbed, only the lore rotation + the local duel are live. */
        public static News of(String duelWinner, List<String> mineNews, List<String> travelerTale) {
            return new News(0, null, null, null, duelWinner, mineNews, travelerTale);
        }
    }

    /** This same beat with the spoken line attached — supplies the structural {@code <line>} tag. */
    public GossipCtx saying(String said) {
        return new GossipCtx(speaker, listener, scene, talk.saying(said), news);
    }

    private static String blankTo(String s, String def) {
        return s == null || s.isBlank() ? def : s.trim();
    }
}
