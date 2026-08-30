package dev.mfoot.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.PrePartitaState
import dev.mfoot.android.app.SchieramentoDiUnClub
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.pitch.Pitch
import dev.mfoot.android.ui.pitch.pitchSlots
import dev.mfoot.android.ui.pitch.PITCH_ASPECT
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType

/**
 * La partita prima che si giochi.
 *
 * ## Perché esiste
 *
 * Perché preparare una partita era compilare un modulo e sperare. La formazione
 * dell'avversario si poteva già guardare — una squadra alla volta, da un'altra schermata —
 * ma non c'era nessun posto in cui **le due stessero insieme**, che è l'unico modo in cui
 * un confronto si guarda.
 *
 * ## Perché due campi in fila e non affiancati
 *
 * Perché su un telefono da 380 punti due campi affiancati diventano due francobolli: i
 * nomi non ci stanno e il modulo non si distingue. In fila si scorre, e ogni campo resta
 * grande quanto quello che si guarda quando si schiera.
 *
 * ## Perché la probabilità arriva dopo
 *
 * Perché è il motore vero, fatto girare trecento volte sui due undici — non una formula a
 * parte, che sarebbe un secondo motore destinato a divergere. Costa qualche secondo, e i
 * campi non devono aspettarlo.
 */
@Composable
fun PrePartitaScreen(
    pre: PrePartitaState,
    onChiudi: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MFootColors.core)
                .padding(MFootSpacing.section, 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pre.nomeCasa,
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    pre.quando,
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                Text(
                    pre.nomeOspite,
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Hairline()

        pre.problema?.let {
            Spacer(Modifier.height(MFootSpacing.section))
            Box(Modifier.padding(horizontal = MFootSpacing.section)) {
                Notice(it, MFootColors.gamble)
            }
        }
        pre.errore?.let {
            Spacer(Modifier.height(MFootSpacing.section))
            Box(Modifier.padding(horizontal = MFootSpacing.section)) {
                Notice(it, MFootColors.gamble)
            }
        }

        Spacer(Modifier.height(MFootSpacing.section))
        Pronostico(pre)

        pre.casa?.let {
            Spacer(Modifier.height(MFootSpacing.section))
            Schieramento(pre.nomeCasa, it)
        }
        pre.ospite?.let {
            Spacer(Modifier.height(MFootSpacing.section))
            Schieramento(pre.nomeOspite, it)
        }

        if (pre.caricamento) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text("Leggo le formazioni…", style = MFootType.secondary, color = MFootColors.ink3)
            }
        }

        Spacer(Modifier.height(MFootSpacing.section))
        Box(Modifier.padding(horizontal = MFootSpacing.section)) {
            GhostButton("Chiudi", onChiudi)
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * Le tre probabilità, come nelle partite vere.
 *
 * Una barra sola divisa in tre, non tre numeri in fila: la larghezza si legge prima della
 * cifra, ed è quello che serve per capire in un istante se la partita è aperta o segnata.
 */
@Composable
private fun Pronostico(pre: PrePartitaState) {
    Column(Modifier.padding(horizontal = MFootSpacing.section)) {
        Label("Come potrebbe finire")
        Spacer(Modifier.height(8.dp))

        val esito = pre.pronostico
        if (esito == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(MFootShapes.pill)
                    .background(MFootColors.core),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (pre.caricamento) "…" else "Simulo la partita…",
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                )
            }
            return@Column
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(MFootShapes.pill),
        ) {
            // I pesi non possono essere zero: una fascia da 0% sparirebbe e la barra
            // sembrerebbe divisa in due invece che in tre.
            Fascia(esito.casa, MFootColors.elite, Modifier.weight(esito.casa.coerceAtLeast(1).toFloat()))
            Fascia(esito.pari, MFootColors.ink3, Modifier.weight(esito.pari.coerceAtLeast(1).toFloat()))
            Fascia(esito.ospite, MFootColors.gamble, Modifier.weight(esito.ospite.coerceAtLeast(1).toFloat()))
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Legenda(pre.nomeCasa, MFootColors.elite, Modifier.weight(1f), TextAlign.Start)
            Legenda("pareggio", MFootColors.ink3, Modifier.weight(1f), TextAlign.Center)
            Legenda(pre.nomeOspite, MFootColors.gamble, Modifier.weight(1f), TextAlign.End)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            // Detta con parole, perché tre percentuali sono un dato e questa è una lettura.
            racconto(esito, pre.nomeCasa, pre.nomeOspite),
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
    }
}

