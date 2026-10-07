package fr.eternom.eterMarket.module.auction;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.auction.AuctionGui.Browse;
import fr.eternom.eterMarket.module.auction.AuctionRepository.Listing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
 * Hôtel des ventes, 6 lignes :
 * <pre>
 *  ▣ ▣ ▢ ▢ ☺ ▢ ▢ ▣ ▣     ☺ = joueur (solde, annonces x/limite, colis à récupérer)
 *  ▣ · · · · · · · ▣     · = annonces de tout le réseau, 28 par page (prix, vendeur, temps restant) ; clic = acheter
 *  ▢ · · · · · · · ▢
 *  ▢ · · · · · · · ▢
 *  ▣ · · · · · · · ▣
 *  ◀ ⌕ ✉ $ « ▣ ▤ ▣ ▶     ⌕ = rechercher · ✉ = mes ventes · $ = vendre l'objet en main · ▤ = boîte de récupération
 * </pre>
 */
class AuctionMenu implements Menu {

    static final List<Integer> SLOTS = innerSlots();
    private static final int INFO = 4;
    private static final int PREVIOUS = 45;
    private static final int SEARCH = 46;
    private static final int MINE = 47;
    private static final int SELL = 48;
    private static final int BACK = 49;
    private static final int COLLECTION = 51;
    private static final int NEXT = 53;
    private static final int EMPTY = 22;

    private final AuctionGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Browse browse;
    private final int page;
    private final Inventory inventory;
    private final Map<Integer, Listing> listingAtSlot = new HashMap<>();

    AuctionMenu(AuctionGui gui, Player viewer, Browse browse, int page) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.browse = browse;
        this.page = Math.clamp(page, 0, pageCount() - 1);
        this.inventory = Bukkit.createInventory(this, 54, browse.filter() == null
                ? text("auction.menu.title")
                : text("auction.menu.title-search", "query", browse.filter()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Listing listing = listingAtSlot.get(slot);
        if (listing != null) {
            if (listing.seller().equals(player.getUniqueId())) {
                player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                return;
            }
            Sounds.click(player);
            gui.confirmBuy(player, listing, page, browse.filter());
            return;
        }
        switch (slot) {
            case PREVIOUS -> {
                if (page > 0) {
                    Sounds.page(player);
                    gui.open(player, page - 1, browse.filter());
                }
            }
            case NEXT -> {
                if (page + 1 < pageCount()) {
                    Sounds.page(player);
                    gui.open(player, page + 1, browse.filter());
                }
            }
            case SEARCH -> {
                Sounds.click(player);
                if (browse.filter() != null && click.isRightClick()) {
                    gui.open(player, 0, null); // clic droit : effacer la recherche
                } else {
                    gui.askSearch(player);
                }
            }
            case MINE -> {
                Sounds.page(player);
                gui.openMine(player);
            }
            case SELL -> {
                Sounds.click(player);
                gui.askSell(player);
            }
            case COLLECTION -> {
                Sounds.page(player);
                gui.openCollection(player);
            }
            case BACK -> gui.backButton().click(player);
            default -> {
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.fill(inventory, Material.ORANGE_STAINED_GLASS_PANE, SLOTS);
        inventory.setItem(INFO, Items.head(viewer.getPlayerProfile(), text("auction.menu.player", "player", viewer.getName()), List.of(
                text("auction.menu.balance", "amount", Money.format(browse.balance())),
                text("auction.menu.listings", "count", String.valueOf(browse.ownListings()), "limit", String.valueOf(browse.limit())),
                text("auction.menu.parcels", "count", String.valueOf(browse.parcels())))));

        int start = page * SLOTS.size();
        List<Listing> listings = browse.listings();
        for (int i = 0; i < SLOTS.size() && start + i < listings.size(); i++) {
            Listing listing = listings.get(start + i);
            listingAtSlot.put(SLOTS.get(i), listing);
            inventory.setItem(SLOTS.get(i), display(listing));
        }
        if (listings.isEmpty()) {
            inventory.setItem(EMPTY, Items.item(Material.BARRIER, text(browse.filter() == null ? "auction.menu.empty" : "auction.menu.empty-search"),
                    List.of()));
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS, Items.item(Material.ARROW, text("shop.menu.previous"), List.of(pageLine(page))));
        }
        if (page + 1 < pageCount()) {
            inventory.setItem(NEXT, Items.item(Material.ARROW, text("shop.menu.next"), List.of(pageLine(page + 2))));
        }
        List<Component> searchLore = new ArrayList<>();
        searchLore.add(text("auction.menu.search-lore"));
        if (browse.filter() != null) {
            searchLore.add(text("auction.menu.search-clear"));
        }
        inventory.setItem(SEARCH, Items.item(Material.SPYGLASS, text("auction.menu.search"), searchLore, browse.filter() != null));
        inventory.setItem(MINE, Items.item(Material.CHEST, text("auction.menu.mine"), List.of(text("auction.menu.mine-lore"))));
        inventory.setItem(SELL, Items.item(Material.EMERALD, text("auction.menu.sell"), List.of(text("auction.menu.sell-lore"))));
        inventory.setItem(COLLECTION, Items.item(Material.ENDER_CHEST, text("auction.menu.collection"),
                List.of(text("auction.menu.parcels", "count", String.valueOf(browse.parcels()))), browse.parcels() > 0));
        inventory.setItem(BACK, gui.backButton().item(viewer));
    }

    private ItemStack display(Listing listing) {
        ItemStack display = listing.item().clone();
        List<Component> lore = new ArrayList<>(display.lore() == null ? List.of() : display.lore());
        lore.add(Component.empty());
        lore.add(text("auction.item.price", "price", Money.format(listing.price())));
        lore.add(text("auction.item.seller", "seller", listing.sellerName()));
        lore.add(text("auction.item.time", "time", gui.timeLeft(viewer, listing)));
        lore.add(text(listing.seller().equals(viewer.getUniqueId()) ? "auction.item.yours" : "auction.item.buy"));
        display.lore(lore.stream().map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
        return display;
    }

    private Component pageLine(int shown) {
        return text("shop.menu.page", "page", String.valueOf(shown), "pages", String.valueOf(pageCount()));
    }

    private int pageCount() {
        return Math.max(1, (browse.listings().size() + SLOTS.size() - 1) / SLOTS.size());
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
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
