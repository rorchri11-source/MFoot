package dev.mfoot.android.ui.pitch

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootMotion
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import kotlin.math.roundToInt

/**
 * Una casella sul campo: dove sta e chi ci gioca.
 *
 * Le coordinate sono normalizzate e non in dp per una ragione precisa: la stessa
 * formazione deve stare su un telefono da 5 pollici e su un tablet senza due tabelle di
 * posizioni da tenere allineate. Chi le produce e' [dev.mfoot.core.match.PitchLayout], che
 * sta in `core` proprio perche' client e server devono concordare su dove sta un terzino
 * sinistro: se le coordinate vivessero qui, il campo dell'app e il campo di un eventuale
 * riepilogo generato dal server disegnerebbero due squadre diverse.
 */
data class PitchSlot(
    val index: Int,
    val position: Position,
    val player: Player?,
    /** 0..1 da sinistra a destra, 0..1 dalla propria porta all'area avversaria. */
    val x: Float,
    val y: Float,
)

/**
 * Il campo di gioco.
 *
 * ## Perche' disegnato e non un'immagine
 *
 * Un PNG di campo va scelto in una risoluzione, e su ogni schermo diverso da quella o
 * sfoca o mostra le righe di spessore sbagliato. Peggio: le caselle dei giocatori
 * andrebbero allineate a mano sulle righe dell'immagine, e ogni ritocco grafico
 * sposterebbe gli undici. Disegnando le righe dalle stesse proporzioni che collocano le
 * caselle, l'allineamento e' esatto per costruzione a qualunque dimensione.
 *
 * ## Perche' le caselle sono composable e non disegnate nel Canvas
 *
 * Il campo e' grafica, le caselle sono controlli: devono avere un'area di tocco vera, il
 * testo con la tipografia del sistema e la risposta al tocco. Disegnarle nel Canvas
 * significherebbe riscrivere a mano misurazione del testo e hit testing, e sbagliare
 * l'area di tocco su un bersaglio da 40dp e' il difetto che rende un'interfaccia
 * frustrante senza che si capisca perche'.
 *
 * Il modificatore deve portare una dimensione: la proporzione giusta e'
 * `Modifier.fillMaxWidth().aspectRatio(PITCH_ASPECT)`. Senza altezza vincolata il campo
 * la ricava dalla larghezza con quella stessa proporzione.
 *
 * @param highlight indici delle caselle da evidenziare, per esempio quelle dove il
 *   giocatore che si sta spostando puo' andare.
 */
