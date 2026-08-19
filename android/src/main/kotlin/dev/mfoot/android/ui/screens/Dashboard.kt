package dev.mfoot.android.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.CompetizioniMie
import dev.mfoot.android.app.ObiettiviState
import dev.mfoot.android.app.Route
import dev.mfoot.android.app.TabLega
import dev.mfoot.android.app.TabMercato
import dev.mfoot.android.app.TabSquadra
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.PrimaryButton
import dev.mfoot.android.ui.kit.Kit
import dev.mfoot.android.ui.kit.Shirt
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.model.Money
import dev.mfoot.core.objectives.ObjectiveStatus

/**
 * La schermata che si apre per prima — **il tuo club**.
 *
 * ## Cosa ci sta e cosa no
 *
 * La maglia grande, il nome, e i tre numeri che servono a decidere qualcosa adesso: quanto
 * si puo' spendere, quanti giocatori ci sono, quante aste sono in corso. Poi, se c'e' una
 * decisione che scade, sta qui in evidenza.
 *
 * Non ci sta un riassunto di tutto: una dashboard che mostra dodici riquadri non fa
 * risparmiare tempo, lo fa perdere, perche' obbliga a cercare fra dodici cose quale
 * riguarda adesso.
 */
@Composable
fun DashboardScreen(
    state: AppState.Dentro,
    competizioni: CompetizioniMie,
    obiettivi: ObiettiviState,
    onCaricaCompetizioni: () -> Unit,
    onCaricaObiettivi: () -> Unit,
    onNavigate: (Route) -> Unit,
    onFoundClub: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val club = state.lega.myClub

    LaunchedEffect(state.lega.league.id) {
        onCaricaCompetizioni()
        onCaricaObiettivi()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(MFootSpacing.section),
    ) {
        state.errore?.let {
            Notice(it, MFootColors.gamble)
            Spacer(Modifier.height(MFootSpacing.related))
        }
        state.avviso?.let {
            Notice(it, MFootColors.elite, Modifier.clickable(onClick = onDismissNotice))
            Spacer(Modifier.height(MFootSpacing.related))
        }

        if (club == null) {
            SenzaClub(onFoundClub)
            return@Column
        }

        Text(
            club.name,
            style = MFootType.playerName,
            color = MFootColors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            club.ownerName ?: "il tuo club",
            style = MFootType.chip,
            color = MFootColors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // La maglia del club, non quella predefinita. Disegnarne una fissa qui voleva
            // dire che i colori scelti alla fondazione si vedevano una volta sola, durante
            // la scelta, e poi mai piu': salvati, e invisibili.
            Shirt(club.kit, Modifier.size(148.dp, 166.dp), showNumber = false)
        }
        Spacer(Modifier.height(24.dp))

        val rosa = state.lega.squadOf(club.id)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Numero(
                valore = Money(club.available).format(),
                etichetta = "Disponibili",
                colore = if (club.available > 0) MFootColors.elite else MFootColors.gamble,
                modifier = Modifier.weight(1f),
            )
            Numero(
                valore = rosa.size.toString(),
                etichetta = "In rosa",
                colore = if (rosa.size >= state.lega.league.config.setup.minSquadSize) {
                    MFootColors.ink
                } else {
                    MFootColors.gamble
                },
                modifier = Modifier.weight(1f),
            )
            Numero(
                valore = state.myAuctions.size.toString(),
                etichetta = "Tue aste",
                colore = if (state.myAuctions.isEmpty()) MFootColors.ink3 else MFootColors.gamble,
                modifier = Modifier.weight(1f),
            )
        }

        // La rosa incompleta non e' un dettaglio: senza il minimo, la squadra non scende in
        // campo e le partite si rinviano. Va detto qui, non scoperto dal registro del tick.
        val minimo = state.lega.league.config.setup.minSquadSize
        if (rosa.size < minimo) {
            Spacer(Modifier.height(MFootSpacing.section))
            Notice(
                "Ti servono ${minimo - rosa.size} giocatori per arrivare a $minimo: " +
                    "sotto il minimo la squadra non scende in campo.",
                MFootColors.gamble,
            )
            Spacer(Modifier.height(MFootSpacing.related))
            PrimaryButton(text = "Vai al mercato", onClick = { onNavigate(Route.Mercato(TabMercato.SVINCOLATI)) })
        }

        Spacer(Modifier.height(28.dp))
        ACosaGiochi(state, competizioni, onNavigate)

        Spacer(Modifier.height(28.dp))
        Obiettivi(state, obiettivi, onNavigate)

        Spacer(Modifier.height(28.dp))
        Label("Scorciatoie")
        Spacer(Modifier.height(10.dp))
        Scorciatoia("Schiera la squadra", "Campo, modulo, panchina") { onNavigate(Route.Squadra(TabSquadra.CAMPO)) }
        Scorciatoia("Aste aperte", "${state.auctions.size} in corso nella lega") { onNavigate(Route.Mercato(TabMercato.ASTE)) }
        Scorciatoia("Classifica", "Punti e calendario") { onNavigate(Route.Lega(TabLega.CLASSIFICA)) }
        Scorciatoia("Le altre squadre", "${state.lega.clubs.size} club") { onNavigate(Route.Lega(TabLega.SQUADRE)) }

        Spacer(Modifier.height(30.dp))
    }
}

