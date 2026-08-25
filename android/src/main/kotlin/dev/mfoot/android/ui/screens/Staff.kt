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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.StaffState
import dev.mfoot.android.data.StaffMember
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.bandiera
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType

/**
 * Chi lavora per te, e chi e' libero.
 *
 * ## Perche' lo staff conta
 *
 * I tre ruoli agganciano tre sistemi diversi, e i numeri non sono decorativi: un allenatore
 * da cinque stelle fa crescere i giocatori **tre volte** piu' di uno da una, un preparatore
 * decide se puoi turnare o se bruci la rosa, un osservatore e' l'unico modo di trovare un
 * giovane. Per questo la riga dice il moltiplicatore vero invece di "migliora la crescita".
 *
 * ## Perche' l'interruttore cambia tutto
 *
 * Ogni squadra ha il suo staff. La scelta interessante e' proprio quella: l'allenatore
 * bravo non si puo' mettere su tutte e due, e metterlo sulla Primavera vuol dire far
 * crescere i ragazzi rinunciando a farlo con la prima squadra.
 */
@Composable
fun StaffScreen(
    state: AppState.Dentro,
    staff: StaffState,
    onCarica: () -> Unit,
    onSposta: (Long, Long) -> Unit,
    onAsta: (Long) -> Unit,
    /** Assume subito chi e' sul listino, al prezzo scritto. */
    onCompra: (Long, Int) -> Unit = { _, _ -> },
    /** Mette in vendita un proprio membro dello staff. */
    onVendi: (Long, Int) -> Unit = { _, _ -> },
) {
    val club = state.clubMostrato
    if (club == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Prima serve un club.", style = MFootType.secondary, color = MFootColors.ink3)
        }
        return
    }

    LaunchedEffect(state.lega.league.id) { onCarica() }

    val miei = staff.di(club.id)
    val altroClub = if (state.guardoLaPrimavera) state.lega.myClub else state.lega.myYouthClub
    val budgetIniziale = state.lega.league.config.economy.startingCredits

    Column(
        Modifier.fillMaxSize().background(MFootColors.bg).verticalScroll(rememberScrollState()),
    ) {
        staff.errore?.let {
            Box(Modifier.padding(MFootSpacing.section)) { Notice(it, MFootColors.gamble) }
        }
        staff.avviso?.let {
            Box(Modifier.padding(MFootSpacing.section)) { Notice(it, MFootColors.elite) }
        }

        Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 8.dp)) {
            Label("${club.shortName} · ${miei.size} in organico")
        }

        if (miei.isEmpty()) {
            Vuoto(
                "Nessuno. Senza allenatore i tuoi crescono al minimo, e senza osservatori " +
                    "non puoi cercare giovani.",
            )
        }

        miei.forEach { membro ->
            Riga(membro) {
                altroClub?.let { altra ->
                    Azione("→ ${altra.shortName}") { onSposta(membro.id, altra.id) }
                }

                // Dal 2026-08-24 lo staff si puo' anche cedere: sta sul listino come i
                // giocatori, con la stessa regola. Prima l'unica azione era spostarlo fra
                // le proprie due squadre, e un allenatore preso all'asta restava tuo per
                // sempre anche quando ne trovavi uno migliore.
                //
                // Il prezzo e' proporzionale alle stelle sul budget della lega: lo staff
                // non ha un valore di mercato come i giocatori, e chiedere un numero a
                // mano su una schermata che si scorre sarebbe un modulo in piu' per una
                // decisione che quasi nessuno vuole rifinire.
                val prezzo = (budgetIniziale / 40) * membro.stars
                if (staff.prezzoDi(membro.id) == null) {
                    Azione("Vendi · $prezzo") { onVendi(membro.id, prezzo.coerceAtLeast(1)) }
                } else {
                    Azione("In vendita") { }
                }
            }
        }

        if (staff.liberi.isNotEmpty()) {
            Spacer(Modifier.height(MFootSpacing.section))
            Column(Modifier.padding(MFootSpacing.section, 0.dp, MFootSpacing.section, 8.dp)) {
                Label("Liberi · ${staff.liberi.size}")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Si prendono all'asta, come i giocatori. Chi apre l'asta non ha nessun " +
                        "vantaggio: paga come tutti gli altri.",
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                )
            }

            staff.liberi.take(40).forEach { membro ->
                val prezzo = staff.prezzoDi(membro.id)
                Riga(membro) {
                    if (prezzo != null) {
                        // Sul listino si assume subito, come per i giocatori. Senza la
                        // finestra di contestazione: un preparatore in piu' non ribalta
                        // una stagione, e dodici ore d'attesa su ogni assunzione
                        // renderebbero lo staff piu' faticoso dei giocatori.
                        Azione("Assumi · $prezzo") { onCompra(membro.id, prezzo) }
                    } else {
                        Azione("All'asta") { onAsta(membro.id) }
                    }
                }
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Riga(membro: StaffMember, azioni: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(MFootSpacing.section, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bandiera(membro.nationality), style = MFootType.chip)
                Spacer(Modifier.width(6.dp))
                Text(
                    membro.shortName,
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${membro.roleLabel} · ${membro.effetto}",
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
        }

        Text(
            "★".repeat(membro.stars),
            style = MFootType.chip,
            color = when {
                membro.stars >= 5 -> MFootColors.elite
                membro.stars >= 4 -> MFootColors.good
                membro.stars >= 3 -> MFootColors.ink2
                else -> MFootColors.ink3
            },
        )
        Spacer(Modifier.width(10.dp))
        azioni()
    }
    Hairline()
}

