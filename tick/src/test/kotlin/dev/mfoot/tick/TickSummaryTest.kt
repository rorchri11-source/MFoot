package dev.mfoot.tick

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Un giro con una lega fallita non è un giro riuscito.
 *
 * ## Il difetto che questo test tiene chiuso
 *
 * Ogni lega gira in una transazione a sé, e un suo errore viene catturato, riportato
 * indietro e messo in un elenco: giusto, perché una lega rotta non deve fermare le altre.
 * Ma il programma restituiva **zero comunque**, quindi su GitHub l'esecuzione risultava
 * verde.
 *
 * L'effetto misurato sul registro vero: sessantasei esecuzioni "riuscite" di fila mentre
 * in una lega otto aste scadute restavano aperte. Il fallimento era scritto nel log di un
 * giro verde, cioè in un posto che nessuno apre.
 */
class TickSummaryTest {

    private fun lega(id: Long) = LeagueSummary(
        leagueId = id,
        name = "Lega $id",
        planned = 3,
        applied = 3,
        pending = 0,
        notes = emptyList(),
    )

    @Test
    fun `senza fallimenti il giro e' riuscito`() {
        val summary = TickSummary(leagues = listOf(lega(1), lega(2)), failures = emptyList())
        assertFalse(summary.failed)
    }

    @Test
    fun `una sola lega fallita fa fallire il giro`() {
        val summary = TickSummary(
            leagues = listOf(lega(1)),
            failures = listOf("lega 2 'Prova': colonna inesistente"),
        )
        assertTrue(summary.failed, "una lega annullata deve tingere di rosso l'esecuzione")
    }

    @Test
    fun `il motivo del fallimento finisce nel riepilogo`() {
        val summary = TickSummary(
            leagues = emptyList(),
            failures = listOf("lega 7 'Prova Scambi': qualcosa e' andato storto"),
        )

        assertTrue(summary.describe().contains("FALLITA"))
        assertTrue(summary.describe().contains("Prova Scambi"))
    }

    /** Nessuna lega attiva non e' un errore: e' un database senza leghe da far girare. */
    @Test
    fun `nessuna lega attiva non e' un fallimento`() {
        val summary = TickSummary(leagues = emptyList(), failures = emptyList())

        assertFalse(summary.failed)
        assertTrue(summary.describe().contains("Nessuna lega attiva"))
    }
}
