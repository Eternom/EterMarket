package fr.eternom.eterMarket.module.shop;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Ce que vend chaque boutique (table etermarket_shop_items) : l'objet exact (avec sa quantité par lot, son nom, ses
 * enchantements...) et son prix par lot, dans l'ordre d'affichage. Appels bloquants : hors du thread principal.
 */
public class ShopRepository {

    private static final String TABLE = "shop_items";

    /** position : ordre dans la boutique ; item : un lot vendu (quantité comprise). */
    public record ShopItem(String npc, int position, ItemStack item, double price) {

        /** Objet simple (sans nom, enchantement...) : il peut passer par le stock commun. */
        public boolean stockable() {
            return item.isSimilar(ItemStack.of(item.getType()));
        }
    }

    private final Database database;

    public ShopRepository(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("npc", Column.Type.STRING).length(32).primaryKey(),
                Column.of("position", Column.Type.INT).primaryKey(),
                Column.of("item", Column.Type.BLOB).notNull(),
                Column.of("price", Column.Type.DOUBLE).notNull());
    }

    public List<ShopItem> items(String npc) {
        return database.get(TABLE, Map.of("npc", npc)).stream()
                .map(row -> new ShopItem(npc, row.getInt("position"), ItemStack.deserializeBytes(row.getBytes("item")),
                        row.getDouble("price")))
                .sorted(Comparator.comparingInt(ShopItem::position))
                .toList();
    }

    /** Tous les objets de toutes les boutiques (contrôle des prix face aux récompenses des quêtes). */
    public List<ShopItem> allItems() {
        return database.get(TABLE, Map.of()).stream()
                .map(row -> new ShopItem(row.getString("npc"), row.getInt("position"), ItemStack.deserializeBytes(row.getBytes("item")),
                        row.getDouble("price")))
                .toList();
    }

    /** Ajoute à la fin de la boutique. */
    public void add(String npc, ItemStack item, double price) {
        int next = items(npc).stream().mapToInt(ShopItem::position).max().orElse(-1) + 1;
        database.insert(TABLE, Map.of("npc", npc, "position", next, "item", item.serializeAsBytes(), "price", price));
    }

    public void setPrice(String npc, int position, double price) {
        database.update(TABLE, Map.of("price", price), Map.of("npc", npc, "position", position));
    }

    public void remove(String npc, int position) {
        database.delete(TABLE, Map.of("npc", npc, "position", position));
    }

    public void removeAll(String npc) {
        database.delete(TABLE, Map.of("npc", npc));
    }
}
