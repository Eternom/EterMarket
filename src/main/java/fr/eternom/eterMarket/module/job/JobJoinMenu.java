package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.job.JobService.Board;
import fr.eternom.eterMarket.module.npc.NpcRepository.Npc;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rejoindre la guilde d'un métier, 3 lignes : le métier au centre (gratuit la première fois ; sinon prix du changement
 * et délai restant), la boutique du métier à gauche, retour ou fermer en bas. Clic = confirmation dans un Dialog.
 */
class JobJoinMenu implements Menu {

    private static final int SHOP = 11;
    private static final int JOIN = 13;
    private static final int BACK = 22;
    private static final Set<Integer> ACCENT_FRAME = Set.of(0, 1, 7, 8, 9, 17, 18, 19, 25, 26);

    private final JobGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Npc npc;
    private final Board.Join join;
    private final Inventory inventory;

    JobJoinMenu(JobGui gui, Player viewer, Npc npc, Board.Join join) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.npc = npc;
        this.join = join;
        this.inventory = Bukkit.createInventory(this, 27, text("job.join.menu-title", "job", jobName(join.job())));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == JOIN) {
            if (join.cooldownSeconds() > 0) {
                player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                return;
            }
            Sounds.click(player);
            gui.confirmJoin(player, npc, join);
        } else if (slot == SHOP) {
            Sounds.page(player);
            gui.openShop(player, npc);
        } else if (slot == BACK) {
            gui.backButton().click(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        ItemStack accent = Items.pane(Material.ORANGE_STAINED_GLASS_PANE);
        ItemStack neutral = Items.pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot / 9 != 1 || slot % 9 == 0 || slot % 9 == 8) {
                inventory.setItem(slot, ACCENT_FRAME.contains(slot) ? accent : neutral);
            }
        }
        List<Component> lore = new ArrayList<>();
        lore.add(text("job.join.description." + join.job()));
        lore.add(Component.empty());
        if (join.current().isEmpty()) {
            lore.add(text("job.join.free"));
        } else {
            lore.add(text("job.join.current", "job", jobName(join.current().get().job())));
            lore.add(text("job.join.cost", "price", gui.money(gui.service().jobs().changeCost())));
        }
        lore.add(join.cooldownSeconds() > 0
                ? text("job.join.cooldown", "time", EterLib.get().formatDuration(viewer, join.cooldownSeconds()))
                : text("job.join.click"));
        inventory.setItem(JOIN, Items.item(gui.service().jobs().icon(join.job()), text("job.join.name", "job", jobName(join.job())),
                lore, join.cooldownSeconds() == 0));
        inventory.setItem(SHOP, Items.item(Material.EMERALD, text("quest.menu.shop"), List.of(text("quest.menu.shop-lore"))));
        inventory.setItem(BACK, gui.backButton().item(viewer));
    }

    private String jobName(String job) {
        return gui.service().jobName(viewer, job);
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
