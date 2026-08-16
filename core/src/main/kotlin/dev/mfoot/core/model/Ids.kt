package dev.mfoot.core.model

/**
 * Identificatori tipizzati.
 *
 * Sono `value class`, quindi a runtime restano dei `Long` senza costo di allocazione,
 * ma il compilatore impedisce di passare un ClubId dove serve un PlayerId. In un sistema
 * dove aste, contratti e prestiti incrociano di continuo club e giocatori, e' una rete
 * di sicurezza che costa zero.
 */

@JvmInline
value class PlayerId(val value: Long) {
    override fun toString(): String = "P$value"
}

@JvmInline
value class ClubId(val value: Long) {
    override fun toString(): String = "C$value"
}

@JvmInline
value class LeagueId(val value: Long) {
    override fun toString(): String = "L$value"
}

@JvmInline
value class StaffId(val value: Long) {
    override fun toString(): String = "S$value"
}

@JvmInline
value class MatchId(val value: Long) {
    override fun toString(): String = "M$value"
}

@JvmInline
value class CompetitionId(val value: Long) {
    override fun toString(): String = "K$value"
}

/**
 * Numero di giornata di gioco.
 *
 * L'unita' di tempo del gioco e' la **giornata**, mai il giorno reale: contratti,
 * stipendi, crescita e scadenze si contano tutti qui. Quanto duri una giornata nel
 * mondo reale lo decide l'admin nel calendario, e cambiarlo non rompe nessun sistema.
 */
@JvmInline
value class MatchDay(val value: Int) : Comparable<MatchDay> {
    operator fun plus(days: Int) = MatchDay(value + days)
    operator fun minus(days: Int) = MatchDay(value - days)
    override fun compareTo(other: MatchDay): Int = value.compareTo(other.value)
    override fun toString(): String = "G$value"
}
