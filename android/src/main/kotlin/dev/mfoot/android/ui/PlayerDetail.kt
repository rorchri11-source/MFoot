package dev.mfoot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import dev.mfoot.core.model.Money
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.android.app.PlayerRow
import dev.mfoot.core.model.Attr

/** Estremi della scala su cui si legge la fascia di crescita. */
private const val SCALE_MIN = 40f
private const val SCALE_MAX = 99f

private fun Int.onScale(): Float =
    ((this - SCALE_MIN) / (SCALE_MAX - SCALE_MIN)).coerceIn(0f, 1f)

/**
 * La scheda giocatore — **registro alto**.
 *
 * Questa schermata si guarda una alla volta, con attenzione, prima di decidere se
 * spendere sessanta crediti. Puo' permettersi il teatro che la lista non puo'.
 */
@Composable
fun PlayerDetailScreen(
    row: PlayerRow,
    /** Presenze, gol e media voto. Vuota finche non ha giocato niente. */
    carriera: dev.mfoot.android.data.Carriera = dev.mfoot.android.data.Carriera.NESSUNA,
    canAuction: Boolean = false,
    /** Vero quando il giocatore e mio: cambia solo la parola sul pulsante, ma cambiarla
     * conta — "metti all asta" e "vendi" sono due gesti diversi. */
    isSelling: Boolean = false,
    /** Il testo del pulsante Primavera, o null se non si puo spostare questo giocatore. */
    youthAction: String? = null,
    onYouth: () -> Unit = {},
    onAuction: () -> Unit = {},
    onClose: () -> Unit,
) {
    val player = row.player

    // La scheda riempie lo schermo e il piede resta ancorato in basso: lasciarla
    // galleggiare su un fondo vuoto la faceva sembrare incompiuta.
    Box(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .padding(MFootSpacing.related),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.03f), MFootShapes.shell)
                .border(1.dp, MFootColors.line, MFootShapes.shell)
                .padding(MFootSpacing.shellPadding),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(MFootShapes.core)
                    .background(
                        Brush.verticalGradient(listOf(MFootColors.coreTop, MFootColors.core)),
                    ),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Header(row)
                    GrowthBand(row)
                    Carriera(carriera)
                    Attributes(row)
                    Stars(row)
                    Traits(row)
                }
                Footer(row, canAuction, isSelling, youthAction, onYouth, onAuction, onClose)
            }
        }
    }
}

@Composable
private fun Header(row: PlayerRow) {
    val player = row.player

    Row(
        Modifier
            .fillMaxWidth()
            .padding(MFootSpacing.gutter, MFootSpacing.gutter, MFootSpacing.gutter, 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // La bandiera al posto del pallino grigio. Con lo scouting che manda gli
                // osservatori in un paese preciso, la nazionalita' smette di essere un
                // dettaglio anagrafico e diventa il posto da cui uno viene.
                Text(bandiera(player.nationality), style = MFootType.chip)
                Spacer(Modifier.width(7.dp))
                Text(
                    "${player.nationality.uppercase()} · ${player.age} ANNI",
                    style = MFootType.label,
                    color = MFootColors.ink3,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(player.firstName, style = MFootType.givenName, color = MFootColors.ink2)
            Text(player.lastName, style = MFootType.playerName, color = MFootColors.ink)

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Chip(
                    "${player.primaryPosition.short} · ${player.primaryPosition.label}",
                    strong = true,
                )
                player.secondaryPositions.firstOrNull()?.let { Chip("anche ${it.short}") }
            }
        }

        Box(
            Modifier
                .size(72.dp)
                .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(20.dp))
                .border(1.dp, MFootColors.lineStrong, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    player.overall.toString(),
                    style = MFootType.overallLarge,
                    color = MFootColors.rating(player.overall),
                )
                Spacer(Modifier.height(4.dp))
                Text("OVR", style = MFootType.label, color = MFootColors.ink3)
            }
        }
    }
}

