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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.ui.icons.MFootIcons
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
        ListHeader(state, onQuery, onFilter, onDismissNotice)

        if (state.browse.scope == ListScope.ASTE) {
            AuctionList(state, onOpenBid, onRefreshAuctions, onAuctionFilter)
            return@Column
        }

        val visible = state.visible
        if (visible.isEmpty()) {
            EmptyState(state)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(visible, key = { it.player.id.value }) { row ->
                    PlayerListRow(row) { onSelect(row) }
                }
            }
        }
    }
}

/**
 * L'intestazione delle colonne: cosa sono i due numeri a destra.
 *
 * Nel riferimento sta sopra la lista e non dentro ogni riga, ed e' il motivo per cui li'
 * i numeri si possono lasciare nudi. Senza, «84» e «12» sono due cifre e basta, e per
 * capirle bisogna aprire una scheda.
 */
@Composable
private fun Colonne() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MFootSpacing.section + 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label("Calciatore", Modifier.weight(1f))
        Label("OVR", Modifier.width(38.dp))
        Label("Valore", Modifier.width(52.dp))
    }
}

@Composable
private fun ListHeader(
    state: AppState.Dentro,
    onQuery: (String) -> Unit,
    onFilter: (RoleFilter) -> Unit,
    onDismissNotice: () -> Unit,
) {
    val browse = state.browse

    // Niente margine in cima: la riga di schede sopra ne ha gia' uno suo, e sommandoli
    // fra i chip e il campo di ricerca restava un buco alto quanto una riga.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(MFootSpacing.section, 0.dp, MFootSpacing.section, 10.dp),
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

        // La lente disegnata, non il carattere `⌕`: quel glifo cambia forma e peso da un
        // telefono all'altro, e su parecchi non esiste affatto.
        // I chip dell'**ambito** stavano qui, e non facevano niente.
        //
        // Erano «Svincolati · Aste · Tutto il mondo · La mia rosa», cioe' le stesse tre
        // destinazioni gia' presenti nella riga di schede sopra, piu' una quarta. E non
        // potevano funzionare: `Lista` nel Router impone l'ambito che la rotta porta con
        // se', a ogni ricomposizione, perche' chi entra da «Svincolati» deve vedere gli
        // svincolati e non l'ultimo filtro lasciato attivo. Toccarli non cambiava lo
        // schermo — occupavano una riga intera per insegnare a non fidarsi dei comandi.
        Ricerca(browse.query, onQuery, "Cerca un giocatore…")

        // I filtri per ruolo non hanno senso sulle aste, che sono poche e si guardano
        // tutte: lasciarli visibili dove non fanno niente insegna a ignorarli.
        if (browse.scope != ListScope.ASTE) {
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    "${state.visible.size} da prendere · $presi hanno già un club"

                ListScope.TUTTI ->
                    "${state.visible.size} in tutto il mondo · $presi con un club"

                ListScope.MIA_ROSA ->
                    "${state.visible.size} nella tua rosa"
            },
        )
    }

    if (browse.scope != ListScope.ASTE) Colonne()
}
/** Una lista vuota deve dire **perche'** e' vuota, o sembra che l'app sia rotta. */
@Composable
private fun EmptyState(state: AppState.Dentro) {
    val message = when {
        state.browse.query.isNotBlank() -> "Nessun giocatore trovato per \"${state.browse.query}\"."
        state.browse.scope == ListScope.MIA_ROSA && state.lega.myClub == null ->
            "Non hai ancora un club in questa lega."
        state.browse.scope == ListScope.MIA_ROSA -> "La tua rosa è ancora vuota."
        else -> "Nessun giocatore in questa selezione."
    }

    Vuoto(message, icona = MFootIcons.cerca)
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

    Scheda(Modifier.padding(horizontal = MFootSpacing.section), onClick) {
        Row(
            Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Il tondo con il ruolo, e sopra il gettone della crescita.
            //
            // Nel riferimento qui c'e' la faccia del calciatore con il ruolo appiccicato
            // in un angolo. MFoot genera i suoi giocatori e le facce non ce le ha, quindi
            // il ruolo prende il posto centrale — ed e' il dato che si cerca davvero
            // scorrendo, molto piu' di un ritratto.
            Box(Modifier.size(46.dp)) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MFootColors.bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        player.primaryPosition.short,
                        style = MFootType.value,
                        color = MFootColors.ink2,
                    )
                }
                if (row.hasUpside) {
                    // Il **segnale di crescita** e' cio' che rende la lista utile: si trova
                    // un prospetto scorrendo, senza aprire una scheda per volta.
                    Text(
                        row.growthLabel,
                        style = MFootType.label,
                        color = MFootColors.onAccent,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .clip(RoundedCornerShape(50))
                            .background(MFootColors.gamble)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    player.fullName,
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(bandiera(player.nationality)).append(" ")
                            .append(player.age).append(" anni")
                        row.club?.let { append(" · ").append(it.shortName) }
                    },
                    style = MFootType.secondary,
                    color = MFootColors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                player.overall.toString(),
                style = MFootType.overallRow,
                color = MFootColors.rating(player.overall),
                modifier = Modifier.width(38.dp),
            )
            Text(
                Money(row.value).formatShort(),
                style = MFootType.value,
                color = MFootColors.ink2,
                modifier = Modifier.width(52.dp),
            )
        }
    }
}
