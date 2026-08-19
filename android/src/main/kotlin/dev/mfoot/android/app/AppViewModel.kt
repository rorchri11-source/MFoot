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
import dev.mfoot.android.data.MatchRepository
import dev.mfoot.android.data.MyLeaguesRepository
import dev.mfoot.android.data.DivisionRepository
import dev.mfoot.android.data.LineupRepository
import dev.mfoot.android.data.ObjectiveRepository
import dev.mfoot.android.data.PlayerRepository
import dev.mfoot.android.data.PromiseRepository
import dev.mfoot.android.data.CounterRepository
import dev.mfoot.android.data.DealRepository
import dev.mfoot.android.data.TradeKind
import dev.mfoot.android.data.TradeRepository
import dev.mfoot.android.data.SavedLineup
import dev.mfoot.android.data.Scouted
import dev.mfoot.android.data.ScoutingRepository
import dev.mfoot.android.data.Session
import dev.mfoot.android.data.StaffRepository
import dev.mfoot.android.data.SquadRepository
import dev.mfoot.android.data.Supabase
import dev.mfoot.android.data.TableRepository
import dev.mfoot.android.data.SupabaseApi
import dev.mfoot.android.data.WorldUpload
import dev.mfoot.android.data.YouthRepository
import dev.mfoot.core.calendar.ClubFate
import dev.mfoot.core.calendar.LeagueCalendar
import dev.mfoot.core.calendar.Division
import dev.mfoot.core.calendar.DivisionAssignment
import dev.mfoot.core.calendar.ClubToPlace
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
import dev.mfoot.core.objectives.ClubSeason
import dev.mfoot.core.objectives.ClubStanding
import dev.mfoot.core.objectives.Objective
import dev.mfoot.core.objectives.ObjectiveBoard
import dev.mfoot.core.objectives.ObjectiveEngine
import dev.mfoot.core.objectives.ObjectiveStatus
import dev.mfoot.core.match.AutoLineup
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Money
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
        _state.value = corrente.copy(
            mode = mode,
            errore = null,
            // L'anteprima appartiene al codice che si stava scrivendo: cambiando schermata
            // resterebbe appesa e la volta dopo si vedrebbe il nome di una lega che non
            // c'entra piu' niente con quello che si sta digitando.
            anteprima = null,
            anteprimaVuota = false,
        )
    }

    /**
     * Guarda che lega apre un codice, **senza entrarci**.
     *
     * ## Perche' un passo in piu' prima di entrare
     *
     * Perche' e' il difetto che ha fatto giocare due amici in due leghe diverse convinti
     * di essere nella stessa. Digitavi un codice e ti ritrovavi dentro: l'app non diceva
     * mai in quale lega ti aveva portato. Un codice vecchio, o quello di un'altra prova, e
     * finivi in un mondo che sembrava il suo — con dentro perfino la sua squadra di
     * quando ci aveva provato lui — ma dove non succedeva niente.
     *
     * Il nome letto **prima** di premere toglie l'ambiguita' nell'unico momento in cui si
     * puo' ancora tornare indietro senza conseguenze.
     */
    fun sbircia(codice: String) {
        if (codice.isBlank()) return

        viewModelScope.launch {
            aggiornaPorta(busy = "Cerco la lega…")

            when (val esito = MyLeaguesRepository.peek(codice)) {
                is ApiResult.Error -> aggiornaPorta(errore = esito.message)
                is ApiResult.Ok -> {
                    val corrente = _state.value as? AppState.Porta ?: AppState.Porta()
                    _state.value = corrente.copy(
                        busy = null,
                        errore = null,
                        anteprima = esito.value,
                        anteprimaVuota = esito.value == null,
                    )
                }
            }
        }
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
                    // Divisioni e club padre arrivano gia' agganciati: li monta
                    // `LeagueRepository`, dove sta la lettura, e non piu' qui. Finche' il
                    // montaggio stava in questa riga, ogni altra rilettura dei club — per
                    // esempio quella dopo un'offerta all'asta — ne restituiva una versione
                    // a meta', e la Primavera spariva dall'app.
                    val lega = snapshot.value
                    ultimaLega = lega

                    // Lo staff serve gia al primo disegno: le aste sullo staff hanno
                    // bisogno del nome, e caricarlo solo aprendo la sua scheda le
                    // lasciava senza. Sono un centinaio di righe.
                    _staff.value = _staff.value.copy(tutti = StaffRepository.all(leagueId))

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
                        stack = dovEro ?: listOf(Route.Casa),
                        avviso = avviso,
                    )
                    caricaFormazione(lega)
                    // Ripulito a ogni ricarica: dopo che qualcuno fonda un club, un elenco
                    // partecipanti in memoria dalla volta prima lo mostrerebbe ancora senza.
                    _desk.value = DeskState()
                    // Stessa ragione: una competizione appena creata, o una giornata
                    // appena giocata, cambiano quello che la Casa deve dire.
                    _competizioni.value = CompetizioniMie()
                    _altrui.value = FormazioneAltrui()
                    _obiettivi.value = ObiettiviState()
                    contaLeghe()
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
                isYouth = snapshot.clubOfPlayer[player.id.value]
                    ?.let { id -> snapshot.clubs.firstOrNull { it.id == id }?.parentClubId != null }
                    ?: false,
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
     * La classifica, dentro il guscio.
     *
     * ## Perche non e piu una schermata a se
     *
     * Perche apriva a schermo pieno e portava via la barra in basso: dalla classifica non
     * si poteva andare da nessuna parte se non tornando indietro. E con la classifica come
     * scheda dentro "Lega", quel comportamento avrebbe reso il posto irraggiungibile — si
     * toccava Lega e ci si ritrovava fuori dal guscio.
     *
     * Adesso e uno stato accanto agli altri, come la scrivania e le trattative, e la
     * schermata la disegna il Router.
     */
    private val _tabella = MutableStateFlow(TableState(emptyList(), null))
    val tabella: StateFlow<TableState> = _tabella

    fun apriClassifica(tab: TableTab = TableTab.CLASSIFICA) {
        val dentro = _state.value as? AppState.Dentro ?: return
        val leagueId = dentro.lega.league.id

        viewModelScope.launch {

            when (val competizioni = CompetitionRepository.list(leagueId)) {
                is ApiResult.Error ->
                    _tabella.value = TableState(emptyList(), null, tab = tab, errore = competizioni.message)

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
                    _tabella.value =
                        if (prima == null) base else base.copy(view = caricaTabella(leagueId, prima))
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

    // ------------------------------------------------------------------------- partita

    /**
     * Apre una partita gia' giocata e la fa ripartire dal primo minuto.
     *
     * ## Perche' non si riproduce in tempo reale
     *
     * Perche' novanta minuti sono novanta minuti. La partita si e' gia' giocata mentre il
     * telefono era spento: quello che si vuole rivedere e' **come e' andata**, e sei
     * minuti di gioco al secondo la raccontano in un quarto d'ora senza saltare niente.
     * Chi ha fretta preme "salta alla fine" e legge le pagelle.
     */
    fun apriPartita(fixtureId: Long, homeName: String, awayName: String) {
        viewModelScope.launch {
            _state.value = AppState.Partita(
                MatchState(homeName = homeName, awayName = awayName, caricamento = true),
            )

            when (val esito = MatchRepository.load(fixtureId)) {
                is ApiResult.Error -> _state.value = AppState.Partita(
                    MatchState(
                        homeName = homeName,
                        awayName = awayName,
                        caricamento = false,
                        errore = esito.message,
                    ),
                )

                is ApiResult.Ok -> {
                    val conPagelle = esito.value.copy(ratings = MatchRepository.ratings(fixtureId))
                    _state.value = AppState.Partita(
                        MatchState(
                            partita = conPagelle,
                            homeName = homeName,
                            awayName = awayName,
                            caricamento = false,
                            inCorso = true,
                        ),
                    )
                    riproduci()
                }
            }
        }
    }

    /**
     * L'orologio della riproduzione.
     *
     * Un ciclo solo, che si ferma da solo quando la partita finisce o quando si esce dalla
     * schermata. Il controllo su `AppState.Partita` a ogni giro non e' pignoleria: senza,
     * chiudere la partita a meta' lascerebbe un ciclo acceso che continua a scrivere sullo
     * stato di una schermata che non esiste piu'.
     */
    private fun riproduci() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                val corrente = (_state.value as? AppState.Partita)?.partita ?: return@launch
                if (!corrente.inCorso || corrente.finita) return@launch

                _state.value = AppState.Partita(
                    corrente.copy(minuto = (corrente.minuto + corrente.velocita).coerceAtMost(90)),
                )
            }
        }
    }

    fun pausaPartita() {
        val corrente = (_state.value as? AppState.Partita)?.partita ?: return
        val ripresa = !corrente.inCorso
        _state.value = AppState.Partita(corrente.copy(inCorso = ripresa))
        if (ripresa) riproduci()
    }

    fun saltaAllaFine() {
        val corrente = (_state.value as? AppState.Partita)?.partita ?: return
        _state.value = AppState.Partita(corrente.copy(minuto = 90, inCorso = false))
    }

    fun chiudiPartita() = ricarica()

    /**
     * Il nome di un giocatore, per le pagelle.
     *
     * Le presenze portano solo l'identificativo: i giocatori veri li ha gia' in mano lo
     * stato della lega, e mandarli anche da li' vorrebbe dire una seconda copia della rosa
     * che invecchia per conto suo. Se non si trova — un giocatore uscito dalla lega dopo
     * quella partita — resta il numero, che e' meglio di una riga vuota.
     */
    /** Il nome di un membro dello staff, per le aste che lo riguardano. */
    fun nomeStaff(id: Long): String =
        _staff.value.tutti.firstOrNull { it.id == id }
            ?.let { "${it.shortName} · ${it.roleLabel}" }
            ?: "Staff #$id"

    fun nomeGiocatore(id: Long): String =
        ultimaLega?.players?.firstOrNull { it.id.value == id }?.shortName ?: "#$id"

    /**
     * L'ultima lega letta.
     *
     * Serve alle schermate che escono da [AppState.Dentro] — il replay di una partita, per
     * esempio — dove i giocatori servono ancora ma lo stato corrente non li porta piu'.
     */
    private var ultimaLega: LeagueSnapshot? = null

    /**
     * Le competizioni che non fanno classifica.
     *
     * Si riempie leggendo le competizioni, e resta vuota finche' la migrazione delle
     * amichevoli non e' applicata: senza, un'amichevole si colora come una partita
     * qualsiasi, che e' un difetto piccolo e non un guasto.
     */
    private var competizioniAmichevoli: Set<Long> = emptySet()

    fun cambiaSchedaTabella(tab: TableTab) {
        _tabella.value = _tabella.value.copy(tab = tab)
    }

    fun scegliCompetizione(id: Long) {
        val schermata = _tabella.value
        val competizione = schermata.competitions.firstOrNull { it.id == id } ?: return
        val leagueId = Session.leagueId ?: return

        viewModelScope.launch {
            _tabella.value =
                schermata.copy(selectedId = id, view = caricaTabella(leagueId, competizione))
        }
    }

    private suspend fun caricaTabella(
        leagueId: Long,
        competizione: dev.mfoot.android.data.CompetitionInfo,
    ) = (TableRepository.load(leagueId, competizione) as? ApiResult.Ok)?.value


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
        // Lo staff si legge una volta al caricamento della lega: sono un centinaio di
        // righe, e senza, le aste sullo staff mostravano «Obiettivo #7».
        val staffById = _staff.value.tutti.associateBy { it.id }

        val mio = snapshot.myClub?.id

        return auctions.map { auction ->
            AuctionRow(
                auction = auction,
                player = if (auction.targetType == "player") playerById[auction.targetId] else null,
                staff = if (auction.targetType == "staff") staffById[auction.targetId] else null,
                leaderName = auction.leaderClubId?.let { clubById[it]?.shortName },
                starterName = clubById[auction.startedBy]?.shortName,
                startedByMe = mio != null && auction.startedBy == mio,
            )
        }
    }

    /** Cambia quali aste si guardano. Nessuna rilettura: il filtro lavora su cio' che c'e'. */
    fun filtraAste(filtro: AuctionFilter) {
        val dentro = statoCorrente() ?: return
        _state.value = dentro.copy(auctionFilter = filtro)
    }

    /**
     * Apre il foglio dell'offerta e va a prendere la cronologia.
     *
     * La cronologia arriva **dopo**: il foglio si apre subito col prezzo e il tempo, che
     * sono gia' in memoria, e l'elenco di chi ha offerto compare un istante dopo. Farlo
     * aspettare vorrebbe dire un tocco che non fa niente per mezzo secondo.
     */
    fun apriOfferta(row: AuctionRow?) {
        val dentro = _state.value as? AppState.Dentro ?: return
        _state.value = dentro.copy(bidding = row, biddingHistory = emptyList(), errore = null)
        if (row == null) return

        viewModelScope.launch {
            val storia = AuctionRepository.history(row.auction.id)
            val corrente = statoCorrente() ?: return@launch
            // Solo se si sta ancora guardando quella: fra la richiesta e la risposta si
            // puo' aver chiuso il foglio o aperto un'altra asta, e attaccare la
            // cronologia sbagliata e' peggio che non averne nessuna.
            if (corrente.bidding?.auction?.id == row.auction.id) {
                _state.value = corrente.copy(biddingHistory = storia)
            }
        }
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

    // ---------------------------------------------------------------------- obiettivi

    private val _obiettivi = MutableStateFlow(ObiettiviState())
    val obiettivi: StateFlow<ObiettiviState> = _obiettivi

    fun caricaObiettivi(forza: Boolean = false) {
        val dentro = statoCorrente() ?: return
        if (_obiettivi.value.letto && !forza) return

        viewModelScope.launch {
            val righe = ObjectiveRepository.all(dentro.lega.league.id)
            _obiettivi.value = _obiettivi.value.copy(righe = righe, letto = true, busy = null)
        }
    }

    /**
     * Assegna gli obiettivi della stagione a **tutti** i club della lega.
     *
     * ## Perche' a tutti insieme e non uno per volta
     *
     * Perche' una lega in cui meta' squadre hanno obiettivi e meta' no e' peggio di una in
     * cui non ne ha nessuno: le prime giocano per un premio, le seconde no, e la stagione
     * non e' piu' la stessa per tutti.
     *
     * ## Perche' li calcola il telefono e non il database
     *
     * Perche' la regola vive in `core`, con i suoi test, ed e' la stessa che spiega
     * l'obiettivo nella schermata. Riscriverla in SQL vorrebbe dire due regolamenti che si
     * separano al primo ritocco, e un premio calcolato in modo diverso da come e'
     * annunciato. Il database non si fida comunque: controlla che i club siano di questa
     * lega e rifiuta il resto.
     */
    fun assegnaObiettivi() {
        val dentro = statoCorrente() ?: return
        val config = dentro.lega.league.config
        if (!config.objectives.enabled) {
            _obiettivi.value = _obiettivi.value.copy(
                errore = "Gli obiettivi sono spenti nel regolamento di questa lega.",
            )
            return
        }

        val stagione = _obiettivi.value.stagione + 1

        viewModelScope.launch {
            _obiettivi.value = _obiettivi.value.copy(busy = "Assegno gli obiettivi…", errore = null)

            val coppe = when (val esito = CompetitionRepository.list(dentro.lega.league.id)) {
                is ApiResult.Error -> emptyList()
                is ApiResult.Ok -> esito.value.filter { it.type == CompetitionType.ELIMINAZIONE_DIRETTA }
            }

            val items = ArrayList<Pair<Long, Objective>>(dentro.lega.clubs.size * 3)

            // Le Primavere non ricevono obiettivi propri: non hanno portafoglio, e chiedere
            // a una seconda squadra di vincere il suo campionato vorrebbe dire pagare due
            // volte la stessa persona per la stessa stagione.
            val prime = dentro.lega.clubs.filter { it.parentClubId == null }

            prime.groupBy { it.divisionLevel }.forEach { (livello, club) ->
                // La forza e' la somma degli overall della rosa: e' la stessa misura con
                // cui si compongono le divisioni, quindi le due cose non si contraddicono.
                val perForza = club.sortedByDescending { c ->
                    dentro.lega.squadOf(c.id).sumOf { it.overall.toLong() }
                }

                perForza.forEachIndexed { indice, c ->
                    val rosa = dentro.lega.squadOf(c.id)
                    val standing = ClubStanding(
                        strengthRank = indice + 1,
                        teamsInDivision = perForza.size,
                        divisionLevel = livello,
                        divisionCount = config.divisions.count,
                        customOverall = c.customPlayerId
                            ?.let { id -> rosa.firstOrNull { it.id.value == id }?.overall }
                            ?: 0,
                        bestOverall = rosa.maxOfOrNull { it.overall } ?: 0,
                        hasCup = coppe.any { c.id in it.participants },
                        hasYouth = dentro.lega.clubs.any { it.parentClubId == c.id },
                    )

                    ObjectiveBoard.forClub(standing, config).forEach { items += c.id to it }
                }
            }

            when (val esito = ObjectiveRepository.assign(dentro.lega.league.id, stagione, items)) {
                is ApiResult.Error ->
                    _obiettivi.value = _obiettivi.value.copy(busy = null, errore = esito.message)
                is ApiResult.Ok -> {
                    _obiettivi.value = _obiettivi.value.copy(
                        busy = null,
                        avviso = "Stagione $stagione: ${esito.value} obiettivi assegnati a " +
                            "${prime.size} squadre.",
                    )
                    caricaObiettivi(forza = true)
                }
            }
        }
    }

    /**
     * Giudica gli obiettivi aperti con quello che e' successo, e paga chi ce l'ha fatta.
     *
     * ## Le stagioni precedenti non si rileggono, e non serve
     *
     * Un obiettivo pluriennale — «non retrocedere per due stagioni» — richiederebbe di
     * conoscere anche gli anni prima. Non c'e' bisogno di conservarli: se in una stagione
     * precedente quel club fosse retrocesso, l'obiettivo sarebbe **gia' stato chiuso come
     * fallito** alla chiusura di quella stagione. Che sia ancora aperto e' esso stesso la
     * prova che le stagioni passate sono andate bene, e le si rappresenta cosi'.
     */
    private suspend fun chiudiObiettivi(
        dentro: AppState.Dentro,
        esiti: List<ClubFate>,
    ): String {
        // Si rileggono adesso invece di fidarsi di cio' che c'e' in memoria: chiudere la
        // stagione senza aver mai aperto la schermata degli obiettivi troverebbe un elenco
        // vuoto e non pagherebbe niente **in silenzio**, che e' il modo peggiore in cui una
        // funzione del genere puo' sbagliare.
        val righe = ObjectiveRepository.all(dentro.lega.league.id)
        _obiettivi.value = _obiettivi.value.copy(righe = righe, letto = true)

        val stato = _obiettivi.value
        val aperti = stato.aperti
        if (aperti.isEmpty()) return ""

        val perClub = esiti.associateBy { it.club.value }
        val quanteInDivisione = esiti.groupingBy { it.level }.eachCount()
        val vincitrici = coppeVinte(dentro)

        var pagati = 0
        var quanti = 0

        aperti.forEach { riga ->
            val club = dentro.lega.clubs.firstOrNull { it.id == riga.clubId } ?: return@forEach
            // La posizione arriva dagli esiti e non da un secondo conteggio: e' la stessa
            // che decide promozioni e retrocessioni, quindi un obiettivo non puo' dire
            // «quarto» mentre la classifica dice «quinto».
            val esito = perClub[club.id] ?: return@forEach

            val rosa = dentro.lega.squadOf(club.id)
            val stagione = ClubSeason(
                position = esito.position,
                teamsInDivision = quanteInDivisione[esito.level] ?: esito.position,
                divisionLevel = esito.level,
                promoted = esito.outcome == SeasonOutcome.PROMOSSO ||
                    esito.outcome == SeasonOutcome.CAMPIONE && esito.level > 1,
                relegated = esito.outcome == SeasonOutcome.RETROCESSO,
                cupWon = club.id in vincitrici,
                bestOverall = rosa.maxOfOrNull { it.overall } ?: 0,
                customOverall = club.customPlayerId
                    ?.let { id -> rosa.firstOrNull { it.id.value == id }?.overall }
                    ?: 0,
                youthPlayed = giovaniInCampo(dentro, club),
                finished = true,
            )

            // Le stagioni gia' passate senza far fallire l'obiettivo: vedi sopra.
            val passate = (stato.stagione - riga.season).coerceAtLeast(0)
            val storia = List(passate) {
                ClubSeason(
                    position = esito.position,
                    teamsInDivision = quanteInDivisione[esito.level] ?: esito.position,
                    divisionLevel = esito.level,
                    finished = true,
                )
            } + stagione

            val verdetto = ObjectiveEngine.status(riga.objective, storia)
            if (verdetto == ObjectiveStatus.IN_CORSO) return@forEach

            when (val esito = ObjectiveRepository.settle(riga.id, verdetto)) {
                is ApiResult.Error -> Unit
                is ApiResult.Ok -> {
                    quanti++
                    pagati += esito.value
                }
            }
        }

        caricaObiettivi(forza = true)
        return if (quanti == 0) "" else " $quanti obiettivi chiusi, ${Money(pagati).format()} di premi."
    }

    /**
     * Quanti ragazzi hanno davvero giocato in prima squadra.
     *
     * ## Cosa conta come «ragazzo»
     *
     * L'eta' massima della Primavera scritta nel regolamento — la stessa oltre la quale non
     * si puo' piu' scendere nella seconda squadra. Usare quel numero e non uno inventato
     * qui significa che l'obiettivo si sposta da solo se l'admin cambia il limite, invece
     * di parlare di un'eta' che nel suo gioco non vuol dire niente.
     *
     * ## Cosa conta come «giocato»
     *
     * Almeno un minuto, per la **prima** squadra. Non essere in rosa: tenere tre
     * diciottenni in panchina per tutta la stagione e' precisamente la scorciatoia che
     * l'obiettivo esiste per non pagare.
     */
    private suspend fun giovaniInCampo(
        dentro: AppState.Dentro,
        club: dev.mfoot.android.data.ClubInfo,
    ): Int {
        val limite = dentro.lega.league.config.rules.youthMaxAge
        val scesi = ObjectiveRepository.chiHaGiocato(club.id)

        return dentro.lega.squadOf(club.id).count { it.age <= limite && it.id.value in scesi }
    }

    /**
     * Chi ha vinto le coppe: il vincitore dell'ultima partita giocata di un tabellone.
     *
     * Una finale pareggiata non ha vincitore qui. Non e' un caso che si verifichi — il
     * motore assegna i rigori — ma se capitasse, meglio nessun premio che uno assegnato a
     * caso fra due squadre.
     */
    private suspend fun coppeVinte(dentro: AppState.Dentro): Set<Long> {
        val leagueId = dentro.lega.league.id
        val competizioni = when (val esito = CompetitionRepository.list(leagueId)) {
            is ApiResult.Error -> return emptySet()
            is ApiResult.Ok -> esito.value.filter {
                it.type == CompetitionType.ELIMINAZIONE_DIRETTA && it.isFinished
            }
        }

        val vincitrici = HashSet<Long>(competizioni.size)
        competizioni.forEach { competizione ->
            val vista = caricaTabella(leagueId, competizione) ?: return@forEach
            val finale = vista.played.maxByOrNull { it.round } ?: return@forEach
            val casa = finale.homeGoals ?: return@forEach
            val fuori = finale.awayGoals ?: return@forEach
            when {
                casa > fuori -> vincitrici += finale.homeClubId
                fuori > casa -> vincitrici += finale.awayClubId
            }
        }
        return vincitrici
    }

    // ------------------------------------------------------------ le mie competizioni

    private val _competizioni = MutableStateFlow(CompetizioniMie())
    val competizioni: StateFlow<CompetizioniMie> = _competizioni

    /** Le competizioni della lega, lette una volta per apertura. */
    fun caricaCompetizioni() {
        val dentro = statoCorrente() ?: return
        if (_competizioni.value.letto) return

        viewModelScope.launch {
            when (val esito = CompetitionRepository.list(dentro.lega.league.id)) {
                // Nessun messaggio d'errore: la Casa non e' il posto in cui segnalare che
                // una lettura secondaria non e' riuscita. La sezione semplicemente non
                // compare, e ricomparira' al prossimo giro.
                is ApiResult.Error -> Unit
                is ApiResult.Ok ->
                    _competizioni.value = CompetizioniMie(tutte = esito.value, letto = true)
            }
        }
    }

    // ------------------------------------------------------------------- le mie leghe

    /**
     * Quante leghe risultano mie.
     *
     * ## Perche' si conta a ogni caricamento
     *
     * Perche' e' l'unica cosa che avverte del guaio prima che diventi un guaio. Chi e' in
     * una lega sola non vede niente e non paga quasi niente — una colonna di id, qualche
     * millisecondo. Chi e' in tre se lo vede scritto in cima insieme a quale sta
     * guardando, che e' esattamente l'informazione che mancava a due amici finiti in
     * mondi diversi senza accorgersene.
     */
    private val _quanteLeghe = MutableStateFlow(1)
    val quanteLeghe: StateFlow<Int> = _quanteLeghe

    private suspend fun contaLeghe() {
        // Le Row Level Security fanno il filtro: «tutte» vuol dire gia' «le mie».
        when (val esito = SupabaseApi.get("/rest/v1/leagues?select=id")) {
            is ApiResult.Error -> Unit
            is ApiResult.Ok ->
                _quanteLeghe.value =
                    dev.mfoot.core.json.JsonNode.parse(esito.value).asList().size.coerceAtLeast(1)
        }
    }

    private val _mieLeghe = MutableStateFlow(MyLeaguesState())
    val mieLeghe: StateFlow<MyLeaguesState> = _mieLeghe

    /**
     * Rilegge l'elenco delle proprie leghe.
     *
     * Sempre, a ogni apertura della schermata: e' l'unico posto da cui ci si accorge che un
     * amico e' entrato in una lega diversa dalla propria, e un elenco vecchio di dieci
     * minuti risponderebbe alla domanda sbagliata.
     */
    fun caricaMieLeghe() {
        viewModelScope.launch {
            _mieLeghe.value = _mieLeghe.value.copy(busy = "Cerco le tue leghe…", errore = null)
            when (val esito = MyLeaguesRepository.mine(Session.leagueId)) {
                is ApiResult.Error ->
                    _mieLeghe.value = _mieLeghe.value.copy(busy = null, errore = esito.message)
                is ApiResult.Ok ->
                    _mieLeghe.value = MyLeaguesState(leghe = esito.value, letto = true)
            }
        }
    }

    /**
     * Passa a un'altra delle proprie leghe.
     *
     * Ricarica tutto da capo: la lega e' la radice di ogni altro dato in memoria — club,
     * giocatori, aste, formazione — e portarne anche solo un pezzo dentro un'altra lega
     * vorrebbe dire mostrare i giocatori di un mondo dentro le squadre di un altro.
     */
    fun cambiaLega(leagueId: Long) {
        if (leagueId == Session.leagueId) return
        Session.leagueId = leagueId
        Session.clubId = null
        _mieLeghe.value = MyLeaguesState()
        _desk.value = DeskState()
        _staff.value = StaffState()
        _trades.value = TradesState()
        carica(leagueId, avviso = "Sei passato a un'altra lega.")
    }

    /** Cambia il codice d'accesso della lega aperta. Solo l'amministratore. */
    fun cambiaCodice(nuovo: String) {
        val dentro = statoCorrente() ?: return
        val pulito = nuovo.trim()
        if (pulito.isBlank()) return

        viewModelScope.launch {
            _mieLeghe.value = _mieLeghe.value.copy(busy = "Cambio il codice…", errore = null)
            when (val esito = MyLeaguesRepository.setAccessCode(dentro.lega.league.id, pulito)) {
                is ApiResult.Error ->
                    _mieLeghe.value = _mieLeghe.value.copy(busy = null, errore = esito.message)
                is ApiResult.Ok -> {
                    _mieLeghe.value = _mieLeghe.value.copy(
                        busy = null,
                        avviso = "Il codice ora e' ${esito.value}.",
                    )
                    caricaMieLeghe()
                }
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

    // --------------------------------------------------------------------- aste finite

    private val _asteConcluse =
        MutableStateFlow<List<dev.mfoot.android.data.ClosedAuction>>(emptyList())
    val asteConcluse: StateFlow<List<dev.mfoot.android.data.ClosedAuction>> = _asteConcluse

    private val _asteConclusePronte = MutableStateFlow(false)
    val asteConclusePronte: StateFlow<Boolean> = _asteConclusePronte

    /**
     * Le aste finite, con dentro chi ha offerto quanto.
     *
     * Si legge all'apertura della scheda: e' un elenco che cresce di qualche riga al
     * giorno, e tenerlo aggiornato in tempo reale sarebbe una richiesta al minuto per una
     * cosa che si guarda dopo, con calma, per vedere quanto ci si e' andati vicini.
     */
    fun caricaAsteConcluse() {
        val dentro = statoCorrente() ?: return
        viewModelScope.launch {
            _asteConcluse.value =
                dev.mfoot.android.data.ClosedAuctionRepository.recent(dentro.lega.league.id)
            _asteConclusePronte.value = true
        }
    }

    // -------------------------------------------------------------- staff e osservatori

    private val _staff = MutableStateFlow(StaffState())
    val staff: StateFlow<StaffState> = _staff

    fun caricaStaff() {
        val dentro = statoCorrente() ?: return
        val miei = listOfNotNull(dentro.lega.myClub?.id, dentro.lega.myYouthClub?.id)

        viewModelScope.launch {
            _staff.value = _staff.value.copy(
                tutti = StaffRepository.all(dentro.lega.league.id),
                missioni = StaffRepository.missions(miei),
                letto = true,
            )
        }
    }

    /** Sposta un membro dello staff fra le proprie due squadre. */
    fun spostaStaff(staffId: Long, clubId: Long) {
        viewModelScope.launch {
            when (val esito = StaffRepository.assign(staffId, clubId)) {
                is ApiResult.Error -> _staff.value = _staff.value.copy(errore = esito.message)
                is ApiResult.Ok -> {
                    _staff.value = _staff.value.copy(errore = null, avviso = "Spostato.")
                    caricaStaff()
                }
            }
        }
    }

    /**
     * Apre l'asta per un membro dello staff libero.
     *
     * Passa dalla stessa funzione dei giocatori, che accetta `target_type = 'staff'` dal
     * primo giorno e non l'aveva mai chiamata nessuno.
     */
    fun mettiStaffAllAsta(staffId: Long) {
        val dentro = statoCorrente() ?: return
        val membro = _staff.value.tutti.firstOrNull { it.id == staffId } ?: return

        viewModelScope.launch {
            // Base bassa come per i giocatori: il prezzo lo deve fare l'asta, non chi la
            // apre. Le stelle contano molto piu' che linearmente, quindi anche la base.
            val base = 1.coerceAtLeast(membro.stars * membro.stars * 200)
            val esito = AuctionRepository.startAuction(
                leagueId = dentro.lega.league.id,
                targetId = staffId,
                startingPrice = base,
                targetType = "staff",
            )

            when (esito) {
                is ApiResult.Error -> _staff.value = _staff.value.copy(errore = esito.message)
                is ApiResult.Ok -> {
                    _staff.value = _staff.value.copy(
                        errore = null,
                        avviso = "${membro.shortName} e' all'asta, base $base.",
                    )
                    aggiornaAste()
                }
            }
        }
    }

    /** Manda un osservatore a cercare in un paese, per un ruolo. */
    fun mandaOsservatore(staffId: Long, paese: String, ruolo: String) {
        viewModelScope.launch {
            _staff.value = _staff.value.copy(busy = "Parte…", errore = null)

            when (val esito = StaffRepository.send(staffId, paese, ruolo)) {
                is ApiResult.Error ->
                    _staff.value = _staff.value.copy(busy = null, errore = esito.message)

                is ApiResult.Ok -> {
                    _staff.value = _staff.value.copy(
                        busy = null,
                        errore = null,
                        avviso = "E' partito per il $paese. Torna quando torna.",
                    )
                    caricaStaff()
                }
            }
        }
    }

    // ---------------------------------------------------------------------- primavera

    /** Sposta l'interruttore fra prima squadra e Primavera. */
    fun guardaLaPrimavera(si: Boolean) {
        val dentro = statoCorrente() ?: return
        _state.value = dentro.copy(guardoLaPrimavera = si)

        // La formazione segue l interruttore: sono due righe diverse di `lineups`, e
        // lasciare a schermo quella di prima farebbe modificare la squadra sbagliata.
        val club = if (si) dentro.lega.myYouthClub?.id else dentro.lega.myClub?.id
        caricaFormazione(dentro.lega, club)
    }

    /**
     * Fonda la seconda squadra.
     *
     * Si fonda su richiesta e non alla creazione della lega: chi entra oggi non ha ancora
     * nemmeno la prima squadra, e generargli una seconda vuota vorrebbe dire una riga in
     * piu' in ogni classifica per un club che non esiste ancora.
     */
    fun fondaLaPrimavera() {
        val dentro = statoCorrente() ?: return
        val club = dentro.lega.myClub ?: return

        viewModelScope.launch {
            when (val esito = YouthRepository.create(club.id)) {
                is ApiResult.Error ->
                    _state.value = statoCorrente()?.copy(errore = esito.message) ?: return@launch

                is ApiResult.Ok -> Session.leagueId?.let {
                    carica(it, avviso = "${club.name} Primavera e' iscritta all'ultima divisione.")
                }
            }
        }
    }

    /**
     * Manda un giovane in Primavera, o lo promuove in prima squadra.
     *
     * Le regole — l'eta' massima, il minimo di prima squadra, il massimo tornando su — le
     * fa rispettare il database, e il rifiuto arriva come una frase da mostrare: "Ha 24
     * anni: in Primavera si sta fino a 21" e' una risposta di gioco, non un guasto.
     */
    fun spostaSquadra(row: PlayerRow) {
        val dentro = statoCorrente() ?: return
        // Sta in Primavera se il suo club ha un padre. Non serve piu' una colonna `squad`:
        // la verita' e' il contratto, e il contratto punta a un club vero.
        val inPrimavera = row.club?.parentClubId != null
        val promuovi = inPrimavera

        viewModelScope.launch {
            when (val esito = YouthRepository.move(row.player.id.value, promuovi)) {
                is ApiResult.Error ->
                    _state.value = statoCorrente()?.copy(errore = esito.message) ?: return@launch

                is ApiResult.Ok -> {
                    val dove = if (promuovi) "in prima squadra" else "in Primavera"
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
     * La regola sta in [DivisionAssignment]: **i club dei giocatori veri partono tutti in
     * prima divisione**, le seconde squadre dall'ultima, le AI riempiono i posti che
     * restano dalla piu' forte alla piu' debole.
     *
     * Prima si distribuivano tutti a serpentina in base alla forza — umani e AI mescolati,
     * cosi' nessuna divisione nasceva gia' decisa. Sensato per un campionato vero,
     * sbagliato per una lega fra amici: chi si iscrive vuole giocare contro gli altri
     * amici, e finiva in seconda divisione contro otto squadre del computer perche' la sua
     * rosa iniziale valeva tre punti di meno.
     */
    fun assegnaDivisioni() {
        val dentro = statoCorrente() ?: return
        val config = dentro.lega.league.config.divisions
        if (!config.enabled) return

        viewModelScope.launch {
            _divisioni.value = _divisioni.value.copy(busy = "Assegno le divisioni…", errore = null)

            val esito = DivisionAssignment.initial(
                clubs = dentro.lega.clubs.map { club ->
                    ClubToPlace(
                        id = ClubId(club.id),
                        // Il proprietario e' una persona: il club ha un `owner_user_id`. Le
                        // AI no, e ci si puo' fidare perche' e' la stessa colonna che decide
                        // chi puo' schierare la formazione.
                        isHuman = !club.isAi && club.ownerUserId != null,
                        isSecondTeam = club.parentClubId != null,
                        strength = dentro.lega.squadOf(club.id).sumOf { it.overall.toLong() },
                    )
                },
                divisions = config.count,
                sizes = config.sizes,
            )

            val avvisi = esito.warnings.joinToString(" ") { it.message }
            applicaLivelli(
                dentro.lega.league.id,
                esito.levels,
                ("Divisioni assegnate: i giocatori veri in ${config.nameOf(1)}. " + avvisi).trim(),
            )
        }
    }

    /**
     * Cosa succederebbe premendo «assegna», detto prima di premere.
     *
     * La schermata la chiama a ogni disegno: e' un conto sui club gia' in memoria, non una
     * richiesta. Serve perche' l'avviso che conta — «hai dodici amici e la Serie A ne
     * prevede dieci» — deve arrivare quando si puo' ancora cambiare idea sulle dimensioni,
     * non dopo aver riscritto la scala di tutta la lega.
     */
    fun anteprimaDivisioni(): List<String> {
        val dentro = statoCorrente() ?: return emptyList()
        val config = dentro.lega.league.config.divisions
        if (!config.enabled) return emptyList()

        return DivisionAssignment.initial(
            clubs = dentro.lega.clubs.map { club ->
                ClubToPlace(
                    id = ClubId(club.id),
                    isHuman = !club.isAi && club.ownerUserId != null,
                    isSecondTeam = club.parentClubId != null,
                    strength = dentro.lega.squadOf(club.id).sumOf { it.overall.toLong() },
                )
            },
            divisions = config.count,
            sizes = config.sizes,
        ).warnings.map { it.message }
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
            // Gli obiettivi si giudicano **qui**, con gli stessi esiti che decidono
            // promozioni e retrocessioni: e' l'unico momento in cui la stagione ha un
            // verdetto, e chiuderli altrove vorrebbe dire un premio pagato su una
            // classifica diversa da quella che ha mosso le divisioni.
            val premi = chiudiObiettivi(dentro, esiti)
            applicaLivelli(leagueId, livelli, riassunto(esiti) + spareggi + premi)
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
        if (bozza.isEmpty(dentro.lega.league.config.calendar.timeZone)) return

        viewModelScope.launch {
            _trades.value = _trades.value.copy(busy = "Mando la proposta…", errore = null)

            // Una controproposta non e una proposta nuova: chiude quella a cui risponde e
            // ne apre una legata. Mandarla per la strada normale lascerebbe due
            // trattative aperte sulle stesse persone.
            val rispondeA = bozza.rispondeA
            val esito = if (rispondeA != null) {
                CounterRepository.counter(rispondeA, bozza.cash, bozza.message)
            } else when (bozza.kind) {
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

    /**
     * Apre una controproposta a una proposta ricevuta.
     *
     * ## Perche' riapre la stessa bozza invece di aprirne una vuota
     *
     * Perche' una controproposta e' **la stessa trattativa vista dall'altra parte**: i
     * giocatori sono quelli, cambia la cifra. Farla ricomporre da zero vorrebbe dire
     * riselezionare a mano quello che l'altro ha gia' scelto, e sbagliare un giocatore
     * trasformerebbe la risposta in una proposta diversa senza che nessuno se ne accorga.
     *
     * I due lati si scambiano: quello che lui offriva adesso lo chiedo io.
     */
    fun apriControproposta(trade: dev.mfoot.android.data.TradeRow) {
        _trades.value = _trades.value.copy(
            bozza = TradeDraft(
                withClub = trade.fromClub,
                offered = trade.wanted.toSet(),
                wanted = trade.offered.toSet(),
                // Il segno si gira con i lati: quello che lui aggiungeva, adesso lo chiedo.
                cash = -trade.cash,
                kind = trade.kind,
                rispondeA = trade.id,
            ),
            avviso = null,
            errore = null,
        )
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
    /**
     * La formazione di una delle due squadre.
     *
     * Il club arriva da fuori e non e piu fisso su `myClub`: `lineups` ha una riga per
     * club, e con due squadre la formazione che si sta componendo deve sapere di chi e —
     * altrimenti salvare mentre l interruttore e sulla Primavera schiererebbe undici
     * ragazzi al posto della prima squadra.
     */
    private fun caricaFormazione(snapshot: LeagueSnapshot, clubId: Long? = null) {
        val club = (clubId?.let { id -> snapshot.clubs.firstOrNull { it.id == id } } ?: snapshot.myClub)
            ?: run {
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
            _lineup.value = base.copy(clubId = club.id, salvata = base.snapshot)
        }
    }

    // ------------------------------------------------------- la formazione degli altri

    private val _altrui = MutableStateFlow(FormazioneAltrui())
    val formazioneAltrui: StateFlow<FormazioneAltrui> = _altrui

    /**
     * Come schiera un altro club.
     *
     * Se non ha schierato niente si mostra **cio' che scenderebbe in campo da solo**,
     * calcolato con lo stesso [AutoLineup] che usa il server: e' un'ipotesi e la schermata
     * lo dice, ma e' un'ipotesi esatta, non un campo vuoto. Un campo vuoto direbbe «non si
     * sa», che e' falso — si sa benissimo, ed e' l'informazione che serve.
     */
    fun caricaFormazioneAltrui(clubId: Long) {
        val dentro = statoCorrente() ?: return
        if (_altrui.value.clubId == clubId && _altrui.value.letto) return

        val squad = dentro.lega.squadOf(clubId)
        val today = MatchDay(dentro.lega.league.currentMatchDay)

        viewModelScope.launch {
            _altrui.value = FormazioneAltrui(clubId = clubId)

            val salvata = when (val esito = LineupRepository.read(clubId)) {
                is ApiResult.Error -> null
                is ApiResult.Ok -> esito.value
            }
            val composta = salvata?.takeIf { it.eleven.any { id -> id != null } }

            _altrui.value = if (composta != null) {
                val byId = squad.associateBy { it.id.value }
                FormazioneAltrui(
                    clubId = clubId,
                    formation = composta.formation,
                    eleven = composta.eleven.map { id -> id?.let { byId[it] } },
                    bench = composta.bench.mapNotNull { byId[it] },
                    tactics = composta.tactics,
                    suPrevisione = false,
                    letto = true,
                )
            } else {
                val modulo = AutoLineup.bestFormation(squad, today)
                val auto = AutoLineup.build(squad, modulo, today)
                FormazioneAltrui(
                    clubId = clubId,
                    formation = modulo,
                    eleven = auto?.slots?.map { it.player }
                        ?: List(modulo.positions.size) { null },
                    bench = auto?.bench.orEmpty(),
                    tactics = salvata?.tactics,
                    suPrevisione = true,
                    letto = true,
                    errore = if (auto == null) {
                        "Rosa troppo corta per undici: questa squadra non scende in campo."
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun salvaFormazione() {
        val dentro = statoCorrente() ?: return
        val edit = _lineup.value
        // Il club e quello per cui la formazione e stata **caricata**, non quello che
        // l interruttore mostra adesso: fra il caricamento e il salvataggio si puo averlo
        // spostato, e scrivere sulla riga sbagliata schiererebbe la squadra sbagliata.
        val clubId = edit.clubId ?: dentro.lega.myClub?.id ?: return
        if (!edit.dirty || edit.busy != null) return

        viewModelScope.launch {
            _lineup.value = edit.copy(busy = "Salvo…", errore = null)

            val esito = LineupRepository.save(
                leagueId = dentro.lega.league.id,
                clubId = clubId,
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

    /** La carriera del giocatore aperto: presenze, gol, media voto. */
    private val _carriera = MutableStateFlow(dev.mfoot.android.data.Carriera.NESSUNA)
    val carriera: StateFlow<dev.mfoot.android.data.Carriera> = _carriera

    /**
     * Apre la scheda di un giocatore, e va a prendergli la storia.
     *
     * La carriera si legge **all'apertura** e non insieme alla lega: sono milletrecento
     * giocatori, e caricare le presenze di tutti per mostrarne una scheda costerebbe piu'
     * del mondo intero. Si azzera subito, cosi' non si vedono per un istante i numeri del
     * giocatore precedente.
     */
    fun select(row: PlayerRow?) {
        aggiornaBrowse { it.copy(selected = row) }
        _carriera.value = dev.mfoot.android.data.Carriera.NESSUNA
        if (row == null) return

        viewModelScope.launch {
            val storia = dev.mfoot.android.data.CareerRepository.of(row.player.id.value)
            // Solo se e' ancora aperto **quel** giocatore: fra la richiesta e la risposta
            // si puo' averne aperto un altro, e mostrargli i numeri del primo sarebbe
            // peggio che non mostrarne nessuno.
            if (statoCorrente()?.browse?.selected?.player?.id == row.player.id) {
                _carriera.value = storia
            }
        }
    }

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

    /** Il codice e' cambiato sotto le dita: l'anteprima di prima non vale piu'. */
    fun scordaAnteprima() {
        val corrente = _state.value as? AppState.Porta ?: return
        if (corrente.anteprima == null && !corrente.anteprimaVuota) return
        _state.value = corrente.copy(anteprima = null, anteprimaVuota = false, errore = null)
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
