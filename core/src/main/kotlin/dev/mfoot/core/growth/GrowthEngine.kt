package dev.mfoot.core.growth

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.config.RulesConfig
import dev.mfoot.core.match.MatchImportance
import dev.mfoot.core.match.PlayerMatchStats
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.growthFactor
import dev.mfoot.core.rng.DeterministicRandom
import dev.mfoot.core.rng.MathX

/** Un attributo che e' cambiato, per poterlo mostrare al giocatore. */
data class AttributeChange(val attr: Attr, val from: Int, val to: Int) {
    val delta: Int get() = to - from
    fun describe(playerName: String): String =
        "$playerName: ${attr.label} $from -> $to"
}

data class GrowthOutcome(
    val player: Player,
    val xpGained: Double,
    val changes: List<AttributeChange>,
) {
    val grew: Boolean get() = changes.any { it.delta > 0 }
    val declined: Boolean get() = changes.any { it.delta < 0 }
}

data class GrowthContext(
    val config: LeagueConfig,
    val coachStars: Int = 3,
    val importance: MatchImportance = MatchImportance.CAMPIONATO,
    val isYouthMatch: Boolean = false,
)

/**
 * Fa crescere (o calare) i giocatori in base a come giocano.
 *
 * ## Perche' la crescita e' frazionaria
 *
 * Con due partite al giorno una stagione da 38 giornate dura diciannove giorni reali.
 * Se ogni partita desse un punto pieno di overall, un player custom da 65 arriverebbe a
 * 90 in due settimane e il gioco finirebbe. L'esperienza si accumula quindi in un
 * serbatoio nascosto e sblocca **un attributo alla volta**: il giocatore sente di
 * migliorare ogni giorno ma per fare +10 serve una stagione intera.
 *
 * ## Perche' sale un attributo e non l'overall
 *
 * Vedere "+0,3 di overall" non dice niente. Vedere *"Ferrero: Tiro 74 -> 75"* si
 * ricorda. La crescita sceglie fra gli attributi che contano per il ruolo, con un peso
 * verso quelli che il giocatore ha effettivamente usato in campo.
 */
object GrowthEngine {

    /** Esperienza base di una prestazione neutra sui novanta minuti. */
    private const val BASE_XP_PER_FULL_MATCH = 16.0

    /** Quanto pesa il voto rispetto ai soli minuti giocati. */
    private const val RATING_WEIGHT = 4.8

    private const val XP_PER_GOAL = 6.0
    private const val XP_PER_ASSIST = 4.0
    private const val XP_PER_SAVE = 2.5
    private const val XP_PER_KEY_ACTION = 0.4

    /**
     * Entro quanti punti dal proprio tetto la crescita comincia a rallentare.
     * Oltre questa distanza si cresce a pieno ritmo.
     */
    private const val SLOWDOWN_RANGE = 14.0

    /**
     * Processa una partita e restituisce il giocatore aggiornato.
     *
     * Restituisce il giocatore invariato se la partita non deve contare — per esempio
     * un'amichevole in una lega dove le amichevoli non fanno crescere. Senza quel
     * blocco, due amici compiacenti potrebbero concordare quindici partite al giorno e
     * far esplodere le rose in un pomeriggio.
     */
    fun processMatch(
        player: Player,
        stats: PlayerMatchStats,
        context: GrowthContext,
    ): GrowthOutcome {
        val rules = context.config.rules

        if (stats.minutesPlayed <= 0) return unchanged(player)
        if (context.importance == MatchImportance.AMICHEVOLE && !rules.friendliesCountForGrowth) {
            return unchanged(player)
        }

        val xp = experienceFrom(player, stats, context)
        return applyExperience(player, xp, stats, rules)
    }

    /**
     * La crescita di chi sta in Primavera e si allena senza giocare.
     *
     * ## Perche' serve
     *
     * Senza, la Primavera e' un magazzino: ci si parcheggia un diciassettenne e lo si
     * ritrova diciassettenne. Il gioco dice che i giovani maturano — c'e' una curva di
     * sviluppo, c'e' un moltiplicatore d'eta' che a diciotto anni vale il doppio che a
     * ventotto — ma tutta quella crescita passa da `processMatch`, e chi non scende in
     * campo non ne vede niente.
     *
     * ## Perche' rende molto meno di una partita
     *
     * Perche' deve restare vero che **giocare e' il modo migliore di crescere**. Un
     * allenamento vale una frazione di una partita: parcheggiare un talento in Primavera
     * e' un modo di non perderlo del tutto, non una scorciatoia per farlo crescere senza
     * rischiare risultati. Chi vuole un ventenne da ottanta lo deve mandare in campo.
     *
     * Il fattore dell'allenatore vale intero: e' l'unico posto del gioco in cui uno staff
     * bravo lavora anche quando non si gioca, ed e' il motivo per cui vale la pena pagarlo.
     */
    fun trainYouth(player: Player, context: GrowthContext): GrowthOutcome {
        val rules = context.config.rules
        if (!rules.youthTeamEnabled) return unchanged(player)

        val ageFactor = ageMultiplier(player.age, rules)
        // Chi e' gia' in parabola discendente non "si allena in negativo": semplicemente
        // non guadagna niente. Il declino lo paga giocando, che e' dove si consuma.
        if (ageFactor <= 0.0) return unchanged(player)

        val xp = BASE_XP_PER_FULL_MATCH * TRAINING_SHARE *
            rules.youthMatchGrowthFactor *
            rules.growthMultiplier *
            coachMultiplier(context.coachStars) *
            player.traits.growthFactor() *
            (if (player.isCustom) rules.customGrowthMultiplier else 1.0) *
            ageFactor *
            slowdownNear(player)

        return applyExperience(player, xp, PlayerMatchStats(player.id), rules)
    }

