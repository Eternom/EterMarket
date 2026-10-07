package fr.eternom.eterMarket.module.auction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.eternom.eterLib.helper.cache.RedisMessenger;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.module.auction.AuctionRepository.Listing;
import fr.eternom.eterMarket.module.auction.AuctionRepository.Parcel;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.UUID;

/**
 * L'hôtel des ventes, entre joueurs, sur tout le réseau. Chaque opération est ordonnée pour ne jamais créer ni perdre un
 * objet ou de l'argent :
 * - vendre : l'objet quitte la main, la limite d'annonces est vérifiée, les frais sont payés, l'annonce est écrite ;
 *   au moindre refus, l'objet est rendu ;
 * - acheter : l'acheteur paie, puis l'annonce est réservée (DELETE atomique) ; si quelqu'un l'a eue avant, il est
 *   remboursé. Le vendeur reçoit le prix moins la taxe (même hors ligne) et est prévenu où qu'il soit (Redis) ;
 * - l'objet acheté, retiré ou invendu va dans l'inventaire s'il y a de la place, sinon dans la boîte de récupération.
 */
public class AuctionService {

    public static final String LISTINGS_PERMISSION = "etermarket.auction.listings.";
    private static final String CHANNEL = "etermarket:auction";

    /** Réglages (config.yml > auction). fee et tax : fractions (0.01 = 1 %). */
    public record Settings(Duration duration, double fee, double tax, double minPrice, double maxPrice, int defaultListings) {
    }

    private enum Result { OK, LIMIT, NOT_ENOUGH, GONE }

    private final JavaPlugin plugin;
    private final AuctionRepository repository;
    private final Settings settings;
    private final RedisMessenger messenger; // null sans Redis
    private final Messages messages;

    public AuctionService(JavaPlugin plugin, AuctionRepository repository, Settings settings, RedisMessenger messenger,
                          Messages messages) {
        this.plugin = plugin;
        this.repository = repository;
        this.settings = settings;
        this.messenger = messenger;
        this.messages = messages;
    }

