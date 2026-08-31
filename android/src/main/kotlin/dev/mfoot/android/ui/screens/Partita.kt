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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
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
import dev.mfoot.android.app.MatchTab
import dev.mfoot.android.data.MatchMoment
import dev.mfoot.android.data.DuelliPartita
import dev.mfoot.android.data.MatchRating
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.pitch.CampoFormazione
import dev.mfoot.android.ui.pitch.CampoLive
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
    onScheda: (MatchTab) -> Unit,
    onRivedi: () -> Unit,
    onPausa: () -> Unit,
    onFine: () -> Unit,
    onVelocita: (Int) -> Unit,
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

        Comandi(state, onRivedi, onPausa, onFine, onVelocita, onChiudi)
        Schede(state, onScheda)
        Hairline()

        when (state.scheda) {
            MatchTab.CAMPO -> SchedaCampo(state)
            MatchTab.RIASSUNTO -> SchedaRiassunto(state, nomeGiocatore)
            MatchTab.NUMERI -> SchedaNumeri(state)
            MatchTab.FORMAZIONI -> SchedaFormazioni(state, nomeGiocatore)
        }
    }
}

/**
 * Le schede.
 *
 * ## Perche' sono arrivate
 *
 * Prima era una pagina sola: campo, sotto la telecronaca, e le pagelle in fondo a tutto.
 * La risposta a «chi ha giocato bene» stava a due schermate di distanza da quella a «com'e'
 * finita», e le statistiche non c'erano proprio — malgrado il motore le calcoli tutte.
 *
 * L'ordine e' quello in cui una persona fa le domande: cosa sta succedendo, cosa e'
 * successo di importante, chi ha dominato, chi ha giocato.
 */
