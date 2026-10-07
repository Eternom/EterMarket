package fr.eternom.eterMarket.module.npc;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.job.Jobs;
import fr.eternom.eterMarket.module.npc.NpcRepository.Role;
import fr.eternom.eterMarket.module.shop.ShopGui;
import fr.eternom.eterMarket.module.shop.ShopRepository;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * /market (staff, etermarket.admin) :
 * create <pnj> [job <métier> | auction] · place <pnj> · remove (le plus proche) · edit <pnj> · delete <pnj> confirm · list · reload.
 */
public class MarketCommand implements TabExecutor {

    private static final List<String> ACTIONS = List.of("create", "place", "remove", "edit", "delete", "list", "reload");

    private final NpcService npcs;
    private final ShopGui shops;
    private final ShopRepository shopRepository;
    private final Jobs jobs;
    private final Messages messages;

    public MarketCommand(NpcService npcs, ShopGui shops, ShopRepository shopRepository, Jobs jobs, Messages messages) {
        this.npcs = npcs;
        this.shops = shops;
        this.shopRepository = shopRepository;
        this.jobs = jobs;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        String id = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;
        switch (action) {
            case "list" -> {
                List<String> ids = npcs.spawner().ids();
                messages.send(sender, ids.isEmpty() ? "npc.list-empty" : "npc.list", "npcs", String.join(", ", ids));
            }
            case "reload" -> {
                npcs.reload();
                messages.send(sender, "npc.reloaded");
            }
            case "delete" -> {
                if (id == null || args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    messages.send(sender, "npc.delete-usage");
                } else {
                    npcs.delete(sender, id, () -> shopRepository.removeAll(id));
                }
            }
            case "create", "place", "edit" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "command.players-only");
                } else if (id == null) {
                    messages.send(player, "npc.usage");
                } else if (action.equals("create")) {
                    create(player, id, args);
                } else if (action.equals("place")) {
                    npcs.place(player, id);
                } else {
                    shops.openEditor(player, id, 0);
                }
            }
            case "remove" -> {
                if (sender instanceof Player player) {
                    npcs.removeNearest(player);
                } else {
                    messages.send(sender, "command.players-only");
                }
            }
            default -> messages.send(sender, "npc.usage");
        }
        return true;
    }

    /** create <pnj> : boutique ; create <pnj> job <métier> : PNJ d'un métier ; create <pnj> auction : hôtel des ventes. */
    private void create(Player player, String id, String[] args) {
        if (args.length < 3) {
            npcs.create(player, id, Role.SHOP, null);
            return;
        }
        if (args[2].equalsIgnoreCase("auction")) {
            npcs.create(player, id, Role.AUCTION, null);
            return;
        }
        String job = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "";
        if (!args[2].equalsIgnoreCase("job") || !jobs.exists(job)) {
            messages.send(player, "npc.create-usage", "jobs", String.join(", ", jobs.icons().keySet()));
            return;
        }
        npcs.create(player, id, Role.JOB, job);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(ACTIONS.stream(), args[0]);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("place", "edit", "delete").contains(action)) {
            return filter(npcs.spawner().ids().stream(), args[1]);
        }
        if (args.length == 3 && action.equals("create")) {
            return filter(Stream.of("job", "auction"), args[2]);
        }
        if (args.length == 4 && action.equals("create") && args[2].equalsIgnoreCase("job")) {
            return filter(jobs.icons().keySet().stream(), args[3]);
        }
        if (args.length == 3 && action.equals("delete")) {
            return filter(Stream.of("confirm"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(Stream<String> values, String start) {
        String prefix = start.toLowerCase(Locale.ROOT);
        return values.filter(value -> value.startsWith(prefix)).toList();
    }
}
