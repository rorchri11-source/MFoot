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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.FoundingState
import dev.mfoot.android.ui.kit.Kit
import dev.mfoot.android.ui.kit.CrestEditor
import dev.mfoot.android.ui.kit.KitEditor
import dev.mfoot.android.ui.pitch.Pitch
import dev.mfoot.android.ui.pitch.pitchSlots
import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.PitchLayout
import dev.mfoot.android.app.FoundingStep
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Position
import dev.mfoot.core.world.CustomPlayerBuilder

/** I colori della maglia. Pochi e decisi: una tavolozza aperta produce solo fango. */
private val KIT_COLORS = listOf(
    0xFF2BE07E, 0xFFFFC53D, 0xFFE8483F, 0xFF3D7BFF, 0xFFB05CFF,
    0xFFF2F4F7, 0xFF1B1E24, 0xFF00C2C7, 0xFFFF7A3D, 0xFF8A0F2E,
)

@Composable
fun FoundingScreen(
    state: FoundingState,
    onChange: ((FoundingState) -> FoundingState) -> Unit,
    onRaise: (Attr) -> Unit,
    onLower: (Attr) -> Unit,
    onPosition: (Position) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MFootSpacing.section)
                .padding(top = MFootSpacing.section, bottom = MFootSpacing.section),
        ) {
            Text(
                if (state.step == FoundingStep.CLUB) "‹ annulla" else "‹ il club",
                style = MFootType.chip,
                color = MFootColors.ink3,
                modifier = Modifier.clickable {
                    if (state.step == FoundingStep.CLUB) onCancel()
                    else onChange { it.copy(step = FoundingStep.CLUB) }
                },
            )
            Spacer(Modifier.height(14.dp))

            when (state.step) {
                FoundingStep.CLUB -> ClubStep(state, onChange)
                FoundingStep.GIOCATORE -> PlayerStep(state, onChange, onRaise, onLower, onPosition)
            }
        }

        BottomBar(state, onChange, onConfirm)
    }
}

// -------------------------------------------------------------------------------- club

@Composable
private fun ClubStep(state: FoundingState, onChange: ((FoundingState) -> FoundingState) -> Unit) {
    Text("Il tuo club", style = MFootType.playerName, color = MFootColors.ink)
    Spacer(Modifier.height(6.dp))
    Text(
        "Il nome resta per tutta la lega. La sigla compare nelle classifiche e nei tabellini.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
    Spacer(Modifier.height(24.dp))

    MFootField(
        value = state.clubName,
        onValueChange = { name ->
            onChange {
                it.copy(
                    clubName = name,
                    // La sigla si compila da sola finche' non la si tocca: chiedere due
                    // campi quando il secondo si deduce dal primo e' attrito gratuito.
                    clubShort = if (it.clubShort.isBlank() ||
                        it.clubShort == suggestShort(it.clubName)
                    ) {
                        suggestShort(name)
                    } else {
                        it.clubShort
                    },
                )
            }
        },
        placeholder = "Atletico Divano",
        label = "Nome del club",
    )
    Spacer(Modifier.height(MFootSpacing.section))
    MFootField(
        value = state.clubShort,
        onValueChange = { s -> onChange { it.copy(clubShort = s.take(3)) } },
        placeholder = "ATD",
        label = "Sigla",
        uppercase = true,
        imeAction = ImeAction.Done,
    )

    Spacer(Modifier.height(32.dp))
    Label("La maglia")
    Spacer(Modifier.height(14.dp))

    KitEditor(kit = state.kit, onChange = { nuova -> onChange { it.copy(kit = nuova) } })

    Spacer(Modifier.height(MFootSpacing.section))

    CrestEditor(
        crest = state.crest,
        initials = state.clubShort,
        onChange = { nuovo -> onChange { it.copy(crest = nuovo) } },
    )
}

@Composable
private fun ColorRow(selected: Long, onPick: (Long) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KIT_COLORS.forEach { color ->
            Box(
                Modifier
                    .size(30.dp)
                    .background(Color(color), RoundedCornerShape(9.dp))
                    .border(
                        if (color == selected) 2.dp else 1.dp,
                        if (color == selected) MFootColors.ink else MFootColors.lineStrong,
                        RoundedCornerShape(9.dp),
                    )
                    .clickable { onPick(color) },
            )
        }
    }
}

