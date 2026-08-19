package dev.mfoot.android.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.CompetitionDraft
import dev.mfoot.android.app.CompetitionsState
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.calendar.CompetitionType
import dev.mfoot.core.calendar.KickoffRules
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val GIORNO = DateTimeFormatter.ofPattern("d MMM")

/**
 * Le competizioni della lega — **la plancia dell'admin**.
 *
 * ## Perche' non parte niente da solo
 *
 * Una lega puo' avere un campionato e una coppa insieme, o solo una coppa, o un torneo a
 * gironi fra otto dei venti club. Nessuna di queste cose e' deducibile da una data, e
 * decidere al posto dell'admin significherebbe togliergli il controllo proprio sulla
 * parte che rende la sua lega diversa da tutte le altre.
 *
 * ## Il calendario si vede prima
 *
 * Ogni modifica ricalcola l'anteprima in locale, con la stessa libreria che poi giochera'
 * le partite sul server. Quello che si legge qui non e' una stima: e' esattamente cio'
 * che verra' scritto.
 */
@Composable
fun CompetitionsScreen(
    state: CompetitionsState,
    onNew: () -> Unit,
    onEdit: ((CompetitionDraft) -> CompetitionDraft) -> Unit,
    onCreate: () -> Unit,
    onCancelDraft: () -> Unit,
    onDelete: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(MFootSpacing.section),
    ) {
        Text(
            if (state.draft == null) "‹ torna alla lega" else "‹ annulla",
            style = MFootType.chip,
            color = MFootColors.ink3,
            modifier = Modifier
                .clickable { if (state.draft == null) onClose() else onCancelDraft() }
                .padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(14.dp))

        if (state.draft == null) {
            Existing(state, onNew, onDelete)
        } else {
            Builder(state, state.draft, onEdit, onCreate)
        }
    }
}

