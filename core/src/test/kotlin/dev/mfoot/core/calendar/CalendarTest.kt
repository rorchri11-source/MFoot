package dev.mfoot.core.calendar

import dev.mfoot.core.config.CalendarConfig
import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.CompetitionId
import dev.mfoot.core.model.MatchDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun clubs(n: Int): List<ClubId> = (1..n).map { ClubId(it.toLong()) }

class FixtureGeneratorTest {

    private fun league(n: Int, doubleRound: Boolean = false) = Competition(
        id = CompetitionId(1),
        name = "Campionato",
        type = CompetitionType.GIRONE,
        participants = clubs(n),
        doubleRound = doubleRound,
    )

    @Test
    fun `un girone con venti squadre ha diciannove giornate`() {
        val rounds = FixtureGenerator.roundRobin(league(20))
        assertEquals(19, rounds.size)
        assertTrue(rounds.all { it.pairings.size == 10 })
    }

    @Test
    fun `andata e ritorno raddoppia le giornate`() {
        assertEquals(38, FixtureGenerator.roundRobin(league(20, doubleRound = true)).size)
    }

    /**
     * L'invariante del metodo del cerchio: ognuno affronta tutti gli altri esattamente
     * una volta. Sbagliarlo produce campionati in cui due squadre non si incontrano mai
     * e nessuno se ne accorge fino a meta' stagione.
     */
    @Test
    fun `ogni squadra affronta tutte le altre esattamente una volta`() {
        listOf(4, 8, 12, 16, 20).forEach { n ->
            val rounds = FixtureGenerator.roundRobin(league(n))
            val incontri = mutableMapOf<Set<ClubId>, Int>()

            rounds.flatMap { it.pairings }.forEach { p ->
                val key = setOf(p.home, p.away)
                incontri[key] = (incontri[key] ?: 0) + 1
            }

            val attesi = n * (n - 1) / 2
            assertEquals(attesi, incontri.size, "con $n squadre mancano degli accoppiamenti")
            assertTrue(incontri.values.all { it == 1 }, "con $n squadre ci sono doppioni")
        }
    }

    @Test
    fun `nessuna squadra gioca due volte nella stessa giornata`() {
        FixtureGenerator.roundRobin(league(16)).forEach { round ->
            val impegnate = round.pairings.flatMap { listOf(it.home, it.away) }
            assertEquals(impegnate.size, impegnate.toSet().size, "doppio impegno nel turno ${round.number}")
        }
    }

    @Test
    fun `con numero dispari una squadra riposa a turno`() {
        val rounds = FixtureGenerator.roundRobin(league(7))
        assertEquals(7, rounds.size)
        rounds.forEach { assertEquals(3, it.pairings.size, "il turno ${it.number} non lascia nessuno a riposo") }
    }

    @Test
    fun `casa e trasferta sono ragionevolmente bilanciate`() {
        val rounds = FixtureGenerator.roundRobin(league(16))
        val inCasa = mutableMapOf<ClubId, Int>()
        rounds.flatMap { it.pairings }.forEach { inCasa[it.home] = (inCasa[it.home] ?: 0) + 1 }

        val totale = rounds.size
        inCasa.forEach { (club, casa) ->
            assertTrue(
                casa in (totale / 2 - 2)..(totale / 2 + 2),
                "$club gioca $casa partite in casa su $totale",
            )
        }
    }

    @Test
    fun `nel ritorno i campi sono invertiti`() {
        val rounds = FixtureGenerator.roundRobin(league(8, doubleRound = true))
        val andata = rounds.first().pairings.first()
        val ritorno = rounds[rounds.size / 2].pairings.first()
        assertEquals(andata.home, ritorno.away)
        assertEquals(andata.away, ritorno.home)
    }

    @Test
    fun `il sorteggio con seed e riproducibile`() {
        val a = FixtureGenerator.roundRobin(league(12), seed = 42L)
        val b = FixtureGenerator.roundRobin(league(12), seed = 42L)
        val c = FixtureGenerator.roundRobin(league(12), seed = 99L)

        assertEquals(a.map { it.pairings }, b.map { it.pairings })
        assertTrue(a.map { it.pairings } != c.map { it.pairings })
    }

