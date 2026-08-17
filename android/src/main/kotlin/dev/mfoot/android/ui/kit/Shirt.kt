package dev.mfoot.android.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.mfoot.android.ui.theme.MFootColors

/**
 * La maglia di un club.
 *
 * I colori sono `Long` e non `Color` perche' questo oggetto viaggia: sta in `clubs.kit`
 * come jsonb e passa dal database all'app e viceversa. Un `Long` ARGB si serializza da
 * solo, un `Color` no.
 */
data class Kit(
    val pattern: KitPattern = KitPattern.TINTA_UNITA,
    val primary: Long,
    val secondary: Long,
    val detail: Long,
    val number: Int? = null,
) {
    companion object {
        /**
         * La maglia di partenza di un club appena fondato.
         *
         * Bianca con dettagli scuri e non un colore acceso a caso: la prima cosa che il
         * proprietario vede deve sembrare una maglia non ancora scelta, non una maglia
         * scelta male da qualcun altro.
         */
        val DEFAULT = Kit(
            pattern = KitPattern.TINTA_UNITA,
            primary = 0xFFF2F4F7,
            secondary = 0xFF12151A,
            detail = 0xFF12151A,
        )
    }
}

/**
 * Gli otto motivi.
 *
 * Otto e non trenta: ogni motivo in piu' e' un'anteprima in piu' da guardare prima di
 * scegliere, e con trenta si sceglie a caso. Questi otto sono quelli che coprono quasi
 * tutte le maglie vere.
 */
enum class KitPattern {
    TINTA_UNITA,
    STRISCE_VERTICALI,
    STRISCE_ORIZZONTALI,
    BANDA_VERTICALE,
    BANDA_DIAGONALE,
    SPALLE,
    SCUDO,
    META_E_META,
}

/**
 * Il nome leggibile del motivo.
 *
 * Fuori dall'enum di proposito: le voci di [KitPattern] sono un contratto scritto nella
 * spec e lette da altri blocchi di lavoro, e aggiungere proprieta' al costruttore di un
 * enum condiviso e' il genere di modifica che si paga altrove.
 */
val KitPattern.label: String
    get() = when (this) {
        KitPattern.TINTA_UNITA -> "Tinta unita"
        KitPattern.STRISCE_VERTICALI -> "Strisce"
        KitPattern.STRISCE_ORIZZONTALI -> "Fasce"
        KitPattern.BANDA_VERTICALE -> "Banda"
        KitPattern.BANDA_DIAGONALE -> "Diagonale"
        KitPattern.SPALLE -> "Spalle"
        KitPattern.SCUDO -> "Scudo"
        KitPattern.META_E_META -> "Metà e metà"
    }

/**
 * La maglia disegnata.
 *
 * ## Perche' una sagoma e non un rettangolo
 *
 * Un rettangolo colorato con una banda in mezzo non e' una maglia: e' una bandiera. La
 * differenza non e' estetica ma di riconoscibilita' — lo stemma di un club deve essere
 * identificabile in una lista di venti, e a quella dimensione la **forma** si legge prima
 * del colore.
 *
 * ## Perche' il motivo sta dentro il ritaglio
 *
 * Il motivo si dipinge dentro `clipPath` della sagoma. Disegnare le strisce su un
 * riquadro e poi arrotondarne gli angoli le fa uscire dai bordi delle maniche e del
 * collo: e' il difetto che fa sembrare la maglia un adesivo appiccicato sopra a una
 * forma, invece della forma stessa.
 *
 * ## Perche' il terzo colore fa sempre lo stesso mestiere
 *
 * `detail` e' colletto, polsini e numero, in tutti gli otto motivi. Un colore senza un
 * compito fisso diventa rumore: chi lo cambia deve vedere subito **cosa** cambia.
 *
 * La proporzione naturale e' [SHIRT_ASPECT]. Con altre proporzioni la sagoma si deforma:
 * meglio darle la dimensione con `Modifier.size(w, w / SHIRT_ASPECT)`.
 */
