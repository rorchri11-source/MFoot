package dev.mfoot.core.match

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test

/**
 * Non e' un test con asserzioni: e' lo strumento di misura.
 *
 * Stampa i numeri veri del motore in modo che la taratura sia una decisione informata
 * invece che un'impressione. Le asserzioni stanno in [MatchBalanceTest]; qui si guarda.
 *
 * Si esegue con:
 * ```
 * gradlew :core:test --tests "*BalanceReportTest*" -i
 * ```
 */
class BalanceReportTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    @Test
    fun `stampa il quadro di bilanciamento`() {
        val scenari = listOf(
            Triple("Squadre pari (75 vs 75)", 75 to 75, 2000),
            Triple("Divario piccolo (78 vs 73)", 78 to 73, 2000),
            Triple("Divario medio (80 vs 70)", 80 to 70, 2000),
            Triple("Divario grande (85 vs 65)", 85 to 65, 1000),
            Triple("Sfavorito in casa (70 vs 80)", 70 to 80, 1000),
        )

        val output = buildString {
            appendLine()
            appendLine("=========== QUADRO DI BILANCIAMENTO MFOOT ===========")
            appendLine("sigmoidK = ${config.engine.sigmoidK}")
            appendLine("azioni/partita = ${config.engine.actionsPerMatch}")
            appendLine("xG centrale = ${config.engine.baseXgCentral}, laterale = ${config.engine.baseXgWide}")
            appendLine("tiro in zona offensiva = ${config.engine.shotChanceInAttackingZone}")
            appendLine("vantaggio casa = ${config.engine.homeAdvantage}")
            appendLine()

            scenari.forEach { (titolo, forze, partite) ->
                val report = BalanceHarness.run(
                    world, config, forze.first, forze.second, partite,
                )
                appendLine(report.describe(titolo))
                appendLine()
            }

            appendLine("--- Effetto delle tattiche (75 vs 75)")
            val catenaccio = BalanceHarness.run(
                world, config, 75, 75, 1000,
                homeTactics = Tactics.CATENACCIO, awayTactics = Tactics.DEFAULT,
            )
            appendLine("  Catenaccio in casa: ${pct(catenaccio.homeWinRate)} vittorie, ${fmt(catenaccio.goalsPerMatch)} gol/partita")

            val arrembante = BalanceHarness.run(
                world, config, 75, 75, 1000,
                homeTactics = Tactics.ARREMBANTE, awayTactics = Tactics.DEFAULT,
            )
            appendLine("  Arrembante in casa: ${pct(arrembante.homeWinRate)} vittorie, ${fmt(arrembante.goalsPerMatch)} gol/partita")

            appendLine()
            appendLine("--- Effetto dell'allenatore (75 vs 75, 5 stelle contro 1)")
            val allenatore = BalanceHarness.run(
                world, config, 75, 75, 1000,
                homeCoachStars = 5, awayCoachStars = 1,
            )
            appendLine("  ${pct(allenatore.homeWinRate)} vittorie casa")
            appendLine("=====================================================")
        }

        println(output)
    }

    private fun pct(v: Double) = "${StrictMath.round(v * 1000) / 10.0}%"
    private fun fmt(v: Double) = "${StrictMath.round(v * 100) / 100.0}"
}
