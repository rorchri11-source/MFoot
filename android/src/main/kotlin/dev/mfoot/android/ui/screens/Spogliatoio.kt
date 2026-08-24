package dev.mfoot.android.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.SpogliatoioState
import dev.mfoot.android.data.OpenConversation
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.conversation.ConversationEngine
import dev.mfoot.core.conversation.ConversationOption
import dev.mfoot.core.conversation.LeagueFacts
import dev.mfoot.core.model.Player

/**
 * Lo spogliatoio: chi ha qualcosa da dirti, e perche'.
 *
 * ## Perche' l'argomento non lo scegli tu
 *
 * Verrebbe naturale mettere un elenco di argomenti e lasciare scegliere. Ma una
 * conversazione non comincia perche' il manager ha voglia di parlare: comincia perche' e'
 * successo qualcosa. Sceglierlo dalla lista vorrebbe dire poter "parlare del rinnovo" a chi
 * il rinnovo non lo ha chiesto — cioe' dire a qualcuno che non lo stai ascoltando.
 *
 * ## Perche' la causa e' scritta accanto al nome
 *
 * "Poco spazio" e' un'etichetta; **"3 partite senza scendere in campo: 17a, 18a, 19a"** e'
 * una ragione. La prima si puo' inventare, la seconda no, e la differenza si sente subito:
 * e' cio' che distingue un gioco che sa cosa e' successo da uno che tira a indovinare da
 * una soglia sul morale, che e' precisamente quello che faceva prima questa schermata.
 *
 * ## Perche' esiste anche la convocazione
 *
 * Perche' un manager deve poter parlare a chi vuole. Ma un colloquio che non nasce da
 * niente rende un terzo, e non si puo' ripetere prima di tre giornate: senza quei due
 * limiti sarebbe di nuovo il pulsante "alza morale" con un altro nome.
 */