@Composable
private fun Chip(text: String, strong: Boolean = false) {
    Text(
        text = text,
        style = MFootType.chip,
        color = if (strong) MFootColors.ink else MFootColors.ink2,
        modifier = Modifier
            .background(
                if (strong) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.05f),
                MFootShapes.pill,
            )
            .border(1.dp, MFootColors.line, MFootShapes.pill)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/**
 * L'elemento firma.
 *
 * Due stati, e la differenza e' concettuale prima che grafica: chi e' arrivato ha una
 * barra **piena e verde**, non una scheggia. La maturita' e' un traguardo, non una
 * mancanza — una barra quasi vuota comunicherebbe il contrario.
 */
@Composable
private fun GrowthBand(row: PlayerRow) {
    val player = row.player
    val upside = row.hasUpside
    val accent = if (upside) MFootColors.gamble else MFootColors.elite

    Column(
        Modifier
            .padding(horizontal = MFootSpacing.gutter)
            .fillMaxWidth()
            .background(
                if (upside) Color.White.copy(alpha = 0.028f) else MFootColors.elite.copy(alpha = 0.05f),
                MFootShapes.band,
            )
            .border(
                1.dp,
                if (upside) MFootColors.line else MFootColors.elite.copy(alpha = 0.18f),
                MFootShapes.band,
            )
            .padding(16.dp, 14.dp, 16.dp, 13.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                if (upside) "MARGINE DI CRESCITA" else "MATURITÀ",
                style = MFootType.label,
                color = MFootColors.ink3,
            )
            Text(
                growthHeadline(row),
                style = MFootType.value,
                color = accent,
            )
        }

        Spacer(Modifier.height(12.dp))

        val now = player.overall.onScale()
        val top = row.estimate.last.onScale()

        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(4.dp)),
        ) {
            // Il tratto gia' percorso. Deve staccarsi nettamente dal fondo, altrimenti
            // si legge grigio su grigio e sparisce.
            Box(
                Modifier
                    .fillMaxWidth(now)
                    .height(7.dp)
                    .background(
                        if (upside) Color.White.copy(alpha = 0.45f)
                        else MFootColors.elite.copy(alpha = 0.55f),
                        RoundedCornerShape(4.dp),
                    ),
            )
            // Il margine ancora da conquistare: e' questo il motivo per cui lo compri.
            if (upside && top > now) {
                SegmentFrom(fraction = now, width = top - now, color = accent)
            }
        }

        Spacer(Modifier.height(9.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Oggi ${player.overall}", style = MFootType.chip, color = MFootColors.ink3)
            Text(growthDetail(row), style = MFootType.chip, color = MFootColors.ink3)
        }
    }

    Spacer(Modifier.height(MFootSpacing.section))
}

/** Un tratto di barra che parte da una frazione data della larghezza disponibile. */
@Composable
private fun SegmentFrom(fraction: Float, width: Float, color: Color) {
    Layout(
        content = {
            Box(
                Modifier
                    .height(7.dp)
                    .background(color, RoundedCornerShape(4.dp)),
            )
        },
        modifier = Modifier.fillMaxWidth().height(7.dp),
    ) { measurables, constraints ->
        val total = constraints.maxWidth
        val start = (total * fraction).toInt()
        val span = (total * width).toInt().coerceAtLeast(1)
        val placeable = measurables.first().measure(
            constraints.copy(minWidth = span, maxWidth = span),
        )
        layout(total, placeable.height) { placeable.place(start, 0) }
    }
}

private fun growthHeadline(row: PlayerRow): String {
    if (!row.hasUpside) {
        return if (row.player.age >= 30) "◆ In parabola discendente" else "◆ Giocatore completo"
    }
    val gain = row.estimate.last - row.player.overall
    return when {
        gain >= 20 -> "Può diventare tutt'altro"
        gain >= 10 -> "Può crescere molto"
        else -> "Ha ancora margine"
    }
}

