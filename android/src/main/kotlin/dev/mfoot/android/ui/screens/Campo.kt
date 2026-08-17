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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.LineupEdit
import dev.mfoot.android.ui.Chip
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.PrimaryButton
import dev.mfoot.android.ui.pitch.PITCH_ASPECT
import dev.mfoot.android.ui.pitch.Pitch
import dev.mfoot.android.ui.pitch.pitchSlots
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.LineupFitter
import dev.mfoot.core.match.TacticalPressing
import dev.mfoot.core.match.TacticalStance
import dev.mfoot.core.match.TacticalTempo
import dev.mfoot.core.match.TacticalWidth
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position

/**
 * La formazione.
 *
 * ## Perche' un campo e non un elenco
 *
 * Undici righe con scritto "TS — Baresi" sono l'informazione completa e non dicono niente:
 * per capire che la fascia destra e' sguarnita bisogna leggerle tutte e ricostruire la
 * disposizione a mente. Sul campo si vede in un colpo d'occhio, ed e' il colpo d'occhio la
 * ragione per cui esiste questa schermata.
 *
 * ## Un uomo alla volta, non il trascinamento
 *
 * Si tocca una casella, si sceglie chi ci va. Trascinare sarebbe piu' bello da vedere e
 * peggio da usare su un telefono: le caselle stanno a pochi millimetri l'una dall'altra e
 * un dito ne copre tre. Il tocco in due tempi non sbaglia mai bersaglio.
 *
 * ## Il pulsante "completa"
 *
 * Riempie solo i buchi e non sposta nessuno. Usa [LineupFitter], che e' lo stesso codice
 * che gira sul server quando la partita si gioca senza che nessuno abbia schierato: quello
 * che si vede premendolo e' esattamente quello che scenderebbe in campo da solo.
 */
@Composable
fun CampoScreen(
    state: AppState.Dentro,
    edit: LineupEdit,
    onChange: (LineupEdit) -> Unit,
    onSave: () -> Unit,
) {
    val club = state.lega.myClub
    if (club == null) {
        Vuoto("Prima serve un club. Torna alla Casa e fondalo.")
        return
    }

    val squad = state.lega.squadOf(club.id)
    val today = MatchDay(state.lega.league.currentMatchDay)

    // La scelta si apre sopra tutto: chi sta scegliendo un terzino non deve poter toccare
    // per sbaglio il modulo o il salvataggio.
    val picking = edit.picking
    if (picking != null) {
        SceltaGiocatore(
            position = edit.formation.positions[picking],
            candidati = squad.filterNot { it.id.value in edit.inCampo },
            today = today,
            onPick = { onChange(edit.with(picking, it).conPanchina(squad, today)) },
            onClear = { onChange(edit.with(picking, null).conPanchina(squad, today)) },
            occupata = edit.eleven[picking],
            onClose = { onChange(edit.copy(picking = null)) },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Moduli(edit.formation) { onChange(edit.withFormation(it)) }

        Box(Modifier.padding(horizontal = MFootSpacing.section)) {
            Pitch(
                slots = pitchSlots(edit.formation, edit.eleven),
                modifier = Modifier.fillMaxWidth().aspectRatio(PITCH_ASPECT),
                // Il cerchio tratteggiato dice gia' che la casella e' libera, e la sigla
                // del ruolo dentro dice quale ruolo e'. Aggiungere "vuoto" sotto ognuna
                // significa scrivere la stessa parola fino a undici volte, sovrapposta
                // alle righe del campo: copre l'unica cosa che c'era da leggere.
                showEmptyLabels = false,
                onSlotClick = { onChange(edit.copy(picking = it)) },
            )
        }

        Spacer(Modifier.height(MFootSpacing.section))
        Riepilogo(edit, squad.size)
        Spacer(Modifier.height(MFootSpacing.related))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = MFootSpacing.section),
            horizontalArrangement = Arrangement.spacedBy(MFootSpacing.related),
        ) {
            GhostButton(
                text = if (edit.completa) "Rifai da capo" else "Completa",
                onClick = {
                    val base = if (edit.completa) {
                        edit.copy(eleven = List(edit.formation.positions.size) { null })
                    } else {
                        edit
                    }
                    onChange(base.completa(squad, today))
                },
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = when {
                    edit.busy != null -> edit.busy
                    !edit.dirty -> "Nessuna modifica"
                    else -> "Salva formazione"
                },
                enabled = edit.dirty && edit.busy == null,
                onClick = onSave,
                modifier = Modifier.weight(1f),
            )
        }

        if (edit.errore != null) {
            Spacer(Modifier.height(MFootSpacing.related))
            Box(Modifier.padding(horizontal = MFootSpacing.section)) {
                Notice(edit.errore, MFootColors.gamble)
            }
        }

        Spacer(Modifier.height(MFootSpacing.section))
        Panchina(edit)
        Spacer(Modifier.height(MFootSpacing.section))
        Assetto(edit) { onChange(edit.copy(tactics = it)) }
        Spacer(Modifier.height(40.dp))
    }
}

