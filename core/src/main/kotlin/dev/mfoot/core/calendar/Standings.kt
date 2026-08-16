package dev.mfoot.core.calendar

import dev.mfoot.core.model.ClubId

/** Una riga di classifica. */
data class StandingRow(
    val club: ClubId,
    val played: Int = 0,
    val won: Int = 0,
    val drawn: Int = 0,
    val lost: Int = 0,
    val goalsFor: Int = 0,
    val goalsAgainst: Int = 0,
    val points: Int = 0,
) {
    val goalDifference: Int get() = goalsFor - goalsAgainst

    fun describe(position: Int): String =
        "$position. $club  $played  $won-$drawn-$lost  $goalsFor:$goalsAgainst  ${points}pt"
}

/**
 * Calcola la classifica di una competizione.
 *
 * I criteri di spareggio sono quelli che l'admin ha scelto, applicati **nell'ordine
 * indicato**: e' una delle cose su cui in una lega fra amici si litiga volentieri, e
 * averla scritta nella configurazione chiude la discussione prima che cominci.
 */
object Standings {

    fun compute(
        competition: Competition,
        results: List<FixtureResult>,
        clubs: List<ClubId> = competition.participants,
    ): List<StandingRow> {
        val relevant = results.filter { it.competitionId == competition.id }
        val rows = clubs.associateWith { StandingRow(it) }.toMutableMap()

        for (result in relevant) {
            rows[result.home]?.let { rows[result.home] = applyHome(it, result, competition) }
            rows[result.away]?.let { rows[result.away] = applyAway(it, result, competition) }
        }

        return rows.values.sortedWith(comparator(competition, relevant))
            .toList()
    }

    private fun applyHome(row: StandingRow, r: FixtureResult, c: Competition): StandingRow {
        val points = when {
            r.homeGoals > r.awayGoals -> c.pointsForWin
            r.homeGoals == r.awayGoals -> c.pointsForDraw
            else -> 0
        }
        return row.copy(
            played = row.played + 1,
            won = row.won + if (r.homeGoals > r.awayGoals) 1 else 0,
            drawn = row.drawn + if (r.homeGoals == r.awayGoals) 1 else 0,
            lost = row.lost + if (r.homeGoals < r.awayGoals) 1 else 0,
            goalsFor = row.goalsFor + r.homeGoals,
            goalsAgainst = row.goalsAgainst + r.awayGoals,
            points = row.points + points,
        )
    }

    private fun applyAway(row: StandingRow, r: FixtureResult, c: Competition): StandingRow {
        val points = when {
            r.awayGoals > r.homeGoals -> c.pointsForWin
            r.awayGoals == r.homeGoals -> c.pointsForDraw
            else -> 0
        }
        return row.copy(
            played = row.played + 1,
            won = row.won + if (r.awayGoals > r.homeGoals) 1 else 0,
            drawn = row.drawn + if (r.awayGoals == r.homeGoals) 1 else 0,
            lost = row.lost + if (r.awayGoals < r.homeGoals) 1 else 0,
            goalsFor = row.goalsFor + r.awayGoals,
            goalsAgainst = row.goalsAgainst + r.homeGoals,
            points = row.points + points,
        )
    }

    private fun comparator(
        competition: Competition,
        results: List<FixtureResult>,
    ): Comparator<StandingRow> {
        var comparator = compareByDescending<StandingRow> { it.points }

        for (tiebreaker in competition.tiebreakers) {
            comparator = when (tiebreaker) {
                Tiebreaker.DIFFERENZA_RETI -> comparator.thenByDescending { it.goalDifference }
                Tiebreaker.GOL_FATTI -> comparator.thenByDescending { it.goalsFor }
                Tiebreaker.PARTITE_VINTE -> comparator.thenByDescending { it.won }
                Tiebreaker.GOL_SUBITI -> comparator.thenBy { it.goalsAgainst }
                Tiebreaker.SCONTRI_DIRETTI -> comparator.thenByDescending {
                    headToHeadPoints(it.club, results, competition)
                }
            }
        }

        // Ultimo criterio stabile: senza, due squadre identiche cambierebbero posto a
        // ogni ricalcolo e la classifica sembrerebbe rotta.
        return comparator.thenBy { it.club.value }
    }

    /**
     * Punti conquistati negli scontri diretti con le squadre a pari punti.
     *
     * Approssimazione volutamente semplice: si contano i punti fatti contro **tutte** le
     * squadre con lo stesso punteggio. Il criterio esatto dei mini-campionati sarebbe
     * piu' preciso ma anche molto piu' difficile da spiegare a venti amici.
     */
    private fun headToHeadPoints(
        club: ClubId,
        results: List<FixtureResult>,
        competition: Competition,
    ): Int = results
        .filter { it.involves(club) }
        .sumOf { result ->
            when {
                result.winner == club -> competition.pointsForWin
                result.winner == null -> competition.pointsForDraw
                else -> 0
            }
        }

    private fun FixtureResult.involves(club: ClubId) = home == club || away == club

    /**
     * Chi passa il turno in un accoppiamento a eliminazione.
     *
     * Nella gara di ritorno conta il totale delle due partite. In caso di parita' passa
     * chi ha giocato in casa il ritorno: e' la regola piu' semplice da capire, e in una
     * lega fra amici la semplicita' vale piu' della fedelta' al regolamento UEFA.
     */
    fun tieWinner(legs: List<FixtureResult>): ClubId? {
        if (legs.isEmpty()) return null
        if (legs.size == 1) {
            val single = legs.first()
            return single.winner ?: single.home
        }

        val first = legs.first()
        val second = legs.last()
        val teamA = first.home
        val teamB = first.away

        val aGoals = first.homeGoals + second.awayGoals
        val bGoals = first.awayGoals + second.homeGoals

        return when {
            aGoals > bGoals -> teamA
            bGoals > aGoals -> teamB
            else -> second.home
        }
    }

    /** Le squadre qualificate dalla fase a gironi, prese in ordine di classifica. */
    fun qualifiers(
        competition: Competition,
        groups: List<List<ClubId>>,
        results: List<FixtureResult>,
    ): List<ClubId> = groups.flatMap { group ->
        compute(competition, results, group).take(competition.qualifiersPerGroup).map { it.club }
    }
}
