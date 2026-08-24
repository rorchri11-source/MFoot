package dev.mfoot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.ui.icons.MFootIcons
import dev.mfoot.android.ui.kit.CrestBadge
import androidx.compose.foundation.layout.size
import dev.mfoot.android.app.TableState
import dev.mfoot.android.app.TableTab
import dev.mfoot.android.data.MatchRow
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import java.time.format.DateTimeFormatter

private val QUANDO = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/**
 * Classifica e calendario — **la Serie A della lega**.
 *
 * ## La classifica non e' salvata da nessuna parte
 *
 * Il database conserva i risultati; punti, differenza reti e ordine si ricalcolano da
 * quelli con i criteri di spareggio scelti dall'admin. Salvarla sarebbe un secondo posto
 * dove la stessa verita' puo' andare alla deriva, e basterebbe un risultato corretto a
 * mano perche' i due non tornino piu'.
 */
@Composable
fun TableScreen(
    state: TableState,
    onPickCompetition: (Long) -> Unit,
    onPickTab: (TableTab) -> Unit,
    onOpenMatch: (MatchRow) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg),
    ) {
        Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 12.dp)) {
            if (state.competitions.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.competitions.forEach { c ->
                        Chip(c.name, c.id == state.selectedId) { onPickCompetition(c.id) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TableTab.entries.forEach { tab ->
                    Chip(tab.label, tab == state.tab) { onPickTab(tab) }
                }
            }
        }

        val view = state.view
        when {
            state.errore != null ->
                Center(state.errore, MFootColors.gamble)

            view == null ->
                Center("Nessuna competizione. L'admin deve ancora crearne una.", MFootColors.ink3)

            view.matches.isEmpty() ->
                Center("Nessuna partita in calendario.", MFootColors.ink3)

            state.tab == TableTab.CLASSIFICA && view.rows.isEmpty() ->
                Center(
                    "Questa competizione non ha una classifica: è a eliminazione. " +
                        "Guarda il calendario.",
                    MFootColors.ink3,
                )

            else -> Content(state, view.matches, state.tab, onOpenMatch)
        }
    }
}

@Composable
private fun Center(text: String, color: androidx.compose.ui.graphics.Color) {
    Vuoto(text, icona = if (color == MFootColors.gamble) MFootIcons.info else MFootIcons.medaglia)
}