    /**
     * Quanto vale un allenamento rispetto a una partita intera.
     *
     * Un quinto. Con il fattore Primavera sopra, un giovane parcheggiato cresce a circa un
     * settimo della velocita' di uno che gioca: si vede muovere nell'arco di una stagione,
     * non di una settimana.
     */
    private const val TRAINING_SHARE = 0.20

    /**
     * Esperienza guadagnata in questa partita. Puo' essere negativa: dopo l'eta' di
     * declino giocare consuma invece di far crescere.
     */
    fun experienceFrom(
        player: Player,
        stats: PlayerMatchStats,
        context: GrowthContext,
    ): Double {
        val rules = context.config.rules
        val exposure = (stats.minutesPlayed / 90.0).coerceIn(0.0, 1.0)
        val rating = stats.rating(player.isGoalkeeper)

        val performance = BASE_XP_PER_FULL_MATCH * exposure +
            (rating - 6.0) * RATING_WEIGHT * exposure +
            stats.goals * XP_PER_GOAL +
            stats.assists * XP_PER_ASSIST +
            stats.saves * XP_PER_SAVE +
            stats.keyActions * XP_PER_KEY_ACTION

        val ageFactor = ageMultiplier(player.age, rules)
        val importanceFactor = if (context.importance.isBig) 1.15 else 1.0
        val youthFactor = if (context.isYouthMatch) rules.youthMatchGrowthFactor else 1.0
        val customFactor = if (player.isCustom) rules.customGrowthMultiplier else 1.0
        val coachFactor = coachMultiplier(context.coachStars)

        val raw = performance.coerceAtLeast(1.0) *
            coachFactor *
            rules.growthMultiplier *
            player.traits.growthFactor() *
            importanceFactor *
            youthFactor *
            customFactor

        // Il declino non dipende dalla prestazione: si perde comunque, e giocare
        // tanto accelera il logoramento invece di rallentarlo.
        return if (ageFactor < 0) {
            BASE_XP_PER_FULL_MATCH * exposure * ageFactor * rules.growthMultiplier
        } else {
            raw * ageFactor * slowdownNear(player)
        }
    }

    /**
     * Il moltiplicatore d'eta'.
     *
     * Fra i 22 e i 26 si cresce al doppio della velocita', dopo i 28 quasi piu' niente,
     * e superata la soglia di declino il valore diventa negativo. E' la ragione per cui
     * un ventiquattrenne promettente vale piu' di un trentenne piu' forte oggi.
     */
    fun ageMultiplier(age: Int, rules: RulesConfig): Double = when {
        age < rules.peakAgeStart -> MathX.remap(
            age.toDouble(), 16.0, rules.peakAgeStart.toDouble(), 1.55, 2.0,
        )
        age <= rules.peakAgeEnd -> 2.0
        age <= rules.plateauAgeEnd -> MathX.remap(
            age.toDouble(), rules.peakAgeEnd.toDouble(), rules.plateauAgeEnd.toDouble(), 2.0, 0.85,
        )
        age < rules.declineAge -> MathX.remap(
            age.toDouble(), rules.plateauAgeEnd.toDouble(), rules.declineAge.toDouble(), 0.85, 0.10,
        )
        else -> -MathX.remap(age.toDouble(), rules.declineAge.toDouble(), 38.0, 0.25, 0.9)
    }

    /** Da 1 stella (0,60) a 5 stelle (1,80), non lineare: i top valgono l'asta. */
    fun coachMultiplier(stars: Int): Double =
        COACH_BY_STARS[stars.coerceIn(1, 5) - 1]

    private val COACH_BY_STARS = doubleArrayOf(0.60, 0.80, 1.00, 1.35, 1.80)

    /**
     * Il tetto vero di questo giocatore, nascosto dentro la sua forbice di potenziale.
     *
     * Derivato in modo deterministico dall'id, cosi' e' sempre lo stesso ma non e'
     * leggibile ne' dal giocatore ne' dall'AI: entrambi vedono solo la forbice stimata.
     */
    fun ceilingOf(player: Player): Int {
        if (player.potentialMin >= player.potentialMax) return player.potentialMax
        val rng = DeterministicRandom(player.id.value * 7919L + 104_729L)
        return rng.nextIntInclusive(player.potentialMin, player.potentialMax)
    }

