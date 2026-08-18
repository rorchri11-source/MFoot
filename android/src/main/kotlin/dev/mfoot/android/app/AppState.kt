package dev.mfoot.android.app

import dev.mfoot.android.data.AuctionView
import dev.mfoot.android.data.ClubInfo
import dev.mfoot.android.data.LeagueSnapshot
import dev.mfoot.android.ui.kit.Crest
import dev.mfoot.android.ui.kit.Kit
import dev.mfoot.core.config.CustomPlayerConfig
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Reparto
import dev.mfoot.core.world.CustomPlayerBuilder

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

/**
 * Un'asta pronta da mostrare: le regole del mercato e il giocatore, insieme.
 *
 * Tenere il giocatore accanto all'asta e' cio' che permette alla schermata di dire
 * "Cengiz Tekin, 24 anni, TRQ, 86" invece di "asta #17": un elenco di numeri non aiuta a
 * decidere se spendere sessanta crediti.
 */
data class AuctionRow(
    val auction: AuctionView,
    val player: PlayerRow?,
    val leaderName: String?,
) {
    val label: String get() = player?.player?.fullName ?: "Obiettivo #${auction.targetId}"
}

/** Cosa si sta guardando della lista: il mercato o una rosa. */
enum class ListScope(val label: String) {
    SVINCOLATI("Svincolati"),
    ASTE("Aste"),
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

/** I due passi della fondazione: prima la squadra, poi il giocatore che sei tu. */
enum class FoundingStep { CLUB, GIOCATORE }

/**
 * La fondazione del proprio club.
 *
 * Vive nel ViewModel e non nella schermata perche' e' un lavoro lungo — si sceglie un
 * nome, dei colori, un ruolo, poi si distribuiscono cento punti — e perdere tutto per una
 * rotazione dello schermo sarebbe insopportabile.
 */
data class FoundingState(
    val lega: LeagueSnapshot,
    val step: FoundingStep = FoundingStep.CLUB,
    val clubName: String = "",
    val clubShort: String = "",
    val kit: Kit = Kit.DEFAULT,
    val crest: Crest = Crest.DEFAULT,
    val draft: CustomPlayerBuilder.Draft = CustomPlayerBuilder.Draft(),
    val busy: String? = null,
    val errore: String? = null,
) {
    val config: CustomPlayerConfig get() = lega.league.config.custom

    val spent: Int get() = CustomPlayerBuilder.totalCost(draft, config)
    val remaining: Int get() = CustomPlayerBuilder.remaining(draft, config)
    val overall: Int get() = CustomPlayerBuilder.overallOf(draft, config)
    val attributes: Attributes get() = CustomPlayerBuilder.attributesOf(draft, config)
    val problems: List<String> get() = CustomPlayerBuilder.problems(draft, config)

    val clubReady: Boolean get() = clubName.isNotBlank()
}

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
        val auctions: List<AuctionRow> = emptyList(),
        /** L'asta aperta a schermo pieno per fare un'offerta. */
        val bidding: AuctionRow? = null,
        /**
         * La pila delle schermate visitate, l'ultima in cima.
         *
         * Una pila e non una sola rotta: il tasto indietro deve tornare da dove si e'
         * arrivati. Chi apre un giocatore dal Listone vuole tornare al Listone, non alla
         * Dashboard — e con una rotta sola l'unico ritorno possibile sarebbe una scelta
         * fissa, sbagliata meta' delle volte.
         */
        val stack: List<Route> = listOf(Route.Dashboard),
        val drawerOpen: Boolean = false,
        /** Un messaggio temporaneo in cima, tipo "lega creata". */
        val avviso: String? = null,
        val errore: String? = null,
    ) : AppState {

        val route: Route get() = stack.last()

        /** C'e' una schermata sotto a cui tornare, o il tasto indietro chiude l'app? */
        val canGoBack: Boolean get() = stack.size > 1 || drawerOpen || bidding != null

        /** Le aste su cui si e' impegnati: sono quelle che vanno tenute d'occhio. */
        val myAuctions: List<AuctionRow> get() = auctions.filter { it.auction.hasMyBid }

        val visible: List<PlayerRow>
            get() = rows
                .filter {
                    when (browse.scope) {
                        ListScope.SVINCOLATI -> it.isFreeAgent
                        ListScope.TUTTI -> true
                        ListScope.MIA_ROSA -> it.club != null && it.club.isMine
                        // La scheda aste ha una lista sua: qui non passa nessuno.
                        ListScope.ASTE -> false
                    }
                }
                .filter { browse.filter.matches(it.player) }
                .filter {
                    browse.query.isBlank() ||
                        it.player.fullName.contains(browse.query, ignoreCase = true) ||
                        it.player.primaryPosition.short.equals(browse.query, ignoreCase = true)
                }
    }

    /** Si sta fondando il proprio club. */
    data class Fondazione(val founding: FoundingState) : AppState

    /** L'admin gestisce le competizioni della lega. */
    data class Competizioni(val competitions: CompetitionsState) : AppState

    /** Classifica e calendario: la schermata che si guarda piu' spesso di tutte. */
    data class Classifica(val table: TableState) : AppState

    /** Qualcosa e' andato storto in modo da cui non si esce da soli. */
    data class Guasto(val motivo: String) : AppState
}