@Composable
private fun Content(
    state: TableState,
    matches: List<MatchRow>,
    tab: TableTab,
    onOpenMatch: (MatchRow) -> Unit,
) {
    val view = state.view ?: return

    LazyColumn(Modifier.fillMaxSize()) {
        if (tab == TableTab.CLASSIFICA) {
            item { TableHeader() }
            itemsIndexed(view.rows.size) { index ->
                val row = view.rows[index]
                TableRow(
                    position = index + 1,
                    name = state.clubName(row.club.value),
                    played = row.played,
                    won = row.won,
                    drawn = row.drawn,
                    lost = row.lost,
                    goalsFor = row.goalsFor,
                    goalsAgainst = row.goalsAgainst,
                    points = row.points,
                    mine = row.club.value == state.myClubId,
                )
            }
            item { TableFooter() }
            item { Spacer(Modifier.height(28.dp)) }
        }

        // Le partite raggruppate per turno: e' cosi' che si guarda un calendario, non
        // come un elenco piatto di cinquanta righe.
        if (tab == TableTab.CALENDARIO) {
            matches.groupBy { it.round }.toSortedMap().forEach { (round, ofRound) ->
                item {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Banda(
                            ofRound.firstOrNull()?.roundLabel?.takeIf { it.isNotBlank() }
                                ?: "Turno $round",
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(ofRound, key = { it.id }) { match ->
                    MatchLine(match, state, onOpenMatch)
                }
            }
        }

        item { Spacer(Modifier.height(30.dp)) }
    }
}

/** `itemsIndexed` su un conteggio: evita di dover esporre la lista due volte. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    count: Int,
    content: @Composable (Int) -> Unit,
) = items(count) { content(it) }

/**
 * La testata blu della classifica.
 *
 * Nel riferimento la tabella e' una scheda unica con il cappello blu: le colonne si
 * leggono perche' il colore le stacca dalle righe, non perche' ci sia una linea sotto.
 * Gli angoli tondi solo in alto — sotto continua la tabella, e arrotondarli li' spezzerebbe
 * la scheda in due.
 */
@Composable
private fun TableHeader() {
    Row(
        Modifier
            .padding(horizontal = MFootSpacing.section)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(MFootColors.blue)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(26.dp))
        Label("Squadra", Modifier.weight(1f), color = Color.White)
        listOf("G", "V", "N", "P", "DR").forEach {
            Label(it, Modifier.width(26.dp).padding(start = 2.dp), color = Color.White)
        }
        Label("PT", Modifier.width(30.dp), color = Color.White)
    }
}

/**
 * Una riga di classifica.
 *
 * La propria squadra e' evidenziata: in venti righe di nomi inventati, trovare la propria
 * senza leggerle tutte e' la differenza fra consultare e cercare.
 */
@Composable
private fun TableRow(
    position: Int,
    name: String,
    played: Int,
    won: Int,
    drawn: Int,
    lost: Int,
    goalsFor: Int,
    goalsAgainst: Int,
    points: Int,
    mine: Boolean,
) {
    Row(
        Modifier
            .padding(horizontal = MFootSpacing.section)
            .fillMaxWidth()
            .background(MFootColors.core),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // La barretta blu segna la propria, come nell'elenco squadre. Prima era un fondo
        // appena schiarito: su una tabella gia' scura, con venti righe, non si vedeva.
        Box(
            Modifier
                .width(4.dp)
                .height(42.dp)
                .background(if (mine) MFootColors.blue else Color.Transparent),
        )
        Row(
            Modifier.padding(start = 8.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                position.toString(),
                style = MFootType.value,
                color = if (position <= 3) MFootColors.elite else MFootColors.ink3,
                modifier = Modifier.width(26.dp),
            )
            Text(
                name,
                style = MFootType.rowTitle,
                color = if (mine) MFootColors.elite else MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            listOf(played, won, drawn, lost, goalsFor - goalsAgainst).forEach {
                Text(
                    it.toString(),
                    style = MFootType.chip,
                    color = MFootColors.ink2,
                    modifier = Modifier.width(26.dp).padding(start = 2.dp),
                )
            }
            Text(
                points.toString(),
                style = MFootType.overallRow,
                color = MFootColors.elite,
                modifier = Modifier.width(30.dp),
            )
        }
    }
    Box(
        Modifier
            .padding(horizontal = MFootSpacing.section)
            .fillMaxWidth()
            .height(1.dp)
            .background(MFootColors.bg),
    )
}

/**
 * Un lato della scheda partita: stemma sopra, nome sotto.
 *
 * Lo stemma e' quello vero, disegnato dal proprietario. Nel riferimento e' il pezzo che
 * rende il calendario leggibile a colpo d'occhio: due nomi affiancati si confondono, due
 * stemmi no — ed e' il motivo per cui i club hanno uno stemma.
 */
@Composable
private fun Squadra(state: TableState, clubId: Long, mine: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val crest = state.crestOf(clubId)
        if (crest != null) {
            CrestBadge(crest, Modifier.size(44.dp), state.shortOf(clubId))
        } else {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MFootColors.bg),
                contentAlignment = Alignment.Center,
            ) {
                Text(state.shortOf(clubId), style = MFootType.value, color = MFootColors.ink3)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            state.clubName(clubId),
            style = MFootType.secondary,
            color = if (mine) MFootColors.ink else MFootColors.ink2,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Chiude la scheda della classifica: sono gli angoli tondi in fondo alla tabella. */
@Composable
private fun TableFooter() {
    Box(
        Modifier
            .padding(horizontal = MFootSpacing.section)
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
            .background(MFootColors.core),
    )
}

/**
 * Una partita del calendario.
 *
 * Le due squadre affiancate con il risultato in mezzo, come nel riferimento, e non
 * «Casa — Trasferta» su una riga sola: con due nomi lunghi quella riga si troncava a
 * meta' e non si capiva piu' chi giocasse contro chi.
 */
@Composable
private fun MatchLine(match: MatchRow, state: TableState, onOpen: (MatchRow) -> Unit) {
    val mine = match.homeClubId == state.myClubId || match.awayClubId == state.myClubId

    Scheda(
        Modifier.padding(horizontal = MFootSpacing.section, vertical = 4.dp),
        // Solo le giocate si aprono: una partita che deve ancora cominciare non ha
        // niente da far rivedere.
        onClick = if (match.played) ({ onOpen(match) }) else null,
        evidenziata = mine,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Squadra(state, match.homeClubId, mine, Modifier.weight(1f))
                Text(
                    match.scoreline,
                    style = if (match.played) MFootType.price else MFootType.chip,
                    color = if (match.played) MFootColors.elite else MFootColors.ink3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clip(MFootShapes.pill)
                        .background(MFootColors.bg)
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                )
                Squadra(state, match.awayClubId, mine, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (match.played) {
                    "tocca per rivederla"
                } else {
                    state.oraDi(match)?.format(QUANDO) ?: "giornata ${match.matchDay}"
                },
                style = MFootType.chip,
                color = if (match.played) MFootColors.elite else MFootColors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
