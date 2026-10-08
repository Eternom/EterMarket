package fr.eternom.eterMarket;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.listeners.Commands;
import fr.eternom.eterMarket.listeners.Events;
import fr.eternom.eterMarket.module.auction.AuctionGui;
import fr.eternom.eterMarket.module.auction.AuctionRepository;
import fr.eternom.eterMarket.module.auction.AuctionService;
import fr.eternom.eterMarket.module.job.JobGui;
import fr.eternom.eterMarket.module.job.JobRepository;
import fr.eternom.eterMarket.module.job.JobService;
import fr.eternom.eterMarket.module.job.Jobs;
import fr.eternom.eterMarket.module.npc.NpcRepository;
import fr.eternom.eterMarket.module.npc.NpcService;
import fr.eternom.eterMarket.module.npc.NpcSpawner;
import fr.eternom.eterMarket.module.shop.ShopGui;
import fr.eternom.eterMarket.module.shop.ShopRepository;
import fr.eternom.eterMarket.module.shop.ShopService;
import fr.eternom.eterMarket.module.stock.StockRepository;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;

/**
 * Le marché du réseau : PNJ (Mannequins) définis une fois et placés partout, boutiques (vente seule), stock commun,
 * guilde des métiers (quêtes quotidiennes de livraison et d'action) et hôtel des ventes entre joueurs.
 */
public final class Main extends JavaPlugin {

    /** Version minimale d'EterLib : étiquettes du joueur (getPlayerTags) depuis 1.7.0. */
    private static final String REQUIRED_ETERLIB = "1.8.0";

    /** Préfixe des tables d'EterMarket dans la base commune : etermarket_npcs, etermarket_stock... */
    private static final String TABLE_PREFIX = "etermarket_";

    private Messages messages;
    private NpcService npcs;
    private NpcSpawner spawner;
    private ShopRepository shopRepository;
    private ShopGui shops;
    private JobGui jobGui;
    private JobService jobService;
    private AuctionGui auctions;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // En premier : vérifie la version d'EterLib (un EterLib < 1.3.0 n'a pas requireVersion, d'où le catch)
        try {
            if (!EterLib.requireVersion(this, REQUIRED_ETERLIB)) {
                return;
            }
        } catch (LinkageError tooOld) {
            getLogger().severe("EterLib " + REQUIRED_ETERLIB + " ou plus récent est nécessaire.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        EterLib lib = EterLib.get();
        messages = lib.messages(this, "en_us", "fr_fr");
        Database database = lib.database(TABLE_PREFIX);
        // Messages entre serveurs : rechargement des PNJ, « ton objet s'est vendu »
        NetworkBus bus = lib.network(this, "etermarket", messages);

        spawner = new NpcSpawner(this, messages);
        NpcRepository npcRepository = new NpcRepository(database);
        // Un lobby (EterHub installé) partage ses PNJ avec tous les lobbys : ceux créés par l'orchestrateur ont un nom neuf
        boolean lobby = getServer().getPluginManager().getPlugin("EterHub") != null;
        if (lobby) {
            npcRepository.shareWithLobbies(lib.getServerName());
        }
        npcs = new NpcService(this, npcRepository, spawner, bus, messages, lobby ? NpcRepository.LOBBIES : lib.getServerName());
        StockRepository stock = new StockRepository(database);
        shopRepository = new ShopRepository(database);
        JobRepository jobRepository = new JobRepository(database);
        Jobs jobs = Jobs.load(getConfig().getConfigurationSection("jobs"), getLogger());
        shops = new ShopGui(this, shopRepository, stock, new ShopService(this, stock, messages), npcs, jobRepository, messages,
                lib.backButton(getConfig().getString("menus.shop.back-command", "")));
        jobService = new JobService(this, jobRepository, stock, jobs, messages, zone());
        jobGui = new JobGui(this, jobService, jobRepository, messages,
                lib.backButton(getConfig().getString("menus.jobs.back-command", "")));
        // Boutiques et guilde se renvoient l'une à l'autre (onglets Quêtes / Boutique)
        shops.linkJobs(jobGui::open);
        jobGui.linkShops((player, npc) -> shops.open(player, npc.id(), 0));
        Tasks.async(this, jobService::seedCatalogs, "Répertoire de quêtes de départ non posé");

        AuctionRepository auctionRepository = new AuctionRepository(database);
        AuctionService auctionService = new AuctionService(this, auctionRepository, auctionSettings(), bus, messages);
        auctions = new AuctionGui(this, auctionRepository, auctionService, messages,
                lib.backButton(getConfig().getString("menus.auction.back-command", "")));
        auctionService.start();

        new Commands(this);
        new Events(this);
        npcs.start();
        jobService.progress().start();
    }

    @Override
    public void onDisable() {
        if (jobService != null) {
            jobService.progress().stop();
        }
        if (spawner != null) {
            spawner.despawnAll();
        }
    }

    public Messages getMessages() {
        return messages;
    }

    public NpcService getNpcs() {
        return npcs;
    }

    public NpcSpawner getSpawner() {
        return spawner;
    }

    public ShopRepository getShopRepository() {
        return shopRepository;
    }

    public ShopGui getShops() {
        return shops;
    }

    public JobGui getJobGui() {
        return jobGui;
    }

    public JobService getJobService() {
        return jobService;
    }

    public AuctionGui getAuctions() {
        return auctions;
    }

    /** config.yml > auction : durée, frais, taxe, prix min/max, annonces par défaut. */
    private AuctionService.Settings auctionSettings() {
        return new AuctionService.Settings(
                Duration.ofHours(Math.max(1, getConfig().getInt("auction.duration-hours", 48))),
                Math.clamp(getConfig().getDouble("auction.listing-fee", 0.01), 0, 1),
                Math.clamp(getConfig().getDouble("auction.tax", 0.05), 0, 1),
                Math.max(1, getConfig().getDouble("auction.min-price", 1)),
                Math.max(1, getConfig().getDouble("auction.max-price", 1_000_000)),
                Math.max(0, getConfig().getInt("auction.default-listings", 5)));
    }

    /** Fuseau du changement de jour des quêtes (jobs.time-zone), Europe/Paris si invalide. */
    private ZoneId zone() {
        String zone = getConfig().getString("jobs.time-zone", "Europe/Paris");
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            getLogger().warning("jobs.time-zone invalide (" + zone + "), Europe/Paris utilisé");
            return ZoneId.of("Europe/Paris");
        }
    }
}
