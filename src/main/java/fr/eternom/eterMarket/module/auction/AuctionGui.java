package fr.eternom.eterMarket.module.auction;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.helper.Inputs;
import fr.eternom.eterMarket.module.auction.AuctionRepository.Listing;
import fr.eternom.eterMarket.module.auction.AuctionRepository.Parcel;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

/**
 * Menus de l'hôtel des ventes (PNJ) : les annonces de tout le réseau (avec recherche), ses propres ventes, sa boîte de
 * récupération, et les Dialogs de vente, d'achat et de recherche. Données lues en tâche de fond avant l'ouverture.
 */
public class AuctionGui {

    /** Ce qu'affiche le menu principal. filter : recherche en cours (minuscules), ou null. */
    record Browse(List<Listing> listings, String filter, double balance, int ownListings, int limit, int parcels) {
    }

    private final JavaPlugin plugin;
    private final AuctionRepository repository;
    private final AuctionService service;
    private final Messages messages;
    private final BackButton backButton;

    public AuctionGui(JavaPlugin plugin, AuctionRepository repository, AuctionService service, Messages messages, BackButton backButton) {
        this.plugin = plugin;
        this.repository = repository;
        this.service = service;
        this.messages = messages;
        this.backButton = backButton;
    }

    public void open(Player player) {
        open(player, 0, null);
    }

    void open(Player player, int page, String filter) {
        Economy economy = Money.economy();
        int limit = service.listingLimit(player);
        Tasks.async(plugin, player, () -> {
            List<Listing> listings = repository.active().stream()
                    .filter(listing -> filter == null || matches(listing.item(), filter))
                    .toList();
            return new Browse(listings, filter, economy == null ? 0 : economy.getBalance(player),
                    repository.countBySeller(player.getUniqueId()), limit, repository.countCollection(player.getUniqueId()));
        }, browse -> player.openInventory(new AuctionMenu(this, player, browse, page).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    void openMine(Player player) {
        Tasks.async(plugin, player, () -> repository.bySeller(player.getUniqueId()),
                listings -> player.openInventory(new MyListingsMenu(this, player, listings).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    void openCollection(Player player) {
        Tasks.async(plugin, player, () -> repository.collection(player.getUniqueId()),
                parcels -> player.openInventory(new CollectionMenu(this, player, parcels).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    void askSell(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            messages.send(player, "auction.hold-item");
            return;
        }
        player.closeInventory();
        AuctionService.Settings settings = service.settings();
        DialogBase base = DialogBase.builder(messages.get(player, "auction.sell.title"))
                .body(List.of(DialogBody.item(held.clone()).build(),
                        DialogBody.plainMessage(messages.get(player, "auction.sell.body",
                                "fee", percent(settings.fee()), "tax", percent(settings.tax()),
                                "duration", EterLib.get().formatDuration(player, settings.duration().toSeconds())))))
                .inputs(List.of(DialogInput.text("price", messages.get(player, "auction.sell.label")).maxLength(12).build()))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "auction.sell.confirm"), messages.get(player, "dialog.cancel"),
                response -> {
                    double price = Inputs.number(response.getText("price"));
                    if (price <= 0) {
                        messages.send(player, "auction.price-invalid");
                        open(player);
                        return;
                    }
                    service.sellHeld(player, price, () -> open(player));
                },
                () -> open(player));
    }

    void confirmBuy(Player player, Listing listing, int page, String filter) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "auction.buy.title"))
                .body(List.of(DialogBody.item(listing.item().clone()).build(),
                        DialogBody.plainMessage(messages.get(player, "auction.buy.body", "price", Money.format(listing.price()),
                                "seller", listing.sellerName()))))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "auction.buy.confirm"), messages.get(player, "dialog.cancel"),
                response -> service.buy(player, listing, () -> open(player, page, filter)),
                () -> open(player, page, filter));
    }

    void confirmCancel(Player player, Listing listing) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "auction.cancel.title"))
                .body(List.of(DialogBody.item(listing.item().clone()).build(),
                        DialogBody.plainMessage(messages.get(player, "auction.cancel.body"))))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "auction.cancel.confirm"), messages.get(player, "dialog.cancel"),
                response -> service.cancel(player, listing, () -> openMine(player)),
                () -> openMine(player));
    }

    void askSearch(Player player) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "auction.search.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "auction.search.body"))))
                .inputs(List.of(DialogInput.text("query", messages.get(player, "auction.search.label")).maxLength(32).build()))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "auction.search.confirm"), messages.get(player, "dialog.cancel"),
                response -> {
                    String query = response.getText("query");
                    open(player, 0, query == null || query.isBlank() ? null : query.trim().toLowerCase(Locale.ROOT));
                },
                () -> open(player));
    }

    void collect(Player player, Parcel parcel) {
        service.collect(player, parcel, () -> openCollection(player));
    }

    // ---------- Outils ----------


    String timeLeft(Player viewer, Listing listing) {
        return EterLib.get().formatDuration(viewer, Math.max(0, (listing.expiresAt() - System.currentTimeMillis()) / 1000));
    }

    Messages messages() {
        return messages;
    }

    BackButton backButton() {
        return backButton;
    }

    /** Recherche : nom affiché de l'objet ou identifiant de sa matière (diamond, diamant...). */
    private static boolean matches(ItemStack item, String filter) {
        String name = PlainTextComponentSerializer.plainText().serialize(item.effectiveName()).toLowerCase(Locale.ROOT);
        return name.contains(filter) || item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ').contains(filter);
    }

    private static String percent(double fraction) {
        return String.valueOf(Math.round(fraction * 1000) / 10.0).replace(".0", "");
    }

}
