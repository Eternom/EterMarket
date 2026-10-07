package fr.eternom.eterMarket.listeners;

import fr.eternom.eterMarket.Main;
import fr.eternom.eterMarket.module.npc.NpcListener;

public class Events {

    public Events(Main main) {
        main.getServer().getPluginManager().registerEvents(new NpcListener(main.getSpawner(), main.getShops(), main.getJobGui()), main);
    }
}
