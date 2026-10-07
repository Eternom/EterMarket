package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Données de la guilde des métiers, communes à tout le réseau. Appels bloquants : hors du thread principal.
 * - etermarket_job_members : le métier de chaque joueur et la date de son dernier changement ;
 * - etermarket_job_catalog : le répertoire des quêtes possibles de chaque métier (objet, quantité, récompense) ;
 * - etermarket_job_quests : les quêtes du jour de chaque joueur (une ligne par emplacement 0 à 3, le 3 étant le bonus),
 *   recopiées depuis le répertoire : une modification du répertoire ne change pas les quêtes déjà tirées.
 */
public class JobRepository {

    private static final String MEMBERS = "job_members";
    private static final String CATALOG = "job_catalog";
    private static final String QUESTS = "job_quests";

    public record Member(String job, long changedAt) {
    }

    /** Une quête possible du répertoire. */
    public record Template(long id, String job, Material material, int amount, double reward) {
    }

    /** Une quête du jour d'un joueur. day : jour julien du tirage ; slot 3 = quête bonus. */
    public record Quest(int slot, long day, Material material, int amount, double reward, boolean done) {
    }

    private final Database database;

    public JobRepository(Database database) {
        this.database = database;
        database.createTable(MEMBERS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("job", Column.Type.STRING).length(32).notNull(),
                Column.of("changed_at", Column.Type.LONG).notNull());
        database.createTable(CATALOG,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("job", Column.Type.STRING).length(32).notNull(),
                Column.of("material", Column.Type.STRING).length(64).notNull(),
                Column.of("amount", Column.Type.INT).notNull(),
                Column.of("reward", Column.Type.DOUBLE).notNull());
        database.createTable(QUESTS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("slot", Column.Type.INT).primaryKey(),
                Column.of("day", Column.Type.LONG).notNull(),
                Column.of("material", Column.Type.STRING).length(64).notNull(),
                Column.of("amount", Column.Type.INT).notNull(),
                Column.of("reward", Column.Type.DOUBLE).notNull(),
                Column.of("done", Column.Type.BOOLEAN).notNull());
    }

    // ---------- Membres ----------

    public Optional<Member> member(UUID player) {
        return database.getFirst(MEMBERS, Map.of("uuid", player))
                .map(row -> new Member(row.getString("job"), row.getLong("changed_at")));
    }

    /** Change de métier : ses quêtes du jour (de l'ancien métier) sont effacées. */
    public void setJob(UUID player, String job, long now) {
        database.set(MEMBERS, Map.of("uuid", player, "job", job, "changed_at", now), "uuid");
        database.delete(QUESTS, Map.of("uuid", player));
    }

    // ---------- Répertoire ----------

    public List<Template> catalog(String job) {
        return database.get(CATALOG, Map.of("job", job)).stream().map(JobRepository::toTemplate)
                .filter(template -> template.material() != null).toList();
    }

    public List<Template> catalog() {
        return database.get(CATALOG, Map.of()).stream().map(JobRepository::toTemplate)
                .filter(template -> template.material() != null).toList();
    }

    public void addTemplate(String job, Material material, int amount, double reward) {
        database.insert(CATALOG, Map.of("job", job, "material", material.name(), "amount", amount, "reward", reward));
    }

    public void updateTemplate(long id, int amount, double reward) {
        database.update(CATALOG, Map.of("amount", amount, "reward", reward), Map.of("id", id));
    }

    public void removeTemplate(long id) {
        database.delete(CATALOG, Map.of("id", id));
    }

    /** Répertoire de départ, seulement si celui de ce métier est vide (premier démarrage). */
    public void seedIfEmpty(String job, List<Template> defaults) {
        if (catalog(job).isEmpty()) {
            defaults.forEach(template -> addTemplate(job, template.material(), template.amount(), template.reward()));
        }
    }

    // ---------- Quêtes du jour ----------

    public List<Quest> quests(UUID player) {
        return database.get(QUESTS, Map.of("uuid", player)).stream()
                .map(row -> new Quest(row.getInt("slot"), row.getLong("day"), Material.matchMaterial(row.getString("material")),
                        row.getInt("amount"), row.getDouble("reward"), row.getBoolean("done")))
                .filter(quest -> quest.material() != null)
                .toList();
    }

    /** Remplace les quêtes du joueur par celles du jour. */
    public void replaceQuests(UUID player, List<Quest> quests) {
        database.delete(QUESTS, Map.of("uuid", player));
        quests.forEach(quest -> saveQuest(player, quest));
    }

    public void saveQuest(UUID player, Quest quest) {
        database.set(QUESTS, Map.of("uuid", player, "slot", quest.slot(), "day", quest.day(), "material", quest.material().name(),
                "amount", quest.amount(), "reward", quest.reward(), "done", quest.done()), "uuid", "slot");
    }

    /** Marque la quête faite, seulement si elle ne l'était pas : deux validations simultanées ne paient qu'une fois. */
    public boolean complete(UUID player, int slot, long day) {
        return database.execute("UPDATE " + database.table(QUESTS) + " SET done = TRUE WHERE uuid = ? AND slot = ? AND day = ? AND done = FALSE",
                player, slot, day) > 0;
    }

    private static Template toTemplate(Row row) {
        return new Template(row.getLong("id"), row.getString("job"), Material.matchMaterial(row.getString("material")),
                row.getInt("amount"), row.getDouble("reward"));
    }
}
