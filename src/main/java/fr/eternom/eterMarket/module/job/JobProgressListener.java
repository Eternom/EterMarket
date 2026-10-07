package fr.eternom.eterMarket.module.job;

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
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Compte les actions des quêtes (tuer, casser, pêcher) et charge les quêtes des joueurs qui se connectent.
 * Contre la triche (gameplay plus dur, pas plus facile) :
 * - tuer : seulement les créatures apparues naturellement (pas de spawner, d'œuf, d'élevage ni de commande), tuées
 *   par le joueur ;
 * - casser : pas un bloc posé par un joueur (retenu en mémoire sur ce serveur, les plus récents d'abord), et une
 *   culture seulement si elle est mûre ;
 * - aucune action en créatif ou spectateur.
 */
public class JobProgressListener implements Listener {

    /** Blocs posés retenus (les plus anciens sont oubliés au-delà). */
    private static final int MAX_PLACED = 200_000;

    private static final Set<SpawnReason> NATURAL = Set.of(SpawnReason.NATURAL, SpawnReason.JOCKEY,
            SpawnReason.MOUNT, SpawnReason.PATROL, SpawnReason.RAID, SpawnReason.REINFORCEMENTS, SpawnReason.SLIME_SPLIT,
            SpawnReason.VILLAGE_INVASION, SpawnReason.LIGHTNING);

    private record Placed(UUID world, long key) {
    }

    private final JobProgress progress;
    private final Set<Placed> placed = new LinkedHashSet<>();

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getBlockData() instanceof Ageable) {
            return; // une culture plantée ne compte que mûre : inutile de la retenir
        }
        Placed key = new Placed(block.getWorld().getUID(), block.getBlockKey());
        placed.add(key);
        if (placed.size() > MAX_PLACED) {
            placed.remove(placed.iterator().next());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!counts(event.getPlayer()) || placed.remove(new Placed(block.getWorld().getUID(), block.getBlockKey()))) {
            return;
        }
        if (block.getBlockData() instanceof Ageable crop && crop.getAge() < crop.getMaximumAge()) {
            return;
        }
        Material type = block.getType();
        progress.record(event.getPlayer(), Kind.BREAK, objective -> objective.material() == type, 1);
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
