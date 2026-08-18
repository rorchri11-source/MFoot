package dev.mfoot.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.AppViewModel
import dev.mfoot.android.app.DoorMode
import dev.mfoot.android.app.Route
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
import dev.mfoot.android.ui.PlayerListScreen
import dev.mfoot.android.ui.TableScreen
import dev.mfoot.android.ui.screens.CalendarioScreen
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootMotion
import dev.mfoot.android.ui.theme.MFootTheme
import dev.mfoot.android.ui.theme.MFootType

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MFootTheme { MFootApp() } }
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
    val scambi by viewModel.trades.collectAsStateWithLifecycle()
    val divisioni by viewModel.divisioni.collectAsStateWithLifecycle()
    val spogliatoio by viewModel.spogliatoio.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .systemBarsPadding(),
    ) {
        when (val current = state) {
            is AppState.Avvio -> Attesa("")

            is AppState.Caricamento -> Attesa(current.fase)

            is AppState.Porta -> DoorScreen(
                state = current,
                onMode = viewModel::apriPorta,
                onCreate = viewModel::creaLega,
                onJoin = viewModel::entraInLega,
            )

            is AppState.Guasto -> Guasto(current.motivo, viewModel::avvia)

            is AppState.Calendario -> CalendarioScreen(
                state = current.calendario,
                onMese = viewModel::sfogliaCalendario,
                onGiorno = viewModel::scegliGiorno,
                onChiudi = viewModel::chiudiCalendario,
            )

            is AppState.Classifica -> TableScreen(
                state = current.table,
                onPickCompetition = viewModel::scegliCompetizione,
                onPickTab = viewModel::cambiaSchedaTabella,
                onClose = viewModel::chiudiClassifica,
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
                    drawerOpen = current.drawerOpen,
                    onToggleDrawer = viewModel::apriChiudiMenu,
                    onNavigate = { route ->
                        // Classifica, calendario e competizioni hanno gia' una schermata
                        // intera loro, con caricamenti propri: si aprono da fuori dal
                        // guscio invece di essere infilate dentro il contenuto.
                        when (route) {
                            is Route.Classifica -> viewModel.apriClassifica(TableTab.CLASSIFICA)
                            is Route.Calendario -> viewModel.apriCalendario()
                            is Route.Competizioni -> viewModel.apriCompetizioni()
                            else -> viewModel.vai(route)
                        }
                    },
                    onLeaveLeague = viewModel::lasciaLega,
                ) {
                    Router(
                        state = current,
                        onNavigate = viewModel::vai,
                        onQuery = viewModel::onQuery,
                        onFilter = viewModel::onFilter,
                        onScope = viewModel::onScope,
                        onSelect = viewModel::select,
                        onOpenBid = viewModel::apriOfferta,
                        onRefreshAuctions = { viewModel.aggiornaAste() },
                        onFoundClub = viewModel::fondaClub,
                        onDismissNotice = viewModel::chiudiAvviso,
                        settings = settings,
                        onConfigChange = viewModel::modificaRegolamento,
                        onConfigSave = viewModel::salvaRegolamento,
                        desk = desk,
                        onLoadMembers = viewModel::caricaPartecipanti,
                        onLoadTick = viewModel::caricaRegistro,
                        scambi = scambi,
                        onLoadTrades = { viewModel.caricaScambi() },
                        onNewTrade = viewModel::nuovoScambio,
                        onEditTrade = viewModel::modificaScambio,
                        onSendTrade = viewModel::inviaScambio,
                        onCancelTrade = viewModel::annullaScambio,
                        onRespondTrade = viewModel::rispondiScambio,
                        onWithdrawTrade = viewModel::ritiraScambio,
                        onDismissTradeNotice = viewModel::chiudiAvvisoScambi,
                        divisioni = divisioni,
                        onAssignDivisions = viewModel::assegnaDivisioni,
                        onCloseSeason = viewModel::chiudiStagione,
                        onDismissDivisionNotice = viewModel::chiudiAvvisoDivisioni,
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
                            onBid = { viewModel.offri(row.auction.id, it) },
                            onClose = { viewModel.apriOfferta(null) },
                        )
                    }
                }

                // La scheda entra dal basso sopra la lista: cosi' si capisce che si sta
                // guardando un dettaglio e non si e' cambiata schermata.
                AnimatedVisibility(
                    visible = current.browse.selected != null,
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
                            // Uno svincolato lo puo' battere chiunque; un tesserato solo
                            // il suo club. La rosa altrui non si tocca — quella si tratta
                            // — ma vendere i propri e' cio' che tiene vivo il mercato dopo
                            // che gli svincolati sono finiti.
                            canAuction = current.lega.myClub != null &&
                                (row.isFreeAgent || row.club?.isMine == true) &&
                                current.auctions.none { it.auction.targetId == row.player.id.value },
                            isSelling = row.club?.isMine == true,
                            // Il pulsante compare solo dove ha senso: sui propri, e solo
                            // se l'eta' lo consente. Mostrarlo su chiunque vorrebbe dire
                            // farlo premere per scoprire da un errore che non si poteva.
                            youthAction = when {
                                row.club?.isMine != true -> null
                                row.isYouth -> "In prima squadra"
                                row.player.age <= current.lega.league.config.rules.youthMaxAge ->
                                    "In Primavera"
                                else -> null
                            },
                            onYouth = { viewModel.spostaSquadra(row) },
                            onAuction = { viewModel.mettiAllAsta(row) },
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
