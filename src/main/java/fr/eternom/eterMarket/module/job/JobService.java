package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.module.job.JobRepository.Member;
import fr.eternom.eterMarket.module.job.JobRepository.Quest;
import fr.eternom.eterMarket.module.job.JobRepository.Template;
import fr.eternom.eterMarket.module.stock.StockRepository;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * La guilde des métiers. Un seul métier à la fois : le premier est gratuit, en changer coûte `change-cost` et n'est
 * possible qu'une fois par `change-cooldown-days`. Chaque jour (minuit, fuseau de la config) : `quests-per-day` quêtes
 * de livraison tirées au hasard dans le répertoire du métier, puis une quête bonus mieux payée quand elles sont faites.
 *
 * Valider une quête : le PNJ prend les objets (objets simples seulement, sans nom ni enchantement), la base marque la
 * quête faite (une seule fois, même en cliquant deux fois), les objets vont dans le STOCK COMMUN et la récompense est
 * versée. Si la quête était déjà faite, les objets sont rendus.
 */
public class JobService {

    /** Ce qu'affiche le PNJ d'un métier : ses quêtes si le joueur en est membre, sinon de quoi le rejoindre. */
    public sealed interface Board {

        /** Membre de ce métier : ses quêtes du jour. */
        record Quests(String job, List<Quest> quests) implements Board {
        }

        /** Pas membre de ce métier : son métier actuel (ou aucun) et le temps avant de pouvoir changer. */
        record Join(String job, Optional<Member> current, long cooldownSeconds) implements Board {
        }
    }

    private final JavaPlugin plugin;
    private final JobRepository repository;
    private final StockRepository stock;
    private final Jobs jobs;
    private final Messages messages;
    private final ZoneId zone;

    public JobService(JavaPlugin plugin, JobRepository repository, StockRepository stock, Jobs jobs, Messages messages, ZoneId zone) {
        this.plugin = plugin;
        this.repository = repository;
        this.stock = stock;
        this.jobs = jobs;
        this.messages = messages;
        this.zone = zone;
    }

    /** Bloquant : premier démarrage, pose le répertoire de départ des métiers qui n'en ont pas. */
    public void seedCatalogs() {
        jobs.icons().keySet().forEach(job -> Jobs.defaults(job).ifPresent(defaults -> repository.seedIfEmpty(job, defaults)));
    }

    /** Bloquant : ce qu'affiche le PNJ du métier job pour ce joueur (les quêtes du jour sont tirées si besoin). */
    public Board board(UUID player, String job) {
        Optional<Member> member = repository.member(player);
        if (member.isEmpty() || !member.get().job().equals(job)) {
            long cooldown = member.map(current -> Math.max(0, current.changedAt() + jobs.changeCooldown().toMillis()
                    - System.currentTimeMillis()) / 1000).orElse(0L);
            return new Board.Join(job, member, cooldown);
        }
        return new Board.Quests(job, todayQuests(player, job));
    }

