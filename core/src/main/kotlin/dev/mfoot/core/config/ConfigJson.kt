package dev.mfoot.core.config

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.model.Position
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * La configurazione della lega su filo, in entrambe le direzioni.
 *
 * ## Perche' lettura e scrittura stanno nello stesso file
 *
 * Perche' devono cambiare insieme. Se il telefono scrivesse un campo che il tick non
 * rilegge, l'impostazione dell'admin verrebbe ignorata **in silenzio**: la lega girerebbe
 * con il valore predefinito e nessuno se ne accorgerebbe fino a fine stagione. Tenerle
 * accanto rende evidente la dimenticanza, e il test di andata e ritorno la trasforma in
 * un errore invece che in un mistero.
 *
 * ## Perche' sta in `core` e non nell'app
 *
 * Perche' la scrive l'app e la rilegge il tick sul server. Sono due programmi diversi che
 * devono avere esattamente la stessa idea del formato: l'unico modo e' che sia lo stesso
 * codice.
 *
 * ## Compatibilita'
 *
 * Ogni lettura passa per un valore di ripiego preso da [LeagueConfig]. Aggiungere una
 * manopola nuova non rompe le leghe gia' create: quel campo semplicemente non c'e' nel
 * loro JSON e prende il predefinito.
 */
object ConfigJson {

    // ------------------------------------------------------------------------ scrittura

    fun write(config: LeagueConfig): String {
        val w = JsonWriter(6 * 1024)
        w.beginObject()
        writeInto(w, config)
        w.endObject()
        return w.toString()
    }

    /** Come [write], ma dentro un oggetto gia' aperto da chi chiama. */
    fun writeInto(w: JsonWriter, config: LeagueConfig) {
        writeSetup(w, config.setup)
        writeDivisions(w, config.divisions)
        writeEconomy(w, config.economy)
        writeMarket(w, config.market)
        writeCalendar(w, config.calendar)
        writeRules(w, config.rules)
        writeCustom(w, config.custom)
        writeWorld(w, config.world)
        writeNotifications(w, config.notifications)
        writeAi(w, config.ai)
        writeEngine(w, config.engine)
        writeObjectives(w, config.objectives)
        writeRoleWeights(w)
    }

    private fun writeObjectives(w: JsonWriter, c: ObjectivesConfig) {
        w.objectField("objectives")
        w.field("enabled", c.enabled)
        w.field("leagueRewardPercent", c.leagueRewardPercent)
        w.field("developmentRewardPercent", c.developmentRewardPercent)
        w.field("longTermRewardPercent", c.longTermRewardPercent)
        w.field("longTermSeasons", c.longTermSeasons)
        w.endObject()
    }

    /**
     * Le tabelle dei pesi per ruolo, scritte ma **mai rilette**.
     *
     * Non sono un'impostazione: vengono da [Position] e cambiano solo cambiando il codice.
     * Viaggiano lo stesso perche' servono al **database**, che deve poter ricalcolare da
     * solo l'overall e la base di partenza quando qualcuno crea il suo giocatore.
     *
     * Il motivo e' semplice: se il controllo del budget vivesse solo nell'app, chiunque
     * sappia comporre una richiesta HTTP potrebbe presentarsi con un 93 il primo giorno.
     * Il database deve poter dire di no da solo — e per dirlo gli servono questi numeri.
     * Mandarglieli invece di riscriverli in SQL vuol dire che la verita' resta una sola,
     * qui in `core`.
     */
    private fun writeRoleWeights(w: JsonWriter) {
        w.objectField("roleWeights")
        Position.entries.forEach { position ->
            w.objectField(position.name)
            position.ovrWeights.forEach { (attr, weight) -> w.field(attr.name, weight) }
            w.endObject()
        }
        w.endObject()
    }

