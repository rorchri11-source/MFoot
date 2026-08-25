package dev.mfoot.android.ui

import dev.mfoot.android.ui.icons.MFootIcons
import dev.mfoot.android.ui.theme.comparsa
import dev.mfoot.android.ui.theme.respiro
import dev.mfoot.android.ui.theme.ricordaIntro
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.AuctionFilter
import dev.mfoot.android.app.AuctionRow
import dev.mfoot.android.data.BidEvent
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
    onFilter: (AuctionFilter) -> Unit = {},
    /** Apre la scheda di un giocatore appena comprato, da cui si contesta. */
    onApriGiocatore: (dev.mfoot.android.app.PlayerRow) -> Unit = {},
) {
    val contestabili = state.contestabili
    // Un orologio condiviso: ricalcolare il tempo residuo dentro ogni riga farebbe
    // ridisegnare la lista a ritmi diversi e la farebbe sembrare nervosa.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = Instant.now()
        }
    }

    if (state.auctions.isEmpty() && contestabili.isEmpty()) {
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
    val visibili = state.asteVisibili

    Column(Modifier.fillMaxSize()) {
        // Gli acquisti dentro la finestra stanno **sopra le aste**, non dentro: sono la
        // cosa che scade prima e l'unica su cui si puo' ancora fare qualcosa. Un acquisto
        // di dieci minuti fa contestato adesso e' un'asta; fra dodici ore non lo e' piu'.
        if (contestabili.isNotEmpty()) {
            Contestabili(contestabili, myClubId, now, onApriGiocatore)
        }

        Filtri(state, onFilter)

        if (visibili.isEmpty() && contestabili.isNotEmpty() && state.auctions.isEmpty()) {
            Spacer(Modifier.height(MFootSpacing.section))
            return@Column
        }

        if (visibili.isEmpty()) {
            Vuoto(
                buildString {
                    append(
                        when (state.auctionFilter) {
                            AuctionFilter.MIE -> "Non hai aperto nessuna asta."
                            AuctionFilter.ALTRUI -> "Le aste aperte le hai aperte tutte tu."
                            AuctionFilter.OFFERTE -> "Non hai offerto su nessuna asta."
                            AuctionFilter.TUTTE -> "Nessuna asta aperta."
                        },
                    )
                    if (state.auctions.isNotEmpty()) {
                        append("\n\nCe ne sono ").append(state.auctions.size)
                        append(" in tutto: tocca «Tutte».")
                    }
                },
                icona = MFootIcons.cartellino,
            )
            return@Column
        }

        val intro = ricordaIntro(state.auctionFilter)
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(visibili, key = { _, r -> r.auction.id }) { indice, row ->
                AuctionCard(row, myClubId, now, indice, intro) { onOpenBid(row) }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/**
 * Gli acquisti ancora contestabili.
 *
 * ## Perche' hanno una fascia loro
 *
 * Perche' sono l'unico posto del gioco dove **il tempo scade su una cosa gia' successa**.
 * Un'asta la si guarda per decidere se offrire; qui il giocatore ha gia' cambiato squadra,
 * e quello che si guarda e' se lasciarglielo. Metterli in mezzo alle aste vorrebbe dire
 * non farli vedere a nessuno, che e' come non averli fatti.
 *
 * Il proprio acquisto resta in elenco, e non e' ridondanza: e' l'unico modo di sapere
 * quanto manca alla certezza.
 */
@Composable
private fun Contestabili(
    righe: List<dev.mfoot.android.app.PlayerRow>,
    myClubId: Long?,
    now: Instant,
    onApri: (dev.mfoot.android.app.PlayerRow) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(MFootSpacing.section, MFootSpacing.related, MFootSpacing.section, 4.dp),
    ) {
        Label("Comprati da poco · si possono ancora contestare")
        Spacer(Modifier.height(9.dp))

        righe.take(4).forEach { row ->
            val acquisto = row.acquisto ?: return@forEach
            val mio = acquisto.buyer == myClubId

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MFootShapes.band)
                    .background(MFootColors.core)
                    .clickable { onApri(row) }
                    .padding(13.dp, 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.player.fullName,
                        style = MFootType.rowTitle,
                        color = MFootColors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            acquisto.contestato -> "Contestato: decide l'asta"
                            mio -> "Tuo, se nessuno si fa avanti"
                            else -> "Comprato per ${acquisto.price}"
                        },
                        style = MFootType.chip,
                        color = if (acquisto.contestato) MFootColors.gamble else MFootColors.ink3,
                    )
                }

                Text(
                    acquisto.tempoRimasto(now),
                    style = MFootType.value,
                    color = if (mio) MFootColors.elite else MFootColors.gamble,
                )
            }
            Spacer(Modifier.height(7.dp))
        }
    }
}

