package dev.mfoot.android.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import dev.mfoot.android.ui.theme.MFootMotion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.MatchState
import dev.mfoot.android.data.MatchMoment
import dev.mfoot.android.data.MatchRating
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType

/**
 * La partita, minuto per minuto.
 *
 * ## Perche' questa schermata cambia il gioco
 *
 * Perche' senza, tutto quello che ci sta intorno non ha conseguenze visibili. Componi
 * l'undici, scegli il modulo, imposti gli ordini condizionali, e poi leggi **2-1**: il
 * lavoro non si vede da nessuna parte, e schierare la formazione diventa compilare un
 * modulo e sperare.
 *
 * La timeline si salvava intera nel database da mesi e nessuno la leggeva.
 *
 * ## Perche' si riproduce con l'orologio del telefono
 *
 * La partita si e' gia' giocata mentre il telefono era spento. Il server ha salvato tutti
 * i novanta minuti in una volta sola: qui si scaricano una volta e si scorrono in locale.
 * Nessuna richiesta durante la riproduzione, costo zero, e chi ha fretta salta alla fine.
 *
 * ## Perche' solo i momenti che contano
 *
 * Una partita sono circa centoventi azioni, e cento sono avanzamenti e palle perse. Farle
 * scorrere tutte vorrebbe dire un elenco in cui il gol si perde in mezzo al rumore: qui
 * passa solo cio' che ha una pericolosita' vera, che e' lo stesso criterio con cui un
 * telecronista decide di alzare la voce.
 */
@Composable
fun PartitaScreen(
    state: MatchState,
    onPausa: () -> Unit,
    onFine: () -> Unit,
    onChiudi: () -> Unit,
    nomeGiocatore: (Long) -> String,
) {
    Column(Modifier.fillMaxSize().background(MFootColors.bg)) {
        Tabellone(state)

        if (state.errore != null) {
            Box(Modifier.padding(MFootSpacing.section)) {
                Notice(state.errore, MFootColors.gamble)
            }
            Box(Modifier.padding(horizontal = MFootSpacing.section)) {
                GhostButton("Chiudi", onChiudi)
            }
            return
        }

        if (state.caricamento) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Carico la partita…", style = MFootType.secondary, color = MFootColors.ink3)
            }
            return
        }

        Comandi(state, onPausa, onFine, onChiudi)
        Hairline()

        val accaduto = state.accaduto
        val pagelle = state.partita?.ratings.orEmpty()

        LazyColumn(Modifier.weight(1f)) {
            if (state.finita && pagelle.isNotEmpty()) {
                item {
                    Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 8.dp)) {
                        Label("Pagelle")
                    }
                }
                items(pagelle, key = { it.playerId }) { voto ->
                    Pagella(voto, nomeGiocatore(voto.playerId))
                }
                item { Spacer(Modifier.height(MFootSpacing.section)) }
            }

            if (accaduto.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Si comincia.",
                            style = MFootType.secondary,
                            color = MFootColors.ink3,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            items(accaduto, key = { "${it.minute}-${it.text.hashCode()}" }) { momento ->
                Momento(momento, state)
            }

            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun Tabellone(state: MatchState) {
    // Il minuto scorre invece di saltare: sei minuti in un colpo si leggono come un
    // guasto, sei minuti che salgono si leggono come una partita.
    val minuto by animateFloatAsState(state.minuto.toFloat(), label = "minuto")

    Column(
        Modifier
            .fillMaxWidth()
            .background(MFootColors.core)
            .padding(MFootSpacing.section),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.homeName,
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )

            // Il risultato sbatte in scala quando cambia.
            //
            // E' il momento per cui si sta guardando la partita, e finora il numero
            // cambiava e basta: se stavi leggendo l'elenco degli eventi, il gol te lo
            // perdevi e lo scoprivi dal tabellino.
            var precedente by remember { mutableStateOf(state.golCasa + state.golFuori) }
            val segnato = remember { Animatable(1f) }
            LaunchedEffect(state.golCasa, state.golFuori) {
                val adesso = state.golCasa + state.golFuori
                if (adesso > precedente) {
                    segnato.snapTo(1.9f)
                    segnato.animateTo(1f, tween(700, easing = MFootMotion.easing))
                }
                precedente = adesso
            }

            Text(
                "  ${state.golCasa} - ${state.golFuori}  ",
                style = MFootType.overallLarge,
                color = MFootColors.ink,
                modifier = Modifier.graphicsLayer {
                    scaleX = segnato.value
                    scaleY = segnato.value
                },
            )

            Text(
                state.awayName,
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(10.dp))

        Box(Modifier.fillMaxWidth().height(3.dp).background(MFootColors.line, MFootShapes.pill)) {
            Box(
                Modifier
                    .fillMaxWidth((minuto / 90f).coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(MFootColors.elite, MFootShapes.pill),
            )
        }

        Spacer(Modifier.height(7.dp))
        Text(
            when {
                state.finita -> "Finita"
                state.minuto == 0 -> "Fischio d'inizio"
                else -> "${state.minuto}'"
            },
            style = MFootType.label,
            color = MFootColors.ink3,
        )
    }
}

@Composable
private fun Comandi(
    state: MatchState,
    onPausa: () -> Unit,
    onFine: () -> Unit,
    onChiudi: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(MFootSpacing.section, 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.finita) {
            Bottone(if (state.inCorso) "Pausa" else "Riprendi", onPausa)
            Bottone("Salta alla fine", onFine)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Chiudi",
            style = MFootType.chip,
            color = MFootColors.ink3,
            modifier = Modifier.clickable(onClick = onChiudi).padding(8.dp),
        )
    }
}

