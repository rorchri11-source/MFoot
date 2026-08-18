package dev.mfoot.tick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotifierTest {

    @Test
    fun `senza token e chat il consegnatore resta spento`() {
        val spento = Notifier(TickEnvironment(dbUrl = "jdbc:x"))
        assertFalse(spento.enabled)
        assertFalse(spento.send("qualcosa"), "ha detto di aver mandato un messaggio senza un bot")
    }

    @Test
    fun `serve tutta e due, non una sola`() {
        val mezzo = Notifier(TickEnvironment(dbUrl = "jdbc:x", telegramToken = "abc"))
        assertFalse(mezzo.enabled, "un token senza chat non basta a mandare niente")
    }

    // ------------------------------------------------------------------------- il JSON

    @Test
    fun `le virgolette nel nome di una squadra non rompono il messaggio`() {
        val corpo = JsonBody.obj("text" to """Il "Vecchio" Borgo ha vinto""")

        assertEquals("""{"text":"Il \"Vecchio\" Borgo ha vinto"}""", corpo)
    }

    @Test
    fun `gli a capo sopravvivono`() {
        assertEquals("""{"text":"prima\nseconda"}""", JsonBody.obj("text" to "prima\nseconda"))
    }

    @Test
    fun `i booleani e i numeri non vanno fra virgolette`() {
        val corpo = JsonBody.obj("disable" to true, "n" to 3, "s" to "x")

        assertEquals("""{"disable":true,"n":3,"s":"x"}""", corpo)
    }

    @Test
    fun `un carattere di controllo non produce JSON illegale`() {
        val corpo = JsonBody.obj("text" to "ab")

        assertTrue(corpo.contains("\\u0001"), "carattere di controllo non protetto: $corpo")
    }
}
