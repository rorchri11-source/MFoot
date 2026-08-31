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
import dev.mfoot.android.data.ScoutingMission
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
    onAccetta: (Long, List<Long>) -> Unit = { _, _ -> },
    onRifiuta: (Long) -> Unit = {},
    onRiScouta: (Long, Long, String, String) -> Unit = { _, _, _, _ -> },
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

    VetrinaStaff(
        state, staff, onSposta, onVendi, onPanchina,
        onAccetta, onRifiuta, onRiScouta,
    ) { negozio = true }
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
    onAccetta: (Long, List<Long>) -> Unit,
    onRifiuta: (Long) -> Unit,
    onRiScouta: (Long, Long, String, String) -> Unit,
    onNegozio: () -> Unit,
) {
    val config = state.lega.league.config
    val prima = state.lega.myClub?.id
    val primavera = state.lega.myYouthClub?.id
    val haPrimavera = primavera != null

    var aperta by remember { mutableStateOf<Cella?>(null) }
    var scelto by remember { mutableStateOf<StaffMember?>(null) }
    var rientrato by remember { mutableStateOf<ScoutingMission?>(null) }

    Column(
        Modifier.fillMaxSize().background(MFootColors.bg).verticalScroll(rememberScrollState()),
    ) {
        staff.errore?.let {
            Box(Modifier.padding(MFootSpacing.section)) { Notice(it, MFootColors.gamble) }
        }
        staff.avviso?.let {
            Box(Modifier.padding(MFootSpacing.section)) { Notice(it, MFootColors.elite) }
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

                            val suaMissione = chi?.let { c ->
                                staff.missioni.firstOrNull {
                                    it.staffId == c.id && (it.inCorso || it.daValutare)
                                }
                            }

                            CellaStaff(
                                cella = cella,
                                chi = chi,
                                impedimento = Celle.impedimento(cella, haPrimavera),
                                inPanchina = staff.inPanchina(prima, role.name).size,
                                missione = suaMissione,
                                modifier = Modifier.weight(1f),
                            ) {
                                when {
                                    // Un osservatore tornato apre quello che ha portato:
                                    // e' la domanda che ci si fa toccandolo.
                                    suaMissione?.daValutare == true -> rientrato = suaMissione
                                    chi != null -> scelto = chi
                                    else -> aperta = cella
                                }
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
            val dove = if (cella.posto == Posto.PRIMA_SQUADRA) prima else primavera
            ScegliChi(
                cella = cella,
                candidati = staff.posseduti(prima).filter {
                    it.role == cella.role.name && it.clubId != dove
                },
                onScegli = { membro ->
                    if (dove != null) onSposta(membro.id, dove)
                    aperta = null
                },
                onNegozio = { aperta = null; onNegozio() },
                onChiudi = { aperta = null },
            )
        }

        rientrato?.let { missione ->
            IlRientro(
                missione = missione,
                righe = state.rows.filter { it.player.id.value in missione.trovati },
                onAccetta = { ids -> onAccetta(missione.id, ids); rientrato = null },
                onRifiuta = { onRifiuta(missione.id); rientrato = null },
                onRiScouta = {
                    onRiScouta(missione.id, missione.staffId, missione.country, missione.position)
                    rientrato = null
                },
                onChiudi = { rientrato = null },
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
                        if (it.daValutare) "tornato · guarda" else "in ${it.country}",
                        style = MFootType.chip,
                        color = if (it.daValutare) MFootColors.elite else MFootColors.gamble,
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

/**
 * Cosa ha portato l'osservatore, e cosa se ne fa.
 *
 * ## Perche' esiste
 *
 * Perche' prima il server assegnava d'ufficio: la missione scadeva, il ragazzo finiva in
 * Primavera, e chi giocava scopriva un giocatore in piu' senza averlo scelto.
 *
 * ## Perche' c'e' scritto il potenziale
 *
 * Perche' senza, «accetta o rifiuta» e' una scelta alla cieca — e soprattutto perche' il
 * numero che si vede da solo mente. L'osservatore pesca sul **potenziale**, quindi un
 * cinque stelle riporta il talento piu' forte che esiste, che e' il piu' giovane e quindi
 * il piu' debole di adesso: misurato il 2026-08-30, il miglior ragazzo del mondo generato
 * vale 43 e arrivera' a 88. Segnalato cosi': *«anche se e' un 5 ti porta un 32»*. Non era
 * rotto: era muto.
 *
 * La forbice mostrata e' quella **stimata**, non quella vera: il potenziale vero non lascia
 * mai il server, e un osservatore bravo la stringe. E' la stessa che si vede sul mercato.
 */
@Composable
private fun IlRientro(
    missione: ScoutingMission,
    righe: List<dev.mfoot.android.app.PlayerRow>,
    onAccetta: (List<Long>) -> Unit,
    onRifiuta: () -> Unit,
    onRiScouta: () -> Unit,
    onChiudi: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(MFootSpacing.section, 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Torna dal ${missione.country}", Modifier.weight(1f))
            Azione("Chiudi", onChiudi)
        }
        Spacer(Modifier.height(8.dp))

        if (righe.isEmpty()) {
            Text(
                "I ragazzi trovati non sono ancora arrivati sul telefono. Riapri fra poco.",
                style = MFootType.secondary,
                color = MFootColors.ink3,
            )
        }

        righe.forEach { riga ->
            val p = riga.player
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "${p.shortName} · ${p.age} anni · ${p.primaryPosition.short}",
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${p.overall} oggi · ${riga.estimate.first}-${riga.estimate.last} domani",
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                )
            }
            Hairline()
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (righe.isNotEmpty()) {
                Azione(
                    if (righe.size == 1) "Accetta" else "Accetta tutti",
                ) { onAccetta(righe.map { it.player.id.value }) }
            }
            Azione("Rifiuta", onRifiuta)
            // Rifiuta e riparte con lo stesso incarico: non si ricompila il modulo.
            Azione("Ri-scouta", onRiScouta)
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
    onAccetta: (Long, List<Long>) -> Unit = { _, _ -> },
    onRifiuta: (Long) -> Unit = {},
    onRiScouta: (Long, Long, String, String) -> Unit = { _, _, _, _ -> },
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

                if (missione != null && missione.daValutare) {
                    val righe: List<dev.mfoot.android.app.PlayerRow> = missione.trovati.mapNotNull { id ->
                        state.rows.firstOrNull { it.player.id.value == id }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tornato dal ${missione.country} · Valuta i talenti trovati:",
                        style = MFootType.chip,
                        color = MFootColors.elite,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (righe.isEmpty()) {
                        Text(
                            "I ragazzi trovati non sono ancora arrivati sul telefono. Riapri fra poco.",
                            style = MFootType.secondary,
                            color = MFootColors.ink3,
                        )
                    }
                    righe.forEach { riga ->
                        val p = riga.player
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${p.shortName} · ${p.age} anni · ${p.primaryPosition.short}",
                                    style = MFootType.rowTitle,
                                    color = MFootColors.ink,
                                )
                                Text(
                                    "${p.overall} oggi · potenziale ${riga.estimate.first}-${riga.estimate.last}",
                                    style = MFootType.chip,
                                    color = MFootColors.ink3,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (righe.isNotEmpty()) {
                            Azione(if (righe.size == 1) "Accetta" else "Accetta tutti") {
                                onAccetta(scout.id, righe.map { it.player.id.value })
                            }
                        }
                        Azione("Rifiuta") { onRifiuta(missione.id) }
                        Azione("Ri-scouta") {
                            onRiScouta(scout.id, missione.id, missione.country, missione.position)
                        }
                    }
                } else if (missione != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "In ${missione.country}, cerca un ${missione.position} · " +
                            missione.quando(java.time.Instant.now()),
                        style = MFootType.chip,
                        color = MFootColors.gamble,
                    )
                } else {
                    // Quanto starebbe via, prima di mandarlo.
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
    var presi by remember { mutableStateOf(emptySet<String>()) }
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
    Text("Che ruoli cerca", style = MFootType.label, color = MFootColors.ink3)
    Spacer(Modifier.height(6.dp))

    // PIU' DI UN RUOLO PER VIAGGIO
    //
    // Chiesto dal proprietario il 2026-08-30. La colonna `position` e' rimasta `text` e
    // contiene una lista separata da virgole: aggiungerne una nuova a una lettura condivisa
    // avrebbe spento tutte le missioni su ogni database indietro.
    //
    // Non torna sempre con tutti — quanti dipende dalle stelle — ma almeno uno sempre.
    Pulsanti(ruoli, presi) { valore ->
        presi = if (valore in presi) presi - valore else presi + valore
    }

    Spacer(Modifier.height(8.dp))
    if (presi.isEmpty()) {
        Text(
            "Scegli almeno un ruolo.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
    } else {
        Azione(
            "Mandalo · ${presi.size} ${if (presi.size == 1) "ruolo" else "ruoli"}",
        ) {
            // L'ordine dei ruoli e' quello in cui li ha scelti chi gioca: se ne torna
            // meno di quanti ne ha chiesti, arrivano i primi.
            onManda(scelto, ruoli.map { it.second }.filter { it in presi }.joinToString(","))
        }
    }
}

/** Una griglia di pulsanti a tre per riga: etichetta da mostrare, valore da restituire. */
@Composable
private fun Pulsanti(
    voci: List<Pair<String, String>>,
    accesi: Set<String> = emptySet(),
    onScegli: (String) -> Unit,
) {
    Column {
        voci.chunked(3).forEach { riga ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                riga.forEach { (etichetta, valore) ->
                    val acceso = valore in accesi
                    Text(
                        etichetta,
                        style = MFootType.chip,
                        color = if (acceso) MFootColors.bg else MFootColors.ink2,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (acceso) MFootColors.elite else MFootColors.core,
                                MFootShapes.field,
                            )
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