    private fun writeSetup(w: JsonWriter, c: SetupConfig) {
        w.objectField("setup")
        w.field("leagueName", c.leagueName)
        // Il codice d'accesso NON passa di qui: sul database ne esiste solo l'hash, e
        // rimetterlo in chiaro nella configurazione lo renderebbe leggibile a chiunque
        // sia gia' nella lega, cioe' a chiunque possa invitarci un estraneo.
        w.field("totalClubs", c.totalClubs)
        w.field("mode", c.mode.name)
        w.field("aiClubs", c.aiClubs)
        w.field("minSquadSize", c.minSquadSize)
        w.field("maxSquadSize", c.maxSquadSize)
        w.field("worldSeed", c.worldSeed)
        w.endObject()
    }

    private fun writeDivisions(w: JsonWriter, c: DivisionsConfig) {
        w.objectField("divisions")
        w.field("count", c.count)
        w.arrayField("names")
        c.names.forEach { w.value(it) }
        w.endArray()
        w.arrayField("sizes")
        c.sizes.forEach { w.value(it) }
        w.endArray()
        w.field("directPromotions", c.directPromotions)
        w.field("playoffSlots", c.playoffSlots)
        w.field("directRelegations", c.directRelegations)
        w.field("playoutSlots", c.playoutSlots)
        w.field("twoLeggedPlayoffs", c.twoLeggedPlayoffs)
        w.endObject()
    }

    private fun writeEconomy(w: JsonWriter, c: EconomyConfig) {
        w.objectField("economy")
        w.field("startingCredits", c.startingCredits)
        w.field("recurringIncome", c.recurringIncome)
        w.field("incomeCadence", c.incomeCadence.name)
        w.arrayField("placementPrizes")
        c.placementPrizes.forEach { w.value(it) }
        w.endArray()
        w.field("winPrize", c.winPrize)
        w.field("drawPrize", c.drawPrize)
        w.field("wagesEnabled", c.wagesEnabled)
        w.field("wageFactor", c.wageFactor)
        w.field("topPlayerBudgetShare", c.topPlayerBudgetShare)
        w.field("staffBudgetShare", c.staffBudgetShare)
        w.field("renewalCostFraction", c.renewalCostFraction)
        w.field("negativeBalanceAllowed", c.negativeBalanceAllowed)
        w.endObject()
    }

    private fun writeMarket(w: JsonWriter, c: MarketConfig) {
        w.objectField("market")
        w.field("initialAuctionMode", c.initialAuctionMode.name)
        w.field("auctionDurationMinutes", c.auctionDurationMinutes)
        w.field("minimumRaise", c.minimumRaise)
        w.field("antiSnipeEnabled", c.antiSnipeEnabled)
        w.field("antiSnipeSeconds", c.antiSnipeSeconds)
        w.field("proxyBiddingEnabled", c.proxyBiddingEnabled)
        w.field("maxParallelAuctionsPerClub", c.maxParallelAuctionsPerClub)
        w.field("maxOpenAuctionsPerLeague", c.maxOpenAuctionsPerLeague)
        w.field("windowMode", c.windowMode.name)
        w.arrayField("windowSlots")
        c.windowSlots.forEach {
            w.beginObject()
            w.field("from", it.start.toString())
            w.field("to", it.endInclusive.toString())
            w.endObject()
        }
        w.endArray()
        w.field("loansEnabled", c.loansEnabled)
        w.field("releaseClausesEnabled", c.releaseClausesEnabled)
        w.field("swapsEnabled", c.swapsEnabled)
        w.field("defaultContractMatchDays", c.defaultContractMatchDays)
        w.field("negotiationExpiryMinutes", c.negotiationExpiryMinutes)
        w.field("minLoanMatchDays", c.minLoanMatchDays)
        w.field("maxLoanMatchDays", c.maxLoanMatchDays)
        w.endObject()
    }

