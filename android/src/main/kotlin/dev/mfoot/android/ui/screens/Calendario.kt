package dev.mfoot.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import dev.mfoot.android.ui.icons.MFootIcons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.CalendarState
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.calendar.CalendarEvent
import dev.mfoot.core.calendar.CalendarEventKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ORA = DateTimeFormatter.ofPattern("HH:mm")
private val GIORNO_ESTESO = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)

/**
 * Il calendario del mese.
 *
 * ## Perche' una griglia e non un elenco
 *
 * Un elenco di partite risponde a "quando gioco la prossima". Una griglia risponde a
 * **"che settimana mi aspetta"**, che e' una domanda diversa e senza risposta finora: con
 * due partite al giorno, contratti che scadono a giornate e aste che chiudono a orari
 * sparsi, la forma di un mese non si ricostruisce scorrendo righe.
 *
 * ## Perche' i colori e non le etichette
 *
 * Perche' una cella e' larga quaranta punti. Un pallino dice "qui c'e' qualcosa e di che
 * tipo" nello spazio che un'etichetta userebbe per dire mezza parola; il dettaglio si
 * apre toccando, che e' un gesto che si fa una volta e non trenta.
 */
@Composable
fun CalendarioScreen(
    state: CalendarState,
    onMese: (Int) -> Unit,
    onGiorno: (LocalDate) -> Unit,
    onPartita: (Long, String, String) -> Unit,
    onChiudi: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Intestazione(state, onMese, onChiudi)

        state.errore?.let {
            Box(Modifier.padding(MFootSpacing.section, 0.dp, MFootSpacing.section, MFootSpacing.section)) {
                Notice(it, MFootColors.gamble)
            }
        }

        Griglia(state, onGiorno)

        Spacer(Modifier.height(MFootSpacing.section))
        Legenda()

        Spacer(Modifier.height(MFootSpacing.section))
        Hairline()
        Dettaglio(state, onPartita)

        Spacer(Modifier.height(20.dp))
        Box(Modifier.padding(horizontal = MFootSpacing.section)) {
            GhostButton("Chiudi", onChiudi)
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Intestazione(state: CalendarState, onMese: (Int) -> Unit, onChiudi: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Freccia(MFootIcons.indietro, "Mese precedente") { onMese(-1) }
        Spacer(Modifier.width(6.dp))

        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                state.mese.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
                    .replaceFirstChar { it.uppercase() },
                style = MFootType.playerName,
                color = MFootColors.ink,
            )
            Text("${state.mese.year}", style = MFootType.label, color = MFootColors.ink3)
        }

        Spacer(Modifier.width(6.dp))
        Freccia(MFootIcons.avanti, "Mese successivo") { onMese(1) }
        Spacer(Modifier.width(4.dp))
        Freccia(MFootIcons.chiudi, "Chiudi", onChiudi)
    }
}

/**
 * Un tondo con una freccia dentro.
 *
 * Erano i caratteri `‹` e `›` in un quadrato: due glifi che ogni carattere di sistema
 * disegna di un peso suo, e che accanto alle icone del resto dell'app si vedevano come
 * corpi estranei.
 */
@Composable
private fun Freccia(
    icona: androidx.compose.ui.graphics.vector.ImageVector,
    descrizione: String,
    onClick: () -> Unit,
) {
    Icon(
        icona,
        contentDescription = descrizione,
        tint = MFootColors.ink2,
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(50))
            .background(MFootColors.core)
            .clickable(onClick = onClick)
            .padding(9.dp),
    )
}

