package fr.eternom.eterMarket.module.auction;

import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.auction.AuctionRepository.Listing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mes annonces en cours (jusqu'à 28) : clic = la retirer (l'objet revient). « = retour à l'hôtel des ventes. */
class MyListingsMenu implements Menu {

    private static final int BACK = 49;

    private final AuctionGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Listing> listingAtSlot = new HashMap<>();

    MyListingsMenu(AuctionGui gui, Player viewer, List<Listing> listings) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 54, text("auction.mine.title"));
        ItemStack neutral = Items.pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!AuctionMenu.SLOTS.contains(slot)) {
                inventory.setItem(slot, neutral);
            }
        }
        for (int i = 0; i < listings.size() && i < AuctionMenu.SLOTS.size(); i++) {
            Listing listing = listings.get(i);
            listingAtSlot.put(AuctionMenu.SLOTS.get(i), listing);
            inventory.setItem(AuctionMenu.SLOTS.get(i), display(listing));
        }
        if (listings.isEmpty()) {
            inventory.setItem(22, Items.item(Material.BARRIER, text("auction.mine.empty"), List.of()));
        }
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("auction.back"), List.of()));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Listing listing = listingAtSlot.get(slot);
        if (listing != null) {
            Sounds.click(player);
            gui.confirmCancel(player, listing);
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.open(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private ItemStack display(Listing listing) {
        ItemStack display = listing.item().clone();
        List<Component> lore = new ArrayList<>(display.lore() == null ? List.of() : display.lore());
        lore.add(Component.empty());
        lore.add(text("auction.item.price", "price", gui.money(listing.price())));
        lore.add(listing.expiresAt() > System.currentTimeMillis()
                ? text("auction.item.time", "time", gui.timeLeft(viewer, listing))
                : text("auction.item.expired"));
        lore.add(text("auction.mine.cancel"));
        display.lore(lore.stream().map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
        return display;
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