    /** Thread principal : rejoindre le métier (gratuit la première fois, sinon payant et limité dans le temps). */
    public void join(Player player, Board.Join join, Runnable after) {
        boolean free = join.current().isEmpty();
        Economy economy = economy();
        if (!free && jobs.changeCost() > 0 && economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            Optional<Member> current = repository.member(uuid);
            if (current.isPresent()) {
                long wait = current.get().changedAt() + jobs.changeCooldown().toMillis() - System.currentTimeMillis();
                if (wait > 0) {
                    return "job.change-too-soon";
                }
                if (jobs.changeCost() > 0 && !economy.withdrawPlayer(player, jobs.changeCost()).transactionSuccess()) {
                    return "job.change-not-enough";
                }
            }
            repository.setJob(uuid, join.job(), System.currentTimeMillis());
            return current.isPresent() ? "job.changed" : "job.joined";
        }, result -> {
            messages.send(player, result, "job", jobName(player, join.job()),
                    "price", economy == null ? String.valueOf(jobs.changeCost()) : economy.format(jobs.changeCost()));
            if (result.equals("job.joined") || result.equals("job.changed")) {
                player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
                after.run();
            }
        }, () -> messages.send(player, "error.generic"));
    }

    /** Thread principal : valider une quête auprès du PNJ. */
    public void deliver(Player player, Quest quest, Runnable after) {
        Economy economy = economy();
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        if (count(player.getInventory(), quest.material()) < quest.amount()) {
            messages.send(player, "quest.missing", "amount", String.valueOf(quest.amount()));
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        take(player.getInventory(), quest.material(), quest.amount());
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            if (!repository.complete(uuid, quest.slot(), quest.day())) {
                return false;
            }
            stock.add(quest.material(), quest.amount());
            economy.depositPlayer(player, quest.reward());
            return true;
        }, completed -> {
            if (!completed) {
                giveBack(player, quest.material(), quest.amount()); // déjà validée (double clic, autre serveur)
                messages.send(player, "quest.already-done");
                return;
            }
            messages.send(player, quest.slot() == Jobs.BONUS_SLOT ? "quest.bonus-done" : "quest.done",
                    "reward", economy.format(quest.reward()));
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
            after.run();
        }, () -> {
            giveBack(player, quest.material(), quest.amount());
            messages.send(player, "error.generic");
        });
    }

    public long today() {
        return LocalDate.now(zone).toEpochDay();
    }

    /** Secondes avant les quêtes de demain. */
    public long secondsUntilTomorrow() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone)).toSeconds();
    }

    public Jobs jobs() {
        return jobs;
    }

    public String jobName(Player viewer, String job) {
        String raw = messages.raw(viewer, "job.name." + job);
        return raw == null ? job : messages.plain(viewer, "job.name." + job);
    }

    /** Quêtes du jour : tirées si c'est un nouveau jour, et la bonus ajoutée quand les autres sont faites. Bloquant. */
    private List<Quest> todayQuests(UUID player, String job) {
        long today = today();
        List<Quest> quests = new ArrayList<>(repository.quests(player));
        if (quests.isEmpty() || quests.getFirst().day() != today) {
            quests = new ArrayList<>(draw(job, today));
            repository.replaceQuests(player, quests);
        }
        boolean dailyDone = quests.stream().filter(quest -> quest.slot() < Jobs.BONUS_SLOT).allMatch(Quest::done);
        boolean hasBonus = quests.stream().anyMatch(quest -> quest.slot() == Jobs.BONUS_SLOT);
        if (jobs.bonusQuest() && dailyDone && !hasBonus) {
            List<Template> catalog = repository.catalog(job);
            if (!catalog.isEmpty()) {
                Template template = catalog.get(ThreadLocalRandom.current().nextInt(catalog.size()));
                Quest bonus = new Quest(Jobs.BONUS_SLOT, today, template.material(), template.amount(),
                        Math.round(template.reward() * jobs.bonusMultiplier()), false);
                repository.saveQuest(player, bonus);
                quests.add(bonus);
            }
        }
        return quests;
    }

    /** quests-per-day quêtes différentes, au hasard dans le répertoire du métier. */
    private List<Quest> draw(String job, long today) {
        List<Template> catalog = new ArrayList<>(repository.catalog(job));
        Collections.shuffle(catalog);
        List<Quest> quests = new ArrayList<>();
        for (int slot = 0; slot < jobs.questsPerDay() && slot < catalog.size(); slot++) {
            Template template = catalog.get(slot);
            quests.add(new Quest(slot, today, template.material(), template.amount(), template.reward(), false));
        }
        return quests;
    }

    /** Objets simples (sans nom ni enchantement) de cette matière dans l'inventaire. */
    static int count(PlayerInventory inventory, Material material) {
        ItemStack plain = ItemStack.of(material);
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && stack.isSimilar(plain)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static void take(PlayerInventory inventory, Material material, int amount) {
        ItemStack plain = ItemStack.of(material);
        ItemStack[] contents = inventory.getStorageContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack != null && stack.isSimilar(plain)) {
                int taken = Math.min(remaining, stack.getAmount());
                stack.setAmount(stack.getAmount() - taken);
                remaining -= taken;
                contents[i] = stack.getAmount() == 0 ? null : stack;
            }
        }
        inventory.setStorageContents(contents);
    }

    private static void giveBack(Player player, Material material, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int size = Math.min(remaining, material.getMaxStackSize());
            player.getInventory().addItem(ItemStack.of(material, size))
                    .values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
            remaining -= size;
        }
    }

    private static Economy economy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }
}
