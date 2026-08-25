package dev.mfoot.android.app

import dev.mfoot.android.data.AuctionView
import dev.mfoot.android.data.ClubInfo
import dev.mfoot.android.data.LeagueSnapshot
import dev.mfoot.android.ui.kit.Crest
import dev.mfoot.android.ui.kit.Kit
import dev.mfoot.core.config.CustomPlayerConfig
import dev.mfoot.core.config.LeagueConfig
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
    /**
     * Quanto il tuo club lo conosce, da 0 a 100.
     *
     * Zero non vuol dire "scarso": vuol dire che non lo hai mai visto giocare e non hai
     * osservatori che ci lavorino sopra. La forbice larga che vedi accanto e un' ammissione
     * di ignoranza, non un giudizio sul giocatore.
     */
    val knowledge: Int = 0,
    /** Sta in Primavera: si allena e non gioca. */
    val isYouth: Boolean = false,
    /**
     * Scadenza e stipendio, se qualcuno lo ha sotto contratto.
     *
     * Null per gli svincolati, ed e' il caso giusto: uno svincolato non ha una scadenza,
     * non ha una scadenza *sconosciuta*.
     */
    val contratto: dev.mfoot.android.data.ContractInfo? = null,
    /** Il prezzo a cui e' in vendita adesso, o null se non lo e'. */
    val inVendita: dev.mfoot.android.data.ListingView? = null,
    /**
     * L'acquisto ancora contestabile che lo riguarda.
     *
     * Vale per dodici ore dopo che qualcuno l'ha comprato: e' l'unica finestra in cui
     * si puo' aprire un'asta, e finita quella il giocatore e' definitivo.
     */
    val acquisto: dev.mfoot.android.data.PurchaseView? = null,
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
    /**
     * Il membro dello staff all asta, quando l obiettivo non e un giocatore.
     *
     * Senza, la riga mostrava «Obiettivo #7»: le AI hanno cominciato ad aprire aste sullo
     * staff e la schermata sapeva disegnare solo i giocatori, quindi il listino si e
     * riempito di numeri senza nome.
     */
    val staff: dev.mfoot.android.data.StaffMember? = null,
    val leaderName: String?,
    /** Chi l'ha aperta. Serve al filtro «mie / non mie» e a capire chi sta vendendo. */
    val starterName: String? = null,
    /** L'ha aperta il mio club? */
    val startedByMe: Boolean = false,
) {
    val label: String
        get() = player?.player?.fullName
            ?: staff?.let { "${it.shortName} · ${it.roleLabel}" }
            ?: "Obiettivo #${auction.targetId}"

    /** La riga sotto al nome: chi e, in una riga. */
    val dettaglio: String?
        get() = player?.let { "${it.player.primaryPosition.short} · ${it.player.overall}" }
            ?: staff?.let { "${"★".repeat(it.stars)} · ${it.effetto}" }
}

/**
 * Quali aste si stanno guardando.
 *
 * ## Perche' un filtro e non solo un ordine
 *
 * Con quindici aste aperte contemporaneamente — che e' il ritmo normale appena le AI si
 * svegliano — l'elenco unico e' illeggibile: le due su cui si sta giocando davvero stanno
 * in mezzo alle altre tredici, e l'unico modo di ritrovarle e' scorrere ogni volta.
 *
 * Le quattro domande che ci si fa davanti al mercato sono sempre le stesse: cosa c'e' in
 * giro, cosa ho messo in vendita io, cosa hanno messo gli altri, dove sono impegnato.
 */
enum class AuctionFilter(val label: String) {
    TUTTE("Tutte"),
    MIE("Aperte da me"),
    ALTRUI("Degli altri"),
    OFFERTE("Ho offerto"),
}

/** Cosa si sta guardando della lista: il mercato o una rosa. */
enum class ListScope(val label: String) {
    /**
     * Chi si compra **adesso**, al prezzo scritto.
     *
     * Ha un posto suo e non e' un filtro degli svincolati, ed e' una correzione a un
     * errore vero: alla prima consegna il mercato a prezzo fisso viveva **dentro** le
     * liste esistenti — un prezzo che prendeva il posto del valore in una riga — e senza
     * un elenco tutto suo non si vedeva. Il proprietario l'ha detto con la frase che
     * chiude ogni discussione: «non esiste nel gioco».
     *
     * Una cosa che non ha un posto dove guardarla non e' una funzionalita' discreta: e'
     * una funzionalita' che non c'e'.
     */
    LISTINO("Listino"),
    SVINCOLATI("Svincolati"),
    ASTE("Aste"),
    TUTTI("Tutto il mondo"),
    MIA_ROSA("La mia rosa"),
}