@Composable
fun Shirt(kit: Kit, modifier: Modifier = Modifier, showNumber: Boolean = false) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val body = shirtPath(size)
        val primary = Color(kit.primary)
        val secondary = Color(kit.secondary)
        val detail = Color(kit.detail)

        clipPath(body) {
            drawRect(primary)
            drawPattern(kit.pattern, primary, secondary, detail)
            drawTrims(detail)
            // Il volume viene da qui: due velature, una chiara in alto a sinistra e una
            // scura verso il basso e i fianchi. Senza, la maglia resta una silhouette
            // piatta anche se il motivo e' giusto.
            drawShading()
        }

        // Il bordo va fuori dal ritaglio, o meta' del suo spessore finisce tagliata e la
        // sagoma sembra sfrangiata.
        drawPath(
            path = body,
            color = MFootColors.bg.copy(alpha = 0.55f),
            style = Stroke(width = size.minDimension * 0.012f),
        )

        val number = kit.number
        if (showNumber && number != null) {
            drawNumber(number, detail, measurer)
        }
    }
}

/** Larghezza diviso altezza della sagoma. Una maglia e' poco piu' alta che larga. */
const val SHIRT_ASPECT = 1f / 1.12f

// ------------------------------------------------------------------------- la sagoma

/**
 * Le proporzioni della sagoma, in frazioni della cornice.
 *
 * Sono raccolte qui perche' vengono usate due volte: dalla sagoma e dai motivi, che
 * devono sapere dove sono le spalle e dove comincia il torso. Ricopiarle porterebbe a una
 * banda "sulle spalle" che finisce sul petto.
 */
private object Cut {
    const val NECK_HALF = 0.125f
    const val NECK_TOP = 0.055f
    const val NECK_BOTTOM = 0.135f
    const val SHOULDER_HALF = 0.255f
    const val SHOULDER_TOP = 0.022f
    const val SLEEVE_HALF = 0.500f
    const val SLEEVE_TOP = 0.115f
    const val CUFF_OUTER = 0.400f
    const val CUFF_INNER = 0.445f
    const val CUFF_INNER_HALF = 0.315f
    const val ARMPIT_HALF = 0.268f
    const val ARMPIT_Y = 0.330f
    const val WAIST_HALF = 0.240f
    const val HEM = 0.980f
}

/**
 * Il contorno della maglia.
 *
 * Si costruisce dalla spalla sinistra in giro fino alla spalla destra, e si chiude sul
 * collo. Le curve sono cubiche dove il tessuto cade — spalla, ascella, fianco — e rette
 * dove il taglio e' netto: e' la differenza fra una maglia e un cartello stradale a forma
 * di maglia.
 */
