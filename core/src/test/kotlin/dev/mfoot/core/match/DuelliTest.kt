package dev.mfoot.core.match

import dev.mfoot.core.config.EngineConfig
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Attributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le cinque contese.
 *
 * Il test che conta davvero e' l'ultimo — quello che verifica che **ogni attributo di
 * movimento decida almeno un episodio**. E' l'intero motivo per cui questo file esiste:
 * prima, `DRIBBLING`, `VELOCITA`, `DIFESA` e `INTERCETTAZIONE` entravano solo dentro la
 * media di zona e non vincevano mai niente da soli.
 */
class DuelliTest {

    private val engine = EngineConfig()

    private fun conAttributi(vararg pairs: Pair<Attr, Int>): Attributes =
        Attributes.of(50, *pairs)

    @Test
    fun `a parita' di valore la contesa sta dov'e' stata configurata`() {
        for (duello in Duello.entries) {
            val atteso = Duelli.equilibrio(duello, engine)
            val esito = Duelli.esito(duello, 70.0, 70.0, engine)
            assertTrue(
                StrictMath.abs(esito - atteso) < 1e-9,
                "$duello a parita' di valore da' $esito invece di $atteso",
            )
        }
    }

    /**
     * La frase del proprietario, tradotta in numero: *«chi e' piu' veloce ci arriva prima,
     * punto»*.
     */
    @Test
    fun `nella corsa venti punti di velocita' decidono quasi sempre`() {
        val esito = Duelli.esito(Duello.CORSA, 85.0, 65.0, engine)
        assertTrue(esito > 0.85, "venti punti di scarto nello scatto valgono solo $esito")
    }

    /**
     * L'altra meta' della stessa frase: *«nel contrasto conta di piu' il caso, perche'
     * c'entrano la posizione, il rimbalzo e l'arbitro»*.
     */
    @Test
    fun `nel contrasto gli stessi venti punti lasciano la porta aperta`() {
        val esito = Duelli.esito(Duello.CONTRASTO, 85.0, 65.0, engine)
        assertTrue(esito < 0.80, "venti punti di scarto nel contrasto valgono gia' $esito")
        assertTrue(esito > 0.55, "venti punti di scarto nel contrasto non contano niente")
    }

    @Test
    fun `la corsa e' piu' decisiva del contrasto e del dribbling`() {
        val corsa = Duelli.esito(Duello.CORSA, 85.0, 65.0, engine)
        val dribbling = Duelli.esito(Duello.DRIBBLING, 85.0, 65.0, engine)
        val contrasto = Duelli.esito(Duello.CONTRASTO, 85.0, 65.0, engine)

        assertTrue(
            corsa > dribbling && dribbling > contrasto,
            "l'ordine di decisivita' non e' quello deciso: corsa $corsa, " +
                "dribbling $dribbling, contrasto $contrasto",
        )
    }

    /**
     * Nessun divario deve produrre una certezza. Un motore che restituisce 1,0 e' un
     * motore in cui il campionato e' deciso al mercato.
     */
    @Test
    fun `nemmeno un divario assurdo produce una certezza`() {
        for (duello in Duello.entries) {
            val schiaccia = Duelli.esito(duello, 99.0, 1.0, engine)
            val schiacciato = Duelli.esito(duello, 1.0, 99.0, engine)
            assertTrue(schiaccia < 1.0, "$duello con 98 punti di scarto e' una certezza")
            assertTrue(schiacciato > 0.0, "$duello con 98 punti di scarto e' impossibile")
        }
    }

    @Test
    fun `piu' si e' forti piu' si vince, in ogni contesa`() {
        for (duello in Duello.entries) {
            var precedente = 0.0
            for (valore in listOf(40.0, 55.0, 70.0, 85.0, 95.0)) {
                val esito = Duelli.esito(duello, valore, 70.0, engine)
                assertTrue(esito > precedente, "$duello non e' monotono a $valore")
                precedente = esito
            }
        }
    }

    /**
     * Correre e saltare sono la stessa cosa per tutti e due; dribblare e far dribblare no.
     * E' il motivo per cui un terzino lento con un gran senso della posizione regge contro
     * un'ala e viene bruciato da un'altra.
     */
    @Test
    fun `la corsa e il duello aereo sono simmetrici, il dribbling no`() {
        assertEquals(Duelli.pesi(Duello.CORSA, Lato.ATTACCO), Duelli.pesi(Duello.CORSA, Lato.DIFESA))
        assertEquals(Duelli.pesi(Duello.AEREO, Lato.ATTACCO), Duelli.pesi(Duello.AEREO, Lato.DIFESA))

        assertTrue(
            Duelli.pesi(Duello.DRIBBLING, Lato.ATTACCO) !=
                Duelli.pesi(Duello.DRIBBLING, Lato.DIFESA),
            "chi dribbla e chi difende starebbero usando gli stessi attributi",
        )
        assertTrue(
            Duelli.pesi(Duello.CONTRASTO, Lato.ATTACCO) !=
                Duelli.pesi(Duello.CONTRASTO, Lato.DIFESA),
            "reggere il pallone e strapparlo starebbero usando gli stessi attributi",
        )
    }

    @Test
    fun `il valore individuale segue l'attributo che conta`() {
        val veloce = conAttributi(Attr.VELOCITA to 95)
        val lento = conAttributi(Attr.VELOCITA to 30)
        assertTrue(
            Duelli.valore(Duello.CORSA, Lato.ATTACCO, veloce) >
                Duelli.valore(Duello.CORSA, Lato.ATTACCO, lento),
        )

        val tecnico = conAttributi(Attr.DRIBBLING to 95, Attr.TECNICA to 90)
        val ruvido = conAttributi(Attr.DRIBBLING to 30, Attr.TECNICA to 35)
        assertTrue(
            Duelli.valore(Duello.DRIBBLING, Lato.ATTACCO, tecnico) >
                Duelli.valore(Duello.DRIBBLING, Lato.ATTACCO, ruvido),
        )
    }

    /**
     * **Il test per cui questo file esiste.**
     *
     * Prima, il motore leggeva direttamente solo `TIRO`, `TECNICA`, `FISICO`,
     * `POSIZIONAMENTO` e `PASSAGGIO`. Gli altri quattro finivano dentro la media di zona
     * e non decidevano mai niente da soli — e infatti due giocatori con lo stesso overall
     * giocavano la stessa identica partita.
     *
     * Il tiro non c'e' ed e' giusto cosi': decide le conclusioni, che sono di
     * [Conclusioni]. Qui si decide come ci si arriva.
     */
    @Test
    fun `ogni attributo di movimento decide almeno una contesa`() {
        val usati = Duello.entries
            .flatMap { duello -> Lato.entries.flatMap { Duelli.pesi(duello, it).keys } }
            .toSet()

        val attesi = Attr.outfield.toSet() - Attr.TIRO
        val dimenticati = attesi - usati

        assertTrue(
            dimenticati.isEmpty(),
            "questi attributi non decidono nessun episodio, come prima: $dimenticati",
        )
    }
}
