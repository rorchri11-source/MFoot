package dev.mfoot.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import dev.mfoot.android.ui.Chip
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
    /**
     * Toccare una riga porta dove il fatto è successo.
     *
     * Era la cosa che mancava: il registro raccontava «asta vinta» e poi bisognava andarla
     * a cercare da soli. Il tipo decide la schermata, `target_id` decide **quale** cosa
     * aprire — e dove il bersaglio non c'è si va comunque nella sezione giusta, che è meno
     * preciso ma sempre meglio di una riga che non fa niente.
     */
    onApri: (NotificationRow) -> Unit = {},
    /** Cambia il tipo che si sta guardando, o null per tutti. */
    onFiltra: (String?) -> Unit = {},
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
    val visibili = novita.visibili
    val mie = visibili.filter { it.clubId != null && it.clubId == mioClub }
    val resto = visibili.filterNot { it.clubId != null && it.clubId == mioClub }
    val adesso = Instant.now()

    LazyColumn(fondo) {
        // I FILTRI
        //
        // Il registro tiene duecento righe, e la domanda che ci si fa non e' «cosa e'
        // successo» ma «cosa e' successo di quella cosa li'»: com'e' finita l'asta, chi mi
        // ha proposto uno scambio. Ordinare non toglie di mezzo le centonovanta righe che
        // non c'entrano; filtrare si'.
        //
        // Compaiono solo i tipi che ci sono davvero: un filtro «Prestito» in una lega dove
        // nessuno ha mai prestato nessuno e' un pulsante che porta sempre a una lista vuota.
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip("Tutte · ${novita.righe.size}", novita.filtro == null) { onFiltra(null) }
                novita.tipiPresenti.forEach { (kind, quante) ->
                    Chip("${etichetta(kind)} · $quante", novita.filtro == kind) { onFiltra(kind) }
                }
            }
        }

        if (visibili.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Niente di questo tipo.",
                        style = MFootType.secondary,
                        color = MFootColors.ink3,
                    )
                }
            }
        }

        if (mie.isNotEmpty()) {
            item {
                Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 8.dp)) {
                    Label("Riguarda te · ${mie.size}")
                }
            }
            items(mie, key = { it.id }) { riga ->
                Riga(riga, adesso, novita.nuovaDopo, tua = true, onApri = onApri)
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
                Riga(riga, adesso, novita.nuovaDopo, tua = false, onApri = onApri)
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
private fun Riga(
    riga: NotificationRow,
    adesso: Instant,
    nuovaDopo: Instant?,
    tua: Boolean,
    onApri: (NotificationRow) -> Unit,
) {
    val nuova = nuovaDopo == null || (riga.createdAt?.isAfter(nuovaDopo) == true)

    val apribile = riga.apribile

    Scheda(Modifier.padding(horizontal = MFootSpacing.section, vertical = 3.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                // Solo quello che porta davvero da qualche parte si può premere: una riga
                // cliccabile che non fa niente insegna a non fidarsi delle altre.
                .then(if (apribile) Modifier.clickable { onApri(riga) } else Modifier)
                .padding(14.dp, 11.dp),
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
                    buildString {
                        append(etichetta(riga.kind))
                        append(" · ")
                        append(riga.quando(adesso))
                        // Dire che si può toccare, e dove porta: senza, il tocco è una
                        // scoperta che quasi nessuno fa.
                        if (apribile) append(" · tocca per ${destinazione(riga.kind)}")
                    },
                    style = MFootType.chip,
                    color = if (apribile) MFootColors.ink2 else MFootColors.ink3,
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

/** Dove porta il tocco, scritto per chi legge e non per chi ha scritto il codice. */
private fun destinazione(kind: String): String = when (kind) {
    "partita" -> "guardarla"
    "asta" -> "vedere l'asta"
    "scambio", "prestito" -> "aprire la trattativa"
    "amichevole", "competizione" -> "il calendario"
    "scouting" -> "vedere l'osservatore"
    "primavera" -> "la Primavera"
    else -> "la rosa"
}
