package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sidebar.SidebarOverrides;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.module.job.JobRepository.Quest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Les quêtes du jour des joueurs connectés : progression des actions (tuer, casser, pêcher) et quête suivie dans la
 * sidebar (dessinée par EterTab, via EterLib).
 *
 * L'état vit sur le thread principal. Les progrès partent en base par paquets (toutes les 30 s, à la déconnexion et
 * avant chaque validation) en s'AJOUTANT à ce qui y est : deux serveurs ne s'écrasent jamais. La clé d'un progrès
 * contient les objectifs de la quête : un progrès d'une quête changée entre-temps ne compte pas pour la nouvelle.
 */
public class JobProgress {

    private static final String SIDEBAR_OWNER = "EterMarket";
    private static final long FLUSH_TICKS = 30 * 20;
    private static final long JOB_TICKS = 60 * 20;
    private static final String JOB_TAG = "job";

    /** Un progrès pas encore écrit en base. */
    private record Key(UUID player, int slot, long day, String objectives, int target) {
    }

    /** Métier et quêtes du jour d'un joueur connecté. */
    private record Active(String job, List<Quest> quests) {
    }

    private final JavaPlugin plugin;
    private final JobRepository repository;
    private final JobService service;
    private final Messages messages;
    private final QuestTexts texts;
    private final Map<UUID, Active> active = new HashMap<>();
    private final Map<Key, Integer> pending = new ConcurrentHashMap<>();

