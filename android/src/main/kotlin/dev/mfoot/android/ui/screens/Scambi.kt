package dev.mfoot.android.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.TradeDraft
import dev.mfoot.android.app.TradesState
import dev.mfoot.android.data.TradeKind
import dev.mfoot.android.data.TradeRow
import dev.mfoot.android.ui.Chip
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.MFootField
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.PrimaryButton
import dev.mfoot.android.ui.kit.CrestBadge
import dev.mfoot.android.ui.settings.MoneyField
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.calendar.KickoffRules
import dev.mfoot.core.model.Money
import dev.mfoot.core.model.Player
import java.time.format.DateTimeFormatter

private val QUANDO_AMICHEVOLE = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

/**
 * Gli scambi fra squadre.
 *
 * ## Perche' e' la cosa che mancava di piu'
 *
 * In una lega fra amici la trattativa e' meta' del gioco, e senza scambi l'unico modo di
 * prendere un giocatore altrui e' aspettare che lo metta all'asta — cioe' mai, se e' bravo.
 * Le chiacchiere avvengono gia' nel gruppo del telefono; qui si formalizzano.
 *
 * ## Perche' le proposte ricevute stanno in cima
 *
 * Perche' sono l'unica cosa che richiede una risposta. Una proposta mandata si guarda per
 * curiosita'; una ricevuta e' qualcuno che aspetta te, e finche' non rispondi non succede
 * niente.
 */
