package dev.mfoot.core.calendar

import dev.mfoot.core.model.ClubId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DivisionAssignmentTest {

    private var prossimo = 1L

    private fun umano(forza: Long = 100) =
        ClubToPlace(ClubId(prossimo++), isHuman = true, isSecondTeam = false, strength = forza)

    private fun ai(forza: Long) =
        ClubToPlace(ClubId(prossimo++), isHuman = false, isSecondTeam = false, strength = forza)

    private fun primavera() =
        ClubToPlace(ClubId(prossimo++), isHuman = true, isSecondTeam = true, strength = 10)

    private fun livello(p: Placement, club: ClubToPlace) = p.levels[club.id]

    // ------------------------------------------------------------------------ la regola

    @Test
    fun `gli umani partono tutti in prima divisione, anche i piu' deboli`() {
        val forte = umano(forza = 900)
        val scarso = umano(forza = 100)
        val aiForti = (1..6).map { ai(forza = 1000) }

        val esito = DivisionAssignment.initial(listOf(forte, scarso) + aiForti, divisions = 2)

        assertEquals(1, livello(esito, forte))
        assertEquals(1, livello(esito, scarso), "un umano debole non deve finire in seconda")
    }

    /**
     * Il difetto che questa funzione sostituisce: la vecchia serpentina ordinava per forza
     * e mescolava umani e AI, quindi un amico con la rosa piu' debole cominciava la sua
     * prima stagione in Serie B contro otto squadre del computer.
     */
    @Test
    fun `le AI forti non spingono gli umani in seconda divisione`() {
        val umani = (1..4).map { umano(forza = 50) }
        val ai = (1..4).map { ai(forza = 9999) }

        val esito = DivisionAssignment.initial(umani + ai, divisions = 2)

        assertTrue(umani.all { livello(esito, it) == 1 })
    }

    @Test
    fun `le AI riempiono i posti che restano, dalla piu' forte`() {
        val umani = (1..2).map { umano() }
        val fortissima = ai(forza = 900)
        val media = ai(forza = 500)
        val debole = ai(forza = 100)

        // Quattro posti in prima, due gia' presi dagli umani: entrano le due AI migliori.
        val esito = DivisionAssignment.initial(
            umani + listOf(debole, fortissima, media),
            divisions = 2,
            sizes = listOf(4, 1),
        )

        assertEquals(1, livello(esito, fortissima))
        assertEquals(1, livello(esito, media))
        assertEquals(2, livello(esito, debole))
    }

    @Test
    fun `le seconde squadre vanno nell'ultima divisione`() {
        val umano = umano()
        val primavera = primavera()

        val esito = DivisionAssignment.initial(
            listOf(umano, primavera) + (1..4).map { ai(100) },
            divisions = 3,
        )

        assertEquals(1, livello(esito, umano))
        assertEquals(3, livello(esito, primavera))
    }

    /**
     * Le Primavere non consumano posti della prima divisione: contarle vorrebbe dire
     * togliere un posto a un amico per darlo alla seconda squadra di un altro.
     */
    @Test
    fun `le seconde squadre non tolgono posti in prima divisione`() {
        val umani = (1..3).map { umano() }
        val primavere = (1..3).map { primavera() }
        val ai = (1..3).map { ai(100) }

        val esito = DivisionAssignment.initial(
            umani + primavere + ai,
            divisions = 2,
            sizes = listOf(4, 2),
        )

        assertTrue(umani.all { livello(esito, it) == 1 })
        // Un posto libero in prima dopo i tre umani: lo prende l'AI piu' forte.
        assertEquals(1, esito.levels.count { (id, l) -> l == 1 && ai.any { it.id == id } })
    }

    // -------------------------------------------------------------------------- avvisi

    @Test
    fun `se gli umani non ci stanno lo dice, e li fa entrare lo stesso`() {
        val umani = (1..12).map { umano() }
        val ai = (1..8).map { ai(100) }

        val esito = DivisionAssignment.initial(umani + ai, divisions = 2, sizes = listOf(10, 10))

        assertTrue(umani.all { livello(esito, it) == 1 }, "la regola vince sulla dimensione")
        assertEquals(1, esito.warnings.size)
        assertTrue(esito.warnings.first().message.contains("12"))
    }

    @Test
    fun `quando ci stanno tutti non avvisa niente`() {
        val umani = (1..4).map { umano() }
        val ai = (1..8).map { ai(100) }

        val esito = DivisionAssignment.initial(umani + ai, divisions = 2, sizes = listOf(6, 6))

        assertTrue(esito.warnings.isEmpty())
    }

    @Test
    fun `una divisione che resterebbe vuota viene segnalata`() {
        val esito = DivisionAssignment.initial(listOf(umano(), umano()), divisions = 3)

        assertTrue(esito.warnings.any { it.message.contains("Nessuna squadra") })
    }

    // ------------------------------------------------------------------------ capienze

    @Test
    fun `senza dimensioni scelte si divide in parti uguali, il resto in alto`() {
        val club = (1..11).map { ai(1000L - it) }
        val esito = DivisionAssignment.initial(club, divisions = 3)

        val quante = (1..3).map { livello -> esito.levels.count { it.value == livello } }
        assertEquals(listOf(4, 4, 3), quante)
    }

    @Test
    fun `le dimensioni scelte dall'admin vincono su quelle calcolate`() {
        val club = (1..12).map { ai(1000L - it) }
        val esito = DivisionAssignment.initial(club, divisions = 2, sizes = listOf(8, 4))

        assertEquals(8, esito.levels.count { it.value == 1 })
        assertEquals(4, esito.levels.count { it.value == 2 })
    }

    @Test
    fun `le AI che avanzano finiscono nell'ultima divisione invece di sparire`() {
        val club = (1..10).map { ai(1000L - it) }
        val esito = DivisionAssignment.initial(club, divisions = 2, sizes = listOf(3, 3))

        assertEquals(10, esito.levels.size, "nessun club puo' restare senza divisione")
        assertEquals(7, esito.levels.count { it.value == 2 })
    }

    @Test
    fun `con una divisione sola stanno tutti li'`() {
        val club = listOf(umano(), ai(500), primavera())
        val esito = DivisionAssignment.initial(club, divisions = 1)

        assertTrue(esito.levels.values.all { it == 1 })
    }

    @Test
    fun `senza club non si assegna niente e non si esplode`() {
        assertTrue(DivisionAssignment.initial(emptyList(), divisions = 3).levels.isEmpty())
    }
}
