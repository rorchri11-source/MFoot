package dev.mfoot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType

/**
 * I pezzi che ricorrono in ogni schermata.
 *
 * Stanno insieme perche' la coerenza di un'interfaccia non viene dall'avere le regole
 * scritte da qualche parte, ma dall'avere **un solo posto** dove il campo di testo e'
 * definito. Venti schermate che ridisegnano il proprio bordo sono venti bordi diversi.
 */

/** Etichetta piccola, maiuscola, larga: e' quella che fa sembrare la scheda curata. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = MFootColors.ink3) {
    Text(text.uppercase(), style = MFootType.label, color = color, modifier = modifier)
}

/**
 * Un campo di testo.
 *
 * `BasicTextField` invece del campo di Material: quello porta con se' etichette
 * fluttuanti, bordi e colori suoi che combattono con il sistema visivo. Qui il bordo lo
 * disegniamo noi e resta uguale a tutto il resto.
 */
@Composable
fun MFootField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    uppercase: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) {
    Column(modifier) {
        if (label != null) {
            Label(label)
            Spacer(Modifier.height(6.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(MFootColors.core, MFootShapes.field)
                .border(1.dp, MFootColors.lineStrong, MFootShapes.field)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = MFootType.rowTitle, color = MFootColors.ink3)
            }
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(if (uppercase) it.uppercase() else it) },
                singleLine = true,
                textStyle = MFootType.rowTitle.copy(color = MFootColors.ink),
                cursorBrush = SolidColor(MFootColors.elite),
                keyboardOptions = KeyboardOptions(
                    capitalization = if (uppercase) KeyboardCapitalization.Characters
                    else KeyboardCapitalization.Words,
                    imeAction = imeAction,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Il bottone che porta avanti. Uno solo per schermata, o non e' piu' primario. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Text(
        text = text,
        style = MFootType.value,
        color = if (enabled) MFootColors.bg else MFootColors.ink3,
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (enabled) MFootColors.elite else MFootColors.core,
                MFootShapes.pill,
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        textAlign = TextAlign.Center,
    )
}

/** L'alternativa: presente, leggibile, ma senza contendere l'attenzione al primario. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MFootType.value,
        color = MFootColors.ink2,
        modifier = modifier
            .fillMaxWidth()
            .background(MFootColors.core, MFootShapes.pill)
            .border(1.dp, MFootColors.lineStrong, MFootShapes.pill)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MFootType.chip,
        color = if (selected) MFootColors.bg else MFootColors.ink2,
        modifier = Modifier
            .background(if (selected) MFootColors.ink else MFootColors.line, MFootShapes.pill)
            .border(1.dp, MFootColors.line, MFootShapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

/** Un messaggio in un riquadro: esito, errore, avviso. Il colore porta il significato. */
@Composable
fun Notice(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MFootType.chip,
        color = color,
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), MFootShapes.field)
            .border(1.dp, color.copy(alpha = 0.22f), MFootShapes.field)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** Il filo che separa senza pesare. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MFootColors.line),
    )
}

/** Riga con etichetta a sinistra e valore a destra: la forma dei riepiloghi. */
@Composable
fun StatRow(label: String, value: String, valueColor: Color = MFootColors.ink) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = MFootSpacing.gridVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, Modifier.weight(1f))
        Text(value, style = MFootType.value, color = valueColor)
    }
}
