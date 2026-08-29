package dev.mfoot.android.ui.pitch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.mfoot.android.data.MatchRating
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.PitchLayout
import dev.mfoot.core.model.Position

/**
 * La formazione di una squadra sul campo, con il voto su ogni giocatore.
 *
 * ## Perche' un campo e non un elenco
 *
 * Perche' la domanda «come ha giocato la mia squadra» ha una forma. In un elenco ordinato
 * per voto non si vede che la difesa ha preso cinque e l'attacco sette: si vedono undici
 * numeri in fila, e per capire dove stava il problema bisogna ricordarsi a memoria chi
 * giocava dove. Sul campo si vede in un colpo, ed e' il motivo per cui ogni app di calcio
 * lo disegna cosi'.
 *
 * ## Perche' il ruolo arriva dal database e non dal giocatore
 *
 * Perche' e' il ruolo **in cui ha giocato quella partita**, che non e' sempre il suo: un
 * centrale schierato terzino va disegnato dove stava. Il tick lo scrive in `appearances`
 * dal 2026-08-29 — prima non lo scriveva nessuno, e un campo del tabellino era
 * impossibile da costruire.
 *
 * Senza il ruolo salvato — le partite giocate prima — si ricade sull'elenco: meglio una
 * risposta piu' povera che un campo con undici giocatori messi a caso.
 */
@Composable
fun CampoFormazione(
    modulo: String?,
    voti: List<MatchRating>,
    nome: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val formation = modulo?.let { m -> Formation.entries.firstOrNull { it.name == m } }
    val titolari = voti.filter { it.started }

    // Il campo si disegna solo se i ruoli ci sono tutti: con meta' dei posti vuoti si
    // legge peggio di un elenco, e sembra che manchino dei giocatori.
    val piazzabili = formation != null &&
        titolari.size == formation.positions.size &&
        titolari.all { it.position != null }

    if (!piazzabili) {
        ElencoTitolari(titolari, nome, modifier)
        return
    }

    val posti = PitchLayout.of(formation!!)
    // A ogni posto del modulo il giocatore che ha giocato in quel ruolo. Si consuma
    // l'elenco man mano, cosi' due terzini nello stesso ruolo non finiscono sullo stesso
    // punto.
    val rimasti = titolari.toMutableList()
    val disposti = formation.positions.map { ruolo ->
        val scelto = rimasti.firstOrNull { it.position == ruolo.name }
            ?: rimasti.firstOrNull()
        scelto?.also { rimasti.remove(it) }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(320.dp)
            .background(MFootColors.core, MFootShapes.band),
    ) {
        Canvas(Modifier.fillMaxSize()) { disegnaCampoVerticale() }

        Layout(
            content = {
                disposti.forEachIndexed { i, voto ->
                    Giocatore(voto, nome, formation.positions.getOrNull(i))
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { misurabili, vincoli ->
            val posizionati = misurabili.map { it.measure(vincoli.copy(minWidth = 0, minHeight = 0)) }
            layout(vincoli.maxWidth, vincoli.maxHeight) {
                posizionati.forEachIndexed { i, p ->
                    val (x, y) = posti.getOrNull(i) ?: (0.5f to 0.5f)
                    // Il modulo e' descritto per una squadra che attacca verso l'alto; qui
                    // il campo e' verticale con la porta avversaria in cima, quindi la y
                    // si usa com'e'.
                    val cx = (vincoli.maxWidth * x).toInt() - p.width / 2
                    val cy = (vincoli.maxHeight * y).toInt() - p.height / 2
                    p.place(
                        cx.coerceIn(0, vincoli.maxWidth - p.width),
                        cy.coerceIn(0, vincoli.maxHeight - p.height),
                    )
                }
            }
        }
    }
}

/** Un giocatore sul campo: il voto in evidenza, il nome sotto. */
@Composable
private fun Giocatore(voto: MatchRating?, nome: (Long) -> String, ruolo: Position?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(74.dp),
    ) {
        Text(
            voto?.let { "%.1f".format(it.rating) } ?: "–",
            style = MFootType.value,
            color = MFootColors.bg,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(coloreVoto(voto?.rating ?: 0.0), MFootShapes.pill)
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            voto?.let { nome(it.playerId).cognome() } ?: (ruolo?.short ?: ""),
            style = MFootType.label,
            color = MFootColors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        // Gol e cartellini sotto il nome: sono i tre fatti che si cercano guardando una
        // formazione, e scritti a parole occuperebbero tre righe per giocatore.
        val segni = buildString {
            repeat(voto?.goals ?: 0) { append("⚽") }
            repeat(voto?.assists ?: 0) { append("↗") }
            if ((voto?.red ?: 0) > 0) append("▮") else if ((voto?.yellow ?: 0) > 0) append("▯")
        }
        if (segni.isNotEmpty()) {
            Text(segni, style = MFootType.label, color = MFootColors.gamble)
        }
    }
}

/**
 * Il colore del voto.
 *
 * Le stesse quattro fasce del resto dell'app, e senza verde: e' la regola dettata il
 * 2026-08-23 e vale anche qui.
 */
private fun coloreVoto(voto: Double) = when {
    voto >= 7.0 -> MFootColors.elite
    voto >= 6.0 -> MFootColors.ink
    voto > 0.0 -> MFootColors.gamble
    else -> MFootColors.line
}

/** Il ripiego: chi ha giocato, in fila, quando i ruoli non ci sono. */
@Composable
private fun ElencoTitolari(voti: List<MatchRating>, nome: (Long) -> String, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        voti.forEach { v ->
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    nome(v.playerId),
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%.1f".format(v.rating),
                    style = MFootType.value,
                    color = MFootColors.bg,
                    modifier = Modifier
                        .background(coloreVoto(v.rating), MFootShapes.pill)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** Solo il cognome: sul campo non c'e' spazio per due parole. */
private fun String.cognome(): String = trim().substringAfterLast(' ').ifBlank { this }

private fun DrawScope.disegnaCampoVerticale() {
    val linea = MFootColors.line.copy(alpha = 0.5f)
    val bordo = 10.dp.toPx()
    val w = size.width - bordo * 2
    val h = size.height - bordo * 2
    val tratto = Stroke(width = 1.5.dp.toPx())

    drawRect(linea, Offset(bordo, bordo), Size(w, h), style = tratto)
    drawLine(linea, Offset(bordo, bordo + h / 2f), Offset(bordo + w, bordo + h / 2f), 1.5.dp.toPx())
    drawCircle(linea, w * 0.14f, Offset(bordo + w / 2f, bordo + h / 2f), style = tratto)

    // Le due aree: quella in alto e' la porta avversaria, ed e' il verso in cui il modulo
    // e' descritto.
    listOf(bordo, bordo + h - h * 0.16f).forEach { top ->
        drawRect(
            linea,
            Offset(bordo + w * 0.22f, top),
            Size(w * 0.56f, h * 0.16f),
            style = tratto,
        )
    }
}
