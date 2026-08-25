package dev.mfoot.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Il sistema visivo di MFoot, rifatto il 2026-08-23 sul riferimento scelto dal
 * proprietario. Tradotto in prosa in `docs/DESIGN-SYSTEM.md`.
 *
 * Nessun composable deve scrivere a mano un colore o una dimensione: viene tutto da qui.
 * E' l'unico modo perche' venti schermate scritte in momenti diversi sembrino la stessa
 * applicazione — ed e' anche il motivo per cui questo cambio di pelle e' costato pochi
 * file invece di trentacinque.
 *
 * ## Perche' i nomi non sono cambiati insieme ai colori
 *
 * `elite` era verde e adesso e' lavanda; `core` era piu' chiaro del fondo e adesso e' piu'
 * scuro. I nomi restano perche' dicono il **ruolo** — l'accento, il corpo delle superfici
 * — e il ruolo non e' cambiato. Rinominarli avrebbe voluto dire toccare settecento punti
 * di richiamo per ottenere esattamente lo stesso pixel.
 */
object MFootColors {

    // ------------------------------------------------------------------------- i fondi

    /**
     * Il fondo dell'applicazione: blu notte, non nero.
     *
     * Il nero puro sembrava un terminale. Questo blu tiene insieme la barra blu in alto e
     * le schede scure sotto, che altrimenti sarebbero due mondi appiccicati.
     */
    val bg = Color(0xFF111D2B)

    /**
     * Il corpo delle schede — **piu' scuro del fondo**, non piu' chiaro.
     *
     * E' il tratto che piu' di ogni altro fa somigliare l'app al riferimento, e va contro
     * l'istinto: di solito una superficie si stacca salendo di luminosita'. Qui scende, e
     * il fondo blu fa da luce intorno. Invertirlo per abitudine disfa l'intera pelle.
     */
    val core = Color(0xFF0A1622)

    /** Alto del gradiente di superficie, dove una scheda deve avere volume. */
    val coreTop = Color(0xFF102030)

    /**
     * L'unica superficie **piu' chiara** del fondo: il tondo grande degli stati vuoti.
     *
     * Le schede scendono di luminosita' ([core]) perche' devono sembrare incassate. Questo
     * tondo deve fare l'opposto — e' un disegno, non un contenitore — e col colore delle
     * schede spariva: due blu notte quasi identici, e in mezzo un'icona che sembrava
     * appoggiata sul niente.
     */
    val raised = Color(0xFF1C2836)

    /** Il fondo della barra in basso. */
    val bar = Color(0xFF0C1520)

    val line = Color(0x14FFFFFF)
    val lineStrong = Color(0x24FFFFFF)

    // --------------------------------------------------------------------- gli accenti

    /**
     * L'accento: lavanda.
     *
     * Sta sui pulsanti primari (fondo pieno, testo [onAccent]), sulle voci accese, sui
     * numeri che contano. Sul blu notte ha piu' contrasto del blu stesso, ed e' il motivo
     * per cui nel riferimento i pulsanti importanti sono chiari e non blu.
     */
    val elite = Color(0xFFBCCDFF)

    /** Il testo sopra [elite]. Quasi nero-blu: sul lavanda il bianco sparisce. */
    val onAccent = Color(0xFF12275C)

    /**
     * Il blu istituzionale: barra in alto, bande di sezione, icone del menu.
     *
     * Distinto da [elite] di proposito. Il blu **inquadra** — dice dove sei; il lavanda
     * **chiama** — dice cosa toccare. Usare l'uno per l'altro e' il modo piu' rapido per
     * ottenere una schermata dove tutto grida e niente si distingue.
     */
    val blue = Color(0xFF3F6ADD)

    /** Il blu profondo delle testate illustrate. */
    val blueDeep = Color(0xFF0A1E86)

    /** L'azzurro degli archi concentrici in cima alle testate. */
    val blueArc = Color(0xFF69C0FF)

    // ------------------------------------------------------------------ gli inchiostri

