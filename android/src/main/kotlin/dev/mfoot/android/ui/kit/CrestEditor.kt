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
import androidx.compose.ui.unit.dp
import dev.mfoot.android.ui.Chip
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootSpacing

/**
 * Lo scegli-stemma.
 *
 * ## Perche' una sola cosa alla volta
 *
 * Forma, motivo, emblema e tre colori fanno sei scelte. Mostrarle tutte insieme riempie lo
 * schermo di righe e chi arriva qui non sa da dove cominciare. Con una scheda per volta
 * resta visibile lo stemma grande in cima, che e' l'unica cosa che conta davvero: si tocca
 * qualcosa e si vede subito cosa cambia.
 */
@Composable
fun CrestEditor(
    crest: Crest,
    initials: String,
    onChange: (Crest) -> Unit,
) {
    var scheda by remember { mutableStateOf(Scheda.FORMA) }

    Column(Modifier.fillMaxWidth()) {
        Label("Lo stemma")
        Spacer(Modifier.height(MFootSpacing.related))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CrestBadge(crest, Modifier.size(112.dp), initials.ifBlank { null })
        }

        Spacer(Modifier.height(MFootSpacing.section))

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Scheda.entries.forEach { s ->
                Chip(s.label, s == scheda) { scheda = s }
            }
        }

        Spacer(Modifier.height(MFootSpacing.related))

        when (scheda) {
            // Le anteprime della forma e dell'emblema sono stemmi veri in miniatura, non
            // icone: si sceglie guardando il risultato, non un simbolo che lo rappresenta.
            Scheda.FORMA -> Miniature(
                opzioni = CrestShape.entries,
                selezionata = crest.shape,
                anteprima = { crest.copy(shape = it) },
            ) { onChange(crest.copy(shape = it)) }

            Scheda.EMBLEMA -> Miniature(
                opzioni = CrestSymbol.entries,
                selezionata = crest.symbol,
                anteprima = { crest.copy(symbol = it) },
            ) { onChange(crest.copy(symbol = it)) }

            Scheda.MOTIVO -> Miniature(
                opzioni = CrestBand.entries,
                selezionata = crest.band,
                anteprima = { crest.copy(band = it) },
            ) { onChange(crest.copy(band = it)) }

            Scheda.COLORI -> Colori(crest, onChange)
        }
    }
}

private enum class Scheda(val label: String) {
    FORMA("Forma"),
    EMBLEMA("Emblema"),
    MOTIVO("Motivo"),
    COLORI("Colori"),
}

@Composable
private fun <T> Miniature(
    opzioni: List<T>,
    selezionata: T,
    anteprima: (T) -> Crest,
    onPick: (T) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        opzioni.forEach { opzione ->
            val scelta = opzione == selezionata
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (scelta) MFootColors.elite.copy(alpha = 0.12f) else MFootColors.core)
                    .border(
                        if (scelta) 2.dp else 1.dp,
                        if (scelta) MFootColors.elite else MFootColors.lineStrong,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onPick(opzione) }
                    .padding(7.dp),
            ) {
                CrestBadge(anteprima(opzione), Modifier.size(42.dp))
            }
        }
    }
}

/**
 * I tre colori dello stemma.
 *
 * Lo slot si sceglie prima del colore, come nell'editor della maglia: sono le stesse mani e
 * lo stesso gesto, e due modi diversi di fare la stessa cosa nella stessa schermata di
 * fondazione sarebbero solo da imparare due volte.
 */
@Composable
private fun Colori(crest: Crest, onChange: (Crest) -> Unit) {
    var slot by remember { mutableStateOf(Slot.FONDO) }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Slot.entries.forEach { s ->
                Chip(s.label, s == slot) { slot = s }
            }
        }
        Spacer(Modifier.height(MFootSpacing.related))

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Crest.TAVOLOZZA.forEach { colore ->
                val scelto = slot.of(crest) == colore
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(colore))
                        .border(
                            if (scelto) 2.5.dp else 1.dp,
                            if (scelto) MFootColors.ink else MFootColors.lineStrong,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onChange(slot.apply(crest, colore)) },
                )
            }
        }
    }
}

private enum class Slot(val label: String) {
    FONDO("Fondo"),
    RIFINITURA("Bordo"),
    EMBLEMA("Emblema");

    fun of(c: Crest): Long = when (this) {
        FONDO -> c.field
        RIFINITURA -> c.trim
        EMBLEMA -> c.emblem
    }

    fun apply(c: Crest, colore: Long): Crest = when (this) {
        FONDO -> c.copy(field = colore)
        RIFINITURA -> c.copy(trim = colore)
        EMBLEMA -> c.copy(emblem = colore)
    }
}
