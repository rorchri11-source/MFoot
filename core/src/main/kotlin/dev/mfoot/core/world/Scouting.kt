package dev.mfoot.core.world

import dev.mfoot.core.config.RulesConfig

/**
 * Quanto sta via un osservatore.
 *
 * ## Perche' questo file e' nato il 2026-08-25
 *
 * Perche' la durata di una missione era scritta dentro una funzione SQL, cosi':
 *
 * ```sql
 * v_ore := 8 + (5 - greatest(1, least(5, v_staff.stars))) * 10;
 * ```
 *
 * Otto ore per un osservatore da cinque stelle, **quarantotto** per uno da una. Due
 * giorni reali per una singola ricerca, in un gioco che gioca due partite al giorno e in
 * cui un mercato intero dura una settimana. Chi comprava il primo osservatore che poteva
 * permettersi — cioe' quello scarso, cioe' tutti all'inizio — lo vedeva sparire per il
 * fine settimana.
 *
 * E non si poteva cambiare senza toccare il database, il che contraddice la regola del
 * progetto: nessun numero di gioco scritto nel codice, tutti in `LeagueConfig` e decisi
 * dall'admin.
 *
 * ## La decisione
 *
 * Presa dal proprietario il 2026-08-25: **due ore, e le fa il peggiore**. Le stelle
 * continuano a comprare tempo — mezz'ora contro due ore sono comunque quattro volte
 * tanto — ma su una scala che sta dentro una serata.
 *
 * ## Perche' le stelle devono comprare anche tempo, e non solo qualita'
 *
 * Perche' altrimenti un osservatore scarso sarebbe soltanto un osservatore bravo piu'
 * economico: stessa velocita', risultato peggiore. Facendogli costare anche l'attesa, la
 * differenza fra uno e cinque stelle diventa una decisione di ritmo oltre che di
 * precisione — e chi ne compra uno buono trova prima **e** trova meglio.
 */
object Scouting {

    /**
     * I minuti di una missione, per un osservatore di [stars] stelle.
     *
     * Interpolazione lineare fra il peggiore e il migliore. Lineare e non curva perche'
     * qui, a differenza del prezzo, l'estremo alto non deve essere sproporzionato: il
     * tempo e' un limite di gioco, non un bene da comprare.
     */
    fun missionMinutes(stars: Int, rules: RulesConfig): Int {
        val stelle = stars.coerceIn(1, 5)
        val peggiore = rules.scoutMinutesWorst.coerceAtLeast(1)
        val migliore = rules.scoutMinutesBest.coerceIn(1, peggiore)
        val passo = (peggiore - migliore) / 4.0
        return StrictMath.round(peggiore - passo * (stelle - 1)).toInt().coerceAtLeast(1)
    }
}
