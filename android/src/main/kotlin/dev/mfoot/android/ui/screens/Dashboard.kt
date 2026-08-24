package dev.mfoot.android.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import dev.mfoot.android.ui.Avanzamento
import dev.mfoot.android.ui.Cartellino
import dev.mfoot.android.ui.Scheda
import dev.mfoot.android.ui.Spiegazione
import dev.mfoot.android.ui.Striscia
import dev.mfoot.android.ui.Tondo
import dev.mfoot.android.ui.Vuoto
import dev.mfoot.android.ui.icons.MFootIcons
import dev.mfoot.android.ui.kit.Shirt
import dev.mfoot.android.ui.theme.MFootColors
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
 *
 * ## Il cielo dietro la maglia
 *
 * La maglia sta su una fascia blu che arriva ai bordi dello schermo, e il resto scorre
 * sotto sul fondo scuro. Prima era su fondo scuro come tutto il resto, e il risultato era
 * che la cosa piu' identitaria dell'app — la maglia che il proprietario ha disegnato —
 * pesava quanto una riga di elenco.
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

    // Chi non ha ancora un club esce di qui: la sua schermata deve occupare tutta
    // l'altezza per mettere il pulsante in fondo, e dentro una colonna che scorre
    // l'altezza dello schermo non esiste — il vincolo e' infinito, `fillMaxSize` non si
    // applica, e il pulsante finirebbe appiccicato sotto al testo a meta' pagina.
    if (club == null) {
        SenzaClub(onFoundClub)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        // Il cielo: fondo blu a tutta larghezza, nome del club, maglia sulla cupola.
        //
        // ## Il nome e la maglia stanno in una colonna, non impilati con gli allineamenti
        //
        // Prima erano due figli dello stesso `Box`, uno in alto e uno in basso: ma
        // l'altezza di un `Box` e' quella del figlio piu' alto — la maglia — e la maglia
        // allineata in basso se la prendeva tutta. Il risultato era il nome del club
        // **dietro** la maglia, con tre lettere che spuntavano sopra il colletto. Una
        // colonna impone l'ordine invece di sperare che gli ingombri non si incontrino;
        // nel `Box` resta solo cio' che deve stare davvero dietro o sopra.
        Box(Modifier.fillMaxWidth().background(MFootColors.hero)) {
            // La cupola dietro la maglia.
            //
            // E' il pezzo che fa sembrare la maglia **appoggiata** invece che incollata
            // sul blu. Un solo tono di bianco all'8%: piu' di cosi' e diventa una forma a
            // se', che ruberebbe l'occhio alla maglia che sta li' per essere guardata.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(122.dp)
                    .clip(RoundedCornerShape(topStartPercent = 55, topEndPercent = 55))
                    .background(Color.White.copy(alpha = 0.08f)),
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // I 76 di margine tengono il nome lontano dai tre tondi a destra: senza,
                // un nome lungo ci finisce sotto.
                Column(
                    Modifier.padding(horizontal = 76.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        club.name,
                        style = MFootType.playerName,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        club.ownerName ?: "il tuo club",
                        style = MFootType.secondary,
                        color = Color.White.copy(alpha = 0.80f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(14.dp))
                Shirt(club.kit, Modifier.size(140.dp, 158.dp), showNumber = false)
            }

            // I tre tondi: le tre cose che si fanno tornando a casa.
            //
            // Non ripetono le scorciatoie qui sotto, le anticipano — quelle portano un
            // conteggio ("3 aste in corso") che un tondo non puo' scrivere, e servono a
            // decidere *se* aprire. Questi servono a chi ha gia' deciso.
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(MFootSpacing.section),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Tondo(
                    MFootIcons.campo,
                    { onNavigate(Route.Squadra(TabSquadra.CAMPO)) },
                    fondo = Color.White,
                    inchiostro = MFootColors.blue,
                    descrizione = "Schiera la squadra",
                )
                Tondo(
                    MFootIcons.carrello,
                    { onNavigate(Route.Mercato(TabMercato.ASTE)) },
                    fondo = MFootColors.gamble,
                    inchiostro = MFootColors.onAccent,
                    descrizione = "Mercato",
                )
                Tondo(
                    MFootIcons.persone,
                    { onNavigate(Route.Lega(TabLega.SQUADRE)) },
                    fondo = MFootColors.elite,
                    inchiostro = MFootColors.onAccent,
                    descrizione = "Le altre squadre",
                )
            }
        }

        Column(Modifier.padding(MFootSpacing.section)) {
            state.errore?.let {
                Notice(it, MFootColors.gamble)
                Spacer(Modifier.height(MFootSpacing.related))
            }
            state.avviso?.let {
                Notice(it, MFootColors.elite, Modifier.clickable(onClick = onDismissNotice))
                Spacer(Modifier.height(MFootSpacing.related))
            }

            val rosa = state.lega.squadOf(club.id)
            val minimo = state.lega.league.config.setup.minSquadSize
            Striscia(
                listOf(
                    Money(club.available).formatShort() to "Disponibili",
                    rosa.size.toString() to "In rosa",
                    state.myAuctions.size.toString() to "Tue aste",
                ),
            )

            // La rosa incompleta non e' un dettaglio: senza il minimo, la squadra non
            // scende in campo e le partite si rinviano. Va detto qui, non scoperto dal
            // registro del tick.
            if (rosa.size < minimo) {
                Spacer(Modifier.height(MFootSpacing.section))
                Notice(
                    "Ti servono ${minimo - rosa.size} giocatori per arrivare a $minimo: " +
                        "sotto il minimo la squadra non scende in campo.",
                    MFootColors.gamble,
                )
                Spacer(Modifier.height(MFootSpacing.related))
                PrimaryButton(
                    text = "Vai al mercato",
                    onClick = { onNavigate(Route.Mercato(TabMercato.SVINCOLATI)) },
                    icona = MFootIcons.carrello,
                )
            }

            Spacer(Modifier.height(28.dp))
            ACosaGiochi(state, competizioni, onNavigate)

            Spacer(Modifier.height(28.dp))
            Obiettivi(state, obiettivi, onNavigate)

            Spacer(Modifier.height(28.dp))
            Label("Scorciatoie")
            Spacer(Modifier.height(10.dp))
            Riga("Schiera la squadra", "Campo, modulo, panchina", MFootIcons.campo) {
                onNavigate(Route.Squadra(TabSquadra.CAMPO))
            }
            Riga("Aste aperte", "${state.auctions.size} in corso nella lega", MFootIcons.cartellino) {
                onNavigate(Route.Mercato(TabMercato.ASTE))
            }
            Riga("Classifica", "Punti e calendario", MFootIcons.medaglia) {
                onNavigate(Route.Lega(TabLega.CLASSIFICA))
            }
            Riga("Le altre squadre", "${state.lega.clubs.size} club", MFootIcons.persone) {
                onNavigate(Route.Lega(TabLega.SQUADRE))
            }

            Spacer(Modifier.height(30.dp))
        }
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
            icona = MFootIcons.divisioni,
        ) { onNavigate(Route.Lega(TabLega.SQUADRE)) }
    }

    when {
        !competizioni.letto -> Text(
            "Leggo le competizioni…",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        mie.isEmpty() -> Spiegazione(
            "Non stai giocando nessun torneo",
            "Finché l'admin non crea una competizione si gioca solo il mercato e le " +
                "amichevoli: il campionato non esiste ancora, quindi in classifica non " +
                "c'è niente da vedere.",
        )

        else -> mie.forEach { c ->
            val perPrimavera = primavera != null && club.id !in c.participants
            Competizione(c, perPrimavera) { onNavigate(Route.Lega(TabLega.CLASSIFICA)) }
        }
    }
}

