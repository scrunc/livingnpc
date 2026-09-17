package dev.servereer.livingshopkeeper.shop;

import org.bukkit.inventory.ItemStack;

/**
 * One authored trade: up to two ingredient stacks in, one result stack out — exactly a vanilla
 * merchant recipe. Stacks are stored as owned copies (defensive-cloned on the way in and out) so a
 * trade can never alias an inventory's live ItemStack.
 */
public record ShopTrade(ItemStack result, ItemStack a, ItemStack b) {

    public ShopTrade {
        if (result == null || a == null) throw new IllegalArgumentException("trade needs a result and a first cost");
        result = result.clone();
        a = a.clone();
        b = b == null || b.getType().isAir() ? null : b.clone();
    }

    public ItemStack result() { return result.clone(); }
    public ItemStack a() { return a.clone(); }
    public ItemStack b() { return b == null ? null : b.clone(); }
}
