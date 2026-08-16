package dev.mfoot.core.world

import dev.mfoot.core.model.Player
import dev.mfoot.core.rng.DeterministicRandom
import dev.mfoot.core.rng.MathX

/**
 * La forbice di potenziale che un club **vede**, che non e' quella vera.
 *
 * ## Perche' esiste
 *
 * E' il meccanismo che sostituisce l'emozione di comprare un nome noto. In un mondo
 * generato nessuno sa chi sia il diciannovenne in asta: si sta scommettendo. Quando poi
 * quel ragazzo pagato quattro crediti diventa un 87 e l'amico ti offre quaranta per
 * riaverlo, quella e' una storia vostra.
 *
 * ## Le tre proprieta' che deve avere
 *
 * 1. **Stabile.** La stima non deve ballare a ogni apertura della scheda, o diventa
 *    rumore. Dipende in modo deterministico da chi guarda e da chi viene guardato.
 * 2. **Distorta.** Il centro della forbice non coincide con la verita', altrimenti
 *    basterebbe leggere il punto medio e lo scouting non servirebbe a niente.
 * 3. **Diversa per ogni osservatore.** Due club vedono stime leggermente diverse dello
 *    stesso giocatore. E' quello che rende possibile l'affare: qualcuno ci vede giusto
 *    e qualcun altro no.
 *
 * L'AI usa esattamente questa funzione. Un'AI che leggesse i valori veri comprerebbe
 * sempre i giovani giusti e sembrerebbe truccata.
 */
object PotentialEstimator {

    /** Incertezza massima in punti di overall, quando non si sa proprio nulla. */
    private const val MAX_UNCERTAINTY = 13.0

    /** Minuti di osservazione oltre i quali guardarlo giocare non aggiunge quasi nulla. */
    private const val MINUTES_FOR_FULL_KNOWLEDGE = 1800.0

    /** Quanto pesano rispettivamente i minuti visti e il lavoro degli osservatori. */
    private const val MINUTES_WEIGHT = 0.55
    private const val SCOUTING_WEIGHT = 0.45

    /**
     * Quanto si sa di un giocatore, da 0 (nulla) a 1 (quasi tutto).
     *
     * Non arriva mai a far collassare la forbice sulla verita': un margine di dubbio
     * resta sempre, ed e' giusto che resti.
     */
    fun knowledge(minutesObserved: Int, scoutAccuracy: Double): Double {
        val fromMinutes = (minutesObserved / MINUTES_FOR_FULL_KNOWLEDGE).coerceIn(0.0, 1.0)
        val fromScouting = scoutAccuracy.coerceIn(0.0, 1.0)
        return (fromMinutes * MINUTES_WEIGHT + fromScouting * SCOUTING_WEIGHT).coerceIn(0.0, 1.0)
    }

    /**
     * La forbice mostrata a [observerId] per [player].
     *
     * @param minutesObserved minuti che questo osservatore ha visto giocare al giocatore
     * @param scoutAccuracy precisione degli osservatori del club, 0..1 (vedi Staff)
     */
    fun estimate(
        player: Player,
        observerId: Long,
        minutesObserved: Int = 0,
        scoutAccuracy: Double = 0.0,
    ): IntRange {
        val k = knowledge(minutesObserved, scoutAccuracy)
        val trueCenter = (player.potentialMin + player.potentialMax) / 2.0
        val trueHalfSpread = (player.potentialMax - player.potentialMin) / 2.0

        // Stesso osservatore e stesso giocatore: sempre la stessa stima.
        val rng = DeterministicRandom(player.id.value * 31L + observerId * 1_000_003L)

        // La distorsione si riduce con la conoscenza ma non sparisce.
        val maxBias = MAX_UNCERTAINTY * 0.45
        val bias = rng.nextGaussian() * maxBias * (1.0 - k) * 0.5

        val halfSpread = MathX.lerp(MAX_UNCERTAINTY, trueHalfSpread, k)
        val center = trueCenter + bias

        val low = StrictMath.round(center - halfSpread).toInt()
        val high = StrictMath.round(center + halfSpread).toInt()

        // Una stima sotto l'overall attuale non ha senso: quel livello lo ha gia'.
        val clampedLow = low.coerceIn(player.overall, 99)
        val clampedHigh = high.coerceIn(clampedLow, 99)
        return clampedLow..clampedHigh
    }

    /**
     * Etichetta leggibile della forbice, come la mostrerebbe il client.
     * Esempio: `"68 ora - potenziale 72-89"`.
     */
    fun describe(player: Player, estimate: IntRange): String =
        if (estimate.first == estimate.last) {
            "${player.overall} ora - potenziale ${estimate.first}"
        } else {
            "${player.overall} ora - potenziale ${estimate.first}-${estimate.last}"
        }
}
