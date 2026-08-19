package dev.mfoot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.AuctionRow
import dev.mfoot.core.model.Money
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Il mercato.
 *
 * ## Perche' non c'e' nessun conto alla rovescia al secondo
 *
 * Perche' non serve. Con l'offerta massima si dichiara il proprio limite e si va via: il
 * sistema difende la posizione da solo. Un cronometro che scorre creerebbe un'urgenza
 * finta e riporterebbe esattamente il problema che il gioco vuole evitare — controllare
 * il telefono ogni ora per non farsi soffiare un giocatore.
 *
 * Il tempo che manca si aggiorna ogni dieci secondi, che basta a non sembrare fermo.
 */
@Composable
fun AuctionList(
    state: AppState.Dentro,
    onOpenBid: (AuctionRow) -> Unit,
    onRefresh: () -> Unit,
) {
    // Un orologio condiviso: ricalcolare il tempo residuo dentro ogni riga farebbe
    // ridisegnare la lista a ritmi diversi e la farebbe sembrare nervosa.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = Instant.now()
        }
    }

    if (state.auctions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Nessuna asta aperta.",
                    style = MFootType.secondary,
                    color = MFootColors.ink3,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Apri la scheda di uno svincolato e mettilo all'asta.",
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "aggiorna",
                    style = MFootType.chip,
                    color = MFootColors.elite,
                    modifier = Modifier.clickable(onClick = onRefresh).padding(8.dp),
                )
            }
        }
        return
    }

    val myClubId = state.lega.myClub?.id

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.auctions, key = { it.auction.id }) { row ->
            AuctionCard(row, myClubId, now) { onOpenBid(row) }
        }
    }
}

@Composable
private fun AuctionCard(row: AuctionRow, myClubId: Long?, now: Instant, onClick: () -> Unit) {
    val leading = row.auction.isLeading(myClubId)
    val involved = row.auction.hasMyBid

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MFootSpacing.section, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(38.dp)
                .background(
                    when {
                        leading -> MFootColors.elite
                        involved -> MFootColors.gamble
                        else -> MFootColors.line
                    },
                    RoundedCornerShape(2.dp),
                ),
        )

        Spacer(Modifier.width(MFootSpacing.related))

        Column(Modifier.weight(1f)) {
            Text(
                row.label,
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    // Funziona per i giocatori e per lo staff: prima mostrava il ruolo e
                    // l'overall solo se c'era un giocatore, e le aste sullo staff restavano
                    // senza niente sotto al nome.
                    row.dettaglio?.let { append(it).append(" · ") }
                    append(row.auction.timeLeft(now))
                    if (row.auction.bidCount > 0) {
                        append(" · ").append(row.auction.bidCount).append(" offerte")
                    }
                },
                style = MFootType.chip,
                color = MFootColors.ink3,
            )

            // Chi e' in testa, sempre — non solo quando sei tu.
            //
            // Il nome del capofila si calcolava e si mostrava soltanto in fondo alla riga,
            // sotto il prezzo, dove si legge come una didascalia del numero invece che
            // come "questa asta la sta vincendo lui". E' l'informazione per cui si apre il
            // mercato: senza, un'asta e' un prezzo e un orologio.
            // Quando sei tu in testa lo dice gia il distintivo a destra: ripeterlo qui
            // sarebbe la stessa informazione due volte sulla stessa riga.
            if (!leading) {
                row.leaderName?.let { capofila ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "in testa: $capofila",
                        style = MFootType.chip,
                        color = MFootColors.gamble,
                    )
                }
            }
            if (row.leaderName == null && row.auction.bidCount == 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "nessuno ha ancora offerto",
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                )
            }
        }

        // Lo stato della propria posizione prima del prezzo: e' la cosa che si cerca
        // scorrendo, e leggerla richiede meno di un secondo.
        if (involved) {
            Text(
                if (leading) "in testa" else "superato",
                style = MFootType.chip,
                color = if (leading) MFootColors.elite else MFootColors.gamble,
                modifier = Modifier
                    .background(
                        (if (leading) MFootColors.elite else MFootColors.gamble)
                            .copy(alpha = 0.11f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(MFootSpacing.related))
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                Money(row.auction.currentPrice).formatShort(),
                style = MFootType.price,
                color = MFootColors.ink,
            )
        }
    }

    Hairline()
}

