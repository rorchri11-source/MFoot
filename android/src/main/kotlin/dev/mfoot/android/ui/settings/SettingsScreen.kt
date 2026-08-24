package dev.mfoot.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.DivisionsAdmin
import dev.mfoot.android.app.SettingsSection
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.GhostButton
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.PrimaryButton
import dev.mfoot.android.ui.Scheda
import dev.mfoot.android.ui.Selettore
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Spiegazione
import dev.mfoot.android.ui.Tessera
import dev.mfoot.android.ui.icons.MFootIcons
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.calendar.Division
import dev.mfoot.core.calendar.DivisionRules
import dev.mfoot.core.calendar.SeasonEnd
import dev.mfoot.core.calendar.SeasonOutcome
import dev.mfoot.core.config.DivisionsConfig
import dev.mfoot.core.config.IncomeCadence
import dev.mfoot.core.config.InjurySeverity
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.config.MatchSpeed
import dev.mfoot.core.model.ClubId

/**
 * L'elenco delle sei sezioni.
 *
 * Sei schermate e non una lunga: centodieci manopole in un'unica pagina si scorrono per
 * mezzo minuto prima di trovare quella che si cercava, e chi cerca "quanto costa il
 * rinnovo" non vuole passare in mezzo al tasso di infortunio.
 */
@Composable
fun SettingsIndexScreen(canEdit: Boolean, onOpen: (SettingsSection) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(MFootSpacing.section),
    ) {
        if (!canEdit) {
            Notice(
                "Solo l'amministratore della lega può cambiare le regole. " +
                    "Puoi guardarle, non modificarle.",
                MFootColors.ink2,
            )
            Spacer(Modifier.height(MFootSpacing.section))
        }

        SettingsSection.entries.forEach { section ->
            Scheda(Modifier.padding(bottom = 10.dp), onClick = { onOpen(section) }) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tessera(icona(section), colore(section))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(section.label, style = MFootType.rowTitle, color = MFootColors.ink)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            descrizione(section),
                            style = MFootType.secondary,
                            color = MFootColors.ink2,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        MFootIcons.avanti,
                        contentDescription = null,
                        tint = MFootColors.ink3,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Spiegazione(
            "Queste regole sono solo vostre",
            "Ogni numero qui dentro vale per questa lega e per nessun'altra. Cambiarlo non " +
                "richiede di aggiornare l'app: le regole stanno sul server, e chi gioca le " +
                "vede cambiare al giro dopo.",
        )
    }
}

/**
 * L'icona e il colore della tessera di ogni sezione.
 *
 * ## Perche' quattro colori e non sette
 *
 * Il colore qui non identifica la voce — a quello serve il nome — ma la **famiglia**: blu
 * chi gioca, verde come si schiera e come cresce, arancione quanto costa, rosso il tuo
 * giocatore. Con sette colori, uno per voce, non raggrupperebbe piu' niente e sarebbe solo
 * rumore; con quattro si trova la riga giusta fra sette senza leggerle.
 */
private fun icona(section: SettingsSection) = when (section) {
    SettingsSection.SQUADRE -> MFootIcons.persone
    SettingsSection.DIVISIONI -> MFootIcons.divisioni
    SettingsSection.ECONOMIA -> MFootIcons.gettoni
    SettingsSection.MERCATO -> MFootIcons.carrello
    SettingsSection.PARTITA -> MFootIcons.campo
    SettingsSection.CRESCITA -> MFootIcons.stella
    SettingsSection.CUSTOM -> MFootIcons.persona
}

private fun colore(section: SettingsSection) = when (section) {
    SettingsSection.SQUADRE, SettingsSection.DIVISIONI -> MFootColors.tileBlue
    SettingsSection.ECONOMIA, SettingsSection.MERCATO -> MFootColors.tileOrange
    SettingsSection.PARTITA, SettingsSection.CRESCITA -> MFootColors.tileGreen
    SettingsSection.CUSTOM -> MFootColors.tileRed
}

private fun descrizione(section: SettingsSection): String = when (section) {
    SettingsSection.SQUADRE -> "Quanti club, quanti gestiti dall'AI, minimo e massimo in rosa"
    SettingsSection.DIVISIONI -> "Serie A, B, C: quante, chi sale, chi scende, playoff e playout"
    SettingsSection.ECONOMIA -> "Budget, entrate, premi, stipendi, rinnovi"
    SettingsSection.MERCATO -> "Aste, rilanci, contratti, prestiti"
    SettingsSection.PARTITA -> "Infortuni, cartellini, velocità della diretta"
    SettingsSection.CRESCITA -> "Velocità di crescita, età di picco, Primavera, morale"
    SettingsSection.CUSTOM -> "Budget punti, costo delle stelle, età di partenza"
}

/**
 * Una sezione del regolamento.
 *
 * Le modifiche cambiano la configurazione in memoria; il salvataggio e' un gesto separato.
 * Cosi' si sistemano tre campi e si conferma una volta, e si puo' cambiare idea senza aver
 * gia' alterato una lega in corso.
 */
