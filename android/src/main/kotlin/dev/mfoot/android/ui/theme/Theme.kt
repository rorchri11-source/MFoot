package dev.mfoot.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Il sistema visivo di MFoot, tradotto da `docs/DESIGN-SYSTEM.md`.
 *
 * Nessun composable deve scrivere a mano un colore o una dimensione: viene tutto da qui.
 * E' l'unico modo perche' venti schermate scritte in momenti diversi sembrino la stessa
 * applicazione.
 */
object MFootColors {
    val bg = Color(0xFF07080A)
    val core = Color(0xFF14171C)
    val coreTop = Color(0xFF181C22)
    val line = Color(0x12FFFFFF)
    val lineStrong = Color(0x1FFFFFFF)

    val ink = Color(0xFFF2F4F7)
    val ink2 = Color(0xFF9BA3AE)
    val ink3 = Color(0xFF5F6874)

    val elite = Color(0xFF2BE07E)
    val good = Color(0xFFCFD6DE)
    val mid = Color(0xFF78828F)
    val low = Color(0xFF4E5661)

    /**
     * Un solo significato in tutta l'app: **margine di crescita ancora da conquistare**.
     * Usarla anche per avvisi o evidenziazioni generiche la svuoterebbe di senso.
     */
    val gamble = Color(0xFFFFC53D)

    /**
     * Tre gradini netti, non una sfumatura continua.
     *
     * Con cinque sfumature di verde un 96 e un 76 si somigliavano e il colore non
     * serviva a niente. Deve essere possibile scansionare una scheda **senza leggere un
     * solo numero**.
     */
    fun rating(value: Int): Color = when {
        value >= 85 -> elite
        value >= 70 -> good
        value >= 55 -> mid
        else -> low
    }
}

object MFootShapes {
    /** Guscio esterno del doppio bordo. */
    val shell = RoundedCornerShape(26.dp)

    /** Nucleo interno: 26 meno i 6 di padding, perche' le curve restino concentriche. */
    val core = RoundedCornerShape(20.dp)

    val band = RoundedCornerShape(16.dp)
    val field = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(50)
}

object MFootSpacing {
    /** Margine orizzontale dentro le superfici. */
    val gutter = 20.dp

    /** Fra sezioni diverse. */
    val section = 18.dp

    /** Fra elementi correlati. */
    val related = 12.dp

    /** Padding del guscio: determina il raggio del nucleo. */
    val shellPadding = 6.dp

    val rowVertical = 11.dp
    val gridVertical = 9.dp
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
 * Il carattere e' ancora quello di sistema. Va scelto: serve una grottesca con cifre
 * tabulari vere e un peso 600 leggibile a 10sp.
 */
object MFootType {
    private val tabular = TextStyle(
        fontFeatureSettings = "tnum",
    )

    val overallLarge = tabular.copy(
        fontSize = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em,
    )
    val playerName = TextStyle(
        fontSize = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em,
    )
    val price = tabular.copy(
        fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em,
    )
    val overallRow = tabular.copy(
        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em,
    )
    val givenName = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val rowTitle = TextStyle(
        fontSize = 13.5.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).em,
    )
    val value = tabular.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    val secondary = TextStyle(fontSize = 11.5.sp)
    val chip = TextStyle(fontSize = 11.sp)

    /**
     * Le etichette piccole, maiuscole e larghe sono quello che fa sembrare
     * l'interfaccia curata: creano il contrasto con i numeri grandi.
     */
    val label = TextStyle(fontSize = 10.sp, letterSpacing = 0.15.em)
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
            onPrimary = Color(0xFF06210F),
        ),
        content = content,
    )
}