    private fun writeCalendar(w: JsonWriter, c: CalendarConfig) {
        w.objectField("calendar")
        w.field("startDate", c.startDate.toString())
        w.field("endDate", c.endDate.toString())
        w.field("matchesPerDayPerClub", c.matchesPerDayPerClub)
        w.arrayField("restWeekdays")
        c.restWeekdays.forEach { w.value(it.name) }
        w.endArray()
        w.arrayField("restDates")
        c.restDates.forEach { w.value(it.toString()) }
        w.endArray()
        w.arrayField("kickoffSlots")
        c.kickoffSlots.forEach { w.value(it.toString()) }
        w.endArray()
        w.field("matchSpeed", c.matchSpeed.name)
        w.field("halfTimeWindowMinutes", c.halfTimeWindowMinutes)
        w.field("timeZone", c.timeZone.id)
        w.endObject()
    }

    private fun writeRules(w: JsonWriter, c: RulesConfig) {
        w.objectField("rules")
        w.field("customMustStart", c.customMustStart)
        w.field("customMinimumMinutes", c.customMinimumMinutes)
        w.field("customGrowthMultiplier", c.customGrowthMultiplier)
        w.field("growthMultiplier", c.growthMultiplier)
        w.field("peakAgeStart", c.peakAgeStart)
        w.field("peakAgeEnd", c.peakAgeEnd)
        w.field("plateauAgeEnd", c.plateauAgeEnd)
        w.field("declineAge", c.declineAge)
        w.field("friendliesEnabled", c.friendliesEnabled)
        w.field("friendliesCountForGrowth", c.friendliesCountForGrowth)
        w.field("injuriesEnabled", c.injuriesEnabled)
        w.field("injurySeverity", c.injurySeverity.name)
        w.field("suspensionsEnabled", c.suspensionsEnabled)
        w.field("yellowCardsForSuspension", c.yellowCardsForSuspension)
        w.field("youthTeamEnabled", c.youthTeamEnabled)
        w.field("scoutMinutesBest", c.scoutMinutesBest)
        w.field("scoutMinutesWorst", c.scoutMinutesWorst)
        w.field("youthMaxAge", c.youthMaxAge)
        w.field("youthMatchGrowthFactor", c.youthMatchGrowthFactor)
        w.field("moraleEnabled", c.moraleEnabled)
        w.field("conversationsEnabled", c.conversationsEnabled)
        w.field("lowMoraleThreshold", c.lowMoraleThreshold)
        w.endObject()
    }

    private fun writeCustom(w: JsonWriter, c: CustomPlayerConfig) {
        w.objectField("custom")
        w.field("baseOverall", c.baseOverall)
        w.field("skillBudget", c.skillBudget)
        w.field("starCost", c.starCost)
        w.field("startingStars", c.startingStars)
        w.arrayField("costTiers")
        c.costTiers.forEach {
            w.beginObject()
            w.field("upTo", it.upTo)
            w.field("cost", it.cost)
            w.endObject()
        }
        w.endArray()
        w.field("offRoleBase", c.offRoleBase)
        w.field("wrongSideBase", c.wrongSideBase)
        w.field("minAge", c.minAge)
        w.field("maxAge", c.maxAge)
        w.field("defaultAge", c.defaultAge)
        w.field("potentialBonus", c.potentialBonus)
        w.field("potentialCeiling", c.potentialCeiling)
        w.endObject()
    }

    private fun writeWorld(w: JsonWriter, c: WorldConfig) {
        w.objectField("world")
        w.objectField("tiers")
        w.field("fuoriclasse", c.tiers.fuoriclasse)
        w.field("top", c.tiers.top)
        w.field("buoni", c.tiers.buoni)
        w.field("normali", c.tiers.normali)
        w.field("gregari", c.tiers.gregari)
        w.endObject()
        w.objectField("positionQuotas")
        c.positionQuotas.forEach { (pos, quota) -> w.field(pos.name, quota) }
        w.endObject()
        w.arrayField("nationalities")
        c.nationalities.forEach { w.value(it) }
        w.endArray()
        w.field("minAge", c.minAge)
        w.field("maxAge", c.maxAge)
        w.field("traitChance", c.traitChance)
        w.field("maxTraitsPerPlayer", c.maxTraitsPerPlayer)
        w.field("minPotentialSpread", c.minPotentialSpread)
        w.field("maxPotentialSpread", c.maxPotentialSpread)
        w.endObject()
    }

