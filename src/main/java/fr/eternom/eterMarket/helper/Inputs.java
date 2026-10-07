package fr.eternom.eterMarket.helper;

/** Saisies des Dialogs d'EterMarket (prix, quantités, récompenses). */
public final class Inputs {

    private Inputs() {
    }

    /** Nombre saisi (virgule ou point), 0 si vide ou illisible. */
    public static double number(String text) {
        try {
            double value = text == null ? 0 : Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(value) ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
