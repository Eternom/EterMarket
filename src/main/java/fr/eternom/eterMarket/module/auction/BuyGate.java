package fr.eternom.eterMarket.module.auction;

import fr.eternom.eterLib.helper.ranking.RankingSource;

import java.util.UUID;

/**
 * Acheter à l'hôtel des ventes seulement après assez de jeu (auction.min-buy-playtime-hours, temps de jeu actif de
 * /stats d'EterSuccess, source playtime_total) : un second compte ne peut pas « acheter » très cher un objet sans
 * valeur au compte principal pour lui passer ses récompenses. Sans EterSuccess sur ce serveur, pas de limite.
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
        // Lu par EterLib (source playtime_total d'EterSuccess) : EterSuccess dépend déjà de ce plugin, pas l'inverse
        var source = RankingSource.find("playtime_total");
        if (minMinutes <= 0 || source.isEmpty()) {
            return 0;
        }
        long played = source.get().of(player)[1];
        return Math.max(0, minMinutes - played);
    }
}