    private fun writeNotifications(w: JsonWriter, c: NotificationConfig) {
        w.objectField("notifications")
        w.field("immediateEnabled", c.immediateEnabled)
        w.field("dailyDigestHour", c.dailyDigestHour.toString())
        w.field("maxAiEventsPerHumanClubPerDay", c.maxAiEventsPerHumanClubPerDay)
        w.endObject()
    }

    private fun writeAi(w: JsonWriter, c: AiConfig) {
        w.objectField("ai")
        w.field("difficultyMultiplier", c.difficultyMultiplier)
        w.field("maxMarketActionsPerDay", c.maxMarketActionsPerDay)
        w.field("minRebidDelayMinutes", c.minRebidDelayMinutes)
        w.field("maxRebidDelayMinutes", c.maxRebidDelayMinutes)
        w.field("minWakeupsPerDay", c.minWakeupsPerDay)
        w.field("maxWakeupsPerDay", c.maxWakeupsPerDay)
        w.field("crowdingPenalty", c.crowdingPenalty)
        w.field("refusalCooldownMatchDays", c.refusalCooldownMatchDays)
        w.field("recentPurchaseGraceMatchDays", c.recentPurchaseGraceMatchDays)
        w.field("rebidStepFraction", c.rebidStepFraction)
        w.endObject()
    }

    /**
     * Le manopole del motore viaggiano con la lega, non con l'APK.
     *
     * Sono tarate misurando migliaia di partite, quindi cambiano di rado. Ma quando
     * cambiano, devono poterlo fare **senza pubblicare una versione nuova dell'app**: se
     * vivessero solo nel codice, correggere un bilanciamento a stagione iniziata
     * significherebbe chiedere a venti persone di aggiornare, e chi non lo facesse
     * vedrebbe partite diverse da quelle che il server ha simulato.
     */
    private fun writeEngine(w: JsonWriter, c: EngineConfig) {
        w.objectField("engine")
        w.field("sigmoidK", c.sigmoidK)
        w.field("homeAdvantage", c.homeAdvantage)
        w.field("actionsPerMatch", c.actionsPerMatch)
        w.field("shotChanceInAttackingZone", c.shotChanceInAttackingZone)
        w.field("baseXgCentral", c.baseXgCentral)
        w.field("baseXgWide", c.baseXgWide)
        w.field("weakFootPenaltyPerStar", c.weakFootPenaltyPerStar)
        w.field("foulChance", c.foulChance)
        w.field("yellowCardChanceOnFoul", c.yellowCardChanceOnFoul)
        w.field("redCardChanceOnFoul", c.redCardChanceOnFoul)
        w.field("cornerChanceOnLostAttack", c.cornerChanceOnLostAttack)
        w.field("injuryChancePerAction", c.injuryChancePerAction)
        w.field("momentumStrength", c.momentumStrength)
        w.field("momentumDecayPerAction", c.momentumDecayPerAction)
        w.field("staminaDrainPerMinute", c.staminaDrainPerMinute)
        w.field("staminaRecoveryPerMatchDay", c.staminaRecoveryPerMatchDay)
        w.field("staminaComfortThreshold", c.staminaComfortThreshold)
        w.field("maxStaminaPenalty", c.maxStaminaPenalty)
        w.field("moraleWeight", c.moraleWeight)
        w.field("formWeight", c.formWeight)
        w.endObject()
    }

    // -------------------------------------------------------------------------- lettura

    fun read(text: String): LeagueConfig = read(JsonNode.parse(text))

