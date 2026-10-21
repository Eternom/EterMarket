package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterMarket.module.job.Objective.Kind;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;

/**
 * Compte les actions des quêtes (tuer, casser, pêcher) et charge les quêtes des joueurs qui se connectent.
 * Contre la triche (gameplay plus dur, pas plus facile) :
 * - tuer : seulement les créatures apparues naturellement (pas de spawner, d'œuf, d'élevage ni de commande), tuées
 *   par le joueur ;
 * - casser : pas un bloc posé par un joueur (EterLib PlacedBlocks : noté dans le chunk, gardé après un redémarrage), et
 *   une culture seulement si elle est mûre ; un minerai et sa version des abîmes comptent pareil ;
 * - aucune action en créatif ou spectateur.
 */
public class JobProgressListener implements Listener {

    private static final Set<SpawnReason> NATURAL = Set.of(SpawnReason.NATURAL, SpawnReason.JOCKEY,
            SpawnReason.MOUNT, SpawnReason.PATROL, SpawnReason.RAID, SpawnReason.REINFORCEMENTS, SpawnReason.SLIME_SPLIT,
            SpawnReason.VILLAGE_INVASION, SpawnReason.LIGHTNING);

    private final JobProgress progress;

    public JobProgressListener(JobProgress progress) {
        this.progress = progress;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        progress.join(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        progress.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || !counts(killer) || entity instanceof Player || !NATURAL.contains(entity.getEntitySpawnReason())) {
            return;
        }
        boolean enemy = entity instanceof Enemy;
        progress.record(killer, Kind.KILL, objective -> objective.target().equals(Objective.ANY)
                ? enemy : entity.getType() == objective.entity(), 1);
    }

    /** HIGH : après les protections (claims), avant qu'EterLib retire la note « posé » (MONITOR). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!counts(event.getPlayer()) || EterLib.get().getPlacedBlocks().isPlaced(block)) {
            return;
        }
        if (block.getBlockData() instanceof Ageable crop && crop.getAge() < crop.getMaximumAge()) {
            return;
        }
        Material type = oreFamily(block.getType());
        progress.record(event.getPlayer(), Kind.BREAK, objective -> objective.material() != null
                && oreFamily(objective.material()) == type, 1);
    }

    /**
     * Un minerai et sa version des abîmes comptent pareil (« casse 6 minerais de redstone » : le normal ne se trouve plus
     * qu'entre les couches 0 et 15, la version des abîmes partout en dessous). DEEPSLATE_X_ORE -> X_ORE ; autre bloc : lui-même.
     */
    static Material oreFamily(Material material) {
        String name = material.name();
        if (name.startsWith("DEEPSLATE_") && name.endsWith("_ORE")) {
            Material ore = Material.getMaterial(name.substring("DEEPSLATE_".length()));
            return ore == null ? material : ore;
        }
        return material;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)
                || !counts(event.getPlayer())) {
            return;
        }
        Material type = caught.getItemStack().getType();
        int amount = caught.getItemStack().getAmount();
        progress.record(event.getPlayer(), Kind.FISH, objective -> objective.target().equals(Objective.ANY)
                || objective.material() == type, amount);
    }

    private static boolean counts(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }
}
