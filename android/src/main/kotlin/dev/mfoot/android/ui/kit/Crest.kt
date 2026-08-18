package dev.mfoot.android.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.mfoot.android.ui.theme.MFootType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lo stemma di un club.
 *
 * ## Perche' disegnato e non caricato
 *
 * Far caricare un'immagine a ognuno vorrebbe dire archiviare file, gestire chi ne carica
 * una da otto megapixel, e ritrovarsi con leghe dove meta' degli stemmi sono foto sfocate
 * prese da internet. Disegnandolo, ogni club ha uno stemma che sta bene accanto agli altri
 * a qualunque dimensione, non costa niente in archiviazione, e viaggia come cinque numeri
 * dentro il JSON della maglia — quindi **senza nessuna modifica al database**.
 *
 * ## Perche' emblemi geometrici e non animali
 *
 * La tentazione e' fare aquile, tori, leoni. Disegnati a mano in codice vengono male: a
 * quaranta pixel un'aquila mediocre e' una macchia, e si nota che e' fatta male. Gli
 * emblemi qui sotto sono forme nette — stella, fulmine, torre, corona, ancora — che restano
 * leggibili in miniatura e sembrano scelte, non sbagliate.
 */
data class Crest(
    val shape: CrestShape = CrestShape.SCUDO,
    val symbol: CrestSymbol = CrestSymbol.PALLONE,
    val band: CrestBand = CrestBand.NESSUNA,
    /** Il fondo. */
    val field: Long = 0xFF1F5FD8,
    /** La fascia e il bordo. */
    val trim: Long = 0xFFF2F4F7,
    /** L'emblema. */
    val emblem: Long = 0xFFF2F4F7,
) {
    companion object {
        val DEFAULT = Crest()

        /**
         * Lo stemma di un club che non ne ha scelto uno.
         *
         * Ricavato dall'id come la maglia, e per la stessa ragione: deve restare identico a
         * ogni apertura e su ogni telefono, senza che nessuno lo salvi da nessuna parte.
         * Cosi' anche gli otto avversari gestiti dal computer hanno ciascuno il proprio.
         */
        fun forClub(clubId: Long): Crest {
            val forme = CrestShape.entries
            val simboli = CrestSymbol.entries
            val fasce = CrestBand.entries

            val fondo = TAVOLOZZA[((clubId * 5) % TAVOLOZZA.size).toInt()]
            // Il bordo deve staccare dal fondo: chiaro su scuro, scuro su chiaro. Sceglierlo
            // dalla tavolozza a caso darebbe blu su blu una volta su dodici.
            val rifinitura = if (luminosita(fondo) > 140) 0xFF12151A else 0xFFF2F4F7

            return Crest(
                shape = forme[((clubId * 3) % forme.size).toInt()],
                symbol = simboli[((clubId * 7 + 1) % simboli.size).toInt()],
                band = fasce[((clubId * 11) % fasce.size).toInt()],
                field = fondo,
                trim = rifinitura,
                emblem = rifinitura,
            )
        }

        private fun luminosita(colore: Long): Double {
            val r = ((colore shr 16) and 0xFF).toDouble()
            val g = ((colore shr 8) and 0xFF).toDouble()
            val b = (colore and 0xFF).toDouble()
            return 0.299 * r + 0.587 * g + 0.114 * b
        }

        internal val TAVOLOZZA = listOf(
            0xFF1F5FD8, 0xFF8A0F2E, 0xFF12151A, 0xFF2BE07E,
            0xFFE8483F, 0xFFFFC53D, 0xFF0B3B8C, 0xFF00A6A6,
            0xFFB05CFF, 0xFFFF7A3D, 0xFF7A8290, 0xFFF2F4F7,
        )
    }
}

/** La sagoma esterna. */
enum class CrestShape(val label: String) {
    SCUDO("Scudo"),
    APPUNTITO("Appuntito"),
    CERCHIO("Cerchio"),
    ROMBO("Rombo"),
    ESAGONO("Esagono"),
}

/** Il motivo sul fondo, dietro l'emblema. */
enum class CrestBand(val label: String) {
    NESSUNA("Nessuna"),
    FASCIA("Fascia"),
    DIAGONALE("Diagonale"),
    PALO("Palo"),
    CAPO("Capo"),
}

