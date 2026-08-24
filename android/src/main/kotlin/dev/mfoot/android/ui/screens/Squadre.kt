package dev.mfoot.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.data.ClubInfo
import dev.mfoot.android.ui.Banda
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Scheda
import dev.mfoot.android.ui.kit.CrestBadge
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.comparsa
import dev.mfoot.android.ui.theme.lampo
import dev.mfoot.android.ui.theme.ricordaIntro
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

    // La cascata dura il tempo della prima comparsa, poi si spegne: dentro una
    // `LazyColumn` le righe che escono vengono buttate via e ricostruite, e con
    // l'animazione sempre accesa scorrere all'indietro le farebbe rientrare tremolando.
    val intro = ricordaIntro(state.lega.league.id)

    LazyColumn(
        Modifier.fillMaxSize().background(MFootColors.bg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(MFootSpacing.related),
    ) {
        item(key = "sommario") {
            Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 0.dp)) {
                Label(
                    if (divisioni.enabled) {
                        "${state.lega.clubs.size} squadre in ${gruppi.size} divisioni"
                    } else {
                        "${state.lega.clubs.size} squadre · ordinate per disponibilità"
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
        }

        gruppi.forEach { (livello, club) ->
            if (divisioni.enabled) {
                item(key = "div-$livello") {
                    // La banda blu a tutta larghezza, come la giornata nel calendario del
                    // riferimento. Arriva ai bordi di proposito: con dei margini
                    // diventerebbe una scheda larga e smetterebbe di leggersi come lo
                    // stacco fra due campionati.
                    Banda(
                        buildString {
                            append(divisioni.nameOf(livello))
                            append(" · ").append(club.size).append(" squadre")
                            if (livello == mio?.divisionLevel) append(" · la tua")
                        },
                    )
                }
            }
            itemsIndexed(club, key = { _, c -> c.id }) { indice, c ->
                ClubRow(
                    club = c,
                    inRosa = state.lega.squadOf(c.id).size,
                    minimo = minimo,
                    modifier = Modifier
                        .padding(horizontal = MFootSpacing.section)
                        .comparsa(indice, intro),
                ) { onOpenClub(c.id) }
            }
        }
    }
}

/**
 * Una squadra: stemma, nome, chi la porta, e quanto le resta.
 *
 * E' la forma dell'elenco squadre del riferimento — tondo, due righe di testo, un numero
 * grande a destra con la sua etichetta — e la propria si riconosce dalla barretta blu a
 * sinistra invece che da un fondo colorato: il fondo colorato su una scheda gia' scura
 * non si vedeva.
 */
@Composable
private fun ClubRow(
    club: ClubInfo,
    inRosa: Int,
    minimo: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // La lama di luce sulla propria: la barretta blu lo dice gia' da ferma, questo lo dice
    // **all'apertura**, che e' il momento in cui si sta ancora cercando.
    Scheda(modifier.lampo(club.isMine, club.id), onClick, evidenziata = club.isMine) {
        Row(
            Modifier.padding(
                start = if (club.isMine) 10.dp else 14.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Lo stemma vero, non un quadrato colorato.
            //
            // Prima il colore si ricavava dall'id e non aveva niente a che vedere con
            // quello che il proprietario aveva disegnato: lo stesso club aveva due
            // identita' diverse a seconda della schermata.
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                CrestBadge(club.crest, Modifier.size(46.dp), club.shortName)
            }
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(club.name)
                        // La seconda squadra si riconosce a colpo d'occhio: senza, in un
                        // elenco di venti righe «Milan» e «Milan Primavera» sono due club
                        // qualsiasi che per caso si somigliano.
                        if (club.parentClubId != null) append("  ⤷")
                    },
                    style = MFootType.rowTitle,
                    color = if (club.isMine) MFootColors.elite else MFootColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(if (club.isAi) "AI" else club.ownerName ?: "senza proprietario")
                        append(" · ").append(inRosa).append(" in rosa")
                        if (club.parentClubId != null) append(" · Primavera")
                    },
                    style = MFootType.secondary,
                    color = if (inRosa < minimo) MFootColors.gamble else MFootColors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Money(club.available).formatShort(),
                    style = MFootType.price,
                    color = MFootColors.elite,
                )
                if (club.committedCredits > 0) {
                    Label("${Money(club.committedCredits).formatShort()} impegnati", color = MFootColors.gamble)
                } else {
                    Label("crediti")
                }
            }
        }
    }
}