@Composable
fun SettingsScreen(
    section: SettingsSection,
    config: LeagueConfig,
    canEdit: Boolean,
    dirty: Boolean,
    busy: String?,
    errore: String?,
    onChange: (LeagueConfig) -> Unit,
    onSave: () -> Unit,
    /** Cio' che va sotto la sezione: oggi solo i pulsanti delle divisioni. */
    azioni: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().background(MFootColors.bg)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MFootSpacing.section)
                .padding(top = MFootSpacing.related, bottom = MFootSpacing.section),
        ) {
            when (section) {
                SettingsSection.SQUADRE -> Squadre(config, canEdit, onChange)
                SettingsSection.DIVISIONI -> Divisioni(config, canEdit, onChange)
                SettingsSection.ECONOMIA -> Economia(config, canEdit, onChange)
                SettingsSection.MERCATO -> Mercato(config, canEdit, onChange)
                SettingsSection.PARTITA -> Partita(config, canEdit, onChange)
                SettingsSection.CRESCITA -> Crescita(config, canEdit, onChange)
                SettingsSection.CUSTOM -> Custom(config, canEdit, onChange)
            }
            azioni?.invoke()
            Spacer(Modifier.height(24.dp))
        }

        if (canEdit) {
            Column(Modifier.fillMaxWidth().background(MFootColors.core).padding(MFootSpacing.section)) {
                errore?.let { Notice(it, MFootColors.gamble); Spacer(Modifier.height(10.dp)) }
                busy?.let { Notice(it, MFootColors.ink2); Spacer(Modifier.height(10.dp)) }
                PrimaryButton(
                    text = if (dirty) "Salva le modifiche" else "Nessuna modifica",
                    onClick = onSave,
                    enabled = dirty && busy == null,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------ squadre

@Composable
private fun Squadre(config: LeagueConfig, edit: Boolean, onChange: (LeagueConfig) -> Unit) {
    val s = config.setup
    fun set(block: dev.mfoot.core.config.SetupConfig.() -> dev.mfoot.core.config.SetupConfig) =
        onChange(config.copy(setup = s.block()))

    SettingRow(
        "Numero di club",
        "Quante squadre in tutto. Cambiarlo a stagione iniziata non rifà il calendario " +
            "delle competizioni già create.",
    ) { IntStepper(s.totalClubs, 2..40, edit) { set { copy(totalClubs = it) } } }

    SettingRow(
        "Gestiti dall'AI",
        "Gli altri restano liberi per le persone. Le AI comprano, schierano e giocano da " +
            "sole, e non aspettano nessuno.",
    ) { IntStepper(s.aiClubs, 0..s.totalClubs, edit) { set { copy(aiClubs = it) } } }

    SettingRow(
        "Minimo in rosa",
        "Sotto questo numero la squadra non scende in campo e le partite si rinviano. " +
            "Alzarlo obbliga a comprare più riserve.",
    ) { IntStepper(s.minSquadSize, 11..40, edit) { set { copy(minSquadSize = it) } } }

    SettingRow(
        "Massimo in rosa",
        "Il tetto agli acquisti. Serve a evitare che chi ha budget si prenda mezzo listino.",
    ) { IntStepper(s.maxSquadSize, s.minSquadSize..60, edit) { set { copy(maxSquadSize = it) } } }
}

// ---------------------------------------------------------------------------- divisioni

/**
 * Serie A, B, C — e l'anteprima di cosa succede a fine stagione.
 *
 * ## L'anteprima non e' un ornamento
 *
 * "Una promozione diretta, due ai playoff, una retrocessione diretta, due ai playout" e'
 * una frase che si legge e non si capisce: bisogna fare il conto a mente, tenere presente
 * che gli spareggi assegnano un posto solo, e ricordarsi quante squadre ha una divisione.
 * Sbagliarlo costa una stagione intera, perche' quando si vede che la Serie A ha un club in
 * piu' non si torna indietro.
 *
 * La scaletta qui sotto mostra le posizioni una per una con cosa capita a chi ci finisce.
 * E' calcolata dallo **stesso** regolamento che girera' a fine stagione, quindi non e' una
 * spiegazione di cosa dovrebbe succedere: e' cosa succedera'.
 */
@Composable
private fun Divisioni(config: LeagueConfig, edit: Boolean, onChange: (LeagueConfig) -> Unit) {
    val d = config.divisions
    fun set(block: DivisionsConfig.() -> DivisionsConfig) =
        onChange(config.copy(divisions = d.block()))

    // Sei scelte, tutte visibili insieme, come nel riferimento.
    //
    // Con lo stepper «− 2 +» per arrivare da uno a sei si preme quattro volte e non si sa
    // mai dove finisce la scala. Qui si vede subito che il massimo e' sei, e si arriva
    // dove si vuole con un tocco solo.
    Spiegazione(
        "Cosa sono le divisioni",
        "Ti permettono di separare le squadre in campionati indipendenti — Serie A, B, C — " +
            "con promozioni, retrocessioni e spareggi a fine stagione. Una sola significa " +
            "girone unico: tutti si incontrano e la classifica è una. Con tanti club " +
            "conviene spezzare, perché in un girone da venti chi resta indietro non ha più " +
            "niente da giocare da novembre.",
    )
    Spacer(Modifier.height(MFootSpacing.section))

    Label("Quante divisioni")
    Spacer(Modifier.height(10.dp))
    Selettore((1..6).toList(), d.count, abilitato = edit) { set { copy(count = it) } }

    if (!d.enabled) {
        Spacer(Modifier.height(MFootSpacing.section))
        Notice(
            "Girone unico: una classifica sola, nessuna promozione e nessuna retrocessione.",
            MFootColors.ink2,
        )
        return
    }

    Spacer(Modifier.height(MFootSpacing.section))

    NomiDivisioni(d, edit) { set { copy(names = it) } }
    DimensioniDivisioni(d, edit) { set { copy(sizes = it) } }

    GroupTitle("Chi sale")

    SettingRow(
        "Promozioni dirette",
        "Salgono senza spareggi, dalla divisione di sotto a quella di sopra.",
    ) { IntStepper(d.directPromotions, 0..6, edit) { set { copy(directPromotions = it) } } }

    SettingRow(
        "Squadre ai playoff",
        "Si giocano un posto solo fra loro, non uno per squadra: quattro ai playoff " +
            "promuovono una squadra. Zero disattiva i playoff.",
    ) { IntStepper(d.playoffSlots, 0..8, edit) { set { copy(playoffSlots = playoffValido(it)) } } }

    GroupTitle("Chi scende")

    SettingRow(
        "Retrocessioni dirette",
        "Scendono senza appello, dal fondo della classifica.",
    ) { IntStepper(d.directRelegations, 0..6, edit) { set { copy(directRelegations = it) } } }

    SettingRow(
        "Squadre ai playout",
        "Si giocano la salvezza fra loro, appena sopra la zona di retrocessione diretta. " +
            "Come i playoff, il posto in gioco è uno.",
    ) { IntStepper(d.playoutSlots, 0..8, edit) { set { copy(playoutSlots = playoffValido(it)) } } }

    SettingRow(
        "Spareggi andata e ritorno",
        "Secco è più emozionante e più ingiusto. Nel doppio confronto, a parità passa " +
            "chi è arrivato davanti in campionato.",
    ) { Switch(d.twoLeggedPlayoffs, edit) { set { copy(twoLeggedPlayoffs = it) } } }

    ScalettaFineStagione(config)
}

/**
 * I due gesti che **applicano** le divisioni, sotto la scaletta che dice cosa succedera'.
 *
 * ## Perche' chiudere la stagione e' un pulsante
 *
 * Il gioco non ha un concetto di stagione: le competizioni le crea l'admin quando vuole,
 * quante ne vuole. Non esiste un momento in cui si possa dire che la stagione e' finita, e
 * inventarlo — l'ultima partita dell'ultima competizione? — vorrebbe dire indovinare, e
 * sbagliare la volta in cui si voleva solo aggiungere una coppa.
 *
 * Chiedendolo l'ambiguita' si toglie invece di nasconderla. E chi preme sa cosa succede,
 * perche' la scaletta e' scritta qui sopra.
 */
@Composable
fun DivisioniAzioni(
    abilitate: Boolean,
    giaAssegnate: Boolean,
    stato: DivisionsAdmin,
    /** Cosa non torrebbe premendo adesso: si legge prima, non dopo. */
    avvisi: List<String>,
    onAssegna: () -> Unit,
    onChiudiStagione: () -> Unit,
    onChiudiAvviso: () -> Unit,
) {
    if (!abilitate) return

    Column(Modifier.fillMaxWidth().padding(top = MFootSpacing.section)) {
        stato.errore?.let {
            Notice(it, MFootColors.gamble, Modifier.clickable(onClick = onChiudiAvviso))
            Spacer(Modifier.height(MFootSpacing.related))
        }
        stato.avviso?.let {
            Notice(it, MFootColors.elite, Modifier.clickable(onClick = onChiudiAvviso))
            Spacer(Modifier.height(MFootSpacing.related))
        }

        Text(
            if (giaAssegnate) {
                "Le squadre sono già divise. Riassegnare rimescola tutto da capo e " +
                    "cancella promozioni e retrocessioni già avvenute."
            } else {
                "Nessuna squadra è ancora assegnata: sono tutte nella prima divisione."
            },
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
        Spacer(Modifier.height(MFootSpacing.related))

        // La regola, scritta dove si preme il pulsante che la applica.
        //
        // Non e' una nota di cortesia: e' il contrario di quello che faceva prima
        // (distribuire tutti per forza, umani compresi), e chi ha giocato una stagione con
        // il comportamento vecchio deve poter capire perche' adesso e' diverso.
        Text(
            "I club dei giocatori veri restano tutti in prima divisione. Le seconde " +
                "squadre partono dall'ultima. Le squadre del computer riempiono i posti " +
                "che restano, dalla più forte alla più debole.",
            style = MFootType.chip,
            color = MFootColors.ink2,
        )
        Spacer(Modifier.height(MFootSpacing.related))

        avvisi.forEach {
            Notice(it, MFootColors.gamble)
            Spacer(Modifier.height(MFootSpacing.related))
        }

        GhostButton(
            text = stato.busy ?: if (giaAssegnate) "Riassegna le divisioni" else "Assegna le divisioni",
            onClick = onAssegna,
        )

        if (giaAssegnate) {
            Spacer(Modifier.height(MFootSpacing.related))
            Text(
                "Chiudere la stagione applica quello che c'è scritto qui sopra: promuove, " +
                    "retrocede, e riscrive la scala. Chi è agli spareggi resta dov'è " +
                    "finché non li gioca.",
                style = MFootType.chip,
                color = MFootColors.ink3,
            )
            Spacer(Modifier.height(MFootSpacing.related))
            PrimaryButton(
                text = stato.busy ?: "Chiudi la stagione",
                onClick = onChiudiStagione,
                enabled = stato.busy == null,
            )
        }
    }
}

/**
 * Uno e' un numero di partecipanti impossibile per uno spareggio.
 *
 * [dev.mfoot.core.calendar.DivisionRules] lo rifiuta con un'eccezione, e giustamente: uno
 * spareggio fra una squadra non e' uno spareggio. Ma il passo dello stepper e' uno, quindi
 * salendo da zero si passa per l'uno e l'app cadrebbe. Si salta.
 */
private fun playoffValido(valore: Int): Int = if (valore == 1) 2 else valore

/**
 * Quante squadre per divisione.
 *
 * ## Perche' non basta dividere il numero di club
 *
 * Perche' la regola di questa lega e' che **i giocatori veri partono tutti in prima
 * divisione**. Con dodici amici e una prima divisione da dieci le due cose non stanno
 * insieme, e la contraddizione la deve sciogliere l'admin: sa lui se preferisce una prima
 * divisione da dodici o due amici in seconda. Il programma non lo sa e non deve indovinare.
 *
 * Zero significa «decidi tu»: le divisioni lasciate a zero si dividono in parti uguali i
 * club che restano, come faceva prima.
 */
@Composable
private fun DimensioniDivisioni(
    d: DivisionsConfig,
    edit: Boolean,
    onChange: (List<Int>) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Text("Quante squadre per divisione", style = MFootType.rowTitle, color = MFootColors.ink)
        Spacer(Modifier.height(5.dp))
        Text(
            "Zero vuol dire «dividile tu in parti uguali». I club dei giocatori veri " +
                "entrano comunque tutti in prima divisione: se sono più dei posti, la " +
                "prima divisione si allarga e l'app te lo dice prima di assegnare.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
        Spacer(Modifier.height(10.dp))

        (1..d.count).forEach { livello ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    d.nameOf(livello),
                    style = MFootType.chip,
                    color = MFootColors.ink2,
                    modifier = Modifier.weight(1f),
                )
                IntStepper(d.sizes.getOrNull(livello - 1) ?: 0, 0..40, edit) { nuovo ->
                    val misure = MutableList(maxOf(d.sizes.size, livello)) { i ->
                        d.sizes.getOrNull(i) ?: 0
                    }
                    misure[livello - 1] = nuovo
                    // Gli zeri in coda si tolgono: una lista di soli zeri e' la lista
                    // vuota, cioe' «dividi in parti uguali», e tenerla lunga farebbe
                    // sembrare configurato qualcosa che non lo e'.
                    onChange(misure.dropLastWhile { it == 0 })
                }
            }
        }
    }
}

@Composable
private fun NomiDivisioni(
    d: DivisionsConfig,
    edit: Boolean,
    onChange: (List<String>) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Text("Come si chiamano", style = MFootType.rowTitle, color = MFootColors.ink)
        Spacer(Modifier.height(5.dp))
        Text(
            "Dalla massima in giù. Chiamale come volete: i nomi dei bar dove vi vedete " +
                "valgono più di \"Serie A\".",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
        Spacer(Modifier.height(10.dp))

        (1..d.count).forEach { level ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$level.",
                    style = MFootType.value,
                    color = MFootColors.ink3,
                    modifier = Modifier.padding(end = 10.dp),
                )
                NameField(
                    value = d.nameOf(level),
                    enabled = edit,
                    onChange = { nuovo ->
                        // La lista si allunga fino al livello toccato: senza, scrivere il
                        // nome della terza divisione quando ce ne sono due nominate
                        // finirebbe nel vuoto.
                        val nomi = MutableList(maxOf(d.names.size, level)) { i ->
                            d.names.getOrNull(i) ?: d.nameOf(i + 1)
                        }
                        nomi[level - 1] = nuovo
                        onChange(nomi)
                    },
                )
            }
        }
    }
    Hairline()
}

