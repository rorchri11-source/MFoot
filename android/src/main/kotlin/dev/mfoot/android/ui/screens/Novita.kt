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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.NovitaState
import dev.mfoot.android.data.NotificationRow
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Scheda
import dev.mfoot.android.ui.Vuoto
import dev.mfoot.android.ui.icons.MFootIcons
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import java.time.Instant

/**
 * Cosa è successo mentre non c'eri.
 *
 * ## Perché questa schermata è arrivata dopo mesi
 *
 * Perché il registro c'era già, e non lo leggeva nessuno. Il server scriveva
 * diligentemente ogni evento nella tabella `notifications` — aste chiuse, scambi
 * proposti, giocatori cresciuti, contratti in scadenza — e l'unica uscita prevista era
 * Telegram. Il 2026-08-26 il proprietario ha detto la cosa che quel progetto non aveva
 * previsto: **nel suo gruppo Telegram non lo usa nessuno**.
 *
 * Anche `NotificationRepository` era scritto per intero, con il «3 minuti fa / ieri / 4
 * giorni» già dentro. Non lo chiamava nessuna schermata. Era una stanza costruita senza
 * porta.
 *
 * ## Perché le proprie vengono prima
 *
 * Perché la domanda che si fa aprendo l'app non è «cosa è successo nella lega» ma **«cosa
 * è successo a me»**. Un'asta vinta, uno scambio proposto e un contratto in scadenza
 * cambiano quello che farai adesso; una giornata giocata da altri no.
 *
 * Le due liste restano nella stessa schermata, però, e non in due schede: separarle
 * costringerebbe a controllarne due, che è il modo più rapido di far smettere di
 * controllarne una.
 */
@Composable
fun NovitaScreen(
    state: AppState.Dentro,
    novita: NovitaState,
    onCarica: () -> Unit,
) {
    LaunchedEffect(state.lega.league.id) { onCarica() }

    val fondo = Modifier.fillMaxSize().background(MFootColors.bg)

    if (novita.errore != null) {
        Vuoto(novita.errore, fondo, icona = MFootIcons.archivio)
        return
    }
    if (!novita.letto) {
        Vuoto("Leggo…", fondo, icona = MFootIcons.archivio)
        return
    }
    if (novita.righe.isEmpty()) {
        Vuoto(
            "Ancora niente da raccontare.\n\n" +
                "Qui finisce quello che succede quando non stai guardando: aste che " +
                "chiudono, proposte che arrivano, partite giocate, contratti in scadenza.",
            fondo,
            icona = MFootIcons.archivio,
        )
        return
    }

    val mioClub = state.lega.myClub?.id
    val mie = novita.righe.filter { it.clubId != null && it.clubId == mioClub }
    val resto = novita.righe.filterNot { it.clubId != null && it.clubId == mioClub }
    val adesso = Instant.now()

    LazyColumn(fondo) {
        if (mie.isNotEmpty()) {
            item {
                Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 8.dp)) {
                    Label("Riguarda te · ${mie.size}")
                }
            }
            items(mie, key = { it.id }) { riga ->
                Riga(riga, adesso, novita.nuovaDopo, tua = true)
            }
            item { Spacer(Modifier.height(MFootSpacing.section)) }
        }

        if (resto.isNotEmpty()) {
            item {
                Column(Modifier.padding(MFootSpacing.section, 0.dp, MFootSpacing.section, 8.dp)) {
                    Label("Nella lega · ${resto.size}")
                }
            }
            items(resto, key = { it.id }) { riga ->
                Riga(riga, adesso, novita.nuovaDopo, tua = false)
            }
        }

        item { Spacer(Modifier.height(30.dp)) }
    }
}

/**
 * Una riga.
 *
 * Il **pallino** dice «questa non l'avevi ancora vista», e sparisce alla riapertura
 * successiva. Senza, un registro di duecento righe costringe a ricordarsi dove si era
 * arrivati, che è precisamente il lavoro che un registro dovrebbe togliere.
 */
@Composable
private fun Riga(riga: NotificationRow, adesso: Instant, nuovaDopo: Instant?, tua: Boolean) {
    val nuova = nuovaDopo == null || (riga.createdAt?.isAfter(nuovaDopo) == true)

    Scheda(Modifier.padding(horizontal = MFootSpacing.section, vertical = 3.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp, 11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(8.dp).padding(top = 5.dp)) {
                if (nuova) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (tua) MFootColors.elite else MFootColors.ink3),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    riga.body,
                    style = MFootType.secondary,
                    color = if (nuova) MFootColors.ink else MFootColors.ink2,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${etichetta(riga.kind)} · ${riga.quando(adesso)}",
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Il tipo, scritto come lo direbbe una persona.
 *
 * I valori arrivano dal server e sono parole macchina — `asta`, `primavera`,
 * `scouting` — che compaiono così come sono se nessuno le traduce.
 */
private fun etichetta(kind: String): String = when (kind) {
    "asta" -> "Asta"
    "mercato" -> "Mercato"
    "partita" -> "Partita"
    "scambio" -> "Scambio"
    "prestito" -> "Prestito"
    "amichevole" -> "Amichevole"
    "contratto" -> "Contratto"
    "primavera" -> "Primavera"
    "scouting" -> "Osservatori"
    else -> kind.replaceFirstChar { it.uppercase() }
}