    fun read(root: JsonNode): LeagueConfig {
        val d = LeagueConfig()
        return LeagueConfig(
            setup = readSetup(root["setup"], d.setup),
            divisions = readDivisions(root["divisions"], d.divisions),
            economy = readEconomy(root["economy"], d.economy),
            market = readMarket(root["market"], d.market),
            calendar = readCalendar(root["calendar"], d.calendar),
            rules = readRules(root["rules"], d.rules),
            custom = readCustom(root["custom"], d.custom),
            world = readWorld(root["world"], d.world),
            notifications = readNotifications(root["notifications"], d.notifications),
            ai = readAi(root["ai"], d.ai),
            engine = readEngine(root["engine"], d.engine),
            objectives = readObjectives(root["objectives"], d.objectives),
        )
    }

    private fun readObjectives(n: JsonNode, d: ObjectivesConfig) = ObjectivesConfig(
        enabled = n["enabled"].bool(d.enabled),
        leagueRewardPercent = n["leagueRewardPercent"].int(d.leagueRewardPercent),
        developmentRewardPercent = n["developmentRewardPercent"].int(d.developmentRewardPercent),
        longTermRewardPercent = n["longTermRewardPercent"].int(d.longTermRewardPercent),
        longTermSeasons = n["longTermSeasons"].int(d.longTermSeasons),
    )

    private fun readSetup(n: JsonNode, d: SetupConfig) = SetupConfig(
        leagueName = n["leagueName"].str(d.leagueName),
        accessCode = d.accessCode,
        totalClubs = n["totalClubs"].int(d.totalClubs),
        mode = n["mode"].enum(d.mode),
        aiClubs = n["aiClubs"].int(d.aiClubs),
        minSquadSize = n["minSquadSize"].int(d.minSquadSize),
        maxSquadSize = n["maxSquadSize"].int(d.maxSquadSize),
        worldSeed = n["worldSeed"].long(d.worldSeed),
    )

    private fun readDivisions(n: JsonNode, d: DivisionsConfig) = DivisionsConfig(
        count = n["count"].int(d.count),
        names = n["names"].listOr(d.names) { it.str("") }.filter { it.isNotBlank() },
        sizes = n["sizes"].listOr(d.sizes) { it.int(0) }.filter { it > 0 },
        directPromotions = n["directPromotions"].int(d.directPromotions),
        playoffSlots = n["playoffSlots"].int(d.playoffSlots),
        directRelegations = n["directRelegations"].int(d.directRelegations),
        playoutSlots = n["playoutSlots"].int(d.playoutSlots),
        twoLeggedPlayoffs = n["twoLeggedPlayoffs"].bool(d.twoLeggedPlayoffs),
    )

    private fun readEconomy(n: JsonNode, d: EconomyConfig) = EconomyConfig(
        startingCredits = n["startingCredits"].int(d.startingCredits),
        recurringIncome = n["recurringIncome"].int(d.recurringIncome),
        incomeCadence = n["incomeCadence"].enum(d.incomeCadence),
        placementPrizes = n["placementPrizes"].listOr(d.placementPrizes) { it.int(0) },
        winPrize = n["winPrize"].int(d.winPrize),
        drawPrize = n["drawPrize"].int(d.drawPrize),
        wagesEnabled = n["wagesEnabled"].bool(d.wagesEnabled),
        wageFactor = n["wageFactor"].double(d.wageFactor),
        topPlayerBudgetShare = n["topPlayerBudgetShare"].double(d.topPlayerBudgetShare),
        staffBudgetShare = n["staffBudgetShare"].double(d.staffBudgetShare),
        renewalCostFraction = n["renewalCostFraction"].double(d.renewalCostFraction),
        negativeBalanceAllowed = n["negativeBalanceAllowed"].bool(d.negativeBalanceAllowed),
    )

