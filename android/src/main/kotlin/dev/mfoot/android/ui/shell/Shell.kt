package dev.mfoot.android.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.BuildConfig
import dev.mfoot.android.app.Route
import dev.mfoot.android.app.SettingsSection
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootMotion
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
 * creare competizioni. La barra in basso porta le quattro o cinque a cui si torna dieci
 * volte al giorno. Mescolarle vorrebbe dire cercare la classifica in un menu a scomparsa.
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
    drawerOpen: Boolean,
    onToggleDrawer: () -> Unit,
    onNavigate: (Route) -> Unit,
    onLeaveLeague: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MFootColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, subtitle, onToggleDrawer)

            Box(Modifier.weight(1f)) { content() }

            // La barra in basso e' l'ultimo elemento della colonna, non sovrapposta: cosi'
            // il contenuto non le finisce mai sotto e non serve nessun margine inventato.
            TabBar(route, onNavigate)
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
                    .background(MFootColors.bg.copy(alpha = 0.72f))
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
            Drawer(
                nickname = nickname,
                clubName = clubName,
                isAdmin = isAdmin,
                route = route,
                onNavigate = { onNavigate(it); onToggleDrawer() },
                onLeaveLeague = onLeaveLeague,
            )
        }
    }
}

@Composable
private fun TopBar(title: String, subtitle: String, onMenu: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MFootColors.core)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tre righe disegnate invece di un carattere: il glifo dell'hamburger cambia
        // spessore da un telefono all'altro secondo il font di sistema.
        Column(
            Modifier
                .width(34.dp)
                .clickable(onClick = onMenu)
                .padding(vertical = 4.dp),
        ) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .background(MFootColors.ink),
                )
                if (index < 2) Spacer(Modifier.height(5.dp))
            }
        }

        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = MFootType.chip, color = MFootColors.ink3)
        }
    }
    Hairline()
}

/**
 * Le cinque voci in basso.
 *
 * Cinque e non sette: oltre, le etichette si accorciano fino a diventare indovinelli e
 * le zone toccabili scendono sotto il polpastrello.
 */
@Composable
private fun TabBar(route: Route, onNavigate: (Route) -> Unit) {
    Hairline()
    Row(Modifier.fillMaxWidth().background(MFootColors.core)) {
        TABS.forEach { tab ->
            val selected = tab.route == route
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onNavigate(tab.route) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tab.glyph,
                    style = MFootType.value,
                    color = if (selected) MFootColors.elite else MFootColors.ink3,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label,
                    style = MFootType.label,
                    color = if (selected) MFootColors.elite else MFootColors.ink3,
                )
            }
        }
    }
}

private data class Tab(val route: Route, val label: String, val glyph: String)

private val TABS = listOf(
    Tab(Route.Dashboard, "Casa", "⌂"),
    Tab(Route.Squadre, "Squadre", "⛨"),
    Tab(Route.Calendario, "Calendario", "▦"),
    Tab(Route.Classifica, "Classifica", "≡"),
    Tab(Route.Campo, "Campo", "⬢"),
)

@Composable
private fun Drawer(
    nickname: String,
    clubName: String?,
    isAdmin: Boolean,
    route: Route,
    onNavigate: (Route) -> Unit,
    onLeaveLeague: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(298.dp)
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MFootColors.core)
                .padding(MFootSpacing.section),
        ) {
            Text(nickname, style = MFootType.playerName, color = MFootColors.ink)
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    append(clubName ?: "nessun club")
                    if (isAdmin) append(" · amministratore")
                },
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
        }

        if (isAdmin) {
            Section("Setup")
            Item("Profilo lega", Route.ProfiloLega, route, onNavigate)
            Item("Partecipanti", Route.Partecipanti, route, onNavigate)
            Item("Regolamento e opzioni", Route.Opzioni, route, onNavigate)
            Item("Competizioni", Route.Competizioni, route, onNavigate)
            // Le divisioni sono una sezione del regolamento, non una schermata a se': la
            // voce di menu ci porta dritto invece di far cercare la riga giusta in un
            // elenco di sette. Due porte per la stessa stanza vanno bene; due stanze che
            // configurano la stessa cosa, no.
            Item(
                "Divisioni",
                Route.Regolamento(SettingsSection.DIVISIONI),
                route,
                onNavigate,
                selected = route is Route.Regolamento && route.sezione == SettingsSection.DIVISIONI,
            )
            Item("Mercati", Route.Mercati, route, onNavigate)
        }

        Section("Gioca")
        Item("Aste", Route.Aste, route, onNavigate)
        Item("Scambi", Route.Scambi, route, onNavigate)
        Item("Svincolati", Route.Svincolati, route, onNavigate)
        Item("Listone", Route.Listone, route, onNavigate)
        Item("Infermeria", Route.Infermeria, route, onNavigate)
        Item("Registro attivita'", Route.RegistroAdmin, route, onNavigate)

        Spacer(Modifier.height(24.dp))
        Hairline()
        Text(
            "Esci dalla lega",
            style = MFootType.rowTitle,
            color = MFootColors.gamble,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onLeaveLeague)
                .padding(MFootSpacing.section, 15.dp),
        )

        // La versione, scritta dove si vede senza cercarla.
        //
        // Non e' vanita': senza, non c'e' modo di sapere se l'APK sul telefono contiene una
        // correzione o e' quello della settimana scorsa, e si finisce per segnalare difetti
        // gia' risolti guardando una build vecchia.
        Text(
            "MFoot ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MFootType.chip,
            color = MFootColors.ink3,
            modifier = Modifier.padding(MFootSpacing.section, 14.dp, MFootSpacing.section, 0.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Section(label: String) {
    Text(
        label.uppercase(),
        style = MFootType.label,
        color = MFootColors.ink3,
        modifier = Modifier.padding(MFootSpacing.section, 18.dp, MFootSpacing.section, 6.dp),
    )
}

/**
 * Una voce del menu.
 *
 * Il confronto per classe e non per uguaglianza: `Regolamento(SQUADRE)` e
 * `Regolamento(ECONOMIA)` sono la stessa voce di menu, e vederla spegnersi passando da una
 * sezione all'altra farebbe pensare di essere usciti.
 */
@Composable
private fun Item(
    label: String,
    target: Route,
    current: Route,
    onNavigate: (Route) -> Unit,
    /**
     * Da passare solo dove il confronto per classe non basta.
     *
     * "Regolamento e opzioni" e "Divisioni" portano entrambe a una [Route.Regolamento], e
     * per classe si accenderebbero insieme.
     */
    selected: Boolean = target::class == current::class,
) {
    Text(
        label,
        style = MFootType.rowTitle,
        color = if (selected) MFootColors.elite else MFootColors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MFootColors.elite.copy(alpha = 0.07f) else MFootColors.bg)
            .clickable { onNavigate(target) }
            .padding(MFootSpacing.section, 12.dp),
    )
}
