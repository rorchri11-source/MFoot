package dev.mfoot.core.calendar

import dev.mfoot.core.match.MatchImportance
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.CompetitionId
import dev.mfoot.core.model.MatchDay
import java.time.LocalDateTime

enum class CompetitionType(val label: String) {
    /** Tutti contro tutti. Vince chi fa piu' punti. */
    GIRONE("Campionato"),

    /** Tabellone a eliminazione. Chi perde esce. */
    ELIMINAZIONE_DIRETTA("Coppa"),

    /** Gironi iniziali, poi tabellone fra i qualificati. Il formato dei mondiali. */
    GIRONI_PIU_ELIMINAZIONE("Gironi + eliminazione"),
}

/** Criteri di spareggio in classifica, applicati nell'ordine indicato dall'admin. */
enum class Tiebreaker(val label: String) {
    DIFFERENZA_RETI("Differenza reti"),
    GOL_FATTI("Gol fatti"),
    SCONTRI_DIRETTI("Scontri diretti"),
    PARTITE_VINTE("Partite vinte"),
    GOL_SUBITI("Gol subiti (meno è meglio)"),
}

/**
 * Una competizione creata dall'admin.
 *
 * L'admin puo' crearne quante vuole e farle girare in parallelo: campionato piu' coppa
 * e' la combinazione normale. Il risolutore di calendario le coordina, cosi' un club che
 * gioca in entrambe non si ritrova con tre partite lo stesso giorno.
 */
data class Competition(
    val id: CompetitionId,
    val name: String,
    val type: CompetitionType,
    val participants: List<ClubId>,
    /** Andata e ritorno nel girone. */
    val doubleRound: Boolean = false,
    val pointsForWin: Int = 3,
    val pointsForDraw: Int = 1,
    val tiebreakers: List<Tiebreaker> = listOf(
        Tiebreaker.DIFFERENZA_RETI,
        Tiebreaker.GOL_FATTI,
        Tiebreaker.SCONTRI_DIRETTI,
    ),
    /** Montepremi per posizione finale, dal primo in giu'. */
    val prizes: List<Int> = emptyList(),
    /** Solo per [CompetitionType.GIRONI_PIU_ELIMINAZIONE]. */
    val groupCount: Int = 4,
    val qualifiersPerGroup: Int = 2,
    /** Turni di eliminazione con andata e ritorno (la finale resta secca). */
    val twoLeggedKnockout: Boolean = false,
    val importance: MatchImportance = MatchImportance.CAMPIONATO,
) {
    init {
        require(participants.size >= 2) { "'$name': servono almeno due partecipanti" }
        require(participants.toSet().size == participants.size) {
            "'$name': lo stesso club compare più volte fra i partecipanti"
        }
        require(pointsForWin >= pointsForDraw) {
            "'$name': vincere non può valere meno che pareggiare"
        }
        if (type == CompetitionType.GIRONI_PIU_ELIMINAZIONE) {
            require(groupCount >= 1) { "'$name': serve almeno un girone" }
            require(participants.size % groupCount == 0) {
                "'$name': ${participants.size} squadre non si dividono in $groupCount gironi uguali"
            }
            require(qualifiersPerGroup in 1 until participants.size / groupCount) {
                "'$name': qualificate per girone non valide"
            }
        }
    }

    val clubCount: Int get() = participants.size
}

/**
 * Una partita in programma.
 *
 * [matchDay] e' l'unita' di tempo del gioco e serve a contratti, stipendi e crescita.
 * [kickoff] e' l'orario reale, deciso dal risolutore: le due cose sono indipendenti,
 * cosi' l'admin puo' cambiare il ritmo nel mondo reale senza rompere nessun sistema.
 */
data class Fixture(
    val id: Long,
    val competitionId: CompetitionId,
    /** Numero progressivo del turno all'interno della competizione, da 1. */
    val round: Int,
    val roundLabel: String,
    val home: ClubId,
    val away: ClubId,
    val matchDay: MatchDay,
    val kickoff: LocalDateTime? = null,
    /** Per l'andata e ritorno: identifica le due gare dello stesso accoppiamento. */
    val tieId: String? = null,
    val isSecondLeg: Boolean = false,
) {
    init {
        require(home != away) { "una squadra non può giocare contro se stessa" }
    }

    fun involves(club: ClubId): Boolean = club == home || club == away

    fun opponentOf(club: ClubId): ClubId? = when (club) {
        home -> away
        away -> home
        else -> null
    }

    val isScheduled: Boolean get() = kickoff != null
}

/** Un turno: tutte le partite che si giocano nello stesso momento della competizione. */
data class Round(
    val competitionId: CompetitionId,
    val number: Int,
    val label: String,
    val pairings: List<Pairing>,
) {
    val clubs: Set<ClubId> get() = pairings.flatMap { listOf(it.home, it.away) }.toSet()
}

data class Pairing(
    val home: ClubId,
    val away: ClubId,
    val tieId: String? = null,
    val isSecondLeg: Boolean = false,
)

/** Il risultato di una partita giocata, per costruire la classifica. */
data class FixtureResult(
    val fixtureId: Long,
    val competitionId: CompetitionId,
    val home: ClubId,
    val away: ClubId,
    val homeGoals: Int,
    val awayGoals: Int,
) {
    val winner: ClubId? get() = when {
        homeGoals > awayGoals -> home
        awayGoals > homeGoals -> away
        else -> null
    }
}
