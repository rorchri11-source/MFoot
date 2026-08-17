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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.SettingsSection
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.PrimaryButton
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.core.config.IncomeCadence
import dev.mfoot.core.config.InjurySeverity
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.config.MatchSpeed

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
                "Solo l'amministratore della lega puo' cambiare le regole. " +
                    "Puoi guardarle, non modificarle.",
                MFootColors.ink2,
            )
            Spacer(Modifier.height(MFootSpacing.section))
        }

        SettingsSection.entries.forEach { section ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MFootColors.core, MFootShapes.field)
                    .border(1.dp, MFootColors.lineStrong, MFootShapes.field)
                    .clickable { onOpen(section) }
                    .padding(15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(section.label, style = MFootType.rowTitle, color = MFootColors.ink)
                    Text(descrizione(section), style = MFootType.chip, color = MFootColors.ink3)
                }
                Text("›", style = MFootType.price, color = MFootColors.ink3)
            }
            Spacer(Modifier.height(9.dp))
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Ogni numero qui dentro vale per questa lega e per nessun'altra. " +
                "Cambiarlo non richiede di aggiornare l'app: le regole stanno sul server.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
    }
}

private fun descrizione(section: SettingsSection): String = when (section) {
    SettingsSection.SQUADRE -> "Quanti club, quanti gestiti dall'AI, minimo e massimo in rosa"
    SettingsSection.ECONOMIA -> "Budget, entrate, premi, stipendi, rinnovi"
    SettingsSection.MERCATO -> "Aste, rilanci, contratti, prestiti"
    SettingsSection.PARTITA -> "Infortuni, cartellini, velocita' della diretta"
    SettingsSection.CRESCITA -> "Velocita' di crescita, eta' di picco, Primavera, morale"
    SettingsSection.CUSTOM -> "Budget punti, costo delle stelle, eta' di partenza"
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
                SettingsSection.ECONOMIA -> Economia(config, canEdit, onChange)
                SettingsSection.MERCATO -> Mercato(config, canEdit, onChange)
                SettingsSection.PARTITA -> Partita(config, canEdit, onChange)
                SettingsSection.CRESCITA -> Crescita(config, canEdit, onChange)
                SettingsSection.CUSTOM -> Custom(config, canEdit, onChange)
            }
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
        "Quante squadre in tutto. Cambiarlo a stagione iniziata non rifa' il calendario " +
            "delle competizioni gia' create.",
    ) { IntStepper(s.totalClubs, 2..40, edit) { set { copy(totalClubs = it) } } }

    SettingRow(
        "Gestiti dall'AI",
        "Gli altri restano liberi per le persone. Le AI comprano, schierano e giocano da " +
            "sole, e non aspettano nessuno.",
    ) { IntStepper(s.aiClubs, 0..s.totalClubs, edit) { set { copy(aiClubs = it) } } }

    SettingRow(
        "Minimo in rosa",
        "Sotto questo numero la squadra non scende in campo e le partite si rinviano. " +
            "Alzarlo obbliga a comprare piu' riserve.",
    ) { IntStepper(s.minSquadSize, 11..40, edit) { set { copy(minSquadSize = it) } } }

    SettingRow(
        "Massimo in rosa",
        "Il tetto agli acquisti. Serve a evitare che chi ha budget si prenda mezzo listino.",
    ) { IntStepper(s.maxSquadSize, s.minSquadSize..60, edit) { set { copy(maxSquadSize = it) } } }
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
            "l'accredito arriva il lunedi' e il primo del mese.",
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
        "Spegnendoli, avere una rosa piena non costa niente e la profondita' diventa " +
            "gratis.",
    ) { Switch(e.wagesEnabled, edit) { set { copy(wagesEnabled = it) } } }

    SettingRow(
        "Peso degli stipendi",
        "Quanto pesa l'ingaggio in rapporto all'overall. Alzandolo i fuoriclasse " +
            "diventano un lusso da mantenere, non solo da comprare.",
    ) { DecimalField(e.wageFactor, edit) { set { copy(wageFactor = it) } } }

    SettingRow(
        "Costo del rinnovo",
        "Frazione di quanto era stato pagato. A 0,5 rinnovare costa meta' dell'acquisto.",
    ) { DecimalField(e.renewalCostFraction, edit) { set { copy(renewalCostFraction = it) } } }

    SettingRow(
        "Saldo negativo permesso",
        "Se spento, gli stipendi si fermano a zero invece di mandare un club in rosso " +
            "perpetuo da cui non potrebbe piu' uscire.",
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
        "Lo scatto piu' piccolo ammesso. Troppo basso e le aste diventano una guerra di " +
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
        "Un rilancio negli ultimi secondi prolunga l'asta: vince chi valuta di piu', non " +
            "chi ha il dito piu' veloce.",
    ) { Switch(m.antiSnipeEnabled, edit) { set { copy(antiSnipeEnabled = it) } } }

    GroupTitle("Asta iniziale")

    SettingRow(
        "Aste in parallelo all'inizio",
        "Con le rose vuote servono molte piu' aste: dieci club AI a tre a testa " +
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
        "Moltiplicatore sul valore base. A 0,5 gli infortuni sono la meta'; a 2 il doppio.",
    ) { DecimalField(r.injuryRateMultiplier, edit) { set { copy(injuryRateMultiplier = it) } } }

    EnumRow(
        label = "Gravita'",
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
        label = "Velocita' della partita",
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
}