    /** Piu' si e' vicini al proprio tetto, piu' lenta diventa la crescita. */
    private fun slowdownNear(player: Player): Double {
        val margin = (ceilingOf(player) - player.overall).toDouble()
        if (margin <= 0) return 0.0
        return (margin / SLOWDOWN_RANGE).coerceIn(0.08, 1.0)
    }

    // ------------------------------------------------------------------ applicazione

    /**
     * Spende l'esperienza accumulata finche' basta per un altro punto.
     *
     * Il ciclo e' necessario, non un dettaglio: applicando un solo punto per partita, un
     * giocatore che ne guadagna il triplo del necessario crescerebbe esattamente come
     * uno che guadagna il minimo, e l'esperienza in eccesso si accumulerebbe senza
     * essere mai spesa. Il moltiplicatore del player custom e quello dell'allenatore a
     * cinque stelle diventerebbero decorativi.
     *
     * Il tetto sale con l'overall, quindi il ciclo converge sempre in fretta.
     */
    private fun applyExperience(
        player: Player,
        xp: Double,
        stats: PlayerMatchStats,
        rules: RulesConfig,
    ): GrowthOutcome {
        var current = player
        var pool = player.experience + xp
        val changes = mutableListOf<AttributeChange>()
        val ceiling = ceilingOf(player)

        while (pool >= thresholdFor(current.overall) && changes.size < MAX_STEPS_PER_MATCH) {
            val threshold = thresholdFor(current.overall)
            val attr = pickAttributeToRaise(current, stats)
            val before = current.attributes[attr]
            val candidate = current.copy(attributes = current.attributes.plus(attr, 1))

            // Non si supera mai il proprio tetto: l'esperienza in eccesso va persa.
            if (candidate.overall > ceiling) {
                pool = threshold * 0.9
                break
            }

            current = candidate
            pool -= threshold
            changes += AttributeChange(attr, before, candidate.attributes[attr])
        }

        while (pool <= -thresholdFor(current.overall) && changes.size < MAX_STEPS_PER_MATCH) {
            val threshold = thresholdFor(current.overall)
            val attr = pickAttributeToDrop(current)
            val before = current.attributes[attr]
            current = current.copy(attributes = current.attributes.plus(attr, -1))
            pool += threshold
            changes += AttributeChange(attr, before, current.attributes[attr])
        }

        return GrowthOutcome(current.copy(experience = pool), xp, changes)
    }

    /** Rete di sicurezza contro configurazioni assurde dell'admin. */
    private const val MAX_STEPS_PER_MATCH = 12

    /**
     * Quanta esperienza serve per un punto di attributo.
     *
     * Cresce con l'overall: migliorare da 60 a 61 e' molto piu' facile che passare da
     * 85 a 86. Senza questo, i fuoriclasse continuerebbero a salire indefinitamente.
     */
    fun thresholdFor(overall: Int): Double =
        14.0 + (overall - 50).coerceAtLeast(0) * 1.35

    /**
     * Quale attributo sale.
     *
     * Solo fra quelli che contano per il ruolo, e con un peso verso quelli che il
     * giocatore ha davvero usato: chi ha segnato migliora il tiro, chi ha parato
     * migliora la parata. Fra i pari merito vince quello piu' arretrato, cosi' i
     * profili non diventano tutti a punta.
     */
    private fun pickAttributeToRaise(player: Player, stats: PlayerMatchStats): Attr {
        val candidates = player.primaryPosition.relevantAttributes
            .filter { player.attributes[it] < 99 }
            .ifEmpty { player.primaryPosition.relevantAttributes }

        val used = usedAttributes(stats, player)
        val preferred = candidates.filter { it in used }.ifEmpty { candidates }

        return preferred.minBy { player.attributes[it] }
    }

    /** Il declino colpisce per primi gli attributi fisici: e' cosi' che si invecchia. */
    private fun pickAttributeToDrop(player: Player): Attr {
        val physical = listOf(Attr.VELOCITA, Attr.FISICO)
            .filter { it in player.primaryPosition.relevantAttributes }
            .filter { player.attributes[it] > 1 }

        return physical.maxByOrNull { player.attributes[it] }
            ?: player.primaryPosition.relevantAttributes
                .filter { player.attributes[it] > 1 }
                .maxByOrNull { player.attributes[it] }
            ?: Attr.FISICO
    }

    private fun usedAttributes(stats: PlayerMatchStats, player: Player): Set<Attr> = buildSet {
        if (stats.goals > 0) add(Attr.TIRO)
        if (stats.assists > 0) { add(Attr.PASSAGGIO); add(Attr.TECNICA) }
        if (stats.saves > 0) { add(Attr.PARATA); add(Attr.RIFLESSI) }
        if (stats.tackles > 0) { add(Attr.DIFESA); add(Attr.INTERCETTAZIONE) }
        if (stats.shots > 0) add(Attr.TIRO)
        if (stats.keyActions > 2) { add(Attr.DRIBBLING); add(Attr.TECNICA) }
    }

    private fun unchanged(player: Player) = GrowthOutcome(player, 0.0, emptyList())
}
