package dev.servereer.livingshopkeeper.shop;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

/**
 * Soft WorldGuard integration for shops. ARM plots are WorldGuard regions with the buyer as
 * owner/member, so "may place a shop here" = member/owner of a region at the spot, and "a customer is
 * in the shop" = standing inside the shop's region(s). Every method is guarded: when WorldGuard is
 * absent (or anything throws) the answers degrade gracefully and callers fall back to radius checks.
 */
public final class RegionGate {

    private RegionGate() {}

    public static boolean available() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
    }

    /** True if {@code p} owns or is a member of ANY region at {@code loc} (false without WG/regions). */
    public static boolean memberOfRegionAt(Player p, Location loc) {
        if (!available()) return false;
        try {
            ApplicableRegionSet set = regionsAt(loc);
            if (set == null) return false;
            var local = WorldGuardPlugin.inst().wrapPlayer(p);
            for (ProtectedRegion r : set) {
                if (r.getOwners().contains(local) || r.getMembers().contains(local)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True if {@code p} OWNS a region at {@code loc} — the ARM buyer/renter, not merely a trusted
     * member. Checks WG region owners (ARM sets the buyer as owner on default configs) and then asks
     * AdvancedRegionMarket itself via reflection, so non-default ARM ownership models still pass.
     */
    public static boolean ownerOfRegionAt(Player p, Location loc) {
        if (available()) {
            try {
                ApplicableRegionSet set = regionsAt(loc);
                if (set != null) {
                    var local = WorldGuardPlugin.inst().wrapPlayer(p);
                    for (ProtectedRegion r : set) {
                        if (r.getOwners().contains(local)) return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return armOwnsAt(p, loc);
    }

    /** Best-effort ARM ownership query (reflection; any mismatch with the installed ARM = false). */
    private static boolean armOwnsAt(Player p, Location loc) {
        org.bukkit.plugin.Plugin arm = Bukkit.getPluginManager().getPlugin("AdvancedRegionMarket");
        if (arm == null || !arm.isEnabled()) return false;
        try {
            Object regionManager = arm.getClass().getMethod("getRegionManager").invoke(arm);
            Object region = regionManager.getClass().getMethod("getRegionByLocation", Location.class)
                    .invoke(regionManager, loc);
            if (region == null) return false;
            Object wgRegion = region.getClass().getMethod("getRegion").invoke(region);
            Object owns = wgRegion.getClass().getMethod("hasOwner", java.util.UUID.class)
                    .invoke(wgRegion, p.getUniqueId());
            return owns instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ids of the regions covering {@code loc}; empty without WG or when unregioned. */
    public static Set<String> regionIdsAt(Location loc) {
        Set<String> out = new HashSet<>();
        if (!available()) return out;
        try {
            ApplicableRegionSet set = regionsAt(loc);
            if (set != null) for (ProtectedRegion r : set) out.add(r.getId());
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** True if {@code loc} lies inside any of the named regions of {@code w}. */
    public static boolean inside(World w, Location loc, Set<String> regionIds) {
        if (!available() || regionIds.isEmpty()) return false;
        try {
            RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w));
            if (rm == null) return false;
            BlockVector3 v = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            for (String id : regionIds) {
                ProtectedRegion r = rm.getRegion(id);
                if (r != null && r.contains(v)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ApplicableRegionSet regionsAt(Location loc) {
        RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(loc.getWorld()));
        return rm == null ? null : rm.getApplicableRegions(
                BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
    }
}