/**
 * Il foglio per offrire.
 *
 * ## Si dichiara il massimo, non il rilancio
 *
 * E' la differenza fra un mercato giocabile da persone che lavorano e uno che premia chi
 * ha il telefono in mano. Il testo lo dice esplicitamente, perche' chi arriva da altri
 * giochi si aspetta di dover rilanciare a mano e altrimenti offre il minimo per poi
 * scoprire di essere stato superato nel sonno.
 */
@Composable
fun BidSheet(
    row: AuctionRow,
    available: Int,
    minimumRaise: Int,
    onBid: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val minimum = row.auction.minimumBid(minimumRaise)
    var amount by remember(row.auction.id) {
        mutableStateOf(maxOf(minimum, row.auction.myMax ?: 0).toString())
    }
    // Si accetta quello che la gente scrive: 1,5M, 1500, 700K. Chi viene dal fantacalcio
    // digita il numero nudo, chi pensa in milioni digita la sigla: rifiutarne una vuol dire
    // un'offerta che non viene fatta.
    val value = Money.parse(amount)?.thousands ?: 0
    val tooLow = value < minimum
    val tooMuch = value > available + (row.auction.myMax ?: 0)

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .padding(MFootSpacing.section),
    ) {
        Text(
            "‹ chiudi",
            style = MFootType.chip,
            color = MFootColors.ink3,
            modifier = Modifier.clickable(onClick = onClose).padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(18.dp))

        Text(row.label, style = MFootType.playerName, color = MFootColors.ink)
        row.player?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "${it.player.primaryPosition.label} · ${it.player.age} anni · " +
                    "overall ${it.player.overall} · valutato ${it.value}",
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
        }

        Spacer(Modifier.height(28.dp))

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Label("Prezzo corrente")
                Text(
                    Money(row.auction.currentPrice).format(),
                    style = MFootType.overallLarge,
                    color = MFootColors.ink,
                )
            }
            Column(Modifier.weight(1f)) {
                Label("Disponibili")
                Text(
                    Money(available).format(),
                    style = MFootType.overallLarge,
                    color = if (available > 0) MFootColors.elite else MFootColors.gamble,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        MFootField(
            value = amount,
            onValueChange = { testo -> amount = testo.filter { c -> c.isDigit() || c in ",.MmKk" }.take(10) },
            placeholder = Money(minimum).format(),
            label = "La tua offerta massima",
            imeAction = ImeAction.Done,
        )

        Spacer(Modifier.height(10.dp))
        Text(
            "Dichiari il tuo limite, non il rilancio. Il prezzo sale da solo quanto basta " +
                "per restare in testa, e si ferma appena supera gli altri: se offri 60 e il " +
                "secondo si ferma a 32, paghi 33.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(minimum, minimum + 5, minimum + 15, minimum + 40).forEach { quick ->
                if (quick <= available + (row.auction.myMax ?: 0)) {
                    Chip(Money(quick).formatShort(), value == quick) { amount = quick.toString() }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        when {
            tooLow -> Notice("L'offerta minima e' ${Money(minimum).format()}.", MFootColors.gamble)
            tooMuch -> Notice("Non hai abbastanza crediti disponibili.", MFootColors.gamble)
            row.auction.myMax != null -> Notice(
                "La tua offerta massima ora e' ${row.auction.myMax}. Si puo' solo alzare.",
                MFootColors.ink2,
            )
        }

        Spacer(Modifier.height(MFootSpacing.related))
        PrimaryButton(
            text = "Offri fino a ${Money(value).format()}",
            onClick = { onBid(value) },
            enabled = !tooLow && !tooMuch,
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "L'asta si chiude fra ${row.auction.timeLeft()}. Un rilancio negli ultimi " +
                "secondi la prolunga: vince chi valuta di piu', non chi ha il dito piu' veloce.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
    }
}