/** Una maglia stilizzata: due rettangoli e una banda. Basta a far capire la scelta. */
@Composable
private fun Shirt(primary: Long, secondary: Long) {
    Box(
        Modifier
            .size(84.dp, 96.dp)
            // Il taglio va prima dello sfondo, o la banda esce dagli angoli arrotondati e
            // la maglia sembra un rettangolo storto.
            .clip(MFootShapes.field)
            .background(Color(primary)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(18.dp)
                .fillMaxHeight()
                .background(Color(secondary)),
        )
    }
}

private fun suggestShort(name: String): String =
    name.trim().split(" ").filter { it.isNotBlank() }
        .joinToString("") { it.take(1) }
        .uppercase()
        .take(3)

// --------------------------------------------------------------------------- giocatore

@Composable
private fun PlayerStep(
    state: FoundingState,
    onChange: ((FoundingState) -> FoundingState) -> Unit,
    onRaise: (Attr) -> Unit,
    onLower: (Attr) -> Unit,
    onPosition: (Position) -> Unit,
) {
    val draft = state.draft
    val config = state.config

    Text("Il giocatore che sei tu", style = MFootType.playerName, color = MFootColors.ink)
    Spacer(Modifier.height(6.dp))
    Text(
        "Non si puo' vendere ne' svincolare, solo prestare, e deve giocare titolare. " +
            "Parte da ${config.baseOverall}: cresce molto piu' in fretta di tutti gli altri.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )

    Spacer(Modifier.height(24.dp))

    Row {
        MFootField(
            draft.firstName, { n -> onChange { it.copy(draft = it.draft.copy(firstName = n)) } },
            "Nome", Modifier.weight(1f), label = "Nome",
        )
        Spacer(Modifier.width(MFootSpacing.related))
        MFootField(
            draft.lastName, { n -> onChange { it.copy(draft = it.draft.copy(lastName = n)) } },
            "Cognome", Modifier.weight(1f), label = "Cognome",
        )
    }

    Spacer(Modifier.height(MFootSpacing.section))
    Label("Dove giochi")
    Spacer(Modifier.height(4.dp))
    Text(
        "Tocca la posizione sul campo.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
    Spacer(Modifier.height(10.dp))

    // Il campo invece della fila di sigle: "TRQ" non dice dove si gioca a chi non
    // conosce le abbreviazioni, e chi le conosce deve comunque tradurle mentalmente in
    // una posizione. Qui la posizione **e'** la scelta.
    val ruoli = remember { PitchLayout.rolePicker() }
    val posizioni = remember { Formation.F_4_3_3.positions }
    Pitch(
        slots = pitchSlots(positions = posizioni, coordinates = ruoli),
        highlight = posizioni.withIndex()
            .filter { it.value == draft.position }
            .map { it.index }
            .toSet(),
        showEmptyLabels = false,
        onSlotClick = { index -> onPosition(posizioni[index]) },
    )

    Spacer(Modifier.height(8.dp))
    Text(
        "${draft.position.short} · ${draft.position.label}",
        style = MFootType.value,
        color = MFootColors.elite,
    )

    Spacer(Modifier.height(MFootSpacing.section))
    Label("Eta'")
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (config.minAge..config.maxAge).forEach { age ->
            Chip(age.toString(), age == draft.age) {
                onChange { it.copy(draft = it.draft.copy(age = age)) }
            }
        }
    }

    Spacer(Modifier.height(28.dp))
    StarPicker(
        "Piede debole", draft.weakFoot, config.starCost, state.remaining,
    ) { stars -> onChange { it.copy(draft = it.draft.copy(weakFoot = stars)) } }
    Spacer(Modifier.height(MFootSpacing.related))
    StarPicker(
        "Tecnica", draft.skillStars, config.starCost, state.remaining,
    ) { stars -> onChange { it.copy(draft = it.draft.copy(skillStars = stars)) } }

    Spacer(Modifier.height(28.dp))
    Label("Attributi")
    Spacer(Modifier.height(4.dp))
    Text(
        "Piu' un attributo e' alto, piu' costa alzarlo ancora.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
    Spacer(Modifier.height(12.dp))

    draft.position.displayAttributes().forEach { attr ->
        AttributeRow(state, attr, onRaise, onLower)
    }
}

/**
 * Una riga di attributo.
 *
 * Il prezzo del prossimo punto sta scritto accanto al pulsante. Senza, il budget si
 * svuota misteriosamente e l'unico modo di capire la regola e' provare a caso.
 */
@Composable
private fun AttributeRow(
    state: FoundingState,
    attr: Attr,
    onRaise: (Attr) -> Unit,
    onLower: (Attr) -> Unit,
) {
    val value = state.attributes[attr]
    val relevant = attr in state.draft.position.ovrWeights
    val cost = CustomPlayerBuilder.costOfNextPoint(state.draft, attr, state.config)
    val canRaise = CustomPlayerBuilder.canRaise(state.draft, attr, state.config)
    val canLower = (state.draft.increments[attr] ?: 0) > 0

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            attr.label,
            style = MFootType.rowTitle,
            color = if (relevant) MFootColors.ink else MFootColors.ink3,
            modifier = Modifier.weight(1f),
        )

        if (cost != null) {
            Text(
                "+1 = $cost",
                style = MFootType.chip,
                color = if (canRaise) MFootColors.ink3 else MFootColors.low,
            )
            Spacer(Modifier.width(10.dp))
        }

        StepButton("−", canLower) { onLower(attr) }
        Text(
            value.toString(),
            style = MFootType.overallRow,
            color = MFootColors.rating(value),
            modifier = Modifier
                .width(42.dp)
                .padding(horizontal = 6.dp),
        )
        StepButton("+", canRaise) { onRaise(attr) }
    }
    Hairline()
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .background(if (enabled) MFootColors.core else MFootColors.bg, MFootShapes.field)
            .border(
                1.dp,
                if (enabled) MFootColors.lineStrong else MFootColors.line,
                MFootShapes.field,
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MFootType.value,
            color = if (enabled) MFootColors.ink else MFootColors.low,
        )
    }
}

