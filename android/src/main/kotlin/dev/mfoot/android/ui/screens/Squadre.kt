package dev.mfoot.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.data.ClubInfo
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.model.Money

/**
 * Tutte le squadre della lega.
 *
 * ## Perche' serve
 *
 * Perche' e' una lega fra amici: sapere quanto ha in cassa quello che ti ha soffiato il
 * centravanti e' meta' del gioco. Senza questa schermata gli altri club esistevano solo
 * come sigla accanto al nome di un giocatore.
 *
 * ## L'ordine
 *
 * Il proprio club per primo, sempre. Poi gli altri per disponibilita' decrescente, perche'
 * la domanda che si fa aprendo questa schermata e' "chi puo' ancora spendere".
 */
@Composable
fun SquadreScreen(
    state: AppState.Dentro,
    onOpenClub: (Long) -> Unit,
) {
    val mio = state.lega.myClub
    val altri = state.lega.clubs
        .filterNot { it.id == mio?.id }
        .sortedByDescending { it.available }
    val ordinate = listOfNotNull(mio) + altri

    Column(Modifier.fillMaxSize().background(MFootColors.bg)) {
        Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 10.dp)) {
            Label("${state.lega.clubs.size} squadre · ordinate per disponibilita'")
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(ordinate, key = { it.id }) { club ->
                ClubRow(
                    club = club,
                    inRosa = state.lega.squadOf(club.id).size,
                    minimo = state.lega.league.config.setup.minSquadSize,
                ) { onOpenClub(club.id) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ClubRow(club: ClubInfo, inRosa: Int, minimo: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (club.isMine) MFootColors.elite.copy(alpha = 0.06f) else MFootColors.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = MFootSpacing.section, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Lo stemma e' la sigla su un colore ricavato dall'id: due club non hanno mai la
        // stessa tinta, e non serve che nessuno carichi un'immagine.
        Box(
            Modifier
                .size(38.dp)
                .background(colorFor(club.id), RoundedCornerShape(11.dp))
                .border(1.dp, MFootColors.lineStrong, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(club.shortName, style = MFootType.label, color = MFootColors.bg)
        }

        Spacer(Modifier.width(MFootSpacing.related))

        Column(Modifier.weight(1f)) {
            Text(
                club.name,
                style = MFootType.rowTitle,
                color = if (club.isMine) MFootColors.elite else MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(if (club.isAi) "AI" else club.ownerName ?: "senza proprietario")
                    append(" · ").append(inRosa).append(" in rosa")
                },
                style = MFootType.chip,
                color = if (inRosa < minimo) MFootColors.gamble else MFootColors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                Money(club.available).formatShort(),
                style = MFootType.value,
                color = MFootColors.ink,
            )
            if (club.committedCredits > 0) {
                Text(
                    "${Money(club.committedCredits).formatShort()} impegnati",
                    style = MFootType.chip,
                    color = MFootColors.gamble,
                )
            }
        }
    }

    Box(Modifier.fillMaxWidth().height(1.dp).background(MFootColors.line))
}

/**
 * Un colore stabile per ogni club.
 *
 * Deriva dall'id, quindi non cambia mai fra un'apertura e l'altra: uno stemma che cambia
 * tinta ogni volta smette di essere un modo per riconoscere la squadra a colpo d'occhio.
 */
private fun colorFor(id: Long): Color = STEMMI[(id % STEMMI.size).toInt()]

private val STEMMI = listOf(
    Color(0xFF2BE07E), Color(0xFF3D7BFF), Color(0xFFE8483F), Color(0xFFFFC53D),
    Color(0xFFB05CFF), Color(0xFF00C2C7), Color(0xFFFF7A3D), Color(0xFF8A0F2E),
    Color(0xFF7A8290), Color(0xFF0B3B8C), Color(0xFF00A6A6), Color(0xFFF2F4F7),
)
