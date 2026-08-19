package dev.mfoot.android.app

import dev.mfoot.android.data.MatchMoment
import dev.mfoot.android.data.PlayedMatch

/**
 * La partita che si sta guardando.
 *
 * ## Perche' il minuto e' nello stato e non un timer dentro la schermata
 *
 * Perche' la partita si puo' mettere in pausa, saltare alla fine, riprendere — e con il
 * minuto dentro la composizione, ruotare lo schermo la farebbe ricominciare dall'inizio.
 * Qui e' un numero come gli altri, e chi disegna si limita a leggerlo.
 */
data class MatchState(
    val partita: PlayedMatch? = null,
    val homeName: String = "",
    val awayName: String = "",
    /** Il minuto a cui e' arrivata la riproduzione. */
    val minuto: Int = 0,
    val inCorso: Boolean = false,
    /** Minuti di gioco al secondo reale. */
    val velocita: Int = 6,
    val caricamento: Boolean = true,
    val errore: String? = null,
) {
    /** Quello che e' gia' successo, dal piu' recente. */
    val accaduto: List<MatchMoment>
        get() = partita?.moments.orEmpty()
            .filter { it.minute <= minuto && it.isNotable }
            .asReversed()

    val golCasa: Int get() = ultimo?.homeGoals ?: 0
    val golFuori: Int get() = ultimo?.awayGoals ?: 0

    /**
     * Il punteggio **a questo minuto**, non quello finale.
     *
     * Ogni evento porta con se' il punteggio di quel momento: senza, guardare una partita
     * gia' finita mostrerebbe il risultato definitivo fin dal primo minuto, e non ci
     * sarebbe niente da guardare.
     */
    private val ultimo: MatchMoment?
        get() = partita?.moments?.lastOrNull { it.minute <= minuto }

    val finita: Boolean get() = partita != null && minuto >= 90
}
