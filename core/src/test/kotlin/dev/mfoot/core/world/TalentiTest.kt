package dev.mfoot.core.world

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.config.WorldConfig
import dev.mfoot.core.model.Position
import dev.mfoot.core.rng.DeterministicRandom
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Il giovane generato su misura, e quanti ne torna una missione.
 *
 * La prova che porta il peso e' l'ultima: **quante combinazioni nazione per ruolo restano
 * vuote alla generazione del mondo**. Era quarantuno su centodieci, ed e' la ragione per
 * cui un osservatore mandato in Brasile tornava a mani vuote.
 */
class TalentiTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))

    private fun rng(seed: Long = 7L) = DeterministicRandom(seed)

    // ------------------------------------------------------------------ il giovane

    @Test
    fun `nasce della nazione e del ruolo chiesti`() {
        config.world.nationalities.forEach { paese ->
            Position.entries.forEach { ruolo ->
                val p = Talenti.giovane(paese, ruolo, config, rng())
                assertEquals(paese, p.nationality, "$paese $ruolo")
                assertEquals(ruolo, p.primaryPosition, "$paese $ruolo")
            }
        }
    }

    @Test
    fun `e' sempre un under`() {
        repeat(300) { seed ->
            val p = Talenti.giovane("Brasile", Position.TS, config, rng(seed.toLong()))
            assertTrue(
                p.age <= Talenti.ETA_MASSIMA,
                "ha ${p.age} anni: un osservatore non trova gente da comprare all'asta",
            )
            assertTrue(p.age >= config.world.minAge, "ha ${p.age} anni, sotto il minimo del mondo")
        }
    }

    /**
     * Una lega che parte dai diciotto non deve ricevere un sedicenne dalla porta di
     * servizio: il minimo del mondo vale anche qui.
     */
    @Test
    fun `rispetta l'eta' minima della lega`() {
        val tardiva = config.copy(world = config.world.copy(minAge = 18))
        repeat(200) { seed ->
            val p = Talenti.giovane("Italia", Position.ATT, tardiva, rng(seed.toLong()))
            assertTrue(p.age >= 18, "ha ${p.age} anni in una lega che parte dai diciotto")
        }
    }

    @Test
    fun `non e' un giocatore privilegiato`() {
        val cento = (1L..300L).map { Talenti.giovane("Italia", Position.CC, config, rng(it)) }
        val mediaPotenziale = cento.map { it.potentialMax }.average()
        val mondo = WorldGenerator.generate(config).players.filter { it.age <= Talenti.ETA_MASSIMA }
        val mediaMondo = mondo.map { it.potentialMax }.average()

        assertTrue(
            StrictMath.abs(mediaPotenziale - mediaMondo) < 8.0,
            "i generati su misura hanno potenziale medio $mediaPotenziale contro " +
                "$mediaMondo del mondo: sarebbero un premio, non una scoperta",
        )
    }

    @Test
    fun `lo stesso seme da' lo stesso ragazzo`() {
        val a = Talenti.giovane("Argentina", Position.TRQ, config, rng(42))
        val b = Talenti.giovane("Argentina", Position.TRQ, config, rng(42))
        assertEquals(a.fullName, b.fullName)
        assertEquals(a.age, b.age)
        assertEquals(a.potentialMax, b.potentialMax)
    }

    @Test
    fun `semi diversi danno ragazzi diversi`() {
        val nomi = (1L..40L).map { Talenti.giovane("Francia", Position.DC, config, rng(it)).fullName }
        assertTrue(nomi.toSet().size > 20, "quaranta missioni producono solo ${nomi.toSet().size} nomi")
    }

    // ------------------------------------------------------------- quanti ne trova

    @Test
    fun `un ruolo solo torna sempre con uno`() {
        repeat(50) { seed ->
            assertEquals(1, Talenti.quantiNeTrova(1, 1, rng(seed.toLong())))
        }
    }

    /** *«Non sempre li torna tutti, ma minimo sempre uno»*. */
    @Test
    fun `non torna mai a vuoto, e non supera i ruoli chiesti`() {
        for (stelle in 1..5) {
            repeat(200) { seed ->
                val quanti = Talenti.quantiNeTrova(3, stelle, rng(seed.toLong()))
                assertTrue(quanti in 1..3, "con $stelle stelle ne ha trovati $quanti su 3")
            }
        }
    }

    @Test
    fun `le stelle comprano anche quantita'`() {
        fun media(stelle: Int) = (1L..500L)
            .map { Talenti.quantiNeTrova(3, stelle, rng(it)) }
            .average()

        val scarso = media(1)
        val bravo = media(5)
        assertTrue(
            bravo > scarso * 1.5,
            "un cinque stelle ne trova $bravo contro $scarso di un una stella: " +
                "pagarlo non cambia quanti ne porta",
        )
    }

    // ------------------------------------------------ il vivaio del mondo generato

    /**
     * **La prova per cui questo file esiste.**
     *
     * Con `ageMean = 25.4` scritto nel codice, quarantuno combinazioni nazione per ruolo
     * su centodieci erano vuote appena creato il mondo. Un osservatore mandato li' non
     * poteva riuscire, mai, e la mappa del mondo era una decorazione.
     */
    @Test
    fun `il mondo nasce con abbastanza giovani da cercare`() {
        val mondo = WorldGenerator.generate(config)
        val under = mondo.players.filter { it.age <= Talenti.ETA_MASSIMA }
        val combinazioni = config.world.nationalities.size * Position.entries.size
        val coperte = under.map { it.nationality to it.primaryPosition }.toSet().size

        assertTrue(
            under.size * 100 / mondo.players.size >= 12,
            "solo il ${under.size * 100 / mondo.players.size}% del mondo ha meno di venti " +
                "anni (${under.size} su ${mondo.players.size})",
        )
        assertTrue(
            coperte * 100 / combinazioni >= 75,
            "solo $coperte combinazioni su $combinazioni hanno almeno un giovane: " +
                "il ${100 - coperte * 100 / combinazioni}% delle ricerche nasce senza speranza",
        )
    }

    @Test
    fun `la distribuzione delle eta' la decide la configurazione`() {
        val vecchia = config.copy(
            world = WorldConfig(ageMean = 30.0, ageStdDev = 2.0),
        )
        val giovane = config.copy(
            world = WorldConfig(ageMean = 20.0, ageStdDev = 3.0),
        )
        val etaVecchia = WorldGenerator.generate(vecchia).players.map { it.age }.average()
        val etaGiovane = WorldGenerator.generate(giovane).players.map { it.age }.average()

        assertTrue(
            etaVecchia > etaGiovane + 5,
            "la media resta $etaVecchia contro $etaGiovane: la configurazione non conta",
        )
    }
}
