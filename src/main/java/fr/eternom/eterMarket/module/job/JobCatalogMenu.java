package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.job.JobGui.CatalogView;
import fr.eternom.eterMarket.module.job.JobRepository.Template;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Éditeur du répertoire de quêtes d'un métier (staff, cadre rouge), 6 lignes : les quêtes possibles, triées par niveau
 * (28 par page). Clic gauche = niveau, récompense et quantités ; Maj + clic = ajouter l'objet en main à la quête
 * (commande à plusieurs objets) ; clic droit = retirer. En bas : pages, nouvelle quête de livraison (objet en main),
 * nouvelle quête d'action, retour à l'éditeur de la boutique.
 * Avertit si on peut acheter en boutique tous les objets d'une quête pour moins que sa récompense (on achèterait pour
 * livrer : de l'argent créé à l'infini).
 */
class JobCatalogMenu implements Menu {

    private static final int INFO = 4;
    private static final int PREVIOUS = 45;
    private static final int ADD = 47;
    private static final int BACK = 49;
    private static final int ADD_ACTION = 51;
    private static final int NEXT = 53;
    private static final int PER_PAGE = 28;

    private final JobGui gui;
    private final Messages messages;
    private final Player viewer;
    private final CatalogView view;
    private final int page;
    private final Inventory inventory;
    private final Map<Integer, Template> templateAtSlot = new HashMap<>();

    JobCatalogMenu(JobGui gui, Player viewer, CatalogView view) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.view = view;
        int pages = Math.max(1, (view.templates().size() + PER_PAGE - 1) / PER_PAGE);
        this.page = Math.clamp(view.page(), 0, pages - 1);
        this.inventory = Bukkit.createInventory(this, 54, text("catalog.title", "job", gui.service().jobName(viewer, view.npc().job())));
        render(pages);
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Template template = templateAtSlot.get(slot);
        if (template != null) {
            Sounds.click(player);
            if (click.isShiftClick()) {
                gui.addHeldObjective(player, view.npc(), page, template);
            } else if (click.isRightClick()) {
                gui.confirmRemoveTemplate(player, view.npc(), page, template);
            } else {
                gui.editTemplate(player, view.npc(), page, template);
            }
        } else if (slot == ADD) {
            Sounds.click(player);
            gui.addHeldTemplate(player, view.npc(), page);
        } else if (slot == ADD_ACTION) {
            Sounds.click(player);
            gui.addActionTemplate(player, view.npc(), page);
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.backToShopEditor(player, view.npc());
        } else if (slot == PREVIOUS && page > 0) {
            Sounds.page(player);
            gui.openCatalog(player, view.npc(), page - 1);
        } else if (slot == NEXT && (page + 1) * PER_PAGE < view.templates().size()) {
            Sounds.page(player);
            gui.openCatalog(player, view.npc(), page + 1);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render(int pages) {
        Frame.draw(inventory, Material.RED_STAINED_GLASS_PANE);
        List<Component> info = new ArrayList<>();
        info.add(text("catalog.info-count", "count", String.valueOf(view.templates().size())));
        for (Tier tier : Tier.values()) {
            long count = view.templates().stream().filter(t -> t.tier() == tier).count();
            info.add(text("catalog.info-tier", "count", String.valueOf(count)).append(gui.texts().tier(viewer, tier)));
        }
        if (pages > 1) {
            info.add(text("catalog.info-page", "page", String.valueOf(page + 1), "pages", String.valueOf(pages)));
        }
        inventory.setItem(INFO, Items.item(gui.service().jobs().icon(view.npc().job()),
                text("catalog.info", "job", gui.service().jobName(viewer, view.npc().job())), info));
        List<Template> shown = view.templates().stream().skip((long) page * PER_PAGE).limit(PER_PAGE).toList();
        for (int i = 0; i < shown.size(); i++) {
            int slot = 10 + i / 7 * 9 + i % 7; // 4 lignes de 7 à l'intérieur du cadre
            templateAtSlot.put(slot, shown.get(i));
            inventory.setItem(slot, item(shown.get(i)));
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS, Items.item(Material.ARROW, text("catalog.previous"), List.of()));
        }
        if ((page + 1) * PER_PAGE < view.templates().size()) {
            inventory.setItem(NEXT, Items.item(Material.ARROW, text("catalog.next"), List.of()));
        }
        inventory.setItem(ADD, Items.item(Material.LIME_DYE, text("catalog.add"), List.of(text("catalog.add-lore"))));
        inventory.setItem(ADD_ACTION, Items.item(Material.IRON_SWORD, text("catalog.add-action"), List.of(text("catalog.add-action-lore"))));
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("catalog.back"), List.of()));
    }

    private ItemStack item(Template template) {
        List<Component> lore = new ArrayList<>();
        lore.add(text("catalog.item.tier").append(gui.texts().tier(viewer, template.tier())));
        for (Objective objective : template.objectives()) {
            lore.add(text("quest.item.objective").append(gui.texts().label(viewer, objective))
                    .append(text("catalog.item.amount", "amount", String.valueOf(objective.amount()))));
        }
        lore.add(text("catalog.item.reward", "reward", Money.format(template.reward())));
        double cost = shopCost(template);
        if (cost >= 0 && cost <= template.reward()) {
            lore.add(text("catalog.item.arbitrage", "price", Money.format(cost)));
        }
        lore.add(Component.empty());
        lore.add(text("catalog.item.edit"));
        lore.add(text("catalog.item.add-objective"));
        lore.add(text("catalog.item.remove"));
        return Items.item(template.objectives().getFirst().icon(),
                gui.texts().name(viewer, new JobRepository.Quest(0, 0, template.tier(), template.objectives(), template.reward(), List.of(), false, false)),
                lore);
    }

    /** Prix pour tout acheter en boutique (au moins cher) ; -1 si une action ou un objet introuvable en boutique. */
    private double shopCost(Template template) {
        double cost = 0;
        for (Objective objective : template.objectives()) {
            Double unit = objective.kind() == Objective.Kind.ITEM ? view.cheapestShopPrice().get(objective.material()) : null;
            if (unit == null) {
                return -1;
            }
            cost += unit * objective.amount();
        }
        return cost;
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
