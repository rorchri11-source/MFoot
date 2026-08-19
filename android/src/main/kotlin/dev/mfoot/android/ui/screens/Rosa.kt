package dev.mfoot.android.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.PlayerRow
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.kit.CrestBadge
import dev.mfoot.android.ui.kit.Shirt
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.model.Money
import dev.mfoot.core.model.Reparto

/**
 * La rosa di un club qualsiasi.
 *
 * ## Perche' non e' la lista dei giocatori con un filtro
 *
 * Prima lo era, e mostrava sempre la propria rosa: la rotta portava il club toccato e
 * nessuno lo guardava. Ma anche funzionando sarebbe stata la schermata sbagliata. La lista
 * del mercato risponde a "chi posso prendere" e ha ricerca, filtri e prezzi; guardando la
 * rosa di un avversario la domanda e' un'altra — **com'e' fatta questa squadra** — e la
 * risposta si legge per reparto, non per prezzo.
 *
 * ## Perche' la maglia sta in cima
 *
 * Perche' e' il modo in cui si riconosce un club a colpo d'occhio senza leggere il nome, ed
 * e' la ragione per cui uno la sceglie. Una maglia che si disegna alla fondazione e non si
 * rivede mai piu' e' peggio che non poterla scegliere.
 */
@Composable
fun RosaScreen(
    state: AppState.Dentro,
    clubId: Long,
    onSelect: (PlayerRow) -> Unit,
    /** Null sulla propria rosa: li' la formazione ha gia' la sua scheda, e si modifica. */
    onFormazione: (() -> Unit)? = null,
) {
    val club = state.lega.clubs.firstOrNull { it.id == clubId }
    if (club == null) {
        Box(
            Modifier.fillMaxSize().background(MFootColors.bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Questo club non esiste piu'.",
                style = MFootType.secondary,
                color = MFootColors.ink3,
            )
        }
        return
    }

    val tutti = state.rows.filter { it.club?.id == clubId }

    // La Primavera sta a parte, non mescolata ai reparti. Sono giocatori che **non
    // scendono in campo**: metterli in mezzo alla difesa farebbe contare quattro difensori
    // dove ce ne sono tre disponibili, che e' precisamente la domanda a cui questa
    // schermata deve rispondere.
    val rosa = tutti.filterNot { it.isYouth }
    val primavera = tutti.filter { it.isYouth }.sortedByDescending { it.estimate.last }

    // Per reparto e non per overall: la domanda e' come e' fatta la squadra, e un elenco
    // ordinato per forza non fa vedere che mancano i terzini.
    val perReparto = Reparto.entries.map { reparto ->
        reparto to rosa
            .filter { it.player.primaryPosition.reparto == reparto }
            .sortedByDescending { it.player.overall }
    }

    LazyColumn(Modifier.fillMaxSize().background(MFootColors.bg)) {
        item { Intestazione(state, club, rosa.size, onFormazione) }

        perReparto.forEach { (reparto, giocatori) ->
            if (giocatori.isEmpty()) return@forEach
            item {
                Box(
                    Modifier.padding(
                        MFootSpacing.section,
                        MFootSpacing.section,
                        MFootSpacing.section,
                        8.dp,
                    ),
                ) {
                    Label("${etichetta(reparto)} · ${giocatori.size}")
                }
            }
            items(giocatori, key = { it.player.id.value }) { riga ->
                Giocatore(riga, onSelect)
            }
        }

        if (primavera.isNotEmpty()) {
            item {
                Column(
                    Modifier.padding(
                        MFootSpacing.section,
                        MFootSpacing.section,
                        MFootSpacing.section,
                        8.dp,
                    ),
                ) {
                    Label("Primavera · ${primavera.size}")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Si allenano e non giocano. Crescono piu' piano di chi scende in " +
                            "campo, ma crescono.",
                        style = MFootType.chip,
                        color = MFootColors.ink3,
                    )
                }
            }
            // Per potenziale e non per overall: in Primavera la domanda non e' chi e' piu'
            // forte oggi, e' su chi vale la pena aspettare.
            items(primavera, key = { it.player.id.value }) { riga ->
                Giocatore(riga, onSelect)
            }
        }

        if (tutti.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Rosa vuota. Non ha ancora comprato nessuno.",
                        style = MFootType.secondary,
                        color = MFootColors.ink3,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun Intestazione(
    state: AppState.Dentro,
    club: dev.mfoot.android.data.ClubInfo,
    inRosa: Int,
    onFormazione: (() -> Unit)?,
) {
    val minimo = state.lega.league.config.setup.minSquadSize
    val divisioni = state.lega.league.config.divisions

    Column(
        Modifier
            .fillMaxWidth()
            .background(MFootColors.core)
            .padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CrestBadge(club.crest, Modifier.size(78.dp), club.shortName)
            Shirt(club.kit, Modifier.size(96.dp, 108.dp), showNumber = false)
        }
        Spacer(Modifier.height(14.dp))
        Text(club.name, style = MFootType.playerName, color = MFootColors.ink)
        Spacer(Modifier.height(3.dp))
        Text(
            buildString {
                append(club.ownerName ?: if (club.isAi) "gestita dal computer" else "senza proprietario")
                if (club.isMine) append(" · la tua")
                // Dove gioca. Era il dato che non compariva in nessuna schermata: la
                // colonna esiste, decide promozioni e retrocessioni, e l'unico modo di
                // scoprire in che serie si e' finiti era guardare il calendario.
                if (divisioni.enabled) append(" · ").append(divisioni.nameOf(club.divisionLevel))
                if (club.parentClubId != null) append(" · Primavera")
            },
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        onFormazione?.let {
            Spacer(Modifier.height(14.dp))
            GhostButton("Vedi come schiera", onClick = it)
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MFootSpacing.related),
        ) {
            Riquadro("Disponibili", Money(club.available).formatShort(), MFootColors.elite, Modifier.weight(1f))
            Riquadro(
                "In rosa",
                "$inRosa",
                if (inRosa >= minimo) MFootColors.ink else MFootColors.gamble,
                Modifier.weight(1f),
            )
            Riquadro("Impegnati", Money(club.committedCredits).formatShort(), MFootColors.ink2, Modifier.weight(1f))
        }

        if (inRosa < minimo) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Sotto i $minimo del minimo: questa squadra non scende in campo.",
                style = MFootType.chip,
                color = MFootColors.gamble,
                textAlign = TextAlign.Center,
            )
        }
    }
    Hairline()
}