@Composable
fun Pitch(
    slots: List<PitchSlot>,
    modifier: Modifier = Modifier,
    highlight: Set<Int> = emptySet(),
    onSlotClick: (Int) -> Unit = {},
) {
    Layout(
        modifier = modifier
            // Il taglio va prima del disegno, o l'erba esce dagli angoli arrotondati.
            .clip(MFootShapes.band)
            .drawBehind {
                drawField(PitchGeometry(size.width, size.height), 1.dp.toPx())
            },
        content = {
            slots.forEach { slot ->
                SlotBadge(
                    slot = slot,
                    highlighted = slot.index in highlight,
                    onClick = { onSlotClick(slot.index) },
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        // Un campo senza altezza vincolata non e' un errore da segnalare: e' il caso di
        // chi lo mette in una colonna scrollabile. Si ricava dalla proporzione vera.
        val height = if (constraints.hasBoundedHeight) {
            constraints.maxHeight
        } else {
            (width / PITCH_ASPECT).roundToInt()
        }

        val geometry = PitchGeometry(width.toFloat(), height.toFloat())
        // Le caselle non si misurano sul campo: prendono la loro misura naturale, perche'
        // un cerchio che si rimpicciolisce col campo diventa illeggibile su un telefono
        // piccolo, ed e' proprio lo schermo su cui va letto.
        val placeables = measurables.map { it.measure(Constraints()) }
        val inset = BADGE_DIAMETER.toPx() / 2f

        layout(width, height) {
            placeables.forEachIndexed { i, placeable ->
                val slot = slots[i]
                val center = geometry.centerOf(slot.x, slot.y, inset)
                placeable.place(
                    x = (center.x - placeable.width / 2f).roundToInt(),
                    // Il cerchio, non l'intera casella, sta sul punto: sotto il cerchio
                    // c'e' il cognome, e centrare la colonna intera farebbe salire le
                    // caselle sopra la riga di campo a cui appartengono.
                    y = (center.y - inset).roundToInt(),
                )
            }
        }
    }
}

/** Larghezza diviso altezza di un campo regolamentare: 68 su 105. */
const val PITCH_ASPECT = 68f / 105f

// ------------------------------------------------------------------------- la casella

private val BADGE_DIAMETER = 42.dp

/**
 * Una casella.
 *
 * Il cerchio pieno con un numero dentro e il cerchio tratteggiato e vuoto sono due
 * immagini opposte a colpo d'occhio, e devono esserlo: scorrendo la formazione si deve
 * vedere **dove manca un uomo** senza leggere niente. Un cerchio grigio uguale agli altri
 * con la scritta "vuoto" costringerebbe a leggere undici etichette.
 */
@Composable
private fun SlotBadge(slot: PitchSlot, highlighted: Boolean, onClick: () -> Unit) {
    val player = slot.player
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(MFootMotion.fast, easing = MFootMotion.easing),
        label = "casella",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // Piu' larga del cerchio: il cognome ha bisogno di spazio, e non deve
            // allargare la superficie toccabile del cerchio accanto.
            .width(74.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .size(BADGE_DIAMETER)
                .drawBehind {
                    drawSlotDisc(
                        filled = player != null,
                        highlighted = highlighted,
                        scale = scale,
                        strokeWidth = 1.5.dp.toPx(),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (player != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        slot.position.short,
                        style = MFootType.label,
                        color = MFootColors.ink3,
                    )
                    Text(
                        player.overall.toString(),
                        style = MFootType.overallRow,
                        color = MFootColors.rating(player.overall),
                    )
                }
            } else {
                Text(
                    slot.position.short,
                    style = MFootType.value,
                    color = if (highlighted) MFootColors.elite else MFootColors.ink2,
                )
            }
        }

        Spacer(Modifier.height(3.dp))
        Text(
            text = player?.lastName ?: "vuoto",
            style = MFootType.secondary,
            color = if (player != null) MFootColors.ink else MFootColors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                // Il nome sta sull'erba: senza una velatura sotto, un cognome chiaro su
                // una striatura chiara diventa illeggibile.
                .padding(horizontal = 2.dp),
        )
    }
}

private fun DrawScope.drawSlotDisc(
    filled: Boolean,
    highlighted: Boolean,
    scale: Float,
    strokeWidth: Float,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension / 2f * scale

    if (filled) {
        // Un'ombra portata sotto il disco lo stacca dall'erba: senza, le caselle
        // sembrano adesivi appoggiati su una texture.
        drawCircle(
            color = MFootColors.bg.copy(alpha = 0.45f),
            radius = radius,
            center = center + Offset(0f, strokeWidth * 1.6f),
        )
        drawCircle(
            brush = Brush.verticalGradient(
                colors = listOf(MFootColors.coreTop, MFootColors.core),
                startY = center.y - radius,
                endY = center.y + radius,
            ),
            radius = radius,
            center = center,
        )
        drawCircle(
            color = if (highlighted) MFootColors.elite else MFootColors.lineStrong,
            radius = radius,
            center = center,
            style = Stroke(width = if (highlighted) strokeWidth * 1.6f else strokeWidth),
        )
    } else {
        // Tratteggiato e senza riempimento: l'erba si vede attraverso, ed e' letteralmente
        // un buco nella formazione.
        drawCircle(
            color = if (highlighted) MFootColors.elite else MFootColors.ink3,
            radius = radius,
            center = center,
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(strokeWidth * 3f, strokeWidth * 2.5f),
                ),
            ),
        )
        if (highlighted) {
            drawCircle(
                color = MFootColors.elite.copy(alpha = 0.14f),
                radius = radius,
                center = center,
            )
        }
    }
}

