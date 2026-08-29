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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.ui.icons.MFootIcons
import dev.mfoot.android.ui.kit.CrestBadge
import dev.mfoot.android.ui.theme.comparsa
import dev.mfoot.android.ui.theme.ricordaIntro
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
            // Toccare un acquisto contestabile apre la scheda di quel giocatore, che e'
            // il posto da cui si contesta: la stessa strada di ogni altra decisione di
            // mercato, invece di un foglio che esiste solo qui.
            AuctionList(state, onOpenBid, onRefreshAuctions, onAuctionFilter, onSelect)
            return@Column
        }

        val visible = state.visible
        // La cascata riparte quando cambia l'ambito o la ricerca: e' li' che l'elenco
        // diventa davvero un altro elenco.
        val intro = ricordaIntro(state.browse.scope to state.browse.query)
        if (visible.isEmpty()) {
            EmptyState(state)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(visible, key = { _, r -> r.player.id.value }) { indice, row ->
                    PlayerListRow(row, indice, intro) { onSelect(row) }
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

        /*
         * L'intestazione resta solo dove dice qualcosa che non si vede scorrendo.
         *
         * Le altre — «991 si comprano subito · nessuna attesa, nessuna asta», «1115 da
         * prendere · 14 hanno gia' un club» — sono state tolte il 2026-08-25 su richiesta
         * del proprietario. Erano nate per distinguere fra loro tre elenchi che a mercato
         * appena aperto mostravano gli stessi nomi, e quel problema non c'e' piu': adesso
         * ogni riga dice se il giocatore e' libero o di chi e'.
         *
         * Sulle aste il conto sopravvive perche' «quante hanno una tua offerta» non e'
         * deducibile guardando: e' l'unica di quelle righe che aggiungeva un fatto invece
         * di ripetere quello che c'era sotto.
         */
        if (browse.scope == ListScope.ASTE) {
            Spacer(Modifier.height(MFootSpacing.related))
            Label("${state.auctions.size} aste aperte · ${state.myAuctions.size} con una tua offerta")
        }
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

        // Il listino vuoto ha **due** cause diverse, e dirle e' l'unica cosa che
        // distingue «non c'e' niente in vendita» da «questa parte del gioco non
        // funziona». La seconda e' la conclusione a cui si arriva da soli, ed e' quella
        // che va evitata.
        state.browse.scope == ListScope.LISTINO ->
            "Nessuno in vendita, per adesso.\n\n" +
                "Ci finiscono due categorie: chi un proprietario mette in vendita — " +
                "apri la scheda di un tuo giocatore e tocca «Metti in vendita» — e tutti " +
                "gli svincolati, che ci mette il server al suo primo giro dopo un " +
                "aggiornamento."

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
private fun PlayerListRow(
    row: PlayerRow,
    indice: Int,
    intro: Boolean,
    onClick: () -> Unit,
) {
    val player = row.player

    Scheda(
        Modifier.padding(horizontal = MFootSpacing.section).comparsa(indice, intro),
        onClick,
    ) {
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
                    "${bandiera(player.nationality)} ${player.age} anni",
                    style = MFootType.secondary,
                    color = MFootColors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Di chi e', **su una riga sua e col nome per intero**.
                //
                // Prima era in coda ai dati anagrafici — «🇮🇹 24 anni · Mangao» — e in
                // forma abbreviata: appiccicato dopo l'eta' si legge come un altro dato
                // del giocatore invece che come il suo proprietario, ed e' la prima cosa
                // che si cerca scorrendo il listone. Chiesto esplicitamente il 2026-08-25.
                //
                // Dal 2026-08-29 non e' piu' una riga di testo grigia ma un **riquadro nei
                // colori della maglia**, con lo stemma. Il motivo e' che «di Matletico
                // Mangao» in grigio chiaro, sotto l'eta', si legge come l'ultimo dei dati
                // anagrafici: il proprietario e' la prima cosa che si cerca scorrendo un
                // listone, e con la stessa importanza visiva della nazionalita' non si
                // trova. Colorato si riconosce senza leggerlo.
                //
                // Chi non e' di nessuno **non ha nessun riquadro**: uno svincolato non ha un
                // proprietario vuoto, non ha proprio un proprietario, e disegnargli una
                // targhetta neutra vorrebbe dire dare una risposta a una domanda che non
                // esiste.
                row.club?.let { proprietario ->
                    Spacer(Modifier.height(4.dp))
                    TargaProprietario(proprietario)
                }
            }

            Text(
                player.overall.toString(),
                style = MFootType.overallRow,
                color = MFootColors.rating(player.overall),
                modifier = Modifier.width(38.dp),
            )

            // In vendita, o comprato da poco: due stati che si leggono scorrendo, e senza
            // i quali il listino esisterebbe solo dentro le schede — cioe' per nessuno.
            val acquisto = row.acquisto?.takeIf { it.aperto() }
            when {
                // Il cartellino del prezzo cambia colore sui propri: lavanda piena vuol
                // dire «lo puoi comprare», spento vuol dire «lo stai vendendo tu». Senza
                // la differenza, in un listino in cui adesso compaiono anche i propri,
                // ogni riga sembrerebbe un acquisto possibile.
                row.inVendita != null -> {
                    val mio = row.club?.isMine == true
                    Text(
                        "${row.inVendita.price}",
                        style = MFootType.value,
                        color = if (mio) MFootColors.ink else MFootColors.onAccent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .width(52.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (mio) MFootColors.raised else MFootColors.elite)
                            .padding(vertical = 4.dp),
                    )
                }

                acquisto != null -> Text(
                    acquisto.tempoRimasto(),
                    style = MFootType.chip,
                    color = MFootColors.gamble,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(52.dp),
                )

                else -> Text(
                    Money(row.value).formatShort(),
                    style = MFootType.value,
                    color = MFootColors.ink2,
                    modifier = Modifier.width(52.dp),
                )
            }
        }
    }
}

/**
 * Di chi e' questo giocatore: un riquadro nei colori del club, con lo stemma e il nome.
 *
 * ## Perche' i colori del club e non un colore del tema
 *
 * Perche' in una lega fra amici le squadre si riconoscono dalla maglia prima che dal nome.
 * Scorrendo duecento righe, il colore risponde da solo alla domanda «questo di chi e'?»,
 * e il nome serve solo a confermare.
 *
 * ## Il testo si legge sopra qualunque maglia
 *
 * Il colore lo sceglie il proprietario, quindi puo' essere bianco come nero: l'inchiostro
 * non si puo' fissare. Si decide dalla **luminanza percepita** del fondo — la formula
 * classica, con il verde che pesa piu' del rosso e il blu quasi niente, perche' e' come
 * l'occhio funziona. Con un inchiostro fisso, meta' delle leghe avrebbe avuto targhette
 * illeggibili e nessuno avrebbe saputo dire perche'.
 *
 * Il bordo sottile serve al caso opposto: una maglia quasi del colore dello sfondo
 * sparirebbe, e il riquadro sembrerebbe non esserci.
 */
@Composable
private fun TargaProprietario(club: dev.mfoot.android.data.ClubInfo) {
    val fondo = Color(club.kit.primary)
    val inchiostro = if (fondo.luminance() > 0.5f) Color(0xFF12151A) else Color(0xFFF2F4F7)

    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(fondo)
            .border(1.dp, MFootColors.line, RoundedCornerShape(50))
            .padding(start = 4.dp, top = 3.dp, end = 9.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrestBadge(club.crest, Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            club.name,
            style = MFootType.label,
            color = inchiostro,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