/**
 * Sotto questa eta' uno svincolato non si compra: si trova mandandoci un osservatore.
 *
 * E' la regola di `0019` — un fuoriclasse di diciotto anni non deve poter essere preso da
 * chi ha solo piu' soldi — e vale a maggior ragione a prezzo fisso. Il server la applica
 * comunque; qui serve a non mostrare un pulsante che verrebbe rifiutato.
 */
const val ETA_MINIMA_LISTINO = 20

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
 * Le scelte che vanno fatte **prima** di generare il mondo.
 *
 * ## Perche' solo queste cinque, e non tutto il regolamento
 *
 * Il regolamento ha piu' di cento manopole. Chiederle tutte a chi sta creando la lega
 * vorrebbe dire un modulo lungo tre schermate prima ancora di aver visto il gioco, e quasi
 * tutte quelle manopole si possono cambiare dopo senza conseguenze: il tasso di infortunio,
 * i premi, la velocita' di crescita.
 *
 * Queste cinque no. Il numero di club decide quanti ne genera il mondo; il budget decide
 * l'intero listino prezzi; le divisioni decidono come e' fatto il campionato. Cambiarle a
 * lega avviata non rigenera niente — restano le squadre e i prezzi di prima — quindi vanno
 * chieste quando servono davvero, che e' adesso.
 */