/**
 * Posizione per posizione, cosa capita a chi ci arriva.
 *
 * Si mostra la **seconda** divisione, perche' con tre o piu' livelli e' di mezzo ed e'
 * l'unica dove valgono tutte le regole insieme: dalla prima non si sale e dall'ultima non
 * si scende, quindi mostrare quelle nasconderebbe meta' di cio' che si sta configurando.
 *
 * Le divisioni finte sono **tante quante quelle vere**, e non e' un dettaglio: costruendone
 * sempre tre, con due divisioni configurate la seconda risulterebbe di mezzo e la scaletta
 * mostrerebbe playout e retrocessioni da un livello da cui non si scende. Una bugia
 * dettagliata e convincente, che e' il tipo peggiore.
 */
@Composable
private fun ScalettaFineStagione(config: LeagueConfig) {
    val d = config.divisions
    val perDivisione = config.setup.totalClubs / d.count
    if (perDivisione < 2) {
        Spacer(Modifier.height(MFootSpacing.section))
        Notice(
            "${config.setup.totalClubs} club in ${d.count} divisioni fanno $perDivisione " +
                "squadre a testa: non è un campionato.",
            MFootColors.gamble,
        )
        return
    }

    // I club sono finti: serve l'ordine delle posizioni, non chi le occupa.
    val finte = (1..d.count).map { level ->
        Division(
            level = level,
            name = d.nameOf(level),
            clubs = (1..perDivisione).map { ClubId((level * 100 + it).toLong()) },
        )
    }
    val esiti = SeasonEnd
        .settle(finte, finte.associate { it.level to it.clubs }, DivisionRules.of(d))
        .filter { it.level == 2 }
        .sortedBy { it.position }

    val rules = DivisionRules.of(d)

    Spacer(Modifier.height(MFootSpacing.section))
    Text(
        "A fine stagione, in ${d.nameOf(2)}",
        style = MFootType.rowTitle,
        color = MFootColors.ink,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        if (d.count >= 3) {
            "$perDivisione squadre per divisione. Dalla ${d.nameOf(1)} non si sale e dalla " +
                "${d.nameOf(d.count)} non si scende: qui in mezzo valgono tutte le regole."
        } else {
            "$perDivisione squadre per divisione. Con due divisioni la ${d.nameOf(2)} è " +
                "l'ultima, quindi da qui si sale ma non si scende."
        },
        style = MFootType.chip,
        color = MFootColors.ink3,
    )
    Spacer(Modifier.height(12.dp))

    esiti.forEach { fate ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${fate.position}ª",
                style = MFootType.value,
                color = MFootColors.ink3,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                fate.outcome.label,
                style = MFootType.rowTitle,
                color = coloreEsito(fate.outcome),
                modifier = Modifier.weight(1f),
            )
        }
        Hairline()
    }

    Spacer(Modifier.height(MFootSpacing.section))
    if (rules.isBalanced) {
        Notice(
            "Salgono ${rules.totalMoves} e scendono ${rules.totalDrops}: le divisioni " +
                "restano della stessa dimensione ogni stagione.",
            MFootColors.elite,
        )
    } else {
        Notice(
            "Salgono ${rules.totalMoves} e scendono ${rules.totalDrops}. Ogni stagione una " +
                "divisione si gonfia e un'altra si svuota, e dopo due nessuno sa più " +
                "rimetterle a posto. Ricorda che ogni spareggio assegna un posto solo.",
            MFootColors.gamble,
        )
    }
}

