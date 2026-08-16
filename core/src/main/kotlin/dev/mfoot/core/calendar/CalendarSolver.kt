package dev.mfoot.core.calendar

import dev.mfoot.core.config.CalendarConfig
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import java.time.LocalDate
import java.time.LocalDateTime

/** Una finestra reale in cui si puo' giocare: data piu' fascia oraria. */
data class TimeSlot(
    val date: LocalDate,
    val kickoff: LocalDateTime,
    /** Numero progressivo globale: e' la giornata di gioco. */
    val matchDay: MatchDay,
)

data class Schedule(
    val fixtures: List<Fixture>,
    /** Turni che non e' stato possibile collocare nella finestra disponibile. */
    val unscheduled: List<Round>,
    val warnings: List<String>,
    val slots: List<TimeSlot>,
) {
    val isComplete: Boolean get() = unscheduled.isEmpty()

    val lastMatchDay: MatchDay
        get() = fixtures.maxOfOrNull { it.matchDay } ?: MatchDay(0)

    fun fixturesOn(matchDay: MatchDay): List<Fixture> =
        fixtures.filter { it.matchDay == matchDay }

    fun fixturesFor(club: ClubId): List<Fixture> =
        fixtures.filter { it.involves(club) }.sortedBy { it.matchDay.value }

    fun fixturesOnDate(date: LocalDate): List<Fixture> =
        fixtures.filter { it.kickoff?.toLocalDate() == date }
}

/**
 * Colloca i turni delle competizioni nel tempo reale, rispettando i vincoli dell'admin.
 *
 * ## Perche' e' un risolutore di vincoli e non una lista
 *
 * L'admin non dice "gioca il 3 settembre alle 21". Dice: comincia il 1, finisci il 20,
 * massimo due partite al giorno per club, niente partite di domenica, si gioca alle 18:30
 * e alle 21. Da questi vincoli va **derivato** il calendario, coordinando anche piu'
 * competizioni in parallelo cosi' che un club impegnato in campionato e coppa non si
 * ritrovi tre partite lo stesso giorno.
 *
 * ## La giornata e' l'orologio del gioco
 *
 * Ogni fascia utilizzata avanza il contatore delle **giornate**, che e' l'unita' con cui
 * si misurano contratti, stipendi, crescita e scadenze delle aste. Cambiare il ritmo nel
 * mondo reale non rompe nessuno di quei sistemi.
 */
object CalendarSolver {

    fun schedule(
        rounds: List<Round>,
        config: LeagueConfig,
        firstMatchDay: MatchDay = MatchDay(1),
    ): Schedule {
        val slots = buildSlots(config.calendar, firstMatchDay)
        val warnings = mutableListOf<String>()

        if (slots.isEmpty()) {
            return Schedule(emptyList(), rounds, listOf("Nessuna data disponibile nel periodo indicato."), emptyList())
        }

        // I turni di una stessa competizione vanno giocati in ordine; competizioni
        // diverse si possono intrecciare liberamente.
        val queues = rounds.groupBy { it.competitionId }
            .mapValues { (_, list) -> ArrayDeque(list.sortedBy { it.number }) }
            .toMutableMap()

        val fixtures = mutableListOf<Fixture>()
        val playedPerDate = mutableMapOf<Pair<LocalDate, ClubId>, Int>()
        val playedPerSlot = mutableSetOf<Pair<LocalDateTime, ClubId>>()
        var fixtureId = 1L
        val usedSlots = mutableListOf<TimeSlot>()

        for (slot in slots) {
            if (queues.values.all { it.isEmpty() }) break
            var slotUsed = false

            // In una stessa fascia possono convivere turni di competizioni diverse,
            // purche' nessun club sia impegnato due volte.
            for (competitionId in queues.keys.toList()) {
                val queue = queues[competitionId] ?: continue
                val round = queue.firstOrNull() ?: continue

                if (!fits(round, slot, config.calendar, playedPerDate, playedPerSlot)) continue

                queue.removeFirst()
                slotUsed = true

                for (pairing in round.pairings) {
                    fixtures += Fixture(
                        id = fixtureId++,
                        competitionId = competitionId,
                        round = round.number,
                        roundLabel = round.label,
                        home = pairing.home,
                        away = pairing.away,
                        matchDay = slot.matchDay,
                        kickoff = slot.kickoff,
                        tieId = pairing.tieId,
                        isSecondLeg = pairing.isSecondLeg,
                    )
                    listOf(pairing.home, pairing.away).forEach { club ->
                        val key = slot.date to club
                        playedPerDate[key] = (playedPerDate[key] ?: 0) + 1
                        playedPerSlot += slot.kickoff to club
                    }
                }
            }

            if (slotUsed) usedSlots += slot
        }

        val leftovers = queues.values.flatten()
        if (leftovers.isNotEmpty()) {
            warnings += "Non c'e' stato spazio per ${leftovers.size} turni: " +
                "servono piu' giorni, piu' fasce orarie o piu' partite al giorno."
        }

        return Schedule(fixtures, leftovers, warnings, usedSlots)
    }

