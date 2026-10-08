package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.module.job.JobRepository.Quest;
import fr.eternom.eterMarket.module.job.JobService.Board;
import fr.eternom.eterMarket.module.npc.NpcRepository.Npc;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Menus du PNJ d'un métier : rejoindre la guilde, quêtes du jour (valider, suivre dans la sidebar, changer une quête,
 * lien vers la boutique du métier). Thread principal ; données lues
 * en tâche de fond avant l'ouverture.
 */
public class JobGui {

    private final JavaPlugin plugin;
    private final JobService service;
    private final JobRepository repository;
    private final Messages messages;
    private final BackButton backButton;
    /** Ouvre la boutique d'un PNJ : branché par ShopGui, qui vit dans un autre module. */
    private BiConsumer<Player, Npc> shopOpener = (player, npc) -> { };

    public JobGui(JavaPlugin plugin, JobService service, JobRepository repository, Messages messages,
                  BackButton backButton) {
        this.plugin = plugin;
        this.service = service;
        this.repository = repository;
        this.messages = messages;
        this.backButton = backButton;
    }

    public void linkShops(BiConsumer<Player, Npc> shopOpener) {
        this.shopOpener = shopOpener;
    }

    /** Clic sur le PNJ du métier : ses quêtes si le joueur en est membre, sinon de quoi rejoindre la guilde. */
    public void open(Player player, Npc npc) {
        if (npc.job() == null || !service.jobs().exists(npc.job())) {
            messages.send(player, "job.unknown", "job", String.valueOf(npc.job()));
            return;
        }
        Tasks.async(plugin, player, () -> service.board(player.getUniqueId(), npc.job()), board -> {
            switch (board) {
                case Board.Quests quests -> {
                    service.progress().update(player, quests.job(), quests.quests());
                    showQuests(player, npc, quests);
                }
                case Board.Join join -> player.openInventory(new JobJoinMenu(this, player, npc, join).getInventory());
            }
        }, () -> messages.send(player, "error.generic"));
    }

    /** Les quêtes telles que ce serveur les connaît (progression des actions à jour). */
    private void showQuests(Player player, Npc npc, Board.Quests board) {
        List<Quest> current = board.quests().stream().map(quest -> service.progress().current(player.getUniqueId(), quest)).toList();
        Board.Quests shown = new Board.Quests(board.job(), current, board.completed(), board.canReroll());
        player.openInventory(new JobQuestMenu(this, player, npc, shown).getInventory());
    }

    void openShop(Player player, Npc npc) {
        shopOpener.accept(player, npc);
    }

    void deliver(Player player, Npc npc, Quest quest) {
        service.deliver(player, quest, () -> open(player, npc));
    }

    /** Suivre la quête dans la sidebar (ou arrêter) ; le menu reste ouvert, mis à jour. */
    void toggleTrack(Player player, Npc npc, Board.Quests board, Quest quest) {
        service.progress().toggleTrack(player, service.progress().current(player.getUniqueId(), quest));
        showQuests(player, npc, board);
    }

    void confirmReroll(Player player, Npc npc, Board.Quests board, Quest quest) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "quest.reroll.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "quest.reroll.body",
                        "price", service.jobs().rerollCost() > 0 ? Money.format(service.jobs().rerollCost()) : messages.plain(player, "quest.reroll.free")))))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "quest.reroll.confirm"), messages.get(player, "dialog.cancel"),
                response -> service.reroll(player, board.job(), quest, () -> open(player, npc)),
                () -> open(player, npc));
    }

    void confirmJoin(Player player, Npc npc, Board.Join join) {
        player.closeInventory();
        String job = service.jobName(player, join.job());
        DialogBase base = DialogBase.builder(messages.get(player, "job.join.title", "job", job))
                .body(List.of(DialogBody.plainMessage(join.current().isEmpty()
                        ? messages.get(player, "job.join.body-first", "job", job)
                        : messages.get(player, "job.join.body-change", "job", job,
                        "current", service.jobName(player, join.current().get().job()), "price", Money.format(service.jobs().changeCost())))))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "job.join.confirm"), messages.get(player, "dialog.cancel"),
                response -> service.join(player, join, () -> open(player, npc)),
                () -> open(player, npc));
    }

    // ---------- Outils ----------

    String timeUntilTomorrow(Player viewer) {
        return EterLib.get().formatDuration(viewer, service.secondsUntilTomorrow());
    }

    JobService service() {
        return service;
    }

    QuestTexts texts() {
        return service.texts();
    }

    Messages messages() {
        return messages;
    }

    BackButton backButton() {
        return backButton;
    }

}
