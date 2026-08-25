package dev.mfoot.android.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import dev.mfoot.android.BuildConfig
import dev.mfoot.android.app.Route
import dev.mfoot.android.app.SettingsSection
import dev.mfoot.android.app.TabMercato
import dev.mfoot.android.app.TabSquadra
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Testata
import dev.mfoot.android.ui.icons.MFootIcons
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootMotion
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType

/**
 * Il guscio dell'applicazione: intestazione, menu laterale, barra in basso.
 *
 * ## Perche' una struttura e non una schermata sola
 *
 * La prima versione era una lista con dei chip in cima. Funzionava per far vedere che il
 * mondo esisteva, e non funzionava per giocare: le impostazioni della lega, l'elenco delle
 * squadre, la formazione non avevano nessun posto dove stare, quindi non esistevano.
 *
 * Il menu laterale porta le cose che si fanno di rado e con calma — configurare, iscrivere,
 * creare competizioni. La barra in basso porta le cinque a cui si torna dieci volte al
 * giorno. Mescolarle vorrebbe dire cercare la classifica in un menu a scomparsa.
 *
 * ## Gli inset se li gestisce lui
 *
 * `MainActivity` non mette piu' `systemBarsPadding()` alla radice quando si e' dentro la
 * lega: la [BarraAlta] passa **sotto** la barra di stato e la colora di blu, la
 * [BarraPosti] passa sotto quella di navigazione. Senza, resterebbe una striscia di fondo
 * pagina sopra il blu, ed e' la cucitura che fa sembrare l'app un contenuto incorniciato
 * invece di una schermata sola.
 *
 * ## Le voci di SETUP le vede solo l'admin
 *
 * Nascondere un pulsante non e' sicurezza: quella la fa il database, che rifiuta le
 * chiamate di chi non e' amministratore. Ma un pulsante che darebbe sempre errore fa
 * sembrare l'applicazione rotta, e questo si evita.
 */
