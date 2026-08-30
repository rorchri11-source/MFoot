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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.StaffState
import dev.mfoot.android.data.StaffMember
import dev.mfoot.core.model.StaffRole
import dev.mfoot.core.staff.Cella
import dev.mfoot.core.staff.Celle
import dev.mfoot.core.staff.Posto
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.world.Scouting
import dev.mfoot.android.ui.Chip
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
    /** Assume subito chi e' sul listino, al prezzo scritto. */
    onCompra: (Long, Int) -> Unit = { _, _ -> },
    /** Mette in vendita un proprio membro dello staff. */
    onVendi: (Long, Int) -> Unit = { _, _ -> },
    /** Toglie da una cella senza cedere: resta tuo, in panchina. */
    onPanchina: (Long) -> Unit = {},
) {
    if (state.lega.myClub == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Prima serve un club.", style = MFootType.secondary, color = MFootColors.ink3)
        }
        return
    }

    LaunchedEffect(state.lega.league.id) { onCarica() }

    var negozio by rememberSaveable { mutableStateOf(false) }
    if (negozio) {
        NegozioStaff(state, staff, onCompra) { negozio = false }
        return
    }

    VetrinaStaff(state, staff, onSposta, onVendi, onPanchina) { negozio = true }
}

/**
 * Le nove celle: chi lavora dove.
 *
 * ## Il difetto che chiudono
 *
 * Prima questa schermata era **una lista sola**: i tuoi tre in cima, e sotto `Liberi · 74`
 * con quaranta righe di ruoli mescolati. Per sapere chi avevi bisognava scorrere oltre il
 * rumore, e per cambiare allenatore bisognava assegnarne un altro — il che liberava il
 * vecchio sul mercato per chiunque, perche' possedere e schierare erano la stessa cosa.
 *
 * ## Perche' gli osservatori sono asimmetrici
 *
 * Le quattro celle di allenatori e preparatori **assegnano**: prima squadra o Primavera. Le
 * cinque degli osservatori **raccontano**, perche' non c'e' niente da scegliere — lavorano
 * tutti in Primavera. Ognuna dice cosa sta facendo quell'uomo, ed e' da li' che si guarda
 * una missione.
 */