@Composable
private fun Riquadro(
    label: String,
    valore: String,
    colore: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(MFootColors.bg, MFootShapes.band)
            .border(1.dp, MFootColors.line, MFootShapes.band)
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(valore, style = MFootType.value, color = colore)
        Spacer(Modifier.height(3.dp))
        Text(label, style = MFootType.label, color = MFootColors.ink3)
    }
}

@Composable
private fun Giocatore(riga: PlayerRow, onSelect: (PlayerRow) -> Unit) {
    val p = riga.player

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(riga) }
            .padding(MFootSpacing.section, 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp, 23.dp)
                .background(MFootColors.core, MFootShapes.field)
                .border(1.dp, MFootColors.line, MFootShapes.field),
            contentAlignment = Alignment.Center,
        ) {
            Text(p.primaryPosition.short, style = MFootType.label, color = MFootColors.ink2)
        }
        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f)) {
            Text(
                p.shortName,
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text("${p.age} anni · ${Money(riga.value).formatShort()}", style = MFootType.chip, color = MFootColors.ink3)
        }

        // La stamina accanto all'overall, non solo dentro la scheda.
        //
        // La domanda che si fa scorrendo una rosa e' "chi schiero domenica", e ha due
        // risposte: quanto vale e se e' in piedi. Con la sola seconda cifra si schierava
        // sempre il migliore, si scopriva il perche' del rendimento dopo la partita, e
        // l'intero motivo per cui esistono una rosa profonda e una Primavera restava
        // invisibile.
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(38.dp)) {
            Text(
                "${p.stamina}",
                style = MFootType.chip,
                color = when {
                    p.stamina >= 70 -> MFootColors.ink3
                    p.stamina >= 40 -> MFootColors.gamble
                    else -> MFootColors.gamble
                },
            )
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MFootColors.line, MFootShapes.pill),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((p.stamina / 100f).coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(
                            if (p.stamina >= 70) MFootColors.elite else MFootColors.gamble,
                            MFootShapes.pill,
                        ),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Text(
            "${p.overall}",
            style = MFootType.overallRow,
            color = MFootColors.rating(p.overall),
        )
    }
    Hairline()
}

private fun etichetta(reparto: Reparto): String = when (reparto) {
    Reparto.PORTIERE -> "Porta"
    Reparto.DIFESA -> "Difesa"
    Reparto.CENTROCAMPO -> "Centrocampo"
    Reparto.ATTACCO -> "Attacco"
}
