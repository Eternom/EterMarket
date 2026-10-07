package fr.eternom.eterMarket.module.shop;

import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterMarket.helper.Inputs;
import fr.eternom.eterMarket.module.job.JobRepository;
import fr.eternom.eterMarket.module.npc.NpcRepository.Npc;
import fr.eternom.eterMarket.module.npc.NpcRepository.Texture;
import fr.eternom.eterMarket.module.npc.NpcService;
import fr.eternom.eterMarket.module.shop.ShopRepository.ShopItem;
import fr.eternom.eterMarket.module.stock.StockRepository;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Ouvre la boutique d'un PNJ (joueurs) et son éditeur (staff), et branche leurs boutons : achat, choix de la quantité,
 * ajout de l'objet en main, prix, retrait, vente sur stock, nom et skin du PNJ. Thread principal ; données lues en
 * tâche de fond avant l'ouverture.
 */
public class ShopGui {

    public static final String EDIT_PERMISSION = "etermarket.edit";
    /** Quantité max choisie dans le Dialog, en lots. */
    private static final int MAX_LOTS = 64;

    /** Ce qu'affiche un menu : la boutique, son stock (matière -> quantité) et le solde du joueur. */
    record View(Npc npc, List<ShopItem> items, Map<Material, Long> stock, double balance, Map<Material, Double> bestReward) {
    }

    private final JavaPlugin plugin;
    private final ShopRepository shops;
    private final StockRepository stock;
    private final ShopService service;
    private final NpcService npcs;
    private final Messages messages;
    private final BackButton backButton;
    private final JobRepository jobs;
    /** Quêtes et éditeur du répertoire d'un PNJ de métier : branchés par JobGui, qui vit dans un autre module. */
    private BiConsumer<Player, Npc> questsOpener = (player, npc) -> { };
    private BiConsumer<Player, Npc> catalogOpener = (player, npc) -> { };

    public ShopGui(JavaPlugin plugin, ShopRepository shops, StockRepository stock, ShopService service, NpcService npcs,
                   JobRepository jobs, Messages messages, BackButton backButton) {
        this.jobs = jobs;
        this.plugin = plugin;
        this.shops = shops;
        this.stock = stock;
        this.service = service;
        this.npcs = npcs;
        this.messages = messages;
        this.backButton = backButton;
    }

    public void open(Player player, String npcId, int page) {
        load(player, npcId, false, view -> player.openInventory(new ShopMenu(this, player, view, page).getInventory()));
    }

    public void openEditor(Player player, String npcId, int page) {
        load(player, npcId, true, view -> player.openInventory(new ShopEditorMenu(this, player, view, page).getInventory()));
    }

    private void load(Player player, String npcId, boolean editor, Consumer<View> then) {
        Npc npc = npcs.spawner().npc(npcId).orElse(null);
        if (npc == null) {
            messages.send(player, "npc.unknown", "npc", npcId);
            return;
        }
        Economy economy = Money.economy();
        Tasks.async(plugin, player, () -> {
            List<ShopItem> items = shops.items(npcId);
            Map<Material, Long> amounts = new HashMap<>();
            if (editor || npc.useStock()) {
                items.stream().filter(ShopItem::stockable).map(item -> item.item().getType()).distinct()
                        .forEach(material -> amounts.put(material, stock.amount(material)));
            }
            // Éditeur : meilleure récompense par unité de chaque matière dans les quêtes (contrôle d'arbitrage)
            Map<Material, Double> bestReward = new HashMap<>();
            if (editor) {
                jobs.catalog().forEach(template -> template.unitRewards().forEach((material, reward) -> bestReward.merge(material, reward, Math::max)));
            }
            return new View(npc, items, amounts, economy == null ? 0 : economy.getBalance(player), bestReward);
        }, then, () -> messages.send(player, "error.generic"));
    }

    public void linkJobs(BiConsumer<Player, Npc> questsOpener, BiConsumer<Player, Npc> catalogOpener) {
        this.questsOpener = questsOpener;
        this.catalogOpener = catalogOpener;
    }

    void openQuests(Player player, Npc npc) {
        questsOpener.accept(player, npc);
    }

    void openCatalog(Player player, Npc npc) {
        catalogOpener.accept(player, npc);
    }

    // ---------- Joueurs ----------

    void buy(Player player, View view, ShopItem item, int lots, int page) {
        service.buy(player, view.npc(), item, lots, () -> open(player, view.npc().id(), page));
    }