// ------------------------------------------------------------------------------ moduli

@Composable
private fun Moduli(selezionato: Formation, onPick: (Formation) -> Unit) {
    Column(Modifier.padding(MFootSpacing.section, MFootSpacing.section, 0.dp, 12.dp)) {
        Label("Modulo")
        Spacer(Modifier.height(9.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Formation.entries.forEach { formation ->
                Chip(formation.label, formation == selezionato) { onPick(formation) }
            }
            Spacer(Modifier.width(MFootSpacing.section))
        }
    }
}

/**
 * Quanti sono in campo, e quanto vale l'undici.
 *
 * "9 su 11" e' l'unica cosa che risponde alla domanda con cui si apre questa schermata.
 * La media dell'undici sta accanto perche' e' cosi' che si scopre che spostare un uomo di
 * ruolo e' costato tre punti.
 */
@Composable
private fun Riepilogo(edit: LineupEdit, rosa: Int) {
    val schierati = edit.eleven.filterNotNull()
    val media = if (schierati.isEmpty()) {
        0
    } else {
        edit.eleven
            .mapIndexedNotNull { index, player ->
                player?.overallAt(edit.formation.positions[index])
            }
            .average()
            .toInt()
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = MFootSpacing.section),
        horizontalArrangement = Arrangement.spacedBy(MFootSpacing.related),
    ) {
        Riquadro(
            "In campo",
            "${edit.schierati} su ${edit.formation.positions.size}",
            if (edit.completa) MFootColors.elite else MFootColors.gamble,
            Modifier.weight(1f),
        )
        Riquadro("Media undici", if (media > 0) "$media" else "—", MFootColors.rating(media), Modifier.weight(1f))
        Riquadro("In rosa", "$rosa", MFootColors.ink, Modifier.weight(1f))
    }
}

@Composable
private fun Riquadro(
    label: String,
    valore: String,
    colore: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(MFootColors.core, MFootShapes.band)
            .border(1.dp, MFootColors.line, MFootShapes.band)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(valore, style = MFootType.price, color = colore)
        Spacer(Modifier.height(3.dp))
        Text(label, style = MFootType.label, color = MFootColors.ink3)
    }
}

// ---------------------------------------------------------------------------- panchina

@Composable
private fun Panchina(edit: LineupEdit) {
    Column(Modifier.padding(horizontal = MFootSpacing.section)) {
        Label("Panchina")
        Spacer(Modifier.height(4.dp))
        Text(
            "La compone il gioco con chi resta: entra chi serve quando qualcuno si stanca " +
                "o si fa male.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
        Spacer(Modifier.height(10.dp))

        if (edit.bench.isEmpty()) {
            Text(
                "Nessuna riserva disponibile.",
                style = MFootType.secondary,
                color = MFootColors.ink3,
            )
            return@Column
        }

        edit.bench.forEach { player ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ruolo(player.primaryPosition)
                Spacer(Modifier.width(10.dp))
                Text(
                    player.shortName,
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${player.overall}",
                    style = MFootType.overallRow,
                    color = MFootColors.rating(player.overall),
                )
            }
            Hairline()
        }
    }
}

// ----------------------------------------------------------------------------- assetto

@Composable
private fun Assetto(edit: LineupEdit, onChange: (Tactics) -> Unit) {
    val t = edit.tactics

    Column(Modifier.padding(horizontal = MFootSpacing.section)) {
        Label("Assetto")
        Spacer(Modifier.height(4.dp))
        Text(
            "Nessuno di questi e' gratis. Alzare ritmo e pressing recupera piu' palloni e " +
                "brucia stamina: con due partite al giorno si arriva alla seconda a pezzi.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
        Spacer(Modifier.height(14.dp))

        Scelta("Atteggiamento", TacticalStance.entries, t.stance, { it.label }) {
            onChange(t.copy(stance = it))
        }
        Scelta("Ampiezza", TacticalWidth.entries, t.width, { it.label }) {
            onChange(t.copy(width = it))
        }
        Scelta("Ritmo", TacticalTempo.entries, t.tempo, { it.label }) {
            onChange(t.copy(tempo = it))
        }
        Scelta("Pressing", TacticalPressing.entries, t.pressing, { it.label }) {
            onChange(t.copy(pressing = it))
        }
    }
}

@Composable
private fun <T> Scelta(
    label: String,
    opzioni: List<T>,
    selezionata: T,
    labelOf: (T) -> String,
    onPick: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(label, style = MFootType.rowTitle, color = MFootColors.ink)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            opzioni.forEach { opzione ->
                Chip(labelOf(opzione), opzione == selezionata) { onPick(opzione) }
            }
        }
    }
}