private fun coloreEsito(outcome: SeasonOutcome) = when (outcome) {
    SeasonOutcome.CAMPIONE, SeasonOutcome.PROMOSSO -> MFootColors.elite
    SeasonOutcome.PLAYOFF, SeasonOutcome.PLAYOUT -> MFootColors.gamble
    SeasonOutcome.RETROCESSO -> MFootColors.low
    SeasonOutcome.RESTA -> MFootColors.ink2
}

// ----------------------------------------------------------------------------- economia

@Composable
private fun Economia(config: LeagueConfig, edit: Boolean, onChange: (LeagueConfig) -> Unit) {
    val e = config.economy
    fun set(block: dev.mfoot.core.config.EconomyConfig.() -> dev.mfoot.core.config.EconomyConfig) =
        onChange(config.copy(economy = e.block()))

    SettingRow(
        "Budget iniziale",
        "Quanto ha ogni club alla partenza. Da qui deriva tutto il listino: alzalo e i " +
            "prezzi salgono insieme, senza toccare nient'altro.",
    ) { MoneyField(e.startingCredits, edit) { set { copy(startingCredits = it) } } }

    SettingRow(
        "Quanto vale il migliore del mondo",
        "In frazione del budget: 0,65 significa che il fuoriclasse assoluto costa il 65% " +
            "del patrimonio iniziale. Abbassarlo mette i campioni alla portata di tutti.",
    ) { DecimalField(e.topPlayerBudgetShare, edit) { set { copy(topPlayerBudgetShare = it) } } }

    GroupTitle("Entrate")

    SettingRow(
        "Entrata ricorrente",
        "Quanto entra ogni volta.",
    ) { MoneyField(e.recurringIncome, edit) { set { copy(recurringIncome = it) } } }

    EnumRow(
        label = "Ogni quanto",
        help = "Settimana e mese sono di calendario vero, non giornate di gioco: " +
            "l'accredito arriva il lunedì e il primo del mese.",
        options = IncomeCadence.entries,
        selected = e.incomeCadence,
        enabled = edit,
        labelOf = ::cadenza,
    ) { set { copy(incomeCadence = it) } }

    GroupTitle("Premi")

    SettingRow("Premio vittoria", null) {
        MoneyField(e.winPrize, edit) { set { copy(winPrize = it) } }
    }
    SettingRow("Premio pareggio", null) {
        MoneyField(e.drawPrize, edit) { set { copy(drawPrize = it) } }
    }
    PrizeList(e.placementPrizes, edit) { set { copy(placementPrizes = it) } }

    GroupTitle("Stipendi e contratti")

    SettingRow(
        "Stipendi attivi",
        "Spegnendoli, avere una rosa piena non costa niente e la profondità diventa " +
            "gratis.",
    ) { Switch(e.wagesEnabled, edit) { set { copy(wagesEnabled = it) } } }

    SettingRow(
        "Peso degli stipendi",
        "Quanto pesa l'ingaggio in rapporto all'overall. Alzandolo i fuoriclasse " +
            "diventano un lusso da mantenere, non solo da comprare.",
    ) { DecimalField(e.wageFactor, edit) { set { copy(wageFactor = it) } } }

    SettingRow(
        "Costo del rinnovo",
        "Frazione di quanto era stato pagato. A 0,5 rinnovare costa metà dell'acquisto.",
    ) { DecimalField(e.renewalCostFraction, edit) { set { copy(renewalCostFraction = it) } } }

    SettingRow(
        "Saldo negativo permesso",
        "Se spento, gli stipendi si fermano a zero invece di mandare un club in rosso " +
            "perpetuo da cui non potrebbe più uscire.",
    ) { Switch(e.negativeBalanceAllowed, edit) { set { copy(negativeBalanceAllowed = it) } } }
}

