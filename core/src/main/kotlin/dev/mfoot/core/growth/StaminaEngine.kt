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
     * Recupero di un giocatore dopo [ore] di riposo.
     *
     * @param physioStars stelle del preparatore atletico del club, 1-5 (0 = nessuno)
     */
    fun recover(player: Player, physioStars: Int, engine: EngineConfig, ore: Double = 1.0): Player {
        val recovered = recoveryAmount(player, physioStars, engine, ore)
        return player.withStamina(player.stamina + recovered)
    }

    /** Comodo quando si ha lo staff invece delle sole stelle. */
    fun recover(player: Player, staff: List<Staff>, engine: EngineConfig, ore: Double = 1.0): Player =
        recover(player, physioStarsOf(staff), engine, ore)

    fun physioStarsOf(staff: List<Staff>): Int =
        staff.filter { it.role == StaffRole.PREPARATORE }.maxOfOrNull { it.stars } ?: 0

    /**
     * Quanti punti di stamina recupera in [ore] ore reali.
     *
     * Il tempo e' un **moltiplicatore lineare** e non una curva: mezz'ora rende meta' di
     * un'ora. Una curva a rendimenti calanti sarebbe piu' realistica e produrrebbe una cosa
     * che nessuno riesce a prevedere — quanto vale aspettare ancora un po' — mentre qui la
     * domanda che il gioco fa e' «gioco adesso o fra due ore?», e la risposta dev'essere
     * leggibile a mente.
     *
     * Il minimo di un punto vale per un'ora piena: chiedere zero ore rende zero, o un tick
     * ogni cinque minuti regalerebbe dodici punti l'ora a chiunque.
     */
    fun recoveryAmount(
        player: Player,
        physioStars: Int,
        engine: EngineConfig,
        ore: Double = 1.0,
    ): Int {
        if (ore <= 0.0) return 0

        val physio = physioMultiplier(physioStars)
        val age = ageMultiplier(player.age)
        val fitness = MathX.remap(
            player.attributes[dev.mfoot.core.model.Attr.FISICO].toDouble(),
            40.0, 95.0, 0.85, 1.20,
        )
        // Chi si stanca poco recupera anche in fretta: e' la stessa qualita' atletica.
        val traits = 1.0 / player.traits.staminaFactor().coerceAtLeast(0.5)

        val amount = engine.staminaRecoveryPerHour * ore * physio * age * fitness * traits
        return StrictMath.round(amount).toInt().coerceAtLeast(if (ore >= 1.0) 1 else 0)
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
     * Quante **ore** di riposo servono per tornare pienamente in forma.
     *
     * Serve all'AI e all'interfaccia per suggerire chi far riposare. Erano giornate, e una
     * giornata valeva un numero di ore diverso in ogni lega — dipendeva da quante fasce
     * orarie l'admin aveva messo in un giorno: «gli servono due giornate» non diceva quasi
     * niente. In ore la risposta e' un appuntamento.
     */
    fun hoursToFullRecovery(player: Player, physioStars: Int, engine: EngineConfig): Int {
        val missing = Player.MAX_STAMINA - player.stamina
        if (missing <= 0) return 0
        val perOra = recoveryAmount(player, physioStars, engine, ore = 1.0)
        return ((missing + perOra - 1) / perOra).coerceAtLeast(1)
    }
}
