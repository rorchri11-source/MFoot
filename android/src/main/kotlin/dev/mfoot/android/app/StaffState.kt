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
    /**
     * Di chi e' ciascun membro, per identificativo della **prima squadra**.
     *
     * Lettura separata di proposito. `owner_club_id` e' arrivata il 2026-08-30, e chiederla
     * dentro la lettura principale vorrebbe dire che una lega col database indietro non
     * apre piu' la schermata dello staff — non perde le celle, sparisce: PostgREST per una
     * colonna che non esiste rifiuta l'intera query. E' la trappola gia' pagata tre volte,
     * l'ultima il 2026-08-29 con `match_results.home_formation`.
     *
     * Vuota significa «database indietro»: si torna al comportamento di prima, dove
     * possedere e schierare erano la stessa cosa.
     */
    val proprieta: Map<Long, Long> = emptyMap(),
    val busy: String? = null,
    val avviso: String? = null,
    val errore: String? = null,
) {
    /** Chi lavora in questa squadra, cioe' chi occupa una cella. */
    fun di(clubId: Long?): List<StaffMember> =
        if (clubId == null) emptyList() else tutti.filter { it.clubId == clubId }

    /** Se la proprieta' e' leggibile. */
    val celleAttive: Boolean get() = true

    /**
     * Tutti quelli che possiedi, schierati o in panchina.
     *
     * Il proprietario e' sempre la prima squadra: si possiede come societa', si schiera
     * come squadra.
     */
    fun posseduti(primaSquadra: Long?): List<StaffMember> =
        if (primaSquadra == null) emptyList()
        else tutti.filter {
            it.ownerClubId == primaSquadra || proprieta[it.id] == primaSquadra || it.clubId == primaSquadra
        }

    /** I tuoi di questo ruolo che non occupano nessuna cella. */
    fun inPanchina(primaSquadra: Long?, role: String): List<StaffMember> =
        posseduti(primaSquadra).filter { it.role == role && it.clubId == null }

    /** Quanti ne possiedi di questo ruolo: e' il numero che il tetto confronta. */
    fun quanti(primaSquadra: Long?, role: String): Int =
        posseduti(primaSquadra).count { it.role == role }

    /**
     * Chi c'e' nel negozio: chi non e' di nessuno e non lavora per nessuno.
     */
    val liberi: List<StaffMember>
        get() = tutti.filter {
            (it.ownerClubId == null || it.ownerClubId == 0L) &&
                proprieta[it.id] == null &&
                (it.clubId == null || it.clubId == 0L)
        }

    /** Il prezzo di listino, se qualcuno lo ha messo in vendita. */
    fun prezzoDi(staffId: Long): Int? = inVendita[staffId]

    fun osservatoriDi(clubId: Long?): List<StaffMember> =
        di(clubId).filter { it.role == "OSSERVATORE" }

    /** La missione in corso o da valutare di questo osservatore. */
    fun missioneDi(staffId: Long): ScoutingMission? =
        missioni.firstOrNull { it.staffId == staffId && (it.inCorso || it.daValutare) }
}

/** Il modulo della missione che si sta preparando. */
data class MissioneDraft(
    val staffId: Long,
    val paese: String? = null,
    val ruolo: String? = null,
) {
    val pronta: Boolean get() = paese != null && ruolo != null
}