// -------------------------------------------------------------------------- geometria

/**
 * Le proporzioni di un campo regolamentare, 105 per 68 metri, ridotte a frazioni.
 *
 * Sono misure reali e non numeri scelti a occhio: un'area di rigore larga a caso si nota
 * subito anche da chi non saprebbe dire quanto e' larga davvero.
 */
private object Field {
    const val PENALTY_AREA_WIDTH = 40.32f / 68f
    const val PENALTY_AREA_DEPTH = 16.5f / 105f
    const val GOAL_AREA_WIDTH = 18.32f / 68f
    const val GOAL_AREA_DEPTH = 5.5f / 105f
    const val PENALTY_SPOT = 11f / 105f
    const val CIRCLE_RX = 9.15f / 68f
    const val CIRCLE_RY = 9.15f / 105f
    const val GOAL_WIDTH = 7.32f / 68f

    /**
     * Semiapertura dell'arco dell'area, in gradi.
     *
     * L'arco ha raggio 9,15 dal dischetto, che sta a 11 metri: sporge oltre la linea dei
     * 16,5 solo per la parte in cui `cos(t) < (16,5 - 11) / 9,15`. Disegnare il cerchio
     * intero mostrerebbe la parte dentro l'area, che sul campo non esiste.
     */
    const val ARC_HALF_ANGLE = 53.05f
}

/**
 * Converte le coordinate di gioco in pixel.
 *
 * Un solo posto per la conversione, usato sia dal disegno del campo sia dal
 * posizionamento delle caselle: e' l'unico modo perche' un terzino stia davvero sulla
 * fascia e non due dita dentro.
 */
private class PitchGeometry(val width: Float, val height: Float) {
    /** Le porte sporgono oltre la linea di fondo: senza margine verrebbero tagliate. */
    val goalDepth = height * 0.017f

    val left = width * 0.055f
    val right = width - left
    val top = goalDepth + height * 0.020f
    val bottom = height - top
    val playWidth = right - left
    val playHeight = bottom - top
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f

    /**
     * Il centro della casella per una coordinata di gioco.
     *
     * `y = 0` e' la propria porta, in basso: sullo schermo si attacca verso l'alto, come
     * su ogni lavagna tattica mai disegnata. Invertirlo sarebbe corretto in matematica e
     * incomprensibile a chi guarda.
     *
     * [inset] tiene i cerchi interi dentro il campo: una casella a `y = 1` centrata sulla
     * linea di fondo avversaria sporgerebbe per meta' fuori dall'erba.
     */
    fun centerOf(x: Float, y: Float, inset: Float): Offset {
        val minX = left + inset
        val maxX = right - inset
        val minY = top + inset
        val maxY = bottom - inset
        return Offset(
            x = minX + x.coerceIn(0f, 1f) * (maxX - minX),
            y = maxY - y.coerceIn(0f, 1f) * (maxY - minY),
        )
    }
}

/**
 * L'erba.
 *
 * I colori non sono scritti a mano: nascono interpolando il fondo dell'app col verde del
 * sistema. Cosi' il campo appartiene alla stessa tavolozza di tutto il resto e non
 * sembra un componente venuto da un'altra applicazione — che e' esattamente come
 * finiscono i campi verde prato su fondo nero.
 */
private val grass = lerp(MFootColors.bg, MFootColors.elite, 0.11f)
private val grassStripe = lerp(MFootColors.bg, MFootColors.elite, 0.155f)
private val chalk = MFootColors.ink.copy(alpha = 0.22f)
private val chalkStrong = MFootColors.ink.copy(alpha = 0.34f)
private val netFill = MFootColors.ink.copy(alpha = 0.07f)

