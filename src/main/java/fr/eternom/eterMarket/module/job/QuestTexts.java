package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.job.JobRepository.Quest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Textes communs aux menus, à la sidebar et à l'action bar : nom d'une quête (selon son niveau) et ligne d'un objectif
 * avec sa progression (« Lingot de fer 12/16 », « Tuer Zombie 4/20 »). Les noms d'objets et de créatures sont
 * traduits par le client.
 */
class QuestTexts {

    private final Messages messages;

    QuestTexts(Messages messages) {
        this.messages = messages;
    }

    Component tier(Player viewer, Tier tier) {
        return messages.get(viewer, "quest.tier." + tier.id());
    }

    /** « Quête facile », « ★ Quête bonus (normale) ». */
    Component name(Player viewer, Quest quest) {
        String key = quest.slot() == Jobs.BONUS_SLOT ? "quest.item.name-bonus" : "quest.item.name";
        return messages.get(viewer, key, Placeholder.component("tier", tier(viewer, quest.tier())));
    }

    /** Une ligne par objectif, have = ce que le joueur a (objets sur lui, ou progression de l'action). */
    Component objective(Player viewer, Objective objective, int have) {
        String progressKey = have >= objective.amount() ? "quest.objective.progress-done" : "quest.objective.progress";
        return line(viewer, objective, messages.get(viewer, progressKey, "have", String.valueOf(Math.min(have, objective.amount())),
                "amount", String.valueOf(objective.amount())));
    }

    /** L'objectif sans progression (« Tuer Zombie »), pour l'éditeur. */
    Component label(Player viewer, Objective objective) {
        return line(viewer, objective, Component.empty());
    }

    private Component line(Player viewer, Objective objective, Component progress) {
        Component target = objective.targetName();
        String key = "quest.objective." + objective.kind().name().toLowerCase(Locale.ROOT) + (target == null ? "-any" : "");
        return messages.get(viewer, key, TagResolver.resolver(Placeholder.component("progress", progress),
                Placeholder.component("item", target == null ? Component.empty() : target)));
    }

    /** Ce que le joueur a pour cet objectif : objets simples sur lui, ou progression de l'action de la quête. */
    static int have(Player player, Quest quest, Objective objective) {
        return objective.kind() == Objective.Kind.ITEM
                ? JobService.count(player.getInventory(), objective.material())
                : quest.progressOf(objective);
    }

    /** Tous les objectifs sont remplis : il ne reste qu'à valider au PNJ. */
    static boolean ready(Player player, Quest quest) {
        return !quest.done() && quest.objectives().stream().allMatch(o -> have(player, quest, o) >= o.amount());
    }
}
