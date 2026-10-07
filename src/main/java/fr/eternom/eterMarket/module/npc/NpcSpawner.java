package fr.eternom.eterMarket.module.npc;

import com.destroystokyo.paper.profile.ProfileProperty;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.npc.NpcRepository.Npc;
import fr.eternom.eterMarket.module.npc.NpcRepository.Placement;
import fr.eternom.eterMarket.module.npc.NpcRepository.Role;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Les PNJ de CE serveur, en Mannequins natifs. Ils ne sont jamais enregistrés dans le monde (non persistants) :
 * ils apparaissent quand le chunk de leur emplacement se charge, disparaissent avec lui, et ne peuvent donc jamais
 * être en double après un redémarrage. Chaque Mannequin porte l'identifiant de son PNJ (données persistantes de
 * l'entité) pour reconnaître un clic. Thread principal.
 */
public class NpcSpawner {

    private final Messages messages;
    private final Logger logger;
    private final NamespacedKey npcKey;
    private final Map<String, Npc> npcs = new ConcurrentHashMap<>();
    private final Map<Long, Placement> placements = new ConcurrentHashMap<>();
    /** Emplacement -> Mannequin actuellement présent. */
    private final Map<Long, UUID> alive = new ConcurrentHashMap<>();

    public NpcSpawner(JavaPlugin plugin, Messages messages) {
        this.messages = messages;
        this.logger = plugin.getLogger();
        this.npcKey = new NamespacedKey(plugin, "npc");
    }

    /** Remplace tout (démarrage, rechargement) : les anciens Mannequins disparaissent, les nouveaux apparaissent. */
    public void load(List<Npc> definitions, List<Placement> serverPlacements) {
        despawnAll();
        npcs.clear();
        definitions.forEach(npc -> npcs.put(npc.id(), npc));
        placements.clear();
        serverPlacements.forEach(placement -> placements.put(placement.id(), placement));
        placements.values().forEach(this::spawnIfLoaded);
    }

    public Optional<Npc> npc(String id) {
        return Optional.ofNullable(npcs.get(id));
    }

    public List<String> ids() {
        return npcs.keySet().stream().sorted().toList();
    }

    /** Un PNJ modifié (nom, skin, rôle) : ses Mannequins sont mis à jour sans réapparaître. */
    public void update(Npc npc) {
        npcs.put(npc.id(), npc);
        alive.forEach((placement, uuid) -> {
            Placement where = placements.get(placement);
            if (where != null && where.npc().equals(npc.id()) && Bukkit.getEntity(uuid) instanceof Mannequin mannequin) {
                configure(mannequin, npc);
            }
        });
    }

    public void addPlacement(Placement placement) {
        placements.put(placement.id(), placement);
        spawnIfLoaded(placement);
    }

    public void removePlacement(long id) {
        placements.remove(id);
        UUID uuid = alive.remove(id);
        if (uuid != null && Bukkit.getEntity(uuid) != null) {
            Bukkit.getEntity(uuid).remove();
        }
    }

    /** Emplacement le plus proche de location, dans un rayon de radius blocs. */
    public Optional<Placement> nearest(Location location, double radius) {
        return placements.values().stream()
                .filter(placement -> placement.world().equals(location.getWorld().getName()))
                .filter(placement -> distanceSquared(placement, location) <= radius * radius)
                .min(Comparator.comparingDouble(placement -> distanceSquared(placement, location)));
    }

    /** Identifiant du PNJ représenté par cette entité, s'il en est un. */
    public Optional<String> npcOf(Entity entity) {
        return Optional.ofNullable(entity.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING));
    }

    /** Un chunk se charge : ses PNJ apparaissent. */
    public void onChunkLoad(Chunk chunk) {
        String world = chunk.getWorld().getName();
        placements.values().stream()
                .filter(placement -> placement.world().equals(world)
                        && (int) Math.floor(placement.x()) >> 4 == chunk.getX()
                        && (int) Math.floor(placement.z()) >> 4 == chunk.getZ())
                .forEach(this::spawnIfLoaded);
    }

    public void despawnAll() {
        alive.values().forEach(uuid -> {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) {
                entity.remove();
            }
        });
        alive.clear();
    }

    private void spawnIfLoaded(Placement placement) {
        Npc npc = npcs.get(placement.npc());
        World world = Bukkit.getWorld(placement.world());
        if (npc == null || world == null || !world.isChunkLoaded((int) Math.floor(placement.x()) >> 4, (int) Math.floor(placement.z()) >> 4)) {
            return;
        }
        UUID current = alive.get(placement.id());
        if (current != null && Bukkit.getEntity(current) != null && Bukkit.getEntity(current).isValid()) {
            return; // déjà là
        }
        Location location = new Location(world, placement.x(), placement.y(), placement.z(), placement.yaw(), 0);
        Mannequin mannequin = world.spawn(location, Mannequin.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setInvulnerable(true);
            spawned.setSilent(true);
            spawned.setImmovable(true);
            spawned.setRemoveWhenFarAway(false);
            spawned.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, npc.id());
            configure(spawned, npc);
        });
        if (!mannequin.isValid()) {
            // Apparition annulée par un autre plugin (protection du spawn, WorldGuard, interdiction des créatures...)
            logger.warning("PNJ " + npc.id() + " : apparition annulée par un autre plugin en " + placement.world() + " "
                    + (int) placement.x() + " " + (int) placement.y() + " " + (int) placement.z()
                    + " (protection du spawn, WorldGuard, interdiction des créatures ?)");
            return;
        }
        alive.put(placement.id(), mannequin.getUniqueId());
    }

    private void configure(Mannequin mannequin, Npc npc) {
        mannequin.customName(messages.render(npc.name(), TagResolver.empty()));
        mannequin.setCustomNameVisible(true);
        CommandSender console = Bukkit.getConsoleSender();
        mannequin.setDescription(npc.role() == Role.JOB
                ? messages.get(console, "npc.role.job", "job", messages.plain(console, "job.name." + npc.job()))
                : messages.get(console, "npc.role." + npc.role().name().toLowerCase(Locale.ROOT)));
        ResolvableProfile.Builder profile = ResolvableProfile.resolvableProfile();
        if (npc.texture() != null) {
            profile.addProperty(new ProfileProperty("textures", npc.texture().value(), npc.texture().signature()));
        } else if (npc.skin() != null) {
            profile.name(npc.skin()); // skin de ce joueur, récupéré par le jeu
        }
        mannequin.setProfile(npc.texture() == null && npc.skin() == null ? Mannequin.defaultProfile() : profile.build());
    }

    private static double distanceSquared(Placement placement, Location location) {
        double dx = placement.x() - location.getX();
        double dy = placement.y() - location.getY();
        double dz = placement.z() - location.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
