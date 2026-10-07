package fr.eternom.eterMarket.module.job;

import fr.eternom.eterMarket.module.job.JobRepository.Template;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Réglages de la guilde (config.yml > jobs) : les métiers (identifiant -> icône ; le nom affiché est dans lang/ >
 * job.name.<métier>), le coût et le délai d'un changement de métier, le nombre de quêtes par jour et la quête bonus.
 * Contient aussi le répertoire de départ de chaque métier, posé en base au premier démarrage.
 */
public record Jobs(Map<String, Material> icons, double changeCost, Duration changeCooldown, int questsPerDay,
                   boolean bonusQuest, double bonusMultiplier) {

    /** Emplacement de la quête bonus (après les quêtes du jour). */
    public static final int BONUS_SLOT = 3;

    public static Jobs load(ConfigurationSection section, Logger logger) {
        Map<String, Material> icons = new LinkedHashMap<>();
        ConfigurationSection list = section == null ? null : section.getConfigurationSection("list");
        if (list != null) {
            for (String job : list.getKeys(false)) {
                Material icon = Material.matchMaterial(list.getString(job, "PAPER"));
                if (icon == null || !icon.isItem()) {
                    logger.warning("jobs.list." + job + " : icône inconnue, PAPER utilisé");
                    icon = Material.PAPER;
                }
                icons.put(job, icon);
            }
        }
        return new Jobs(icons,
                Math.max(0, section == null ? 2000 : section.getDouble("change-cost", 2000)),
                Duration.ofDays(Math.max(0, section == null ? 7 : section.getInt("change-cooldown-days", 7))),
                Math.clamp(section == null ? 3 : section.getInt("quests-per-day", 3), 1, BONUS_SLOT),
                section == null || section.getBoolean("bonus-quest", true),
                Math.max(1, section == null ? 1.5 : section.getDouble("bonus-multiplier", 1.5)));
    }

    public boolean exists(String job) {
        return icons.containsKey(job);
    }

    public Material icon(String job) {
        return icons.getOrDefault(job, Material.PAPER);
    }

    /**
     * Répertoire de départ, calé sur les repères économiques (README d'EterEconomy) : 150 à 250 Heloks par quête,
     * environ 15 à 20 minutes de jeu chacune. À ajuster dans l'éditeur en jeu.
     */
    public static Optional<List<Template>> defaults(String job) {
        return Optional.ofNullable(switch (job) {
            case "mineur" -> List.of(t(Material.COAL, 64, 150), t(Material.COPPER_INGOT, 48, 150), t(Material.IRON_INGOT, 24, 200),
                    t(Material.GOLD_INGOT, 12, 200), t(Material.REDSTONE, 48, 150), t(Material.LAPIS_LAZULI, 32, 150),
                    t(Material.QUARTZ, 48, 200), t(Material.DIAMOND, 3, 250));
            case "bucheron" -> List.of(t(Material.OAK_LOG, 64, 150), t(Material.SPRUCE_LOG, 64, 150), t(Material.BIRCH_LOG, 64, 150),
                    t(Material.JUNGLE_LOG, 48, 160), t(Material.ACACIA_LOG, 48, 160), t(Material.DARK_OAK_LOG, 48, 160),
                    t(Material.CHERRY_LOG, 48, 170), t(Material.MANGROVE_LOG, 48, 170), t(Material.APPLE, 8, 200));
            case "fermier" -> List.of(t(Material.WHEAT, 96, 150), t(Material.CARROT, 96, 150), t(Material.POTATO, 96, 150),
                    t(Material.BEETROOT, 64, 160), t(Material.PUMPKIN, 32, 160), t(Material.MELON_SLICE, 128, 150),
                    t(Material.SUGAR_CANE, 96, 150), t(Material.COCOA_BEANS, 48, 170), t(Material.NETHER_WART, 64, 200));
            case "chasseur" -> List.of(t(Material.ROTTEN_FLESH, 48, 150), t(Material.BONE, 32, 160), t(Material.STRING, 32, 170),
                    t(Material.SPIDER_EYE, 16, 170), t(Material.GUNPOWDER, 24, 200), t(Material.SLIME_BALL, 16, 200),
                    t(Material.BLAZE_ROD, 8, 250), t(Material.ENDER_PEARL, 6, 250));
            case "pecheur" -> List.of(t(Material.COD, 32, 160), t(Material.SALMON, 24, 170), t(Material.TROPICAL_FISH, 8, 200),
                    t(Material.PUFFERFISH, 8, 200), t(Material.INK_SAC, 24, 160), t(Material.LILY_PAD, 8, 150),
                    t(Material.NAUTILUS_SHELL, 2, 250));
            default -> null;
        });
    }

    private static Template t(Material material, int amount, double reward) {
        return new Template(0, null, material, amount, reward);
    }
}
