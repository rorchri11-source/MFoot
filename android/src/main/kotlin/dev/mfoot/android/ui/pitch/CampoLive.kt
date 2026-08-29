package dev.mfoot.android.ui.pitch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes

/**
 * Il campo durante la partita: la palla che segue l'azione.
 *
 * ## Cos'e' e cosa non e'
 *
 * E' **decorativo**. Non decide niente, non si tocca, non cambia il risultato: legge la
 * zona che il motore ha gia' scritto in ogni evento della timeline e la disegna. Chiesto
 * cosi' dal proprietario il 2026-08-29 — «animazioni, visual, ma non gameplay, solo visivo
 * e decorativo».
 *
 * ## Perche' i dati c'erano gia' tutti
 *
 * `MatchEngine` ragiona su una griglia di **nove zone** — tre fasce per tre altezze — e
 * scrive in ogni evento in quale si trovava la palla. La scriveva dal primo giorno, e per
 * mesi non l'ha letta nessuno: la partita si guardava come un elenco di frasi. Qui non c'e'
 * nessuna simulazione nuova, solo la stessa partita disegnata invece che raccontata.
 *
 * ## Perche' la palla scivola invece di saltare
 *
 * Perche' un pallino che si teletrasporta ogni minuto e' un cursore, non una palla. La
 * transizione di un secondo e mezzo copre l'intervallo fra due eventi e trasforma nove
 * posizioni discrete in un movimento continuo — che e' tutto quello che serve perche'
 * l'occhio ci veda una partita.
 */
@Composable
fun CampoLive(
    zona: String?,
    /** Vero se la palla ce l'ha la squadra di casa: decide da che parte del campo si va. */
    casa: Boolean,
    /** La pericolosita' dell'azione, 0-100: quanto il campo deve accendersi. */
    pericolo: Int,
    /** Il gol appena segnato, per l'esultanza. Si passa il punteggio totale. */
    golTotali: Int,
    modifier: Modifier = Modifier,
) {
    val bersaglio = posizioneDi(zona, casa)

    // Le due coordinate scorrono verso la zona nuova. `animateFloatAsState` ricomincia da
    // dov'era: se l'azione cambia a meta' del movimento la palla non torna indietro.
    val x by animateFloatAsState(bersaglio.x, tween(1500, easing = LinearEasing), label = "palla-x")
    val y by animateFloatAsState(bersaglio.y, tween(1500, easing = LinearEasing), label = "palla-y")

    // L'alone che si accende sulle occasioni. Sopra la soglia il campo dice «guarda qui»
    // senza che nessuno debba leggere una frase.
    val acceso by animateFloatAsState(
        if (pericolo >= SOGLIA_OCCASIONE) 1f else 0f,
        tween(400),
        label = "pericolo",
    )

    // L'esultanza: un lampo che attraversa il campo quando il punteggio cambia. Dura
    // poco di proposito — e' un accento, non un'interruzione.
    val esultanza = remember { Animatable(0f) }
    LaunchedEffect(golTotali) {
        if (golTotali > 0) {
            esultanza.snapTo(1f)
            esultanza.animateTo(0f, tween(1400))
        }
    }

    // Il respiro del prato: due secondi e mezzo di andata e ritorno su una luminosita'
    // quasi impercettibile. Serve a togliere l'aria di immagine ferma quando per quindici
    // minuti non succede niente, che nel calcio vero e' la maggior parte del tempo.
    val respiro by rememberInfiniteTransition(label = "prato").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Reverse),
        label = "respiro",
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(168.dp)
            .background(MFootColors.core, MFootShapes.band),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            disegnaCampo(respiro)
            disegnaAlone(x, y, acceso, esultanza.value)
            disegnaPalla(x, y)
        }
    }
}

/** Sopra questa pericolosita' l'azione si accende: e' la soglia OCCASIONE del motore. */
private const val SOGLIA_OCCASIONE = 51