@Composable
private fun Existing(state: CompetitionsState, onNew: () -> Unit, onDelete: (Long) -> Unit) {
    Text("Competizioni", style = MFootType.playerName, color = MFootColors.ink)
    Spacer(Modifier.height(6.dp))
    Text(
        "Campionato, coppa, gironi: le decidi tu, con i partecipanti e le date che vuoi. " +
            "Puoi averne piu' di una insieme.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )

    Spacer(Modifier.height(20.dp))

    state.avviso?.let {
        Notice(it, MFootColors.elite)
        Spacer(Modifier.height(MFootSpacing.related))
    }
    state.errore?.let {
        Notice(it, MFootColors.gamble)
        Spacer(Modifier.height(MFootSpacing.related))
    }

    if (state.existing.isEmpty()) {
        Text(
            "Nessuna competizione. Finche' non ne crei una non si gioca niente.",
            style = MFootType.secondary,
            color = MFootColors.ink3,
            modifier = Modifier.padding(vertical = 20.dp),
        )
    } else {
        state.existing.forEach { c ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MFootColors.core, MFootShapes.band)
                    .border(1.dp, MFootColors.lineStrong, MFootShapes.band)
                    .padding(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MFootType.value, color = MFootColors.ink)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "${c.type.label} · ${c.participants.size} club · " +
                                "${c.played}/${c.fixtures} giocate",
                            style = MFootType.chip,
                            color = MFootColors.ink3,
                        )
                    }
                    // Cancellare si puo' solo finche' non si e' giocato: dopo, i
                    // risultati sono storia, e toglierli falserebbe la crescita dei
                    // giocatori che quelle partite le hanno gia' giocate.
                    if (c.canDelete) {
                        Text(
                            "cancella",
                            style = MFootType.chip,
                            color = MFootColors.gamble,
                            modifier = Modifier.clickable { onDelete(c.id) }.padding(8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    Spacer(Modifier.height(16.dp))
    PrimaryButton("Crea una competizione", onNew)
}

@Composable
private fun Builder(
    state: CompetitionsState,
    draft: CompetitionDraft,
    onEdit: ((CompetitionDraft) -> CompetitionDraft) -> Unit,
    onCreate: () -> Unit,
) {
    Text("Nuova competizione", style = MFootType.playerName, color = MFootColors.ink)
    Spacer(Modifier.height(20.dp))

    MFootField(
        value = draft.name,
        onValueChange = { n -> onEdit { it.copy(name = n) } },
        placeholder = "Campionato",
        label = "Nome",
    )

    Spacer(Modifier.height(MFootSpacing.section))
    Label("Formato")
    Spacer(Modifier.height(8.dp))
    CompetitionType.entries.forEach { type ->
        FormatCard(type, type == draft.type) { onEdit { it.copy(type = type) } }
        Spacer(Modifier.height(8.dp))
    }

    if (draft.supportsDoubleRound) {
        Spacer(Modifier.height(6.dp))
        Toggle(
            label = if (draft.type == CompetitionType.GIRONE) "Andata e ritorno" else "Doppia sfida",
            on = draft.doubleRound,
        ) { onEdit { it.copy(doubleRound = !it.doubleRound) } }
    }

    Spacer(Modifier.height(28.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label("Partecipanti", Modifier.weight(1f))
        Text(
            if (draft.participants.size == state.clubs.size) "nessuno" else "tutti",
            style = MFootType.chip,
            color = MFootColors.elite,
            modifier = Modifier
                .clickable {
                    onEdit {
                        it.copy(
                            participants = if (it.participants.size == state.clubs.size) {
                                emptySet()
                            } else {
                                state.clubs.map { c -> c.id }.toSet()
                            },
                        )
                    }
                }
                .padding(6.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    state.clubs.forEach { club ->
        val iscritto = club.id in draft.participants
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    onEdit {
                        it.copy(
                            participants = if (iscritto) it.participants - club.id
                            else it.participants + club.id,
                        )
                    }
                }
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(18.dp)
                    .height(18.dp)
                    .background(
                        if (iscritto) MFootColors.elite else MFootColors.core,
                        MFootShapes.field,
                    )
                    .border(1.dp, MFootColors.lineStrong, MFootShapes.field),
                contentAlignment = Alignment.Center,
            ) {
                if (iscritto) {
                    Text("✓", style = MFootType.chip, color = MFootColors.bg)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                club.name,
                style = MFootType.rowTitle,
                color = if (iscritto) MFootColors.ink else MFootColors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (club.isAi) {
                Text("AI", style = MFootType.label, color = MFootColors.ink3)
            }
        }
        Hairline()
    }

    Spacer(Modifier.height(28.dp))
    Label("Quando si gioca")
    Spacer(Modifier.height(10.dp))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Inizio", style = MFootType.chip, color = MFootColors.ink3, modifier = Modifier.weight(1f))
        Stepper(draft.startDate.format(GIORNO)) { delta ->
            onEdit {
                val nuovo = it.startDate.plusDays(delta.toLong())
                it.copy(
                    startDate = nuovo,
                    // La fine si sposta con l'inizio se le si passa davanti: due date
                    // incrociate producono un calendario vuoto e nessuna spiegazione.
                    endDate = if (it.endDate.isBefore(nuovo)) nuovo.plusDays(1) else it.endDate,
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Fine", style = MFootType.chip, color = MFootColors.ink3, modifier = Modifier.weight(1f))
        Stepper(draft.endDate.format(GIORNO)) { delta ->
            onEdit {
                val nuovo = it.endDate.plusDays(delta.toLong())
                it.copy(endDate = if (nuovo.isBefore(it.startDate)) it.startDate else nuovo)
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    Label("Giorni buca")
    Spacer(Modifier.height(4.dp))
    Text(
        "I giorni della settimana in cui non si gioca.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DayOfWeek.entries.forEach { giorno ->
            val buca = giorno in draft.restWeekdays
            Chip(giorno.getDisplayName(TextStyle.SHORT, Locale.ITALIAN), buca) {
                onEdit {
                    it.copy(
                        restWeekdays = if (buca) it.restWeekdays - giorno
                        else it.restWeekdays + giorno,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    Orari(draft, onEdit)

    Spacer(Modifier.height(28.dp))
    Preview(draft)

    // Cosa non torna nelle date, prima di scrivere il calendario e non dopo.
    //
    // Il caso che si e' visto davvero: una competizione creata alle 18 che partiva "oggi"
    // con la fascia delle 15. La prima giornata nasceva gia' scaduta, il tick la trattava
    // come una partita da recuperare e la giocava subito, con le formazioni di nessuno.
    val problemi = KickoffRules.problemiDiCalendario(draft.calendar, LocalDateTime.now())

    Spacer(Modifier.height(16.dp))
    problemi.forEach {
        Notice(it, MFootColors.gamble)
        Spacer(Modifier.height(8.dp))
    }

    draft.busy?.let { Notice(it, MFootColors.ink2); Spacer(Modifier.height(10.dp)) }
    draft.errore?.let { Notice(it, MFootColors.gamble); Spacer(Modifier.height(10.dp)) }

    PrimaryButton(
        text = "Crea e scrivi il calendario",
        onClick = onCreate,
        enabled = draft.ready && draft.busy == null && problemi.isEmpty(),
    )
    Spacer(Modifier.height(30.dp))
}

/**
 * Gli orari di inizio: i soliti da toccare, e qualunque altro da scrivere.
 *
 * ## Perche' non bastavano sei orari fissi
 *
 * Perche' erano sei orari *di qualcun altro*. 12:30, 15:00, 18:30, 20:45, 21:00, 22:30
 * sono le fasce della Serie A, e una lega di amici gioca quando i suoi amici sono liberi:
 * alle 14 in pausa pranzo, alle 23 quando i figli dormono, alle 10 di domenica. Un elenco
 * chiuso non e' una semplificazione, e' una regola inventata che nessuno ha chiesto.
 *
 * I sei restano come scorciatoia — sono comodi e coprono il caso normale — ma accanto c'e'
 * un campo in cui si scrive l'ora che si vuole.
 */
@Composable
private fun Orari(draft: CompetitionDraft, onEdit: ((CompetitionDraft) -> CompetitionDraft) -> Unit) {
    var scritto by remember { mutableStateOf("") }

    Label("Orari di inizio")
    Spacer(Modifier.height(4.dp))
    Text(
        "Ora della lega. Una giornata puo' avere piu' fasce: il calendario le usa in ordine.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
    Spacer(Modifier.height(8.dp))

    // Prima quelli scelti, che sono la risposta alla domanda "a che ora si gioca".
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        draft.kickoffSlots.forEach { ora ->
            Chip("${testo(ora)} ✕", true) {
                onEdit {
                    // Almeno un orario, o non esiste nessuna fascia in cui giocare e il
                    // risolutore restituirebbe un calendario vuoto senza spiegare perche'.
                    val nuovi = it.kickoffSlots - ora
                    it.copy(kickoffSlots = nuovi.ifEmpty { it.kickoffSlots })
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Text("Da aggiungere con un tocco", style = MFootType.chip, color = MFootColors.ink3)
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ORARI.filterNot { it in draft.kickoffSlots }.forEach { ora ->
            Chip(testo(ora), false) {
                onEdit { it.copy(kickoffSlots = (it.kickoffSlots + ora).sorted()) }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            MFootField(
                value = scritto,
                onValueChange = { testo ->
                    scritto = testo.filter { it.isDigit() || it == ':' }.take(5)
                },
                placeholder = "es. 14:30",
                label = "Un altro orario",
                imeAction = ImeAction.Done,
            )
        }
        Spacer(Modifier.width(10.dp))
        val letto = leggiOra(scritto)
        Chip(if (letto != null) "aggiungi" else "hh:mm", letto != null) {
            if (letto != null) {
                onEdit { it.copy(kickoffSlots = (it.kickoffSlots + letto).distinct().sorted()) }
                scritto = ""
            }
        }
    }
}

/** `18:30`, sempre a due cifre: `18:5` accanto a `21:00` si legge come un refuso. */
private fun testo(ora: LocalTime): String = "%02d:%02d".format(ora.hour, ora.minute)

/**
 * Da quello che si scrive a un orario, o null.
 *
 * Accetta `21`, `21:0`, `21:00`, `2100`: chi digita in fretta scrive in tutti e quattro i
 * modi, e rifiutarne tre vuol dire un orario che non viene aggiunto e un campo che sembra
 * rotto.
 */
private fun leggiOra(testo: String): LocalTime? {
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
    return LocalTime.of(ore, minuti)
}

/**
 * L'anteprima del calendario.
 *
 * E' la parte che fa la differenza fra decidere e scommettere: quante partite escono, da
 * quando a quando, e soprattutto **cosa non torna**. Un periodo troppo corto per le
 * partite richieste va saputo adesso, non a stagione iniziata.
 */
@Composable
private fun Preview(draft: CompetitionDraft) {
    val schedule = draft.schedule

    Column(
        Modifier
            .fillMaxWidth()
            .background(MFootColors.core, MFootShapes.band)
            .border(1.dp, MFootColors.lineStrong, MFootShapes.band)
            .padding(16.dp),
    ) {
        Label("Anteprima del calendario")
        Spacer(Modifier.height(10.dp))

        if (draft.participants.size < 2) {
            Text(
                "Scegli almeno due partecipanti.",
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
            return@Column
        }

        if (schedule == null || schedule.fixtures.isEmpty()) {
            Text(
                "Nessuna partita: prova ad allungare il periodo o ad aggiungere un orario.",
                style = MFootType.chip,
                color = MFootColors.gamble,
            )
            return@Column
        }

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    schedule.fixtures.size.toString(),
                    style = MFootType.overallLarge,
                    color = MFootColors.ink,
                )
                Text("partite", style = MFootType.chip, color = MFootColors.ink3)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    schedule.lastMatchDay.value.toString(),
                    style = MFootType.overallLarge,
                    color = MFootColors.ink,
                )
                Text("giornate", style = MFootType.chip, color = MFootColors.ink3)
            }
            // Le partite al giorno sono un **risultato**, non una scelta: si ricavano
            // dalle squadre iscritte e dai giorni disponibili. Mostrarle qui, accanto
            // agli altri numeri calcolati, dice da sola che non e' una manopola.
            Column(Modifier.weight(1f)) {
                Text(
                    draft.matchesPerDayPerClub.toString(),
                    style = MFootType.overallLarge,
                    color = MFootColors.ink,
                )
                Text("al giorno, per club", style = MFootType.chip, color = MFootColors.ink3)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "${draft.participants.size} squadre · ${draft.matchDaysNeeded} giornate da giocare " +
                "in ${draft.playableDays} giorni disponibili",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )

        schedule.fixtures.mapNotNull { it.kickoff }.minOrNull()?.let { prima ->
            val ultima = schedule.fixtures.mapNotNull { it.kickoff }.max()
            Spacer(Modifier.height(10.dp))
            Text(
                "Dal ${prima.toLocalDate().format(GIORNO)} al ${ultima.toLocalDate().format(GIORNO)}",
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
        }

        if (!schedule.isComplete) {
            Spacer(Modifier.height(12.dp))
            Notice(
                "${schedule.unscheduled.size} turni non ci stanno nel periodo scelto.",
                MFootColors.gamble,
            )
        }

        schedule.warnings.take(3).forEach {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MFootType.chip, color = MFootColors.gamble)
        }
    }
}

@Composable
private fun FormatCard(type: CompetitionType, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MFootColors.elite.copy(alpha = 0.07f) else MFootColors.core,
                MFootShapes.band,
            )
            .border(
                1.dp,
                if (selected) MFootColors.elite.copy(alpha = 0.45f) else MFootColors.lineStrong,
                MFootShapes.band,
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(
            type.label,
            style = MFootType.value,
            color = if (selected) MFootColors.elite else MFootColors.ink,
        )
        Spacer(Modifier.height(4.dp))
        Text(descrizione(type), style = MFootType.chip, color = MFootColors.ink3)
    }
}

private fun descrizione(type: CompetitionType): String = when (type) {
    CompetitionType.GIRONE ->
        "Come la Serie A o la Premier: tutti contro tutti, classifica a punti. " +
            "Vince chi ne fa di piu' alla fine."
    CompetitionType.ELIMINAZIONE_DIRETTA ->
        "Tabellone: chi perde esce. Serve un numero di squadre potenza di due, " +
            "altrimenti qualcuno passa il turno senza giocare."
    CompetitionType.GIRONI_PIU_ELIMINAZIONE ->
        "Gironi iniziali, poi tabellone fra i qualificati. Il formato dei mondiali."
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MFootType.rowTitle, color = MFootColors.ink, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .width(42.dp)
                .height(24.dp)
                .background(if (on) MFootColors.elite else MFootColors.core, MFootShapes.pill)
                .border(1.dp, MFootColors.lineStrong, MFootShapes.pill),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .width(18.dp)
                    .height(18.dp)
                    .background(if (on) MFootColors.bg else MFootColors.ink3, MFootShapes.pill),
            )
        }
    }
}

@Composable
private fun Stepper(value: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepBox("−") { onStep(-1) }
        Text(
            value,
            style = MFootType.value,
            color = MFootColors.ink,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        StepBox("+") { onStep(1) }
    }
}

@Composable
private fun StepBox(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .width(34.dp)
            .height(34.dp)
            .background(MFootColors.core, MFootShapes.field)
            .border(1.dp, MFootColors.lineStrong, MFootShapes.field)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MFootType.value, color = MFootColors.ink)
    }
}

private val ORARI = listOf(
    LocalTime.of(12, 30), LocalTime.of(15, 0), LocalTime.of(18, 30),
    LocalTime.of(20, 45), LocalTime.of(21, 0), LocalTime.of(22, 30),
)
