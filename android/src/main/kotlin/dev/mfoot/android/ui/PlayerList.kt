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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.ListScope
import dev.mfoot.android.app.PlayerRow
import dev.mfoot.android.app.RoleFilter
import dev.mfoot.core.model.Money
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType

/**
 * La lista dei giocatori — **registro calmo**.
 *
 * Questa schermata si scorre per venti minuti cercando un terzino, quindi deve stare
 * zitta: densita' alta, colore spento, nessun effetto. Il teatro sta nella scheda, che si
 * guarda una alla volta prima di spendere sessanta crediti.
 */
@Composable
fun PlayerListScreen(
    state: AppState.Dentro,
    onQuery: (String) -> Unit,
    onFilter: (RoleFilter) -> Unit,
    onScope: (ListScope) -> Unit,
    onSelect: (PlayerRow) -> Unit,
    onDismissNotice: () -> Unit,
    onOpenBid: (dev.mfoot.android.app.AuctionRow) -> Unit,
    onRefreshAuctions: () -> Unit,
    onAuctionFilter: (dev.mfoot.android.app.AuctionFilter) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg),
    ) {
        ListHeader(state, onQuery, onFilter, onScope, onDismissNotice)

        if (state.browse.scope == ListScope.ASTE) {
            AuctionList(state, onOpenBid, onRefreshAuctions, onAuctionFilter)
            return@Column
        }

        val visible = state.visible
        if (visible.isEmpty()) {
            EmptyState(state)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.player.id.value }) { row ->
                    PlayerListRow(row) { onSelect(row) }
                }
            }
        }
    }
}

@Composable
private fun ListHeader(
    state: AppState.Dentro,
    onQuery: (String) -> Unit,
    onFilter: (RoleFilter) -> Unit,
    onScope: (ListScope) -> Unit,
    onDismissNotice: () -> Unit,
) {
    val browse = state.browse

    Column(
        Modifier
            .fillMaxWidth()
            .padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 14.dp),
    ) {
        if (state.errore != null) {
            Spacer(Modifier.height(MFootSpacing.related))
            Notice(state.errore, MFootColors.gamble)
        } else if (state.avviso != null) {
            Spacer(Modifier.height(MFootSpacing.related))
            Notice(
                state.avviso,
                MFootColors.elite,
                Modifier.clickable(onClick = onDismissNotice),
            )
        }

        Spacer(Modifier.height(MFootSpacing.related))

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
                if (browse.query.isEmpty()) {
                    Text("Cerca un giocatore…", style = MFootType.rowTitle, color = MFootColors.ink3)
                }
                BasicTextField(
                    value = browse.query,
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
            ListScope.entries.forEach { scope ->
                Chip(scope.label, scope == browse.scope) { onScope(scope) }
            }
        }

        // I filtri per ruolo non hanno senso sulle aste, che sono poche e si guardano
        // tutte: lasciarli visibili dove non fanno niente insegna a ignorarli.
        if (browse.scope != ListScope.ASTE) {
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RoleFilter.entries.forEach { filter ->
                    Chip(filter.label, filter == browse.filter) { onFilter(filter) }
                }
            }
        }

        Spacer(Modifier.height(MFootSpacing.related))

        // L'intestazione dice **cosa distingue questo elenco dagli altri**.
        //
        // Prima diceva "N giocatori · ordinati per overall" per ogni ambito, e con il mercato
        // appena aperto — quando quasi nessuno ha ancora un club — "Svincolati" e "Tutto il
        // mondo" mostravano gli stessi identici nomi con la stessa identica scritta sopra.
        // Sembravano due voci di menu per la stessa schermata, e a ragione.
        val presi = state.rows.count { !it.isFreeAgent }
        Label(
            when (browse.scope) {
                ListScope.ASTE ->
                    "${state.auctions.size} aste aperte · ${state.myAuctions.size} con una tua offerta"

                ListScope.SVINCOLATI ->
                    "${state.visible.size} da prendere · $presi hanno gia' un club"

                ListScope.TUTTI ->
                    "${state.visible.size} in tutto il mondo · $presi con un club"

                ListScope.MIA_ROSA ->
                    "${state.visible.size} nella tua rosa"
            },
        )
    }

    Hairline()
}
/** Una lista vuota deve dire **perche'** e' vuota, o sembra che l'app sia rotta. */
@Composable
private fun EmptyState(state: AppState.Dentro) {
    val message = when {
        state.browse.query.isNotBlank() -> "Nessun giocatore trovato per \"${state.browse.query}\"."
        state.browse.scope == ListScope.MIA_ROSA && state.lega.myClub == null ->
            "Non hai ancora un club in questa lega."
        state.browse.scope == ListScope.MIA_ROSA -> "La tua rosa e' ancora vuota."
        else -> "Nessun giocatore in questa selezione."
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MFootType.secondary,
            color = MFootColors.ink3,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
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
                buildString {
                    append(bandiera(player.nationality)).append(" ")
                        .append(player.age).append(" anni · ").append(player.nationality)
                    row.club?.let { append(" · ").append(it.shortName) }
                },
                style = MFootType.chip,
                color = MFootColors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            Money(row.value).formatShort(),
            style = MFootType.value,
            color = MFootColors.ink3,
            modifier = Modifier.width(32.dp),
        )

        // Come nella rosa: la riga si tocca e la scheda esiste. Un carattere che vale una
        // stagione di attributi mai guardati.
        Spacer(Modifier.width(4.dp))
        Text("›", style = MFootType.value, color = MFootColors.ink3)
    }

    Hairline()
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
