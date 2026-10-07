package fr.eternom.eterMarket.module.job;

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
 * Éditeur du répertoire de quêtes d'un métier (staff, cadre rouge), 6 lignes : les quêtes possibles (jusqu'à 28) ;
 * clic gauche = quantité et récompense, clic droit = retirer ; en bas : ajouter l'objet en main, retour à l'éditeur de
 * la boutique. Avertit si une boutique vend la même matière moins cher que ce que la livraison rapporte (on achèterait
 * pour livrer : de l'argent créé à l'infini).
 */
class JobCatalogMenu implements Menu {

    private static final int INFO = 4;
    private static final int ADD = 47;
    private static final int BACK = 49;

    private final JobGui gui;
    private final Messages messages;
    private final Player viewer;
    private final CatalogView view;
    private final Inventory inventory;
    private final Map<Integer, Template> templateAtSlot = new HashMap<>();

    JobCatalogMenu(JobGui gui, Player viewer, CatalogView view) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.view = view;
        this.inventory = Bukkit.createInventory(this, 54, text("catalog.title", "job", gui.service().jobName(viewer, view.npc().job())));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Template template = templateAtSlot.get(slot);
        if (template != null) {
            Sounds.click(player);
            if (click.isRightClick()) {
                gui.confirmRemoveTemplate(player, view.npc(), template);
            } else {
                gui.editTemplate(player, view.npc(), template);
            }
        } else if (slot == ADD) {
            Sounds.click(player);
            gui.addHeldTemplate(player, view.npc());
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.backToShopEditor(player, view.npc());
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        ItemStack accent = Items.pane(Material.RED_STAINED_GLASS_PANE);
        ItemStack neutral = Items.pane(Material.GRAY_STAINED_GLASS_PANE);
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            boolean inside = slot / 9 >= 1 && slot / 9 <= 4 && slot % 9 >= 1 && slot % 9 <= 7;
            if (inside) {
                slots.add(slot);
            } else {
                inventory.setItem(slot, slot % 9 == 0 || slot % 9 == 8 ? accent : neutral);
            }
        }
        inventory.setItem(INFO, Items.item(gui.service().jobs().icon(view.npc().job()),
                text("catalog.info", "job", gui.service().jobName(viewer, view.npc().job())),
                List.of(text("catalog.info-count", "count", String.valueOf(view.templates().size())))));
        for (int i = 0; i < view.templates().size() && i < slots.size(); i++) {
            Template template = view.templates().get(i);
            templateAtSlot.put(slots.get(i), template);
            inventory.setItem(slots.get(i), item(template));
        }
        inventory.setItem(ADD, Items.item(Material.LIME_DYE, text("catalog.add"), List.of(text("catalog.add-lore"))));
        inventory.setItem(BACK, Items.item(Material.OAK_DOOR, text("catalog.back"), List.of()));
    }

    private ItemStack item(Template template) {
        double perUnit = template.reward() / template.amount();
        List<Component> lore = new ArrayList<>();
        lore.add(text("catalog.item.amount", "amount", String.valueOf(template.amount())));
        lore.add(text("catalog.item.reward", "reward", gui.money(template.reward()), "unit", String.format("%.2f", perUnit)));
        Double cheapest = view.cheapestShopPrice().get(template.material());
        if (cheapest != null && cheapest <= perUnit) {
            lore.add(text("catalog.item.arbitrage", "price", String.format("%.2f", cheapest)));
        }
        lore.add(Component.empty());
        lore.add(text("catalog.item.edit"));
        lore.add(text("catalog.item.remove"));
        ItemStack icon = Items.item(template.material(), Component.translatable(template.material().translationKey()), lore);
        icon.setAmount(Math.clamp(template.amount(), 1, icon.getMaxStackSize()));
        return icon;
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
