package fr.eternom.eterMarket.module.shop;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.module.npc.NpcRepository.Npc;
import fr.eternom.eterMarket.module.shop.ShopRepository.ShopItem;
import fr.eternom.eterMarket.module.stock.StockRepository;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Achat dans une boutique (les boutiques ne rachètent rien). Ordre, pour ne jamais rien donner ni prendre à tort :
 * place dans l'inventaire (sinon refus), stock commun si la boutique vend sur stock (retrait atomique), paiement
 * (retrait atomique) ; si le paiement échoue, le stock est rendu. Les objets sont donnés en dernier.
 */
public class ShopService {

    private enum Result { OK, NO_STOCK, NOT_ENOUGH }

    private final JavaPlugin plugin;
    private final StockRepository stock;
    private final Messages messages;

    public ShopService(JavaPlugin plugin, StockRepository stock, Messages messages) {
        this.plugin = plugin;
        this.stock = stock;
        this.messages = messages;
    }

    /** Thread principal. lots : nombre de lots achetés (un lot = l'objet de la boutique avec sa quantité). */
    public void buy(Player player, Npc npc, ShopItem item, int lots, Runnable after) {
        Economy economy = Money.economy();
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        List<ItemStack> stacks = stacks(item.item(), lots);
        if (!fits(player, stacks)) {
            messages.send(player, "shop.no-space");
            return;
        }
        double total = item.price() * lots;
        boolean fromStock = npc.useStock() && item.stockable();
        Material material = item.item().getType();
        long units = (long) item.item().getAmount() * lots;
        Tasks.async(plugin, player, () -> {
            if (fromStock && !stock.take(material, units)) {
                return Result.NO_STOCK;
            }
            if (!economy.withdrawPlayer(player, total).transactionSuccess()) {
                if (fromStock) {
                    stock.add(material, units); // paiement refusé : le stock est rendu
                }
                return Result.NOT_ENOUGH;
            }
            return Result.OK;
        }, result -> {
            switch (result) {
                case OK -> {
                    stacks.forEach(stack -> player.getInventory().addItem(stack)
                            .values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left)));
                    messages.send(player, "shop.bought", "amount", String.valueOf(units), "price", economy.format(total));
                    player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                }
                case NO_STOCK -> messages.send(player, "shop.no-stock");
                case NOT_ENOUGH -> messages.send(player, "shop.not-enough", "price", economy.format(total));
            }
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** lots fois l'objet, en piles de taille normale. */
    static List<ItemStack> stacks(ItemStack item, int lots) {
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = item.getAmount() * lots;
        int max = item.getMaxStackSize();
        while (remaining > 0) {
            ItemStack stack = item.clone();
            stack.setAmount(Math.min(max, remaining));
            remaining -= stack.getAmount();
            stacks.add(stack);
        }
        return stacks;
    }

    /** Essai sur une copie de l'inventaire : tout rentre-t-il ? */
    private static boolean fits(Player player, List<ItemStack> stacks) {
        Inventory copy = Bukkit.createInventory(null, 36);
        copy.setContents(player.getInventory().getStorageContents());
        return copy.addItem(stacks.stream().map(ItemStack::clone).toArray(ItemStack[]::new)).isEmpty();
    }

}
