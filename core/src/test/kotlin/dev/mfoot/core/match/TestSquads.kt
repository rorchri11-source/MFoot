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
