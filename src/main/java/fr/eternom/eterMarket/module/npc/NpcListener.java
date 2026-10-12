package fr.eternom.eterMarket.module.npc;

import fr.eternom.eterMarket.module.auction.AuctionGui;
import fr.eternom.eterMarket.module.job.JobGui;
import fr.eternom.eterMarket.module.npc.NpcRepository.Role;
import fr.eternom.eterMarket.module.shop.ShopGui;
import fr.eternom.eterNpc.api.NpcClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Clic droit sur un PNJ d'EterMarket (EterNpc) : sa boutique, la guilde de son métier (PNJ de métier) ou l'hôtel des
 * ventes ; Maj + clic droit avec etermarket.edit : son éditeur.
 */
public class NpcListener implements Listener {

    private final NpcService npcs;
    private final ShopGui shops;
    private final JobGui jobs;
    private final AuctionGui auctions;

    public NpcListener(NpcService npcs, ShopGui shops, JobGui jobs, AuctionGui auctions) {
        this.npcs = npcs;
        this.shops = shops;
        this.jobs = jobs;
        this.auctions = auctions;
    }

    @EventHandler
    public void onClick(NpcClickEvent event) {
        npcs.fromNpcId(event.getNpc()).ifPresent(npc -> {
            Player player = event.getPlayer();
            if (player.isSneaking() && player.hasPermission(ShopGui.EDIT_PERMISSION)) {
                shops.openEditor(player, npc.id(), 0);
            } else if (npc.role() == Role.JOB) {
                jobs.open(player, npc);
            } else if (npc.role() == Role.AUCTION) {
                auctions.open(player);
            } else {
                shops.open(player, npc.id(), 0);
            }
        });
    }
}