private fun shirtPath(size: Size): Path {
    val w = size.width
    val h = size.height
    fun x(f: Float) = w * f
    fun y(f: Float) = h * f
    // Le frazioni orizzontali sono distanze dal centro: cosi' la sagoma e' simmetrica per
    // costruzione e non per copia-incolla di numeri.
    fun cx(half: Float) = w * (0.5f + half)

    return Path().apply {
        // Collo, lato sinistro.
        moveTo(cx(-Cut.NECK_HALF), y(Cut.NECK_TOP))
        // Spalla: sale appena verso l'esterno.
        cubicTo(
            cx(-Cut.NECK_HALF - 0.05f), y(Cut.NECK_TOP - 0.02f),
            cx(-Cut.SHOULDER_HALF + 0.06f), y(Cut.SHOULDER_TOP),
            cx(-Cut.SHOULDER_HALF), y(Cut.SHOULDER_TOP),
        )
        // Manica: spiove verso il polsino.
        cubicTo(
            cx(-Cut.SHOULDER_HALF - 0.10f), y(Cut.SHOULDER_TOP + 0.005f),
            cx(-Cut.SLEEVE_HALF), y(Cut.SLEEVE_TOP - 0.03f),
            cx(-Cut.SLEEVE_HALF), y(Cut.SLEEVE_TOP),
        )
        lineTo(cx(-Cut.SLEEVE_HALF + 0.012f), y(Cut.CUFF_OUTER))
        // Il polsino e' obliquo: il braccio non e' orizzontale.
        lineTo(cx(-Cut.CUFF_INNER_HALF), y(Cut.CUFF_INNER))
        // Ascella: rientra con un raccordo, o la manica sembra incollata al fianco.
        cubicTo(
            cx(-Cut.CUFF_INNER_HALF + 0.02f), y(Cut.CUFF_INNER - 0.055f),
            cx(-Cut.ARMPIT_HALF - 0.012f), y(Cut.ARMPIT_Y + 0.035f),
            cx(-Cut.ARMPIT_HALF), y(Cut.ARMPIT_Y),
        )
        // Fianco: si stringe leggermente in vita, poi torna a scendere.
        cubicTo(
            cx(-Cut.WAIST_HALF - 0.004f), y(Cut.ARMPIT_Y + 0.24f),
            cx(-Cut.WAIST_HALF - 0.006f), y(Cut.ARMPIT_Y + 0.42f),
            cx(-Cut.WAIST_HALF), y(Cut.HEM),
        )
        // Orlo: appena convesso, perche' la maglia cade sopra i calzoncini.
        cubicTo(
            cx(-Cut.WAIST_HALF * 0.4f), h,
            cx(Cut.WAIST_HALF * 0.4f), h,
            cx(Cut.WAIST_HALF), y(Cut.HEM),
        )
        // E ora tutto a specchio, risalendo il lato destro.
        cubicTo(
            cx(Cut.WAIST_HALF + 0.006f), y(Cut.ARMPIT_Y + 0.42f),
            cx(Cut.WAIST_HALF + 0.004f), y(Cut.ARMPIT_Y + 0.24f),
            cx(Cut.ARMPIT_HALF), y(Cut.ARMPIT_Y),
        )
        cubicTo(
            cx(Cut.ARMPIT_HALF + 0.012f), y(Cut.ARMPIT_Y + 0.035f),
            cx(Cut.CUFF_INNER_HALF - 0.02f), y(Cut.CUFF_INNER - 0.055f),
            cx(Cut.CUFF_INNER_HALF), y(Cut.CUFF_INNER),
        )
        lineTo(cx(Cut.SLEEVE_HALF - 0.012f), y(Cut.CUFF_OUTER))
        lineTo(cx(Cut.SLEEVE_HALF), y(Cut.SLEEVE_TOP))
        cubicTo(
            cx(Cut.SLEEVE_HALF), y(Cut.SLEEVE_TOP - 0.03f),
            cx(Cut.SHOULDER_HALF + 0.10f), y(Cut.SHOULDER_TOP + 0.005f),
            cx(Cut.SHOULDER_HALF), y(Cut.SHOULDER_TOP),
        )
        cubicTo(
            cx(Cut.SHOULDER_HALF - 0.06f), y(Cut.SHOULDER_TOP),
            cx(Cut.NECK_HALF + 0.05f), y(Cut.NECK_TOP - 0.02f),
            cx(Cut.NECK_HALF), y(Cut.NECK_TOP),
        )
        // Lo scollo: scende al centro. E' il dettaglio che si nota di meno e senza il
        // quale la maglia sembra un grembiule.
        cubicTo(
            cx(Cut.NECK_HALF * 0.7f), y(Cut.NECK_BOTTOM),
            cx(-Cut.NECK_HALF * 0.7f), y(Cut.NECK_BOTTOM),
            cx(-Cut.NECK_HALF), y(Cut.NECK_TOP),
        )
        close()
    }
}

// -------------------------------------------------------------------------- i motivi