@Composable
fun SpogliatoioScreen(
    state: AppState.Dentro,
    spogliatoio: SpogliatoioState,
    onCarica: () -> Unit,
    onApri: (Long) -> Unit,
    onConvoca: (Long) -> Unit,
    onParla: (Long, ConversationOption) -> Unit,
    onChiudi: () -> Unit,
) {
    val club = state.lega.myClub
    if (club == null) {
        Box(
            Modifier.fillMaxSize().background(MFootColors.bg),
            contentAlignment = Alignment.Center,
        ) {
            Text("Prima serve un club.", style = MFootType.secondary, color = MFootColors.ink3)
        }
        return
    }

    LaunchedEffect(club.id) { onCarica() }

    val rosa = state.lega.squadOf(club.id)
    val oggi = state.lega.league.currentMatchDay

    val aperto = spogliatoio.conPlayerId?.let { id -> rosa.firstOrNull { it.id.value == id } }
    val colloquio = aperto?.let { spogliatoio.spogliatoio.apertoPer(it.id.value) }
    if (aperto != null && colloquio != null) {
        Colloquio(aperto, colloquio, spogliatoio, onParla, onChiudi)
        return
    }

    // Solo chi ha qualcosa da dire, e i piu' scontenti in cima: e' l'ordine in cui uno
    // affronterebbe davvero lo spogliatoio.
    val perId = rosa.associateBy { it.id.value }
    val daSentire = spogliatoio.spogliatoio.aperti
        .mapNotNull { c -> perId[c.playerId]?.let { it to c } }
        .sortedBy { (giocatore, _) -> giocatore.morale }

    val sentiti = daSentire.map { it.first.id.value }.toSet()
    val altri = rosa.filterNot { it.id.value in sentiti }

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        spogliatoio.avviso?.let {
            Box(Modifier.padding(MFootSpacing.section)) {
                Notice(it, MFootColors.gamble)
            }
        }

        if (rosa.isEmpty()) {
            Vuoto("Rosa vuota: non c'è nessuno con cui parlare.")
            return@Column
        }

        Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 10.dp)) {
            Label(
                when {
                    !spogliatoio.letto -> "Spogliatoio"
                    daSentire.isEmpty() -> "Nessuno ha niente da ridire"
                    daSentire.size == 1 -> "Uno vuole parlarti"
                    else -> "${daSentire.size} vogliono parlarti"
                },
            )
        }

        daSentire.forEach { (giocatore, conversazione) ->
            Riga(
                giocatore = giocatore,
                sotto = conversazione.cause.ifBlank { conversazione.topic.label },
                coloreSotto = MFootColors.gamble,
                onClick = { onApri(giocatore.id.value) },
            )
        }

        if (altri.isNotEmpty()) {
            Spacer(Modifier.height(MFootSpacing.section))
            Column(Modifier.padding(MFootSpacing.section, 0.dp, MFootSpacing.section, 10.dp)) {
                Label("Convoca")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Puoi chiamare chi vuoi, ma un discorso che non nasce da niente rende " +
                        "poco, e c'è da aspettare prima di ripeterlo.",
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                )
            }

            altri.forEach { giocatore ->
                val attesa = LeagueFacts.attesaResidua(
                    spogliatoio.spogliatoio.ultimoColloquio[giocatore.id.value],
                    oggi,
                )

                Riga(
                    giocatore = giocatore,
                    sotto = if (attesa > 0) {
                        "Gli hai già parlato: fra $attesa giornate"
                    } else {
                        "Convocalo"
                    },
                    coloreSotto = if (attesa > 0) MFootColors.ink3 else MFootColors.good,
                    onClick = { if (attesa == 0 && !spogliatoio.inCorso) onConvoca(giocatore.id.value) },
                )
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Vuoto(testo: String) {
    Box(Modifier.fillMaxWidth().padding(44.dp), contentAlignment = Alignment.Center) {
        Text(testo, style = MFootType.secondary, color = MFootColors.ink3, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Riga(
    giocatore: Player,
    sotto: String,
    coloreSotto: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(MFootSpacing.section, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp, 23.dp)
                .background(MFootColors.core, MFootShapes.field),
            contentAlignment = Alignment.Center,
        ) {
            Text(giocatore.primaryPosition.short, style = MFootType.label, color = MFootColors.ink2)
        }
        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f)) {
            Text(
                giocatore.shortName,
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(sotto, style = MFootType.chip, color = coloreSotto, maxLines = 2)
        }

        Morale(giocatore.morale)
    }
    Hairline()
}

@Composable
private fun Morale(valore: Int) {
    val colore = when {
        valore >= 70 -> MFootColors.elite
        valore >= 45 -> MFootColors.good
        valore >= 25 -> MFootColors.gamble
        else -> MFootColors.low
    }
    Column(horizontalAlignment = Alignment.End) {
        Text("$valore", style = MFootType.overallRow, color = colore)
        Text("morale", style = MFootType.label, color = MFootColors.ink3)
    }
}

// ---------------------------------------------------------------------- il colloquio

@Composable
private fun Colloquio(
    player: Player,
    conversazione: OpenConversation,
    spogliatoio: SpogliatoioState,
    onParla: (Long, ConversationOption) -> Unit,
    onChiudi: () -> Unit,
) {
    val opzioni = ConversationEngine.optionsFor(conversazione.topic)
    val risposto = spogliatoio.rispostaUltima != null

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(MFootSpacing.section),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(player.fullName, style = MFootType.playerName, color = MFootColors.ink)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${player.primaryPosition.short} · ${player.age} anni · morale ${player.morale}",
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                )
            }
            Text(
                "Chiudi",
                style = MFootType.rowTitle,
                color = MFootColors.ink2,
                modifier = Modifier.clickable(onClick = onChiudi).padding(8.dp),
            )
        }

        // I tratti sono l'informazione che rende sensata la scelta: le stesse parole a un
        // testa calda e a un uomo spogliatoio danno esiti opposti.
        if (player.traits.isNotEmpty()) {
            Spacer(Modifier.height(MFootSpacing.related))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                player.traits.forEach { tratto ->
                    Text(
                        tratto.label,
                        style = MFootType.chip,
                        color = MFootColors.ink2,
                        modifier = Modifier
                            .background(MFootColors.core, MFootShapes.pill)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Il fatto prima delle parole: e' il pezzo che spiega perche' si sta parlando.
        if (conversazione.cause.isNotBlank()) {
            Spacer(Modifier.height(MFootSpacing.section))
            Text(conversazione.cause, style = MFootType.chip, color = MFootColors.gamble)
        }

        Spacer(Modifier.height(MFootSpacing.related))
        Text("“${conversazione.topic.prompt}”", style = MFootType.rowTitle, color = MFootColors.ink2)

        if (conversazione.spontaneous) {
            Spacer(Modifier.height(MFootSpacing.related))
            Text(
                "Lo hai chiamato tu: non aveva niente da dirti, e quello che gli dirai " +
                    "peserà molto meno.",
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
        }

        spogliatoio.rispostaUltima?.let {
            Spacer(Modifier.height(MFootSpacing.section))
            Notice(it, if (spogliatoio.deltaUltimo >= 0) MFootColors.elite else MFootColors.gamble)
        }

        if (!risposto) {
            Spacer(Modifier.height(MFootSpacing.section))
            Label("Cosa gli dici")
            Spacer(Modifier.height(10.dp))

            opzioni.forEach { opzione ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MFootColors.core, MFootShapes.field)
                        .clickable { onParla(player.id.value, opzione) }
                        .padding(14.dp),
                ) {
                    Text(opzione.text, style = MFootType.rowTitle, color = MFootColors.ink)
                    if (opzione.createsPromise != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "è una promessa: il tick conta le partite giocate, e se non la " +
                                "mantieni il crollo è peggiore di non aver detto niente.",
                            style = MFootType.chip,
                            color = MFootColors.gamble,
                        )
                    }
                }
                Spacer(Modifier.height(9.dp))
            }
        }

        Spacer(Modifier.height(MFootSpacing.section))
        GhostButton(if (risposto) "Torna allo spogliatoio" else "Non dirgli niente", onChiudi)
        Spacer(Modifier.height(30.dp))
    }
}