    private fun readMarket(n: JsonNode, d: MarketConfig) = MarketConfig(
        initialAuctionMode = n["initialAuctionMode"].enum(d.initialAuctionMode),
        auctionDurationMinutes = n["auctionDurationMinutes"].int(d.auctionDurationMinutes),
        minimumRaise = n["minimumRaise"].int(d.minimumRaise),
        antiSnipeEnabled = n["antiSnipeEnabled"].bool(d.antiSnipeEnabled),
        antiSnipeSeconds = n["antiSnipeSeconds"].int(d.antiSnipeSeconds),
        proxyBiddingEnabled = n["proxyBiddingEnabled"].bool(d.proxyBiddingEnabled),
        maxParallelAuctionsPerClub = n["maxParallelAuctionsPerClub"].int(d.maxParallelAuctionsPerClub),
        maxOpenAuctionsPerLeague = n["maxOpenAuctionsPerLeague"].int(d.maxOpenAuctionsPerLeague),
        windowMode = n["windowMode"].enum(d.windowMode),
        windowSlots = n["windowSlots"].listOr(d.windowSlots) { slot ->
            val from = slot["from"].strOrNull()?.let(LocalTime::parse)
            val to = slot["to"].strOrNull()?.let(LocalTime::parse)
            if (from != null && to != null) from..to else null
        },
        loansEnabled = n["loansEnabled"].bool(d.loansEnabled),
        releaseClausesEnabled = n["releaseClausesEnabled"].bool(d.releaseClausesEnabled),
        swapsEnabled = n["swapsEnabled"].bool(d.swapsEnabled),
        defaultContractMatchDays = n["defaultContractMatchDays"].int(d.defaultContractMatchDays),
        negotiationExpiryMinutes = n["negotiationExpiryMinutes"].int(d.negotiationExpiryMinutes),
        minLoanMatchDays = n["minLoanMatchDays"].int(d.minLoanMatchDays),
        maxLoanMatchDays = n["maxLoanMatchDays"].int(d.maxLoanMatchDays),
    )

    private fun readCalendar(n: JsonNode, d: CalendarConfig) = CalendarConfig(
        startDate = n["startDate"].strOrNull()?.let(LocalDate::parse) ?: d.startDate,
        endDate = n["endDate"].strOrNull()?.let(LocalDate::parse) ?: d.endDate,
        matchesPerDayPerClub = n["matchesPerDayPerClub"].int(d.matchesPerDayPerClub),
        restWeekdays = n["restWeekdays"].asList()
            .mapNotNull { it.strOrNull()?.let(DayOfWeek::valueOf) }.toSet(),
        restDates = n["restDates"].asList()
            .mapNotNull { it.strOrNull()?.let(LocalDate::parse) }.toSet(),
        kickoffSlots = n["kickoffSlots"].listOr(d.kickoffSlots) {
            it.strOrNull()?.let(LocalTime::parse)
        },
        matchSpeed = n["matchSpeed"].enum(d.matchSpeed),
        halfTimeWindowMinutes = n["halfTimeWindowMinutes"].int(d.halfTimeWindowMinutes),
        // Un fuso sconosciuto non deve impedire di aprire la lega: si ricade su quello
        // predefinito, che e' sbagliato di un'ora al massimo, invece di lanciare
        // un'eccezione che lascerebbe l'app su una schermata bianca.
        timeZone = n["timeZone"].strOrNull()
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: d.timeZone,
    )

