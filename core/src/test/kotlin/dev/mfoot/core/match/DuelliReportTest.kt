package dev.mfoot.core.match

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test

/**
 * Lo strumento con cui si tara il motore a duelli.
 *
 * Non ha asserzioni: mette i due motori uno accanto all'altro sugli stessi scenari e sullo
 * stesso mondo. Le asserzioni stanno in [MatchBalanceTest], che e' il collaudo; qui si
 * guarda per decidere quale delle cinque manopole girare.
 *
 * ```
 * gradlew :core:test --tests "*DuelliReportTest*" -i
 * ```
 */
class DuelliReportTest {

    private val preset = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(preset)

    /**
     * Il motore vecchio va **spento a mano**.
     *
     * Da quando l'interruttore e' acceso di serie, prendere il preset e basta significa
     * confrontare il motore con se stesso — ed e' esattamente quello che questo file ha
     * fatto per una misura, stampando due colonne identiche senza che niente segnalasse
     * niente. Uno strumento di misura rotto e' peggio di nessuno strumento.
     */
    private val base: LeagueConfig =
        preset.copy(engine = preset.engine.copy(duelliAttivi = false))

    private val conDuelli: LeagueConfig =
        preset.copy(engine = preset.engine.copy(duelliAttivi = true))

    @Test
    fun `confronta il motore vecchio e quello a duelli`() {
        val scenari = listOf(
            "Squadre pari (75 vs 75)" to (75 to 75),
            "Divario piccolo (78 vs 73)" to (78 to 73),
            "Divario medio (80 vs 70)" to (80 to 70),
            "Divario grande (85 vs 65)" to (85 to 65),
        )

        val output = buildString {
            appendLine()
            appendLine("=============== MOTORE A DUELLI ===============")
            appendLine(
                "pendenze   corsa ${conDuelli.engine.kCorsa} · dribbling ${conDuelli.engine.kDribbling} · " +
                    "contrasto ${conDuelli.engine.kContrasto} · aereo ${conDuelli.engine.kAereo} · " +
                    "passaggio ${conDuelli.engine.kPassaggio}",
            )
            appendLine(
                "equilibri  corsa ${conDuelli.engine.equilibrioCorsa} · " +
                    "dribbling ${conDuelli.engine.equilibrioDribbling} · " +
                    "contrasto ${conDuelli.engine.equilibrioContrasto} · " +
                    "aereo ${conDuelli.engine.equilibrioAereo} · " +
                    "passaggio ${conDuelli.engine.equilibrioPassaggio}",
            )
            appendLine(
                "azioni ${conDuelli.engine.actionsPerMatchDuelli} · " +
                    "tiro in zona ${conDuelli.engine.shotChanceDuelli}",
            )
            appendLine()

            scenari.forEach { (titolo, forze) ->
                val vecchio = BalanceHarness.run(world, base, forze.first, forze.second, 800)
                val nuovo = BalanceHarness.run(world, conDuelli, forze.first, forze.second, 800)
                appendLine(vecchio.describe("$titolo — MOTORE VECCHIO"))
                appendLine()
                appendLine(nuovo.describe("$titolo — DUELLI"))
                appendLine()
            }

            appendLine("--- Tattiche coi duelli (75 vs 75, 600 partite)")
            val catenaccio = BalanceHarness.run(
                world, conDuelli, 75, 75, 600, homeTactics = Tactics.CATENACCIO,
            )
            val arrembante = BalanceHarness.run(
                world, conDuelli, 75, 75, 600, homeTactics = Tactics.ARREMBANTE,
            )
            appendLine(
                "  Catenaccio  ${pct(catenaccio.homeWinRate)} vittorie · " +
                    "${fmt(catenaccio.goalsPerMatch)} gol · " +
                    "${fmt(catenaccio.dribblesPerMatch)} dribbling",
            )
            appendLine(
                "  Arrembante  ${pct(arrembante.homeWinRate)} vittorie · " +
                    "${fmt(arrembante.goalsPerMatch)} gol · " +
                    "${fmt(arrembante.dribblesPerMatch)} dribbling",
            )
            appendLine("===============================================")
        }

        println(output)
    }

    private fun pct(v: Double) = "${StrictMath.round(v * 1000) / 10.0}%"
    private fun fmt(v: Double) = "${StrictMath.round(v * 100) / 100.0}"
}
