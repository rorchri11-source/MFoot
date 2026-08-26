package dev.mfoot.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.mfoot.android.data.PushRepository
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.AppViewModel
import dev.mfoot.android.app.DoorMode
import dev.mfoot.android.app.Route
import dev.mfoot.android.app.TabLega
import dev.mfoot.android.app.TableTab
import dev.mfoot.android.data.Session
import dev.mfoot.android.ui.shell.Router
import dev.mfoot.android.ui.shell.Shell
import dev.mfoot.android.ui.BidSheet
import dev.mfoot.android.ui.CompetitionsScreen
import dev.mfoot.android.ui.DoorScreen
import dev.mfoot.android.ui.FoundingScreen
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.PlayerDetailScreen
import dev.mfoot.android.ui.TableScreen
import dev.mfoot.android.ui.screens.CalendarioScreen
import dev.mfoot.android.ui.screens.PartitaScreen
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootMotion
import dev.mfoot.android.ui.theme.MFootTheme
import dev.mfoot.android.ui.theme.MFootType

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Icone di sistema chiare, sempre.
        //
        // `enableEdgeToEdge()` senza argomenti le decide dal tema del telefono: su un
        // telefono in modalita' chiara le disegna **nere**, e MFoot e' scura sempre —
        // barra blu in cima, blu notte sotto. Il risultato era l'orologio e la batteria
        // neri sul blu, illeggibili, su ogni telefono non impostato in scuro.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        chiediLeNotifiche()
        registraIlTelefono()

        setContent { MFootTheme { MFootApp() } }
    }

    /**
     * Il permesso di avvisare, chiesto una volta.
     *
     * ## Perche' serve chiederlo
     *
     * Da Android 13 le notifiche sono un permesso come la fotocamera. Senza, il sistema
     * **scarta ogni notifica in silenzio**: nessun errore, nessun avviso, semplicemente non
     * compare niente. E' il tipo di guasto che si scopre solo notando che non arriva mai
     * niente — cioe' esattamente il problema da cui nasce tutto questo lavoro.
     *
     * ## Perche' qui e non dietro una schermata di benvenuto
     *
     * Perche' la schermata che spiega perche' servono le notifiche costerebbe piu' della
     * cosa che spiega. Il sistema chiede una volta sola: se dici di no, non lo richiede
     * piu' e il gioco funziona identico, solo silenzioso.
     */
    private fun chiediLeNotifiche() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val gia = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!gia) chiediPermesso.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Il richiedente, registrato **alla costruzione dell'attivita'**.
     *
     * Non e' pignoleria: `registerForActivityResult` va chiamato prima che l'attivita' sia
     * avviata, perche' deve poter ricevere una risposta anche se Android nel frattempo
     * distrugge e ricrea la schermata. Chiamarlo dentro un `if`, al momento del bisogno,
     * funziona quasi sempre e fallisce proprio nel caso che conta.
     *
     * Il risultato non si guarda: se l'utente dice di no non c'e' niente da fare se non
     * continuare. Il gioco resta identico, solo senza campanello.
     */
    private val chiediPermesso =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Il telefono si presenta al server, a ogni avvio.
     *
     * Non solo la prima volta: Firebase ruota i gettoni quando gli pare — dopo una
     * reinstallazione, dopo una cancellazione dei dati, o senza motivo apparente — e un
     * gettone vecchio non da' errore, smette semplicemente di consegnare. Vedi
     * [PushRepository].
     *
     * Fuori dal thread principale e senza bloccare niente: se Firebase non risponde,
     * l'app parte identica.
     */
    private fun registraIlTelefono() {
        lifecycleScope.launch { runCatching { PushRepository.registra() } }
    }
}

/**
 * Lo smistamento fra le schermate.
 *
 * Non c'e' un navigatore: gli stati sono pochi e si escludono a vicenda, quindi un `when`
 * sullo stato dice in cinque righe tutto quello che l'app puo' mostrare. Una libreria di
 * navigazione diventera' utile quando ci saranno rotte con parametri e cronologia, non
 * prima.
 */