@Composable
private fun Azione(testo: String, onClick: () -> Unit) {
    Text(
        testo,
        style = MFootType.chip,
        color = MFootColors.ink2,
        modifier = Modifier
            .background(MFootColors.core, MFootShapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

@Composable
private fun Vuoto(testo: String) {
    Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
        Text(testo, style = MFootType.secondary, color = MFootColors.ink3, textAlign = TextAlign.Center)
    }
}

/**
 * Gli osservatori e i loro viaggi.
 *
 * ## Perche' sta sotto Mercato e non sotto Squadra
 *
 * Perche' una missione **e' un modo di comprare**: gli under 20 non passano dalle aste, e
 * questa e' l'unica porta da cui entrano. Metterla accanto allo staff sarebbe corretto per
 * l'organigramma e sbagliato per la domanda che ci si fa aprendola, che e' "dove trovo un
 * attaccante".
 */
@Composable
fun OsservatoriScreen(
    state: AppState.Dentro,
    staff: StaffState,
    onCarica: () -> Unit,
    onManda: (Long, String, String) -> Unit,
) {
    val club = state.lega.myClub
    if (club == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Prima serve un club.", style = MFootType.secondary, color = MFootColors.ink3)
        }
        return
    }

    LaunchedEffect(state.lega.league.id) { onCarica() }

    val osservatori = staff.osservatoriDi(club.id) +
        staff.osservatoriDi(state.lega.myYouthClub?.id)
    val paesi = state.lega.league.config.world.nationalities
    val ruoli = dev.mfoot.core.model.Position.entries

    Column(
        Modifier.fillMaxSize().background(MFootColors.bg).verticalScroll(rememberScrollState()),
    ) {
        staff.errore?.let {
            Box(Modifier.padding(MFootSpacing.section)) { Notice(it, MFootColors.gamble) }
        }
        staff.avviso?.let {
            Box(Modifier.padding(MFootSpacing.section)) { Notice(it, MFootColors.elite) }
        }

        Column(Modifier.padding(MFootSpacing.section)) {
            Label("Osservatori · ${osservatori.size} su 5")
            Spacer(Modifier.height(4.dp))
            Text(
                "Sotto i vent'anni non si compra: si trova. Mandane uno in un paese a " +
                    "cercare un ruolo, e aspetta. Più stelle ha, più in fretta torna e " +
                    "meglio sceglie.",
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
        }

        if (osservatori.isEmpty()) {
            Vuoto("Nessun osservatore. Prendine uno all'asta dalla scheda Staff.")
            Spacer(Modifier.height(30.dp))
            return@Column
        }

        osservatori.forEach { scout ->
            val missione = staff.missioneDi(scout.id)

            Column(Modifier.fillMaxWidth().padding(MFootSpacing.section, 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bandiera(scout.nationality), style = MFootType.chip)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        scout.shortName,
                        style = MFootType.rowTitle,
                        color = MFootColors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "★".repeat(scout.stars),
                        style = MFootType.chip,
                        color = if (scout.stars >= 4) MFootColors.elite else MFootColors.ink3,
                    )
                }

                if (missione != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "In ${missione.country}, cerca un ${missione.position} · " +
                            missione.quando(java.time.Instant.now()),
                        style = MFootType.chip,
                        color = MFootColors.gamble,
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Missione(paesi, ruoli.map { it.short to it.name }) { paese, ruolo ->
                        onManda(scout.id, paese, ruolo)
                    }
                }
            }
            Hairline()
        }

        Spacer(Modifier.height(30.dp))
    }
}

/**
 * La scelta a due passi: prima il paese, poi il ruolo.
 *
 * Dieci paesi per dodici ruoli sono centoventi combinazioni. Metterle tutte a schermo
 * vorrebbe dire una parete di pulsanti in cui non si trova niente; due passi sono due
 * tocchi e nessuna parete.
 *
 * Il paese scelto resta acceso durante il secondo passo: senza, si arriva a scegliere il
 * ruolo senza piu' ricordare dove lo si stava mandando.
 */
@Composable
private fun Missione(
    paesi: List<String>,
    ruoli: List<Pair<String, String>>,
    onManda: (String, String) -> Unit,
) {
    var paese by remember { mutableStateOf<String?>(null) }
    val scelto = paese

    if (scelto == null) {
        Text("Dove lo mandi", style = MFootType.label, color = MFootColors.ink3)
        Spacer(Modifier.height(6.dp))
        Pulsanti(paesi.map { it to it }) { paese = it }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${bandiera(scelto)} $scelto", style = MFootType.chip, color = MFootColors.elite)
        Spacer(Modifier.width(8.dp))
        Text(
            "cambia",
            style = MFootType.chip,
            color = MFootColors.ink3,
            modifier = Modifier.clickable { paese = null },
        )
    }
    Spacer(Modifier.height(6.dp))
    Text("Che ruolo cerca", style = MFootType.label, color = MFootColors.ink3)
    Spacer(Modifier.height(6.dp))
    Pulsanti(ruoli) { onManda(scelto, it) }
}

/** Una griglia di pulsanti a tre per riga: etichetta da mostrare, valore da restituire. */
@Composable
private fun Pulsanti(voci: List<Pair<String, String>>, onScegli: (String) -> Unit) {
    Column {
        voci.chunked(3).forEach { riga ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                riga.forEach { (etichetta, valore) ->
                    Text(
                        etichetta,
                        style = MFootType.chip,
                        color = MFootColors.ink2,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .background(MFootColors.core, MFootShapes.field)
                            .clickable { onScegli(valore) }
                            .padding(vertical = 9.dp),
                    )
                }
                repeat(3 - riga.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
