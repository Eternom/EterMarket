package fr.eternom.eterMarket.module.auction;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * L'hôtel des ventes, entre joueurs, commun à tout le réseau (il ne touche jamais au stock commun). Appels bloquants.
 * - etermarket_auction_listings : les annonces en cours (supprimées quand elles sont achetées, retirées ou expirées) ;
 * - etermarket_auction_collection : la boîte de récupération de chaque joueur (invendus, annonces retirées, achats
 *   sans place dans l'inventaire).
 * Chaque sortie d'une annonce passe par un DELETE ... WHERE id = ? : un seul serveur, un seul acheteur la gagne.
 */
public class AuctionRepository {

    private static final String LISTINGS = "auction_listings";
    private static final String COLLECTION = "auction_collection";

    public record Listing(long id, UUID seller, String sellerName, ItemStack item, double price, long createdAt, long expiresAt) {
    }

    public record Parcel(long id, UUID owner, ItemStack item, String reason) {
    }

    private final Database database;

    public AuctionRepository(Database database) {
        this.database = database;
        database.createTable(LISTINGS,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("seller", Column.Type.UUID).notNull(),
                Column.of("seller_name", Column.Type.STRING).length(16).notNull(),
                Column.of("item", Column.Type.BLOB).notNull(),
                Column.of("price", Column.Type.DOUBLE).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull(),
                Column.of("expires_at", Column.Type.LONG).notNull());
        database.createTable(COLLECTION,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("owner", Column.Type.UUID).notNull(),
                Column.of("item", Column.Type.BLOB).notNull(),
                Column.of("reason", Column.Type.STRING).length(16).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull());
    }

    // ---------- Annonces ----------

    /** Annonces en cours (non expirées), les plus récentes en premier. */
    public List<Listing> active() {
        return database.query("SELECT * FROM " + database.table(LISTINGS) + " WHERE expires_at > ? ORDER BY created_at DESC",
                System.currentTimeMillis()).stream().map(AuctionRepository::toListing).toList();
    }

    public List<Listing> bySeller(UUID seller) {
        return database.query("SELECT * FROM " + database.table(LISTINGS) + " WHERE seller = ? ORDER BY created_at DESC", seller)
                .stream().map(AuctionRepository::toListing).toList();
    }

    public int countBySeller(UUID seller) {
        return database.query("SELECT COUNT(*) AS total FROM " + database.table(LISTINGS) + " WHERE seller = ?", seller)
                .getFirst().getInt("total");
    }

    public Optional<Listing> get(long id) {
        return database.getFirst(LISTINGS, Map.of("id", id)).map(AuctionRepository::toListing);
    }

    public void list(UUID seller, String sellerName, ItemStack item, double price, long now, long expiresAt) {
        database.insert(LISTINGS, Map.of("seller", seller, "seller_name", sellerName, "item", item.serializeAsBytes(),
                "price", price, "created_at", now, "expires_at", expiresAt));
    }

    /** Achat : retire l'annonce seulement si elle est encore en vente. @return false si quelqu'un l'a eue avant */
    public boolean claim(long id) {
        return database.execute("DELETE FROM " + database.table(LISTINGS) + " WHERE id = ? AND expires_at > ?",
                id, System.currentTimeMillis()) > 0;
    }

    /** Retrait par son vendeur. @return false si elle n'est plus en vente */
    public boolean cancel(long id, UUID seller) {
        return database.execute("DELETE FROM " + database.table(LISTINGS) + " WHERE id = ? AND seller = ?", id, seller) > 0;
    }

    /** Annonces expirées : chacune passe dans la boîte de son vendeur, une seule fois même si plusieurs serveurs le font. */
    public int expire() {
        int moved = 0;
        for (Row row : database.query("SELECT * FROM " + database.table(LISTINGS) + " WHERE expires_at <= ?", System.currentTimeMillis())) {
            if (database.delete(LISTINGS, Map.of("id", row.getLong("id"))) > 0) {
                Listing listing = toListing(row);
                deposit(listing.seller(), listing.item(), "expired");
                moved++;
            }
        }
        return moved;
    }

    // ---------- Boîte de récupération ----------

    public List<Parcel> collection(UUID owner) {
        return database.query("SELECT * FROM " + database.table(COLLECTION) + " WHERE owner = ? ORDER BY created_at", owner)
                .stream()
                .map(row -> new Parcel(row.getLong("id"), row.getUUID("owner"), ItemStack.deserializeBytes(row.getBytes("item")),
                        row.getString("reason")))
                .toList();
    }

    public int countCollection(UUID owner) {
        return database.query("SELECT COUNT(*) AS total FROM " + database.table(COLLECTION) + " WHERE owner = ?", owner)
                .getFirst().getInt("total");
    }

    /** reason : expired (invendu), cancelled (retiré), bought (acheté sans place). */
    public void deposit(UUID owner, ItemStack item, String reason) {
        database.insert(COLLECTION, Map.of("owner", owner, "item", item.serializeAsBytes(), "reason", reason,
                "created_at", System.currentTimeMillis()));
    }

    /** Retire un colis de la boîte. @return false s'il a déjà été pris */
    public boolean take(long id, UUID owner) {
        return database.execute("DELETE FROM " + database.table(COLLECTION) + " WHERE id = ? AND owner = ?", id, owner) > 0;
    }

    private static Listing toListing(Row row) {
        return new Listing(row.getLong("id"), row.getUUID("seller"), row.getString("seller_name"),
                ItemStack.deserializeBytes(row.getBytes("item")), row.getDouble("price"), row.getLong("created_at"),
                row.getLong("expires_at"));
    }
}
