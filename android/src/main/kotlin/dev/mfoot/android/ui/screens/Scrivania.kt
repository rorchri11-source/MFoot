package dev.mfoot.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.DeskState
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.StatRow
import dev.mfoot.android.ui.kit.CrestBadge
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.model.Money
import java.time.Duration
import java.time.Instant

/**
 * Il profilo della lega: cos'e' questa lega, in che stato, con che regole.
 *
 * ## Il codice d'accesso adesso c'e', ed era un errore tenerlo nascosto
 *
 * L'argomento di prima era che mostrarlo avrebbe richiesto di salvarlo in chiaro, e che a
 * quel punto chiunque fosse gia' dentro avrebbe potuto farci entrare un estraneo. Il
 * ragionamento non regge: chi e' dentro il codice **lo ha digitato**, quindi lo conosce
 * gia'. Nascondendolo non si impediva niente a nessuno.
 *
 * Si impediva invece la cosa piu' normale del mondo — «qual era il codice? Rimandamelo» —
 * e si e' pagata cara: due amici hanno usato codici diversi credendoli lo stesso e sono
 * finiti in due leghe diverse, senza che niente nell'app potesse dirglielo. Il numero
 * dell'id della lega e' li' per lo stesso motivo: due leghe possono chiamarsi uguale, l'id
 * no.
 */
@Composable
fun ProfiloLegaScreen(state: AppState.Dentro) {
    val lega = state.lega.league
    val config = lega.config
    val clubs = state.lega.clubs

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(MFootSpacing.section),
    ) {
        Text(lega.name, style = MFootType.playerName, color = MFootColors.ink)
        Spacer(Modifier.height(4.dp))
        Text(
            statoLeggibile(lega.status),
            style = MFootType.chip,
            color = if (lega.status == "in_corso") MFootColors.elite else MFootColors.gamble,
        )
        Spacer(Modifier.height(3.dp))
        // L'id, non per pignoleria: due leghe possono avere lo stesso nome, e quando due
        // amici confrontano gli schermi e' l'unico numero che non mente.
        Text("lega #${lega.id}", style = MFootType.chip, color = MFootColors.ink3)

        Spacer(Modifier.height(MFootSpacing.section))

        Row(horizontalArrangement = Arrangement.spacedBy(MFootSpacing.related)) {
            Riquadro("Club", "${clubs.size}", MFootColors.ink, Modifier.weight(1f))
            Riquadro("Giocatori", "${state.lega.players.size}", MFootColors.ink, Modifier.weight(1f))
            Riquadro("Giornata", "${lega.currentMatchDay}", MFootColors.ink, Modifier.weight(1f))
        }

        Spacer(Modifier.height(MFootSpacing.section))
        Label("Le regole in breve")
        Spacer(Modifier.height(8.dp))

        StatRow("Budget iniziale", Money(config.economy.startingCredits).format())
        StatRow("Rosa minima", "${config.setup.minSquadSize}")
        StatRow("Rosa massima", "${config.setup.maxSquadSize}")
        StatRow("Gestiti dal computer", "${config.setup.aiClubs} su ${config.setup.totalClubs}")
        StatRow(
            "Divisioni",
            if (config.divisions.enabled) "${config.divisions.count}" else "girone unico",
        )
        StatRow("Stipendi", if (config.economy.wagesEnabled) "attivi" else "spenti")
        StatRow("Cartellini", if (config.rules.yellowCardsEnabled) "attivi" else "spenti")

        Spacer(Modifier.height(MFootSpacing.section))
        Notice(
            "Il codice per entrare, e l'elenco di tutte le leghe di cui fai parte, stanno " +
                "nel menu sotto «Le mie leghe». Se un amico non compare fra i " +
                "partecipanti, ha usato un codice diverso ed e' entrato altrove.",
            MFootColors.ink2,
        )

        if (lega.isAdmin) {
            Spacer(Modifier.height(MFootSpacing.related))
            Notice("Sei l'amministratore: le regole le cambi da Regolamento e opzioni.", MFootColors.elite)
        }

        Spacer(Modifier.height(30.dp))
    }
}

private fun statoLeggibile(stato: String): String = when (stato) {
    "setup" -> "In preparazione"
    "mercato" -> "Mercato aperto, campionato non ancora cominciato"
    "in_corso" -> "Campionato in corso"
    "conclusa" -> "Conclusa"
    else -> stato
}

/**
 * Chi c'e' nella lega.
 *
 * ## Perche' mostra anche chi non ha un club
 *
 * E' l'informazione piu' utile della schermata. Un amico che si e' iscritto col codice ma
 * non ha fondato la squadra e' invisibile ovunque altro — non compare fra le squadre, non
 * ha giocatori, non fa offerte — e intanto il suo posto in campionato resta vuoto. Qui si
 * vede, e si sa chi bisogna andare a chiamare.
 */
