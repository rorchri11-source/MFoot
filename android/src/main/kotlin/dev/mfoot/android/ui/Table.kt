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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.TableState
import dev.mfoot.android.app.TableTab
import dev.mfoot.android.data.MatchRow
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import java.time.format.DateTimeFormatter

private val QUANDO = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/**
 * Classifica e calendario — **la Serie A della lega**.
 *
 * ## La classifica non e' salvata da nessuna parte
 *
 * Il database conserva i risultati; punti, differenza reti e ordine si ricalcolano da
 * quelli con i criteri di spareggio scelti dall'admin. Salvarla sarebbe un secondo posto
 * dove la stessa verita' puo' andare alla deriva, e basterebbe un risultato corretto a
 * mano perche' i due non tornino piu'.
 */
@Composable
fun TableScreen(
    state: TableState,
    onPickCompetition: (Long) -> Unit,
    onPickTab: (TableTab) -> Unit,
    onOpenMatch: (MatchRow) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg),
    ) {
        Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 12.dp)) {
            Text(
                "‹ torna alla lega",
                style = MFootType.chip,
                color = MFootColors.ink3,
                modifier = Modifier.clickable(onClick = onClose).padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(12.dp))

            if (state.competitions.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.competitions.forEach { c ->
                        Chip(c.name, c.id == state.selectedId) { onPickCompetition(c.id) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TableTab.entries.forEach { tab ->
                    Chip(tab.label, tab == state.tab) { onPickTab(tab) }
                }
            }
        }

        val view = state.view
        when {
            state.errore != null ->
                Center(state.errore, MFootColors.gamble)

            view == null ->
                Center("Nessuna competizione. L'admin deve ancora crearne una.", MFootColors.ink3)

            view.matches.isEmpty() ->
                Center("Nessuna partita in calendario.", MFootColors.ink3)

            state.tab == TableTab.CLASSIFICA && view.rows.isEmpty() ->
                Center(
                    "Questa competizione non ha una classifica: e' a eliminazione. " +
                        "Guarda il calendario.",
                    MFootColors.ink3,
                )

            else -> Content(state, view.matches, state.tab, onOpenMatch)
        }
    }
}

@Composable
private fun Center(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MFootType.secondary,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun Content(
    state: TableState,
    matches: List<MatchRow>,
    tab: TableTab,
    onOpenMatch: (MatchRow) -> Unit,
) {
    val view = state.view ?: return

    LazyColumn(Modifier.fillMaxSize()) {
        if (tab == TableTab.CLASSIFICA) {
            item { TableHeader() }
            itemsIndexed(view.rows.size) { index ->
                val row = view.rows[index]
                TableRow(
                    position = index + 1,
                    name = state.clubName(row.club.value),
                    played = row.played,
                    won = row.won,
                    drawn = row.drawn,
                    lost = row.lost,
                    goalsFor = row.goalsFor,
                    goalsAgainst = row.goalsAgainst,
                    points = row.points,
                    mine = row.club.value == state.myClubId,
                )
            }
            item { Spacer(Modifier.height(28.dp)) }
        }

        // Le partite raggruppate per turno: e' cosi' che si guarda un calendario, non
        // come un elenco piatto di cinquanta righe.
        if (tab == TableTab.CALENDARIO) {
            matches.groupBy { it.round }.toSortedMap().forEach { (round, ofRound) ->
                item {
                    Column(Modifier.padding(MFootSpacing.section, 16.dp, MFootSpacing.section, 8.dp)) {
                        Label(ofRound.firstOrNull()?.roundLabel?.takeIf { it.isNotBlank() } ?: "Turno $round")
                    }
                }
                items(ofRound, key = { it.id }) { match ->
                    MatchLine(match, state, onOpenMatch)
                }
            }
        }

        item { Spacer(Modifier.height(30.dp)) }
    }
}

/** `itemsIndexed` su un conteggio: evita di dover esporre la lista due volte. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    count: Int,
    content: @Composable (Int) -> Unit,
) = items(count) { content(it) }

@Composable
private fun TableHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MFootSpacing.section, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(26.dp))
        Label("Squadra", Modifier.weight(1f))
        listOf("G", "V", "N", "P", "DR").forEach {
            Label(it, Modifier.width(26.dp).padding(start = 2.dp))
        }
        Label("PT", Modifier.width(30.dp))
    }
    Hairline()
}

/**
 * Una riga di classifica.
 *
 * La propria squadra e' evidenziata: in venti righe di nomi inventati, trovare la propria
 * senza leggerle tutte e' la differenza fra consultare e cercare.
 */
@Composable
private fun TableRow(
    position: Int,
    name: String,
    played: Int,
    won: Int,
    drawn: Int,
    lost: Int,
    goalsFor: Int,
    goalsAgainst: Int,
    points: Int,
    mine: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (mine) MFootColors.elite.copy(alpha = 0.07f) else MFootColors.bg)
            .padding(horizontal = MFootSpacing.section, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            position.toString(),
            style = MFootType.value,
            color = if (position <= 3) MFootColors.elite else MFootColors.ink3,
            modifier = Modifier.width(26.dp),
        )
        Text(
            name,
            style = MFootType.rowTitle,
            color = if (mine) MFootColors.elite else MFootColors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        listOf(played, won, drawn, lost, goalsFor - goalsAgainst).forEach {
            Text(
                it.toString(),
                style = MFootType.chip,
                color = MFootColors.ink3,
                modifier = Modifier.width(26.dp).padding(start = 2.dp),
            )
        }
        Text(
            points.toString(),
            style = MFootType.overallRow,
            color = MFootColors.ink,
            modifier = Modifier.width(30.dp),
        )
    }
    Hairline()
}

@Composable
private fun MatchLine(match: MatchRow, state: TableState, onOpen: (MatchRow) -> Unit) {
    val mine = match.homeClubId == state.myClubId || match.awayClubId == state.myClubId

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (mine) MFootColors.elite.copy(alpha = 0.05f) else MFootColors.bg)
            // Solo le giocate si aprono: una partita che deve ancora cominciare non ha
            // niente da far rivedere.
            .then(if (match.played) Modifier.clickable { onOpen(match) } else Modifier)
            .padding(horizontal = MFootSpacing.section, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${state.clubName(match.homeClubId)} — ${state.clubName(match.awayClubId)}",
                style = MFootType.rowTitle,
                color = if (mine) MFootColors.ink else MFootColors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (match.played) {
                    "tocca per rivederla"
                } else {
                    state.oraDi(match)?.format(QUANDO) ?: "giornata ${match.matchDay}"
                },
                style = MFootType.chip,
                color = if (match.played) MFootColors.elite else MFootColors.ink3,
            )
        }

        Text(
            match.scoreline,
            style = if (match.played) MFootType.price else MFootType.chip,
            color = if (match.played) MFootColors.ink else MFootColors.ink3,
            modifier = Modifier
                .background(
                    if (match.played) MFootColors.core else MFootColors.bg,
                    MFootShapes.field,
                )
                .border(
                    1.dp,
                    if (match.played) MFootColors.lineStrong else MFootColors.line,
                    MFootShapes.field,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    Hairline()
}