    JobProgress(JavaPlugin plugin, JobRepository repository, JobService service, Messages messages, QuestTexts texts) {
        this.plugin = plugin;
        this.repository = repository;
        this.service = service;
        this.messages = messages;
        this.texts = texts;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll, FLUSH_TICKS, FLUSH_TICKS);
        // Changement de jour : le nombre de quêtes dispo de la sidebar repasse au plein
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::publishJob), JOB_TICKS, JOB_TICKS);
        Bukkit.getOnlinePlayers().forEach(this::join); // /reload
    }

    /** Arrêt du serveur : écrit les derniers progrès (bloquant, comme tout onDisable). */
    public void stop() {
        flushAll();
    }

    // ---------- Connexion ----------

    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> service.activeQuests(uuid),
                board -> board.ifPresentOrElse(quests -> update(player, quests.job(), quests.quests()), () -> publishJob(player)),
                () -> { });
    }

    public void quit(Player player) {
        UUID uuid = player.getUniqueId();
        active.remove(uuid);
        EterLib.get().getSidebars().clear(uuid, SIDEBAR_OWNER);
        Tasks.async(plugin, () -> flush(uuid), "Progression des quêtes non enregistrée");
    }

    /**
     * Thread principal, après lecture des quêtes en base : remplace l'état, en gardant les progrès pas encore écrits et
     * la quête suivie choisie ici (son écriture en base peut arriver après cette lecture).
     */
    void update(Player player, String job, List<Quest> quests) {
        UUID uuid = player.getUniqueId();
        Active previous = active.get(uuid);
        List<Quest> merged = new ArrayList<>();
        for (Quest quest : quests) {
            Integer waiting = pending.get(key(uuid, quest));
            Quest updated = waiting == null ? quest : quest.withProgress(Math.min(quest.target(), quest.progress() + waiting));
            if (previous != null && previous.job().equals(job)) {
                boolean tracked = previous.quests().stream().anyMatch(q -> q.tracked() && q.slot() == quest.slot() && q.day() == quest.day());
                updated = updated.withTracked(tracked && !updated.done());
            }
            merged.add(updated);
        }
        active.put(uuid, new Active(job, merged));
        refreshSidebar(player);
        publishJob(player);
    }

    /** Thread principal : la quête telle que ce serveur la connaît (progression à jour), sinon celle donnée. */
    Quest current(UUID player, Quest quest) {
        Active state = active.get(player);
        if (state == null) {
            return quest;
        }
        return state.quests().stream().filter(q -> q.slot() == quest.slot() && q.day() == quest.day()).findFirst().orElse(quest);
    }

    /** Thread principal : quête validée. */
    void markDone(Player player, Quest quest) {
        replace(player.getUniqueId(), quest.slot(), Quest::withDone);
        refreshSidebar(player);
        publishJob(player);
    }

    // ---------- Actions ----------

    /** Thread principal : le joueur a fait amount fois une action ; compte pour ses quêtes dont l'action correspond. */
    public void record(Player player, Objective.Kind kind, Predicate<Objective> matches, int amount) {
        Active state = active.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        long today = service.today();
        List<Quest> quests = state.quests();
        for (int i = 0; i < quests.size(); i++) {
            Quest quest = quests.get(i);
            Optional<Objective> action = quest.action();
            if (quest.done() || quest.day() != today || action.isEmpty() || action.get().kind() != kind
                    || quest.actionComplete() || !matches.test(action.get())) {
                continue;
            }
            int progress = Math.min(quest.target(), quest.progress() + amount);
            pending.merge(key(player.getUniqueId(), quest), progress - quest.progress(), Integer::sum);
            quests.set(i, quest.withProgress(progress));
            if (progress >= quest.target()) {
                messages.send(player, "quest.action-done");
                player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            } else {
                player.sendActionBar(texts.objective(player, action.get(), progress));
            }
        }
    }

    /** Bloquant : écrit les progrès en attente d'un joueur (avant de lire ou valider ses quêtes). */
    void flush(UUID player) {
        for (Key key : pending.keySet()) {
            if (key.player().equals(player)) {
                write(key);
            }
        }
    }

    private void flushAll() {
        pending.keySet().forEach(this::write);
    }

    private void write(Key key) {
        Integer delta = pending.remove(key);
        if (delta != null && delta > 0) {
            repository.addProgress(key.player(), key.slot(), key.day(), key.objectives(), delta, key.target());
        }
    }

    private static Key key(UUID player, Quest quest) {
        return new Key(player, quest.slot(), quest.day(), Objective.format(quest.objectives()), quest.target());
    }

    // ---------- Sidebar : métier ----------

    /**
     * Ligne du métier dans la sidebar d'EterTab, sous l'argent (étiquette <tag_job> d'EterLib), dans la langue du joueur :
     * son métier et ses quêtes encore dispo aujourd'hui, ou « aucun ». Nouveau jour : toutes les quêtes du jour sont
     * dispo ; quêtes du jour faites : la bonus compte tant qu'elle n'est pas faite.
     */
    private void publishJob(Player player) {
        Active state = active.get(player.getUniqueId());
        if (state == null) {
            EterLib.get().getPlayerTags().set(player, JOB_TAG, messages.raw(player, "job.sidebar-none"));
            return;
        }
        long today = service.today();
        List<Quest> todays = state.quests().stream().filter(q -> q.day() == today).toList();
        long available;
        if (todays.isEmpty()) {
            available = service.jobs().daily().size();
        } else {
            available = todays.stream().filter(q -> !q.done()).count();
            boolean dailyDone = todays.stream().filter(q -> q.slot() < Jobs.BONUS_SLOT).allMatch(Quest::done);
            boolean hasBonus = todays.stream().anyMatch(q -> q.slot() == Jobs.BONUS_SLOT);
            if (service.jobs().bonusQuest() && dailyDone && !hasBonus) {
                available++;
            }
        }
        String format = messages.raw(player, "job.sidebar");
        String value = format == null ? "" : format
                .replace("<job>", MiniMessage.miniMessage().escapeTags(service.jobName(player, state.job())))
                .replace("<count>", String.valueOf(available));
        EterLib.get().getPlayerTags().set(player, JOB_TAG, value);
    }

    // ---------- Sidebar ----------

    /** Thread principal : suit cette quête dans la sidebar, ou arrête de la suivre. */
    void toggleTrack(Player player, Quest quest) {
        UUID uuid = player.getUniqueId();
        boolean track = !quest.tracked();
        Active state = active.get(uuid);
        if (state != null) {
            state.quests().replaceAll(q -> q.withTracked(track && q.slot() == quest.slot()));
        }
        Tasks.async(plugin, () -> repository.track(uuid, track ? quest.slot() : -1), "Quête suivie non enregistrée");
        messages.send(player, track ? "quest.tracked" : "quest.untracked");
        refreshSidebar(player);
    }

    private void refreshSidebar(Player player) {
        if (tracked(player.getUniqueId()) != null) {
            EterLib.get().getSidebars().show(player, SIDEBAR_OWNER, this::sidebar);
        } else {
            EterLib.get().getSidebars().clear(player.getUniqueId(), SIDEBAR_OWNER);
        }
    }

    private Quest tracked(UUID player) {
        Active state = active.get(player);
        if (state == null) {
            return null;
        }
        long today = service.today();
        return state.quests().stream().filter(q -> q.tracked() && !q.done() && q.day() == today).findFirst().orElse(null);
    }

    /** Appelée par EterTab à chaque rafraîchissement : la quête suivie, en direct (inventaire et progression). */
    private SidebarOverrides.Content sidebar(Player player) {
        Quest quest = tracked(player.getUniqueId());
        if (quest == null) {
            return null; // nouveau jour : retour à la sidebar habituelle
        }
        Active state = active.get(player.getUniqueId());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(texts.name(player, quest));
        lines.add(Component.empty());
        for (Objective objective : quest.objectives()) {
            lines.add(texts.objective(player, objective, QuestTexts.have(player, quest, objective)));
        }
        lines.add(Component.empty());
        lines.add(messages.get(player, "quest.sidebar.reward", "reward", Money.format(quest.reward())));
        lines.add(messages.get(player, QuestTexts.ready(player, quest) ? "quest.sidebar.ready" : "quest.sidebar.todo"));
        return new SidebarOverrides.Content(
                messages.get(player, "quest.sidebar.title", "job", service.jobName(player, state.job())), lines);
    }

    private void replace(UUID player, int slot, UnaryOperator<Quest> change) {
        Active state = active.get(player);
        if (state != null) {
            state.quests().replaceAll(q -> q.slot() == slot ? change.apply(q) : q);
        }
    }
}
