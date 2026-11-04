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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.illustro.picore.menu.PiButton;
import org.illustro.picore.menu.PiMenu;

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
 *   <li><b>Owners</b> get a Shopkeepers-style trade editor powered by PiCore: each column is one trade —
 *       top row the item sold, the two rows below its costs — with pages for up to
 *       {@code shop.max-trade-pages}×9 trades, plus a main menu and an AI behavior toggle menu.</li>
 * </ul>
 *
 * <p><b>Dupe-proofing:</b> Managed by PiCore's {@code PiMenuGuard}. Real items can never enter
 * the GUI; template slots copy cursor items safely; cross-inventory shift-clicks and double-click
 * item raking are strictly prevented.
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
        PiMenu menu = PiMenu.create(Component.text("Shop — " + plainName(c), NamedTextColor.DARK_GREEN), 3);
        menu.fill(pane(Material.GRAY_STAINED_GLASS_PANE));

        menu.button(MAIN_OPEN, PiButton.create(Material.EMERALD, "<green><b>Open shop</b></green>",
                "Trade with your own shop", "exactly like a customer sees it.")
                .onClick((player, e) -> openCustomer(player, c)));

        menu.button(MAIN_TRADES, PiButton.create(Material.WRITABLE_BOOK, "<yellow><b>Edit trades</b></yellow>",
                "Each column is one trade:", "top = item sold, below = its costs.",
                c.trades().size() + " of " + capacity() + " trades set.")
                .onClick((player, e) -> openTrades(player, c)));

        menu.button(MAIN_DIALOGUE, PiButton.create(Material.OAK_SIGN, "<gold><b>Dialogue</b></gold>",
                "Edit what your shopkeeper says:", "greetings, thanks, calls, and more.",
                "Colors with & codes or &#hex work.")
                .onClick((player, e) -> openDialogue(player, c)));

        menu.button(MAIN_BEHAVIOR, PiButton.create(Material.LEAD, "<aqua><b>Behavior</b></aqua>",
                "Toggle what your shopkeeper does", "while idle: roam, sit, gossip,", "hawk wares at passers-by.")
                .onClick((player, e) -> openBehavior(player, c)));

        menu.button(MAIN_OUTINGS, PiButton.create(Material.CLOCK, "<light_purple><b>Outings</b></light_purple>",
                "Let it wander off to hangout spaces", "(off by default). Choose which",
                "spaces it's allowed to visit.")
                .onClick((player, e) -> openOutings(player, c)));

        menu.button(MAIN_SEATS, PiButton.create(Material.OAK_STAIRS, "<gold><b>Seat mode</b></gold>",
                "Toggle seat mode on / off.",
                "With an empty hand, right-click slabs/stairs",
                "to mark sit spots (" + c.seats().size() + " set).",
                "Click a marked seat again = PRIORITY seat",
                "(it sits there for customers). Sneak-click removes.",
                "Left-click air to finish.")
                .onClick((player, e) -> {
                    player.closeInventory();
                    if (seatWand.isBound(player, c.getId())) {
                        seatWand.unbind(player);
                        player.sendMessage(Component.text("Exited seat mode for '" + c.getId() + "'.", NamedTextColor.YELLOW));
                        return;
                    }
                    seatWand.bind(player, c.getId());
                    player.sendMessage(Component.text("Seat mode: with an empty hand, right-click slabs/stairs to mark sit spots ("
                            + c.seats().size() + " set).", NamedTextColor.GOLD));
                    player.sendMessage(Component.text("Click a marked seat again = priority seat. Sneak-click removes. Left-click air to finish.", NamedTextColor.GRAY));
                }));

        List<String> info = new ArrayList<>();
        info.add((c.owner() != null ? "Your shop" : "Admin shop (infinite stock)"));
        info.add("Trades: " + c.trades().size());
        if (c.container() != null) {
            int[] at = c.container();
            info.add("Stock chest: " + at[0] + ", " + at[1] + ", " + at[2]);
            info.add("Keep it stocked — sold items leave it,");
            info.add("payments arrive into it.");
        }
        menu.button(MAIN_INFO, PiButton.create(Material.PAPER, "<aqua><b>How it works</b></aqua>", info.toArray(new String[0])));

        menu.button(MAIN_MOVE, PiButton.create(Material.ENDER_PEARL, "<light_purple><b>Move shopkeeper here</b></light_purple>",
                "Stand where it should stand,", "then click this.")
                .onClick((player, e) -> moveShopHere(player, c)));

        if (c.owner() != null) {
            menu.button(MAIN_CHEST, PiButton.create(Material.CHEST, "<yellow><b>Move stock chest</b></yellow>",
                    "Then right-click the NEW chest", "to link it.")
                    .onClick((player, e) -> settings.begin(player, c, ShopSettings.Mode.CHEST)));
        }

        menu.button(MAIN_NAMETAG, PiButton.create(Material.OAK_HANGING_SIGN, "<yellow><b>Nametag</b></yellow>",
                "Floating text above the shopkeeper", "(none by default). Add/remove lines;",
                c.hasTag() ? c.tagLines().size() + " line(s) set." : "no lines set.")
                .onClick((player, e) -> openLineList(player, c, "tag")));

        menu.button(MAIN_RENAME, PiButton.create(Material.NAME_TAG, "<yellow><b>Rename</b></yellow>",
                "Type the new name in chat", "(& / &#hex / MiniMessage colors).")
                .onClick((player, e) -> settings.begin(player, c, ShopSettings.Mode.RENAME)));

        menu.button(MAIN_SKIN, PiButton.create(Material.PLAYER_HEAD, "<yellow><b>Change skin</b></yellow>",
                "Left-click: type a player name in chat.", "Right-click: use YOUR skin.")
                .onLeftClick((player, e) -> settings.begin(player, c, ShopSettings.Mode.SKIN))
                .onRightClick((player, e) -> {
                    manager.applySkin(c, player.getName());
                    player.sendMessage(Component.text("Shopkeeper now wears your skin.", NamedTextColor.GREEN));
                }));

        // Inline Two-Step Auto-Expiring Delete Confirmation via PiButton.confirm(...)
        menu.button(MAIN_DELETE, PiButton.confirm(
                Material.BARRIER,
                "<red><b>Delete shop</b></red>",
                List.of("&7Click TWICE to remove this", "&7shopkeeper. Chest items stay put."),
                Material.BARRIER,
                "<dark_red><b>REALLY delete?</b></dark_red>",
                List.of("&cClick again within 5 seconds", "&cto remove this shopkeeper."),
                5,
                (player, e) -> {
                    player.closeInventory();
                    manager.removeShopViaGui(player, c.getId());
                }
        ));

        menu.open(p);
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

    public void openTrades(Player p, Character c) {
        openEditor(p, c, 0);
    }

    public void openEditor(Player p, Character c, int page) {
        int maxPages = pages();
        int safePage = Math.max(0, Math.min(page, maxPages - 1));

        ItemStack[] col = new ItemStack[27];
        List<ShopTrade> trades = c.trades();
        for (int i = 0; i < 9; i++) {
            int ti = safePage * 9 + i;
            if (ti >= trades.size()) break;
            ShopTrade t = trades.get(ti);
            col[i] = t.result().clone();
            col[i + 9] = t.a().clone();
            col[i + 18] = t.b() != null ? t.b().clone() : null;
        }

        renderEditorMenu(p, c, safePage, col);
    }

    private void renderEditorMenu(Player p, Character c, int page, ItemStack[] col) {
        int maxPages = pages();
        PiMenu menu = PiMenu.create(Component.text("Trades — " + plainName(c)
                + "  (page " + (page + 1) + "/" + maxPages + ")", NamedTextColor.DARK_GREEN), 6);

        // Allow picking up items from own inventory to click onto template slots
        menu.allowBottomInventoryPickup(true);

        // 1. Template slots (0-26)
        for (int i = 0; i < 27; i++) {
            final int slot = i;
            ItemStack current = col[slot];
            ItemStack icon = current != null ? withEditLore(current, slot) : emptyTemplate(slot);

            menu.button(slot, PiButton.create(icon).onClick((player, e) -> {
                ItemStack cursor = e.getCursor();
                boolean holding = cursor != null && !cursor.getType().isAir();
                if (holding) {
                    col[slot] = PiMenu.cloneClean(cursor);
                } else {
                    col[slot] = adjust(col[slot], e);
                }
                renderEditorMenu(player, c, page, col);
            }));
        }

        // 2. Chrome / Navigation (27-53)
        for (int i = 27; i < 54; i++) menu.button(i, PiButton.create(pane(Material.GRAY_STAINED_GLASS_PANE)));

        if (page > 0) {
            menu.button(ED_PREV, PiButton.create(Material.ARROW, "<white>◀ Page " + page, "Saves this page first.")
                    .onClick((player, e) -> {
                        saveEditor(col, page, c, player);
                        openEditor(player, c, page - 1);
                    }));
        }

        if (page < maxPages - 1) {
            menu.button(ED_NEXT, PiButton.create(Material.ARROW, "<white>Page " + (page + 2) + " ▶", "Saves this page first.")
                    .onClick((player, e) -> {
                        saveEditor(col, page, c, player);
                        openEditor(player, c, page + 1);
                    }));
        }

        menu.button(ED_BACK, PiButton.create(Material.OAK_DOOR, "<white>Save & back", "To the shop menu.")
                .onClick((player, e) -> {
                    saveEditor(col, page, c, player);
                    openMain(player, c);
                }));

        menu.button(ED_INFO, PiButton.create(Material.BOOK, "<aqua><b>How trades work</b></aqua>",
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

        // 3. Bottom inventory shift-click interaction (start new trade in row 0)
        menu.onBottomClick((player, e) -> {
            if (!e.getClick().isShiftClick()) return;
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;
            for (int i = 0; i < 9; i++) {
                if (col[i] == null) {
                    col[i] = PiMenu.cloneClean(clicked);
                    renderEditorMenu(player, c, page, col);
                    return;
                }
            }
            player.sendMessage(Component.text("This page is full — turn the page for more trade slots.", NamedTextColor.GRAY));
        });

        // 4. Drag support: dragging an item across template slots sets the template
        menu.onDrag((player, e) -> {
            int topSize = 54;
            List<Integer> topSlots = e.getRawSlots().stream().filter(s -> s < topSize).toList();
            if (topSlots.size() != 1) return;
            int targetSlot = topSlots.get(0);
            if (targetSlot < 0 || targetSlot >= 27) return;
            ItemStack dragged = e.getOldCursor();
            if (dragged == null || dragged.getType().isAir()) return;
            col[targetSlot] = PiMenu.cloneClean(dragged);
            renderEditorMenu(player, c, page, col);
        });

        // 5. On close auto-persistence
        menu.onClose(player -> saveEditor(col, page, c, player));

        menu.open(p);
    }

    private void openBehavior(Player p, Character c) {
        PiMenu menu = PiMenu.create(Component.text("Behavior — " + plainName(c), NamedTextColor.DARK_GREEN), 3);
        menu.fill(pane(Material.GRAY_STAINED_GLASS_PANE));

        int[] slots = {BEH_ROAM, BEH_SIT, BEH_GOSSIP, BEH_HAWK};
        for (int i = 0; i < BEHAVIORS.length; i++) {
            String[] b = BEHAVIORS[i];
            boolean on = c.ai(b[0], "t".equals(b[1]));
            List<String> lore = new ArrayList<>(List.of(b).subList(3, b.length));
            lore.add(on ? "&aCurrently ON — click to turn off." : "&7Currently OFF — click to turn on.");

            String title = (on ? "<green><b>" : "<gray><b>") + b[2] + "</b> " + (on ? "<green>✔" : "<dark_gray>✘");
            Material mat = on ? Material.LIME_DYE : Material.GRAY_DYE;

            menu.button(slots[i], PiButton.create(mat, title, lore.toArray(new String[0]))
                    .onClick((player, e) -> {
                        manager.setAi(c, b[0], !on);
                        openBehavior(player, c);
                    }));
        }

        menu.button(BEH_BACK, PiButton.create(Material.OAK_DOOR, "<white>Back", "To the shop menu.")
                .onClick((player, e) -> openMain(player, c)));

        menu.open(p);
    }

    private void openDialogue(Player p, Character c) {
        PiMenu menu = PiMenu.create(Component.text("Dialogue — " + plainName(c), NamedTextColor.DARK_GREEN), 3);
        menu.fill(pane(Material.GRAY_STAINED_GLASS_PANE));

        for (Object[] cat : DIALOGUE_CATS) {
            String trigger = (String) cat[0];
            int count = manager.ownerDialogLines(c, trigger).size();
            List<String> lore = new ArrayList<>();
            lore.add((String) cat[3]);
            lore.add("");
            lore.add(count == 0 ? "No lines yet." : count + " line" + (count == 1 ? "" : "s") + " set.");
            lore.add("<yellow>Click to edit.");

            menu.button((Integer) cat[1], PiButton.create(Material.WRITABLE_BOOK,
                    "<gold><b>" + cat[2] + "</b></gold>", lore.toArray(new String[0]))
                    .onClick((player, e) -> openLineList(player, c, trigger)));
        }

        menu.button(22, PiButton.create(Material.OAK_DOOR, "<white>Back", "To the shop menu.")
                .onClick((player, e) -> openMain(player, c)));

        menu.open(p);
    }

    private void openOutings(Player p, Character c) {
        PiMenu menu = PiMenu.create(Component.text("Outings — " + plainName(c), NamedTextColor.DARK_GREEN), 6);
        menu.fill(pane(Material.GRAY_STAINED_GLASS_PANE));

        boolean on = manager.outingsOn(c);
        menu.button(OUT_TOGGLE, PiButton.create(on ? Material.LIME_DYE : Material.GRAY_DYE,
                on ? "<green><b>Outings: ON</b>" : "<gray><b>Outings: OFF</b>",
                on ? "It may wander off to a hangout" : "It stays at its post.",
                on ? "space between customers." : "",
                on ? "Click to turn OFF." : "Click to turn ON.")
                .onClick((player, e) -> {
                    manager.setAi(c, "outings", !on);
                    openOutings(player, c);
                }));

        List<Space> inWorld = new ArrayList<>();
        for (Space sp : manager.spaces().all()) if (sp.world().equals(c.getWorld())) inWorld.add(sp);
        List<String> allow = c.awaySpaces();

        if (inWorld.isEmpty()) {
            menu.button(22, PiButton.create(Material.BARRIER, "<red>No hangout spaces here",
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

                Material paneMat = shownOn ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
                String title = (shownOn ? "<green><b>" : "<red><b>") + sp.id() + "</b>";

                menu.button(slot, PiButton.create(paneMat, title, lore.toArray(new String[0]))
                        .onClick((player, e) -> {
                            manager.toggleAwaySpace(c, sp.id());
                            openOutings(player, c);
                        }));
                slot++;
            }
        }

        menu.button(OUT_BACK, PiButton.create(Material.OAK_DOOR, "<white>Back", "To the shop menu.")
                .onClick((player, e) -> openMain(player, c)));

        menu.open(p);
    }

    public void openLineList(Player p, Character c, String key) {
        if (!"tag".equals(key)) manager.seedOwnerDialogIfNeeded(c, key);
        PiMenu menu = PiMenu.create(Component.text(listTitle(key) + " — " + plainName(c), NamedTextColor.DARK_GREEN), 6);

        List<String> lines = listLines(c, key);
        for (int i = 0; i < lines.size() && i < 45; i++) {
            final int lineIdx = i;
            String raw = lines.get(i);
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta m = it.getItemMeta();
            m.displayName(loreLine(raw));
            List<Component> lore = new ArrayList<>();
            if (raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0 || raw.indexOf('<') >= 0) {
                lore.add(Component.text(raw, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }
            lore.add(loreLine("<red>Click to remove."));
            m.lore(lore);
            it.setItemMeta(m);

            menu.button(i, PiButton.create(it).onClick((player, e) -> {
                List<String> currentLines = listLines(c, key);
                if (lineIdx < currentLines.size()) {
                    currentLines.remove(lineIdx);
                    saveListLines(c, key, currentLines);
                    openLineList(player, c, key);
                }
            }));
        }

        for (int i = 45; i < 54; i++) menu.button(i, PiButton.create(pane(Material.GRAY_STAINED_GLASS_PANE)));

        menu.button(LIST_ADD, PiButton.create(Material.WRITABLE_BOOK, "<green><b>Add a line</b>",
                "Type it in chat.", "& / &#hex / MiniMessage colors,", "and <player> <npc> <time> work.")
                .onClick((player, e) -> settings.beginLine(player, c, key)));

        menu.button(LIST_BACK, PiButton.create(Material.OAK_DOOR, "<white>Back", listBackHint(key))
                .onClick((player, e) -> {
                    if ("tag".equals(key)) openMain(player, c);
                    else openDialogue(player, c);
                }));

        menu.open(p);
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

    public void addLine(Player p, Character c, String key, String text) {
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

    private void openGrid(Player p, Character c) {
        PiMenu menu = PiMenu.create(Component.text(plainName(c), NamedTextColor.DARK_GREEN), 6);
        List<ShopTrade> trades = c.trades();

        for (int i = 0; i < trades.size() && i < 45; i++) {
            ShopTrade t = trades.get(i);
            int uses = engine.stockUses(c, t);
            ItemStack icon = t.result().clone();
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

            menu.button(i, PiButton.create(icon).onClick((player, e) -> {
                int count = e.isShiftClick() ? 8 : 1;
                int bought = 0;
                String error = null;
                for (int buy = 0; buy < count; buy++) {
                    error = engine.purchase(player, c, t);
                    if (error != null) break;
                    bought++;
                }
                if (bought > 0) {
                    player.sendMessage(Component.text("Bought " + (bought * t.result().getAmount()) + "× for "
                            + ShopEngine.describe(withAmount(t.a(), t.a().getAmount() * bought))
                            + (t.b() != null ? " + " + ShopEngine.describe(withAmount(t.b(), t.b().getAmount() * bought)) : "")
                            + ".", NamedTextColor.GREEN));
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                    manager.onShopPurchase(player, c);
                    manager.notifyOwnerSale(c, player, t.result(), bought * t.result().getAmount());
                } else if (error != null) {
                    player.sendMessage(Component.text(error, NamedTextColor.RED));
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
                }
                openGrid(player, c);
            }));
        }

        for (int i = 45; i < 54; i++) menu.button(i, PiButton.create(pane(Material.GRAY_STAINED_GLASS_PANE)));

        menu.button(49, PiButton.create(Material.PAPER, "<aqua><b>How to buy</b></aqua>",
                "Have the price items in your inventory,",
                "then click what you want. The payment",
                "is taken and the item handed to you."));

        menu.open(p);
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

    private void saveEditor(ItemStack[] col, int page, Character c, Player p) {
        if (c == null) return;
        List<ShopTrade> built = new ArrayList<>(9);
        boolean costless = false;
        for (int i = 0; i < 9; i++) {
            ItemStack result = col[i], a = col[i + 9], b = col[i + 18];
            if (result == null) continue;
            ItemStack costA = a != null ? a : b;
            ItemStack costB = a != null ? b : null;
            if (costA == null) { costless = true; continue; }
            built.add(new ShopTrade(PiMenu.cloneClean(result), PiMenu.cloneClean(costA), costB != null ? PiMenu.cloneClean(costB) : null));
        }
        List<ShopTrade> out = new ArrayList<>(c.trades());
        int from = Math.min(page * 9, out.size());
        int to = Math.min(from + 9, out.size());
        out.subList(from, to).clear();
        out.addAll(from, built);
        c.setTrades(out);
        manager.persist(c);
        if (costless && p != null) {
            p.sendMessage(Component.text("A trade with no cost item was not saved — give it a Cost 1.",
                    NamedTextColor.YELLOW));
        }
    }

    private static ItemStack adjust(ItemStack current, InventoryClickEvent e) {
        if (current == null) return null;
        int step = e.isShiftClick() ? 16 : 1;
        int amount = current.getAmount() + (e.isRightClick() ? -step : step);
        if (amount <= 0) return null;
        current.setAmount(Math.min(amount, current.getMaxStackSize()));
        return current;
    }

    private static ItemStack withAmount(ItemStack item, int amount) {
        ItemStack copy = item.clone();
        copy.setAmount(Math.max(1, amount));
        return copy;
    }

    private int pages() {
        return Math.max(1, plugin.getConfig().getInt("shop.max-trade-pages", 10));
    }

    public int capacity() {
        return pages() * 9;
    }

    // --- vanilla merchant purchases ----------------------------------------

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
    }

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

    private static Component loreLine(String mini) {
        return dev.servereer.livingshopkeeper.dialog.DialogText.mini(mini)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static String plainName(Character c) {
        return PlainTextComponentSerializer.plainText().serialize(
                dev.servereer.livingshopkeeper.dialog.DialogText.mini(c.display()));
    }
}
