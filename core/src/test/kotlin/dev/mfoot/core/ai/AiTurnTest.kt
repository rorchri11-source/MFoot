package dev.mfoot.core.ai

import dev.mfoot.core.config.ConfigPresets
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quante cose fa un club del computer quando si sveglia.
 *
 * ## Il difetto che questi test descrivono
 *
 * Misurato dal proprietario il 2026-08-25, dopo mezza giornata reale di gioco: cinque
 * club su dieci avevano qualche giocatore, **nessuno** ne aveva piu' di tre.
 *
 * La causa non era una decisione sbagliata dell'AI: era il ritmo. Una mossa per risveglio,
 * un risveglio per giro di server, un giro ogni cinquanta minuti — e due giri su tre
 * annullati dal timeout. Quattro acquisti al giorno per club, sedici giocatori da
 * raggiungere.
 */
class AiTurnTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))

    @Test
    fun `a rosa completa resta una mossa per risveglio`() {
        // E' la difesa dallo sciame, e a rosa completa e' quella giusta: ogni mossa di
        // un'AI qui e' un'asta, un'offerta, una notifica su un telefono.
        assertEquals(1, AiTurn.movesPerWake(config.setup.minSquadSize, config))
        assertEquals(1, AiTurn.movesPerWake(config.setup.maxSquadSize, config))
    }

    @Test
    fun `a rosa incompleta se ne concedono molte di piu`() {
        val mosse = AiTurn.movesPerWake(0, config)
        assertTrue(mosse > 1, "un club vuoto deve poter fare piu' di una mossa, non $mosse")
        assertEquals(AiTurn.MOSSE_IN_ALLESTIMENTO, mosse)
    }

    @Test
    fun `il confine e il minimo di rosa, non un numero a parte`() {
        assertEquals(AiTurn.MOSSE_IN_ALLESTIMENTO, AiTurn.movesPerWake(config.setup.minSquadSize - 1, config))
        assertEquals(1, AiTurn.movesPerWake(config.setup.minSquadSize, config))
    }

    /**
     * Il conto che deve tornare: da rosa vuota a rosa legale in pochi risvegli.
     *
     * Non e' una verifica di stile — e' il requisito. Con una mossa per risveglio servivano
     * sedici risvegli, che al ritmo vero del server sono giorni.
     */
    @Test
    fun `una rosa legale si raggiunge in meno di quattro risvegli`() {
        var rosa = 0
        var risvegli = 0
        while (rosa < config.setup.minSquadSize && risvegli < 50) {
            rosa += AiTurn.movesPerWake(rosa, config)
            risvegli++
        }
        assertTrue(rosa >= config.setup.minSquadSize, "non ci arriva mai")
        assertTrue(risvegli < 4, "servono ancora $risvegli risvegli")
    }

    @Test
    fun `chi ha la rosa corta compra a listino prima di aprire aste`() {
        val ordine = AiTurn.order(0, config)
        assertEquals(AiMove.COMPRA_A_LISTINO, ordine.first())
    }
}