private fun DrawScope.drawField(g: PitchGeometry, unit: Float) {
    val stroke = Stroke(width = unit)

    drawRect(grass)

    // Le striature del taglio danno la scala: senza, un rettangolo verde non si legge
    // come un campo visto dall'alto ma come uno sfondo.
    val bands = 8
    val bandHeight = size.height / bands
    for (i in 0 until bands) {
        if (i % 2 == 0) continue
        drawRect(
            color = grassStripe,
            topLeft = Offset(0f, i * bandHeight),
            size = Size(size.width, bandHeight),
        )
    }

    // Ombra ai due fondi: un campo uniformemente illuminato sembra piatto come un
    // adesivo. E' l'unico effetto concesso qui.
    drawRect(
        brush = Brush.verticalGradient(
            0f to MFootColors.bg.copy(alpha = 0.34f),
            0.42f to Color.Transparent,
            1f to MFootColors.bg.copy(alpha = 0.20f),
        ),
    )

    // Perimetro e meta' campo.
    drawRect(
        color = chalk,
        topLeft = Offset(g.left, g.top),
        size = Size(g.playWidth, g.playHeight),
        style = stroke,
    )
    drawLine(chalk, Offset(g.left, g.centerY), Offset(g.right, g.centerY), unit)

    // Il cerchio di centrocampo e' un'ellisse, e non e' un errore: il campo e' 105 per 68
    // e lo schermo non e' in quella proporzione se chi chiama non la impone. Disegnare un
    // cerchio perfetto lo farebbe uscire dalle righe.
    val rx = g.playWidth * Field.CIRCLE_RX
    val ry = g.playHeight * Field.CIRCLE_RY
    drawOval(
        color = chalk,
        topLeft = Offset(g.centerX - rx, g.centerY - ry),
        size = Size(rx * 2f, ry * 2f),
        style = stroke,
    )
    drawCircle(chalkStrong, radius = unit * 1.8f, center = Offset(g.centerX, g.centerY))

    drawEnd(g, unit, stroke, atBottom = true)
    drawEnd(g, unit, stroke, atBottom = false)
    drawCorners(g, unit, stroke)
}

/** Area di rigore, area piccola, dischetto, arco e porta di un lato del campo. */
private fun DrawScope.drawEnd(g: PitchGeometry, unit: Float, stroke: Stroke, atBottom: Boolean) {
    val goalLineY = if (atBottom) g.bottom else g.top
    // Verso l'interno del campo: dal fondo in basso si va verso y minori.
    val inward = if (atBottom) -1f else 1f

    fun box(widthFraction: Float, depthFraction: Float) {
        val w = g.playWidth * widthFraction
        val d = g.playHeight * depthFraction
        drawRect(
            color = chalk,
            topLeft = Offset(g.centerX - w / 2f, minOf(goalLineY, goalLineY + inward * d)),
            size = Size(w, d),
            style = stroke,
        )
    }

    box(Field.PENALTY_AREA_WIDTH, Field.PENALTY_AREA_DEPTH)
    box(Field.GOAL_AREA_WIDTH, Field.GOAL_AREA_DEPTH)

    val spot = Offset(g.centerX, goalLineY + inward * g.playHeight * Field.PENALTY_SPOT)
    drawCircle(chalkStrong, radius = unit * 1.6f, center = spot)

    val rx = g.playWidth * Field.CIRCLE_RX
    val ry = g.playHeight * Field.CIRCLE_RY
    // 270 gradi punta verso l'alto, 90 verso il basso: l'arco sporge sempre verso il
    // centro del campo.
    val start = (if (atBottom) 270f else 90f) - Field.ARC_HALF_ANGLE
    drawArc(
        color = chalk,
        startAngle = start,
        sweepAngle = Field.ARC_HALF_ANGLE * 2f,
        useCenter = false,
        topLeft = Offset(spot.x - rx, spot.y - ry),
        size = Size(rx * 2f, ry * 2f),
        style = stroke,
    )

    // La porta: sporge fuori dalla linea di fondo, che e' il motivo del margine.
    val goalWidth = g.playWidth * Field.GOAL_WIDTH
    val goalTop = if (atBottom) goalLineY else goalLineY - g.goalDepth
    val goalRect = Offset(g.centerX - goalWidth / 2f, goalTop)
    val goalSize = Size(goalWidth, g.goalDepth)
    drawRect(netFill, topLeft = goalRect, size = goalSize)
    drawRect(
        color = chalkStrong,
        topLeft = goalRect,
        size = goalSize,
        style = Stroke(width = unit * 1.5f),
    )
}

