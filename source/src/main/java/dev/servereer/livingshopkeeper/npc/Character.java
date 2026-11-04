package dev.servereer.livingshopkeeper.npc;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One authored NPC of some {@link CharacterType}, living in its own folder
 * {@code characters/<id>/} (with {@code character.yml}, a {@code dialog/} folder and a {@code voice/}
 * folder). The identity {@link #uuid} backs the tab-list profile and spawned entity; {@link #entityId}
 * is assigned fresh at load and maps interact packets back to this character.
 *
 * <p>Data + YAML I/O only — rendering lives in {@link PacketNpc}, lifecycle/behavior in
 * {@link CharacterManager}. Barker fields ({@link #region}, {@link #home}, {@link #waypoints}) are only
 * meaningful for {@link CharacterType#BARKER}; they're parsed and preserved regardless.
 */
public final class Character {

    private final String id;         // == folder name
    private final File folder;       // characters/<id>/
    private final UUID uuid;
    private CharacterType type;
    private String world;
    private double x, y, z;
    private float yaw, pitch;
    private String name;
    private String display;   // MiniMessage display name (floating tag + chat); null → fall back to name

    // skin (may be null = default). Persisted once resolved so restarts never refetch.
    private String skinName, textureValue, textureSignature;

    private String greetingClip = "greeting";   // fallback clip when no dialog matches

    // per-character hit reaction: "flee" | "fightback" | "none", or null → follow the global config
    private String combatReaction;

    // floating multi-line tag (MiniMessage per line; replaces the profile nametag when non-empty)
    private final List<String> tagLines = new ArrayList<>();

    // --- outings (any post-holding type): alternate identity while away at a space ---
    private final List<String> awayTagLines = new ArrayList<>();   // tag shown while out; empty → keep normal
    private Double awayMinutes;      // default outing length for /lsk away; null → config outings.default-minutes
    private String awaySpace;        // legacy single preferred space id (migrated into awaySpaces on load)
    private final List<String> awaySpaces = new ArrayList<>();   // spaces this NPC may visit; empty → any

    private String persona;          // short character description fed to the AI chat system, or null
    // gossip flavour: what the NPC does, how it carries itself, how it feels — read by <speaker_job>,
    // <speaker_trait>, <mood_speaker> (see GossipPlaceholders). All optional, all free text.
    private String job, trait, mood;
    private String sparRegion;       // WG region where this NPC spars (ai.spar on); null → anywhere
    private String sparStyle;        // balanced | aggressive | defensive; null → balanced
    private String sparArmor;        // armor tier worn while dueling; null → global spar.armor config
    // per-NPC combat numbers (damage, swing-cooldown-ms, …); absent key → the global spar.* config
    private final Map<String, Double> sparStats = new LinkedHashMap<>();

    // click actions ("player:/warps", "console:…", "message:…", "sound:…"); run on click when non-empty
    private final List<String> actions = new ArrayList<>();

    // --- lobby config (type == LOBBY) ---
    private final List<double[]> seats = new ArrayList<>();   // wand-marked sit spots, each {x,y,z,yaw}
    private int prioritySeat = -1;                            // index into seats a SHOP prefers while attending

    // --- shop config (type == SHOP) ---
    private UUID owner;            // the player who placed it; null → admin shop (infinite stock)
    private int[] container;        // linked stock chest {x,y,z} in this character's world; null for admin shops
    private final List<dev.servereer.livingshopkeeper.shop.ShopTrade> trades = new ArrayList<>();
    // owner-toggleable AI behaviors ("roam", "sit", "gossip", "hawk"); absent → each one's default
    private final Map<String, Boolean> ai = new LinkedHashMap<>();

    // --- barker config (type == BARKER) ---
    private String region;                       // ARM/WG region id, or null
    private double[] home;                        // {x,y,z,yaw,pitch} idle/return point, or null → use x,y,z
    private final List<double[]> waypoints = new ArrayList<>();   // patrol route, each {x,y,z}
    private final Map<String, ActivityPlace> places = new LinkedHashMap<>();
    private Double inviteCooldownSeconds;         // per-barker re-greet gap; null → use global config default

    // --- runtime (not persisted) ---
    final Set<UUID> viewers = new HashSet<>();
    int entityId;
    int seatEntityId;
    int[] tagEntityIds = new int[0];   // one armor-stand hologram per ACTIVE tag line
    List<String> tagOverride;          // runtime tag replacement (away tag while out); null → config tag
    boolean seated;
    float lastSentYaw = Float.NaN, lastSentPitch = Float.NaN;

    Character(String id, File folder, UUID uuid, CharacterType type, String world,
              double x, double y, double z, float yaw, float pitch, String name) {
        this.id = id;
        this.folder = folder;
        this.uuid = uuid;
        this.type = type;
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch;
        this.name = name;
    }

    // --- identity / position ---
    public String getId() { return id; }
    File folder() { return folder; }
    UUID uuid() { return uuid; }
    public CharacterType type() { return type; }
    public String getWorld() { return world; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    float yaw() { return yaw; }
    float pitch() { return pitch; }
    void setYaw(float yaw) { this.yaw = yaw; }
    public float movementYaw() { return yaw; }
    public void setMovementYaw(float yaw) { this.yaw = yaw; }
    public String getName() { return name; }
    void setName(String name) { this.name = name; }
    /** MiniMessage display name for the floating tag + chat, or the plain name if none was set. */
    public String display() { return display != null ? display : name; }
    boolean hasDisplay() { return display != null; }
    void setDisplay(String display) { this.display = (display == null || display.isBlank()) ? null : display; }

    /** Move the live position (barker walking). Does not persist — call {@link #save} to keep it. */
    public void setPosition(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

    // --- skin ---
    String skinName() { return skinName; }
    String textureValue() { return textureValue; }
    String textureSignature() { return textureSignature; }
    public boolean hasSkin() { return textureValue != null; }
    void setSkin(String skinName, String value, String signature) {
        this.skinName = skinName; this.textureValue = value; this.textureSignature = signature;
    }

    String greetingClip() { return greetingClip; }
    void setGreetingClip(String c) { this.greetingClip = (c == null || c.isBlank()) ? "greeting" : c; }

    /** Hit reaction override ("flee" | "fightback" | "none"), or null → the global {@code combat.reaction}. */
    public String combatReaction() { return combatReaction; }
    void setCombatReaction(String r) { this.combatReaction = (r == null || r.isBlank()) ? null : r; }

    // --- floating tag / click actions ---
    public List<String> tagLines() { return tagLines; }
    void setTagLines(List<String> lines) { tagLines.clear(); if (lines != null) tagLines.addAll(lines); }
    /** The lines actually rendered right now: the runtime override (away tag) when set, else the config tag. */
    List<String> activeTagLines() { return tagOverride != null ? tagOverride : tagLines; }
    public boolean hasTag() { return !activeTagLines().isEmpty(); }

    // --- outings ---
    public List<String> awayTagLines() { return awayTagLines; }
    void setAwayTagLines(List<String> lines) { awayTagLines.clear(); if (lines != null) awayTagLines.addAll(lines); }
    /** Default outing length (minutes) for this character, or null → the global config default. */
    public Double awayMinutes() { return awayMinutes; }
    void setAwayMinutes(Double m) { this.awayMinutes = m; }
    /** Spaces this NPC is allowed to visit on an outing; empty → any space in its world. */
    public List<String> awaySpaces() { return awaySpaces; }
    void setAwaySpaces(List<String> ids) {
        awaySpaces.clear();
        if (ids != null) for (String id : ids) if (id != null && !id.isBlank()) awaySpaces.add(id);
    }
    /** Toggle one space in the allow-list; returns true if it is now allowed, false if it was removed. */
    boolean toggleAwaySpace(String id) {
        if (id == null || id.isBlank()) return false;
        if (awaySpaces.remove(id)) return false;
        awaySpaces.add(id);
        return true;
    }

    /** Short in-character description for the AI chat system, or null → a generic one. */
    public String persona() { return persona; }
    void setPersona(String p) { this.persona = (p == null || p.isBlank()) ? null : p; }

    /** What the NPC does, shown by {@code <speaker_job>}/{@code <listener_job>}; null → "trader". */
    public String job() { return job; }
    void setJob(String v) { this.job = blankNull(v); }
    /** How the NPC carries itself; only the first word is shown by {@code <speaker_trait>}; null → "plain". */
    public String trait() { return trait; }
    void setTrait(String v) { this.trait = blankNull(v); }
    /** How the NPC feels right now, shown by {@code <mood_speaker>}/{@code <mood_listener>}; null → "content". */
    public String mood() { return mood; }
    void setMood(String v) { this.mood = blankNull(v); }
    /** WG region this NPC spars in (with ai.spar on), or null → anywhere it's hit. */
    public String sparRegion() { return sparRegion; }
    void setSparRegion(String r) { this.sparRegion = (r == null || r.isBlank()) ? null : r; }
    /** Fighting style: balanced | aggressive | defensive; null → balanced. */
    public String sparStyle() { return sparStyle; }
    void setSparStyle(String s) { this.sparStyle = (s == null || s.isBlank()) ? null : s.toLowerCase(java.util.Locale.ROOT); }
    /** Armor tier worn while dueling (iron/diamond/…/none), or null → the global spar.armor config. */
    public String sparArmor() { return sparArmor; }
    void setSparArmor(String a) { this.sparArmor = (a == null || a.isBlank()) ? null : a.toLowerCase(java.util.Locale.ROOT); }
    /** This NPC's own value for a spar stat (e.g. "damage"), or null → the global config's. */
    public Double sparStat(String key) { return sparStats.get(key); }
    public Map<String, Double> sparStats() { return sparStats; }
    void setSparStat(String key, Double value) {
        if (value == null) sparStats.remove(key);
        else sparStats.put(key, value);
    }
    public List<String> actions() { return actions; }
    void setActions(List<String> a) { actions.clear(); if (a != null) actions.addAll(a); }

    // --- shop ---
    public UUID owner() { return owner; }
    public void setOwner(UUID owner) { this.owner = owner; }
    public int[] container() { return container; }
    public void setContainer(int[] xyz) { this.container = xyz; }
    /** This character's setting for the AI behavior {@code key}, or {@code def} when never toggled. */
    public boolean ai(String key, boolean def) { return ai.getOrDefault(key, def); }
    public void setAi(String key, boolean on) { ai.put(key, on); }
    public List<dev.servereer.livingshopkeeper.shop.ShopTrade> trades() { return trades; }
    public void setTrades(List<dev.servereer.livingshopkeeper.shop.ShopTrade> list) {
        trades.clear();
        if (list != null) trades.addAll(list);
    }

    // --- lobby ---
    public List<double[]> seats() { return seats; }
    void addSeat(double sx, double sy, double sz, float syaw) { seats.add(new double[]{sx, sy, sz, syaw}); }
    boolean removeSeatNear(double sx, double sy, double sz, double radius) {
        int i = seatIndexNear(sx, sy, sz, radius);
        if (i < 0) return false;
        seats.remove(i);
        if (prioritySeat == i) prioritySeat = -1;
        else if (prioritySeat > i) prioritySeat--;
        return true;
    }
    void clearSeats() { seats.clear(); prioritySeat = -1; }
    int seatIndexNear(double sx, double sy, double sz, double radius) {
        for (int i = 0; i < seats.size(); i++) {
            double[] s = seats.get(i);
            double dx = s[0] - sx, dy = s[1] - sy, dz = s[2] - sz;
            if (dx * dx + dy * dy + dz * dz <= radius * radius) return i;
        }
        return -1;
    }
    /** The seat this NPC prefers while attending customers, or null when unset/invalid. */
    public double[] prioritySeat() {
        return prioritySeat >= 0 && prioritySeat < seats.size() ? seats.get(prioritySeat) : null;
    }
    void setPrioritySeat(int index) { this.prioritySeat = index; }

    // --- barker ---
    public String region() { return region; }
    void setRegion(String r) { this.region = (r == null || r.isBlank()) ? null : r; }
    public double[] home() { return home; }
    void setHome(double[] h) { this.home = h; }
    public List<double[]> waypoints() { return waypoints; }
    void addWaypoint(double wx, double wy, double wz) { waypoints.add(new double[]{wx, wy, wz}); }
    void clearWaypoints() { waypoints.clear(); }
    public List<ActivityPlace> places() { return List.copyOf(places.values()); }
    void putPlace(ActivityPlace place) { places.put(place.id(), place); }
    boolean removePlace(String id) { return places.remove(id.toLowerCase(java.util.Locale.ROOT)) != null; }
    /** Per-barker re-greet cooldown in seconds, or null to fall back to the global config default. */
    public Double inviteCooldownSeconds() { return inviteCooldownSeconds; }
    void setInviteCooldownSeconds(Double seconds) { this.inviteCooldownSeconds = seconds; }

    // --- persistence ---

    void save() throws IOException {
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        YamlConfiguration yc = new YamlConfiguration();
        yc.set("type", type.id());
        yc.set("uuid", uuid.toString());
        yc.set("name", name);
        if (display != null) yc.set("display", display);
        yc.set("world", world);
        yc.set("x", x); yc.set("y", y); yc.set("z", z);
        yc.set("yaw", yaw); yc.set("pitch", pitch);
        if (skinName != null) yc.set("skin.name", skinName);
        if (textureValue != null) yc.set("skin.value", textureValue);
        if (textureSignature != null) yc.set("skin.signature", textureSignature);
        yc.set("voice.greeting", greetingClip);
        if (combatReaction != null) yc.set("combat.reaction", combatReaction);
        for (Map.Entry<String, Boolean> en : ai.entrySet()) yc.set("ai." + en.getKey(), en.getValue());
        if (!tagLines.isEmpty()) yc.set("tag", new ArrayList<>(tagLines));
        if (!actions.isEmpty()) yc.set("actions", new ArrayList<>(actions));
        if (!awayTagLines.isEmpty()) yc.set("away.tag", new ArrayList<>(awayTagLines));
        if (awayMinutes != null) yc.set("away.minutes", awayMinutes);
        if (!awaySpaces.isEmpty()) yc.set("away.spaces", new ArrayList<>(awaySpaces));
        if (persona != null) yc.set("persona", persona);
        if (job != null) yc.set("job", job);
        if (trait != null) yc.set("trait", trait);
        if (mood != null) yc.set("mood", mood);
        if (sparRegion != null) yc.set("spar.region", sparRegion);
        if (sparStyle != null) yc.set("spar.style", sparStyle);
        if (sparArmor != null) yc.set("spar.armor", sparArmor);
        for (Map.Entry<String, Double> en : sparStats.entrySet()) yc.set("spar." + en.getKey(), en.getValue());
        if (!seats.isEmpty()) {
            List<Object> out = new ArrayList<>();
            for (double[] s : seats) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("x", s[0]); m.put("y", s[1]); m.put("z", s[2]); m.put("yaw", s[3]);
                out.add(m);
            }
            yc.set("lobby.seats", out);
            if (prioritySeat >= 0) yc.set("lobby.priority-seat", prioritySeat);
        }
        if (type == CharacterType.SHOP) {
            if (owner != null) yc.set("shop.owner", owner.toString());
            if (container != null) {
                yc.set("shop.container.x", container[0]);
                yc.set("shop.container.y", container[1]);
                yc.set("shop.container.z", container[2]);
            }
            int ti = 0;
            for (dev.servereer.livingshopkeeper.shop.ShopTrade t : trades) {
                String base = "shop.trades." + (ti++) + ".";
                yc.set(base + "result", t.result());
                if (t.a() != null) yc.set(base + "a", t.a());
                if (t.b() != null) yc.set(base + "b", t.b());
            }
        }
        // home doubles as the post, so it persists for every type that walks back to one
        if (home != null && (type == CharacterType.BARKER || type == CharacterType.LOBBY || type == CharacterType.SHOP)) {
            yc.set("barker.home.x", home[0]); yc.set("barker.home.y", home[1]); yc.set("barker.home.z", home[2]);
            yc.set("barker.home.yaw", home[3]); yc.set("barker.home.pitch", home[4]);
        }
        if (type == CharacterType.BARKER) {
            if (region != null) yc.set("barker.region", region);
            if (inviteCooldownSeconds != null) yc.set("barker.invite-cooldown-seconds", inviteCooldownSeconds);
            if (!waypoints.isEmpty()) {
                List<Object> wp = new ArrayList<>();
                for (double[] w : waypoints) {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("x", w[0]); m.put("y", w[1]); m.put("z", w[2]);
                    wp.add(m);
                }
                yc.set("barker.waypoints", wp);
            }
            for (ActivityPlace p : places.values()) {
                String base = "barker.places." + p.id() + ".";
                yc.set(base + "x", p.x()); yc.set(base + "y", p.y()); yc.set(base + "z", p.z());
                yc.set(base + "yaw", p.yaw()); yc.set(base + "pitch", p.pitch());
                yc.set(base + "radius", p.radius()); yc.set(base + "visit", p.visit());
                if (p.sit()) yc.set(base + "sit", true);
                if (p.visit()) {
                    yc.set(base + "from", ActivityPlace.formatWorldTime(p.fromTick()));
                    yc.set(base + "until", ActivityPlace.formatWorldTime(p.untilTick()));
                }
            }
        }
        yc.save(new File(folder, "character.yml"));
    }

    /** Load a character from its folder ({@code <folder>/character.yml}), or null if unreadable. */
    static Character load(File folder) {
        File f = new File(folder, "character.yml");
        if (!f.isFile()) return null;
        YamlConfiguration yc = YamlConfiguration.loadConfiguration(f);
        return fromConfig(folder.getName(), folder, yc);
    }

    /** Build a Character from a parsed config + its folder + id (folder name). Shared by load + migration. */
    static Character fromConfig(String id, File folder, ConfigurationSection yc) {
        UUID uuid;
        try {
            uuid = UUID.fromString(yc.getString("uuid", UUID.randomUUID().toString()));
        } catch (IllegalArgumentException e) {
            uuid = UUID.randomUUID();
        }
        Character c = new Character(id, folder, uuid,
                CharacterType.from(yc.getString("type")),
                yc.getString("world", "world"),
                yc.getDouble("x"), yc.getDouble("y"), yc.getDouble("z"),
                (float) yc.getDouble("yaw"), (float) yc.getDouble("pitch"),
                yc.getString("name", id));
        c.display = yc.getString("display");
        if (yc.isString("skin")) {
            c.skinName = yc.getString("skin");
        } else {
            c.skinName = yc.getString("skin.name");
        }
        c.textureValue = yc.getString("skin.value");
        c.textureSignature = yc.getString("skin.signature");
        c.greetingClip = yc.getString("voice.greeting", "greeting");
        c.combatReaction = yc.getString("combat.reaction");
        ConfigurationSection aiSec = yc.getConfigurationSection("ai");
        if (aiSec != null) {
            for (String key : aiSec.getKeys(false)) c.ai.put(key, aiSec.getBoolean(key));
        }
        c.tagLines.addAll(yc.getStringList("tag"));
        c.actions.addAll(yc.getStringList("actions"));
        c.awayTagLines.addAll(yc.getStringList("away.tag"));
        if (yc.isSet("away.minutes")) c.awayMinutes = yc.getDouble("away.minutes");
        c.awaySpaces.addAll(yc.getStringList("away.spaces"));
        c.awaySpace = yc.getString("away.space");
        if (c.awaySpaces.isEmpty() && c.awaySpace != null) c.awaySpaces.add(c.awaySpace);   // migrate legacy single
        c.persona = yc.getString("persona");
        c.setJob(yc.getString("job"));
        c.setTrait(yc.getString("trait"));
        c.setMood(yc.getString("mood"));
        c.sparRegion = yc.getString("spar.region");
        c.setSparStyle(yc.getString("spar.style"));
        c.setSparArmor(yc.getString("spar.armor"));
        ConfigurationSection sparSec = yc.getConfigurationSection("spar");
        if (sparSec != null) {
            for (String key : sparSec.getKeys(false)) {
                if (key.equals("region") || key.equals("style") || key.equals("armor")) continue;
                Object v = sparSec.get(key);
                if (v instanceof Number n) c.sparStats.put(key, n.doubleValue());
            }
        }
        for (Object o : yc.getList("lobby.seats", List.of())) {
            if (o instanceof java.util.Map<?, ?> m) {
                c.seats.add(new double[]{ num(m.get("x")), num(m.get("y")), num(m.get("z")), num(m.get("yaw")) });
            }
        }
        c.prioritySeat = yc.getInt("lobby.priority-seat", -1);
        String ownerRaw = yc.getString("shop.owner");
        if (ownerRaw != null) {
            try {
                c.owner = UUID.fromString(ownerRaw);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (yc.isSet("shop.container.x")) {
            c.container = new int[]{ yc.getInt("shop.container.x"), yc.getInt("shop.container.y"), yc.getInt("shop.container.z") };
        }
        ConfigurationSection tradeSec = yc.getConfigurationSection("shop.trades");
        if (tradeSec != null) {
            for (String key : tradeSec.getKeys(false)) {
                ConfigurationSection t = tradeSec.getConfigurationSection(key);
                if (t == null) continue;
                org.bukkit.inventory.ItemStack result = t.getItemStack("result");
                org.bukkit.inventory.ItemStack a = t.getItemStack("a");
                org.bukkit.inventory.ItemStack b = t.getItemStack("b");
                if (result != null && (a != null || b != null)) {
                    c.trades.add(new dev.servereer.livingshopkeeper.shop.ShopTrade(result, a != null ? a : b, a != null ? b : null));
                }
            }
        }
        c.region = yc.getString("barker.region");
        if (yc.isSet("barker.invite-cooldown-seconds")) c.inviteCooldownSeconds = yc.getDouble("barker.invite-cooldown-seconds");
        if (yc.isSet("barker.home.x")) {
            c.home = new double[]{ yc.getDouble("barker.home.x"), yc.getDouble("barker.home.y"),
                    yc.getDouble("barker.home.z"), (float) yc.getDouble("barker.home.yaw"),
                    (float) yc.getDouble("barker.home.pitch") };
        }
        for (Object o : yc.getList("barker.waypoints", List.of())) {
            if (o instanceof java.util.Map<?, ?> m) {
                c.waypoints.add(new double[]{ num(m.get("x")), num(m.get("y")), num(m.get("z")) });
            }
        }
        ConfigurationSection places = yc.getConfigurationSection("barker.places");
        if (places != null) {
            for (String placeId : places.getKeys(false)) {
                ConfigurationSection p = places.getConfigurationSection(placeId);
                if (p == null) continue;
                boolean visit = p.getBoolean("visit", p.getBoolean("sit", false));
                try {
                    c.putPlace(new ActivityPlace(placeId, p.getDouble("x"), p.getDouble("y"), p.getDouble("z"),
                            (float) p.getDouble("yaw"), (float) p.getDouble("pitch"), p.getDouble("radius", 4),
                            visit, p.getBoolean("sit", false), readWorldTime(p, "from", 0),
                            readWorldTime(p, "until", 0)));
                } catch (IllegalArgumentException ignored) {
                    // A malformed place must not stop the rest of the character from loading.
                }
            }
        }
        return c;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    /** {@code null} for missing/blank optional strings, so "clear" really clears the field. */
    private static String blankNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int readWorldTime(ConfigurationSection s, String key, int def) {
        Object raw = s.get(key);
        if (raw == null) return def;
        if (raw instanceof Number n) return Math.floorMod(n.intValue(), 24_000);
        return ActivityPlace.parseWorldTime(String.valueOf(raw));
    }
}
