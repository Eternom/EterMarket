package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.job.JobRepository.Quest;
import fr.eternom.eterMarket.module.job.JobService.Board;
import fr.eternom.eterMarket.module.npc.NpcRepository.Npc;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
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
 * Quêtes du jour au PNJ du métier, 5 lignes :
 * <pre>
 *  ▣ ▣ ▢ ▢ ☺ ▢ ▢ ▣ ▣     ☺ = joueur (métier, quêtes faites, temps avant les suivantes)
 *  ▣ · · · · · · · ▣
 *  ▢ · 1 · 2 · 3 · ▢     quêtes du jour : objet à livrer, quantité, récompense, ce que tu en as ; clic = valider
 *  ▣ · · · ★ · · · ▣     ★ = quête bonus (débloquée quand les 3 sont faites)
 *  ▣ ▣ ▢ $ « ▢ ▢ ▣ ▣     $ = boutique du métier · « = retour ou fermer
 * </pre>
 */
class JobQuestMenu implements Menu {

    private static final int INFO = 4;
    private static final List<Integer> QUEST_SLOTS = List.of(20, 22, 24);
    private static final int BONUS = 31;
    private static final int SHOP = 39;
    private static final int BACK = 40;
    private static final Set<Integer> ACCENT_FRAME = Set.of(0, 1, 7, 8, 9, 17, 27, 35, 36, 37, 43, 44);

    private final JobGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Npc npc;
    private final Board.Quests board;
    private final Inventory inventory;
    private final Map<Integer, Quest> questAtSlot = new HashMap<>();

    JobQuestMenu(JobGui gui, Player viewer, Npc npc, Board.Quests board) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.npc = npc;
        this.board = board;
        this.inventory = Bukkit.createInventory(this, 45, text("quest.menu.title", "job", gui.service().jobName(viewer, board.job())));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Quest quest = questAtSlot.get(slot);
        if (quest != null) {
            if (quest.done()) {
                player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            } else {
                Sounds.click(player);
                player.closeInventory();
                gui.deliver(player, npc, quest);
            }
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
            if (slot / 9 == 0 || slot / 9 == 4 || slot % 9 == 0 || slot % 9 == 8) {
                inventory.setItem(slot, ACCENT_FRAME.contains(slot) ? accent : neutral);
            }
        }
        List<Quest> daily = board.quests().stream().filter(quest -> quest.slot() < Jobs.BONUS_SLOT).toList();
        long done = daily.stream().filter(Quest::done).count();
        inventory.setItem(INFO, Items.head(viewer.getPlayerProfile(), text("quest.menu.player", "player", viewer.getName()), List.of(
                text("quest.menu.job", "job", gui.service().jobName(viewer, board.job())),
                text("quest.menu.progress", "done", String.valueOf(done), "total", String.valueOf(daily.size())),
                text("quest.menu.reset", "time", gui.timeUntilTomorrow(viewer)))));

        for (Quest quest : daily) {
            if (quest.slot() < QUEST_SLOTS.size()) {
                place(QUEST_SLOTS.get(quest.slot()), quest);
            }
        }
        board.quests().stream().filter(quest -> quest.slot() == Jobs.BONUS_SLOT).findFirst().ifPresentOrElse(
                bonus -> place(BONUS, bonus),
                () -> {
                    if (gui.service().jobs().bonusQuest()) {
                        inventory.setItem(BONUS, Items.item(Material.GRAY_DYE, text("quest.bonus.locked"),
                                List.of(text("quest.bonus.locked-lore"))));
                    }
                });
        inventory.setItem(SHOP, Items.item(Material.EMERALD, text("quest.menu.shop"), List.of(text("quest.menu.shop-lore"))));
        inventory.setItem(BACK, gui.backButton().item(viewer));
    }

    private void place(int slot, Quest quest) {
        questAtSlot.put(slot, quest);
        boolean bonus = quest.slot() == Jobs.BONUS_SLOT;
        int owned = JobService.count(viewer.getInventory(), quest.material());
        boolean ready = !quest.done() && owned >= quest.amount();
        Component item = Component.translatable(quest.material().translationKey());
        List<Component> lore = new ArrayList<>();
        lore.add(text("quest.item.reward", "reward", gui.money(quest.reward())));
        if (quest.done()) {
            lore.add(text("quest.item.done"));
        } else {
            lore.add(text(ready ? "quest.item.owned-enough" : "quest.item.owned", "owned", String.valueOf(Math.min(owned, quest.amount())),
                    "amount", String.valueOf(quest.amount())));
            lore.add(Component.empty());
            lore.add(text(ready ? "quest.item.deliver" : "quest.item.gather"));
        }
        Component name = messages.render(raw(bonus ? "quest.item.name-bonus" : "quest.item.name"),
                TagResolver.resolver(Placeholder.component("item", item)), "amount", String.valueOf(quest.amount()));
        ItemStack icon = Items.item(quest.done() ? Material.LIME_DYE : quest.material(), name, lore, ready);
        icon.setAmount(Math.clamp(quest.amount(), 1, icon.getMaxStackSize()));
        inventory.setItem(slot, icon);
    }

    private String raw(String key) {
        String raw = messages.raw(viewer, key);
        return raw == null ? key : raw;
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
