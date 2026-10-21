package fr.eternom.eterMarket.module.job;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterMarket.module.job.JobAdmin.Snapshot;
import fr.eternom.eterMarket.module.job.JobRepository.Member;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Choisir le métier d'un joueur (staff), 3 lignes : les métiers au milieu (l'actuel brille), retour en bas. */
class JobAdminPickMenu implements Menu {

    private static final int BACK = 22;

    private final JobAdmin admin;
    private final Messages messages;
    private final Player viewer;
    private final Snapshot target;
    private final Inventory inventory;
    private final Map<Integer, String> jobAtSlot = new HashMap<>();

    JobAdminPickMenu(JobAdmin admin, Player viewer, Snapshot target) {
        this.admin = admin;
        this.messages = admin.messages();
        this.viewer = viewer;
        this.target = target;
        this.inventory = Bukkit.createInventory(this, 27, text("job-admin.set-job.title", "player", target.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        String job = jobAtSlot.get(slot);
        if (job != null) {
            if (target.member().map(Member::job).filter(job::equals).isPresent()) {
                Sounds.refuse(player);
                return;
            }
            Sounds.click(player);
            admin.setJob(player, target, job);
        } else if (slot == BACK) {
            Sounds.page(player);
            admin.reopen(player, target);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.RED_STAINED_GLASS_PANE);
        List<String> jobs = List.copyOf(admin.service().jobs().icons().keySet());
        // Centrés sur la ligne du milieu (7 places)
        int first = 10 + Math.max(0, (7 - jobs.size()) / 2);
        for (int i = 0; i < jobs.size() && i < 7; i++) {
            String job = jobs.get(i);
            boolean current = target.member().map(Member::job).filter(job::equals).isPresent();
            jobAtSlot.put(first + i, job);
            inventory.setItem(first + i, Items.item(admin.service().jobs().icon(job),
                    text("job-admin.set-job.job", "job", admin.service().jobName(viewer, job)),
                    List.of(text(current ? "job-admin.set-job.current" : "job-admin.set-job.click")), current));
        }
        inventory.setItem(BACK, Items.item(Material.ARROW, text("menu.back"), List.of()));
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
