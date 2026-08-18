package dev.mfoot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.DoorMode
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.android.app.SetupChoices
import dev.mfoot.android.ui.settings.IntStepper
import dev.mfoot.android.ui.settings.MoneyField
import dev.mfoot.android.ui.settings.SettingRow
import dev.mfoot.core.config.ConfigPresets
import java.time.LocalDate

/**
 * La porta d'ingresso.
 *
 * ## Perche' non c'e' nessuna registrazione
 *
 * Nessuna email, nessuna password, nessun messaggio di conferma da aspettare. Per una
 * lega fra venti amici, chiedere un indirizzo e' attrito puro: meta' delle persone non
 * arriverebbe mai alla seconda schermata. L'identita' e' anonima e vive sul telefono.
 *
 * Il prezzo va detto: chi disinstalla l'app perde il club. E' scritto qui sotto invece di
 * essere scoperto dopo.
 */
@Composable
fun DoorScreen(
    state: AppState.Porta,
    onMode: (DoorMode) -> Unit,
    onCreate: (nome: String, codice: String, nickname: String, preset: String, scelte: SetupChoices) -> Unit,
    onJoin: (codice: String, nickname: String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Wordmark()
        Spacer(Modifier.height(10.dp))
        Text(
            "Il tuo club, i tuoi amici, un mondo inventato da zero.",
            style = MFootType.secondary,
            color = MFootColors.ink3,
        )
        Spacer(Modifier.height(44.dp))

        Box(Modifier.widthIn(max = 420.dp)) {
            when (state.mode) {
                DoorMode.SCELTA -> Choice(onMode)
                DoorMode.CREA -> CreateForm(state, onCreate) { onMode(DoorMode.SCELTA) }
                DoorMode.ENTRA -> JoinForm(state, onJoin) { onMode(DoorMode.SCELTA) }
            }
        }

        if (state.busy != null) {
            Spacer(Modifier.height(MFootSpacing.section))
            Notice(state.busy, MFootColors.ink2, Modifier.widthIn(max = 420.dp))
        } else if (state.errore != null) {
            Spacer(Modifier.height(MFootSpacing.section))
            Notice(state.errore, MFootColors.gamble, Modifier.widthIn(max = 420.dp))
        }
    }
}

@Composable
private fun Wordmark() {
    Text(
        "MFOOT",
        style = MFootType.overallLarge.copy(letterSpacing = 0.18.em),
        color = MFootColors.ink,
    )
}

@Composable
private fun Choice(onMode: (DoorMode) -> Unit) {
    Column {
        PrimaryButton("Crea una lega", { onMode(DoorMode.CREA) })
        Spacer(Modifier.height(MFootSpacing.related))
        GhostButton("Entra con un codice", { onMode(DoorMode.ENTRA) })
        Spacer(Modifier.height(28.dp))
        Text(
            "Non serve nessuna registrazione. Il tuo club resta legato a questo telefono: " +
                "se disinstalli l'app, lo perdi.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
    }
}

@Composable
private fun CreateForm(
    state: AppState.Porta,
    onCreate: (String, String, String, String, SetupChoices) -> Unit,
    onBack: () -> Unit,
) {
    var nome by rememberSaveable { mutableStateOf("") }
    var codice by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var preset by rememberSaveable { mutableStateOf(ConfigPresets.all.first().id) }

    // Le scelte ripartono dal preset ogni volta che se ne cambia uno: e' il senso di un
    // preset. Tenere i ritocchi attraverso il cambio darebbe una lega che non e' ne' quella
    // scelta ne' quella impostata, e nessuno saprebbe piu' da dove vengono i suoi numeri.
    var scelte by remember(preset) {
        mutableStateOf(SetupChoices.from(ConfigPresets.byId(preset)!!.build(16, 8, LocalDate.now())))
    }
    val pronto = nome.isNotBlank() && codice.isNotBlank() && nickname.isNotBlank()

    Column {
        SectionTitle("Crea una lega", onBack)

        MFootField(nome, { nome = it }, "Lega del giovedi", label = "Nome della lega")
        Spacer(Modifier.height(MFootSpacing.section))
        MFootField(
            codice, { codice = it }, "PAROLA-SEGRETA",
            label = "Codice d'accesso", uppercase = true,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "E' quello che darai agli altri per entrare.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
        Spacer(Modifier.height(MFootSpacing.section))
        MFootField(
            nickname, { nickname = it }, "Come ti chiamano",
            label = "Il tuo nome", imeAction = ImeAction.Done,
        )

        Spacer(Modifier.height(28.dp))
        Label("Ritmo della lega")
        Spacer(Modifier.height(8.dp))
        ConfigPresets.all.forEach { p ->
            PresetCard(p, p.id == preset) { preset = p.id }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(24.dp))
        Impostazioni(scelte) { scelte = it }

        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = "Genera il mondo e crea la lega",
            onClick = { onCreate(nome, codice, nickname, preset, scelte) },
            enabled = pronto && state.busy == null,
        )
    }
}

@Composable
private fun JoinForm(
    state: AppState.Porta,
    onJoin: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var codice by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    val pronto = codice.isNotBlank() && nickname.isNotBlank()

    Column {
        SectionTitle("Entra in una lega", onBack)

        MFootField(
            codice, { codice = it }, "IL CODICE CHE TI HANNO DATO",
            label = "Codice d'accesso", uppercase = true,
        )
        Spacer(Modifier.height(MFootSpacing.section))
        MFootField(
            nickname, { nickname = it }, "Come ti chiamano",
            label = "Il tuo nome", imeAction = ImeAction.Done,
        )

        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            text = "Entra",
            onClick = { onJoin(codice, nickname) },
            enabled = pronto && state.busy == null,
        )
    }
}

