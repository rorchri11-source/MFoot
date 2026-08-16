package dev.mfoot.core.match

import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position

/**
 * Moduli disponibili.
 *
 * Un modulo e' semplicemente la lista degli undici ruoli da coprire. La forza di ogni
 * zona del campo nasce da chi ci finisce dentro (vedi [ZoneRatings]), quindi il modulo
 * non ha bisogno di bonus propri: giocare a tre dietro indebolisce le fasce difensive
 * da solo, perche' ci sono meno uomini a coprirle.
 */
enum class Formation(val label: String, val positions: List<Position>) {
    F_4_3_3(
        "4-3-3",
        listOf(
            Position.POR,
            Position.TD, Position.DC, Position.DC, Position.TS,
            Position.MED, Position.CC, Position.CC,
            Position.AD, Position.ATT, Position.AS,
        ),
    ),

    F_4_4_2(
        "4-4-2",
        listOf(
            Position.POR,
            Position.TD, Position.DC, Position.DC, Position.TS,
            Position.AD, Position.CC, Position.CC, Position.AS,
            Position.ATT, Position.SP,
        ),
    ),

    F_4_2_3_1(
        "4-2-3-1",
        listOf(
            Position.POR,
            Position.TD, Position.DC, Position.DC, Position.TS,
            Position.MED, Position.MED,
            Position.AD, Position.TRQ, Position.AS,
            Position.ATT,
        ),
    ),

    F_3_5_2(
        "3-5-2",
        listOf(
            Position.POR,
            Position.DC, Position.DC, Position.DC,
            Position.AD, Position.MED, Position.CC, Position.CC, Position.AS,
            Position.ATT, Position.SP,
        ),
    ),

    F_5_3_2(
        "5-3-2",
        listOf(
            Position.POR,
            Position.TD, Position.DC, Position.DC, Position.DC, Position.TS,
            Position.MED, Position.CC, Position.CC,
            Position.ATT, Position.SP,
        ),
    ),

    F_4_4_1_1(
        "4-4-1-1",
        listOf(
            Position.POR,
            Position.TD, Position.DC, Position.DC, Position.TS,
            Position.AD, Position.CC, Position.CC, Position.AS,
            Position.TRQ, Position.ATT,
        ),
    );

    init {
        // Costante letterale e non `PLAYERS_ON_PITCH`: il companion object di un enum
        // non e' ancora inizializzato quando si costruiscono le sue entry.
        require(positions.size == 11) {
            "il modulo $label ha ${positions.size} ruoli invece di 11"
        }
    }

    companion object {
        const val PLAYERS_ON_PITCH = 11

        fun byLabel(label: String): Formation? = entries.firstOrNull { it.label == label }
    }
}

/** Un giocatore e il ruolo in cui e' stato schierato, che puo' non essere il suo. */
data class LineupSlot(val player: Player, val position: Position) {
    /** Quanto rende in questo ruolo, da 0 a 1. */
    val fitness: Double get() = player.positionalFitness(position)
}

/**
 * La formazione schierata: undici titolari con il ruolo assegnato, piu' la panchina.
 *
 * Il ruolo assegnato e' distinto dal ruolo naturale del giocatore di proposito: mettere
 * un centrocampista terzino deve essere possibile e costare qualcosa, non essere vietato.
 */
data class Lineup(
    val formation: Formation,
    val slots: List<LineupSlot>,
    val bench: List<Player> = emptyList(),
    val captainId: PlayerId? = null,
    val penaltyTakerId: PlayerId? = null,
) {

    init {
        // Il minimo e' 7: sotto quella soglia il regolamento sospende la partita, e
        // arrivarci significa comunque aver preso quattro rossi in novanta minuti.
        require(slots.size in MIN_PLAYERS_ON_PITCH..Formation.PLAYERS_ON_PITCH) {
            "una squadra in campo puo' avere da $MIN_PLAYERS_ON_PITCH a " +
                "${Formation.PLAYERS_ON_PITCH} giocatori, ne sono stati indicati ${slots.size}"
        }
        val ids = slots.map { it.player.id }
        require(ids.size == ids.toSet().size) { "lo stesso giocatore compare piu' volte in formazione" }
        require(slots.count { it.position.isGoalkeeper } <= 1) {
            "non si possono schierare due portieri"
        }
    }

    /** Null se il portiere e' stato espulso: in quel caso qualcuno va in porta come capita. */
    val goalkeeper: Player?
        get() = slots.firstOrNull { it.position.isGoalkeeper }?.player

    /** Chi difende la porta adesso: il portiere, o il primo disponibile se e' stato espulso. */
    val goalkeeperOrStandIn: Player?
        get() = goalkeeper ?: slots.lastOrNull()?.player

    val isFullStrength: Boolean get() = slots.size == Formation.PLAYERS_ON_PITCH

    val outfield: List<LineupSlot>
        get() = slots.filterNot { it.position.isGoalkeeper }

    val playerIds: Set<PlayerId> get() = slots.map { it.player.id }.toSet()

    fun playerById(id: PlayerId): Player? =
        slots.firstOrNull { it.player.id == id }?.player
            ?: bench.firstOrNull { it.id == id }

    fun slotOf(id: PlayerId): LineupSlot? = slots.firstOrNull { it.player.id == id }

    fun contains(id: PlayerId): Boolean = slots.any { it.player.id == id }

    /** Sostituisce [out] con [inPlayer], che deve venire dalla panchina. */
    fun substitute(out: PlayerId, inPlayer: PlayerId): Lineup? {
        val outSlot = slotOf(out) ?: return null
        val entering = bench.firstOrNull { it.id == inPlayer } ?: return null
        return copy(
            slots = slots.map { if (it.player.id == out) LineupSlot(entering, outSlot.position) else it },
            bench = bench.filterNot { it.id == inPlayer },
        )
    }

    /**
     * Rimuove un espulso: la squadra resta in inferiorita' numerica.
     * Restituisce null se scendere sotto il minimo regolamentare.
     */
    fun sendOff(playerId: PlayerId): Lineup? {
        if (slots.size <= MIN_PLAYERS_ON_PITCH) return null
        if (slots.none { it.player.id == playerId }) return null
        return copy(slots = slots.filterNot { it.player.id == playerId })
    }

    /** Aggiorna un giocatore in campo, per esempio dopo un consumo di stamina. */
    fun withUpdatedPlayer(updated: Player): Lineup =
        copy(slots = slots.map { if (it.player.id == updated.id) it.copy(player = updated) else it })

    companion object {
        const val MIN_PLAYERS_ON_PITCH = 7

        /**
         * Costruisce una formazione assegnando i ruoli del modulo nell'ordine dato.
         * Utile nei test e per l'AI, che schiera per ruolo naturale.
         */
        fun of(
            formation: Formation,
            players: List<Player>,
            bench: List<Player> = emptyList(),
        ): Lineup {
            require(players.size == Formation.PLAYERS_ON_PITCH) {
                "servono ${Formation.PLAYERS_ON_PITCH} giocatori, ne sono stati passati ${players.size}"
            }
            return Lineup(
                formation = formation,
                slots = players.zip(formation.positions) { player, position ->
                    LineupSlot(player, position)
                },
                bench = bench,
            )
        }
    }
}
