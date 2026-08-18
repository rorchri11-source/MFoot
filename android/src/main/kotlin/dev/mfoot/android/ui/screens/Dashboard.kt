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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.Route
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.PrimaryButton
import dev.mfoot.android.ui.kit.Kit
import dev.mfoot.android.ui.kit.Shirt
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.model.Money

/**
 * La schermata che si apre per prima — **il tuo club**.
 *
 * ## Cosa ci sta e cosa no
 *
 * La maglia grande, il nome, e i tre numeri che servono a decidere qualcosa adesso: quanto
 * si puo' spendere, quanti giocatori ci sono, quante aste sono in corso. Poi, se c'e' una
 * decisione che scade, sta qui in evidenza.
 *
 * Non ci sta un riassunto di tutto: una dashboard che mostra dodici riquadri non fa
 * risparmiare tempo, lo fa perdere, perche' obbliga a cercare fra dodici cose quale
 * riguarda adesso.
 */
@Composable
fun DashboardScreen(
    state: AppState.Dentro,
    onNavigate: (Route) -> Unit,
    onFoundClub: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val club = state.lega.myClub

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(MFootSpacing.section),
    ) {
        state.errore?.let {
            Notice(it, MFootColors.gamble)
            Spacer(Modifier.height(MFootSpacing.related))
        }
        state.avviso?.let {
            Notice(it, MFootColors.elite, Modifier.clickable(onClick = onDismissNotice))
            Spacer(Modifier.height(MFootSpacing.related))
        }

        if (club == null) {
            SenzaClub(onFoundClub)
            return@Column
        }

        Text(
            club.name,
            style = MFootType.playerName,
            color = MFootColors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            club.ownerName ?: "il tuo club",
            style = MFootType.chip,
            color = MFootColors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // La maglia del club, non quella predefinita. Disegnarne una fissa qui voleva
            // dire che i colori scelti alla fondazione si vedevano una volta sola, durante
            // la scelta, e poi mai piu': salvati, e invisibili.
            Shirt(club.kit, Modifier.size(148.dp, 166.dp), showNumber = false)
        }
        Spacer(Modifier.height(24.dp))

        val rosa = state.lega.squadOf(club.id)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Numero(
                valore = Money(club.available).format(),
                etichetta = "Disponibili",
                colore = if (club.available > 0) MFootColors.elite else MFootColors.gamble,
                modifier = Modifier.weight(1f),
            )
            Numero(
                valore = rosa.size.toString(),
                etichetta = "In rosa",
                colore = if (rosa.size >= state.lega.league.config.setup.minSquadSize) {
                    MFootColors.ink
                } else {
                    MFootColors.gamble
                },
                modifier = Modifier.weight(1f),
            )
            Numero(
                valore = state.myAuctions.size.toString(),
                etichetta = "Tue aste",
                colore = if (state.myAuctions.isEmpty()) MFootColors.ink3 else MFootColors.gamble,
                modifier = Modifier.weight(1f),
            )
        }

        // La rosa incompleta non e' un dettaglio: senza il minimo, la squadra non scende in
        // campo e le partite si rinviano. Va detto qui, non scoperto dal registro del tick.
        val minimo = state.lega.league.config.setup.minSquadSize
        if (rosa.size < minimo) {
            Spacer(Modifier.height(MFootSpacing.section))
            Notice(
                "Ti servono ${minimo - rosa.size} giocatori per arrivare a $minimo: " +
                    "sotto il minimo la squadra non scende in campo.",
                MFootColors.gamble,
            )
            Spacer(Modifier.height(MFootSpacing.related))
            PrimaryButton(text = "Vai al mercato", onClick = { onNavigate(Route.Svincolati) })
        }

        Spacer(Modifier.height(28.dp))
        Label("Scorciatoie")
        Spacer(Modifier.height(10.dp))
        Scorciatoia("Schiera la squadra", "Campo, modulo, panchina") { onNavigate(Route.Campo) }
        Scorciatoia("Aste aperte", "${state.auctions.size} in corso nella lega") { onNavigate(Route.Aste) }
        Scorciatoia("Classifica", "Punti e calendario") { onNavigate(Route.Classifica) }
        Scorciatoia("Le altre squadre", "${state.lega.clubs.size} club") { onNavigate(Route.Squadre) }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun SenzaClub(onFoundClub: () -> Unit) {
    Spacer(Modifier.height(40.dp))
    Text(
        "Non hai ancora un club",
        style = MFootType.playerName,
        color = MFootColors.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Scegli nome, maglia, e costruisci il giocatore che sei tu. " +
            "Senza club non si compra, non si schiera e non si gioca.",
        style = MFootType.secondary,
        color = MFootColors.ink3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(28.dp))
    PrimaryButton("Fonda il tuo club", onFoundClub)
}

@Composable
private fun Numero(
    valore: String,
    etichetta: String,
    colore: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(MFootColors.core, MFootShapes.band)
            .border(1.dp, MFootColors.lineStrong, MFootShapes.band)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(valore, style = MFootType.price, color = colore)
        Spacer(Modifier.height(3.dp))
        Text(etichetta.uppercase(), style = MFootType.label, color = MFootColors.ink3)
    }
}

@Composable
private fun Scorciatoia(titolo: String, dettaglio: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MFootColors.core, MFootShapes.field)
            .border(1.dp, MFootColors.lineStrong, MFootShapes.field)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(titolo, style = MFootType.rowTitle, color = MFootColors.ink)
            Text(dettaglio, style = MFootType.chip, color = MFootColors.ink3)
        }
        Text("›", style = MFootType.price, color = MFootColors.ink3)
    }
    Spacer(Modifier.height(8.dp))
}