@Composable
fun ScambiScreen(
    state: AppState.Dentro,
    scambi: TradesState,
    onNuovo: (Long) -> Unit,
    onEdit: (TradeDraft) -> Unit,
    onInvia: () -> Unit,
    onAnnulla: () -> Unit,
    onRispondi: (Long, Boolean) -> Unit,
    onControproponi: (TradeRow) -> Unit,
    onRitira: (Long) -> Unit,
    onChiudiAvviso: () -> Unit,
) {
    val mio = state.lega.myClub
    if (mio == null) {
        Vuoto("Prima serve un club.")
        return
    }

    val bozza = scambi.bozza
    if (bozza != null) {
        Composizione(state, scambi, bozza, onEdit, onInvia, onAnnulla)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.padding(MFootSpacing.section)) {
            scambi.errore?.let {
                Notice(it, MFootColors.gamble, Modifier.clickable(onClick = onChiudiAvviso))
                Spacer(Modifier.height(MFootSpacing.related))
            }
            scambi.avviso?.let {
                Notice(it, MFootColors.elite, Modifier.clickable(onClick = onChiudiAvviso))
                Spacer(Modifier.height(MFootSpacing.related))
            }

            Label("Proponi a")
            Spacer(Modifier.height(9.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.lega.clubs.filterNot { it.id == mio.id }.forEach { club ->
                    Column(
                        Modifier
                            .background(MFootColors.core, MFootShapes.band)
                            .clickable { onNuovo(club.id) }
                            .padding(10.dp)
                            .width(78.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CrestBadge(club.crest, Modifier.size(38.dp), club.shortName)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            club.name,
                            style = MFootType.chip,
                            color = MFootColors.ink2,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Sezione("Ricevute", scambi.ricevute(mio.id), state, mio.id) { trade ->
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(MFootSpacing.related)) {
                    GhostButton("Rifiuta", { onRispondi(trade.id, false) }, Modifier.weight(1f))
                    PrimaryButton(
                        text = scambi.busy ?: "Accetta",
                        onClick = { onRispondi(trade.id, true) },
                        modifier = Modifier.weight(1f),
                        enabled = scambi.busy == null,
                    )
                }
                // Il terzo pulsante e quello che trasforma un si/no in una trattativa:
                // apre la stessa proposta dalla parte opposta, con i giocatori gia
                // dentro e la cifra da ritoccare.
                Spacer(Modifier.height(MFootSpacing.related))
                GhostButton("Controproponi", { onControproponi(trade) })
            }
        }

        Sezione("Mandate", scambi.mandate(mio.id), state, mio.id) { trade ->
            GhostButton("Ritira", { onRitira(trade.id) })
        }

        Sezione("Concluse", scambi.concluse(), state, mio.id, azioni = null)

        if (scambi.trades.isEmpty() && scambi.letto) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nessuna trattativa aperta. Tocca una squadra qui sopra per " +
                        "proporle uno scambio, un prestito o un'amichevole.",
                    style = MFootType.secondary,
                    color = MFootColors.ink3,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Sezione(
    titolo: String,
    righe: List<TradeRow>,
    state: AppState.Dentro,
    myClubId: Long,
    azioni: (@Composable (TradeRow) -> Unit)?,
) {
    if (righe.isEmpty()) return

    Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 8.dp)) {
        Label("$titolo · ${righe.size}")
    }

    righe.forEach { trade ->
        Column(Modifier.fillMaxWidth().padding(MFootSpacing.section, 12.dp)) {
            val altro = state.lega.clubs.firstOrNull {
                it.id == if (trade.isIncoming(myClubId)) trade.fromClub else trade.toClub
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                altro?.let { CrestBadge(it.crest, Modifier.size(30.dp)) }
                Spacer(Modifier.width(9.dp))
                Text(
                    altro?.name ?: "Club sconosciuto",
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(trade.status.label, style = MFootType.chip, color = coloreStato(trade))
            }

            Spacer(Modifier.height(7.dp))
            Text(
                trade.kind.label.uppercase(),
                style = MFootType.label,
                color = when (trade.kind) {
                    TradeKind.SCAMBIO -> MFootColors.ink3
                    TradeKind.PRESTITO -> MFootColors.gamble
                    TradeKind.AMICHEVOLE -> MFootColors.good
                },
            )

            Spacer(Modifier.height(7.dp))

            when (trade.kind) {
                TradeKind.SCAMBIO -> {
                    // Chi da' cosa, dal punto di vista di chi guarda. "Ricevi / Dai" e non
                    // "offerti / chiesti": chi legge vuole sapere cosa entra e cosa esce da
                    // casa sua, non da quale lato della proposta stava scritto.
                    val ricevo = if (trade.isIncoming(myClubId)) trade.offered else trade.wanted
                    val do_ = if (trade.isIncoming(myClubId)) trade.wanted else trade.offered
                    val soldi = if (trade.isIncoming(myClubId)) trade.cash else -trade.cash

                    Colonna("Ricevi", ricevo, soldi.takeIf { it > 0 }, state, MFootColors.elite)
                    Spacer(Modifier.height(6.dp))
                    Colonna("Dai", do_, (-soldi).takeIf { it > 0 }, state, MFootColors.gamble)
                }

                TradeKind.PRESTITO -> {
                    val chi = trade.loanedPlayer
                    Colonna(
                        if (trade.isIncoming(myClubId)) "Prendi" else "Presti",
                        listOfNotNull(chi),
                        null,
                        state,
                        if (trade.isIncoming(myClubId)) MFootColors.elite else MFootColors.gamble,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append("${trade.terms.matchDays} giornate")
                            if (trade.terms.fee > 0) {
                                append(" · ${Money(trade.terms.fee).formatShort()} a giornata")
                            }
                            append(
                                if (trade.terms.wagePaidByBorrower) {
                                    " · ingaggio a chi lo prende"
                                } else {
                                    " · ingaggio a chi lo presta"
                                },
                            )
                            if (trade.terms.canPlayAgainstOwner) append(" · può giocare contro")
                        },
                        style = MFootType.chip,
                        color = MFootColors.ink3,
                    )
                }

                TradeKind.AMICHEVOLE -> Text(
                    trade.terms.kickoff
                        ?.atZone(state.lega.league.config.calendar.timeZone)
                        ?.format(QUANDO_AMICHEVOLE)
                        ?: "orario non indicato",
                    style = MFootType.chip,
                    color = MFootColors.ink2,
                )
            }

            if (trade.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("“${trade.message}”", style = MFootType.chip, color = MFootColors.ink2)
            }
            if (trade.answer.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(trade.answer, style = MFootType.chip, color = MFootColors.ink3)
            }

            if (azioni != null) {
                Spacer(Modifier.height(MFootSpacing.related))
                azioni(trade)
            }
        }
        Hairline()
    }
}

@Composable
private fun Colonna(
    etichetta: String,
    ids: List<Long>,
    soldi: Int?,
    state: AppState.Dentro,
    colore: androidx.compose.ui.graphics.Color,
) {
    if (ids.isEmpty() && soldi == null) return

    val nomi = ids.mapNotNull { id ->
        state.lega.players.firstOrNull { it.id.value == id }?.let {
            "${it.shortName} (${it.primaryPosition.short} ${it.overall})"
        }
    }
    val pezzi = nomi + listOfNotNull(soldi?.let { Money(it).formatShort() })

    Row {
        Text(
            etichetta,
            style = MFootType.label,
            color = colore,
            modifier = Modifier.width(46.dp),
        )
        Text(
            pezzi.joinToString(" · "),
            style = MFootType.chip,
            color = MFootColors.ink2,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun coloreStato(trade: TradeRow) = when (trade.status.name) {
    "ACCETTATA" -> MFootColors.elite
    "PROPOSTA" -> MFootColors.gamble
    else -> MFootColors.ink3
}

// ------------------------------------------------------------------------ comporre

/**
 * Il modulo della proposta.
 *
 * ## Perche' le due rose stanno una sopra l'altra
 *
 * Perche' uno scambio e' un confronto: si guarda cosa si da' e cosa si prende **insieme**.
 * Con due schermate separate bisognerebbe ricordarsi a memoria cosa si e' scelto di la',
 * e la proposta la si compone al buio.
 */
@Composable
private fun Composizione(
    state: AppState.Dentro,
    scambi: TradesState,
    bozza: TradeDraft,
    onEdit: (TradeDraft) -> Unit,
    onInvia: () -> Unit,
    onAnnulla: () -> Unit,
) {
    val mio = state.lega.myClub ?: return
    val altro = state.lega.clubs.firstOrNull { it.id == bozza.withClub } ?: return
    val miaRosa = state.lega.squadOf(mio.id)
    val suaRosa = state.lega.squadOf(altro.id)
    // Il giudizio sull'ora si da' in ora di lega, come quello scritto sotto il selettore:
    // due orologi diversi produrrebbero un pulsante spento accanto a un «va bene».
    val vuota = bozza.isEmpty(state.lega.league.config.calendar.timeZone)

    Column(Modifier.fillMaxSize().background(MFootColors.bg)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(MFootSpacing.section),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CrestBadge(altro.crest, Modifier.size(42.dp), altro.shortName)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Proposta a ${altro.name}", style = MFootType.rowTitle, color = MFootColors.ink)
                    Text(
                        "${suaRosa.size} in rosa · ${Money(altro.available).formatShort()} disponibili",
                        style = MFootType.chip,
                        color = MFootColors.ink3,
                    )
                }
            }

            scambi.errore?.let {
                Spacer(Modifier.height(MFootSpacing.related))
                Notice(it, MFootColors.gamble)
            }

            // Il tipo si sceglie qui e non prima: il gesto iniziale — toccare una squadra —
            // e' lo stesso per tutte e tre, e chiedere "che cosa vuoi proporre" prima di
            // sapere a chi obbligherebbe a tornare indietro per cambiare idea.
            Spacer(Modifier.height(MFootSpacing.section))
            Label("Che cosa gli proponi")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TradeKind.entries.forEach { tipo ->
                    Chip(tipo.label, bozza.kind == tipo) { onEdit(bozza.copy(kind = tipo)) }
                }
            }

            when (bozza.kind) {
                TradeKind.SCAMBIO -> {
                    Spacer(Modifier.height(MFootSpacing.section))
                    Label("Chiedi a lui · ${bozza.wanted.size}")
                    Spacer(Modifier.height(8.dp))
                    Scelta(suaRosa, bozza.wanted) { id ->
                        onEdit(bozza.copy(wanted = bozza.wanted.toggle(id)))
                    }

                    Spacer(Modifier.height(MFootSpacing.section))
                    Label("Offri tu · ${bozza.offered.size}")
                    Spacer(Modifier.height(8.dp))
                    Scelta(miaRosa, bozza.offered) { id ->
                        onEdit(bozza.copy(offered = bozza.offered.toggle(id)))
                    }

                    Spacer(Modifier.height(MFootSpacing.section))
                    Label("Conguaglio")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (bozza.cash >= 0) {
                            "Aggiungi ${Money(bozza.cash).format()} ai giocatori che offri."
                        } else {
                            "Gli chiedi ${Money(-bozza.cash).format()} oltre ai giocatori."
                        },
                        style = MFootType.chip,
                        color = MFootColors.ink3,
                    )
                    Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MoneyField(kotlin.math.abs(bozza.cash), true) { valore ->
                            val segno = if (bozza.cash < 0) -1 else 1
                            onEdit(bozza.copy(cash = valore * segno))
                        }
                        Spacer(Modifier.width(MFootSpacing.related))
                        Chip("Metto io", bozza.cash >= 0) {
                            onEdit(bozza.copy(cash = kotlin.math.abs(bozza.cash)))
                        }
                        Spacer(Modifier.width(6.dp))
                        Chip("Chiedo io", bozza.cash < 0) {
                            onEdit(bozza.copy(cash = -kotlin.math.abs(bozza.cash)))
                        }
                    }
                }

                TradeKind.PRESTITO -> Prestito(miaRosa, bozza, onEdit)

                TradeKind.AMICHEVOLE -> Amichevole(bozza, state.lega.league.config.calendar.timeZone, onEdit)
            }

            // Due righe da scrivere.
            //
            // Il campo `message` esisteva ed era mostrato, e non c era nessuna casella
            // dove digitarlo: ogni proposta umana partiva vuota mentre le AI ti
            // scrivevano. Una trattativa muta non e una trattativa.
            Spacer(Modifier.height(MFootSpacing.section))
            MFootField(
                value = bozza.message,
                onValueChange = { onEdit(bozza.copy(message = it)) },
                placeholder = "Due righe per convincerlo",
                label = "Il tuo messaggio",
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            )

            Spacer(Modifier.height(30.dp))
        }

        Column(
            Modifier.fillMaxWidth().background(MFootColors.core).padding(MFootSpacing.section),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MFootSpacing.related)) {
                GhostButton("Annulla", onAnnulla, Modifier.weight(1f))
                PrimaryButton(
                    text = scambi.busy ?: if (vuota) "Proposta vuota" else "Manda",
                    onClick = onInvia,
                    modifier = Modifier.weight(1f),
                    enabled = !vuota && scambi.busy == null,
                )
            }
        }
    }
}

