package dev.mfoot.core.growth

import dev.mfoot.core.config.EngineConfig
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Staff
import dev.mfoot.core.model.StaffRole
import dev.mfoot.core.model.staminaFactor
import dev.mfoot.core.rng.MathX

/**
 * Recupero della stamina fra una giornata e l'altra.
 *
 * ## Il sistema che tiene in piedi tutto il resto
 *
 * Con due partite al giorno **non si possono schierare gli stessi undici due volte**:
 * si bruciano. Da qui discende tutto il resto del gioco — serve una rosa profonda,
 * serve la Primavera per turnare, serve il preparatore atletico. Il "minimo 16
 * giocatori" smette di essere una regola imposta e diventa una necessita' che si sente.
 *
 * Il preparatore a cinque stelle recupera circa il doppio di uno a una stella: e' il
 * motivo per cui vale la pena spenderci crediti all'asta.
 */
object StaminaEngine {

    /** I giovani recuperano piu' in fretta: sotto questa eta' scatta il bonus. */
    private const val YOUNG_AGE = 23

    /** Dopo questa eta' il recupero peggiora sensibilmente. */
    private const val VETERAN_AGE = 31

    /**
     * Recupero di un giocatore alla fine di una giornata.
     *
     * @param physioStars stelle del preparatore atletico del club, 1-5 (0 = nessuno)
     */
    fun recover(player: Player, physioStars: Int, engine: EngineConfig): Player {
        val recovered = recoveryAmount(player, physioStars, engine)
        return player.withStamina(player.stamina + recovered)
    }

    /** Comodo quando si ha lo staff invece delle sole stelle. */
    fun recover(player: Player, staff: List<Staff>, engine: EngineConfig): Player =
        recover(player, physioStarsOf(staff), engine)

    fun physioStarsOf(staff: List<Staff>): Int =
        staff.filter { it.role == StaffRole.PREPARATORE }.maxOfOrNull { it.stars } ?: 0

    /** Quanti punti di stamina recupera in una giornata. */
    fun recoveryAmount(player: Player, physioStars: Int, engine: EngineConfig): Int {
        val physio = physioMultiplier(physioStars)
        val age = ageMultiplier(player.age)
        val fitness = MathX.remap(
            player.attributes[dev.mfoot.core.model.Attr.FISICO].toDouble(),
            40.0, 95.0, 0.85, 1.20,
        )
        // Chi si stanca poco recupera anche in fretta: e' la stessa qualita' atletica.
        val traits = 1.0 / player.traits.staminaFactor().coerceAtLeast(0.5)

        val amount = engine.staminaRecoveryPerMatchDay * physio * age * fitness * traits
        return StrictMath.round(amount).toInt().coerceAtLeast(1)
    }

    /** Senza preparatore si recupera comunque, ma male. */
    private fun physioMultiplier(stars: Int): Double = when (stars) {
        0 -> 0.60
        else -> PHYSIO_BY_STARS[stars.coerceIn(1, 5) - 1]
    }

    private val PHYSIO_BY_STARS = doubleArrayOf(0.70, 0.85, 1.00, 1.25, 1.55)

    private fun ageMultiplier(age: Int): Double = when {
        age < YOUNG_AGE -> 1.18
        age <= VETERAN_AGE -> 1.00
        else -> MathX.remap(age.toDouble(), VETERAN_AGE.toDouble(), 38.0, 1.00, 0.68)
    }

    /**
     * E' abbastanza fresco per giocare senza penalita'?
     * Sotto la soglia di comfort i rating cominciano a calare (vedi ZoneRatings).
     */
    fun isFresh(player: Player, engine: EngineConfig): Boolean =
        player.stamina >= engine.staminaComfortThreshold

    /**
     * Quanti giorni di riposo servono per tornare pienamente in forma.
     * Serve all'AI e all'interfaccia per suggerire chi far riposare.
     */
    fun matchDaysToFullRecovery(player: Player, physioStars: Int, engine: EngineConfig): Int {
        val missing = Player.MAX_STAMINA - player.stamina
        if (missing <= 0) return 0
        val perDay = recoveryAmount(player, physioStars, engine)
        return ((missing + perDay - 1) / perDay).coerceAtLeast(1)
    }
}
