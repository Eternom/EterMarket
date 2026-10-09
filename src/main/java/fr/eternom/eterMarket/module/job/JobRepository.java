package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Données de la guilde des métiers, communes à tout le réseau. Appels bloquants : hors du thread principal.
 * - etermarket_job_members : le métier de chaque joueur, la date de son dernier changement, ses quêtes accomplies
 *   (toutes, pour de futurs niveaux) et le dernier jour où il a changé une quête ;
 * - etermarket_job_templates : le répertoire des quêtes possibles de chaque métier (niveau, objectifs, récompense) ;
 * - etermarket_job_daily : les quêtes du jour de chaque joueur (une ligne par emplacement 0 à 3, le 3 étant le bonus),
 *   recopiées depuis le répertoire (le modifier ne change pas les quêtes déjà tirées), avec la progression des actions
 *   (une colonne par action, MAX_ACTIONS) et la quête suivie dans la sidebar.
 * Les tables de la 1.2 (job_catalog, job_quests : un seul objet par quête) sont supprimées au démarrage.
 */
public class JobRepository {

    private static final String MEMBERS = "job_members";
    private static final String TEMPLATES = "job_templates";
    private static final String DAILY = "job_daily";
    /** Métiers dont le répertoire de départ a déjà été posé (une seule fois sur tout le réseau). */
    private static final String SEEDED = "job_seeded";
    /** Actions (tuer, casser, pêcher) au plus par quête : un compteur en base pour chacune. */
    public static final int MAX_ACTIONS = 3;
    /** Compteur de chaque action, dans l'ordre (progress : nom d'avant 1.3.6, quand une quête avait une seule action). */
    private static final List<String> PROGRESS = List.of("progress", "progress_2", "progress_3");

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

    /**
     * Une quête du jour d'un joueur. day : jour julien du tirage ; slot 3 = quête bonus ; progress : un compteur par
     * action (tuer, casser, pêcher), dans l'ordre de actions().
     */
    public record Quest(int slot, long day, Tier tier, List<Objective> objectives, double reward, List<Integer> progress,
                        boolean done, boolean tracked) {

        /** Les objectifs d'action, au plus MAX_ACTIONS (un compteur en base pour chacun). */
        public List<Objective> actions() {
            return objectives.stream().filter(o -> o.kind().isAction()).limit(MAX_ACTIONS).toList();
        }

        public List<Objective> items() {
            return objectives.stream().filter(o -> o.kind() == Objective.Kind.ITEM).toList();
        }

        /** Progression de l'action numéro action (0 si elle n'existe pas). */
        public int progress(int action) {
            return action < progress.size() ? progress.get(action) : 0;
        }

        /** Progression de cet objectif d'action (0 pour un objet ou un objectif inconnu). */
        public int progressOf(Objective objective) {
            int action = actions().indexOf(objective);
            return action < 0 ? 0 : progress(action);
        }

        /** Quantité à atteindre pour chaque compteur (0 pour un compteur inutilisé). */
        public List<Integer> targets() {
            List<Objective> actions = actions();
            return IntStream.range(0, MAX_ACTIONS).mapToObj(i -> i < actions.size() ? actions.get(i).amount() : 0).toList();
        }

        public boolean actionComplete() {
            List<Objective> actions = actions();
            return IntStream.range(0, actions.size()).allMatch(i -> progress(i) >= actions.get(i).amount());
        }

        public Quest withProgress(int action, int value) {
            List<Integer> next = new ArrayList<>(IntStream.range(0, MAX_ACTIONS).mapToObj(this::progress).toList());
            next.set(action, value);
            return new Quest(slot, day, tier, objectives, reward, List.copyOf(next), done, tracked);
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
                Column.of("changed_at", Column.Type.LONG).notNull(),
                Column.of("completed", Column.Type.INT),
                Column.of("reroll_day", Column.Type.LONG));
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
                Column.of("progress_2", Column.Type.INT),
                Column.of("progress_3", Column.Type.INT),
                Column.of("done", Column.Type.BOOLEAN).notNull(),
                Column.of("tracked", Column.Type.BOOLEAN).notNull());
        database.createTable(SEEDED, Column.of("job", Column.Type.STRING).length(32).primaryKey());
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

