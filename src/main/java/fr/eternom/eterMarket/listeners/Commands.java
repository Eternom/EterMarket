package fr.eternom.eterMarket.listeners;

import fr.eternom.eterMarket.Main;
import fr.eternom.eterMarket.module.npc.MarketCommand;
import org.bukkit.command.PluginCommand;

import java.util.Objects;

public class Commands {

    public Commands(Main main) {
        MarketCommand market = new MarketCommand(main, main.getNpcs(), main.getShops(), main.getShopRepository(), main.getJobService(), main.getMessages());
        PluginCommand command = Objects.requireNonNull(main.getCommand("market"), "Commande absente du plugin.yml : market");
        command.setExecutor(market);
        command.setTabCompleter(market);
    }
}
