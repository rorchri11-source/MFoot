package dev.mfoot.core.match

import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position
import dev.mfoot.core.world.GeneratedWorld
import kotlin.math.abs

/**
 * Costruisce squadre di forza controllata a partire da un mondo generato.
 *
 * Serve ai test di bilanciamento: per misurare "una squadra con +10 di overall quanto
 * spesso vince?" bisogna poter chiedere esattamente una squadra da 70 e una da 80.
 */
object TestSquads {

    /**
     * Squadra con overall medio il piu' vicino possibile a [targetOverall].
     *
     * I giocatori vengono scelti per ruolo dal pool disponibile e non riutilizzati:
     * due squadre costruite dallo stesso mondo non condividono nessun giocatore.
     */
    fun build(
        world: GeneratedWorld,
        clubId: Int,
        name: String,
        targetOverall: Int,
        formation: Formation = Formation.F_4_3_3,
        coachStars: Int = 3,
        tactics: Tactics = Tactics.DEFAULT,
        exclude: Set<Player> = emptySet(),
        benchSize: Int = 7,
    ): TeamSetup {
        val used = exclude.toMutableSet()
        val starters = formation.positions.map { position ->
            pickClosest(world, position, targetOverall, used).also { used += it }
        }
        // Panchina con una copertura sensata dei reparti, leggermente sotto i titolari.
        val benchPositions = listOf(
            Position.POR, Position.DC, Position.TS, Position.CC,
            Position.MED, Position.AD, Position.ATT,
        )
        val bench = (0 until benchSize).map { index ->
            val position = benchPositions[index % benchPositions.size]
            pickClosest(world, position, targetOverall - 6, used).also { used += it }
        }

        return TeamSetup(
            clubId = ClubId(clubId.toLong()),
            name = name,
            lineup = Lineup.of(formation, starters, bench),
            tactics = tactics,
            coachStars = coachStars,
        )
    }

    /**
     * Le due squadre di una partita, costruite **insieme**.
     *
     * ## Perche' non bastava costruirle una dopo l'altra
     *
     * Perche' chi pesca per primo pesca meglio. `build` prende, per ogni ruolo, il
     * giocatore piu' vicino all'overall voluto; la seconda squadra riceve quello che
     * avanza, che e' sistematicamente un po' piu' lontano dal bersaglio.
     *
     * Con un mondo ricco di giocatori vicini al bersaglio la differenza spariva nel rumore.
     * Il 2026-08-30, abbassando l'eta' media del mondo per riempire il vivaio degli
     * osservatori, i giocatori vicini a 75 sono passati da 127 a 107 — e il vantaggio
     * nascosto e' emerso: **53,7% di vittorie in casa fra due squadre "pari"**, fuori dalla
     * banda sana. Non era il gioco che si era rotto, era il banco di prova che misurava
     * anche se stesso.
     *
     * Qui i due candidati piu' vicini a ogni ruolo si dividono uno per parte, alternando
     * chi prende il migliore: nessuna delle due squadre ha il vantaggio di aver scelto
     * prima.
     */
    fun coppia(
        world: GeneratedWorld,
        homeOverall: Int,
        awayOverall: Int,
        formation: Formation = Formation.F_4_3_3,
        homeTactics: Tactics = Tactics.DEFAULT,
        awayTactics: Tactics = Tactics.DEFAULT,
        homeCoachStars: Int = 3,
        awayCoachStars: Int = 3,
        benchSize: Int = 7,
    ): Pair<TeamSetup, TeamSetup> {
        val used = mutableSetOf<Player>()
        val casa = mutableListOf<Player>()
        val ospite = mutableListOf<Player>()

        fun coppiaPer(position: Position, targetCasa: Int, targetOspite: Int, alterna: Boolean) {
            // Chi prende per primo cambia a ogni ruolo: sull'undici il vantaggio si annulla.
            if (alterna) {
                casa += pickClosest(world, position, targetCasa, used).also { used += it }
                ospite += pickClosest(world, position, targetOspite, used).also { used += it }
            } else {
                ospite += pickClosest(world, position, targetOspite, used).also { used += it }
                casa += pickClosest(world, position, targetCasa, used).also { used += it }
            }
        }

        formation.positions.forEachIndexed { index, position ->
            coppiaPer(position, homeOverall, awayOverall, index % 2 == 0)
        }

        val panchina = listOf(
            Position.POR, Position.DC, Position.TS, Position.CC,
            Position.MED, Position.AD, Position.ATT,
        )
        val casaPanchina = mutableListOf<Player>()
        val ospitePanchina = mutableListOf<Player>()
        (0 until benchSize).forEach { index ->
            val position = panchina[index % panchina.size]
            if (index % 2 == 0) {
                casaPanchina += pickClosest(world, position, homeOverall - 6, used).also { used += it }
                ospitePanchina += pickClosest(world, position, awayOverall - 6, used).also { used += it }
            } else {
                ospitePanchina += pickClosest(world, position, awayOverall - 6, used).also { used += it }
                casaPanchina += pickClosest(world, position, homeOverall - 6, used).also { used += it }
            }
        }

        return TeamSetup(
            clubId = ClubId(1L),
            name = "Casa",
            lineup = Lineup.of(formation, casa, casaPanchina),
            tactics = homeTactics,
            coachStars = homeCoachStars,
        ) to TeamSetup(
            clubId = ClubId(2L),
            name = "Ospite",
            lineup = Lineup.of(formation, ospite, ospitePanchina),
            tactics = awayTactics,
            coachStars = awayCoachStars,
        )
    }

    /** Tutti i giocatori impegnati da questa squadra, titolari e panchina. */
    fun playersOf(setup: TeamSetup): Set<Player> =
        (setup.lineup.slots.map { it.player } + setup.lineup.bench).toSet()

    /** Overall medio degli undici titolari, nel ruolo in cui sono schierati. */
    fun lineupOverall(setup: TeamSetup): Double =
        setup.lineup.slots.map { it.player.overallAt(it.position).toDouble() }.average()

    private fun pickClosest(
        world: GeneratedWorld,
        position: Position,
        target: Int,
        used: Set<Player>,
    ): Player {
        val pool = world.players.filter { it.primaryPosition == position && it !in used }
        val fallback = world.players.filter { it !in used }
        val candidates = pool.ifEmpty { fallback }
        require(candidates.isNotEmpty()) { "mondo esaurito: nessun giocatore libero per $position" }
        return candidates.minBy { abs(it.overall - target) }
    }
}
