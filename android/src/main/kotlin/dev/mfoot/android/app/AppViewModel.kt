package dev.mfoot.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mfoot.android.data.ApiResult
import dev.mfoot.android.data.AuctionRepository
import dev.mfoot.android.data.AuctionView
import dev.mfoot.android.data.ClubUpload
import dev.mfoot.android.data.CalendarRepository
import dev.mfoot.android.data.CompetitionRepository
import dev.mfoot.android.data.ConversationRepository
import dev.mfoot.android.data.LeagueDeskRepository
import dev.mfoot.android.data.LeagueRepository
import dev.mfoot.android.data.LeagueSnapshot
import dev.mfoot.android.data.DivisionRepository
import dev.mfoot.android.data.LineupRepository
import dev.mfoot.android.data.PlayerRepository
import dev.mfoot.android.data.PromiseRepository
import dev.mfoot.android.data.DealRepository
import dev.mfoot.android.data.TradeKind
import dev.mfoot.android.data.TradeRepository
import dev.mfoot.android.data.SavedLineup
import dev.mfoot.android.data.Scouted
import dev.mfoot.android.data.ScoutingRepository
import dev.mfoot.android.data.Session
import dev.mfoot.android.data.SquadRepository
import dev.mfoot.android.data.Supabase
import dev.mfoot.android.data.TableRepository
import dev.mfoot.android.data.SupabaseApi
import dev.mfoot.android.data.WorldUpload
import dev.mfoot.core.calendar.ClubFate
import dev.mfoot.core.calendar.LeagueCalendar
import dev.mfoot.core.calendar.Division
import dev.mfoot.core.calendar.DivisionRules
import dev.mfoot.core.calendar.SeasonEnd
import dev.mfoot.core.calendar.CompetitionType
import dev.mfoot.core.calendar.SeasonOutcome
import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.conversation.ConversationEngine
import dev.mfoot.core.conversation.ConversationOption
import dev.mfoot.core.conversation.ConversationTopic
import dev.mfoot.core.conversation.LeagueFacts
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.match.AutoLineup
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Position
import dev.mfoot.core.world.CustomPlayerBuilder
import dev.mfoot.core.world.PotentialEstimator
import dev.mfoot.core.world.WorldGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

/**
 * Il cervello dell'app.
 *
 * ## Il mondo si genera sul telefono, ma la verita' sta sul database
 *
 * `core` e' la stessa identica libreria che gira sul server, quindi il dispositivo
 * produce milletrecento giocatori in millisecondi. Ma li genera **una volta sola**, al
 * momento di creare la lega: subito dopo li carica, e da li' in avanti legge dal database
 * come tutti gli altri.
 *
 * E' la differenza fra un'app che mostra un mondo e un'app che partecipa a un mondo. Se
 * ogni avvio rigenerasse, due telefoni vedrebbero due leghe diverse con lo stesso nome.
 */
class AppViewModel : ViewModel() {

    companion object {
        /** Quanti giorni si danno agli spareggi: sono due o tre turni, non un torneo. */
        private const val GIORNI_SPAREGGIO = 4L
    }

    /**
     * L'ultima configurazione letta.
     *
     * Serve nelle schermate che escono da `Dentro` — la creazione di una competizione,
     * per esempio — dove la lega non e' piu' nello stato ma le sue regole servono lo
     * stesso per calcolare il calendario.
     */
    private var ultimaConfig: LeagueConfig? = null

    private val _state = MutableStateFlow<AppState>(AppState.Avvio)
    val state: StateFlow<AppState> = _state

    init {
        avvia()
    }

    // ------------------------------------------------------------------------- ingresso

    /**
     * Recupera l'identita' salvata e, se c'e' gia' una lega, la apre.
     *
     * Nessuna schermata di accesso: chi ha gia' giocato riapre l'app e si ritrova dentro.
     */
    fun avvia() {
        viewModelScope.launch {
            if (!Supabase.isConfigured) {
                _state.value = AppState.Guasto(
                    "Credenziali Supabase assenti. Vanno messe in local.properties: " +
                        "vedi docs/SETUP.md.",
                )
                return@launch
            }

            _state.value = AppState.Avvio

            when (val session = SupabaseApi.ensureSession()) {
                is ApiResult.Error -> {
                    _state.value = AppState.Porta(errore = session.message)
                    return@launch
                }
                is ApiResult.Ok -> Unit
            }

            val leagueId = Session.leagueId
            if (leagueId == null) {
                _state.value = AppState.Porta()
            } else {
                carica(leagueId)
            }
        }
    }

    fun apriPorta(mode: DoorMode) {
        val corrente = _state.value as? AppState.Porta ?: AppState.Porta()
        _state.value = corrente.copy(mode = mode, errore = null)
    }

    /**
     * Crea la lega: genera il mondo, lo carica, poi lo rilegge da dove l'ha messo.
     *
     * La rilettura non e' pignoleria. Un 200 dice che la chiamata e' andata a buon fine,
     * non che il mondo sia arrivato tutto: leggere indietro quello che si e' scritto e'
     * l'unica conferma che valga qualcosa, e costa una richiesta.
     */
    fun creaLega(
        nome: String,
        codice: String,
        nickname: String,
        presetId: String = "sprint",
        scelte: SetupChoices? = null,
    ) {
        if (nome.isBlank() || codice.isBlank() || nickname.isBlank()) {
            aggiornaPorta(errore = "Servono nome della lega, codice e il tuo nickname.")
            return
        }

        viewModelScope.launch {
            aggiornaPorta(busy = "Genero il mondo…")

            val preset = ConfigPresets.byId(presetId) ?: ConfigPresets.all.first()
            val base = preset.build(16, 8, LocalDate.now())
            // Le scelte fatte alla creazione vincono sul preset: il preset e' un punto di
            // partenza, non un vincolo.
            val config = (scelte?.applyTo(base) ?: base).let {
                it.copy(
                    setup = it.setup.copy(
                        leagueName = nome.trim(),
                        // Ogni lega ha il suo mondo. Con il seed predefinito, due gruppi
                        // di amici si ritroverebbero gli stessi identici giocatori con gli
                        // stessi identici nomi, e la sensazione di scoprire qualcosa
                        // sparirebbe alla seconda lega.
                        worldSeed = nuovoSeed(),
                    ),
                )
            }

            val world = withContext(Dispatchers.Default) { WorldGenerator.generate(config) }

            aggiornaPorta(busy = "Carico ${world.players.size} giocatori…")
            val payload = withContext(Dispatchers.Default) {
                WorldUpload.buildPayload(world, config, nome.trim(), codice.trim(), nickname.trim())
            }

            when (val creata = SupabaseApi.createLeague(payload)) {
                is ApiResult.Error -> aggiornaPorta(errore = creata.message)
                is ApiResult.Ok -> {
                    Session.leagueId = creata.value
                    Session.nickname = nickname.trim()
                    carica(creata.value, avviso = "Lega creata. Il codice per gli altri e' ${codice.trim()}.")
                }
            }
        }
    }

    fun entraInLega(codice: String, nickname: String) {
        if (codice.isBlank() || nickname.isBlank()) {
            aggiornaPorta(errore = "Servono il codice della lega e il tuo nickname.")
            return
        }

        viewModelScope.launch {
            aggiornaPorta(busy = "Cerco la lega…")

            when (val entrata = SupabaseApi.joinLeague(codice.trim(), nickname.trim())) {
                is ApiResult.Error -> aggiornaPorta(errore = entrata.message)
                is ApiResult.Ok -> {
                    Session.leagueId = entrata.value
                    Session.nickname = nickname.trim()
                    carica(entrata.value, avviso = "Sei dentro.")
                }
            }
        }
    }

    /** Torna alla porta senza perdere l'identita': la lega resta, ci si puo' rientrare. */
    fun lasciaLega() {
        Session.leagueId = null
        _state.value = AppState.Porta()
    }

    // -------------------------------------------------------------------------- lettura

    fun ricarica() {
        Session.leagueId?.let { carica(it) } ?: run { _state.value = AppState.Porta() }
    }

