package fr.eternom.eterMarket.module.job;

import org.bukkit.Material;

import java.util.Locale;

/** Difficulté d'une quête. Le nom affiché est dans lang/ (quest.tier.<id>). */
public enum Tier {
    EASY(Material.LIME_DYE), NORMAL(Material.YELLOW_DYE), HARD(Material.RED_DYE);

    private final Material icon;

    Tier(Material icon) {
        this.icon = icon;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Material icon() {
        return icon;
    }

    /** easy / normal / hard ; null si inconnu. */
    public static Tier of(String id) {
        if (id == null) {
            return null;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