/**
 * Quanto puo' arrivare, e **quanto ne sai**.
 *
 * ## Perche' la seconda meta' conta quanto la prima
 *
 * Una forbice larga puo' voler dire due cose opposte: che il giocatore e' imprevedibile,
 * o che non lo hai mai visto giocare. Senza dirlo, chi guarda non sa se aspettare che si
 * stringa o se quello e' tutto cio' che si potra' mai sapere — e la scommessa, che e' la
 * meccanica centrale del gioco, resta muta.
 *
 * La conoscenza cresce con i minuti che lo hai visto in campo e con gli osservatori che
 * paghi. E' il motivo per cui vale la pena assumerli, e il motivo per cui un giovane
 * comprato oggi e' un rischio piu' grande di uno cresciuto in casa.
 */
private fun growthDetail(row: PlayerRow): String {
    val base = if (row.hasUpside) {
        "Potrebbe arrivare fra ${row.estimate.first} e ${row.estimate.last}"
    } else {
        "Tetto stimato ${row.estimate.last}"
    }

    val quanto = when {
        row.knowledge >= 70 -> "lo conosci bene"
        row.knowledge >= 40 -> "lo conosci abbastanza"
        row.knowledge >= 15 -> "lo conosci poco"
        // Zero copre due casi che si assomigliano: non lo hai mai visto, oppure la lega
        // non ha ancora lo scouting. In tutti e due la frase e' vera.
        else -> "non lo conosci"
    }
    return "$base · $quanto"
}

/**
 * Gli attributi fuori ruolo restano **visibili ma spenti**: cosi' tutte le schede hanno
 * la stessa altezza e si vede comunque che un difensore ha 41 di tiro.
 */
@Composable
private fun Attributes(row: PlayerRow) {
    val player = row.player
    val relevant = player.primaryPosition.relevantAttributes.toSet()
    val attrs = player.primaryPosition.displayAttributes()

    Column(Modifier.padding(horizontal = MFootSpacing.gutter)) {
        SectionLabel("ATTRIBUTI")
        Spacer(Modifier.height(MFootSpacing.related))

        attrs.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MFootSpacing.gridHorizontal),
            ) {
                pair.forEach { attr ->
                    Box(Modifier.weight(1f)) {
                        AttributeCell(attr, player.attributes[attr], attr in relevant)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(MFootSpacing.gridVertical))
        }
    }

    Spacer(Modifier.height(MFootSpacing.related))
}

@Composable
private fun AttributeCell(attr: Attr, value: Int, key: Boolean) {
    Column(Modifier.alpha(if (key) 1f else 0.42f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                attr.label,
                style = MFootType.secondary,
                color = if (key) MFootColors.ink else MFootColors.ink2,
            )
            Text(value.toString(), style = MFootType.value, color = MFootColors.rating(value))
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.09f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value / 99f)
                    .height(4.dp)
                    .background(MFootColors.rating(value), RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MFootType.label, color = MFootColors.ink3)
        Spacer(Modifier.width(9.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MFootColors.line),
        )
    }
}

/**
 * Presenze, gol, media voto.
 *
 * ## Perche non c era
 *
 * Perche fino a ieri non esisteva `appearances`: la formazione salvata era una riga per
 * club, sovrascritta, e di chi avesse giocato la settimana scorsa non restava traccia. Gli
 * attributi dicono quanto vale; questo dice **cosa ha fatto**, che e la domanda che ci si
 * fa prima di rinnovargli il contratto.
 */