/** L'emblema al centro. */
enum class CrestSymbol(val label: String) {
    NESSUNO("Nessuno"),
    PALLONE("Pallone"),
    STELLA("Stella"),
    FULMINE("Fulmine"),
    TORRE("Torre"),
    CORONA("Corona"),
    ANCORA("Ancora"),
    ALLORO("Alloro"),
    CROCE("Croce"),
    MONTE("Monte"),
}

/**
 * Disegna lo stemma.
 *
 * [initials] compaiono sotto l'emblema quando c'e' spazio: sono cio' che distingue due club
 * che hanno scelto la stessa forma e lo stesso simbolo, e in una lega da venti succede.
 */
@Composable
fun CrestBadge(
    crest: Crest,
    modifier: Modifier = Modifier,
    initials: String? = null,
) {
    // La misura la decide chi chiama e basta.
    //
    // Prima c'era `modifier.size(56.dp)` in coda, che **sovrascriveva** la dimensione
    // passata: ogni stemma usciva a 56dp qualunque cosa chiedesse la schermata, e accanto a
    // una maglia da 30 sembrava sproporzionato. Un modificatore applicato dopo quello del
    // chiamante vince sempre, ed e' il modo piu' facile di rendere un componente sordo.
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val sagoma = shapePath(crest.shape, size)

            clipPath(sagoma) {
                drawPath(sagoma, Color(crest.field))
                bandPath(crest.band, size)?.let { drawPath(it, Color(crest.trim), alpha = 0.9f) }
            }

            // Il bordo si disegna **dopo** e fuori dal ritaglio, o la meta' esterna del
            // tratto verrebbe tagliata via e il contorno sembrerebbe la meta' piu' sottile.
            drawPath(
                sagoma,
                Color(crest.trim),
                style = Stroke(width = size.minDimension * 0.055f),
            )

            symbolPath(crest.symbol, size, initials != null)?.let {
                drawPath(it, Color(crest.emblem))
            }
        }

        // Le iniziali seguono la larghezza del riquadro invece di avere una misura fissa.
        //
        // Con `size(56.dp, 16.dp)` la casella del testo era piu' larga dello stemma su ogni
        // badge piccolo: le lettere uscivano a sinistra e venivano tagliate dal bordo. Qui
        // occupano la larghezza vera, qualunque sia, e restano centrate.
        if (!initials.isNullOrBlank()) {
            Text(
                initials.take(3).uppercase(),
                style = MFootType.label.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.04.em,
                ),
                color = Color(crest.emblem),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    // Poco sotto il centro, non appoggiate al fondo.
                    //
                    // Il rombo e l'esagono si stringono a una punta in basso: li' dentro non
                    // ci sta niente, e le lettere finivano a cavallo del bordo. A questa
                    // altezza ogni sagoma e' ancora larga, quindi la regola vale per tutte
                    // invece di funzionare solo sullo scudo.
                    .align(BiasAlignment(horizontalBias = 0f, verticalBias = 0.45f))
                    .fillMaxWidth(0.68f),
            )
        }
    }
}

// ----------------------------------------------------------------------------- sagome

