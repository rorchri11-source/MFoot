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

    /** Incertezza massima, per un sedicenne di cui non si sa nulla. */
    private const val MAX_UNCERTAINTY = 13.0

    /**
     * Incertezza minima, per chi ha gia' finito di crescere.
     *
     * Non e' zero perche' un margine di dubbio resta sempre, ma e' piccola: il livello
     * attuale di un ventottenne e' sotto gli occhi di tutti, e su quanto crescera'
     * ancora non c'e' molto da discutere.
     */
    private const val MIN_UNCERTAINTY = 1.5

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

        // Quanto ci si puo' sbagliare dipende dall'eta', che e' pubblica: su un
        // sedicenne moltissimo, su un ventottenne quasi per niente.
        val upside = DevelopmentCurve.remainingUpside(player.age)
        val maxUncertainty = MathX.lerp(MIN_UNCERTAINTY, MAX_UNCERTAINTY, upside)

        val bias = rng.nextGaussian() * maxUncertainty * 0.45 * (1.0 - k) * 0.5

        // Senza informazioni si parte da cio' che e' deducibile pubblicamente; man mano
        // che si osserva il giocatore, la stima converge verso la verita'.
        val center = MathX.lerp(plausiblePeak(player) + bias, trueCenter, k)
        val halfSpread = MathX.lerp(maxUncertainty, trueHalfSpread, k)

        val low = StrictMath.round(center - halfSpread).toInt()
        val high = StrictMath.round(center + halfSpread).toInt()

        // Una stima sotto l'overall attuale non ha senso: quel livello lo ha gia'.
        val clampedLow = low.coerceIn(player.overall, 99)
        val clampedHigh = high.coerceIn(clampedLow, 99)
        return clampedLow..clampedHigh
    }

    /**
     * Il tetto deducibile da eta' e overall, senza sbirciare niente di nascosto.
     *
     * E' l'inverso della curva di sviluppo: se il mondo genera l'overall attuale come
     * `potenziale x frazione realizzata`, allora chi guarda puo' fare la divisione. Un
     * ventenne a 62, che a quell'eta' ha realizzato circa il 79% di se stesso, punta a
     * un tetto intorno a 78. Non serve barare per stimare: basta saper leggere una
     * scheda, ed e' esattamente quello che fa un osservatore vero.
     *
     * Superato il picco la divisione darebbe il massimo **storico**, che e' alle spalle
     * e non tornera'. Per chi sta calando il tetto futuro e' il livello attuale: e' il
     * motivo per cui la scheda di un trentatreenne non deve promettere crescita.
     */
    fun plausiblePeak(player: Player): Double {
        if (player.age >= DevelopmentCurve.PEAK_AGE) return player.overall.toDouble()
        val realized = DevelopmentCurve.realizedFraction(player.age).coerceAtLeast(0.35)
        // Il limite non e' 99 ma il massimo che il mondo genera davvero: sulle eta'
        // piu' basse la divisione amplifica il rumore e puo' proiettare oltre il tetto,
        // e a quel punto la stima si appiattisce contro il massimo assoluto perdendo
        // ogni capacita' di distinguere un buon prospetto da un fenomeno.
        return (player.overall / realized).coerceAtMost(REALISTIC_CEILING)
    }

    /** Il massimo che la generazione produce davvero (fascia fuoriclasse: 87-93). */
    private const val REALISTIC_CEILING = 93.0

    /**
     * Ha ancora margine di crescita?
     *
     * L'interfaccia dovrebbe mostrare la forbice di potenziale **solo** quando la
     * risposta e' si'. Per un veterano ha piu' senso indicare che sta calando che
     * ripetere un numero uguale al suo overall.
     */
    fun hasUpside(player: Player): Boolean =
        DevelopmentCurve.remainingUpside(player.age) > 0.0

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