/**
 * A cosa stai giocando: la divisione, e i tornei a cui il tuo club e' iscritto.
 *
 * ## Perche' e' una sezione e non una riga
 *
 * Perche' erano due informazioni fondamentali che il gioco non scriveva da nessuna parte.
 *
 * La **divisione** decide contro chi giochi e cosa succede a fine stagione, ed esisteva
 * solo come numero nel database: l'unico modo di sapere in che serie si stava era dedurlo
 * dal calendario.
 *
 * Le **competizioni** vivevano dentro un menu a tendina della classifica. Un admin puo'
 * creare un campionato, una coppa e un torneo a gironi insieme — e' la cosa che rende una
 * lega la sua — e chi ci gioca dentro vedeva solo delle partite, senza sapere di che
 * torneo facessero parte.
 */
@Composable
private fun ACosaGiochi(
    state: AppState.Dentro,
    competizioni: CompetizioniMie,
    onNavigate: (Route) -> Unit,
) {
    val club = state.lega.myClub ?: return
    val primavera = state.lega.myYouthClub
    val divisioni = state.lega.league.config.divisions

    // Sia la prima squadra sia la Primavera: sono due club veri, giocano due campionati
    // diversi, e chi ha la seconda squadra vuole sapere anche dove gioca quella.
    val miei = listOfNotNull(club.id, primavera?.id).toSet()
    val mie = competizioni.tutte.filter { c -> c.participants.any { it in miei } }

    Label("A cosa giochi")
    Spacer(Modifier.height(10.dp))

    if (divisioni.enabled) {
        Riga(
            titolo = divisioni.nameOf(club.divisionLevel),
            dettaglio = buildString {
                append("la tua divisione · ")
                append(state.lega.clubs.count { it.divisionLevel == club.divisionLevel })
                append(" squadre")
                primavera?.let {
                    append(" · Primavera in ").append(divisioni.nameOf(it.divisionLevel))
                }
            },
        ) { onNavigate(Route.Lega(TabLega.SQUADRE)) }
    }

    when {
        !competizioni.letto -> Text(
            "Leggo le competizioni…",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        mie.isEmpty() -> Text(
            "Nessuna competizione in corso per il tuo club. Finche' l'admin non ne crea " +
                "una, si gioca solo il mercato e le amichevoli.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        else -> mie.forEach { c ->
            val perPrimavera = primavera != null && club.id !in c.participants
            Riga(
                titolo = c.name,
                dettaglio = buildString {
                    append(c.type.label)
                    append(" · ").append(c.participants.size).append(" squadre")
                    if (c.fixtures > 0) {
                        append(" · ").append(c.played).append(" di ").append(c.fixtures)
                            .append(" partite giocate")
                    }
                    if (c.isFinished) append(" · finita")
                    if (perPrimavera) append(" · e' della tua Primavera")
                },
            ) { onNavigate(Route.Lega(TabLega.CLASSIFICA)) }
        }
    }
}

/**
 * Cosa ti chiede la societa' quest'anno, e quanto paga.
 *
 * ## Perche' sta in Casa e non solo nella sua schermata
 *
 * Perche' un obiettivo che si legge una volta a settembre e poi mai piu' non cambia nessuna
 * decisione: e' un promemoria, non una pressione. Deve stare dove si guarda ogni giorno,
 * accanto ai crediti disponibili — che sono la cosa con cui si compra quello che serve per
 * raggiungerlo.
 */
@Composable
private fun Obiettivi(
    state: AppState.Dentro,
    obiettivi: ObiettiviState,
    onNavigate: (Route) -> Unit,
) {
    val club = state.lega.myClub ?: return
    val miei = obiettivi.diClub(club.id)
    if (!obiettivi.letto || miei.isEmpty()) return

    val inBallo = miei
        .filter { it.status == ObjectiveStatus.IN_CORSO }
        .sumOf { it.premio }

    Label("I tuoi obiettivi · stagione ${obiettivi.stagione}")
    Spacer(Modifier.height(10.dp))

    miei.forEach { riga ->
        Riga(
            titolo = riga.descrizione,
            dettaglio = when (riga.status) {
                ObjectiveStatus.IN_CORSO -> "in corso · ${Money(riga.premio).formatShort()} se ce la fai"
                ObjectiveStatus.RAGGIUNTO -> "raggiunto · ${Money(riga.paid).formatShort()} incassati"
                ObjectiveStatus.FALLITO -> "fallito · niente premio"
            },
        ) { onNavigate(Route.Obiettivi) }
    }

    if (inBallo > 0) {
        Text(
            "In ballo ${Money(inBallo).format()}. Si prendono solo raggiungendoli: " +
                "arrivarci vicino non paga niente.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
    }
}

@Composable
private fun Riga(titolo: String, dettaglio: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MFootColors.core, MFootShapes.field)
            .border(1.dp, MFootColors.line, MFootShapes.field)
            .clickable(onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(titolo, style = MFootType.rowTitle, color = MFootColors.ink)
            Spacer(Modifier.height(2.dp))
            Text(dettaglio, style = MFootType.chip, color = MFootColors.ink3)
        }
        Text("›", style = MFootType.price, color = MFootColors.ink3)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SenzaClub(onFoundClub: () -> Unit) {
    Spacer(Modifier.height(40.dp))
    Text(
        "Non hai ancora un club",
        style = MFootType.playerName,
        color = MFootColors.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Scegli nome, maglia, e costruisci il giocatore che sei tu. " +
            "Senza club non si compra, non si schiera e non si gioca.",
        style = MFootType.secondary,
        color = MFootColors.ink3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(28.dp))
    PrimaryButton("Fonda il tuo club", onFoundClub)
}

@Composable
private fun Numero(
    valore: String,
    etichetta: String,
    colore: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(MFootColors.core, MFootShapes.band)
            .border(1.dp, MFootColors.lineStrong, MFootShapes.band)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(valore, style = MFootType.price, color = colore)
        Spacer(Modifier.height(3.dp))
        Text(etichetta.uppercase(), style = MFootType.label, color = MFootColors.ink3)
    }
}

@Composable
private fun Scorciatoia(titolo: String, dettaglio: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MFootColors.core, MFootShapes.field)
            .border(1.dp, MFootColors.lineStrong, MFootShapes.field)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(titolo, style = MFootType.rowTitle, color = MFootColors.ink)
            Text(dettaglio, style = MFootType.chip, color = MFootColors.ink3)
        }
        Text("›", style = MFootType.price, color = MFootColors.ink3)
    }
    Spacer(Modifier.height(8.dp))
}