private fun shapePath(shape: CrestShape, size: Size): Path {
    val w = size.width
    val h = size.height
    val p = Path()

    when (shape) {
        CrestShape.SCUDO -> {
            // Lo scudo classico: spalle dritte, fianchi che rientrano, punta arrotondata.
            p.moveTo(w * 0.08f, h * 0.06f)
            p.lineTo(w * 0.92f, h * 0.06f)
            p.lineTo(w * 0.92f, h * 0.52f)
            p.cubicTo(w * 0.92f, h * 0.78f, w * 0.72f, h * 0.92f, w * 0.5f, h * 0.97f)
            p.cubicTo(w * 0.28f, h * 0.92f, w * 0.08f, h * 0.78f, w * 0.08f, h * 0.52f)
            p.close()
        }

        CrestShape.APPUNTITO -> {
            p.moveTo(w * 0.5f, h * 0.03f)
            p.lineTo(w * 0.95f, h * 0.22f)
            p.lineTo(w * 0.95f, h * 0.56f)
            p.lineTo(w * 0.5f, h * 0.97f)
            p.lineTo(w * 0.05f, h * 0.56f)
            p.lineTo(w * 0.05f, h * 0.22f)
            p.close()
        }

        CrestShape.CERCHIO -> {
            p.addOval(Rect(w * 0.05f, h * 0.05f, w * 0.95f, h * 0.95f))
        }

        CrestShape.ROMBO -> {
            p.moveTo(w * 0.5f, h * 0.02f)
            p.lineTo(w * 0.96f, h * 0.5f)
            p.lineTo(w * 0.5f, h * 0.98f)
            p.lineTo(w * 0.04f, h * 0.5f)
            p.close()
        }

        CrestShape.ESAGONO -> {
            for (i in 0 until 6) {
                // Ruotato di novanta gradi: la punta in alto, non il lato.
                val a = PI / 3 * i - PI / 2
                val x = w * 0.5f + (w * 0.47f * cos(a)).toFloat()
                val y = h * 0.5f + (h * 0.47f * sin(a)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
    }
    return p
}

// ------------------------------------------------------------------------------ fasce

private fun bandPath(band: CrestBand, size: Size): Path? {
    val w = size.width
    val h = size.height
    if (band == CrestBand.NESSUNA) return null

    val p = Path()
    when (band) {
        CrestBand.NESSUNA -> return null
        CrestBand.FASCIA -> p.addRect(Rect(0f, h * 0.40f, w, h * 0.60f))
        CrestBand.PALO -> p.addRect(Rect(w * 0.40f, 0f, w * 0.60f, h))
        CrestBand.CAPO -> p.addRect(Rect(0f, 0f, w, h * 0.28f))
        CrestBand.DIAGONALE -> {
            p.moveTo(0f, h * 0.62f)
            p.lineTo(w * 0.62f, 0f)
            p.lineTo(w, 0f)
            p.lineTo(0f, h)
            p.close()
        }
    }
    return p
}

// ---------------------------------------------------------------------------- emblemi

/**
 * L'emblema, centrato nel riquadro utile della sagoma.
 *
 * Quando ci sono le iniziali l'emblema sale e si stringe, per non finirci sopra: e' l'unico
 * modo perche' entrambe le cose restino leggibili in quaranta pixel.
 */
private fun symbolPath(symbol: CrestSymbol, size: Size, conIniziali: Boolean): Path? {
    if (symbol == CrestSymbol.NESSUNO) return null

    val lato = size.minDimension * (if (conIniziali) 0.34f else 0.44f)
    val cx = size.width / 2f
    val cy = size.height * (if (conIniziali) 0.40f else 0.48f)
    val r = lato / 2f

    val p = Path()
    when (symbol) {
        CrestSymbol.NESSUNO -> return null

        CrestSymbol.PALLONE -> {
            p.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
            // Il pentagono centrale, tolto dal cerchio: un pallone senza niente dentro e'
            // un pallino, e a colpo d'occhio non dice "calcio".
            val buco = Path()
            for (i in 0 until 5) {
                val a = 2 * PI / 5 * i - PI / 2
                val x = cx + (r * 0.42f * cos(a)).toFloat()
                val y = cy + (r * 0.42f * sin(a)).toFloat()
                if (i == 0) buco.moveTo(x, y) else buco.lineTo(x, y)
            }
            buco.close()
            return Path().apply { op(p, buco, PathOperation.Difference) }
        }

        CrestSymbol.STELLA -> {
            for (i in 0 until 10) {
                val raggio = if (i % 2 == 0) r else r * 0.42f
                val a = PI / 5 * i - PI / 2
                val x = cx + (raggio * cos(a)).toFloat()
                val y = cy + (raggio * sin(a)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }

        CrestSymbol.FULMINE -> {
            p.moveTo(cx + r * 0.30f, cy - r)
            p.lineTo(cx - r * 0.55f, cy + r * 0.12f)
            p.lineTo(cx - r * 0.05f, cy + r * 0.12f)
            p.lineTo(cx - r * 0.30f, cy + r)
            p.lineTo(cx + r * 0.55f, cy - r * 0.16f)
            p.lineTo(cx + r * 0.05f, cy - r * 0.16f)
            p.close()
        }

        CrestSymbol.TORRE -> {
            // Merli, corpo, base.
            p.addRect(Rect(cx - r * 0.62f, cy - r, cx - r * 0.30f, cy - r * 0.62f))
            p.addRect(Rect(cx - r * 0.16f, cy - r, cx + r * 0.16f, cy - r * 0.62f))
            p.addRect(Rect(cx + r * 0.30f, cy - r, cx + r * 0.62f, cy - r * 0.62f))
            p.addRect(Rect(cx - r * 0.62f, cy - r * 0.62f, cx + r * 0.62f, cy + r * 0.72f))
            p.addRect(Rect(cx - r * 0.85f, cy + r * 0.72f, cx + r * 0.85f, cy + r))
        }

        CrestSymbol.CORONA -> {
            p.moveTo(cx - r, cy + r * 0.55f)
            p.lineTo(cx - r * 0.82f, cy - r * 0.75f)
            p.lineTo(cx - r * 0.38f, cy + r * 0.02f)
            p.lineTo(cx, cy - r)
            p.lineTo(cx + r * 0.38f, cy + r * 0.02f)
            p.lineTo(cx + r * 0.82f, cy - r * 0.75f)
            p.lineTo(cx + r, cy + r * 0.55f)
            p.close()
            p.addRect(Rect(cx - r, cy + r * 0.68f, cx + r, cy + r))
        }

        CrestSymbol.ANCORA -> {
            val s = r * 0.16f
            p.addRect(Rect(cx - s, cy - r * 0.55f, cx + s, cy + r * 0.75f))
            p.addRect(Rect(cx - r * 0.5f, cy - r * 0.42f, cx + r * 0.5f, cy - r * 0.24f))
            p.addOval(Rect(cx - r * 0.26f, cy - r, cx + r * 0.26f, cy - r * 0.48f))
            // I bracci in basso: due archi resi come triangoli, che a questa scala si
            // leggono uguale e non diventano una poltiglia.
            p.moveTo(cx - r, cy + r * 0.28f)
            p.lineTo(cx - r * 0.62f, cy + r * 0.95f)
            p.lineTo(cx - r * 0.10f, cy + r * 0.70f)
            p.close()
            p.moveTo(cx + r, cy + r * 0.28f)
            p.lineTo(cx + r * 0.62f, cy + r * 0.95f)
            p.lineTo(cx + r * 0.10f, cy + r * 0.70f)
            p.close()
        }

        CrestSymbol.ALLORO -> {
            // Due rami curvi affrontati.
            listOf(-1f, 1f).forEach { lato2 ->
                val ramo = Path()
                ramo.moveTo(cx + lato2 * r * 0.20f, cy + r * 0.92f)
                ramo.cubicTo(
                    cx + lato2 * r * 1.05f, cy + r * 0.45f,
                    cx + lato2 * r * 0.95f, cy - r * 0.55f,
                    cx + lato2 * r * 0.30f, cy - r * 0.95f,
                )
                ramo.cubicTo(
                    cx + lato2 * r * 0.72f, cy - r * 0.35f,
                    cx + lato2 * r * 0.70f, cy + r * 0.35f,
                    cx + lato2 * r * 0.05f, cy + r * 0.92f,
                )
                ramo.close()
                p.addPath(ramo)
            }
        }

        CrestSymbol.CROCE -> {
            val s = r * 0.30f
            p.addRect(Rect(cx - s, cy - r, cx + s, cy + r))
            p.addRect(Rect(cx - r, cy - s, cx + r, cy + s))
        }

        CrestSymbol.MONTE -> {
            p.moveTo(cx - r, cy + r * 0.78f)
            p.lineTo(cx - r * 0.28f, cy - r * 0.35f)
            p.lineTo(cx + r * 0.05f, cy + r * 0.10f)
            p.lineTo(cx + r * 0.42f, cy - r)
            p.lineTo(cx + r, cy + r * 0.78f)
            p.close()
        }
    }
    return p
}