/**
 * Il modulo del prestito.
 *
 * ## Perche' un giocatore solo
 *
 * Perche' un prestito ha una scadenza, e due giocatori con una scadenza sola sono due
 * prestiti travestiti da uno: se nel frattempo uno dei due viene girato altrove, alla
 * scadenza il gioco dovrebbe far tornare indietro qualcosa che non c'e' piu'. Sceglierne
 * uno per volta costa un giro in piu' e toglie una classe intera di stati assurdi.
 */
@Composable
private fun Prestito(miaRosa: List<Player>, bozza: TradeDraft, onEdit: (TradeDraft) -> Unit) {
    Spacer(Modifier.height(MFootSpacing.section))
    Label("Chi gli presti")
    Spacer(Modifier.height(4.dp))
    Text(
        "Uno solo. Resta tuo e torna alla scadenza.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
    Spacer(Modifier.height(8.dp))
    Scelta(miaRosa, bozza.offered) { id ->
        // Sceglierne un altro sostituisce il precedente invece di aggiungerlo: e' la
        // regola del prestito, e farla rispettare qui evita di doverla spiegare con un
        // errore dopo aver premuto Manda.
        onEdit(bozza.copy(offered = if (id in bozza.offered) emptySet() else setOf(id)))
    }

    Spacer(Modifier.height(MFootSpacing.section))
    Label("Per quanto")
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(5, 10, 20, 40).forEach { giornate ->
            Chip("$giornate giornate", bozza.loanMatchDays == giornate) {
                onEdit(bozza.copy(loanMatchDays = giornate))
            }
        }
    }

    Spacer(Modifier.height(MFootSpacing.section))
    Label("Quanto ti paga per giornata")
    Spacer(Modifier.height(8.dp))
    MoneyField(bozza.loanFee, true) { onEdit(bozza.copy(loanFee = it)) }

    Spacer(Modifier.height(MFootSpacing.section))
    Label("Condizioni")
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Ingaggio a suo carico", bozza.wagePaidByBorrower) {
            onEdit(bozza.copy(wagePaidByBorrower = !bozza.wagePaidByBorrower))
        }
        Chip("Può giocare contro di te", bozza.canPlayAgainstOwner) {
            onEdit(bozza.copy(canPlayAgainstOwner = !bozza.canPlayAgainstOwner))
        }
    }
}