@Composable
private fun StarPicker(
    label: String,
    stars: Int,
    starCost: Int,
    remaining: Int,
    onPick: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label(label)
            Spacer(Modifier.height(3.dp))
            Text("$starCost punti a stella", style = MFootType.chip, color = MFootColors.ink3)
        }
        (1..5).forEach { n ->
            // Una stella si puo' sempre togliere; aggiungerne una solo se il budget regge.
            val affordable = n <= stars || (n - stars) * starCost <= remaining
            Text(
                if (n <= stars) "★" else "☆",
                style = MFootType.price,
                color = when {
                    n <= stars -> MFootColors.gamble
                    affordable -> MFootColors.ink3
                    else -> MFootColors.low
                },
                modifier = Modifier
                    .clickable(enabled = affordable) { onPick(n) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------- barra

/**
 * La barra fissa in basso.
 *
 * Il budget residuo e l'overall corrente devono restare visibili **mentre** si tocca un
 * pulsante: sono le uniche due cifre che contano, e mandarle a scorrere via renderebbe la
 * schermata un indovinello.
 */
@Composable
private fun BottomBar(
    state: FoundingState,
    onChange: ((FoundingState) -> FoundingState) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MFootColors.core)
            .padding(MFootSpacing.section),
    ) {
        if (state.errore != null) {
            Notice(state.errore, MFootColors.gamble)
            Spacer(Modifier.height(MFootSpacing.related))
        } else if (state.busy != null) {
            Notice(state.busy, MFootColors.ink2)
            Spacer(Modifier.height(MFootSpacing.related))
        }

        if (state.step == FoundingStep.GIOCATORE) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Punti rimasti")
                    Text(
                        "${state.remaining} / ${state.config.skillBudget}",
                        style = MFootType.price,
                        color = if (state.remaining > 0) MFootColors.gamble else MFootColors.ink3,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Label("Overall")
                    Text(
                        state.overall.toString(),
                        style = MFootType.overallLarge,
                        color = MFootColors.rating(state.overall),
                    )
                }
            }
            Spacer(Modifier.height(MFootSpacing.related))
        }

        when (state.step) {
            FoundingStep.CLUB -> PrimaryButton(
                text = "Avanti: il tuo giocatore",
                onClick = { onChange { it.copy(step = FoundingStep.GIOCATORE) } },
                enabled = state.clubReady,
            )

            FoundingStep.GIOCATORE -> PrimaryButton(
                text = "Fonda ${state.clubName}",
                onClick = onConfirm,
                enabled = state.problems.isEmpty() && state.busy == null,
            )
        }
    }
}
