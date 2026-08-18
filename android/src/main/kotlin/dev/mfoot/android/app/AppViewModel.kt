package dev.mfoot.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mfoot.android.data.ApiResult
import dev.mfoot.android.data.AuctionRepository
import dev.mfoot.android.data.AuctionView
import dev.mfoot.android.data.ClubUpload
import dev.mfoot.android.data.CompetitionRepository
import dev.mfoot.android.data.LeagueRepository
import dev.mfoot.android.data.LeagueSnapshot
import dev.mfoot.android.data.LineupRepository
import dev.mfoot.android.data.SavedLineup
import dev.mfoot.android.data.Session
import dev.mfoot.android.data.Supabase
import dev.mfoot.android.data.TableRepository
import dev.mfoot.android.data.SupabaseApi
import dev.mfoot.android.data.WorldUpload
import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.match.AutoLineup
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.model.Attr
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
    fun creaLega(nome: String, codice: String, nickname: String, presetId: String = "sprint") {
        if (nome.isBlank() || codice.isBlank() || nickname.isBlank()) {
            aggiornaPorta(errore = "Servono nome della lega, codice e il tuo nickname.")
            return
        }

        viewModelScope.launch {
            aggiornaPorta(busy = "Genero il mondo…")

            val preset = ConfigPresets.byId(presetId) ?: ConfigPresets.all.first()
            val config = preset.build(16, 8, LocalDate.now()).let {
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
        viewModelScope.launch {
            _state.value = AppState.Caricamento("Leggo la lega…")

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
                    val rows = withContext(Dispatchers.Default) { righe(snapshot.value) }
                    val aste = AuctionRepository.openAuctions(leagueId)
                    _state.value = AppState.Dentro(
                        lega = snapshot.value,
                        rows = rows,
                        auctions = asteViste(aste, rows, snapshot.value),
                        browse = BrowseState(
                            // Chi ha gia' una rosa vuole vedere la sua rosa; chi non ce
                            // l'ha ancora vuole vedere cosa c'e' da prendere.
                            scope = if (snapshot.value.myClub != null) ListScope.MIA_ROSA
                            else ListScope.SVINCOLATI,
                        ),
                        avviso = avviso,
                    )
                    caricaFormazione(snapshot.value)
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
            val estimate = PotentialEstimator.publicEstimate(player, observerId)
            PlayerRow(
                player = player,
                estimate = estimate,
                hasUpside = PotentialEstimator.hasUpside(player),
                value = Valuation.estimatedValue(player, estimate, config),
                club = snapshot.clubOfPlayer[player.id.value]?.let(clubById::get),
            )
        }
    }

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
                    val prima = competizioni.value.firstOrNull()
                    val base = TableState(
                        competitions = competizioni.value,
                        selectedId = prima?.id,
                        clubs = dentro.lega.clubs,
                        myClubId = dentro.lega.myClub?.id,
                        tab = tab,
                    )
                    _state.value = AppState.Classifica(
                        if (prima == null) base else base.copy(view = caricaTabella(leagueId, prima))
                    )
                }
            }
        }
    }

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
                    aggiornaAste(
                        avviso = "${row.player.fullName} e' all'asta, base $prezzo. " +
                            "Chiunque puo' offrire, ${club.shortName} compreso.",
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
