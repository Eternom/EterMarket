package fr.eternom.eterMarket;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterMarket.listeners.Commands;
import fr.eternom.eterMarket.listeners.Events;
import fr.eternom.eterMarket.module.npc.NpcRepository;
import fr.eternom.eterMarket.module.npc.NpcService;
import fr.eternom.eterMarket.module.npc.NpcSpawner;
import fr.eternom.eterMarket.module.shop.ShopGui;
import fr.eternom.eterMarket.module.shop.ShopRepository;
import fr.eternom.eterMarket.module.shop.ShopService;
import fr.eternom.eterMarket.module.stock.StockRepository;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Le marché du réseau : PNJ (Mannequins) définis une fois et placés partout, boutiques (vente seule), stock commun.
 * Viendront ensuite la guilde des métiers (quêtes) et l'hôtel des ventes.
 */
public final class Main extends JavaPlugin {

    /** Version minimale d'EterLib : Dialogs communs depuis 1.5.2. */
    private static final String REQUIRED_ETERLIB = "1.5.2";

    /** Préfixe des tables d'EterMarket dans la base commune : etermarket_npcs, etermarket_stock... */
    private static final String TABLE_PREFIX = "etermarket_";

    private Messages messages;
    private NpcService npcs;
    private NpcSpawner spawner;
    private ShopRepository shopRepository;
    private ShopGui shops;

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

        spawner = new NpcSpawner(this, messages);
        npcs = new NpcService(this, new NpcRepository(database), spawner, lib.getMessenger(), messages, lib.getServerName());
        StockRepository stock = new StockRepository(database);
        shopRepository = new ShopRepository(database);
        shops = new ShopGui(this, shopRepository, stock, new ShopService(this, stock, messages), npcs, messages,
                lib.backButton(getConfig().getString("menus.shop.back-command", "")));

        new Commands(this);
        new Events(this);
        npcs.start();
    }

    @Override
    public void onDisable() {
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
}
