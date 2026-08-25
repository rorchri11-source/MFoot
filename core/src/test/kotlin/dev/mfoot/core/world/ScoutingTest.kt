package dev.mfoot.core.world

import dev.mfoot.core.config.RulesConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quanto sta via un osservatore.
 *
 * ## Cosa difende questo test
 *
 * La decisione del proprietario del 2026-08-25: **due ore al massimo, e le fa il
 * peggiore**. Prima erano quarantotto — scritte in SQL, dove nessuno poteva vederle — e
 * un osservatore da una stella spariva per due giorni reali.
 */
class ScoutingTest {

    private val rules = RulesConfig()

    @Test
    fun `il peggiore non supera le due ore`() {
        assertEquals(120, Scouting.missionMinutes(1, rules))
        assertTrue(Scouting.missionMinutes(1, rules) <= 120, "il tetto deciso e' due ore")
    }

    @Test
    fun `il migliore ci mette mezz ora`() {
        assertEquals(30, Scouting.missionMinutes(5, rules))
    }

    @Test
    fun `piu stelle, meno attesa, senza salti`() {
        val minuti = (1..5).map { Scouting.missionMinutes(it, rules) }
        assertEquals(minuti.sortedDescending(), minuti, "l'attesa deve calare con le stelle: $minuti")
        assertEquals(listOf(120, 98, 75, 53, 30), minuti)
    }

    /**
     * Le stelle devono comprare **anche** tempo.
     *
     * Se il migliore e il peggiore stessero via uguale, un osservatore scarso sarebbe
     * soltanto uno bravo piu' economico: stessa velocita', risultato peggiore.
     */
    @Test
    fun `fra il migliore e il peggiore ci sono almeno tre volte`() {
        val rapporto = Scouting.missionMinutes(1, rules).toDouble() / Scouting.missionMinutes(5, rules)
        assertTrue(rapporto >= 3.0, "rapporto $rapporto")
    }

    @Test
    fun `i due numeri li decide la configurazione, non il codice`() {
        val lenta = rules.copy(scoutMinutesWorst = 600, scoutMinutesBest = 120)
        assertEquals(600, Scouting.missionMinutes(1, lenta))
        assertEquals(120, Scouting.missionMinutes(5, lenta))
    }

    @Test
    fun `stelle fuori scala non fanno esplodere niente`() {
        assertEquals(Scouting.missionMinutes(1, rules), Scouting.missionMinutes(0, rules))
        assertEquals(Scouting.missionMinutes(5, rules), Scouting.missionMinutes(12, rules))
    }

    @Test
    fun `una configurazione incoerente non produce mai zero o un numero negativo`() {
        // Migliore piu' lento del peggiore: l'admin ha scritto una sciocchezza, e il
        // gioco deve continuare a girare invece di programmare una missione istantanea.
        val storta = rules.copy(scoutMinutesWorst = 60, scoutMinutesBest = 300)
        (1..5).forEach { assertTrue(Scouting.missionMinutes(it, storta) >= 1) }
    }
}