    void askQuantity(Player player, View view, ShopItem item, int page) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "shop.quantity.title"))
                .body(List.of(DialogBody.item(item.item().clone()).build(),
                        DialogBody.plainMessage(messages.get(player, "shop.quantity.body", "price", Money.format(item.price())))))
                .inputs(List.of(DialogInput.numberRange("lots", messages.get(player, "shop.quantity.label"), 1, MAX_LOTS)
                        .step(1f).initial(1f).build()))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "shop.quantity.confirm"), messages.get(player, "dialog.cancel"),
                response -> {
                    Float lots = response.getFloat("lots");
                    buy(player, view, item, lots == null ? 1 : Math.max(1, Math.round(lots)), page);
                },
                () -> open(player, view.npc().id(), page));
    }

    // ---------- Éditeur ----------

    void addHeld(Player admin, View view, int page) {
        ItemStack held = admin.getInventory().getItemInMainHand().clone();
        if (held.isEmpty()) {
            messages.send(admin, "editor.hold-item");
            return;
        }
        askPrice(admin, view, held, 0, price -> Tasks.async(plugin, admin, () -> {
            shops.add(view.npc().id(), held, price);
            return price;
        }, added -> {
            messages.send(admin, "editor.added", "price", Money.format(added));
            openEditor(admin, view.npc().id(), page);
        }, () -> messages.send(admin, "error.generic")), page);
    }

    void changePrice(Player admin, View view, ShopItem item, int page) {
        askPrice(admin, view, item.item(), item.price(), price -> Tasks.async(plugin, admin, () -> {
            shops.setPrice(item.npc(), item.position(), price);
            return price;
        }, changed -> openEditor(admin, view.npc().id(), page), () -> messages.send(admin, "error.generic")), page);
    }

    void confirmRemove(Player admin, View view, ShopItem item, int page) {
        admin.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(admin, "editor.remove.title"))
                .body(List.of(DialogBody.item(item.item().clone()).build(),
                        DialogBody.plainMessage(messages.get(admin, "editor.remove.body"))))
                .build();
        Dialogs.show(plugin, admin, base, messages.get(admin, "editor.remove.confirm"), messages.get(admin, "dialog.cancel"),
                response -> Tasks.async(plugin, admin, () -> {
                    shops.remove(item.npc(), item.position());
                    return true;
                }, removed -> openEditor(admin, view.npc().id(), page), () -> messages.send(admin, "error.generic")),
                () -> openEditor(admin, view.npc().id(), page));
    }

    void toggleStock(Player admin, View view, int page) {
        Npc npc = view.npc();
        npcs.save(npc.withUseStock(!npc.useStock()));
        messages.send(admin, npc.useStock() ? "editor.stock.disabled" : "editor.stock.enabled");
        openEditor(admin, npc.id(), page);
    }

    void rename(Player admin, View view, int page) {
        admin.closeInventory();
        Npc npc = view.npc();
        DialogBase base = DialogBase.builder(messages.get(admin, "editor.rename.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(admin, "editor.rename.body"))))
                .inputs(List.of(DialogInput.text("name", messages.get(admin, "editor.rename.label"))
                        .initial(npc.name()).maxLength(255).build()))
                .build();
        Dialogs.show(plugin, admin, base, messages.get(admin, "editor.save"), messages.get(admin, "dialog.cancel"), response -> {
            String name = response.getText("name");
            if (name != null && !name.isBlank()) {
                npcs.save(npc.withName(name.trim()));
            }
            openEditor(admin, npc.id(), page);
        }, () -> openEditor(admin, npc.id(), page));
    }

    /** Skin : pseudo d'un joueur, ou texture précise (valeur + signature, ex : MineSkin), prioritaire. */
    void changeSkin(Player admin, View view, int page) {
        admin.closeInventory();
        Npc npc = view.npc();
        DialogBase base = DialogBase.builder(messages.get(admin, "editor.skin.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(admin, "editor.skin.body"))))
                .inputs(List.of(
                        DialogInput.text("skin", messages.get(admin, "editor.skin.player")).initial(npc.skin() == null ? "" : npc.skin())
                                .maxLength(16).build(),
                        DialogInput.text("value", messages.get(admin, "editor.skin.value")).maxLength(4096).build(),
                        DialogInput.text("signature", messages.get(admin, "editor.skin.signature")).maxLength(4096).build()))
                .build();
        Dialogs.show(plugin, admin, base, messages.get(admin, "editor.save"), messages.get(admin, "dialog.cancel"), response -> {
            String skin = blankToNull(response.getText("skin"));
            String value = blankToNull(response.getText("value"));
            String signature = blankToNull(response.getText("signature"));
            // Texture laissée vide : on garde l'ancienne seulement si aucun pseudo n'est donné
            Texture texture = value != null ? new Texture(value, signature) : skin != null ? null : npc.texture();
            npcs.save(npc.withSkin(skin, texture));
            openEditor(admin, npc.id(), page);
        }, () -> openEditor(admin, npc.id(), page));
    }

    private void askPrice(Player admin, View view, ItemStack item, double current, Consumer<Double> onPrice, int page) {
        admin.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(admin, "editor.price.title"))
                .body(List.of(DialogBody.item(item.clone()).build(),
                        DialogBody.plainMessage(messages.get(admin, "editor.price.body", "amount", String.valueOf(item.getAmount())))))
                .inputs(List.of(DialogInput.text("price", messages.get(admin, "editor.price.label"))
                        .initial(current > 0 ? String.valueOf((long) current) : "").maxLength(12).build()))
                .build();
        Dialogs.show(plugin, admin, base, messages.get(admin, "editor.save"), messages.get(admin, "dialog.cancel"), response -> {
            double price = Inputs.number(response.getText("price"));
            if (price <= 0) {
                messages.send(admin, "editor.price.invalid");
                openEditor(admin, view.npc().id(), page);
                return;
            }
            onPrice.accept(price);
        }, () -> openEditor(admin, view.npc().id(), page));
    }

    // ---------- Outils ----------


    Messages messages() {
        return messages;
    }

    BackButton backButton() {
        return backButton;
    }


    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