    // ------------------------------------------------------------- eliminazione

    private fun cup(n: Int, twoLegged: Boolean = false) = Competition(
        id = CompetitionId(2),
        name = "Coppa",
        type = CompetitionType.ELIMINAZIONE_DIRETTA,
        participants = clubs(n),
        twoLeggedKnockout = twoLegged,
    )

    @Test
    fun `l'eliminazione diretta genera il primo turno con le etichette giuste`() {
        assertEquals("Ottavi", FixtureGenerator.knockout(cup(16)).first().label)
        assertEquals("Quarti", FixtureGenerator.knockout(cup(8)).first().label)
        assertEquals("Semifinali", FixtureGenerator.knockout(cup(4)).first().label)
        assertEquals("Finale", FixtureGenerator.knockout(cup(2)).first().label)
    }

    @Test
    fun `un turno di eliminazione accoppia tutti una volta sola`() {
        val round = FixtureGenerator.knockout(cup(16)).first()
        assertEquals(8, round.pairings.size)
        val impegnate = round.pairings.flatMap { listOf(it.home, it.away) }
        assertEquals(16, impegnate.toSet().size)
    }

    /**
     * L'andata e il ritorno sono **due turni**, non due gare dentro lo stesso.
     *
     * Nello stesso `Round` finivano nella stessa fascia oraria — il risolutore colloca un
     * turno intero in una fascia sola — cioe' la stessa squadra in casa e in trasferta
     * allo stesso istante.
     */
    @Test
    fun `l'andata e ritorno raddoppia le gare ma non la finale`() {
        val ottavi = FixtureGenerator.knockout(cup(16, twoLegged = true))
        assertEquals(2, ottavi.size, "andata e ritorno sono due turni distinti")
        assertEquals(8, ottavi[0].pairings.size)
        assertEquals(8, ottavi[1].pairings.size)
        assertTrue(ottavi[1].pairings.all { it.isSecondLeg })
        assertEquals(
            ottavi[0].pairings.map { it.tieId },
            ottavi[1].pairings.map { it.tieId },
            "le due gare di un confronto restano lo stesso accoppiamento",
        )

        val finale = FixtureGenerator.knockout(cup(2, twoLegged = true))
        assertEquals(1, finale.size, "la finale deve restare in gara secca")
        assertEquals(1, finale.first().pairings.size)
    }

    @Test
    fun `il turno successivo si costruisce dai vincitori`() {
        val competition = cup(16)
        val vincitori = clubs(8)
        val quarti = FixtureGenerator.nextKnockoutRound(competition, vincitori, previousRoundNumber = 1)

        assertEquals(1, quarti.size)
        assertEquals("Quarti", quarti.first().label)
        assertEquals(4, quarti.first().pairings.size)
    }

    @Test
    fun `con un solo vincitore il torneo e finito`() {
        assertTrue(FixtureGenerator.nextKnockoutRound(cup(2), listOf(ClubId(1)), 1).isEmpty())
    }

    // ----------------------------------------------------- gironi + eliminazione

    private fun worldCup() = Competition(
        id = CompetitionId(3),
        name = "Mondiale",
        type = CompetitionType.GIRONI_PIU_ELIMINAZIONE,
        participants = clubs(16),
        groupCount = 4,
        qualifiersPerGroup = 2,
    )

    @Test
    fun `i gironi hanno tutti lo stesso numero di squadre`() {
        val gironi = FixtureGenerator.splitIntoGroups(worldCup())
        assertEquals(4, gironi.size)
        assertTrue(gironi.all { it.size == 4 })
        assertEquals(16, gironi.flatten().toSet().size)
    }