@Composable
fun Shell(
    title: String,
    subtitle: String,
    nickname: String,
    clubName: String?,
    isAdmin: Boolean,
    route: Route,
    /**
     * Dove si sta giocando: la divisione e la giornata.
     *
     * Prima nel nastro finiva il sottotitolo, cioe' la stessa parola gia' scritta due
     * centimetri piu' su, dentro la barra blu. Una riga alta quanto questa che ripete
     * quella sopra e' spazio speso per niente — nel riferimento li' ci sta il nome della
     * competizione, che e' l'unica cosa di contesto che altrove non e' scritta.
     */
    contesto: String,
    drawerOpen: Boolean,
    /** In quante leghe risulta iscritto chi guarda. Uno e' il caso normale. */
    quanteLeghe: Int,
    /** L'ultima lettura andata a buon fine, o null se non ce n'e' ancora stata nessuna. */
    ultimoAggiornamento: java.time.Instant?,
    onToggleDrawer: () -> Unit,
    onNavigate: (Route) -> Unit,
    onRefresh: () -> Unit,
    onLeaveLeague: () -> Unit,
    /** Torna alla schermata precedente. Chiamato solo dove c'e' davvero dove tornare. */
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MFootColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            // Due intestazioni diverse per due tipi di schermata.
            //
            // Sui **cinque posti** — quelli a cui si torna dieci volte al giorno — la barra
            // blu compatta con l'hamburger e il nastro: serve spazio per il contenuto, e
            // serve la via d'accesso al menu.
            //
            // Sulle schermate del **menu** — profilo, partecipanti, regolamento, registro —
            // la testata illustrata con gli archi, che porta il titolo e la freccia. E' la
            // struttura del riferimento, e risolve due cose insieme: quelle schermate si
            // raggiungono da una porta sola e finora si lasciavano solo col gesto di
            // sistema, e il titolo non e' piu' scritto due volte (sottotitolo della barra
            // e testata della pagina) per finire nello stesso schermo.
            if (route.isTab) {
                BarraAlta(title, subtitle, onToggleDrawer, onRefresh)
                Nastro(contesto, ultimoAggiornamento, onRefresh)
            } else {
                Testata(
                    titolo = subtitle,
                    sopra = title,
                    onIndietro = onBack,
                    insetAlto = true,
                )
            }

            // La riga che avverte di stare guardando una lega fra tante.
            //
            // Compare solo se ce n'e' piu' d'una, e non e' una decorazione: due amici
            // hanno giocato in leghe diverse convinti di essere nella stessa, e non
            // esisteva un solo posto nell'app che dicesse quale delle proprie si stava
            // guardando. Con una lega sola non c'e' niente da dire e non compare.
            if (quanteLeghe > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MFootColors.elite.copy(alpha = 0.12f))
                        .clickable { onNavigate(Route.MieLeghe) }
                        .padding(horizontal = MFootSpacing.section, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Sei in $quanteLeghe leghe. Stai guardando $title.",
                        style = MFootType.chip,
                        color = MFootColors.elite,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("cambia", style = MFootType.chip, color = MFootColors.elite)
                }
            }

            Box(Modifier.weight(1f)) { content() }

            // La barra in basso e' l'ultimo elemento della colonna, non sovrapposta: cosi'
            // il contenuto non le finisce mai sotto e non serve nessun margine inventato.
            BarraPosti(route, onNavigate)
        }

        // Il velo intercetta il tocco: senza, si finisce per premere un pulsante della
        // schermata sotto mentre si voleva solo chiudere il menu.
        AnimatedVisibility(
            visible = drawerOpen,
            enter = fadeIn(tween(MFootMotion.fast, easing = MFootMotion.easing)),
            exit = fadeOut(tween(MFootMotion.fast, easing = MFootMotion.easing)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .clickable(onClick = onToggleDrawer),
            )
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInHorizontally(
                animationSpec = tween(MFootMotion.normal, easing = MFootMotion.easing),
                initialOffsetX = { -it },
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(MFootMotion.fast, easing = MFootMotion.easing),
                targetOffsetX = { -it },
            ),
        ) {
            Menu(
                nickname = nickname,
                clubName = clubName,
                leagueName = title,
                isAdmin = isAdmin,
                route = route,
                // Solo `onNavigate`, senza chiudere il menu a mano.
                //
                // Qui c'era `{ onNavigate(it); onToggleDrawer() }`, e il menu **restava
                // aperto** dopo ogni scelta: `vai()` mette gia' `drawerOpen = false`, e il
                // toggle subito dopo lo rimetteva a true. Un interruttore chiamato su uno
                // stato che qualcun altro ha appena deciso non lo conferma, lo ribalta.
                onNavigate = onNavigate,
                onLeaveLeague = onLeaveLeague,
            )
        }
    }
}

/**
 * La barra blu in cima.
 *
 * Il blu arriva fino allo spigolo dello schermo, barra di stato compresa: e' il pezzo che
 * da' all'app la sua faccia, e fermarlo qualche pixel piu' in basso lo ridurrebbe a una
 * fascia colorata dentro una schermata scura.
 */
@Composable
private fun BarraAlta(
    title: String,
    subtitle: String,
    onMenu: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MFootColors.blue)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconaBarra(MFootIcons.menu, "Menu", onMenu)
        Spacer(Modifier.width(6.dp))

        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MFootType.barTitle,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MFootType.secondary,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconaBarra(MFootIcons.aggiorna, "Aggiorna", onRefresh)
    }
}

/** Un'icona toccabile della barra alta: bianca, con la sua area di tocco tonda. */
@Composable
private fun IconaBarra(icona: ImageVector, descrizione: String, onClick: () -> Unit) {
    Icon(
        icona,
        contentDescription = descrizione,
        tint = Color.White,
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(10.dp),
    )
}

