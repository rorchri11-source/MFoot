package dev.mfoot.core.calendar

import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.CompetitionId
import dev.mfoot.core.rng.DeterministicRandom

/**
 * Costruisce i turni di una competizione: chi affronta chi e in quale ordine.
 *
 * Si occupa solo degli **accoppiamenti**, non delle date: quelle le assegna il
 * [CalendarSolver], che deve coordinare piu' competizioni insieme. Tenere separate le
 * due cose significa poter testare gli accoppiamenti senza tirare in ballo il calendario.
 */
object FixtureGenerator {

    fun generate(competition: Competition, seed: Long = 0L): List<Round> =
        when (competition.type) {
            CompetitionType.GIRONE -> roundRobin(competition, seed)
            CompetitionType.ELIMINAZIONE_DIRETTA -> knockout(competition, seed)
            CompetitionType.GIRONI_PIU_ELIMINAZIONE -> groupsThenKnockout(competition, seed)
        }

    // -------------------------------------------------------------------- girone

    /**
     * Metodo del cerchio (tabelle di Berger).
     *
     * Una squadra resta fissa e le altre ruotano attorno: garantisce che ognuna affronti
     * tutte le altre esattamente una volta, con un turno di riposo a testa se il numero
     * e' dispari. E' l'algoritmo standard, ed e' importante usarlo invece di improvvisare
     * perche' produce anche un'alternanza casa-trasferta decente.
     */
    fun roundRobin(competition: Competition, seed: Long = 0L): List<Round> {
        val clubs = shuffleClubs(competition.participants, seed)
        val rounds = circleMethod(clubs, competition.id)

        if (!competition.doubleRound) return rounds

        // Ritorno: stessi accoppiamenti a campi invertiti.
        val returnRounds = rounds.map { round ->
            round.copy(
                number = round.number + rounds.size,
                label = "Giornata ${round.number + rounds.size}",
                pairings = round.pairings.map { Pairing(home = it.away, away = it.home) },
            )
        }
        return rounds + returnRounds
    }

    private fun circleMethod(clubs: List<ClubId>, competitionId: CompetitionId): List<Round> {
        // Con numero dispari si aggiunge un "riposo" fittizio.
        val withBye = if (clubs.size % 2 == 0) clubs else clubs + BYE
        val n = withBye.size
        val fixed = withBye.first()
        var rotating = withBye.drop(1)

        return (1 until n).map { roundNumber ->
            val lineup = listOf(fixed) + rotating
            val pairings = buildList {
                for (i in 0 until n / 2) {
                    val a = lineup[i]
                    val b = lineup[n - 1 - i]
                    if (a == BYE || b == BYE) continue
                    // Alternando casa e trasferta a turni pari e dispari si evita che
                    // la prima squadra giochi sempre in casa.
                    if (roundNumber % 2 == 0) add(Pairing(a, b)) else add(Pairing(b, a))
                }
            }
            rotating = listOf(rotating.last()) + rotating.dropLast(1)
            Round(competitionId, roundNumber, "Giornata $roundNumber", pairings)
        }
    }

    // ---------------------------------------------------------------- eliminazione

    /**
     * Tabellone a eliminazione diretta.
     *
     * I turni successivi al primo non possono essere generati adesso: dipendono da chi
     * vince. Qui si produce **solo il primo turno**, con l'etichetta giusta ("Sedicesimi",
     * "Ottavi", ...) ricavata dal numero di partecipanti. I turni successivi si generano
     * con [nextKnockoutRound] man mano che i risultati arrivano.
     */
    fun knockout(competition: Competition, seed: Long = 0L): List<Round> {
        val clubs = shuffleClubs(competition.participants, seed)
        return listOf(buildKnockoutRound(competition, clubs, roundNumber = 1))
    }

    /** Genera il turno successivo dati i vincitori del precedente. */
    fun nextKnockoutRound(
        competition: Competition,
        winners: List<ClubId>,
        previousRoundNumber: Int,
    ): Round? {
        if (winners.size < 2) return null
        return buildKnockoutRound(competition, winners, previousRoundNumber + 1)
    }

    private fun buildKnockoutRound(
        competition: Competition,
        clubs: List<ClubId>,
        roundNumber: Int,
    ): Round {
        val label = knockoutLabel(clubs.size)
        val isFinal = clubs.size == 2

        val pairings = buildList {
            var index = 0
            while (index + 1 < clubs.size) {
                val a = clubs[index]
                val b = clubs[index + 1]
                val tieId = "${competition.id.value}-r$roundNumber-${index / 2}"
                add(Pairing(a, b, tieId))
                // La finale resta sempre in gara secca, anche in un torneo a doppia gara.
                if (competition.twoLeggedKnockout && !isFinal) {
                    add(Pairing(b, a, tieId, isSecondLeg = true))
                }
                index += 2
            }
        }

        return Round(competition.id, roundNumber, label, pairings)
    }

    /** "Ottavi", "Quarti", "Semifinale", "Finale": dal numero di squadre rimaste. */
    fun knockoutLabel(remainingClubs: Int): String = when (remainingClubs) {
        2 -> "Finale"
        3, 4 -> "Semifinali"
        5, 6, 7, 8 -> "Quarti"
        in 9..16 -> "Ottavi"
        in 17..32 -> "Sedicesimi"
        else -> "Turno preliminare"
    }

    // -------------------------------------------------------- gironi + eliminazione

    /**
     * Formato mondiale: gironi all'italiana, poi tabellone fra le qualificate.
     *
     * Anche qui si genera solo la fase a gironi: chi si qualifica lo dira' la classifica.
     */
    fun groupsThenKnockout(competition: Competition, seed: Long = 0L): List<Round> {
        val groups = splitIntoGroups(competition, seed)

        // I gironi si giocano in parallelo: il turno 1 di ogni girone e' lo stesso turno.
        val perGroupRounds = groups.mapIndexed { index, group ->
            circleMethod(group, competition.id).map { round ->
                round.copy(label = "${round.label} - Girone ${'A' + index}")
            }
        }

        val maxRounds = perGroupRounds.maxOfOrNull { it.size } ?: 0
        return (0 until maxRounds).map { roundIndex ->
            val pairings = perGroupRounds.mapNotNull { it.getOrNull(roundIndex) }
                .flatMap { it.pairings }
            Round(
                competitionId = competition.id,
                number = roundIndex + 1,
                label = "Girone - giornata ${roundIndex + 1}",
                pairings = pairings,
            )
        }
    }

    fun splitIntoGroups(competition: Competition, seed: Long = 0L): List<List<ClubId>> {
        val clubs = shuffleClubs(competition.participants, seed)
        val perGroup = clubs.size / competition.groupCount
        return (0 until competition.groupCount).map { index ->
            clubs.subList(index * perGroup, (index + 1) * perGroup)
        }
    }

    // ------------------------------------------------------------------- utility

    /**
     * Il sorteggio.
     *
     * Con seed 0 l'ordine resta quello dato, il che rende i test leggibili. Con un seed
     * vero il sorteggio e' casuale ma riproducibile: lo stesso sorteggio si puo'
     * rigenerare e verificare, che in una lega fra amici evita discussioni.
     */
    private fun shuffleClubs(clubs: List<ClubId>, seed: Long): List<ClubId> =
        if (seed == 0L) clubs else DeterministicRandom(seed).shuffled(clubs)

    /** Segnaposto per il turno di riposo quando le squadre sono in numero dispari. */
    private val BYE = ClubId(-1L)
}
