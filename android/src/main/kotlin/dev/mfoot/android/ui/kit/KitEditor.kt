package dev.mfoot.android.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootType

/**
 * L'editor della maglia.
 *
 * ## Anteprima grande, comandi piccoli
 *
 * La maglia occupa la parte alta e non si muove mai: si sceglie guardando quella, non
 * leggendo i nomi dei motivi. I comandi stanno sotto, compatti, perche' la decisione la
 * prende l'occhio.
 *
 * ## Perche' i motivi sono anteprime e non etichette
 *
 * "Banda diagonale" e "scudo" non dicono niente finche' non le si vede. Otto maglie
 * piccole nei colori scelti si confrontano in un secondo; otto nomi in fila costringono a
 * provarli tutti uno per uno.
 */
@Composable
fun KitEditor(
    kit: Kit,
    onChange: (Kit) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Quale dei tre colori si sta cambiando: senza questo servirebbero tre tavolozze da
    // dodici sullo schermo insieme, che sono trentasei quadratini e nessuna gerarchia.
    var slot by remember { mutableStateOf(ColorSlot.PRINCIPALE) }

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Shirt(kit, Modifier.size(150.dp, 168.dp), showNumber = true)
        }

        Spacer(Modifier.height(22.dp))
        Caption("Motivo")
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KitPattern.entries.forEach { pattern ->
                val selected = pattern == kit.pattern
                Box(
                    Modifier
                        .clip(MFootShapes.field)
                        .background(
                            if (selected) MFootColors.elite.copy(alpha = 0.10f) else MFootColors.core,
                        )
                        .border(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) MFootColors.elite else MFootColors.lineStrong,
                            MFootShapes.field,
                        )
                        .clickable { onChange(kit.copy(pattern = pattern)) }
                        .padding(7.dp),
                ) {
                    // L'anteprima usa i colori veri della maglia: mostrarla in grigio
                    // costringerebbe a immaginare il risultato invece di vederlo.
                    Shirt(kit.copy(pattern = pattern), Modifier.size(38.dp, 43.dp))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption("Colori", Modifier.weight(1f))
            ColorSlot.entries.forEach { candidate ->
                SlotTab(
                    label = candidate.label,
                    swatch = candidate.of(kit),
                    selected = candidate == slot,
                ) { slot = candidate }
                Spacer(Modifier.width(6.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PALETTE.forEach { color ->
                val selected = slot.of(kit) == color
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(color))
                        .border(
                            if (selected) 2.5.dp else 1.dp,
                            if (selected) MFootColors.ink else MFootColors.lineStrong,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onChange(slot.apply(kit, color)) },
                )
            }
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MFootType.label,
        color = MFootColors.ink3,
        modifier = modifier,
    )
}

/**
 * La linguetta di un colore mostra il colore stesso, non solo il suo nome.
 *
 * Serve a sapere quale dei tre si sta cambiando senza doverlo dedurre: "dettaglio" da solo
 * non dice se e' quello scuro o quello acceso.
 */
@Composable
private fun SlotTab(label: String, swatch: Long, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(MFootShapes.pill)
            .background(if (selected) MFootColors.ink else MFootColors.line)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(11.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(swatch))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MFootType.chip,
            color = if (selected) MFootColors.bg else MFootColors.ink2,
        )
    }
}

private enum class ColorSlot(val label: String) {
    PRINCIPALE("Principale"),
    SECONDARIO("Secondo"),
    DETTAGLIO("Dettaglio");

    fun of(kit: Kit): Long = when (this) {
        PRINCIPALE -> kit.primary
        SECONDARIO -> kit.secondary
        DETTAGLIO -> kit.detail
    }

    fun apply(kit: Kit, color: Long): Kit = when (this) {
        PRINCIPALE -> kit.copy(primary = color)
        SECONDARIO -> kit.copy(secondary = color)
        DETTAGLIO -> kit.copy(detail = color)
    }
}

/**
 * Dodici colori, non un selettore libero.
 *
 * Con la ruota completa si ottengono maglie fangose che nessuno avrebbe scelto guardando
 * una tavolozza. Questi dodici sono saturi abbastanza da restare leggibili accanto al
 * verde dell'interfaccia, e comprendono il bianco e il nero perche' meta' delle maglie
 * vere sono bianche o nere.
 */
private val PALETTE = listOf(
    0xFFF2F4F7, 0xFF12151A, 0xFFE8483F, 0xFF1F5FD8,
    0xFF2BE07E, 0xFFFFC53D, 0xFF8A0F2E, 0xFF0B3B8C,
    0xFF00A6A6, 0xFFB05CFF, 0xFFFF7A3D, 0xFF7A8290,
)

@Preview(widthDp = 360, heightDp = 480, backgroundColor = 0xFF07080A, showBackground = true)
@Composable
private fun KitEditorPreview() {
    var kit by remember {
        mutableStateOf(
            Kit(
                pattern = KitPattern.STRISCE_VERTICALI,
                primary = 0xFFE8483F,
                secondary = 0xFF12151A,
                detail = 0xFFF2F4F7,
                number = 10,
            ),
        )
    }
    Box(Modifier.padding(18.dp)) {
        KitEditor(kit, { kit = it })
    }
}
