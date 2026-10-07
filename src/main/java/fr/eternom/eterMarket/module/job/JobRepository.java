package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Données de la guilde des métiers, communes à tout le réseau. Appels bloquants : hors du thread principal.
 * - etermarket_job_members : le métier de chaque joueur, la date de son dernier changement, ses quêtes accomplies
 *   (toutes, pour de futurs niveaux) et le dernier jour où il a changé une quête ;
 * - etermarket_job_templates : le répertoire des quêtes possibles de chaque métier (niveau, objectifs, récompense) ;
 * - etermarket_job_daily : les quêtes du jour de chaque joueur (une ligne par emplacement 0 à 3, le 3 étant le bonus),
 *   recopiées depuis le répertoire (le modifier ne change pas les quêtes déjà tirées), avec la progression de l'action
 *   et la quête suivie dans la sidebar.
 * Les tables de la 1.2 (job_catalog, job_quests : un seul objet par quête) sont supprimées au démarrage.
 */
public class JobRepository {

    private static final String MEMBERS = "job_members";
    private static final String TEMPLATES = "job_templates";
    private static final String DAILY = "job_daily";

    public record Member(String job, long changedAt, int completed, long rerollDay) {
    }

    /** Une quête possible du répertoire. */
    public record Template(long id, String job, Tier tier, List<Objective> objectives, double reward) {

        /**
         * Récompense par objet livré, pour chaque matière d'une quête de livraison (contrôle d'arbitrage des
         * boutiques) : la récompense partagée entre tous les objets demandés. Vide si la quête a une action
         * (impossible de l'acheter).
         */
        public Map<Material, Double> unitRewards() {
            Map<Material, Double> rewards = new HashMap<>();
            if (objectives.stream().anyMatch(o -> o.kind().isAction())) {
                return rewards;
            }
            int units = objectives.stream().mapToInt(Objective::amount).sum();
            objectives.forEach(o -> rewards.put(o.material(), reward / Math.max(1, units)));
            return rewards;
        }
    }

    /** Une quête du jour d'un joueur. day : jour julien du tirage ; slot 3 = quête bonus ; progress : de l'action. */
    public record Quest(int slot, long day, Tier tier, List<Objective> objectives, double reward, int progress,
                        boolean done, boolean tracked) {

        /** L'objectif d'action (tuer, casser, pêcher), s'il y en a un. */
        public Optional<Objective> action() {
            return objectives.stream().filter(o -> o.kind().isAction()).findFirst();
        }

        public List<Objective> items() {
            return objectives.stream().filter(o -> o.kind() == Objective.Kind.ITEM).toList();
        }

        /** Quantité d'action à atteindre (0 sans action). */
        public int target() {
            return action().map(Objective::amount).orElse(0);
        }

        public boolean actionComplete() {
            return progress >= target();
        }

        public Quest withProgress(int progress) {
            return new Quest(slot, day, tier, objectives, reward, progress, done, tracked);
        }

        public Quest withDone() {
            return new Quest(slot, day, tier, objectives, reward, progress, true, false);
        }

        public Quest withTracked(boolean tracked) {
            return new Quest(slot, day, tier, objectives, reward, progress, done, tracked);
        }
    }

    private final Database database;

    public JobRepository(Database database) {
        this.database = database;
        database.createTable(MEMBERS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("job", Column.Type.STRING).length(32).notNull(),
                Column.of("changed_at", Column.Type.LONG).notNull());
        database.addColumn(MEMBERS, Column.of("completed", Column.Type.INT));
        database.addColumn(MEMBERS, Column.of("reroll_day", Column.Type.LONG));
        database.createTable(TEMPLATES,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("job", Column.Type.STRING).length(32).notNull(),
                Column.of("tier", Column.Type.STRING).length(16).notNull(),
                Column.of("objectives", Column.Type.STRING).length(512).notNull(),
                Column.of("reward", Column.Type.DOUBLE).notNull());
        database.createTable(DAILY,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("slot", Column.Type.INT).primaryKey(),
                Column.of("day", Column.Type.LONG).notNull(),
                Column.of("tier", Column.Type.STRING).length(16).notNull(),
                Column.of("objectives", Column.Type.STRING).length(512).notNull(),
                Column.of("reward", Column.Type.DOUBLE).notNull(),
                Column.of("progress", Column.Type.INT).notNull(),
                Column.of("done", Column.Type.BOOLEAN).notNull(),
                Column.of("tracked", Column.Type.BOOLEAN).notNull());
        // Tables de la 1.2, remplacées par job_templates et job_daily (pas de table morte dans la base)
        database.execute("DROP TABLE IF EXISTS " + database.table("job_catalog") + ", " + database.table("job_quests"));
    }

    // ---------- Membres ----------

    public Optional<Member> member(UUID player) {
        return database.getFirst(MEMBERS, Map.of("uuid", player))
                .map(row -> new Member(row.getString("job"), row.getLong("changed_at"),
                        row.get("completed") == null ? 0 : row.getInt("completed"),
                        row.get("reroll_day") == null ? -1 : row.getLong("reroll_day")));
    }

    /** Change de métier : ses quêtes du jour (de l'ancien métier) sont effacées. */
    public void setJob(UUID player, String job, long now) {
        database.set(MEMBERS, Map.of("uuid", player, "job", job, "changed_at", now), "uuid");
        database.delete(DAILY, Map.of("uuid", player));
    }

    public void addCompleted(UUID player) {
        database.execute("UPDATE " + database.table(MEMBERS) + " SET completed = COALESCE(completed, 0) + 1 WHERE uuid = ?", player);
    }

