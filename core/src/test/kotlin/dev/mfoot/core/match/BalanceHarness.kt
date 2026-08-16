package dev.mfoot.core.match

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.world.GeneratedWorld

/**
 * Simulatore a forza bruta per la taratura del motore.
 *
 * Bilanciare un manageriale a occhio e' impossibile: nessuno riesce a dire, guardando
 * il codice, se una squadra con dieci punti di overall in piu' vincera' il 60% o il 90%
 * delle partite. Facendo girare diecimila partite in pochi secondi, invece, diventa una
 * misura.
 *
 * E' il motivo per cui `core` non dipende ne' da Android ne' dal server: puo' girare
 * diecimila volte di fila in un test.
 */
object BalanceHarness {

    data class Report(
        val matches: Int,
        val homeWins: Int,
        val draws: Int,
        val awayWins: Int,
        val totalGoals: Int,
        val homeGoals: Int,
        val awayGoals: Int,
        val totalShots: Int,
        val totalXg: Double,
        val averagePossessionHome: Double,
        val cleanSheets: Int,
        val biggestWin: Int,
    ) {
        val homeWinRate: Double get() = homeWins.toDouble() / matches
        val drawRate: Double get() = draws.toDouble() / matches
        val awayWinRate: Double get() = awayWins.toDouble() / matches
        val goalsPerMatch: Double get() = totalGoals.toDouble() / matches
        val shotsPerMatch: Double get() = totalShots.toDouble() / matches
        val xgPerMatch: Double get() = totalXg / matches
        val conversionRate: Double get() = if (totalShots == 0) 0.0 else totalGoals.toDouble() / totalShots

        fun describe(title: String): String = buildString {
            appendLine("--- $title ($matches partite)")
            appendLine("  Casa      ${pct(homeWinRate)}   Pari ${pct(drawRate)}   Ospite ${pct(awayWinRate)}")
            appendLine("  Gol/partita        ${fmt(goalsPerMatch)}  (casa ${fmt(homeGoals.toDouble() / matches)}, ospite ${fmt(awayGoals.toDouble() / matches)})")
            appendLine("  Tiri/partita       ${fmt(shotsPerMatch)}")
            appendLine("  xG/partita         ${fmt(xgPerMatch)}")
            appendLine("  Conversione        ${pct(conversionRate)}")
            appendLine("  Possesso casa      ${pct(averagePossessionHome)}")
            appendLine("  Porta inviolata    ${pct(cleanSheets.toDouble() / matches)}")
            append("  Scarto massimo     $biggestWin")
        }

        private fun pct(v: Double) = "${StrictMath.round(v * 1000) / 10.0}%"
        private fun fmt(v: Double) = "${StrictMath.round(v * 100) / 100.0}"
    }

    /**
     * Simula [matches] partite fra due squadre di forza data, alternando il seed.
     *
     * Le due squadre vengono costruite una volta sola: quello che varia e' solo il seed,
     * cosi' la differenza fra i risultati dipende dal motore e non dal campione.
     */
    fun run(
        world: GeneratedWorld,
        config: LeagueConfig,
        homeOverall: Int,
        awayOverall: Int,
        matches: Int,
        homeTactics: Tactics = Tactics.DEFAULT,
        awayTactics: Tactics = Tactics.DEFAULT,
        homeCoachStars: Int = 3,
        awayCoachStars: Int = 3,
        importance: MatchImportance = MatchImportance.CAMPIONATO,
    ): Report {
        val home = TestSquads.build(
            world, 1, "Casa", homeOverall,
            tactics = homeTactics, coachStars = homeCoachStars,
        )
        val away = TestSquads.build(
            world, 2, "Ospite", awayOverall,
            tactics = awayTactics, coachStars = awayCoachStars,
            exclude = TestSquads.playersOf(home),
        )

        var homeWins = 0
        var draws = 0
        var awayWins = 0
        var totalGoals = 0
        var homeGoalsTotal = 0
        var awayGoalsTotal = 0
        var totalShots = 0
        var totalXg = 0.0
        var possessionSum = 0.0
        var cleanSheets = 0
        var biggestWin = 0

        repeat(matches) { index ->
            val result = MatchEngine.simulate(
                home = home,
                away = away,
                config = config,
                seed = 1_000_000L + index,
                importance = importance,
            )

            when (result.winner) {
                Side.CASA -> homeWins++
                Side.OSPITE -> awayWins++
                null -> draws++
            }
            totalGoals += result.homeGoals + result.awayGoals
            homeGoalsTotal += result.homeGoals
            awayGoalsTotal += result.awayGoals
            totalShots += result.homeShots + result.awayShots
            totalXg += result.homeXg + result.awayXg
            possessionSum += result.homePossession
            if (result.homeGoals == 0 || result.awayGoals == 0) cleanSheets++
            val margin = StrictMath.abs(result.homeGoals - result.awayGoals)
            if (margin > biggestWin) biggestWin = margin
        }

        return Report(
            matches = matches,
            homeWins = homeWins,
            draws = draws,
            awayWins = awayWins,
            totalGoals = totalGoals,
            homeGoals = homeGoalsTotal,
            awayGoals = awayGoalsTotal,
            totalShots = totalShots,
            totalXg = totalXg,
            averagePossessionHome = possessionSum / matches,
            cleanSheets = cleanSheets,
            biggestWin = biggestWin,
        )
    }
}
