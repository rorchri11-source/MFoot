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
import dev.mfoot.android.ui.kit.CrestBadge
import dev.mfoot.android.ui.kit.Shirt
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
    val divisioni = state.lega.league.config.divisions
    val mio = state.lega.myClub
    val minimo = state.lega.league.config.setup.minSquadSize

    // Raggruppate per divisione, non tutte in fila.
    //
    // ## Perche' non bastava ordinarle per disponibilita'
    //
    // Perche' rispondeva a una domanda sola — chi puo' ancora spendere — e ne lasciava
    // scoperta una piu' importante: **in che campionato gioco, e contro chi**. Con venti
    // club in un elenco unico, la Serie A e la Serie B erano indistinguibili: `division_level`
    // esisteva sul database, decideva promozioni e retrocessioni, e non era scritto in
    // nessuna schermata. Chi retrocedeva se ne accorgeva dal calendario.
    //
    // Con una divisione sola il raggruppamento non si vede: c'e' un blocco solo, senza
    // titolo, ed e' esattamente com'era prima.
    val gruppi = state.lega.clubs
        .groupBy { it.divisionLevel }
        .toSortedMap()
        .map { (livello, club) ->
            livello to (
                club.sortedWith(
                    // Il proprio per primo dentro la sua divisione, poi gli altri per
                    // disponibilita': la domanda "chi puo' spendere" resta, dentro il
                    // gruppo in cui ha senso farsela.
                    compareByDescending<ClubInfo> { it.id == mio?.id }
                        .thenByDescending { it.available },
                )
                )
        }

    Column(Modifier.fillMaxSize().background(MFootColors.bg)) {
        Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 10.dp)) {
            Label(
                if (divisioni.enabled) {
                    "${state.lega.clubs.size} squadre in ${gruppi.size} divisioni"
                } else {
                    "${state.lega.clubs.size} squadre · ordinate per disponibilita'"
                },
            )
            mio?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("Tu giochi in ")
                        append(if (divisioni.enabled) divisioni.nameOf(it.divisionLevel) else "girone unico")
                        append(" con ")
                        append(gruppi.firstOrNull { g -> g.first == it.divisionLevel }?.second?.size ?: 0)
                        append(" squadre.")
                    },
                    style = MFootType.chip,
                    color = MFootColors.elite,
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            gruppi.forEach { (livello, club) ->
                if (divisioni.enabled) {
                    item(key = "div-$livello") {
                        Intestazione(divisioni.nameOf(livello), club.size, livello == mio?.divisionLevel)
                    }
                }
                items(club, key = { it.id }) { c ->
                    ClubRow(
                        club = c,
                        inRosa = state.lega.squadOf(c.id).size,
                        minimo = minimo,
                    ) { onOpenClub(c.id) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Il nome della divisione, con quante squadre ci sono e se e' la propria. */
@Composable
private fun Intestazione(nome: String, quante: Int, mia: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (mia) MFootColors.elite.copy(alpha = 0.10f) else MFootColors.core)
            .padding(MFootSpacing.section, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            nome.uppercase(),
            style = MFootType.label,
            color = if (mia) MFootColors.elite else MFootColors.ink2,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (mia) "$quante squadre · la tua" else "$quante squadre",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MFootColors.line))
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
        // La maglia vera, non un quadrato colorato.
        //
        // Prima il colore si ricavava dall'id e non aveva niente a che vedere con la maglia
        // che il proprietario aveva disegnato: lo stesso club aveva due identita' diverse a
        // seconda della schermata. Qui c'e' quella che scende in campo.
        CrestBadge(club.crest, Modifier.size(40.dp), club.shortName)
        Spacer(Modifier.width(8.dp))
        Shirt(club.kit, Modifier.size(30.dp, 34.dp), showNumber = false)

        Spacer(Modifier.width(MFootSpacing.related))

        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(club.name)
                    // La seconda squadra si riconosce a colpo d'occhio: senza, in una
                    // classifica di venti righe «Milan» e «Milan Primavera» sono due club
                    // qualsiasi che per caso si somigliano.
                    if (club.parentClubId != null) append("  ⤷")
                },
                style = MFootType.rowTitle,
                color = if (club.isMine) MFootColors.elite else MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(if (club.isAi) "AI" else club.ownerName ?: "senza proprietario")
                    append(" · ").append(inRosa).append(" in rosa")
                    if (club.parentClubId != null) append(" · Primavera")
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
