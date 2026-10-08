package fr.eternom.eterMarket.module.npc;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.module.npc.NpcRepository.Npc;
import fr.eternom.eterMarket.module.npc.NpcRepository.Placement;
import fr.eternom.eterMarket.module.npc.NpcRepository.Role;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Gestion des PNJ par le staff : créer une définition, la placer ici, retirer un emplacement, modifier nom, skin et
 * vente sur stock, supprimer. Une définition modifiée sur un serveur est rechargée par les autres (bus réseau
 * d'EterMarket, message "reload").
 */
public class NpcService {

    private static final String RELOAD = "reload";
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Distance max pour retirer le PNJ le plus proche. */
    private static final double REMOVE_RADIUS = 5;

    private final JavaPlugin plugin;
    private final NpcRepository repository;
    private final NpcSpawner spawner;
    private final NetworkBus bus;
    private final Messages messages;
    /** Clé des emplacements de ce serveur : son nom, ou NpcRepository.LOBBIES sur un lobby (communs à tous les lobbys). */
    private final String serverName;

    public NpcService(JavaPlugin plugin, NpcRepository repository, NpcSpawner spawner, NetworkBus bus,
                      Messages messages, String serverName) {
        this.plugin = plugin;
        this.repository = repository;
        this.spawner = spawner;
        this.bus = bus;
        this.messages = messages;
        this.serverName = serverName;
    }

    /** Démarrage : abonnement aux rechargements des autres serveurs, puis premier chargement. */
    public void start() {
        spawner.start();
        bus.on(RELOAD, data -> reload()); // le serveur d'origine a déjà appliqué sa propre modification
        reload();
    }

    /** Relit définitions et emplacements de ce serveur, puis fait réapparaître les PNJ. */
    public void reload() {
        Tasks.async(plugin, () -> {
            List<Npc> npcs = repository.all();
            List<Placement> placements = repository.placements(serverName);
            Bukkit.getScheduler().runTask(plugin, () -> spawner.load(npcs, placements));
        }, "PNJ d'EterMarket illisibles");
    }

    /**
     * @param job        métier dont ce PNJ est la référence (rôle JOB), null pour une boutique
     * @param alsoCreate en tâche de fond, une fois le PNJ créé (ex : la boutique de départ d'un PNJ de métier)
     */
    public void create(Player admin, String id, Role role, String job, Runnable alsoCreate) {
        if (!ID.matcher(id).matches()) {
            messages.send(admin, "npc.invalid-id");
            return;
        }
        Tasks.async(plugin, admin, () -> {
            boolean created = repository.create(id, role, job);
            if (created) {
                alsoCreate.run();
            }
            return created;
        }, created -> {
            if (!created) {
                messages.send(admin, "npc.exists", "npc", id);
                return;
            }
            messages.send(admin, "npc.created", "npc", id);
            reload();
            changed();
        }, () -> messages.send(admin, "error.generic"));
    }

    /** Place le PNJ là où se trouve le staff, en regardant dans la même direction. */
    public void place(Player admin, String id) {
        if (spawner.npc(id).isEmpty()) {
            messages.send(admin, "npc.unknown", "npc", id);
            return;
        }
        Location at = admin.getLocation();
        String world = at.getWorld().getName();
        Tasks.async(plugin, admin, () -> repository.place(id, serverName, world, at.getX(), at.getY(), at.getZ(), at.getYaw()),
                placementId -> {
                    spawner.addPlacement(new Placement(placementId, id, serverName, world, at.getX(), at.getY(), at.getZ(), at.getYaw()));
                    messages.send(admin, "npc.placed", "npc", id);
                    changed(); // sur un lobby : les autres lobbys l'affichent aussi
                }, () -> messages.send(admin, "error.generic"));
    }

    /** Retire l'emplacement le plus proche (la définition reste, ainsi que ses autres emplacements). */
    public void removeNearest(Player admin) {
        spawner.nearest(admin.getLocation(), REMOVE_RADIUS).ifPresentOrElse(placement ->
                Tasks.async(plugin, admin, () -> {
                    repository.removePlacement(placement.id());
                    return placement;
                }, removed -> {
                    spawner.removePlacement(removed.id());
                    messages.send(admin, "npc.removed", "npc", removed.npc());
                    changed();
                }, () -> messages.send(admin, "error.generic")),
                () -> messages.send(admin, "npc.none-near", "radius", String.valueOf((int) REMOVE_RADIUS)));
    }

    /** Supprime la définition, tous ses emplacements et sa boutique, sur tout le réseau. */
    public void delete(CommandSender sender, String id, Runnable alsoDelete) {
        Tasks.async(plugin, () -> {
            repository.delete(id);
            alsoDelete.run();
            Bukkit.getScheduler().runTask(plugin, () -> {
                messages.send(sender, "npc.deleted", "npc", id);
                reload();
                changed();
            });
        }, "Suppression du PNJ " + id);
    }

    /** Enregistre une définition modifiée (éditeur) : mise à jour ici tout de suite, puis sur les autres serveurs. */
    public void save(Npc npc) {
        spawner.update(npc);
        Tasks.async(plugin, () -> {
            repository.save(npc);
            changed();
        }, "PNJ " + npc.id() + " non enregistré");
    }

    public NpcSpawner spawner() {
        return spawner;
    }

    /** Prévient les autres serveurs qu'une définition a changé. */
    private void changed() {
        bus.publish(RELOAD, new JsonObject(),
                () -> plugin.getLogger().warning("Redis injoignable : les autres serveurs verront ce PNJ après /market reload"));
    }
}
