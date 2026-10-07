package fr.eternom.eterMarket.module.npc;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PNJ d'EterMarket, en base commune à tout le réseau :
 * - etermarket_npcs : la DÉFINITION d'un PNJ (nom, skin, rôle, vente sur stock), écrite une seule fois ;
 * - etermarket_placements : où il est placé (serveur, monde, position). Un même PNJ peut être placé autant de fois
 *   qu'on veut, sur n'importe quel serveur, sans rien redéfinir.
 * Appels bloquants : hors du thread principal.
 */
public class NpcRepository {

    private static final String NPCS = "npcs";
    private static final String PLACEMENTS = "placements";

    /** Rôle d'un PNJ. Pour l'instant les boutiques ; les métiers et l'hôtel des ventes viendront ensuite. */
    public enum Role { SHOP }

    /**
     * @param name     nom affiché au-dessus de la tête (MiniMessage)
     * @param skin     pseudo dont le PNJ prend le skin, ou null
     * @param texture  texture précise (valeur et signature, ex : MineSkin), prioritaire sur skin ; null sinon
     * @param useStock la boutique ne vend que ce qu'il y a dans le stock commun (sinon : quantité illimitée)
     */
    public record Npc(String id, String name, String skin, Texture texture, Role role, boolean useStock) {
    }

    public record Texture(String value, String signature) {
    }

    public record Placement(long id, String npc, String server, String world, double x, double y, double z, float yaw) {
    }

    private final Database database;

    public NpcRepository(Database database) {
        this.database = database;
        database.createTable(NPCS,
                Column.of("id", Column.Type.STRING).length(32).primaryKey(),
                Column.of("name", Column.Type.STRING).length(255).notNull(),
                Column.of("skin", Column.Type.STRING).length(16),
                Column.of("texture_value", Column.Type.TEXT),
                Column.of("texture_signature", Column.Type.TEXT),
                Column.of("role", Column.Type.STRING).length(16).notNull(),
                Column.of("use_stock", Column.Type.BOOLEAN).notNull());
        database.createTable(PLACEMENTS,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("npc", Column.Type.STRING).length(32).notNull(),
                Column.of("server", Column.Type.STRING).length(64).notNull(),
                Column.of("world", Column.Type.STRING).length(64).notNull(),
                Column.of("x", Column.Type.DOUBLE).notNull(),
                Column.of("y", Column.Type.DOUBLE).notNull(),
                Column.of("z", Column.Type.DOUBLE).notNull(),
                Column.of("yaw", Column.Type.FLOAT).notNull());
    }

    public List<Npc> all() {
        return database.get(NPCS, Map.of()).stream().map(NpcRepository::toNpc).toList();
    }

    public Optional<Npc> get(String id) {
        return database.getFirst(NPCS, Map.of("id", id)).map(NpcRepository::toNpc);
    }

    /** @return false si un PNJ porte déjà cet identifiant */
    public boolean create(String id, Role role) {
        return database.execute("INSERT IGNORE INTO " + database.table(NPCS) + " (id, name, role, use_stock) VALUES (?, ?, ?, ?)",
                id, "<accent>" + id, role, false) > 0;
    }

    public void save(Npc npc) {
        Map<String, Object> values = new HashMap<>(); // HashMap : skin et texture peuvent être null
        values.put("id", npc.id());
        values.put("name", npc.name());
        values.put("skin", npc.skin());
        values.put("texture_value", npc.texture() == null ? null : npc.texture().value());
        values.put("texture_signature", npc.texture() == null ? null : npc.texture().signature());
        values.put("role", npc.role());
        values.put("use_stock", npc.useStock());
        database.set(NPCS, values, "id");
    }

    /** Supprime le PNJ et tous ses emplacements, sur tous les serveurs. */
    public void delete(String id) {
        database.delete(PLACEMENTS, Map.of("npc", id));
        database.delete(NPCS, Map.of("id", id));
    }

    public List<Placement> placements(String server) {
        return database.get(PLACEMENTS, Map.of("server", server)).stream().map(NpcRepository::toPlacement).toList();
    }

    public long place(String npc, String server, String world, double x, double y, double z, float yaw) {
        database.insert(PLACEMENTS, Map.of("npc", npc, "server", server, "world", world, "x", x, "y", y, "z", z, "yaw", yaw));
        return database.query("SELECT MAX(id) AS id FROM " + database.table(PLACEMENTS) + " WHERE npc = ? AND server = ?", npc, server)
                .getFirst().getLong("id");
    }

    public void removePlacement(long id) {
        database.delete(PLACEMENTS, Map.of("id", id));
    }

    private static Npc toNpc(Row row) {
        String value = row.getString("texture_value");
        return new Npc(row.getString("id"), row.getString("name"), row.getString("skin"),
                value == null ? null : new Texture(value, row.getString("texture_signature")),
                Role.valueOf(row.getString("role")), row.getBoolean("use_stock"));
    }

    private static Placement toPlacement(Row row) {
        return new Placement(row.getLong("id"), row.getString("npc"), row.getString("server"), row.getString("world"),
                row.getDouble("x"), row.getDouble("y"), row.getDouble("z"), row.getFloat("yaw"));
    }
}