private fun cadenza(c: IncomeCadence): String = when (c) {
    IncomeCadence.PER_GIORNATA -> "Ogni giornata"
    IncomeCadence.PER_SETTIMANA -> "A settimana"
    IncomeCadence.PER_MESE -> "Al mese"
    IncomeCadence.FINE_COMPETIZIONE -> "A fine competizione"
    IncomeCadence.MAI -> "Mai"
}

// ------------------------------------------------------------------------------ mercato

@Composable
private fun Mercato(config: LeagueConfig, edit: Boolean, onChange: (LeagueConfig) -> Unit) {
    val m = config.market
    fun set(block: dev.mfoot.core.config.MarketConfig.() -> dev.mfoot.core.config.MarketConfig) =
        onChange(config.copy(market = m.block()))

    GroupTitle("Aste a regime")

    SettingRow("Durata di un'asta", "In minuti reali.") {
        IntStepper(m.auctionDurationMinutes, 5..1440, edit) {
            set { copy(auctionDurationMinutes = it) }
        }
    }
    SettingRow(
        "Rilancio minimo",
        "Lo scatto più piccolo ammesso. Troppo basso e le aste diventano una guerra di " +
            "spiccioli.",
    ) { MoneyField(m.minimumRaise, edit) { set { copy(minimumRaise = it) } } }

    SettingRow(
        "Aste in parallelo per club",
        "Quante ne puoi tenere aperte insieme. Il tetto impedisce a un club di bloccare " +
            "mezzo listino mentre decide.",
    ) {
        IntStepper(m.maxParallelAuctionsPerClub, 1..20, edit) {
            set { copy(maxParallelAuctionsPerClub = it) }
        }
    }

    SettingRow(
        "Anti-snipe",
        "Un rilancio negli ultimi secondi prolunga l'asta: vince chi valuta di più, non " +
            "chi ha il dito più veloce.",
    ) { Switch(m.antiSnipeEnabled, edit) { set { copy(antiSnipeEnabled = it) } } }

    GroupTitle("Asta iniziale")

    SettingRow(
        "Aste in parallelo all'inizio",
        "Con le rose vuote servono molte più aste: dieci club AI a tre a testa " +
            "impiegherebbero giorni a completare le squadre.",
    ) {
        IntStepper(m.initialParallelAuctionsPerClub, 1..20, edit) {
            set { copy(initialParallelAuctionsPerClub = it) }
        }
    }
    SettingRow("Durata asta iniziale", "In minuti. Corta, per riempire in fretta.") {
        IntStepper(m.initialAuctionDurationMinutes, 3..240, edit) {
            set { copy(initialAuctionDurationMinutes = it) }
        }
    }

    GroupTitle("Contratti")

    SettingRow(
        "Durata contratti iniziali",
        "In giornate di gioco, non giorni reali: cambiare il ritmo della lega non accorcia " +
            "i contratti.",
    ) {
        IntStepper(m.defaultContractMatchDays, 1..200, edit) {
            set { copy(defaultContractMatchDays = it) }
        }
    }
    SettingRow("Prestiti", null) { Switch(m.loansEnabled, edit) { set { copy(loansEnabled = it) } } }
    SettingRow("Clausole rescissorie", null) {
        Switch(m.releaseClausesEnabled, edit) { set { copy(releaseClausesEnabled = it) } }
    }
    SettingRow("Scambi", null) { Switch(m.swapsEnabled, edit) { set { copy(swapsEnabled = it) } } }
}

