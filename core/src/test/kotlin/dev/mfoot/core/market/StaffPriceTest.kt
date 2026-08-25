package dev.mfoot.core.market

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quanto costa un membro dello staff.
 *
 * ## Il difetto che questo test esiste per impedire
 *
 * Il prezzo dello staff viveva dentro una schermata dell'app e da nessun'altra parte, per
 * cui il server non lo conosceva e l'unica strada per prendere un allenatore restava
 * l'asta — segnalato dal proprietario il 2026-08-25: «per prendere lo staff si e' ancora
 * obbligati a farlo tramite asta».
 *
 * Adesso e' una regola di `core` con una copia in SQL, e quello che va difeso e' il
 * rapporto fra prezzo ed effetto: se il cinque stelle costasse poco lo prenderebbe chi
 * apre l'app per primo, e se costasse troppo non lo prenderebbe nessuno.
 */
class StaffPriceTest {

    /** Budget 100.000: la scala di riferimento della lega. */
    private val config = ConfigPresets.sprint(16, 8, LocalDate.of(2026, 9, 1))
        .let { it.copy(economy = it.economy.copy(startingCredits = 100_000)) }

    @Test
    fun `il listino dello staff, stampato`() {
        println("--- Staff, budget ${Money(config.economy.startingCredits).format()} ---")
        (1..5).forEach { stelle ->
            val prezzo = Valuation.staffPrice(stelle, config)
            val quota = prezzo * 100.0 / config.economy.startingCredits
            println("${"*".repeat(stelle)}\t${Money(prezzo).format()}\t${"%.2f".format(quota)}% del budget")
        }
    }

    @Test
    fun `piu stelle costa piu caro, sempre`() {
        val prezzi = (1..5).map { Valuation.staffPrice(it, config) }
        assertEquals(prezzi.sorted(), prezzi, "il listino non e' crescente: $prezzi")
    }

    /**
     * Il salto fra quattro e cinque stelle deve essere piu' grande di quello fra una e due.
     *
     * E' la stessa forma della scala degli effetti in [dev.mfoot.core.model.Staff]: fra 4 e
     * 5 stelle la crescita passa da 1,35 a 1,80, fra 1 e 2 da 0,60 a 0,80. Un prezzo
     * lineare renderebbe il cinque stelle un affare ovvio.
     */
    @Test
    fun `la curva e ripida in alto, come lo sono gli effetti`() {
        val saltoBasso = Valuation.staffPrice(2, config) - Valuation.staffPrice(1, config)
        val saltoAlto = Valuation.staffPrice(5, config) - Valuation.staffPrice(4, config)
        assertTrue(saltoAlto > saltoBasso * 2, "salto basso $saltoBasso, salto alto $saltoAlto")
    }

    /**
     * Un cinque stelle deve costare abbastanza da essere una decisione, non tanto da
     * essere fuori portata: la rosa resta la spesa principale.
     */
    @Test
    fun `il migliore costa quanto dice la manopola del budget`() {
        val quota = Valuation.staffPrice(5, config) * 1.0 / config.economy.startingCredits
        assertEquals(config.economy.staffBudgetShare, quota, 0.001)
        assertTrue(quota in 0.02..0.08, "il cinque stelle costa il ${quota * 100}% del budget")
    }

    /** E il peggiore dev'essere accessibile a chiunque fin dal primo giorno. */
    @Test
    fun `il peggiore costa meno di mezzo punto percentuale`() {
        val quota = Valuation.staffPrice(1, config) * 100.0 / config.economy.startingCredits
        assertTrue(quota < 0.5, "il una stella costa il $quota% del budget")
    }

    @Test
    fun `il prezzo segue il budget della lega, non e un numero fisso`() {
        val poverissima = config.copy(economy = config.economy.copy(startingCredits = 1_000))
        assertTrue(Valuation.staffPrice(5, poverissima) < Valuation.staffPrice(5, config))
        assertTrue(Valuation.staffPrice(5, poverissima) >= 1, "non deve mai essere gratis")
    }

    @Test
    fun `stelle fuori scala non fanno esplodere niente`() {
        assertEquals(Valuation.staffPrice(1, config), Valuation.staffPrice(0, config))
        assertEquals(Valuation.staffPrice(5, config), Valuation.staffPrice(9, config))
    }
}
