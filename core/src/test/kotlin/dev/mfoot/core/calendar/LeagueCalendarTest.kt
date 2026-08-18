package dev.mfoot.core.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeagueCalendarTest {

    private val settembre = LocalDate.of(2026, 9, 1)

    @Test
    fun `una giornata cade nel giorno della sua prima partita`() {
        val partite = listOf(
            partita(giornata = 3, ora = LocalDateTime.of(2026, 9, 5, 21, 0)),
            partita(giornata = 3, ora = LocalDateTime.of(2026, 9, 5, 18, 30)),
            partita(giornata = 4, ora = LocalDateTime.of(2026, 9, 6, 18, 30)),
        )

        val quando = LeagueCalendar.dateOfMatchDay(partite)

        assertEquals(LocalDate.of(2026, 9, 5), quando[3])
        assertEquals(LocalDate.of(2026, 9, 6), quando[4])
    }

    @Test
    fun `una scadenza in una giornata non ancora programmata non compare`() {
        // Il calendario arriva alla terza; il contratto scade alla decima. Quella data non
        // esiste ancora: dipende da quando l'admin programmera' il resto. Inventarla
        // vorrebbe dire mettere un pallino rosso su un giorno a caso.
        val partite = listOf(partita(giornata = 3, ora = LocalDateTime.of(2026, 9, 5, 21, 0)))

        val giorni = LeagueCalendar.build(
            matches = partite,
            contracts = listOf(CalendarDeadline(matchDay = 10, what = "A. Rossi")),
        )

        assertTrue(
            giorni.values.flatten().none { it.kind == CalendarEventKind.SCADENZA_CONTRATTO },
            "una scadenza senza data e' comparsa lo stesso",
        )
    }

    @Test
    fun `una scadenza in una giornata programmata cade in quel giorno`() {
        val partite = listOf(partita(giornata = 7, ora = LocalDateTime.of(2026, 9, 9, 21, 0)))

        val giorni = LeagueCalendar.build(
            matches = partite,
            contracts = listOf(CalendarDeadline(matchDay = 7, what = "A. Rossi")),
        )

        val quelGiorno = giorni.getValue(LocalDate.of(2026, 9, 9))
        assertTrue(quelGiorno.any { it.kind == CalendarEventKind.SCADENZA_CONTRATTO })
    }

    @Test
    fun `dentro un giorno prima chi ha un'ora, poi chi vale tutto il giorno`() {
        val partite = listOf(
            partita(giornata = 5, ora = LocalDateTime.of(2026, 9, 7, 21, 0)),
            partita(giornata = 5, ora = LocalDateTime.of(2026, 9, 7, 18, 30), casa = "Terza"),
        )

        val giorni = LeagueCalendar.build(
            matches = partite,
            contracts = listOf(CalendarDeadline(matchDay = 5, what = "A. Rossi")),
        )

        val eventi = giorni.getValue(LocalDate.of(2026, 9, 7))
        assertEquals(18, eventi[0].at?.hour)
        assertEquals(21, eventi[1].at?.hour)
        assertEquals(null, eventi[2].at, "la scadenza deve stare in fondo, non a mezzanotte")
    }

    @Test
    fun `la tua partita viene prima delle altre nei pallini`() {
        val eventi = listOf(
            CalendarEvent(CalendarEventKind.PARTITA_ALTRUI, null, "altrui"),
            CalendarEvent(CalendarEventKind.ASTA, null, "asta"),
            CalendarEvent(CalendarEventKind.PARTITA_TUA, null, "tua"),
        )

        assertEquals(
            listOf(
                CalendarEventKind.PARTITA_TUA,
                CalendarEventKind.ASTA,
                CalendarEventKind.PARTITA_ALTRUI,
            ),
            LeagueCalendar.dots(eventi),
        )
    }

    @Test
    fun `i pallini non si ripetono e non superano tre`() {
        val eventi = List(8) { CalendarEvent(CalendarEventKind.PARTITA_ALTRUI, null, "x") } +
            CalendarEvent(CalendarEventKind.PARTITA_TUA, null, "tua") +
            CalendarEvent(CalendarEventKind.ASTA, null, "asta") +
            CalendarEvent(CalendarEventKind.AMICHEVOLE, null, "amichevole")

        val pallini = LeagueCalendar.dots(eventi)

        assertEquals(3, pallini.size)
        assertEquals(pallini.size, pallini.toSet().size)
    }

    @Test
    fun `una partita giocata porta il risultato al posto della giornata`() {
        val giorni = LeagueCalendar.build(
            listOf(
                partita(giornata = 2, ora = LocalDateTime.of(2026, 9, 4, 21, 0))
                    .copy(played = true, scoreline = "2 - 1"),
            ),
        )

        val evento = giorni.getValue(LocalDate.of(2026, 9, 4)).single()
        assertTrue(evento.done)
        assertEquals("2 - 1", evento.detail)
    }

    @Test
    fun `un'amichevole ha un colore suo, non quello di una partita qualsiasi`() {
        val giorni = LeagueCalendar.build(
            listOf(
                partita(giornata = 0, ora = settembre.atTime(17, 0))
                    .copy(friendly = true, mine = true),
            ),
        )

        assertEquals(
            CalendarEventKind.AMICHEVOLE,
            giorni.getValue(settembre).single().kind,
        )
    }

    private fun partita(
        giornata: Int,
        ora: LocalDateTime?,
        casa: String = "Prima",
        mine: Boolean = false,
    ) = CalendarMatch(
        matchDay = giornata,
        kickoff = ora,
        homeName = casa,
        awayName = "Seconda",
        mine = mine,
    )
}
