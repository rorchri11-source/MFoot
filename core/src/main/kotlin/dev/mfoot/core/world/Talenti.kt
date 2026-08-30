package dev.mfoot.core.world

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.rng.DeterministicRandom

/**
 * Il giovane che l'osservatore trova quando non c'era piu' nessuno da trovare.
 *
 * ## Il difetto che chiude
 *
 * La ricerca di un osservatore e' un `and` di tre filtri esatti — under 20, quella nazione,
 * quel ruolo, e libero — e il mondo non ne ha abbastanza per reggerlo. Misurato sul preset
 * sprint: 1128 giocatori, **98 under 20**, su 110 combinazioni nazione per ruolo.
 * Quarantuno combinazioni sono **vuote al primo giorno**, altre quarantasei ne hanno
 * esattamente uno. Segnalato dal proprietario cosi': *«quando mandi un osservatore in una
 * specifica nazione a cercare determinati ruoli non ti porta niente»*.
 *
 * Deciso il 2026-08-30: quando non c'e', **si genera su misura**. Scartato l'allargare la
 * ricerca a paesi o ruoli vicini.
 *
 * ## Perche' non e' un giocatore privilegiato
 *
 * Perche' nasce dalla stessa curva di tutti gli altri: potenziale dalle fasce della lega,
 * eta' dalla fascia under, overall di adesso come conseguenza dei due. E' un giocatore che
 * sarebbe potuto esistere e che il mondo non aveva estratto — non un premio per chi ha
 * cercato dove non c'era niente.
 */
object Talenti {

    /** Sopra questa eta' non e' piu' un giovane da scoprire, ma uno da comprare all'asta. */
    const val ETA_MASSIMA = 19

    /**
     * Un under 20 di quella nazione e di quel ruolo, creato adesso.
     *
     * L'identificativo lo assegna chi scrive nel database: qui vale zero, perche' un id
     * inventato in `core` sarebbe un id che finge di essere vero.
     */
    fun giovane(
        nationality: String,
        position: Position,
        config: LeagueConfig,
        rng: DeterministicRandom,
    ): Player {
        val potenziale = WorldGenerator.buildPotentialPool(config.world.tiers, rng).let { pool ->
            if (pool.isEmpty()) 70 else pool[rng.nextInt(pool.size)]
        }

        // L'eta' sta nella fascia under, mai sotto il minimo del mondo: una lega che parte
        // dai diciotto non deve ricevere un sedicenne dalla porta di servizio.
        val minima = config.world.minAge.coerceAtMost(ETA_MASSIMA)
        val eta = rng.nextIntInclusive(minima, ETA_MASSIMA)

        return WorldGenerator.buildPlayer(
            id = PlayerId(0),
            potential = potenziale,
            position = position,
            config = config,
            rng = rng,
            usedNames = mutableSetOf(),
            forcedAge = eta,
            forcedNationality = nationality,
        )
    }

    /**
     * Quanti giovani deve trovare una missione che ha chiesto [ruoli] ruoli.
     *
     * *«Non sempre li torna tutti, ma minimo sempre uno»*: le stelle comprano anche
     * quantita', non solo qualita'. A cinque stelle li trova quasi tutti, a una quasi mai
     * piu' di uno — ed e' un'altra ragione per pagare un osservatore bravo, oltre alla
     * precisione e al tempo.
     */
    fun quantiNeTrova(ruoli: Int, stelle: Int, rng: DeterministicRandom): Int {
        if (ruoli <= 1) return 1
        val quota = when (stelle.coerceIn(1, 5)) {
            1 -> 0.20
            2 -> 0.35
            3 -> 0.50
            4 -> 0.68
            else -> 0.85
        }
        // Il primo e' garantito, gli altri si tirano uno per uno.
        var quanti = 1
        repeat(ruoli - 1) { if (rng.chance(quota)) quanti++ }
        return quanti
    }
}
