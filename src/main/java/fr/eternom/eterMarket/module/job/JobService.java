package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.module.job.JobRepository.Member;
import fr.eternom.eterMarket.module.job.JobRepository.Quest;
import fr.eternom.eterMarket.module.job.JobRepository.Template;
import fr.eternom.eterMarket.module.stock.StockRepository;
import fr.eternom.eterEconomy.api.EconomyApi;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * La guilde des métiers. Un seul métier à la fois : le premier est gratuit, en changer coûte `change-cost` et n'est
 * possible qu'une fois par `change-cooldown-days`. Chaque jour (minuit, fuseau de la config) : une quête par niveau de
 * `daily` (facile, normale, difficile), tirée au hasard dans le répertoire du métier, puis une quête bonus mieux payée
 * quand elles sont faites. Une fois par jour, une quête peut être changée contre une autre du même niveau (payant).
 *
 * Une quête a un ou plusieurs objectifs : des objets à livrer, et jusqu'à 3 actions (tuer, casser, pêcher) comptées par
 * JobProgress. Valider au PNJ : les actions doivent être faites, le PNJ prend les objets (simples seulement, sans nom ni
 * enchantement), la base marque la quête faite (une seule fois, même en cliquant deux fois), les objets vont dans le
 * STOCK COMMUN et la récompense est versée. Si la quête était déjà faite, les objets sont rendus.
 */
public class JobService {

    /** Ce qu'affiche le PNJ d'un métier : ses quêtes si le joueur en est membre, sinon de quoi le rejoindre. */
    public sealed interface Board {

        /** Membre de ce métier : ses quêtes du jour, ses quêtes accomplies et s'il peut encore changer une quête. */
        record Quests(String job, List<Quest> quests, int completed, boolean canReroll) implements Board {
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
    private final QuestTexts texts;
    private final JobProgress progress;

    public JobService(JavaPlugin plugin, JobRepository repository, StockRepository stock, Jobs jobs, Messages messages, ZoneId zone) {
        this.plugin = plugin;
        this.repository = repository;
        this.stock = stock;
        this.jobs = jobs;
        this.messages = messages;
        this.zone = zone;
        this.texts = new QuestTexts(messages);
        this.progress = new JobProgress(plugin, repository, this, messages, texts);
    }

    /** Bloquant : pose le répertoire de départ des métiers, une seule fois par métier sur tout le réseau. */
    public void seedCatalogs() {
        jobs.icons().keySet().forEach(job -> Jobs.defaults(job).ifPresent(defaults -> repository.seedOnce(job, defaults)));
    }

    /** Bloquant : remet le répertoire de départ du métier ; false si le métier n'en a pas (métier ajouté dans la config). */
    public boolean resetCatalog(String job) {
        Optional<List<Template>> defaults = Jobs.defaults(job);
        defaults.ifPresent(list -> repository.resetCatalog(job, list));
        return defaults.isPresent();
    }

    /** Bloquant : ce qu'affiche le PNJ du métier job pour ce joueur (les quêtes du jour sont tirées si besoin). */
    public Board board(UUID player, String job) {
        Optional<Member> member = repository.member(player);
        if (member.isEmpty() || !member.get().job().equals(job)) {
            long cooldown = member.map(current -> Math.max(0, current.changedAt() + jobs.changeCooldown().toMillis()
                    - System.currentTimeMillis()) / 1000).orElse(0L);
            return new Board.Join(job, member, cooldown);
        }
        progress.flush(player);
        return new Board.Quests(job, todayQuests(player, job), member.get().completed(),
                jobs.rerollEnabled() && member.get().rerollDay() != today());
    }

    /** Bloquant : les quêtes du jour du joueur, s'il a un métier (connexion : suivi des actions et de la sidebar). */
    Optional<Board.Quests> activeQuests(UUID player) {
        return repository.member(player).map(member -> new Board.Quests(member.job(), todayQuests(player, member.job()),
                member.completed(), jobs.rerollEnabled() && member.rerollDay() != today()));
    }