@Composable
private fun VetrinaStaff(
    state: AppState.Dentro,
    staff: StaffState,
    onSposta: (Long, Long) -> Unit,
    onVendi: (Long, Int) -> Unit,
    onPanchina: (Long) -> Unit,
    onNegozio: () -> Unit,
) {
    val config = state.lega.league.config
    val prima = state.lega.myClub?.id
    val primavera = state.lega.myYouthClub?.id
    val haPrimavera = primavera != null

    var aperta by remember { mutableStateOf<Cella?>(null) }
    var scelto by remember { mutableStateOf<StaffMember?>(null) }

    Column(
        Modifier.fillMaxSize().background(MFootColors.bg).verticalScroll(rememberScrollState()),
    ) {
        staff.errore?.let {
            Box(Modifier.padding(MFootSpacing.section)) { Notice(it, MFootColors.gamble) }
        }
        staff.avviso?.let {
            Box(Modifier.padding(MFootSpacing.section)) { Notice(it, MFootColors.elite) }
        }

        // Il database indietro toglie le celle, non la schermata: e' la ragione per cui la
        // proprieta' si legge a parte.
        if (!staff.celleAttive) {
            Box(Modifier.padding(MFootSpacing.section)) {
                Notice(
                    "Le celle arrivano quando il database e' aggiornato. Intanto lo staff " +
                        "si vede e si compra come prima.",
                    MFootColors.gamble,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("Staff", Modifier.weight(1f))
            Azione("Negozio", onNegozio)
        }

        StaffRole.entries.forEach { role ->
            val celle = Celle.di(role, config)
            Column(Modifier.padding(horizontal = MFootSpacing.section)) {
                Text(
                    intestazione(role),
                    style = MFootType.label,
                    color = MFootColors.ink3,
                )
                Spacer(Modifier.height(6.dp))

                celle.chunked(2).forEach { coppia ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        coppia.forEach { cella ->
                            val clubDellaCella =
                                if (cella.posto == Posto.PRIMA_SQUADRA) prima else primavera
                            val occupanti = staff.di(clubDellaCella).filter { it.role == role.name }
                            val chi = occupanti.getOrNull(cella.indice)

                            CellaStaff(
                                cella = cella,
                                chi = chi,
                                impedimento = Celle.impedimento(cella, haPrimavera),
                                inPanchina = staff.inPanchina(prima, role.name).size,
                                missione = chi?.let { staff.missioneDi(it.id) },
                                modifier = Modifier.weight(1f),
                            ) {
                                if (chi != null) scelto = chi else aperta = cella
                            }
                        }
                        // Riga dispari: la cella sola non deve occupare tutta la larghezza,
                        // o sembrerebbe un'altra cosa.
                        if (coppia.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        aperta?.let { cella ->
            ScegliChi(
                cella = cella,
                candidati = staff.inPanchina(prima, cella.role.name),
                onScegli = { membro ->
                    val dove = if (cella.posto == Posto.PRIMA_SQUADRA) prima else primavera
                    if (dove != null) onSposta(membro.id, dove)
                    aperta = null
                },
                onNegozio = { aperta = null; onNegozio() },
                onChiudi = { aperta = null },
            )
        }

        scelto?.let { membro ->
            CosaFarne(
                membro = membro,
                prezzo = Valuation.staffPrice(membro.stars, state.lega.league.config),
                inVendita = staff.prezzoDi(membro.id) != null,
                onPanchina = { onPanchina(membro.id); scelto = null },
                onVendi = { prezzo -> onVendi(membro.id, prezzo); scelto = null },
                onChiudi = { scelto = null },
            )
        }

        Spacer(Modifier.height(30.dp))
    }
}

private fun intestazione(role: StaffRole): String = when (role) {
    StaffRole.ALLENATORE -> "Allenatori"
    StaffRole.PREPARATORE -> "Preparatori atletici"
    StaffRole.OSSERVATORE -> "Osservatori · lavorano in Primavera"
}

/**
 * Una cella.
 *
 * Alta abbastanza per tre righe e non una di piu': nome, stelle, e una riga di stato. Il
 * vincolo viene dalle proporzioni vere di un telefono, non da una preferenza.
 */
@Composable
private fun CellaStaff(
    cella: Cella,
    chi: StaffMember?,
    impedimento: String?,
    inPanchina: Int,
    missione: dev.mfoot.android.data.ScoutingMission?,
    modifier: Modifier = Modifier,
    onTocco: () -> Unit,
) {
    val chiusa = impedimento != null
    Column(
        modifier
            .height(74.dp)
            .background(if (chi != null) MFootColors.core else MFootColors.bg, MFootShapes.field)
            .border(1.dp, MFootColors.line, MFootShapes.field)
            .clickable(enabled = !chiusa, onClick = onTocco)
            .padding(10.dp),
    ) {
        if (cella.role != StaffRole.OSSERVATORE) {
            Text(
                cella.posto.etichetta,
                style = MFootType.chip,
                color = MFootColors.ink3,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
        }

        when {
            // Il divieto sta scritto sulla tessera, non in un errore dopo il tocco.
            chiusa -> Text(
                impedimento.orEmpty(),
                style = MFootType.chip,
                color = MFootColors.ink3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            chi != null -> {
                Text(
                    chi.shortName,
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "★".repeat(chi.stars),
                    style = MFootType.chip,
                    color = if (chi.stars >= 4) MFootColors.elite else MFootColors.ink2,
                )
                missione?.let {
                    Text(
                        "in ${it.country}",
                        style = MFootType.chip,
                        color = MFootColors.gamble,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            else -> {
                Text("+ Vuota", style = MFootType.secondary, color = MFootColors.ink2)
                // Dice che toccandola si sceglie fra i tuoi, invece di comprare.
                if (inPanchina > 0) {
                    Text(
                        "ne hai $inPanchina liberi",
                        style = MFootType.chip,
                        color = MFootColors.ink3,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Chi mettere in questa cella, fra quelli che possiedi e non stanno lavorando. */
@Composable
private fun ScegliChi(
    cella: Cella,
    candidati: List<StaffMember>,
    onScegli: (StaffMember) -> Unit,
    onNegozio: () -> Unit,
    onChiudi: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(MFootSpacing.section, 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Chi ci metti", Modifier.weight(1f))
            Azione("Chiudi", onChiudi)
        }
        Spacer(Modifier.height(6.dp))

        if (candidati.isEmpty()) {
            Text(
                "Non hai nessun ${etichettaRuolo(cella.role).lowercase()} libero.",
                style = MFootType.secondary,
                color = MFootColors.ink3,
            )
            Spacer(Modifier.height(8.dp))
            Azione("Vai al negozio", onNegozio)
        } else {
            candidati.forEach { membro ->
                Riga(membro) { Azione("Scegli") { onScegli(membro) } }
            }
        }
    }
}

/** Cosa si fa di chi occupa gia' una cella. */
@Composable
private fun CosaFarne(
    membro: StaffMember,
    prezzo: Int,
    inVendita: Boolean,
    onPanchina: () -> Unit,
    onVendi: (Int) -> Unit,
    onChiudi: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(MFootSpacing.section, 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(membro.shortName, Modifier.weight(1f))
            Azione("Chiudi", onChiudi)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${membro.roleLabel} · ${membro.effetto}",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Toglierlo dalla cella non lo cede: resta tuo. Prima non si poteva fare
            // affatto, e l'unico modo di liberare una cella era regalarne il contenuto.
            Azione("Togli dalla cella", onPanchina)
            if (inVendita) {
                Azione("In vendita") { }
            } else {
                Azione("Vendi · $prezzo") { onVendi(prezzo.coerceAtLeast(1)) }
            }
        }
    }
}

/**
 * Il negozio dello staff.
 *
 * ## Perche' una schermata sua
 *
 * Perche' prima lo scaffale stava in fondo alla stessa lista in cui c'era la tua squadra:
 * settantaquattro righe di ruoli mescolati sotto i tuoi tre. Guardare chi hai e cercare chi
 * comprare sono due domande diverse, e in una lista sola vincono le righe piu' numerose.
 *
 * ## Cosa dice prima che tu prema
 *
 * Il tetto (`ne hai 2 su 4`), il divieto degli osservatori se manca la Primavera — sul
 * filtro, non dopo il tocco — e cosa comprano le stelle, che senza il moltiplicatore
 * sarebbero solo simboli.
 */
@Composable
private fun NegozioStaff(
    state: AppState.Dentro,
    staff: StaffState,
    onCompra: (Long, Int) -> Unit,
    onIndietro: () -> Unit,
) {
    val config = state.lega.league.config
    val prima = state.lega.myClub?.id
    val haPrimavera = state.lega.myYouthClub != null
    var ruolo by rememberSaveable { mutableStateOf(StaffRole.ALLENATORE.name) }
    val role = StaffRole.entries.firstOrNull { it.name == ruolo } ?: StaffRole.ALLENATORE

    val scaffale = staff.liberi
        .filter { it.role == role.name }
        .sortedByDescending { it.stars }
    val posseduti = staff.quanti(prima, role.name)
    val divieto = Celle.impedimentoAcquisto(role, posseduti, haPrimavera, config)

    Column(
        Modifier.fillMaxSize().background(MFootColors.bg).verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(MFootSpacing.section, MFootSpacing.section, MFootSpacing.section, 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Azione("‹ Indietro", onIndietro)
            Spacer(Modifier.width(10.dp))
            Label("Negozio", Modifier.weight(1f))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = MFootSpacing.section),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StaffRole.entries.forEach { r ->
                // Il lucchetto sta sul filtro: si vede prima di premere che quella corsia
                // e' chiusa, e perche'.
                val chiuso = r == StaffRole.OSSERVATORE && !haPrimavera
                Chip(
                    label = if (chiuso) "${etichettaBreve(r)} ⚿" else etichettaBreve(r),
                    selected = r == role && !chiuso,
                ) { if (!chiuso) ruolo = r.name }
            }
        }

        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = MFootSpacing.section)) {
            Text(
                divieto ?: "Ne hai $posseduti su ${Celle.tetto(role, config)}",
                style = MFootType.chip,
                color = if (divieto != null) MFootColors.gamble else MFootColors.ink3,
            )
        }
        Spacer(Modifier.height(10.dp))

        if (scaffale.isEmpty()) {
            Vuoto("Lo scaffale di questo ruolo e' vuoto. Si rifornisce a ogni giornata.")
        }

        scaffale.forEach { membro ->
            val prezzo = staff.prezzoDi(membro.id)
                ?: Valuation.staffPrice(membro.stars, config)
            Riga(membro) {
                if (divieto != null) {
                    Text("—", style = MFootType.chip, color = MFootColors.ink3)
                } else {
                    Azione("Assumi · $prezzo") { onCompra(membro.id, prezzo) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Column(Modifier.padding(horizontal = MFootSpacing.section)) {
            Text(
                "Lo scaffale si rinnova a ogni giornata. I quattro e cinque stelle " +
                    "compaiono di rado e non tornano: quando qualcuno li prende, spariscono.",
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

private fun etichettaRuolo(role: StaffRole): String = when (role) {
    StaffRole.ALLENATORE -> "Allenatore"
    StaffRole.PREPARATORE -> "Preparatore"
    StaffRole.OSSERVATORE -> "Osservatore"
}

private fun etichettaBreve(role: StaffRole): String = when (role) {
    StaffRole.ALLENATORE -> "Allenatori"
    StaffRole.PREPARATORE -> "Preparatori"
    StaffRole.OSSERVATORE -> "Oss."
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
            Vuoto("Nessun osservatore. Assumine uno dalla scheda Staff.")
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
                    // Quanto starebbe via, prima di mandarlo.
                    //
                    // Il numero cambia con le stelle, e senza vederlo la scelta fra due
                    // osservatori e' meta' informata. Il conto e' [Scouting.missionMinutes],
                    // in `core`, lo stesso che il server usa per fissare il rientro.
                    val minuti = Scouting.missionMinutes(scout.stars, state.lega.league.config.rules)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (minuti < 60) "Sta via $minuti minuti"
                        else "Sta via ${minuti / 60}h${(minuti % 60).toString().padStart(2, '0')}",
                        style = MFootType.chip,
                        color = MFootColors.ink3,
                    )

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
