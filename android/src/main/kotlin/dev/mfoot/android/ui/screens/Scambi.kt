package dev.mfoot.android.ui.screens

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
import dev.mfoot.android.app.TradeDraft
import dev.mfoot.android.app.TradesState
import dev.mfoot.android.data.TradeRow
import dev.mfoot.android.ui.Chip
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.PrimaryButton
import dev.mfoot.android.ui.kit.CrestBadge
import dev.mfoot.android.ui.settings.MoneyField
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.model.Money
import dev.mfoot.core.model.Player

/**
 * Gli scambi fra squadre.
 *
 * ## Perche' e' la cosa che mancava di piu'
 *
 * In una lega fra amici la trattativa e' meta' del gioco, e senza scambi l'unico modo di
 * prendere un giocatore altrui e' aspettare che lo metta all'asta — cioe' mai, se e' bravo.
 * Le chiacchiere avvengono gia' nel gruppo del telefono; qui si formalizzano.
 *
 * ## Perche' le proposte ricevute stanno in cima
 *
 * Perche' sono l'unica cosa che richiede una risposta. Una proposta mandata si guarda per
 * curiosita'; una ricevuta e' qualcuno che aspetta te, e finche' non rispondi non succede
 * niente.
 */
@Composable
fun ScambiScreen(
    state: AppState.Dentro,
    scambi: TradesState,
    onNuovo: (Long) -> Unit,
    onEdit: (TradeDraft) -> Unit,
    onInvia: () -> Unit,
    onAnnulla: () -> Unit,
    onRispondi: (Long, Boolean) -> Unit,
    onRitira: (Long) -> Unit,
    onChiudiAvviso: () -> Unit,
) {
    val mio = state.lega.myClub
    if (mio == null) {
        Vuoto("Prima serve un club.")
        return
    }

    val bozza = scambi.bozza
    if (bozza != null) {
        Composizione(state, scambi, bozza, onEdit, onInvia, onAnnulla)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.padding(MFootSpacing.section)) {
            scambi.errore?.let {
                Notice(it, MFootColors.gamble, Modifier.clickable(onClick = onChiudiAvviso))
                Spacer(Modifier.height(MFootSpacing.related))
            }
            scambi.avviso?.let {
                Notice(it, MFootColors.elite, Modifier.clickable(onClick = onChiudiAvviso))
                Spacer(Modifier.height(MFootSpacing.related))
            }

            Label("Proponi uno scambio a")
            Spacer(Modifier.height(9.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.lega.clubs.filterNot { it.id == mio.id }.forEach { club ->
                    Column(
                        Modifier
                            .background(MFootColors.core, MFootShapes.band)
                            .border(1.dp, MFootColors.lineStrong, MFootShapes.band)
                            .clickable { onNuovo(club.id) }
                            .padding(10.dp)
                            .width(78.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CrestBadge(club.crest, Modifier.size(38.dp), club.shortName)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            club.name,
                            style = MFootType.chip,
                            color = MFootColors.ink2,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Sezione("Ricevute", scambi.ricevute(mio.id), state, mio.id) { trade ->
            Row(horizontalArrangement = Arrangement.spacedBy(MFootSpacing.related)) {
                GhostButton("Rifiuta", { onRispondi(trade.id, false) }, Modifier.weight(1f))
                PrimaryButton(
                    text = scambi.busy ?: "Accetta",
                    onClick = { onRispondi(trade.id, true) },
                    modifier = Modifier.weight(1f),
                    enabled = scambi.busy == null,
                )
            }
        }

        Sezione("Mandate", scambi.mandate(mio.id), state, mio.id) { trade ->
            GhostButton("Ritira", { onRitira(trade.id) })
        }

        Sezione("Concluse", scambi.concluse(), state, mio.id, azioni = null)

        if (scambi.trades.isEmpty() && scambi.letto) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nessuno scambio. Tocca una squadra qui sopra per proporne uno.",
                    style = MFootType.secondary,
                    color = MFootColors.ink3,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Sezione(
    titolo: String,
    righe: List<TradeRow>,
    state: AppState.Dentro,
    myClubId: Long,
    azioni: (@Composable (TradeRow) -> Unit)?,
) {
    if (righe.isEmpty()) return

    Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 8.dp)) {
        Label("$titolo · ${righe.size}")
    }

    righe.forEach { trade ->
        Column(Modifier.fillMaxWidth().padding(MFootSpacing.section, 12.dp)) {
            val altro = state.lega.clubs.firstOrNull {
                it.id == if (trade.isIncoming(myClubId)) trade.fromClub else trade.toClub
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                altro?.let { CrestBadge(it.crest, Modifier.size(30.dp)) }
                Spacer(Modifier.width(9.dp))
                Text(
                    altro?.name ?: "Club sconosciuto",
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(trade.status.label, style = MFootType.chip, color = coloreStato(trade))
            }

            Spacer(Modifier.height(9.dp))

            // Chi da' cosa, dal punto di vista di chi guarda. "Ricevi / Dai" e non
            // "offerti / chiesti": chi legge vuole sapere cosa entra e cosa esce da casa
            // sua, non da quale lato della proposta stava scritto.
            val ricevo = if (trade.isIncoming(myClubId)) trade.offered else trade.wanted
            val do_ = if (trade.isIncoming(myClubId)) trade.wanted else trade.offered
            val soldi = if (trade.isIncoming(myClubId)) trade.cash else -trade.cash

            Colonna("Ricevi", ricevo, soldi.takeIf { it > 0 }, state, MFootColors.elite)
            Spacer(Modifier.height(6.dp))
            Colonna("Dai", do_, (-soldi).takeIf { it > 0 }, state, MFootColors.gamble)

            if (trade.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("“${trade.message}”", style = MFootType.chip, color = MFootColors.ink2)
            }
            if (trade.answer.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(trade.answer, style = MFootType.chip, color = MFootColors.ink3)
            }

            if (azioni != null) {
                Spacer(Modifier.height(MFootSpacing.related))
                azioni(trade)
            }
        }
        Hairline()
    }
}

@Composable
private fun Colonna(
    etichetta: String,
    ids: List<Long>,
    soldi: Int?,
    state: AppState.Dentro,
    colore: androidx.compose.ui.graphics.Color,
) {
    if (ids.isEmpty() && soldi == null) return

    val nomi = ids.mapNotNull { id ->
        state.lega.players.firstOrNull { it.id.value == id }?.let {
            "${it.shortName} (${it.primaryPosition.short} ${it.overall})"
        }
    }
    val pezzi = nomi + listOfNotNull(soldi?.let { Money(it).formatShort() })

    Row {
        Text(
            etichetta,
            style = MFootType.label,
            color = colore,
            modifier = Modifier.width(46.dp),
        )
        Text(
            pezzi.joinToString(" · "),
            style = MFootType.chip,
            color = MFootColors.ink2,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun coloreStato(trade: TradeRow) = when (trade.status.name) {
    "ACCETTATA" -> MFootColors.elite
    "PROPOSTA" -> MFootColors.gamble
    else -> MFootColors.ink3
}

// ------------------------------------------------------------------------ comporre

/**
 * Il modulo della proposta.
 *
 * ## Perche' le due rose stanno una sopra l'altra
 *
 * Perche' uno scambio e' un confronto: si guarda cosa si da' e cosa si prende **insieme**.
 * Con due schermate separate bisognerebbe ricordarsi a memoria cosa si e' scelto di la',
 * e la proposta la si compone al buio.
 */
@Composable
private fun Composizione(
    state: AppState.Dentro,
    scambi: TradesState,
    bozza: TradeDraft,
    onEdit: (TradeDraft) -> Unit,
    onInvia: () -> Unit,
    onAnnulla: () -> Unit,
) {
    val mio = state.lega.myClub ?: return
    val altro = state.lega.clubs.firstOrNull { it.id == bozza.withClub } ?: return
    val miaRosa = state.lega.squadOf(mio.id)
    val suaRosa = state.lega.squadOf(altro.id)

    Column(Modifier.fillMaxSize().background(MFootColors.bg)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(MFootSpacing.section),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CrestBadge(altro.crest, Modifier.size(42.dp), altro.shortName)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Proposta a ${altro.name}", style = MFootType.rowTitle, color = MFootColors.ink)
                    Text(
                        "${suaRosa.size} in rosa · ${Money(altro.available).formatShort()} disponibili",
                        style = MFootType.chip,
                        color = MFootColors.ink3,
                    )
                }
            }

            scambi.errore?.let {
                Spacer(Modifier.height(MFootSpacing.related))
                Notice(it, MFootColors.gamble)
            }

            Spacer(Modifier.height(MFootSpacing.section))
            Label("Chiedi a lui · ${bozza.wanted.size}")
            Spacer(Modifier.height(8.dp))
            Scelta(suaRosa, bozza.wanted) { id ->
                onEdit(bozza.copy(wanted = bozza.wanted.toggle(id)))
            }

            Spacer(Modifier.height(MFootSpacing.section))
            Label("Offri tu · ${bozza.offered.size}")
            Spacer(Modifier.height(8.dp))
            Scelta(miaRosa, bozza.offered) { id ->
                onEdit(bozza.copy(offered = bozza.offered.toggle(id)))
            }

            Spacer(Modifier.height(MFootSpacing.section))
            Label("Conguaglio")
            Spacer(Modifier.height(4.dp))
            Text(
                if (bozza.cash >= 0) {
                    "Aggiungi ${Money(bozza.cash).format()} ai giocatori che offri."
                } else {
                    "Gli chiedi ${Money(-bozza.cash).format()} oltre ai giocatori."
                },
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MoneyField(kotlin.math.abs(bozza.cash), true) { valore ->
                    val segno = if (bozza.cash < 0) -1 else 1
                    onEdit(bozza.copy(cash = valore * segno))
                }
                Spacer(Modifier.width(MFootSpacing.related))
                Chip("Metto io", bozza.cash >= 0) { onEdit(bozza.copy(cash = kotlin.math.abs(bozza.cash))) }
                Spacer(Modifier.width(6.dp))
                Chip("Chiedo io", bozza.cash < 0) { onEdit(bozza.copy(cash = -kotlin.math.abs(bozza.cash))) }
            }

            Spacer(Modifier.height(30.dp))
        }

        Column(
            Modifier.fillMaxWidth().background(MFootColors.core).padding(MFootSpacing.section),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MFootSpacing.related)) {
                GhostButton("Annulla", onAnnulla, Modifier.weight(1f))
                PrimaryButton(
                    text = scambi.busy ?: if (bozza.isEmpty) "Proposta vuota" else "Manda",
                    onClick = onInvia,
                    modifier = Modifier.weight(1f),
                    enabled = !bozza.isEmpty && scambi.busy == null,
                )
            }
        }
    }
}

@Composable
private fun Scelta(rosa: List<Player>, scelti: Set<Long>, onToggle: (Long) -> Unit) {
    if (rosa.isEmpty()) {
        Text("Rosa vuota.", style = MFootType.chip, color = MFootColors.ink3)
        return
    }

    rosa.sortedByDescending { it.overall }.forEach { p ->
        val scelto = p.id.value in scelti
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (scelto) MFootColors.elite.copy(alpha = 0.10f) else MFootColors.bg,
                    MFootShapes.field,
                )
                .clickable { onToggle(p.id.value) }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                p.primaryPosition.short,
                style = MFootType.label,
                color = MFootColors.ink3,
                modifier = Modifier.width(34.dp),
            )
            Text(
                p.shortName,
                style = MFootType.rowTitle,
                color = if (scelto) MFootColors.elite else MFootColors.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${p.overall}",
                style = MFootType.overallRow,
                color = MFootColors.rating(p.overall),
            )
        }
        Spacer(Modifier.height(3.dp))
    }
}

/** Aggiunge o toglie: e' il gesto di una lista a scelta multipla. */
private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id

@Composable
private fun Vuoto(testo: String) {
    Box(
        Modifier.fillMaxSize().background(MFootColors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(testo, style = MFootType.secondary, color = MFootColors.ink3)
    }
}