    private fun readRules(n: JsonNode, d: RulesConfig) = RulesConfig(
        customMustStart = n["customMustStart"].bool(d.customMustStart),
        customMinimumMinutes = n["customMinimumMinutes"].int(d.customMinimumMinutes),
        customGrowthMultiplier = n["customGrowthMultiplier"].double(d.customGrowthMultiplier),
        growthMultiplier = n["growthMultiplier"].double(d.growthMultiplier),
        peakAgeStart = n["peakAgeStart"].int(d.peakAgeStart),
        peakAgeEnd = n["peakAgeEnd"].int(d.peakAgeEnd),
        plateauAgeEnd = n["plateauAgeEnd"].int(d.plateauAgeEnd),
        declineAge = n["declineAge"].int(d.declineAge),
        friendliesEnabled = n["friendliesEnabled"].bool(d.friendliesEnabled),
        friendliesCountForGrowth = n["friendliesCountForGrowth"].bool(d.friendliesCountForGrowth),
        injuriesEnabled = n["injuriesEnabled"].bool(d.injuriesEnabled),
        injurySeverity = n["injurySeverity"].enum(d.injurySeverity),
        suspensionsEnabled = n["suspensionsEnabled"].bool(d.suspensionsEnabled),
        yellowCardsForSuspension = n["yellowCardsForSuspension"].int(d.yellowCardsForSuspension),
        youthTeamEnabled = n["youthTeamEnabled"].bool(d.youthTeamEnabled),
        scoutMinutesBest = n["scoutMinutesBest"].int(d.scoutMinutesBest),
        scoutMinutesWorst = n["scoutMinutesWorst"].int(d.scoutMinutesWorst),
        youthMaxAge = n["youthMaxAge"].int(d.youthMaxAge),
        youthMatchGrowthFactor = n["youthMatchGrowthFactor"].double(d.youthMatchGrowthFactor),
        moraleEnabled = n["moraleEnabled"].bool(d.moraleEnabled),
        conversationsEnabled = n["conversationsEnabled"].bool(d.conversationsEnabled),
        lowMoraleThreshold = n["lowMoraleThreshold"].int(d.lowMoraleThreshold),
    )

    private fun readCustom(n: JsonNode, d: CustomPlayerConfig) = CustomPlayerConfig(
        baseOverall = n["baseOverall"].int(d.baseOverall),
        skillBudget = n["skillBudget"].int(d.skillBudget),
        starCost = n["starCost"].int(d.starCost),
        startingStars = n["startingStars"].int(d.startingStars),
        costTiers = n["costTiers"].listOr(d.costTiers) { tier ->
            CostTier(upTo = tier["upTo"].int(99), cost = tier["cost"].int(1))
        },
        offRoleBase = n["offRoleBase"].int(d.offRoleBase),
        wrongSideBase = n["wrongSideBase"].int(d.wrongSideBase),
        minAge = n["minAge"].int(d.minAge),
        maxAge = n["maxAge"].int(d.maxAge),
        defaultAge = n["defaultAge"].int(d.defaultAge),
        potentialBonus = n["potentialBonus"].int(d.potentialBonus),
        potentialCeiling = n["potentialCeiling"].int(d.potentialCeiling),
    )

    private fun readWorld(n: JsonNode, d: WorldConfig): WorldConfig {
        val t = n["tiers"]
        val quotas = n["positionQuotas"]
        return WorldConfig(
            tiers = OverallTiers(
                fuoriclasse = t["fuoriclasse"].int(d.tiers.fuoriclasse),
                top = t["top"].int(d.tiers.top),
                buoni = t["buoni"].int(d.tiers.buoni),
                normali = t["normali"].int(d.tiers.normali),
                gregari = t["gregari"].int(d.tiers.gregari),
            ),
            positionQuotas = if (!quotas.exists) {
                d.positionQuotas
            } else {
                quotas.keys().mapNotNull { key ->
                    val pos = Position.entries.firstOrNull { it.name == key } ?: return@mapNotNull null
                    pos to quotas[key].double(0.0)
                }.toMap()
            },
            nationalities = n["nationalities"].listOr(d.nationalities) { it.strOrNull() },
            minAge = n["minAge"].int(d.minAge),
            maxAge = n["maxAge"].int(d.maxAge),
            traitChance = n["traitChance"].double(d.traitChance),
            maxTraitsPerPlayer = n["maxTraitsPerPlayer"].int(d.maxTraitsPerPlayer),
            minPotentialSpread = n["minPotentialSpread"].int(d.minPotentialSpread),
            maxPotentialSpread = n["maxPotentialSpread"].int(d.maxPotentialSpread),
        )
    }

    private fun readNotifications(n: JsonNode, d: NotificationConfig) = NotificationConfig(
        immediateEnabled = n["immediateEnabled"].bool(d.immediateEnabled),
        dailyDigestHour = n["dailyDigestHour"].strOrNull()?.let(LocalTime::parse) ?: d.dailyDigestHour,
        maxAiEventsPerHumanClubPerDay =
            n["maxAiEventsPerHumanClubPerDay"].int(d.maxAiEventsPerHumanClubPerDay),
    )

