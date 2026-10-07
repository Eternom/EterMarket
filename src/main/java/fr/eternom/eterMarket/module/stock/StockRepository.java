package fr.eternom.eterMarket.module.stock;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import org.bukkit.Material;

import java.util.Map;

/**
 * Le stock commun, l'« entrepôt de la guilde » : UN stock par matière pour toutes les boutiques et tous les serveurs
 * (table etermarket_stock). Les livraisons des quêtes l'alimentent toujours ; une boutique n'y puise que si elle est
 * réglée « vente sur stock ». L'hôtel des ventes (entre joueurs) n'y touche jamais.
 * Seuls les objets simples (sans nom, enchantement...) passent par le stock. Appels bloquants, mouvements atomiques.
 */
public class StockRepository {

    private static final String TABLE = "stock";

    private final Database database;

    public StockRepository(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("material", Column.Type.STRING).length(64).primaryKey(),
                Column.of("amount", Column.Type.LONG).notNull());
    }

    public long amount(Material material) {
        return database.getFirst(TABLE, Map.of("material", material.name())).map(row -> row.getLong("amount")).orElse(0L);
    }

    /** Ajout (livraisons, remboursement d'un achat raté). */
    public void add(Material material, long amount) {
        database.execute("INSERT INTO " + database.table(TABLE) + " (material, amount) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE amount = amount + ?", material.name(), amount, amount);
    }

    /** Retire amount seulement s'il y en a assez, en une seule opération. @return false s'il en manque */
    public boolean take(Material material, long amount) {
        return database.execute("UPDATE " + database.table(TABLE) + " SET amount = amount - ? WHERE material = ? AND amount >= ?",
                amount, material.name(), amount) > 0;
    }
}