private fun DrawScope.drawPattern(
    pattern: KitPattern,
    primary: Color,
    secondary: Color,
    detail: Color,
) {
    val w = size.width
    val h = size.height

    when (pattern) {
        // Il fondo e' gia' stato dipinto in primario: qui non serve altro.
        KitPattern.TINTA_UNITA -> Unit

        KitPattern.STRISCE_VERTICALI -> {
            // Sette strisce e non undici: sotto una certa larghezza le strisce si
            // impastano in un colore medio, e in una lista di squadre le maglie a strisce
            // sembrerebbero tutte tinta unita.
            val count = 7
            val stripe = w / count
            for (i in 0 until count step 2) {
                drawRect(secondary, Offset(i * stripe, 0f), Size(stripe, h))
            }
        }

        KitPattern.STRISCE_ORIZZONTALI -> {
            val count = 9
            val band = h / count
            for (i in 0 until count step 2) {
                drawRect(secondary, Offset(0f, i * band), Size(w, band))
            }
        }

        KitPattern.BANDA_VERTICALE -> {
            val bandWidth = w * 0.22f
            drawRect(secondary, Offset((w - bandWidth) / 2f, 0f), Size(bandWidth, h))
            // I due filetti sono quello che distingue una banda da un rettangolo
            // appoggiato sopra.
            val pin = w * 0.018f
            drawRect(detail, Offset((w - bandWidth) / 2f - pin, 0f), Size(pin, h))
            drawRect(detail, Offset((w + bandWidth) / 2f, 0f), Size(pin, h))
        }

        KitPattern.BANDA_DIAGONALE -> {
            // Una fascia obliqua da spalla a fianco: si costruisce come parallelogramma
            // che esce dalla cornice da entrambi i lati, cosi' il ritaglio della sagoma
            // la taglia esattamente sul bordo della maglia.
            val thickness = h * 0.30f
            drawPath(
                path = Path().apply {
                    moveTo(-w * 0.2f, h * 0.62f)
                    lineTo(w * 1.2f, -h * 0.12f)
                    lineTo(w * 1.2f, -h * 0.12f + thickness)
                    lineTo(-w * 0.2f, h * 0.62f + thickness)
                    close()
                },
                color = secondary,
            )
        }

        KitPattern.SPALLE -> {
            // Il taglio cade appena sotto l'ascella: piu' in alto sembrerebbe un colletto
            // gigante, piu' in basso una fascia orizzontale qualunque.
            val cutY = h * (Cut.ARMPIT_Y + 0.02f)
            drawRect(secondary, Offset.Zero, Size(w, cutY))
            drawRect(detail, Offset(0f, cutY), Size(w, h * 0.014f))
        }

        KitPattern.SCUDO -> {
            // Un pannello sul petto che termina a punta. Deve stare dentro il torso e non
            // invadere le maniche, o non si legge come pannello.
            val half = w * 0.20f
            val top = h * Cut.NECK_BOTTOM
            val shoulderY = h * 0.58f
            val tip = h * 0.76f
            drawPath(
                path = Path().apply {
                    moveTo(w / 2f - half, top)
                    lineTo(w / 2f + half, top)
                    lineTo(w / 2f + half, shoulderY)
                    quadraticTo(w / 2f, tip, w / 2f - half, shoulderY)
                    close()
                },
                color = secondary,
            )
        }

        KitPattern.META_E_META -> {
            drawRect(secondary, Offset(w / 2f, 0f), Size(w / 2f, h))
        }
    }
}

/**
 * Colletto e polsini.
 *
 * Si disegnano dopo il motivo e dentro lo stesso ritaglio: sono le tre zone in cui una
 * maglia vera cambia sempre colore, e sono anche il modo di dare un compito al terzo
 * colore invece di lasciarlo a decorare a caso.
 */
private fun DrawScope.drawTrims(detail: Color) {
    val w = size.width
    val h = size.height

    // Il colletto segue lo scollo: e' una fascia spessa lungo il bordo superiore del
    // torso, non un rettangolo.
    val collarThickness = h * 0.055f
    val neck = Path().apply {
        moveTo(w * (0.5f - Cut.NECK_HALF - 0.03f), h * (Cut.NECK_TOP - 0.02f))
        cubicTo(
            w * (0.5f - Cut.NECK_HALF * 0.6f), h * (Cut.NECK_BOTTOM + collarThickness / h),
            w * (0.5f + Cut.NECK_HALF * 0.6f), h * (Cut.NECK_BOTTOM + collarThickness / h),
            w * (0.5f + Cut.NECK_HALF + 0.03f), h * (Cut.NECK_TOP - 0.02f),
        )
        lineTo(w * (0.5f + Cut.NECK_HALF + 0.03f), 0f)
        lineTo(w * (0.5f - Cut.NECK_HALF - 0.03f), 0f)
        close()
    }
    drawPath(neck, detail)

    // I polsini: due bande oblique in punta alle maniche. Sporgono dalla cornice perche'
    // il ritaglio della sagoma le rifili sul bordo esatto della manica.
    val cuff = h * 0.05f
    listOf(-1f, 1f).forEach { side ->
        drawPath(
            path = Path().apply {
                val outerX = w * (0.5f + side * (Cut.SLEEVE_HALF + 0.03f))
                val innerX = w * (0.5f + side * (Cut.CUFF_INNER_HALF - 0.03f))
                moveTo(outerX, h * (Cut.CUFF_OUTER - cuff / h))
                lineTo(innerX, h * (Cut.CUFF_INNER - cuff / h))
                lineTo(innerX, h * (Cut.CUFF_INNER + 0.03f))
                lineTo(outerX, h * (Cut.CUFF_OUTER + 0.03f))
                close()
            },
            color = detail,
        )
    }
}

