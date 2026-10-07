package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.module.job.JobRepository.Quest;
import fr.eternom.eterMarket.module.job.JobRepository.Template;
import fr.eternom.eterMarket.module.job.JobService.Board;
import fr.eternom.eterMarket.module.npc.NpcRepository.Npc;
import fr.eternom.eterMarket.module.shop.ShopRepository;
import fr.eternom.eterMarket.module.shop.ShopRepository.ShopItem;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Menus du PNJ d'un métier : rejoindre la guilde, quêtes du jour (et lien vers la boutique du métier), et l'éditeur du
 * répertoire de quêtes pour le staff. Thread principal ; données lues en tâche de fond avant l'ouverture.
 */
public class JobGui {

    /** Répertoire d'un métier, avec pour chaque matière le prix unitaire le plus bas en boutique (contrôle d'arbitrage). */
    record CatalogView(Npc npc, List<Template> templates, Map<Material, Double> cheapestShopPrice) {
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
                case Board.Quests quests -> player.openInventory(new JobQuestMenu(this, player, npc, quests).getInventory());
                case Board.Join join -> player.openInventory(new JobJoinMenu(this, player, npc, join).getInventory());
            }
        }, () -> messages.send(player, "error.generic"));
    }

    void openShop(Player player, Npc npc) {
        shopOpener.accept(player, npc);
    }

    void deliver(Player player, Npc npc, Quest quest) {
        service.deliver(player, quest, () -> open(player, npc));
    }

    void confirmJoin(Player player, Npc npc, Board.Join join) {
        player.closeInventory();
        String job = service.jobName(player, join.job());
        DialogBase base = DialogBase.builder(messages.get(player, "job.join.title", "job", job))
                .body(List.of(DialogBody.plainMessage(join.current().isEmpty()
                        ? messages.get(player, "job.join.body-first", "job", job)
                        : messages.get(player, "job.join.body-change", "job", job,
                        "current", service.jobName(player, join.current().get().job()), "price", money(service.jobs().changeCost())))))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "job.join.confirm"), messages.get(player, "dialog.cancel"),
                response -> service.join(player, join, () -> open(player, npc)),
                () -> open(player, npc));
    }

    // ---------- Éditeur du répertoire ----------

    public void openCatalog(Player admin, Npc npc) {
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
            return new CatalogView(npc, repository.catalog(npc.job()), cheapest);
        }, view -> admin.openInventory(new JobCatalogMenu(this, admin, view).getInventory()),
                () -> messages.send(admin, "error.generic"));
    }

    void backToShopEditor(Player admin, Npc npc) {
        shopEditorOpener.accept(admin, npc);
    }

    void addHeldTemplate(Player admin, Npc npc) {
        ItemStack held = admin.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            messages.send(admin, "editor.hold-item");
            return;
        }
        Material material = held.getType();
        askTemplate(admin, npc, material, held.getAmount(), 0, (amount, reward) -> Tasks.async(plugin, admin, () -> {
            repository.addTemplate(npc.job(), material, amount, reward);
            return true;
        }, added -> openCatalog(admin, npc), () -> messages.send(admin, "error.generic")));
    }

    void editTemplate(Player admin, Npc npc, Template template) {
        askTemplate(admin, npc, template.material(), template.amount(), template.reward(),
                (amount, reward) -> Tasks.async(plugin, admin, () -> {
                    repository.updateTemplate(template.id(), amount, reward);
                    return true;
                }, changed -> openCatalog(admin, npc), () -> messages.send(admin, "error.generic")));
    }

    void confirmRemoveTemplate(Player admin, Npc npc, Template template) {
        admin.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(admin, "catalog.remove.title"))
                .body(List.of(DialogBody.item(ItemStack.of(template.material())).build(),
                        DialogBody.plainMessage(messages.get(admin, "catalog.remove.body"))))
                .build();
        Dialogs.show(plugin, admin, base, messages.get(admin, "editor.remove.confirm"), messages.get(admin, "dialog.cancel"),
                response -> Tasks.async(plugin, admin, () -> {
                    repository.removeTemplate(template.id());
                    return true;
                }, removed -> openCatalog(admin, npc), () -> messages.send(admin, "error.generic")),
                () -> openCatalog(admin, npc));
    }

    /** Dialog : quantité à livrer et récompense. */
    private void askTemplate(Player admin, Npc npc, Material material, int amount, double reward, BiConsumer<Integer, Double> then) {
        admin.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(admin, "catalog.edit.title"))
                .body(List.of(DialogBody.item(ItemStack.of(material)).build(),
                        DialogBody.plainMessage(messages.get(admin, "catalog.edit.body"))))
                .inputs(List.of(
                        DialogInput.text("amount", messages.get(admin, "catalog.edit.amount")).initial(String.valueOf(amount))
                                .maxLength(6).build(),
                        DialogInput.text("reward", messages.get(admin, "catalog.edit.reward"))
                                .initial(reward > 0 ? String.valueOf((long) reward) : "").maxLength(12).build()))
                .build();
        Dialogs.show(plugin, admin, base, messages.get(admin, "editor.save"), messages.get(admin, "dialog.cancel"), response -> {
            int newAmount = (int) parse(response.getText("amount"));
            double newReward = parse(response.getText("reward"));
            if (newAmount < 1 || newReward <= 0) {
                messages.send(admin, "catalog.invalid");
                openCatalog(admin, npc);
                return;
            }
            then.accept(newAmount, newReward);
        }, () -> openCatalog(admin, npc));
    }

    // ---------- Outils ----------

    String money(double amount) {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? String.valueOf(amount) : provider.getProvider().format(amount);
    }

    String timeUntilTomorrow(Player viewer) {
        return EterLib.get().formatDuration(viewer, service.secondsUntilTomorrow());
    }

    JobService service() {
        return service;
    }

    Messages messages() {
        return messages;
    }

    BackButton backButton() {
        return backButton;
    }

    private static double parse(String text) {
        try {
            double value = text == null ? 0 : Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(value) ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