    private fun readAi(n: JsonNode, d: AiConfig) = AiConfig(
        difficultyMultiplier = n["difficultyMultiplier"].double(d.difficultyMultiplier),
        maxMarketActionsPerDay = n["maxMarketActionsPerDay"].int(d.maxMarketActionsPerDay),
        minRebidDelayMinutes = n["minRebidDelayMinutes"].int(d.minRebidDelayMinutes),
        maxRebidDelayMinutes = n["maxRebidDelayMinutes"].int(d.maxRebidDelayMinutes),
        minWakeupsPerDay = n["minWakeupsPerDay"].int(d.minWakeupsPerDay),
        maxWakeupsPerDay = n["maxWakeupsPerDay"].int(d.maxWakeupsPerDay),
        crowdingPenalty = n["crowdingPenalty"].double(d.crowdingPenalty),
        refusalCooldownMatchDays = n["refusalCooldownMatchDays"].int(d.refusalCooldownMatchDays),
        recentPurchaseGraceMatchDays = n["recentPurchaseGraceMatchDays"].int(d.recentPurchaseGraceMatchDays),
        rebidStepFraction = n["rebidStepFraction"].double(d.rebidStepFraction),
    )

    private fun readEngine(n: JsonNode, d: EngineConfig) = EngineConfig(
        sigmoidK = n["sigmoidK"].double(d.sigmoidK),
        homeAdvantage = n["homeAdvantage"].double(d.homeAdvantage),
        actionsPerMatch = n["actionsPerMatch"].int(d.actionsPerMatch),
        shotChanceInAttackingZone = n["shotChanceInAttackingZone"].double(d.shotChanceInAttackingZone),
        baseXgCentral = n["baseXgCentral"].double(d.baseXgCentral),
        baseXgWide = n["baseXgWide"].double(d.baseXgWide),
        weakFootPenaltyPerStar = n["weakFootPenaltyPerStar"].double(d.weakFootPenaltyPerStar),
        foulChance = n["foulChance"].double(d.foulChance),
        yellowCardChanceOnFoul = n["yellowCardChanceOnFoul"].double(d.yellowCardChanceOnFoul),
        redCardChanceOnFoul = n["redCardChanceOnFoul"].double(d.redCardChanceOnFoul),
        cornerChanceOnLostAttack = n["cornerChanceOnLostAttack"].double(d.cornerChanceOnLostAttack),
        injuryChancePerAction = n["injuryChancePerAction"].double(d.injuryChancePerAction),
        momentumStrength = n["momentumStrength"].double(d.momentumStrength),
        momentumDecayPerAction = n["momentumDecayPerAction"].double(d.momentumDecayPerAction),
        staminaDrainPerMinute = n["staminaDrainPerMinute"].double(d.staminaDrainPerMinute),
        staminaRecoveryPerMatchDay = n["staminaRecoveryPerMatchDay"].double(d.staminaRecoveryPerMatchDay),
        staminaComfortThreshold = n["staminaComfortThreshold"].int(d.staminaComfortThreshold),
        maxStaminaPenalty = n["maxStaminaPenalty"].double(d.maxStaminaPenalty),
        moraleWeight = n["moraleWeight"].double(d.moraleWeight),
        formWeight = n["formWeight"].double(d.formWeight),
    )

    /**
     * Una lista, distinguendo "assente" da "vuota".
     *
     * Non e' pignoleria: se un admin toglie tutti i premi di posizione, la sua scelta e'
     * una lista vuota. Trattarla come "manca il campo" gli rimetterebbe i premi
     * predefiniti, cioe' esattamente il contrario di quello che ha chiesto.
     */
    private fun <T> JsonNode.listOr(fallback: List<T>, map: (JsonNode) -> T?): List<T> =
        if (!exists) fallback else asList().mapNotNull(map)
}