    @Test
    fun `la fase a gironi mette in parallelo tutti i gironi`() {
        val rounds = FixtureGenerator.groupsThenKnockout(worldCup())
        assertEquals(3, rounds.size, "quattro squadre per girone sono tre giornate")
        rounds.forEach {
            assertEquals(8, it.pairings.size, "quattro gironi da due partite ciascuno")
        }
    }

    @Test
    fun `una competizione con gironi disuguali viene rifiutata`() {
        val errore = runCatching {
            Competition(
                id = CompetitionId(4), name = "Rotta",
                type = CompetitionType.GIRONI_PIU_ELIMINAZIONE,
                participants = clubs(15), groupCount = 4,
            )
        }.exceptionOrNull()
        assertTrue(errore is IllegalArgumentException)
    }
}

class CalendarSolverTest {

    private val start = LocalDate.of(2026, 9, 1)

    private fun configWith(
        clubsCount: Int = 20,
        days: Int = 14,
        matchesPerDay: Int = 2,
        slots: List<LocalTime> = listOf(LocalTime.of(18, 30), LocalTime.of(21, 0)),
        restWeekdays: Set<DayOfWeek> = emptySet(),
    ) = ConfigPresets.sprint(clubsCount, clubsCount / 2, start).copy(
        calendar = CalendarConfig(
            startDate = start,
            endDate = start.plusDays(days.toLong()),
            matchesPerDayPerClub = matchesPerDay,
            kickoffSlots = slots,
            restWeekdays = restWeekdays,
        ),
    )

    private fun league(n: Int) = Competition(
        id = CompetitionId(1), name = "Campionato",
        type = CompetitionType.GIRONE, participants = clubs(n),
    )

    @Test
    fun `un campionato da venti squadre entra in due settimane a due partite al giorno`() {
        val config = configWith()
        val rounds = FixtureGenerator.roundRobin(league(20))
        val schedule = CalendarSolver.schedule(rounds, config)

        assertTrue(schedule.isComplete, schedule.warnings.joinToString())
        assertEquals(19 * 10, schedule.fixtures.size)
    }

    /**
     * L'invariante piu' importante del calendario: con la stamina in gioco, una terza
     * partita in un giorno distruggerebbe una rosa.
     */
    @Test
    fun `nessun club supera mai il limite giornaliero`() {
        val config = configWith()
        val schedule = CalendarSolver.schedule(FixtureGenerator.roundRobin(league(20)), config)
        assertEquals(emptyList(), CalendarSolver.violations(schedule, config.calendar))
    }

    @Test
    fun `ogni partita riceve data e ora`() {
        val config = configWith()
        val schedule = CalendarSolver.schedule(FixtureGenerator.roundRobin(league(16)), config)
        assertTrue(schedule.fixtures.all { it.isScheduled })
    }

    @Test
    fun `le giornate avanzano insieme al tempo reale`() {
        val config = configWith()
        val schedule = CalendarSolver.schedule(FixtureGenerator.roundRobin(league(16)), config)

        val ordinate = schedule.fixtures.sortedBy { it.kickoff }
        ordinate.zipWithNext().forEach { (a, b) ->
            assertTrue(
                b.matchDay >= a.matchDay,
                "le giornate non seguono l'ordine cronologico",
            )
        }
    }

    @Test
    fun `i giorni di riposo restano vuoti`() {
        val config = configWith(
            days = 30,
            restWeekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        )
        val schedule = CalendarSolver.schedule(FixtureGenerator.roundRobin(league(16)), config)

        schedule.fixtures.forEach { fixture ->
            val giorno = fixture.kickoff!!.dayOfWeek
            assertTrue(
                giorno != DayOfWeek.SATURDAY && giorno != DayOfWeek.SUNDAY,
                "partita programmata di $giorno",
            )
        }
    }

    @Test
    fun `le partite usano solo le fasce orarie configurate`() {
        val slots = listOf(LocalTime.of(19, 0), LocalTime.of(21, 30))
        val config = configWith(slots = slots)
        val schedule = CalendarSolver.schedule(FixtureGenerator.roundRobin(league(16)), config)

        schedule.fixtures.forEach {
            assertTrue(it.kickoff!!.toLocalTime() in slots, "orario inatteso: ${it.kickoff}")
        }
    }

