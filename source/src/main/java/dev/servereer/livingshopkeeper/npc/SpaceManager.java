package dev.servereer.livingshopkeeper.npc;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Registry of shared hangout {@link Space}s ({@code plugins/LivingShopkeeper/spaces.yml}) plus the
 * runtime seat ledger: which character currently claims which seat, so two visitors never sit on the
 * same stair. Claims are volatile — a reload clears them and visitors simply re-pick.
 */
public final class SpaceManager {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Space> byId = new LinkedHashMap<>();
    private final Map<String, Map<Integer, UUID>> claims = new LinkedHashMap<>();   // space → seat index → npc

    public SpaceManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spaces.yml");
        load();
    }

    public void load() {
        byId.clear();
        claims.clear();
        if (!file.isFile()) return;
        YamlConfiguration yc = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yc.getConfigurationSection("spaces");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Space sp = new Space(id, s.getString("world", "world"),
                    s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"));
            sp.setArena(s.getBoolean("arena", false));
            for (Object o : s.getList("seats", List.of())) {
                if (o instanceof Map<?, ?> m) {
                    sp.addSeat(num(m.get("x")), num(m.get("y")), num(m.get("z")), (float) num(m.get("yaw")));
                }
            }
            byId.put(sp.id(), sp);
        }
    }

    public void save() {
        YamlConfiguration yc = new YamlConfiguration();
        for (Space sp : byId.values()) {
            String base = "spaces." + sp.id() + ".";
            yc.set(base + "world", sp.world());
            yc.set(base + "x", sp.x()); yc.set(base + "y", sp.y()); yc.set(base + "z", sp.z());
            yc.set(base + "yaw", sp.yaw());
            if (sp.arena()) yc.set(base + "arena", true);
            List<Object> out = new ArrayList<>();
            for (double[] s : sp.seats()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("x", s[0]); m.put("y", s[1]); m.put("z", s[2]); m.put("yaw", s[3]);
                out.add(m);
            }
            yc.set(base + "seats", out);
        }
        try {
            yc.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save spaces.yml: " + e.getMessage());
        }
    }

    public Space get(String id) { return id == null ? null : byId.get(Space.cleanId(id)); }
    public Collection<Space> all() { return byId.values(); }
    public boolean exists(String id) { return get(id) != null; }

    public Space create(String id, Location at) {
        String key = Space.cleanId(id);
        if (byId.containsKey(key)) return null;
        Space sp = new Space(key, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw());
        byId.put(key, sp);
        save();
        return sp;
    }

    public boolean remove(String id) {
        Space sp = byId.remove(Space.cleanId(id));
        if (sp == null) return false;
        claims.remove(sp.id());
        save();
        return true;
    }

    /** A random space in {@code world} with any capacity, or null. "any" picks from all in the world. */
    public Space pick(String idOrAny, String world) {
        if (idOrAny != null && !idOrAny.equalsIgnoreCase("any")) {
            Space sp = get(idOrAny);
            return sp != null && sp.world().equals(world) ? sp : null;
        }
        List<Space> inWorld = new ArrayList<>();
        for (Space sp : byId.values()) if (sp.world().equals(world)) inWorld.add(sp);
        if (inWorld.isEmpty()) return null;
        return inWorld.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(inWorld.size()));
    }

    /** A random arena-flagged space in {@code world}, or null when none exists. */
    public Space pickArena(String world) {
        List<Space> arenas = new ArrayList<>();
        for (Space sp : byId.values()) if (sp.arena() && sp.world().equals(world)) arenas.add(sp);
        if (arenas.isEmpty()) return null;
        return arenas.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(arenas.size()));
    }

    // --- runtime seat claims -----------------------------------------------

    /**
     * Claim a free seat for {@code npc}; returns its {x,y,z,yaw} or null when the space has no free seat.
     * Any seat this NPC already held (here or elsewhere) is released first — one NPC, one chair.
     */
    public double[] claimSeat(Space sp, UUID npc) {
        releaseSeats(npc);
        Map<Integer, UUID> taken = claims.computeIfAbsent(sp.id(), k -> new LinkedHashMap<>());
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < sp.seats().size(); i++) if (!taken.containsKey(i)) free.add(i);
        if (free.isEmpty()) return null;
        int idx = free.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(free.size()));
        taken.put(idx, npc);
        return sp.seats().get(idx);
    }

    /** Like {@link #claimSeat} but prefers the free seat nearest {@code (x,z)} — companions sit together. */
    public double[] claimSeatNear(Space sp, UUID npc, double x, double z) {
        releaseSeats(npc);
        Map<Integer, UUID> taken = claims.computeIfAbsent(sp.id(), k -> new LinkedHashMap<>());
        int best = -1;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < sp.seats().size(); i++) {
            if (taken.containsKey(i)) continue;
            double[] s = sp.seats().get(i);
            double dx = s[0] - x, dz = s[2] - z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestSq) { bestSq = d2; best = i; }
        }
        if (best < 0) return null;
        taken.put(best, npc);
        return sp.seats().get(best);
    }

    /** Release every seat {@code npc} holds anywhere (outing over, reload, removal). */
    public void releaseSeats(UUID npc) {
        for (Map<Integer, UUID> taken : claims.values()) taken.values().removeIf(npc::equals);
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    public String describe(Space sp) {
        return sp.id() + " @ " + sp.world() + " " + (int) sp.x() + "," + (int) sp.y() + "," + (int) sp.z()
                + " (" + sp.seats().size() + " seat" + (sp.seats().size() == 1 ? "" : "s") + ")"
                + (sp.arena() ? " [ARENA]" : "");
    }

    /** Ids for tab completion. */
    public List<String> ids(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String id : byId.keySet()) if (id.startsWith(p)) out.add(id);
        return out;
    }
}