/**
 * Il nastro scuro sotto la barra blu: dove sei dentro la lega, e quanto e' fresco.
 *
 * ## Perche' l'orologio sta qui e non e' una riga sua
 *
 * Perche' un aggiornamento silenzioso e un aggiornamento rotto, da fuori, sono la stessa
 * cosa: in entrambi i casi non succede niente. Con l'app che non si aggiornava mai, il
 * proprietario di questa lega ha passato giorni a chiedersi se l'amico stesse giocando
 * davvero. Un numero che sale dice «sto guardando», e quando smette di salire dice
 * «qualcosa non va» — che e' la meta' del valore di tutto il meccanismo.
 *
 * Prima era una riga tutta sua, alta quanto questa e con dentro una sola informazione.
 * Nel riferimento quella riga porta il nome della competizione, e le due cose ci stanno
 * insieme: a sinistra dove sei, a destra da quanto.
 */
@Composable
private fun Nastro(dove: String, quando: java.time.Instant?, onRefresh: () -> Unit) {
    // Un orologio che batte da solo: senza, la scritta direbbe «2s fa» per mezz'ora,
    // che e' peggio di non scriverla.
    var adesso by remember { mutableStateOf(java.time.Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            adesso = java.time.Instant.now()
        }
    }

    val testo = when {
        quando == null -> "in attesa"
        else -> {
            val secondi = java.time.Duration.between(quando, adesso).seconds.coerceAtLeast(0)
            when {
                secondi < 10 -> "aggiornato adesso"
                secondi < 60 -> "aggiornato ${secondi}s fa"
                secondi < 3600 -> "aggiornato ${secondi / 60}min fa"
                else -> "aggiornato ${secondi / 3600}h fa"
            }
        }
    }

    // Oltre due minuti qualcosa non gira: l'orologio batte ogni trenta secondi, quindi
    // quattro giri saltati di fila non sono un caso.
    val vecchio = quando != null &&
        java.time.Duration.between(quando, adesso).seconds > 120

    Row(
        Modifier
            .fillMaxWidth()
            .background(MFootColors.bar)
            .clickable(onClick = onRefresh)
            .padding(horizontal = MFootSpacing.section, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            dove,
            style = MFootType.rowTitle,
            color = MFootColors.elite,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            testo,
            style = MFootType.chip,
            color = if (vecchio) MFootColors.gamble else MFootColors.ink3,
        )
    }
}

/**
 * I cinque posti in basso.
 *
 * Cinque e non sette: oltre, le etichette si accorciano fino a diventare indovinelli e
 * le zone toccabili scendono sotto il polpastrello.
 *
 * L'ordine e le icone sono quelli del riferimento — casa, maglia, calendario, medaglia —
 * fino al quinto, che li' e' «Video» e qui non puo' esserlo: MFoot non ha filmati, ha un
 * mercato, e un posto vuoto in barra vale meno di un posto che serve.
 */
