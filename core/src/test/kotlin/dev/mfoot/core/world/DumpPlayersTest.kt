package dev.mfoot.core.world

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Player
import java.time.LocalDate
import kotlin.test.Test

/**
 * Stampa alcuni giocatori generati, per progettare l'interfaccia su dati veri.
 *
 * Disegnare una scheda su numeri inventati porta a scoprire tardi che i valori reali
 * hanno un'altra distribuzione — nomi piu' lunghi, attributi meno vari, fasce d'eta'
 * diverse — e che il layout non regge. Meglio partire da quello che il gioco produce
 * davvero.
 *
 *     gradlew :core:test --tests "*DumpPlayersTest*" -i
 */
class DumpPlayersTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    @Test
    fun `stampa un campione rappresentativo`() {
        val campione = listOf(
            "FUORICLASSE" to world.players.maxBy { it.overall },
            "GIOVANE PROMESSA" to world.players
                .filter { it.age <= 19 }.maxBy { it.potentialMax - it.overall },
            "PORTIERE TOP" to world.goalkeepers.maxBy { it.overall },
            "VETERANO" to world.players.filter { it.age >= 33 }.maxBy { it.overall },
            "GREGARIO" to world.players.filter { it.overall in 60..66 }.first(),
        )

        println()
        println("=".repeat(70))
        campione.forEach { (etichetta, p) -> println(descrivi(etichetta, p)) }
        println("=".repeat(70))
        println(statistiche())
    }

    private fun descrivi(etichetta: String, p: Player): String = buildString {
        val stima = PotentialEstimator.estimate(p, observerId = 1L)
        appendLine()
        appendLine("--- $etichetta ---")
        appendLine("nome        ${p.fullName}   (breve: ${p.shortName})")
        appendLine("nazione     ${p.nationality}")
        appendLine("eta         ${p.age}")
        appendLine("ruolo       ${p.primaryPosition.short} (${p.primaryPosition.label})" +
            if (p.secondaryPositions.isEmpty()) "" else "  alt: ${p.secondaryPositions.joinToString { it.short }}")
        appendLine("overall     ${p.overall}")
        appendLine("stima pot.  ${stima.first}-${stima.last}   (vero: ${p.potentialMin}-${p.potentialMax})")
        appendLine("valore      ${Valuation.marketValue(p, config)} crediti")
        appendLine("piede deb.  ${p.weakFoot}/5    tecnica ${p.skillStars}/5")
        appendLine("tratti      ${if (p.traits.isEmpty()) "nessuno" else p.traits.joinToString { it.label }}")
        appendLine("stamina ${p.stamina}  morale ${p.morale}  forma ${p.form}")
        appendLine("attributi:")
        val rilevanti = p.primaryPosition.relevantAttributes.toSet()
        Attr.entries
            .filterNot { it.goalkeeperOnly != p.isGoalkeeper }
            .forEach { attr ->
                val marca = if (attr in rilevanti) "*" else " "
                appendLine("  $marca ${attr.label.padEnd(16)} ${p.attributes[attr]}")
            }
    }

    private fun statistiche(): String = buildString {
        appendLine("Totale giocatori: ${world.players.size}")
        appendLine("Lunghezza nome completo: min ${world.players.minOf { it.fullName.length }}, " +
            "max ${world.players.maxOf { it.fullName.length }}, " +
            "media ${"%.1f".format(world.players.map { it.fullName.length }.average())}")
        appendLine("Overall: min ${world.players.minOf { it.overall }}, " +
            "max ${world.players.maxOf { it.overall }}, " +
            "media ${"%.1f".format(world.players.map { it.overall }.average())}")
        appendLine("Con almeno un tratto: ${world.players.count { it.traits.isNotEmpty() }}")
        appendLine("Ampiezza forbice stimata: media ${"%.1f".format(
            world.players.map { val e = PotentialEstimator.estimate(it, 1L); (e.last - e.first).toDouble() }.average(),
        )}")
    }
}
