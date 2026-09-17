package dev.servereer.livingshopkeeper.shop;

import dev.servereer.livingshopkeeper.npc.Character;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Purchase mechanics for SHOP characters. A purchase is fully pre-checked (buyer can pay, buyer has
 * room, chest has stock, chest has room for the payment) before anything moves, then executed in one
 * main-thread pass — there is no partial state to glitch. Player-owned shops move stock/payment
 * through their linked chest; admin shops (no owner) trade infinitely from nothing.
 */
public final class ShopEngine {

    /** The shop's stock inventory, or null when it has no (reachable) container. */
    public Inventory stockOf(Character c) {
        int[] at = c.container();
        if (at == null) return null;
        World w = Bukkit.getWorld(c.getWorld());
        if (w == null || !w.isChunkLoaded(at[0] >> 4, at[2] >> 4)) return null;
        BlockState state = w.getBlockAt(at[0], at[1], at[2]).getState();
        return state instanceof Container container ? container.getInventory() : null;
    }

    /** How many times this trade can still be bought from stock (∞-ish for admin shops). */
    public int stockUses(Character c, ShopTrade t) {
        if (c.owner() == null) return Integer.MAX_VALUE;
        Inventory stock = stockOf(c);
        if (stock == null) return 0;
        return countMatching(stock, t.result()) / Math.max(1, t.result().getAmount());
    }

    /**
     * Buy one unit of {@code t} from {@code c} for {@code p}. Returns null on success, or the message
     * explaining why nothing happened (and nothing DID happen — all checks run before any item moves).
     */
    public String purchase(Player p, Character c, ShopTrade t) {
        List<ItemStack> costs = new ArrayList<>(2);
        costs.add(t.a());
        if (t.b() != null) costs.add(t.b());
        boolean playerShop = c.owner() != null;
        Inventory stock = playerShop ? stockOf(c) : null;

        if (playerShop && stock == null) return "This shop's chest is missing — tell the owner!";
        if (playerShop && countMatching(stock, t.result()) < t.result().getAmount()) return "Out of stock!";
        for (ItemStack cost : costs) {
            if (countMatching(p.getInventory(), cost) < cost.getAmount()) return "You need " + describe(cost) + " to buy this.";
        }
        if (!hasRoomFor(p.getInventory(), costs, List.of(t.result()))) return "Your inventory is full!";
        if (playerShop && !hasRoomFor(stock, List.of(t.result()), costs)) return "The shop's chest is full — tell the owner!";

        for (ItemStack cost : costs) removeMatching(p.getInventory(), cost);
        if (playerShop) removeMatching(stock, t.result());
        p.getInventory().addItem(t.result());
        if (playerShop) {
            for (ItemStack cost : costs) stock.addItem(cost.clone());
        }
        return null;
    }

    // --- inventory math (storage slots only: no armor/offhand on players) ---

    static int countMatching(Inventory inv, ItemStack item) {
        int total = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s != null && s.isSimilar(item)) total += s.getAmount();
        }
        return total;
    }

    /** Remove {@code item.getAmount()} matching items, all-or-nothing. */
    static boolean removeMatching(Inventory inv, ItemStack item) {
        if (countMatching(inv, item) < item.getAmount()) return false;
        int remaining = item.getAmount();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || !s.isSimilar(item)) continue;
            int take = Math.min(remaining, s.getAmount());
            remaining -= take;
            if (take >= s.getAmount()) contents[i] = null;
            else s.setAmount(s.getAmount() - take);
        }
        inv.setStorageContents(contents);
        return true;
    }

    /** Can {@code incoming} fit once {@code leaving} has left? Simulated on copies, no side effects. */
    static boolean hasRoomFor(Inventory inv, List<ItemStack> leaving, List<ItemStack> incoming) {
        ItemStack[] real = inv.getStorageContents();
        ItemStack[] sim = new ItemStack[real.length];
        for (int i = 0; i < real.length; i++) sim[i] = real[i] == null ? null : real[i].clone();
        for (ItemStack out : leaving) {
            if (out == null) continue;
            int leave = out.getAmount();
            for (int i = 0; i < sim.length && leave > 0; i++) {
                if (sim[i] == null || !sim[i].isSimilar(out)) continue;
                int take = Math.min(leave, sim[i].getAmount());
                leave -= take;
                if (take >= sim[i].getAmount()) sim[i] = null;
                else sim[i].setAmount(sim[i].getAmount() - take);
            }
        }
        for (ItemStack in : incoming) {
            if (in == null || in.getType().isAir()) continue;
            int need = in.getAmount();
            for (int i = 0; i < sim.length && need > 0; i++) {   // top up similar stacks
                if (sim[i] != null && sim[i].isSimilar(in) && sim[i].getAmount() < sim[i].getMaxStackSize()) {
                    int fit = Math.min(need, sim[i].getMaxStackSize() - sim[i].getAmount());
                    sim[i].setAmount(sim[i].getAmount() + fit);
                    need -= fit;
                }
            }
            for (int i = 0; i < sim.length && need > 0; i++) {   // then empty slots
                if (sim[i] == null) {
                    int fit = Math.min(need, in.getMaxStackSize());
                    sim[i] = in.clone();
                    sim[i].setAmount(fit);
                    need -= fit;
                }
            }
            if (need > 0) return false;
        }
        return true;
    }

    // --- naming helpers (shared with the GUIs) ---

    public static String describe(ItemStack item) {
        if (item == null) return "nothing";
        String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName())
                : prettify(item.getType());
        return item.getAmount() + "× " + name;
    }

    static String prettify(Material m) {
        String[] parts = m.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(java.lang.Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
