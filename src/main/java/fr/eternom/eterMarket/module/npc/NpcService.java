package fr.eternom.eterMarket.module.npc;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.module.npc.NpcRepository.Npc;
import fr.eternom.eterMarket.module.npc.NpcRepository.Placement;
import fr.eternom.eterMarket.module.npc.NpcRepository.Role;
import fr.eternom.eterNpc.api.NpcApi;
import fr.eternom.eterNpc.api.NpcLook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Les PNJ d'EterMarket : leurs définitions (nom, skin, rôle, vente sur stock) sont ici ; EterNpc les fait apparaître
 * et garde leurs emplacements (identifiant « etermarket:<pnj> »). Une définition modifiée sur un serveur est rechargée
 * par les autres (bus réseau d'EterMarket, message "reload").
 */
public class NpcService {

    private static final String RELOAD = "reload";
    private static final String PREFIX = "etermarket:";
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Distance max pour retirer le PNJ le plus proche. */
    private static final double REMOVE_RADIUS = 5;

    private final JavaPlugin plugin;
    private final NpcRepository repository;
    private final NpcApi api;
    private final NetworkBus bus;
    private final Messages messages;
    private final Map<String, Npc> npcs = new ConcurrentHashMap<>();

    public NpcService(JavaPlugin plugin, NpcRepository repository, NpcApi api, NetworkBus bus, Messages messages) {
        this.plugin = plugin;
        this.repository = repository;
        this.api = api;
        this.bus = bus;
        this.messages = messages;
    }

    /** Démarrage : reprise des anciens emplacements, abonnement aux rechargements des autres serveurs, premier chargement. */
    public void start() {
        bus.on(RELOAD, data -> reload()); // le serveur d'origine a déjà appliqué sa propre modification
        Tasks.async(plugin, this::migratePlacements, "Emplacements d'EterMarket non repris par EterNpc");
        reload();
    }

    /**
     * Avant EterNpc, les emplacements étaient dans etermarket_placements : chacun passe à EterNpc une seule fois (la
     * ligne n'est copiée que par le serveur qui a pu la supprimer), avec le même serveur ou « tous les lobbys ».
     */
    private void migratePlacements() {
        int moved = 0;
        for (Placement placement : repository.oldPlacements()) {
            if (repository.takeOldPlacement(placement.id())) {
                api.importPlacement(PREFIX + placement.npc(), placement.server(), placement.world(),
                        placement.x(), placement.y(), placement.z(), placement.yaw());
                moved++;
            }
        }
        if (moved > 0) {
            plugin.getLogger().info(moved + " emplacement(s) de PNJ repris par EterNpc");
        }
    }

    /** Relit les définitions, et les donne à EterNpc. */
    public void reload() {
        Tasks.async(plugin, () -> {
            List<Npc> all = repository.all();
            Bukkit.getScheduler().runTask(plugin, () -> {
                npcs.keySet().stream().filter(id -> all.stream().noneMatch(npc -> npc.id().equals(id))).toList()
                        .forEach(id -> {
                            npcs.remove(id);
                            api.undefine(PREFIX + id);
                        });
                all.forEach(this::show);
            });
        }, "PNJ d'EterMarket illisibles");
    }

    public Optional<Npc> npc(String id) {
        return Optional.ofNullable(npcs.get(id));
    }

    public List<String> ids() {
        return npcs.keySet().stream().sorted().toList();
    }

    /** Le PNJ d'EterMarket derrière un identifiant d'EterNpc (« etermarket:<pnj> »). */
    public Optional<Npc> fromNpcId(String npcId) {
        return npcId.startsWith(PREFIX) ? npc(npcId.substring(PREFIX.length())) : Optional.empty();
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
        if (!npcs.containsKey(id)) {
            messages.send(admin, "npc.unknown", "npc", id);
            return;
        }
        api.place(PREFIX + id, admin.getLocation(), () -> messages.send(admin, "npc.placed", "npc", id));
    }

    /** Retire l'emplacement le plus proche (la définition reste, ainsi que ses autres emplacements). */
    public void removeNearest(Player admin) {
        api.removeNearest(admin.getLocation(), REMOVE_RADIUS, removed -> removed.ifPresentOrElse(
                npcId -> messages.send(admin, "npc.removed", "npc", npcId.startsWith(PREFIX) ? npcId.substring(PREFIX.length()) : npcId),
                () -> messages.send(admin, "npc.none-near", "radius", String.valueOf((int) REMOVE_RADIUS))));
    }

    /** Supprime la définition, tous ses emplacements et sa boutique, sur tout le réseau. */
    public void delete(CommandSender sender, String id, Runnable alsoDelete) {
        Tasks.async(plugin, () -> {
            repository.delete(id);
            api.deletePlacements(PREFIX + id);
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
        show(npc);
        Tasks.async(plugin, () -> {
            repository.save(npc);
            changed();
        }, "PNJ " + npc.id() + " non enregistré");
    }

    /** Apparence donnée à EterNpc : nom, rôle (ou métier) sous le nom, skin. */
    private void show(Npc npc) {
        npcs.put(npc.id(), npc);
        CommandSender console = Bukkit.getConsoleSender();
        Component description = npc.role() == Role.JOB
                ? messages.get(console, "npc.role.job", "job", messages.plain(console, "job.name." + npc.job()))
                : messages.get(console, "npc.role." + npc.role().name().toLowerCase(Locale.ROOT));
        api.define(plugin, new NpcLook(PREFIX + npc.id(), messages.render(npc.name(), TagResolver.empty()), description, npc.skin(),
                npc.texture() == null ? null : new NpcLook.Texture(npc.texture().value(), npc.texture().signature())));
    }

    /** Prévient les autres serveurs qu'une définition a changé. */
    private void changed() {
        bus.publish(RELOAD, new JsonObject(),
                () -> plugin.getLogger().warning("Redis injoignable : les autres serveurs verront ce PNJ après /market reload"));
    }
}