    /**
     * Répertoire de départ, posé UNE seule fois par métier sur tout le réseau : le serveur qui inscrit le métier dans
     * job_seeded (INSERT IGNORE, atomique) est le seul à le poser, même si plusieurs serveurs démarrent ensemble. Un
     * métier vidé exprès en jeu le reste. Un métier qui a déjà des quêtes (d'avant 1.3.6) est seulement inscrit.
     */
    public void seedOnce(String job, List<Template> defaults) {
        String insert = "INSERT IGNORE INTO " + database.table(SEEDED) + " (job) VALUES (?)";
        if (!catalog(job).isEmpty()) {
            database.execute(insert, job);
        } else if (database.execute(insert, job) > 0) {
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
                        Objective.parse(row.getString("objectives")), row.getDouble("reward"),
                        PROGRESS.stream().map(row::getInt).toList(),
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
        Map<String, Object> values = new HashMap<>(Map.of("uuid", player, "slot", quest.slot(), "day", quest.day(),
                "tier", quest.tier().id(), "objectives", Objective.format(quest.objectives()), "reward", quest.reward(),
                "done", quest.done(), "tracked", quest.tracked()));
        for (int action = 0; action < MAX_ACTIONS; action++) {
            values.put(PROGRESS.get(action), quest.progress(action));
        }
        database.set(DAILY, values, "uuid", "slot");
    }

    /** Remplace une quête pas encore faite par une autre (changement de quête) : false si elle a été faite entre-temps. */
    public boolean swapQuest(UUID player, Quest replacement) {
        return database.execute("UPDATE " + database.table(DAILY) + " SET tier = ?, objectives = ?, reward = ?,"
                        + " progress = 0, progress_2 = 0, progress_3 = 0"
                        + " WHERE uuid = ? AND slot = ? AND day = ? AND done = FALSE",
                replacement.tier().id(), Objective.format(replacement.objectives()), replacement.reward(),
                player, replacement.slot(), replacement.day()) > 0;
    }

    /**
     * Ajoute de la progression à l'action numéro action (sans dépasser target) ; ignoré si la quête est faite, d'un
     * autre jour ou changée.
     */
    public void addProgress(UUID player, int slot, long day, String objectives, int action, int delta, int target) {
        String column = PROGRESS.get(action);
        database.execute("UPDATE " + database.table(DAILY) + " SET " + column + " = LEAST(?, COALESCE(" + column + ", 0) + ?)"
                + " WHERE uuid = ? AND slot = ? AND day = ? AND objectives = ? AND done = FALSE", target, delta, player, slot, day, objectives);
    }

    /** La quête suivie dans la sidebar (une seule) ; slot -1 = aucune. */
    public void track(UUID player, int slot) {
        database.execute("UPDATE " + database.table(DAILY) + " SET tracked = (slot = ?) WHERE uuid = ?", slot, player);
    }

    /**
     * Marque la quête faite, seulement si elle ne l'était pas et que chaque action est accomplie (targets : un par
     * compteur) : deux validations simultanées ne paient qu'une fois.
     */
    public boolean complete(UUID player, int slot, long day, List<Integer> targets) {
        return database.execute("UPDATE " + database.table(DAILY) + " SET done = TRUE, tracked = FALSE"
                        + " WHERE uuid = ? AND slot = ? AND day = ? AND done = FALSE AND progress >= ?"
                        + " AND COALESCE(progress_2, 0) >= ? AND COALESCE(progress_3, 0) >= ?",
                player, slot, day, targets.get(0), targets.get(1), targets.get(2)) > 0;
    }

    private static Template toTemplate(Row row) {
        return new Template(row.getLong("id"), row.getString("job"), Tier.of(row.getString("tier")),
                Objective.parse(row.getString("objectives")), row.getDouble("reward"));
    }
}