/**
 * I quattro filtri, ognuno col suo numero.
 *
 * ## Perche' il numero sta sul chip
 *
 * Perche' e' il modo di accorgersi che una manca. Con quindici aste in corso, la domanda
 * «sono tutte qui?» non ha risposta guardando un elenco che si scorre: ha risposta
 * guardando un totale. Se «Tutte» dice 15 e la somma di «Aperte da me» e «Degli altri»
 * dice 15, l'elenco e' completo — e se un giorno non lo fosse, si vedrebbe subito.
 */
@Composable
private fun Filtri(state: AppState.Dentro, onFilter: (AuctionFilter) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(MFootSpacing.section, 10.dp, MFootSpacing.section, 8.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AuctionFilter.entries.forEach { filtro ->
                val quante = state.quanteAste(filtro)
                Chip("${filtro.label} $quante", filtro == state.auctionFilter) {
                    onFilter(filtro)
                }
            }
        }
    }
}

@Composable
private fun AuctionCard(
    row: AuctionRow,
    myClubId: Long?,
    now: Instant,
    indice: Int,
    intro: Boolean,
    onClick: () -> Unit,
) {
    val leading = row.auction.isLeading(myClubId)
    val involved = row.auction.hasMyBid

    // Il respiro solo sotto i cinque minuti. Le altre restano ferme, ed e' per questo
    // che quella si vede: se pulsassero tutte, non direbbe piu' niente.
    val inScadenza = java.time.Duration.between(now, row.auction.endsAt).toMinutes() in 0..4

    Scheda(
        Modifier
            .padding(horizontal = MFootSpacing.section, vertical = 5.dp)
            .comparsa(indice, intro)
            .respiro(inScadenza),
        onClick = onClick,
    ) {
    Row(
        Modifier.padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(42.dp)
                .background(
                    when {
                        leading -> MFootColors.elite
                        involved -> MFootColors.gamble
                        else -> MFootColors.lineStrong
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
                        append(" · ").append(row.auction.bidCount)
                        append(if (row.auction.bidCount == 1) " offerta" else " offerte")
                        // Quante **squadre**, non quante offerte: sette rilanci di una
                        // persona sola sono una coda, sette di quattro club sono una gara,
                        // e col solo totale si leggono identiche.
                        if (row.auction.bidders > 1) {
                            append(" di ").append(row.auction.bidders).append(" squadre")
                        }
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

            // Chi l'ha aperta. Cambia cosa significa l'asta: uno svincolato messo in
            // vetrina da un avversario e un giocatore che quell'avversario sta **vendendo**
            // sono due situazioni diverse, e finora si leggevano identiche.
            Spacer(Modifier.height(2.dp))
            Text(
                if (row.startedByMe) "l'hai aperta tu"
                else row.starterName?.let { "aperta da $it" } ?: "aperta dalla lega",
                style = MFootType.chip,
                color = if (row.startedByMe) MFootColors.elite else MFootColors.ink3,
            )
        }

        // Lo stato della propria posizione prima del prezzo: e' la cosa che si cerca
        // scorrendo, e leggerla richiede meno di un secondo.
        if (involved) {
            Cartellino(
                if (leading) "in testa" else "superato",
                fondo = (if (leading) MFootColors.elite else MFootColors.gamble)
                    .copy(alpha = 0.16f),
                inchiostro = if (leading) MFootColors.elite else MFootColors.gamble,
            )
            Spacer(Modifier.width(MFootSpacing.related))
        }

        Text(
            Money(row.auction.currentPrice).formatShort(),
            style = MFootType.price,
            color = MFootColors.elite,
        )
    }
    }
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
    storia: List<BidEvent>,
    myClubId: Long?,
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
            // Scorrevole: con la cronologia sotto, su un telefono corto il pulsante
            // dell'offerta finiva fuori schermo e l'asta diventava impossibile da fare.
            .verticalScroll(rememberScrollState())
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
            tooLow -> Notice("L'offerta minima è ${Money(minimum).format()}.", MFootColors.gamble)
            tooMuch -> Notice("Non hai abbastanza crediti disponibili.", MFootColors.gamble)
            row.auction.myMax != null -> Notice(
                "La tua offerta massima ora è ${row.auction.myMax}. Si può solo alzare.",
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
                "secondi la prolunga: vince chi valuta di più, non chi ha il dito più veloce.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        Spacer(Modifier.height(26.dp))
        Cronologia(row, storia, myClubId)
        Spacer(Modifier.height(30.dp))
    }
}

/**
 * Chi ha offerto, in ordine dall'ultimo.
 *
 * ## Cosa si vede e cosa no, e perche'
 *
 * Si vede **il nome e il prezzo a cui l'asta e' arrivata dopo la sua offerta**. Non si vede
 * fino a quanto quel club sarebbe disposto a spingersi: quello e' il massimo dichiarato, e
 * resta segreto fino alla chiusura.
 *
 * La differenza non e' un dettaglio. Il prezzo l'hanno visto tutti — sta scritto in cima
 * all'asta — e sapere chi ce l'ha portato non aggiunge nessuna informazione riservata,
 * aggiunge il **contesto**: se il prezzo e' salito da 12 a 40 in dieci minuti perche' due
 * club se lo stanno contendendo, e' una cosa; se e' salito perche' uno solo ha alzato la
 * sua asticella, e' un'altra. Il massimo invece no: sapendolo si offre quel numero piu'
 * uno e si vince sempre, e l'asta smette di essere un'asta.
 *
 * A chiusura cade anche l'ultimo segreto, e i massimi si leggono nella scheda «Concluse».
 */
@Composable
private fun Cronologia(row: AuctionRow, storia: List<BidEvent>, myClubId: Long?) {
    Label("Chi ha offerto")
    Spacer(Modifier.height(6.dp))

    if (storia.isEmpty()) {
        Text(
            if (row.auction.bidCount == 0) {
                "Nessuno, ancora. Sei il primo."
            } else {
                "${row.auction.bidCount} offerte. L'elenco arriva col prossimo aggiornamento " +
                    "del database: serve la migrazione 0023."
            },
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
        return
    }

    // Il riepilogo che dice se e' una gara.
    //
    // Prima c'era solo l'elenco, e per capire se ci fossero due club o cinque bisognava
    // leggere tutte le righe e ricordarsi i nomi. Il numero risponde in un colpo d'occhio,
    // che e' quello che si cerca aprendo un'asta a cui si sta pensando di partecipare.
    val quanti = storia.map { it.clubId }.distinct().size
    Text(
        "$quanti ${if (quanti == 1) "squadra dentro" else "squadre dentro"} · " +
            "${storia.size} ${if (storia.size == 1) "offerta" else "offerte"}",
        style = MFootType.chip,
        color = MFootColors.ink2,
    )
    Spacer(Modifier.height(9.dp))

    storia.forEach { evento ->
        val mio = myClubId != null && evento.clubId == myClubId
        val capofila = evento.clubId == row.auction.leaderClubId
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (mio) MFootColors.elite.copy(alpha = 0.08f) else MFootColors.bg,
                    RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (mio) "${evento.clubName} (tu)" else evento.clubName,
                    style = MFootType.chip,
                    color = if (mio) MFootColors.elite else MFootColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (capofila) {
                    Spacer(Modifier.height(1.dp))
                    Text("in testa adesso", style = MFootType.chip, color = MFootColors.elite)
                }
            }
            // «Ha offerto», non «prezzo a».
            //
            // E' lo stesso numero — il prezzo pubblico dopo quella mossa — ma la frase
            // cambia cosa si sta leggendo: una colonna di prezzi e' l'andamento dell'asta,
            // una colonna di offerte e' l'elenco di chi ha fatto cosa. La seconda e'
            // quella che serve, ed e' quella che era stata chiesta.
            Text(
                evento.publicPrice?.let { "ha offerto ${Money(it).formatShort()}" }
                    ?: "ha offerto",
                style = MFootType.value,
                color = if (mio) MFootColors.elite else MFootColors.ink2,
            )
        }
        Spacer(Modifier.height(3.dp))
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "L'importo è il prezzo a cui l'asta è arrivata con quella mossa. Fin dove ognuno " +
            "sarebbe disposto a spingersi resta segreto fino alla chiusura: si legge poi, " +
            "nella scheda Concluse.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
}