    val ink = Color(0xFFF2F5FA)
    val ink2 = Color(0xFF93A2B8)
    val ink3 = Color(0xFF5E6E85)

    // ------------------------------------------------------------------------ l'allarme

    /** Fondo del cartellino che avverte: rosso spento, non acceso. */
    val alarm = Color(0xFF5D2725)

    /** Il testo sopra [alarm]. */
    val onAlarm = Color(0xFFFFCAC8)

    /**
     * Un solo significato in tutta l'app: **margine di crescita ancora da conquistare**.
     * Usarla anche per avvisi o evidenziazioni generiche la svuoterebbe di senso.
     *
     * Ritarata sull'oro del riferimento — quello della corona accanto a «Superadmin» —
     * invece dell'ambra di prima, che sul blu notte virava al verde.
     */
    val gamble = Color(0xFFE9BC5A)

    // --------------------------------------------------- i quattro colori delle tessere

    /**
     * Le tessere quadrate con l'icona, negli elenchi di impostazioni.
     *
     * Quattro e non dodici: il colore raggruppa le voci per famiglia — chi gioca, quanto
     * costa, come si calcola — e con dodici colori non raggrupperebbe piu' niente.
     */
    val tileBlue = Color(0xFF3F6ADD)
    val tileGreen = Color(0xFF7DC63F)
    val tileOrange = Color(0xFFF0954A)
    val tileRed = Color(0xFFF04A5E)

    /**
     * Il viola dei riquadri che **spiegano**.
     *
     * E' l'unico colore dell'app che non vuol dire ne' «tocca qui» ne' «attento»: vuol dire
     * «questa cosa e' nuova, ecco cos'e'». Serviva perche' le spiegazioni lunghe erano
     * paragrafi grigi in fondo alla schermata, indistinguibili dalle didascalie, e non le
     * leggeva nessuno. Un colore che compare tre volte in tutta l'app e sempre per la
     * stessa ragione si impara alla seconda.
     */
    val teach = Color(0xFF4C0AC4)

    /** Il corpo del testo dentro [teach]: il bianco pieno li' dentro accecherebbe. */
    val onTeach = Color(0xFFD4B8FF)

    // ------------------------------------------------------------- la scala dei valori

    val good = Color(0xFFE6ECF5)
    val mid = Color(0xFF8494AC)
    val low = Color(0xFF55647A)

    /**
     * Tre gradini netti, non una sfumatura continua.
     *
     * Con cinque sfumature un 96 e un 76 si somigliavano e il colore non serviva a niente.
     * Deve essere possibile scansionare una scheda **senza leggere un solo numero**.
     *
     * Il gradino alto era verde fino al 2026-08-23. Adesso e' il lavanda dell'accento: nel
     * riferimento il verde non esiste, e un solo colore acceso in tutta l'app significa
     * che quando compare vuol dire sempre la stessa cosa — questo conta piu' della tinta.
     */
    fun rating(value: Int): Color = when {
        value >= 85 -> elite
        value >= 70 -> good
        value >= 55 -> mid
        else -> low
    }

    // --------------------------------------------------------------------- i gradienti

    /** La testata del menu laterale: dal blu al blu piu' freddo. */
    val drawerHeader = Brush.horizontalGradient(listOf(Color(0xFF3A6FD8), Color(0xFF3E86C4)))

    /** Il cielo dietro il nome del club, in Casa. */
    val hero = Brush.verticalGradient(listOf(Color(0xFF3F6ADD), Color(0xFF2A4EAF)))
}

/**
 * Gli angoli.
 *
 * Il riferimento e' molto piu' arrotondato di quanto fosse MFoot: le schede stanno a 18,
 * i campi e i pulsanti sono pillole intere. Angoli timidi su fondo scuro fanno sembrare
 * l'interfaccia una tabella.
 */
object MFootShapes {
    /** Guscio esterno, dove serve un doppio bordo. */
    val shell = RoundedCornerShape(24.dp)

