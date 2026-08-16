package dev.mfoot.android.app

import dev.mfoot.android.data.ClubInfo
import dev.mfoot.android.data.CompetitionInfo
import dev.mfoot.android.data.TableView
import dev.mfoot.core.calendar.CompetitionType
import dev.mfoot.core.calendar.Schedule
import dev.mfoot.core.config.CalendarConfig
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
    val days: Int = 14,
    val matchesPerDayPerClub: Int = 2,
    val kickoffSlots: List<LocalTime> = listOf(LocalTime.of(18, 30), LocalTime.of(21, 0)),
    /** L'anteprima, ricalcolata a ogni modifica. */
    val schedule: Schedule? = null,
    val busy: String? = null,
    val errore: String? = null,
) {
    val endDate: LocalDate get() = startDate.plusDays(days.toLong())

    val calendar: CalendarConfig
        get() = CalendarConfig(
            startDate = startDate,
            endDate = endDate,
            matchesPerDayPerClub = matchesPerDayPerClub,
            kickoffSlots = kickoffSlots,
        )

    val ready: Boolean
        get() = name.isNotBlank() && participants.size >= 2 &&
            schedule != null && schedule.fixtures.isNotEmpty()

    /**
     * Il raddoppio ha senso solo dove esiste un ritorno.
     *
     * In un girone significa andata e ritorno; in un tabellone, doppia sfida. Mostrarlo
     * come opzione libera in ogni formato porterebbe a chiedersi cosa faccia.
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