// -------------------------------------------------------------------------- la scelta

/**
 * Chi mettere in questa casella.
 *
 * L'ordine e' per **resa nel ruolo**, non per overall: un centrale da 80 schierato terzino
 * rende meno di un terzino da 76, e ordinare per overall metterebbe in cima proprio la
 * scelta sbagliata. Accanto al nome si vede quanto rende li', cosi' il salto e' visibile
 * invece che sottinteso.
 */
@Composable
private fun SceltaGiocatore(
    position: Position,
    candidati: List<Player>,
    today: MatchDay,
    occupata: Player?,
    onPick: (Player) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val ordinati = candidati
        .filterNot { it.isInjured(today) }
        .sortedByDescending { LineupFitter.fitness(it, position) }
    val infortunati = candidati.filter { it.isInjured(today) }

    Column(Modifier.fillMaxSize().background(MFootColors.bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MFootColors.core)
                .padding(MFootSpacing.section, 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Chi gioca ${position.short}", style = MFootType.playerName, color = MFootColors.ink)
                Spacer(Modifier.height(3.dp))
                Text(
                    occupata?.let { "Adesso: ${it.shortName}" } ?: "Casella vuota",
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                )
            }
            Text(
                "Chiudi",
                style = MFootType.rowTitle,
                color = MFootColors.ink2,
                modifier = Modifier.clickable(onClick = onClose).padding(8.dp),
            )
        }
        Hairline()

        LazyColumn(Modifier.weight(1f)) {
            if (occupata != null) {
                item {
                    Text(
                        "Lascia la casella vuota",
                        style = MFootType.rowTitle,
                        color = MFootColors.gamble,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClear)
                            .padding(MFootSpacing.section, 15.dp),
                    )
                    Hairline()
                }
            }

            items(ordinati, key = { it.id.value }) { player ->
                Candidato(player, position, onPick)
            }

            if (infortunati.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(MFootSpacing.section))
                    Box(Modifier.padding(horizontal = MFootSpacing.section)) {
                        Label("Non disponibili")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                items(infortunati, key = { it.id.value }) { player ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(MFootSpacing.section, 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ruolo(player.primaryPosition)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            player.shortName,
                            style = MFootType.rowTitle,
                            color = MFootColors.ink3,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "rientra alla ${player.injuredUntil?.value}ª",
                            style = MFootType.chip,
                            color = MFootColors.ink3,
                        )
                    }
                    Hairline()
                }
            }

            if (ordinati.isEmpty() && infortunati.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Nessun altro in rosa. Servono acquisti.",
                            style = MFootType.secondary,
                            color = MFootColors.ink3,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Candidato(player: Player, position: Position, onPick: (Player) -> Unit) {
    val nelRuolo = player.overallAt(position)
    // Quanto ci perde giocando fuori ruolo. Zero non si mostra: sarebbe rumore su ogni
    // riga, e la riga che conta e' quella dove il numero c'e'.
    val perdita = player.overall - nelRuolo

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(player) }
            .padding(MFootSpacing.section, 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ruolo(player.primaryPosition)
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(
                player.shortName,
                style = MFootType.rowTitle,
                color = MFootColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (perdita > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "fuori ruolo, −$perdita",
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                )
            }
        }

        if (player.stamina < LineupFitter.TIRED_THRESHOLD) {
            Text(
                "stanco",
                style = MFootType.chip,
                color = MFootColors.gamble,
                modifier = Modifier.padding(end = 10.dp),
            )
        }

        Text(
            "$nelRuolo",
            style = MFootType.overallRow,
            color = MFootColors.rating(nelRuolo),
        )
    }
    Hairline()
}

/** La sigla del ruolo in un quadratino: si riconosce senza leggere. */
@Composable
private fun Ruolo(position: Position) {
    Box(
        Modifier
            .size(34.dp, 22.dp)
            .background(MFootColors.core, MFootShapes.field)
            .border(1.dp, MFootColors.line, MFootShapes.field),
        contentAlignment = Alignment.Center,
    ) {
        Text(position.short, style = MFootType.label, color = MFootColors.ink2)
    }
}

@Composable
private fun Vuoto(testo: String) {
    Box(
        Modifier.fillMaxSize().background(MFootColors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            testo,
            style = MFootType.secondary,
            color = MFootColors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}
