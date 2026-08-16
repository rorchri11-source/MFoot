package dev.mfoot.core.config

import java.time.LocalDate

/** Gravita' di un problema di configurazione. */
enum class IssueLevel {
    /** La lega non puo' partire: e' matematicamente irrisolvibile. */
    ERRORE,

    /** La lega parte, ma probabilmente non sara' divertente. */
    AVVISO,
}

data class ConfigIssue(
    val level: IssueLevel,
    val field: String,
    val message: String,
    /** Cosa fare per risolvere, con il numero concreto quando esiste. */
    val suggestion: String? = null,
)

data class ValidationResult(val issues: List<ConfigIssue>) {
    val errors: List<ConfigIssue> get() = issues.filter { it.level == IssueLevel.ERRORE }
    val warnings: List<ConfigIssue> get() = issues.filter { it.level == IssueLevel.AVVISO }
    val isValid: Boolean get() = errors.isEmpty()

    fun describe(): String =
        if (issues.isEmpty()) "Configurazione valida."
        else issues.joinToString("\n") { issue ->
            val tag = if (issue.level == IssueLevel.ERRORE) "ERRORE" else "AVVISO"
            "[$tag] ${issue.field}: ${issue.message}" + (issue.suggestion?.let { "  -> $it" } ?: "")
        }
}

/**
 * Controlla che una configurazione di lega sia giocabile **prima** che la lega parta.
 *
 * Senza questo passaggio, un admin puo' impostare in buona fede 50 crediti iniziali con
 * rosa minima 16, o un calendario che non ha abbastanza giorni per le partite previste,
 * e il problema salta fuori a mercato gia' aperto, quando ormai venti persone hanno
 * speso una serata. Meglio dirlo subito, e dire anche **quale numero** cambiare.
 */
object ConfigValidator {

    fun validate(config: LeagueConfig): ValidationResult {
        val issues = buildList {
            addAll(validateSetup(config))
            addAll(validateWorld(config))
            addAll(validateCalendar(config))
            addAll(validateEconomy(config))
            addAll(validateRules(config))
            addAll(validateMarket(config))
        }
        return ValidationResult(issues)
    }

    // ------------------------------------------------------------------------- setup

    private fun validateSetup(config: LeagueConfig): List<ConfigIssue> = buildList {
        val s = config.setup

        if (s.totalClubs < 2) {
            add(error("setup.totalClubs", "Servono almeno 2 club per giocare.", "Imposta almeno 2."))
        }
        if (s.aiClubs < 0 || s.aiClubs > s.totalClubs) {
            add(error("setup.aiClubs", "I club AI (${s.aiClubs}) devono stare fra 0 e ${s.totalClubs}."))
        }
        if (s.mode == LeagueMode.SOLO_MULTIPLAYER && s.aiClubs > 0) {
            add(error(
                "setup.aiClubs",
                "La modalita' e' 'solo multiplayer' ma sono previsti ${s.aiClubs} club AI.",
                "Porta i club AI a 0 oppure passa a 'Multiplayer + AI'.",
            ))
        }
        if (s.humanClubs < 1) {
            add(error("setup.aiClubs", "Non resta nessun posto per i giocatori umani."))
        }
        if (s.minSquadSize > s.maxSquadSize) {
            add(error(
                "setup.minSquadSize",
                "Rosa minima (${s.minSquadSize}) maggiore della massima (${s.maxSquadSize}).",
            ))
        }
        if (s.minSquadSize < 11) {
            add(warning(
                "setup.minSquadSize",
                "Con meno di 11 giocatori non si puo' schierare una formazione completa.",
                "Alza la rosa minima ad almeno 11, meglio 16 con due partite al giorno.",
            ))
        }
        if (s.accessCode.isBlank()) {
            add(error("setup.accessCode", "Serve un codice d'accesso per far entrare gli altri."))
        }
    }

    // ------------------------------------------------------------------------- mondo

