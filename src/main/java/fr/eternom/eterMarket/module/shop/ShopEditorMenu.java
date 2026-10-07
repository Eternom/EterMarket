package fr.eternom.eterMarket.module.shop;

import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.shop.ShopGui.View;
import fr.eternom.eterMarket.module.shop.ShopRepository.ShopItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
import java.util.Set;

/**
 * Éditeur d'une boutique (staff, cadre rouge), 6 lignes :
 * <pre>
 *  ▣ ▣ ▢ ▢ ⚙ ▢ ▢ ▣ ▣     ⚙ = le PNJ (identifiant, vente sur stock)
 *  ▣ · · · · · · · ▣     · = objets vendus : clic gauche = prix, clic droit = retirer
 *  ▢ · · · · · · · ▢
 *  ▢ · · · · · · · ▢
 *  ▣ · · · · · · · ▣
 *  ◀ + ⇄ ✎ « ☺ ▢ ▣ ▶     + = ajouter l'objet en main · ⇄ = vente sur stock · ✎ = nom · ☺ = skin
 * </pre>
 * Le stock commun est affiché pour chaque objet simple, même quand la boutique vend en quantité illimitée.
 */
class ShopEditorMenu implements Menu {

    private static final int INFO = 4;
    private static final int ADD = 46;
    private static final int STOCK = 47;
    private static final int RENAME = 48;
    private static final int BACK = 49;
    private static final int SKIN = 50;
    private static final int PREVIOUS = 45;
    private static final int NEXT = 53;
    private static final Set<Integer> ACCENT_FRAME = Set.of(0, 1, 7, 8, 9, 17, 36, 44, 52);

    private final ShopGui gui;
    private final Messages messages;
    private final Player viewer;
    private final View view;
    private final int page;
    private final Inventory inventory;
    private final Map<Integer, ShopItem> itemAtSlot = new HashMap<>();

    ShopEditorMenu(ShopGui gui, Player viewer, View view, int page) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.view = view;
        this.page = Math.clamp(page, 0, ShopMenu.pageCount(view.items().size()) - 1);
        this.inventory = Bukkit.createInventory(this, 54, text("editor.title", "npc", view.npc().id()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        ShopItem item = itemAtSlot.get(slot);
        if (item != null) {
            Sounds.click(player);
            if (click.isRightClick()) {
                gui.confirmRemove(player, view, item, page);
            } else {
                gui.changePrice(player, view, item, page);
            }
            return;
        }
        switch (slot) {
            case ADD -> {
                Sounds.click(player);
                gui.addHeld(player, view, page);
            }
            case STOCK -> {
                Sounds.click(player);
                gui.toggleStock(player, view, page);
            }
            case RENAME -> {
                Sounds.click(player);
                gui.rename(player, view, page);
            }
            case SKIN -> {
                Sounds.click(player);
                gui.changeSkin(player, view, page);
            }
            case BACK -> gui.backButton().click(player);
            case PREVIOUS -> {
                if (page > 0) {
                    Sounds.page(player);
                    gui.openEditor(player, view.npc().id(), page - 1);
                }
            }
            case NEXT -> {
                if (page + 1 < ShopMenu.pageCount(view.items().size())) {
                    Sounds.page(player);
                    gui.openEditor(player, view.npc().id(), page + 1);
                }
            }
            default -> {
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        ShopMenu.frame(inventory, Material.RED_STAINED_GLASS_PANE, ACCENT_FRAME);
        boolean useStock = view.npc().useStock();
        inventory.setItem(INFO, Items.item(Material.COMMAND_BLOCK, text("editor.info.name", "npc", view.npc().id()), List.of(
                text("editor.info.items", "count", String.valueOf(view.items().size())),
                text(useStock ? "editor.info.stock-on" : "editor.info.stock-off"))));

        int start = page * ShopMenu.SLOTS.size();
        List<ShopItem> items = view.items();
        for (int i = 0; i < ShopMenu.SLOTS.size() && start + i < items.size(); i++) {
            ShopItem item = items.get(start + i);
            itemAtSlot.put(ShopMenu.SLOTS.get(i), item);
            inventory.setItem(ShopMenu.SLOTS.get(i), display(item));
        }
        ShopMenu.pages(inventory, page, ShopMenu.pageCount(items.size()), messages, viewer);
        inventory.setItem(ADD, Items.item(Material.LIME_DYE, text("editor.add"), List.of(text("editor.add-lore"))));
        inventory.setItem(STOCK, Items.item(useStock ? Material.CHEST : Material.ENDER_CHEST,
                text(useStock ? "editor.stock.button-on" : "editor.stock.button-off"), List.of(text("editor.stock.lore")), useStock));
        inventory.setItem(RENAME, Items.item(Material.NAME_TAG, text("editor.rename.button"), List.of(text("editor.rename.lore"))));
        inventory.setItem(SKIN, Items.item(Material.ARMOR_STAND, text("editor.skin.button"), List.of(text("editor.skin.lore"))));
        inventory.setItem(BACK, gui.backButton().item(viewer));
    }

    private ItemStack display(ShopItem item) {
        ItemStack display = item.item().clone();
        List<Component> lore = new ArrayList<>(display.lore() == null ? List.of() : display.lore());
        lore.add(Component.empty());
        lore.add(text("shop.item.price", "price", gui.money(item.price()), "amount", String.valueOf(item.item().getAmount())));
        lore.add(item.stockable()
                ? text("editor.item.stock", "amount", String.valueOf(view.stock().getOrDefault(item.item().getType(), 0L)))
                : text("editor.item.not-stockable"));
        lore.add(text("editor.item.price"));
        lore.add(text("editor.item.remove"));
        display.lore(lore.stream().map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
        return display;
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