/**
 * Da zona a punto sul campo, in coordinate 0..1.
 *
 * ## Perche' la squadra ribalta le coordinate
 *
 * Perche' le zone del motore sono **relative a chi attacca**: `ATT_C` vuol dire «in area
 * avversaria», e l'area avversaria sta a destra per la squadra di casa e a sinistra per
 * l'altra. Disegnarle uguali per tutti e due mostrerebbe l'ospite che attacca la propria
 * porta.
 *
 * Anche la fascia si specchia: la sinistra di chi gioca da destra a sinistra e' la destra
 * di chi guarda.
 */
private fun posizioneDi(zona: String?, casa: Boolean): Offset {
    // Senza zona la palla sta al centro: e' il fischio d'inizio, ed e' anche il ripiego
    // giusto per un evento che non ne porta una (un cambio, un'ammonizione).
    val nome = zona ?: return Offset(0.5f, 0.5f)

    val altezza = when {
        nome.startsWith("DIF") -> 0.18f
        nome.startsWith("MID") -> 0.50f
        else -> 0.82f
    }
    val fascia = when {
        nome.endsWith("_SX") -> 0.24f
        nome.endsWith("_DX") -> 0.76f
        else -> 0.50f
    }

    return if (casa) Offset(altezza, fascia) else Offset(1f - altezza, 1f - fascia)
}

private fun DrawScope.disegnaCampo(respiro: Float) {
    val linea = MFootColors.line.copy(alpha = 0.55f + respiro * 0.12f)
    val bordo = 12.dp.toPx()
    val w = size.width - bordo * 2
    val h = size.height - bordo * 2

    // Il perimetro, la linea di meta' campo e il cerchio: bastano a leggere «campo».
    // Aggiungere aree di rigore e bandierine renderebbe il disegno piu' fedele e piu'
    // rumoroso, su una superficie alta centosessanta punti.
    drawRect(
        color = linea,
        topLeft = Offset(bordo, bordo),
        size = Size(w, h),
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawLine(
        color = linea,
        start = Offset(bordo + w / 2f, bordo),
        end = Offset(bordo + w / 2f, bordo + h),
        strokeWidth = 1.5.dp.toPx(),
    )
    drawCircle(
        color = linea,
        radius = h * 0.16f,
        center = Offset(bordo + w / 2f, bordo + h / 2f),
        style = Stroke(width = 1.5.dp.toPx()),
    )

    // Le due porte, come due tacche sul bordo.
    listOf(bordo, bordo + w).forEach { x ->
        drawLine(
            color = linea,
            start = Offset(x, bordo + h * 0.36f),
            end = Offset(x, bordo + h * 0.64f),
            strokeWidth = 3.dp.toPx(),
        )
    }
}

private fun DrawScope.disegnaAlone(x: Float, y: Float, acceso: Float, esultanza: Float) {
    if (acceso <= 0.01f && esultanza <= 0.01f) return

    val bordo = 12.dp.toPx()
    val centro = Offset(bordo + (size.width - bordo * 2) * x, bordo + (size.height - bordo * 2) * y)

    if (acceso > 0.01f) {
        drawCircle(
            color = MFootColors.gamble.copy(alpha = 0.20f * acceso),
            radius = 26.dp.toPx() * (0.7f + acceso * 0.3f),
            center = centro,
        )
    }
    if (esultanza > 0.01f) {
        // Il cerchio si allarga mentre svanisce: l'onda del gol.
        drawCircle(
            color = MFootColors.elite.copy(alpha = 0.45f * esultanza),
            radius = 26.dp.toPx() + (1f - esultanza) * 80.dp.toPx(),
            center = centro,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private fun DrawScope.disegnaPalla(x: Float, y: Float) {
    val bordo = 12.dp.toPx()
    val centro = Offset(bordo + (size.width - bordo * 2) * x, bordo + (size.height - bordo * 2) * y)

    drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = 6.dp.toPx(), center = centro + Offset(0f, 2.dp.toPx()))
    drawCircle(color = MFootColors.ink, radius = 5.dp.toPx(), center = centro)
}
