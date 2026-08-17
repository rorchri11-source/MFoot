package dev.mfoot.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.AuctionRow
import dev.mfoot.android.app.LineupEdit
import dev.mfoot.android.app.ListScope
import dev.mfoot.android.app.PlayerRow
import dev.mfoot.android.app.RoleFilter
import dev.mfoot.android.app.Route
import dev.mfoot.android.app.SettingsEdit
import dev.mfoot.android.ui.settings.SettingsIndexScreen
import dev.mfoot.android.ui.settings.SettingsScreen
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.PlayerListScreen
import dev.mfoot.android.ui.screens.CampoScreen
import dev.mfoot.android.ui.screens.DashboardScreen
import dev.mfoot.android.ui.screens.SquadreScreen
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType

/**
 * Da rotta a schermata.
 *
 * Un `when` esaustivo e non una mappa: se domani si aggiunge una rotta e si dimenticasse
 * di collegarla, il compilatore lo dice subito invece di lasciare una voce di menu che
 * apre il vuoto.
 */
@Composable
fun Router(
    state: AppState.Dentro,
    onNavigate: (Route) -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (RoleFilter) -> Unit,
    onScope: (ListScope) -> Unit,
    onSelect: (PlayerRow) -> Unit,
    onOpenBid: (AuctionRow) -> Unit,
    onRefreshAuctions: () -> Unit,
    onFoundClub: () -> Unit,
    onDismissNotice: () -> Unit,
    settings: SettingsEdit,
    onConfigChange: (LeagueConfig) -> Unit,
    onConfigSave: () -> Unit,
    lineup: LineupEdit,
    onLineupChange: (LineupEdit) -> Unit,
    onLineupSave: () -> Unit,
) {
    when (val route = state.route) {
        is Route.Dashboard -> DashboardScreen(state, onNavigate, onFoundClub, onDismissNotice)

        is Route.Squadre -> SquadreScreen(state) { clubId -> onNavigate(Route.Rosa(clubId)) }

        // Il mercato e le liste sono la stessa schermata con un ambito diverso: le regole
        // di ricerca, filtro e riga sono identiche, e duplicarla produrrebbe due liste che
        // divergono al primo ritocco.
        is Route.Svincolati -> Lista(state, ListScope.SVINCOLATI, onQuery, onFilter, onScope, onSelect, onOpenBid, onRefreshAuctions, onDismissNotice)
        is Route.Listone -> Lista(state, ListScope.TUTTI, onQuery, onFilter, onScope, onSelect, onOpenBid, onRefreshAuctions, onDismissNotice)
        is Route.Aste -> Lista(state, ListScope.ASTE, onQuery, onFilter, onScope, onSelect, onOpenBid, onRefreshAuctions, onDismissNotice)
        is Route.Rosa -> Lista(state, ListScope.MIA_ROSA, onQuery, onFilter, onScope, onSelect, onOpenBid, onRefreshAuctions, onDismissNotice)

        is Route.Infermeria -> Infermeria(state)

        // Queste tre hanno gia' una schermata propria, aperta a schermo pieno dal
        // contenitore: qui non devono comparire due volte.
        is Route.Classifica, is Route.Calendario, is Route.Competizioni ->
            DaFare(route.label, "Si apre da qui a schermo pieno.")

        is Route.Campo -> CampoScreen(state, lineup, onLineupChange, onLineupSave)

        is Route.ProfiloLega -> DaFare("Profilo lega", "Nome, codice, stato, giornata.")
        is Route.Partecipanti -> DaFare("Partecipanti", "Chi c'e', con quale club, chi e' admin.")

        is Route.Opzioni -> SettingsIndexScreen(state.lega.league.isAdmin) {
            onNavigate(Route.Regolamento(it))
        }

        is Route.Regolamento -> SettingsScreen(
            section = route.sezione,
            config = settings.bozza ?: state.lega.league.config,
            canEdit = state.lega.league.isAdmin,
            dirty = settings.dirty,
            busy = settings.busy,
            errore = settings.errore,
            onChange = onConfigChange,
            onSave = onConfigSave,
        )
        is Route.Mercati -> DaFare("Mercati", "Finestre di mercato e asta iniziale.")
        is Route.RegistroAdmin -> DaFare("Registro", "Cosa ha fatto il tick, giro per giro.")

        is Route.Giocatore, is Route.Offerta -> Box(Modifier.fillMaxSize())
    }
}

@Composable
private fun Lista(
    state: AppState.Dentro,
    scope: ListScope,
    onQuery: (String) -> Unit,
    onFilter: (RoleFilter) -> Unit,
    onScope: (ListScope) -> Unit,
    onSelect: (PlayerRow) -> Unit,
    onOpenBid: (AuctionRow) -> Unit,
    onRefreshAuctions: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    // L'ambito lo impone la rotta: chi entra da "Svincolati" deve vedere gli svincolati,
    // non l'ultimo filtro che aveva lasciato attivo la volta prima.
    val forzato = state.copy(browse = state.browse.copy(scope = scope))

    PlayerListScreen(
        state = forzato,
        onQuery = onQuery,
        onFilter = onFilter,
        onScope = onScope,
        onSelect = onSelect,
        onDismissNotice = onDismissNotice,
        onOpenBid = onOpenBid,
        onRefreshAuctions = onRefreshAuctions,
    )
}

/**
 * Gli infortunati della propria rosa.
 *
 * Sta in una schermata sua e non come filtro della lista perche' la domanda e' diversa:
 * non "chi posso schierare" ma "quando torna". L'unica colonna che conta e' il rientro.
 */
@Composable
private fun Infermeria(state: AppState.Dentro) {
    val club = state.lega.myClub
    val giornata = state.lega.league.currentMatchDay
    val infortunati = club
        ?.let { state.lega.squadOf(it.id) }
        ?.filter { p -> p.injuredUntil?.let { it.value >= giornata } == true }
        .orEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .padding(MFootSpacing.section),
    ) {
        if (club == null) {
            Vuoto("Non hai ancora un club.")
            return@Column
        }
        if (infortunati.isEmpty()) {
            Vuoto("Nessun infortunato. Tutti disponibili.")
            return@Column
        }

        Label("${infortunati.size} indisponibili")
        Spacer(Modifier.height(12.dp))
        infortunati.forEach { p ->
            Text(
                "${p.shortName} · ${p.primaryPosition.short} · rientra alla giornata " +
                    "${p.injuredUntil?.value}",
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Vuoto(testo: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(testo, style = MFootType.secondary, color = MFootColors.ink3)
    }
}

/**
 * Una schermata ancora da scrivere, che dice **cosa** ci sara'.
 *
 * Meglio di una voce di menu che non fa niente: chi la tocca capisce che il posto esiste e
 * cosa conterra', invece di chiedersi se ha sbagliato a premere.
 */
@Composable
private fun DaFare(titolo: String, cosa: String) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(titolo, style = MFootType.playerName, color = MFootColors.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(cosa, style = MFootType.secondary, color = MFootColors.ink3, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Label("Non ancora scritta")
    }
}