@Composable
private fun Schede(state: MatchState, onScheda: (MatchTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(MFootSpacing.section, 4.dp, MFootSpacing.section, 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MatchTab.entries.forEach { scheda ->
            val attiva = scheda == state.scheda
            Text(
                scheda.label,
                style = MFootType.chip,
                color = if (attiva) MFootColors.bg else MFootColors.ink2,
                modifier = Modifier
                    .background(
                        if (attiva) MFootColors.ink else MFootColors.core,
                        MFootShapes.pill,
                    )
                    .clickable { onScheda(scheda) }
                    .padding(horizontal = 15.dp, vertical = 8.dp),
            )
        }
    }
}

/** Il campo, l'inerzia e la telecronaca: quello che si guarda mentre si gioca. */
@Composable
private fun ColumnScope.SchedaCampo(state: MatchState) {
    // Il campo lo si guarda anche quando non succede niente — che nel calcio vero e' la
    // maggior parte del tempo — mentre la telecronaca si legge solo quando succede.
    val azione = state.azione
    Box(Modifier.padding(MFootSpacing.section, 0.dp, MFootSpacing.section, 0.dp)) {
        CampoLive(
            zona = azione?.zone,
            casa = azione?.homeSide ?: true,
            pericolo = azione?.danger ?: 0,
            golTotali = state.golCasa + state.golFuori,
        )
        if (state.inIntervallo) Sipario(state, Modifier.matchParentSize())
    }

    Inerzia(state)
    Hairline()

    val accaduto = state.accaduto
    LazyColumn(Modifier.weight(1f)) {
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
        itemsIndexed(accaduto, key = { index, it -> "$index-${it.minute}-${it.type}" }) { _, momento ->
            Momento(momento, state)
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

/**
 * La partita in dieci righe: gol, cambi, cartellini pesanti.
 *
 * ## Perche' non e' la telecronaca filtrata
 *
 * Perche' risponde a un'altra domanda. La telecronaca racconta *com'e' andata* e si legge
 * dall'alto; questa risponde a *cos'e' successo* e si guarda in un colpo — chi ha segnato e
 * al minuto, chi e' entrato per chi. E' la scheda che si apre a partita finita per capire
 * la gara in cinque secondi, senza scorrere trenta righe di occasioni sbagliate.
 */
@Composable
private fun ColumnScope.SchedaRiassunto(state: MatchState, nomeGiocatore: (Long) -> String) {
    val pesanti = state.partita?.moments.orEmpty()
        .filter { it.minute <= state.minuto }
        .filter { it.isGoal || it.type in TIPI_RIASSUNTO }

    if (pesanti.isEmpty()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Ancora niente da raccontare.",
                style = MFootType.secondary,
                color = MFootColors.ink3,
            )
        }
        return
    }

    LazyColumn(Modifier.weight(1f)) {
        itemsIndexed(pesanti, key = { index, m -> "$index-${m.minute}-${m.type}" }) { _, m ->
            RigaRiassunto(m, nomeGiocatore)
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

private val TIPI_RIASSUNTO = setOf(
    "SOSTITUZIONE", "ESPULSIONE", "AMMONIZIONE", "RIGORE_SBAGLIATO", "INFORTUNIO",
)

/**
 * Una riga del riassunto: minuto a sinistra o a destra secondo la squadra.
 *
 * Il lato dice **di chi e'** senza doverlo scrivere: e' il modo in cui un tabellino si
 * legge da sempre, e con due nomi di club lunghi e' anche l'unico che ci sta in larghezza.
 */
@Composable
private fun RigaRiassunto(m: MatchMoment, nomeGiocatore: (Long) -> String) {
    val icona = when {
        m.isGoal -> "⚽"
        m.type == "SOSTITUZIONE" -> "⇄"
        m.type == "ESPULSIONE" -> "▮"
        m.type == "AMMONIZIONE" -> "▯"
        m.type == "INFORTUNIO" -> "✚"
        else -> "•"
    }
    val colore = when {
        m.isGoal -> MFootColors.elite
        m.type == "ESPULSIONE" -> MFootColors.low
        m.type == "AMMONIZIONE" -> MFootColors.gamble
        else -> MFootColors.ink2
    }
    val chi = m.playerId?.let(nomeGiocatore).orEmpty()

    Row(
        Modifier.fillMaxWidth().padding(MFootSpacing.section, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.homeSide) {
            Text("${m.minute}'", style = MFootType.label, color = MFootColors.ink3, modifier = Modifier.width(38.dp))
            Text(icona, style = MFootType.chip, color = colore)
            Spacer(Modifier.width(8.dp))
            Text(
                chi.ifBlank { m.text },
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                chi.ifBlank { m.text },
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(icona, style = MFootType.chip, color = colore)
            Text(
                "${m.minute}'",
                style = MFootType.label,
                color = MFootColors.ink3,
                textAlign = TextAlign.End,
                modifier = Modifier.width(38.dp),
            )
        }
    }
    Hairline()
}

/**
 * I numeri, a confronto.
 *
 * ## Perche' si contano dagli eventi
 *
 * Perche' la timeline **e'** la partita. Salvare anche dei totali vorrebbe dire un secondo
 * posto in cui la stessa verita' puo' sbagliarsi, e non sapere a quale dei due credere. I
 * tiri fanno eccezione e arrivano dal motore: comprendono conclusioni che non producono un
 * evento a se'.
 */
@Composable
private fun ColumnScope.SchedaNumeri(state: MatchState) {
    val p = state.partita ?: return

    val angoli = p.conta("ANGOLO")
    val falli = p.conta("FALLO")
    val parate = p.conta("PARATA")
    val legni = p.conta("PALO")
    val gialli = p.conta("AMMONIZIONE")
    val rossi = p.conta("ESPULSIONE")
    val possessoCasa = StrictMath.round(p.homePossession * 100).toInt()

    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(MFootSpacing.section),
    ) {
        Confronto("Gol", state.golCasa, state.golFuori)
        Confronto("Tiri", p.homeShots, p.awayShots)
        // Le parate dell'una sono i tiri nello specchio dell'altra: si incrociano.
        Confronto("In porta", parate.second + state.golCasa, parate.first + state.golFuori)
        Confronto("Legni", legni.first, legni.second)
        Confronto("Angoli", angoli.first, angoli.second)
        Confronto("Possesso", possessoCasa, 100 - possessoCasa, suffisso = "%")
        Confronto("Falli", falli.first, falli.second)
        Confronto("Ammonizioni", gialli.first, gialli.second)
        if (rossi.first + rossi.second > 0) Confronto("Espulsioni", rossi.first, rossi.second)

        if (!p.completa) {
            Spacer(Modifier.height(MFootSpacing.section))
            Text(
                "Sono i numeri del primo tempo: il resto arriva quando la partita finisce.",
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

/** Una riga di confronto: due numeri e una barra che li pesa. */
@Composable
private fun Confronto(titolo: String, casa: Int, fuori: Int, suffisso: String = "") {
    val totale = (casa + fuori).coerceAtLeast(1)
    val quota by animateFloatAsState(casa.toFloat() / totale, tween(600), label = titolo)

    Column(Modifier.padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$casa$suffisso",
                style = MFootType.value,
                color = if (casa >= fuori) MFootColors.ink else MFootColors.ink3,
                modifier = Modifier.width(52.dp),
            )
            Text(
                titolo,
                style = MFootType.chip,
                color = MFootColors.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$fuori$suffisso",
                style = MFootType.value,
                color = if (fuori >= casa) MFootColors.ink else MFootColors.ink3,
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(MFootShapes.pill)
                .background(MFootColors.core),
        ) {
            Box(Modifier.fillMaxWidth(quota).fillMaxHeight().background(MFootColors.elite))
            Box(Modifier.weight(1f).fillMaxHeight().background(MFootColors.gamble))
        }
    }
}

/**
 * Le pagelle, divise per squadra.
 *
 * ## Perche' divise, e non in un elenco solo ordinato per voto
 *
 * Perche' l'elenco unico rispondeva a «chi ha giocato meglio in campo», che non e' la
 * domanda: quella e' **«come ha giocato la mia squadra»**. Con ventidue nomi mescolati per
 * voto bisognava leggerli tutti per trovare i propri.
 */
@Composable
private fun ColumnScope.SchedaFormazioni(state: MatchState, nomeGiocatore: (Long) -> String) {
    val p = state.partita ?: return

    if (p.ratings.isEmpty()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                if (p.completa) "Per questa partita non ci sono pagelle."
                else "Le pagelle arrivano a fine partita.",
                style = MFootType.secondary,
                color = MFootColors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(40.dp),
            )
        }
        return
    }

    val moduli = mapOf(p.homeClubId to p.homeFormation, p.awayClubId to p.awayFormation)

    LazyColumn(Modifier.weight(1f)) {
        listOf(p.homeClubId to state.homeName, p.awayClubId to state.awayName).forEach { (club, nome) ->
            val suoi = p.ratings.filter { it.clubId == club }
            if (suoi.isEmpty()) return@forEach

            item(key = "t-$club") {
                Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Label(nome, Modifier.weight(1f))
                        // Il modulo accanto al nome, come nel riferimento: dice **come**
                        // e' scesa in campo, che e' la prima cosa che si guarda insieme ai
                        // voti.
                        moduli[club]?.let { m ->
                            Text(
                                m.removePrefix("F_").replace('_', '-'),
                                style = MFootType.chip,
                                color = MFootColors.ink3,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    CampoFormazione(moduli[club], suoi, nomeGiocatore)
                    Spacer(Modifier.height(14.dp))
                    Text("In campo dall'inizio", style = MFootType.label, color = MFootColors.ink3)
                }
            }
            items(suoi.filter { it.started }, key = { "s${it.playerId}" }) { v ->
                Pagella(v, nomeGiocatore(v.playerId), p.duelli[v.playerId])
            }
            val dentro = suoi.filter { !it.started && it.minutes > 0 }
            if (dentro.isNotEmpty()) {
                item(key = "p-$club") {
                    Column(Modifier.padding(MFootSpacing.section, 10.dp, MFootSpacing.section, 6.dp)) {
                        Text("Entrati", style = MFootType.label, color = MFootColors.ink3)
                    }
                }
                items(dentro, key = { "e${it.playerId}" }) { v ->
                    Pagella(v, nomeGiocatore(v.playerId), p.duelli[v.playerId])
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
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

/**
 * Il velo dell'intervallo, sopra il campo.
 *
 * Un campo fermo con la palla al centro e' indistinguibile da un'app bloccata. Qui c'e'
 * scritto **quale** delle due attese si sta vivendo: l'intervallo vero, o i minuti in cui
 * l'intervallo e' finito e il server non ha ancora giocato il secondo tempo — il tick passa
 * ogni cinque minuti, non ogni secondo.
 */
@Composable
private fun Sipario(state: MatchState, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Color.Black.copy(alpha = 0.55f), MFootShapes.band),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (state.attesaRipresa) "Si riprende" else "Intervallo",
                style = MFootType.playerName,
                color = MFootColors.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.attesaRipresa) "Il secondo tempo sta per cominciare."
                else "${state.golCasa} - ${state.golFuori} · si torna in campo fra poco",
                style = MFootType.chip,
                color = MFootColors.ink2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * La barra dell'inerzia: da che parte sta andando la partita **adesso**.
 *
 * Si conta sulla pericolosita' degli ultimi dieci minuti, non sul possesso: il possesso
 * dice chi ha la palla, l'inerzia dice chi sta facendo male. Sono due cose diverse, e
 * quella che si vuole sapere guardando e' la seconda.
 */
@Composable
private fun Inerzia(state: MatchState) {
    val recenti = state.partita?.moments.orEmpty()
        .filter { it.minute in (state.minuto - 10)..state.minuto }

    val casa = recenti.filter { it.homeSide }.sumOf { it.danger }.toFloat()
    val fuori = recenti.filter { !it.homeSide }.sumOf { it.danger }.toFloat()
    val quota = if (casa + fuori <= 0f) 0.5f else casa / (casa + fuori)

    // Scorre invece di saltare: un'inerzia che sbatte da un lato all'altro a ogni evento
    // non e' leggibile, ed e' anche falsa — l'inerzia e' una cosa che si sposta piano.
    val animata by animateFloatAsState(quota, tween(900), label = "inerzia")

    Column(Modifier.padding(MFootSpacing.section, 10.dp, MFootSpacing.section, 2.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(MFootColors.gamble.copy(alpha = 0.35f), MFootShapes.pill),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animata.coerceIn(0.03f, 0.97f))
                    .height(4.dp)
                    .background(MFootColors.elite, MFootShapes.pill),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("Inerzia", style = MFootType.label, color = MFootColors.ink3)
    }
}

@Composable
private fun Comandi(
    state: MatchState,
    onRivedi: () -> Unit,
    onPausa: () -> Unit,
    onFine: () -> Unit,
    onVelocita: (Int) -> Unit,
    onChiudi: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(MFootSpacing.section, 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // In diretta non ci sono comandi: non si mette in pausa una partita, e «salta alla
        // fine» vorrebbe dire saltare a un finale che non e' ancora successo. Al loro posto
        // c'e' quello che sta succedendo.
        if (state.diretta) {
            Text(
                state.avviso ?: "In diretta",
                style = MFootType.chip,
                color = if (state.avviso == null) MFootColors.elite else MFootColors.ink2,
            )
        } else {
            Bottone("Rivedila", onRivedi)
            if (!state.finita) {
                Bottone(if (state.inCorso) "Pausa" else "Riprendi", onPausa)
                Bottone("Salta alla fine", onFine)
            }
            val velocitaDisponibili = listOf(1 to "X1", 2 to "X2", 3 to "X3", 10 to "X10")
            velocitaDisponibili.forEach { (vel, label) ->
                val attiva = state.velocita == vel
                Text(
                    label,
                    style = MFootType.chip,
                    color = if (attiva) MFootColors.bg else MFootColors.ink2,
                    modifier = Modifier
                        .background(
                            if (attiva) MFootColors.ink else MFootColors.core,
                            MFootShapes.pill,
                        )
                        .clickable { onVelocita(vel) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Text(
            "Chiudi",
            style = MFootType.chip,
            color = MFootColors.ink3,
            modifier = Modifier.clickable(onClick = onChiudi).padding(horizontal = 8.dp, vertical = 8.dp),
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
private fun Pagella(voto: MatchRating, nome: String, duelli: DuelliPartita? = null) {
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

            // La seconda riga e' quella che rende leggibile un difensore. Sulla prima, di
            // un centrale c'era scritto solo quanti cartellini aveva preso: un grande
            // centrale e uno scarso producevano lo stesso identico foglio.
            val racconto = duelli?.let(::rigaDeiDuelli)
            if (racconto != null) {
                Spacer(Modifier.height(2.dp))
                Text(racconto, style = MFootType.chip, color = MFootColors.ink3)
            }
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

/**
 * I duelli in una riga sola.
 *
 * Si scrive solo quello che c'e'. Un terzino che ha vinto nove duelli e non ha mai
 * dribblato non deve leggere «0 dribbling»: leggerebbe un difetto dove c'e' un mestiere.
 */
private fun rigaDeiDuelli(d: DuelliPartita): String? {
    val pezzi = buildList {
        d.percentualeDuelli?.let { add("${d.vinti}/${d.duelli} duelli · $it%") }
        if (d.dribbling > 0) add("${d.dribbling} dribbling")
        if (d.dribblingSubiti > 0) add("saltato ${d.dribblingSubiti} volte")
        d.precisione?.let { add("$it% passaggi") }
    }
    return pezzi.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
