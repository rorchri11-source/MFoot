package dev.mfoot.android.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.mfoot.android.ui.Coriandoli
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.data.ClosedAuction
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.model.Money

/**
 * Come sono andate le aste finite.
 *
 * ## Perche' i massimi si vedono solo qui
 *
 * Durante l'asta l'offerta e' un **massimo segreto**: si dichiara fin dove si e' disposti a
 * spingersi e il sistema difende la posizione da solo, il che e' cio' che permette di
 * andare a dormire invece di controllare il telefono ogni ora. Vederli mentre l'asta e'
 * aperta cancellerebbe la meccanica — sapendo che il capofila si ferma a diciotto, si offre
 * diciotto e cento e si vince sempre.
 *
 * A cose finite non c'e' piu' niente da proteggere, e scoprire chi si era spinto fino a
 * dove e' la parte piu' bella di un'asta. Finora spariva senza che nessuno la vedesse.
 */
@Composable
fun AsteConcluseScreen(
    state: AppState.Dentro,
    aste: List<ClosedAuction>,
    letto: Boolean,
    nomeStaff: (Long) -> String,
    onCarica: () -> Unit,
) {
    LaunchedEffect(state.lega.league.id) { onCarica() }

    // Hai vinto qualcosa mentre non guardavi?
    //
    // Le aste concluse arrivano tutte insieme, e fra quelle ce ne possono essere di tue.
    // La festa parte una volta sola per apertura della scheda — non a ogni ridisegno — e
    // solo se c'e' davvero un acquisto tuo: `remember` sulla lega tiene il conto.
    val mio = state.lega.myClub?.id
    val vinte = aste.count { it.status == "AGGIUDICATA" && it.winnerClubId != null && it.winnerClubId == mio }
    var festeggiato by remember(state.lega.league.id) { mutableStateOf(false) }
    LaunchedEffect(vinte) { if (vinte > 0) festeggiato = true }

    if (aste.isEmpty()) {
        Box(
            Modifier.fillMaxSize().background(MFootColors.bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (letto) "Nessun'asta finita, per adesso." else "Leggo…",
                style = MFootType.secondary,
                color = MFootColors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(40.dp),
            )
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().background(MFootColors.bg)) {
            items(aste, key = { it.id }) { asta ->
                Asta(state, asta, nomeStaff)
            }
            item { Spacer(Modifier.height(30.dp)) }
        }

        // I coriandoli nei colori della tua maglia, sopra l'elenco.
        //
        // Non intercettano il tocco — sono un `Canvas` senza `clickable` — quindi si puo'
        // continuare a scorrere mentre volano.
        val kit = state.lega.myClub?.kit
        Coriandoli(
            attivi = festeggiato,
            tinte = listOfNotNull(
                kit?.let { Color(it.primary) },
                kit?.let { Color(it.secondary) },
                MFootColors.elite,
                MFootColors.gamble,
            ),
        )
    }
}

@Composable
private fun Asta(state: AppState.Dentro, asta: ClosedAuction, nomeStaff: (Long) -> String) {
    val nome = if (asta.targetType == "staff") {
        nomeStaff(asta.targetId)
    } else {
        state.lega.players.firstOrNull { it.id.value == asta.targetId }?.fullName
            ?: "Giocatore #${asta.targetId}"
    }

    Column(Modifier.fillMaxWidth().padding(MFootSpacing.section, 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                nome,
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                asta.esito,
                style = MFootType.chip,
                color = if (asta.status == "AGGIUDICATA") MFootColors.elite else MFootColors.ink3,
            )
        }

        asta.finalPrice?.let { prezzo ->
            Spacer(Modifier.height(3.dp))
            Text(
                "${state.lega.clubs.firstOrNull { it.id == asta.winnerClubId }?.name ?: "qualcuno"}" +
                    " · ${Money(prezzo).format()}",
                style = MFootType.chip,
                color = MFootColors.ink2,
            )
        }

        if (asta.bids.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Nessuno ha offerto.", style = MFootType.chip, color = MFootColors.ink3)
        } else {
            Spacer(Modifier.height(9.dp))
            Label("Chi ci ha provato")
            Spacer(Modifier.height(5.dp))

            // In ordine decrescente: si legge come una classifica, che e' quello che e'.
            // Il numero e' il **massimo dichiarato**, non quanto ha pagato: e' la cifra
            // che dice quanto lo voleva davvero.
            asta.bids.forEachIndexed { posto, offerta ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        "${posto + 1}.",
                        style = MFootType.label,
                        color = MFootColors.ink3,
                        modifier = Modifier.width(22.dp),
                    )
                    Text(
                        state.lega.clubs.firstOrNull { it.id == offerta.clubId }?.name
                            ?: "Club #${offerta.clubId}",
                        style = MFootType.chip,
                        color = if (offerta.clubId == asta.winnerClubId) {
                            MFootColors.elite
                        } else {
                            MFootColors.ink2
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        Money(offerta.maxAmount).format(),
                        style = MFootType.chip,
                        color = MFootColors.ink2,
                    )
                }
            }
        }
    }
    Hairline()
}
