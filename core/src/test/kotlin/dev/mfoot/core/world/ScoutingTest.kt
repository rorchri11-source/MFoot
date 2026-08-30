package dev.mfoot.core.world

import dev.mfoot.core.config.RulesConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quanto sta via un osservatore.
 *
 * ## Cosa difende questo test, e come e' cambiato due volte
 *
 * All'inizio erano **quarantotto ore** per un una stella, scritte in SQL dove nessuno
 * poteva vederle: due giorni reali per una singola ricerca.
 *
 * Il 2026-08-25 il proprietario ha deciso **due ore al massimo**, e i numeri sono usciti
 * dall'SQL per entrare nella configurazione della lega.
 *
 * Il 2026-08-30 ha deciso **quindici minuti**: *«diminuisci tempo massimo da 40 a 15»*.
 * Insieme al fatto che una missione adesso torna sempre con qualcuno e che si possono
 * chiedere piu' ruoli in un viaggio, l'osservatore smette di essere una scommessa da
 * mezza serata e diventa una cosa che si fa mentre si gioca.
 *
 * Le prove sotto cambiano perche' e' cambiata la **regola**, non perche' era comodo:
 * quelle che dicono cosa il conto non deve mai fare — niente salti, niente zero, le
 * stelle che comprano tempo — sono rimaste identiche da allora.
 */
class ScoutingTest {

    private val rules = RulesConfig()

    @Test
    fun `il peggiore non supera il quarto d'ora`() {
        assertEquals(15, Scouting.missionMinutes(1, rules))
        assertTrue(
            Scouting.missionMinutes(1, rules) <= 15,
            "il tetto deciso il 2026-08-30 e' un quarto d'ora",
        )
    }

    @Test
    fun `il migliore ci mette cinque minuti`() {
        assertEquals(5, Scouting.missionMinutes(5, rules))
    }

    @Test
    fun `piu stelle, meno attesa, senza salti`() {
        val minuti = (1..5).map { Scouting.missionMinutes(it, rules) }
        assertEquals(minuti.sortedDescending(), minuti, "l'attesa deve calare con le stelle: $minuti")
        assertEquals(listOf(15, 13, 10, 8, 5), minuti)
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
