package dev.mfoot.android.ui.screens

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
    onCarica: () -> Unit,
) {
    LaunchedEffect(state.lega.league.id) { onCarica() }

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

    LazyColumn(Modifier.fillMaxSize().background(MFootColors.bg)) {
        items(aste, key = { it.id }) { asta ->
            Asta(state, asta)
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun Asta(state: AppState.Dentro, asta: ClosedAuction) {
    val nome = if (asta.targetType == "staff") {
        "Membro dello staff #${asta.targetId}"
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
