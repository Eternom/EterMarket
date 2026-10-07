package fr.eternom.eterMarket.module.npc;

import fr.eternom.eterMarket.module.shop.ShopGui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Clic droit sur un PNJ : sa boutique ; Maj + clic droit avec etermarket.edit : son éditeur.
 * Chargement d'un chunk : ses PNJ apparaissent.
 */
public class NpcListener implements Listener {

    private final NpcSpawner spawner;
    private final ShopGui shops;

    public NpcListener(NpcSpawner spawner, ShopGui shops) {
        this.spawner = spawner;
        this.shops = shops;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        spawner.npcOf(event.getRightClicked()).ifPresent(npc -> {
            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND) {
                return; // un seul clic, pas deux (main principale et seconde main)
            }
            Player player = event.getPlayer();
            if (player.isSneaking() && player.hasPermission(ShopGui.EDIT_PERMISSION)) {
                shops.openEditor(player, npc, 0);
            } else {
                shops.open(player, npc, 0);
            }
        });
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        spawner.onChunkLoad(event.getChunk());
    }
}