@Composable
private fun Carriera(carriera: dev.mfoot.android.data.Carriera) {
    if (carriera.vuota) return

    Column(Modifier.padding(horizontal = MFootSpacing.gutter)) {
        Spacer(Modifier.height(MFootSpacing.section))
        SectionLabel("IN CAMPO")
        Spacer(Modifier.height(MFootSpacing.related))

        Row(Modifier.fillMaxWidth()) {
            Voce("Presenze", "", Modifier.weight(1f))
            Voce("Da titolare", "", Modifier.weight(1f))
            Voce("Minuti", "", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Voce("Gol", "", Modifier.weight(1f))
            Voce("Assist", "", Modifier.weight(1f))
            Voce("Media voto", voto(carriera.mediaVoto), Modifier.weight(1f))
        }
    }
}

/** Un voto con la virgola, che e come si scrive in italiano. */
private fun voto(valore: Double): String =
    (StrictMath.round(valore * 10.0) / 10.0).toString().replace(Char(46), Char(44))

@Composable
private fun Voce(etichetta: String, valore: String, modifier: Modifier) {
    Column(modifier) {
        Text(valore, style = MFootType.value, color = MFootColors.ink)
        Spacer(Modifier.height(2.dp))
        Text(etichetta, style = MFootType.label, color = MFootColors.ink3)
    }
}

@Composable
private fun Stars(row: PlayerRow) {
    Row(
        Modifier
            .padding(horizontal = MFootSpacing.gutter)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MFootSpacing.gutter),
    ) {
        StarGroup("PIEDE DEBOLE", row.player.weakFoot, Modifier.weight(1f))
        StarGroup("TECNICA", row.player.skillStars, Modifier.weight(1f))
    }
    Spacer(Modifier.height(MFootSpacing.section))
}

@Composable
private fun StarGroup(label: String, filled: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MFootType.label, color = MFootColors.ink3)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(5) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (index < filled) MFootColors.ink2 else Color.White.copy(alpha = 0.09f),
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun Traits(row: PlayerRow) {
    val traits = row.player.traits
    Row(
        Modifier
            .padding(horizontal = MFootSpacing.gutter)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (traits.isEmpty()) {
            Text(
                "Nessun tratto noto",
                style = MFootType.chip,
                color = MFootColors.ink3,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.03f), MFootShapes.pill)
                    .border(1.dp, MFootColors.line, MFootShapes.pill)
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        } else {
            traits.take(2).forEach { trait ->
                Text(
                    trait.label,
                    style = MFootType.chip,
                    color = Color(0xFFA7F3C0),
                    modifier = Modifier
                        .background(MFootColors.elite.copy(alpha = 0.09f), MFootShapes.pill)
                        .border(1.dp, MFootColors.elite.copy(alpha = 0.22f), MFootShapes.pill)
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(MFootSpacing.section))
}

@Composable
private fun Footer(
    row: PlayerRow,
    canAuction: Boolean,
    isSelling: Boolean,
    youthAction: String?,
    onYouth: () -> Unit,
    onAuction: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MFootColors.line),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(MFootSpacing.gutter, 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Money(row.value).format(), style = MFootType.price, color = MFootColors.ink)
            Spacer(Modifier.width(6.dp))
            Text("valore stimato", style = MFootType.chip, color = MFootColors.ink3)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Anche la Primavera si decide guardando la scheda: e' li' che si vede l'eta'
            // accanto alla forbice di crescita, cioe' esattamente i due numeri che dicono
            // se conviene farlo maturare o farlo giocare.
            youthAction?.let { testo ->
                Text(
                    testo,
                    style = MFootType.value,
                    color = MFootColors.ink2,
                    modifier = Modifier
                        .border(1.dp, MFootColors.lineStrong, MFootShapes.pill)
                        .clickable(onClick = onYouth)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Spacer(Modifier.width(8.dp))
            }

            // L'asta si apre da qui e non da un menu: e' la decisione che si prende
            // guardando la scheda, e farla cercare altrove significa non farla prendere.
            if (canAuction) {
                Text(
                    if (isSelling) "Vendi all'asta" else "Metti all'asta",
                    style = MFootType.value,
                    color = MFootColors.bg,
                    modifier = Modifier
                        .background(MFootColors.elite, MFootShapes.pill)
                        .clickable(onClick = onAuction)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Spacer(Modifier.width(8.dp))
            }

            Text(
                "Chiudi",
                style = MFootType.value,
                color = MFootColors.bg,
                modifier = Modifier
                    .background(MFootColors.ink, MFootShapes.pill)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
