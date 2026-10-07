package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.helper.Inputs;
import fr.eternom.eterMarket.module.job.JobRepository.Quest;
import fr.eternom.eterMarket.module.job.JobRepository.Template;
import fr.eternom.eterMarket.module.job.JobService.Board;
import fr.eternom.eterMarket.module.npc.NpcRepository.Npc;
import fr.eternom.eterMarket.module.shop.ShopRepository;
import fr.eternom.eterMarket.module.shop.ShopRepository.ShopItem;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Menus du PNJ d'un métier : rejoindre la guilde, quêtes du jour (valider, suivre dans la sidebar, changer une quête,
 * lien vers la boutique du métier), et l'éditeur du répertoire de quêtes pour le staff. Thread principal ; données lues
 * en tâche de fond avant l'ouverture.
 */
public class JobGui {

    /** Répertoire d'un métier, avec pour chaque matière le prix unitaire le plus bas en boutique (contrôle d'arbitrage). */
    record CatalogView(Npc npc, List<Template> templates, Map<Material, Double> cheapestShopPrice, int page) {
    }

    private final JavaPlugin plugin;
    private final JobService service;
    private final JobRepository repository;
    private final ShopRepository shops;
    private final Messages messages;
    private final BackButton backButton;
    /** Ouvre la boutique (ou son éditeur) d'un PNJ : branché par ShopGui, qui vit dans un autre module. */
    private BiConsumer<Player, Npc> shopOpener = (player, npc) -> { };
    private BiConsumer<Player, Npc> shopEditorOpener = (player, npc) -> { };

    public JobGui(JavaPlugin plugin, JobService service, JobRepository repository, ShopRepository shops, Messages messages,
                  BackButton backButton) {
        this.plugin = plugin;
        this.service = service;
        this.repository = repository;
        this.shops = shops;
        this.messages = messages;
        this.backButton = backButton;
    }

    public void linkShops(BiConsumer<Player, Npc> shopOpener, BiConsumer<Player, Npc> shopEditorOpener) {
        this.shopOpener = shopOpener;
        this.shopEditorOpener = shopEditorOpener;
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

    // ---------- Éditeur du répertoire ----------

    public void openCatalog(Player admin, Npc npc) {
        openCatalog(admin, npc, 0);
    }

    void openCatalog(Player admin, Npc npc, int page) {
        if (npc.job() == null) {
            return;
        }
        Tasks.async(plugin, admin, () -> {
            Map<Material, Double> cheapest = new HashMap<>();
            for (ShopItem item : shops.allItems()) {
                if (item.stockable()) {
                    cheapest.merge(item.item().getType(), item.price() / item.item().getAmount(), Math::min);
                }
            }
            List<Template> templates = new ArrayList<>(repository.catalog(npc.job()));
            templates.sort((a, b) -> a.tier() != b.tier() ? a.tier().compareTo(b.tier()) : Long.compare(a.id(), b.id()));
            return new CatalogView(npc, templates, cheapest, page);
        }, view -> admin.openInventory(new JobCatalogMenu(this, admin, view).getInventory()),
                () -> messages.send(admin, "error.generic"));
    }

    void backToShopEditor(Player admin, Npc npc) {
        shopEditorOpener.accept(admin, npc);
    }

    /** Nouvelle quête de livraison : l'objet en main, la quantité tenue. */
    void addHeldTemplate(Player admin, Npc npc, int page) {
        ItemStack held = admin.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            messages.send(admin, "editor.hold-item");
            return;
        }
        List<Objective> objectives = List.of(Objective.item(held.getType(), held.getAmount()));
        askTemplate(admin, npc, page, ItemStack.of(held.getType()), objectives, Tier.EASY, 0,
                (tier, edited, reward) -> save(admin, npc, page, () -> repository.addTemplate(npc.job(), tier, edited, reward)));
    }