    /**
     * Il caso che rende il calendario un risolutore e non una lista: campionato e coppa
     * insieme, senza che nessuno finisca con tre partite in un giorno.
     */
    @Test
    fun `campionato e coppa convivono senza sovrapporsi`() {
        val config = configWith(days = 20)
        val campionato = FixtureGenerator.roundRobin(league(16))
        val coppa = FixtureGenerator.knockout(
            Competition(
                id = CompetitionId(2), name = "Coppa",
                type = CompetitionType.ELIMINAZIONE_DIRETTA, participants = clubs(16),
            ),
        )

        val schedule = CalendarSolver.schedule(campionato + coppa, config)

        assertEquals(emptyList(), CalendarSolver.violations(schedule, config.calendar))
        assertTrue(schedule.fixtures.any { it.competitionId == CompetitionId(1) })
        assertTrue(schedule.fixtures.any { it.competitionId == CompetitionId(2) })
    }

    @Test
    fun `i turni di una competizione restano in ordine`() {
        val config = configWith(days = 20)
        val schedule = CalendarSolver.schedule(FixtureGenerator.roundRobin(league(16)), config)

        val perTurno = schedule.fixtures.groupBy { it.round }
            .mapValues { it.value.first().kickoff!! }
            .toSortedMap()

        perTurno.values.zipWithNext().forEach { (prima, dopo) ->
            assertTrue(!dopo.isBefore(prima), "i turni non sono in ordine cronologico")
        }
    }

    @Test
    fun `un calendario troppo corto lo dice invece di produrre spazzatura`() {
        val config = configWith(days = 2)
        val schedule = CalendarSolver.schedule(FixtureGenerator.roundRobin(league(20)), config)

        assertTrue(!schedule.isComplete)
        assertTrue(schedule.unscheduled.isNotEmpty())
        assertTrue(schedule.warnings.isNotEmpty())
        // Quello che ha programmato deve comunque essere valido.
        assertEquals(emptyList(), CalendarSolver.violations(schedule, config.calendar))
    }

    @Test
    fun `senza fasce orarie non si programma niente`() {
        val config = configWith(slots = emptyList())
        val schedule = CalendarSolver.schedule(FixtureGenerator.roundRobin(league(8)), config)
        assertTrue(schedule.fixtures.isEmpty())
        assertTrue(schedule.warnings.isNotEmpty())
    }

    @Test
    fun `con una sola partita al giorno si usa una sola fascia`() {
        val config = configWith(days = 30, matchesPerDay = 1)
        val schedule = CalendarSolver.schedule(FixtureGenerator.roundRobin(league(16)), config)

        val orari = schedule.fixtures.mapNotNull { it.kickoff?.toLocalTime() }.toSet()
        assertEquals(1, orari.size, "con una partita al giorno non serve una seconda fascia")
    }

    @Test
    fun `fixturesFor restituisce il calendario di un club in ordine`() {
        val config = configWith()
        val schedule = CalendarSolver.schedule(FixtureGenerator.roundRobin(league(16)), config)
        val calendario = schedule.fixturesFor(ClubId(3))

        assertEquals(15, calendario.size, "in un girone da 16 si giocano 15 partite")
        calendario.zipWithNext().forEach { (a, b) ->
            assertTrue(b.matchDay >= a.matchDay)
        }
    }

    @Test
    fun `le fasce disponibili partono dalla giornata indicata`() {
        val config = configWith()
        val slots = CalendarSolver.buildSlots(config.calendar, MatchDay(10))
        assertEquals(MatchDay(10), slots.first().matchDay)
        assertEquals(MatchDay(11), slots[1].matchDay)
    }
}

class StandingsTest {

    private val competition = Competition(
        id = CompetitionId(1), name = "Campionato",
        type = CompetitionType.GIRONE, participants = clubs(4),
    )

