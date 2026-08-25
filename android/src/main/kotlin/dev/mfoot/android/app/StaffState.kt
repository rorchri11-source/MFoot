package dev.mfoot.android.app

import dev.mfoot.android.data.ScoutingMission
import dev.mfoot.android.data.StaffMember

/**
 * Lo staff delle due squadre, e le missioni in corso.
 *
 * Un elenco solo per tutte e due: chi lavora dove lo dice `clubId`, e tenerne due copie
 * vorrebbe dire ricaricare a ogni tocco dell'interruttore per un dato che cambia due volte
 * a stagione.
 */
data class StaffState(
    val tutti: List<StaffMember> = emptyList(),
    val missioni: List<ScoutingMission> = emptyList(),
    val letto: Boolean = false,
    /**
     * Chi e' sul listino, e a quanto.
     *
     * Vuota quando la migrazione `0030` non c'e' ancora: allora lo staff torna a
     * prendersi solo all'asta, che e' come ha sempre funzionato.
     */
    val inVendita: Map<Long, Int> = emptyMap(),
    val busy: String? = null,
    val avviso: String? = null,
    val errore: String? = null,
) {
    fun di(clubId: Long?): List<StaffMember> =
        if (clubId == null) emptyList() else tutti.filter { it.clubId == clubId }

    /** Chi non lavora per nessuno: sono quelli che si possono battere all'asta. */
    val liberi: List<StaffMember> get() = tutti.filter { it.clubId == null }

    /** Il prezzo di listino, se qualcuno lo ha messo in vendita. */
    fun prezzoDi(staffId: Long): Int? = inVendita[staffId]

    fun osservatoriDi(clubId: Long?): List<StaffMember> =
        di(clubId).filter { it.role == "OSSERVATORE" }

    /** La missione in corso di questo osservatore, se e' via. */
    fun missioneDi(staffId: Long): ScoutingMission? =
        missioni.firstOrNull { it.staffId == staffId && it.inCorso }
}

/** Il modulo della missione che si sta preparando. */
data class MissioneDraft(
    val staffId: Long,
    val paese: String? = null,
    val ruolo: String? = null,
) {
    val pronta: Boolean get() = paese != null && ruolo != null
}
