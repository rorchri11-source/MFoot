package dev.mfoot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.data.ConnectionStatus
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.android.world.PlayerRow
import dev.mfoot.android.world.RoleFilter
import dev.mfoot.android.world.WorldUiState

/**
 * La lista dei giocatori del mondo — **registro calmo**.
 *
 * Questa schermata si scorre per venti minuti cercando un terzino, quindi deve stare
 * zitta: densita' alta, colore spento, nessun effetto. Il teatro sta nella scheda, che si
 * guarda una alla volta prima di spendere sessanta crediti.
 */
@Composable
fun PlayerListScreen(
    state: WorldUiState,
    onQuery: (String) -> Unit,
    onFilter: (RoleFilter) -> Unit,
    onSelect: (PlayerRow) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg),
    ) {
        ListHeader(state, onQuery, onFilter)

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MFootColors.elite)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.visible, key = { it.player.id.value }) { row ->
                    PlayerListRow(row) { onSelect(row) }
                }
            }
        }
    }
}

@Composable
private fun ListHeader(
    state: WorldUiState,
    onQuery: (String) -> Unit,
    onFilter: (RoleFilter) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MFootColors.line, MFootShapes.field)
                .border(1.dp, MFootColors.line, MFootShapes.field)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⌕", style = MFootType.rowTitle, color = MFootColors.ink3)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (state.query.isEmpty()) {
                    Text("Cerca un giocatore…", style = MFootType.rowTitle, color = MFootColors.ink3)
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MFootType.rowTitle.copy(color = MFootColors.ink),
                    cursorBrush = SolidColor(MFootColors.elite),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(MFootSpacing.related))

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RoleFilter.entries.forEach { filter ->
                FilterChip(filter.label, filter == state.filter) { onFilter(filter) }
            }
        }

        Spacer(Modifier.height(MFootSpacing.related))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (state.loading) {
                    "GENERAZIONE DEL MONDO…"
                } else {
                    "${state.visible.size} SVINCOLATI · ORDINATI PER OVERALL"
                },
                style = MFootType.label,
                color = MFootColors.ink3,
            )
            ConnectionDot(state.connection)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MFootColors.line),
    )
}

/**
 * Lo stato del collegamento al database, discreto ma sempre visibile.
 *
 * L'app funziona comunque senza database — il mondo lo genera in locale — quindi questo
 * non e' un errore bloccante: e' un'informazione. Ma va mostrata, o si finisce per non
 * capire perche' la lega non si salva.
 */
@Composable
private fun ConnectionDot(status: ConnectionStatus) {
    val color = when (status) {
        is ConnectionStatus.Connected -> MFootColors.elite
        is ConnectionStatus.Checking -> MFootColors.ink3
        else -> MFootColors.gamble
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(color, RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(6.dp))
        Text(status.label.uppercase(), style = MFootType.label, color = MFootColors.ink3)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MFootType.chip,
        color = if (selected) MFootColors.bg else MFootColors.ink2,
        modifier = Modifier
            .background(if (selected) MFootColors.ink else MFootColors.line, MFootShapes.pill)
            .border(1.dp, MFootColors.line, MFootShapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

/**
 * Una riga.
 *
 * Il **segnale di crescita** accanto all'overall e' cio' che rende la lista utile: si
 * trova un prospetto scorrendo, senza dover aprire una scheda per volta.
 */
@Composable
private fun PlayerListRow(row: PlayerRow, onClick: () -> Unit) {
    val player = row.player

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MFootSpacing.section, vertical = MFootSpacing.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(MFootColors.line, RoundedCornerShape(10.dp))
                .border(1.dp, MFootColors.line, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(player.primaryPosition.short, style = MFootType.label, color = MFootColors.ink2)
        }

        Spacer(Modifier.width(MFootSpacing.related))

        Column(Modifier.weight(1f)) {
            Text(
                player.fullName,
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${player.age} anni · ${player.nationality}",
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
        }

        GrowthBadge(row)

        Spacer(Modifier.width(MFootSpacing.related))

        Text(
            player.overall.toString(),
            style = MFootType.overallRow,
            color = MFootColors.rating(player.overall),
        )

        Spacer(Modifier.width(MFootSpacing.related))

        Text(
            row.value.toString(),
            style = MFootType.value,
            color = MFootColors.ink3,
            modifier = Modifier.width(32.dp),
        )
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MFootColors.line),
    )
}

@Composable
private fun GrowthBadge(row: PlayerRow) {
    val upside = row.hasUpside
    Text(
        text = row.growthLabel,
        style = MFootType.chip,
        color = if (upside) MFootColors.gamble else MFootColors.ink3,
        modifier = Modifier
            .background(
                if (upside) MFootColors.gamble.copy(alpha = 0.11f) else MFootColors.line,
                RoundedCornerShape(6.dp),
            )
            .border(
                1.dp,
                if (upside) MFootColors.gamble.copy(alpha = 0.25f) else MFootColors.line,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