    private fun result(home: Int, away: Int, hg: Int, ag: Int) = FixtureResult(
        fixtureId = 0, competitionId = CompetitionId(1),
        home = ClubId(home.toLong()), away = ClubId(away.toLong()),
        homeGoals = hg, awayGoals = ag,
    )

    @Test
    fun `i punti si assegnano secondo la configurazione`() {
        val table = Standings.compute(competition, listOf(result(1, 2, 2, 0)))
        assertEquals(3, table.first { it.club == ClubId(1) }.points)
        assertEquals(0, table.first { it.club == ClubId(2) }.points)
    }

    @Test
    fun `il pareggio da un punto a testa`() {
        val table = Standings.compute(competition, listOf(result(1, 2, 1, 1)))
        assertEquals(1, table.first { it.club == ClubId(1) }.points)
        assertEquals(1, table.first { it.club == ClubId(2) }.points)
    }

    @Test
    fun `gol fatti e subiti si accumulano da entrambe le parti`() {
        val table = Standings.compute(
            competition,
            listOf(result(1, 2, 3, 1), result(2, 1, 2, 2)),
        )
        val uno = table.first { it.club == ClubId(1) }
        assertEquals(2, uno.played)
        assertEquals(5, uno.goalsFor)
        assertEquals(3, uno.goalsAgainst)
        assertEquals(2, uno.goalDifference)
    }

    @Test
    fun `a pari punti decide la differenza reti`() {
        val table = Standings.compute(
            competition,
            listOf(result(1, 3, 1, 0), result(2, 4, 5, 0)),
        )
        assertEquals(ClubId(2), table.first().club, "la differenza reti non e' stata applicata")
    }

    @Test
    fun `l'ordinamento e stabile fra due calcoli`() {
        val results = listOf(result(1, 2, 1, 1), result(3, 4, 1, 1))
        assertEquals(
            Standings.compute(competition, results).map { it.club },
            Standings.compute(competition, results).map { it.club },
        )
    }

    @Test
    fun `una competizione senza risultati ha tutti a zero`() {
        val table = Standings.compute(competition, emptyList())
        assertEquals(4, table.size)
        assertTrue(table.all { it.points == 0 && it.played == 0 })
    }

    @Test
    fun `i risultati di altre competizioni vengono ignorati`() {
        val altrove = result(1, 2, 5, 0).copy(competitionId = CompetitionId(99))
        val table = Standings.compute(competition, listOf(altrove))
        assertTrue(table.all { it.played == 0 })
    }

    // --------------------------------------------------------------- eliminazione

    @Test
    fun `in gara secca passa chi vince`() {
        assertEquals(ClubId(1), Standings.tieWinner(listOf(result(1, 2, 2, 1))))
    }

    @Test
    fun `in gara secca il pareggio favorisce chi gioca in casa`() {
        assertEquals(ClubId(1), Standings.tieWinner(listOf(result(1, 2, 1, 1))))
    }

    @Test
    fun `nell'andata e ritorno conta il totale`() {
        val andata = result(1, 2, 1, 0)
        val ritorno = result(2, 1, 3, 0)
        assertEquals(ClubId(2), Standings.tieWinner(listOf(andata, ritorno)))
    }

    @Test
    fun `a totale pari passa chi gioca in casa il ritorno`() {
        val andata = result(1, 2, 1, 0)
        val ritorno = result(2, 1, 1, 0)
        assertEquals(ClubId(2), Standings.tieWinner(listOf(andata, ritorno)))
    }

    @Test
    fun `le qualificate si prendono in ordine di classifica`() {
        val gironi = listOf(listOf(ClubId(1), ClubId(2)), listOf(ClubId(3), ClubId(4)))
        val results = listOf(result(1, 2, 3, 0), result(3, 4, 0, 2))

        val qualificate = Standings.qualifiers(
            competition.copy(qualifiersPerGroup = 1), gironi, results,
        )
        assertEquals(listOf(ClubId(1), ClubId(4)), qualificate)
    }
}
