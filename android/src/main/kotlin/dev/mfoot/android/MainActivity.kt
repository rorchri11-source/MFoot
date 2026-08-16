package dev.mfoot.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mfoot.android.ui.PlayerListScreen
import dev.mfoot.android.ui.theme.MFootColors
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
        )
    }
}