    private fun validateWorld(config: LeagueConfig): List<ConfigIssue> = buildList {
        val s = config.setup
        val w = config.world

        val playersNeeded = s.totalClubs * s.minSquadSize
        if (w.playerCount < playersNeeded) {
            add(error(
                "world.tiers",
                "Il mondo genera ${w.playerCount} giocatori ma ne servono almeno $playersNeeded " +
                    "per ${s.totalClubs} club da ${s.minSquadSize}.",
                "Alza il numero di giocatori generati ad almeno ${(playersNeeded * 1.6).toInt()}.",
            ))
        } else if (w.playerCount < playersNeeded * 1.3) {
            add(warning(
                "world.tiers",
                "Ci sono solo ${w.playerCount} giocatori per $playersNeeded posti: il mercato " +
                    "si svuotera' quasi subito e non ci sara' piu' niente da comprare.",
                "Meglio almeno ${(playersNeeded * 1.6).toInt()} giocatori.",
            ))
        }

        // Servono abbastanza portieri: almeno uno per club, meglio due.
        val gkQuota = w.positionQuotas.entries
            .firstOrNull { it.key.isGoalkeeper }?.value ?: 0.0
        val quotaSum = w.positionQuotas.values.sum()
        val expectedGk = if (quotaSum > 0) (w.playerCount * gkQuota / quotaSum).toInt() else 0
        if (expectedGk < s.totalClubs) {
            add(error(
                "world.positionQuotas",
                "Il mondo generera' circa $expectedGk portieri per ${s.totalClubs} club.",
                "Alza la quota dei portieri o il numero totale di giocatori.",
            ))
        }

        if (w.minAge > w.maxAge) {
            add(error("world.minAge", "Eta' minima (${w.minAge}) maggiore della massima (${w.maxAge})."))
        }
        if (w.minPotentialSpread > w.maxPotentialSpread) {
            add(error(
                "world.minPotentialSpread",
                "Forbice di potenziale incoerente: min ${w.minPotentialSpread} > max ${w.maxPotentialSpread}.",
            ))
        }
        if (w.nationalities.isEmpty()) {
            add(error("world.nationalities", "Serve almeno una nazionalita' per generare i nomi."))
        }
    }

    // --------------------------------------------------------------------- calendario

    private fun validateCalendar(config: LeagueConfig): List<ConfigIssue> = buildList {
        val c = config.calendar
        val s = config.setup

        if (!c.endDate.isAfter(c.startDate)) {
            add(error(
                "calendar.endDate",
                "La data di fine (${c.endDate}) non e' dopo quella di inizio (${c.startDate}).",
            ))
            return@buildList
        }

        if (c.kickoffSlots.isEmpty()) {
            add(error("calendar.kickoffSlots", "Serve almeno una fascia oraria di inizio partite."))
            return@buildList
        }

        if (c.matchesPerDayPerClub < 1) {
            add(error("calendar.matchesPerDayPerClub", "Serve almeno una partita al giorno."))
            return@buildList
        }

        if (c.kickoffSlots.size < c.matchesPerDayPerClub) {
            add(error(
                "calendar.kickoffSlots",
                "Sono previste ${c.matchesPerDayPerClub} partite al giorno per club ma ci sono " +
                    "solo ${c.kickoffSlots.size} fasce orarie: due partite non possono iniziare " +
                    "alla stessa ora.",
                "Aggiungi almeno ${c.matchesPerDayPerClub - c.kickoffSlots.size} fascia/e oraria/e.",
            ))
        }

        val availableDays = countAvailableDays(config)
        val matchDaysNeeded = roundRobinMatchDays(s.totalClubs)
        val daysNeeded = ceilDiv(matchDaysNeeded, c.matchesPerDayPerClub)

        if (availableDays < daysNeeded) {
            add(error(
                "calendar.endDate",
                "Un girone con ${s.totalClubs} club richiede $matchDaysNeeded giornate, cioe' " +
                    "almeno $daysNeeded giorni a ${c.matchesPerDayPerClub} partite al giorno. " +
                    "Ne sono disponibili solo $availableDays.",
                "Sposta la fine al ${c.startDate.plusDays((daysNeeded + countRestDays(config)).toLong())} " +
                    "oppure alza le partite al giorno.",
            ))
        } else if (availableDays > daysNeeded * 3) {
            add(warning(
                "calendar.endDate",
                "Ci sono $availableDays giorni disponibili per $daysNeeded giorni di partite: " +
                    "la stagione avra' lunghi periodi vuoti.",
                "Accorcia la finestra o aggiungi una seconda competizione.",
            ))
        }
    }

