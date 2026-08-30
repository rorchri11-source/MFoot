package dev.mfoot.core.match

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Il pronostico.
 *
 * La prova che porta il peso e' `lo stesso pronostico su ogni telefono`: un numero che
 * cambia a ogni apertura non e' un pronostico, e due amici che leggono 46% e 51% sulla
 * stessa partita smettono di credere a tutti e due.
 */
class PronosticoTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    private fun coppia(casa: Int, ospite: Int) = TestSquads.coppia(world, casa, ospite)

    // ------------------------------------------------------------ le tre percentuali

    @Test
    fun `le tre probabilita' fanno sempre cento`() {
        listOf(75 to 75, 85 to 62, 62 to 85, 79 to 78).forEach { (a, b) ->
            val (casa, ospite) = coppia(a, b)
            val esito = Pronostico.calcola(casa, ospite, config, fixtureId = 11, quante = 120)
            assertEquals(
                100, esito.casa + esito.pari + esito.ospite,
                "con $a contro $b le probabilita' fanno ${esito.casa + esito.pari + esito.ospite}",
            )
        }
    }

    /**
     * Arrotondando ciascuna per conto suo si ottiene 33+33+33, e novantanove non e' una
     * probabilita'.
     */
    @Test
    fun `il resto dell'arrotondamento non si perde`() {
        val esito = Pronostico.percentuali(casa = 1, pari = 1, ospite = 1, totale = 3)
        assertEquals(100, esito.casa + esito.pari + esito.ospite)
    }

    @Test
    fun `senza simulazioni non si inventa una certezza`() {
        val esito = Pronostico.percentuali(0, 0, 0, 0)
        assertEquals(100, esito.casa + esito.pari + esito.ospite)
    }

    // --------------------------------------------------------------- il determinismo

    /**
     * **La prova per cui questo file esiste.**
     *
     * I semi vengono dall'identificativo della partita, non da un orologio: due telefoni
     * che aprono la stessa partita devono leggere lo stesso numero.
     */
    @Test
    fun `lo stesso pronostico su ogni telefono, e a ogni apertura`() {
        val (casa, ospite) = coppia(78, 73)
        val primo = Pronostico.calcola(casa, ospite, config, fixtureId = 4242, quante = 150)
        val secondo = Pronostico.calcola(casa, ospite, config, fixtureId = 4242, quante = 150)
        assertEquals(primo, secondo)
    }

    @Test
    fun `partite diverse hanno pronostici diversi`() {
        val (casa, ospite) = coppia(78, 73)
        val esiti = (1L..12L)
            .map { Pronostico.calcola(casa, ospite, config, fixtureId = it, quante = 120) }
            .toSet()
        assertTrue(
            esiti.size > 3,
            "dodici partite danno solo ${esiti.size} pronostici diversi: i semi non " +
                "dipendono dalla partita",
        )
    }

    // -------------------------------------------------------------- dice la verita'

    @Test
    fun `la squadra piu' forte e' data avanti`() {
        val (casa, ospite) = coppia(84, 66)
        val esito = Pronostico.calcola(casa, ospite, config, fixtureId = 7, quante = 300)
        assertTrue(
            esito.casa > esito.ospite,
            "la squadra da 84 e' data sotto quella da 66: ${esito.casa} contro ${esito.ospite}",
        )
    }

    @Test
    fun `giocare in casa si vede, ma non decide`() {
        val (casa, ospite) = coppia(75, 75)
        val esito = Pronostico.calcola(casa, ospite, config, fixtureId = 9, quante = 400)
        assertTrue(
            esito.casa > esito.ospite,
            "fra pari il campo non conta niente: ${esito.casa} contro ${esito.ospite}",
        )
        assertTrue(
            esito.casa < 60,
            "fra squadre pari la casa e' data al ${esito.casa}%, che non e' una partita",
        )
    }

    /**
     * Il vantaggio del campo vale venti punti percentuali fra squadre identiche, ed e' la
     * ragione per cui una scorciatoia tipo «chi e' favorito» direbbe «la casa» in quasi ogni
     * partita. Le tre percentuali dicono gia' tutto.
     */
    @Test
    fun `fra squadre pari la partita resta aperta`() {
        val (casa, ospite) = coppia(75, 75)
        val esito = Pronostico.calcola(casa, ospite, config, fixtureId = 13, quante = 400)
        assertTrue(
            esito.ospite + esito.pari > 40,
            "fra due squadre uguali chi gioca fuori ha solo il " +
                "${esito.ospite + esito.pari}% di non perdere: il campo sta decidendo " +
                "la partita da solo",
        )
    }

    @Test
    fun `il pareggio non sparisce mai`() {
        val (casa, ospite) = coppia(86, 64)
        val esito = Pronostico.calcola(casa, ospite, config, fixtureId = 21, quante = 300)
        assertTrue(
            esito.pari > 0,
            "contro una squadra di ventidue punti inferiore il pareggio e' dato a zero: " +
                "e' proprio la partita che poi si racconta per settimane",
        )
    }

    @Test
    fun `quante simulazioni si chiedono non cambia il verso della risposta`() {
        val (casa, ospite) = coppia(82, 68)
        val poche = Pronostico.calcola(casa, ospite, config, fixtureId = 5, quante = 60)
        val tante = Pronostico.calcola(casa, ospite, config, fixtureId = 5, quante = 500)
        assertTrue(poche.casa > poche.ospite && tante.casa > tante.ospite)
        assertTrue(
            StrictMath.abs(poche.casa - tante.casa) < 20,
            "sessanta e cinquecento simulazioni danno ${poche.casa}% e ${tante.casa}%",
        )
    }

    @Test
    fun `un numero assurdo di simulazioni non fa esplodere niente`() {
        val (casa, ospite) = coppia(75, 75)
        val esito = Pronostico.calcola(casa, ospite, config, fixtureId = 1, quante = 0)
        assertEquals(100, esito.casa + esito.pari + esito.ospite)
    }
}
