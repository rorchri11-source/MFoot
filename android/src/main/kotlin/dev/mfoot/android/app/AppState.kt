package dev.mfoot.android.app

import dev.mfoot.android.data.ClubInfo
import dev.mfoot.android.data.LeagueSnapshot
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Reparto

/**
 * Un giocatore pronto da mostrare.
 *
 * La [estimate] e' la forbice **pubblica**: quella che chiunque puo' dedurre da eta' e
 * overall. I potenziali veri non arrivano mai sul telefono.
 */
data class PlayerRow(
    val player: Player,
    val estimate: IntRange,
    val hasUpside: Boolean,
    val value: Int,
    /** Il club che lo possiede, se qualcuno lo possiede. */
    val club: ClubInfo?,
) {
    val isFreeAgent: Boolean get() = club == null

    /** L'etichetta compatta della lista: `+24`, `al max`, `in calo`. */
    val growthLabel: String
        get() = when {
            !hasUpside && player.age >= 30 -> "in calo"
            !hasUpside -> "al max"
            else -> "+${(estimate.last - player.overall).coerceAtLeast(0)}"
        }
}

enum class RoleFilter(val label: String) {
    TUTTI("Tutti"),
    POR("POR"),
    DIF("DIF"),
    CEN("CEN"),
    ATT("ATT"),
    GIOVANI("Under 21");

    fun matches(player: Player): Boolean = when (this) {
        TUTTI -> true
        POR -> player.primaryPosition.isGoalkeeper
        DIF -> player.primaryPosition.reparto == Reparto.DIFESA
        CEN -> player.primaryPosition.reparto == Reparto.CENTROCAMPO
        ATT -> player.primaryPosition.reparto == Reparto.ATTACCO
        GIOVANI -> player.age <= 21
    }
}

/** Cosa si sta guardando della lista: il mercato o una rosa. */
enum class ListScope(val label: String) {
    SVINCOLATI("Svincolati"),
    TUTTI("Tutto il mondo"),
    MIA_ROSA("La mia rosa"),
}

/** Lo stato della schermata di lista, separato dai dati che mostra. */
data class BrowseState(
    val query: String = "",
    val filter: RoleFilter = RoleFilter.TUTTI,
    val scope: ListScope = ListScope.SVINCOLATI,
    val selected: PlayerRow? = null,
)

/** Quale porta si sta usando per entrare. */
enum class DoorMode { SCELTA, CREA, ENTRA }

/**
 * Lo stato dell'intera app.
 *
 * E' un tipo chiuso invece di una manciata di booleani perche' gli stati si escludono
 * davvero: non esiste "sta caricando **e** e' pronta". Con i booleani quella combinazione
 * sarebbe rappresentabile, e prima o poi qualcuno la produrrebbe.
 */
sealed interface AppState {

    /** Si sta recuperando l'identita' salvata. Dura una frazione di secondo. */
    data object Avvio : AppState

    /** Non si e' ancora in nessuna lega: si crea o si entra. */
    data class Porta(
        val mode: DoorMode = DoorMode.SCELTA,
        val busy: String? = null,
        val errore: String? = null,
    ) : AppState

    data class Caricamento(val fase: String) : AppState

    data class Dentro(
        val lega: LeagueSnapshot,
        val rows: List<PlayerRow>,
        val browse: BrowseState = BrowseState(),
        /** Un messaggio temporaneo in cima, tipo "lega creata". */
        val avviso: String? = null,
    ) : AppState {

        val visible: List<PlayerRow>
            get() = rows
                .filter {
                    when (browse.scope) {
                        ListScope.SVINCOLATI -> it.isFreeAgent
                        ListScope.TUTTI -> true
                        ListScope.MIA_ROSA -> it.club != null && it.club.isMine
                    }
                }
                .filter { browse.filter.matches(it.player) }
                .filter {
                    browse.query.isBlank() ||
                        it.player.fullName.contains(browse.query, ignoreCase = true) ||
                        it.player.primaryPosition.short.equals(browse.query, ignoreCase = true)
                }
    }

    /** Qualcosa e' andato storto in modo da cui non si esce da soli. */
    data class Guasto(val motivo: String) : AppState
}
