package dev.mfoot.core.match

import dev.mfoot.core.model.Band
import dev.mfoot.core.model.Lane

/**
 * Quanto la squadra si sbilancia in avanti.
 *
 * I moltiplicatori si applicano ai rating di zona: alzare l'attacco costa in difesa,
 * sempre. Non esiste un assetto gratuitamente migliore degli altri, o la scelta tattica
 * si ridurrebbe a selezionare l'unica opzione sensata.
 */
enum class TacticalStance(
    val label: String,
    val defenceFactor: Double,
    val midfieldFactor: Double,
    val attackFactor: Double,
    /**
     * Quanto spesso si conclude una volta arrivati in zona offensiva.
     *
     * Senza questo, l'assetto offensivo sarebbe sempre perdente. Il motivo e' che la
     * difesa agisce **prima** nella catena di possesso: fermare l'avversario a
     * centrocampo impedisce del tutto che si arrivi al tiro, mentre un attacco forte
     * serve solo negli ultimi trenta metri. La leva difensiva e' strutturalmente
     * maggiore, e va compensata dando all'attacco piu' conclusioni.
     */
    val shotChanceFactor: Double,
) {
    ULTRA_DIFENSIVO("Ultra difensivo", 1.05, 0.99, 0.90, 0.80),
    DIFENSIVO("Difensivo", 1.03, 1.00, 0.95, 0.90),
    EQUILIBRATO("Equilibrato", 1.00, 1.00, 1.00, 1.00),
    OFFENSIVO("Offensivo", 0.97, 1.00, 1.05, 1.15),
    ULTRA_OFFENSIVO("Ultra offensivo", 0.94, 0.99, 1.10, 1.34);

    fun factorFor(band: Band): Double = when (band) {
        Band.DIF -> defenceFactor
        Band.MID -> midfieldFactor
        Band.ATT -> attackFactor
    }
}

/**
 * Dove la squadra cerca di sviluppare il gioco.
 *
 * Giocare stretto rafforza il centro e lascia le fasce scoperte, e viceversa. E' la
 * leva che permette di attaccare la fascia debole dell'avversario, che e' proprio la
 * lettura che il modello a nove zone rende visibile.
 */
enum class TacticalWidth(
    val label: String,
    val centralFactor: Double,
    val wideFactor: Double,
) {
    STRETTO("Stretto", 1.07, 0.95),
    NORMALE("Normale", 1.00, 1.00),
    LARGO("Largo", 0.95, 1.06);

    fun factorFor(lane: Lane): Double =
        if (lane == Lane.C) centralFactor else wideFactor
}

/**
 * Ritmo di gioco: quante azioni si producono in una partita.
 *
 * Alzare il ritmo genera piu' occasioni per entrambe le squadre, non solo per la
 * propria: e' un moltiplicatore di varianza, quindi conviene a chi e' piu' debole e
 * spaventa chi e' avanti nel punteggio. Costa stamina.
 */
enum class TacticalTempo(
    val label: String,
    val actionMultiplier: Double,
    val staminaMultiplier: Double,
) {
    LENTO("Lento", 0.88, 0.86),
    NORMALE("Normale", 1.00, 1.00),
    ALTO("Alto", 1.14, 1.22);
}

/**
 * Intensita' del pressing.
 *
 * Recupera piu' palloni a centrocampo ma brucia stamina, e con due partite al giorno
 * la stamina e' la risorsa piu' scarsa che esista. Pressare alto per novanta minuti
 * significa arrivare alla partita della sera con la rosa a pezzi.
 */
enum class TacticalPressing(
    val label: String,
    val midfieldBonus: Double,
    val staminaMultiplier: Double,
    val foulMultiplier: Double,
) {
    BASSO("Basso", -1.2, 0.86, 0.85),
    MEDIO("Medio", 0.0, 1.00, 1.00),
    ALTO("Alto", 1.8, 1.20, 1.30);
}

data class Tactics(
    val stance: TacticalStance = TacticalStance.EQUILIBRATO,
    val width: TacticalWidth = TacticalWidth.NORMALE,
    val tempo: TacticalTempo = TacticalTempo.NORMALE,
    val pressing: TacticalPressing = TacticalPressing.MEDIO,
) {
    /** Quanto questo assetto consuma, combinando ritmo e pressing. */
    val staminaMultiplier: Double
        get() = tempo.staminaMultiplier * pressing.staminaMultiplier

    companion object {
        val DEFAULT = Tactics()

        val CATENACCIO = Tactics(
            stance = TacticalStance.ULTRA_DIFENSIVO,
            width = TacticalWidth.STRETTO,
            tempo = TacticalTempo.LENTO,
            pressing = TacticalPressing.BASSO,
        )

        val ARREMBANTE = Tactics(
            stance = TacticalStance.ULTRA_OFFENSIVO,
            width = TacticalWidth.LARGO,
            tempo = TacticalTempo.ALTO,
            pressing = TacticalPressing.ALTO,
        )
    }
}

/** Quanto pesa la partita: alcuni tratti si attivano solo nelle sfide che contano. */
enum class MatchImportance(val label: String, val isBig: Boolean) {
    AMICHEVOLE("Amichevole", false),
    CAMPIONATO("Campionato", false),
    COPPA("Coppa", true),
    FINALE("Finale", true),
}
