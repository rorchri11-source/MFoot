package dev.mfoot.core.config

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.model.Position
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L'andata e ritorno della configurazione.
 *
 * È il test che protegge il principio su cui poggia tutto il gioco: **nessun numero vive
 * nel codice, li decide l'admin**. Se un campo si scrivesse ma non si rileggesse, la sua
 * impostazione tornerebbe al valore predefinito in silenzio — la lega girerebbe con
 * regole diverse da quelle scelte e nessuno se ne accorgerebbe fino a fine stagione.
 *
 * Per questo la configurazione di prova qui sotto ha **tutti** i campi diversi dal
 * predefinito: un campo dimenticato nel codec torna indietro col valore di serie, e il
 * confronto fallisce.
 */
class ConfigJsonTest {

    /** Volutamente assurda: nessun valore coincide con il predefinito. */
    private fun configStravagante() = LeagueConfig(
        setup = SetupConfig(
            leagueName = "Lega dei Quattro Amici",
            accessCode = "SEGRETO",
            totalClubs = 12,
            mode = LeagueMode.SOLO_MULTIPLAYER,
            aiClubs = 3,
            minSquadSize = 14,
            maxSquadSize = 22,
            worldSeed = 987654321L,
        ),
        divisions = DivisionsConfig(
            count = 3,
            names = listOf("Prima", "Seconda", "Terza"),
            directPromotions = 2,
            playoffSlots = 4,
            directRelegations = 3,
            playoutSlots = 0,
            twoLeggedPlayoffs = true,
        ),
        economy = EconomyConfig(
            startingCredits = 1000,
            recurringIncome = 25,
            incomeCadence = IncomeCadence.PER_SETTIMANA,
            placementPrizes = listOf(500, 250, 100),
            winPrize = 12,
            drawPrize = 5,
            wagesEnabled = false,
            wageFactor = 0.0021,
            renewalCostFraction = 0.33,
            negativeBalanceAllowed = true,
        ),
        market = MarketConfig(
            initialAuctionMode = InitialAuctionMode.ASINCRONA,
            auctionDurationMinutes = 240,
            minimumRaise = 5,
            antiSnipeEnabled = false,
            antiSnipeSeconds = 120,
            proxyBiddingEnabled = false,
            maxParallelAuctionsPerClub = 7,
            windowMode = MarketWindowMode.FASCE_ORARIE,
            windowSlots = listOf(LocalTime.of(12, 0)..LocalTime.of(14, 30)),
            loansEnabled = false,
            releaseClausesEnabled = false,
            swapsEnabled = false,
            defaultContractMatchDays = 30,
            negotiationExpiryMinutes = 720,
            minLoanMatchDays = 3,
            maxLoanMatchDays = 25,
        ),
        calendar = CalendarConfig(
            startDate = LocalDate.of(2027, 3, 1),
            endDate = LocalDate.of(2027, 4, 15),
            matchesPerDayPerClub = 3,
            restWeekdays = setOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY),
            restDates = setOf(LocalDate.of(2027, 3, 25)),
            kickoffSlots = listOf(LocalTime.of(19, 0), LocalTime.of(22, 15)),
            matchSpeed = MatchSpeed.ISTANTANEA,
            halfTimeWindowMinutes = 8,
        ),
        rules = RulesConfig(
            customMustStart = false,
            customMinimumMinutes = 30,
            customGrowthMultiplier = 6.0,
            growthMultiplier = 2.5,
            peakAgeStart = 20,
            peakAgeEnd = 25,
            plateauAgeEnd = 30,
            declineAge = 34,
            friendliesEnabled = false,
            friendliesCountForGrowth = true,
            injuriesEnabled = false,
            injurySeverity = InjurySeverity.REALISTICA,
            suspensionsEnabled = false,
            yellowCardsForSuspension = 3,
            youthTeamEnabled = false,
            youthMaxAge = 23,
            youthMatchGrowthFactor = 0.9,
            moraleEnabled = false,
            conversationsEnabled = false,
            lowMoraleThreshold = 45,
        ),
        custom = CustomPlayerConfig(
            baseOverall = 58,
            skillBudget = 140,
            starCost = 7,
            startingStars = 2,
            costTiers = listOf(CostTier(60, 1), CostTier(90, 5)),
            offRoleBase = 33,
            wrongSideBase = 8,
            minAge = 15,
            maxAge = 26,
            defaultAge = 20,
            potentialBonus = 25,
            potentialCeiling = 97,
        ),
        world = WorldConfig(
            tiers = OverallTiers(3, 20, 90, 200, 300),
            positionQuotas = mapOf(Position.POR to 0.2, Position.ATT to 0.8),
            nationalities = listOf("Islanda", "Nuova Zelanda"),
            minAge = 17,
            maxAge = 35,
            traitChance = 0.9,
            maxTraitsPerPlayer = 4,
            minPotentialSpread = 1,
            maxPotentialSpread = 25,
        ),
        notifications = NotificationConfig(
            immediateEnabled = false,
            dailyDigestHour = LocalTime.of(20, 45),
            maxAiEventsPerHumanClubPerDay = 9,
        ),
        ai = AiConfig(
            difficultyMultiplier = 1.7,
            maxMarketActionsPerDay = 5,
            minRebidDelayMinutes = 30,
            maxRebidDelayMinutes = 400,
            minWakeupsPerDay = 2,
            maxWakeupsPerDay = 6,
            crowdingPenalty = 0.75,
            refusalCooldownMatchDays = 9,
            recentPurchaseGraceMatchDays = 11,
            rebidStepFraction = 0.2,
        ),
        engine = EngineConfig(
            sigmoidK = 41.5,
            homeAdvantage = 7.25,
            actionsPerMatch = 90,
            shotChanceInAttackingZone = 0.5,
            baseXgCentral = 0.2,
            baseXgWide = 0.09,
            weakFootPenaltyPerStar = 0.1,
            foulChance = 0.15,
            yellowCardChanceOnFoul = 0.25,
            redCardChanceOnFoul = 0.01,
            cornerChanceOnLostAttack = 0.2,
            injuryChancePerAction = 0.003,
            momentumStrength = 5.0,
            momentumDecayPerAction = 0.88,
            staminaDrainPerMinute = 0.5,
            staminaRecoveryPerMatchDay = 50.0,
            staminaComfortThreshold = 70,
            maxStaminaPenalty = 20.0,
            moraleWeight = 0.12,
            formWeight = 1.4,
        ),
    )

    @Test
    fun `la configurazione torna indietro identica`() {
        val originale = configStravagante()
        val riletta = ConfigJson.read(ConfigJson.write(originale))

        // Il codice d'accesso è l'unica eccezione voluta: vedi il test dedicato.
        assertEquals(originale.copy(setup = originale.setup.copy(accessCode = "")),
            riletta.copy(setup = riletta.setup.copy(accessCode = "")))
    }

    /**
     * Il codice d'accesso non deve viaggiare nella configurazione.
     *
     * Sul database ne esiste solo l'hash. Rimetterlo in chiaro dentro `config` — che ogni
     * membro della lega può leggere — significherebbe che chiunque sia già dentro può
     * passarlo a un estraneo senza che l'admin lo sappia, e che chiunque legga un backup
     * lo trova scritto in bella vista.
     */
    @Test
    fun `il codice d'accesso non finisce nel json`() {
        val json = ConfigJson.write(configStravagante())
        assertFalse(json.contains("SEGRETO"), "il codice d'accesso è finito nella configurazione")
        assertFalse(json.contains("accessCode"), "il campo del codice d'accesso non deve esistere")
    }

    /**
     * Una lega creata da una versione più vecchia dell'app non deve diventare illeggibile
     * quando si aggiunge una manopola nuova.
     */
    @Test
    fun `un json vecchio senza campi nuovi prende i predefiniti`() {
        val minimo = """{"economy":{"startingCredits":500}}"""
        val letta = ConfigJson.read(minimo)
        val predefinita = LeagueConfig()

        assertEquals(500, letta.economy.startingCredits)
        assertEquals(predefinita.engine.sigmoidK, letta.engine.sigmoidK)
        assertEquals(predefinita.rules.declineAge, letta.rules.declineAge)
        assertEquals(predefinita.market.auctionDurationMinutes, letta.market.auctionDurationMinutes)
    }

    /** Un json completamente vuoto deve dare la configurazione predefinita, non un crash. */
    @Test
    fun `un json vuoto da' la configurazione predefinita`() {
        assertEquals(LeagueConfig(), ConfigJson.read("{}"))
    }

    /**
     * Vuoto e assente non sono la stessa cosa: se l'admin toglie tutti i premi, deve
     * restare senza premi, non ritrovarsi quelli di serie.
     */
    @Test
    fun `una lista svuotata resta vuota`() {
        val senzaPremi = LeagueConfig(economy = EconomyConfig(placementPrizes = emptyList()))
        val riletta = ConfigJson.read(ConfigJson.write(senzaPremi))

        assertTrue(riletta.economy.placementPrizes.isEmpty(), "i premi cancellati sono tornati")
    }

    @Test
    fun `i preset sopravvivono al viaggio`() {
        listOf(
            ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1)),
            ConfigPresets.classica(16, 8, LocalDate.of(2026, 9, 1)),
            ConfigPresets.fantacalcio(10, 0, LocalDate.of(2026, 9, 1)),
        ).forEach { preset ->
            val riletta = ConfigJson.read(ConfigJson.write(preset))
            assertEquals(
                preset.copy(setup = preset.setup.copy(accessCode = "")),
                riletta.copy(setup = riletta.setup.copy(accessCode = "")),
                "il preset ${preset.setup.leagueName} non sopravvive al viaggio",
            )
        }
    }

    @Test
    fun `il parser regge stringhe con virgolette accenti e ritorni a capo`() {
        val node = JsonNode.parse(
            """{"a":"virgolette \" dentro","b":"riga\nnuova","c":"Velocità","d":[1,2,3],"e":null}""",
        )

        assertEquals("virgolette \" dentro", node["a"].str(""))
        assertEquals("riga\nnuova", node["b"].str(""))
        assertEquals("Velocità", node["c"].str(""))
        assertEquals(3, node["d"].size)
        assertEquals(2, node["d"][1].int(0))
        assertFalse(node["e"].exists)
        assertFalse(node["mai_esistito"].exists)
        assertEquals(42, node["mai_esistito"].int(42))
    }
}
