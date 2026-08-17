package dev.mfoot.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La tabella di formattazione del denaro, fissata.
 *
 * Non è pignoleria tipografica: il prezzo che l'utente legge in un'asta deve coincidere
 * con quello che gli viene addebitato. Se `format()` mostrasse `1,5M` per una cifra che
 * vale 1500 crediti e l'addebito ne prendesse 1,5, la lega finirebbe a litigare — ed è il
 * difetto peggiore possibile in un gioco di soldi.
 */
class MoneyTest {

    @Test
    fun `la tabella di formattazione della spec`() {
        assertEquals("0", Money(0).format())
        assertEquals("450K", Money(450).format())
        assertEquals("1M", Money(1000).format())
        assertEquals("1,5M", Money(1500).format())
        assertEquals("18,5M", Money(18500).format())
        assertEquals("120M", Money(120000).format())
        assertEquals("1,25Mrd", Money(1250000).format())
    }

    @Test
    fun `il separatore decimale è la virgola`() {
        assertTrue(Money(1500).format().contains(','), "il gioco è in italiano")
        assertTrue(!Money(1500).format().contains('.'))
    }

    @Test
    fun `le cifre negative conservano il segno`() {
        assertEquals("-450K", Money(-450).format())
        assertEquals("-1,5M", Money(-1500).format())
    }

    @Test
    fun `i decimali si fermano dove la spec dice`() {
        // Su un milione si mostra il decimo, su un miliardo il centesimo: sono gli ordini
        // di grandezza in cui si ragiona a quelle cifre.
        assertEquals("1,2M", Money(1234).format())
        assertEquals("2,35Mrd", Money(2345678).format())
    }

    @Test
    fun `la forma compatta tiene il decimale solo su una cifra sola`() {
        assertEquals("1,5M", Money(1500).formatShort())
        assertEquals("700K", Money(700).formatShort())
        assertEquals("19M", Money(18500).formatShort())
        assertEquals("120M", Money(120000).formatShort())
    }

    /** L'arrotondamento non deve produrre `1000M`: quello si scrive `1Mrd`. */
    @Test
    fun `arrotondando verso l'alto scatta l'unità successiva`() {
        assertEquals("1Mrd", Money(999_999).format())
    }

    // ---------------------------------------------------------------------- lettura

    @Test
    fun `parse accetta tutte le forme che l'utente digita`() {
        assertEquals(Money(1500), Money.parse("1,5M"))
        assertEquals(Money(1500), Money.parse("1.5M"))
        assertEquals(Money(1500), Money.parse("1500"))
        assertEquals(Money(700), Money.parse("700K"))
        assertEquals(Money(700), Money.parse("700k"))
        assertEquals(Money(1_250_000), Money.parse("1,25Mrd"))
        assertEquals(Money(120_000), Money.parse(" 120 M "))
    }

    @Test
    fun `parse rifiuta la spazzatura`() {
        listOf("", "   ", "abc", "12x", "1,5,5", "M", "K", "-", "1e5", "1..5", "12,5,M")
            .forEach { assertNull(Money.parse(it), "'$it' non è un numero e non deve passare") }
    }

    @Test
    fun `una cifra senza unità è in migliaia`() {
        // 1500 vale 1,5M: è l'unità di tutto il sistema, e chi digita 1500 pensando ai
        // crediti del fantacalcio ottiene esattamente quello che si aspetta.
        assertEquals("1,5M", Money.parse("1500")!!.format())
    }

    @Test
    fun `andata e ritorno fra format e parse`() {
        listOf(0, 450, 1000, 1500, 18500, 120000, 1250000).forEach { value ->
            val testo = Money(value).format()
            assertEquals(Money(value), Money.parse(testo), "'$testo' non torna indietro uguale")
        }
    }

    // ---------------------------------------------------------------------- aritmetica

    @Test
    fun `somma sottrazione e prodotto restano in migliaia`() {
        assertEquals(Money(1700), Money(1500) + Money(200))
        assertEquals(Money(1300), Money(1500) - Money(200))
        assertEquals(Money(750), Money(1500) * 0.5)
        assertEquals(Money(-1500), -Money(1500))
    }

    /** Il troncamento farebbe sparire crediti nella divisione dei premi. */
    @Test
    fun `il prodotto arrotonda invece di troncare`() {
        assertEquals(Money(2), Money(3) * 0.5)
    }

    @Test
    fun `il confronto ordina come i numeri`() {
        assertEquals(Money(1500), Money(700).coerceAtLeast(Money(1500)))
        assertEquals(Money(700), Money(700).coerceAtMost(Money(1500)))
        assertTrue(Money(1500) > Money(700))
        assertEquals(Money.ZERO, Money(0))
        assertEquals(listOf(Money(1), Money(700), Money(1500)), listOf(Money(700), Money(1500), Money(1)).sorted())
    }

    @Test
    fun `i costruttori nominati sono coerenti con l'unità`() {
        assertEquals(Money(1500), Money.thousands(1500))
        assertEquals(Money(1500), Money.millions(1.5))
        assertEquals(Money(100_000), Money.millions(100.0))
    }
}