@Composable
fun PartecipantiScreen(desk: DeskState) {
    if (desk.errore != null) {
        Centro(desk.errore, MFootColors.gamble)
        return
    }
    if (desk.members == null) {
        Centro("Leggo i partecipanti…", MFootColors.ink3)
        return
    }

    val senzaClub = desk.members.count { !it.hasClub }

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 10.dp)) {
            Label(if (desk.members.size == 1) "1 iscritto" else "${desk.members.size} iscritti")
            if (senzaClub > 0) {
                Spacer(Modifier.height(10.dp))
                Notice(
                    if (senzaClub == 1) "Uno non ha ancora fondato la squadra."
                    else "$senzaClub non hanno ancora fondato la squadra.",
                    MFootColors.gamble,
                )
            }
        }

        desk.members.forEach { membro ->
            Row(
                Modifier.fillMaxWidth().padding(MFootSpacing.section, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val club = membro.club
                if (club != null) {
                    CrestBadge(club.crest, Modifier.size(38.dp), club.shortName)
                } else {
                    Box(
                        Modifier
                            .size(38.dp)
                            .background(MFootColors.core, MFootShapes.field)
                            .border(1.dp, MFootColors.lineStrong, MFootShapes.field),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("—", style = MFootType.value, color = MFootColors.ink3)
                    }
                }

                Spacer(Modifier.width(MFootSpacing.related))

                Column(Modifier.weight(1f)) {
                    Text(
                        membro.nickname,
                        style = MFootType.rowTitle,
                        color = MFootColors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(club?.name ?: "nessuna squadra")
                            if (membro.isAdmin) append(" · amministratore")
                        },
                        style = MFootType.chip,
                        color = if (club == null) MFootColors.gamble else MFootColors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (club != null) {
                    Text(
                        Money(club.available).formatShort(),
                        style = MFootType.value,
                        color = MFootColors.ink2,
                    )
                }
            }
            Hairline()
        }

        Spacer(Modifier.height(30.dp))
    }
}

/**
 * Cosa ha fatto il server, l'ultimo giro.
 *
 * ## Perche' serve davvero
 *
 * Il tick gira ogni cinque minuti su un computer che non e' il tuo, fa partire aste, gioca
 * partite, paga stipendi e fa crescere i giocatori. Quando qualcosa non torna — "perche' la
 * mia squadra non ha giocato?", "perche' ho meno soldi?" — senza questa schermata l'unica
 * risposta possibile e' un'alzata di spalle.
 *
 * Il caso piu' importante e' anche il piu' facile da confondere: **il tick non ha mai
 * girato**. E' diverso da "ha girato e non ha fatto niente", e la differenza e' fra un
 * problema del server e una lega che sta semplicemente aspettando.
 */
@Composable
fun RegistroScreen(desk: DeskState) {
    if (desk.errore != null) {
        Centro(desk.errore, MFootColors.gamble)
        return
    }

    val tick = desk.tick
    if (desk.tickLetto && tick == null) {
        // Nessuna riga visibile ha due cause, e dal telefono non si distinguono: o il tick
        // non ha mai girato, o le Row Level Security nascondono la riga. La seconda e'
        // successa davvero — `tick_state` non aveva nessuna regola di lettura — e il
        // messaggio di prima accusava il server di non partire mentre stava lavorando.
        // Meglio dire tutte e due le cose che sceglierne una a caso.
        Centro(
            "Nessun registro da mostrare.\n\nO il tick non ha ancora girato su questa lega — " +
                "se e' appena stata creata e' normale — oppure manca la migrazione " +
                "0007_tick_state_read.sql, che da' il permesso di leggerlo.",
            MFootColors.gamble,
        )
        return
    }
    if (tick == null) {
        Centro("Leggo il registro…", MFootColors.ink3)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(MFootSpacing.section),
    ) {
        Label("Ultimo giro")
        Spacer(Modifier.height(8.dp))
        StatRow("Quando", quandoLeggibile(tick.lastRunAt))
        StatRow("Elaborato fino a", quandoLeggibile(tick.lastProcessedAt))
        StatRow(
            "Giornate liquidate",
            if (tick.settledMatchDays.isEmpty()) "nessuna"
            else tick.settledMatchDays.sorted().joinToString(", "),
        )

        Spacer(Modifier.height(MFootSpacing.section))
        Label("Cosa ha fatto")
        Spacer(Modifier.height(8.dp))

        if (tick.righe.isEmpty()) {
            Text(
                "Niente da segnalare nell'ultimo giro.",
                style = MFootType.secondary,
                color = MFootColors.ink3,
            )
        } else {
            tick.righe.forEach { riga ->
                Text(
                    riga,
                    style = MFootType.secondary,
                    color = MFootColors.ink2,
                    modifier = Modifier.padding(vertical = 7.dp),
                )
                Hairline()
            }
        }

        Spacer(Modifier.height(MFootSpacing.section))
        Notice(
            "Il registro conserva solo l'ultimo giro: il server lo riscrive ogni volta. " +
                "Serve a capire cosa sta succedendo adesso, non a ricostruire la stagione.",
            MFootColors.ink2,
        )
        Spacer(Modifier.height(30.dp))
    }
}