// ------------------------------------------------------------------------------ partita

@Composable
private fun Partita(config: LeagueConfig, edit: Boolean, onChange: (LeagueConfig) -> Unit) {
    val r = config.rules
    fun set(block: dev.mfoot.core.config.RulesConfig.() -> dev.mfoot.core.config.RulesConfig) =
        onChange(config.copy(rules = r.block()))

    GroupTitle("Infortuni")

    SettingRow(
        "Infortuni attivi",
        "Spegnendoli non si infortuna nessuno mai, e la rosa profonda serve solo per la " +
            "stanchezza.",
    ) { Switch(r.injuriesEnabled, edit) { set { copy(injuriesEnabled = it) } } }

    SettingRow(
        "Tasso di infortunio",
        "Moltiplicatore sul valore base. A 0,5 gli infortuni sono la metà; a 2 il doppio.",
    ) { DecimalField(r.injuryRateMultiplier, edit) { set { copy(injuryRateMultiplier = it) } } }

    EnumRow(
        label = "Gravità",
        help = "Quanto durano gli stop.",
        options = InjurySeverity.entries,
        selected = r.injurySeverity,
        enabled = edit,
        labelOf = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
    ) { set { copy(injurySeverity = it) } }

    GroupTitle("Cartellini")

    SettingRow("Cartellini gialli", null) {
        Switch(r.yellowCardsEnabled, edit) { set { copy(yellowCardsEnabled = it) } }
    }
    SettingRow("Squalifiche", "Se spente, i cartellini restano ma nessuno salta partite.") {
        Switch(r.suspensionsEnabled, edit) { set { copy(suspensionsEnabled = it) } }
    }
    SettingRow("Gialli per una squalifica", null) {
        IntStepper(r.yellowCardsForSuspension, 1..15, edit) {
            set { copy(yellowCardsForSuspension = it) }
        }
    }

    GroupTitle("La diretta")

    EnumRow(
        label = "Velocità della partita",
        help = "Quanto dura la diretta in tempo reale. Il risultato non cambia: cambia " +
            "quanto tempo si sta a guardarla.",
        options = MatchSpeed.entries,
        selected = config.calendar.matchSpeed,
        enabled = edit,
        labelOf = { it.label },
    ) { onChange(config.copy(calendar = config.calendar.copy(matchSpeed = it))) }

    SettingRow(
        "Finestra dell'intervallo",
        "Minuti reali per cambiare formazione a metà partita.",
    ) {
        IntStepper(config.calendar.halfTimeWindowMinutes, 0..30, edit) {
            onChange(config.copy(calendar = config.calendar.copy(halfTimeWindowMinutes = it)))
        }
    }

    GroupTitle("L'ora della lega")

    // Senza questa impostazione il codice faceva la cosa piu' comoda: prendeva le 21:00
    // scelte qui e le marcava come UTC. In Italia d'estate la partita finiva in calendario
    // alle 23:00, e nessuno capiva perche'.
    EnumRow(
        label = "Fuso orario",
        help = "\"Alle 21\" vuol dire le 21 di qui, per tutti. Chi guarda da un altro " +
            "paese legge lo stesso orario, non il proprio.",
        options = FUSI,
        selected = FUSI.firstOrNull { it.zone == config.calendar.timeZone } ?: FUSI.first(),
        enabled = edit,
        labelOf = { it.label },
    ) { onChange(config.copy(calendar = config.calendar.copy(timeZone = it.zone))) }
}