@Composable
private fun BarraPosti(route: Route, onNavigate: (Route) -> Unit) {
    Hairline()
    Row(
        Modifier
            .fillMaxWidth()
            .background(MFootColors.bar)
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
    ) {
        POSTI.forEach { posto ->
            // Acceso sul **posto**, non sulla scheda: passando da Rosa a Campo si resta
            // dentro "Squadra", e la barra deve dirlo. Con un confronto secco la voce si
            // spegnerebbe al primo chip toccato, e sembrerebbe di essere usciti.
            val acceso = posto.route.samePlace(route)
            val tinta = if (acceso) MFootColors.blue else MFootColors.ink3
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onNavigate(posto.route) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    posto.icona,
                    contentDescription = null,
                    tint = tinta,
                    modifier = Modifier.size(25.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    posto.label,
                    style = MFootType.tab,
                    color = tinta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class Posto(val route: Route, val label: String, val icona: ImageVector)

private val POSTI = listOf(
    Posto(Route.Casa, "Casa", MFootIcons.casa),
    Posto(Route.Squadra(), "Squadra", MFootIcons.maglia),
    Posto(Route.Calendario, "Calendario", MFootIcons.calendario),
    Posto(Route.Lega(), "Classifica", MFootIcons.medaglia),
    Posto(Route.Mercato(), "Mercato", MFootIcons.carrello),
)

/**
 * Il menu laterale.
 *
 * ## I tre gruppi
 *
 * Sono quelli del riferimento — **Setup**, **Gioca**, **Gestione** — e il combaciare non
 * e' una coincidenza cercata: sono le tre domande che si fa chi apre un menu di lega
 * («com'e' configurata», «cosa posso fare adesso», «cosa amministro»), e nel riferimento
 * hanno gia' la stessa risposta.
 *
 * ## «Profilo lega» e «Partecipanti» stanno in Setup ma le vedono tutti
 *
 * Nel riferimento Setup e' un gruppo da amministratore. Qui no, e non per distrazione:
 * quelle due schermate non configurano niente, **raccontano** — che lega e' questa, chi
 * c'e' dentro, chi si e' iscritto e non ha ancora fondato. Sono precisamente le due a cui
 * serve rispondere quando un amico dice «io ti vedo e tu no», e l'amico in questione quasi
 * mai e' l'amministratore. Il gruppo resta dov'e' per somiglianza; il filtro si applica
 * alle quattro voci sotto, che configurano davvero.
 */
@Composable
private fun Menu(
    nickname: String,
    clubName: String?,
    leagueName: String,
    isAdmin: Boolean,
    route: Route,
    onNavigate: (Route) -> Unit,
    onLeaveLeague: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(302.dp)
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        // La testata a gradiente: chi sei, e la via d'uscita.
        Row(
            Modifier
                .fillMaxWidth()
                .background(MFootColors.drawerHeader)
                .statusBarsPadding()
                .padding(horizontal = MFootSpacing.section, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    nickname,
                    style = MFootType.playerName,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(clubName ?: "nessun club")
                        if (isAdmin) append(" · amministratore")
                    },
                    style = MFootType.secondary,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(10.dp))

            // «Cambia lega», non «Esci».
            //
            // Il gesto e' sempre stato questo — la lega resta dov'e', il club pure, e ci
            // si rientra col codice — ma scritto in rosso in fondo al menu sembrava una
            // cancellazione, quindi non lo toccava nessuno. Ed era l'unico modo che aveva
            // chi si era ritrovato nella lega sbagliata di uscirne.
            Text(
                "CAMBIA LEGA",
                style = MFootType.label,
                color = Color.White,
                modifier = Modifier
                    .clip(MFootShapes.pill)
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable(onClick = onLeaveLeague)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // Quale lega si sta guardando, e la porta per cambiarla.
        Row(
            Modifier
                .fillMaxWidth()
                .background(MFootColors.core)
                .clickable { onNavigate(Route.MieLeghe) }
                .padding(horizontal = MFootSpacing.section, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                leagueName,
                style = MFootType.playerName,
                color = MFootColors.elite,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MFootColors.blue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MFootIcons.piu,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Gruppo("Setup")
        Voce("Profilo lega", MFootIcons.scudo, Route.ProfiloLega, route, onNavigate)
        Voce("Partecipanti", MFootIcons.persona, Route.Partecipanti, route, onNavigate)
        if (isAdmin) {
            Voce("Regolamento e opzioni", MFootIcons.documento, Route.Opzioni, route, onNavigate)
            Voce("Competizioni", MFootIcons.coppa, Route.Competizioni, route, onNavigate)
            // Le divisioni sono una sezione del regolamento, non una schermata a se': la
            // voce di menu ci porta dritto invece di far cercare la riga giusta in un
            // elenco di sette. Due porte per la stessa stanza vanno bene; due stanze che
            // configurano la stessa cosa, no.
            Voce(
                "Gestione divisioni",
                MFootIcons.divisioni,
                Route.Regolamento(SettingsSection.DIVISIONI),
                route,
                onNavigate,
            )
            Voce("Mercati", MFootIcons.carrello, Route.Mercati, route, onNavigate)
        }

        Gruppo("Gioca")
        // Il listino prima degli svincolati: dal 2026-08-24 e' il modo normale di
        // comprare, e una voce di menu che non c'e' e' una funzionalita' che non c'e'.
        Voce(
            "Listino",
            MFootIcons.carrello,
            Route.Mercato(TabMercato.LISTINO),
            route,
            onNavigate,
        )
        Voce(
            "Svincolati",
            MFootIcons.cartellino,
            Route.Mercato(TabMercato.SVINCOLATI),
            route,
            onNavigate,
        )
        Voce("Listone", MFootIcons.persone, Route.Mercato(TabMercato.LISTONE), route, onNavigate)
        Voce(
            "Infermeria",
            MFootIcons.croce,
            Route.Squadra(TabSquadra.INFERMERIA),
            route,
            onNavigate,
        )
        Voce("Registro attività", MFootIcons.archivio, Route.RegistroAdmin, route, onNavigate)

        Gruppo("Gestione")
        Voce("Obiettivi e premi", MFootIcons.stella, Route.Obiettivi, route, onNavigate)
        Voce("Le mie leghe", MFootIcons.pianeta, Route.MieLeghe, route, onNavigate)

        Spacer(Modifier.height(20.dp))
        Hairline()

        // La versione, scritta dove si vede senza cercarla.
        //
        // Non e' vanita': senza, non c'e' modo di sapere se l'APK sul telefono contiene una
        // correzione o e' quello della settimana scorsa, e si finisce per segnalare difetti
        // gia' risolti guardando una build vecchia.
        Text(
            "MFoot ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MFootType.chip,
            color = MFootColors.ink3,
            modifier = Modifier.padding(MFootSpacing.section, 14.dp),
        )
        Spacer(Modifier.height(20.dp).navigationBarsPadding())
    }
}

/**
 * L'intestazione di un gruppo: una pillola scura con la scritta in lavanda.
 *
 * Era un'etichetta maiuscola grigia. La pillola costa gli stessi due elementi e fa una
 * cosa che l'etichetta non faceva: si vede **mentre si scorre**, quindi si capisce in
 * quale gruppo si e' senza risalire in cima.
 */
@Composable
private fun Gruppo(label: String) {
    Text(
        label,
        style = MFootType.rowTitle,
        color = MFootColors.elite,
        modifier = Modifier
            .padding(MFootSpacing.section, 18.dp, MFootSpacing.section, 8.dp)
            .clip(MFootShapes.pill)
            .background(MFootColors.core)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Una voce del menu.
 *
 * ## Il confronto e' esatto, non per classe
 *
 * Prima bastava la classe, perche' ogni voce portava a una rotta diversa. Adesso tre voci
 * — Svincolati, Listone, Infermeria — portano tutte a una `Mercato` o a una `Squadra` con
 * la scheda giusta dentro: con il confronto per classe si accenderebbero **tutte e tre
 * insieme** appena si apre una qualsiasi delle due, e il menu direbbe di essere in tre
 * posti contemporaneamente.
 *
 * L'eccezione e' `Regolamento`, che porta un dato con se' e cambia sezione restando la
 * stessa voce: li' l'uguaglianza secca la spegnerebbe al primo passaggio di sezione.
 */
@Composable
private fun Voce(
    label: String,
    icona: ImageVector,
    target: Route,
    current: Route,
    onNavigate: (Route) -> Unit,
) {
    val acceso = when {
        target is Route.Regolamento && current is Route.Regolamento ->
            target.sezione == current.sezione
        else -> target == current
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (acceso) MFootColors.elite.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { onNavigate(target) }
            .padding(horizontal = MFootSpacing.section, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            icona,
            contentDescription = null,
            tint = if (acceso) MFootColors.elite else MFootColors.blue,
            modifier = Modifier.size(23.dp),
        )
        Text(
            label,
            style = MFootType.rowTitle,
            color = if (acceso) MFootColors.elite else MFootColors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