/**
 * Lo stato del mercato: quale dei due e' in corso, e quanto e' lontana la normalita'.
 *
 * ## Perche' non e' una pagina di impostazioni
 *
 * Le manopole del mercato stanno nel Regolamento, dove stanno tutte le altre. Qui si
 * risponde a una domanda diversa e piu' urgente: **perche' il mercato si comporta cosi'**.
 * Durante l'allestimento le aste durano un quarto d'ora invece di un'ora e i club ne aprono
 * sei invece di tre, e senza spiegarlo sembra che le regole scritte nel regolamento siano
 * sbagliate.
 */
@Composable
fun MercatiScreen(state: AppState.Dentro) {
    val config = state.lega.league.config
    val minimo = config.setup.minSquadSize
    val incomplete = state.lega.clubs.count { state.lega.squadOf(it.id).size < minimo }
    val allestimento = incomplete > 0

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(MFootSpacing.section),
    ) {
        Text(
            if (allestimento) "Allestimento" else "Mercato a regime",
            style = MFootType.playerName,
            color = if (allestimento) MFootColors.gamble else MFootColors.elite,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (allestimento) {
                "$incomplete squadre su ${state.lega.clubs.size} non arrivano a $minimo " +
                    "giocatori. Finche' e' cosi' comprano in fretta, e le loro partite si " +
                    "rinviano."
            } else {
                "Tutte le squadre hanno la rosa completa. Il mercato e' quello di stagione: " +
                    "aste piu' lunghe, meno in parallelo."
            },
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        Spacer(Modifier.height(MFootSpacing.section))
        Label("Chi non arriva al minimo compra cosi'")
        Spacer(Modifier.height(8.dp))
        StatRow("Aste in parallelo", "${config.market.initialParallelAuctionsPerClub}")
        StatRow("Durata", "${config.market.initialAuctionDurationMinutes} minuti")

        Spacer(Modifier.height(MFootSpacing.section))
        Label("Chi ce la fa compra cosi'")
        Spacer(Modifier.height(8.dp))
        StatRow("Aste in parallelo", "${config.market.maxParallelAuctionsPerClub}")
        StatRow("Durata", "${config.market.auctionDurationMinutes} minuti")

        Spacer(Modifier.height(MFootSpacing.section))
        Label("Sempre")
        Spacer(Modifier.height(8.dp))
        StatRow("Rilancio minimo", Money(config.market.minimumRaise).format())
        StatRow(
            "Offerta massima automatica",
            if (config.market.proxyBiddingEnabled) "attiva" else "spenta",
        )
        StatRow(
            "Anti-snipe",
            if (config.market.antiSnipeEnabled) "${config.market.antiSnipeSeconds} secondi"
            else "spento",
        )

        Spacer(Modifier.height(MFootSpacing.section))
        Notice(
            "Il confine fra i due mercati e' la rosa, non una data: un club che non puo' " +
                "schierare una squadra legale compra in fretta, chiunque sia e in qualunque " +
                "mese della stagione.",
            MFootColors.ink2,
        )
        Spacer(Modifier.height(30.dp))
    }
}

// ------------------------------------------------------------------------------ comuni

@Composable
private fun Riquadro(
    label: String,
    valore: String,
    colore: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(MFootColors.core, MFootShapes.band)
            .border(1.dp, MFootColors.line, MFootShapes.band)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(valore, style = MFootType.price, color = colore)
        Spacer(Modifier.height(3.dp))
        Text(label, style = MFootType.label, color = MFootColors.ink3)
    }
}

@Composable
private fun Centro(testo: String, colore: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.fillMaxSize().background(MFootColors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            testo,
            style = MFootType.secondary,
            color = colore,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 34.dp),
        )
    }
}

/**
 * "3 minuti fa" invece dell'ora esatta.
 *
 * Guardando il registro la domanda e' "e' aggiornato?", e a quella un orario non risponde:
 * bisogna guardare l'orologio e fare la sottrazione. Oltre il giorno si passa alle ore
 * intere, perche' "1847 minuti fa" non lo legge nessuno.
 */
private fun quandoLeggibile(quando: Instant?): String {
    if (quando == null) return "mai"
    val passati = Duration.between(quando, Instant.now())
    return when {
        passati.isNegative -> "adesso"
        passati.toMinutes() < 1 -> "meno di un minuto fa"
        passati.toMinutes() < 60 -> "${passati.toMinutes()} minuti fa"
        passati.toHours() < 24 -> "${passati.toHours()} ore fa"
        else -> "${passati.toDays()} giorni fa"
    }
}