    /**
     * Un turno entra in questa fascia se **nessuno** dei club coinvolti ha gia' esaurito
     * il proprio limite giornaliero o e' gia' impegnato in quella stessa ora.
     */
    private fun fits(
        round: Round,
        slot: TimeSlot,
        calendar: CalendarConfig,
        playedPerDate: Map<Pair<LocalDate, ClubId>, Int>,
        playedPerSlot: Set<Pair<LocalDateTime, ClubId>>,
    ): Boolean = round.clubs.all { club ->
        val onDate = playedPerDate[slot.date to club] ?: 0
        onDate < calendar.matchesPerDayPerClub && (slot.kickoff to club) !in playedPerSlot
    }

    /**
     * Tutte le finestre giocabili nel periodo, in ordine cronologico.
     *
     * Il numero di fasce usate per giorno e' il minimo fra quelle configurate e il
     * limite di partite giornaliere: avere tre orari con un limite di due partite non
     * serve a niente.
     */
    fun buildSlots(calendar: CalendarConfig, firstMatchDay: MatchDay): List<TimeSlot> {
        if (calendar.kickoffSlots.isEmpty()) return emptyList()
        if (calendar.endDate.isBefore(calendar.startDate)) return emptyList()

        val slotsPerDay = minOf(calendar.kickoffSlots.size, calendar.matchesPerDayPerClub)
            .coerceAtLeast(1)
        val times = calendar.kickoffSlots.sorted().take(slotsPerDay)

        val result = mutableListOf<TimeSlot>()
        var date = calendar.startDate
        var matchDay = firstMatchDay.value

        while (!date.isAfter(calendar.endDate)) {
            if (isPlayable(date, calendar)) {
                for (time in times) {
                    result += TimeSlot(date, LocalDateTime.of(date, time), MatchDay(matchDay))
                    matchDay++
                }
            }
            date = date.plusDays(1)
        }
        return result
    }

    fun isPlayable(date: LocalDate, calendar: CalendarConfig): Boolean =
        date.dayOfWeek !in calendar.restWeekdays && date !in calendar.restDates

    /**
     * Verifica a posteriori che nessun club superi il limite giornaliero.
     *
     * Usata dai test e dal creatore di lega: e' l'invariante piu' importante del
     * calendario, perche' con la stamina in gioco una terza partita in un giorno
     * distruggerebbe una rosa.
     */
    fun violations(schedule: Schedule, calendar: CalendarConfig): List<String> {
        val perDate = mutableMapOf<Pair<LocalDate, ClubId>, Int>()
        schedule.fixtures.forEach { fixture ->
            val date = fixture.kickoff?.toLocalDate() ?: return@forEach
            listOf(fixture.home, fixture.away).forEach { club ->
                val key = date to club
                perDate[key] = (perDate[key] ?: 0) + 1
            }
        }
        return perDate.filter { it.value > calendar.matchesPerDayPerClub }
            .map { (key, count) ->
                "Il club ${key.second} gioca $count partite il ${key.first}, " +
                    "oltre il limite di ${calendar.matchesPerDayPerClub}"
            }
    }
}