    // ----------------------------------------------------------------------- economia

    private fun validateEconomy(config: LeagueConfig): List<ConfigIssue> = buildList {
        val e = config.economy
        val s = config.setup

        if (e.startingCredits < 0) {
            add(error("economy.startingCredits", "Il budget iniziale non puo' essere negativo."))
            return@buildList
        }
        if (e.renewalCostFraction < 0.0 || e.renewalCostFraction > 2.0) {
            add(error(
                "economy.renewalCostFraction",
                "Il costo di rinnovo (${e.renewalCostFraction}) deve stare fra 0 e 2 volte il prezzo pagato.",
            ))
        }

        // Ogni giocatore costa almeno 1 credito: sotto questa soglia la rosa e' impossibile.
        if (e.startingCredits < s.minSquadSize) {
            add(error(
                "economy.startingCredits",
                "Con ${e.startingCredits} crediti non si possono comprare ${s.minSquadSize} " +
                    "giocatori nemmeno pagandoli 1 credito ciascuno.",
                "Alza il budget ad almeno ${s.minSquadSize * 4} crediti.",
            ))
            return@buildList
        }

        val perSlot = e.startingCredits.toDouble() / s.minSquadSize
        if (perSlot < 3.0) {
            add(warning(
                "economy.startingCredits",
                "Restano circa ${"%.1f".format(perSlot)} crediti per giocatore: quasi tutte le " +
                    "aste si chiuderanno a 1 credito e non ci sara' nessuna scelta da fare.",
                "Per aste interessanti servono almeno ${s.minSquadSize * 8} crediti.",
            ))
        }
        if (perSlot > 60.0) {
            add(warning(
                "economy.startingCredits",
                "Ci sono circa ${perSlot.toInt()} crediti per giocatore: tutti potranno " +
                    "permettersi quasi tutto e le aste perderanno di significato.",
                "Considera di scendere verso ${s.minSquadSize * 20} crediti.",
            ))
        }

        if (e.wagesEnabled && e.recurringIncome <= 0) {
            add(warning(
                "economy.recurringIncome",
                "Gli stipendi sono attivi ma non c'e' nessuna entrata ricorrente: i club " +
                    "andranno progressivamente in rosso senza poterci fare niente.",
                "Attiva un'entrata per giornata oppure disattiva gli stipendi.",
            ))
        }
        if (e.placementPrizes.isEmpty()) {
            add(warning(
                "economy.placementPrizes",
                "Nessun premio di piazzamento: vincere il campionato non dara' nessun vantaggio.",
            ))
        }
    }

    // ------------------------------------------------------------------------- regole

    private fun validateRules(config: LeagueConfig): List<ConfigIssue> = buildList {
        val r = config.rules
        val w = config.world

        if (!(r.peakAgeStart <= r.peakAgeEnd && r.peakAgeEnd <= r.plateauAgeEnd && r.plateauAgeEnd <= r.declineAge)) {
            add(error(
                "rules.peakAgeStart",
                "Le fasce d'eta' non sono in ordine: picco ${r.peakAgeStart}-${r.peakAgeEnd}, " +
                    "plateau fino a ${r.plateauAgeEnd}, declino da ${r.declineAge}.",
                "Servono valori crescenti, per esempio 22 / 26 / 28 / 32.",
            ))
        }
        if (r.customMinimumMinutes > 90) {
            add(error(
                "rules.customMinimumMinutes",
                "Il minimo di minuti per il player custom (${r.customMinimumMinutes}) supera la durata di una partita.",
                "Scendi a 90 o meno.",
            ))
        }
        if (r.youthTeamEnabled && r.youthMaxAge < w.minAge) {
            add(error(
                "rules.youthMaxAge",
                "L'eta' massima della Primavera (${r.youthMaxAge}) e' sotto l'eta' minima del mondo (${w.minAge}): " +
                    "la Primavera resterebbe sempre vuota.",
                "Alza il limite ad almeno ${w.minAge + 3}.",
            ))
        }
        if (r.growthMultiplier <= 0.0) {
            add(error("rules.growthMultiplier", "Il moltiplicatore di crescita deve essere positivo."))
        }
        if (r.friendliesEnabled && r.friendliesCountForGrowth) {
            add(warning(
                "rules.friendliesCountForGrowth",
                "Le amichevoli fanno crescere i giocatori: due amici possono concordarne " +
                    "quindici al giorno e far esplodere le rose in un pomeriggio.",
                "Disattiva la crescita da amichevole, o dimezzala.",
            ))
        }
        if (r.moraleEnabled && !r.conversationsEnabled) {
            add(warning(
                "rules.conversationsEnabled",
                "Il morale e' attivo ma le conversazioni no: un giocatore scontento non potra' " +
                    "essere recuperato in nessun modo.",
            ))
        }
    }

