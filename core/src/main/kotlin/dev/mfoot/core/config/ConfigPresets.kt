package dev.mfoot.core.config

import java.time.LocalDate
import java.time.LocalTime

/**
 * Configurazioni pronte.
 *
 * Sessanta impostazioni in faccia al primo utilizzo sono un muro: l'admin apre la
 * schermata, non sa cosa significhi meta' delle voci e abbandona. I preset riempiono
 * tutto con un tap e lasciano la sezione "Avanzate" a chi vuole davvero smanettare.
 *
 * Ogni preset e' un punto di partenza coerente, gia' passato dal [ConfigValidator].
 */
object ConfigPresets {

    data class Preset(
        val id: String,
        val name: String,
        val description: String,
        val build: (clubs: Int, humanClubs: Int, startDate: LocalDate) -> LeagueConfig,
    )

    val all: List<Preset> = listOf(
        Preset(
            id = "sprint",
            name = "Sprint",
            description = "Due partite al giorno, stagione in una decina di giorni. " +
                "Ritmo intenso, mercato sempre in movimento, crescita rapida.",
            build = ::sprint,
        ),
        Preset(
            id = "classica",
            name = "Classica",
            description = "Una partita al giorno, stagione di circa tre settimane. " +
                "Ritmo sostenibile, si puo' saltare una sera senza restare indietro.",
            build = ::classica,
        ),
        Preset(
            id = "fantacalcio",
            name = "Fantacalcio",
            description = "Serata d'asta iniziale, tre giornate a settimana, economia " +
                "tirata e contratti lunghi. Il ritmo delle leghe fantacalcio serie.",
            build = ::fantacalcio,
        ),
    )

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }

    // ------------------------------------------------------------------------ sprint

    fun sprint(
        clubs: Int = 16,
        humanClubs: Int = 8,
        startDate: LocalDate = LocalDate.now(),
    ): LeagueConfig {
        val matchDays = ConfigValidator.roundRobinMatchDays(clubs)
        // Due partite al giorno piu' un margine per aste e giorni buca.
        val days = (matchDays + 1) / 2 + 3
        return LeagueConfig(
            setup = SetupConfig(
                leagueName = "Lega Sprint",
                totalClubs = clubs,
                aiClubs = (clubs - humanClubs).coerceAtLeast(0),
                mode = if (clubs > humanClubs) LeagueMode.MULTIPLAYER_PIU_AI else LeagueMode.SOLO_MULTIPLAYER,
                minSquadSize = 18,
                maxSquadSize = 30,
            ),
            economy = EconomyConfig(
                startingCredits = 320,
                recurringIncome = 10,
                incomeCadence = IncomeCadence.PER_GIORNATA,
            ),
            market = MarketConfig(
                initialAuctionMode = InitialAuctionMode.ASINCRONA,
                auctionDurationMinutes = 90,
                defaultContractMatchDays = matchDays,
            ),
            calendar = CalendarConfig(
                startDate = startDate,
                endDate = startDate.plusDays(days.toLong()),
                matchesPerDayPerClub = 2,
                kickoffSlots = listOf(LocalTime.of(18, 30), LocalTime.of(21, 0)),
                matchSpeed = MatchSpeed.RAPIDA,
            ),
            // Rosa profonda obbligatoria: con due partite al giorno non si puo' schierare
            // due volte lo stesso undici senza bruciarlo.
            rules = RulesConfig(growthMultiplier = 1.0),
        )
    }

    // ---------------------------------------------------------------------- classica

    fun classica(
        clubs: Int = 16,
        humanClubs: Int = 10,
        startDate: LocalDate = LocalDate.now(),
    ): LeagueConfig {
        val matchDays = ConfigValidator.roundRobinMatchDays(clubs)
        val days = matchDays + 4
        return LeagueConfig(
            setup = SetupConfig(
                leagueName = "Lega Classica",
                totalClubs = clubs,
                aiClubs = (clubs - humanClubs).coerceAtLeast(0),
                mode = if (clubs > humanClubs) LeagueMode.MULTIPLAYER_PIU_AI else LeagueMode.SOLO_MULTIPLAYER,
                minSquadSize = 16,
                maxSquadSize = 26,
            ),
            economy = EconomyConfig(
                startingCredits = 260,
                recurringIncome = 12,
                incomeCadence = IncomeCadence.PER_GIORNATA,
            ),
            market = MarketConfig(
                initialAuctionMode = InitialAuctionMode.ASINCRONA,
                auctionDurationMinutes = 180,
                defaultContractMatchDays = matchDays,
            ),
            calendar = CalendarConfig(
                startDate = startDate,
                endDate = startDate.plusDays(days.toLong()),
                matchesPerDayPerClub = 1,
                kickoffSlots = listOf(LocalTime.of(21, 0)),
                matchSpeed = MatchSpeed.VELOCE,
            ),
            rules = RulesConfig(growthMultiplier = 1.4),
        )
    }

    // ------------------------------------------------------------------- fantacalcio

    fun fantacalcio(
        clubs: Int = 12,
        humanClubs: Int = 12,
        startDate: LocalDate = LocalDate.now(),
    ): LeagueConfig {
        val matchDays = ConfigValidator.roundRobinMatchDays(clubs) * 2  // andata e ritorno
        // Tre giornate a settimana: martedi', giovedi', domenica.
        val days = (matchDays * 7) / 3 + 5
        return LeagueConfig(
            setup = SetupConfig(
                leagueName = "Lega Fantacalcio",
                totalClubs = clubs,
                aiClubs = (clubs - humanClubs).coerceAtLeast(0),
                mode = if (clubs > humanClubs) LeagueMode.MULTIPLAYER_PIU_AI else LeagueMode.SOLO_MULTIPLAYER,
                minSquadSize = 20,
                maxSquadSize = 25,
            ),
            economy = EconomyConfig(
                startingCredits = 200,
                recurringIncome = 6,
                incomeCadence = IncomeCadence.PER_GIORNATA,
                placementPrizes = listOf(150, 90, 55, 30, 15),
            ),
            market = MarketConfig(
                initialAuctionMode = InitialAuctionMode.SERATA_ASTA,
                auctionDurationMinutes = 240,
                defaultContractMatchDays = matchDays,
                maxParallelAuctionsPerClub = 2,
            ),
            calendar = CalendarConfig(
                startDate = startDate,
                endDate = startDate.plusDays(days.toLong()),
                matchesPerDayPerClub = 1,
                kickoffSlots = listOf(LocalTime.of(21, 0)),
                matchSpeed = MatchSpeed.VELOCE,
            ),
            rules = RulesConfig(growthMultiplier = 1.8, youthMaxAge = 20),
        )
    }
}
