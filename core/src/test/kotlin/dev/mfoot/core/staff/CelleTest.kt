package dev.mfoot.core.staff

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.config.StaffConfig
import dev.mfoot.core.model.StaffRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Le celle dello staff.
 *
 * La prova che porta il peso e' `si possono possedere piu' membri dello stesso ruolo`: e'
 * la cosa che oggi e' impossibile per costruzione, non per una svista di interfaccia.
 */
class CelleTest {

    private val config = LeagueConfig()

    @Test
    fun `le celle sono nove, due piu' due piu' cinque`() {
        val celle = Celle.tutte(config)
        assertEquals(9, celle.size, "le celle sono ${celle.size} invece di nove")
        assertEquals(2, Celle.quanteCelle(StaffRole.ALLENATORE, config))
        assertEquals(2, Celle.quanteCelle(StaffRole.PREPARATORE, config))
        assertEquals(5, Celle.quanteCelle(StaffRole.OSSERVATORE, config))
    }

    /**
     * Allenatori e preparatori scelgono fra due squadre; gli osservatori no, stanno tutti
     * in Primavera. E' un'asimmetria voluta, non una dimenticanza.
     */
    @Test
    fun `allenatori e preparatori hanno una cella per squadra`() {
        listOf(StaffRole.ALLENATORE, StaffRole.PREPARATORE).forEach { role ->
            val posti = Celle.di(role, config).map { it.posto }
            assertEquals(listOf(Posto.PRIMA_SQUADRA, Posto.PRIMAVERA), posti, "$role")
        }
    }

    @Test
    fun `gli osservatori stanno tutti in Primavera`() {
        val celle = Celle.di(StaffRole.OSSERVATORE, config)
        assertTrue(
            celle.all { it.posto == Posto.PRIMAVERA },
            "qualche osservatore ha una cella in prima squadra",
        )
        assertEquals(
            listOf(0, 1, 2, 3, 4), celle.map { it.indice },
            "le cinque celle degli osservatori non sono distinguibili fra loro",
        )
    }

    // ------------------------------------------------------------------ la Primavera

    @Test
    fun `senza Primavera le celle della Primavera dicono perche'`() {
        Celle.tutte(config).filter { it.posto == Posto.PRIMAVERA }.forEach { cella ->
            val perche = Celle.impedimento(cella, haPrimavera = false)
            assertNotNull(perche, "la cella $cella non spiega perche' e' chiusa")
            assertTrue(perche.isNotBlank())
        }
    }

    @Test
    fun `con la Primavera si aprono tutte`() {
        Celle.tutte(config).forEach { cella ->
            assertNull(
                Celle.impedimento(cella, haPrimavera = true),
                "la cella $cella resta chiusa anche con la Primavera",
            )
        }
    }

    @Test
    fun `la prima squadra non dipende dalla Primavera`() {
        Celle.tutte(config).filter { it.posto == Posto.PRIMA_SQUADRA }.forEach { cella ->
            assertNull(Celle.impedimento(cella, haPrimavera = false), "$cella")
        }
    }

    // --------------------------------------------------------------------- l'acquisto

    /**
     * *«Gli osservatori li possono prendere solo le primavere»*. Il divieto sta sul
     * pulsante, prima del tocco.
     */
    @Test
    fun `senza Primavera non si comprano osservatori`() {
        val perche = Celle.impedimentoAcquisto(
            StaffRole.OSSERVATORE, posseduti = 0, haPrimavera = false, config = config,
        )
        assertNotNull(perche)
        assertTrue(
            perche.contains("Primavera"),
            "il divieto non dice che c'entra la Primavera: $perche",
        )
    }

    @Test
    fun `gli altri ruoli si comprano anche senza Primavera`() {
        listOf(StaffRole.ALLENATORE, StaffRole.PREPARATORE).forEach { role ->
            assertNull(
                Celle.impedimentoAcquisto(role, 0, haPrimavera = false, config = config),
                "$role non si compra senza Primavera",
            )
        }
    }

    /**
     * **La prova per cui questo file esiste.**
     *
     * Con due celle se ne possono possedere quattro: due schierati e due di scorta. Oggi
     * assegnarne uno nuovo libera il vecchio, quindi possederne due e' impossibile.
     */
    @Test
    fun `si possono possedere piu' membri dello stesso ruolo di quante celle ci sono`() {
        listOf(StaffRole.ALLENATORE, StaffRole.PREPARATORE).forEach { role ->
            val celle = Celle.quanteCelle(role, config)
            val tetto = Celle.tetto(role, config)
            assertTrue(
                tetto > celle,
                "$role: tetto $tetto contro $celle celle, non resta nessuno da scegliere",
            )
        }
    }

    @Test
    fun `al tetto il negozio dice di no, e dice quanti`() {
        val tetto = Celle.tetto(StaffRole.ALLENATORE, config)
        assertNull(
            Celle.impedimentoAcquisto(StaffRole.ALLENATORE, tetto - 1, true, config),
            "sotto il tetto l'acquisto e' bloccato",
        )
        val perche = Celle.impedimentoAcquisto(StaffRole.ALLENATORE, tetto, true, config)
        assertNotNull(perche)
        assertTrue(
            perche.contains(tetto.toString()),
            "il divieto non dice quanti se ne possono avere: $perche",
        )
    }

    @Test
    fun `oltre il tetto resta bloccato`() {
        val tetto = Celle.tetto(StaffRole.OSSERVATORE, config)
        assertNotNull(
            Celle.impedimentoAcquisto(StaffRole.OSSERVATORE, tetto + 3, true, config),
        )
    }

    /**
     * Gli osservatori si schierano tutti: tetto uguale alle celle, e non serve scorta.
     */
    @Test
    fun `gli osservatori non hanno riserve`() {
        assertEquals(
            Celle.quanteCelle(StaffRole.OSSERVATORE, config),
            Celle.tetto(StaffRole.OSSERVATORE, config),
        )
    }

    // ---------------------------------------------------------------- configurabilita'

    @Test
    fun `i tetti e le celle li decide la configurazione`() {
        val stretta = LeagueConfig(
            staff = StaffConfig(maxAllenatori = 2, maxPreparatori = 2, maxOsservatori = 2),
        )
        assertEquals(2, Celle.tetto(StaffRole.ALLENATORE, stretta))
        assertEquals(2, Celle.quanteCelle(StaffRole.OSSERVATORE, stretta))
        assertEquals(6, Celle.tutte(stretta).size, "due piu' due piu' due fanno sei celle")
    }

    @Test
    fun `un tetto a zero non spegne il ruolo`() {
        val assurda = LeagueConfig(staff = StaffConfig(maxAllenatori = 0))
        assertEquals(
            1, Celle.tetto(StaffRole.ALLENATORE, assurda),
            "con un tetto a zero nessuno potrebbe mai allenare, e la lega sarebbe rotta " +
                "da una configurazione invece che da un bug",
        )
    }
}
