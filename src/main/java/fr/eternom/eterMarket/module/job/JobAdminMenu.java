package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.job.JobAdmin.Snapshot;
import fr.eternom.eterMarket.module.job.JobRepository.Member;
import fr.eternom.eterMarket.module.job.JobRepository.Quest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Métier d'un joueur, vu par le staff, 5 lignes :
 * <pre>
 *  ▣ ▣ ▢ ▢ ☺ ▢ ▢ ▣ ▣     ☺ = joueur (en ligne, métier, délai, quêtes accomplies, changement de quête)
 *  ▣ · · · · · · · ▣
 *  ▢ · 1 · 2 · 3 · ▢     ses quêtes du jour (lecture seule)
 *  ▣ · · · ★ · · · ▣     ★ = sa quête bonus
 *  ▣ M D Q R C X F ▣     M = changer de métier · D = délai à zéro · Q = nouvelles quêtes · R = rendre le changement
 * </pre>                   de quête · C = quêtes accomplies (±1, Maj ±10) · X = tout effacer · F = fermer
 * Sans métier, seul M (choisir son métier) reste actif.
 */
class JobAdminMenu implements Menu {

    private static final int INFO = 4;
    private static final List<Integer> QUEST_SLOTS = List.of(20, 22, 24);
    private static final int BONUS = 31;
    private static final int SET_JOB = 37;
    private static final int COOLDOWN = 38;
    private static final int QUESTS = 39;
    private static final int REROLL = 40;
    private static final int COMPLETED = 41;
    private static final int REMOVE = 42;
    private static final int CLOSE = 43;

    private final JobAdmin admin;
    private final JobService service;
    private final Messages messages;
    private final Player viewer;
    private final Snapshot target;
    private final Inventory inventory;

    JobAdminMenu(JobAdmin admin, Player viewer, Snapshot target) {
        this.admin = admin;
        this.service = admin.service();
        this.messages = admin.messages();
        this.viewer = viewer;
        this.target = target;
        this.inventory = Bukkit.createInventory(this, 45, text("job-admin.title", "player", target.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == SET_JOB) {
            Sounds.page(player);
            player.openInventory(new JobAdminPickMenu(admin, player, target).getInventory());
            return;
        }
        if (!List.of(COOLDOWN, QUESTS, REROLL, COMPLETED, REMOVE).contains(slot)) {
            return;
        }
        if (target.member().isEmpty()) {
            Sounds.refuse(player);
            return;
        }
        Sounds.click(player);
        switch (slot) {
            case COOLDOWN -> admin.resetCooldown(player, target);
            case QUESTS -> admin.clearQuests(player, target);
            case REROLL -> admin.clearReroll(player, target);
            case COMPLETED -> admin.addCompleted(player, target, (click.isRightClick() ? -1 : 1) * (click.isShiftClick() ? 10 : 1));
            default -> admin.confirmRemove(player, target);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.RED_STAINED_GLASS_PANE);
        inventory.setItem(INFO, Items.head(Bukkit.createProfile(target.uuid(), target.name()),
                text("quest.menu.player", "player", target.name()), info()));

        List<Quest> today = target.quests().stream().filter(quest -> quest.day() == service.today()).toList();
        if (target.member().isPresent() && today.isEmpty()) {
            inventory.setItem(QUEST_SLOTS.get(1), Items.item(Material.CLOCK, text("job-admin.quests-none"),
                    List.of(text("job-admin.quests-none-lore"))));
        }
        for (Quest quest : today) {
            int slot = quest.slot() == Jobs.BONUS_SLOT ? BONUS : quest.slot() < QUEST_SLOTS.size() ? QUEST_SLOTS.get(quest.slot()) : -1;
            if (slot >= 0) {
                place(slot, quest);
            }
        }

        boolean hasJob = target.member().isPresent();
        inventory.setItem(SET_JOB, Items.item(Material.WRITABLE_BOOK, text("job-admin.set-job.name"), List.of(text("job-admin.set-job.lore"))));
        inventory.setItem(COOLDOWN, action(hasJob, Material.CLOCK, "job-admin.cooldown"));
        inventory.setItem(QUESTS, action(hasJob, Material.PAPER, "job-admin.quests"));
        inventory.setItem(REROLL, action(hasJob, Material.COMPASS, "job-admin.reroll"));
        inventory.setItem(COMPLETED, hasJob
                ? Items.item(Material.EXPERIENCE_BOTTLE, text("job-admin.completed.name", "count", String.valueOf(completed())),
                List.of(text("job-admin.completed.lore")))
                : Items.pane(Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(REMOVE, action(hasJob, Material.LAVA_BUCKET, "job-admin.remove"));
        inventory.setItem(CLOSE, Items.item(Material.BARRIER, text("menu.close"), List.of()));
    }

    /** La tête du joueur : en ligne, métier, dernier changement et délai, quêtes accomplies, changement de quête. */
    private List<Component> info() {
        List<Component> lore = new ArrayList<>();
        lore.add(target.server() == null ? text("job-admin.offline") : text("job-admin.online", "server", target.server()));
        if (target.member().isEmpty()) {
            lore.add(text("job-admin.job-none"));
            return lore;
        }
        Member member = target.member().get();
        lore.add(text("quest.menu.job", "job", service.jobName(viewer, member.job())));
        long now = System.currentTimeMillis();
        if (member.changedAt() > 0) {
            lore.add(text("job-admin.changed", "time", duration((now - member.changedAt()) / 1000)));
        }
        long wait = (member.changedAt() + service.jobs().changeCooldown().toMillis() - now) / 1000;
        lore.add(wait > 0 ? text("job-admin.wait", "time", duration(wait)) : text("job-admin.wait-none"));
        lore.add(text("quest.menu.completed", "count", String.valueOf(member.completed())));
        if (target.rank() > 0) {
            lore.add(text("job-admin.rank", "rank", String.valueOf(target.rank())));
        }
        if (service.jobs().rerollEnabled()) {
            lore.add(text(member.rerollDay() == service.today() ? "quest.menu.reroll-used" : "quest.menu.reroll-ready"));
        }
        return lore;
    }

    /** Une quête du joueur : objectifs (progression des actions ; les objets se comptent sur lui, au PNJ) et récompense. */
    private void place(int slot, Quest quest) {
        QuestTexts texts = service.texts();
        List<Component> lore = new ArrayList<>();
        for (Objective objective : quest.objectives()) {
            Component line = quest.done() || !objective.kind().isAction()
                    ? texts.label(viewer, objective).append(Component.text(" ×" + objective.amount()))
                    : texts.objective(viewer, objective, quest.progressOf(objective));
            lore.add(text("quest.item.objective").append(line));
        }
        lore.add(text("quest.item.reward", "reward", Money.format(quest.reward())));
        if (quest.done()) {
            lore.add(text("quest.item.done"));
        } else if (quest.tracked()) {
            lore.add(text("quest.item.tracked"));
        }
        Material icon = quest.done() ? Material.LIME_DYE : quest.objectives().getFirst().icon();
        inventory.setItem(slot, Items.item(icon, texts.name(viewer, quest), lore));
    }

    /** Bouton d'action, grisé si le joueur n'a pas de métier. */
    private ItemStack action(boolean active, Material icon, String key) {
        return active
                ? Items.item(icon, text(key + ".name"), List.of(text(key + ".lore")))
                : Items.pane(Material.GRAY_STAINED_GLASS_PANE);
    }

    private int completed() {
        return target.member().map(Member::completed).orElse(0);
    }

    private String duration(long seconds) {
        return EterLib.get().formatDuration(viewer, Math.max(0, seconds));
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