    private fun carica(leagueId: Long, avviso: String? = null) {
        // Dove si era: la ricarica non deve buttare fuori da dove si stava lavorando.
        //
        // Ricaricare la lega e' l'ultimo passo di mezza dozzina di azioni — parlare con un
        // giocatore, accettare uno scambio, chiudere la stagione — perche' cambiano rose e
        // conti in banca. Ricostruendo lo stato da zero si ricostruiva anche la pila delle
        // schermate, e chi aveva appena risposto a un giocatore si ritrovava alla Dashboard
        // senza aver toccato niente: sembra che l'app abbia perso il filo.
        val dovEro = statoCorrente()?.stack

        viewModelScope.launch {
            _state.value = AppState.Caricamento("Leggo la lega…")

            val livelli = DivisionRepository.levels(leagueId)

            when (val snapshot = LeagueRepository.snapshot(leagueId)) {
                is ApiResult.Error -> {
                    // Non si cancella la lega salvata: quasi sempre e' un problema di rete
                    // e al prossimo tentativo funziona. Buttarla via costringerebbe a
                    // reinserire il codice ogni volta che il treno entra in galleria.
                    _state.value = AppState.Porta(
                        errore = "Non riesco a leggere la lega: ${snapshot.message}",
                    )
                }

                is ApiResult.Ok -> {
                    ultimaConfig = snapshot.value.league.config
                    // Le divisioni arrivano da una lettura separata: si attaccano qui,
                    // dove i club esistono gia.
                    val lega = snapshot.value.copy(
                        clubs = snapshot.value.clubs.map {
                            it.copy(divisionLevel = livelli[it.id] ?: 1)
                        },
                    )
                    // Prima le stime, poi le righe: `righe` le legge, e calcolarle con la
                    // mappa vuota vorrebbe dire mostrare forbici larghe per un istante e
                    // poi vederle cambiare sotto gli occhi.
                    scouting = lega.myClub?.let { ScoutingRepository.load(it.id) }.orEmpty()

                    val rows = withContext(Dispatchers.Default) { righe(lega) }
                    val aste = AuctionRepository.openAuctions(leagueId)
                    _state.value = AppState.Dentro(
                        lega = lega,
                        rows = rows,
                        auctions = asteViste(aste, rows, lega),
                        browse = BrowseState(
                            // Chi ha gia. una rosa vuole vedere la sua rosa; chi non ce
                            // l.ha ancora vuole vedere cosa c.e. da prendere.
                            scope = if (lega.myClub != null) ListScope.MIA_ROSA
                            else ListScope.SVINCOLATI,
                        ),
                        // Con la pila di prima se c'era: le rotte con un dato dentro —
                        // la rosa di un club, la scheda di un giocatore — restano valide,
                        // perche' sono gli stessi club e gli stessi giocatori appena riletti.
                        stack = dovEro ?: listOf(Route.Dashboard),
                        avviso = avviso,
                    )
                    caricaFormazione(lega)
                    // Ripulito a ogni ricarica: dopo che qualcuno fonda un club, un elenco
                    // partecipanti in memoria dalla volta prima lo mostrerebbe ancora senza.
                    _desk.value = DeskState()
                }
            }
        }
    }

    /**
     * Da giocatori del database a righe da mostrare.
     *
     * La stima di potenziale usa l'id del **proprio club** come osservatore: due club
     * vedono forbici leggermente diverse dello stesso giocatore, ed e' quello che rende
     * possibile l'affare. Chi non ha ancora un club guarda con l'occhio della lega.
     */
    private fun righe(snapshot: LeagueSnapshot): List<PlayerRow> {
        val observerId = snapshot.myClub?.id ?: snapshot.league.id
        val clubById = snapshot.clubs.associateBy { it.id }
        val config: LeagueConfig = snapshot.league.config

        return snapshot.players.map { player ->
            // La stima ristretta, se il server ne ha calcolata una per questo club.
            //
            // Il potenziale e' nascosto di proposito, e la forbice si stringe con i minuti
            // che lo hai visto giocare e con il lavoro degli osservatori. Il conto lo fa il
            // tick, che i valori veri li ha: qui arriva solo l'intervallo, e non c'e' modo
            // di dedurre il segreto per differenza.
            //
            // Quando manca — migrazione non applicata, giocatore che non interessa a
            // nessuno — si ricade sulla stima pubblica a conoscenza zero, che e' quella che
            // l'app ha sempre mostrato: la forbice resta larga, ed e' la verita'.
            val scouted = scouting[player.id.value]
            val estimate = scouted?.range ?: PotentialEstimator.publicEstimate(player, observerId)

            PlayerRow(
                player = player,
                estimate = estimate,
                hasUpside = PotentialEstimator.hasUpside(player),
                value = Valuation.estimatedValue(player, estimate, config),
                club = snapshot.clubOfPlayer[player.id.value]?.let(clubById::get),
                knowledge = scouted?.knowledge ?: 0,
                isYouth = player.id.value in snapshot.youth,
            )
        }
    }

    /**
     * Quello che il proprio club sa, per giocatore.
     *
     * Si legge una volta a caricamento e resta in memoria: cambia solo quando il tick
     * ricalcola, cioe' dopo una partita, e rileggerlo a ogni schermata sarebbe una
     * richiesta in piu' per un dato che si muove una volta al giorno.
     */
    private var scouting: Map<Long, Scouted> = emptyMap()

    // ------------------------------------------------------------------------ fondazione

    /** Apre la fondazione del club, con il progetto gia' impostato sull'eta' di partenza. */
    fun fondaClub() {
        val dentro = _state.value as? AppState.Dentro ?: return
        if (dentro.lega.myClub != null) return

        val config = dentro.lega.league.config.custom
        _state.value = AppState.Fondazione(
            FoundingState(
                lega = dentro.lega,
                draft = CustomPlayerBuilder.Draft(
                    age = config.defaultAge,
                    weakFoot = config.startingStars,
                    skillStars = config.startingStars,
                    nationality = dentro.lega.league.config.world.nationalities.firstOrNull() ?: "Italia",
                ),
            ),
        )
    }

    fun aggiornaFondazione(block: (FoundingState) -> FoundingState) {
        val fondazione = _state.value as? AppState.Fondazione ?: return
        _state.value = AppState.Fondazione(block(fondazione.founding).copy(errore = null))
    }

    fun alzaAttributo(attr: Attr) = aggiornaFondazione {
        it.copy(draft = CustomPlayerBuilder.raise(it.draft, attr, it.config))
    }

    fun abbassaAttributo(attr: Attr) = aggiornaFondazione {
        it.copy(draft = CustomPlayerBuilder.lower(it.draft, attr, it.config))
    }

    fun cambiaRuolo(position: Position) = aggiornaFondazione {
        it.copy(draft = CustomPlayerBuilder.withPosition(it.draft, position))
    }

    fun annullaFondazione() = ricarica()

    /**
     * Fonda il club.
     *
     * Il risultato lo dice il server: se l'overall che torna non e' quello mostrato a
     * schermo, e' il server ad avere ragione, ed e' giusto che l'utente veda il numero
     * vero invece di quello che credeva di aver costruito.
     */
    fun confermaFondazione() {
        val fondazione = (_state.value as? AppState.Fondazione)?.founding ?: return

        val problemi = fondazione.problems
        if (problemi.isNotEmpty()) {
            aggiornaFondazione { it.copy(errore = problemi.first()) }
            return
        }

        viewModelScope.launch {
            aggiornaFondazione { it.copy(busy = "Fondo il club…") }

            val payload = ClubUpload.payload(
                leagueId = fondazione.lega.league.id,
                clubName = fondazione.clubName,
                clubShort = fondazione.clubShort,
                kit = fondazione.kit,
                crest = fondazione.crest,
                draft = fondazione.draft,
            )

            when (val creato = SupabaseApi.createClub(payload)) {
                is ApiResult.Error ->
                    aggiornaFondazione { it.copy(busy = null, errore = creato.message) }

                is ApiResult.Ok -> {
                    Session.clubId = creato.value.clubId
                    carica(
                        fondazione.lega.league.id,
                        avviso = "${fondazione.clubName} e' nato. " +
                            "${fondazione.draft.firstName} ${fondazione.draft.lastName} " +
                            "esce a ${creato.value.overall}, con ${creato.value.spent} punti spesi.",
                    )
                }
            }
        }
    }

