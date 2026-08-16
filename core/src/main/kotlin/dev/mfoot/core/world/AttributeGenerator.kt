package dev.mfoot.core.world

import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.Position
import dev.mfoot.core.rng.DeterministicRandom

/**
 * Costruisce un profilo di attributi credibile per un ruolo e un overall dati.
 *
 * ## Il problema
 *
 * Non basta generare dodici numeri a caso: devono comporsi in **esattamente**
 * l'overall richiesto (altrimenti la distribuzione del mondo va a farsi benedire)
 * e allo stesso tempo sembrare un giocatore vero, non una media uniforme.
 *
 * ## La soluzione
 *
 * Si parte da un profilo grezzo — alto negli attributi del ruolo, basso negli altri —
 * si aggiunge rumore per dare varieta', e poi si **corregge iterativamente** finche'
 * l'overall non combacia. La correzione somma lo stesso scarto a tutti gli attributi
 * pesati, quindi sposta l'overall senza deformare il profilo: una punta corretta resta
 * una punta.
 */
object AttributeGenerator {

    /** Quanto valgono gli attributi non caratteristici del ruolo. */
    private const val SECONDARY_LEVEL = 0.72

    /** Quanto valgono gli attributi dall'altra parte della barriera portiere/movimento. */
    private const val CROSS_ROLE_LEVEL = 0.30

    /** Deviazione del rumore: piu' e' alta, piu' i giocatori sono spigolosi. */
    private const val NOISE_STD = 6.5

    private const val MAX_CORRECTION_PASSES = 8

    fun generate(
        position: Position,
        targetOverall: Int,
        rng: DeterministicRandom,
    ): Attributes {
        val target = targetOverall.coerceIn(Attributes.MIN, Attributes.MAX)
        var attributes = roughProfile(position, target, rng)

        repeat(MAX_CORRECTION_PASSES) {
            val delta = target - position.overallOf(attributes)
            if (delta == 0) return attributes
            attributes = shiftRelevant(attributes, position, delta)
        }
        return attributes
    }

    /** Profilo iniziale: alto dove serve, basso dove non serve, con rumore. */
    private fun roughProfile(
        position: Position,
        target: Int,
        rng: DeterministicRandom,
    ): Attributes {
        val values = mutableMapOf<Attr, Int>()
        for (attr in Attr.entries) {
            val level = when {
                position.ovrWeights.containsKey(attr) -> 1.0
                attr.goalkeeperOnly != position.isGoalkeeper -> CROSS_ROLE_LEVEL
                else -> SECONDARY_LEVEL
            }
            val base = target * level
            val noisy = base + rng.nextGaussian() * NOISE_STD * level
            values[attr] = StrictMath.round(noisy).toInt().coerceIn(Attributes.MIN, Attributes.MAX)
        }
        return Attributes.fromMap(values)
    }

    /**
     * Sposta l'overall di [delta] sommandolo a tutti gli attributi che contano per il
     * ruolo. Poiche' i pesi sommano a 1, sommare `delta` a ciascuno sposta l'overall
     * esattamente di `delta` — a meno del troncamento ai limiti, che e' il motivo per
     * cui la correzione viene ripetuta.
     */
    private fun shiftRelevant(
        attributes: Attributes,
        position: Position,
        delta: Int,
    ): Attributes {
        var result = attributes
        for (attr in position.ovrWeights.keys) {
            result = result.plus(attr, delta)
        }
        return result
    }

    /**
     * Piede debole e stelle tecnica, correlati alla qualita' ma non determinati da essa.
     *
     * I giocatori forti tendono ad averle alte, ma restano possibili il fenomeno
     * monopiede e il gregario dai piedi buoni: e' proprio l'eccezione a rendere
     * interessante leggere una scheda.
     */
    fun generateStars(overall: Int, rng: DeterministicRandom): Pair<Int, Int> {
        val weakFoot = starFromOverall(overall, rng, spread = 1.15)
        val skill = starFromOverall(overall, rng, spread = 0.95)
        return weakFoot to skill
    }

    private fun starFromOverall(overall: Int, rng: DeterministicRandom, spread: Double): Int {
        // 55 -> circa 2 stelle, 93 -> circa 4
        val mean = 2.0 + (overall - 55).coerceAtLeast(0) * (2.0 / 38.0)
        val value = mean + rng.nextGaussian() * spread
        return StrictMath.round(value).toInt().coerceIn(1, 5)
    }
}