    /** Ajoute l'objet en main à une quête existante (commande à plusieurs objets). */
    void addHeldObjective(Player admin, Npc npc, int page, Template template) {
        ItemStack held = admin.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            messages.send(admin, "editor.hold-item");
            return;
        }
        List<Objective> objectives = new ArrayList<>(template.objectives());
        objectives.add(Objective.item(held.getType(), held.getAmount()));
        List<Objective> merged = Objective.merge(objectives);
        save(admin, npc, page, () -> repository.updateTemplate(template.id(), template.tier(), merged, template.reward()));
        messages.send(admin, "catalog.objective-added");
    }

    /** Nouvelle quête d'action : tuer, casser ou pêcher. */
    void addActionTemplate(Player admin, Npc npc, int page) {
        admin.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(admin, "catalog.action.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(admin, "catalog.action.body"))))
                .inputs(List.of(
                        DialogInput.singleOption("kind", messages.get(admin, "catalog.action.kind"), List.of(
                                SingleOptionDialogInput.OptionEntry.create("kill", messages.get(admin, "catalog.action.kill"), true),
                                SingleOptionDialogInput.OptionEntry.create("break", messages.get(admin, "catalog.action.break"), false),
                                SingleOptionDialogInput.OptionEntry.create("fish", messages.get(admin, "catalog.action.fish"), false))).build(),
                        DialogInput.text("target", messages.get(admin, "catalog.action.target")).maxLength(64).build(),
                        DialogInput.text("amount", messages.get(admin, "catalog.edit.amount")).maxLength(6).build(),
                        tierInput(admin, Tier.EASY),
                        DialogInput.text("reward", messages.get(admin, "catalog.edit.reward")).maxLength(12).build()))
                .build();
        Dialogs.show(plugin, admin, base, messages.get(admin, "editor.save"), messages.get(admin, "dialog.cancel"), response -> {
            Objective.Kind kind = kind(response.getText("kind"));
            if (kind == null) {
                messages.send(admin, "catalog.action.invalid");
                openCatalog(admin, npc, page);
                return;
            }
            String target = String.valueOf(response.getText("target")).trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
            Objective objective = new Objective(kind, target.isEmpty() ? Objective.ANY : target, (int) Inputs.number(response.getText("amount")));
            Tier tier = Tier.of(response.getText("tier"));
            double reward = Inputs.number(response.getText("reward"));
            if (!objective.valid() || (kind == Objective.Kind.BREAK && target.isEmpty()) || tier == null || reward <= 0) {
                messages.send(admin, "catalog.action.invalid");
                openCatalog(admin, npc, page);
                return;
            }
            save(admin, npc, page, () -> repository.addTemplate(npc.job(), tier, List.of(objective), reward));
        }, () -> openCatalog(admin, npc, page));
    }

    void editTemplate(Player admin, Npc npc, int page, Template template) {
        askTemplate(admin, npc, page, ItemStack.of(template.objectives().getFirst().icon()), template.objectives(),
                template.tier(), template.reward(),
                (tier, objectives, reward) -> save(admin, npc, page, () -> repository.updateTemplate(template.id(), tier, objectives, reward)));
    }

    void confirmRemoveTemplate(Player admin, Npc npc, int page, Template template) {
        admin.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(admin, "catalog.remove.title"))
                .body(List.of(DialogBody.item(ItemStack.of(template.objectives().getFirst().icon())).build(),
                        DialogBody.plainMessage(messages.get(admin, "catalog.remove.body"))))
                .build();
        Dialogs.show(plugin, admin, base, messages.get(admin, "editor.remove.confirm"), messages.get(admin, "dialog.cancel"),
                response -> save(admin, npc, page, () -> repository.removeTemplate(template.id())),
                () -> openCatalog(admin, npc, page));
    }

    private interface TemplateEdit {
        void accept(Tier tier, List<Objective> objectives, double reward);
    }

    /** Dialog : niveau, récompense, et la quantité de chaque objectif (0 = retirer l'objectif). */
    private void askTemplate(Player admin, Npc npc, int page, ItemStack icon, List<Objective> objectives, Tier tier,
                             double reward, TemplateEdit then) {
        admin.closeInventory();
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(tierInput(admin, tier));
        inputs.add(DialogInput.text("reward", messages.get(admin, "catalog.edit.reward"))
                .initial(reward > 0 ? String.valueOf((long) reward) : "").maxLength(12).build());
        for (int i = 0; i < objectives.size(); i++) {
            inputs.add(DialogInput.text("amount" + i, service.texts().label(admin, objectives.get(i)))
                    .initial(String.valueOf(objectives.get(i).amount())).maxLength(6).build());
        }
        DialogBase base = DialogBase.builder(messages.get(admin, "catalog.edit.title"))
                .body(List.of(DialogBody.item(icon).build(), DialogBody.plainMessage(messages.get(admin, "catalog.edit.body"))))
                .inputs(inputs)
                .build();
        Dialogs.show(plugin, admin, base, messages.get(admin, "editor.save"), messages.get(admin, "dialog.cancel"), response -> {
            Tier newTier = Tier.of(response.getText("tier"));
            double newReward = Inputs.number(response.getText("reward"));
            List<Objective> edited = new ArrayList<>();
            for (int i = 0; i < objectives.size(); i++) {
                int amount = (int) Inputs.number(response.getText("amount" + i));
                if (amount > 0) {
                    edited.add(new Objective(objectives.get(i).kind(), objectives.get(i).target(), amount));
                }
            }
            if (newTier == null || newReward <= 0 || edited.isEmpty()) {
                messages.send(admin, "catalog.invalid");
                openCatalog(admin, npc, page);
                return;
            }
            then.accept(newTier, edited, newReward);
        }, () -> openCatalog(admin, npc, page));
    }

    private DialogInput tierInput(Player admin, Tier selected) {
        return DialogInput.singleOption("tier", messages.get(admin, "catalog.edit.tier"), Arrays.stream(Tier.values())
                .map(tier -> SingleOptionDialogInput.OptionEntry.create(tier.id(), service.texts().tier(admin, tier), tier == selected))
                .toList()).build();
    }

    /** Écrit en tâche de fond, puis rouvre le répertoire. */
    private void save(Player admin, Npc npc, int page, Runnable write) {
        Tasks.async(plugin, admin, () -> {
            write.run();
            return true;
        }, saved -> openCatalog(admin, npc, page), () -> messages.send(admin, "error.generic"));
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

    private static Objective.Kind kind(String id) {
        try {
            return id == null ? null : Objective.Kind.valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