    // ------------------------------------------------------------------------ mercato

    private fun validateMarket(config: LeagueConfig): List<ConfigIssue> = buildList {
        val m = config.market

        if (m.auctionDurationMinutes < 1) {
            add(error("market.auctionDurationMinutes", "Un'asta deve durare almeno un minuto."))
        }
        if (m.minimumRaise < 1) {
            add(error("market.minimumRaise", "Il rilancio minimo deve essere almeno 1 credito."))
        }
        if (m.defaultContractMatchDays < 1) {
            add(error("market.defaultContractMatchDays", "Un contratto deve durare almeno una giornata."))
        }
        if (m.minLoanMatchDays > m.maxLoanMatchDays) {
            add(error(
                "market.minLoanMatchDays",
                "Durata prestito incoerente: min ${m.minLoanMatchDays} > max ${m.maxLoanMatchDays}.",
            ))
        }
        if (!m.proxyBiddingEnabled) {
            add(warning(
                "market.proxyBiddingEnabled",
                "Senza offerta massima automatica bisogna presidiare ogni asta di persona: " +
                    "chi lavora o studia perdera' sistematicamente i giocatori che voleva.",
                "Tienila attiva salvo che tutti giochiate alla stessa ora.",
            ))
        }
        if (m.windowMode == MarketWindowMode.FASCE_ORARIE && m.windowSlots.isEmpty()) {
            add(error(
                "market.windowSlots",
                "Il mercato e' limitato a fasce orarie ma non ne e' stata definita nessuna: " +
                    "non si potrebbe mai comprare.",
            ))
        }
        if (m.antiSnipeEnabled && m.antiSnipeSeconds < 10) {
            add(warning(
                "market.antiSnipeSeconds",
                "Un'estensione anti-snipe di ${m.antiSnipeSeconds} secondi e' troppo breve per " +
                    "permettere una risposta reale.",
                "Almeno 30-60 secondi.",
            ))
        }
    }

    // ------------------------------------------------------------------------ utility

    /** Giornate necessarie per un girone all'italiana con [clubs] squadre. */
    fun roundRobinMatchDays(clubs: Int): Int =
        if (clubs < 2) 0 else if (clubs % 2 == 0) clubs - 1 else clubs

    fun countAvailableDays(config: LeagueConfig): Int {
        val c = config.calendar
        var day = c.startDate
        var count = 0
        while (!day.isAfter(c.endDate)) {
            if (isPlayable(day, config)) count++
            day = day.plusDays(1)
        }
        return count
    }

    private fun countRestDays(config: LeagueConfig): Int {
        val c = config.calendar
        var day = c.startDate
        var count = 0
        while (!day.isAfter(c.endDate)) {
            if (!isPlayable(day, config)) count++
            day = day.plusDays(1)
        }
        return count
    }

    fun isPlayable(day: LocalDate, config: LeagueConfig): Boolean {
        val c = config.calendar
        return day.dayOfWeek !in c.restWeekdays && day !in c.restDates
    }

    private fun ceilDiv(a: Int, b: Int): Int = if (b == 0) 0 else (a + b - 1) / b

    private fun error(field: String, message: String, suggestion: String? = null) =
        ConfigIssue(IssueLevel.ERRORE, field, message, suggestion)

    private fun warning(field: String, message: String, suggestion: String? = null) =
        ConfigIssue(IssueLevel.AVVISO, field, message, suggestion)
}
