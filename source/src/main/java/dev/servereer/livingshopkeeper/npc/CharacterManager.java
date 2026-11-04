package dev.servereer.livingshopkeeper.npc;

import dev.servereer.livingshopkeeper.audio.SpeechController;
import dev.servereer.livingshopkeeper.dialog.DialogEngine;
import dev.servereer.livingshopkeeper.dialog.DialogNode;
import dev.servereer.livingshopkeeper.dialog.GossipCtx;
import dev.servereer.livingshopkeeper.dialog.GossipPlaceholders;
import dev.servereer.livingshopkeeper.gui.DialogMenuGui;
import dev.servereer.livingshopkeeper.move.MovementController;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns every {@link Character} (of any {@link CharacterType}): loads their per-folder defs from
 * {@code characters/<id>/}, renders them to nearby players over packets, tracks per-player viewer sets,
 * turns each toward the nearest listener, routes interact packets, and dispatches type-specific behavior.
 * No real entities exist — a repeating scan diffs who should see each NPC (the MachineConstruct pattern).
 *
 * <p>B0 handles the shopkeeper behavior (interact → dialog) for all types; barker movement/invite logic
 * lands in B1–B3 via {@link #tickBehavior}.
 */
public final class CharacterManager {

    private static final double EYE_HEIGHT = 1.62;
    private static final float ROTATE_THRESHOLD_DEG = 3f;
    private static final long SCAN_PERIOD_TICKS = 5L;
    private static final long MOVE_PERIOD_TICKS = 1L;   // 20 Hz: smoother than the old 10 Hz block-step cadence
    /** Fallback {@code gossip.smalltalk-format} when the config has none — {@code <line>} is the spoken line. */
    private static final String DEFAULT_SMALLTALK_FORMAT =
            "<gold><npc></gold> <dark_gray>»</dark_gray> <gray><line></gray>";

    private final JavaPlugin plugin;
    private final SkinCache skins;
    private final SpeechController speech;
    private final DialogEngine dialog;
    private final DialogMenuGui menuGui;
    private final File dir;   // plugins/LivingShopkeeper/characters/

    private final Map<String, Character> byId = new LinkedHashMap<>();
    private final Map<Integer, Character> byEntityId = new LinkedHashMap<>();
    private final Map<UUID, MovementController> movers = new java.util.HashMap<>();   // barker id → patrol
    private final Map<UUID, Map<UUID, Long>> inviteCd = new java.util.HashMap<>();     // barker → player → next-invite ms
    private final Map<UUID, Long> addressUntil = new java.util.HashMap<>();            // barker → gesturing-until ms
    private final Map<UUID, Long> lastSwing = new java.util.HashMap<>();               // barker → last arm-swing ms
    private final Map<UUID, UUID> pendingTarget = new java.util.HashMap<>();           // barker → player it invited, awaiting entry
    private final Map<UUID, Long> pendingUntil = new java.util.HashMap<>();            // barker → give-up deadline ms
    private final Map<UUID, Long> nextGlance = new java.util.HashMap<>();              // npc → next idle look-around ms
    private final Map<UUID, Long> nextFidget = new java.util.HashMap<>();              // npc → next idle gesture ms
    private final Map<UUID, Long> crouchUntil = new java.util.HashMap<>();             // npc → stand-up time
    private final Set<UUID> crouched = new java.util.HashSet<>();
    private final Map<UUID, Long> nextSpeechGesture = new java.util.HashMap<>();
    private final Map<UUID, String> currentPlace = new java.util.HashMap<>();
    private final Map<UUID, Long> nextPlaceAmbient = new java.util.HashMap<>();
    private final Map<UUID, Map<UUID, long[]>> hits = new java.util.HashMap<>(); // npc → attacker → {score, lastHitMs}
    private final Map<UUID, CombatState> combat = new java.util.HashMap<>();     // npc → active flee/fight
    private final Map<UUID, Long> nextResync = new java.util.HashMap<>();        // npc → next absolute-position resend ms
    private final Map<UUID, LobbyState> lobbyStates = new java.util.HashMap<>(); // lobby npc → roam/sit/attend state
    private final Map<UUID, Long> fallbackCd = new java.util.HashMap<>();       // player → next fallback-greet ms
    private final Map<UUID, Long> nextDoorCheck = new java.util.HashMap<>();    // npc → next door scan ms
    private boolean doorsEnabled = true;

    // --- outings: NPCs away at a shared Space, a ghost head minding the post ---
    private final Map<UUID, AwayState> away = new java.util.HashMap<>();            // npc → active outing
    private final Map<UUID, PendingOuting> pendingOutings = new java.util.HashMap<>(); // npc → staggered departure
    private final Set<Integer> ghostIds = new java.util.HashSet<>();                // ghost entity ids (click = use NPC)
    private final Map<String, Long> outingWindowKey = new java.util.HashMap<>();    // schedule+world → window start
    private final Map<String, Set<UUID>> outingRolled = new java.util.HashMap<>();  // schedule+world → npcs rolled
    private final Map<String, long[]> spaceChat = new java.util.HashMap<>();        // space → {nextLineMs, speakerIdx}
    private final List<long[]> gestureQueueKeys = new ArrayList<>();                // parallel: {atMs}
    private final List<Runnable> gestureQueue = new ArrayList<>();                  // deferred nods/laughs/waves
    private final Map<UUID, List<UUID>> outingGroups = new java.util.HashMap<>();   // outing leader → companions
    private final Map<UUID, SparState> spar = new java.util.HashMap<>();            // npc → active sparring match
    private final Map<UUID, Map<UUID, Long>> challengeCd = new java.util.HashMap<>(); // npc → player → re-ask at
    private List<OutingSchedule> outingSchedules = List.of();
    private long nextOutingCheckMs;
    private SpaceManager spaces;
    private dev.servereer.livingshopkeeper.ai.AiChatService aiChat;

    // --- gossip placeholders (the registry lives in GossipPlaceholders; see GUIDE.md "gossip placeholders") ---
    private boolean gossipPlaceholders = true;      // gossip.placeholders.enabled — off = pre-placeholder behaviour
    private int crowdBusy = GossipPlaceholders.DEFAULT_CROWD_BUSY;
    private int crowdPacked = GossipPlaceholders.DEFAULT_CROWD_PACKED;
    private List<String> loreMineNews = GossipPlaceholders.DEFAULT_MINE_NEWS;
    private List<String> loreTravelerTale = GossipPlaceholders.DEFAULT_TRAVELER_TALES;

    /** A friendly duel: the NPC mirrors the player's weapon class and only ever responds, never starts. */
    private static final class SparState {
        final UUID player;
        boolean ranged;                       // bow/crossbow → kite + shoot; else melee
        org.bukkit.Material weapon;           // what we're mirroring in the main hand
        long lastActionMs;                    // last time the PLAYER hit us — idle timeout ends the spar
        long nextAttackMs;
        long retreatUntilMs;                  // defensive style: back off briefly after landing a hit
        long nextStrafeMs;                    // sidestep cadence while waiting out the swing cooldown
        int strafeSign = 1;
        long nextSpecialMs;                   // gate on signature moves (mace smash / sword crit / power shot)
        int windPhase;                        // mace wind-smash: 0 none · 1 rising · 2 apex hang · 3 slamming
        long windPhaseUntil;
        double windBaseY, slamX, slamZ;       // launch height to return to + the locked-on landing spot
        long knockUntilMs;                    // reeling back from the player's blow (visible hit reaction)
        double kbVx, kbVz;

        SparState(UUID player, long now) {
            this.player = player;
            this.lastActionMs = now;
        }
    }

    /** One NPC's outing: walking out, hanging out at the space, or walking back — ghost at the post meanwhile. */
    private static final class AwayState {
        static final int LEAVING = 0, OUT = 1, RETURNING = 2;
        int phase = LEAVING;
        String spaceId;
        double[] target;         // seat {x,y,z,yaw} or stand spot {x,y,z,NaN}
        boolean targetIsSeat;
        long untilMs;            // head home when passed (manual outings + scheduled stay length)
        String scheduleKey;      // schedule id, or null for /lsk away
        int ghostHeadId = -1;
        int[] ghostTagIds = new int[0];
        final Set<UUID> ghostViewers = new java.util.HashSet<>();
        float ghostYaw;
        long nextIdleMs;         // next reposition/fidget while out
        long nextChatMs;         // next stroll-chat beat (group leaders while walking)
        boolean arrivedOnce;     // first arrival fired (tag swap + arrive_space happen once)
        UUID leader;             // group outing: the companion we walk beside, or null
        double sideOffset;       // lateral offset from the leader while walking together
        UUID challengeTarget;    // arena: the player we're inviting to a duel, or null
        long challengeUntilMs;   // give up waiting for them to accept after this
        long nextChallengeMs;    // next time we may look for someone to challenge
    }

    private record PendingOuting(long departAtMs, String spaceId, long stayMs, String scheduleKey, UUID leader) {}

    /** One parsed {@code outings.schedules.<id>} entry: a Minecraft-time window + who goes and where. */
    private record OutingSchedule(String id, int fromTick, int untilTick, Set<CharacterType> types,
                                  double chance, String space, long minStayMs, long maxStayMs, long staggerMs) {
        boolean activeAt(long worldTime) {
            int t = (int) Math.floorMod(worldTime, 24_000L);
            if (fromTick == untilTick) return true;
            if (fromTick < untilTick) return t >= fromTick && t < untilTick;
            return t >= fromTick || t < untilTick;
        }
        /** Full-time tick this window last opened — identifies "tonight" across the midnight wrap. */
        long windowKey(long fullTime) {
            long since = Math.floorMod(fullTime - fromTick, 24_000L);
            return fullTime - since;
        }
    }

    /** A LOBBY/SHOP npc's little life around its post: attending, roaming, sitting, or gossiping. */
    private static final class LobbyState {
        boolean attending;      // a player is at the post and we're (heading) there for them
        long stayUntilMs;       // keep standing at the post this long after the last visitor leaves
        long nextWanderMs;      // when the next roam/sit decision may happen
        long sitUntilMs;        // seated until (0 → not on a seat)
        double[] target;        // current wander/seat destination {x,y,z,yaw or NaN}, null → none
        boolean targetIsSeat;
        UUID lastVisitor;       // for the "thanks for coming" farewell when a buyer walks out
        long nextHawkMs;        // next time the "hawk" behavior may shout its wares
        UUID gossipWith;        // fellow shopkeeper this one is chatting with, or null
        boolean gossipLead;     // this side initiated and drives the conversation
        boolean gossipStarted;  // the opener line has been said
        boolean gossipMyTurn;   // whose beat is next (lead-side view)
        long gossipUntilMs;
        long nextGossipLineMs;
    }

    private final Map<UUID, java.util.Set<String>> shopRegions = new java.util.HashMap<>(); // shop → WG region ids at post
    private final Map<UUID, Map<UUID, Long>> recentBuyers = new java.util.HashMap<>();      // shop → buyer → boughtAtMs
    private final Map<UUID, Map<org.bukkit.block.Block, Long>> openedDoors = new java.util.HashMap<>(); // npc → doors to close

    /** One NPC's active hit reaction: fleeing from or fighting back at {@link #attacker}, then walking home. */
    private static final class CombatState {
        final UUID attacker;
        final boolean fighting;    // false → fleeing
        long untilMs;
        long nextSwingMs;
        long nextRepathMs;
        final double[] returnPost; // {x,y,z,yaw,pitch} to walk back to after; null → barker just resumes patrol
        boolean returning;

        CombatState(UUID attacker, boolean fighting, long untilMs, double[] returnPost) {
            this.attacker = attacker;
            this.fighting = fighting;
            this.untilMs = untilMs;
            this.returnPost = returnPost;
        }
    }

    private double hearingDistance, hearingSq;
    private BukkitTask scanTask, moveTask;
    private dev.servereer.livingshopkeeper.shop.ShopEngine shopEngine;
    private dev.servereer.livingshopkeeper.shop.ShopGuis shopGuis;

    /** Wire the shop subsystem (engine + GUI suite) — called once by the plugin after construction. */
    public void wireShop(dev.servereer.livingshopkeeper.shop.ShopEngine engine,
                         dev.servereer.livingshopkeeper.shop.ShopGuis guis) {
        this.shopEngine = engine;
        this.shopGuis = guis;
    }

    /** Wire the shared-space registry — called once by the plugin after construction. */
    public void wireSpaces(SpaceManager spaces) {
        this.spaces = spaces;
    }

    public SpaceManager spaces() {
        return spaces;
    }

    /** Wire the optional AI chat service — called once by the plugin after construction. */
    public void wireAiChat(dev.servereer.livingshopkeeper.ai.AiChatService aiChat) {
        this.aiChat = aiChat;
    }

    public CharacterManager(JavaPlugin plugin, SkinCache skins, SpeechController speech, DialogEngine dialog, DialogMenuGui menuGui) {
        this.plugin = plugin;
        this.skins = skins;
        this.speech = speech;
        this.dialog = dialog;
        this.menuGui = menuGui;
        this.dir = new File(plugin.getDataFolder(), "characters");
        //noinspection ResultOfMethodCallIgnored
        this.dir.mkdirs();
    }

    /** The dialog engine needs the mouth position + the character id (its dialog pack + voice namespace). */
    private DialogEngine.Speaker speakerOf(Character c) {
        return new DialogEngine.Speaker(c.uuid(), c.getId().toLowerCase(Locale.ROOT), c.display(),
                Bukkit.getWorld(c.getWorld()), c.getX(), c.getY() + 1.2, c.getZ());
    }

    // --- lifecycle ---------------------------------------------------------

    public void load() {
        readConfig();
        migrateLegacy();
        File[] folders = dir.listFiles(File::isDirectory);
        if (folders != null) {
            for (File folder : folders) {
                Character c = Character.load(folder);
                if (c == null) {
                    plugin.getLogger().warning("Skipping unreadable character folder: " + folder.getName());
                    continue;
                }
                register(c);
                if (c.skinName() != null && c.textureValue() == null) {
                    resolveSkin(c, c.skinName());
                }
            }
        }
        plugin.getLogger().info("Loaded " + byId.size() + " character(s).");
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scan, SCAN_PERIOD_TICKS, SCAN_PERIOD_TICKS);
        moveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::moveBarkers, MOVE_PERIOD_TICKS, MOVE_PERIOD_TICKS);
    }

    public void reload() {
        despawnAllEverywhere();
        if (scanTask != null) { scanTask.cancel(); scanTask = null; }
        if (moveTask != null) { moveTask.cancel(); moveTask = null; }
        byId.clear();
        byEntityId.clear();
        movers.clear();
        inviteCd.clear();
        addressUntil.clear();
        lastSwing.clear();
        pendingTarget.clear();
        pendingUntil.clear();
        nextGlance.clear();
        nextFidget.clear();
        crouchUntil.clear();
        crouched.clear();
        nextSpeechGesture.clear();
        currentPlace.clear();
        nextPlaceAmbient.clear();
        hits.clear();
        combat.clear();
        nextResync.clear();
        lobbyStates.clear();
        fallbackCd.clear();
        shopRegions.clear();
        recentBuyers.clear();
        openedDoors.clear();
        nextDoorCheck.clear();
        away.clear();
        pendingOutings.clear();
        ghostIds.clear();
        outingWindowKey.clear();
        outingRolled.clear();
        spaceChat.clear();
        gestureQueueKeys.clear();
        gestureQueue.clear();
        outingGroups.clear();
        spar.clear();
        challengeCd.clear();
        load();
    }

    public void shutdown() {
        if (scanTask != null) { scanTask.cancel(); scanTask = null; }
        if (moveTask != null) { moveTask.cancel(); moveTask = null; }
        if (speech != null) speech.stopAll();
        despawnAllEverywhere();
    }

    private void readConfig() {
        plugin.reloadConfig();
        this.hearingDistance = Math.max(4, plugin.getConfig().getDouble("hearing-distance", 16));
        this.hearingSq = hearingDistance * hearingDistance;
        PacketNpc.seatMountOffset = plugin.getConfig().getDouble("seat-mount-offset", 1.7);
        PacketNpc.tagHeight = plugin.getConfig().getDouble("tag-height", 2.05);
        PacketNpc.tagSeatedDrop = plugin.getConfig().getDouble("tag-seated-drop", 1.0);
        this.doorsEnabled = plugin.getConfig().getBoolean("doors.enabled", true);
        this.outingSchedules = parseOutingSchedules();
        if (spaces != null) spaces.load();
        if (aiChat != null) aiChat.reload();
        // Gossip placeholders: every key is optional — a config.yml from an older version simply gets the
        // built-in defaults below, and the fail-open registry keeps old `smalltalk` lines working verbatim.
        this.gossipPlaceholders = plugin.getConfig().getBoolean("gossip.placeholders.enabled", true);
        this.crowdBusy = plugin.getConfig().getInt("gossip.placeholders.crowd-busy",
                GossipPlaceholders.DEFAULT_CROWD_BUSY);
        this.crowdPacked = plugin.getConfig().getInt("gossip.placeholders.crowd-packed",
                GossipPlaceholders.DEFAULT_CROWD_PACKED);
        List<String> mineNews = plugin.getConfig().getStringList("gossip.lore.mine_news");
        List<String> travelerTales = plugin.getConfig().getStringList("gossip.lore.traveler_tale");
        this.loreMineNews = mineNews.isEmpty() ? GossipPlaceholders.DEFAULT_MINE_NEWS : List.copyOf(mineNews);
        this.loreTravelerTale = travelerTales.isEmpty()
                ? GossipPlaceholders.DEFAULT_TRAVELER_TALES : List.copyOf(travelerTales);
        GossipPlaceholders.clearWarnings();   // a typo fixed in config.yml stops nagging after /lsk reload
    }

    private List<OutingSchedule> parseOutingSchedules() {
        var out = new ArrayList<OutingSchedule>();
        var root = plugin.getConfig().getConfigurationSection("outings.schedules");
        if (root == null) return out;
        for (String id : root.getKeys(false)) {
            var s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                Set<CharacterType> types = new java.util.HashSet<>();
                for (String t : s.getStringList("types")) types.add(CharacterType.from(t));
                if (types.isEmpty()) types.add(CharacterType.SHOP);
                double minM = s.getDouble("min-stay-minutes", 4), maxM = Math.max(minM, s.getDouble("max-stay-minutes", 10));
                out.add(new OutingSchedule(id,
                        ActivityPlace.parseWorldTime(s.getString("from", "night")),
                        ActivityPlace.parseWorldTime(s.getString("until", "dawn")),
                        types,
                        Math.max(0, Math.min(1, s.getDouble("chance", 0.5))),
                        s.getString("space", "any"),
                        (long) (minM * 60_000), (long) (maxM * 60_000),
                        (long) (Math.max(0, s.getDouble("stagger-seconds", 90)) * 1000)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Outing schedule '" + id + "' has a bad from/until time: " + e.getMessage());
            }
        }
        return out;
    }

    private void register(Character c) {
        c.entityId = SpigotReflectionUtil.generateEntityId();
        c.seatEntityId = SpigotReflectionUtil.generateEntityId();
        byId.put(c.getId().toLowerCase(Locale.ROOT), c);
        byEntityId.put(c.entityId, c);
        byEntityId.put(c.seatEntityId, c);
        allocateTagIds(c);
        if (c.type() == CharacterType.BARKER || c.type() == CharacterType.LOBBY || c.type() == CharacterType.SHOP) {
            movers.put(c.uuid(), newMover());
        }
    }

    /** One hologram stand per active tag line; clicking a tag line maps back to the character like its body. */
    private void allocateTagIds(Character c) {
        for (int id : c.tagEntityIds) byEntityId.remove(id);
        c.tagEntityIds = new int[c.activeTagLines().size()];
        for (int i = 0; i < c.tagEntityIds.length; i++) {
            c.tagEntityIds[i] = SpigotReflectionUtil.generateEntityId();
            byEntityId.put(c.tagEntityIds[i], c);
        }
    }

    private MovementController newMover() {
        double perTick = plugin.getConfig().getDouble("barker.walk-speed", 4.3) / (20.0 / MOVE_PERIOD_TICKS);
        return new MovementController(perTick,
                plugin.getConfig().getInt("barker.nav.max-nodes", 1500),
                plugin.getConfig().getInt("barker.nav.max-range", 40),
                plugin.getConfig().getDouble("natural.patrol-jump-chance-per-second", 0.02),
                plugin.getConfig().getDouble("natural.patrol-jump-height", 0.42),
                plugin.getConfig().getDouble("natural.turn-degrees-per-tick", 16));
    }

    /** One-time migration: flat {@code shopkeepers/<id>.yml} → {@code characters/<id>/} (+ shared dialog/voice). */
    private void migrateLegacy() {
        File legacy = new File(plugin.getDataFolder(), "shopkeepers");
        File[] files = legacy.isDirectory()
                ? legacy.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml")) : null;
        if (files == null || files.length == 0) return;
        File sharedDialog = new File(plugin.getDataFolder(), "dialog");
        File sharedVoice = new File(plugin.getDataFolder(), "voice");
        int n = 0;
        for (File f : files) {
            YamlConfiguration yc = YamlConfiguration.loadConfiguration(f);
            String id = yc.getString("id");
            if (id == null || id.isBlank()) id = f.getName().substring(0, f.getName().length() - 4);
            id = id.toLowerCase(Locale.ROOT);
            File folder = new File(dir, id);
            if (new File(folder, "character.yml").isFile()) continue;
            yc.set("type", "shopkeeper");     // legacy files predate the type field
            try {
                Character.fromConfig(id, folder, yc).save();
                copyFilesShallow(sharedDialog, new File(folder, "dialog"));
                copyFilesShallow(sharedVoice, new File(folder, "voice"));
                n++;
            } catch (Exception e) {
                plugin.getLogger().warning("Migrating shopkeeper '" + id + "' failed: " + e.getMessage());
            }
        }
        //noinspection ResultOfMethodCallIgnored
        legacy.renameTo(new File(plugin.getDataFolder(), "shopkeepers.migrated-" + System.currentTimeMillis()));
        if (n > 0) plugin.getLogger().info("Migrated " + n + " legacy shopkeeper(s) to characters/.");
    }

    // --- admin operations --------------------------------------------------

    public boolean exists(String id) { return byId.containsKey(id.toLowerCase(Locale.ROOT)); }
    public Collection<Character> all() { return byId.values(); }
    public Character get(String id) { return byId.get(id.toLowerCase(Locale.ROOT)); }
    Character byEntityId(int entityId) { return byEntityId.get(entityId); }

    /** Create a character of {@code type} at {@code loc}. Returns null if the id is taken. */
    public Character create(String id, Location loc, String skinName, CharacterType type) {
        if (exists(id)) return null;
        World w = loc.getWorld();
        String key = id.toLowerCase(Locale.ROOT);   // folder + id are lowercase (case-safe on Linux)
        File folder = new File(dir, key);
        Character c = new Character(key, folder, UUID.randomUUID(), type, w.getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), id);
        if (type == CharacterType.BARKER || type == CharacterType.LOBBY || type == CharacterType.SHOP) {
            c.setHome(new double[]{ loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch() });
        }
        register(c);
        if (skinName != null) {
            SkinCache.Skin hit = skins.cached(skinName);
            if (hit != null) c.setSkin(skinName, hit.value(), hit.signature());
        }
        persist(c);
        shipDefaultDialog(folder, switch (type) {
            case BARKER -> "dialog/barker.yml";
            case LOBBY -> "dialog/lobby.yml";
            case SHOP -> "dialog/shop.yml";
            default -> "dialog/default.yml";
        });
        if (dialog != null) dialog.reloadLibrary();   // pick up the just-shipped dialog immediately
        scanOne(c);
        if (skinName != null && c.textureValue() == null) resolveSkin(c, skinName);
        return c;
    }

    /** Set the NPC's MiniMessage display name (floating tag + chat), persist, and re-render it. */
    public void setDisplay(Character c, String display) {
        c.setDisplay(display);
        persist(c);
        respawn(c);
    }

    public void applySkin(Character c, String skinName) {
        SkinCache.Skin hit = skins.cached(skinName);
        if (hit != null) {
            c.setSkin(skinName, hit.value(), hit.signature());
            persist(c);
            respawn(c);
        } else {
            resolveSkin(c, skinName);
        }
    }

    /** Apply a raw texture value (+ optional signature) pasted straight from NameMC/MineSkin — offline. */
    public void applySkinValue(Character c, String value, String signature) {
        c.setSkin(null, value, signature);
        persist(c);
        respawn(c);
    }

    /**
     * Apply a skin from a {@code textures.minecraft.net} URL (or bare hash) — built locally, no network.
     * Returns false if the URL isn't a usable skin URL (the caller then points the admin at MineSkin).
     */
    public boolean applySkinUrl(Character c, String url) {
        String value = SkinCache.textureValueForUrl(url);
        if (value == null) return false;
        c.setSkin(url, value, null);
        persist(c);
        respawn(c);
        return true;
    }

    public boolean remove(String id) {
        Character c = byId.remove(id.toLowerCase(Locale.ROOT));
        if (c == null) return false;
        despawnAll(c);
        byEntityId.remove(c.entityId);
        byEntityId.remove(c.seatEntityId);
        for (int tagId : c.tagEntityIds) byEntityId.remove(tagId);
        lobbyStates.remove(c.uuid());
        movers.remove(c.uuid());
        inviteCd.remove(c.uuid());
        pendingTarget.remove(c.uuid());
        pendingUntil.remove(c.uuid());
        nextGlance.remove(c.uuid());
        nextFidget.remove(c.uuid());
        crouchUntil.remove(c.uuid());
        crouched.remove(c.uuid());
        nextSpeechGesture.remove(c.uuid());
        currentPlace.remove(c.uuid());
        nextPlaceAmbient.remove(c.uuid());
        hits.remove(c.uuid());
        combat.remove(c.uuid());
        nextResync.remove(c.uuid());
        shopRegions.remove(c.uuid());
        recentBuyers.remove(c.uuid());
        openedDoors.remove(c.uuid());
        pendingOutings.remove(c.uuid());
        AwayState as = away.remove(c.uuid());
        if (as != null) clearGhost(c, as);
        if (spaces != null) spaces.releaseSeats(c.uuid());
        outingGroups.remove(c.uuid());
        for (List<UUID> members : outingGroups.values()) members.remove(c.uuid());
        spar.remove(c.uuid());
        challengeCd.remove(c.uuid());
        deleteRecursive(c.folder());
        return true;
    }

    public Location location(Character c) {
        World w = Bukkit.getWorld(c.getWorld());
        return w == null ? null : new Location(w, c.getX(), c.getY(), c.getZ(), c.yaw(), c.pitch());
    }

    // --- barker setup (called by /lsk barker …) ---

    public void barkerAddWaypoint(Character c, Location loc) {
        c.addWaypoint(loc.getX(), loc.getY(), loc.getZ());
        persist(c);
        resetMover(c);
    }

    public void barkerClearWaypoints(Character c) {
        c.clearWaypoints();
        persist(c);
        resetMover(c);
    }

    public void barkerSetHome(Character c, Location loc) {
        c.setHome(new double[]{loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()});
        persist(c);
    }

    public void barkerSetRegion(Character c, String region) {
        c.setRegion(region);
        persist(c);
    }

    /** Add/update a passive area, scheduled standing visit, or scheduled sitting visit. */
    public void barkerSetPlace(Character c, String id, Location loc, double radius, boolean visit, boolean sit,
                               int fromTick, int untilTick) {
        c.putPlace(new ActivityPlace(id, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
                radius, visit, sit, fromTick, untilTick));
        persist(c);
        currentPlace.remove(c.uuid());
        resetMover(c);
    }

    public boolean barkerRemovePlace(Character c, String id) {
        boolean removed = c.removePlace(id);
        if (removed) {
            setSeated(c, false);
            persist(c);
            currentPlace.remove(c.uuid());
            resetMover(c);
        }
        return removed;
    }

    /** Set how often (seconds) this barker re-greets the same player; clears live cooldowns so it applies now. */
    public void barkerSetCooldown(Character c, double seconds) {
        c.setInviteCooldownSeconds(seconds);
        persist(c);
        inviteCd.remove(c.uuid());   // drop stale stamps so the new cadence takes effect immediately
    }

    private void resetMover(Character c) {
        if (c.type() == CharacterType.BARKER) movers.put(c.uuid(), newMover());   // restart patrol from the new route
    }

    /** Persist a character (used by commands editing barker region/waypoints too). */
    public void persist(Character c) {
        try {
            c.save();
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save character '" + c.getId() + "': " + e.getMessage());
        }
    }

    private void resolveSkin(Character c, String skinName) {
        skins.fetchAsync(skinName, (skin, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (skin == null) {
                plugin.getLogger().warning("Skin '" + skinName + "' for '" + c.getId() + "' failed: " + err);
                return;
            }
            if (byId.get(c.getId().toLowerCase(Locale.ROOT)) != c) return;
            c.setSkin(skinName, skin.value(), skin.signature());
            persist(c);
            respawn(c);
            plugin.getLogger().info("Applied skin '" + skinName + "' to character '" + c.getId() + "'.");
        }));
    }

    // --- interaction -------------------------------------------------------

    void onInteract(Player player, Character c, boolean sneaking) {
        if (c.type() == CharacterType.SHOP && shopGuis != null) {
            if (dialog != null) dialog.trigger(speakerOf(c), "interact", player);   // flavor line/voice
            boolean canManage = player.getUniqueId().equals(c.owner()) || player.hasPermission("livingnpc.admin");
            if (canManage && sneaking) shopGuis.openTrades(player, c);   // owner shortcut straight to trades
            else if (canManage) shopGuis.openMain(player, c);
            else shopGuis.openCustomer(player, c);
            return;
        }
        if (runActions(player, c)) return;   // lobby-style click actions take priority over dialog
        DialogEngine.Speaker sp = speakerOf(c);
        ActivityPlace place = placeAt(c, location(c));
        if (place != null && dialog != null && dialog.trigger(sp, "interact_" + place.id(), player)) return;
        // if this character has a conversation menu, open it instead of a one-off line
        if (menuGui != null && dialog != null) {
            DialogNode entry = dialog.menuEntry(c.getId().toLowerCase(Locale.ROOT), player);
            if (entry != null) { menuGui.open(player, sp, entry); return; }
        }
        if (dialog != null && dialog.trigger(sp, "interact", player)) return;
        // no menu and no matching line — fall back so the click isn't dead. Configurable + rate-limited:
        // spam-clicking an unconfigured NPC was printing "<npc> greets you." on every single click.
        long fnow = System.currentTimeMillis();
        if (fnow < fallbackCd.getOrDefault(player.getUniqueId(), 0L)) return;
        fallbackCd.put(player.getUniqueId(), fnow
                + (long) (plugin.getConfig().getDouble("messages.fallback-cooldown-seconds", 5) * 1000));
        String fmt = plugin.getConfig().getString("messages.fallback-greet", "<gold><npc></gold><gray> greets you.</gray>");
        if (fmt != null && !fmt.isBlank()) {
            player.sendMessage(dev.servereer.livingshopkeeper.dialog.DialogText.render(
                    fmt, player.getName(), c.display(), Bukkit.getWorld(c.getWorld())));
        }
        String clip = c.getId().toLowerCase(Locale.ROOT) + "/" + c.greetingClip();
        if (speech != null && speech.available() && speech.hasClip(clip)) {
            speech.speak(c.uuid(), Bukkit.getWorld(c.getWorld()), c.getX(), c.getY() + 1.2, c.getZ(), clip);
        } else {
            Location loc = location(c);
            if (loc != null) player.playSound(loc, Sound.ENTITY_VILLAGER_AMBIENT, 1f, 1f);
        }
    }

    // --- click actions -----------------------------------------------------

    /**
     * Run this character's click actions for {@code player} (lobby-NPC style). Action formats:
     * {@code player:<cmd>} (run as the player), {@code console:<cmd>} ({@code %player%} replaced),
     * {@code message:<MiniMessage>}, {@code sound:<SOUND[:vol[:pitch]]>}. Returns false when the
     * character has no actions (caller falls through to dialog).
     */
    private boolean runActions(Player player, Character c) {
        List<String> actions = c.actions();
        if (actions.isEmpty()) return false;
        for (String raw : actions) {
            int i = raw.indexOf(':');
            String kind = i < 0 ? "player" : raw.substring(0, i).toLowerCase(Locale.ROOT).trim();
            String arg = (i < 0 ? raw : raw.substring(i + 1)).trim();
            try {
                switch (kind) {
                    case "player" -> player.performCommand(arg.startsWith("/") ? arg.substring(1) : arg);
                    case "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            arg.replace("%player%", player.getName()));
                    case "message" -> player.sendMessage(dev.servereer.livingshopkeeper.dialog.DialogText.mini(arg));
                    case "sound" -> {
                        String[] parts = arg.split(":");
                        Sound sound = org.bukkit.Registry.SOUNDS.get(
                                org.bukkit.NamespacedKey.minecraft(parts[0].toLowerCase(Locale.ROOT)));
                        if (sound != null) player.playSound(player.getLocation(), sound,
                                parts.length > 1 ? Float.parseFloat(parts[1]) : 1f,
                                parts.length > 2 ? Float.parseFloat(parts[2]) : 1f);
                    }
                    default -> player.performCommand(raw.startsWith("/") ? raw.substring(1) : raw);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Action '" + raw + "' on '" + c.getId() + "' failed: " + e.getMessage());
            }
        }
        swingViewers(c, System.currentTimeMillis());   // a little acknowledging gesture
        return true;
    }

    // --- tag / action / lobby admin ops ------------------------------------

    /** Replace the floating-tag lines (MiniMessage each), persist, and re-render with fresh hologram ids. */
    public void setTagLines(Character c, List<String> lines) {
        c.setTagLines(lines);
        persist(c);
        despawnAll(c);
        allocateTagIds(c);
        respawn(c);
    }

    public void setActions(Character c, List<String> actions) {
        c.setActions(actions);
        persist(c);
    }

    /** Move a lobby/shop NPC's post (the spot it runs back to) and walk it there. */
    public void lobbySetPost(Character c, Location loc) {
        c.setHome(new double[]{loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()});
        persist(c);
        lobbyStates.remove(c.uuid());
        shopRegions.remove(c.uuid());   // the attend region follows the post
    }

    /** Mark seat {@code index} as the one a shop keeper prefers while attending customers. */
    public void lobbySetPrioritySeat(Character c, int index) {
        c.setPrioritySeat(index);
        persist(c);
    }

    public void lobbyAddSeat(Character c, double x, double y, double z, float yaw) {
        c.addSeat(x, y, z, yaw);
        persist(c);
    }

    /** Remove the seat nearest to {@code loc} (within 1.5 blocks); true if one was removed. */
    public boolean lobbyRemoveSeatNear(Character c, Location loc) {
        boolean removed = c.removeSeatNear(loc.getX(), loc.getY(), loc.getZ(), 1.5);
        if (removed) {
            persist(c);
            lobbyStates.remove(c.uuid());
            setSeated(c, false);
        }
        return removed;
    }

    public void lobbyClearSeats(Character c) {
        c.clearSeats();
        persist(c);
        lobbyStates.remove(c.uuid());
        setSeated(c, false);
    }

    // --- player shops -------------------------------------------------------

    /**
     * Create a player-owned SHOP character at {@code spot}, stocked from {@code chest}. The placer is
     * the owner: their skin, their name on the tag, and only they (or an admin) get the manage menu.
     * Returns null (after messaging the player) when their shop limit is reached.
     */
    public Character createPlayerShop(Player placer, Location spot, Location chest) {
        int max = shopLimit(placer);
        long owned = byId.values().stream().filter(x -> placer.getUniqueId().equals(x.owner())).count();
        if (max >= 0 && owned >= max && !placer.hasPermission("livingnpc.admin")) {
            placer.sendMessage(Component.text("You already have " + owned + " shop" + (owned == 1 ? "" : "s")
                    + " — the limit is " + max + ".", NamedTextColor.RED));
            return null;
        }
        String base = "shop-" + placer.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        String id = base;
        int n = 2;
        while (exists(id)) id = base + "-" + (n++);

        Character c = create(id, spot, null, CharacterType.SHOP);
        if (c == null) return null;
        c.setOwner(placer.getUniqueId());
        c.setContainer(new int[]{chest.getBlockX(), chest.getBlockY(), chest.getBlockZ()});
        c.setName(placer.getName());
        // No floating overhead text by default — the owner adds it from the shop menu's Nametag editor.
        persist(c);
        applySkin(c, placer.getName());   // the owner's own face behind the counter
        return c;
    }

    /**
     * How many shops this player may own. Starts at the config baseline ({@code shop.max-per-player}) and
     * is raised by permission: {@code livingshopkeeper.shops.<n>} grants at least {@code n} shops (highest
     * granted node wins), and {@code livingnpc.shops.unlimited} lifts the cap entirely. Returns
     * {@code -1} for unlimited. Per-rank limits are set by giving ranks the matching {@code .shops.<n>} node.
     */
    public int shopLimit(Player placer) {
        if (placer.hasPermission("livingnpc.admin")
                || placer.hasPermission("livingnpc.shops.unlimited")) return -1;
        int max = plugin.getConfig().getInt("shop.max-per-player", 2);
        for (org.bukkit.permissions.PermissionAttachmentInfo pai : placer.getEffectivePermissions()) {
            if (!pai.getValue()) continue;
            String perm = pai.getPermission().toLowerCase(Locale.ROOT);
            // Both roots: ranks granted livingshopkeeper.shops.<n> before the rename still count.
            String tail;
            if (perm.startsWith("livingnpc.shops.")) tail = perm.substring("livingnpc.shops.".length());
            else if (perm.startsWith("livingshopkeeper.shops.")) tail = perm.substring("livingshopkeeper.shops.".length());
            else continue;
            if (tail.equals("unlimited")) return -1;
            try {
                max = Math.max(max, Integer.parseInt(tail));
            } catch (NumberFormatException ignored) { /* not a numbered node */ }
        }
        return max;
    }

    /** Delete a shop through the GUI (owner/admin already verified by the menu route). */
    public void removeShopViaGui(Player by, String id) {
        Character c = get(id);
        if (c == null) return;
        remove(id);
        by.sendMessage(Component.text("Shop removed. Anything in the chest is still yours.", NamedTextColor.YELLOW));
    }

    // --- being hit ---------------------------------------------------------

    /**
     * A player punched/struck this NPC. Flinch + a dramatic line (different for a bare fist vs a weapon),
     * and once the same player has hit it {@code combat.react-after-hits} times, escalate: flee or fight
     * back per {@code combat.reaction} (character override via {@code combat.reaction} in character.yml).
     * Dialog triggers: {@code hit_punch}, {@code hit_again}, {@code hit_weapon}, {@code hit} (fallback),
     * {@code flee}, {@code fightback}, {@code fight_hit}, {@code calm}.
     */
    void onAttack(Player player, Character c) {
        // A lobby NPC treats a punch as a click (hub convention) — unless combat was explicitly enabled
        // for it via /lsk combat <id> flee|fightback.
        if (c.type() == CharacterType.LOBBY) {
            String own = c.combatReaction();
            if (own == null || own.equalsIgnoreCase("none")) {
                runActions(player, c);
                return;
            }
        }
        if (!plugin.getConfig().getBoolean("combat.enabled", true)) return;
        World w = Bukkit.getWorld(c.getWorld());
        if (w == null || !w.equals(player.getWorld())) return;
        long now = System.currentTimeMillis();

        // the visible/audible flinch, for everyone watching
        for (UUID id : c.viewers) {
            Player v = Bukkit.getPlayer(id);
            if (v != null) PacketNpc.hurt(v, c);
        }
        Location loc = location(c);
        if (loc != null) w.playSound(loc, Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        faceViewers(c, w, player);

        // Sparring partners answer the blow in kind (mirrored weapon) instead of fleeing/escalating.
        if (trySpar(c, player, w, now)) return;

        boolean weapon = isWeapon(player.getInventory().getItemInMainHand().getType());
        long memoryMs = (long) (plugin.getConfig().getDouble("combat.hit-memory-seconds", 60) * 1000);
        long[] rec = hits.computeIfAbsent(c.uuid(), k -> new java.util.HashMap<>())
                .computeIfAbsent(player.getUniqueId(), k -> new long[2]);
        if (now - rec[1] > memoryMs) rec[0] = 0;
        rec[0] += weapon ? plugin.getConfig().getInt("combat.weapon-counts-as", 2) : 1;
        rec[1] = now;

        // being hit interrupts salesmanship — stop inviting, and don't pester the attacker for a while
        clearPending(c);
        addressUntil.remove(c.uuid());
        setInviteCd(c, player.getUniqueId(), now, plugin.getConfig().getDouble("combat.no-invite-after-hit-seconds", 300));

        CombatState st = combat.get(c.uuid());
        if (st != null && !st.returning) {   // already fleeing/fighting — hitting it more keeps it going
            long ms = (long) (plugin.getConfig().getDouble(st.fighting ? "combat.fight-seconds" : "combat.flee-seconds",
                    st.fighting ? 15 : 12) * 1000);
            st.untilMs = now + ms;
            return;
        }
        String reaction = reactionFor(c);
        if (!"none".equals(reaction) && rec[0] >= plugin.getConfig().getInt("combat.react-after-hits", 3)) {
            beginCombat(c, player, "fightback".equals(reaction), now);
            return;
        }
        String t = weapon ? "hit_weapon" : (rec[0] > 1 ? "hit_again" : "hit_punch");
        DialogEngine.Speaker sp = speakerOf(c);
        if (dialog != null && !dialog.trigger(sp, t, player)) dialog.trigger(sp, "hit", player);
        MovementController m = movers.get(c.uuid());
        if (m != null) m.pauseFor(700);   // a hit visibly staggers it mid-stride
    }

    // --- sparring: a consensual duel that mirrors the player's weapon ------------------------------
    // The NPC NEVER initiates: sparring begins only when a player hits a spar-enabled NPC (optionally
    // only inside its arena region), mirrors their weapon class — sword answers sword, mace answers
    // mace, bow answers bow with real arrows and kiting — and stands down once the player stops.

    /** Absorb this hit into a spar (starting one if allowed). True → normal combat must not run. */
    private boolean trySpar(Character c, Player player, World w, long now) {
        if (!c.ai("spar", false) || !plugin.getConfig().getBoolean("spar.enabled", true)) return false;
        // sparring works at its post AND while hanging out at a space (an arena-space NPC is duelable
        // right where it visits) — just not mid-walk
        AwayState awaySt = away.get(c.uuid());
        if (awaySt != null && awaySt.phase != AwayState.OUT) return false;
        SparState st = spar.get(c.uuid());
        if (st != null) {
            if (!st.player.equals(player.getUniqueId())) return true;   // busy with another duelist
            st.lastActionMs = now;
            mirrorWeapon(c, st, player);
            applyHitKnockback(c, st, player, now);   // visibly reel back from the blow
            return true;
        }
        String region = c.sparRegion();
        if (region != null) {
            if (!dev.servereer.livingshopkeeper.shop.RegionGate.available()
                    || !dev.servereer.livingshopkeeper.shop.RegionGate.inside(
                            w, player.getLocation(), Set.of(region.toLowerCase(Locale.ROOT)))) {
                return false;   // outside the arena → the normal hit reaction applies
            }
        }
        st = new SparState(player.getUniqueId(), now);
        spar.put(c.uuid(), st);
        combat.remove(c.uuid());
        hits.remove(c.uuid());
        clearPending(c);
        pendingOutings.remove(c.uuid());   // a queued departure would fight the duel for the mover
        addressUntil.remove(c.uuid());
        setSeated(c, false);
        if (awaySt != null && spaces != null) spaces.releaseSeats(c.uuid());   // free the chair for others
        setCrouching(c, false);
        LobbyState ls = lobbyStates.remove(c.uuid());
        if (ls != null && ls.gossipWith != null) {
            Character partner = byUuid(ls.gossipWith);
            LobbyState ps = partner == null ? null : lobbyStates.get(partner.uuid());
            if (ps != null && c.uuid().equals(ps.gossipWith)) clearGossip(ps);
        }
        MovementController mover = movers.computeIfAbsent(c.uuid(), k -> newMover());
        mover.clearApproach();
        mover.setSpeedMultiplier(sparNum(c, "speed-multiplier", 1.2)
                * ("aggressive".equals(sparStyle(c)) ? 1.15 : 1.0));
        if (awaySt != null) awaySt.challengeTarget = null;   // the challenge was answered
        mirrorWeapon(c, st, player);
        String armor = sparArmor(c);
        if (armor != null) {
            for (UUID id : c.viewers) {
                Player v = Bukkit.getPlayer(id);
                if (v != null) PacketNpc.equipArmor(v, c, armor);   // suit up for the bout
            }
        }
        if (dialog != null) dialog.trigger(speakerOf(c), "spar_start", player);
        applyHitKnockback(c, st, player, now);   // the opening blow shoves it back too
        return true;
    }

    /** A short, ground-hugging shove back from the player's blow — the NPC visibly reacts to being hit. */
    private void applyHitKnockback(Character c, SparState st, Player player, long now) {
        if (st.windPhase != 0) return;   // mid-leap: don't interrupt the combo
        double dx = c.getX() - player.getX(), dz = c.getZ() - player.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 0.01) {   // dead-on top of us → shove along the player's facing
            double yaw = Math.toRadians(player.getLocation().getYaw());
            dx = -Math.sin(yaw); dz = Math.cos(yaw); len = Math.hypot(dx, dz);
            if (len < 0.01) { dx = 0; dz = 1; len = 1; }
        }
        boolean mace = player.getInventory().getItemInMainHand().getType() == org.bukkit.Material.MACE;
        double h = mace ? plugin.getConfig().getDouble("spar.hit-knockback-mace", 0.6)
                : plugin.getConfig().getDouble("spar.hit-knockback", 0.32);
        st.kbVx = dx / len * h;
        st.kbVz = dz / len * h;
        st.knockUntilMs = now + (mace ? 360 : 240);
    }

    /** The armor tier this character duels in, or null when it fights unarmored. */
    private String sparArmor(Character c) {
        String tier = c.sparArmor() != null ? c.sparArmor()
                : plugin.getConfig().getString("spar.armor", "iron");
        return tier == null || tier.isBlank() || tier.equalsIgnoreCase("none") ? null : tier;
    }

    /**
     * A spar stat for THIS character: its own {@code spar.<key>} from character.yml
     * (/lsk spar <id> set <key> <n>) wins; the global {@code spar.*} config is only the default.
     * Every fighter can carry unique damage/speed/cadence numbers.
     */
    private double sparNum(Character c, String key, double def) {
        Double own = c.sparStat(key);
        return own != null ? own : plugin.getConfig().getDouble("spar." + key, def);
    }

    private static String sparStyle(Character c) {
        String s = c.sparStyle();
        return s == null ? "balanced" : s;
    }

    /** Match the NPC's main hand to the player's current weapon (re-checked on every player hit). */
    private void mirrorWeapon(Character c, SparState st, Player player) {
        org.bukkit.Material m = player.getInventory().getItemInMainHand().getType();
        st.ranged = m == org.bukkit.Material.BOW || m == org.bukkit.Material.CROSSBOW;
        if (m == st.weapon) return;
        st.weapon = m;
        org.bukkit.inventory.ItemStack item = m.isAir() ? null : new org.bukkit.inventory.ItemStack(m);
        for (UUID id : c.viewers) {
            Player v = Bukkit.getPlayer(id);
            if (v != null) PacketNpc.equipMainHand(v, c, item);
        }
    }

    private void tickSpar(Character c, World w, MovementController mover, SparState st, long now) {
        Player p = Bukkit.getPlayer(st.player);
        Location npc = new Location(w, c.getX(), c.getY(), c.getZ());
        double giveUp = sparNum(c, "give-up-distance", 24);
        long idleMs = (long) (sparNum(c, "idle-end-seconds", 10) * 1000);
        if (p == null || !w.equals(p.getWorld())) { endSpar(c, mover, null); return; }
        if (p.isDead()) { sparVictory(c, mover, p, now); return; }   // a sporting "gg" before standing down
        // a signature move in flight (mace wind-smash) owns the tick until it lands
        if (st.windPhase != 0) { tickWindSmash(c, w, st, p, now); return; }
        // the player walked off or simply stopped fighting → bow out and walk home
        if (now - st.lastActionMs > idleMs || p.getLocation().distanceSquared(npc) > giveUp * giveUp) {
            endSpar(c, mover, p);
            return;
        }
        String style = sparStyle(c);
        faceViewers(c, w, p);
        // reeling from the player's blow — a brief ground-hugging shove back (kept on the floor by followY)
        if (now < st.knockUntilMs) {
            double nx = c.getX() + st.kbVx, nz = c.getZ() + st.kbVz;
            double ny = dev.servereer.livingshopkeeper.move.NavMeshSampler.followY(w, nx, c.getY(), nz, 3);
            moveNpc(c, nx, ny, nz);
            st.kbVx *= 0.7; st.kbVz *= 0.7;
            return;
        }
        // defensive: after landing a blow, break contact for a moment before re-engaging
        if (now < st.retreatUntilMs) {
            double dx = c.getX() - p.getX(), dz = c.getZ() - p.getZ();
            double len = Math.hypot(dx, dz);
            if (len < 0.01) { dx = 1; dz = 0; len = 1; }
            mover.approach(c.getX() + dx / len * 4, c.getY(), c.getZ() + dz / len * 4);
            sendMove(c, mover.tick(c, w, 0));
            return;
        }
        if (st.ranged) {
            double d2 = p.getLocation().distanceSquared(npc);
            // defensive archers keep more distance; aggressive ones press closer
            double keep = switch (style) { case "aggressive" -> 5; case "defensive" -> 9; default -> 6; };
            double maxR = switch (style) { case "aggressive" -> 13; case "defensive" -> 20; default -> 16; };
            if (d2 < keep * keep) {         // too close for a bow — kite away
                double dx = c.getX() - p.getX(), dz = c.getZ() - p.getZ();
                double len = Math.hypot(dx, dz);
                if (len < 0.01) { dx = 1; dz = 0; len = 1; }
                mover.approach(c.getX() + dx / len * keep, c.getY(), c.getZ() + dz / len * keep);
                sendMove(c, mover.tick(c, w, 0));
            } else if (d2 > maxR * maxR) {  // drifted out of bow range — close in a bit
                mover.approach(p.getX(), p.getY(), p.getZ());
                sendMove(c, mover.tick(c, w, 0));
            } else {
                mover.clearApproach();
                strafe(c, w, mover, st, p, now);   // never a stationary target between shots
            }
            if (now >= st.nextAttackMs) {
                long interval = (long) sparNum(c, "shot-interval-ms", 2200);
                if ("aggressive".equals(style)) interval = (long) (interval * 0.75);
                // occasional CHARGED power shot: a faster, harder, critical arrow with a heavier twang
                boolean power = now >= st.nextSpecialMs
                        && Math.random() < plugin.getConfig().getDouble("spar.bow-power-shot-chance", 0.3);
                st.nextAttackMs = now + interval + (long) (Math.random() * 500);
                swingViewers(c, now);
                Location eye = new Location(w, c.getX(), c.getY() + 1.5, c.getZ());
                org.bukkit.util.Vector dir = p.getEyeLocation().toVector().subtract(eye.toVector());
                if (dir.lengthSquared() < 1e-4) return;
                dir.normalize();
                float shotSpeed = power ? 3.0f : 1.7f;
                org.bukkit.entity.AbstractArrow arrow =
                        w.spawnArrow(eye.clone().add(dir.clone().multiply(0.8)), dir, shotSpeed, 3.5f);
                arrow.setDamage(sparNum(c, "arrow-damage", 1.0) * (power ? 1.8 : 1.0));
                arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
                if (power) {
                    arrow.setCritical(true);
                    st.nextSpecialMs = now + specialCd();
                    playKey(w, eye, "item.crossbow.loading_end", 1f, 0.9f);
                }
                w.playSound(eye, Sound.ENTITY_ARROW_SHOOT, 1f, power ? 0.8f : 1f);
            }
        } else {
            double reach = sparNum(c, "attack-reach", 2.6);
            if (p.getLocation().distanceSquared(npc) > reach * reach) {
                mover.approach(p.getX(), p.getY(), p.getZ());
                sendMove(c, mover.tick(c, w, 0));
            } else if (now >= st.nextAttackMs) {
                mover.clearApproach();
                String wn = st.weapon == null ? "" : st.weapon.name();
                boolean mace = wn.equals("MACE");
                boolean sword = wn.endsWith("_SWORD");
                boolean axe = wn.endsWith("_AXE");
                long cd = (long) sparNum(c, "swing-cooldown-ms", 900);
                cd = switch (style) { case "aggressive" -> (long) (cd * 0.75);
                    case "defensive" -> (long) (cd * 1.25); default -> cd; };

                // MACE SIGNATURE: wind-charge launch + falling smash. Take off now; the slam lands the hit.
                if (mace && now >= st.nextSpecialMs
                        && Math.random() < plugin.getConfig().getDouble("spar.mace-smash-chance", 0.5)) {
                    beginWindSmash(c, w, st, p, now);
                    return;
                }
                st.nextAttackMs = now + (mace ? (long) (cd * 1.35) : cd);
                swingViewers(c, now);
                double dmg = sparNum(c, "damage", 1.0);
                double kbAmount = sparNum(c, "knockback", 0.25);
                double yLift = 0.22;
                if (mace) {                       // plain mace swings still hit heavier + shove harder
                    dmg *= plugin.getConfig().getDouble("spar.mace-damage-multiplier", 1.4);
                    kbAmount *= plugin.getConfig().getDouble("spar.mace-knockback-multiplier", 1.8);
                    yLift = 0.42;
                    playKey(w, p.getLocation(), "entity.mace.smash_ground", 1f, 1f);
                } else if (sword && now >= st.nextSpecialMs
                        && Math.random() < plugin.getConfig().getDouble("spar.sword-crit-chance", 0.3)) {
                    dmg *= plugin.getConfig().getDouble("spar.sword-crit-multiplier", 1.6);   // jump-crit
                    st.nextSpecialMs = now + specialCd();
                    w.spawnParticle(particle("CRIT", org.bukkit.Particle.CRIT),
                            p.getLocation().add(0, 1, 0), 14, 0.3, 0.5, 0.3, 0.1);
                    w.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
                } else if (axe && p.isBlocking()
                        && plugin.getConfig().getBoolean("spar.axe-shield-break", true)) {
                    p.setCooldown(org.bukkit.Material.SHIELD,               // an axe knocks the guard aside
                            plugin.getConfig().getInt("spar.axe-shield-disable-ticks", 100));
                    st.nextSpecialMs = now + specialCd();
                    w.playSound(p.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);
                    dmg *= 1.2;
                }
                p.damage(dmg);
                org.bukkit.util.Vector kb = p.getLocation().toVector().subtract(npc.toVector());
                kb.setY(0);
                if (kb.lengthSquared() < 1e-4) kb = new org.bukkit.util.Vector(0, 0, 0.01);
                kb.normalize().multiply(kbAmount).setY(yLift);
                p.setVelocity(p.getVelocity().add(kb));
                if (!mace) w.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.7f, 1.1f);
                if (dialog != null) dialog.trigger(speakerOf(c), "spar_hit", p);
                if ("defensive".equals(style)) st.retreatUntilMs = now + 1100;   // hit and step out
            } else {
                strafe(c, w, mover, st, p, now);   // circle while the swing recharges
            }
        }
    }

    /** Sidestep around the opponent between attacks — a duelist circles, it doesn't stand and wait. */
    private void strafe(Character c, World w, MovementController mover, SparState st, Player p, long now) {
        if (now < st.nextStrafeMs) {
            sendMove(c, mover.tick(c, w, 0));
            return;
        }
        st.nextStrafeMs = now + 550 + (long) (Math.random() * 500);
        if (Math.random() < 0.25) st.strafeSign = -st.strafeSign;
        double dx = c.getX() - p.getX(), dz = c.getZ() - p.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 0.01) return;
        // perpendicular to the line between them, keeping roughly the current distance
        double px = -dz / len * st.strafeSign, pz = dx / len * st.strafeSign;
        mover.approach(c.getX() + px * 1.4, c.getY(), c.getZ() + pz * 1.4);
        sendMove(c, mover.tick(c, w, 0));
    }

    // --- mace wind-burst smash: launch skyward on a wind charge, then fall onto the target ------------

    private long specialCd() {
        return (long) (plugin.getConfig().getDouble("spar.special-cooldown-seconds", 4) * 1000);
    }

    /** Kick off the wind-smash: shove off the ground with a wind charge and start rising. */
    private void beginWindSmash(Character c, World w, SparState st, Player p, long now) {
        st.windPhase = 1;
        st.windBaseY = c.getY();
        st.slamX = p.getX();
        st.slamZ = p.getZ();
        st.nextSpecialMs = now + specialCd();
        st.lastActionMs = now;                 // the NPC is very much still engaged mid-combo
        st.nextAttackMs = now + 2200;
        Location at = new Location(w, c.getX(), c.getY(), c.getZ());
        playKey(w, at, "entity.wind_charge.throw", 1.2f, 1f);
        w.spawnParticle(particle("GUST", org.bukkit.Particle.CLOUD), at.clone().add(0, 0.2, 0), 1);
        swingViewers(c, now);
    }

    /**
     * Drive the airborne combo: rise on the wind charge, hang for a beat locking onto where the target is,
     * then drop and — on landing — deliver the falling smash (heavy scripted damage + a gust of knockback,
     * with the wind-burst + ground-smash effects). The NPC is a packet body, so its flight is choreographed
     * directly via move packets.
     */
    private void tickWindSmash(Character c, World w, SparState st, Player p, long now) {
        faceViewers(c, w, p);
        double apex = st.windBaseY + plugin.getConfig().getDouble("spar.mace-launch-height", 4.5);
        if (st.windPhase == 1) {               // rising — deliberately slow so the client renders the FULL
            double rise = plugin.getConfig().getDouble("spar.mace-rise-speed", 0.28);   // height, not a blur
            double ny = Math.min(apex, c.getY() + rise);
            moveNpc(c, c.getX(), ny, c.getZ());
            w.spawnParticle(particle("GUST", org.bukkit.Particle.CLOUD), c.getX(), c.getY(), c.getZ(), 1, 0.1, 0.1, 0.1, 0);
            if (ny >= apex - 0.03) { st.windPhase = 2; st.windPhaseUntil = now + plugin.getConfig().getLong("spar.mace-hang-ms", 650); }
            return;
        }
        if (st.windPhase == 2) {               // HOVER at the top — a clear telegraph, tracking the target
            st.slamX = p.getX();
            st.slamZ = p.getZ();
            w.spawnParticle(particle("GUST", org.bukkit.Particle.CLOUD), c.getX(), c.getY() + 0.5, c.getZ(), 2, 0.25, 0.1, 0.25, 0);
            if (now >= st.windPhaseUntil) st.windPhase = 3;
            return;
        }
        // slamming — descend fast, HOMING onto the target so the hit visibly connects
        st.slamX = p.getX();
        st.slamZ = p.getZ();
        double slam = plugin.getConfig().getDouble("spar.mace-slam-speed", 0.9);
        double ndx = st.slamX - c.getX(), ndz = st.slamZ - c.getZ();
        double stepX = c.getX() + Math.max(-slam, Math.min(slam, ndx));
        double stepZ = c.getZ() + Math.max(-slam, Math.min(slam, ndz));
        double ny = Math.max(st.windBaseY, c.getY() - slam);
        moveNpc(c, stepX, ny, stepZ);
        w.spawnParticle(particle("GUST", org.bukkit.Particle.CLOUD), c.getX(), c.getY() + 1, c.getZ(), 2, 0.1, 0.3, 0.1, 0);
        if (ny <= st.windBaseY + 0.25) {        // LANDED — the smash
            moveNpc(c, st.slamX, st.windBaseY, st.slamZ);
            Location land = new Location(w, st.slamX, st.windBaseY, st.slamZ);
            w.spawnParticle(particle("GUST_EMITTER_LARGE", org.bukkit.Particle.EXPLOSION), land.clone().add(0, 0.2, 0), 1);
            w.spawnParticle(particle("SONIC_BOOM", org.bukkit.Particle.EXPLOSION_EMITTER), land.clone().add(0, 0.4, 0), 1);
            playKey(w, land, "entity.wind_charge.wind_burst", 1.5f, 0.8f);
            playKey(w, land, "entity.mace.smash_ground_heavy", 1.2f, 1f);
            playKey(w, land, "entity.mace.smash_ground", 1f, 1f);
            swingViewers(c, now);
            double smashDmg = sparNum(c, "damage", 1.0)
                    * plugin.getConfig().getDouble("spar.mace-smash-damage-multiplier", 2.5);
            double radius = plugin.getConfig().getDouble("spar.mace-smash-radius", 4.0);
            double kbH = plugin.getConfig().getDouble("spar.mace-smash-knockback", 1.1);
            double kbUp = plugin.getConfig().getDouble("spar.mace-smash-launch", 1.2);
            for (Player near : w.getPlayers()) {
                double d2 = near.getLocation().distanceSquared(land);
                if (d2 > radius * radius) continue;
                boolean target = near.getUniqueId().equals(st.player);
                if (target) near.damage(smashDmg);                       // full smash to the duelist
                org.bukkit.util.Vector kb = near.getLocation().toVector().subtract(land.toVector());
                kb.setY(0);
                if (kb.lengthSquared() < 1e-4) kb = new org.bukkit.util.Vector(Math.random() - 0.5, 0, Math.random() - 0.5);
                kb.normalize().multiply(kbH * (target ? 1.0 : 0.6)).setY(kbUp * (target ? 1.0 : 0.7));
                near.setVelocity(near.getVelocity().add(kb));       // launched up + out by the wind burst
            }
            if (dialog != null) dialog.trigger(speakerOf(c), "spar_hit", p);
            st.windPhase = 0;
            st.lastActionMs = now;
            st.nextAttackMs = now + (long) (sparNum(c, "swing-cooldown-ms", 900) * 1.6);
        }
    }

    /** Move the packet body to an absolute point, animating the delta to every viewer (used for leaps). */
    private void moveNpc(Character c, double nx, double ny, double nz) {
        double dx = nx - c.getX(), dy = ny - c.getY(), dz = nz - c.getZ();
        if (Math.abs(dx) < 1e-4 && Math.abs(dy) < 1e-4 && Math.abs(dz) < 1e-4) return;
        c.setPosition(nx, ny, nz);
        for (UUID id : c.viewers) {
            Player v = Bukkit.getPlayer(id);
            if (v != null) PacketNpc.move(v, c, dx, dy, dz, c.movementYaw(), false);
        }
    }

    /** Play a sound by namespaced key (some spar/wind sounds have no Bukkit enum constant), if it exists. */
    private void playKey(World w, Location at, String key, float vol, float pitch) {
        Sound s = org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.minecraft(key));
        if (s != null) w.playSound(at, s, vol, pitch);
    }

    /** Resolve a particle by name with a fallback, so a version without it still compiles + runs. */
    private static org.bukkit.Particle particle(String name, org.bukkit.Particle fallback) {
        try {
            return org.bukkit.Particle.valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * The opponent went down: a little victory celebration and a sporting "gg" — the authored
     * {@code spar_win} line if there is one, else an AI-generated one (ai-chat providers), else a
     * random {@code spar.gg-lines} config line. Then stand down as usual.
     */
    private void sparVictory(Character c, MovementController mover, Player dead, long now) {
        World w = Bukkit.getWorld(c.getWorld());
        if (w != null) {
            faceViewers(c, w, dead);
            swingViewers(c, now);   // raise the weapon…
            for (int i = 0; i < 4; i++) {   // …and a little victory hop-bob
                boolean down = i % 2 == 0;
                schedule(now + 400 + i * 220L, () -> setCrouching(c, down));
            }
            schedule(now + 1400, () -> setCrouching(c, false));
        }
        boolean spoke = w != null && dialog != null && dialog.trigger(speakerOf(c), "spar_win", dead);
        if (!spoke && w != null) {
            boolean requested = aiChat != null && aiChat.tryGenerate("sparwin:" + c.uuid(),
                    plainName(c), c.persona(), dead.getName(), "the player you just defeated",
                    "You just won a friendly practice duel against " + dead.getName()
                            + " in the arena. Say GG — one short, sporting, good-natured line.",
                    line -> {
                        World lw = Bukkit.getWorld(c.getWorld());
                        if (line != null && lw != null) broadcastLine(c, lw, line);
                    });
            if (!requested) {
                List<String> pool = plugin.getConfig().getStringList("spar.gg-lines");
                String line = pool.isEmpty() ? "GG, <player> — good bout!"
                        : pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
                broadcastLine(c, w, line.replace("<player>", dead.getName()));
            }
        }
        endSpar(c, mover, null);   // null: spar_end stays quiet — the gg was the goodbye
    }

    private void endSpar(Character c, MovementController mover, Player p) {
        spar.remove(c.uuid());
        for (UUID id : c.viewers) {
            Player v = Bukkit.getPlayer(id);
            if (v != null) {
                PacketNpc.equipMainHand(v, c, null);   // sheathe the mirrored weapon
                PacketNpc.equipArmor(v, c, null);      // and take the battle kit off
            }
        }
        mover.setSpeedMultiplier(1.0);
        mover.clearApproach();
        if (p != null && dialog != null) dialog.trigger(speakerOf(c), "spar_end", p);
        // still on an outing → tickAway resumes at the space (re-seat/stroll, or head home if overdue)
        AwayState as = away.get(c.uuid());
        if (as != null) {
            as.nextIdleMs = 0;
            return;
        }
        // walk home via the existing combat return path
        double[] post = c.home() != null ? c.home()
                : new double[]{c.getX(), c.getY(), c.getZ(), c.yaw(), c.pitch()};
        CombatState back = new CombatState(c.uuid(), false, 0, post);
        back.returning = true;
        combat.put(c.uuid(), back);
    }

    /** flee | fightback | none for this character (its character.yml override, else the global config). */
    private String reactionFor(Character c) {
        String own = c.combatReaction();
        return (own != null ? own : plugin.getConfig().getString("combat.reaction", "flee")).toLowerCase(Locale.ROOT);
    }

    /** Persist a per-character combat reaction override ({@code null} → follow the global config). */
    public void setCombatReaction(Character c, String mode) {
        c.setCombatReaction(mode);
        persist(c);
    }

    private static boolean isWeapon(org.bukkit.Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.equals("TRIDENT") || n.equals("MACE");
    }

    private void beginCombat(Character c, Player attacker, boolean fight, long now) {
        long ms = (long) (plugin.getConfig().getDouble(fight ? "combat.fight-seconds" : "combat.flee-seconds",
                fight ? 15 : 12) * 1000);
        // Non-barkers walk back to their post afterwards; a barker returns to home (or just resumes patrol).
        double[] post = c.type() != CharacterType.BARKER
                ? new double[]{c.getX(), c.getY(), c.getZ(), c.yaw(), c.pitch()}
                : c.home();
        combat.put(c.uuid(), new CombatState(attacker.getUniqueId(), fight, now + ms, post));
        MovementController mover = movers.computeIfAbsent(c.uuid(), k -> newMover());
        mover.clearApproach();
        mover.pauseFor(0);
        mover.setSpeedMultiplier(plugin.getConfig().getDouble(
                fight ? "combat.fight-speed-multiplier" : "combat.flee-speed-multiplier", fight ? 1.35 : 1.6));
        setSeated(c, false);
        setCrouching(c, false);
        if (dialog != null) dialog.trigger(speakerOf(c), fight ? "fightback" : "flee", attacker);
    }

    private void tickCombat(Character c, World w, MovementController mover, CombatState st, long now) {
        if (st.returning) {
            double[] post = st.returnPost;
            if (post == null) { endCombat(c, mover); return; }   // barker with no home → resume patrol from here
            double dx = post[0] - c.getX(), dz = post[2] - c.getZ();
            if (dx * dx + dz * dz <= 0.45 * 0.45) {
                snapTo(c, post[0], post[1], post[2], (float) post[3], post.length > 4 ? (float) post[4] : 0f);
                endCombat(c, mover);
                return;
            }
            mover.approach(post[0], post[1], post[2]);
            sendMove(c, mover.tick(c, w, 0));
            return;
        }

        Player att = Bukkit.getPlayer(st.attacker);
        Location npc = new Location(w, c.getX(), c.getY(), c.getZ());
        boolean gone = att == null || !w.equals(att.getWorld()) || att.isDead();
        double giveUp = plugin.getConfig().getDouble("combat.give-up-distance", 24);
        if (now >= st.untilMs || gone || att.getLocation().distanceSquared(npc) > giveUp * giveUp) {
            if (!gone && dialog != null) dialog.trigger(speakerOf(c), "calm", att);
            st.returning = true;
            mover.clearApproach();
            mover.setSpeedMultiplier(1.0);
            return;
        }

        if (st.fighting) {
            double reach = plugin.getConfig().getDouble("combat.attack-reach", 2.6);
            if (att.getLocation().distanceSquared(npc) > reach * reach) {
                mover.approach(att.getX(), att.getY(), att.getZ());
                sendMove(c, mover.tick(c, w, 0));
            } else {
                mover.clearApproach();
                faceViewers(c, w, att);
                if (now >= st.nextSwingMs) {
                    st.nextSwingMs = now + plugin.getConfig().getLong("combat.swing-cooldown-ms", 900);
                    swingViewers(c, now);
                    // Scripted damage + shove, not PvP — so it lands even inside protected regions.
                    att.damage(plugin.getConfig().getDouble("combat.damage", 2.0));
                    org.bukkit.util.Vector kb = att.getLocation().toVector().subtract(npc.toVector());
                    kb.setY(0);
                    if (kb.lengthSquared() < 1e-4) kb = new org.bukkit.util.Vector(0, 0, 0.01);
                    kb.normalize().multiply(plugin.getConfig().getDouble("combat.knockback", 0.4)).setY(0.32);
                    att.setVelocity(att.getVelocity().add(kb));
                    w.playSound(att.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 1f);
                    if (dialog != null) dialog.trigger(speakerOf(c), "fight_hit", att);
                }
            }
        } else {
            // flee: re-aim away from the attacker about once a second (not every tick — A* isn't free)
            if (now >= st.nextRepathMs) {
                st.nextRepathMs = now + 700;
                double dx = c.getX() - att.getX(), dz = c.getZ() - att.getZ();
                double len = Math.hypot(dx, dz);
                if (len < 0.01) { dx = Math.random() - 0.5; dz = Math.random() - 0.5; len = Math.hypot(dx, dz); }
                double dist = plugin.getConfig().getDouble("combat.flee-distance", 10);
                mover.approach(c.getX() + dx / len * dist, c.getY(), c.getZ() + dz / len * dist);
            }
            sendMove(c, mover.tick(c, w, 0));
        }
    }

    private void endCombat(Character c, MovementController mover) {
        combat.remove(c.uuid());
        mover.setSpeedMultiplier(1.0);
        mover.clearApproach();
        if (c.type() != CharacterType.BARKER && c.type() != CharacterType.LOBBY && c.type() != CharacterType.SHOP) {
            movers.remove(c.uuid());
        }
    }

    // --- lobby behavior ----------------------------------------------------

    /**
     * The hub/shop life: a player near the post (LOBBY) or inside the shop's region (SHOP) → run back,
     * attend (a SHOP prefers its priority seat), greet, and hold while they stay. Otherwise roam small
     * distances around the post, sit on wand-marked seats — and a SHOP sometimes wanders over to a
     * fellow shopkeeper for a chat until a customer shows up.
     */
    private void tickLobby(Character c, World w, MovementController mover, long now) {
        LobbyState st = lobbyStates.computeIfAbsent(c.uuid(), k -> new LobbyState());
        double[] home = c.home();
        double px = home != null ? home[0] : c.getX();
        double py = home != null ? home[1] : c.getY();
        double pz = home != null ? home[2] : c.getZ();
        float pyaw = home != null ? (float) home[3] : c.yaw();
        float ppitch = home != null ? (float) home[4] : 0f;
        boolean isShop = c.type() == CharacterType.SHOP;

        Player visitor;
        if (isShop) {
            visitor = shopVisitor(c, w, px, py, pz);
        } else {
            visitor = nearestPlayer(w, px, py, pz, plugin.getConfig().getDouble("lobby.trigger-radius", 2.0));
        }

        if (visitor != null) {
            if (st.gossipWith != null) {   // customers beat gossip — excuse yourself and rush back in
                Character partner = byUuid(st.gossipWith);
                LobbyState ps = partner == null ? null : lobbyStates.get(partner.uuid());
                if (ps != null && c.uuid().equals(ps.gossipWith)) clearGossip(ps);
                clearGossip(st);
            }
            st.stayUntilMs = now + (long) (plugin.getConfig().getDouble("lobby.stay-seconds", 15) * 1000);
            st.lastVisitor = visitor.getUniqueId();

            // where to attend: a SHOP with a priority seat sits there; otherwise stand at the post
            double[] seat = isShop ? c.prioritySeat() : null;
            double ax = seat != null ? seat[0] : px;
            double ay = seat != null ? seat[1] : py;
            double az = seat != null ? seat[2] : pz;

            double dx = ax - c.getX(), dz = az - c.getZ();
            if (dx * dx + dz * dz > 0.35 * 0.35) {
                // caught away from the counter → hustle back
                if (c.seated) { setSeated(c, false); st.sitUntilMs = 0; }
                st.target = null;
                mover.setSpeedMultiplier(plugin.getConfig().getDouble("lobby.run-speed-multiplier", 1.6));
                mover.approach(ax, ay, az);
                sendMove(c, mover.tick(c, w, 0));
                st.attending = false;   // wave fires on arrival
            } else {
                mover.clearApproach();
                mover.setSpeedMultiplier(1.0);
                if (!st.attending) {
                    st.attending = true;
                    snapTo(c, ax, ay, az, seat != null ? (float) seat[3] : pyaw, seat != null ? 0f : ppitch);
                    setSeated(c, seat != null);
                    swingViewers(c, now);   // the little "at your service" wave
                    if (dialog != null) dialog.trigger(speakerOf(c), "greet", visitor);
                }
                float[] yp = lookAt(new Location(w, c.getX(), c.getY(), c.getZ()), visitor.getEyeLocation());
                faceAll(c, yp[0], yp[1]);
            }
            return;
        }

        // the last visitor walked out — thank recent buyers on their way
        if (st.lastVisitor != null) {
            farewell(c, st.lastVisitor, now);
            st.lastVisitor = null;
        }
        if (st.attending && c.seated && (!isShop || c.prioritySeat() == null)) setSeated(c, false);
        st.attending = false;

        if (isShop) tickHawk(c, w, st, now);   // the crier: shout wares at passers-by (owner opt-in)

        // mid-gossip: the lead drives the chat, the partner stands in and listens/replies
        if (st.gossipWith != null) {
            if (st.gossipLead) tickGossip(c, w, mover, st, now);
            else tickGossipPassive(c, mover, st, now);
            return;
        }

        if (now < st.stayUntilMs) {   // linger at the post a while after the last visitor leaves
            mover.clearApproach();
            mover.setSpeedMultiplier(1.0);
            idleFidget(c, now);
            return;
        }

        // seated: stay put until the sit runs out
        if (c.seated) {
            if (now >= st.sitUntilMs) {
                setSeated(c, false);
                st.sitUntilMs = 0;
                st.nextWanderMs = now + jitter(plugin.getConfig().getDouble("lobby.wander-cooldown-seconds", 18));
            }
            return;
        }

        // walking toward a wander target / seat
        if (st.target != null) {
            double dx = st.target[0] - c.getX(), dz = st.target[2] - c.getZ();
            if (dx * dx + dz * dz <= 0.35 * 0.35) {
                if (st.targetIsSeat) {
                    snapTo(c, st.target[0], st.target[1], st.target[2], (float) st.target[3], 0f);
                    setSeated(c, true);
                    st.sitUntilMs = now + jitter(plugin.getConfig().getDouble("lobby.sit-seconds", 25));
                } else {
                    st.nextWanderMs = now + jitter(plugin.getConfig().getDouble("lobby.wander-cooldown-seconds", 18));
                }
                st.target = null;
                mover.clearApproach();
            } else {
                mover.approach(st.target[0], st.target[1], st.target[2]);
                sendMove(c, mover.tick(c, w, 0));
            }
            return;
        }

        // idle at rest → pick the next little outing
        idleFidget(c, now);
        if (now < st.nextWanderMs) return;
        st.nextWanderMs = now + jitter(plugin.getConfig().getDouble("lobby.wander-cooldown-seconds", 18));
        mover.setSpeedMultiplier(plugin.getConfig().getDouble("lobby.walk-speed-multiplier", 0.85));
        // Both shops and hub LOBBY NPCs chatter with nearby colleagues while idle (a lively spawn/market).
        boolean social = isShop || c.type() == CharacterType.LOBBY;
        if (social && c.ai("gossip", true) && plugin.getConfig().getBoolean("gossip.enabled", true)
                && Math.random() < plugin.getConfig().getDouble("shop.gossip-chance", 0.35)) {
            Character partner = nearbyColleague(c, w, plugin.getConfig().getDouble("shop.gossip-range", 16));
            if (partner != null) {
                st.gossipWith = partner.uuid();
                st.gossipLead = true;
                st.gossipStarted = false;
                st.gossipUntilMs = now + jitter(plugin.getConfig().getDouble("shop.gossip-seconds", 22));
                st.nextGossipLineMs = 0;
                // the partner knows it's being visited: it stays put and takes part instead of wandering off
                LobbyState ps = lobbyStates.computeIfAbsent(partner.uuid(), k -> new LobbyState());
                ps.gossipWith = c.uuid();
                ps.gossipLead = false;
                ps.gossipStarted = false;
                return;
            }
        }
        List<double[]> seats = c.seats();
        if (!seats.isEmpty() && c.ai("sit", true) && Math.random() < plugin.getConfig().getDouble("lobby.sit-chance", 0.35)) {
            double[] seat = seats.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(seats.size()));
            st.target = seat;
            st.targetIsSeat = true;
            return;
        }
        if (!roamOn(c)) return;   // roaming disabled → stand at the post (seats/gossip still apply)
        double roam = Math.max(2, plugin.getConfig().getDouble("lobby.roam-radius", 8));
        for (int attempt = 0; attempt < 4; attempt++) {
            double ang = Math.random() * Math.PI * 2;
            double r = 2 + Math.random() * (roam - 2);
            double tx = px + Math.cos(ang) * r, tz = pz + Math.sin(ang) * r;
            int gy = dev.servereer.livingshopkeeper.move.NavMeshSampler.groundY(
                    w, (int) Math.floor(tx), (int) Math.floor(py), (int) Math.floor(tz), 4);
            if (gy == Integer.MIN_VALUE) continue;
            st.target = new double[]{tx, gy, tz, Float.NaN};
            st.targetIsSeat = false;
            return;
        }
    }

    /**
     * The customer a SHOP should attend: inside the shop's WorldGuard region(s) when regions exist at
     * its post (the ARM plot), else within {@code shop.attend-radius} of the post.
     */
    private Player shopVisitor(Character c, World w, double px, double py, double pz) {
        java.util.Set<String> regions = shopRegions.computeIfAbsent(c.uuid(), k ->
                dev.servereer.livingshopkeeper.shop.RegionGate.available()
                        ? dev.servereer.livingshopkeeper.shop.RegionGate.regionIdsAt(new Location(w, px, py, pz))
                        : java.util.Set.of());
        Location post = new Location(w, px, py, pz);
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        double fallbackR = plugin.getConfig().getDouble("shop.attend-radius", 8);
        for (Player p : w.getPlayers()) {
            if (c.owner() != null && p.getUniqueId().equals(c.owner())) continue;   // the owner isn't a customer
            double d2 = p.getLocation().distanceSquared(post);
            if (d2 > 48 * 48) continue;   // sanity cap for huge regions
            boolean inside = regions.isEmpty()
                    ? d2 <= fallbackR * fallbackR
                    : dev.servereer.livingshopkeeper.shop.RegionGate.inside(w, p.getLocation(), regions);
            if (inside && d2 < bestSq) { bestSq = d2; best = p; }
        }
        return best;
    }

    /** Another idle shopkeeper within range to chat with, or null. */
    private Character nearbyColleague(Character c, World w, double range) {
        Character best = null;
        double bestSq = range * range;
        for (Character other : byId.values()) {
            // shops chat with shops, hub lobby NPCs with hub lobby NPCs — both run the tickLobby life
            boolean sameSocial = other.type() == c.type()
                    || (other.type() == CharacterType.SHOP && c.type() == CharacterType.LOBBY)
                    || (other.type() == CharacterType.LOBBY && c.type() == CharacterType.SHOP);
            if (other == c || !sameSocial || (other.type() != CharacterType.SHOP && other.type() != CharacterType.LOBBY)
                    || !w.getName().equals(other.getWorld())) continue;
            if (away.containsKey(other.uuid()) || pendingOutings.containsKey(other.uuid())
                    || combat.containsKey(other.uuid()) || spar.containsKey(other.uuid())) continue;
            LobbyState os = lobbyStates.get(other.uuid());
            if (os != null && (os.attending || os.gossipWith != null)) continue;   // busy
            double dx = other.getX() - c.getX(), dz = other.getZ() - c.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 < bestSq && d2 > 1) { bestSq = d2; best = other; }
        }
        return best;
    }

    /**
     * The lead side of a gossip: walk over, then hold an actual back-and-forth — opener
     * ({@code gossip_start}), alternating {@code gossip}/{@code gossip_reply} beats with reactions, and a
     * {@code gossip_end} sign-off with goodbye waves. Every beat falls back to the config small-talk pool,
     * so the conversation is visible and audible even with zero authored dialog.
     */
    private void tickGossip(Character c, World w, MovementController mover, LobbyState st, long now) {
        Character partner = byUuid(st.gossipWith);
        LobbyState ps = partner == null ? null : lobbyStates.get(partner.uuid());
        boolean linkBroken = partner == null || ps == null || !c.uuid().equals(ps.gossipWith)
                || !w.getName().equals(partner.getWorld()) || away.containsKey(partner.uuid());
        if (linkBroken || now >= st.gossipUntilMs) {
            if (!linkBroken && st.gossipStarted) {
                chatBeat(c, partner, w, now, "gossip_end", "gossip");
                Character p2 = partner;
                schedule(now + 700, () -> swingViewers(p2, System.currentTimeMillis()));   // goodbye wave back
            }
            if (ps != null && !linkBroken) clearGossip(ps);
            if (partner != null && aiChat != null) aiChat.endConversation(convKey(c, partner));
            clearGossip(st);
            st.nextWanderMs = now + jitter(plugin.getConfig().getDouble("lobby.wander-cooldown-seconds", 18));
            mover.clearApproach();
            return;
        }
        double dx = partner.getX() - c.getX(), dz = partner.getZ() - c.getZ();
        if (dx * dx + dz * dz > 1.8 * 1.8) {
            mover.approach(partner.getX(), partner.getY(), partner.getZ());
            sendMove(c, mover.tick(c, w, 0));
            return;
        }
        mover.clearApproach();
        faceEachOther(c, partner, w);
        if (!st.gossipStarted) {
            st.gossipStarted = true;
            st.gossipMyTurn = false;   // we opened; the partner answers next
            chatBeat(c, partner, w, now, "gossip_start", "gossip");
            st.nextGossipLineMs = now + gossipLineDelay();
            return;
        }
        if (now >= st.nextGossipLineMs) {
            st.nextGossipLineMs = now + gossipLineDelay();
            if (st.gossipMyTurn) chatBeat(c, partner, w, now, "gossip");
            else chatBeat(partner, c, w, now, "gossip_reply", "gossip");
            st.gossipMyTurn = !st.gossipMyTurn;
        }
    }

    /** The visited side: stand still and take part (the lead runs the beats); bail if the lead moved on. */
    private void tickGossipPassive(Character c, MovementController mover, LobbyState st, long now) {
        Character lead = byUuid(st.gossipWith);
        LobbyState ls = lead == null ? null : lobbyStates.get(lead.uuid());
        if (lead == null || ls == null || !c.uuid().equals(ls.gossipWith)) {
            clearGossip(st);
            st.nextWanderMs = now + jitter(plugin.getConfig().getDouble("lobby.wander-cooldown-seconds", 18));
            return;
        }
        mover.clearApproach();
    }

    private void clearGossip(LobbyState st) {
        if (st == null) return;
        st.gossipWith = null;
        st.gossipLead = false;
        st.gossipStarted = false;
        st.gossipMyTurn = false;
    }

    /** Toggle an owner-facing AI behavior ("roam" | "sit" | "gossip" | "hawk") and persist it. */
    public void setAi(Character c, String key, boolean on) {
        c.setAi(key, on);
        persist(c);
    }

    /**
     * The crier (opt-in per shop): every {@code shop.hawk-seconds} ± jitter, if someone is within
     * {@code shop.hawk-range}, fire the authored {@code hawk} dialog line — or, when none exists,
     * shout a random ware and its price at them.
     */
    private void tickHawk(Character c, World w, LobbyState st, long now) {
        if (!c.ai("hawk", false) || c.trades().isEmpty()) return;
        if (st.nextHawkMs == 0) {   // arm on first sight rather than shouting the instant the server starts
            st.nextHawkMs = now + (long) (Math.random() * 15_000) + 5_000;
            return;
        }
        if (now < st.nextHawkMs) return;
        Player target = nearestPlayer(w, c.getX(), c.getY(), c.getZ(),
                plugin.getConfig().getDouble("shop.hawk-range", 12));
        if (target == null) return;
        st.nextHawkMs = now + (long) (plugin.getConfig().getDouble("shop.hawk-seconds", 45) * 1000)
                + (long) (Math.random() * 15_000);
        swingViewers(c, now);
        if (dialog != null && dialog.trigger(speakerOf(c), "hawk", target)) return;
        dev.servereer.livingshopkeeper.shop.ShopTrade t =
                c.trades().get(java.util.concurrent.ThreadLocalRandom.current().nextInt(c.trades().size()));
        target.sendMessage(dev.servereer.livingshopkeeper.dialog.DialogText.mini(
                "<gold>" + c.display() + "</gold><yellow> calls out: </yellow><white>"
                        + dev.servereer.livingshopkeeper.shop.ShopEngine.describe(t.result())
                        + "</white><gray> — yours for </gray><white>"
                        + dev.servereer.livingshopkeeper.shop.ShopEngine.describe(t.a())
                        + (t.b() != null ? " <gray>+</gray> " + dev.servereer.livingshopkeeper.shop.ShopEngine.describe(t.b()) : "")
                        + "</white><gray>!</gray>"));
    }

    /** A SHOP purchase went through: thank the buyer now and remember them for the goodbye later. */
    public void onShopPurchase(Player buyer, Character c) {
        if (dialog != null) dialog.trigger(speakerOf(c), "thanks", buyer);
        recentBuyers.computeIfAbsent(c.uuid(), k -> new java.util.HashMap<>()).put(buyer.getUniqueId(),
                System.currentTimeMillis());
    }

    /** The visitor left the shop — if they bought something recently, wish them well on the way out. */
    private void farewell(Character c, UUID visitor, long now) {
        Map<UUID, Long> buyers = recentBuyers.get(c.uuid());
        if (buyers == null) return;
        Long boughtAt = buyers.get(visitor);
        if (boughtAt == null) return;
        buyers.remove(visitor);
        if (now - boughtAt > 300_000) return;   // stale
        Player p = Bukkit.getPlayer(visitor);
        if (p == null || !p.getWorld().getName().equals(c.getWorld())) return;
        Location npc = new Location(p.getWorld(), c.getX(), c.getY(), c.getZ());
        if (p.getLocation().distanceSquared(npc) > hearingSq) return;
        if (dialog != null) dialog.trigger(speakerOf(c), "farewell", p);
        faceViewers(c, p.getWorld(), p);
        swingViewers(c, now);   // a goodbye wave
    }

    // --- outings: leave the post for a shared Space, a ghost head minding the counter ---------------

    /** Whether this character is currently out (or queued to leave). */
    public boolean isAway(Character c) {
        return away.containsKey(c.uuid()) || pendingOutings.containsKey(c.uuid());
    }

    /** Ghost entities are click-through to their character even when punched. */
    public boolean isGhost(int entityId) {
        return ghostIds.contains(entityId);
    }

    /**
     * Whether this character may leave on scheduled/group outings. Shops are OPT-IN (default from
     * {@code outings.default-enabled}, off unless the owner turns it on in the shop's Outings menu);
     * lobby/barker NPCs keep the historical default-on behaviour. An explicit {@code ai.outings} in the
     * character file always wins.
     */
    private boolean outingsEnabled(Character c) {
        boolean def = c.type() != CharacterType.SHOP
                || plugin.getConfig().getBoolean("outings.default-enabled", false);
        return c.ai("outings", def);
    }

    /** Public view of {@link #outingsEnabled} for the shop GUI's Outings toggle. */
    public boolean outingsOn(Character c) { return outingsEnabled(c); }

    /**
     * Whether this NPC idly roams around its post. SHOPS default to NOT roaming (they stand at the counter
     * — wandering shopkeepers phased through terrain), lobby/barker keep the old default-on. The owner can
     * turn roaming on per shop from the Behavior menu (an explicit {@code ai.roam} always wins).
     */
    public boolean roamOn(Character c) {
        return c.ai("roam", c.type() != CharacterType.SHOP);
    }

    /**
     * Resolve a space for an outing honouring this NPC's allow-list: with allowed spaces set, pick a random
     * one that exists in its world; with none set, fall back to {@code fallbackId} ("any" → anywhere).
     * Returns null when nothing usable is available.
     */
    private Space pickAllowedSpace(Character c, String fallbackId) {
        if (spaces == null) return null;
        List<String> allow = c.awaySpaces();
        if (allow.isEmpty()) return spaces.pick(fallbackId, c.getWorld());
        List<Space> ok = new ArrayList<>();
        for (String id : allow) {
            Space sp = spaces.get(id);
            if (sp != null && sp.world().equals(c.getWorld())) ok.add(sp);
        }
        if (ok.isEmpty()) return null;
        return ok.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(ok.size()));
    }

    /** Send a character out now (manual /lsk away). Returns an error message, or null on success. */
    public String sendAway(Character c, Double minutes, String spaceId) {
        if (c.home() == null) return "'" + c.getId() + "' has no post — only lobby/shop/barker NPCs can go out.";
        if (away.containsKey(c.uuid())) return "'" + c.getId() + "' is already out.";
        Space resolved = spaceId != null ? (spaces == null ? null : spaces.pick(spaceId, c.getWorld()))
                : pickAllowedSpace(c, "any");
        if (resolved == null) {
            String target = spaceId != null ? spaceId : "any";
            return "No space " + ("any".equalsIgnoreCase(target) ? "in world '" + c.getWorld() + "'"
                    : "called '" + target + "' in world '" + c.getWorld() + "'")
                    + " — create one with /lsk space create <id>" + (c.awaySpaces().isEmpty() ? ""
                    : ", or check this shopkeeper's allowed spaces") + ".";
        }
        String target = resolved.id();
        pendingOutings.remove(c.uuid());
        double mins = minutes != null ? minutes
                : (c.awayMinutes() != null ? c.awayMinutes() : plugin.getConfig().getDouble("outings.default-minutes", 6));
        long stayMs = (long) (Math.max(0.2, mins) * 60_000);
        long now = System.currentTimeMillis();
        beginOuting(c, target, stayMs, null, now, null);
        AwayState as = away.get(c.uuid());
        if (as == null) return "Could not start the outing.";
        // manual outings gather buddies too, so a hand-sent NPC still gets its little group walk
        if (plugin.getConfig().getBoolean("outings.group-on-manual", true)) {
            Space sp = spaces.get(as.spaceId);
            if (sp != null) {
                recruitCompanions(c, sp, java.util.EnumSet.of(CharacterType.LOBBY, CharacterType.SHOP,
                        CharacterType.BARKER), null, now, stayMs, null);
            }
        }
        return null;
    }

    /** Recall a character from its outing (or cancel a queued one). True if anything changed. */
    public boolean sendBack(Character c) {
        boolean cancelled = pendingOutings.remove(c.uuid()) != null;
        AwayState as = away.get(c.uuid());
        if (as == null) return cancelled;
        if (as.phase != AwayState.RETURNING) beginReturn(c, as);
        return true;
    }

    public void setAwayTag(Character c, List<String> lines) { c.setAwayTagLines(lines); persist(c); }
    public void setAwayMinutes(Character c, Double m) { c.setAwayMinutes(m); persist(c); }
    /** {@code null}/"any" → allow every space (empty list); a single id → restrict to just that space. */
    public void setAwaySpace(Character c, String s) {
        c.setAwaySpaces(s == null ? List.of() : List.of(s));
        persist(c);
    }
    /** Replace the outing allow-list wholesale (empty → any space). */
    public void setAwaySpaces(Character c, List<String> ids) { c.setAwaySpaces(ids); persist(c); }
    /** Toggle one space in this NPC's allow-list; returns whether it is now allowed. */
    public boolean toggleAwaySpace(Character c, String id) {
        boolean now = c.toggleAwaySpace(id);
        persist(c);
        return now;
    }

    // --- owner-editable dialogue (menu-authored lines in characters/<id>/dialog/owner.yml) ----------

    /** The dialog file a shop owner's menu edits write to (kept apart from any admin-authored dialog). */
    private File ownerDialogFile(Character c) {
        return new File(new File(c.folder(), "dialog"), "owner.yml");
    }

    /** Current owner-authored lines for {@code trigger} (interact/thanks/hawk/hit …); never null. */
    public List<String> ownerDialogLines(Character c, String trigger) {
        File f = ownerDialogFile(c);
        if (!f.isFile()) return new ArrayList<>();
        YamlConfiguration yc = YamlConfiguration.loadConfiguration(f);
        return new ArrayList<>(yc.getStringList("nodes.owner_" + trigger + ".lines"));
    }

    /**
     * Replace the owner-authored lines for {@code trigger}, then hot-reload dialog so it takes effect at
     * once. An empty list removes the node. Lines support {@code &}/{@code &#hex}/MiniMessage colours and
     * the {@code <player>}/{@code <npc>}/{@code <time>} placeholders.
     */
    public void setOwnerDialogLines(Character c, String trigger, List<String> lines) {
        File f = ownerDialogFile(c);
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        YamlConfiguration yc = f.isFile() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        String base = "nodes.owner_" + trigger;
        if (lines == null || lines.isEmpty()) {
            yc.set(base, null);
        } else {
            yc.set(base + ".trigger", trigger);
            yc.set(base + ".lines", new ArrayList<>(lines));
        }
        try {
            yc.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save owner dialog for '" + c.getId() + "': " + e.getMessage());
        }
        if (dialog != null) dialog.reloadLibrary();
    }

    /** The shipped (non-owner) line texts for {@code trigger}, read from the character's other dialog files. */
    public List<String> defaultDialogLines(Character c, String trigger) {
        List<String> out = new ArrayList<>();
        File dir = new File(c.folder(), "dialog");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml")
                && !n.equalsIgnoreCase("owner.yml"));
        if (files == null) return out;
        String want = trigger.toLowerCase(Locale.ROOT);
        for (File f : files) {
            YamlConfiguration yc = YamlConfiguration.loadConfiguration(f);
            org.bukkit.configuration.ConfigurationSection nodes = yc.getConfigurationSection("nodes");
            if (nodes == null) continue;
            for (String id : nodes.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection ns = nodes.getConfigurationSection(id);
                if (ns == null) continue;
                java.util.Set<String> trigs = new java.util.HashSet<>();
                for (String t : ns.getStringList("triggers")) trigs.add(t.toLowerCase(Locale.ROOT));
                String single = ns.getString("trigger");
                if (single != null) trigs.add(single.toLowerCase(Locale.ROOT));
                if (trigs.isEmpty()) trigs.add("interact");
                if (!trigs.contains(want)) continue;
                for (Object raw : ns.getList("lines", List.of())) {
                    if (raw instanceof String s) out.add(s);
                    else if (raw instanceof Map<?, ?> m && m.get("text") != null) out.add(String.valueOf(m.get("text")));
                }
            }
        }
        return out;
    }

    /**
     * First time an owner opens a dialogue category, copy the shipped default lines into their editable
     * {@code owner.yml} so they start from (and can tweak) the real lines instead of a blank list. A
     * {@code seeded} marker means clearing every line stays cleared (we don't re-add the defaults).
     */
    public void seedOwnerDialogIfNeeded(Character c, String trigger) {
        File f = ownerDialogFile(c);
        YamlConfiguration yc = f.isFile() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        List<String> seeded = new ArrayList<>(yc.getStringList("seeded"));
        if (seeded.contains(trigger)) return;                 // already seeded once — leave it be
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        seeded.add(trigger);
        yc.set("seeded", seeded);
        String base = "nodes.owner_" + trigger;
        if (!yc.isSet(base)) {
            List<String> defaults = defaultDialogLines(c, trigger);
            if (!defaults.isEmpty()) {
                yc.set(base + ".trigger", trigger);
                yc.set(base + ".lines", new ArrayList<>(defaults));
            }
        }
        try {
            yc.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not seed owner dialog for '" + c.getId() + "': " + e.getMessage());
        }
        if (dialog != null) dialog.reloadLibrary();
    }

    // --- sale notifications ------------------------------------------------

    /** Message the (online) shop owner that a customer just bought something, if enabled. */
    public void notifyOwnerSale(Character c, Player buyer, org.bukkit.inventory.ItemStack result, int amount) {
        if (c.owner() == null || !plugin.getConfig().getBoolean("shop.sale-notify", true)) return;
        if (buyer != null && buyer.getUniqueId().equals(c.owner())) return;   // own purchase — no self-ping
        Player owner = Bukkit.getPlayer(c.owner());
        if (owner == null || (buyer != null && owner.getUniqueId().equals(buyer.getUniqueId()))) return;
        String fmt = plugin.getConfig().getString("shop.sale-notify-format",
                "<gold>[Sale]</gold> <yellow><player></yellow> <gray>bought</gray> <white><amount>×</white> <item> <gray>from</gray> <npc>");
        if (fmt == null || fmt.isBlank()) return;
        String filled = fmt.replace("<amount>", String.valueOf(Math.max(1, amount)))
                .replace("<item>", friendlyItemName(result));
        owner.sendMessage(dev.servereer.livingshopkeeper.dialog.DialogText.render(
                filled, buyer == null ? "Someone" : buyer.getName(), c.display(), Bukkit.getWorld(c.getWorld())));
    }

    /** A readable, MiniMessage-safe name for an item — its custom name if set, else the prettified type. */
    private static String friendlyItemName(org.bukkit.inventory.ItemStack it) {
        if (it == null) return "item";
        if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(it.getItemMeta().displayName());
        }
        String[] words = it.getType().name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(java.lang.Character.toUpperCase(w.charAt(0))).append(w, 1, w.length());
        }
        return sb.toString();
    }
    public void setPersona(Character c, String persona) { c.setPersona(persona); persist(c); }
    /** Gossip flavour ({@code <speaker_job>}/{@code <speaker_trait>}/{@code <mood_speaker>}); null clears. */
    public void setJob(Character c, String job) { c.setJob(job); persist(c); }
    public void setTrait(Character c, String trait) { c.setTrait(trait); persist(c); }
    public void setMood(Character c, String mood) { c.setMood(mood); persist(c); }
    public void setSparRegion(Character c, String region) { c.setSparRegion(region); persist(c); }
    public void setSparStyle(Character c, String style) { c.setSparStyle(style); persist(c); }
    public void setSparArmor(Character c, String tier) { c.setSparArmor(tier); persist(c); }
    public void setSparStat(Character c, String key, Double value) { c.setSparStat(key, value); persist(c); }
    /** The value this character actually fights with for {@code key} (own stat, else global config). */
    public double effectiveSparStat(Character c, String key, double def) { return sparNum(c, key, def); }

    /** Once a second: fire due (staggered) departures, then roll the config schedules per NPC per window. */
    private void tickOutingScheduler(long now) {
        for (Iterator<Map.Entry<UUID, PendingOuting>> it = pendingOutings.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PendingOuting> en = it.next();
            if (now < en.getValue().departAtMs()) continue;
            it.remove();
            Character c = byUuid(en.getKey());
            if (c != null && !away.containsKey(c.uuid()) && !combat.containsKey(c.uuid())
                    && !spar.containsKey(c.uuid())) {
                PendingOuting po = en.getValue();
                beginOuting(c, po.spaceId(), po.stayMs(), po.scheduleKey(), now, po.leader());
            }
        }
        if (!plugin.getConfig().getBoolean("outings.enabled", true) || outingSchedules.isEmpty() || spaces == null) return;
        for (OutingSchedule sch : outingSchedules) {
            for (Character c : new ArrayList<>(byId.values())) {
                if (!sch.types().contains(c.type()) || c.home() == null) continue;
                World w = Bukkit.getWorld(c.getWorld());
                if (w == null || !sch.activeAt(w.getTime())) continue;
                String wk = sch.id() + ":" + w.getName();
                long key = sch.windowKey(w.getFullTime());
                Long prev = outingWindowKey.get(wk);
                Set<UUID> rolled = outingRolled.computeIfAbsent(wk, k -> new java.util.HashSet<>());
                if (prev == null || prev.longValue() != key) {   // a new window (tonight) → everyone rolls anew
                    outingWindowKey.put(wk, key);
                    rolled.clear();
                }
                if (!rolled.add(c.uuid())) continue;             // one roll per NPC per window
                if (!outingsEnabled(c)) continue;                // shops opt-in; owner can toggle off
                if (away.containsKey(c.uuid()) || pendingOutings.containsKey(c.uuid())
                        || combat.containsKey(c.uuid()) || spar.containsKey(c.uuid())) continue;
                if (Math.random() >= sch.chance()) continue;
                long stay = sch.minStayMs() + (long) (Math.random() * (sch.maxStayMs() - sch.minStayMs() + 1));
                long stagger = (long) (Math.random() * sch.staggerMs());
                // resolve the space now so any companions provably head to the SAME spot; honour the
                // NPC's allow-list (empty → the schedule's space, "any" → anywhere in its world)
                Space sp = pickAllowedSpace(c, sch.space());
                // a fighter sometimes heads to an arena instead, looking for a bout
                if (c.ai("spar", false)
                        && Math.random() < plugin.getConfig().getDouble("outings.arena-visit-chance", 0.35)) {
                    Space arenaSp = spaces.pickArena(c.getWorld());
                    if (arenaSp != null) sp = arenaSp;
                }
                if (sp == null) continue;
                pendingOutings.put(c.uuid(), new PendingOuting(now + stagger, sp.id(), stay, sch.id(), null));
                recruitCompanions(c, sp, sch.types(), rolled, now + stagger, stay, sch.id());
            }
        }
    }

    /**
     * An outing sometimes becomes a little group trip: nearby idle colleagues of the allowed types join
     * the leader — departing moments later so they fall in beside it, walking together (stroll chat on
     * the way) and sitting on adjacent seats once there. Used by the scheduler AND manual /lsk away.
     */
    private void recruitCompanions(Character leader, Space sp, Set<CharacterType> types, Set<UUID> rolled,
                                   long departAtMs, long stayMs, String scheduleKey) {
        double chance = plugin.getConfig().getDouble("outings.group-chance", 0.5);
        int maxGroup = Math.max(1, plugin.getConfig().getInt("outings.max-group", 3));
        double range = plugin.getConfig().getDouble("outings.buddy-range", 32);
        if (maxGroup < 2 || chance <= 0) return;
        List<UUID> members = new ArrayList<>();
        for (Character other : new ArrayList<>(byId.values())) {
            if (members.size() >= maxGroup - 1) break;
            if (other == leader || other.home() == null || !types.contains(other.type())) continue;
            if (!other.getWorld().equals(leader.getWorld())) continue;
            if (!outingsEnabled(other)) continue;
            if (away.containsKey(other.uuid()) || pendingOutings.containsKey(other.uuid())
                    || combat.containsKey(other.uuid()) || spar.containsKey(other.uuid())) continue;
            double dx = other.getX() - leader.getX(), dz = other.getZ() - leader.getZ();
            if (dx * dx + dz * dz > range * range) continue;
            if (Math.random() >= chance) continue;
            if (rolled != null) rolled.add(other.uuid());   // joining counts as tonight's roll
            // slight delay so the leader has left (and claimed its seat) before the buddy sets off
            pendingOutings.put(other.uuid(), new PendingOuting(
                    departAtMs + 600 + members.size() * 500L, sp.id(), stayMs, scheduleKey, leader.uuid()));
            members.add(other.uuid());
        }
        if (!members.isEmpty()) outingGroups.put(leader.uuid(), members);
    }

    /** Start the outing: claim a destination, raise the ghost at the post, and set off. */
    private void beginOuting(Character c, String spaceId, long stayMs, String scheduleKey, long now, UUID leader) {
        if (spaces == null) return;
        Space sp = spaces.pick(spaceId, c.getWorld());
        if (sp == null) return;
        AwayState as = new AwayState();
        as.spaceId = sp.id();
        as.scheduleKey = scheduleKey;
        as.untilMs = now + stayMs;
        // a companion walks beside its leader and prefers the seat next to the leader's
        double[] near = null;
        if (leader != null) {
            AwayState ls2 = away.get(leader);
            if (ls2 != null && sp.id().equals(ls2.spaceId)) {
                as.leader = leader;
                as.sideOffset = (Math.floorMod(c.uuid().hashCode(), 2) == 0 ? 1 : -1)
                        * (1.0 + Math.random() * 0.6);
                near = ls2.target;
            }
        }
        double[] seat = near != null
                ? spaces.claimSeatNear(sp, c.uuid(), near[0], near[2])
                : spaces.claimSeat(sp, c.uuid());
        if (seat != null) {
            as.target = seat;
            as.targetIsSeat = true;
        } else {
            World w0 = Bukkit.getWorld(c.getWorld());
            double bx = near != null ? near[0] : sp.x(), bz = near != null ? near[2] : sp.z();
            as.target = w0 != null
                    ? groundSpot(w0, bx, bz, sp.y(), plugin.getConfig().getDouble("outings.mill-radius", 5))
                    : new double[]{bx, sp.y(), bz, Float.NaN};
        }
        as.ghostHeadId = SpigotReflectionUtil.generateEntityId();
        List<String> lines = ghostLines(c);
        as.ghostTagIds = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) as.ghostTagIds[i] = SpigotReflectionUtil.generateEntityId();
        ghostIds.add(as.ghostHeadId);
        byEntityId.put(as.ghostHeadId, c);
        for (int id : as.ghostTagIds) { ghostIds.add(id); byEntityId.put(id, c); }
        away.put(c.uuid(), as);

        // drop whatever it was doing around the post
        LobbyState ls = lobbyStates.remove(c.uuid());
        if (ls != null && ls.gossipWith != null) {
            Character partner = byUuid(ls.gossipWith);
            LobbyState ps = partner == null ? null : lobbyStates.get(partner.uuid());
            if (ps != null && c.uuid().equals(ps.gossipWith)) clearGossip(ps);
        }
        clearPending(c);
        addressUntil.remove(c.uuid());
        setSeated(c, false);
        setCrouching(c, false);
        MovementController mover = movers.computeIfAbsent(c.uuid(), k -> newMover());
        mover.clearApproach();
        mover.setSpeedMultiplier(plugin.getConfig().getDouble("outings.walk-speed-multiplier", 0.9));
        World w = Bukkit.getWorld(c.getWorld());
        if (w != null && dialog != null) {
            Player listener = nearestPlayer(w, c.getX(), c.getY(), c.getZ(), hearingDistance);
            if (listener != null) dialog.trigger(speakerOf(c), "leave_post", listener);
        }
    }

    /** Drive one away NPC: walk out (fade only if configured), live at the space, then walk home. */
    private void tickAway(Character c, World w, MovementController mover, AwayState as, long now) {
        // ≤0 → NEVER teleport: they walk the whole way (direct ground-walk covers unpathable stretches)
        double walkMax = plugin.getConfig().getDouble("outings.walk-max-distance", -1);
        boolean mayFade = walkMax > 0;
        if (as.phase == AwayState.LEAVING) {
            double dx = as.target[0] - c.getX(), dz = as.target[2] - c.getZ();
            double d2 = dx * dx + dz * dz;
            if (mayFade && d2 > walkMax * walkMax) {   // opted-in fade for far-flung spaces
                despawnAll(c);
                snapTo(c, as.target[0], as.target[1], as.target[2], awayYaw(as, c), 0f);
                arriveAtSpace(c, as, now);
                return;
            }
            if (d2 <= 0.45 * 0.45) {
                mover.clearApproach();
                snapTo(c, as.target[0], as.target[1], as.target[2], awayYaw(as, c), 0f);
                arriveAtSpace(c, as, now);
                return;
            }
            // a companion falls in beside its leader while the leader is still walking
            Character lc = as.leader != null ? byUuid(as.leader) : null;
            AwayState ls = lc != null ? away.get(as.leader) : null;
            if (ls != null && ls.phase == AwayState.LEAVING
                    && lc.getWorld().equals(c.getWorld())) {
                double yawRad = Math.toRadians(lc.movementYaw());
                mover.approach(lc.getX() + Math.cos(yawRad) * as.sideOffset,
                        lc.getY(), lc.getZ() + Math.sin(yawRad) * as.sideOffset);
            } else {
                mover.approach(as.target[0], as.target[1], as.target[2]);
            }
            sendMove(c, mover.tick(c, w, 0));
            tickStrollChat(c, w, as, now);
            return;
        }
        if (as.phase == AwayState.OUT) {
            boolean windowOver = false;
            if (as.scheduleKey != null) {
                for (OutingSchedule sch : outingSchedules) {
                    if (sch.id().equals(as.scheduleKey)) { windowOver = !sch.activeAt(w.getTime()); break; }
                }
            }
            if (now >= as.untilMs || windowOver) {
                beginReturn(c, as);
                return;
            }
            // an arena visitor with spar on actively looks for someone to duel
            if (c.ai("spar", false) && plugin.getConfig().getBoolean("spar.enabled", true) && spaces != null) {
                Space here = spaces.get(as.spaceId);
                if (here != null && here.arena() && tickChallenge(c, w, mover, as, now)) return;
            }
            // seated: sit a while, then sometimes get up and stretch the legs — a zone full of NPCs
            // frozen on chairs reads as statues, so they cycle sit → stroll → (re)sit
            if (c.seated) {
                if (now >= as.nextIdleMs) {
                    if (Math.random() < 0.45) {
                        setSeated(c, false);
                        if (spaces != null) spaces.releaseSeats(c.uuid());
                        as.targetIsSeat = false;
                        as.nextIdleMs = now + jitter(plugin.getConfig().getDouble("outings.idle-seconds", 14));
                    } else {
                        as.nextIdleMs = now + jitter(plugin.getConfig().getDouble("outings.sit-seconds", 45));
                    }
                }
                return;
            }
            if (now >= as.nextIdleMs) {   // sit, mill to a new spot, or grab a chair that freed up
                as.nextIdleMs = now + jitter(plugin.getConfig().getDouble("outings.idle-seconds", 14));
                Space sp = spaces != null ? spaces.get(as.spaceId) : null;
                if (sp != null) {
                    double[] seat = spaces.claimSeat(sp, c.uuid());
                    if (seat != null) {                 // a real wand-marked chair → go sit on it
                        as.target = seat;
                        as.targetIsSeat = true;
                        as.phase = AwayState.LEAVING;
                        return;
                    }
                    // no free chair: sit right where we're standing, or amble to a fresh spot on the floor
                    if (Math.random() < plugin.getConfig().getDouble("outings.ground-sit-chance", 0.4)) {
                        setSeated(c, true);
                        as.targetIsSeat = false;
                        as.nextIdleMs = now + jitter(plugin.getConfig().getDouble("outings.sit-seconds", 45));
                        return;
                    }
                    as.target = groundSpot(w, sp.x(), sp.z(), sp.y(),
                            plugin.getConfig().getDouble("outings.mill-radius", 5));
                    as.targetIsSeat = false;
                    as.phase = AwayState.LEAVING;   // reuse the approach walk; NEVER mover.tick without a
                    return;                         // target here — a barker would resume its patrol route
                }
            }
            idleFidget(c, now);
            return;
        }
        // RETURNING — back to the post
        double[] home = c.home();
        double hx = home != null ? home[0] : c.getX();
        double hy = home != null ? home[1] : c.getY();
        double hz = home != null ? home[2] : c.getZ();
        float hyaw = home != null ? (float) home[3] : c.yaw();
        float hpitch = home != null && home.length > 4 ? (float) home[4] : 0f;
        double dx = hx - c.getX(), dz = hz - c.getZ();
        double d2 = dx * dx + dz * dz;
        if (mayFade && d2 > walkMax * walkMax) {
            despawnAll(c);
            snapTo(c, hx, hy, hz, hyaw, hpitch);
            endOuting(c, as);
        } else if (d2 <= 0.45 * 0.45) {
            mover.clearApproach();
            snapTo(c, hx, hy, hz, hyaw, hpitch);
            endOuting(c, as);
        } else {
            mover.approach(hx, hy, hz);
            sendMove(c, mover.tick(c, w, 0));
        }
    }

    /**
     * Arena life: walk up to a nearby player, throw down the gauntlet ({@code challenge} dialog trigger,
     * else a config line), and wait — the player ACCEPTS BY HITTING the NPC (which starts the spar).
     * Declined challenges cool that player down so nobody gets pestered. True → this owned the tick.
     */
    private boolean tickChallenge(Character c, World w, MovementController mover, AwayState as, long now) {
        if (as.challengeTarget != null) {   // waiting on an answer: stay close, face them
            Player t = Bukkit.getPlayer(as.challengeTarget);
            boolean gone = t == null || !w.equals(t.getWorld()) || t.isDead();
            if (gone || now >= as.challengeUntilMs
                    || t.getLocation().distanceSquared(new Location(w, c.getX(), c.getY(), c.getZ())) > 20 * 20) {
                as.challengeTarget = null;   // no takers — back to hanging out
                mover.clearApproach();
                return false;
            }
            double dx = t.getX() - c.getX(), dz = t.getZ() - c.getZ();
            if (dx * dx + dz * dz > 2.8 * 2.8) {
                mover.approach(t.getX(), t.getY(), t.getZ());
                sendMove(c, mover.tick(c, w, 0));
            } else {
                mover.clearApproach();
                faceViewers(c, w, t);
            }
            return true;
        }
        if (now < as.nextChallengeMs) return false;
        as.nextChallengeMs = now + jitter(plugin.getConfig().getDouble("spar.challenge-interval-seconds", 15));
        double range = plugin.getConfig().getDouble("spar.challenge-range", 12);
        Map<UUID, Long> cd = challengeCd.computeIfAbsent(c.uuid(), k -> new java.util.HashMap<>());
        Location at = new Location(w, c.getX(), c.getY(), c.getZ());
        Player best = null;
        double bestSq = range * range;
        for (Player p : w.getPlayers()) {
            if (p.isDead() || now < cd.getOrDefault(p.getUniqueId(), 0L)) continue;
            double d2 = p.getLocation().distanceSquared(at);
            if (d2 <= bestSq) { bestSq = d2; best = p; }
        }
        if (best == null) return false;
        if (c.seated) {   // hop off the chair to issue the challenge
            setSeated(c, false);
            if (spaces != null) spaces.releaseSeats(c.uuid());
            as.targetIsSeat = false;
        }
        as.challengeTarget = best.getUniqueId();
        as.challengeUntilMs = now
                + (long) (plugin.getConfig().getDouble("spar.challenge-window-seconds", 15) * 1000);
        cd.put(best.getUniqueId(), now
                + (long) (plugin.getConfig().getDouble("spar.challenge-cooldown-seconds", 120) * 1000));
        faceViewers(c, w, best);
        swingViewers(c, now);
        if (dialog == null || !dialog.trigger(speakerOf(c), "challenge", best)) {
            List<String> pool = plugin.getConfig().getStringList("spar.challenge-lines");
            String line = pool.isEmpty()
                    ? "Care for a duel, <player>? Strike me when you're ready!"
                    : pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
            broadcastLine(c, w, line.replace("<player>", best.getName()));
        }
        return true;
    }

    private static float awayYaw(AwayState as, Character c) {
        return as.targetIsSeat && !Float.isNaN((float) as.target[3]) ? (float) as.target[3] : c.yaw();
    }

    /**
     * A standable point within {@code radius} of (cx,cz) SNAPPED TO THE REAL GROUND (so hangout visitors
     * stand on the floor instead of a guessed space Y — the cause of the float/ground-glitch), or the
     * centre when nothing samples. {@code baseY} seeds the vertical search around the space's height.
     */
    private double[] groundSpot(World w, double cx, double cz, double baseY, double radius) {
        double r = Math.max(1.5, radius);
        for (int i = 0; i < 8; i++) {
            double ang = Math.random() * Math.PI * 2;
            double dist = 1.5 + Math.random() * (r - 1.5);
            double x = cx + Math.cos(ang) * dist, z = cz + Math.sin(ang) * dist;
            int gy = dev.servereer.livingshopkeeper.move.NavMeshSampler.groundY(
                    w, (int) Math.floor(x), (int) Math.floor(baseY), (int) Math.floor(z), 5);
            if (gy != Integer.MIN_VALUE) return new double[]{x, gy, z, Float.NaN};
        }
        int gy = dev.servereer.livingshopkeeper.move.NavMeshSampler.groundY(
                w, (int) Math.floor(cx), (int) Math.floor(baseY), (int) Math.floor(cz), 6);
        return new double[]{cx, gy == Integer.MIN_VALUE ? baseY : gy, cz, Float.NaN};
    }

    private void arriveAtSpace(Character c, AwayState as, long now) {
        as.phase = AwayState.OUT;
        MovementController mover = movers.get(c.uuid());
        if (mover != null) {
            mover.clearApproach();
            mover.setSpeedMultiplier(1.0);
        }
        if (as.targetIsSeat) setSeated(c, true);
        as.nextIdleMs = now + jitter(plugin.getConfig().getDouble(
                as.targetIsSeat ? "outings.sit-seconds" : "outings.idle-seconds", as.targetIsSeat ? 45 : 14));
        if (as.arrivedOnce) return;   // strolls/seat-swaps come back through here — one-time bits below
        as.arrivedOnce = true;
        if (c.tagOverride == null && !c.awayTagLines().isEmpty()) {
            applyTagOverride(c, new ArrayList<>(c.awayTagLines()));   // its "off duty" identity
        }
        World w = Bukkit.getWorld(c.getWorld());
        if (w != null && dialog != null) {
            Player near = nearestPlayer(w, c.getX(), c.getY(), c.getZ(), hearingDistance);
            if (near != null) dialog.trigger(speakerOf(c), "arrive_space", near);
        }
    }

    private void beginReturn(Character c, AwayState as) {
        as.phase = AwayState.RETURNING;
        setSeated(c, false);
        if (spaces != null) spaces.releaseSeats(c.uuid());
        if (c.tagOverride != null) applyTagOverride(c, null);
        MovementController mover = movers.computeIfAbsent(c.uuid(), k -> newMover());
        mover.clearApproach();
        mover.setSpeedMultiplier(1.0);
    }

    private void endOuting(Character c, AwayState as) {
        away.remove(c.uuid());
        clearGhost(c, as);
        lobbyStates.remove(c.uuid());   // fresh post life
        outingGroups.remove(c.uuid());
        for (List<UUID> members : outingGroups.values()) members.remove(c.uuid());
        if (aiChat != null) aiChat.endConversation("stroll:" + c.uuid());
        if (c.type() == CharacterType.BARKER) movers.put(c.uuid(), newMover());   // restart patrol cleanly
        World w = Bukkit.getWorld(c.getWorld());
        if (w != null && dialog != null) {
            Player near = nearestPlayer(w, c.getX(), c.getY(), c.getZ(), hearingDistance);
            if (near != null) dialog.trigger(speakerOf(c), "back_at_post", near);
        }
    }

    /** Chatter while a group walks to its space — the leader paces it so beats never double-fire. */
    private void tickStrollChat(Character c, World w, AwayState as, long now) {
        if (as.leader != null) return;   // companions listen; the leader drives
        if (!plugin.getConfig().getBoolean("gossip.enabled", true)) return;
        List<UUID> members = outingGroups.get(c.uuid());
        if (members == null || members.isEmpty()) return;
        if (as.nextChatMs == 0) {
            as.nextChatMs = now + 3000 + (long) (Math.random() * 3000);
            return;
        }
        if (now < as.nextChatMs) return;
        as.nextChatMs = now + gossipLineDelay();
        List<Character> group = new ArrayList<>();
        group.add(c);
        for (UUID id : members) {
            Character mc = byUuid(id);
            if (mc != null && away.containsKey(id) && mc.getWorld().equals(c.getWorld())) group.add(mc);
        }
        if (group.size() < 2) return;
        int si = java.util.concurrent.ThreadLocalRandom.current().nextInt(group.size());
        Character speaker = group.get(si);
        Character listener = group.get((si + 1 + java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(group.size() - 1)) % group.size());
        if (speaker != listener) chatBeat(speaker, listener, w, now, "stroll_chat", "gossip");
    }

    /** The ghost's hologram: the NPC's tag (or name) + the configurable "not available" line. */
    private List<String> ghostLines(Character c) {
        List<String> out = new ArrayList<>(c.tagLines().isEmpty() ? List.of(c.display()) : c.tagLines());
        String extra = plugin.getConfig().getString("outings.placeholder-line", "<gray>(not available right now)</gray>");
        if (extra != null && !extra.isBlank()) out.add(extra);
        return out;
    }

    /** Render the ghost head to players near the POST (the body renders near the space) + slow spin. */
    private void tickGhostRender(Character c, AwayState as, World w) {
        if (as.ghostHeadId == -1) return;
        double[] home = c.home();
        if (home == null) return;
        Location post = new Location(w, home[0], home[1], home[2]);
        Set<UUID> shouldSee = new java.util.HashSet<>();
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(post) <= hearingSq) shouldSee.add(p.getUniqueId());
        }
        double headHeight = plugin.getConfig().getDouble("outings.head-height", 1.5);
        List<String> lines = ghostLines(c);
        for (UUID id : shouldSee) {
            if (as.ghostViewers.add(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) PacketNpc.spawnGhost(p, c, as.ghostHeadId, as.ghostTagIds,
                        home[0], home[1], home[2], lines, headHeight);
            }
        }
        for (Iterator<UUID> it = as.ghostViewers.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (!shouldSee.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) PacketNpc.despawnGhost(p, as.ghostHeadId, as.ghostTagIds);
                it.remove();
            }
        }
        as.ghostYaw = (as.ghostYaw + 9f) % 360f;   // gentle idle spin at scan cadence
        for (UUID id : as.ghostViewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) PacketNpc.rotateGhost(p, as.ghostHeadId, as.ghostYaw);
        }
    }

    private void clearGhost(Character c, AwayState as) {
        for (UUID vid : as.ghostViewers) {
            Player p = Bukkit.getPlayer(vid);
            if (p != null) PacketNpc.despawnGhost(p, as.ghostHeadId, as.ghostTagIds);
        }
        as.ghostViewers.clear();
        ghostIds.remove(as.ghostHeadId);
        byEntityId.remove(as.ghostHeadId);
        for (int id : as.ghostTagIds) { ghostIds.remove(id); byEntityId.remove(id); }
        as.ghostHeadId = -1;
        as.ghostTagIds = new int[0];
    }

    /** Swap (or restore) the rendered tag stack at runtime — the away tag while out. Not persisted. */
    private void applyTagOverride(Character c, List<String> lines) {
        c.tagOverride = lines;
        despawnAll(c);
        allocateTagIds(c);
        respawn(c);
    }

    // --- conversation beats: turn-taking chatter with body language (gossip + space hangouts) -------

    /** Enqueue a deferred gesture (nod step, chuckle bob, reply wave) for the movement tick to run. */
    private void schedule(long atMs, Runnable r) {
        gestureQueueKeys.add(new long[]{atMs});
        gestureQueue.add(r);
    }

    private void runDueGestures(long now) {
        for (int i = 0; i < gestureQueueKeys.size(); ) {
            if (gestureQueueKeys.get(i)[0] <= now) {
                Runnable r = gestureQueue.remove(i);
                gestureQueueKeys.remove(i);
                try {
                    r.run();
                } catch (Exception ignored) {
                }
            } else i++;
        }
    }

    /**
     * One conversational beat: the speaker turns to the listener, gestures, says a line — the first
     * matching authored trigger, else a small-talk line every nearby player can read — murmurs audibly,
     * and the listener visibly reacts (nod / chuckle / wave) a beat later. This is what makes two NPCs
     * standing together read as an actual conversation instead of two statues.
     */
    private void chatBeat(Character speaker, Character listener, World w, long now, String... triggers) {
        faceEachOther(speaker, listener, w);
        swingViewers(speaker, now);
        murmur(speaker, w);
        Player audience = nearestPlayer(w, speaker.getX(), speaker.getY(), speaker.getZ(), hearingDistance);
        boolean spoke = false;
        if (audience != null && dialog != null) {
            for (String t : triggers) {
                if (dialog.trigger(speakerOf(speaker), t, audience)) {
                    spoke = true;
                    break;
                }
            }
        }
        if (!spoke && audience != null) {
            // The placeholder snapshot is only needed when we actually have to speak, and it is read once
            // per beat (one surroundings scan), never per candidate line.
            GossipCtx ctx = gossipPlaceholders
                    ? gossipCtx(speaker, listener, w, speaker.getX(), speaker.getY(), speaker.getZ()) : null;
            fallbackLine(speaker, listener, w, ctx);
        }
        scheduleReaction(listener, now);
    }

    /**
     * No authored line matched: prefer an AI-generated line (async; providers/keys/models failover),
     * else a static small-talk line. Whatever ends up said is recorded into the conversation transcript
     * so later AI turns stay coherent.
     */
    private void fallbackLine(Character speaker, Character listener, World w, GossipCtx ctx) {
        String key = convKey(speaker, listener);
        if (aiChat != null && aiChat.enabled()) {
            boolean requested = aiChat.tryGenerate(key,
                    plainName(speaker), speaker.persona(), plainName(listener), listener.persona(),
                    chatContext(speaker, listener),
                    line -> {
                        if (line == null) {   // every provider failed — static line so the beat still lands
                            World lw = Bukkit.getWorld(speaker.getWorld());
                            if (lw != null) smalltalk(speaker, listener, lw, key, ctx);
                            return;
                        }
                        World lw = Bukkit.getWorld(speaker.getWorld());
                        if (lw != null && byId.get(speaker.getId().toLowerCase(Locale.ROOT)) == speaker) {
                            broadcastLine(lw, speaker, ctx, line);
                        }
                    });
            if (requested) return;   // rate caps in effect → fall through to static
        }
        smalltalk(speaker, listener, w, key, ctx);
    }

    /** One stable key per conversation: the pair, the space hangout, or the walking group. */
    private String convKey(Character a, Character b) {
        AwayState aa = away.get(a.uuid()), ab = away.get(b.uuid());
        if (aa != null && ab != null && aa.spaceId != null && aa.spaceId.equals(ab.spaceId)) {
            if (aa.phase == AwayState.OUT && ab.phase == AwayState.OUT) return "space:" + aa.spaceId;
            UUID lead = aa.leader != null ? aa.leader : (ab.leader != null ? ab.leader : a.uuid());
            return "stroll:" + lead;
        }
        String x = a.uuid().toString(), y = b.uuid().toString();
        return x.compareTo(y) < 0 ? "pair:" + x + ":" + y : "pair:" + y + ":" + x;
    }

    /** A one-line scene description for the AI prompt, from what the two are actually doing. */
    private String chatContext(Character a, Character b) {
        AwayState aa = away.get(a.uuid()), ab = away.get(b.uuid());
        if (aa != null && ab != null && aa.phase == AwayState.OUT && ab.phase == AwayState.OUT) {
            return "You are both relaxing off-duty at the '" + aa.spaceId + "' hangout spot.";
        }
        if (aa != null || ab != null) return "You are walking together through town to a hangout spot.";
        return "You are chatting between your market stalls while no customers are around.";
    }

    private static String plainName(Character c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(dev.servereer.livingshopkeeper.dialog.DialogText.mini(c.display()));
    }

    private void faceEachOther(Character a, Character b, World w) {
        float[] ab = lookAt(new Location(w, a.getX(), a.getY(), a.getZ()),
                new Location(w, b.getX(), b.getY() + EYE_HEIGHT, b.getZ()));
        faceAll(a, ab[0], ab[1]);
        LobbyState bs = lobbyStates.get(b.uuid());
        if (bs == null || !bs.attending) {
            float[] ba = lookAt(new Location(w, b.getX(), b.getY(), b.getZ()),
                    new Location(w, a.getX(), a.getY() + EYE_HEIGHT, a.getZ()));
            faceAll(b, ba[0], ba[1]);
        }
    }

    /** A soft villager mumble, pitched per NPC so voices are tellable apart across the plaza. */
    private void murmur(Character c, World w) {
        if (!plugin.getConfig().getBoolean("gossip.murmur", true)) return;
        float pitch = 0.82f + Math.floorMod(c.uuid().hashCode(), 8) * 0.05f;
        w.playSound(new Location(w, c.getX(), c.getY() + 1.4, c.getZ()),
                Sound.ENTITY_VILLAGER_AMBIENT, 0.55f, pitch);
    }

    /**
     * A static line from the config small-talk pool so the chat is never mute. With a {@link GossipCtx} the
     * line goes through the placeholder registry here, so {@code <speaker>}/{@code <speaker_job>}/{@code <crowd>}
     * — and the legacy {@code <partner>} — all resolve and every substituted value is scrubbed; without a ctx
     * (placeholders switched off) the pre-1.0 behaviour of replacing {@code <partner>} alone is kept.
     */
    private void smalltalk(Character speaker, Character listener, World w, String convKey, GossipCtx ctx) {
        List<String> pool = plugin.getConfig().getStringList("gossip.smalltalk");
        if (pool.isEmpty()) return;
        String pick = pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
        String line = ctx == null ? pick.replace("<partner>", listener.display())
                : GossipPlaceholders.fill(pick, ctx);
        if (aiChat != null) aiChat.record(convKey, plainName(speaker), line);
        broadcastLine(w, speaker, ctx, line);
    }

    /**
     * Show one said line to every player within hearing of the speaker. The format may itself use the gossip
     * placeholders ({@code <speaker>}, {@code <space>}, {@code <crowd>}, legacy {@code <npc>}) and always gets
     * the line through its {@code <line>} tag — inserted verbatim, so an author's own markup survives.
     */
    private void broadcastLine(World w, Character speaker, GossipCtx ctx, String line) {
        String fmt = plugin.getConfig().getString("gossip.smalltalk-format", DEFAULT_SMALLTALK_FORMAT);
        Component msg = ctx == null
                ? dev.servereer.livingshopkeeper.dialog.DialogText.mini(
                        fmt.replace("<npc>", speaker.display()).replace("<line>", line))
                : dev.servereer.livingshopkeeper.dialog.DialogText.renderGossip(fmt, ctx.saying(line));
        Location at = new Location(w, speaker.getX(), speaker.getY(), speaker.getZ());
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= hearingSq) {
                p.sendMessage(msg);
            }
        }
    }

    /** Legacy overload for the non-gossip callers (spar GG / duel challenge) — no placeholder context. */
    private void broadcastLine(Character speaker, World w, String line) {
        broadcastLine(w, speaker, null, line);
    }

    // --- gossip placeholders: the per-beat snapshot (the registry itself lives in GossipPlaceholders) ------

    /**
     * The snapshot {@link GossipPlaceholders} resolves against: the pair's flavour fields plus the world as it
     * is at {@code (x,y,z)} right now. Read once per beat so no resolver ever touches the world or the config.
     */
    private GossipCtx gossipCtx(Character speaker, Character listener, World w, double x, double y, double z) {
        int playersNear = w.getNearbyPlayers(new Location(w, x, y, z), hearingDistance).size();
        return new GossipCtx(
                GossipCtx.Person.of(speaker.getId(), plainName(speaker),
                        speaker.job(), speaker.trait(), speaker.mood()),
                GossipCtx.Person.of(listener.getId(), plainName(listener),
                        listener.job(), listener.trait(), listener.mood()),
                GossipCtx.Scene.of(stallOf(speaker), spaceOf(speaker), w.getFullTime(), w.isThundering(),
                        w.hasStorm(), playersNear,
                        GossipPlaceholders.crowdOf(playersNear, crowdBusy, crowdPacked)),
                GossipCtx.Talk.stub(),
                GossipCtx.News.of(duelWinnerNear(w, x, y, z), loreMineNews, loreTravelerTale));
    }

    /**
     * The same snapshot resolved at an arbitrary spot — used by {@code /lsk gossip placeholders}. The pair is
     * whoever the admin named; world, clock, weather and crowd come from {@code at}, so the printed table
     * matches what players standing there would read.
     */
    public GossipCtx gossipContextAt(Character speaker, Character listener, Location at) {
        World w = at == null ? null : at.getWorld();
        if (w == null) return null;
        return gossipCtx(speaker, listener, w, at.getX(), at.getY(), at.getZ());
    }

    /** Where the beat happens: the outing space while away, {@code "road"} while walking there, else the market. */
    private String spaceOf(Character c) {
        AwayState as = away.get(c.uuid());
        if (as == null) return "market";
        return as.phase == AwayState.OUT && as.spaceId != null ? as.spaceId : "road";
    }

    /** The speaker's post as a place name: the WorldGuard plot at its home when known, else a generic label. */
    private String stallOf(Character c) {
        Set<String> regions = shopRegions.get(c.uuid());
        return regions == null || regions.isEmpty() ? "the stall" : regions.iterator().next();
    }

    /**
     * A player currently duelling an NPC within earshot — what {@code <duel_winner>} shows, else "nobody".
     * Phase 1 reports the duel in progress (there is no recorded winner yet); Phase 1.5 can remember results
     * by filling {@link GossipCtx.News} from the spar lifecycle instead.
     */
    private String duelWinnerNear(World w, double x, double y, double z) {
        if (spar.isEmpty()) return "nobody";
        Location at = new Location(w, x, y, z);
        for (Map.Entry<UUID, SparState> en : spar.entrySet()) {
            Character npc = byUuid(en.getKey());
            if (npc == null || !w.getName().equals(npc.getWorld())) continue;
            if (new Location(w, npc.getX(), npc.getY(), npc.getZ()).distanceSquared(at) > hearingSq) continue;
            Player fighter = Bukkit.getPlayer(en.getValue().player);
            if (fighter != null) return fighter.getName();
        }
        return "nobody";
    }

    /** The listener's delayed reaction: an agreeing nod, a chuckle (bob + bright note), or a wave back. */
    private void scheduleReaction(Character listener, long now) {
        long at = now + 500 + (long) (Math.random() * 900);
        double roll = Math.random();
        if (roll < 0.75 && listener.seated) roll = roll < 0.5 ? 0.2 : 0.9;   // no crouch-bobs while seated
        if (roll < 0.5) {           // nod: two quick head dips
            for (int i = 0; i < 4; i++) {
                float pitch = (i % 2 == 0) ? 18f : 0f;
                schedule(at + i * 170L, () -> sendPitch(listener, pitch));
            }
        } else if (roll < 0.75) {   // chuckle: crouch-bob twice + a bright villager note
            schedule(at, () -> {
                World lw = Bukkit.getWorld(listener.getWorld());
                if (lw != null) lw.playSound(new Location(lw, listener.getX(), listener.getY() + 1.4, listener.getZ()),
                        Sound.ENTITY_VILLAGER_YES, 0.7f, 1.25f);
            });
            for (int i = 0; i < 4; i++) {
                boolean down = i % 2 == 0;
                schedule(at + i * 200L, () -> setCrouching(listener, down));
            }
            schedule(at + 900, () -> setCrouching(listener, false));
        } else {                    // a hand gesture back
            schedule(at, () -> swingViewers(listener, System.currentTimeMillis()));
        }
    }

    private void sendPitch(Character c, float pitch) {
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) PacketNpc.rotate(p, c, c.yaw(), pitch);
        }
    }

    /** Group chatter at each space: visitors take turns speaking to a random companion. */
    private void tickSpaceChats(long now) {
        if (spaces == null || away.isEmpty()) return;
        if (!plugin.getConfig().getBoolean("gossip.enabled", true)) return;
        Map<String, List<Character>> bySpace = new java.util.HashMap<>();
        for (Map.Entry<UUID, AwayState> en : away.entrySet()) {
            if (en.getValue().phase != AwayState.OUT) continue;
            Character c = byUuid(en.getKey());
            if (c != null) bySpace.computeIfAbsent(en.getValue().spaceId, k -> new ArrayList<>()).add(c);
        }
        spaceChat.keySet().retainAll(bySpace.keySet());
        for (Map.Entry<String, List<Character>> en : bySpace.entrySet()) {
            List<Character> group = en.getValue();
            if (group.size() < 2) continue;
            long[] st = spaceChat.computeIfAbsent(en.getKey(), k -> new long[]{now + jitter(5), 0});
            if (now < st[0]) continue;
            st[0] = now + gossipLineDelay();
            int idx = (int) (st[1] % group.size());
            st[1]++;
            Character speaker = group.get(idx);
            int others = group.size() - 1;
            Character listener = group.get((idx + 1
                    + java.util.concurrent.ThreadLocalRandom.current().nextInt(others)) % group.size());
            if (listener == speaker) continue;
            World w = Bukkit.getWorld(speaker.getWorld());
            if (w == null) continue;
            chatBeat(speaker, listener, w, now, "space_chat", "gossip");
        }
    }

    private long gossipLineDelay() {
        return (long) (plugin.getConfig().getDouble("gossip.line-interval-seconds", 7) * 1000
                * (0.7 + Math.random() * 0.8));
    }

    // --- doors: a walking NPC opens doors/gates/trapdoors ahead and closes them behind ---

    private void tickDoors(Character c, World w, boolean walking, long now) {
        if (!doorsEnabled) return;
        Map<org.bukkit.block.Block, Long> open = openedDoors.get(c.uuid());
        if (open != null) {
            for (Iterator<Map.Entry<org.bukkit.block.Block, Long>> it = open.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<org.bukkit.block.Block, Long> en = it.next();
                org.bukkit.block.Block b = en.getKey();
                double dx = b.getX() + 0.5 - c.getX(), dz = b.getZ() + 0.5 - c.getZ();
                if (now >= en.getValue() && dx * dx + dz * dz > 1.4 * 1.4) {
                    setDoorOpen(w, b, false);
                    it.remove();
                }
            }
            if (open.isEmpty()) openedDoors.remove(c.uuid());
        }
        if (!walking) return;
        long nextCheck = nextDoorCheck.getOrDefault(c.uuid(), 0L);
        if (now < nextCheck) return;
        nextDoorCheck.put(c.uuid(), now + 200L);   // throttle scan to ~5 Hz instead of 20 Hz

        double yaw = Math.toRadians(c.movementYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        int bx1 = (int) Math.floor(c.getX() + fx * 0.6);
        int by = (int) Math.floor(c.getY() + 0.1);
        int bz1 = (int) Math.floor(c.getZ() + fz * 0.6);

        int bx2 = (int) Math.floor(c.getX() + fx * 1.2);
        int bz2 = (int) Math.floor(c.getZ() + fz * 1.2);

        if (w.isChunkLoaded(bx1 >> 4, bz1 >> 4)) {
            openIfDoor(c, w, w.getBlockAt(bx1, by, bz1), now);
        }
        // Deduplicate: only inspect second block if it differs from the first
        if ((bx1 != bx2 || bz1 != bz2) && w.isChunkLoaded(bx2 >> 4, bz2 >> 4)) {
            openIfDoor(c, w, w.getBlockAt(bx2, by, bz2), now);
        }
    }

    private void openIfDoor(Character c, World w, org.bukkit.block.Block b, long now) {
        org.bukkit.Material mat = b.getType();
        if (mat.isAir() || mat.name().startsWith("IRON")) return;   // fast isAir bail + can't open iron by hand
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Door door
                && door.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) {
            b = b.getRelative(org.bukkit.block.BlockFace.DOWN);
        }
        if (!(b.getBlockData() instanceof org.bukkit.block.data.Openable o)) return;
        Map<org.bukkit.block.Block, Long> open = openedDoors.computeIfAbsent(c.uuid(), k -> new java.util.HashMap<>());
        if (o.isOpen()) {
            if (open.containsKey(b)) open.put(b, now + 1200);   // keep ours open while still passing
            return;
        }
        setDoorOpen(w, b, true);
        open.put(b, now + 1200);
    }

    private void setDoorOpen(World w, org.bukkit.block.Block b, boolean value) {
        if (!(b.getBlockData() instanceof org.bukkit.block.data.Openable o) || o.isOpen() == value) return;
        o.setOpen(value);
        b.setBlockData((org.bukkit.block.data.BlockData) o, false);
        String n = b.getType().name();
        Sound sound = n.contains("FENCE_GATE") ? (value ? Sound.BLOCK_FENCE_GATE_OPEN : Sound.BLOCK_FENCE_GATE_CLOSE)
                : n.contains("TRAPDOOR") ? (value ? Sound.BLOCK_WOODEN_TRAPDOOR_OPEN : Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE)
                : (value ? Sound.BLOCK_WOODEN_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_CLOSE);
        w.playSound(b.getLocation().add(0.5, 0.5, 0.5), sound, 0.8f, 1f);
    }

    /** Nearest player within {@code radius} of a point, or null. */
    private Player nearestPlayer(World w, double x, double y, double z, double radius) {
        double r2 = radius * radius;
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        Location at = new Location(w, x, y, z);
        for (Player p : w.getPlayers()) {
            double d2 = p.getLocation().distanceSquared(at);
            if (d2 <= r2 && d2 < bestSq) { bestSq = d2; best = p; }
        }
        return best;
    }

    /** Per-scan behavior hook. B0: shopkeepers/informers are static; barker movement/invite lands in B1–B3. */
    private void tickBehavior(Character c, World w) {
        // no-op until the barker behavior module (B1+)
    }

    // --- render scan -------------------------------------------------------

    private void scan() {
        for (Character c : byId.values()) scanOne(c);
    }

    /** Movement tick: combat (flee/fight) first, then outings, then per-type behavior. */
    private void moveBarkers() {
        long now = System.currentTimeMillis();
        runDueGestures(now);
        if (now >= nextOutingCheckMs) {
            nextOutingCheckMs = now + 1000;
            tickOutingScheduler(now);
            tickSpaceChats(now);
        }
        if (movers.isEmpty()) return;
        long pauseMs = (long) (plugin.getConfig().getDouble("barker.waypoint-pause-seconds", 3) * 1000);
        long addressMs = (long) (plugin.getConfig().getDouble("barker.invite-address-seconds", 4) * 1000);
        double noticeR = plugin.getConfig().getDouble("barker.notice-radius", 12);
        double talkR = plugin.getConfig().getDouble("barker.invite-radius", 5);
        for (Character c : new ArrayList<>(byId.values())) {
            MovementController mover = movers.get(c.uuid());
            if (mover == null) continue;
            World w = Bukkit.getWorld(c.getWorld());
            if (w == null) continue;
            resync(c, now);
            tickDoors(c, w, mover.isMoving(), now);

            // being hit overrides everything: run away or fight back, then walk home
            CombatState st = combat.get(c.uuid());
            if (st != null) {
                tickCombat(c, w, mover, st, now);
                continue;
            }
            // a friendly duel in progress — mirror the player's weapon until they stand down
            SparState sps = spar.get(c.uuid());
            if (sps != null) {
                tickSpar(c, w, mover, sps, now);
                continue;
            }
            // out on the town: the outing state machine owns the NPC until it's back at its post
            AwayState as = away.get(c.uuid());
            if (as != null) {
                tickAway(c, w, mover, as, now);
                continue;
            }
            if (c.type() == CharacterType.LOBBY || c.type() == CharacterType.SHOP) {
                tickLobby(c, w, mover, now);   // shops live the same little life around their post
                continue;
            }
            if (c.type() != CharacterType.BARKER) {
                movers.remove(c.uuid());   // shopkeepers/informers only carry a mover during combat
                continue;
            }
            setSeated(c, false);
            tickPassivePlace(c, w, now);

            // mid-invite → keep gesturing while the line plays
            if (now < addressUntil.getOrDefault(c.uuid(), 0L)) { continue; }

            // waiting to see if a just-invited player enters the shop region → resolve success/give-up first
            if (resolvePending(c, w, now, noticeR)) {
                // still watching them — patrol on, but don't call out to anyone new this window
                mover.clearApproach();
                sendMove(c, mover.tick(c, w, pauseMs));
                idleFidget(c, now);
                continue;
            }

            Player target = nearestProspect(c, w, noticeR);
            if (target != null) {
                Location npc = new Location(w, c.getX(), c.getY(), c.getZ());
                if (target.getLocation().distanceSquared(npc) <= talkR * talkR) {
                    // close enough → stop, face them, and call out (gesturing)
                    mover.clearApproach();
                    if (dialog != null && dialog.trigger(speakerOf(c), "invite", target)) {
                        beginInvite(c, target, now);
                        mover.pauseFor(addressMs);
                        addressUntil.put(c.uuid(), now + addressMs);
                        faceViewers(c, w, target);
                        swingViewers(c, now);
                    } else {
                        // can't speak right now (no invite node / cooldowns) — back off and keep patrolling
                        // instead of standing frozen staring at them (the old behavior locked the barker in
                        // place for as long as ANY player stood within invite range).
                        setInviteCd(c, target.getUniqueId(), now,
                                plugin.getConfig().getDouble("barker.invite-retry-seconds", 8));
                        sendMove(c, mover.tick(c, w, pauseMs));
                    }
                } else {
                    // spotted them across the way → walk over
                    mover.approach(target.getX(), target.getY(), target.getZ());
                    sendMove(c, mover.tick(c, w, pauseMs));
                }
                continue;
            }

            // nobody about → patrol (with the occasional idle gesture)
            mover.clearApproach();
            sendMove(c, mover.tick(c, w, pauseMs));
            idleFidget(c, now);
            tickPassivePlace(c, w, now);
        }
    }

    /** Re-send the absolute position every few seconds so relative-move rounding never strands the client. */
    private void resync(Character c, long now) {
        if (c.viewers.isEmpty() || c.seated) return;   // a seated NPC's position belongs to its seat entity
        if (now < nextResync.getOrDefault(c.uuid(), 0L)) return;
        nextResync.put(c.uuid(), now + 4000);
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                dev.servereer.livingshopkeeper.move.MovePackets.teleport(
                        p, c.entityId, c.getX(), c.getY(), c.getZ(), c.yaw(), c.pitch(), true);
                PacketNpc.teleportTags(p, c);
            }
        }
    }

    /** Hard-set the position for us and every viewer (arrivals, combat return) — kills accumulated drift. */
    private void snapTo(Character c, double x, double y, double z, float yaw, float pitch) {
        c.setPosition(x, y, z);
        c.setYaw(yaw);
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                dev.servereer.livingshopkeeper.move.MovePackets.teleport(p, c.entityId, x, y, z, yaw, pitch, true);
                PacketNpc.teleportTags(p, c);
            }
        }
    }

    /**
     * Begin watching an invited player: mark them pending, block re-inviting them for the watch window, and
     * arm the give-up deadline. {@link #resolvePending} then fires {@code invite_success} when they step into
     * the shop region, or {@code invite_giveup} when the window lapses / they wander off.
     */
    private void beginInvite(Character c, Player target, long now) {
        long window = (long) (plugin.getConfig().getDouble("barker.invite-window-seconds", 12) * 1000);
        pendingTarget.put(c.uuid(), target.getUniqueId());
        pendingUntil.put(c.uuid(), now + window);
        inviteCd.computeIfAbsent(c.uuid(), k -> new java.util.HashMap<>()).put(target.getUniqueId(), now + window);
    }

    /** @return true if a pending invite is still unresolved (barker should keep watching, not invite anew). */
    private boolean resolvePending(Character c, World w, long now, double noticeR) {
        UUID pend = pendingTarget.get(c.uuid());
        if (pend == null) return false;
        Player pt = Bukkit.getPlayer(pend);
        Location npcLoc = new Location(w, c.getX(), c.getY(), c.getZ());

        if (pt != null && w.equals(pt.getWorld()) && insideRegion(c, pt, w)) {
            if (dialog != null) dialog.trigger(speakerOf(c), "invite_success", pt);   // they came in!
            setInviteCd(c, pend, now, plugin.getConfig().getDouble("barker.attracted-cooldown-seconds", 3600));
            clearPending(c);
            return false;
        }
        boolean gone = pt == null || !w.equals(pt.getWorld())
                || pt.getLocation().distanceSquared(npcLoc) > sq(noticeR * 1.6);
        boolean lapsed = now > pendingUntil.getOrDefault(c.uuid(), 0L);
        if (gone || lapsed) {
            if (pt != null && dialog != null) dialog.trigger(speakerOf(c), "invite_giveup", pt);
            double ignored = c.inviteCooldownSeconds() != null ? c.inviteCooldownSeconds()
                    : plugin.getConfig().getDouble("barker.ignored-cooldown-seconds", 300);
            setInviteCd(c, pend, now, ignored);
            clearPending(c);
            return false;
        }
        return true;   // still within the window and near — keep watching
    }

    /** True if {@code p} is inside this barker's shop region — a radius around its home (or its post). */
    private boolean insideRegion(Character c, Player p, World w) {
        double[] home = c.home();
        double hx = home != null ? home[0] : c.getX();
        double hy = home != null ? home[1] : c.getY();
        double hz = home != null ? home[2] : c.getZ();
        double r = Math.max(1, plugin.getConfig().getDouble("barker.region-radius", 6));
        return p.getLocation().distanceSquared(new Location(w, hx, hy, hz)) <= r * r;
    }

    private void setInviteCd(Character c, UUID player, long now, double seconds) {
        inviteCd.computeIfAbsent(c.uuid(), k -> new java.util.HashMap<>()).put(player, now + (long) (seconds * 1000));
    }

    private void clearPending(Character c) {
        pendingTarget.remove(c.uuid());
        pendingUntil.remove(c.uuid());
    }

    private static double sq(double v) { return v * v; }

    private void sendMove(Character c, double[] mv) {
        if (mv == null) return;
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) PacketNpc.move(p, c, mv[0], mv[1], mv[2], (float) mv[3], mv.length < 5 || mv[4] > 0.5);
        }
    }

    private void swingViewers(Character c, long now) {
        Long last = lastSwing.get(c.uuid());
        if (last != null && now - last < 650) return;   // leave a visible pause between gestures
        lastSwing.put(c.uuid(), now);
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) PacketNpc.swing(p, c);
        }
    }

    /** Nearest same-world player within {@code radius} the barker hasn't invited recently, or null. */
    private Player nearestProspect(Character c, World w, double radius) {
        double r2 = radius * radius;
        long now = System.currentTimeMillis();
        Map<UUID, Long> cd = inviteCd.get(c.uuid());
        Location npc = new Location(w, c.getX(), c.getY(), c.getZ());
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        for (Player p : w.getPlayers()) {
            if (cd != null && now < cd.getOrDefault(p.getUniqueId(), 0L)) continue;
            double d2 = p.getLocation().distanceSquared(npc);
            if (d2 <= r2 && d2 < bestSq) { bestSq = d2; best = p; }
        }
        return best;
    }

    private ActivityPlace placeAt(Character c, Location loc) {
        if (loc == null) return null;
        ActivityPlace best = null;
        double bestD2 = Double.MAX_VALUE;
        for (ActivityPlace p : c.places()) {
            if (!p.contains(loc)) continue;
            double d2 = loc.distanceSquared(new Location(loc.getWorld(), p.x(), p.y(), p.z()));
            if (d2 < bestD2) { bestD2 = d2; best = p; }
        }
        return best;
    }

    private void tickPassivePlace(Character c, World w, long now) {
        ActivityPlace place = placeAt(c, new Location(w, c.getX(), c.getY(), c.getZ()));
        if (place == null) {
            currentPlace.remove(c.uuid());
            return;
        }
        tickPlaceConversation(c, w, place, now);
    }

    private void tickPlaceConversation(Character c, World w, ActivityPlace place, long now) {
        String old = currentPlace.put(c.uuid(), place.id());
        Player listener = nearestInPlace(w, place);
        if (old == null || !old.equals(place.id())) {
            if (listener != null && dialog != null) dialog.trigger(speakerOf(c), "arrive_" + place.id(), listener);
            nextPlaceAmbient.put(c.uuid(), now + placeAmbientDelay());
        }
        long next = nextPlaceAmbient.getOrDefault(c.uuid(), 0L);
        if (listener != null && now >= next && dialog != null) {
            dialog.trigger(speakerOf(c), "ambient_" + place.id(), listener);
            nextPlaceAmbient.put(c.uuid(), now + placeAmbientDelay());
        }
    }

    private Player nearestInPlace(World w, ActivityPlace place) {
        Player best = null;
        double bestD2 = Double.MAX_VALUE;
        Location center = new Location(w, place.x(), place.y(), place.z());
        double radius = Math.max(place.radius(), plugin.getConfig().getDouble("hearing-distance", 16));
        for (Player p : w.getPlayers()) {
            double d2 = p.getLocation().distanceSquared(center);
            if (d2 <= radius * radius && d2 < bestD2) { best = p; bestD2 = d2; }
        }
        return best;
    }

    private long placeAmbientDelay() {
        double seconds = plugin.getConfig().getDouble("natural.place-ambient-interval-seconds", 28);
        return jitter(Math.max(4, seconds));
    }

    private void faceAll(Character c, float yaw, float pitch) {
        if (!rotationChanged(c, yaw, pitch)) return;
        c.setYaw(yaw);
        c.lastSentYaw = yaw;
        c.lastSentPitch = pitch;
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) PacketNpc.rotate(p, c, yaw, pitch);
        }
    }

    private void setSeated(Character c, boolean seated) {
        if (c.seated == seated) return;
        c.seated = seated;
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (seated) PacketNpc.attachSeat(p, c); else PacketNpc.detachSeat(p, c);
            PacketNpc.teleportTags(p, c);   // the tag stack rides at a lower height while sitting
        }
    }

    /** Small body-language choices while a line is being spoken; the pauses are deliberately irregular. */
    private void speechGesture(Character c, long now) {
        if (speech == null || !speech.isSpeaking(c.uuid())) {
            if (crouched.contains(c.uuid()) && now >= crouchUntil.getOrDefault(c.uuid(), 0L)) setCrouching(c, false);
            return;
        }
        if (now < nextSpeechGesture.getOrDefault(c.uuid(), 0L)) return;
        nextSpeechGesture.put(c.uuid(), now + 900 + (long) (Math.random() * 1100));
        if (isWalking(c)) {   // mid-stride: only an arm gesture — crouching here looked like sneak-walking
            if (Math.random() < 0.4) swingViewers(c, now);
            return;
        }
        double roll = Math.random();
        if (roll < 0.48) swingViewers(c, now);
        else if (roll < 0.72) {
            setCrouching(c, true);
            crouchUntil.put(c.uuid(), now + 350 + (long) (Math.random() * 550));
        } else if (roll < 0.9) {
            float yaw = c.yaw() + (float) ((Math.random() - 0.5) * 24);
            faceAll(c, yaw, c.pitch());
        }
    }

    /** True while this character's legs are actually moving (an active route, not dwelling). */
    private boolean isWalking(Character c) {
        MovementController m = movers.get(c.uuid());
        return m != null && m.isMoving();
    }

    private void setCrouching(Character c, boolean value) {
        if (value == crouched.contains(c.uuid())) return;
        if (value) crouched.add(c.uuid()); else crouched.remove(c.uuid());
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) PacketNpc.crouch(p, c, value);
        }
    }

    // --- idle body life: small look-arounds + gestures so a still NPC doesn't feel like a statue ---

    /** Occasionally swing the arm — a small fidget. Armed on first sight so it never swings the instant seen. */
    private void idleFidget(Character c, long now) {
        if (!plugin.getConfig().getBoolean("idle.enabled", true) || !plugin.getConfig().getBoolean("idle.fidget", true)) return;
        long next = nextFidget.getOrDefault(c.uuid(), 0L);
        if (next == 0L) { nextFidget.put(c.uuid(), now + fidgetDelay()); return; }
        if (now < next) return;
        nextFidget.put(c.uuid(), now + fidgetDelay());
        if (isWalking(c)) {   // never crouch or yank the head mid-stride — that read as broken sneak-walking
            if (Math.random() < 0.35) swingViewers(c, now);
            return;
        }
        double roll = Math.random();
        if (roll < 0.58) swingViewers(c, now);
        else if (roll < 0.78) {
            setCrouching(c, true);
            crouchUntil.put(c.uuid(), now + 300 + (long) (Math.random() * 600));
        } else {
            float yaw = c.yaw() + (float) ((Math.random() - 0.5) * 35);
            faceAll(c, yaw, c.pitch());
        }
    }

    /** Occasionally glance to a gentle random direction (used when no player is close enough to look at). */
    private void idleGlance(Character c, World w, long now) {
        if (!plugin.getConfig().getBoolean("idle.enabled", true) || !plugin.getConfig().getBoolean("idle.glance", true)) return;
        long next = nextGlance.getOrDefault(c.uuid(), 0L);
        if (next == 0L) { nextGlance.put(c.uuid(), now + glanceDelay()); return; }
        if (now < next) return;
        nextGlance.put(c.uuid(), now + glanceDelay());
        float yaw = c.yaw() + (float) ((Math.random() - 0.5) * 120);   // ±60° gentle turn
        float pitch = (float) ((Math.random() - 0.4) * 30);           // a little up/down
        c.setYaw(yaw);
        c.lastSentYaw = yaw;
        c.lastSentPitch = pitch;
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) PacketNpc.rotate(p, c, yaw, pitch);
        }
    }

    private long fidgetDelay() {
        return jitter(plugin.getConfig().getDouble("idle.fidget-interval-seconds", 12));
    }

    private long glanceDelay() {
        return jitter(plugin.getConfig().getDouble("idle.glance-interval-seconds", 6));
    }

    /** base seconds → millis with a 0.6×–1.4× random spread so the rhythm never looks metronomic. */
    private static long jitter(double baseSeconds) {
        return (long) (baseSeconds * 1000 * (0.6 + Math.random() * 0.8));
    }

    private void faceViewers(Character c, World w, Player target) {
        float[] yp = lookAt(new Location(w, c.getX(), c.getY(), c.getZ()), target.getEyeLocation());
        c.setYaw(yp[0]);
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) PacketNpc.rotate(p, c, yp[0], yp[1]);
        }
    }

    private void scanOne(Character c) {
        World w = Bukkit.getWorld(c.getWorld());
        if (w == null) { despawnAll(c); return; }
        tickBehavior(c, w);
        AwayState as = away.get(c.uuid());
        if (as != null) tickGhostRender(c, as, w);
        Location npc = new Location(w, c.getX(), c.getY(), c.getZ());

        Set<UUID> shouldSee = new java.util.HashSet<>();
        Player nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (Player p : w.getPlayers()) {
            double d2 = p.getLocation().distanceSquared(npc);
            if (d2 <= hearingSq) {
                shouldSee.add(p.getUniqueId());
                if (d2 < bestSq) { bestSq = d2; nearest = p; }
            }
        }

        for (UUID id : shouldSee) {
            if (!c.viewers.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    PacketNpc.spawn(p, c);
                    c.viewers.add(id);
                    if (crouched.contains(c.uuid())) PacketNpc.crouch(p, c, true);
                    SparState sps = spar.get(c.uuid());
                    if (sps != null) {
                        if (sps.weapon != null && !sps.weapon.isAir()) {
                            PacketNpc.equipMainHand(p, c, new org.bukkit.inventory.ItemStack(sps.weapon));
                        }
                        String armor = sparArmor(c);
                        if (armor != null) PacketNpc.equipArmor(p, c, armor);
                    }
                    boolean spoke = false;
                    ActivityPlace place = placeAt(c, npc);
                    if (place != null && dialog != null) spoke = dialog.trigger(speakerOf(c), "approach_" + place.id(), p);
                    if (!spoke && dialog != null) dialog.trigger(speakerOf(c), "approach", p);
                }
            }
        }
        for (Iterator<UUID> it = c.viewers.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (!shouldSee.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) PacketNpc.despawn(p, c);
                it.remove();
            }
        }

        if (!c.viewers.isEmpty()) {
            speechGesture(c, System.currentTimeMillis());
        }
        if (c.type() != CharacterType.BARKER && !c.viewers.isEmpty() && !isWalking(c)) {
            long now = System.currentTimeMillis();
            double engage = plugin.getConfig().getDouble("idle.engage-distance", 5);
            boolean engaged = nearest != null && bestSq <= engage * engage;
            if (engaged) {   // someone's close → track their eyes
                float[] yp = lookAt(npc, nearest.getEyeLocation());
                if (rotationChanged(c, yp[0], yp[1])) {
                    c.setYaw(yp[0]);
                    for (UUID id : c.viewers) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) PacketNpc.rotate(p, c, yp[0], yp[1]);
                    }
                    c.lastSentYaw = yp[0];
                    c.lastSentPitch = yp[1];
                }
            } else {         // nobody close → idle life: glance around + the odd gesture
                idleGlance(c, w, now);
                idleFidget(c, now);
            }
        }
    }

    private static boolean rotationChanged(Character c, float yaw, float pitch) {
        if (Float.isNaN(c.lastSentYaw)) return true;
        return angleDelta(yaw, c.lastSentYaw) >= ROTATE_THRESHOLD_DEG
                || Math.abs(pitch - c.lastSentPitch) >= ROTATE_THRESHOLD_DEG;
    }

    private static float angleDelta(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    private static float[] lookAt(Location npc, Location targetEye) {
        double dx = targetEye.getX() - npc.getX();
        double dy = targetEye.getY() - (npc.getY() + EYE_HEIGHT);
        double dz = targetEye.getZ() - npc.getZ();
        double distXz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, distXz));
        return new float[]{yaw, pitch};
    }

    private void despawnAll(Character c) {
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) PacketNpc.despawn(p, c);
        }
        c.viewers.clear();
    }

    private void respawn(Character c) {
        despawnAll(c);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (byId.get(c.getId().toLowerCase(Locale.ROOT)) == c) scanOne(c);
        }, 2L);
    }

    private void despawnAllEverywhere() {
        for (Character c : byId.values()) despawnAll(c);
        for (Map.Entry<UUID, AwayState> en : away.entrySet()) {
            Character c = byUuid(en.getKey());
            if (c != null) clearGhost(c, en.getValue());
        }
    }

    private Character byUuid(UUID uuid) {
        for (Character c : byId.values()) if (c.uuid().equals(uuid)) return c;
        return null;
    }

    // --- helpers -----------------------------------------------------------

    private void shipDefaultDialog(File folder, String resource) {
        File out = new File(new File(folder, "dialog"), "default.yml");
        if (out.isFile()) return;
        //noinspection ResultOfMethodCallIgnored
        out.getParentFile().mkdirs();
        try (InputStream in = plugin.getResource(resource)) {
            if (in != null) Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private static void copyFilesShallow(File src, File dst) throws IOException {
        if (src == null || !src.isDirectory()) return;
        //noinspection ResultOfMethodCallIgnored
        dst.mkdirs();
        File[] fs = src.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isFile()) Files.copy(f.toPath(), new File(dst, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
