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
    /**
     * Quanto ripida e' la curva del prezzo.
     *
     * ## Perche' sette e mezzo e non tre
     *
     * Con l'esponente 3 un giocatore da 71 costava il **15% del budget**: un club AI ne
     * pagava cinquanta su trecentoventi per un onesto gregario. Non era sfortuna, era la
     * curva: troppo piatta, quindi a meta' classifica i prezzi erano già quelli di un
     * titolare, e con dodici gregari da comprare la rosa non si completava mai.
     *
     * Sette e mezzo e' **misurato**, non scelto: `PriceScaleTest` stampa il listino e
     * fallisce se un prezzo esce dalla sua fascia. Chi cambia questo numero lo scopre
     * subito, insieme al perche'.
     */
    private const val PRICE_EXPONENT = 7.5

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
        config.economy.startingCredits * config.economy.topPlayerBudgetShare

    /**
     * Da overall a punteggio 0..1, con curva cubica.
     *
     * 40 -> 0.00 | 60 -> 0.07 | 75 -> 0.42 | 85 -> 0.74 | 93 -> 1.00
     */
    fun overallScore(overall: Double): Double {
        val t = ((overall - FLOOR_OVERALL) / (CEILING_OVERALL - FLOOR_OVERALL)).coerceIn(0.0, 1.0)
        return MathX.pow(t, PRICE_EXPONENT)
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

    /**
     * Quanto costa un membro dello staff, in crediti.
     *
     * ## Perche' questa funzione e' nata il 2026-08-25
     *
     * Perche' lo stesso prezzo era scritto dentro la schermata dello staff dell'app —
     * `(budgetIniziale / 40) * stelle` — e da nessun'altra parte. Il server non lo
     * conosceva, quindi non poteva metterlo su un pulsante «Assumi», quindi l'unico modo
     * di prendere un allenatore restava l'asta. La segnalazione del proprietario era
     * esattamente questa: «per prendere lo staff si e' ancora obbligati all'asta».
     *
     * Adesso vive qui, come tutte le regole di gioco, e la usano l'app, il tick e — nella
     * sua copia SQL, per la stessa ragione per cui `mfoot_market_value` esiste — il
     * database, che sui soldi non puo' fidarsi di quello che gli manda un telefono.
     *
     * ## Perche' la curva e' quadratica
     *
     * Perche' le stelle non sono lineari e il prezzo deve dirlo. Un allenatore da cinque
     * fa crescere i giocatori **tre volte** piu' di uno da una (0,60 contro 1,80), e la
     * differenza fra il quarto e il quinto e' molto piu' grande di quella fra il primo e il
     * secondo. Un prezzo lineare renderebbe il cinque stelle un affare ovvio per chiunque
     * apra l'app per primo, che e' precisamente il motivo per cui lo staff era finito
     * all'asta.
     *
     * Il tetto e' [dev.mfoot.core.config.EconomyConfig.staffBudgetShare] — quanto costa un
     * cinque stelle — e le stelle sotto scendono con il quadrato: 5★ paga il tetto pieno,
     * 1★ un venticinquesimo. Con i valori predefiniti sono il 4% del budget contro lo
     * 0,16%. Resta un affare prenderne uno bravo — deve esserlo, o non lo comprerebbe
     * nessuno — ma costa abbastanza da essere una decisione.
     *
     * `StaffPriceTest` stampa il listino e fallisce se il migliore esce dalla sua fascia.
     */
    fun staffPrice(stars: Int, config: LeagueConfig): Int {
        val stelle = stars.coerceIn(1, 5)
        val tetto = config.economy.startingCredits * config.economy.staffBudgetShare
        val frazione = (stelle * stelle) / 25.0
        return StrictMath.round(tetto * frazione).toInt().coerceAtLeast(1)
    }
}