// ----------------------------------------------------------------------------- crescita

@Composable
private fun Crescita(config: LeagueConfig, edit: Boolean, onChange: (LeagueConfig) -> Unit) {
    val r = config.rules
    fun set(block: dev.mfoot.core.config.RulesConfig.() -> dev.mfoot.core.config.RulesConfig) =
        onChange(config.copy(rules = r.block()))

    SettingRow(
        "Velocita' di crescita",
        "Moltiplicatore globale. A 2 i giovani maturano nella meta' del tempo, e una " +
            "stagione basta a cambiare la gerarchia della lega.",
    ) { DecimalField(r.growthMultiplier, edit) { set { copy(growthMultiplier = it) } } }

    GroupTitle("Le eta' che contano")

    SettingRow("Inizio del picco", null) {
        IntStepper(r.peakAgeStart, 16..30, edit) { set { copy(peakAgeStart = it) } }
    }
    SettingRow("Fine del picco", null) {
        IntStepper(r.peakAgeEnd, r.peakAgeStart..34, edit) { set { copy(peakAgeEnd = it) } }
    }
    SettingRow("Eta' del declino", "Da qui in poi giocare consuma invece di far crescere.") {
        IntStepper(r.declineAge, r.peakAgeEnd..40, edit) { set { copy(declineAge = it) } }
    }

    GroupTitle("Primavera")

    SettingRow("Primavera attiva", null) {
        Switch(r.youthTeamEnabled, edit) { set { copy(youthTeamEnabled = it) } }
    }
    SettingRow("Eta' massima Primavera", null) {
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
        "Quanto cresce piu' in fretta",
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
        "Il budget abilita'. Alzarlo permette giocatori piu' forti, e toglie la rinuncia " +
            "che rende la schermata una scelta.",
    ) { IntStepper(c.skillBudget, 0..400, edit) { set { copy(skillBudget = it) } } }

    SettingRow(
        "Costo di una stella",
        "Piede debole e tecnica. A 10 punti, portarle entrambe a cinque costa 80 dei 100 " +
            "punti disponibili.",
    ) { IntStepper(c.starCost, 0..50, edit) { set { copy(starCost = it) } } }

    GroupTitle("Eta'")

    SettingRow("Eta' minima", null) {
        IntStepper(c.minAge, 14..25, edit) { set { copy(minAge = it) } }
    }
    SettingRow("Eta' massima", null) {
        IntStepper(c.maxAge, c.minAge..35, edit) { set { copy(maxAge = it) } }
    }
    SettingRow("Eta' proposta", null) {
        IntStepper(c.defaultAge, c.minAge..c.maxAge, edit) { set { copy(defaultAge = it) } }
    }

    GroupTitle("Potenziale")

    SettingRow(
        "Quanto puo' crescere",
        "Punti di potenziale sopra l'overall con cui esce. Generoso di proposito: non si " +
            "puo' vendere e deve giocare, quindi il tetto alto e' cio' che rende " +
            "l'obbligo una scommessa invece di una tassa.",
    ) { IntStepper(c.potentialBonus, 0..40, edit) { set { copy(potentialBonus = it) } } }

    SettingRow("Tetto assoluto", null) {
        IntStepper(c.potentialCeiling, 70..99, edit) { set { copy(potentialCeiling = it) } }
    }
}
