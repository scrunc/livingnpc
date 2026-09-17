package dev.servereer.livingshopkeeper.shop;

import dev.servereer.livingshopkeeper.npc.Character;
import dev.servereer.livingshopkeeper.npc.CharacterManager;
import dev.servereer.livingshopkeeper.npc.SeatWandListener;
import dev.servereer.livingshopkeeper.npc.Space;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The GUI suite for SHOP characters, Shopkeepers-style:
 * <ul>
 *   <li><b>Customers</b> get the real vanilla villager trading window (a virtual {@link Merchant}) —
 *       every trade in the scrollable list, out-of-stock trades greyed out. Stock movement for
 *       player shops happens in {@link #onPurchase} against the linked chest.</li>
 *   <li><b>Owners</b> get a Shopkeepers-style trade editor: each column is one trade — top row the
 *       item sold, the two rows below its costs — with pages for up to
 *       {@code shop.max-trade-pages}×9 trades, plus a main menu and an AI behavior toggle menu.</li>
 * </ul>
 *
 * <p><b>Dupe-proofing:</b> every {@code InventoryClickEvent} and {@code InventoryDragEvent} touching a
 * view with our holder is cancelled outright, then re-interpreted as a button press. Items are never
 * moved, only <i>copied as templates</i> (click an item in your own inventory → a copy appears in the
 * editor). Nothing real ever enters the top inventory, so there is nothing to steal, rake with a
 * double-click, hotbar-swap, or drop. The merchant window is vanilla logic on the player's own items.
 */
public final class ShopGuis implements Listener {

    private static final int MAIN_OPEN = 10, MAIN_TRADES = 11, MAIN_DIALOGUE = 12, MAIN_BEHAVIOR = 13,
            MAIN_OUTINGS = 14, MAIN_SEATS = 15, MAIN_INFO = 16,
            MAIN_MOVE = 19, MAIN_CHEST = 20, MAIN_NAMETAG = 21, MAIN_RENAME = 22, MAIN_SKIN = 23, MAIN_DELETE = 8;
    private static final int ED_PREV = 45, ED_BACK = 47, ED_INFO = 49, ED_NEXT = 53;
    private static final int BEH_ROAM = 10, BEH_SIT = 12, BEH_GOSSIP = 14, BEH_HAWK = 16, BEH_BACK = 22;
    private static final int OUT_TOGGLE = 4, OUT_BACK = 49, OUT_SPACES_FROM = 9;   // spaces fill slots 9..44
    private static final int LIST_ADD = 48, LIST_BACK = 50;                        // line-list editor footer
    private static final String[][] BEHAVIORS = {   // {key, default(“t”/“f”), title, description…}
            {"roam", "f", "Roaming", "Wanders a little around its post", "between customers (off by default)."},
            {"sit", "t", "Sitting", "Sometimes sits on its wand-marked", "seats while idle."},
            {"gossip", "t", "Gossip", "Wanders over to a nearby fellow", "shopkeeper for a chat."},
            {"hawk", "f", "Hawk wares", "Calls out a random trade to", "passers-by, market-crier style."},
    };
    /** Owner-editable dialogue categories: {trigger, slot, title, description…}. */
    private static final Object[][] DIALOGUE_CATS = {
            {"interact", 10, "Greeting", "Said when a customer clicks the shop."},
            {"thanks", 12, "Thanks for buying", "Said right after a customer buys something."},
            {"hawk", 14, "Call to passers-by", "Shouted out — needs the Hawk behavior on."},
            {"hit", 16, "When hit", "Grumbled if someone punches the shopkeeper."},
    };

    private enum View { MAIN, EDITOR, BEHAVIOR, CUSTOMER, DIALOGUE, OUTINGS, LIST }

    private final JavaPlugin plugin;
    private final CharacterManager manager;
    private final ShopEngine engine;
    private final SeatWandListener seatWand;
    private final ShopSettings settings;

    /** player → their open vanilla-merchant session (which shop, and its trade list at open time). */
    private final Map<UUID, MerchantSession> merchants = new HashMap<>();

    public ShopGuis(JavaPlugin plugin, CharacterManager manager, ShopEngine engine,
                    SeatWandListener seatWand, ShopSettings settings) {
        this.plugin = plugin;
        this.manager = manager;
        this.engine = engine;
        this.seatWand = seatWand;
        this.settings = settings;
    }

    private final class Holder implements InventoryHolder {
        Inventory inv;
        String shopId;
        View view;
        int page;                            // editor page (9 trades per page)
        final ItemStack[] col = new ItemStack[27];   // editor templates: 0-8 result, 9-17 cost1, 18-26 cost2
        boolean warnedCostless;              // one nag per editor visit about result-without-cost columns
        long deleteArmedAt;                  // two-click delete confirmation
        String listKey;                      // LIST view: "tag" or a dialogue trigger (interact/thanks/…)
        final Map<Integer, String> spaceSlot = new HashMap<>();   // OUTINGS view: slot → space id

        @Override
        public Inventory getInventory() { return inv; }

        Character shop() { return manager.get(shopId); }
    }

    private record MerchantSession(String shopId, List<ShopTrade> trades) {
        ShopTrade match(MerchantRecipe r) {
            for (ShopTrade t : trades) {
                if (!similar(r.getResult(), t.result())) continue;
                List<ItemStack> ing = r.getIngredients();
                ItemStack first = !ing.isEmpty() ? ing.get(0) : null;
                ItemStack second = ing.size() > 1 && ing.get(1) != null && !ing.get(1).getType().isAir()
                        ? ing.get(1) : null;
                if (first == null || !similar(first, t.a())) continue;
                if ((second == null) != (t.b() == null)) continue;
                if (second != null && !similar(second, t.b())) continue;
                return t;
            }
            return null;
        }

        private static boolean similar(ItemStack x, ItemStack y) {
            return x != null && y != null && x.isSimilar(y) && x.getAmount() == y.getAmount();
        }
    }

    // --- opening views ------------------------------------------------------

    public void openMain(Player p, Character c) {
        Holder h = new Holder();
        h.shopId = c.getId();
        h.view = View.MAIN;
        h.inv = Bukkit.createInventory(h, 27, Component.text("Shop — " + plainName(c), NamedTextColor.DARK_GREEN));
        renderMain(h, c);
        p.openInventory(h.inv);
    }

    /** What a customer sees: the vanilla villager trade window (or the legacy grid via config). */
    public void openCustomer(Player p, Character c) {
        if ("grid".equalsIgnoreCase(plugin.getConfig().getString("shop.customer-gui", "villager"))) {
            openGrid(p, c);
            return;
        }
        List<ShopTrade> trades = List.copyOf(c.trades());
        if (trades.isEmpty()) {
            p.sendMessage(Component.text("This shop has nothing for sale yet.", NamedTextColor.GRAY));
            return;
        }
        Merchant m = Bukkit.createMerchant(dev.servereer.livingshopkeeper.dialog.DialogText.mini(c.display()));
        List<MerchantRecipe> recipes = new ArrayList<>(trades.size());
        for (ShopTrade t : trades) {
            int uses = Math.min(999_999, engine.stockUses(c, t));
            MerchantRecipe r = new MerchantRecipe(t.result().clone(), 0, Math.max(0, uses), false);
            r.addIngredient(t.a().clone());
            if (t.b() != null) r.addIngredient(t.b().clone());
            recipes.add(r);
        }
        m.setRecipes(recipes);
        merchants.put(p.getUniqueId(), new MerchantSession(c.getId(), trades));
        p.openMerchant(m, true);
    }

    private void openEditor(Player p, Holder from, int page) {
        Character c = from.shop();
        if (c == null) { p.closeInventory(); return; }
        Holder h = new Holder();
        h.shopId = c.getId();
        h.view = View.EDITOR;
        h.page = Math.max(0, Math.min(page, pages() - 1));
        loadEditor(h, c);
        h.inv = Bukkit.createInventory(h, 54, Component.text("Trades — " + plainName(c)
                + "  (page " + (h.page + 1) + "/" + pages() + ")", NamedTextColor.DARK_GREEN));
        renderEditor(h, c);
        p.openInventory(h.inv);
    }

    private void openBehavior(Player p, Holder from) {
        Character c = from.shop();
        if (c == null) { p.closeInventory(); return; }
        Holder h = new Holder();
        h.shopId = c.getId();
        h.view = View.BEHAVIOR;
        h.inv = Bukkit.createInventory(h, 27, Component.text("Behavior — " + plainName(c), NamedTextColor.DARK_GREEN));
        renderBehavior(h, c);
        p.openInventory(h.inv);
    }

    /** Open the trade editor straight away (used by the owner's sneak-right-click shortcut). */
    public void openTrades(Player p, Character c) {
        Holder from = new Holder();
        from.shopId = c.getId();
        openEditor(p, from, 0);
    }

    private void openDialogue(Player p, Character c) {
        Holder h = new Holder();
        h.shopId = c.getId();
        h.view = View.DIALOGUE;
        h.inv = Bukkit.createInventory(h, 27, Component.text("Dialogue — " + plainName(c), NamedTextColor.DARK_GREEN));
        renderDialogue(h, c);
        p.openInventory(h.inv);
    }

    private void openOutings(Player p, Character c) {
        Holder h = new Holder();
        h.shopId = c.getId();
        h.view = View.OUTINGS;
        h.inv = Bukkit.createInventory(h, 54, Component.text("Outings — " + plainName(c), NamedTextColor.DARK_GREEN));
        renderOutings(h, c);
        p.openInventory(h.inv);
    }

    /** Open the line-list editor for {@code key} ("tag" or a dialogue trigger). Public so chat-add can reopen it. */
    public void openLineList(Player p, Character c, String key) {
        if (!"tag".equals(key)) manager.seedOwnerDialogIfNeeded(c, key);   // first open → show the real lines
        Holder h = new Holder();
        h.shopId = c.getId();
        h.view = View.LIST;
        h.listKey = key;
        h.inv = Bukkit.createInventory(h, 54, Component.text(listTitle(key) + " — " + plainName(c),
                NamedTextColor.DARK_GREEN));
        renderList(h, c);
        p.openInventory(h.inv);
    }

    private int pages() {
        return Math.max(1, plugin.getConfig().getInt("shop.max-trade-pages", 10));
    }

    /** Max trades a shop can hold (editor pages × 9 columns). */
    public int capacity() {
        return pages() * 9;
    }

    // --- rendering ----------------------------------------------------------

    private void renderMain(Holder h, Character c) {
        fill(h.inv, pane(Material.GRAY_STAINED_GLASS_PANE));
        h.inv.setItem(MAIN_OPEN, button(Material.EMERALD, "<green><b>Open shop</b></green>",
                "Trade with your own shop", "exactly like a customer sees it."));
        h.inv.setItem(MAIN_TRADES, button(Material.WRITABLE_BOOK, "<yellow><b>Edit trades</b></yellow>",
                "Each column is one trade:", "top = item sold, below = its costs.",
                c.trades().size() + " of " + capacity() + " trades set."));
        h.inv.setItem(MAIN_DIALOGUE, button(Material.OAK_SIGN, "<gold><b>Dialogue</b></gold>",
                "Edit what your shopkeeper says:", "greetings, thanks, calls, and more.",
                "Colors with & codes or &#hex work."));
        h.inv.setItem(MAIN_BEHAVIOR, button(Material.LEAD, "<aqua><b>Behavior</b></aqua>",
                "Toggle what your shopkeeper does", "while idle: roam, sit, gossip,", "hawk wares at passers-by."));
        h.inv.setItem(MAIN_OUTINGS, button(Material.CLOCK, "<light_purple><b>Outings</b></light_purple>",
                "Let it wander off to hangout spaces", "(off by default). Choose which",
                "spaces it's allowed to visit."));
        h.inv.setItem(MAIN_SEATS, button(Material.GOLDEN_SWORD, "<gold><b>Seat mode</b></gold>",
                "Get the seat wand, then right-click",
                "slabs/stairs to mark sit spots (" + c.seats().size() + " set).",
                "Click a marked seat again = PRIORITY seat",
                "(it sits there for customers). Sneak-click removes."));
        List<String> info = new ArrayList<>();
        info.add((c.owner() != null ? "Your shop" : "Admin shop (infinite stock)"));
        info.add("Trades: " + c.trades().size());
        if (c.container() != null) {
            int[] at = c.container();
            info.add("Stock chest: " + at[0] + ", " + at[1] + ", " + at[2]);
            info.add("Keep it stocked — sold items leave it,");
            info.add("payments arrive into it.");
        }
        h.inv.setItem(MAIN_INFO, button(Material.PAPER, "<aqua><b>How it works</b></aqua>", info.toArray(new String[0])));
        h.inv.setItem(MAIN_MOVE, button(Material.ENDER_PEARL, "<light_purple><b>Move shopkeeper here</b></light_purple>",
                "Stand where it should stand,", "then click this."));
        if (c.owner() != null) {
            h.inv.setItem(MAIN_CHEST, button(Material.CHEST, "<yellow><b>Move stock chest</b></yellow>",
                    "Then right-click the NEW chest", "to link it."));
        }
        h.inv.setItem(MAIN_NAMETAG, button(Material.OAK_HANGING_SIGN, "<yellow><b>Nametag</b></yellow>",
                "Floating text above the shopkeeper", "(none by default). Add/remove lines;",
                c.hasTag() ? c.tagLines().size() + " line(s) set." : "no lines set."));
        h.inv.setItem(MAIN_RENAME, button(Material.NAME_TAG, "<yellow><b>Rename</b></yellow>",
                "Type the new name in chat", "(& / &#hex / MiniMessage colors)."));
        h.inv.setItem(MAIN_SKIN, button(Material.PLAYER_HEAD, "<yellow><b>Change skin</b></yellow>",
                "Left-click: type a player name in chat.", "Right-click: use YOUR skin."));
        h.inv.setItem(MAIN_DELETE, button(Material.BARRIER, "<red><b>Delete shop</b></red>",
                "Click TWICE to remove this", "shopkeeper. Chest items stay put."));
    }

    /** Pull this page's trades into the 27 template slots. */
    private void loadEditor(Holder h, Character c) {
        java.util.Arrays.fill(h.col, null);
        List<ShopTrade> trades = c.trades();
        for (int i = 0; i < 9; i++) {
            int ti = h.page * 9 + i;
            if (ti >= trades.size()) break;
            ShopTrade t = trades.get(ti);
            h.col[i] = t.result().clone();
            h.col[i + 9] = t.a().clone();
            h.col[i + 18] = t.b() != null ? t.b().clone() : null;
        }
    }

    private void renderEditor(Holder h, Character c) {
        fill(h.inv, null);
        for (int i = 0; i < 27; i++) {
            h.inv.setItem(i, h.col[i] != null ? withEditLore(h.col[i], i) : emptyTemplate(i));
        }
        for (int i = 27; i < 54; i++) h.inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE));
        if (h.page > 0) h.inv.setItem(ED_PREV, button(Material.ARROW, "<white>◀ Page " + h.page,
                "Saves this page first."));
        if (h.page < pages() - 1) h.inv.setItem(ED_NEXT, button(Material.ARROW, "<white>Page " + (h.page + 2) + " ▶",
                "Saves this page first."));
        h.inv.setItem(ED_BACK, button(Material.OAK_DOOR, "<white>Save & back", "To the shop menu."));
        h.inv.setItem(ED_INFO, button(Material.BOOK, "<aqua><b>How trades work</b></aqua>",
                "Each COLUMN is one trade.",
                "Top slot: the item customers get.",
                "Two below: what they pay (2nd optional).",
                "",
                "Pick an item up from your inventory and",
                "click a slot — a COPY is used, yours stays.",
                "Shift-click an item = start a new trade.",
                "Left-click: +1 · Right-click: −1 · Shift: ±16",
                "Clear a top item to delete its trade.",
                "Saved when you leave or turn the page."));
    }

    private void renderBehavior(Holder h, Character c) {
        fill(h.inv, pane(Material.GRAY_STAINED_GLASS_PANE));
        int[] slots = {BEH_ROAM, BEH_SIT, BEH_GOSSIP, BEH_HAWK};
        for (int i = 0; i < BEHAVIORS.length; i++) {
            String[] b = BEHAVIORS[i];
            boolean on = c.ai(b[0], "t".equals(b[1]));
            List<String> lore = new ArrayList<>(List.of(b).subList(3, b.length));
            lore.add(on ? "Currently ON — click to turn off." : "Currently OFF — click to turn on.");
            h.inv.setItem(slots[i], button(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    (on ? "<green><b>" : "<gray><b>") + b[2] + "</b> " + (on ? "<green>✔" : "<dark_gray>✘"),
                    lore.toArray(new String[0])));
        }
        h.inv.setItem(BEH_BACK, button(Material.OAK_DOOR, "<white>Back", "To the shop menu."));
    }

    private void renderDialogue(Holder h, Character c) {
        fill(h.inv, pane(Material.GRAY_STAINED_GLASS_PANE));
        for (Object[] cat : DIALOGUE_CATS) {
            String trigger = (String) cat[0];
            int count = manager.ownerDialogLines(c, trigger).size();
            List<String> lore = new ArrayList<>();
            lore.add((String) cat[3]);
            lore.add("");
            lore.add(count == 0 ? "No lines yet." : count + " line" + (count == 1 ? "" : "s") + " set.");
            lore.add("<yellow>Click to edit.");
            h.inv.setItem((Integer) cat[1], button(Material.WRITABLE_BOOK,
                    "<gold><b>" + cat[2] + "</b></gold>", lore.toArray(new String[0])));
        }
        h.inv.setItem(22, button(Material.OAK_DOOR, "<white>Back", "To the shop menu."));
    }

    private void renderOutings(Holder h, Character c) {
        fill(h.inv, pane(Material.GRAY_STAINED_GLASS_PANE));
        boolean on = manager.outingsOn(c);
        h.inv.setItem(OUT_TOGGLE, button(on ? Material.LIME_DYE : Material.GRAY_DYE,
                on ? "<green><b>Outings: ON</b>" : "<gray><b>Outings: OFF</b>",
                on ? "It may wander off to a hangout" : "It stays at its post.",
                on ? "space between customers." : "",
                on ? "Click to turn OFF." : "Click to turn ON."));

        h.spaceSlot.clear();
        List<Space> inWorld = new ArrayList<>();
        for (Space sp : manager.spaces().all()) if (sp.world().equals(c.getWorld())) inWorld.add(sp);
        List<String> allow = c.awaySpaces();
        if (inWorld.isEmpty()) {
            h.inv.setItem(22, button(Material.BARRIER, "<red>No hangout spaces here",
                    "An admin creates one with", "/lsk space create <id>."));
        } else {
            boolean anyMode = allow.isEmpty();
            int slot = OUT_SPACES_FROM;
            for (Space sp : inWorld) {
                if (slot > 44) break;
                boolean ticked = allow.contains(sp.id());
                boolean shownOn = ticked || anyMode;
                List<String> lore = new ArrayList<>();
                lore.add(sp.seats().size() + " seat" + (sp.seats().size() == 1 ? "" : "s")
                        + (sp.arena() ? " · ARENA" : ""));
                lore.add("");
                if (anyMode) lore.add("Allowed (no filter = every space).");
                else lore.add(ticked ? "<green>Allowed — click to disallow." : "<red>Not allowed — click to allow.");
                h.inv.setItem(slot, button(shownOn ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                        (shownOn ? "<green><b>" : "<red><b>") + sp.id() + "</b>", lore.toArray(new String[0])));
                h.spaceSlot.put(slot, sp.id());
                slot++;
            }
        }
        h.inv.setItem(OUT_BACK, button(Material.OAK_DOOR, "<white>Back", "To the shop menu."));
    }

    private void renderList(Holder h, Character c) {
        fill(h.inv, null);
        List<String> lines = listLines(c, h.listKey);
        for (int i = 0; i < lines.size() && i < 45; i++) {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta m = it.getItemMeta();
            String raw = lines.get(i);
            m.displayName(loreLine(raw));
            List<Component> lore = new ArrayList<>();
            // Show the raw form only when it carries colour/format codes — otherwise it just repeats the name.
            if (raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0 || raw.indexOf('<') >= 0) {
                lore.add(Component.text(raw, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }
            lore.add(loreLine("<red>Click to remove."));
            m.lore(lore);
            it.setItemMeta(m);
            h.inv.setItem(i, it);
        }
        for (int i = 45; i < 54; i++) h.inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE));
        h.inv.setItem(LIST_ADD, button(Material.WRITABLE_BOOK, "<green><b>Add a line</b>",
                "Type it in chat.", "& / &#hex / MiniMessage colors,", "and <player> <npc> <time> work."));
        h.inv.setItem(LIST_BACK, button(Material.OAK_DOOR, "<white>Back", listBackHint(h.listKey)));
    }

    private List<String> listLines(Character c, String key) {
        return "tag".equals(key) ? new ArrayList<>(c.tagLines()) : manager.ownerDialogLines(c, key);
    }

    private void saveListLines(Character c, String key, List<String> lines) {
        if ("tag".equals(key)) manager.setTagLines(c, lines);
        else manager.setOwnerDialogLines(c, key, lines);
    }

    private static String listTitle(String key) {
        if ("tag".equals(key)) return "Nametag";
        for (Object[] cat : DIALOGUE_CATS) if (cat[0].equals(key)) return (String) cat[2];
        return "Lines";
    }

    private static String listBackHint(String key) {
        return "tag".equals(key) ? "To the shop menu." : "To the dialogue menu.";
    }

    /** Append a chat-entered line to the list {@code key} for {@code c}, then reopen the editor. */
    public void addLine(Player p, Character c, String key, String text) {
        // Security: strip command/URL clicks + insertions from owner-typed text; cap length and count so a
        // shop can't be used to inject actions or spam giant holograms.
        String clean = dev.servereer.livingshopkeeper.dialog.DialogText.sanitizeUserInput(text).trim();
        if (clean.length() > 200) clean = clean.substring(0, 200);
        if (clean.isEmpty()) { openLineList(p, c, key); return; }
        List<String> lines = listLines(c, key);
        int cap = "tag".equals(key) ? 6 : 15;
        if (lines.size() >= cap) {
            p.sendMessage(Component.text("This list is full (max " + cap + " lines).", NamedTextColor.YELLOW));
            openLineList(p, c, key);
            return;
        }
        lines.add(clean);
        saveListLines(c, key, lines);
        openLineList(p, c, key);
    }

    /** The legacy customer grid (kept behind {@code shop.customer-gui: grid}). */
    private void openGrid(Player p, Character c) {
        Holder h = new Holder();
        h.shopId = c.getId();
        h.view = View.CUSTOMER;
        h.inv = Bukkit.createInventory(h, 54, Component.text(plainName(c), NamedTextColor.DARK_GREEN));
        renderGrid(h, c);
        p.openInventory(h.inv);
    }

    private void renderGrid(Holder h, Character c) {
        fill(h.inv, null);
        List<ShopTrade> trades = c.trades();
        for (int i = 0; i < trades.size() && i < 45; i++) {
            ShopTrade t = trades.get(i);
            int uses = engine.stockUses(c, t);
            ItemStack icon = t.result();
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(loreLine("<dark_gray>—"));
            lore.add(loreLine("<gray>Price: <white>" + ShopEngine.describe(t.a())
                    + (t.b() != null ? " <gray>+ <white>" + ShopEngine.describe(t.b()) : "")));
            if (uses <= 0) {
                lore.add(loreLine("<red><b>OUT OF STOCK</b>"));
            } else {
                if (c.owner() != null) lore.add(loreLine("<gray>In stock: <white>" + uses));
                lore.add(loreLine("<yellow>Click: <gray>buy 1 · <yellow>Shift-click: <gray>buy 8"));
            }
            meta.lore(lore);
            icon.setItemMeta(meta);
            h.inv.setItem(i, icon);
        }
        for (int i = 45; i < 54; i++) h.inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE));
        h.inv.setItem(49, button(Material.PAPER, "<aqua><b>How to buy</b></aqua>",
                "Have the price items in your inventory,",
                "then click what you want. The payment",
                "is taken and the item handed to you."));
    }

    private ItemStack withEditLore(ItemStack current, int slot) {
        ItemStack shown = current.clone();
        ItemMeta meta = shown.getItemMeta();
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(loreLine("<dark_gray>—"));
        lore.add(loreLine(rowTitle(slot) + " <gray>×" + shown.getAmount()));
        lore.add(loreLine("<yellow>Left: +1 · Right: −1 · Shift: ±16"));
        meta.lore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    private ItemStack emptyTemplate(int slot) {
        return button(slot < 9 ? Material.LIME_STAINED_GLASS_PANE
                        : slot < 18 ? Material.YELLOW_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE,
                rowTitle(slot) + " <gray>— empty</gray>",
                "Click with an item on your cursor", "(a copy is used — yours stays yours).");
    }

    private static String rowTitle(int slot) {
        return slot < 9 ? "<green><b>Sold item</b></green>"
                : slot < 18 ? "<yellow><b>Cost 1</b></yellow>" : "<white><b>Cost 2</b></white> <gray>(optional)</gray>";
    }

    // --- editor persistence -------------------------------------------------

    /** Write this page's columns back into the trade list (idempotent — safe to run twice). */
    private void saveEditor(Holder h, Character c, Player p) {
        List<ShopTrade> built = new ArrayList<>(9);
        boolean costless = false;
        for (int i = 0; i < 9; i++) {
            ItemStack result = h.col[i], a = h.col[i + 9], b = h.col[i + 18];
            if (result == null) continue;               // no sold item → the column is deleted/empty
            ItemStack costA = a != null ? a : b;        // cost 2 alone just becomes cost 1
            ItemStack costB = a != null ? b : null;
            if (costA == null) { costless = true; continue; }
            built.add(new ShopTrade(result, costA, costB));
        }
        List<ShopTrade> out = new ArrayList<>(c.trades());
        int from = Math.min(h.page * 9, out.size());
        int to = Math.min(from + 9, out.size());
        out.subList(from, to).clear();
        out.addAll(from, built);
        c.setTrades(out);
        manager.persist(c);
        if (costless && !h.warnedCostless && p != null) {
            h.warnedCostless = true;
            p.sendMessage(Component.text("A trade with no cost item was not saved — give it a Cost 1.",
                    NamedTextColor.YELLOW));
        }
    }

    // --- click handling -----------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        boolean top = e.getClickedInventory() == e.getView().getTopInventory();
        // The EDITOR lets the player play normally in their OWN inventory so they can pick an item up
        // and click it onto a template slot. Everything touching the top inventory — and every
        // cross-inventory action (shift-move, double-click collect) — is cancelled and re-interpreted,
        // so real items can never enter the GUI. Other views stay buttons-only everywhere.
        boolean allowVanilla = h.view == View.EDITOR && !top
                && !e.getClick().isShiftClick()
                && e.getClick() != ClickType.DOUBLE_CLICK
                && e.getAction() != InventoryAction.COLLECT_TO_CURSOR
                && e.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY;
        if (!allowVanilla) e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Character c = h.shop();
        if (c == null) { p.closeInventory(); return; }

        int slot = e.getSlot();
        switch (h.view) {
            case MAIN -> {
                if (!top) return;
                if (slot == MAIN_OPEN) {
                    openCustomer(p, c);
                } else if (slot == MAIN_TRADES) {
                    openEditor(p, h, 0);
                } else if (slot == MAIN_DIALOGUE) {
                    openDialogue(p, c);
                } else if (slot == MAIN_BEHAVIOR) {
                    openBehavior(p, h);
                } else if (slot == MAIN_OUTINGS) {
                    openOutings(p, c);
                } else if (slot == MAIN_NAMETAG) {
                    openLineList(p, c, "tag");
                } else if (slot == MAIN_SEATS) {
                    p.closeInventory();
                    seatWand.bind(p, c.getId());
                    Material wand = seatWand.wandItem(c);
                    if (!p.getInventory().contains(wand)) p.getInventory().addItem(new ItemStack(wand));
                    p.sendMessage(Component.text("Seat mode: right-click slabs/stairs with the "
                            + wand.name().toLowerCase().replace('_', ' ') + " to mark sit spots. Click a marked"
                            + " seat again = priority seat. Sneak-click removes.", NamedTextColor.GOLD));
                } else if (slot == MAIN_MOVE) {
                    moveShopHere(p, c);
                } else if (slot == MAIN_CHEST && c.owner() != null) {
                    settings.begin(p, c, ShopSettings.Mode.CHEST);
                } else if (slot == MAIN_RENAME) {
                    settings.begin(p, c, ShopSettings.Mode.RENAME);
                } else if (slot == MAIN_SKIN) {
                    if (e.isRightClick()) {
                        manager.applySkin(c, p.getName());
                        p.sendMessage(Component.text("Shopkeeper now wears your skin.", NamedTextColor.GREEN));
                    } else {
                        settings.begin(p, c, ShopSettings.Mode.SKIN);
                    }
                } else if (slot == MAIN_DELETE) {
                    long now = System.currentTimeMillis();
                    if (now - h.deleteArmedAt <= 5000) {
                        p.closeInventory();
                        manager.removeShopViaGui(p, c.getId());
                    } else {
                        h.deleteArmedAt = now;
                        h.inv.setItem(MAIN_DELETE, button(Material.BARRIER, "<dark_red><b>REALLY delete?</b></dark_red>",
                                "Click again within 5 seconds", "to remove this shopkeeper."));
                    }
                }
            }
            case EDITOR -> handleEditorClick(e, h, c, p, top, slot);
            case BEHAVIOR -> {
                if (!top) return;
                int[] slots = {BEH_ROAM, BEH_SIT, BEH_GOSSIP, BEH_HAWK};
                for (int i = 0; i < slots.length; i++) {
                    if (slot == slots[i]) {
                        String[] b = BEHAVIORS[i];
                        manager.setAi(c, b[0], !c.ai(b[0], "t".equals(b[1])));
                        renderBehavior(h, c);
                        return;
                    }
                }
                if (slot == BEH_BACK) openMain(p, c);
            }
            case DIALOGUE -> {
                if (!top) return;
                for (Object[] cat : DIALOGUE_CATS) {
                    if (slot == (Integer) cat[1]) { openLineList(p, c, (String) cat[0]); return; }
                }
                if (slot == 22) openMain(p, c);
            }
            case OUTINGS -> {
                if (!top) return;
                if (slot == OUT_TOGGLE) {
                    manager.setAi(c, "outings", !manager.outingsOn(c));
                    renderOutings(h, c);
                } else if (slot == OUT_BACK) {
                    openMain(p, c);
                } else {
                    String spaceId = h.spaceSlot.get(slot);
                    if (spaceId != null) {
                        manager.toggleAwaySpace(c, spaceId);
                        renderOutings(h, c);
                    }
                }
            }
            case LIST -> {
                if (!top) return;
                List<String> lines = listLines(c, h.listKey);
                if (slot >= 0 && slot < 45 && slot < lines.size()) {
                    lines.remove(slot);
                    saveListLines(c, h.listKey, lines);
                    renderList(h, c);
                } else if (slot == LIST_ADD) {
                    settings.beginLine(p, c, h.listKey);   // chat capture → append → reopen this list
                } else if (slot == LIST_BACK) {
                    if ("tag".equals(h.listKey)) openMain(p, c);
                    else openDialogue(p, c);
                }
            }
            case CUSTOMER -> {
                if (!top || slot < 0 || slot >= 45 || slot >= c.trades().size()) return;
                ShopTrade t = c.trades().get(slot);
                int count = e.isShiftClick() ? 8 : 1;
                int bought = 0;
                String error = null;
                for (int i = 0; i < count; i++) {
                    error = engine.purchase(p, c, t);
                    if (error != null) break;
                    bought++;
                }
                if (bought > 0) {
                    p.sendMessage(Component.text("Bought " + (bought * t.result().getAmount()) + "× for "
                            + ShopEngine.describe(withAmount(t.a(), t.a().getAmount() * bought))
                            + (t.b() != null ? " + " + ShopEngine.describe(withAmount(t.b(), t.b().getAmount() * bought)) : "")
                            + ".", NamedTextColor.GREEN));
                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                    manager.onShopPurchase(p, c);   // the keeper says thanks (and waves you out later)
                    manager.notifyOwnerSale(c, p, t.result(), bought * t.result().getAmount());
                } else if (error != null) {
                    p.sendMessage(Component.text(error, NamedTextColor.RED));
                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
                }
                renderGrid(h, c);   // stock/lore may have changed
            }
        }
    }

    private void handleEditorClick(InventoryClickEvent e, Holder h, Character c, Player p, boolean top, int slot) {
        if (!top) {
            // shift-click on the player's OWN item (cancelled above) → start a new trade with a copy of
            // it as the sold item; plain clicks are vanilla, so they can also pick it up and click a slot.
            if (!e.getClick().isShiftClick()) return;
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;
            for (int i = 0; i < 9; i++) {
                if (h.col[i] == null) {
                    h.col[i] = clicked.clone();
                    renderEditor(h, c);
                    return;
                }
            }
            p.sendMessage(Component.text("This page is full — turn the page for more trade slots.", NamedTextColor.GRAY));
            return;
        }
        if (slot >= 0 && slot < 27) {
            ItemStack cursor = e.getCursor();
            boolean holding = cursor != null && !cursor.getType().isAir();
            h.col[slot] = holding ? cursor.clone() : adjust(h.col[slot], e);
            renderEditor(h, c);
        } else if (slot == ED_BACK) {
            saveEditor(h, c, p);
            openMain(p, c);
        } else if (slot == ED_PREV && h.page > 0) {
            saveEditor(h, c, p);
            openEditor(p, h, h.page - 1);
        } else if (slot == ED_NEXT && h.page < pages() - 1) {
            saveEditor(h, c, p);
            openEditor(p, h, h.page + 1);
        }
    }

    /** Amount button logic on a template slot: left +, right −, shift ×16; −1 at one clears. */
    private static ItemStack adjust(ItemStack current, InventoryClickEvent e) {
        if (current == null) return null;
        int step = e.isShiftClick() ? 16 : 1;
        int amount = current.getAmount() + (e.isRightClick() ? -step : step);
        if (amount <= 0) return null;   // reduced away → clear the slot
        current.setAmount(Math.min(amount, current.getMaxStackSize()));
        return current;
    }

    private static ItemStack withAmount(ItemStack item, int amount) {
        ItemStack copy = item.clone();
        copy.setAmount(Math.max(1, amount));
        return copy;
    }

    // --- vanilla merchant purchases ----------------------------------------

    /**
     * A vanilla-merchant trade is about to complete. Vanilla already handles the player's side
     * (ingredients out, result in); here the player shop's chest mirrors it — the sold item leaves
     * the chest, the payment lands in it — or the purchase is cancelled when the chest can't.
     */
    @EventHandler
    public void onPurchase(PlayerPurchaseEvent e) {
        MerchantSession s = merchants.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        Character c = manager.get(s.shopId());
        if (c == null) { e.setCancelled(true); return; }
        ShopTrade t = s.match(e.getTrade());
        if (t == null) { e.setCancelled(true); return; }
        if (c.owner() != null) {
            Inventory stock = engine.stockOf(c);
            List<ItemStack> costs = new ArrayList<>(2);
            costs.add(t.a());
            if (t.b() != null) costs.add(t.b());
            if (stock == null || ShopEngine.countMatching(stock, t.result()) < t.result().getAmount()) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(Component.text("Out of stock!", NamedTextColor.RED));
                return;
            }
            if (!ShopEngine.hasRoomFor(stock, List.of(t.result()), costs)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(Component.text("The shop's chest is full — tell the owner!", NamedTextColor.RED));
                return;
            }
            ShopEngine.removeMatching(stock, t.result());
            for (ItemStack cost : costs) stock.addItem(cost.clone());
        }
        manager.onShopPurchase(e.getPlayer(), c);
        manager.notifyOwnerSale(c, e.getPlayer(), t.result(), t.result().getAmount());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getView().getTopInventory() instanceof MerchantInventory) {
            merchants.remove(e.getPlayer().getUniqueId());
        }
        if (e.getView().getTopInventory().getHolder() instanceof Holder h && h.view == View.EDITOR
                && e.getPlayer() instanceof Player p) {
            Character c = h.shop();
            if (c != null) saveEditor(h, c, p);
        }
    }

    /** Move the shopkeeper's post to the clicker's position (validated against region + chest range). */
    private void moveShopHere(Player p, Character c) {
        Location to = p.getLocation();
        if (c.owner() != null && !p.hasPermission("livingnpc.admin")
                && plugin.getConfig().getBoolean("shop.require-region", true) && RegionGate.available()) {
            boolean ownerOnly = plugin.getConfig().getBoolean("shop.require-region-owner", true);
            boolean allowed = ownerOnly ? RegionGate.ownerOfRegionAt(p, to) : RegionGate.memberOfRegionAt(p, to);
            if (!allowed) {
                p.sendMessage(Component.text("Your shopkeeper must stay on a plot YOU own.", NamedTextColor.RED));
                return;
            }
        }
        int[] chest = c.container();
        if (chest != null) {
            double max = plugin.getConfig().getDouble("shop.max-container-distance", 10);
            if (to.distanceSquared(new Location(to.getWorld(), chest[0] + 0.5, chest[1] + 0.5, chest[2] + 0.5)) > max * max) {
                p.sendMessage(Component.text("Too far from the stock chest (max " + (int) max
                        + " blocks) — move the chest first.", NamedTextColor.RED));
                return;
            }
        }
        manager.lobbySetPost(c, to);
        p.sendMessage(Component.text("Shopkeeper post moved here.", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        int topSize = e.getView().getTopInventory().getSize();
        List<Integer> topSlots = e.getRawSlots().stream().filter(s -> s < topSize).toList();
        if (topSlots.isEmpty()) return;   // a drag entirely in their own inventory is fine
        e.setCancelled(true);
        // dragging an item onto exactly one template slot = set that template (a copy, item stays theirs)
        if (h.view != View.EDITOR || topSlots.size() != 1) return;
        ItemStack dragged = e.getOldCursor();
        if (dragged == null || dragged.getType().isAir()) return;
        int slot = topSlots.get(0);
        if (slot < 0 || slot >= 27) return;
        h.col[slot] = dragged.clone();
        Character c = h.shop();
        if (c != null) renderEditor(h, c);
    }

    // --- item helpers -------------------------------------------------------

    private static ItemStack button(Material m, String title, String... loreLines) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(loreLine(title));
        List<Component> lore = new ArrayList<>(loreLines.length);
        for (String l : loreLines) lore.add(loreLine("<gray>" + l));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack pane(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.empty());
        it.setItemMeta(meta);
        return it;
    }

    private static void fill(Inventory inv, ItemStack with) {
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, with);
    }

    /** MiniMessage → Component with the default-italic of lore turned off. */
    private static Component loreLine(String mini) {
        return dev.servereer.livingshopkeeper.dialog.DialogText.mini(mini)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static String plainName(Character c) {
        return PlainTextComponentSerializer.plainText().serialize(
                dev.servereer.livingshopkeeper.dialog.DialogText.mini(c.display()));
    }
}