@Composable
private fun MFootApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.configEdit.collectAsStateWithLifecycle()
    val lineup by viewModel.lineupEdit.collectAsStateWithLifecycle()
    val desk by viewModel.desk.collectAsStateWithLifecycle()
    val mieLeghe by viewModel.mieLeghe.collectAsStateWithLifecycle()
    val formazioneAltrui by viewModel.formazioneAltrui.collectAsStateWithLifecycle()
    val competizioni by viewModel.competizioni.collectAsStateWithLifecycle()
    val obiettivi by viewModel.obiettivi.collectAsStateWithLifecycle()
    val quanteLeghe by viewModel.quanteLeghe.collectAsStateWithLifecycle()
    val ultimoAggiornamento by viewModel.ultimoAggiornamento.collectAsStateWithLifecycle()

    // L'app davanti o dietro.
    //
    // Non e' pignoleria sul risparmio di batteria: e' il caso in cui prima non si
    // aggiornava mai niente. Su Android uscire col tasto home e rientrare dal selettore
    // **non fa ripartire l'app** — resta viva con la stessa fotografia del mondo di
    // mezz'ora fa — quindi tornare davanti e' esattamente il momento in cui c'e' piu'
    // roba nuova da leggere, ed era l'unico momento in cui non si leggeva niente.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.cambiaPrimoPiano(true)
                Lifecycle.Event.ON_STOP -> viewModel.cambiaPrimoPiano(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val scambi by viewModel.trades.collectAsStateWithLifecycle()
    val divisioni by viewModel.divisioni.collectAsStateWithLifecycle()
    val spogliatoio by viewModel.spogliatoio.collectAsStateWithLifecycle()
    val tabella by viewModel.tabella.collectAsStateWithLifecycle()
    val staff by viewModel.staff.collectAsStateWithLifecycle()
    val carriera by viewModel.carriera.collectAsStateWithLifecycle()
    val prezzoSvincolato by viewModel.prezzoSvincolato.collectAsStateWithLifecycle()
    val concluse by viewModel.asteConcluse.collectAsStateWithLifecycle()
    val conclusePronte by viewModel.asteConclusePronte.collectAsStateWithLifecycle()

    // Dentro la lega gli inset li gestisce il guscio, non la radice.
    //
    // Nel riferimento la barra di stato e' **blu**: la barra in alto ci passa sotto e la
    // colora. Con un `systemBarsPadding()` qui alla radice quello non e' possibile — resta
    // una striscia di fondo pagina sopra la barra blu, ed e' esattamente la cucitura che
    // fa sembrare l'app un contenuto dentro una cornice invece di una schermata sola.
    // Fuori dalla lega non c'e' nessun guscio a cui delegare, e la radice fa da se'.
    val guscioSuo = state is AppState.Dentro
    Box(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .then(if (guscioSuo) Modifier else Modifier.systemBarsPadding()),
    ) {
        when (val current = state) {
            is AppState.Avvio -> Attesa("")

            is AppState.Caricamento -> Attesa(current.fase)

            is AppState.Porta -> DoorScreen(
                state = current,
                onMode = viewModel::apriPorta,
                onCreate = viewModel::creaLega,
                onJoin = viewModel::entraInLega,
                onPeek = viewModel::sbircia,
                onCodeChanged = viewModel::scordaAnteprima,
            )

            is AppState.Guasto -> Guasto(current.motivo, viewModel::avvia)

            is AppState.Partita -> PartitaScreen(
                state = current.partita,
                onPausa = viewModel::pausaPartita,
                onFine = viewModel::saltaAllaFine,
                onChiudi = viewModel::chiudiPartita,
                nomeGiocatore = viewModel::nomeGiocatore,
            )

            is AppState.Calendario -> CalendarioScreen(
                state = current.calendario,
                onMese = viewModel::sfogliaCalendario,
                onGiorno = viewModel::scegliGiorno,
                onPartita = viewModel::apriPartita,
                onChiudi = viewModel::chiudiCalendario,
            )

            is AppState.Competizioni -> CompetitionsScreen(
                state = current.competitions,
                onNew = viewModel::nuovaCompetizione,
                onEdit = viewModel::modificaCompetizione,
                onCreate = viewModel::creaCompetizione,
                onCancelDraft = viewModel::annullaCompetizione,
                onDelete = viewModel::cancellaCompetizione,
                onClose = viewModel::chiudiCompetizioni,
            )

            is AppState.Fondazione -> FoundingScreen(
                state = current.founding,
                onChange = viewModel::aggiornaFondazione,
                onRaise = viewModel::alzaAttributo,
                onLower = viewModel::abbassaAttributo,
                onPosition = viewModel::cambiaRuolo,
                onConfirm = viewModel::confermaFondazione,
                onCancel = viewModel::annullaFondazione,
            )

            is AppState.Dentro -> {
                Shell(
                    title = current.lega.league.name,
                    subtitle = current.route.label,
                    nickname = Session.nickname ?: "giocatore",
                    clubName = current.lega.myClub?.name,
                    isAdmin = current.lega.league.isAdmin,
                    route = current.route,
                    // Con le divisioni accese la piu' utile e' in quale si gioca: e' la
                    // cosa che decide contro chi si scende in campo e non e' scritta in
                    // nessun'altra schermata. Senza divisioni quella riga direbbe sempre
                    // «girone unico», che non e' un'informazione, quindi al suo posto va
                    // quante squadre ci sono.
                    contesto = buildString {
                        val divisioni = current.lega.league.config.divisions
                        val club = current.lega.myClub
                        if (divisioni.enabled && club != null) {
                            append(divisioni.nameOf(club.divisionLevel))
                        } else {
                            append(current.lega.clubs.size).append(" squadre")
                        }
                        append(" · ").append(current.lega.league.currentMatchDay)
                        append("ª giornata")
                    },
                    drawerOpen = current.drawerOpen,
                    onToggleDrawer = viewModel::apriChiudiMenu,
                    onNavigate = { route ->
                        // Classifica, calendario e competizioni hanno una schermata intera
                        // loro, con caricamenti propri: si aprono da fuori dal guscio
                        // invece di essere infilate dentro il contenuto.
                        //
                        // Le prime due sono schede dentro "Lega", quindi l'intercettazione
                        // guarda anche quale scheda: toccare il chip Classifica apre la
                        // schermata piena, toccare Squadre resta dentro il guscio.
                        when {
                            route is Route.Calendario -> viewModel.apriCalendario()
                            route is Route.Competizioni -> viewModel.apriCompetizioni()
                            else -> viewModel.vai(route)
                        }
                    },
                    quanteLeghe = quanteLeghe,
                    ultimoAggiornamento = ultimoAggiornamento,
                    onRefresh = viewModel::aggiornaAdesso,
                    onLeaveLeague = viewModel::lasciaLega,
                    onBack = { viewModel.indietro() },
                ) {
                    Router(
                        state = current,
                        onNavigate = viewModel::vai,
                        onQuery = viewModel::onQuery,
                        onFilter = viewModel::onFilter,
                        onSelect = viewModel::select,
                        onOpenBid = viewModel::apriOfferta,
                        onRefreshAuctions = { viewModel.aggiornaAste() },
                        onAuctionFilter = viewModel::filtraAste,
                        onFoundClub = viewModel::fondaClub,
                        onSwitchTeam = viewModel::guardaLaPrimavera,
                        onCreateYouth = viewModel::fondaLaPrimavera,
                        concluse = concluse,
                        concluseLette = conclusePronte,
                        onLoadClosed = viewModel::caricaAsteConcluse,
                        onStaffName = viewModel::nomeStaff,
                        staff = staff,
                        onLoadStaff = viewModel::caricaStaff,
                        onMoveStaff = viewModel::spostaStaff,
                        onAssumiStaff = viewModel::assumiStaff,
                        onVendiStaff = viewModel::vendiStaff,
                        onSendScout = viewModel::mandaOsservatore,
                        onDismissNotice = viewModel::chiudiAvviso,
                        onCrediti = viewModel::adminCrediti,
                        settings = settings,
                        onConfigChange = viewModel::modificaRegolamento,
                        onConfigSave = viewModel::salvaRegolamento,
                        desk = desk,
                        obiettivi = obiettivi,
                        onLoadObjectives = { viewModel.caricaObiettivi() },
                        onAssignObjectives = viewModel::assegnaObiettivi,
                        competizioni = competizioni,
                        onLoadCompetitions = viewModel::caricaCompetizioni,
                        formazioneAltrui = formazioneAltrui,
                        onLoadOtherLineup = viewModel::caricaFormazioneAltrui,
                        mieLeghe = mieLeghe,
                        onLoadLeagues = viewModel::caricaMieLeghe,
                        onSwitchLeague = viewModel::cambiaLega,
                        onChangeCode = viewModel::cambiaCodice,
                        onLoadMembers = viewModel::caricaPartecipanti,
                        onLoadTick = viewModel::caricaRegistro,
                        scambi = scambi,
                        onLoadTrades = { viewModel.caricaScambi() },
                        onNewTrade = viewModel::nuovoScambio,
                        onEditTrade = viewModel::modificaScambio,
                        onSendTrade = viewModel::inviaScambio,
                        onCancelTrade = viewModel::annullaScambio,
                        onRespondTrade = viewModel::rispondiScambio,
                        onCounterTrade = viewModel::apriControproposta,
                        onWithdrawTrade = viewModel::ritiraScambio,
                        onDismissTradeNotice = viewModel::chiudiAvvisoScambi,
                        divisioni = divisioni,
                        avvisiDivisioni = viewModel::anteprimaDivisioni,
                        onAssignDivisions = viewModel::assegnaDivisioni,
                        onCloseSeason = viewModel::chiudiStagione,
                        onDismissDivisionNotice = viewModel::chiudiAvvisoDivisioni,
                        tabella = tabella,
                        onLoadTable = { viewModel.apriClassifica() },
                        onPickCompetition = viewModel::scegliCompetizione,
                        onPickTableTab = viewModel::cambiaSchedaTabella,
                        onOpenMatch = { m ->
                            viewModel.apriPartita(
                                m.id,
                                tabella.clubName(m.homeClubId),
                                tabella.clubName(m.awayClubId),
                            )
                        },
                        spogliatoio = spogliatoio,
                        onLoadTalks = viewModel::caricaSpogliatoio,
                        onOpenTalk = viewModel::apriColloquio,
                        onSummon = viewModel::convoca,
                        onTalk = viewModel::parla,
                        onCloseTalk = viewModel::chiudiColloquio,
                        lineup = lineup,
                        onLineupChange = viewModel::modificaFormazione,
                        onLineupSave = viewModel::salvaFormazione,
                    )
                }

                // Il foglio dell'offerta copre tutto: si sta decidendo quanto spendere, e
                // ogni altra cosa a schermo in quel momento e' una distrazione.
                AnimatedVisibility(
                    visible = current.bidding != null,
                    // I due fogli coprono il guscio, quindi gli inset che il guscio si
                    // gestisce da solo qui non li ha nessuno: senza, il titolo dell'asta
                    // finisce sotto l'orologio del telefono.
                    modifier = Modifier.systemBarsPadding(),
                    enter = slideInVertically(
                        animationSpec = tween(MFootMotion.normal, easing = MFootMotion.easing),
                        initialOffsetY = { it / 3 },
                    ) + fadeIn(tween(MFootMotion.fast, easing = MFootMotion.easing)),
                    exit = slideOutVertically(
                        animationSpec = tween(MFootMotion.fast, easing = MFootMotion.easing),
                        targetOffsetY = { it / 3 },
                    ) + fadeOut(tween(MFootMotion.fast, easing = MFootMotion.easing)),
                ) {
                    current.bidding?.let { row ->
                        BidSheet(
                            row = row,
                            available = current.lega.myClub?.available ?: 0,
                            minimumRaise = current.lega.league.config.market.minimumRaise,
                            storia = current.biddingHistory,
                            myClubId = current.lega.myClub?.id,
                            onBid = { viewModel.offri(row.auction.id, it) },
                            onClose = { viewModel.apriOfferta(null) },
                        )
                    }
                }

                // La scheda entra dal basso sopra la lista: cosi' si capisce che si sta
                // guardando un dettaglio e non si e' cambiata schermata.
                AnimatedVisibility(
                    visible = current.browse.selected != null,
                    modifier = Modifier.systemBarsPadding(),
                    enter = slideInVertically(
                        animationSpec = tween(MFootMotion.normal, easing = MFootMotion.easing),
                        initialOffsetY = { it / 3 },
                    ) + fadeIn(tween(MFootMotion.fast, easing = MFootMotion.easing)),
                    exit = slideOutVertically(
                        animationSpec = tween(MFootMotion.fast, easing = MFootMotion.easing),
                        targetOffsetY = { it / 3 },
                    ) + fadeOut(tween(MFootMotion.fast, easing = MFootMotion.easing)),
                ) {
                    current.browse.selected?.let { row ->
                        PlayerDetailScreen(
                            row = row,
                            carriera = carriera,
                            giornata = current.lega.league.currentMatchDay,
                            // Solo per i propri: gli incarichi degli altri club non si
                            // conoscono, e mostrarne di finti sarebbe peggio che tacere.
                            incarichi = if (row.club?.isMine == true) {
                                lineup.incarichiDi(row.player.id.value)
                            } else {
                                emptyList()
                            },
                            // Uno svincolato lo puo' battere chiunque; un tesserato solo
                            // il suo club. La rosa altrui non si tocca — quella si tratta
                            // — ma vendere i propri e' cio' che tiene vivo il mercato dopo
                            // che gli svincolati sono finiti.
                            /*
                             * IL CONFRONTO DEVE GUARDARE ANCHE IL TIPO DI BERSAGLIO
                             *
                             * `targetId` di un'asta e' un id di **giocatore oppure di
                             * staff**, e le due tabelle hanno sequenze separate: il
                             * giocatore 7 e l'allenatore 7 esistono tutti e due. Senza
                             * `targetType`, un'asta aperta su un membro dello staff
                             * spegneva «Metti all'asta» sul giocatore con lo stesso
                             * numero.
                             *
                             * E' il difetto visto sull'emulatore il 2026-08-25 e
                             * registrato in STATO.md come «visto e non capito»: alla prima
                             * apertura il pulsante c'era, riaprendo la stessa scheda piu'
                             * tardi spariva. La differenza era che nel frattempo il giro
                             * leggero aveva caricato le aste — comprese quelle sullo staff
                             * che aprivano i club del computer.
                             *
                             * Ogni altro punto dell'app confronta gia' anche il tipo:
                             * questo era l'unico rimasto indietro.
                             */
                            canAuction = current.lega.myClub != null &&
                                (row.isFreeAgent || row.club?.isMine == true) &&
                                current.auctions.none {
                                    it.auction.targetType == "player" &&
                                        it.auction.targetId == row.player.id.value
                                },
                            isSelling = row.club?.isMine == true,
                            // Il pulsante compare solo dove ha senso: sui propri, e solo
                            // se l'eta' lo consente. Mostrarlo su chiunque vorrebbe dire
                            // farlo premere per scoprire da un errore che non si poteva.
                            // Il pulsante compare solo dove ha senso: sui propri, e solo
                            // se c e una Primavera dove mandarlo e l eta lo consente.
                            youthAction = when {
                                row.club?.isMine != true -> null
                                !current.haLaPrimavera -> null
                                row.isYouth -> "In prima squadra"
                                row.player.age <= current.lega.league.config.rules.youthMaxAge ->
                                    "In Primavera"
                                else -> null
                            },
                            creditiDisponibili = current.lega.myClub?.available ?: 0,
                            rilancioMinimo = current.lega.league.config.market.minimumRaise,
                            prezzoSvincolato = prezzoSvincolato,
                            onYouth = { viewModel.spostaSquadra(row) },
                            onAuction = { viewModel.mettiAllAsta(row) },
                            onCompra = { viewModel.compra(row) },
                            onVendi = { prezzo -> viewModel.mettiInVendita(row, prezzo) },
                            onRitira = { viewModel.ritiraDalListino(row) },
                            onSvincola = { viewModel.svincola(row) },
                            onContesta = { massimo -> viewModel.contesta(row, massimo) },
                            // Solo all'admin, e solo sulle prime squadre: spostare un
                            // giocatore in una Primavera vorrebbe dire scavalcare la
                            // regola dell'eta', che vive da un'altra parte.
                            adminClubs = if (current.lega.league.isAdmin) {
                                current.lega.clubs
                                    .filter { it.parentClubId == null }
                                    .map { it.id to it.name }
                            } else {
                                emptyList()
                            },
                            onAdminAssegna = { clubId -> viewModel.adminAssegna(row, clubId) },
                            onAdminSvincola = { viewModel.adminSvincola(row) },
                            onClose = { viewModel.select(null) },
                        )
                    }
                }
            }
        }
    }

    BackHandler(enabled = state.canGoBack()) {
        when (val current = state) {
            // Dentro la lega il ritorno lo gestisce il ViewModel, che conosce la pila
            // delle schermate: menu aperto, foglio dell'offerta, scheda, poi la pila.
            is AppState.Dentro -> {
                if (current.browse.selected != null) viewModel.select(null)
                else viewModel.indietro()
            }

            is AppState.Porta ->
                if (current.mode != DoorMode.SCELTA) viewModel.apriPorta(DoorMode.SCELTA)

            else -> Unit
        }
    }
}

/** C'e' un passo indietro possibile dentro l'app, o il tasto deve chiuderla? */
private fun AppState.canGoBack(): Boolean = when (this) {
    is AppState.Dentro -> browse.selected != null || canGoBack
    is AppState.Porta -> mode != DoorMode.SCELTA
    else -> false
}

@Composable
private fun Attesa(fase: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MFootColors.elite)
        if (fase.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            Text(fase, style = MFootType.secondary, color = MFootColors.ink3)
        }
    }
}

/**
 * Il guasto da cui non si esce da soli.
 *
 * Distinto dagli errori normali di proposito: qui non ha senso offrire "riprova" e basta,
 * perche' quasi sempre manca una configurazione. Il messaggio deve dire **dove guardare**.
 */
@Composable
private fun Guasto(motivo: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            motivo,
            style = MFootType.secondary,
            color = MFootColors.ink2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        GhostButton("Riprova", onRetry)
    }
}
