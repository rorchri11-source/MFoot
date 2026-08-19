package dev.mfoot.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.ObiettiviState
import dev.mfoot.android.data.ObjectiveRow
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.model.Money
import dev.mfoot.core.objectives.ObjectiveStatus

/**
 * Gli obiettivi di stagione: i propri in cima, poi quelli di tutti gli altri.
 *
 * ## Perche' si vedono anche quelli degli altri
 *
 * Perche' spiegano il mercato. Un avversario che a marzo compra un difensore invece di
 * vendere sembra fare una mossa senza senso, finche' non si sa che ha in ballo un premio
 * grosso se non retrocede. Tenuti nascosti, gli obiettivi muoverebbero le decisioni di
 * tutti senza che nessuno capisca perche', e una lega in cui le mosse degli altri sembrano
 * casuali e' una lega in cui non si puo' giocare di anticipo.
 *
 * ## Perche' il premio si paga solo per intero
 *
 * Un premio che paga meta' se arrivi vicino non cambia nessuna decisione: si fa la stagione
 * che si sarebbe fatta comunque e si incassa quello che capita. Tutto o niente rende
 * costoso il rischio — vendere il centravanti a gennaio, mandare in campo il diciottenne —
 * che e' esattamente cio' che un obiettivo deve far pesare.
 */
@Composable
fun ObiettiviScreen(
    state: AppState.Dentro,
    obiettivi: ObiettiviState,
    onCarica: () -> Unit,
    onAssegna: () -> Unit,
) {
    LaunchedEffect(state.lega.league.id) { onCarica() }

    val mio = state.lega.myClub

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(MFootSpacing.section),
    ) {
        obiettivi.errore?.let { Notice(it, MFootColors.gamble); Spacer(Modifier.height(12.dp)) }
        obiettivi.avviso?.let { Notice(it, MFootColors.elite); Spacer(Modifier.height(12.dp)) }

        if (!obiettivi.letto) {
            Text("Leggo gli obiettivi…", style = MFootType.secondary, color = MFootColors.ink3)
            return@Column
        }

        if (obiettivi.righe.isEmpty()) {
            Vuoti(state, obiettivi, onAssegna)
            return@Column
        }

        Label("Stagione ${obiettivi.stagione}")
        Spacer(Modifier.height(4.dp))
        Text(
            "Il premio si prende solo raggiungendoli. Se non li raggiungi, niente.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        mio?.let { club ->
            val miei = obiettivi.diClub(club.id)
            if (miei.isNotEmpty()) {
                Spacer(Modifier.height(MFootSpacing.section))
                Label("I tuoi")
                Spacer(Modifier.height(8.dp))
                miei.forEach { Riga(it, evidenza = true) }

                val incassabili = miei
                    .filter { it.status == ObjectiveStatus.IN_CORSO }
                    .sumOf { it.premio }
                if (incassabili > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "In ballo: ${Money(incassabili).format()}.",
                        style = MFootType.chip,
                        color = MFootColors.elite,
                    )
                }
            }
        }

        val altri = obiettivi.righe
            .filter { it.season == obiettivi.stagione && it.clubId != mio?.id }
            .groupBy { it.clubId }

        if (altri.isNotEmpty()) {
            Spacer(Modifier.height(MFootSpacing.section))
            Label("Le altre squadre")
            Spacer(Modifier.height(8.dp))

            altri.forEach { (clubId, righe) ->
                val club = state.lega.clubs.firstOrNull { it.id == clubId }
                Text(
                    club?.name ?: "Club #$clubId",
                    style = MFootType.rowTitle,
                    color = MFootColors.ink2,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                righe.forEach { Riga(it, evidenza = false) }
            }
        }

        if (state.lega.league.isAdmin) {
            Spacer(Modifier.height(MFootSpacing.section))
            Assegnazione(obiettivi, onAssegna)
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Vuoti(state: AppState.Dentro, obiettivi: ObiettiviState, onAssegna: () -> Unit) {
    Label("Nessun obiettivo assegnato")
    Spacer(Modifier.height(6.dp))
    Text(
        "Senza obiettivi la lega ha una domanda sola per tutti — chi arriva primo — e per " +
            "le altre squadre quella domanda smette di contare a meta' stagione. Con gli " +
            "obiettivi ognuno ha una stagione sua: chi punta al titolo, chi a non " +
            "retrocedere, chi a far crescere il proprio giocatore.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )

    if (state.lega.league.isAdmin) {
        Spacer(Modifier.height(MFootSpacing.section))
        Assegnazione(obiettivi, onAssegna)
    } else {
        Spacer(Modifier.height(MFootSpacing.section))
        Text(
            "Li assegna l'amministratore, tutti insieme a inizio stagione.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
    }
}

/**
 * Il pulsante dell'admin.
 *
 * Non sceglie **cosa** chiedere a chi: quello lo decide una regola scritta in `core`,
 * uguale per tutti e leggibile da tutti. Obiettivi scelti a mano da uno dei concorrenti
 * sarebbero crediti assegnati da un avversario, e non ci sarebbe modo di renderli credibili
 * nemmeno quando sono onesti — che e' il caso quasi sempre.
 */
@Composable
private fun Assegnazione(obiettivi: ObiettiviState, onAssegna: () -> Unit) {
    val prossima = obiettivi.stagione + 1

    Text(
        "Assegnare gli obiettivi della stagione $prossima li da' a tutte le squadre " +
            "insieme, secondo quanto vale ciascuna nella sua divisione. Quelli gia' " +
            "assegnati non si toccano: un obiettivo in corso non deve poter cambiare a " +
            "stagione iniziata.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
    Spacer(Modifier.height(12.dp))
    GhostButton(
        text = obiettivi.busy ?: "Assegna gli obiettivi della stagione $prossima",
        onClick = { if (obiettivi.busy == null) onAssegna() },
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Si chiudono da soli quando chiudi la stagione, dalle Divisioni: i premi si pagano li'.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
}

@Composable
private fun Riga(riga: ObjectiveRow, evidenza: Boolean) {
    val colore = when (riga.status) {
        ObjectiveStatus.RAGGIUNTO -> MFootColors.elite
        ObjectiveStatus.FALLITO -> MFootColors.gamble
        ObjectiveStatus.IN_CORSO -> MFootColors.ink2
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (evidenza) MFootColors.core else MFootColors.bg,
                MFootShapes.field,
            )
            .border(1.dp, if (evidenza) MFootColors.line else MFootColors.line, MFootShapes.field)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(riga.descrizione, style = MFootType.rowTitle, color = MFootColors.ink)
            Spacer(Modifier.height(2.dp))
            Text(
                when (riga.status) {
                    ObjectiveStatus.IN_CORSO -> "in corso · premio ${Money(riga.premio).formatShort()}"
                    ObjectiveStatus.RAGGIUNTO -> "raggiunto · incassati ${Money(riga.paid).formatShort()}"
                    ObjectiveStatus.FALLITO ->
                        "fallito · ${Money(riga.premio).formatShort()} non pagati"
                },
                style = MFootType.chip,
                color = colore,
            )
        }
        Text(
            when (riga.status) {
                ObjectiveStatus.IN_CORSO -> "…"
                ObjectiveStatus.RAGGIUNTO -> "✓"
                ObjectiveStatus.FALLITO -> "✕"
            },
            style = MFootType.value,
            color = colore,
        )
    }
    Spacer(Modifier.height(8.dp))
}
