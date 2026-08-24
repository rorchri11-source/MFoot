package dev.mfoot.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.MyLeaguesState
import dev.mfoot.android.data.LeagueCard
import dev.mfoot.android.ui.Cartellino
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.MFootField
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.PrimaryButton
import dev.mfoot.android.ui.Scheda
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType

/**
 * Le mie leghe.
 *
 * ## La domanda a cui risponde
 *
 * «Siamo nella stessa partita?»
 *
 * Sembra una domanda che non dovrebbe esistere, e infatti nasce da un difetto: il codice
 * d'accesso non era univoco, quindi due persone potevano digitare lo stesso identico
 * codice e finire in due leghe diverse. Da fuori l'effetto era incomprensibile — uno vedeva
 * la squadra dell'altro senza vederne mai le mosse — perche' nell'app non c'era **niente**
 * che dicesse in quale lega si stava.
 *
 * `0022` impedisce che due leghe condividano un codice. Questa schermata serve a chi ci e'
 * gia' dentro: elenca tutte le proprie leghe, dice quante persone ci sono in ognuna, e
 * marca quella aperta adesso. Se due amici la aprono e leggono lo stesso nome e lo stesso
 * numero di iscritti, sono nella stessa partita. Se no, si vede subito perche' no.
 */
@Composable
fun MieLegheScreen(
    stato: MyLeaguesState,
    isAdmin: Boolean,
    onCarica: () -> Unit,
    onApri: (Long) -> Unit,
    onCambiaCodice: (String) -> Unit,
) {
    LaunchedEffect(Unit) { onCarica() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(MFootSpacing.section),
    ) {
        stato.errore?.let { Notice(it, MFootColors.gamble); Spacer(Modifier.height(12.dp)) }
        stato.avviso?.let { Notice(it, MFootColors.elite); Spacer(Modifier.height(12.dp)) }

        if (!stato.letto) {
            Text(
                stato.busy ?: "Leggo le tue leghe…",
                style = MFootType.secondary,
                color = MFootColors.ink3,
            )
            return@Column
        }

        Label(if (stato.leghe.size == 1) "1 lega" else "${stato.leghe.size} leghe")
        Spacer(Modifier.height(4.dp))
        Text(
            if (stato.leghe.size <= 1) {
                "Sei in una lega sola: tutto quello che vedi succede qui."
            } else {
                "Sei in più di una lega. L'app ne apre una alla volta — quella marcata " +
                    "«aperta adesso» — e le altre continuano per conto loro."
            },
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        Spacer(Modifier.height(MFootSpacing.section))

        stato.leghe.forEach { lega ->
            Riga(lega, onApri)
            Spacer(Modifier.height(10.dp))
        }

        if (isAdmin) {
            Spacer(Modifier.height(MFootSpacing.section))
            CambiaCodice(stato, onCambiaCodice)
        }

        Spacer(Modifier.height(MFootSpacing.section))
        Text(
            "Se un amico non compare fra gli iscritti di questa lega, è entrato in " +
                "un'altra: il codice che ha usato non era questo. Mandagli il codice qui " +
                "sopra e fallo rientrare.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Riga(lega: LeagueCard, onApri: (Long) -> Unit) {
    Scheda(evidenziata = lega.current) {
    Column(Modifier.padding(start = if (lega.current) 10.dp else 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                lega.name,
                style = MFootType.rowTitle,
                color = if (lega.current) MFootColors.elite else MFootColors.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (lega.current) {
                // Lavanda pieno, non lavanda trasparente: al 18% su una scheda scura
                // veniva fuori un grigio, e il cartellino che dovrebbe dire «sei qui»
                // sembrava spento come tutto il resto.
                Cartellino(
                    "aperta adesso",
                    fondo = MFootColors.elite,
                    inchiostro = MFootColors.onAccent,
                )
            }
        }

        Spacer(Modifier.height(5.dp))
        Text(
            buildString {
                append(lega.members).append(if (lega.members == 1) " iscritto" else " iscritti")
                append(" · ").append(lega.statoLeggibile)
                if (lega.currentMatchDay > 0) append(" · giornata ").append(lega.currentMatchDay)
            },
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        Spacer(Modifier.height(3.dp))
        Text(
            buildString {
                append("tu: ").append(lega.nickname.ifBlank { "senza nome" })
                if (lega.isAdmin) append(" (amministratore)")
                append(" · ").append(lega.myClubName ?: "nessun club fondato")
            },
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        lega.accessCode?.let { codice ->
            Spacer(Modifier.height(3.dp))
            Text("codice d'ingresso: $codice", style = MFootType.chip, color = MFootColors.ink2)
        }

        if (!lega.current) {
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Apri questa lega", onClick = { onApri(lega.id) })
        }
    }
    }
}

/**
 * Cambiare il codice d'accesso.
 *
 * Serve a due cose, e la seconda e' quella che conta: dare un codice nuovo a chi non si
 * ricorda il vecchio, e **districare** le leghe di chi ne ha gia' due con lo stesso codice
 * da prima della correzione. Finche' restano identiche, chi entra continua a finire in una
 * a caso.
 */
@Composable
private fun CambiaCodice(stato: MyLeaguesState, onCambia: (String) -> Unit) {
    var codice by remember { mutableStateOf("") }

    Label("Il codice di questa lega")
    Spacer(Modifier.height(6.dp))
    Text(
        "Cambiarlo non butta fuori nessuno: chi è già dentro resta. Vale solo per chi " +
            "entra da adesso in poi.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
    Spacer(Modifier.height(10.dp))

    MFootField(
        value = codice,
        onValueChange = { codice = it.take(24) },
        placeholder = "nuovo codice",
        label = "Nuovo codice",
        imeAction = ImeAction.Done,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton(
            text = if (stato.busy != null) "…" else "Cambia il codice",
            onClick = { if (stato.busy == null) onCambia(codice) },
        )
    }
}