@Composable
private fun Fascia(percento: Int, colore: Color, modifier: Modifier) {
    Box(modifier.fillMaxSize().background(colore), contentAlignment = Alignment.Center) {
        // Sotto una certa larghezza il numero non ci sta e si taglia a metà: meglio niente.
        if (percento >= 12) {
            Text("$percento%", style = MFootType.chip, color = MFootColors.bg, maxLines = 1)
        }
    }
}

@Composable
private fun Legenda(testo: String, colore: Color, modifier: Modifier, allineamento: TextAlign) {
    Text(
        testo,
        style = MFootType.chip,
        color = colore,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = allineamento,
        modifier = modifier,
    )
}

private fun racconto(
    esito: dev.mfoot.core.match.Pronostico.Esito,
    casa: String,
    ospite: String,
): String {
    val scarto = esito.casa - esito.ospite
    return when {
        scarto > 25 -> "$casa parte nettamente avanti."
        scarto > 10 -> "$casa parte avanti, ma non è chiusa."
        scarto < -25 -> "$ospite parte nettamente avanti, e gioca fuori casa."
        scarto < -10 -> "$ospite parte avanti pur giocando fuori."
        else -> "Partita aperta: il campo pesa più della differenza fra le due rose."
    }
}

/**
 * Un campo, con la sua panchina.
 *
 * Grande quanto quello su cui si schiera: un campo piccolo si guarda e non si legge, e
 * questa schermata serve proprio a leggere chi c'è dove.
 */
@Composable
private fun Schieramento(nome: String, s: SchieramentoDiUnClub) {
    Column(Modifier.fillMaxWidth().padding(horizontal = MFootSpacing.section)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(nome, Modifier.weight(1f))
            Text(
                s.formation.label,
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
        }
        Spacer(Modifier.height(6.dp))

        // Una previsione e una scelta si leggono identiche, e preparare la partita contro
        // un modulo che l'avversario non ha mai scelto è peggio che non guardarlo affatto.
        Text(
            if (s.suPrevisione) {
                "Non ha ancora schierato: questo è l'undici che scenderebbe in campo da solo."
            } else {
                "Formazione scelta."
            },
            style = MFootType.chip,
            color = if (s.suPrevisione) MFootColors.ink3 else MFootColors.elite,
        )

        Spacer(Modifier.height(10.dp))
        Pitch(
            slots = pitchSlots(s.formation, s.eleven),
            modifier = Modifier.fillMaxWidth().aspectRatio(PITCH_ASPECT),
            showEmptyLabels = false,
        )

        Spacer(Modifier.height(12.dp))
        Label("In panchina · ${s.bench.size}", color = MFootColors.ink3)
        Spacer(Modifier.height(6.dp))
        if (s.bench.isEmpty()) {
            Text("Nessuno.", style = MFootType.chip, color = MFootColors.ink3)
        } else {
            s.bench.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(34.dp, 21.dp).background(MFootColors.core, MFootShapes.field),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            p.primaryPosition.short,
                            style = MFootType.label,
                            color = MFootColors.ink3,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        p.shortName,
                        style = MFootType.chip,
                        color = MFootColors.ink2,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${p.overall}",
                        style = MFootType.value,
                        color = MFootColors.rating(p.overall),
                    )
                }
            }
        }

        s.tactics?.let { t ->
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    t.stance.name.lowercase(),
                    t.tempo.name.lowercase(),
                    t.width.name.lowercase(),
                    t.pressing.name.lowercase(),
                ).forEach {
                    Text(
                        it,
                        style = MFootType.chip,
                        color = MFootColors.ink3,
                        modifier = Modifier
                            .background(MFootColors.core, MFootShapes.pill)
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}
