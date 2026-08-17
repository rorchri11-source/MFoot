package dev.mfoot.android.ui.settings

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.mfoot.android.ui.Chip
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.model.Money

/**
 * I comandi delle impostazioni.
 *
 * ## Perche' ognuno porta una spiegazione
 *
 * Centodieci manopole senza spiegazione sono centodieci occasioni di rompere la lega senza
 * capire come. "Peso degli stipendi 0,0009" non dice niente; "quanto pesa l'ingaggio
 * sull'overall: alzandolo i fuoriclasse diventano un lusso da mantenere" dice cosa
 * succede. La riga di aiuto non e' decorazione, e' la differenza fra una schermata che si
 * usa e una da cui si scappa.
 *
 * ## Perche' non si salva a ogni tocco
 *
 * Ogni modifica riscrive la configurazione in memoria; il salvataggio e' un gesto
 * separato. Cosi' si puo' sistemare tre campi e confermare una volta, e soprattutto si
 * puo' cambiare idea senza aver gia' alterato una lega in corso.
 */

/** Una riga con nome, spiegazione, e il comando a destra o sotto. */
@Composable
fun SettingRow(
    label: String,
    help: String? = null,
    control: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                modifier = Modifier.weight(1f),
            )
            control()
        }
        if (help != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                help,
                style = MFootType.chip,
                color = MFootColors.ink3,
                modifier = Modifier.fillMaxWidth(0.92f),
            )
        }
    }
    Hairline()
}

/**
 * Un campo di denaro.
 *
 * Accetta `1,5M`, `1500`, `700K`: chi viene dal fantacalcio digita il numero nudo, chi
 * pensa in milioni digita la sigla. Quando perde il fuoco mostra la forma normalizzata,
 * cosi' si vede subito **come e' stato capito** invece di scoprirlo dopo il salvataggio.
 */
@Composable
fun MoneyField(value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(Money(value).format()) }

    Box(
        Modifier
            .width(116.dp)
            .background(if (enabled) MFootColors.core else MFootColors.bg, MFootShapes.field)
            .border(1.dp, MFootColors.lineStrong, MFootShapes.field)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() || it in ",.MmKkRrDd" }.take(10)
                Money.parse(text)?.let { onChange(it.thousands) }
            },
            enabled = enabled,
            singleLine = true,
            textStyle = MFootType.value.copy(color = MFootColors.ink),
            cursorBrush = SolidColor(MFootColors.elite),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) text = Money(value).format() },
        )
    }
}

/** Un intero con meno e piu'. Per i conteggi, dove digitare e' piu' lento che toccare. */
@Composable
fun IntStepper(value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepBox("−", enabled && value > range.first) { onChange(value - 1) }
        Text(
            value.toString(),
            style = MFootType.value,
            color = MFootColors.ink,
            modifier = Modifier.width(44.dp).padding(horizontal = 8.dp),
        )
        StepBox("+", enabled && value < range.last) { onChange(value + 1) }
    }
}

/** Un decimale, per le manopole fini: peso stipendi, frazioni, moltiplicatori. */
@Composable
fun DecimalField(value: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(trim(value)) }

    Box(
        Modifier
            .width(92.dp)
            .background(if (enabled) MFootColors.core else MFootColors.bg, MFootShapes.field)
            .border(1.dp, MFootColors.lineStrong, MFootShapes.field)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() || it == ',' || it == '.' }.take(8)
                text.replace(',', '.').toDoubleOrNull()?.let(onChange)
            },
            enabled = enabled,
            singleLine = true,
            textStyle = MFootType.value.copy(color = MFootColors.ink),
            cursorBrush = SolidColor(MFootColors.elite),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) text = trim(value) },
        )
    }
}

private fun trim(value: Double): String =
    if (value == StrictMath.floor(value) && StrictMath.abs(value) < 1e9) {
        value.toLong().toString()
    } else {
        value.toString().replace('.', ',')
    }

@Composable
fun Switch(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .width(44.dp)
            .height(25.dp)
            .background(
                if (on) MFootColors.elite else MFootColors.core,
                MFootShapes.pill,
            )
            .border(1.dp, MFootColors.lineStrong, MFootShapes.pill)
            .then(if (enabled) Modifier.clickable { onChange(!on) } else Modifier),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(19.dp)
                .background(if (on) MFootColors.bg else MFootColors.ink3, MFootShapes.pill),
        )
    }
}

/** Scelta fra pochi valori. Sotto la riga e non accanto: le etichette sono lunghe. */
@Composable
fun <T> EnumRow(
    label: String,
    help: String?,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    labelOf: (T) -> String,
    onChange: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Text(label, style = MFootType.rowTitle, color = MFootColors.ink)
        if (help != null) {
            Spacer(Modifier.height(5.dp))
            Text(help, style = MFootType.chip, color = MFootColors.ink3)
        }
        Spacer(Modifier.height(9.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                Chip(labelOf(option), option == selected) { if (enabled) onChange(option) }
            }
        }
    }
    Hairline()
}

/**
 * I premi per posizione: una lista che si allunga e si accorcia.
 *
 * Il numero di premi non e' fisso perche' non lo e' il numero di squadre: in una lega da
 * sei club premiare le prime otto non ha senso, e in una da venti fermarsi a tre e' avaro.
 */
@Composable
fun PrizeList(prizes: List<Int>, enabled: Boolean, onChange: (List<Int>) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Text("Premi per posizione", style = MFootType.rowTitle, color = MFootColors.ink)
        Spacer(Modifier.height(5.dp))
        Text(
            "Dal primo in giu'. Togli tutte le righe se non vuoi premi di posizione.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
        Spacer(Modifier.height(10.dp))

        prizes.forEachIndexed { index, prize ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("${index + 1}°", Modifier.width(40.dp))
                MoneyField(prize, enabled) { nuovo ->
                    onChange(prizes.toMutableList().also { it[index] = nuovo })
                }
                Spacer(Modifier.width(10.dp))
                if (enabled) {
                    Text(
                        "togli",
                        style = MFootType.chip,
                        color = MFootColors.gamble,
                        modifier = Modifier
                            .clickable { onChange(prizes.filterIndexed { i, _ -> i != index }) }
                            .padding(6.dp),
                    )
                }
            }
        }

        if (enabled) {
            Spacer(Modifier.height(6.dp))
            Text(
                "+ aggiungi una posizione",
                style = MFootType.chip,
                color = MFootColors.elite,
                modifier = Modifier
                    .clickable {
                        // Il nuovo premio parte a meta' dell'ultimo: la scala dei premi
                        // scende, e proporre di nuovo la stessa cifra sarebbe sbagliato
                        // quasi sempre.
                        val ultimo = prizes.lastOrNull() ?: 1_000
                        onChange(prizes + (ultimo / 2).coerceAtLeast(100))
                    }
                    .padding(vertical = 6.dp),
            )
        }
    }
    Hairline()
}

@Composable
private fun StepBox(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .background(if (enabled) MFootColors.core else MFootColors.bg, MFootShapes.field)
            .border(1.dp, if (enabled) MFootColors.lineStrong else MFootColors.line, MFootShapes.field)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MFootType.value,
            color = if (enabled) MFootColors.ink else MFootColors.low,
        )
    }
}

/** Il titolo di un gruppo dentro una sezione. */
@Composable
fun GroupTitle(text: String) {
    Spacer(Modifier.height(MFootSpacing.section))
    Label(text)
    Spacer(Modifier.height(4.dp))
}