@Composable
private fun Griglia(state: CalendarState, onGiorno: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = MFootSpacing.section)) {
        listOf("L", "M", "M", "G", "V", "S", "D").forEach { lettera ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(lettera, style = MFootType.label, color = MFootColors.ink3)
            }
        }
    }
    Spacer(Modifier.height(6.dp))

    state.griglia.forEach { settimana ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MFootSpacing.section),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            settimana.forEach { giorno ->
                Cella(state, giorno, Modifier.weight(1f)) { onGiorno(giorno) }
            }
        }
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun Cella(
    state: CalendarState,
    giorno: LocalDate,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val fuoriMese = giorno.month != state.mese.month
    val oggi = giorno == state.oggi
    val scelto = giorno == state.giornoMostrato
    val riposo = giorno.dayOfWeek in state.riposi
    val pallini = state.pallini(giorno)

    // Il giorno di riposo si vede dal fondo piu' scuro e non da un pallino: e' l'assenza
    // di impegni, e darle un simbolo la farebbe sembrare un impegno anche lei.
    val fondo = when {
        scelto -> MFootColors.elite.copy(alpha = 0.14f)
        oggi -> MFootColors.core
        riposo && !fuoriMese -> MFootColors.bg
        else -> MFootColors.bg
    }

    Box(
        modifier
            .aspectRatio(0.92f)
            .background(fondo, MFootShapes.field)
            .border(
                1.dp,
                when {
                    scelto -> MFootColors.elite
                    oggi -> MFootColors.lineStrong
                    else -> MFootColors.line
                },
                MFootShapes.field,
            )
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxSize().padding(top = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${giorno.dayOfMonth}",
                style = MFootType.chip,
                color = when {
                    fuoriMese -> MFootColors.ink3.copy(alpha = 0.35f)
                    oggi -> MFootColors.ink
                    riposo -> MFootColors.ink3
                    else -> MFootColors.ink2
                },
            )

            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                pallini.forEach { tipo ->
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(
                                coloreDi(tipo).copy(alpha = if (fuoriMese) 0.35f else 1f),
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Legenda() {
    Column(Modifier.padding(horizontal = MFootSpacing.section)) {
        Label("Cosa vuol dire un colore")
        Spacer(Modifier.height(8.dp))
        CalendarEventKind.entries.chunked(2).forEach { coppia ->
            Row(Modifier.fillMaxWidth()) {
                coppia.forEach { tipo ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(coloreDi(tipo), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(etichettaDi(tipo), style = MFootType.label, color = MFootColors.ink3)
                    }
                }
                if (coppia.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(5.dp))
        }
    }
}

@Composable
private fun Dettaglio(state: CalendarState, onPartita: (Long, String, String) -> Unit) {
    val giorno = state.giornoMostrato
    if (giorno == null) {
        Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
            Text(
                if (state.caricamento) "Leggo il calendario…" else "Non c'è niente in programma.",
                style = MFootType.secondary,
                color = MFootColors.ink3,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val eventi = state.eventiDi(giorno)

    Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 8.dp)) {
        Text(
            giorno.format(GIORNO_ESTESO).replaceFirstChar { it.uppercase() },
            style = MFootType.rowTitle,
            color = MFootColors.ink,
        )
        if (state.selezionato == null) {
            Spacer(Modifier.height(2.dp))
            Text("Il prossimo giorno con qualcosa dentro.", style = MFootType.label, color = MFootColors.ink3)
        }
    }

    if (eventi.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
            Text("Niente in programma.", style = MFootType.secondary, color = MFootColors.ink3)
        }
        return
    }

    eventi.forEach { evento -> RigaEvento(evento, onPartita) }
}

@Composable
private fun RigaEvento(evento: CalendarEvent, onPartita: (Long, String, String) -> Unit) {
    // Le partite giocate si aprono; tutto il resto no. Rendere toccabile una riga che non
    // porta da nessuna parte e' peggio che lasciarla ferma: si prova una volta, non succede
    // niente, e da li' in poi non si prova piu' nemmeno su quelle che funzionano.
    val squadre = evento.title.split(" — ")
    val apribile = evento.fixtureId != null && squadre.size == 2

    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (apribile) {
                    Modifier.clickable { onPartita(evento.fixtureId!!, squadre[0], squadre[1]) }
                } else {
                    Modifier
                },
            )
            .padding(MFootSpacing.section, 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(coloreDi(evento.kind), CircleShape))
        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f)) {
            Text(
                evento.title,
                style = MFootType.rowTitle,
                color = if (evento.done) MFootColors.ink2 else MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val inCorso = apribile && !evento.done && evento.at?.let { java.time.LocalDateTime.now().isAfter(it) } == true
            Text(
                when {
                    evento.done -> "${evento.detail} · tocca per rivederla"
                    inCorso -> "si sta giocando · tocca per guardarla"
                    else -> evento.detail
                },
                style = MFootType.chip,
                color = if (evento.done || inCorso) MFootColors.elite else MFootColors.ink3,
            )
        }

        Text(
            // Chi non ha un'ora vale per tutta la giornata, e dirlo e' piu' onesto che
            // scrivere 00:00 accanto a un contratto che scade.
            evento.at?.format(ORA) ?: "in giornata",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
    }
    Hairline()
}

private fun coloreDi(kind: CalendarEventKind): Color = when (kind) {
    CalendarEventKind.PARTITA_TUA -> MFootColors.elite
    CalendarEventKind.PARTITA_ALTRUI -> MFootColors.ink3
    CalendarEventKind.AMICHEVOLE -> MFootColors.good
    CalendarEventKind.ASTA -> MFootColors.gamble
    CalendarEventKind.SCADENZA_CONTRATTO -> MFootColors.low
    CalendarEventKind.SCADENZA_PROMESSA -> MFootColors.low
}

private fun etichettaDi(kind: CalendarEventKind): String = when (kind) {
    CalendarEventKind.PARTITA_TUA -> "La tua partita"
    CalendarEventKind.PARTITA_ALTRUI -> "Partite altrui"
    CalendarEventKind.AMICHEVOLE -> "Amichevole"
    CalendarEventKind.ASTA -> "Asta in chiusura"
    CalendarEventKind.SCADENZA_CONTRATTO -> "Contratto in scadenza"
    CalendarEventKind.SCADENZA_PROMESSA -> "Promessa in scadenza"
}