@Composable
private fun Bottone(testo: String, onClick: () -> Unit) {
    Text(
        testo,
        style = MFootType.chip,
        color = MFootColors.ink2,
        modifier = Modifier
            .background(MFootColors.core, MFootShapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Momento(momento: MatchMoment, state: MatchState) {
    val colore = when {
        momento.isGoal -> MFootColors.elite
        momento.type == "ESPULSIONE" -> MFootColors.low
        momento.type == "AMMONIZIONE" -> MFootColors.gamble
        momento.type == "INFORTUNIO" -> MFootColors.low
        else -> MFootColors.ink3
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(
                // I gol hanno un fondo loro: in un elenco che scorre, il momento per cui
                // si sta guardando non puo' avere lo stesso peso di un angolo.
                if (momento.isGoal) MFootColors.elite.copy(alpha = 0.07f) else Color.Transparent,
            )
            .padding(MFootSpacing.section, 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${momento.minute}'",
            style = MFootType.label,
            color = MFootColors.ink3,
            modifier = Modifier.width(34.dp),
        )

        Box(Modifier.size(6.dp).background(colore, MFootShapes.pill))
        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f)) {
            Text(
                momento.text,
                style = if (momento.isGoal) MFootType.rowTitle else MFootType.secondary,
                color = if (momento.isGoal) MFootColors.ink else MFootColors.ink2,
            )
            if (momento.isGoal) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "${state.homeName} ${momento.homeGoals} - ${momento.awayGoals} ${state.awayName}",
                    style = MFootType.chip,
                    color = MFootColors.elite,
                )
            }
        }
    }
    Hairline()
}

@Composable
private fun Pagella(voto: MatchRating, nome: String) {
    // Chi non e' sceso in campo non ha un voto: uno zero si distingue da un sei, e un sei
    // di comodo a chi e' rimasto in panchina falserebbe ogni media.
    if (voto.minutes == 0) return

    Row(
        Modifier.fillMaxWidth().padding(MFootSpacing.section, 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(nome, style = MFootType.rowTitle, color = MFootColors.ink, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(if (voto.started) "titolare" else "subentrato")
                    append(" · ${voto.minutes}'")
                    if (voto.goals > 0) append(" · ${voto.goals} gol")
                    if (voto.assists > 0) append(" · ${voto.assists} assist")
                    if (voto.yellow > 0) append(" · giallo")
                    if (voto.red > 0) append(" · rosso")
                },
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
        }

        Text(
            String.format("%.1f", voto.rating).replace('.', ','),
            style = MFootType.overallRow,
            color = when {
                voto.rating >= 7.5 -> MFootColors.elite
                voto.rating >= 6.0 -> MFootColors.good
                voto.rating >= 5.0 -> MFootColors.gamble
                else -> MFootColors.low
            },
        )
    }
    Hairline()
}