/**
 * Le velature che danno volume.
 *
 * Sono l'unico effetto della maglia, e servono a un problema concreto: senza, due maglie
 * di colori diversi nella stessa lista sembrano due ritagli di carta, e la piu' scura
 * spariste sul fondo dell'app.
 */
private fun DrawScope.drawShading() {
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.45f to Color.Transparent,
            1f to MFootColors.bg.copy(alpha = 0.22f),
        ),
    )
    // Fianchi in ombra: il torso e' un cilindro, non un piano.
    drawRect(
        brush = Brush.horizontalGradient(
            0f to MFootColors.bg.copy(alpha = 0.20f),
            0.28f to Color.Transparent,
            0.72f to Color.Transparent,
            1f to MFootColors.bg.copy(alpha = 0.20f),
        ),
    )
}

private fun DrawScope.drawNumber(
    number: Int,
    color: Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val text = number.toString()
    val layout = measurer.measure(
        text = text,
        style = TextStyle(
            // La dimensione segue la maglia: un numero in sp fisso diventa un francobollo
            // sull'anteprima grande e copre tutto sulla miniatura.
            fontSize = (size.height * 0.30f).toSp(),
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.04).em,
        ),
    )
    drawText(
        textLayoutResult = layout,
        color = color,
        topLeft = Offset(
            x = (size.width - layout.size.width) / 2f,
            // Non centrato in verticale: il numero sta sul petto, non sull'ombelico.
            y = size.height * 0.40f - layout.size.height / 2f,
        ),
    )
}

private fun DrawScope.toSp(value: Float) = value.toSp()

private fun Float.toSpIn(scope: DrawScope) = with(scope) { this@toSpIn.toSp() }

// ------------------------------------------------------------------------- anteprime

/**
 * La tavolozza: dodici colori.
 *
 * Non un selettore libero come primo strumento. Con una ruota RGB completa la meta' delle
 * squadre finisce con un marrone spento scelto per sbaglio, e la lega diventa illeggibile
 * a colpo d'occhio. Dodici colori saturi e distinguibili fra loro coprono quasi ogni
 * maglia vera e garantiscono che due club non si somiglino per caso.
 */
val KIT_PALETTE: List<Long> = listOf(
    0xFFF2F4F7, // bianco
    0xFF12151A, // nero
    0xFFE23B3B, // rosso
    0xFF8A0F2E, // granata
    0xFFF07A22, // arancio
    0xFFF5C518, // giallo
    0xFF2BE07E, // verde
    0xFF11694A, // verde scuro
    0xFF00B8C4, // celeste
    0xFF1E4FD8, // blu
    0xFF0E2A6B, // navy
    0xFF7B2FF7, // viola
)

@Preview(widthDp = 340, heightDp = 300, backgroundColor = 0xFF07080A, showBackground = true)
@Composable
private fun ShirtMotiviPreview() {
    Row(
        Modifier.padding(10.dp).height(280.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KitPattern.entries.take(4).forEach { pattern ->
            Shirt(
                kit = Kit(
                    pattern = pattern,
                    primary = 0xFF1E4FD8,
                    secondary = 0xFFF2F4F7,
                    detail = 0xFFF5C518,
                    number = 10,
                ),
                modifier = Modifier.size(76.dp, 76.dp / SHIRT_ASPECT),
                showNumber = true,
            )
        }
    }
}

@Preview(widthDp = 340, heightDp = 300, backgroundColor = 0xFF07080A, showBackground = true)
@Composable
private fun ShirtMotiviDuePreview() {
    Row(
        Modifier.padding(10.dp).height(280.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KitPattern.entries.drop(4).forEach { pattern ->
            Shirt(
                kit = Kit(
                    pattern = pattern,
                    primary = 0xFF8A0F2E,
                    secondary = 0xFFF5C518,
                    detail = 0xFF12151A,
                ),
                modifier = Modifier.size(76.dp, 76.dp / SHIRT_ASPECT),
            )
        }
    }
}

@Preview(widthDp = 200, heightDp = 240, backgroundColor = 0xFF07080A, showBackground = true)
@Composable
private fun ShirtGrandePreview() {
    Shirt(
        kit = Kit(
            pattern = KitPattern.STRISCE_VERTICALI,
            primary = 0xFF12151A,
            secondary = 0xFF2BE07E,
            detail = 0xFFF2F4F7,
            number = 9,
        ),
        modifier = Modifier.size(170.dp, 170.dp / SHIRT_ASPECT),
        showNumber = true,
    )
}
