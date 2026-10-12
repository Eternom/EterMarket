package fr.eternom.eterMarket.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Une quête de métier vient d'être validée auprès du PNJ (thread principal), une seule fois par quête : la base l'a
 * déjà enregistrée et la récompense est payée. Pour les autres plugins, sans qu'EterMarket les connaisse (ex : l'XP du
 * pass d'EterReward).
 * <pre>
 *     // compileOnly("com.github.Eternom:EterMarket:&lt;tag&gt;") ; plugin.yml : softdepend: [EterMarket]
 *     // Écouteur dans une classe à part, enregistrée seulement si EterMarket est présent
 * </pre>
 */
public class JobQuestCompleteEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String job;
    private final String tier;
    private final boolean bonus;

    public JobQuestCompleteEvent(Player player, String job, String tier, boolean bonus) {
        super(player);
        this.job = job;
        this.tier = tier;
        this.bonus = bonus;
    }

    /** Métier du joueur (identifiant de config.yml > jobs.list), vide s'il n'en a plus. */
    public String getJob() {
        return job;
    }

    /** Difficulté : easy, normal ou hard. */
    public String getTier() {
        return tier;
    }

    /** true pour la quête bonus (celle qui s'ouvre une fois les quêtes du jour finies). */
    public boolean isBonus() {
        return bonus;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
