package dev.mfoot.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mfoot.android.ui.PlayerDetailScreen
import dev.mfoot.android.ui.PlayerListScreen
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootMotion
import dev.mfoot.android.ui.theme.MFootTheme
import dev.mfoot.android.world.WorldViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MFootTheme { MFootApp() } }
    }
}

@Composable
private fun MFootApp(viewModel: WorldViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .systemBarsPadding(),
    ) {
        PlayerListScreen(
            state = state,
            onQuery = viewModel::onQuery,
            onFilter = viewModel::onFilter,
            onSelect = viewModel::select,
            onCreateLeague = {
                viewModel.createLeague(
                    name = "Lega di prova",
                    accessCode = "MFOOT",
                    nickname = "admin",
                )
            },
        )

        // La scheda entra dal basso sopra la lista: cosi' si capisce che si sta
        // guardando un dettaglio e non si e' cambiata schermata.
        AnimatedVisibility(
            visible = state.selected != null,
            enter = slideInVertically(
                animationSpec = tween(MFootMotion.normal, easing = MFootMotion.easing),
                initialOffsetY = { it / 3 },
            ) + fadeIn(tween(MFootMotion.fast, easing = MFootMotion.easing)),
            exit = slideOutVertically(
                animationSpec = tween(MFootMotion.fast, easing = MFootMotion.easing),
                targetOffsetY = { it / 3 },
            ) + fadeOut(tween(MFootMotion.fast, easing = MFootMotion.easing)),
        ) {
            state.selected?.let { row ->
                PlayerDetailScreen(row) { viewModel.select(null) }
            }
        }
    }

    BackHandler(enabled = state.selected != null) { viewModel.select(null) }
}
