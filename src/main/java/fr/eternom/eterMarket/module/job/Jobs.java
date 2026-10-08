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
 * le changement de quête. Contient aussi le répertoire de départ de chaque métier, posé en base une seule fois par métier.
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
     * Répertoire de départ, calé sur les repères économiques (README d'EterEconomy) : facile ~110-140 Heloks
     * (10 min), normale ~170-210 (15-20 min), difficile ~270-320 (30 min). Une journée complète (facile, normale,
     * difficile, puis la bonus normale x 1,3) rapporte environ 850. Chaque quête mélange plusieurs choses : des actions
     * (tuer, casser, pêcher : jusqu'à 3, qu'on ne peut pas acheter) et des objets à livrer, souvent transformés.
     * À ajuster dans l'éditeur en jeu ; /market jobs reset <métier> confirm revient à cette liste.
     */
    public static Optional<List<Template>> defaults(String job) {
        return Optional.ofNullable(switch (job) {
            case "mineur" -> List.of(
                    t(Tier.EASY, 120, brk(Material.STONE, 128), item(Material.COAL, 16)),
                    t(Tier.EASY, 120, brk(Material.COAL_ORE, 16), item(Material.TORCH, 32)),
                    t(Tier.EASY, 130, brk(Material.COPPER_ORE, 12), item(Material.COPPER_INGOT, 24)),
                    t(Tier.EASY, 130, brk(Material.DIORITE, 32), brk(Material.ANDESITE, 32), brk(Material.GRANITE, 32)),
                    t(Tier.EASY, 140, brk(Material.STONE, 96), item(Material.FURNACE, 4), item(Material.STONE_BRICKS, 32)),
                    t(Tier.NORMAL, 190, brk(Material.IRON_ORE, 16), item(Material.IRON_INGOT, 16)),
                    t(Tier.NORMAL, 190, brk(Material.DEEPSLATE, 192), brk(Material.TUFF, 32), item(Material.POLISHED_DEEPSLATE, 32)),
                    t(Tier.NORMAL, 200, brk(Material.REDSTONE_ORE, 6), brk(Material.LAPIS_ORE, 4), item(Material.REDSTONE, 32),
                            item(Material.LAPIS_LAZULI, 16)),
                    t(Tier.NORMAL, 200, brk(Material.GOLD_ORE, 8), item(Material.GOLD_INGOT, 8), item(Material.RAIL, 32)),
                    t(Tier.NORMAL, 190, brk(Material.NETHERRACK, 128), brk(Material.NETHER_QUARTZ_ORE, 12), item(Material.QUARTZ, 32)),
                    t(Tier.NORMAL, 210, brk(Material.AMETHYST_CLUSTER, 8), item(Material.AMETHYST_SHARD, 24), item(Material.CALCITE, 16)),
                    t(Tier.HARD, 300, brk(Material.DEEPSLATE_DIAMOND_ORE, 4), item(Material.DIAMOND, 6), item(Material.IRON_BLOCK, 2)),
                    t(Tier.HARD, 290, brk(Material.DEEPSLATE_IRON_ORE, 24), brk(Material.DEEPSLATE_GOLD_ORE, 8),
                            brk(Material.DEEPSLATE_REDSTONE_ORE, 8), item(Material.IRON_INGOT, 16)),
                    t(Tier.HARD, 300, brk(Material.OBSIDIAN, 12), item(Material.OBSIDIAN, 12), item(Material.DIAMOND, 2)),
                    t(Tier.HARD, 320, brk(Material.ANCIENT_DEBRIS, 2), brk(Material.BASALT, 64), item(Material.NETHERITE_SCRAP, 2)),
                    t(Tier.HARD, 290, brk(Material.EMERALD_ORE, 2), item(Material.EMERALD, 4), item(Material.GOLD_BLOCK, 2)),
                    t(Tier.HARD, 280, brk(Material.BLACKSTONE, 96), brk(Material.NETHER_GOLD_ORE, 16), item(Material.GOLD_INGOT, 8)));
            case "bucheron" -> List.of(
                    t(Tier.EASY, 120, brk(Material.OAK_LOG, 48), item(Material.OAK_PLANKS, 64)),
                    t(Tier.EASY, 120, brk(Material.BIRCH_LOG, 48), item(Material.BIRCH_LOG, 16)),
                    t(Tier.EASY, 130, brk(Material.SPRUCE_LOG, 48), item(Material.CHARCOAL, 16)),
                    t(Tier.EASY, 130, brk(Material.OAK_LOG, 32), item(Material.STICK, 64), item(Material.CRAFTING_TABLE, 4)),
                    t(Tier.EASY, 140, brk(Material.OAK_LEAVES, 64), item(Material.APPLE, 2), item(Material.OAK_SAPLING, 16)),
                    t(Tier.NORMAL, 190, brk(Material.JUNGLE_LOG, 64), item(Material.JUNGLE_LOG, 32), item(Material.COCOA_BEANS, 8)),
                    t(Tier.NORMAL, 190, brk(Material.ACACIA_LOG, 64), item(Material.ACACIA_PLANKS, 64), item(Material.ACACIA_FENCE, 16)),
                    t(Tier.NORMAL, 200, brk(Material.DARK_OAK_LOG, 64), item(Material.DARK_OAK_LOG, 32), item(Material.APPLE, 4)),
                    t(Tier.NORMAL, 190, brk(Material.SPRUCE_LOG, 64), brk(Material.BIRCH_LOG, 32), item(Material.BARREL, 6)),
                    t(Tier.NORMAL, 200, brk(Material.OAK_LOG, 64), item(Material.CHARCOAL, 32), item(Material.CHEST, 8)),
                    t(Tier.NORMAL, 210, brk(Material.MANGROVE_LOG, 48), brk(Material.MANGROVE_ROOTS, 16), item(Material.MANGROVE_PROPAGULE, 8)),
                    t(Tier.HARD, 290, brk(Material.CHERRY_LOG, 64), item(Material.CHERRY_LOG, 48), item(Material.CHERRY_SAPLING, 8)),
                    t(Tier.HARD, 300, brk(Material.PALE_OAK_LOG, 64), item(Material.PALE_OAK_LOG, 32), item(Material.PALE_MOSS_BLOCK, 16)),
                    t(Tier.HARD, 290, brk(Material.DARK_OAK_LOG, 64), brk(Material.JUNGLE_LOG, 64), brk(Material.ACACIA_LOG, 64)),
                    t(Tier.HARD, 300, brk(Material.CRIMSON_STEM, 48), brk(Material.WARPED_STEM, 48), item(Material.SHROOMLIGHT, 8)),
                    t(Tier.HARD, 280, brk(Material.SPRUCE_LOG, 128), item(Material.SPRUCE_LOG, 64), item(Material.CAMPFIRE, 4)),
                    t(Tier.HARD, 310, brk(Material.OAK_LOG, 64), brk(Material.BIRCH_LOG, 64), item(Material.BOOKSHELF, 6)));
            case "fermier" -> List.of(
                    t(Tier.EASY, 120, brk(Material.WHEAT, 64), item(Material.BREAD, 16)),
                    t(Tier.EASY, 120, brk(Material.CARROTS, 64), item(Material.CARROT, 32)),
                    t(Tier.EASY, 120, brk(Material.POTATOES, 64), item(Material.BAKED_POTATO, 24)),
                    t(Tier.EASY, 130, item(Material.SUGAR_CANE, 48), item(Material.PAPER, 24)),
                    t(Tier.EASY, 140, brk(Material.WHEAT, 48), item(Material.EGG, 8), item(Material.HAY_BLOCK, 4)),
                    t(Tier.NORMAL, 190, brk(Material.BEETROOTS, 64), item(Material.BEETROOT_SOUP, 8)),
                    t(Tier.NORMAL, 190, brk(Material.PUMPKIN, 24), item(Material.PUMPKIN_PIE, 8), item(Material.CARVED_PUMPKIN, 4)),
                    t(Tier.NORMAL, 190, brk(Material.MELON, 24), item(Material.MELON_SLICE, 64), item(Material.GLISTERING_MELON_SLICE, 2)),
                    t(Tier.NORMAL, 200, brk(Material.WHEAT, 32), item(Material.LEATHER, 8), item(Material.COOKED_BEEF, 16)),
                    t(Tier.NORMAL, 200, brk(Material.WHEAT, 32), item(Material.WHITE_WOOL, 16), item(Material.COOKED_MUTTON, 8)),
                    t(Tier.NORMAL, 210, brk(Material.WHEAT, 64), brk(Material.CARROTS, 64), brk(Material.POTATOES, 64)),
                    t(Tier.HARD, 290, brk(Material.NETHER_WART, 64), item(Material.NETHER_WART, 64), item(Material.FERMENTED_SPIDER_EYE, 4)),
                    t(Tier.HARD, 290, brk(Material.COCOA, 32), item(Material.COOKIE, 32), item(Material.CAKE, 2)),
                    t(Tier.HARD, 300, brk(Material.CARROTS, 96), item(Material.GOLDEN_CARROT, 16), item(Material.RABBIT_STEW, 2)),
                    t(Tier.HARD, 290, brk(Material.POTATOES, 64), brk(Material.CARROTS, 64), item(Material.COOKED_PORKCHOP, 16),
                            item(Material.FEATHER, 16)),
                    t(Tier.HARD, 310, brk(Material.SWEET_BERRY_BUSH, 32), item(Material.HONEY_BOTTLE, 4), item(Material.HONEYCOMB, 8)),
                    t(Tier.HARD, 280, brk(Material.BEETROOTS, 64), brk(Material.MELON, 32), brk(Material.PUMPKIN, 32)));
            case "chasseur" -> List.of(
                    t(Tier.EASY, 120, kill(EntityType.ZOMBIE, 15), item(Material.ROTTEN_FLESH, 16)),
                    t(Tier.EASY, 120, kill(EntityType.SKELETON, 12), item(Material.BONE, 16)),
                    t(Tier.EASY, 130, kill(EntityType.SPIDER, 10), item(Material.STRING, 16)),
                    t(Tier.EASY, 130, kill(null, 30), item(Material.ARROW, 32)),
                    t(Tier.EASY, 140, kill(EntityType.ZOMBIE, 10), kill(EntityType.SKELETON, 10), kill(EntityType.SPIDER, 10)),
                    t(Tier.NORMAL, 190, kill(EntityType.CREEPER, 10), item(Material.GUNPOWDER, 16), item(Material.TNT, 2)),
                    t(Tier.NORMAL, 200, kill(EntityType.DROWNED, 10), kill(EntityType.HUSK, 10), item(Material.ROTTEN_FLESH, 32)),
                    t(Tier.NORMAL, 190, kill(EntityType.SPIDER, 12), item(Material.SPIDER_EYE, 8), item(Material.FERMENTED_SPIDER_EYE, 2)),
                    t(Tier.NORMAL, 200, kill(EntityType.SLIME, 15), item(Material.SLIME_BALL, 12)),
                    t(Tier.NORMAL, 210, kill(null, 60), kill(EntityType.CREEPER, 6), item(Material.BONE_MEAL, 32)),
                    t(Tier.NORMAL, 190, kill(EntityType.STRAY, 8), item(Material.ARROW, 64), item(Material.BOW, 1)),
                    t(Tier.HARD, 300, kill(EntityType.ENDERMAN, 10), item(Material.ENDER_PEARL, 8), item(Material.ENDER_EYE, 2)),
                    t(Tier.HARD, 310, kill(EntityType.BLAZE, 12), item(Material.BLAZE_ROD, 8), item(Material.BLAZE_POWDER, 8)),
                    t(Tier.HARD, 300, kill(EntityType.WITHER_SKELETON, 8), item(Material.COAL, 32), item(Material.BONE, 32)),
                    t(Tier.HARD, 290, kill(EntityType.WITCH, 3), kill(EntityType.PHANTOM, 4), item(Material.PHANTOM_MEMBRANE, 4)),
                    t(Tier.HARD, 320, kill(EntityType.GUARDIAN, 10), item(Material.PRISMARINE_SHARD, 16), item(Material.PRISMARINE_CRYSTALS, 8)),
                    t(Tier.HARD, 300, kill(EntityType.MAGMA_CUBE, 12), kill(EntityType.GHAST, 2), item(Material.MAGMA_CREAM, 8),
                            item(Material.GHAST_TEAR, 1)));
            case "pecheur" -> List.of(
                    t(Tier.EASY, 120, fish(null, 15), item(Material.COD, 12)),
                    t(Tier.EASY, 120, fish(Material.COD, 12), item(Material.COOKED_COD, 12)),
                    t(Tier.EASY, 130, fish(null, 10), kill(EntityType.SQUID, 4), item(Material.INK_SAC, 8)),
                    t(Tier.EASY, 130, fish(Material.SALMON, 6), item(Material.COOKED_SALMON, 6)),
                    t(Tier.EASY, 140, fish(null, 12), item(Material.LILY_PAD, 4), item(Material.KELP, 32)),
                    t(Tier.NORMAL, 190, fish(null, 35), item(Material.COOKED_COD, 16), item(Material.COOKED_SALMON, 8)),
                    t(Tier.NORMAL, 200, fish(Material.SALMON, 12), fish(Material.COD, 20)),
                    t(Tier.NORMAL, 190, kill(EntityType.GLOW_SQUID, 4), item(Material.GLOW_INK_SAC, 8), item(Material.SEA_PICKLE, 8)),
                    t(Tier.NORMAL, 200, fish(null, 25), item(Material.KELP, 64), item(Material.DRIED_KELP_BLOCK, 8)),
                    t(Tier.NORMAL, 210, fish(null, 30), kill(EntityType.DROWNED, 6), item(Material.COPPER_INGOT, 8)),
                    t(Tier.NORMAL, 190, fish(Material.PUFFERFISH, 3), fish(null, 20), item(Material.PUFFERFISH, 2)),
                    t(Tier.HARD, 300, fish(null, 80), item(Material.COOKED_COD, 32), item(Material.COOKED_SALMON, 16)),
                    t(Tier.HARD, 310, fish(Material.PUFFERFISH, 6), fish(Material.TROPICAL_FISH, 2), item(Material.PUFFERFISH, 2)),
                    t(Tier.HARD, 320, fish(null, 60), item(Material.NAME_TAG, 1)),
                    t(Tier.HARD, 300, fish(null, 40), item(Material.NAUTILUS_SHELL, 1), item(Material.COOKED_SALMON, 16)),
                    t(Tier.HARD, 290, kill(EntityType.GUARDIAN, 6), fish(null, 30), item(Material.PRISMARINE_SHARD, 16)),
                    t(Tier.HARD, 300, fish(Material.SALMON, 24), kill(EntityType.SQUID, 8), item(Material.BOOK, 6)));
            default -> null;
        });
    }

    /** Un objet de la boutique de départ d'un métier : amount objets (un lot) pour price Heloks. */
    public record ShopOffer(Material material, int amount, double price) {
    }

    /**
     * Boutique de départ du PNJ d'un métier : ses outils et ce qui lui sert au quotidien (un évier d'argent, les boutiques
     * ne rachètent rien). Règle d'or des repères économiques : jamais moins cher à l'unité que ce qu'un objet rapporte
     * livré en quête. Posée à la création du PNJ ; /market jobs reset <métier> confirm la remet. Modifiable ensuite
     * dans l'éditeur de la boutique.
     */
    public static List<ShopOffer> shop(String job) {
        return switch (job) {
            case "mineur" -> List.of(
                    offer(Material.IRON_PICKAXE, 1, 250),
                    offer(Material.DIAMOND_PICKAXE, 1, 1800),
                    offer(Material.IRON_SHOVEL, 1, 150),
                    offer(Material.TORCH, 32, 96),
                    offer(Material.LADDER, 16, 64),
                    offer(Material.BUCKET, 1, 150),
                    offer(Material.COOKED_BEEF, 16, 96));
            case "bucheron" -> List.of(
                    offer(Material.IRON_AXE, 1, 250),
                    offer(Material.DIAMOND_AXE, 1, 1800),
                    offer(Material.SHEARS, 1, 120),
                    offer(Material.OAK_SAPLING, 16, 64),
                    offer(Material.SPRUCE_SAPLING, 16, 64),
                    offer(Material.BONE_MEAL, 32, 128),
                    offer(Material.COOKED_BEEF, 16, 96));
            case "fermier" -> List.of(
                    offer(Material.IRON_HOE, 1, 200),
                    offer(Material.WHEAT_SEEDS, 32, 64),
                    offer(Material.BEETROOT_SEEDS, 16, 64),
                    offer(Material.MELON_SEEDS, 4, 80),
                    offer(Material.PUMPKIN_SEEDS, 4, 80),
                    offer(Material.BONE_MEAL, 32, 128),
                    offer(Material.COMPOSTER, 1, 60),
                    offer(Material.WATER_BUCKET, 1, 200));
            case "chasseur" -> List.of(
                    offer(Material.IRON_SWORD, 1, 250),
                    offer(Material.BOW, 1, 300),
                    offer(Material.ARROW, 32, 128),
                    offer(Material.SHIELD, 1, 200),
                    offer(Material.IRON_CHESTPLATE, 1, 600),
                    offer(Material.COOKED_BEEF, 16, 96));
            case "pecheur" -> List.of(
                    offer(Material.FISHING_ROD, 1, 150),
                    offer(Material.OAK_BOAT, 1, 80),
                    offer(Material.BUCKET, 1, 150),
                    offer(Material.LANTERN, 4, 80),
                    offer(Material.COOKED_BEEF, 16, 96));
            default -> List.of();
        };
    }

    private static ShopOffer offer(Material material, int amount, double price) {
        return new ShopOffer(material, amount, price);
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
