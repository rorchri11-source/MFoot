package dev.mfoot.core.match

import dev.mfoot.core.model.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le coordinate del campo.
 *
 * Sono numeri scritti a mano, quindi l'errore possibile è la distrazione: un modulo con
 * dieci coordinate invece di undici, due giocatori nello stesso punto, un terzino finito
 * fuori dal campo. Nessuno di questi si nota leggendo la tabella; tutti si notano
 * guardando il campo disegnato, cioè troppo tardi.
 */
class PitchLayoutTest {

    @Test
    fun `ogni modulo ha una coordinata per ogni ruolo`() {
        Formation.entries.forEach { formation ->
            assertEquals(
                formation.positions.size,
                PitchLayout.of(formation).size,
                "${formation.label} ha ${PitchLayout.of(formation).size} coordinate " +
                    "per ${formation.positions.size} ruoli",
            )
        }
    }

    @Test
    fun `nessuno finisce fuori dal campo`() {
        Formation.entries.forEach { formation ->
            PitchLayout.of(formation).forEachIndexed { index, (x, y) ->
                assertTrue(x in 0f..1f, "${formation.label}, ruolo $index: x = $x")
                assertTrue(y in 0f..1f, "${formation.label}, ruolo $index: y = $y")
            }
        }
    }

    /** Due giocatori nello stesso punto si sovrappongono e uno dei due diventa invisibile. */
    @Test
    fun `nessuna coppia di ruoli occupa lo stesso punto`() {
        Formation.entries.forEach { formation ->
            val punti = PitchLayout.of(formation)
            assertEquals(
                punti.size,
                punti.toSet().size,
                "${formation.label} ha due ruoli nello stesso punto",
            )
        }
    }

    @Test
    fun `il portiere e sempre il piu arretrato`() {
        Formation.entries.forEach { formation ->
            val punti = PitchLayout.of(formation)
            val portiere = formation.positions.indexOfFirst { it == Position.POR }
            val yPortiere = punti[portiere].second

            punti.forEachIndexed { index, (_, y) ->
                if (index != portiere) {
                    assertTrue(
                        y > yPortiere,
                        "${formation.label}: il ruolo $index sta a $y, dietro al portiere",
                    )
                }
            }
        }
    }

    /**
     * Gli attaccanti davanti ai difensori.
     *
     * Sembra ovvio e invece è il difetto più facile: si copia la riga di un modulo per
     * scriverne un altro e si dimentica di alzare le `y`.
     */
    @Test
    fun `l'attacco sta davanti alla difesa`() {
        Formation.entries.forEach { formation ->
            val punti = PitchLayout.of(formation)
            val difensori = formation.positions.withIndex()
                .filter { it.value.reparto == dev.mfoot.core.model.Reparto.DIFESA }
                .map { punti[it.index].second }
            val attaccanti = formation.positions.withIndex()
                .filter { it.value.reparto == dev.mfoot.core.model.Reparto.ATTACCO }
                .map { punti[it.index].second }

            if (difensori.isNotEmpty() && attaccanti.isNotEmpty()) {
                assertTrue(
                    attaccanti.min() > difensori.max(),
                    "${formation.label}: attacco a ${attaccanti.min()}, difesa fino a ${difensori.max()}",
                )
            }
        }
    }

    @Test
    fun `il selettore dei ruoli copre tutti gli undici posti`() {
        assertEquals(11, PitchLayout.rolePicker().size)
    }
}
