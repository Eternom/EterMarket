package fr.eternom.eterMarket.module.job;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import fr.eternom.eterMarket.module.job.JobRepository.Member;
import fr.eternom.eterMarket.module.job.JobRepository.Quest;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gestion des métiers d'un joueur par le staff (/market jobs player <joueur>, etermarket.admin) : voir son métier, ses
 * quêtes et ses quêtes accomplies, et les ajuster (métier, délai de changement, quêtes du jour, changement de quête,
 * compteur, tout effacer). Marche hors ligne. Après chaque changement, le serveur où il joue relit ses données (message
 * job-refresh) : sa sidebar et le suivi de ses actions repartent de la base. Chaque action est notée dans la console.
 */
public class JobAdmin {

    private static final String BUS_REFRESH = "job-refresh";

    /** Ce que le menu montre d'un joueur. quests : celles en base, éventuellement d'un jour passé. */
    record Snapshot(UUID uuid, String name, String server, Optional<Member> member, List<Quest> quests, int rank) {
    }

    private final JavaPlugin plugin;
    private final JobService service;
    private final JobRepository repository;
    private final Messages messages;
    private final NetworkBus bus;

    public JobAdmin(JavaPlugin plugin, JobService service, JobRepository repository, Messages messages, NetworkBus bus) {
        this.plugin = plugin;
        this.service = service;
        this.repository = repository;
        this.messages = messages;
        this.bus = bus;
        bus.on(BUS_REFRESH, data -> reloadIfOnline(UUID.fromString(data.get("uuid").getAsString())));
    }

    /** Thread principal : ouvre le menu du joueur name (dernier joueur vu sous ce nom). */
    public void open(Player admin, String name) {
        Tasks.async(plugin, admin, () -> EterLib.get().getPlayers().find(name).map(this::snapshot), snapshot -> snapshot.ifPresentOrElse(
                found -> admin.openInventory(new JobAdminMenu(this, admin, found).getInventory()),
                () -> messages.send(admin, "player.unknown", "player", name)), () -> messages.send(admin, "error.generic"));
    }

    /** Thread principal : /market jobs cooldown <joueur>, sans menu (console comprise). */
    public void resetCooldown(CommandSender sender, String name) {
        Tasks.async(plugin, () -> {
            Optional<NetworkPlayer> player = EterLib.get().getPlayers().find(name);
            Optional<Member> member = player.flatMap(found -> repository.member(found.uuid()));
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isEmpty()) {
                    messages.send(sender, "player.unknown", "player", name);
                } else if (member.isEmpty()) {
                    messages.send(sender, "job-admin.no-job", "player", player.get().name());
                } else {
                    run(sender, player.get().uuid(), player.get().name(), "cooldown", () -> repository.resetCooldown(player.get().uuid()),
                            "job-admin.done-cooldown", () -> { });
                }
            });
        }, "Délai de changement de métier non remis à zéro : " + name);
    }

    // ---------- Actions du menu (thread principal) ----------

    void setJob(Player admin, Snapshot target, String job) {
        run(admin, target.uuid(), target.name(), "métier " + job, () -> repository.adminSetJob(target.uuid(), job),
                "job-admin.done-job", () -> reopen(admin, target), "job", service.jobName(admin, job));
    }

    void resetCooldown(Player admin, Snapshot target) {
        run(admin, target.uuid(), target.name(), "délai remis à zéro", () -> repository.resetCooldown(target.uuid()),
                "job-admin.done-cooldown", () -> reopen(admin, target));
    }

    void clearQuests(Player admin, Snapshot target) {
        run(admin, target.uuid(), target.name(), "quêtes du jour effacées", () -> repository.clearQuests(target.uuid()),
                "job-admin.done-quests", () -> reopen(admin, target));
    }

    void clearReroll(Player admin, Snapshot target) {
        run(admin, target.uuid(), target.name(), "changement de quête rendu", () -> repository.clearReroll(target.uuid()),
                "job-admin.done-reroll", () -> reopen(admin, target));
    }

    /** Ajuste le compteur de quêtes accomplies (sans message : on clique plusieurs fois de suite). */
    void addCompleted(Player admin, Snapshot target, int delta) {
        int next = Math.max(0, target.member().map(Member::completed).orElse(0) + delta);
        run(admin, target.uuid(), target.name(), "quêtes accomplies " + next, () -> repository.setCompleted(target.uuid(), next),
                null, () -> reopen(admin, target));
    }

    void confirmRemove(Player admin, Snapshot target) {
        admin.closeInventory();
        int completed = target.member().map(Member::completed).orElse(0);
        DialogBase base = DialogBase.builder(messages.get(admin, "job-admin.remove.title", "player", target.name()))
                .body(List.of(DialogBody.plainMessage(messages.get(admin, "job-admin.remove.body", "count", String.valueOf(completed)))))
                .build();
        Dialogs.show(plugin, admin, base, messages.get(admin, "job-admin.remove.confirm"), messages.get(admin, "dialog.cancel"),
                response -> run(admin, target.uuid(), target.name(), "métier retiré (" + completed + " quêtes accomplies effacées)",
                        () -> repository.removeMember(target.uuid()), "job-admin.done-remove", () -> reopen(admin, target)),
                () -> reopen(admin, target));
    }

    /** Relit le joueur en base et rouvre son menu. */
    void reopen(Player admin, Snapshot target) {
        Tasks.async(plugin, admin, () -> snapshot(target.uuid(), target.name(), target.server()),
                fresh -> admin.openInventory(new JobAdminMenu(this, admin, fresh).getInventory()),
                () -> messages.send(admin, "error.generic"));
    }

    JobService service() {
        return service;
    }

    Messages messages() {
        return messages;
    }

    // ---------- Outils ----------

    /**
     * Thread principal : change en tâche de fond, puis note l'action dans la console, prévient le serveur du joueur,
     * envoie doneKey à l'admin (s'il n'est pas null) et lance after.
     */
    private void run(CommandSender admin, UUID uuid, String name, String action, Runnable change, String doneKey, Runnable after,
                     String... placeholders) {
        Runnable done = () -> {
            plugin.getLogger().info("[Métiers] " + admin.getName() + " : " + action + " pour " + name);
            refresh(uuid);
            if (doneKey != null) {
                String[] all = new String[placeholders.length + 2];
                all[0] = "player";
                all[1] = name;
                System.arraycopy(placeholders, 0, all, 2, placeholders.length);
                messages.send(admin, doneKey, all);
            }
            after.run();
        };
        Tasks.async(plugin, () -> {
            change.run();
            Bukkit.getScheduler().runTask(plugin, done);
        }, "Action sur le métier de " + name + " non faite : " + action);
    }

    /** Bloquant : le joueur tel qu'en base (ses progrès en attente sur ce serveur écrits d'abord). */
    private Snapshot snapshot(NetworkPlayer player) {
        return snapshot(player.uuid(), player.name(), player.server());
    }

    private Snapshot snapshot(UUID uuid, String name, String server) {
        service.progress().flush(uuid);
        return new Snapshot(uuid, name, server, repository.member(uuid), repository.quests(uuid), repository.completedRank(uuid));
    }

    /** Thread principal : le serveur où il joue (celui-ci compris) relit son métier et ses quêtes. */
    private void refresh(UUID uuid) {
        reloadIfOnline(uuid);
        JsonObject data = new JsonObject();
        data.addProperty("uuid", uuid.toString());
        bus.publish(BUS_REFRESH, data);
    }

    private void reloadIfOnline(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            service.progress().reload(player);
        }
    }
}
