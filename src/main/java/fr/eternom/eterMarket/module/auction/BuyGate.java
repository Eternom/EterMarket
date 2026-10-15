package fr.eternom.eterMarket.module.auction;

import fr.eternom.eterSuccess.api.SuccessApi;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Acheter à l'hôtel des ventes seulement après assez de jeu (auction.min-buy-playtime-hours, temps de jeu actif de
 * /stats d'EterSuccess) : un second compte ne peut pas « acheter » très cher un objet sans valeur au compte principal
 * pour lui passer ses récompenses. Sans EterSuccess sur ce serveur, pas de limite.
 */
final class BuyGate {

    private final long minMinutes;

    BuyGate(double minHours) {
        this.minMinutes = Math.round(Math.max(0, minHours) * 60);
    }

    long minMinutes() {
        return minMinutes;
    }

    /** Minutes de jeu encore nécessaires (0 : achat permis). Bloquant (base). */
    long minutesLeft(UUID player) {
        if (minMinutes <= 0 || !Bukkit.getPluginManager().isPluginEnabled("EterSuccess")) {
            return 0;
        }
        long played = SuccessApi.get().map(api -> api.playtimeMinutes(player)).orElse(minMinutes);
        return Math.max(0, minMinutes - played);
    }
}
