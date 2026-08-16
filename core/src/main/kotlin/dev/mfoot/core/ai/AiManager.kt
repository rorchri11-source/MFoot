package dev.mfoot.core.ai

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.market.Auction
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.Lineup
import dev.mfoot.core.match.LineupSlot
import dev.mfoot.core.model.Club
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position
import dev.mfoot.core.rng.MathX
import dev.mfoot.core.world.PotentialEstimator

/** Quanto interessa un giocatore a una AI, e fino a dove si spingerebbe. */
data class TargetAppeal(
    val player: Player,
    /** 0 = non interessa, 1 = lo vuole moltissimo. */
    val appeal: Double,
    /** Tetto massimo in crediti. Non viene superato in nessuna circostanza. */
    val ceiling: Int,
    val reason: String,
) {
    val isInterested: Boolean get() = appeal > 0.15 && ceiling >= 1
}

/**
 * Le decisioni di un club AI.
 *
 * ## Le regole che l'utente ha chiesto
 *
 * L'AI deve essere competitiva — puo' benissimo intromettersi in un duello fra due
 * amici — ma non deve **mai** comportarsi come uno sciame. Le regole anti-sciame sono
 * distribuite fra questa classe e [AiScheduler]:
 *
 * 1. **Risvegli scaglionati** ([AiScheduler]) — un'AI che dorme non sa che l'asta esiste.
 * 2. **Si scansano da sole** — l'appetibilita' cala per ogni AI gia' impegnata sullo
 *    stesso giocatore: la seconda ci pensa, la terza quasi mai, la quarta mai.
 * 3. **Tetto di azioni giornaliere** ([AiScheduler]).
 * 4. **Tetto di notifiche** — imposto dal server, non da qui.
 * 5. **Non insistono** — dopo un rifiuto stanno ferme per N giornate.
 * 6. **Non barano mai** — valutano sulla forbice stimata, non sui valori veri.
 *
 * ## Perche' tutto e' in percentuale del budget
 *
 * L'AI non conosce nessun numero assoluto. Ragiona sempre in frazione del proprio
 * disponibile, quindi si adatta da sola a una lega povera come a una ricca, senza che
 * l'admin debba tarare niente.
 */
object AiManager {

    /**
     * Quanto interessa questo giocatore, e fino a quanto si spingerebbe.
     *
     * @param competingAi quante altre AI stanno gia' puntando questo giocatore
     */
    fun evaluate(
        state: AiState,
        club: Club,
        squad: List<Player>,
        player: Player,
        config: LeagueConfig,
        competingAi: Int = 0,
    ): TargetAppeal {
        val personality = state.personality

        // Regola 6: la stima usa la stessa incertezza che ha un umano. Un'AI che
        // leggesse i potenziali veri comprerebbe sempre i giovani giusti e sembrerebbe
        // truccata.
        val estimate = PotentialEstimator.estimate(
            player = player,
            observerId = club.id.value,
            minutesObserved = 0,
            scoutAccuracy = 0.0,
        )
        val estimatedValue = Valuation.estimatedValue(player, estimate, config)

        var appeal = qualityAppeal(player, estimate)
        appeal *= needFactor(squad, player.primaryPosition, config)
        appeal *= youthFit(personality, player, config)
        appeal += personality.obsessionBonusFor(player.primaryPosition)
        if (personality.preferredNationality == player.nationality &&
            AiObsession.CONNAZIONALI in personality.obsessions
        ) {
            appeal += 0.2
        }

        // Regola 2: piu' AI sono gia' sopra a questo giocatore, meno interessa.
        appeal *= crowdingFactor(competingAi, config)

        appeal = appeal.coerceIn(0.0, 1.5)

        val ceiling = ceilingFor(personality, club, estimatedValue, appeal, config)

        return TargetAppeal(
            player = player,
            appeal = appeal,
            ceiling = ceiling,
            reason = reasonFor(personality, player, squad, competingAi),
        )
    }

