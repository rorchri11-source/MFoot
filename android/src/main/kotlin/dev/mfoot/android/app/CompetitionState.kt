package dev.mfoot.android.app

import dev.mfoot.android.data.ClubInfo
import dev.mfoot.android.data.CompetitionInfo
import dev.mfoot.android.data.TableView
import dev.mfoot.core.calendar.CompetitionType
import dev.mfoot.core.calendar.Schedule
import dev.mfoot.core.config.CalendarConfig
import dev.mfoot.core.config.ConfigValidator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * La competizione che l'admin sta componendo.
 *
 * Ogni modifica ricalcola il calendario in locale con `core`: e' quello che permette di
 * mostrare *prima* quante partite escono, in che giorni e con quali avvisi. Un pulsante
 * che genera al buio e si scopre dopo non e' una decisione, e' una scommessa.
 */
data class CompetitionDraft(
    val name: String = "",
    val type: CompetitionType = CompetitionType.GIRONE,
    val doubleRound: Boolean = false,
    /** Id dei club iscritti. Di partenza ci sono tutti. */
    val participants: Set<Long> = emptySet(),
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate = LocalDate.now().plusDays(14),
    /** I giorni della settimana in cui non si gioca. */
    val restWeekdays: Set<DayOfWeek> = emptySet(),
    val kickoffSlots: List<LocalTime> = listOf(LocalTime.of(18, 30), LocalTime.of(21, 0)),
    /** L'anteprima, ricalcolata a ogni modifica. */
    val schedule: Schedule? = null,
    val busy: String? = null,
    val errore: String? = null,
) {

    /** Quante giornate servono, dato il numero di squadre e se c'e' il ritorno. */
    val matchDaysNeeded: Int
        get() {
            val squadre = participants.size
            if (squadre < 2) return 0
            val andata = when (type) {
                CompetitionType.GIRONE -> ConfigValidator.roundRobinMatchDays(squadre)
                // In un tabellone ogni turno dimezza: log2 arrotondato per eccesso.
                CompetitionType.ELIMINAZIONE_DIRETTA -> {
                    var turni = 0
                    var rimasti = squadre
                    while (rimasti > 1) { rimasti = (rimasti + 1) / 2; turni++ }
                    turni
                }
                CompetitionType.GIRONI_PIU_ELIMINAZIONE -> ConfigValidator.roundRobinMatchDays(squadre)
            }
            return if (doubleRound && supportsDoubleRound) andata * 2 else andata
        }

    /** I giorni del periodo in cui si puo' davvero giocare, tolti quelli buca. */
    val playableDays: Int
        get() {
            if (endDate.isBefore(startDate)) return 0
            var day = startDate
            var count = 0
            while (!day.isAfter(endDate)) {
                if (day.dayOfWeek !in restWeekdays) count++
                day = day.plusDays(1)
            }
            return count
        }

    /**
     * Le partite al giorno **non si impostano: si calcolano**.
     *
     * E' il numero di giornate da giocare diviso i giorni disponibili. Chiederlo
     * all'admin significherebbe fargli fare a mano una divisione che il programma sa
     * fare, e sbagliarla vuol dire o una stagione che non ci sta nel periodo o giornate
     * ammassate senza motivo.
     *
     * Con venti squadre e diciannove giornate in dieci giorni vengono due partite al
     * giorno; nello stesso periodo con dodici squadre ne basta una.
     */
    val matchesPerDayPerClub: Int
        get() {
            val giorni = playableDays
            if (giorni <= 0 || matchDaysNeeded <= 0) return 1
            return ((matchDaysNeeded + giorni - 1) / giorni).coerceIn(1, 4)
        }

    /** Il periodo basta per le partite richieste? */
    val fits: Boolean get() = matchDaysNeeded <= playableDays * matchesPerDayPerClub

    val calendar: CalendarConfig
        get() = CalendarConfig(
            startDate = startDate,
            endDate = endDate,
            matchesPerDayPerClub = matchesPerDayPerClub,
            restWeekdays = restWeekdays,
            kickoffSlots = kickoffSlots,
        )

    val ready: Boolean
        get() = name.isNotBlank() && participants.size >= 2 &&
            schedule != null && schedule.fixtures.isNotEmpty()

    /**
     * Il ritorno ha senso solo dove esiste.
     *
     * In un campionato significa andata e ritorno; in un tabellone, doppia sfida.
     * Mostrarlo come opzione libera in ogni formato porterebbe a chiedersi cosa faccia.
     */
    val supportsDoubleRound: Boolean get() = type != CompetitionType.GIRONI_PIU_ELIMINAZIONE
}

/**
 * Classifica e calendario di una competizione.
 *
 * Aperta da chiunque, non solo dall'admin: e' la schermata che si guarda piu' spesso di
 * tutte, ed e' quella che rende una lega un campionato invece di una serie di partite.
 */
data class TableState(
    val competitions: List<CompetitionInfo>,
    val selectedId: Long?,
    val view: TableView? = null,
    val clubs: List<ClubInfo> = emptyList(),
    val myClubId: Long? = null,
    val errore: String? = null,
) {
    fun clubName(id: Long): String = clubs.firstOrNull { it.id == id }?.name ?: "Club #$id"
}

/** La schermata delle competizioni: quelle esistenti e quella in costruzione. */
data class CompetitionsState(
    val leagueId: Long,
    val clubs: List<ClubInfo>,
    val existing: List<CompetitionInfo> = emptyList(),
    /** Non null quando si sta creando: e' la differenza fra elenco e modulo. */
    val draft: CompetitionDraft? = null,
    val avviso: String? = null,
    val errore: String? = null,
) {
    fun clubName(id: Long): String = clubs.firstOrNull { it.id == id }?.name ?: "Club #$id"
}