    /** Réserve le changement de quête du jour : false s'il a déjà été utilisé aujourd'hui (même sur un autre serveur). */
    public boolean claimReroll(UUID player, long today) {
        return database.execute("UPDATE " + database.table(MEMBERS) + " SET reroll_day = ? WHERE uuid = ?"
                + " AND (reroll_day IS NULL OR reroll_day <> ?)", today, player, today) > 0;
    }

    /** Rend le changement de quête du jour (paiement refusé, quête faite entre-temps). */
    public void releaseReroll(UUID player, long today) {
        database.execute("UPDATE " + database.table(MEMBERS) + " SET reroll_day = NULL WHERE uuid = ? AND reroll_day = ?", player, today);
    }

    // ---------- Répertoire ----------

    public List<Template> catalog(String job) {
        return database.get(TEMPLATES, Map.of("job", job)).stream().map(JobRepository::toTemplate)
                .filter(template -> template.tier() != null && !template.objectives().isEmpty()).toList();
    }

    public List<Template> catalog() {
        return database.get(TEMPLATES, Map.of()).stream().map(JobRepository::toTemplate)
                .filter(template -> template.tier() != null && !template.objectives().isEmpty()).toList();
    }

    public void addTemplate(String job, Tier tier, List<Objective> objectives, double reward) {
        database.insert(TEMPLATES, Map.of("job", job, "tier", tier.id(), "objectives", Objective.format(objectives), "reward", reward));
    }

    public void updateTemplate(long id, Tier tier, List<Objective> objectives, double reward) {
        database.update(TEMPLATES, Map.of("tier", tier.id(), "objectives", Objective.format(objectives), "reward", reward),
                Map.of("id", id));
    }

    public void removeTemplate(long id) {
        database.delete(TEMPLATES, Map.of("id", id));
    }

    /** Répertoire de départ, seulement si celui de ce métier est vide (premier démarrage). */
    public void seedIfEmpty(String job, List<Template> defaults) {
        if (catalog(job).isEmpty()) {
            defaults.forEach(template -> addTemplate(job, template.tier(), template.objectives(), template.reward()));
        }
    }

    /** Remplace tout le répertoire du métier par celui de départ (/market jobs reset). */
    public void resetCatalog(String job, List<Template> defaults) {
        database.delete(TEMPLATES, Map.of("job", job));
        defaults.forEach(template -> addTemplate(job, template.tier(), template.objectives(), template.reward()));
    }

    // ---------- Quêtes du jour ----------

    public List<Quest> quests(UUID player) {
        return database.get(DAILY, Map.of("uuid", player)).stream()
                .map(row -> new Quest(row.getInt("slot"), row.getLong("day"), Tier.of(row.getString("tier")),
                        Objective.parse(row.getString("objectives")), row.getDouble("reward"), row.getInt("progress"),
                        row.getBoolean("done"), row.getBoolean("tracked")))
                .filter(quest -> quest.tier() != null && !quest.objectives().isEmpty())
                .toList();
    }

    /** Remplace les quêtes du joueur par celles du jour. */
    public void replaceQuests(UUID player, List<Quest> quests) {
        database.delete(DAILY, Map.of("uuid", player));
        quests.forEach(quest -> saveQuest(player, quest));
    }

    public void saveQuest(UUID player, Quest quest) {
        database.set(DAILY, Map.of("uuid", player, "slot", quest.slot(), "day", quest.day(), "tier", quest.tier().id(),
                "objectives", Objective.format(quest.objectives()), "reward", quest.reward(), "progress", quest.progress(),
                "done", quest.done(), "tracked", quest.tracked()), "uuid", "slot");
    }

    /** Remplace une quête pas encore faite par une autre (changement de quête) : false si elle a été faite entre-temps. */
    public boolean swapQuest(UUID player, Quest replacement) {
        return database.execute("UPDATE " + database.table(DAILY) + " SET tier = ?, objectives = ?, reward = ?, progress = 0"
                        + " WHERE uuid = ? AND slot = ? AND day = ? AND done = FALSE",
                replacement.tier().id(), Objective.format(replacement.objectives()), replacement.reward(),
                player, replacement.slot(), replacement.day()) > 0;
    }

    /** Ajoute de la progression d'action (sans dépasser target) ; ignoré si la quête est faite, d'un autre jour ou changée. */
    public void addProgress(UUID player, int slot, long day, String objectives, int delta, int target) {
        database.execute("UPDATE " + database.table(DAILY) + " SET progress = LEAST(?, progress + ?)"
                + " WHERE uuid = ? AND slot = ? AND day = ? AND objectives = ? AND done = FALSE", target, delta, player, slot, day, objectives);
    }

    /** La quête suivie dans la sidebar (une seule) ; slot -1 = aucune. */
    public void track(UUID player, int slot) {
        database.execute("UPDATE " + database.table(DAILY) + " SET tracked = (slot = ?) WHERE uuid = ?", slot, player);
    }

    /**
     * Marque la quête faite, seulement si elle ne l'était pas et que l'action est accomplie : deux validations
     * simultanées ne paient qu'une fois.
     */
    public boolean complete(UUID player, int slot, long day, int target) {
        return database.execute("UPDATE " + database.table(DAILY) + " SET done = TRUE, tracked = FALSE"
                + " WHERE uuid = ? AND slot = ? AND day = ? AND done = FALSE AND progress >= ?", player, slot, day, target) > 0;
    }

    private static Template toTemplate(Row row) {
        return new Template(row.getLong("id"), row.getString("job"), Tier.of(row.getString("tier")),
                Objective.parse(row.getString("objectives")), row.getDouble("reward"));
    }
}