    /**
     * Fino a quanto si spinge questa AI.
     *
     * Il tetto viene calcolato **una volta sola** all'inizio e non viene mai superato:
     * l'AI non si fa prendere dalla foga. E' quello che permette a un umano di battere
     * un'AI semplicemente valutando il giocatore di piu' di quanto lo valuti lei.
     */
    fun ceilingFor(
        personality: AiPersonality,
        club: Club,
        estimatedValue: Int,
        appeal: Double,
        config: LeagueConfig,
    ): Int {
        val available = club.availableCredits.coerceAtLeast(0)
        if (available <= 0) return 0

        // Frazione massima del disponibile che questa AI mette su un singolo giocatore.
        val maxShare = MathX.lerp(0.45, 0.18, personality.budgetDiscipline)
        val hardCap = available * maxShare

        val desired = estimatedValue * (0.75 + personality.marketAggression * 0.55) * appeal

        return StrictMath.round(minOf(desired, hardCap)).toInt().coerceIn(0, available)
    }

    /**
     * L'offerta massima da dichiarare in un'asta, o null per passare.
     *
     * Restituisce sempre il proprio **tetto**, non un rilancio minimo: e' il modo
     * corretto di usare l'offerta massima automatica, e impedisce all'AI di partecipare
     * a una guerra di rilanci al centesimo.
     */
    fun decideBid(
        state: AiState,
        club: Club,
        auction: Auction,
        appeal: TargetAppeal,
        config: LeagueConfig,
    ): Int? {
        if (!appeal.isInterested) return null
        if (auction.id in state.abandonedTargets.map { it }) return null

        val currentPrice = auction.currentPrice(config.market)
        val minimum = currentPrice + config.market.minimumRaise

        // Regola: il tetto e' il tetto. Se il prezzo lo ha superato, si molla.
        if (minimum > appeal.ceiling) return null
        if (appeal.ceiling > club.availableCredits) return null

        return appeal.ceiling
    }

    /**
     * Dopo essere stata superata due volte sullo stesso giocatore, l'AI lascia perdere.
     *
     * Serve a evitare i duelli infiniti che riempiono di notifiche l'umano dall'altra
     * parte, ma anche a rendere l'AI un avversario battibile: chi insiste, vince.
     */
    fun abandonAfterOutbids(state: AiState, auctionId: Long, timesOutbid: Int): AiState =
        if (timesOutbid >= 2) {
            state.copy(abandonedTargets = state.abandonedTargets + auctionId)
        } else {
            state
        }

    // ---------------------------------------------------------------- formazione

    /**
     * Schiera la formazione migliore **rispettando la stamina**.
     *
     * Con due partite al giorno un'AI che schiera sempre gli stessi undici si distrugge
     * la rosa da sola e diventa un avversario ridicolo a meta' stagione. Turna come
     * dovrebbe fare un umano.
     */
    fun chooseLineup(
        clubId: ClubId,
        name: String,
        squad: List<Player>,
        formation: Formation = Formation.F_4_3_3,
        mustStart: Player? = null,
    ): Lineup? {
        if (squad.size < Formation.PLAYERS_ON_PITCH) return null

        val available = squad.toMutableList()
        val slots = mutableListOf<LineupSlot>()

        // Il giocatore obbligatorio (il custom del proprietario) va sistemato per primo,
        // nel ruolo in cui rende di piu' fra quelli previsti dal modulo.
        mustStart?.let { forced ->
            val position = formation.positions
                .maxByOrNull { forced.overallAt(it) } ?: forced.primaryPosition
            slots += LineupSlot(forced, position)
            available.remove(forced)
        }

        val remainingPositions = formation.positions.toMutableList()
        slots.forEach { remainingPositions.remove(it.position) }

        for (position in remainingPositions) {
            val best = available.maxByOrNull { score(it, position) } ?: break
            slots += LineupSlot(best, position)
            available.remove(best)
        }

        if (slots.size < Formation.PLAYERS_ON_PITCH) return null

        return Lineup(
            formation = formation,
            slots = slots.sortedBy { formation.positions.indexOf(it.position) },
            bench = available.sortedByDescending { it.overall }.take(7),
        )
    }

