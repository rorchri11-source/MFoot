package dev.mfoot.core.market

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.config.RulesConfig
import dev.mfoot.core.model.Player
import dev.mfoot.core.rng.MathX

/**
 * Quanto vale un giocatore, in crediti.
 *
 * ## Perche' la scala si deriva dall'economia
 *
 * Il valore non e' un numero assoluto scritto nel codice: e' una **frazione del budget
 * iniziale** deciso dall'admin. In una lega da 300 crediti il fuoriclasse ne vale ~66,
 * in una da 60 ne vale ~13. Cosi' lo stesso motore regge sia le leghe povere che quelle
 * ricche, e soprattutto l'AI non ha bisogno di numeri suoi: ragiona sempre in percentuale
 * del disponibile.
 *
 * ## Perche' la curva e' ripida
 *
 * `overallScore` e' cubica, quindi un 90 non costa "un po' piu'" di un 80: costa molto
 * di piu'. Serve a rendere le aste per i fuoriclasse una scelta vera — prenderne uno
 * significa rinunciare a mezza rosa — invece che un acquisto indolore.
 */
object Valuation {

    /** Overall sotto il quale un giocatore vale il minimo sindacale. */
    private const val FLOOR_OVERALL = 40.0

    /** Overall di riferimento del fuoriclasse assoluto. */
    private const val CEILING_OVERALL = 93.0

    /** Frazione del budget iniziale che costa il miglior giocatore del mondo. */
    private const val TOP_PLAYER_BUDGET_SHARE = 0.22

    /**
     * Valore di mercato "oggettivo", senza incertezza sul potenziale.
     *
     * Usato dal validatore di configurazione e come base per le stime dell'AI.
     * Non e' il prezzo d'asta: quello lo decide chi partecipa.
     */
    fun marketValue(player: Player, config: LeagueConfig): Int {
        val scale = priceScale(config)
        val quality = overallScore(player.overall.toDouble())
        val age = ageFactor(player.age, config.rules)
        val upside = potentialFactor(player, config.rules)
        return StrictMath.round(quality * scale * age * upside).toInt().coerceAtLeast(1)
    }

    /**
     * Valore stimato usando una forbice di potenziale invece di quella vera.
     *
     * E' la funzione che deve usare l'AI: valuta sulla stima, mai sul valore reale.
     * Un'AI che conoscesse i potenziali veri comprerebbe sempre i giovani giusti e
     * sembrerebbe truccata.
     */
    fun estimatedValue(
        player: Player,
        estimatedPotential: IntRange,
        config: LeagueConfig,
    ): Int {
        val scale = priceScale(config)
        val quality = overallScore(player.overall.toDouble())
        val age = ageFactor(player.age, config.rules)
        val midPotential = (estimatedPotential.first + estimatedPotential.last) / 2.0
        val upside = upsideFrom(player.overall.toDouble(), midPotential, player.age, config.rules)
        return StrictMath.round(quality * scale * age * upside).toInt().coerceAtLeast(1)
    }

    /** Quanti crediti costa il miglior giocatore possibile, dato il budget della lega. */
    fun priceScale(config: LeagueConfig): Double =
        config.economy.startingCredits * TOP_PLAYER_BUDGET_SHARE

    /**
     * Da overall a punteggio 0..1, con curva cubica.
     *
     * 40 -> 0.00 | 60 -> 0.07 | 75 -> 0.42 | 85 -> 0.74 | 93 -> 1.00
     */
    fun overallScore(overall: Double): Double {
        val t = ((overall - FLOOR_OVERALL) / (CEILING_OVERALL - FLOOR_OVERALL)).coerceIn(0.0, 1.0)
        return MathX.pow(t, 3.0)
    }

    /**
     * Quanto pesa l'eta' sul valore.
     *
     * Il picco sta appena prima della fascia di crescita massima: un ventiduenne forte
     * vale piu' di un ventottenne uguale, perche' ha ancora stagioni davanti. Dopo la
     * soglia di declino il valore crolla in fretta.
     */
    fun ageFactor(age: Int, rules: RulesConfig): Double = when {
        age < rules.peakAgeStart -> MathX.remap(age.toDouble(), 16.0, rules.peakAgeStart.toDouble(), 0.82, 1.15)
        age <= rules.peakAgeEnd -> 1.15
        age <= rules.plateauAgeEnd -> MathX.remap(age.toDouble(), rules.peakAgeEnd.toDouble(), rules.plateauAgeEnd.toDouble(), 1.15, 1.0)
        age < rules.declineAge -> MathX.remap(age.toDouble(), rules.plateauAgeEnd.toDouble(), rules.declineAge.toDouble(), 1.0, 0.62)
        else -> MathX.remap(age.toDouble(), rules.declineAge.toDouble(), 38.0, 0.62, 0.22)
    }

    private fun potentialFactor(player: Player, rules: RulesConfig): Double {
        val mid = (player.potentialMin + player.potentialMax) / 2.0
        return upsideFrom(player.overall.toDouble(), mid, player.age, rules)
    }

    /**
     * Il margine di crescita residuo si paga, ma solo finche' l'eta' lo rende credibile.
     * Un trentaduenne con potenziale 90 non vale nulla di piu': non ci arrivera' mai.
     */
    private fun upsideFrom(
        overall: Double,
        potential: Double,
        age: Int,
        rules: RulesConfig,
    ): Double {
        val margin = (potential - overall).coerceAtLeast(0.0)
        val credibility = when {
            age <= rules.peakAgeEnd -> 1.0
            age <= rules.plateauAgeEnd -> 0.45
            else -> 0.0
        }
        return 1.0 + (margin / 25.0) * credibility
    }
}
