package dev.mfoot.core.match

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La formazione automatica non è il ripiego per l'AI: è la rete che tiene in piedi il
 * calendario. Con due partite al giorno, prima o poi qualcuno si dimentica di schierare
 * la squadra — e il campionato non può fermarsi perché una persona è andata a cena.
 */
class AutoLineupTest {

    private val config = ConfigPresets.sprint(16, 8, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    /** Una rosa realistica: presa dal mondo vero, non costruita a tavolino. */
    private fun squad(size: Int = 20, from: Int = 0): List<Player> =
        world.players.drop(from).take(size)

    /** Una rosa con una copertura sensata dei ruoli, come sarebbe una rosa vera. */
    private fun balancedSquad(): List<Player> {
        val wanted = listOf(
            Position.POR to 2, Position.TD to 2, Position.DC to 4, Position.TS to 2,
            Position.MED to 2, Position.CC to 3, Position.TRQ to 1,
            Position.AD to 2, Position.AS to 2, Position.ATT to 2,
        )
        val used = mutableSetOf<Long>()
        return wanted.flatMap { (position, count) ->
            world.players
                .filter { it.primaryPosition == position && it.id.value !in used }
                .take(count)
                .onEach { used += it.id.value }
        }
    }

    @Test
    fun `schiera undici giocatori tutti diversi`() {
        val lineup = AutoLineup.build(balancedSquad(), Formation.F_4_3_3, MatchDay(1))
        assertNotNull(lineup)

        assertEquals(Formation.PLAYERS_ON_PITCH, lineup.slots.size)
        assertEquals(
            Formation.PLAYERS_ON_PITCH,
            lineup.slots.map { it.player.id }.toSet().size,
            "lo stesso giocatore è stato schierato due volte",
        )
    }

    @Test
    fun `gli slot rispettano l'ordine dei ruoli del modulo`() {
        val formation = Formation.F_4_2_3_1
        val lineup = AutoLineup.build(balancedSquad(), formation, MatchDay(1))
        assertNotNull(lineup)

        assertEquals(formation.positions, lineup.slots.map { it.position })
    }

    /**
     * Il caso che rende necessario assegnare i ruoli in ordine di scarsità.
     *
     * Se il portiere venisse scelto per ultimo si ritroverebbe fra i pali chiunque fosse
     * avanzato — e un giocatore di movimento in porta vale quaranta punti di malus, cioè
     * una partita persa in partenza.
     */
    @Test
    fun `in porta ci va un portiere`() {
        val lineup = AutoLineup.build(balancedSquad(), Formation.F_4_3_3, MatchDay(1))
        assertNotNull(lineup)

        val keeper = lineup.goalkeeper
        assertNotNull(keeper, "nessuno in porta")
        assertTrue(
            keeper.primaryPosition.isGoalkeeper,
            "in porta c'è ${keeper.shortName}, che di ruolo è ${keeper.primaryPosition.short}",
        )
    }

    @Test
    fun `senza abbastanza giocatori non si scende in campo`() {
        assertNull(AutoLineup.build(squad(size = 8), Formation.F_4_3_3, MatchDay(1)))
        assertNull(AutoLineup.setup(ClubId(1), "Corti", squad(size = 8), MatchDay(1)))
    }

    @Test
    fun `gli infortunati restano fuori`() {
        val base = balancedSquad()
        val infortunati = base.take(5).map { it.copy(injuredUntil = MatchDay(9)) }
        val rosa = infortunati + base.drop(5)

        val lineup = AutoLineup.build(rosa, Formation.F_4_3_3, MatchDay(4))
        assertNotNull(lineup)

        val inCampo = lineup.slots.map { it.player.id.value }.toSet()
        infortunati.forEach {
            assertTrue(it.id.value !in inCampo, "${it.shortName} è infortunato ma gioca")
        }
    }

    /**
     * È il vincolo su cui poggia metà del design: rosa profonda e Primavera servono
     * perché la stessa squadra non regge due partite al giorno. Se la formazione
     * automatica ignorasse la stanchezza, quel vincolo sparirebbe.
     */
    @Test
    fun `a parita di forza gioca chi e' piu' fresco`() {
        val base = balancedSquad()
        val stanchi = base.map { it.withStamina(20) }
        val unoFresco = stanchi.first { !it.primaryPosition.isGoalkeeper }.withStamina(100)
        val rosa = listOf(unoFresco) + stanchi.filterNot { it.id == unoFresco.id }

        val lineup = AutoLineup.build(rosa, Formation.F_4_3_3, MatchDay(1))
        assertNotNull(lineup)

        assertTrue(
            lineup.contains(unoFresco.id),
            "l'unico giocatore riposato è rimasto in panchina",
        )
    }

    @Test
    fun `sceglie il modulo che la rosa sa reggere`() {
        val formation = AutoLineup.bestFormation(balancedSquad(), MatchDay(1))
        val migliore = AutoLineup.build(balancedSquad(), formation, MatchDay(1))
        assertNotNull(migliore)

        val forzaScelta = migliore.slots.sumOf { it.player.overallAt(it.position) }
        Formation.entries.forEach { altro ->
            val alternativa = AutoLineup.build(balancedSquad(), altro, MatchDay(1)) ?: return@forEach
            val forza = alternativa.slots.sumOf { it.player.overallAt(it.position) }
            assertTrue(
                forzaScelta >= forza,
                "${formation.label} vale $forzaScelta ma ${altro.label} vale $forza",
            )
        }
    }

    @Test
    fun `la panchina non contiene nessuno dei titolari`() {
        val lineup = AutoLineup.build(balancedSquad(), Formation.F_4_3_3, MatchDay(1))
        assertNotNull(lineup)

        val titolari = lineup.slots.map { it.player.id }.toSet()
        lineup.bench.forEach {
            assertTrue(it.id !in titolari, "${it.shortName} è titolare e in panchina insieme")
        }
    }

    /** La prova che serve davvero: due squadre automatiche giocano una partita vera. */
    @Test
    fun `due formazioni automatiche riescono a giocare una partita`() {
        val casa = AutoLineup.setup(ClubId(1), "Casa", balancedSquad(), MatchDay(1))
        val fuori = AutoLineup.setup(
            ClubId(2), "Fuori",
            world.players.drop(300).take(22),
            MatchDay(1),
        )
        assertNotNull(casa)
        assertNotNull(fuori)

        val risultato = MatchEngine.simulate(casa, fuori, config, seed = 42L)

        assertTrue(risultato.homeGoals >= 0 && risultato.awayGoals >= 0)
        assertTrue(risultato.events.isNotEmpty(), "una partita senza un solo evento")
    }
}