    /**
     * Quanto vale schierare questo giocatore qui, adesso.
     *
     * La stanchezza pesa in modo non lineare: un giocatore a 40 di stamina non vale
     * "un po' meno", vale molto meno, perche' crollera' nel finale.
     */
    private fun score(player: Player, position: Position): Double {
        val ability = player.overallAt(position).toDouble()
        val freshness = MathX.remap(player.stamina.toDouble(), 30.0, 90.0, 0.55, 1.0)
        val moraleFactor = MathX.remap(player.morale.toDouble(), 0.0, 100.0, 0.90, 1.05)
        return ability * freshness * moraleFactor
    }

    // ------------------------------------------------------------------ interni

    /** Quanto e' forte e promettente, sulla base della sola stima. */
    private fun qualityAppeal(player: Player, estimate: IntRange): Double {
        val now = Valuation.overallScore(player.overall.toDouble())
        val potential = Valuation.overallScore(((estimate.first + estimate.last) / 2).toDouble())
        return (now * 0.55 + potential * 0.45).coerceIn(0.0, 1.0)
    }

    /**
     * Quanto serve un giocatore di quel ruolo.
     *
     * Un club senza portieri di riserva deve volere un portiere molto piu' di un
     * quarto attaccante, altrimenti costruisce rose assurde.
     */
    private fun needFactor(squad: List<Player>, position: Position, config: LeagueConfig): Double {
        val inRole = squad.count { it.primaryPosition == position }
        val squadSize = squad.size
        val minSize = config.setup.minSquadSize

        val roleNeed = when (inRole) {
            0 -> 1.6
            1 -> 1.25
            2 -> 1.0
            3 -> 0.7
            else -> 0.4
        }
        // Sotto la rosa minima si compra qualsiasi cosa: e' un obbligo, non una scelta.
        val sizeNeed = if (squadSize < minSize) 1.4 else 1.0
        return roleNeed * sizeNeed
    }

    /** Quanto questo giocatore corrisponde al gusto giovani/pronti del club. */
    private fun youthFit(
        personality: AiPersonality,
        player: Player,
        config: LeagueConfig,
    ): Double {
        val isYoung = player.age <= config.rules.peakAgeStart
        val isVeteran = player.age >= config.rules.declineAge
        return when {
            isYoung -> MathX.lerp(0.75, 1.30, personality.youthPreference)
            isVeteran -> MathX.lerp(1.05, 0.55, personality.youthPreference)
            else -> 1.0
        }
    }

    /**
     * Regola anti-sciame numero 2.
     *
     * L'appetibilita' cala per ogni AI gia' impegnata sullo stesso giocatore. Con la
     * penalita' di default, la seconda AI ci pensa, la terza quasi mai e la quarta mai:
     * il risultato naturale e' 1-3 AI per asta invece di venticinque.
     */
    fun crowdingFactor(competingAi: Int, config: LeagueConfig): Double {
        if (competingAi <= 0) return 1.0
        return MathX.pow(1.0 - config.ai.crowdingPenalty, competingAi.toDouble())
    }

    private fun reasonFor(
        personality: AiPersonality,
        player: Player,
        squad: List<Player>,
        competingAi: Int,
    ): String = when {
        competingAi >= 3 -> "Troppa concorrenza, lascia perdere"
        squad.none { it.primaryPosition == player.primaryPosition } ->
            "Manca completamente un ${player.primaryPosition.label.lowercase()}"
        personality.youthPreference > 0.7 && player.age <= 22 -> "Punta sui giovani"
        AiObsession.PORTIERE_FORTE in personality.obsessions && player.isGoalkeeper ->
            "Vuole un portiere di livello"
        else -> "Rinforzo di rosa"
    }
}