/**
 * I fusi fra cui scegliere.
 *
 * Un elenco corto e non tutti i seicento identificatori del sistema: questa lega la
 * giocano una ventina di amici, e una tendina con seicento voci per sceglierne uno che
 * nel novanta per cento dei casi e' Roma non e' liberta', e' un ostacolo.
 */
private enum class FusoOrario(val label: String, val zone: java.time.ZoneId) {
    ROMA("Italia (Roma)", java.time.ZoneId.of("Europe/Rome")),
    LONDRA("Regno Unito (Londra)", java.time.ZoneId.of("Europe/London")),
    MADRID("Spagna (Madrid)", java.time.ZoneId.of("Europe/Madrid")),
    ATENE("Grecia (Atene)", java.time.ZoneId.of("Europe/Athens")),
    NEW_YORK("Stati Uniti est (New York)", java.time.ZoneId.of("America/New_York")),
    SAN_PAOLO("Brasile (San Paolo)", java.time.ZoneId.of("America/Sao_Paulo")),
    UTC("UTC", java.time.ZoneId.of("UTC")),
}

private val FUSI = FusoOrario.entries

// ----------------------------------------------------------------------------- crescita

@Composable
private fun Crescita(config: LeagueConfig, edit: Boolean, onChange: (LeagueConfig) -> Unit) {
    val r = config.rules
    fun set(block: dev.mfoot.core.config.RulesConfig.() -> dev.mfoot.core.config.RulesConfig) =
        onChange(config.copy(rules = r.block()))

    SettingRow(
        "Velocità di crescita",
        "Moltiplicatore globale. A 2 i giovani maturano nella metà del tempo, e una " +
            "stagione basta a cambiare la gerarchia della lega.",
    ) { DecimalField(r.growthMultiplier, edit) { set { copy(growthMultiplier = it) } } }

    GroupTitle("Le età che contano")

    SettingRow("Inizio del picco", null) {
        IntStepper(r.peakAgeStart, 16..30, edit) { set { copy(peakAgeStart = it) } }
    }
    SettingRow("Fine del picco", null) {
        IntStepper(r.peakAgeEnd, r.peakAgeStart..34, edit) { set { copy(peakAgeEnd = it) } }
    }
    SettingRow("Età del declino", "Da qui in poi giocare consuma invece di far crescere.") {
        IntStepper(r.declineAge, r.peakAgeEnd..40, edit) { set { copy(declineAge = it) } }
    }

    GroupTitle("Primavera")

    SettingRow("Primavera attiva", null) {
        Switch(r.youthTeamEnabled, edit) { set { copy(youthTeamEnabled = it) } }
    }
    SettingRow("Età massima Primavera", null) {
        IntStepper(r.youthMaxAge, 16..25, edit) { set { copy(youthMaxAge = it) } }
    }

    GroupTitle("Morale")

    SettingRow(
        "Morale attivo",
        "Con il morale spento nessuno chiede la cessione e giocare poco non ha " +
            "conseguenze.",
    ) { Switch(r.moraleEnabled, edit) { set { copy(moraleEnabled = it) } } }

    SettingRow("Conversazioni", "Poter parlare con un giocatore scontento.") {
        Switch(r.conversationsEnabled, edit) { set { copy(conversationsEnabled = it) } }
    }
    SettingRow("Soglia di morale basso", "Sotto questo valore comincia a protestare.") {
        IntStepper(r.lowMoraleThreshold, 0..80, edit) { set { copy(lowMoraleThreshold = it) } }
    }

    GroupTitle("Il tuo giocatore in campo")

    SettingRow(
        "Deve giocare titolare",
        "Senza l'obbligo, il custom parte da 65 in un mondo dove i migliori stanno a 91: " +
            "non entrerebbe mai, quindi non crescerebbe mai.",
    ) { Switch(r.customMustStart, edit) { set { copy(customMustStart = it) } } }

    SettingRow("Minuti minimi", null) {
        IntStepper(r.customMinimumMinutes, 0..90, edit) { set { copy(customMinimumMinutes = it) } }
    }
    SettingRow(
        "Quanto cresce più in fretta",
        "Moltiplicatore sulla sua crescita rispetto ai giocatori generati.",
    ) { DecimalField(r.customGrowthMultiplier, edit) { set { copy(customGrowthMultiplier = it) } } }
}

