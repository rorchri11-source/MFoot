package dev.mfoot.core.match

/**
 * Dove sta ogni ruolo sul campo, per ogni modulo.
 *
 * ## Perche' sta in `core` e non nell'interfaccia
 *
 * Perche' non e' grafica: e' la stessa informazione che serve a disegnare il campo sul
 * telefono e a raccontare una partita ("cross dalla fascia destra"). Tenerla in due posti
 * significherebbe che l'undici disegnato e l'undici simulato non stanno negli stessi punti,
 * e nessuno saprebbe quale dei due ha ragione.
 *
 * ## Il sistema di coordinate
 *
 * `x` va da 0 (fascia sinistra) a 1 (fascia destra). `y` va da 0 (la propria porta) a 1
 * (la porta avversaria). Sono frazioni e non pixel: il campo si disegna grande sul tablet
 * e piccolo nell'anteprima senza che nessuna coordinata cambi.
 *
 * Il portiere sta a `y = 0.06` e non a zero: attaccato alla linea non si vedrebbe il
 * cerchio della casella.
 */
object PitchLayout {

    /** Le undici coordinate del modulo, nello stesso ordine di [Formation.positions]. */
    fun of(formation: Formation): List<Pair<Float, Float>> = when (formation) {
        Formation.F_4_3_3 -> listOf(
            0.50f to 0.06f,
            0.86f to 0.24f, 0.62f to 0.20f, 0.38f to 0.20f, 0.14f to 0.24f,
            0.50f to 0.44f, 0.72f to 0.52f, 0.28f to 0.52f,
            0.84f to 0.78f, 0.50f to 0.86f, 0.16f to 0.78f,
        )

        Formation.F_4_4_2 -> listOf(
            0.50f to 0.06f,
            0.86f to 0.24f, 0.62f to 0.20f, 0.38f to 0.20f, 0.14f to 0.24f,
            0.86f to 0.54f, 0.62f to 0.48f, 0.38f to 0.48f, 0.14f to 0.54f,
            0.62f to 0.84f, 0.38f to 0.84f,
        )

        Formation.F_4_2_3_1 -> listOf(
            0.50f to 0.06f,
            0.86f to 0.24f, 0.62f to 0.20f, 0.38f to 0.20f, 0.14f to 0.24f,
            0.62f to 0.42f, 0.38f to 0.42f,
            0.84f to 0.68f, 0.50f to 0.66f, 0.16f to 0.68f,
            0.50f to 0.88f,
        )

        Formation.F_3_5_2 -> listOf(
            0.50f to 0.06f,
            0.72f to 0.20f, 0.50f to 0.18f, 0.28f to 0.20f,
            0.90f to 0.52f, 0.50f to 0.40f, 0.68f to 0.54f, 0.32f to 0.54f, 0.10f to 0.52f,
            0.62f to 0.84f, 0.38f to 0.84f,
        )

        Formation.F_5_3_2 -> listOf(
            0.50f to 0.06f,
            0.90f to 0.28f, 0.70f to 0.18f, 0.50f to 0.16f, 0.30f to 0.18f, 0.10f to 0.28f,
            0.50f to 0.44f, 0.70f to 0.54f, 0.30f to 0.54f,
            0.62f to 0.84f, 0.38f to 0.84f,
        )

        Formation.F_4_4_1_1 -> listOf(
            0.50f to 0.06f,
            0.86f to 0.24f, 0.62f to 0.20f, 0.38f to 0.20f, 0.14f to 0.24f,
            0.86f to 0.54f, 0.62f to 0.48f, 0.38f to 0.48f, 0.14f to 0.54f,
            0.50f to 0.72f,
            0.50f to 0.90f,
        )

        Formation.F_4_3_1_2 -> listOf(
            0.50f to 0.06f,
            0.86f to 0.24f, 0.62f to 0.20f, 0.38f to 0.20f, 0.14f to 0.24f,
            0.50f to 0.42f, 0.72f to 0.50f, 0.28f to 0.50f,
            0.50f to 0.68f,
            0.62f to 0.86f, 0.38f to 0.86f,
        )

        Formation.F_3_4_3 -> listOf(
            0.50f to 0.06f,
            0.72f to 0.20f, 0.50f to 0.18f, 0.28f to 0.20f,
            0.88f to 0.50f, 0.62f to 0.46f, 0.38f to 0.46f, 0.12f to 0.50f,
            0.82f to 0.80f, 0.50f to 0.86f, 0.18f to 0.80f,
        )

        Formation.F_4_1_4_1 -> listOf(
            0.50f to 0.06f,
            0.86f to 0.24f, 0.62f to 0.20f, 0.38f to 0.20f, 0.14f to 0.24f,
            0.50f to 0.38f,
            0.86f to 0.60f, 0.62f to 0.56f, 0.38f to 0.56f, 0.14f to 0.60f,
            0.50f to 0.88f,
        )

        Formation.F_5_4_1 -> listOf(
            0.50f to 0.06f,
            0.90f to 0.28f, 0.70f to 0.18f, 0.50f to 0.16f, 0.30f to 0.18f, 0.10f to 0.28f,
            0.86f to 0.56f, 0.62f to 0.50f, 0.38f to 0.50f, 0.14f to 0.56f,
            0.50f to 0.88f,
        )
    }

    /**
     * Gli undici ruoli di riferimento su cui scegliere **dove si vuole giocare**.
     *
     * Usa il 4-3-3 perche' e' il modulo che copre tutti i ruoli fondamentali in posizioni
     * riconoscibili: chi cerca "l'ala destra" la trova dove se l'aspetta. Serve alla
     * fondazione del club, dove non esiste ancora nessuna rosa e quindi nessun modulo.
     */
    fun rolePicker(): List<Pair<Float, Float>> = of(Formation.F_4_3_3)
}
