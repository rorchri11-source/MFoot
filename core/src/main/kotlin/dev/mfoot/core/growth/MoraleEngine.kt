package dev.mfoot.core.growth

import dev.mfoot.core.config.RulesConfig
import dev.mfoot.core.match.PlayerMatchStats
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Trait
import dev.mfoot.core.model.moraleVolatility
import dev.mfoot.core.model.squadMoraleBonus

enum class TeamOutcome { VITTORIA, PAREGGIO, SCONFITTA }

/** Cosa e' successo al morale e perche', per poterlo raccontare al giocatore. */
data class MoraleChange(
    val player: Player,
    val delta: Int,
    val reasons: List<String>,
) {
    val isUnhappy: Boolean get() = player.morale < 30
}

/**
 * Il morale dello spogliatoio.
 *
 * ## Perche' esiste
 *
 * Senza morale, tenere un fuoriclasse in panchina per venti giornate non costerebbe
 * niente e la profondita' della rosa sarebbe una risorsa gratuita. Con il morale ha un
 * prezzo: il giocatore rende meno, poi chiede la cessione, e alla scadenza rifiuta il
 * rinnovo. Le tre cose sono collegate e nascono tutte da qui.
 *
 * ## I tratti contano davvero
 *
 * Un *Testa calda* oscilla molto piu' di un *Uomo spogliatoio*: la stessa panchina
 * produce reazioni diverse. E' quello che rende sensato leggere la scheda di un
 * giocatore prima di comprarlo.
 */
object MoraleEngine {

    private const val PLAYED_FULL_MATCH = 3
    private const val PLAYED_PARTIAL = 1
    /**
     * Deve superare in valore assoluto il bonus di una vittoria: restare in panchina
     * costa sempre qualcosa, anche quando la squadra vince. Altrimenti si potrebbe
     * tenere un fuoriclasse fuori a tempo indeterminato senza pagarne il prezzo.
     */
    private const val BENCHED = -5
    private const val NOT_IN_SQUAD = -4
    private const val SUBSTITUTED_EARLY = -2

    private const val PER_GOAL = 5
    private const val PER_ASSIST = 3
    private const val GREAT_RATING = 4
    private const val POOR_RATING = -3

    private const val WIN = 3
    private const val DRAW = 0
    private const val LOSS = -2

    private const val RED_CARD = -3

    /** Effetto di una partita giocata. */
    fun afterMatch(
        player: Player,
        stats: PlayerMatchStats,
        outcome: TeamOutcome,
        rules: RulesConfig,
        squadLeadershipBonus: Double = 0.0,
    ): MoraleChange {
        if (!rules.moraleEnabled) return MoraleChange(player, 0, emptyList())

        val reasons = mutableListOf<String>()
        var delta = 0.0

        when {
            stats.minutesPlayed >= 60 -> {
                delta += PLAYED_FULL_MATCH
                reasons += "ha giocato"
            }
            stats.minutesPlayed > 0 -> {
                delta += PLAYED_PARTIAL
                reasons += "è entrato a partita in corso"
            }
            else -> {
                delta += BENCHED
                reasons += "è rimasto in panchina"
            }
        }

        // Uscire presto e' una bocciatura, non un riposo.
        if (stats.minutesPlayed in 1..44) {
            delta += SUBSTITUTED_EARLY
            reasons += "è stato sostituito presto"
        }

        if (stats.goals > 0) {
            delta += PER_GOAL * stats.goals
            reasons += if (stats.goals == 1) "ha segnato" else "ha segnato ${stats.goals} gol"
        }
        if (stats.assists > 0) {
            delta += PER_ASSIST * stats.assists
            reasons += "ha servito assist"
        }
        if (stats.redCards > 0) {
            delta += RED_CARD
            reasons += "è stato espulso"
        }

        if (stats.minutesPlayed > 0) {
            val rating = stats.rating(player.isGoalkeeper)
            when {
                rating >= 7.5 -> { delta += GREAT_RATING; reasons += "ha fatto una grande partita" }
                rating <= 5.0 -> { delta += POOR_RATING; reasons += "ha giocato male" }
            }
        }

        delta += when (outcome) {
            TeamOutcome.VITTORIA -> { reasons += "la squadra ha vinto"; WIN }
            TeamOutcome.PAREGGIO -> DRAW
            TeamOutcome.SCONFITTA -> { reasons += "la squadra ha perso"; LOSS }
        }.toDouble()

        delta += squadLeadershipBonus

        val volatility = player.traits.moraleVolatility()
        val finalDelta = StrictMath.round(delta * volatility).toInt()

        return MoraleChange(
            player = player.withMorale(player.morale + finalDelta),
            delta = finalDelta,
            reasons = reasons,
        )
    }

    /**
     * Effetto di una giornata saltata del tutto, senza nemmeno andare in panchina.
     * Pesa piu' della panchina: e' l'esclusione completa.
     */
    fun afterExcludedMatchDay(player: Player, rules: RulesConfig): MoraleChange {
        if (!rules.moraleEnabled) return MoraleChange(player, 0, emptyList())

        val delta = StrictMath.round(NOT_IN_SQUAD * player.traits.moraleVolatility()).toInt()
        return MoraleChange(
            player = player.withMorale(player.morale + delta),
            delta = delta,
            reasons = listOf("non è stato nemmeno convocato"),
        )
    }

    /** Bonus di morale che i leader regalano al resto della rosa, a testa. */
    fun leadershipBonus(squad: List<Player>): Double {
        val total = squad.sumOf { it.traits.squadMoraleBonus() }
        // Diviso per la rosa: un leader in venti conta meno che uno in undici.
        return if (squad.isEmpty()) 0.0 else (total / squad.size) * 0.6
    }

    /** Un rinnovo e' una conferma: tira su il morale in modo sensibile. */
    fun afterRenewal(player: Player): MoraleChange {
        val delta = StrictMath.round(12 * player.traits.moraleVolatility()).toInt()
        return MoraleChange(
            player.withMorale(player.morale + delta),
            delta,
            listOf("gli è stato rinnovato il contratto"),
        )
    }

    /** Vedersi rifiutare una cessione che si era chiesta brucia. */
    fun afterRefusedTransfer(player: Player): MoraleChange {
        val delta = -StrictMath.round(9 * player.traits.moraleVolatility()).toInt()
        return MoraleChange(
            player.withMorale(player.morale + delta),
            delta,
            listOf("gli è stata rifiutata la cessione"),
        )
    }

    /**
     * Il giocatore chiede di andarsene?
     *
     * Serve morale sotto soglia **e** un tratto che lo renda plausibile: un *Fedele*
     * incassa e resta, un *Ambizioso* fa le valigie molto prima.
     */
    fun wantsToLeave(player: Player, rules: RulesConfig): Boolean {
        if (!rules.moraleEnabled) return false
        if (player.isCustom) return false  // il giocatore del proprietario non se ne va

        val threshold = when {
            Trait.FEDELE in player.traits -> rules.lowMoraleThreshold - 12
            Trait.AMBIZIOSO in player.traits -> rules.lowMoraleThreshold + 8
            else -> rules.lowMoraleThreshold
        }
        return player.morale < threshold
    }

    /**
     * Il giocatore accetterebbe il rinnovo?
     *
     * E' l'aggancio fra morale e mercato: tenerlo in tribuna tutta la stagione e poi
     * pretendere che rifirmi non funziona.
     */
    fun wouldAcceptRenewal(player: Player, rules: RulesConfig): Boolean {
        if (!rules.moraleEnabled) return true
        if (player.isCustom) return true
        return player.morale >= rules.lowMoraleThreshold
    }
}
