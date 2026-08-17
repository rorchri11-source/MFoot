package dev.mfoot.core.config

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigValidatorTest {

    private val start = LocalDate.of(2026, 9, 1)

    private fun hasErrorOn(result: ValidationResult, field: String): Boolean =
        result.errors.any { it.field == field }

    private fun hasWarningOn(result: ValidationResult, field: String): Boolean =
        result.warnings.any { it.field == field }

    // ------------------------------------------------------------------------ preset

    @Test
    fun `tutti i preset sono validi appena creati`() {
        ConfigPresets.all.forEach { preset ->
            val config = preset.build(16, 8, start)
            val result = ConfigValidator.validate(config)
            assertTrue(
                result.isValid,
                "il preset '${preset.name}' non passa la validazione:\n${result.describe()}",
            )
        }
    }

    @Test
    fun `i preset restano validi anche con pochi club`() {
        ConfigPresets.all.forEach { preset ->
            val config = preset.build(8, 4, start)
            val result = ConfigValidator.validate(config)
            assertTrue(
                result.isValid,
                "il preset '${preset.name}' fallisce con 8 club:\n${result.describe()}",
            )
        }
    }

    @Test
    fun `i preset restano validi con venti club`() {
        ConfigPresets.all.forEach { preset ->
            val config = preset.build(20, 12, start)
            val result = ConfigValidator.validate(config)
            assertTrue(
                result.isValid,
                "il preset '${preset.name}' fallisce con 20 club:\n${result.describe()}",
            )
        }
    }

    // ------------------------------------------------------------------------- setup

    @Test
    fun `una lega senza avversari viene rifiutata`() {
        val result = ConfigValidator.validate(
            LeagueConfig(setup = SetupConfig(totalClubs = 1, aiClubs = 0)),
        )
        assertTrue(hasErrorOn(result, "setup.totalClubs"))
    }

    @Test
    fun `modalita solo multiplayer con club AI e incoerente`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                setup = SetupConfig(
                    totalClubs = 8,
                    aiClubs = 4,
                    mode = LeagueMode.SOLO_MULTIPLAYER,
                ),
            ),
        )
        assertTrue(hasErrorOn(result, "setup.aiClubs"))
    }

    @Test
    fun `una lega di sole AI non lascia posto agli umani`() {
        val result = ConfigValidator.validate(
            LeagueConfig(setup = SetupConfig(totalClubs = 8, aiClubs = 8)),
        )
        assertTrue(hasErrorOn(result, "setup.aiClubs"))
    }

    // ------------------------------------------------------------------------- mondo

    /**
     * Il caso classico: l'admin non tocca il mondo e alza le squadre a venti,
     * e il giorno dell'asta scopre che i giocatori non bastano per tutti.
     */
    @Test
    fun `un mondo troppo piccolo per le rose viene rifiutato`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                setup = SetupConfig(totalClubs = 20, aiClubs = 10, minSquadSize = 20),
                world = WorldConfig(
                    tiers = OverallTiers(
                        fuoriclasse = 2, top = 8, buoni = 20, normali = 40, gregari = 50,
                    ),
                ),
            ),
        )
        assertTrue(hasErrorOn(result, "world.tiers"))
        assertTrue(
            result.errors.first { it.field == "world.tiers" }.suggestion != null,
            "l'errore deve dire quale numero mettere, non solo che e' sbagliato",
        )
    }

    @Test
    fun `un mondo appena sufficiente genera un avviso sul mercato che si svuota`() {
        val needed = 16 * 16
        val result = ConfigValidator.validate(
            LeagueConfig(
                setup = SetupConfig(totalClubs = 16, aiClubs = 8, minSquadSize = 16),
                world = WorldConfig(
                    tiers = OverallTiers(
                        fuoriclasse = 4, top = 16, buoni = 40, normali = 90,
                        gregari = needed + 10 - 150,
                    ),
                ),
            ),
        )
        assertTrue(hasWarningOn(result, "world.tiers"))
    }

    @Test
    fun `senza portieri a sufficienza la lega non parte`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                setup = SetupConfig(totalClubs = 20, aiClubs = 10),
                world = WorldConfig(
                    positionQuotas = mapOf(
                        dev.mfoot.core.model.Position.POR to 0.001,
                        dev.mfoot.core.model.Position.DC to 0.5,
                        dev.mfoot.core.model.Position.CC to 0.5,
                    ),
                ),
            ),
        )
        assertTrue(hasErrorOn(result, "world.positionQuotas"))
    }

    // --------------------------------------------------------------------- calendario

    @Test
    fun `un calendario troppo corto viene rifiutato con la data giusta da mettere`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                setup = SetupConfig(totalClubs = 20, aiClubs = 10),
                calendar = CalendarConfig(
                    startDate = start,
                    endDate = start.plusDays(3),
                    matchesPerDayPerClub = 1,
                    kickoffSlots = listOf(LocalTime.of(21, 0)),
                ),
            ),
        )
        val issue = result.errors.firstOrNull { it.field == "calendar.endDate" }
        assertTrue(issue != null, "un calendario impossibile deve essere un errore")
        assertTrue(issue.suggestion != null, "deve suggerire la data corretta")
    }

    /**
     * Due partite nello stesso giorno non possono iniziare alla stessa ora:
     * e' il tipo di incoerenza che il generatore di calendario non puo' risolvere da solo.
     */
    @Test
    fun `piu partite al giorno che fasce orarie viene rifiutato`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                calendar = CalendarConfig(
                    startDate = start,
                    endDate = start.plusDays(40),
                    matchesPerDayPerClub = 2,
                    kickoffSlots = listOf(LocalTime.of(21, 0)),
                ),
            ),
        )
        assertTrue(hasErrorOn(result, "calendar.kickoffSlots"))
    }

    @Test
    fun `la data di fine deve venire dopo quella di inizio`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                calendar = CalendarConfig(startDate = start, endDate = start.minusDays(1)),
            ),
        )
        assertTrue(hasErrorOn(result, "calendar.endDate"))
    }

    @Test
    fun `i giorni di riposo riducono i giorni disponibili`() {
        val config = LeagueConfig(
            calendar = CalendarConfig(
                startDate = LocalDate.of(2026, 9, 7),   // lunedi'
                endDate = LocalDate.of(2026, 9, 20),    // due settimane piene
                restWeekdays = setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY),
            ),
        )
        assertEquals(10, ConfigValidator.countAvailableDays(config))
    }

    // ----------------------------------------------------------------------- economia

    @Test
    fun `un budget che non copre nemmeno un credito a giocatore viene rifiutato`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                setup = SetupConfig(totalClubs = 16, aiClubs = 8, minSquadSize = 16),
                economy = EconomyConfig(startingCredits = 10),
            ),
        )
        val issue = result.errors.firstOrNull { it.field == "economy.startingCredits" }
        assertTrue(issue != null)
        assertTrue(issue.suggestion != null, "deve dire quanti crediti servono davvero")
    }

    @Test
    fun `un budget risicato avvisa che le aste saranno insignificanti`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                setup = SetupConfig(totalClubs = 16, aiClubs = 8, minSquadSize = 16),
                economy = EconomyConfig(startingCredits = 30),
            ),
        )
        assertTrue(hasWarningOn(result, "economy.startingCredits"))
    }

    @Test
    fun `un budget enorme avvisa che le aste perdono senso`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                setup = SetupConfig(totalClubs = 16, aiClubs = 8, minSquadSize = 16),
                economy = EconomyConfig(startingCredits = 5000),
            ),
        )
        assertTrue(hasWarningOn(result, "economy.startingCredits"))
    }

    @Test
    fun `stipendi senza entrate ricorrenti sono un vicolo cieco`() {
        val result = ConfigValidator.validate(
            LeagueConfig(economy = EconomyConfig(wagesEnabled = true, recurringIncome = 0)),
        )
        assertTrue(hasWarningOn(result, "economy.recurringIncome"))
    }

    // ------------------------------------------------------------------------- regole

    @Test
    fun `fasce d eta fuori ordine vengono rifiutate`() {
        val result = ConfigValidator.validate(
            LeagueConfig(rules = RulesConfig(peakAgeStart = 30, peakAgeEnd = 22)),
        )
        assertTrue(hasErrorOn(result, "rules.peakAgeStart"))
    }

    @Test
    fun `una primavera con eta massima impossibile viene rifiutata`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                rules = RulesConfig(youthTeamEnabled = true, youthMaxAge = 14),
                world = WorldConfig(minAge = 16),
            ),
        )
        assertTrue(hasErrorOn(result, "rules.youthMaxAge"))
    }

    /**
     * Se le amichevoli facessero crescere, due amici compiacenti potrebbero concordarne
     * quindici al giorno e far esplodere le rose in un pomeriggio.
     */
    @Test
    fun `la crescita da amichevole viene segnalata come exploit`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                rules = RulesConfig(friendliesEnabled = true, friendliesCountForGrowth = true),
            ),
        )
        assertTrue(hasWarningOn(result, "rules.friendliesCountForGrowth"))
    }

    @Test
    fun `un minimo di minuti superiore alla partita viene rifiutato`() {
        val result = ConfigValidator.validate(
            LeagueConfig(rules = RulesConfig(customMinimumMinutes = 120)),
        )
        assertTrue(hasErrorOn(result, "rules.customMinimumMinutes"))
    }

    // ------------------------------------------------------------------------ mercato

    @Test
    fun `disattivare l offerta massima automatica genera un avviso`() {
        val result = ConfigValidator.validate(
            LeagueConfig(market = MarketConfig(proxyBiddingEnabled = false)),
        )
        assertTrue(hasWarningOn(result, "market.proxyBiddingEnabled"))
    }

    @Test
    fun `finestre di mercato senza fasce definite bloccherebbero il mercato`() {
        val result = ConfigValidator.validate(
            LeagueConfig(
                market = MarketConfig(
                    windowMode = MarketWindowMode.FASCE_ORARIE,
                    windowSlots = emptyList(),
                ),
            ),
        )
        assertTrue(hasErrorOn(result, "market.windowSlots"))
    }

    @Test
    fun `durate di prestito incoerenti vengono rifiutate`() {
        val result = ConfigValidator.validate(
            LeagueConfig(market = MarketConfig(minLoanMatchDays = 20, maxLoanMatchDays = 5)),
        )
        assertTrue(hasErrorOn(result, "market.minLoanMatchDays"))
    }

    // --------------------------------------------------------------------- divisioni

    /** Un girone unico e' la configurazione predefinita, e non deve dire niente. */
    @Test
    fun `senza divisioni non si controlla niente`() {
        val result = ConfigValidator.validate(LeagueConfig())
        assertFalse(hasWarningOn(result, "divisions.count"))
        assertFalse(hasErrorOn(result, "divisions.directPromotions"))
    }

    /**
     * L'errore che si vede solo alla terza stagione: salgono tre, scendono due.
     *
     * Nessun sintomo per un anno, poi la divisione di sopra ha un club in piu' ogni
     * stagione. Quando si nota non si corregge — si ricomincia — quindi va detto prima.
     */
    @Test
    fun `promozioni e retrocessioni che non coincidono sono un errore`() {
        val sbilanciata = LeagueConfig(
            setup = SetupConfig(totalClubs = 18, aiClubs = 9),
            divisions = DivisionsConfig(
                count = 3,
                directPromotions = 3,
                playoffSlots = 0,
                directRelegations = 2,
                playoutSlots = 0,
            ),
        )
        val result = ConfigValidator.validate(sbilanciata)

        assertTrue(hasErrorOn(result, "divisions.directPromotions"), result.describe())
        // Il messaggio deve dire i due numeri, o non si capisce cosa aggiustare.
        assertTrue(
            result.errors.first { it.field == "divisions.directPromotions" }
                .message.contains("scendono 2"),
            result.describe(),
        )
    }

    /**
     * I playoff assegnano un posto, e la configurazione che lo rispetta deve passare.
     *
     * Una che sale diretta piu' il vincitore dei playoff fanno due; due che scendono
     * dirette fanno due. Se il validatore contasse quattro promossi perche' quattro
     * squadre giocano i playoff, respingerebbe la configurazione piu' normale che c'e'.
     */
    @Test
    fun `un playoff a quattro che promuove uno e in equilibrio`() {
        val equilibrata = LeagueConfig(
            setup = SetupConfig(totalClubs = 24, aiClubs = 12),
            divisions = DivisionsConfig(
                count = 3,
                names = listOf("Serie A", "Serie B", "Serie C"),
                directPromotions = 1,
                playoffSlots = 4,
                directRelegations = 2,
                playoutSlots = 0,
            ),
        )
        val result = ConfigValidator.validate(equilibrata)

        assertFalse(hasErrorOn(result, "divisions.directPromotions"), result.describe())
    }

    @Test
    fun `divisioni con una squadra a testa vengono rifiutate`() {
        val assurda = LeagueConfig(
            setup = SetupConfig(totalClubs = 4, aiClubs = 2),
            divisions = DivisionsConfig(count = 4),
        )
        assertTrue(hasErrorOn(ConfigValidator.validate(assurda), "divisions.count"))
    }

    @Test
    fun `divisioni da tre squadre avvisano che la classifica non significa niente`() {
        val strette = LeagueConfig(
            setup = SetupConfig(totalClubs = 9, aiClubs = 4),
            divisions = DivisionsConfig(count = 3, names = listOf("A", "B", "C")),
        )
        assertTrue(hasWarningOn(ConfigValidator.validate(strette), "divisions.count"))
    }

    /**
     * Fasce sovrapposte: un avviso, non un errore.
     *
     * Il regolamento sa cosa fare — la promozione batte la retrocessione — quindi la lega
     * gira. Ma chi ha scritto sei posizioni di spareggio in una divisione da cinque quasi
     * certamente non voleva questo, e conviene dirglielo.
     */
    @Test
    fun `fasce di promozione e retrocessione sovrapposte danno un avviso`() {
        val sovrapposte = LeagueConfig(
            setup = SetupConfig(totalClubs = 10, aiClubs = 5),
            divisions = DivisionsConfig(
                count = 2,
                names = listOf("A", "B"),
                directPromotions = 1,
                playoffSlots = 2,
                directRelegations = 1,
                playoutSlots = 2,
            ),
        )
        val result = ConfigValidator.validate(sovrapposte)

        assertTrue(hasWarningOn(result, "divisions.playoffSlots"), result.describe())
        assertFalse(hasErrorOn(result, "divisions.playoffSlots"))
    }

    @Test
    fun `nomi in meno delle divisioni avvisano con il nome di ripiego`() {
        val senzaNomi = LeagueConfig(
            setup = SetupConfig(totalClubs = 20, aiClubs = 10),
            divisions = DivisionsConfig(count = 4, names = listOf("Serie A", "Serie B")),
        )
        val result = ConfigValidator.validate(senzaNomi)

        assertTrue(hasWarningOn(result, "divisions.names"))
        assertTrue(
            result.warnings.first { it.field == "divisions.names" }
                .message.contains("Divisione 4"),
            result.describe(),
        )
    }

    // --------------------------------------------------------------- mercato iniziale

    /**
     * Il difetto che ha tenuto ferma una lega vera: tre aste per club da un'ora l'una.
     *
     * Con quei numeri servono piu' di cinque ore di sole aggiudicazioni per arrivare a
     * sedici giocatori, e sono cinque ore *ottimistiche*, senza un'asta andata deserta ne'
     * un duello perso. Nella lega vera erano giorni, e nessuno ha mai visto una partita.
     */
    @Test
    fun `un mercato iniziale lento come quello a regime viene segnalato`() {
        val config = LeagueConfig(
            market = MarketConfig(
                initialParallelAuctionsPerClub = 3,
                initialAuctionDurationMinutes = 60,
            ),
        )
        val result = ConfigValidator.validate(config)
        assertTrue(
            hasWarningOn(result, "market.initialAuctionDurationMinutes"),
            "tre aste da un'ora non riempiono una rosa in una serata:\n${result.describe()}",
        )
    }

    /** I valori predefiniti devono stare dentro la serata, o l'avviso non serve a niente. */
    @Test
    fun `il mercato iniziale predefinito riempie le rose nella stessa serata`() {
        val result = ConfigValidator.validate(LeagueConfig())
        assertTrue(
            !hasWarningOn(result, "market.initialAuctionDurationMinutes"),
            "i valori predefiniti non riempiono le rose in tempo:\n${result.describe()}",
        )
    }

    @Test
    fun `zero aste iniziali per club e un errore, non un avviso`() {
        val result = ConfigValidator.validate(
            LeagueConfig(market = MarketConfig(initialParallelAuctionsPerClub = 0)),
        )
        assertTrue(hasErrorOn(result, "market.initialParallelAuctionsPerClub"))
    }

    /**
     * Alzare il minimo rosa allunga l'allestimento, e a un certo punto va detto.
     *
     * E' il legame che rende l'avviso utile: non giudica le aste in astratto, ma le aste
     * *rispetto a quante caselle ci sono da riempire*. Quaranta titolari con le aste
     * predefinite non ci stanno piu' in una serata.
     */
    @Test
    fun `un minimo rosa molto alto allunga l allestimento oltre la serata`() {
        val stretta = LeagueConfig(setup = SetupConfig(minSquadSize = 40, maxSquadSize = 45))
        assertTrue(
            hasWarningOn(
                ConfigValidator.validate(stretta),
                "market.initialAuctionDurationMinutes",
            ),
            "quaranta caselle a dodici l'ora non stanno in due ore",
        )
    }

    /**
     * Il soffitto del tick esiste e va detto: aste sempre piu' corte non velocizzano oltre.
     *
     * Un club agisce una volta per risveglio e i risvegli li conta il tick, che gira ogni
     * cinque minuti: dodici acquisti l'ora e non uno di piu'. Chi mettesse cento aste da
     * un minuto si aspetterebbe di finire in un lampo, e resterebbe a guardare.
     */
    @Test
    fun `aste iniziali fulminee non superano il ritmo del tick`() {
        val impaziente = LeagueConfig(
            setup = SetupConfig(minSquadSize = 40, maxSquadSize = 45),
            market = MarketConfig(
                initialParallelAuctionsPerClub = 100,
                initialAuctionDurationMinutes = 1,
            ),
        )
        assertTrue(
            hasWarningOn(
                ConfigValidator.validate(impaziente),
                "market.initialAuctionDurationMinutes",
            ),
            "il tick ogni cinque minuti resta il soffitto anche con cento aste da un minuto",
        )
    }

    // ------------------------------------------------------------------------ utility

    @Test
    fun `le giornate di un girone seguono il metodo del cerchio`() {
        assertEquals(19, ConfigValidator.roundRobinMatchDays(20))
        assertEquals(15, ConfigValidator.roundRobinMatchDays(16))
        assertEquals(7, ConfigValidator.roundRobinMatchDays(8))
        // Con numero dispari serve un turno di riposo in piu'.
        assertEquals(7, ConfigValidator.roundRobinMatchDays(7))
        assertEquals(0, ConfigValidator.roundRobinMatchDays(1))
    }

    @Test
    fun `describe elenca tutti i problemi trovati`() {
        val result = ConfigValidator.validate(
            LeagueConfig(setup = SetupConfig(totalClubs = 1, aiClubs = 0)),
        )
        val text = result.describe()
        assertTrue(text.contains("ERRORE"))
        assertTrue(text.contains("setup.totalClubs"))
    }
}
