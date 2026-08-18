package dev.mfoot.android.app

import dev.mfoot.core.calendar.CalendarEvent
import dev.mfoot.core.calendar.CalendarEventKind
import dev.mfoot.core.calendar.LeagueCalendar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Il calendario del mese.
 *
 * ## Perche' gli eventi si tengono tutti, non solo quelli del mese aperto
 *
 * Perche' cambiare mese e' il gesto piu' frequente su questa schermata, e ricaricare a
 * ogni freccia significherebbe una richiesta e mezzo secondo di attesa per sfogliare
 * avanti e indietro. Un campionato intero sono qualche centinaio di eventi: stanno in
 * memoria senza che nessuno se ne accorga.
 */
data class CalendarState(
    val mese: YearMonth = YearMonth.now(),
    val eventi: Map<LocalDate, List<CalendarEvent>> = emptyMap(),
    /** Il giorno aperto sotto la griglia. Null: nessuno, e si mostra il prossimo impegno. */
    val selezionato: LocalDate? = null,
    val oggi: LocalDate = LocalDate.now(),
    val riposi: Set<DayOfWeek> = emptySet(),
    val caricamento: Boolean = false,
    val errore: String? = null,
) {
    fun eventiDi(day: LocalDate): List<CalendarEvent> = eventi[day].orEmpty()

    fun pallini(day: LocalDate): List<CalendarEventKind> = LeagueCalendar.dots(eventiDi(day))

    /**
     * Le sei settimane da disegnare.
     *
     * Sempre sei, anche quando cinque basterebbero: una griglia che cambia altezza da un
     * mese all'altro fa saltare il contenuto sotto ogni volta che si preme una freccia.
     */
    val griglia: List<List<LocalDate>>
        get() {
            val primo = mese.atDay(1)
            // Lunedi' come primo giorno: e' la settimana con cui si ragiona qui.
            val inizio = primo.minusDays((primo.dayOfWeek.value - 1).toLong())
            return (0 until 6).map { settimana ->
                (0 until 7).map { giorno -> inizio.plusDays((settimana * 7 + giorno).toLong()) }
            }
        }

    /** Il prossimo giorno con qualcosa dentro, da oggi in avanti. */
    val prossimo: LocalDate?
        get() = eventi.keys.filter { !it.isBefore(oggi) }.minOrNull()

    val giornoMostrato: LocalDate? get() = selezionato ?: prossimo
}