data class SetupChoices(
    val totalClubs: Int = 16,
    val aiClubs: Int = 8,
    val minSquadSize: Int = 18,
    val maxSquadSize: Int = 30,
    val startingCredits: Int = 100_000,
    val divisions: Int = 1,
) {
    /** Gli umani che ci stanno: e' il numero che interessa a chi invita gli amici. */
    val humanClubs: Int get() = totalClubs - aiClubs

    companion object {
        /** I valori del preset scelto, come punto di partenza da ritoccare. */
        fun from(config: LeagueConfig) = SetupChoices(
            totalClubs = config.setup.totalClubs,
            aiClubs = config.setup.aiClubs,
            minSquadSize = config.setup.minSquadSize,
            maxSquadSize = config.setup.maxSquadSize,
            startingCredits = config.economy.startingCredits,
            divisions = config.divisions.count,
        )
    }

    /** Le scelte applicate alla configurazione del preset. */
    fun applyTo(config: LeagueConfig): LeagueConfig = config.copy(
        setup = config.setup.copy(
            totalClubs = totalClubs,
            aiClubs = aiClubs.coerceIn(0, totalClubs),
            minSquadSize = minSquadSize,
            maxSquadSize = maxOf(maxSquadSize, minSquadSize),
        ),
        economy = config.economy.copy(startingCredits = startingCredits),
        divisions = config.divisions.copy(count = divisions),
    )
}

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
        /**
         * Che lega apre il codice appena scritto.
         *
         * Tre stati e non due: null vuol dire «non l'ho ancora chiesto», [anteprimaVuota]
         * vuol dire «chiesto, e quel codice non apre niente». Confonderli farebbe sembrare
         * un codice sbagliato uguale a un codice non ancora cercato, che e' esattamente
         * l'ambiguita' per cui questa schermata esiste.
         */
        val anteprima: dev.mfoot.android.data.LeaguePreview? = null,
        val anteprimaVuota: Boolean = false,
    ) : AppState

    data class Caricamento(val fase: String) : AppState

    data class Dentro(
        val lega: LeagueSnapshot,
        val rows: List<PlayerRow>,
        val browse: BrowseState = BrowseState(),
        val auctions: List<AuctionRow> = emptyList(),
        val auctionFilter: AuctionFilter = AuctionFilter.TUTTE,
        /**
         * Gli acquisti ancora contestabili, di tutta la lega.
         *
         * Pubblici di proposito: contestare richiede di sapere che qualcuno ha comprato.
         * Un acquisto segreto per dodici ore sarebbe una finestra che nessuno puo' usare.
         */
        val acquisti: List<dev.mfoot.android.data.PurchaseView> = emptyList(),
        /** L'acquisto che si sta contestando, a schermo pieno. */
        val contestando: PlayerRow? = null,
        /**
         * Una partita propria ferma all'intervallo.
         *
         * Dura pochi minuti, ed e' l'unica cosa dell'app che ha una scadenza cosi' breve:
         * senza scriverlo da qualche parte, la finestra passerebbe senza che nessuno sappia
         * di averla avuta.
         */
        val intervallo: dev.mfoot.android.data.Intervallo? = null,
        /** L'asta aperta a schermo pieno per fare un'offerta. */
        val bidding: AuctionRow? = null,
        /** La cronologia dell'asta aperta a schermo pieno: chi ha offerto, e a che prezzo. */
        val biddingHistory: List<dev.mfoot.android.data.BidEvent> = emptyList(),
        /**
         * La pila delle schermate visitate, l'ultima in cima.
         *
         * Una pila e non una sola rotta: il tasto indietro deve tornare da dove si e'
         * arrivati. Chi apre un giocatore dal Listone vuole tornare al Listone, non alla
         * Dashboard — e con una rotta sola l'unico ritorno possibile sarebbe una scelta
         * fissa, sbagliata meta' delle volte.
         */
        val stack: List<Route> = listOf(Route.Casa),
        /**
         * Quale delle due squadre si sta gestendo.
         *
         * Uno **stato** e non una rotta: deve restare dov'e' passando da Rosa a Campo. Se
         * viaggiasse dentro la destinazione, ogni chip toccato riporterebbe alla prima
         * squadra, e gestire la Primavera sarebbe un continuo tornare indietro.
         */
        val guardoLaPrimavera: Boolean = false,
        val drawerOpen: Boolean = false,
        /** Un messaggio temporaneo in cima, tipo "lega creata". */
        val avviso: String? = null,
        val errore: String? = null,
    ) : AppState {

        val route: Route get() = stack.last()

        /**
         * Il club che l'interruttore sta mostrando.
         *
         * Ricade sulla prima squadra quando la Primavera non e' stata fondata: chi non ce
         * l'ha non deve vedere schermate vuote, deve vedere il pulsante per fondarla.
         */
        val clubMostrato: ClubInfo?
            get() = if (guardoLaPrimavera) lega.myYouthClub ?: lega.myClub else lega.myClub

        /** C'e' una seconda squadra fra cui passare? */
        val haLaPrimavera: Boolean get() = lega.myYouthClub != null

        /**
         * Gli acquisti che si possono ancora contestare.
         *
         * I propri restano dentro: chi ha comprato deve poter vedere quanto manca alla
         * fine della finestra, ed e' l'informazione che gli interessa di piu' — e' il
         * momento in cui sapra' se il giocatore e' suo davvero.
         */
        val contestabili: List<PlayerRow>
            get() = rows.filter { it.acquisto?.aperto() == true }
                .sortedBy { it.acquisto?.contestableUntil }

        /** C'e' una schermata sotto a cui tornare, o il tasto indietro chiude l'app? */
        val canGoBack: Boolean get() = stack.size > 1 || drawerOpen || bidding != null

        /** Le aste su cui si e' impegnati: sono quelle che vanno tenute d'occhio. */
        val myAuctions: List<AuctionRow> get() = auctions.filter { it.auction.hasMyBid }

        /** Le aste che il filtro lascia passare. */
        val asteVisibili: List<AuctionRow>
            get() = auctions.filter {
                when (auctionFilter) {
                    AuctionFilter.TUTTE -> true
                    AuctionFilter.MIE -> it.startedByMe
                    AuctionFilter.ALTRUI -> !it.startedByMe
                    AuctionFilter.OFFERTE -> it.auction.hasMyBid
                }
            }

        /** Quante ne contiene ogni filtro: il numero sta sul chip, cosi' non se ne perde una. */
        fun quanteAste(filtro: AuctionFilter): Int = when (filtro) {
            AuctionFilter.TUTTE -> auctions.size
            AuctionFilter.MIE -> auctions.count { it.startedByMe }
            AuctionFilter.ALTRUI -> auctions.count { !it.startedByMe }
            AuctionFilter.OFFERTE -> auctions.count { it.auction.hasMyBid }
        }

        val visible: List<PlayerRow>
            get() = rows
                .filter {
                    when (browse.scope) {
                        // Chi ha un prezzo e non e' gia' tuo: comprarlo e' un tocco.
                        ListScope.LISTINO ->
                            (it.inVendita != null || (it.isFreeAgent && it.player.age >= ETA_MINIMA_LISTINO)) &&
                                it.club?.isMine != true
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

    /** Il calendario del mese: la griglia, non l'elenco delle partite di una competizione. */
    data class Calendario(val calendario: CalendarState) : AppState

    /** Una partita gia' giocata, che si rivede minuto per minuto. */
    data class Partita(val partita: MatchState) : AppState

    /** Qualcosa e' andato storto in modo da cui non si esce da soli. */
    data class Guasto(val motivo: String) : AppState
}