    /** Nucleo interno: 24 meno i 6 di padding, perche' le curve restino concentriche. */
    val core = RoundedCornerShape(18.dp)

    /** La scheda: la forma piu' frequente dell'app. */
    val band = RoundedCornerShape(18.dp)

    /** Campi, riquadri piccoli. */
    val field = RoundedCornerShape(14.dp)

    /** La tessera quadrata con l'icona. */
    val tile = RoundedCornerShape(12.dp)

    val pill = RoundedCornerShape(50)
}

object MFootSpacing {
    /** Margine orizzontale dentro le superfici. */
    val gutter = 18.dp

    /** Fra sezioni diverse, e margine della pagina. */
    val section = 16.dp

    /** Fra elementi correlati, e fra una scheda e la successiva. */
    val related = 12.dp

    /** Padding del guscio: determina il raggio del nucleo. */
    val shellPadding = 6.dp

    val rowVertical = 12.dp
    val gridVertical = 10.dp
    val gridHorizontal = 18.dp
}

object MFootMotion {
    /** Mai LinearEasing: il movimento deve avere massa. */
    val easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    const val fast = 400
    const val normal = 500
}

/**
 * Tipografia.
 *
 * Tutti i numeri usano cifre a larghezza fissa: senza, le colonne di attributi ballano
 * mentre si scorre la lista, ed e' uno di quei difetti che si notano senza saper dire
 * cosa non va.
 *
 * ## La scala e' salita di due punti
 *
 * Il riferimento e' vistosamente piu' grande: titoli di scheda a 16, sottotitoli a 13,
 * niente sotto gli 11. La scala di prima — righe a 13.5, chip a 11 — era da tabella
 * densa, e su un telefono in mano faceva strizzare gli occhi. Buona parte della
 * somiglianza viene da qui, non dal colore.
 */
object MFootType {
    private val tabular = TextStyle(
        fontFeatureSettings = "tnum",
    )

    /** Il titolo delle testate a schermo pieno: «Lista mercati», «Sala trofei». */
    val display = TextStyle(
        fontSize = 29.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.02).em,
    )

    /**
     * L'overall della scheda giocatore, che e' il numero piu' grande dell'app.
     *
     * Esiste separato da [overallLarge] perche' sulla figurina non e' «un numero grande»:
     * e' il soggetto. Sotto ci sta il gradino della crescita, e i due insieme devono
     * leggersi in un colpo d'occhio solo — che e' il mestiere che prima faceva una barra
     * alta centoventi pixel.
     */
    val overallHero = tabular.copy(
        fontSize = 46.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.04).em,
    )

    val overallLarge = tabular.copy(
        fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em,
    )
    val playerName = TextStyle(
        fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em,
    )

    /** Il titolo nella barra in alto. */
    val barTitle = TextStyle(
        fontSize = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).em,
    )

    val price = tabular.copy(
        fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em,
    )
    val overallRow = tabular.copy(
        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em,
    )
    val givenName = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)

    /** Il titolo di una riga di elenco: il nome della squadra, del giocatore, della voce. */
    val rowTitle = TextStyle(
        fontSize = 15.5.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).em,
    )

    val value = tabular.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

    /** Il sottotitolo sotto un titolo di riga: chi possiede il club, che ruolo ha. */
    val secondary = TextStyle(fontSize = 13.sp)

    val chip = TextStyle(fontSize = 12.5.sp)

    /**
     * Le etichette piccole, maiuscole e larghe sono quello che fa sembrare
     * l'interfaccia curata: creano il contrasto con i numeri grandi.
     */
    val label = TextStyle(fontSize = 10.5.sp, letterSpacing = 0.14.em)

    /** L'etichetta sotto un'icona nella barra in basso. */
    val tab = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun MFootTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = MFootColors.bg,
            surface = MFootColors.core,
            onBackground = MFootColors.ink,
            onSurface = MFootColors.ink,
            primary = MFootColors.elite,
            onPrimary = MFootColors.onAccent,
        ),
        content = content,
    )
}
