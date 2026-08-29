package dev.mfoot.core.match

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Reparto
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
 *
 * ## Perche' misura anche chi segna e quanti duelli si giocano
 *
 * Perche' gol a partita e percentuale di pareggi non bastano a dire se una partita e'
 * *credibile*. Un motore puo' produrre 2,6 gol a partita facendoli segnare tutti al
 * centravanti, e i numeri d'insieme non se ne accorgono — e' esattamente il difetto che il
 * proprietario ha segnalato guardando, non misurando: *«gol solo da quelli forti,
 * dall'attacco e basta»*.
 *
 * Le misure per contesa servono a un'altra cosa ancora: **tarare una manopola alla volta**.
 * La pendenza del dribbling si legge nei dribbling riusciti a partita, quella del passaggio
 * nella precisione dei passaggi. Guardando solo i gol si girerebbero cinque manopole
 * insieme senza sapere quale ha fatto cosa.
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
        /** Gol per reparto di chi li ha segnati. */
        val goalsByReparto: Map<Reparto, Int> = emptyMap(),
        /** Quanti giocatori diversi hanno segnato almeno una volta. */
        val distinctScorers: Int = 0,
        /** Quanti potevano segnare: titolari piu' panchina delle due squadre. */
        val squadSize: Int = 0,
        val totalDuelsWon: Int = 0,
        val totalDuelsLost: Int = 0,
        val totalDribblesCompleted: Int = 0,
        val totalDribblesAttempted: Int = 0,
        val totalPassesCompleted: Int = 0,
        val totalPassesAttempted: Int = 0,
        /**
         * Angoli e falli si contano **dagli eventi**, non da un contatore.
         *
         * Stessa ragione gia' scritta in `PlayedMatch.conta`: la timeline e' la partita. Un
         * totale salvato a parte sarebbe un secondo posto in cui la stessa verita' puo'
         * sbagliarsi, e non si saprebbe a quale dei due credere.
         */
        val totalCorners: Int = 0,
        val totalFouls: Int = 0,
    ) {
        val cornersPerMatch: Double get() = totalCorners.toDouble() / matches
        val foulsPerMatch: Double get() = totalFouls.toDouble() / matches

        val homeWinRate: Double get() = homeWins.toDouble() / matches
        val drawRate: Double get() = draws.toDouble() / matches
        val awayWinRate: Double get() = awayWins.toDouble() / matches
        val goalsPerMatch: Double get() = totalGoals.toDouble() / matches
        val shotsPerMatch: Double get() = totalShots.toDouble() / matches
        val xgPerMatch: Double get() = totalXg / matches
        val conversionRate: Double get() = if (totalShots == 0) 0.0 else totalGoals.toDouble() / totalShots

        /** Che quota dei gol arriva da questo reparto. */
        fun goalShare(reparto: Reparto): Double =
            if (totalGoals == 0) 0.0 else (goalsByReparto[reparto] ?: 0).toDouble() / totalGoals

        val duelsPerMatch: Double get() = (totalDuelsWon + totalDuelsLost).toDouble() / matches
        val dribblesPerMatch: Double get() = totalDribblesCompleted.toDouble() / matches

        /** Quanti dribbling su cento vanno a buon fine. */
        val dribbleSuccess: Double
            get() = if (totalDribblesAttempted == 0) {
                0.0
            } else {
                totalDribblesCompleted.toDouble() / totalDribblesAttempted
            }

        val passesPerMatch: Double get() = totalPassesAttempted.toDouble() / matches

        val passAccuracy: Double
            get() = if (totalPassesAttempted == 0) {
                0.0
            } else {
                totalPassesCompleted.toDouble() / totalPassesAttempted
            }

        fun describe(title: String): String = buildString {
            appendLine("--- $title ($matches partite)")
            appendLine("  Casa      ${pct(homeWinRate)}   Pari ${pct(drawRate)}   Ospite ${pct(awayWinRate)}")
            appendLine("  Gol/partita        ${fmt(goalsPerMatch)}  (casa ${fmt(homeGoals.toDouble() / matches)}, ospite ${fmt(awayGoals.toDouble() / matches)})")
            appendLine("  Tiri/partita       ${fmt(shotsPerMatch)}")
            appendLine("  xG/partita         ${fmt(xgPerMatch)}")
            appendLine("  Conversione        ${pct(conversionRate)}")
            appendLine("  Possesso casa      ${pct(averagePossessionHome)}")
            appendLine("  Porta inviolata    ${pct(cleanSheets.toDouble() / matches)}")
            appendLine("  Scarto massimo     $biggestWin")
            appendLine(
                "  Chi segna          att ${pct(goalShare(Reparto.ATTACCO))} · " +
                    "cen ${pct(goalShare(Reparto.CENTROCAMPO))} · " +
                    "dif ${pct(goalShare(Reparto.DIFESA))} · " +
                    "por ${pct(goalShare(Reparto.PORTIERE))}",
            )
            appendLine("  Marcatori diversi  $distinctScorers su $squadSize")
            append("  Angoli ${fmt(cornersPerMatch)} · falli ${fmt(foulsPerMatch)}")
            if (duelsPerMatch > 0.0) {
                appendLine()
                appendLine("  Duelli/partita     ${fmt(duelsPerMatch)}")
                appendLine("  Dribbling/partita  ${fmt(dribblesPerMatch)}  riusciti ${pct(dribbleSuccess)}")
                append("  Passaggi/partita   ${fmt(passesPerMatch)}  precisione ${pct(passAccuracy)}")
            }
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

        // Il ruolo in cui ciascuno e' schierato: serve a sapere **chi** segna, che e' la
        // meta' della credibilita' che i numeri d'insieme non vedono. La panchina va
        // inclusa o un gol di chi e' entrato finirebbe attribuito al reparto sbagliato.
        val reparti: Map<PlayerId, Reparto> =
            (home.lineup.slots + away.lineup.slots)
                .associate { it.player.id to it.position.reparto } +
                (home.lineup.bench + away.lineup.bench)
                    .associate { it.id to it.primaryPosition.reparto }

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

        val goalsByReparto = mutableMapOf<Reparto, Int>()
        val scorers = mutableSetOf<PlayerId>()
        var duelsWon = 0
        var duelsLost = 0
        var dribblesCompleted = 0
        var dribblesAttempted = 0
        var passesCompleted = 0
        var passesAttempted = 0
        var corners = 0
        var fouls = 0

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

            for ((playerId, s) in result.stats) {
                if (s.goals > 0) {
                    scorers += playerId
                    val reparto = reparti[playerId] ?: Reparto.ATTACCO
                    goalsByReparto[reparto] = (goalsByReparto[reparto] ?: 0) + s.goals
                }
                duelsWon += s.duelsWon
                duelsLost += s.duelsLost
                dribblesCompleted += s.dribblesCompleted
                dribblesAttempted += s.dribblesAttempted
                passesCompleted += s.passesCompleted
                passesAttempted += s.passesAttempted
                fouls += s.fouls
            }
            corners += result.events.count { it.type == MatchEventType.ANGOLO }
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
            goalsByReparto = goalsByReparto,
            distinctScorers = scorers.size,
            squadSize = reparti.size,
            totalDuelsWon = duelsWon,
            totalDuelsLost = duelsLost,
            totalDribblesCompleted = dribblesCompleted,
            totalDribblesAttempted = dribblesAttempted,
            totalPassesCompleted = passesCompleted,
            totalPassesAttempted = passesAttempted,
            totalCorners = corners,
            totalFouls = fouls,
        )
    }
}
