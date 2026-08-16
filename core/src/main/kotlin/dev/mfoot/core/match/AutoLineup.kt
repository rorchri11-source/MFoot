package dev.mfoot.core.match

import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position

/**
 * Sceglie l'undici da solo.
 *
 * ## Perche' serve sempre, non solo per l'AI
 *
 * I club dell'AI non compileranno mai una formazione a mano. Ma nemmeno gli umani lo
 * faranno *ogni volta*: con due partite al giorno, prima o poi qualcuno si dimentica. Se
 * la partita non si giocasse senza formazione, il campionato si fermerebbe perche' una
 * persona e' andata a cena.
 *
 * Quindi la formazione automatica non e' il ripiego per l'AI: e' la rete che tiene in
 * piedi il calendario. Chi la imposta a mano fa meglio, chi non la imposta gioca lo
 * stesso.
 *
 * ## Cosa ottimizza
 *
 * Non l'overall puro: l'overall **nel ruolo che deve coprire**, corretto per quanto e'
 * fresco. Un 80 a terra dopo due partite in un giorno rende meno di un 74 riposato, ed e'
 * esattamente il motivo per cui il gioco chiede una rosa profonda invece di undici
 * fenomeni.
 */
object AutoLineup {

    /**
     * Sotto questa stamina un giocatore viene schierato solo se non c'e' nessun altro.
     *
     * Non e' un divieto ma una penalita' forte: con una rosa decimata da infortuni e
     * squalifiche bisogna comunque poter scendere in campo.
     */
    private const val TIRED_THRESHOLD = 45
    private const val TIRED_PENALTY = 0.75

    /**
     * Il modulo che meglio si adatta a chi si ha davvero.
     *
     * Imporre il 4-3-3 a una rosa senza ali produce due terzini schierati larghi in
     * attacco e una squadra che sembra rotta senza che il proprietario capisca perche'.
     */
    fun bestFormation(squad: List<Player>, today: MatchDay = MatchDay(0)): Formation =
        Formation.entries.maxByOrNull { formation ->
            build(squad, formation, today)?.let(::totalStrength) ?: -1.0
        } ?: Formation.F_4_3_3

    /**
     * Costruisce la formazione, o null se non ci sono abbastanza giocatori disponibili.
     *
     * Restituire null invece di lanciare e' voluto: una rosa incompleta e' una situazione
     * di gioco normale, non un errore di programmazione, e chi chiama deve poter decidere
     * cosa fare.
     */
    fun build(
        squad: List<Player>,
        formation: Formation,
        today: MatchDay,
        benchSize: Int = 7,
    ): Lineup? {
        val available = squad.filterNot { it.isInjured(today) }
        if (available.size < Formation.PLAYERS_ON_PITCH) return null

        // I ruoli piu' difficili da coprire vengono assegnati per primi. Lasciare il
        // portiere per ultimo significherebbe mettere fra i pali chiunque sia avanzato,
        // e un giocatore di movimento in porta vale quaranta punti di malus.
        val assignmentOrder = formation.positions.indices
            .sortedBy { index -> candidatesFor(formation.positions[index], available) }

        val chosen = arrayOfNulls<LineupSlot>(formation.positions.size)
        val taken = HashSet<Long>(Formation.PLAYERS_ON_PITCH)

        for (index in assignmentOrder) {
            val position = formation.positions[index]
            val best = available
                .filterNot { it.id.value in taken }
                .maxByOrNull { score(it, position) }
                ?: return null

            taken += best.id.value
            chosen[index] = LineupSlot(best, position)
        }

        val slots = chosen.filterNotNull()
        if (slots.size < Formation.PLAYERS_ON_PITCH) return null

        val bench = available
            .filterNot { it.id.value in taken }
            .sortedByDescending { it.overall * (0.75 + 0.25 * freshness(it)) }
            .take(benchSize)

        return Lineup(
            formation = formation,
            slots = slots,
            bench = bench,
            // Il piu' forte fra i piu' esperti: la fascia non cambia niente nel motore,
            // ma vederla addosso a un diciottenne appena arrivato stona.
            captainId = slots.maxByOrNull { it.player.overall + it.player.age }?.player?.id,
            penaltyTakerId = slots.maxByOrNull { it.player.attributes[Attr.TIRO] }?.player?.id,
        )
    }

    /** La squadra pronta a giocare. Null se non c'e' modo di scendere in campo. */
    fun setup(
        clubId: ClubId,
        name: String,
        squad: List<Player>,
        today: MatchDay,
        coachStars: Int = 3,
        tactics: Tactics = Tactics.DEFAULT,
    ): TeamSetup? {
        val formation = bestFormation(squad, today)
        val lineup = build(squad, formation, today) ?: return null
        return TeamSetup(
            clubId = clubId,
            name = name,
            lineup = lineup,
            tactics = tactics,
            coachStars = coachStars,
        )
    }

    /**
     * Quanto vale questo giocatore in questo ruolo, adesso.
     *
     * La stanchezza pesa gia' nella scelta e non solo in campo: scegliendo per overall e
     * guardando la stamina dopo, si finirebbe per schierare sempre gli stessi undici fino
     * a bruciarli.
     */
    private fun score(player: Player, position: Position): Double {
        val base = player.overallAt(position).toDouble()
        val tired = if (player.stamina < TIRED_THRESHOLD) TIRED_PENALTY else 1.0
        return base * (0.75 + 0.25 * freshness(player)) * tired
    }

    private fun freshness(player: Player): Double = player.stamina / 100.0

    /**
     * Quanti giocatori sanno davvero fare quel ruolo.
     *
     * Meno sono, prima va assegnato: il portiere ha di solito due candidati veri, l'ala
     * destra sei, e chi sceglie per ultimo si prende quello che resta.
     */
    private fun candidatesFor(position: Position, squad: List<Player>): Int =
        squad.count { it.canPlay(position) }

    private fun totalStrength(lineup: Lineup): Double =
        lineup.slots.sumOf { it.player.overallAt(it.position).toDouble() }
}
