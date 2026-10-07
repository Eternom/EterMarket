package fr.eternom.eterMarket.module.job;

import fr.eternom.eterMarket.module.job.JobRepository.Template;
import fr.eternom.eterMarket.module.job.Objective.Kind;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Réglages de la guilde (config.yml > jobs) : les métiers (identifiant -> icône ; le nom affiché est dans lang/ >
 * job.name.<métier>), le coût et le délai d'un changement de métier, les niveaux des quêtes du jour, la quête bonus et
 * le changement de quête. Contient aussi le répertoire de départ de chaque métier, posé en base au premier démarrage.
 */
public record Jobs(Map<String, Material> icons, double changeCost, Duration changeCooldown, List<Tier> daily,
                   boolean bonusQuest, Tier bonusTier, double bonusMultiplier, double rerollCost) {

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
        List<Tier> daily = new ArrayList<>();
        List<String> configured = section == null || !section.isList("daily") ? List.of("easy", "normal", "hard") : section.getStringList("daily");
        for (String id : configured) {
            Tier tier = Tier.of(id);
            if (tier == null) {
                logger.warning("jobs.daily : niveau inconnu « " + id + " » (easy, normal ou hard)");
            } else if (daily.size() < BONUS_SLOT) {
                daily.add(tier);
            }
        }
        if (daily.isEmpty()) {
            daily = List.of(Tier.EASY, Tier.NORMAL, Tier.HARD);
        }
        Tier bonusTier = Tier.of(section == null ? null : section.getString("bonus-tier", "normal"));
        return new Jobs(icons,
                Math.max(0, section == null ? 2000 : section.getDouble("change-cost", 2000)),
                Duration.ofDays(Math.max(0, section == null ? 7 : section.getInt("change-cooldown-days", 7))),
                List.copyOf(daily),
                section == null || section.getBoolean("bonus-quest", true),
                bonusTier == null ? Tier.NORMAL : bonusTier,
                Math.max(1, section == null ? 1.3 : section.getDouble("bonus-multiplier", 1.3)),
                section == null ? 150 : section.getDouble("reroll-cost", 150));
    }

    public boolean exists(String job) {
        return icons.containsKey(job);
    }

    public Material icon(String job) {
        return icons.getOrDefault(job, Material.PAPER);
    }

    /** Changer une quête par jour : désactivé si reroll-cost est négatif. */
    public boolean rerollEnabled() {
        return rerollCost >= 0;
    }

    /**
     * Répertoire de départ, calé sur les repères économiques (README d'EterEconomy) : facile ~100-130 Heloks
     * (5 à 10 min), normale ~160-200 (15 min), difficile ~250-300 (25-30 min). Une journée complète (facile, normale,
     * difficile, puis la bonus normale x 1,3) rapporte environ 800. Des objets transformés (cuits, fabriqués) et des
     * commandes à plusieurs objets pour varier ; des actions (tuer, casser, pêcher) qu'on ne peut pas acheter.
     * À ajuster dans l'éditeur en jeu ; /market jobs reset <métier> revient à cette liste.
     */
    public static Optional<List<Template>> defaults(String job) {
        return Optional.ofNullable(switch (job) {
            case "mineur" -> List.of(
                    t(Tier.EASY, 110, item(Material.COAL, 32)),
                    t(Tier.EASY, 110, item(Material.COPPER_INGOT, 32)),
                    t(Tier.EASY, 110, item(Material.REDSTONE, 32)),
                    t(Tier.EASY, 110, item(Material.LAPIS_LAZULI, 24)),
                    t(Tier.EASY, 120, brk(Material.STONE, 192)),
                    t(Tier.NORMAL, 180, item(Material.IRON_INGOT, 24)),
                    t(Tier.NORMAL, 180, item(Material.GOLD_INGOT, 12)),
                    t(Tier.NORMAL, 170, item(Material.QUARTZ, 48)),
                    t(Tier.NORMAL, 170, item(Material.AMETHYST_SHARD, 24)),
                    t(Tier.NORMAL, 170, item(Material.SMOOTH_STONE, 64)),
                    t(Tier.NORMAL, 180, brk(Material.DEEPSLATE, 256)),
                    t(Tier.NORMAL, 190, item(Material.IRON_INGOT, 8), item(Material.GOLD_INGOT, 4), item(Material.COAL, 16)),
                    t(Tier.HARD, 280, item(Material.DIAMOND, 4)),
                    t(Tier.HARD, 280, item(Material.EMERALD, 6)),
                    t(Tier.HARD, 260, item(Material.OBSIDIAN, 16)),
                    t(Tier.HARD, 300, item(Material.ANCIENT_DEBRIS, 1)),
                    t(Tier.HARD, 290, item(Material.IRON_BLOCK, 2), item(Material.GOLD_BLOCK, 1), item(Material.REDSTONE_BLOCK, 4)),
                    t(Tier.HARD, 290, item(Material.DIAMOND, 2), item(Material.OBSIDIAN, 8)));
            case "bucheron" -> List.of(
                    t(Tier.EASY, 110, item(Material.OAK_LOG, 48)),
                    t(Tier.EASY, 110, item(Material.BIRCH_LOG, 48)),
                    t(Tier.EASY, 110, item(Material.SPRUCE_LOG, 48)),
                    t(Tier.EASY, 120, item(Material.CHARCOAL, 32)),
                    t(Tier.EASY, 120, brk(Material.OAK_LOG, 64)),
                    t(Tier.NORMAL, 170, item(Material.JUNGLE_LOG, 48)),
                    t(Tier.NORMAL, 170, item(Material.ACACIA_LOG, 48)),
                    t(Tier.NORMAL, 170, item(Material.DARK_OAK_LOG, 48)),
                    t(Tier.NORMAL, 190, item(Material.APPLE, 6)),
                    t(Tier.NORMAL, 170, brk(Material.SPRUCE_LOG, 96)),
                    t(Tier.NORMAL, 190, item(Material.OAK_PLANKS, 64), item(Material.STICK, 32), item(Material.CHARCOAL, 16)),
                    t(Tier.HARD, 260, item(Material.CHERRY_LOG, 64)),
                    t(Tier.HARD, 260, item(Material.MANGROVE_LOG, 64)),
                    t(Tier.HARD, 280, item(Material.PALE_OAK_LOG, 48)),
                    t(Tier.HARD, 260, brk(Material.JUNGLE_LOG, 128)),
                    t(Tier.HARD, 290, item(Material.DARK_OAK_LOG, 32), item(Material.JUNGLE_LOG, 32), item(Material.ACACIA_LOG, 32)));
            case "fermier" -> List.of(
                    t(Tier.EASY, 110, item(Material.WHEAT, 64)),
                    t(Tier.EASY, 110, item(Material.CARROT, 64)),
                    t(Tier.EASY, 110, item(Material.POTATO, 64)),
                    t(Tier.EASY, 110, item(Material.SUGAR_CANE, 64)),
                    t(Tier.EASY, 120, brk(Material.WHEAT, 96)),
                    t(Tier.NORMAL, 170, item(Material.BEETROOT, 64)),
                    t(Tier.NORMAL, 170, item(Material.PUMPKIN, 24)),
                    t(Tier.NORMAL, 170, item(Material.MELON_SLICE, 128)),
                    t(Tier.NORMAL, 170, item(Material.SWEET_BERRIES, 48)),
                    t(Tier.NORMAL, 180, item(Material.BREAD, 32)),
                    t(Tier.NORMAL, 180, brk(Material.CARROTS, 128)),
                    t(Tier.NORMAL, 190, item(Material.EGG, 16), item(Material.LEATHER, 8), item(Material.WHEAT, 32)),
                    t(Tier.HARD, 260, item(Material.NETHER_WART, 64)),
                    t(Tier.HARD, 260, item(Material.COCOA_BEANS, 48)),
                    t(Tier.HARD, 270, item(Material.PUMPKIN_PIE, 16)),
                    t(Tier.HARD, 290, item(Material.GOLDEN_CARROT, 16)),
                    t(Tier.HARD, 280, item(Material.HAY_BLOCK, 8), item(Material.PUMPKIN, 16), item(Material.MELON, 8)));
            case "chasseur" -> List.of(
                    t(Tier.EASY, 110, item(Material.ROTTEN_FLESH, 32)),
                    t(Tier.EASY, 110, item(Material.BONE, 24)),
                    t(Tier.EASY, 110, item(Material.STRING, 16)),
                    t(Tier.EASY, 120, kill(EntityType.ZOMBIE, 20)),
                    t(Tier.EASY, 120, kill(EntityType.SKELETON, 15)),
                    t(Tier.NORMAL, 180, item(Material.GUNPOWDER, 16)),
                    t(Tier.NORMAL, 170, item(Material.SPIDER_EYE, 12)),
                    t(Tier.NORMAL, 170, item(Material.ARROW, 64)),
                    t(Tier.NORMAL, 180, kill(EntityType.CREEPER, 12)),
                    t(Tier.NORMAL, 180, kill(null, 60)),
                    t(Tier.NORMAL, 190, kill(EntityType.SPIDER, 15), item(Material.STRING, 16)),
                    t(Tier.HARD, 270, item(Material.BLAZE_ROD, 8)),
                    t(Tier.HARD, 270, item(Material.ENDER_PEARL, 6)),
                    t(Tier.HARD, 280, kill(EntityType.ENDERMAN, 10)),
                    t(Tier.HARD, 280, kill(EntityType.WITCH, 3)),
                    t(Tier.HARD, 290, item(Material.SLIME_BALL, 12), item(Material.PHANTOM_MEMBRANE, 4)),
                    t(Tier.HARD, 300, kill(EntityType.BLAZE, 15), item(Material.BLAZE_ROD, 6)));
            case "pecheur" -> List.of(
                    t(Tier.EASY, 110, item(Material.COD, 24)),
                    t(Tier.EASY, 120, item(Material.SALMON, 12)),
                    t(Tier.EASY, 110, item(Material.INK_SAC, 16)),
                    t(Tier.EASY, 110, item(Material.LILY_PAD, 6)),
                    t(Tier.EASY, 120, fish(null, 20)),
                    t(Tier.NORMAL, 170, item(Material.SALMON, 24)),
                    t(Tier.NORMAL, 170, item(Material.COOKED_COD, 24)),
                    t(Tier.NORMAL, 180, item(Material.GLOW_INK_SAC, 12)),
                    t(Tier.NORMAL, 190, item(Material.TROPICAL_FISH, 6)),
                    t(Tier.NORMAL, 180, fish(null, 50)),
                    t(Tier.NORMAL, 190, item(Material.COOKED_COD, 16), item(Material.COOKED_SALMON, 16)),
                    t(Tier.HARD, 270, item(Material.PUFFERFISH, 8)),
                    t(Tier.HARD, 280, item(Material.NAUTILUS_SHELL, 2)),
                    t(Tier.HARD, 300, item(Material.NAME_TAG, 1)),
                    t(Tier.HARD, 280, fish(null, 120)),
                    t(Tier.HARD, 290, item(Material.TROPICAL_FISH, 4), item(Material.PUFFERFISH, 4), item(Material.SALMON, 16)));
            default -> null;
        });
    }

    private static Template t(Tier tier, double reward, Objective... objectives) {
        return new Template(0, null, tier, List.of(objectives), reward);
    }

    private static Objective item(Material material, int amount) {
        return Objective.item(material, amount);
    }

    private static Objective brk(Material block, int amount) {
        return new Objective(Kind.BREAK, block.name(), amount);
    }

    /** entity null = n'importe quel monstre. */
    private static Objective kill(EntityType entity, int amount) {
        return new Objective(Kind.KILL, entity == null ? Objective.ANY : entity.name(), amount);
    }

    /** material null = n'importe quelle prise. */
    private static Objective fish(Material material, int amount) {
        return new Objective(Kind.FISH, material == null ? Objective.ANY : material.name(), amount);
    }
}
