package fr.eternom.eterMarket.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Une annonce de l'hôtel des ventes vient d'être achetée (thread principal, sur le serveur de l'acheteur), une seule
 * fois : payée, l'objet livré ou mis de côté. Le vendeur peut être hors ligne ou ailleurs. Pour les autres plugins
 * (succès...).
 */
public class AuctionSaleEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID buyer;
    private final UUID seller;
    private final ItemStack item;
    private final double price;

    public AuctionSaleEvent(UUID buyer, UUID seller, ItemStack item, double price) {
        this.buyer = buyer;
        this.seller = seller;
        this.item = item.clone();
        this.price = price;
    }

    public UUID getBuyer() {
        return buyer;
    }

    public UUID getSeller() {
        return seller;
    }

    /** Copie de l'objet vendu. */
    public ItemStack getItem() {
        return item.clone();
    }

    public double getPrice() {
        return price;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
