package fr.eternom.eterMarket.module.shop;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.npc.NpcRepository.Role;
import fr.eternom.eterMarket.module.shop.ShopGui.View;
import fr.eternom.eterMarket.module.shop.ShopRepository.ShopItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Boutique d'un PNJ, 6 lignes :
 * <pre>
 *  ▣ ▣ ▢ ▢ ☺ ▢ ▢ ▣ ▣     ☺ = joueur (solde)
 *  ▣ · · · · · · · ▣     · = objets vendus, 28 par page (prix du lot, stock si la boutique vend sur stock)
 *  ▢ · · · · · · · ▢     clic gauche : acheter un lot · clic droit : choisir la quantité
 *  ▢ · · · · · · · ▢
 *  ▣ · · · · · · · ▣
 *  ◀ ▣ ✎ ▢ « ▢ ▢ ▣ ▶     ✎ = quêtes du métier (PNJ de métier) · « = retour (commande de la config) ou fermer
 * </pre>
 */
class ShopMenu implements Menu {

    static final List<Integer> SLOTS = innerSlots();
    private static final int INFO = 4;
    private static final int PREVIOUS = 45;
    private static final int QUESTS = 47;
    private static final int BACK = 49;
    private static final int NEXT = 53;
    private static final int EMPTY = 22;

    private final ShopGui gui;
    private final Messages messages;
    private final Player viewer;
    private final View view;
    private final int page;
    private final Inventory inventory;
    private final Map<Integer, ShopItem> itemAtSlot = new HashMap<>();

    ShopMenu(ShopGui gui, Player viewer, View view, int page) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.view = view;
        this.page = Math.clamp(page, 0, pageCount(view.items().size()) - 1);
        this.inventory = Bukkit.createInventory(this, 54, messages.render(view.npc().name(), TagResolver.empty()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        ShopItem item = itemAtSlot.get(slot);
        if (item != null) {
            if (soldOut(item)) {
                player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            } else if (click.isRightClick()) {
                Sounds.click(player);
                gui.askQuantity(player, view, item, page);
            } else {
                Sounds.click(player);
                gui.buy(player, view, item, 1, page);
            }
        } else if (slot == PREVIOUS && page > 0) {
            Sounds.page(player);
            gui.open(player, view.npc().id(), page - 1);
        } else if (slot == NEXT && page + 1 < pageCount(view.items().size())) {
            Sounds.page(player);
            gui.open(player, view.npc().id(), page + 1);
        } else if (slot == QUESTS && view.npc().role() == Role.JOB) {
            Sounds.page(player);
            gui.openQuests(player, view.npc());
        } else if (slot == BACK) {
            gui.backButton().click(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.fill(inventory, Material.ORANGE_STAINED_GLASS_PANE, SLOTS);
        inventory.setItem(INFO, Items.head(viewer.getPlayerProfile(), text("shop.menu.player", "player", viewer.getName()),
                List.of(text("shop.menu.balance", "amount", Money.format(view.balance())))));

        int start = page * SLOTS.size();
        List<ShopItem> items = view.items();
        for (int i = 0; i < SLOTS.size() && start + i < items.size(); i++) {
            ShopItem item = items.get(start + i);
            itemAtSlot.put(SLOTS.get(i), item);
            inventory.setItem(SLOTS.get(i), display(item));
        }
        if (items.isEmpty()) {
            inventory.setItem(EMPTY, Items.item(Material.BARRIER, text("shop.menu.empty"), List.of()));
        }
        pages(inventory, page, pageCount(items.size()), messages, viewer);
        if (view.npc().role() == Role.JOB) {
            inventory.setItem(QUESTS, Items.item(Material.WRITABLE_BOOK, text("shop.menu.quests"), List.of(text("shop.menu.quests-lore"))));
        }
        inventory.setItem(BACK, gui.backButton().item(viewer));
    }

    private ItemStack display(ShopItem item) {
        ItemStack display = item.item().clone();
        List<Component> lore = new ArrayList<>(display.lore() == null ? List.of() : display.lore());
        lore.add(Component.empty());
        lore.add(text("shop.item.price", "price", Money.format(item.price()), "amount", String.valueOf(item.item().getAmount())));
        if (view.npc().useStock() && item.stockable()) {
            long amount = view.stock().getOrDefault(item.item().getType(), 0L);
            lore.add(soldOut(item) ? text("shop.item.sold-out") : text("shop.item.stock", "amount", String.valueOf(amount)));
        }
        if (!soldOut(item)) {
            lore.add(text("shop.item.buy"));
            lore.add(text("shop.item.buy-many"));
        }
        display.lore(lore.stream().map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
        return display;
    }

    private boolean soldOut(ShopItem item) {
        return view.npc().useStock() && item.stockable()
                && view.stock().getOrDefault(item.item().getType(), 0L) < item.item().getAmount();
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }

    // ---------- Partagé avec l'éditeur ----------

    static int pageCount(int items) {
        return Math.max(1, (items + SLOTS.size() - 1) / SLOTS.size());
    }

    static void pages(Inventory inventory, int page, int pages, Messages messages, Player viewer) {
        if (page > 0) {
            inventory.setItem(PREVIOUS, Items.item(Material.ARROW, messages.get(viewer, "shop.menu.previous"),
                    List.of(messages.get(viewer, "shop.menu.page", "page", String.valueOf(page), "pages", String.valueOf(pages)))));
        }
        if (page + 1 < pages) {
            inventory.setItem(NEXT, Items.item(Material.ARROW, messages.get(viewer, "shop.menu.next"),
                    List.of(messages.get(viewer, "shop.menu.page", "page", String.valueOf(page + 2), "pages", String.valueOf(pages)))));
        }
    }

    /** Lignes 2 à 5, colonnes 2 à 8 : l'intérieur du cadre. */
    private static List<Integer> innerSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int column = 1; column <= 7; column++) {
                slots.add(row * 9 + column);
            }
        }
        return List.copyOf(slots);
    }
}