/**
 * Il modulo dell'amichevole: quando, davvero quando.
 *
 * ## Cosa c'era prima, e perche' non bastava
 *
 * Nove pulsanti: oggi/domani/dopodomani per le 15, le 18 e le 21. L'argomento era che un
 * selettore completo serve a fissare partite fra tre mesi che nessuno giochera'.
 * L'argomento vale per la **data**, non per l'ora: le tre ore erano scelte a caso, e chi
 * voleva giocare alle 22:30 non aveva modo di chiederlo.
 *
 * Peggio: «oggi alle 15» restava toccabile anche alle 18. La proposta partiva, il database
 * la rifiutava — perche' `propose_friendly` controlla `p_kickoff <= now()` — e l'unico
 * segnale era un messaggio d'errore dopo il fatto. Un pulsante che si puo' premere e che da'
 * sempre errore insegna a non fidarsi di nessun pulsante.
 *
 * Adesso: la data si sceglie fino a due settimane avanti, l'ora si scrive, e cio' che e'
 * gia' passato **si vede spento**. Il controllo e' [KickoffRules], lo stesso che usa la
 * creazione delle competizioni, cosi' le due schermate non possono dire cose diverse.
 */
@Composable
private fun Amichevole(
    bozza: TradeDraft,
    fuso: java.time.ZoneId,
    onEdit: (TradeDraft) -> Unit,
) {
    // «Adesso» in ora di lega, non in ora del telefono: le fasce che si scelgono qui sono
    // ore di lega, e confrontarle con l'orologio locale vorrebbe dire che la stessa
    // proposta e' valida da Milano e scaduta da Londra.
    val adesso = java.time.LocalDateTime.now(fuso)
    val oggi = adesso.toLocalDate()
    var giorniAvanti by remember { mutableStateOf(0L) }
    var oraScritta by remember { mutableStateOf("") }

    Spacer(Modifier.height(MFootSpacing.section))
    Label("Quando")
    Spacer(Modifier.height(4.dp))
    Text(
        "Ora della lega. Se una delle due squadre gioca già in quella fascia, la " +
            "proposta viene rifiutata.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
    Spacer(Modifier.height(9.dp))

    // Il giorno.
    val giornoScelto = bozza.friendlyAt?.toLocalDate() ?: oggi.plusDays(giorniAvanti)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (0L..13L).forEach { giorni ->
            val giorno = oggi.plusDays(giorni)
            val etichetta = when (giorni) {
                0L -> "Oggi"
                1L -> "Domani"
                else -> "%02d/%02d".format(giorno.dayOfMonth, giorno.monthValue)
            }
            Chip(etichetta, giorno == giornoScelto) {
                giorniAvanti = giorni
                // Cambiando giorno l'ora resta: chi sposta l'appuntamento da stasera a
                // domani sera vuole ancora "sera", non ricominciare da capo.
                val ora = bozza.friendlyAt?.toLocalTime()
                onEdit(bozza.copy(friendlyAt = ora?.let { giorno.atTime(it) }))
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // Le tre solite, spente quando sono passate.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(15, 18, 21).forEach { ora ->
            val quando = giornoScelto.atTime(ora, 0)
            val giocabile = KickoffRules.isPlayable(quando, adesso)
            Text(
                "$ora:00",
                style = MFootType.chip,
                color = when {
                    !giocabile -> MFootColors.ink3.copy(alpha = 0.45f)
                    bozza.friendlyAt == quando -> MFootColors.bg
                    else -> MFootColors.ink2
                },
                modifier = Modifier
                    .background(
                        if (bozza.friendlyAt == quando) MFootColors.ink else MFootColors.line,
                        MFootShapes.pill,
                    )
                    .clickable(enabled = giocabile) { onEdit(bozza.copy(friendlyAt = quando)) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            MFootField(
                value = oraScritta,
                onValueChange = { t -> oraScritta = t.filter { it.isDigit() || it == ':' }.take(5) },
                placeholder = "es. 22:30",
                label = "Un'altra ora",
                imeAction = ImeAction.Done,
            )
        }
        Spacer(Modifier.width(10.dp))
        val letta = oraDaTesto(oraScritta)
        val quando = letta?.let { giornoScelto.atTime(it) }
        val ok = quando != null && KickoffRules.isPlayable(quando, adesso)
        Chip(if (ok) "usa quest'ora" else "hh:mm", ok) {
            if (ok) {
                onEdit(bozza.copy(friendlyAt = quando))
                oraScritta = ""
            }
        }
    }

    // Il verdetto sull'ora scelta, scritto per esteso: e' quello che prima arrivava dal
    // server sotto forma di errore, e arrivava dopo.
    val problema = bozza.friendlyAt?.let { KickoffRules.problema(it, adesso) }
    Spacer(Modifier.height(10.dp))
    when {
        bozza.friendlyAt == null ->
            Text("Scegli un'ora.", style = MFootType.chip, color = MFootColors.ink3)
        problema != null -> Notice(problema, MFootColors.gamble)
        else -> Text(
            "Appuntamento: ${quandoLeggibile(bozza.friendlyAt, oggi)}.",
            style = MFootType.chip,
            color = MFootColors.elite,
        )
    }
}

/** Come [leggiOra] nelle competizioni: `21`, `21:0`, `21:00`, `2130` valgono tutti. */
private fun oraDaTesto(testo: String): java.time.LocalTime? {
    val pulito = testo.trim().removeSuffix(":")
    if (pulito.isEmpty()) return null

    val (h, m) = when {
        ':' in pulito -> pulito.substringBefore(':') to pulito.substringAfter(':').ifEmpty { "0" }
        pulito.length <= 2 -> pulito to "0"
        else -> pulito.dropLast(2) to pulito.takeLast(2)
    }

    val ore = h.toIntOrNull() ?: return null
    val minuti = m.toIntOrNull() ?: return null
    if (ore !in 0..23 || minuti !in 0..59) return null
    return java.time.LocalTime.of(ore, minuti)
}

private fun quandoLeggibile(
    quando: java.time.LocalDateTime,
    oggi: java.time.LocalDate,
): String {
    val giorno = when (quando.toLocalDate()) {
        oggi -> "oggi"
        oggi.plusDays(1) -> "domani"
        else -> "%02d/%02d".format(quando.dayOfMonth, quando.monthValue)
    }
    return "$giorno alle %02d:%02d".format(quando.hour, quando.minute)
}

@Composable
private fun Scelta(rosa: List<Player>, scelti: Set<Long>, onToggle: (Long) -> Unit) {
    if (rosa.isEmpty()) {
        Text("Rosa vuota.", style = MFootType.chip, color = MFootColors.ink3)
        return
    }

    rosa.sortedByDescending { it.overall }.forEach { p ->
        val scelto = p.id.value in scelti
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (scelto) MFootColors.elite.copy(alpha = 0.10f) else MFootColors.bg,
                    MFootShapes.field,
                )
                .clickable { onToggle(p.id.value) }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                p.primaryPosition.short,
                style = MFootType.label,
                color = MFootColors.ink3,
                modifier = Modifier.width(34.dp),
            )
            Text(
                p.shortName,
                style = MFootType.rowTitle,
                color = if (scelto) MFootColors.elite else MFootColors.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${p.overall}",
                style = MFootType.overallRow,
                color = MFootColors.rating(p.overall),
            )
        }
        Spacer(Modifier.height(3.dp))
    }
}

/** Aggiunge o toglie: e' il gesto di una lista a scelta multipla. */
private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id

@Composable
private fun Vuoto(testo: String) {
    Box(
        Modifier.fillMaxSize().background(MFootColors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(testo, style = MFootType.secondary, color = MFootColors.ink3)
    }
}
