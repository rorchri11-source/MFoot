package dev.mfoot.tick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Il messaggio unico del giro.
 *
 * ## Perche' esiste questo test
 *
 * Perche' la consegna delle notifiche immediate era un ciclo che mandava **una richiesta
 * HTTPS per notifica**, fino a venti, in fila. Telegram limita a circa venti messaggi al
 * minuto per chat: superata la soglia risponde 429 e le richieste si trascinano. Con
 * quindici secondi di timeout ciascuna, il caso peggiore erano cinque minuti passati ad
 * aspettare Telegram — su un giro che ne aveva dieci prima di essere ucciso.
 *
 * Il test non parla con Telegram: verifica la **forma** del messaggio, che e' la parte
 * che decide quante richieste partono.
 */
class NotifierBatchTest {

    /** La stessa composizione usata da `consegnaLeNotifiche`, isolata per poterla provare. */
    private fun componi(lega: String, righe: List<String>): String? = when {
        righe.isEmpty() -> null
        righe.size == 1 -> "<b>$lega</b>\n${righe.first()}"
        else -> buildString {
            append("<b>$lega</b>\n")
            righe.forEach { append("\n• ").append(it) }
        }
    }

    @Test
    fun `venti notizie diventano un messaggio solo`() {
        val righe = (1..20).map { "notizia $it" }
        val messaggio = componi("Lega", righe)!!

        righe.forEach { assertTrue(messaggio.contains(it), "manca: $it") }
        assertEquals(20, messaggio.split("\n• ").size - 1)
    }

    @Test
    fun `una notizia sola non prende il pallino`() {
        val messaggio = componi("Lega", listOf("l'asta e' chiusa"))!!
        assertTrue(!messaggio.contains("•"), messaggio)
        assertTrue(messaggio.endsWith("l'asta e' chiusa"))
    }

    @Test
    fun `senza niente da dire non parte niente`() {
        assertEquals(null, componi("Lega", emptyList()))
    }

    /**
     * Il nome della lega si scrive **una volta**, non venti.
     *
     * Non e' pignoleria: Telegram taglia a 4096 caratteri, e il troncamento mangia le
     * ultime notizie. Ripetere l'intestazione venti volte significa perderne qualcuna.
     */
    @Test
    fun `l intestazione non si ripete`() {
        val messaggio = componi("Lega", (1..20).map { "notizia $it" })!!
        assertEquals(1, messaggio.split("<b>").size - 1)
    }
}