    /** Démarrage : messages « ton objet s'est vendu » des autres serveurs, et expiration des annonces chaque minute. */
    public void start() {
        if (messenger != null) {
            messenger.subscribe(CHANNEL, json -> {
                JsonObject data = JsonParser.parseString(json).getAsJsonObject();
                UUID seller = UUID.fromString(data.get("seller").getAsString());
                if (plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, () -> notifySoldLocal(seller, data.get("item").getAsString(),
                            data.get("amount").getAsString()));
                }
            });
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                repository.expire();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Expiration des annonces impossible : " + e.getMessage());
            }
        }, 20 * 30, 20 * 60);
    }

    public Settings settings() {
        return settings;
    }

    /** Nombre d'annonces permises : la plus grande permission etermarket.auction.listings.<n>, sinon la valeur par défaut. */
    public int listingLimit(Player player) {
        int limit = settings.defaultListings();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String permission = info.getPermission();
            if (info.getValue() && permission.startsWith(LISTINGS_PERMISSION)) {
                try {
                    limit = Math.max(limit, Integer.parseInt(permission.substring(LISTINGS_PERMISSION.length())));
                } catch (NumberFormatException ignored) {
                    // permission mal écrite : ignorée
                }
            }
        }
        return limit;
    }

    /** Thread principal : met en vente l'objet en main au prix donné. */
    public void sellHeld(Player player, double price, Runnable after) {
        Economy economy = economy();
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand().clone();
        if (item.isEmpty()) {
            messages.send(player, "auction.hold-item");
            return;
        }
        double rounded = round(price, economy.fractionalDigits());
        if (rounded < settings.minPrice() || rounded > settings.maxPrice()) {
            messages.send(player, "auction.price-range", "min", economy.format(settings.minPrice()), "max", economy.format(settings.maxPrice()));
            return;
        }
        double fee = settings.fee() > 0 ? Math.max(1, round(rounded * settings.fee(), economy.fractionalDigits())) : 0;
        int limit = listingLimit(player);
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        player.getInventory().setItemInMainHand(null); // l'objet quitte la main tout de suite : impossible de le vendre deux fois
        Tasks.async(plugin, player, () -> {
            if (repository.countBySeller(uuid) >= limit) {
                return Result.LIMIT;
            }
            if (fee > 0 && !economy.withdrawPlayer(player, fee).transactionSuccess()) {
                return Result.NOT_ENOUGH;
            }
            long now = System.currentTimeMillis();
            repository.list(uuid, name, item, rounded, now, now + settings.duration().toMillis());
            return Result.OK;
        }, result -> {
            switch (result) {
                case OK -> {
                    messages.send(player, "auction.listed", "price", economy.format(rounded), "fee", economy.format(fee));
                    player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                }
                case LIMIT -> {
                    give(player, item);
                    messages.send(player, "auction.limit", "limit", String.valueOf(limit));
                }
                default -> {
                    give(player, item);
                    messages.send(player, "auction.fee-not-enough", "fee", economy.format(fee));
                }
            }
            after.run();
        }, () -> give(player, item));
    }

    /** Thread principal : achète une annonce. */
    public void buy(Player player, Listing listing, Runnable after) {
        Economy economy = economy();
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        if (listing.seller().equals(player.getUniqueId())) {
            messages.send(player, "auction.own-listing");
            return;
        }
        double toSeller = round(listing.price() * (1 - settings.tax()), economy.fractionalDigits());
        Tasks.async(plugin, player, () -> {
            if (!economy.withdrawPlayer(player, listing.price()).transactionSuccess()) {
                return Result.NOT_ENOUGH;
            }
            if (!repository.claim(listing.id())) {
                economy.depositPlayer(player, listing.price()); // quelqu'un l'a eue avant : remboursé
                return Result.GONE;
            }
            economy.depositPlayer(Bukkit.getOfflinePlayer(listing.seller()), toSeller);
            return Result.OK;
        }, result -> {
            switch (result) {
                case OK -> {
                    deliver(player, listing.item(), "bought");
                    messages.send(player, "auction.bought", "price", economy.format(listing.price()), "seller", listing.sellerName());
                    player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                    notifySold(listing.seller(), itemName(listing.item()), economy.format(toSeller));
                }
                case NOT_ENOUGH -> messages.send(player, "auction.not-enough", "price", economy.format(listing.price()));
                default -> messages.send(player, "auction.gone");
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** Thread principal : retire sa propre annonce (l'objet revient). */
    public void cancel(Player player, Listing listing, Runnable after) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> repository.cancel(listing.id(), uuid), cancelled -> {
            if (cancelled) {
                deliver(player, listing.item(), "cancelled");
                messages.send(player, "auction.cancelled");
            } else {
                messages.send(player, "auction.gone");
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** Thread principal : prend un colis de la boîte de récupération. */
    public void collect(Player player, Parcel parcel, Runnable after) {
        if (!fits(player, parcel.item())) {
            messages.send(player, "auction.no-space");
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> repository.take(parcel.id(), uuid), taken -> {
            if (taken) {
                give(player, parcel.item());
                player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1f);
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** Dans l'inventaire s'il y a la place, sinon dans la boîte de récupération (avec un message). */
    private void deliver(Player player, ItemStack item, String reason) {
        if (fits(player, item)) {
            give(player, item);
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> repository.deposit(uuid, item, reason), "Objet non déposé dans la boîte de " + player.getName());
        messages.send(player, "auction.to-collection");
    }

    private void notifySold(UUID seller, String item, String amount) {
        if (Bukkit.getPlayer(seller) != null || messenger == null) {
            notifySoldLocal(seller, item, amount);
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("seller", seller.toString());
        data.addProperty("item", item);
        data.addProperty("amount", amount);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                messenger.publish(CHANNEL, data.toString());
            } catch (RuntimeException ignored) {
                // Redis en panne : le vendeur verra son argent sans le message
            }
        });
    }

    private void notifySoldLocal(UUID seller, String item, String amount) {
        Player player = Bukkit.getPlayer(seller);
        if (player != null) {
            messages.send(player, "auction.sold", "item", item, "amount", amount);
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.4f);
        }
    }

    static String itemName(ItemStack item) {
        return item.getAmount() + "× " + PlainTextComponentSerializer.plainText().serialize(item.effectiveName());
    }

    private static void give(Player player, ItemStack item) {
        player.getInventory().addItem(item.clone()).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
    }

    private static boolean fits(Player player, ItemStack item) {
        Inventory copy = Bukkit.createInventory(null, 36);
        copy.setContents(player.getInventory().getStorageContents());
        return copy.addItem(item.clone()).isEmpty();
    }

    private static double round(double amount, int digits) {
        if (!Double.isFinite(amount)) {
            return -1;
        }
        return BigDecimal.valueOf(amount).setScale(Math.max(0, digits), RoundingMode.HALF_UP).doubleValue();
    }

    static Economy economy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }
}
