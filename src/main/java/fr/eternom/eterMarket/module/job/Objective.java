package fr.eternom.eterMarket.module.job;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Un objectif de quête.
 * - ITEM : livrer des objets au PNJ (pris dans l'inventaire au moment de valider) ;
 * - KILL, BREAK, FISH : tuer, casser, pêcher. Ces actions sont comptées pendant le jeu (progression gardée en base),
 *   puis la quête se valide au PNJ comme les autres.
 * target : la matière (ITEM, BREAK, FISH) ou le type de créature (KILL). ANY = n'importe lequel : un monstre pour KILL,
 * une prise pour FISH. Une quête a au plus UN objectif d'action (une seule progression par quête).
 * En base : "KIND:TARGET:AMOUNT", séparés par des ';' (ex : "ITEM:IRON_INGOT:16;ITEM:GOLD_INGOT:8").
 */
public record Objective(Kind kind, String target, int amount) {

    public enum Kind {
        ITEM, KILL, BREAK, FISH;

        public boolean isAction() {
            return this != ITEM;
        }
    }

    public static final String ANY = "ANY";

    public static Objective item(Material material, int amount) {
        return new Objective(Kind.ITEM, material.name(), amount);
    }

    /** Matière visée (ITEM, BREAK, FISH), null pour ANY ou une matière inconnue. */
    public Material material() {
        return kind == Kind.KILL || target.equals(ANY) ? null : Material.matchMaterial(target);
    }

    /** Créature visée (KILL), null pour ANY ou un type inconnu. */
    public EntityType entity() {
        if (kind != Kind.KILL || target.equals(ANY)) {
            return null;
        }
        try {
            return EntityType.valueOf(target);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean valid() {
        if (amount < 1) {
            return false;
        }
        return switch (kind) {
            case ITEM -> material() != null && material().isItem();
            case BREAK -> material() != null && material().isBlock();
            case FISH -> target.equals(ANY) || (material() != null && material().isItem());
            case KILL -> target.equals(ANY) || (entity() != null && entity().isAlive());
        };
    }

    /** Nom de la cible, traduit par le client (« Lingot de fer », « Zombie »...) ; null pour ANY. */
    public Component targetName() {
        if (kind == Kind.KILL) {
            EntityType entity = entity();
            return entity == null ? null : Component.translatable(entity.translationKey());
        }
        Material material = material();
        return material == null ? null : Component.translatable(material.translationKey());
    }

    /** Icône de l'objectif dans les menus. */
    public Material icon() {
        Material fallback = switch (kind) {
            case ITEM, BREAK -> Material.PAPER;
            case KILL -> Material.IRON_SWORD;
            case FISH -> Material.FISHING_ROD;
        };
        if (kind == Kind.KILL) {
            EntityType entity = entity();
            Material egg = entity == null ? null : Bukkit.getItemFactory().getSpawnEgg(entity);
            return egg == null ? fallback : egg;
        }
        Material material = material();
        if (material == null || !material.isItem()) {
            // Blocs sans objet (cultures en place) : la graine ou l'objet récolté
            return material == null ? fallback : switch (material) {
                case WHEAT -> Material.WHEAT;
                case CARROTS -> Material.CARROT;
                case POTATOES -> Material.POTATO;
                case BEETROOTS -> Material.BEETROOT;
                case COCOA -> Material.COCOA_BEANS;
                default -> fallback;
            };
        }
        return material;
    }

    public static List<Objective> parse(String text) {
        List<Objective> objectives = new ArrayList<>();
        if (text == null) {
            return objectives;
        }
        for (String part : text.split(";")) {
            String[] fields = part.trim().split(":");
            if (fields.length != 3) {
                continue;
            }
            try {
                Objective objective = new Objective(Kind.valueOf(fields[0].toUpperCase(Locale.ROOT)),
                        fields[1].toUpperCase(Locale.ROOT), Integer.parseInt(fields[2]));
                if (objective.valid()) {
                    objectives.add(objective);
                }
            } catch (IllegalArgumentException ignored) {
                // objectif illisible : ignoré, la quête garde les autres
            }
        }
        return merge(objectives);
    }

    public static String format(List<Objective> objectives) {
        return objectives.stream().map(o -> o.kind() + ":" + o.target() + ":" + o.amount()).collect(Collectors.joining(";"));
    }

    /** Regroupe les objectifs identiques (deux fois le même objet = une seule ligne, quantités additionnées). */
    static List<Objective> merge(List<Objective> objectives) {
        Map<String, Objective> merged = new LinkedHashMap<>();
        for (Objective objective : objectives) {
            merged.merge(objective.kind() + ":" + objective.target(), objective,
                    (a, b) -> new Objective(a.kind(), a.target(), a.amount() + b.amount()));
        }
        return new ArrayList<>(merged.values());
    }
}
