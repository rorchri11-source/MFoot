package dev.mfoot.core.rng

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeterministicRandomTest {

    /**
     * Il test piu' importante di tutto il progetto.
     *
     * Il server simula la partita e salva la timeline; i client la riproducono. Se la
     * sequenza casuale non fosse riproducibile, due persone vedrebbero due partite
     * diverse e il gioco perderebbe ogni credibilita'.
     */
    @Test
    fun `stesso seed produce la stessa sequenza`() {
        val a = DeterministicRandom(20260816L)
        val b = DeterministicRandom(20260816L)

        val seqA = LongArray(1000) { a.nextLong() }
        val seqB = LongArray(1000) { b.nextLong() }

        assertContentEquals(seqA, seqB)
    }

    @Test
    fun `seed diversi producono sequenze diverse`() {
        val a = DeterministicRandom(1L)
        val b = DeterministicRandom(2L)

        val seqA = List(100) { a.nextLong() }
        val seqB = List(100) { b.nextLong() }

        assertTrue(seqA != seqB, "due seed diversi hanno prodotto la stessa sequenza")
    }

    @Test
    fun `seed zero non blocca il generatore`() {
        val r = DeterministicRandom(0L)
        val values = List(50) { r.nextLong() }.toSet()
        assertTrue(values.size > 40, "xorshift con stato 0 si sarebbe bloccato su un valore fisso")
    }

    @Test
    fun `nextDouble resta in zero-uno`() {
        val r = DeterministicRandom(7L)
        repeat(20_000) {
            val v = r.nextDouble()
            assertTrue(v >= 0.0 && v < 1.0, "nextDouble fuori intervallo: $v")
        }
    }

    @Test
    fun `nextInt resta nei limiti`() {
        val r = DeterministicRandom(11L)
        repeat(20_000) {
            val v = r.nextInt(10)
            assertTrue(v in 0..9, "nextInt(10) ha prodotto $v")
        }
    }

    @Test
    fun `nextIntInclusive copre entrambi gli estremi`() {
        val r = DeterministicRandom(13L)
        val seen = mutableSetOf<Int>()
        repeat(5_000) { seen += r.nextIntInclusive(3, 7) }
        assertEquals(setOf(3, 4, 5, 6, 7), seen)
    }

    @Test
    fun `la distribuzione e ragionevolmente uniforme`() {
        val r = DeterministicRandom(42L)
        val buckets = IntArray(10)
        val draws = 100_000
        repeat(draws) { buckets[r.nextInt(10)]++ }

        val expected = draws / 10.0
        buckets.forEachIndexed { i, count ->
            val deviation = abs(count - expected) / expected
            assertTrue(deviation < 0.05, "bucket $i sbilanciato del ${(deviation * 100).toInt()}%")
        }
    }

    @Test
    fun `chance gestisce gli estremi senza consumare entropia inutile`() {
        val r = DeterministicRandom(99L)
        assertFalse(r.chance(0.0))
        assertFalse(r.chance(-1.0))
        assertTrue(r.chance(1.0))
        assertTrue(r.chance(2.0))
    }

    @Test
    fun `chance rispetta la probabilita richiesta`() {
        val r = DeterministicRandom(5L)
        var hits = 0
        val draws = 100_000
        repeat(draws) { if (r.chance(0.3)) hits++ }

        val rate = hits.toDouble() / draws
        assertTrue(abs(rate - 0.3) < 0.01, "chance(0.3) ha dato $rate")
    }

    @Test
    fun `la gaussiana ha media zero e deviazione circa uno`() {
        val r = DeterministicRandom(2024L)
        val n = 200_000
        var sum = 0.0
        var sumSq = 0.0
        repeat(n) {
            val v = r.nextGaussian()
            sum += v
            sumSq += v * v
        }
        val mean = sum / n
        val variance = sumSq / n - mean * mean

        assertTrue(abs(mean) < 0.02, "media gaussiana fuori centro: $mean")
        assertTrue(abs(variance - 1.0) < 0.05, "varianza gaussiana anomala: $variance")
    }

    @Test
    fun `la gaussiana troncata rispetta i limiti`() {
        val r = DeterministicRandom(77L)
        repeat(10_000) {
            val v = r.nextGaussian(mean = 70.0, stdDev = 30.0, min = 1.0, max = 99.0)
            assertTrue(v in 1.0..99.0, "gaussiana troncata fuori limiti: $v")
        }
    }

    @Test
    fun `pickWeighted rispetta i pesi`() {
        val r = DeterministicRandom(31L)
        val items = listOf("raro", "comune")
        val counts = mutableMapOf("raro" to 0, "comune" to 0)

        repeat(50_000) {
            val pick = r.pickWeighted(items) { if (it == "raro") 1.0 else 9.0 }
            counts[pick] = counts.getValue(pick) + 1
        }

        val rareRate = counts.getValue("raro") / 50_000.0
        assertTrue(abs(rareRate - 0.1) < 0.01, "il peso non e' stato rispettato: $rareRate")
    }

    @Test
    fun `shuffled conserva tutti gli elementi`() {
        val r = DeterministicRandom(8L)
        val original = (1..50).toList()
        val shuffled = r.shuffled(original)

        assertEquals(original.size, shuffled.size)
        assertEquals(original.toSet(), shuffled.toSet())
        assertTrue(original != shuffled, "shuffled ha restituito la lista identica")
    }

    /**
     * I fork servono a isolare i flussi: se aggiungo una chiamata random nella
     * generazione del mondo, i risultati delle partite gia' giocate non devono spostarsi.
     */
    @Test
    fun `fork produce flussi indipendenti ma riproducibili`() {
        val parentA = DeterministicRandom(500L)
        val parentB = DeterministicRandom(500L)

        val worldA = parentA.fork(1).nextLong()
        val matchA = parentA.fork(2).nextLong()
        val worldB = parentB.fork(1).nextLong()
        val matchB = parentB.fork(2).nextLong()

        assertEquals(worldA, worldB, "lo stesso fork deve essere riproducibile")
        assertEquals(matchA, matchB, "lo stesso fork deve essere riproducibile")
        assertTrue(worldA != matchA, "fork diversi hanno prodotto lo stesso flusso")
    }
}