// ------------------------------------------------------------------------------- custom

@Composable
private fun Custom(config: LeagueConfig, edit: Boolean, onChange: (LeagueConfig) -> Unit) {
    val c = config.custom
    fun set(block: dev.mfoot.core.config.CustomPlayerConfig.() -> dev.mfoot.core.config.CustomPlayerConfig) =
        onChange(config.copy(custom = c.block()))

    SettingRow(
        "Overall di partenza",
        "Quanto vale il giocatore appena aperta la schermata, prima di spendere un punto.",
    ) { IntStepper(c.baseOverall, 40..85, edit) { set { copy(baseOverall = it) } } }

    SettingRow(
        "Punti da distribuire",
        "Il budget abilità. Alzarlo permette giocatori più forti, e toglie la rinuncia " +
            "che rende la schermata una scelta.",
    ) { IntStepper(c.skillBudget, 0..400, edit) { set { copy(skillBudget = it) } } }

    SettingRow(
        "Costo di una stella",
        "Piede debole e tecnica. A 10 punti, portarle entrambe a cinque costa 80 dei 100 " +
            "punti disponibili.",
    ) { IntStepper(c.starCost, 0..50, edit) { set { copy(starCost = it) } } }

    GroupTitle("Età")

    SettingRow("Età minima", null) {
        IntStepper(c.minAge, 14..25, edit) { set { copy(minAge = it) } }
    }
    SettingRow("Età massima", null) {
        IntStepper(c.maxAge, c.minAge..35, edit) { set { copy(maxAge = it) } }
    }
    SettingRow("Età proposta", null) {
        IntStepper(c.defaultAge, c.minAge..c.maxAge, edit) { set { copy(defaultAge = it) } }
    }

    GroupTitle("Potenziale")

    SettingRow(
        "Quanto può crescere",
        "Punti di potenziale sopra l'overall con cui esce. Generoso di proposito: non si " +
            "può vendere e deve giocare, quindi il tetto alto è ciò che rende " +
            "l'obbligo una scommessa invece di una tassa.",
    ) { IntStepper(c.potentialBonus, 0..40, edit) { set { copy(potentialBonus = it) } } }

    SettingRow("Tetto assoluto", null) {
        IntStepper(c.potentialCeiling, 70..99, edit) { set { copy(potentialCeiling = it) } }
    }
}