    // --------------------------------------------------------------------- competizioni

    /**
     * Apre la gestione delle competizioni.
     *
     * Le crea l'admin, una per una: campionato, coppa, gironi piu' eliminazione, con i
     * partecipanti e le date che decide lui. Una prima versione le faceva partire da
     * sola alla data indicata in configurazione — comodo, e sbagliato: toglieva
     * all'admin la cosa piu' importante che deve poter fare.
     */
    fun apriCompetizioni() {
        val dentro = _state.value as? AppState.Dentro ?: return
        val leagueId = dentro.lega.league.id

        viewModelScope.launch {
            val esistenti = CompetitionRepository.list(leagueId)
            _state.value = AppState.Competizioni(
                CompetitionsState(
                    leagueId = leagueId,
                    clubs = dentro.lega.clubs,
                    existing = (esistenti as? ApiResult.Ok)?.value ?: emptyList(),
                    errore = (esistenti as? ApiResult.Error)?.message,
                ),
            )
        }
    }

    fun chiudiCompetizioni() = ricarica()

    /**
     * Apre classifica e calendario.
     *
     * La aprono tutti, non solo l'admin: e' la schermata che si guarda piu' spesso, ed e'
     * quella che fa sembrare la lega un campionato invece di una serie di partite slegate.
     */
    /**
     * Apre classifica e calendario, sulla scheda giusta.
     *
     * La scheda arriva da **quale voce e' stata toccata** nella barra in basso. Aprire
     * sempre la classifica e lasciare che sia l'utente a spostarsi vorrebbe dire che il
     * tasto "Calendario" mostra la classifica, che e' quello che faceva prima.
     */
    fun apriClassifica(tab: TableTab = TableTab.CLASSIFICA) {
        val dentro = _state.value as? AppState.Dentro ?: return
        val leagueId = dentro.lega.league.id

        viewModelScope.launch {
            _state.value = AppState.Caricamento(
                if (tab == TableTab.CALENDARIO) "Leggo il calendario…" else "Leggo la classifica…",
            )

            when (val competizioni = CompetitionRepository.list(leagueId)) {
                is ApiResult.Error -> _state.value = AppState.Classifica(
                    TableState(emptyList(), null, tab = tab, errore = competizioni.message),
                )

                is ApiResult.Ok -> {
                    // Le amichevoli hanno una competizione loro perche' una partita deve
                    // appartenere a qualcosa, ma non e' un torneo e non va nell'elenco: una
                    // riga "Amichevoli" fra Serie A e Coppa, con una classifica priva di
                    // senso, farebbe cercare a lungo cosa sia.
                    competizioniAmichevoli = CompetitionRepository.friendlyIds(leagueId)
                    val elenco = competizioni.value.filterNot { it.id in competizioniAmichevoli }

                    val prima = elenco.firstOrNull()
                    val base = TableState(
                        competitions = elenco,
                        selectedId = prima?.id,
                        clubs = dentro.lega.clubs,
                        myClubId = dentro.lega.myClub?.id,
                        tab = tab,
                        zone = dentro.lega.league.config.calendar.timeZone,
                    )
                    _state.value = AppState.Classifica(
                        if (prima == null) base else base.copy(view = caricaTabella(leagueId, prima))
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------- il calendario

    /**
     * Apre la griglia del mese.
     *
     * Si carica una volta sola e poi si sfoglia in memoria: cambiare mese e' il gesto piu'
     * frequente qui, e una richiesta a ogni freccia renderebbe insopportabile guardare
     * avanti di due mesi.
     */
    fun apriCalendario() {
        val dentro = statoCorrente() ?: return
        val lega = dentro.lega
        val zona = lega.league.config.calendar.timeZone
        val oggi = LocalDate.now(zona)

        viewModelScope.launch {
            _state.value = AppState.Calendario(
                CalendarState(
                    mese = YearMonth.from(oggi),
                    oggi = oggi,
                    riposi = lega.league.config.calendar.restWeekdays,
                    caricamento = true,
                ),
            )

            competizioniAmichevoli = CompetitionRepository.friendlyIds(lega.league.id)

            val esito = CalendarRepository.load(
                leagueId = lega.league.id,
                myClubId = lega.myClub?.id,
                zone = zona,
                clubName = { id -> lega.clubs.firstOrNull { it.id == id }?.shortName ?: "Club #$id" },
                playerName = { id ->
                    lega.players.firstOrNull { it.id.value == id }?.shortName ?: "giocatore #$id"
                },
                friendlyCompetitions = competizioniAmichevoli,
            )

            val corrente = (_state.value as? AppState.Calendario)?.calendario ?: return@launch
            _state.value = AppState.Calendario(
                when (esito) {
                    is ApiResult.Error -> corrente.copy(caricamento = false, errore = esito.message)
                    is ApiResult.Ok -> corrente.copy(
                        caricamento = false,
                        errore = null,
                        eventi = LeagueCalendar.build(
                            matches = esito.value.matches,
                            auctions = esito.value.auctions,
                            contracts = esito.value.contracts,
                            promises = esito.value.promises,
                        ),
                    )
                },
            )
        }
    }

    fun sfogliaCalendario(mesi: Int) {
        val corrente = (_state.value as? AppState.Calendario)?.calendario ?: return
        _state.value = AppState.Calendario(corrente.copy(mese = corrente.mese.plusMonths(mesi.toLong())))
    }

    fun scegliGiorno(giorno: LocalDate) {
        val corrente = (_state.value as? AppState.Calendario)?.calendario ?: return
        // Ritoccare sullo stesso giorno lo deseleziona: e' il modo di tornare a "il
        // prossimo impegno" senza cercare un pulsante che lo faccia.
        _state.value = AppState.Calendario(
            corrente.copy(
                selezionato = if (corrente.selezionato == giorno) null else giorno,
                mese = YearMonth.from(giorno),
            ),
        )
    }

    fun chiudiCalendario() = ricarica()

    /**
     * Le competizioni che non fanno classifica.
     *
     * Si riempie leggendo le competizioni, e resta vuota finche' la migrazione delle
     * amichevoli non e' applicata: senza, un'amichevole si colora come una partita
     * qualsiasi, che e' un difetto piccolo e non un guasto.
     */
    private var competizioniAmichevoli: Set<Long> = emptySet()

    fun cambiaSchedaTabella(tab: TableTab) {
        val schermata = (_state.value as? AppState.Classifica)?.table ?: return
        _state.value = AppState.Classifica(schermata.copy(tab = tab))
    }

    fun scegliCompetizione(id: Long) {
        val schermata = (_state.value as? AppState.Classifica)?.table ?: return
        val competizione = schermata.competitions.firstOrNull { it.id == id } ?: return
        val leagueId = Session.leagueId ?: return

        viewModelScope.launch {
            _state.value = AppState.Classifica(
                schermata.copy(selectedId = id, view = caricaTabella(leagueId, competizione)),
            )
        }
    }

    private suspend fun caricaTabella(
        leagueId: Long,
        competizione: dev.mfoot.android.data.CompetitionInfo,
    ) = (TableRepository.load(leagueId, competizione) as? ApiResult.Ok)?.value

    fun chiudiClassifica() = ricarica()

    /** Comincia una competizione nuova, con tutti i club gia' iscritti. */
    fun nuovaCompetizione() {
        val schermata = (_state.value as? AppState.Competizioni)?.competitions ?: return
        aggiornaDraft(
            CompetitionDraft(
                name = "Campionato",
                participants = schermata.clubs.map { it.id }.toSet(),
            ),
        )
    }

    fun annullaCompetizione() {
        val schermata = (_state.value as? AppState.Competizioni)?.competitions ?: return
        _state.value = AppState.Competizioni(schermata.copy(draft = null, errore = null))
    }

    /**
     * Applica una modifica e **ricalcola subito il calendario**.
     *
     * Il ricalcolo passa da `core`, la stessa libreria che il server usa per giocare le
     * partite: quello che l'admin vede in anteprima e' esattamente quello che verra'
     * scritto, non una stima.
     */
    fun modificaCompetizione(block: (CompetitionDraft) -> CompetitionDraft) {
        val schermata = (_state.value as? AppState.Competizioni)?.competitions ?: return
        val draft = schermata.draft ?: return
        aggiornaDraft(block(draft))
    }

    private fun aggiornaDraft(draft: CompetitionDraft) {
        val schermata = (_state.value as? AppState.Competizioni)?.competitions ?: return
        val lega = Session.leagueId ?: return

        val calcolato = if (draft.participants.size < 2) {
            draft.copy(schedule = null, errore = null)
        } else {
            runCatching {
                draft.copy(
                    schedule = CompetitionRepository.preview(
                        participants = draft.participants.sorted(),
                        type = draft.type,
                        doubleRound = draft.doubleRound && draft.supportsDoubleRound,
                        calendar = draft.calendar,
                        config = configCorrente(),
                        seed = lega,
                    ),
                    errore = null,
                )
            }.getOrElse { draft.copy(schedule = null, errore = it.message) }
        }

        _state.value = AppState.Competizioni(schermata.copy(draft = calcolato))
    }

    private fun configCorrente(): LeagueConfig =
        (_state.value as? AppState.Dentro)?.lega?.league?.config
            ?: ultimaConfig
            ?: LeagueConfig()

    fun creaCompetizione() {
        val schermata = (_state.value as? AppState.Competizioni)?.competitions ?: return
        val draft = schermata.draft ?: return
        val schedule = draft.schedule ?: return

        viewModelScope.launch {
            _state.value = AppState.Competizioni(
                schermata.copy(draft = draft.copy(busy = "Scrivo il calendario…")),
            )

            val esito = CompetitionRepository.create(
                leagueId = schermata.leagueId,
                name = draft.name,
                type = draft.type,
                doubleRound = draft.doubleRound && draft.supportsDoubleRound,
                participants = draft.participants.sorted(),
                calendar = draft.calendar,
                schedule = schedule,
            )

            when (esito) {
                is ApiResult.Error -> _state.value = AppState.Competizioni(
                    schermata.copy(draft = draft.copy(busy = null, errore = esito.message)),
                )

                is ApiResult.Ok -> {
                    val aggiornate = CompetitionRepository.list(schermata.leagueId)
                    _state.value = AppState.Competizioni(
                        schermata.copy(
                            draft = null,
                            existing = (aggiornate as? ApiResult.Ok)?.value ?: schermata.existing,
                            avviso = "${draft.name}: ${schedule.fixtures.size} partite in calendario.",
                        ),
                    )
                }
            }
        }
    }

    fun cancellaCompetizione(id: Long) {
        val schermata = (_state.value as? AppState.Competizioni)?.competitions ?: return

        viewModelScope.launch {
            when (val esito = CompetitionRepository.delete(id)) {
                is ApiResult.Error ->
                    _state.value = AppState.Competizioni(schermata.copy(errore = esito.message))

                is ApiResult.Ok -> {
                    val aggiornate = CompetitionRepository.list(schermata.leagueId)
                    _state.value = AppState.Competizioni(
                        schermata.copy(
                            existing = (aggiornate as? ApiResult.Ok)?.value ?: emptyList(),
                            avviso = "Competizione cancellata.",
                            errore = null,
                        ),
                    )
                }
            }
        }
    }

    // ----------------------------------------------------------------------------- aste

    /**
     * Aggancia a ogni asta il giocatore e il capofila.
     *
     * Un elenco di "asta #17, 42 crediti" non aiuta nessuno a decidere: serve vedere chi
     * si sta comprando e chi ce l'ha in mano adesso.
     */
    private fun asteViste(
        result: ApiResult<List<AuctionView>>,
        rows: List<PlayerRow>,
        snapshot: LeagueSnapshot,
    ): List<AuctionRow> {
        val auctions = (result as? ApiResult.Ok)?.value ?: return emptyList()
        val playerById = rows.associateBy { it.player.id.value }
        val clubById = snapshot.clubs.associateBy { it.id }

        return auctions.map { auction ->
            AuctionRow(
                auction = auction,
                player = if (auction.targetType == "player") playerById[auction.targetId] else null,
                leaderName = auction.leaderClubId?.let { clubById[it]?.shortName },
            )
        }
    }

    fun apriOfferta(row: AuctionRow?) {
        val dentro = _state.value as? AppState.Dentro ?: return
        _state.value = dentro.copy(bidding = row, errore = null)
    }

    /**
     * Mette all'asta uno svincolato.
     *
     * Chiunque puo' aprire l'asta, non solo chi poi la vince: e' cosi' che il listino si
     * muove. Chi apre non ha nessun vantaggio, se non aver deciso il momento.
     */
    fun mettiAllAsta(row: PlayerRow) {
        val dentro = _state.value as? AppState.Dentro ?: return
        val club = dentro.lega.myClub ?: run {
            _state.value = dentro.copy(errore = "Prima devi fondare il tuo club.")
            return
        }

        viewModelScope.launch {
            val prezzo = 1.coerceAtLeast(row.value / 4)
            when (val esito = AuctionRepository.startAuction(
                leagueId = dentro.lega.league.id,
                targetId = row.player.id.value,
                startingPrice = prezzo,
            )) {
                is ApiResult.Error ->
                    _state.value = statoCorrente()?.copy(errore = esito.message) ?: return@launch

                is ApiResult.Ok -> {
                    // Si chiude la scheda e si passa alle aste: l'azione appena fatta
                    // deve essere visibile, o sembra non essere successo niente.
                    val corrente = statoCorrente() ?: return@launch
                    _state.value = corrente.copy(
                        browse = corrente.browse.copy(scope = ListScope.ASTE, selected = null),
                    )
                    val mio = row.club?.isMine == true
                    aggiornaAste(
                        avviso = if (mio) {
                            "${row.player.fullName} e' in vendita, base $prezzo. " +
                                "Alla chiusura il prezzo arriva a te."
                        } else {
                            "${row.player.fullName} e' all'asta, base $prezzo. " +
                                "Chiunque puo' offrire, ${club.shortName} compreso."
                        },
                    )
                }
            }
        }
    }

    /**
     * Dichiara l'offerta massima.
     *
     * Non e' un rilancio: e' il proprio limite. Il sistema difende la posizione da solo
     * alzando il prezzo quanto basta, ed e' cio' che permette di andare a dormire senza
     * perdere l'asta per un credito.
     */
    fun offri(auctionId: Long, maxAmount: Int) {
        val dentro = _state.value as? AppState.Dentro ?: return
        val club = dentro.lega.myClub ?: return

        viewModelScope.launch {
            when (val esito = AuctionRepository.bid(auctionId, club.id, maxAmount)) {
                is ApiResult.Error ->
                    _state.value = statoCorrente()?.copy(errore = esito.message) ?: return@launch

                is ApiResult.Ok -> {
                    val testa = if (esito.value.youLead) "Sei in testa" else "Non basta"
                    aggiornaAste(avviso = "$testa: prezzo a ${esito.value.currentPrice} crediti.")
                    _state.value = statoCorrente()?.copy(bidding = null) ?: return@launch
                }
            }
        }
    }

    /**
     * Rilegge aste e crediti senza ricaricare tutto il mondo.
     *
     * Milletrecento giocatori non cambiano perche' qualcuno ha rilanciato: rileggerli
     * costerebbe quattrocento kilobyte per aggiornare un numero.
     */
    fun aggiornaAste(avviso: String? = null) {
        val dentro = _state.value as? AppState.Dentro ?: return

        viewModelScope.launch {
            val aste = AuctionRepository.openAuctions(dentro.lega.league.id)
            val clubs = LeagueRepository.clubs(dentro.lega.league.id)
            val snapshot = when (clubs) {
                is ApiResult.Ok -> dentro.lega.copy(clubs = clubs.value)
                is ApiResult.Error -> dentro.lega
            }
            val corrente = statoCorrente() ?: return@launch

            _state.value = corrente.copy(
                lega = snapshot,
                auctions = asteViste(aste, corrente.rows, snapshot),
                avviso = avviso ?: corrente.avviso,
                errore = null,
            )
        }
    }

    private fun statoCorrente(): AppState.Dentro? = _state.value as? AppState.Dentro

    fun chiudiErrore() {
        val dentro = statoCorrente() ?: return
        _state.value = dentro.copy(errore = null)
    }

    // ------------------------------------------------------------------ regolamento

    /**
     * Le modifiche al regolamento vivono qui finche' non si salvano.
     *
     * Separare la modifica dal salvataggio permette di sistemare tre campi e confermare una
     * volta, e soprattutto di cambiare idea senza aver gia' alterato una lega in corso.
     * Null significa "nessuna modifica pendente": e' anche quello che spegne il pulsante.
     */
    private val _config = MutableStateFlow(SettingsEdit())
    val configEdit: StateFlow<SettingsEdit> = _config

    fun modificaRegolamento(nuova: LeagueConfig) {
        _config.value = _config.value.copy(bozza = nuova, errore = null)
    }

    fun salvaRegolamento() {
        val bozza = _config.value.bozza ?: return
        val leagueId = Session.leagueId ?: return

        viewModelScope.launch {
            _config.value = _config.value.copy(busy = "Salvo le regole…")

            when (val esito = LeagueRepository.updateConfig(leagueId, bozza)) {
                is ApiResult.Error ->
                    _config.value = _config.value.copy(busy = null, errore = esito.message)

                is ApiResult.Ok -> {
                    _config.value = SettingsEdit()
                    ultimaConfig = bozza
                    // Si rilegge la lega: le regole nuove cambiano valutazioni e prezzi,
                    // e lasciare a schermo numeri calcolati con quelle vecchie sarebbe
                    // peggio di non aver salvato.
                    carica(leagueId, avviso = "Regole aggiornate.")
                }
            }
        }
    }

    /** La configurazione da mostrare: la bozza se c'e', altrimenti quella della lega. */
    fun configMostrata(): LeagueConfig =
        _config.value.bozza ?: configCorrente()

    // --------------------------------------------------------------------- scrivania

    /**
     * Partecipanti e registro del tick.
     *
     * Si leggono **quando si apre la schermata**, non all'avvio: sono due richieste in piu'
     * per dati che si guardano due volte a stagione, e farle sempre significherebbe far
     * aspettare tutti per comodita' di pochi.
     */
    private val _desk = MutableStateFlow(DeskState())
    val desk: StateFlow<DeskState> = _desk

    fun caricaPartecipanti() {
        val dentro = statoCorrente() ?: return
        if (_desk.value.members != null) return

        viewModelScope.launch {
            when (val esito = LeagueDeskRepository.members(dentro.lega.league.id, dentro.lega.clubs)) {
                is ApiResult.Error -> _desk.value = _desk.value.copy(errore = esito.message)
                is ApiResult.Ok -> _desk.value = _desk.value.copy(members = esito.value, errore = null)
            }
        }
    }

    fun caricaRegistro() {
        val dentro = statoCorrente() ?: return
        if (_desk.value.tickLetto) return

        viewModelScope.launch {
            when (val esito = LeagueDeskRepository.tick(dentro.lega.league.id)) {
                is ApiResult.Error -> _desk.value = _desk.value.copy(errore = esito.message)
                // `tickLetto` si alza anche quando il risultato e' null: e' proprio quel caso
                // — il tick non ha mai girato — che la schermata deve poter distinguere da
                // "sto ancora caricando".
                is ApiResult.Ok ->
                    _desk.value = _desk.value.copy(tick = esito.value, tickLetto = true, errore = null)
            }
        }
    }

    // -------------------------------------------------------------------- spogliatoio

    private val _spogliatoio = MutableStateFlow(SpogliatoioState())
    val spogliatoio: StateFlow<SpogliatoioState> = _spogliatoio

    /**
     * Carica i discorsi aperti.
     *
     * Si chiama entrando nello spogliatoio e dopo ogni risposta: l'elenco cambia solo per
     * effetto del tick o di quello che hai appena detto, e tenerlo aggiornato in tempo
     * reale costerebbe una richiesta al minuto per un dato che si guarda due volte al
     * giorno.
     */
    fun caricaSpogliatoio() {
        val club = statoCorrente()?.lega?.myClub ?: return
        viewModelScope.launch {
            when (val esito = ConversationRepository.load(club.id)) {
                is ApiResult.Error ->
                    _spogliatoio.value = _spogliatoio.value.copy(avviso = esito.message, letto = true)

                is ApiResult.Ok ->
                    _spogliatoio.value = _spogliatoio.value.copy(
                        spogliatoio = esito.value,
                        letto = true,
                        avviso = null,
                    )
            }
        }
    }

    fun apriColloquio(playerId: Long) {
        _spogliatoio.value = _spogliatoio.value.copy(
            conPlayerId = playerId,
            rispostaUltima = null,
            deltaUltimo = 0,
            avviso = null,
        )
    }

    /**
     * Convoca qualcuno che non aveva niente da dire.
     *
     * Il colloquio si apre sul server prima di comparire sullo schermo: senza, si potrebbe
     * cominciare a parlare e scoprire solo alla risposta che l'attesa non era finita, dopo
     * aver gia' scelto cosa dire.
     */
    fun convoca(playerId: Long) {
        val dentro = statoCorrente() ?: return
        val club = dentro.lega.myClub ?: return
        val player = dentro.lega.squadOf(club.id).firstOrNull { it.id.value == playerId } ?: return

        viewModelScope.launch {
            _spogliatoio.value = _spogliatoio.value.copy(inCorso = true, avviso = null)
            val argomento = LeagueFacts.argomentoDiCortesia(player)

            when (val esito = ConversationRepository.convoca(playerId, argomento)) {
                is ApiResult.Error ->
                    _spogliatoio.value = _spogliatoio.value.copy(avviso = esito.message, inCorso = false)

                is ApiResult.Ok -> {
                    val aggiornato = ConversationRepository.load(club.id)
                    _spogliatoio.value = _spogliatoio.value.copy(
                        spogliatoio = (aggiornato as? ApiResult.Ok)?.value
                            ?: _spogliatoio.value.spogliatoio,
                        conPlayerId = playerId,
                        rispostaUltima = null,
                        deltaUltimo = 0,
                        inCorso = false,
                        letto = true,
                    )
                }
            }
        }
    }

    fun chiudiColloquio() {
        _spogliatoio.value = _spogliatoio.value.copy(
            conPlayerId = null,
            rispostaUltima = null,
            deltaUltimo = 0,
        )
    }

    /**
     * Parla con un giocatore.
     *
     * ## L'argomento non lo sceglie piu' il telefono
     *
     * Prima lo ricavava qui, da una soglia sul morale: parlavi, il morale saliva, la soglia
     * cambiava e compariva l'argomento successivo — quattro colloqui di fila con lo stesso
     * giocatore, +5 ogni volta. Adesso l'argomento e' quello della riga che qualcuno ha
     * aperto per un motivo, e rispondere la chiude. Senza riga non si parla.
     *
     * ## Il morale e la promessa restano due scritture
     *
     * La risposta chiude il colloquio e paga il morale in una transazione sola. La promessa
     * viene dopo, separata: la reazione del giocatore e' gia' avvenuta e un errore nel
     * registrare il debito non deve cancellarla.
     */
    fun parla(playerId: Long, option: ConversationOption) {
        val dentro = statoCorrente() ?: return
        val club = dentro.lega.myClub ?: return
        val player = dentro.lega.squadOf(club.id).firstOrNull { it.id.value == playerId } ?: return

        val colloquio = _spogliatoio.value.spogliatoio.apertoPer(playerId) ?: run {
            _spogliatoio.value = _spogliatoio.value.copy(
                avviso = "Questo discorso non e' piu' aperto.",
            )
            return
        }

        val esito = ConversationEngine.resolve(
            player = player,
            topic = colloquio.topic,
            option = option,
            today = MatchDay(dentro.lega.league.currentMatchDay),
            rules = dentro.lega.league.config.rules,
            spontanea = colloquio.spontaneous,
        )

        viewModelScope.launch {
            val salvato = ConversationRepository.rispondi(
                conversationId = colloquio.id,
                tone = option.tone.name,
                moraleDelta = esito.moraleDelta,
            )

            when (salvato) {
                is ApiResult.Error ->
                    _spogliatoio.value = _spogliatoio.value.copy(
                        rispostaUltima = salvato.message,
                        deltaUltimo = -1,
                    )

                is ApiResult.Ok -> {
                    // La promessa si salva **dopo** il morale e separatamente: la reazione
                    // del giocatore e' gia' avvenuta, e un errore qui non deve cancellarla.
                    // In una variabile locale: esito.promise viene da un altro modulo e
                    // Kotlin non puo' garantirne la stabilita' fra due letture.
                    val promessa = esito.promise
                    val debito = promessa?.let { p ->
                        PromiseRepository.make(
                            playerId = playerId,
                            type = p.type.name,
                            madeOn = p.madeOn.value,
                            deadline = p.deadline.value,
                            target = p.target,
                        )
                    }

                    val coda = when {
                        debito is ApiResult.Error -> "  (la promessa non e' stata registrata: ${debito.message})"
                        promessa != null -> "  Promessa presa: ${promessa.describe()}"
                        else -> ""
                    }

                    _spogliatoio.value = _spogliatoio.value.copy(
                        rispostaUltima = "“${esito.reply}”  ${segno(esito.moraleDelta)} morale$coda",
                        deltaUltimo = esito.moraleDelta,
                    )
                    // L'elenco dei discorsi cambia — quello appena chiuso sparisce — e la
                    // rosa pure, perche' il morale e' cambiato. Due letture, non una: la
                    // seconda ricarica tutta la lega ed e' quella lenta.
                    caricaSpogliatoio()
                    Session.leagueId?.let { carica(it) }
                }
            }
        }
    }

    private fun segno(delta: Int) = if (delta >= 0) "+$delta" else "$delta"

    // ---------------------------------------------------------------------- primavera

    /**
     * Manda un giovane in Primavera, o lo promuove in prima squadra.
     *
     * Le regole — l'eta' massima, il minimo di prima squadra, il massimo tornando su — le
     * fa rispettare il database, e il rifiuto arriva come una frase da mostrare: "Ha 24
     * anni: in Primavera si sta fino a 21" e' una risposta di gioco, non un guasto.
     */
    fun spostaSquadra(row: PlayerRow) {
        val dentro = statoCorrente() ?: return
        val destinazione = if (row.isYouth) "prima" else "primavera"

        viewModelScope.launch {
            when (val esito = SquadRepository.move(row.player.id.value, destinazione)) {
                is ApiResult.Error ->
                    _state.value = statoCorrente()?.copy(errore = esito.message) ?: return@launch

                is ApiResult.Ok -> {
                    val dove = if (destinazione == "primavera") "in Primavera" else "in prima squadra"
                    Session.leagueId?.let { carica(it, avviso = "${row.player.shortName} $dove.") }
                }
            }
        }
    }

    // --------------------------------------------------------------------- divisioni

    private val _divisioni = MutableStateFlow(DivisionsAdmin())
    val divisioni: StateFlow<DivisionsAdmin> = _divisioni

    /**
     * Assegna i club alle divisioni per la prima volta.
     *
     * L'ordine di partenza e' quello di **forza attuale**, cioe' quanto vale la rosa: e' il
     * meglio che si possa fare prima che si sia giocata una partita. La serpentina di
     * [SeasonEnd.split] poi lo distribuisce, cosi' nessuna divisione nasce gia' decisa.
     */
    fun assegnaDivisioni() {
        val dentro = statoCorrente() ?: return
        val config = dentro.lega.league.config.divisions
        if (!config.enabled) return

        viewModelScope.launch {
            _divisioni.value = _divisioni.value.copy(busy = "Assegno le divisioni…", errore = null)

            val perForza = dentro.lega.clubs
                .sortedByDescending { club ->
                    dentro.lega.squadOf(club.id).sumOf { it.overall.toLong() }
                }
                .map { ClubId(it.id) }

            val livelli = SeasonEnd.split(perForza, config.count)
            applicaLivelli(dentro.lega.league.id, livelli, "Divisioni assegnate.")
        }
    }

    /**
     * Chiude la stagione: promuove, retrocede, e riscrive la scala.
     *
     * ## Perche' e' un pulsante e non qualcosa che succede da solo
     *
     * Il gioco non ha un concetto di "stagione": le competizioni le crea l'admin quando
     * vuole, quante ne vuole, con le date che vuole. Non esiste da nessuna parte un momento
     * in cui si possa dire che la stagione e' finita, e inventarlo — l'ultima partita
     * dell'ultima competizione? — vorrebbe dire indovinare al posto di chi gioca, e
     * sbagliare la volta in cui l'admin voleva aggiungere una coppa.
     *
     * Chiedendolo si toglie l'ambiguita' invece di nasconderla, e chi preme sa cosa sta
     * facendo perche' la scaletta di cosa succedera' e' scritta nella stessa schermata.
     */
    fun chiudiStagione() {
        val dentro = statoCorrente() ?: return
        val config = dentro.lega.league.config.divisions
        if (!config.enabled) return
        val leagueId = dentro.lega.league.id

        viewModelScope.launch {
            _divisioni.value = _divisioni.value.copy(busy = "Chiudo la stagione…", errore = null)

            val classifiche = classifichePerDivisione(leagueId, dentro)
            if (classifiche == null) {
                _divisioni.value = _divisioni.value.copy(
                    busy = null,
                    errore = "Non riesco a leggere le classifiche: serve almeno una competizione.",
                )
                return@launch
            }

            val divisioni = (1..config.count).map { livello ->
                Division(
                    level = livello,
                    name = config.nameOf(livello),
                    clubs = dentro.lega.clubs
                        .filter { it.divisionLevel == livello }
                        .map { ClubId(it.id) },
                )
            }

            val esiti = SeasonEnd.settle(divisioni, classifiche, DivisionRules.of(config))
            // Chi e' in bilico resta dov'e': gli spareggi non si sono ancora giocati, e
            // muoverlo adesso vorrebbe dire promuovere una squadra che potrebbe perderli.
            val livelli = SeasonEnd.apply(esiti)

            val spareggi = creaSpareggi(leagueId, dentro, esiti, config)
            applicaLivelli(leagueId, livelli, riassunto(esiti) + spareggi)
        }
    }

    /**
     * Le classifiche vere, una per divisione.
     *
     * Si leggono dalle competizioni esistenti e si calcolano con [Standings], lo stesso
     * codice della schermata Classifica: due modi di ordinare la stessa tabella
     * produrrebbero un retrocesso diverso da quello mostrato.
     */
    private suspend fun classifichePerDivisione(
        leagueId: Long,
        dentro: AppState.Dentro,
    ): Map<Int, List<ClubId>>? {
        val competizioni = when (val esito = CompetitionRepository.list(leagueId)) {
            is ApiResult.Error -> return null
            is ApiResult.Ok -> esito.value
        }
        if (competizioni.isEmpty()) return null

        val perDivisione = mutableMapOf<Int, List<ClubId>>()
        for (competizione in competizioni) {
            val vista = caricaTabella(leagueId, competizione) ?: continue
            if (vista.rows.isEmpty()) continue

            // La divisione di una competizione e' quella dei suoi partecipanti: l'admin ne
            // crea una per divisione, e chiedergli di dichiararlo di nuovo sarebbe chiedere
            // due volte la stessa cosa.
            val livello = vista.rows
                .mapNotNull { riga ->
                    dentro.lega.clubs.firstOrNull { it.id == riga.club.value }?.divisionLevel
                }
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key
                ?: continue

            perDivisione[livello] = vista.rows.map { it.club }
        }
        return perDivisione.ifEmpty { null }
    }

    private suspend fun applicaLivelli(
        leagueId: Long,
        livelli: Map<ClubId, Int>,
        avviso: String,
    ) {
        when (val esito = DivisionRepository.assign(leagueId, livelli)) {
            is ApiResult.Error ->
                _divisioni.value = _divisioni.value.copy(busy = null, errore = esito.message)

            is ApiResult.Ok -> {
                _divisioni.value = DivisionsAdmin(avviso = avviso)
                carica(leagueId, avviso = avviso)
            }
        }
    }

    /**
     * Crea le partite di spareggio, invece di lasciarle da fare a mano.
     *
     * ## Perche' vanno generate qui e non chieste all'admin
     *
     * Chi sale ai playoff lo decide la classifica, gli accoppiamenti li decide il
     * regolamento — primo contro ultimo — e le date sono quelle subito dopo la stagione
     * regolare. Non resta nessuna scelta da fare, e chiedere all'admin di ricreare a mano
     * un tabellone che il gioco conosce gia' significa solo dargli l'occasione di
     * sbagliarlo: un accoppiamento diverso, una squadra dimenticata, e lo spareggio non
     * corrisponde piu' alla classifica.
     *
     * Una competizione per divisione e per tipo, perche' sono tornei separati: i playoff
     * della Serie B non c'entrano niente con i playout della Serie A, e mescolarli in un
     * tabellone solo produrrebbe accoppiamenti fra squadre di divisioni diverse.
     */
    private suspend fun creaSpareggi(
        leagueId: Long,
        dentro: AppState.Dentro,
        esiti: List<ClubFate>,
        config: dev.mfoot.core.config.DivisionsConfig,
    ): String {
        val perLivello = esiti.groupBy { it.level }
        var creati = 0

        for ((livello, fates) in perLivello) {
            listOf(
                SeasonOutcome.PLAYOFF to "Playoff ${config.nameOf(livello)}",
                SeasonOutcome.PLAYOUT to "Playout ${config.nameOf(livello)}",
            ).forEach { (esito, nome) ->
                val qualificate = fates
                    .filter { it.outcome == esito }
                    .sortedBy { it.position }
                    .map { it.club }
                if (qualificate.size < 2) return@forEach

                // Gli accoppiamenti li conosce il regolamento; qui serve solo l'elenco
                // ordinato, che il generatore di tabelloni riaccoppia allo stesso modo.
                val partecipanti = qualificate.map { it.value }
                val calendario = dentro.lega.league.config.calendar.copy(
                    startDate = LocalDate.now(),
                    endDate = LocalDate.now().plusDays(GIORNI_SPAREGGIO),
                )

                val schedule = CompetitionRepository.preview(
                    participants = partecipanti,
                    type = CompetitionType.ELIMINAZIONE_DIRETTA,
                    doubleRound = config.twoLeggedPlayoffs,
                    calendar = calendario,
                    config = dentro.lega.league.config,
                    seed = dentro.lega.league.config.setup.worldSeed + livello,
                )

                val creata = CompetitionRepository.create(
                    leagueId = leagueId,
                    name = nome,
                    type = CompetitionType.ELIMINAZIONE_DIRETTA,
                    doubleRound = config.twoLeggedPlayoffs,
                    participants = partecipanti,
                    calendar = calendario,
                    schedule = schedule,
                )
                if (creata is ApiResult.Ok) creati++
            }
        }

        return if (creati == 0) "" else " Creati $creati tabelloni di spareggio."
    }

    private fun riassunto(esiti: List<ClubFate>): String {
        val promossi = esiti.count {
            it.outcome == SeasonOutcome.PROMOSSO || (it.outcome == SeasonOutcome.CAMPIONE && it.level > 1)
        }
        val retrocessi = esiti.count { it.outcome == SeasonOutcome.RETROCESSO }
        return "Stagione chiusa: $promossi promosse, $retrocessi retrocesse."
    }

    fun chiudiAvvisoDivisioni() {
        _divisioni.value = _divisioni.value.copy(avviso = null, errore = null)
    }

    // ------------------------------------------------------------------------ scambi

    private val _trades = MutableStateFlow(TradesState())
    val trades: StateFlow<TradesState> = _trades

    fun caricaScambi(forza: Boolean = false) {
        val dentro = statoCorrente() ?: return
        if (_trades.value.letto && !forza) return

        viewModelScope.launch {
            when (val esito = TradeRepository.list(dentro.lega.league.id)) {
                is ApiResult.Error ->
                    _trades.value = _trades.value.copy(letto = true, errore = esito.message)
                is ApiResult.Ok ->
                    _trades.value = _trades.value.copy(
                        trades = esito.value, letto = true, errore = null,
                    )
            }
        }
    }

    fun nuovoScambio(withClub: Long) {
        _trades.value = _trades.value.copy(
            bozza = TradeDraft(withClub = withClub), avviso = null, errore = null,
        )
    }

    fun modificaScambio(bozza: TradeDraft) {
        _trades.value = _trades.value.copy(bozza = bozza, errore = null)
    }

    fun annullaScambio() {
        _trades.value = _trades.value.copy(bozza = null, errore = null)
    }

    fun inviaScambio() {
        val dentro = statoCorrente() ?: return
        val mio = dentro.lega.myClub ?: return
        val bozza = _trades.value.bozza ?: return
        if (bozza.isEmpty) return

        viewModelScope.launch {
            _trades.value = _trades.value.copy(busy = "Mando la proposta…", errore = null)

            val esito = when (bozza.kind) {
                TradeKind.SCAMBIO -> TradeRepository.propose(
                    fromClub = mio.id,
                    toClub = bozza.withClub,
                    offered = bozza.offered.toList(),
                    wanted = bozza.wanted.toList(),
                    cash = bozza.cash,
                    message = bozza.message,
                )

                TradeKind.PRESTITO -> DealRepository.proposeLoan(
                    fromClub = mio.id,
                    toClub = bozza.withClub,
                    playerId = bozza.offered.first(),
                    matchDays = bozza.loanMatchDays,
                    fee = bozza.loanFee,
                    wagePaidByBorrower = bozza.wagePaidByBorrower,
                    canPlayAgainstOwner = bozza.canPlayAgainstOwner,
                    message = bozza.message,
                )

                TradeKind.AMICHEVOLE -> DealRepository.proposeFriendly(
                    fromClub = mio.id,
                    toClub = bozza.withClub,
                    // L'ora scelta e' ora di lega: qui diventa il momento vero, che e'
                    // l'unica cosa che il database deve conoscere.
                    kickoff = bozza.friendlyAt!!
                        .atZone(dentro.lega.league.config.calendar.timeZone).toInstant(),
                    message = bozza.message,
                )
            }

            _trades.value = when (esito) {
                is ApiResult.Error -> _trades.value.copy(busy = null, errore = esito.message)
                is ApiResult.Ok -> _trades.value.copy(busy = null, bozza = null, avviso = "Proposta mandata.")
            }
            if (esito is ApiResult.Ok) caricaScambi(forza = true)
        }
    }

    /**
     * Accetta, rifiuta o ritira.
     *
     * Dopo un'accettazione si **ricarica la lega**, non solo l'elenco degli scambi: sono
     * cambiate due rose e due conti in banca, e lasciare a schermo i numeri di prima
     * significherebbe mostrare una squadra che non esiste piu'.
     */
    fun rispondiScambio(tradeId: Long, accetta: Boolean) {
        // Il tipo decide quale funzione risponde. `respond_trade` sposta giocatori e denaro
        // in una transazione ed e' la piu' delicata del sistema: prestiti e amichevoli
        // hanno una funzione loro invece di aggiungerle due rami dentro.
        val tipo = _trades.value.trades.firstOrNull { it.id == tradeId }?.kind ?: TradeKind.SCAMBIO

        viewModelScope.launch {
            _trades.value = _trades.value.copy(
                busy = if (accetta) "Accetto…" else "Rifiuto…", errore = null,
            )

            val risposta = if (tipo == TradeKind.SCAMBIO) {
                TradeRepository.respond(tradeId, accetta)
            } else {
                DealRepository.respond(tradeId, accetta)
            }

            when (val esito = risposta) {
                is ApiResult.Error ->
                    _trades.value = _trades.value.copy(busy = null, errore = esito.message)

                is ApiResult.Ok -> {
                    _trades.value = _trades.value.copy(
                        busy = null,
                        avviso = if (accetta) "Scambio fatto." else "Proposta rifiutata.",
                    )
                    caricaScambi(forza = true)
                    if (accetta) Session.leagueId?.let { carica(it, avviso = "Scambio fatto.") }
                }
            }
        }
    }

    fun ritiraScambio(tradeId: Long) {
        viewModelScope.launch {
            when (val esito = TradeRepository.withdraw(tradeId)) {
                is ApiResult.Error -> _trades.value = _trades.value.copy(errore = esito.message)
                is ApiResult.Ok -> {
                    _trades.value = _trades.value.copy(avviso = "Proposta ritirata.")
                    caricaScambi(forza = true)
                }
            }
        }
    }

    fun chiudiAvvisoScambi() {
        _trades.value = _trades.value.copy(avviso = null, errore = null)
    }

    // -------------------------------------------------------------------- formazione

    /**
     * La formazione che si sta componendo.
     *
     * Sta accanto allo stato della lega e non dentro, per la stessa ragione del regolamento:
     * comporre un undici e' un lavoro lungo e la lega si ricarica da sola quando arriva un
     * risultato o si chiude un'asta. Se la bozza vivesse dentro lo snapshot, un
     * aggiornamento in arrivo mentre si sposta un terzino cancellerebbe il lavoro fatto.
     */
    private val _lineup = MutableStateFlow(LineupEdit())
    val lineupEdit: StateFlow<LineupEdit> = _lineup

    fun modificaFormazione(nuova: LineupEdit) {
        _lineup.value = nuova
    }

    /**
     * Carica la formazione salvata e la riempie con i giocatori veri della rosa.
     *
     * Gli identificativi salvati vengono incrociati con la rosa di adesso, non con quella
     * di quando si e' salvato: un titolare venduto tre giorni fa semplicemente non compare,
     * e la sua casella torna vuota. E' la stessa cosa che fa il server quando gioca la
     * partita, quindi lo schermo dice il vero.
     */
    private fun caricaFormazione(snapshot: LeagueSnapshot) {
        val club = snapshot.myClub ?: run {
            _lineup.value = LineupEdit()
            return
        }
        val squad = snapshot.squadOf(club.id)
        val today = MatchDay(snapshot.league.currentMatchDay)

        viewModelScope.launch {
            val salvata = when (val esito = LineupRepository.read(club.id)) {
                is ApiResult.Error -> null
                is ApiResult.Ok -> esito.value
            }

            // Una riga con zero titolari **non e' una formazione**: e' quella che
            // `create_club` inserisce vuota alla fondazione. Trattarla come una scelta
            // mostrerebbe un campo deserto al primo ingresso, e chi lo vede pensa che
            // senza schierare a mano non si giochi — mentre il server schiera da solo.
            val composta = salvata?.takeIf { it.eleven.any { id -> id != null } }

            val base = if (composta == null) {
                LineupEdit(formation = AutoLineup.bestFormation(squad, today))
                    .completa(squad, today)
                    // Le tattiche salvate valgono anche senza titolari: chi ha scelto
                    // "ultra difensivo" e non ha ancora schierato nessuno non deve
                    // ritrovarsi equilibrato.
                    .copy(tactics = salvata?.tactics ?: Tactics.DEFAULT)
            } else {
                val byId = squad.associateBy { it.id.value }
                LineupEdit(
                    formation = composta.formation,
                    eleven = composta.eleven.map { id -> id?.let { byId[it] } },
                    tactics = composta.tactics,
                    captainId = composta.captainId,
                    penaltyTakerId = composta.penaltyTakerId,
                ).conPanchina(squad, today)
            }

            // La copia di riferimento e' quella **appena costruita**: cosi' il pulsante di
            // salvataggio resta spento fino a una modifica vera. Se fosse quella salvata sul
            // server, aprire la schermata la mostrerebbe subito come da salvare ogni volta
            // che un titolare non c'e' piu'.
            _lineup.value = base.copy(salvata = base.snapshot)
        }
    }

    fun salvaFormazione() {
        val dentro = statoCorrente() ?: return
        val club = dentro.lega.myClub ?: return
        val edit = _lineup.value
        if (!edit.dirty || edit.busy != null) return

        viewModelScope.launch {
            _lineup.value = edit.copy(busy = "Salvo…", errore = null)

            val esito = LineupRepository.save(
                leagueId = dentro.lega.league.id,
                clubId = club.id,
                lineup = SavedLineup(
                    formation = edit.formation,
                    eleven = edit.eleven.map { it?.id?.value },
                    bench = edit.bench.map { it.id.value },
                    tactics = edit.tactics,
                    captainId = edit.captainId,
                    penaltyTakerId = edit.penaltyTakerId,
                ),
            )

            _lineup.value = when (esito) {
                is ApiResult.Error -> edit.copy(busy = null, errore = esito.message)
                // Da qui in poi la copia di riferimento e' questa: il pulsante si spegne e
                // si riaccende solo alla modifica successiva.
                is ApiResult.Ok -> edit.copy(busy = null, salvata = edit.snapshot)
            }
        }
    }

    // ---------------------------------------------------------------- rotte e guscio

    /**
     * Va a una schermata.
     *
     * Le cinque voci della barra in basso **azzerano la pila** invece di impilarsi: sono
     * destinazioni, non passi di un percorso, e con venti tocchi sulla barra il tasto
     * indietro dovrebbe ripercorrere venti schermate per uscire.
     */
    fun vai(route: Route) {
        val dentro = statoCorrente() ?: return
        val nuova = if (route.isTab) {
            listOf(route)
        } else if (dentro.route == route) {
            dentro.stack
        } else {
            dentro.stack + route
        }
        _state.value = dentro.copy(stack = nuova, drawerOpen = false, errore = null)
    }

    /** Torna indietro. Restituisce false se non c'era dove tornare: allora l'app si chiude. */
    fun indietro(): Boolean {
        val dentro = statoCorrente() ?: return false
        return when {
            dentro.drawerOpen -> {
                _state.value = dentro.copy(drawerOpen = false); true
            }
            dentro.bidding != null -> {
                _state.value = dentro.copy(bidding = null); true
            }
            dentro.stack.size > 1 -> {
                _state.value = dentro.copy(stack = dentro.stack.dropLast(1)); true
            }
            else -> false
        }
    }

    fun apriChiudiMenu() {
        val dentro = statoCorrente() ?: return
        _state.value = dentro.copy(drawerOpen = !dentro.drawerOpen)
    }

    // ---------------------------------------------------------------------- navigazione

    fun onQuery(text: String) = aggiornaBrowse { it.copy(query = text) }

    fun onFilter(filter: RoleFilter) = aggiornaBrowse { it.copy(filter = filter) }

    fun onScope(scope: ListScope) = aggiornaBrowse { it.copy(scope = scope) }

    fun select(row: PlayerRow?) = aggiornaBrowse { it.copy(selected = row) }

    fun chiudiAvviso() {
        val dentro = _state.value as? AppState.Dentro ?: return
        _state.value = dentro.copy(avviso = null)
    }

    // ------------------------------------------------------------------------- aiutanti

    private fun aggiornaBrowse(block: (BrowseState) -> BrowseState) {
        val dentro = _state.value as? AppState.Dentro ?: return
        _state.value = dentro.copy(browse = block(dentro.browse))
    }

    private fun aggiornaPorta(busy: String? = null, errore: String? = null) {
        val corrente = _state.value as? AppState.Porta ?: AppState.Porta()
        _state.value = corrente.copy(busy = busy, errore = errore)
    }

    /**
     * Un seed che non si ripete.
     *
     * L'orologio da solo non basta: due leghe create nello stesso millisecondo da due
     * telefoni diversi avrebbero lo stesso mondo. Il seed vero e' quello salvato con la
     * lega, e da li' in poi tutto e' riproducibile.
     */
    private fun nuovoSeed(): Long =
        System.currentTimeMillis() * 1_000_003L + Random.nextLong(1_000_003L)
}
