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
import dev.mfoot.android.ui.DoorScreen
import dev.mfoot.android.ui.FoundingScreen
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.PlayerDetailScreen
import dev.mfoot.android.ui.PlayerListScreen
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
                PlayerListScreen(
                    state = current,
                    onQuery = viewModel::onQuery,
                    onFilter = viewModel::onFilter,
                    onScope = viewModel::onScope,
                    onSelect = viewModel::select,
                    onDismissNotice = viewModel::chiudiAvviso,
                    onLeave = viewModel::lasciaLega,
                    onFoundClub = viewModel::fondaClub,
                )

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
                        PlayerDetailScreen(row) { viewModel.select(null) }
                    }
                }
            }
        }
    }

    BackHandler(enabled = state.canGoBack()) {
        when (val current = state) {
            is AppState.Dentro ->
                if (current.browse.selected != null) viewModel.select(null)

            is AppState.Porta ->
                if (current.mode != DoorMode.SCELTA) viewModel.apriPorta(DoorMode.SCELTA)

            else -> Unit
        }
    }
}

/** C'e' un passo indietro possibile dentro l'app, o il tasto deve chiuderla? */
private fun AppState.canGoBack(): Boolean = when (this) {
    is AppState.Dentro -> browse.selected != null
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
