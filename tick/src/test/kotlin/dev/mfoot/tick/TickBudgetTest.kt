package dev.mfoot.tick

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TickBudgetTest {

    private val avvio = Instant.parse("2026-08-25T18:00:00Z")

    private fun budget(
        totaleSecondi: Long = 900,
        riservaSecondi: Long = 45,
        adesso: Instant,
    ) = TickBudget(
        startedAt = avvio,
        totale = Duration.ofSeconds(totaleSecondi),
        riserva = Duration.ofSeconds(riservaSecondi),
        orologio = { adesso },
    )

    @Test
    fun `appena partito il tempo utile e quasi tutto il budget`() {
        val b = budget(adesso = avvio)
        assertEquals(855, b.rimanente().toSeconds())
        assertTrue(b.consentito(Duration.ofMinutes(5)))
        assertFalse(b.scaduto)
    }

    @Test
    fun `dentro la riserva non si comincia piu niente`() {
        // Quattordici minuti e mezzo su quindici: restano trenta secondi, meno della
        // riserva di quarantacinque.
        val b = budget(adesso = avvio.plusSeconds(870))
        assertTrue(b.scaduto)
        assertFalse(b.consentito())
    }

    @Test
    fun `un lavoro lungo viene rifiutato prima di cominciare, non a meta`() {
        // Restano cinque minuti utili. Una fase che ne costa sei non deve partire: e'
        // esattamente il caso in cui il runner staccava la spina a transazione aperta.
        val b = budget(adesso = avvio.plusSeconds(600))
        assertEquals(255, b.rimanente().toSeconds())
        assertFalse(b.consentito(Duration.ofMinutes(6)))
        assertTrue(b.consentito(Duration.ofMinutes(4)))
    }

    @Test
    fun `il tempo utile non scende mai sotto zero`() {
        val b = budget(adesso = avvio.plusSeconds(5_000))
        assertEquals(0, b.rimanente().toSeconds())
    }

    /*
     * `fromEnv` legge l'orologio vero, quindi questi tre partono da adesso.
     *
     * Con un istante fissato nel passato — com'era scritto la prima volta — il budget
     * risultava gia' esaurito prima ancora di cominciare, e i test fallivano descrivendo
     * il proprio errore invece di quello del codice.
     */
    @Test
    fun `senza variabile d ambiente vale il valore predefinito`() {
        val b = TickBudget.fromEnv(Instant.now()) { null }
        assertTrue(b.rimanente().toSeconds() in 850..855, "rimanente: ${b.rimanente()}")
    }

    @Test
    fun `la variabile d ambiente decide il budget`() {
        val b = TickBudget.fromEnv(Instant.now()) {
            if (it == "MFOOT_BUDGET_SECONDS") "300" else null
        }
        assertTrue(b.rimanente().toSeconds() in 250..255, "rimanente: ${b.rimanente()}")
    }

    @Test
    fun `un valore assurdo nell ambiente non azzera il budget`() {
        val b = TickBudget.fromEnv(Instant.now()) { "zero virgola" }
        assertTrue(b.rimanente().toSeconds() > 800)
    }

    @Test
    fun `il cronometro somma le ripetizioni della stessa fase`() {
        val c = Cronometro()
        repeat(3) { c.fase("partite") { Thread.sleep(5) } }
        c.fase("mercato") { }

        val riepilogo = c.riepilogo(sogliaMillis = 1)
        assertTrue(riepilogo.startsWith("partite"), riepilogo)
        assertTrue(c.totaleMillis() >= 15, "totale: ${c.totaleMillis()}ms")
    }

    @Test
    fun `il cronometro restituisce il valore della fase`() {
        val c = Cronometro()
        assertEquals(7, c.fase("conto") { 7 })
    }

    @Test
    fun `una fase che fallisce viene comunque misurata`() {
        val c = Cronometro()
        runCatching { c.fase("rotta") { error("boom") } }
        assertTrue(c.riepilogo(sogliaMillis = 0).contains("rotta"))
    }
}