    /** Thread principal : rejoindre le métier (gratuit la première fois, sinon payant et limité dans le temps). */
    public void join(Player player, Board.Join join, Runnable after) {
        boolean free = join.current().isEmpty();
        EconomyApi economy = EconomyApi.get().orElse(null);
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
                if (jobs.changeCost() > 0 && !economy.withdraw(player.getUniqueId(), jobs.changeCost(), "EterMarket · métiers")) {
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
    public void deliver(Player player, Quest shown, Runnable after) {
        EconomyApi economy = EconomyApi.get().orElse(null);
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        Quest quest = progress.current(player.getUniqueId(), shown);
        if (!quest.actionComplete()) {
            messages.send(player, "quest.action-missing");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        List<Objective> items = quest.items();
        if (items.stream().anyMatch(item -> count(player.getInventory(), item.material()) < item.amount())) {
            messages.send(player, "quest.missing");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        items.forEach(item -> take(player.getInventory(), item.material(), item.amount()));
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            progress.flush(uuid);
            if (!repository.complete(uuid, quest.slot(), quest.day(), quest.targets())) {
                return false;
            }
            items.forEach(item -> stock.add(item.material(), item.amount()));
            economy.deposit(player.getUniqueId(), quest.reward(), "EterMarket · métiers");
            repository.addCompleted(uuid);
            return true;
        }, completed -> {
            if (!completed) {
                giveBack(player, items); // déjà validée (double clic, autre serveur)
                messages.send(player, "quest.already-done");
                return;
            }
            progress.markDone(player, quest);
            messages.send(player, quest.slot() == Jobs.BONUS_SLOT ? "quest.bonus-done" : "quest.done",
                    "reward", economy.format(quest.reward()));
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
            after.run();
        }, () -> {
            giveBack(player, items);
            messages.send(player, "error.generic");
        });
    }

    /**
     * Thread principal : changer une quête pas encore faite contre une autre du même niveau, une fois par jour, pour
     * reroll-cost. La progression de l'ancienne est perdue.
     */
    public void reroll(Player player, String job, Quest quest, Runnable after) {
        EconomyApi economy = EconomyApi.get().orElse(null);
        if (!jobs.rerollEnabled() || quest.done()) {
            return;
        }
        if (jobs.rerollCost() > 0 && economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        UUID uuid = player.getUniqueId();
        long today = today();
        Tasks.async(plugin, player, () -> {
            if (quest.day() != today) {
                return "quest.reroll-old";
            }
            Set<String> current = new HashSet<>();
            repository.quests(uuid).forEach(q -> current.add(Objective.format(q.objectives())));
            List<Template> choices = repository.catalog(job).stream()
                    .filter(t -> t.tier() == quest.tier() && !current.contains(Objective.format(t.objectives()))).toList();
            if (choices.isEmpty()) {
                return "quest.reroll-none";
            }
            if (!repository.claimReroll(uuid, today)) {
                return "quest.reroll-used";
            }
            if (jobs.rerollCost() > 0 && !economy.withdraw(player.getUniqueId(), jobs.rerollCost(), "EterMarket · métiers")) {
                repository.releaseReroll(uuid, today);
                return "quest.reroll-not-enough";
            }
            Template template = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
            Quest replacement = fromTemplate(quest.slot(), today, template);
            if (!repository.swapQuest(uuid, replacement)) {
                if (jobs.rerollCost() > 0) {
                    economy.deposit(player.getUniqueId(), jobs.rerollCost(), "EterMarket · métiers");
                }
                repository.releaseReroll(uuid, today);
                return "quest.reroll-done";
            }
            return "quest.rerolled";
        }, result -> {
            messages.send(player, result, "price", Money.format(jobs.rerollCost()));
            if (result.equals("quest.rerolled")) {
                player.playSound(player, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1f);
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
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

    public JobProgress progress() {
        return progress;
    }

    QuestTexts texts() {
        return texts;
    }

    public String jobName(CommandSender viewer, String job) {
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
            Set<String> taken = new HashSet<>();
            quests.forEach(quest -> taken.add(Objective.format(quest.objectives())));
            pick(repository.catalog(job), jobs.bonusTier(), taken).ifPresent(template -> {
                Quest bonus = fromTemplate(Jobs.BONUS_SLOT, today, template);
                repository.saveQuest(player, bonus);
            });
            quests = new ArrayList<>(repository.quests(player));
        }
        quests.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        return quests;
    }

    /** Une quête par niveau de jobs.daily, toutes différentes, au hasard dans le répertoire du métier. */
    private List<Quest> draw(String job, long today) {
        List<Template> catalog = repository.catalog(job);
        Set<String> taken = new HashSet<>();
        List<Quest> quests = new ArrayList<>();
        for (Tier tier : jobs.daily()) {
            Optional<Template> template = pick(catalog, tier, taken);
            if (template.isPresent()) {
                taken.add(Objective.format(template.get().objectives()));
                quests.add(fromTemplate(quests.size(), today, template.get()));
            }
        }
        return quests;
    }

    /** Au hasard parmi celles du niveau ; à défaut (niveau vide), parmi toutes. Jamais deux fois la même quête. */
    private static Optional<Template> pick(List<Template> catalog, Tier tier, Set<String> taken) {
        List<Template> free = catalog.stream().filter(t -> !taken.contains(Objective.format(t.objectives()))).toList();
        List<Template> sameTier = new ArrayList<>(free.stream().filter(t -> t.tier() == tier).toList());
        List<Template> choices = sameTier.isEmpty() ? new ArrayList<>(free) : sameTier;
        if (choices.isEmpty()) {
            return Optional.empty();
        }
        Collections.shuffle(choices);
        return Optional.of(choices.getFirst());
    }

    /** Recopie une quête du répertoire ; la bonus est payée bonus-multiplier fois plus. */
    private Quest fromTemplate(int slot, long day, Template template) {
        double reward = slot == Jobs.BONUS_SLOT ? Math.round(template.reward() * jobs.bonusMultiplier()) : template.reward();
        return new Quest(slot, day, template.tier(), template.objectives(), reward, List.of(0, 0, 0), false, false);
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

    private static void giveBack(Player player, List<Objective> items) {
        for (Objective item : items) {
            Material material = item.material();
            int remaining = item.amount();
            while (remaining > 0) {
                int size = Math.min(remaining, material.getMaxStackSize());
                player.getInventory().addItem(ItemStack.of(material, size))
                        .values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
                remaining -= size;
            }
        }
    }


}