/** Gli archetti d'angolo: dettaglio piccolo, ma la loro assenza si nota. */
private fun DrawScope.drawCorners(g: PitchGeometry, unit: Float, stroke: Stroke) {
    val r = g.playWidth * 0.032f
    val size = Size(r * 2f, r * 2f)
    // Ogni angolo apre il suo quarto di cerchio verso l'interno del campo.
    val corners = listOf(
        Triple(Offset(g.left, g.bottom), 270f, size),
        Triple(Offset(g.right, g.bottom), 180f, size),
        Triple(Offset(g.left, g.top), 0f, size),
        Triple(Offset(g.right, g.top), 90f, size),
    )
    corners.forEach { (center, start, arcSize) ->
        drawArc(
            color = chalk,
            startAngle = start,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = arcSize,
            style = stroke,
        )
    }
}

// -------------------------------------------------------------------------- anteprime

/**
 * Coordinate del 4-3-3 **solo per le anteprime**.
 *
 * Non sono una seconda copia di `PitchLayout`: servono a poter guardare il componente
 * senza avviare l'app. Le coordinate vere del gioco arrivano sempre da `core`, o due
 * tabelle finirebbero per divergere.
 */
private val previewLayout = listOf(
    0.50f to 0.03f,
    0.14f to 0.24f, 0.38f to 0.16f, 0.62f to 0.16f, 0.86f to 0.24f,
    0.50f to 0.42f, 0.28f to 0.55f, 0.72f to 0.55f,
    0.14f to 0.80f, 0.50f to 0.90f, 0.86f to 0.80f,
)

private fun previewPlayer(name: String, position: Position, overall: Int): Player =
    Player(
        id = PlayerId(overall.toLong()),
        firstName = "M",
        lastName = name,
        nationality = "Italia",
        age = 26,
        primaryPosition = position,
        // Tutti gli attributi allo stesso valore danno esattamente quell'overall in
        // qualunque ruolo: e' il modo piu' breve di fissare un overall in un'anteprima.
        attributes = Attributes.uniform(overall),
        potentialMin = overall,
        potentialMax = overall,
    )

@Preview(widthDp = 360, heightDp = 620, backgroundColor = 0xFF07080A, showBackground = true)
@Composable
private fun PitchCompletoPreview() {
    val names = listOf(
        "Ferrero", "Bellini", "Costa", "Rizzo", "Mancuso",
        "Baldi", "Greco", "Sarti", "Neri", "Vitale", "Longo",
    )
    val overalls = listOf(78, 71, 84, 86, 69, 74, 91, 66, 58, 88, 52)
    val slots = Formazione433.mapIndexed { i, position ->
        PitchSlot(
            index = i,
            position = position,
            player = previewPlayer(names[i], position, overalls[i]),
            x = previewLayout[i].first,
            y = previewLayout[i].second,
        )
    }
    Pitch(slots, Modifier.fillMaxWidth().aspectRatio(PITCH_ASPECT))
}

@Preview(widthDp = 360, heightDp = 620, backgroundColor = 0xFF07080A, showBackground = true)
@Composable
private fun PitchConBuchiPreview() {
    val slots = Formazione433.mapIndexed { i, position ->
        PitchSlot(
            index = i,
            position = position,
            player = if (i % 3 == 0) null else previewPlayer("Rossi", position, 60 + i * 3),
            x = previewLayout[i].first,
            y = previewLayout[i].second,
        )
    }
    Pitch(
        slots = slots,
        modifier = Modifier.fillMaxWidth().aspectRatio(PITCH_ASPECT),
        highlight = setOf(0, 3, 6, 9),
    )
}

private val Formazione433 = listOf(
    Position.POR,
    Position.TD, Position.DC, Position.DC, Position.TS,
    Position.MED, Position.CC, Position.CC,
    Position.AD, Position.ATT, Position.AS,
)
