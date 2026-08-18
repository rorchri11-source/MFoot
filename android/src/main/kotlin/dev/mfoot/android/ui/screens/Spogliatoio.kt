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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.SpogliatoioState
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
import dev.mfoot.core.conversation.ConversationTopic
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player

/**
 * Lo spogliatoio: chi ha qualcosa da dirti, e cosa rispondergli.
 *
 * ## Perche' l'argomento non lo scegli tu
 *
 * Verrebbe naturale mettere un elenco di argomenti e lasciare scegliere. Ma una
 * conversazione non comincia perche' il manager ha voglia di parlare: comincia perche' il
 * giocatore ha un problema, e quel problema e' uno solo e lo sa lui. Sceglierlo dalla lista
 * vorrebbe dire poter "parlare del rinnovo" a chi il rinnovo non lo ha chiesto — cioe' dire
 * a qualcuno che non lo stai ascoltando, e infatti nel motore quella risposta va male.
 *
 * ## Perche' chi sta bene non compare
 *
 * Un elenco di venti giocatori con scritto "tutto bene" accanto a diciotto di loro
 * nasconde i due che contano. Qui ci sono solo quelli che hanno qualcosa da dire, e se non
 * c'e' nessuno la schermata lo dice in una riga.
 */
@Composable
fun SpogliatoioScreen(
    state: AppState.Dentro,
    spogliatoio: SpogliatoioState,
    onApri: (Long) -> Unit,
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

    val rosa = state.lega.squadOf(club.id)
    val giornata = MatchDay(state.lega.league.currentMatchDay)

    val aperto = spogliatoio.conPlayerId?.let { id -> rosa.firstOrNull { it.id.value == id } }
    if (aperto != null) {
        Colloquio(aperto, spogliatoio, giornata, onParla, onChiudi)
        return
    }

    // Solo chi ha qualcosa da dire, e i piu' scontenti in cima: e' l'ordine in cui uno
    // affronterebbe davvero lo spogliatoio.
    val daSentire = rosa
        .map { it to argomentoDi(it) }
        .filter { (_, argomento) -> argomento != null }
        .sortedBy { (giocatore, _) -> giocatore.morale }

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        spogliatoio.avviso?.let {
            Box(Modifier.padding(MFootSpacing.section)) {
                Notice(it, MFootColors.elite, Modifier.clickable(onClick = onChiudi))
            }
        }

        if (daSentire.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(44.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (rosa.isEmpty()) "Rosa vuota: non c'e' nessuno con cui parlare."
                    else "Nessuno ha niente da ridire. Spogliatoio tranquillo.",
                    style = MFootType.secondary,
                    color = MFootColors.ink3,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 10.dp)) {
            Label("${daSentire.size} vogliono parlarti")
        }

        daSentire.forEach { (giocatore, argomento) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onApri(giocatore.id.value) }
                    .padding(MFootSpacing.section, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp, 23.dp)
                        .background(MFootColors.core, MFootShapes.field)
                        .border(1.dp, MFootColors.line, MFootShapes.field),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        giocatore.primaryPosition.short,
                        style = MFootType.label,
                        color = MFootColors.ink2,
                    )
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
                    Text(
                        argomento!!.label,
                        style = MFootType.chip,
                        color = MFootColors.gamble,
                    )
                }

                Morale(giocatore.morale)
            }
            Hairline()
        }

        Spacer(Modifier.height(30.dp))
    }
}

/**
 * Di cosa vuole parlare questo giocatore.
 *
 * Uno solo, il piu' urgente. Chi vuole andarsene lo dice prima di ogni altra cosa: e' la
 * lamentela che, se ignorata, finisce con una cessione.
 */
private fun argomentoDi(player: Player): ConversationTopic? = when {
    player.morale < 20 -> ConversationTopic.RICHIESTA_CESSIONE
    player.morale < 35 -> ConversationTopic.MORALE_BASSO
    player.morale < 50 -> ConversationTopic.POCO_MINUTAGGIO
    player.form < 40 -> ConversationTopic.PRESTAZIONI_SCARSE
    else -> null
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
    spogliatoio: SpogliatoioState,
    giornata: MatchDay,
    onParla: (Long, ConversationOption) -> Unit,
    onChiudi: () -> Unit,
) {
    val argomento = argomentoDi(player) ?: ConversationTopic.MORALE_BASSO
    val opzioni = ConversationEngine.optionsFor(argomento)

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
                            .border(1.dp, MFootColors.line, MFootShapes.pill)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(MFootSpacing.section))
        Text(
            "“${argomento.prompt}”",
            style = MFootType.rowTitle,
            color = MFootColors.ink2,
        )

        spogliatoio.rispostaUltima?.let {
            Spacer(Modifier.height(MFootSpacing.section))
            Notice(it, if (spogliatoio.deltaUltimo >= 0) MFootColors.elite else MFootColors.gamble)
        }

        Spacer(Modifier.height(MFootSpacing.section))
        Label("Cosa gli dici")
        Spacer(Modifier.height(10.dp))

        opzioni.forEach { opzione ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MFootColors.core, MFootShapes.field)
                    .border(1.dp, MFootColors.lineStrong, MFootShapes.field)
                    .clickable { onParla(player.id.value, opzione) }
                    .padding(14.dp),
            ) {
                Text(opzione.text, style = MFootType.rowTitle, color = MFootColors.ink)
                if (opzione.createsPromise != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "E' una promessa: il tick la controlla giornata per giornata, e se " +
                            "non la mantieni il crollo e' peggiore di non aver detto niente.",
                        style = MFootType.chip,
                        color = MFootColors.gamble,
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
        }

        Spacer(Modifier.height(MFootSpacing.section))
        GhostButton("Torna allo spogliatoio", onChiudi)
        Spacer(Modifier.height(30.dp))
    }
}