/**
 * Una competizione con la sua barra di avanzamento.
 *
 * ## Perche' la barra e non «12 di 25 partite giocate»
 *
 * Perche' quella frase la si legge, questa la si vede. La domanda che ci si fa guardando
 * un torneo in corso e' «a che punto siamo» — se e' l'inizio conviene comprare, se e' la
 * fine conviene tenere i crediti — ed e' una domanda a cui una proporzione risponde meglio
 * di due numeri da confrontare a mente.
 *
 * I dati sono quelli che `onCaricaCompetizioni` legge gia': nessuna lettura in piu'.
 */
@Composable
private fun Competizione(
    c: dev.mfoot.android.data.CompetitionInfo,
    perPrimavera: Boolean,
    onClick: () -> Unit,
) {
    Scheda(Modifier.padding(bottom = 10.dp), onClick) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    MFootIcons.coppa,
                    contentDescription = null,
                    tint = if (c.isFinished) MFootColors.ink3 else MFootColors.blue,
                    modifier = Modifier.size(23.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        c.name,
                        style = MFootType.rowTitle,
                        color = MFootColors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(c.type.label)
                            append(" · ").append(c.participants.size).append(" squadre")
                            if (perPrimavera) append(" · della tua Primavera")
                        },
                        style = MFootType.secondary,
                        color = MFootColors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (c.isFinished) Cartellino("finita")
            }

            if (c.fixtures > 0) {
                Spacer(Modifier.height(14.dp))
                Avanzamento(
                    fatto = c.played,
                    totale = c.fixtures,
                    inizio = "${c.played} giocate",
                    fine = "${c.fixtures} in tutto",
                )
            }
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
            icona = MFootIcons.stella,
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

/**
 * Una riga che porta altrove.
 *
 * Era due composable identici — `Riga` e `Scorciatoia` — con quattordici pixel di padding
 * di differenza e nessun motivo per averla. Adesso e' uno, e la freccia e' un'icona invece
 * del carattere `›`, che su certi telefoni veniva disegnato piu' piccolo del resto.
 */
@Composable
private fun Riga(
    titolo: String,
    dettaglio: String,
    icona: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Scheda(Modifier.padding(bottom = 10.dp), onClick) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                icona,
                contentDescription = null,
                tint = MFootColors.blue,
                modifier = Modifier.size(23.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(titolo, style = MFootType.rowTitle, color = MFootColors.ink)
                Spacer(Modifier.height(2.dp))
                Text(dettaglio, style = MFootType.secondary, color = MFootColors.ink2)
            }
            Icon(
                MFootIcons.avanti,
                contentDescription = null,
                tint = MFootColors.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SenzaClub(onFoundClub: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MFootColors.bg)) {
        Vuoto(
            "Non hai ancora un club.\n\nScegli nome, maglia, e costruisci il giocatore che " +
                "sei tu. Senza club non si compra, non si schiera e non si gioca.",
            icona = MFootIcons.maglia,
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(MFootSpacing.section),
        ) {
            PrimaryButton("Fonda il tuo club", onFoundClub, icona = MFootIcons.piu)
        }
    }
}