@Composable
private fun SectionTitle(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "‹ indietro",
            style = MFootType.chip,
            color = MFootColors.ink3,
            modifier = Modifier.clickable(onClick = onBack),
        )
        Spacer(Modifier.height(14.dp))
        Text(title, style = MFootType.playerName, color = MFootColors.ink)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Un preset e' una configurazione intera, non un'etichetta.
 *
 * Sessanta impostazioni in faccia al primo utilizzo sono un muro: si apre la schermata,
 * non si sa cosa significhi meta' delle voci e si abbandona. Qui si sceglie un ritmo e
 * basta — le manopole restano tutte modificabili dopo, dalle impostazioni della lega.
 */
@Composable
private fun PresetCard(preset: ConfigPresets.Preset, selected: Boolean, onClick: () -> Unit) {
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
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            preset.name,
            style = MFootType.value,
            color = if (selected) MFootColors.elite else MFootColors.ink,
        )
        Text(preset.description, style = MFootType.chip, color = MFootColors.ink3)
    }
}

/**
 * Le poche impostazioni che vanno decise prima di generare il mondo.
 *
 * ## Perche' qui e non solo nel Regolamento
 *
 * Perche' dopo e' tardi. Il numero di club decide quante squadre nascono, il budget decide
 * l'intero listino prezzi, le divisioni decidono la forma del campionato: cambiarli a lega
 * avviata non rigenera niente, resta tutto com'era e cambia solo il numero scritto.
 *
 * Tutto il resto — infortuni, premi, crescita, stipendi — si tocca quando si vuole da
 * Regolamento e opzioni, ed e' scritto qui sotto perche' nessuno debba chiederselo.
 */
@Composable
private fun Impostazioni(scelte: SetupChoices, onChange: (SetupChoices) -> Unit) {
    Label("Come e' fatta la lega")
    Spacer(Modifier.height(4.dp))
    Text(
        "Queste vanno decise adesso: dopo la creazione il mondo e' gia' generato. " +
            "Tutto il resto lo cambi quando vuoi da Regolamento e opzioni.",
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
    Spacer(Modifier.height(MFootSpacing.related))

    SettingRow(
        "Squadre in tutto",
        "Quante ne esistono nella lega, umane e non.",
    ) { IntStepper(scelte.totalClubs, 2..40, true) { onChange(scelte.copy(totalClubs = it)) } }

    SettingRow(
        "Gestite dal computer",
        "Le altre ${scelte.humanClubs} restano libere per te e i tuoi amici. " +
            "Le AI comprano e giocano da sole, e non aspettano nessuno.",
    ) {
        IntStepper(scelte.aiClubs, 0..scelte.totalClubs, true) {
            onChange(scelte.copy(aiClubs = it))
        }
    }

    SettingRow(
        "Budget iniziale",
        "Da qui deriva tutto il listino: alzandolo salgono anche i prezzi, quindi non " +
            "rende nessuno piu' ricco — cambia solo la scala dei numeri.",
    ) { MoneyField(scelte.startingCredits, true) { onChange(scelte.copy(startingCredits = it)) } }

    SettingRow(
        "Minimo in rosa",
        "Sotto questo numero una squadra non scende in campo e le sue partite si rinviano.",
    ) { IntStepper(scelte.minSquadSize, 11..40, true) { onChange(scelte.copy(minSquadSize = it)) } }

    SettingRow(
        "Massimo in rosa",
        "Il tetto agli acquisti, perche' chi ha budget non si prenda mezzo listino.",
    ) {
        IntStepper(scelte.maxSquadSize, scelte.minSquadSize..60, true) {
            onChange(scelte.copy(maxSquadSize = it))
        }
    }

    SettingRow(
        "Divisioni",
        if (scelte.divisions > 1) {
            "${scelte.totalClubs / scelte.divisions} squadre per divisione, con promozioni e " +
                "retrocessioni. Chi resta indietro ha comunque qualcosa da giocare."
        } else {
            "Una sola: girone unico, tutti contro tutti. Con tante squadre conviene spezzare."
        },
    ) { IntStepper(scelte.divisions, 1..6, true) { onChange(scelte.copy(divisions = it)) } }
}
