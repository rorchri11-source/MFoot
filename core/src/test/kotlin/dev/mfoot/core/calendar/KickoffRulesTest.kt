package dev.mfoot.core.calendar

import dev.mfoot.core.config.CalendarConfig
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KickoffRulesTest {

    private val adesso = LocalDateTime.of(2026, 8, 19, 13, 0)

    @Test
    fun `oggi alle dodici quando sono le tredici non si puo' fissare`() {
        val scelto = LocalDateTime.of(2026, 8, 19, 12, 0)
        assertFalse(KickoffRules.isPlayable(scelto, adesso))
        assertNotNull(KickoffRules.problema(scelto, adesso))
    }

    @Test
    fun `oggi alle sedici quando sono le tredici si puo' fissare`() {
        val scelto = LocalDateTime.of(2026, 8, 19, 16, 0)
        assertTrue(KickoffRules.isPlayable(scelto, adesso))
        assertNull(KickoffRules.problema(scelto, adesso))
    }

    /**
     * Il margine e' la parte che si dimentica: un orario nel futuro di trenta secondi passa
     * qualsiasi controllo `> now` ed e' inutilizzabile lo stesso.
     */
    @Test
    fun `fra cinque minuti e' nel futuro e non basta`() {
        val scelto = adesso.plusMinutes(5)
        assertTrue(scelto.isAfter(adesso))
        assertFalse(KickoffRules.isPlayable(scelto, adesso))
        assertTrue(KickoffRules.problema(scelto, adesso)!!.contains("meno di"))
    }

    @Test
    fun `esattamente al margine si accetta`() {
        assertTrue(KickoffRules.isPlayable(adesso.plusMinutes(KickoffRules.MARGINE_MINUTI), adesso))
    }

    @Test
    fun `degli orari di oggi restano solo quelli ancora davanti`() {
        val slots = listOf(LocalTime.of(12, 0), LocalTime.of(15, 0), LocalTime.of(21, 0))
        val usabili = KickoffRules.usableSlots(LocalDate.of(2026, 8, 19), slots, adesso)

        assertEquals(listOf(LocalTime.of(15, 0), LocalTime.of(21, 0)), usabili)
    }

    @Test
    fun `di domani sono buoni tutti, anche quelli piu' presti di adesso`() {
        val slots = listOf(LocalTime.of(9, 0), LocalTime.of(12, 0))
        val usabili = KickoffRules.usableSlots(LocalDate.of(2026, 8, 20), slots, adesso)

        assertEquals(slots, usabili)
    }

    // ------------------------------------------------------------------- il calendario

    @Test
    fun `la prima partita e' il primo giorno utile all'orario piu' presto`() {
        val calendario = CalendarConfig(
            startDate = LocalDate.of(2026, 8, 22),
            endDate = LocalDate.of(2026, 9, 30),
            kickoffSlots = listOf(LocalTime.of(21, 0), LocalTime.of(18, 30)),
        )

        assertEquals(
            LocalDateTime.of(2026, 8, 22, 18, 30),
            KickoffRules.firstKickoff(calendario),
        )
    }

    @Test
    fun `i giorni buca si saltano anche in partenza`() {
        // Il 22 agosto 2026 e' un sabato: se il sabato e' buca, si comincia domenica.
        val calendario = CalendarConfig(
            startDate = LocalDate.of(2026, 8, 22),
            endDate = LocalDate.of(2026, 9, 30),
            restWeekdays = setOf(DayOfWeek.SATURDAY),
            kickoffSlots = listOf(LocalTime.of(18, 30)),
        )

        assertEquals(
            LocalDateTime.of(2026, 8, 23, 18, 30),
            KickoffRules.firstKickoff(calendario),
        )
    }

    @Test
    fun `una competizione che comincia ieri viene segnalata`() {
        val calendario = CalendarConfig(
            startDate = LocalDate.of(2026, 8, 18),
            endDate = LocalDate.of(2026, 9, 30),
            kickoffSlots = listOf(LocalTime.of(18, 30)),
        )

        val problemi = KickoffRules.problemiDiCalendario(calendario, adesso)
        assertEquals(1, problemi.size)
        assertTrue(problemi.first().contains("già passato"))
    }

    @Test
    fun `una competizione che comincia domani non ha problemi`() {
        val calendario = CalendarConfig(
            startDate = LocalDate.of(2026, 8, 20),
            endDate = LocalDate.of(2026, 9, 30),
            kickoffSlots = listOf(LocalTime.of(18, 30)),
        )

        assertTrue(KickoffRules.problemiDiCalendario(calendario, adesso).isEmpty())
    }

    /**
     * Oggi alle 21 va benissimo anche se il periodo comincia oggi: il difetto non e' «la
     * data e' oggi», e' «l'ora e' passata». Confonderli vorrebbe dire impedire di creare
     * un campionato che comincia stasera, che e' esattamente quello che si vuole fare.
     */
    @Test
    fun `si puo' cominciare oggi, purche' a un'ora che deve ancora arrivare`() {
        val calendario = CalendarConfig(
            startDate = LocalDate.of(2026, 8, 19),
            endDate = LocalDate.of(2026, 9, 30),
            kickoffSlots = listOf(LocalTime.of(21, 0)),
        )

        assertTrue(KickoffRules.problemiDiCalendario(calendario, adesso).isEmpty())
    }

    @Test
    fun `un periodo al contrario e senza orari da' due problemi distinti`() {
        val calendario = CalendarConfig(
            startDate = LocalDate.of(2026, 9, 30),
            endDate = LocalDate.of(2026, 8, 19),
            kickoffSlots = emptyList(),
        )

        assertEquals(2, KickoffRules.problemiDiCalendario(calendario, adesso).size)
    }
// ------------------------------------------------- la distanza fra due partite

    /**
     * La regola nuova del 2026-08-29: fra due partite dello stesso club passano due ore.
     *
     * Prima bastava il tetto giornaliero, che non sa che ore sono: accettava due partite
     * alle 20:30 e alle 21:00 — sono due, nella stessa giornata — e con la partita che dura
     * 110 minuti veri quelle due si sovrappongono per un ora e venti.
     */
    @Test
    fun `due partite a mezz ora di distanza sono troppo vicine`() {
        val prima = LocalDateTime.of(2026, 8, 19, 20, 30)
        val poi = LocalDateTime.of(2026, 8, 19, 21, 0)

        assertTrue(KickoffRules.troppoVicine(prima, poi, oreMinime = 2))
        assertTrue(KickoffRules.troppoVicine(poi, prima, oreMinime = 2), "vale nei due sensi")
    }

    @Test
    fun `alle dieci e a mezzogiorno si puo`() {
        val prima = LocalDateTime.of(2026, 8, 19, 10, 0)
        val poi = LocalDateTime.of(2026, 8, 19, 12, 0)

        assertFalse(KickoffRules.troppoVicine(prima, poi, oreMinime = 2))
        assertTrue(
            KickoffRules.troppoVicine(prima, LocalDateTime.of(2026, 8, 19, 11, 0), oreMinime = 2),
            "alle undici no: e esattamente l esempio del proprietario",
        )
    }

    @Test
    fun `il problema dice da che ora si potrebbe`() {
        val impegni = listOf(LocalDateTime.of(2026, 8, 19, 20, 30))
        val messaggio = KickoffRules.problemaDiDistanza(
            LocalDateTime.of(2026, 8, 19, 21, 0), impegni, oreMinime = 2,
        )

        assertNotNull(messaggio)
        assertTrue(messaggio.contains("22:30"), "deve dire quando si libera, non solo che non si puo: $messaggio")
    }

    @Test
    fun `a zero ore la regola e spenta`() {
        val prima = LocalDateTime.of(2026, 8, 19, 20, 30)
        assertFalse(KickoffRules.troppoVicine(prima, prima.plusMinutes(1), oreMinime = 0))
    }
}
