package fr.eternom.eterMarket.module.auction;

import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.auction.AuctionRepository.Parcel;
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

/**
 * Boîte de récupération (jusqu'à 28 colis affichés) : invendus, annonces retirées, achats sans place.
 * Clic = prendre le colis (s'il y a la place). « = retour à l'hôtel des ventes.
 */
class CollectionMenu implements Menu {

    private static final int BACK = 49;

    private final AuctionGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Parcel> parcelAtSlot = new HashMap<>();

    CollectionMenu(AuctionGui gui, Player viewer, List<Parcel> parcels) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 54, text("auction.collection.title"));
        ItemStack neutral = Items.pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!AuctionMenu.SLOTS.contains(slot)) {
                inventory.setItem(slot, neutral);
            }
        }
        for (int i = 0; i < parcels.size() && i < AuctionMenu.SLOTS.size(); i++) {
            Parcel parcel = parcels.get(i);
            parcelAtSlot.put(AuctionMenu.SLOTS.get(i), parcel);
            ItemStack display = parcel.item().clone();
            List<Component> lore = new ArrayList<>(display.lore() == null ? List.of() : display.lore());
            lore.add(Component.empty());
            lore.add(text("auction.collection.reason." + parcel.reason()));
            lore.add(text("auction.collection.take"));
            display.lore(lore.stream().map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
            inventory.setItem(AuctionMenu.SLOTS.get(i), display);
        }
        if (parcels.isEmpty()) {
            inventory.setItem(22, Items.item(Material.BARRIER, text("auction.collection.empty"), List.of()));
        }
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("auction.back"), List.of()));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Parcel parcel = parcelAtSlot.get(slot);
        if (parcel != null) {
            Sounds.click(player);
            gui.collect(player, parcel);
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.open(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
