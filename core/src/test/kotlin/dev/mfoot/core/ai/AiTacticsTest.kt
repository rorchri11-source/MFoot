package dev.mfoot.core.ai

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.match.TacticalPressing
import dev.mfoot.core.match.TacticalStance
import dev.mfoot.core.match.TacticalTempo
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Le scelte tecniche dei club del computer.
 *
 * ## Cosa difendono questi test
 *
 * La segnalazione del proprietario del 2026-08-25: «non schierano o fanno nessuna tattica
 * o scelta tecnica». Era vera alla lettera — il tick dava [Tactics.DEFAULT] a tutti e
 * dieci i club — e la correzione va difesa dalle due strade con cui potrebbe tornare
 * indietro:
 *
 * 1. tutti che scelgono lo stesso assetto, cioe' la scelta che non e' una scelta;
 * 2. un assetto che ignora la rosa, cioe' club che giocano all'attacco senza attaccanti.
 */
class AiTacticsTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)
    private val oggi = MatchDay(1)

    private fun carattere(
        clubId: Long = 1L,
        aggressivita: Double = 0.5,
        pazienza: Double = 0.5,
        fissazioni: Set<AiObsession> = emptySet(),
    ) = AiPersonality(
        clubId = ClubId(clubId),
        marketAggression = aggressivita,
        youthPreference = 0.5,
        budgetDiscipline = 0.5,
        patience = pazienza,
        obsessions = fissazioni,
        activeFromHour = 9,
        activeToHour = 22,
        checksPerDay = 4,
    )

    /**
     * I migliori o i peggiori di un ruolo, presi dal mondo generato.
     *
     * ## Perche' «i peggiori» e non «salta i primi duecento»
     *
     * Perche' la prima versione di questo test faceva `drop(220)` su ruoli che nel mondo
     * generato hanno cinquantacinque giocatori: la lista usciva **vuota**, il reparto
     * risultava inesistente, lo sbilanciamento veniva zero e tre test fallivano dicendo
     * «EQUILIBRATO» senza che ci fosse niente di sbagliato nel codice sotto esame.
     *
     * Le quantita' vere, misurate: 55 attaccanti (da 77 a 41), 192 difensori centrali (da
     * 85 a 32). Chiedere «i peggiori tre» invece di un indice inventato vale su qualunque
     * mondo, e resta valido se un giorno la generazione cambia.
     */
    private fun daiMigliori(posizione: Position, quanti: Int, peggiori: Boolean = false): List<Player> {
        val ordinati = world.players
            .filter { it.primaryPosition == posizione }
            .sortedByDescending { it.overall }
        val scelti = if (peggiori) ordinati.takeLast(quanti) else ordinati.take(quanti)
        assertTrue(scelti.size == quanti, "il mondo generato non ha $quanti giocatori in $posizione")
        return scelti
    }

    /** Una rosa con l'attacco molto piu' forte della difesa, o il contrario. */
    private fun rosa(attaccoForte: Boolean): List<Player> {
        val attaccanti = daiMigliori(Position.ATT, 3, peggiori = !attaccoForte)
        val difensori = daiMigliori(Position.DC, 3, peggiori = attaccoForte)
        return attaccanti + difensori + daiMigliori(Position.CC, 3) + daiMigliori(Position.POR, 1)
    }

    /**
     * Una rosa con i reparti allo stesso livello.
     *
     * I giocatori si prendono **per overall** e non per posizione nella lista: le liste
     * dei due ruoli hanno lunghezze molto diverse — cinquantacinque attaccanti contro
     * centonovantadue difensori — quindi «il centesimo di ognuna» non e' affatto lo stesso
     * livello.
     */
    private fun rosaEquilibrata(): List<Player> {
        fun intornoA(posizione: Position, overall: Int, quanti: Int) =
            world.players.filter { it.primaryPosition == posizione }
                .sortedBy { kotlin.math.abs(it.overall - overall) }
                .take(quanti)

        return intornoA(Position.ATT, 65, 3) + intornoA(Position.DC, 65, 3) +
            intornoA(Position.CC, 65, 3) + intornoA(Position.POR, 65, 1)
    }

    @Test
    fun `chi ha l attacco piu forte della difesa gioca in avanti`() {
        val t = AiTactics.choose(carattere(), rosa(attaccoForte = true), oggi)
        assertTrue(
            t.stance in listOf(TacticalStance.OFFENSIVO, TacticalStance.ULTRA_OFFENSIVO),
            "assetto scelto: ${t.stance}",
        )
    }

    @Test
    fun `chi ha la difesa piu forte dell attacco si chiude`() {
        val t = AiTactics.choose(carattere(), rosa(attaccoForte = false), oggi)
        assertTrue(
            t.stance in listOf(TacticalStance.DIFENSIVO, TacticalStance.ULTRA_DIFENSIVO),
            "assetto scelto: ${t.stance}",
        )
    }

    /**
     * La rosa pesa piu' del carattere.
     *
     * E' la regola che impedisce il club fissato con l'attacco che gioca ultra offensivo
     * con tre attaccanti da quaranta: il carattere inclina, non decide.
     */
    @Test
    fun `la fissazione per l attacco non ribalta una rosa difensiva`() {
        val fissato = AiTactics.choose(
            carattere(fissazioni = setOf(AiObsession.ATTACCO)),
            rosa(attaccoForte = false),
            oggi,
        )
        assertNotEquals(TacticalStance.ULTRA_OFFENSIVO, fissato.stance)
    }

    /**
     * ...ma si sente: su una rosa equilibrata e' il carattere a decidere.
     *
     * La rosa dev'essere equilibrata sul serio, e il test lo verifica invece di darlo per
     * scontato. Su una rosa estrema lo sbilanciamento satura il conto e i due caratteri
     * finiscono sullo stesso assetto — che e' il comportamento giusto, non un difetto: con
     * tre attaccanti da settantasette e tre difensori da trentacinque si attacca comunque.
     */
    @Test
    fun `su una rosa equilibrata la fissazione decide l assetto`() {
        val squadra = rosaEquilibrata()
        assertTrue(
            kotlin.math.abs(AiTactics.sbilanciamento(squadra)) < 0.5,
            "la rosa di prova non e' equilibrata: ${AiTactics.sbilanciamento(squadra)}",
        )

        val offensivo = AiTactics.choose(carattere(fissazioni = setOf(AiObsession.ATTACCO)), squadra, oggi)
        val difensivo = AiTactics.choose(carattere(fissazioni = setOf(AiObsession.DIFESA)), squadra, oggi)
        assertNotEquals(offensivo.stance, difensivo.stance)
    }

    @Test
    fun `una rosa a terra non pressa e non corre, qualunque sia il carattere`() {
        val stanchi = rosa(attaccoForte = true).map { it.withStamina(35) }
        val t = AiTactics.choose(carattere(aggressivita = 1.0), stanchi, oggi)

        assertEquals(TacticalTempo.LENTO, t.tempo)
        assertEquals(TacticalPressing.BASSO, t.pressing)
    }

    @Test
    fun `una rosa fresca e un carattere aggressivo pressano alto`() {
        val freschi = rosa(attaccoForte = true).map { it.withStamina(100) }
        val t = AiTactics.choose(carattere(aggressivita = 0.9), freschi, oggi)

        assertEquals(TacticalPressing.ALTO, t.pressing)
        assertEquals(TacticalTempo.ALTO, t.tempo)
    }

    /**
     * Il difetto vero, misurato: dieci club, un solo assetto.
     *
     * Questo test fallirebbe con il codice di prima — [Tactics.DEFAULT] per tutti — ed e'
     * l'unico che descrive la lamentela cosi' come e' arrivata.
     */
    @Test
    fun `dieci club generati non giocano tutti allo stesso modo`() {
        val assetti = (1L..10L).map { id ->
            val personalita = AiPersonalityGenerator.generate(ClubId(id), config.setup.worldSeed, config.ai)
            // Rose diverse per club diversi: e' la situazione vera di una lega.
            val squadra = world.players.shuffled(java.util.Random(id)).take(18)
            AiTactics.choose(personalita, squadra, oggi)
        }

        assertTrue(
            assetti.distinct().size >= 3,
            "dieci club hanno prodotto solo ${assetti.distinct().size} assetti: $assetti",
        )
        assertTrue(
            assetti.any { it != Tactics.DEFAULT },
            "sono tutti sull'assetto predefinito, cioe' nessuno ha scelto niente",
        )
    }

    @Test
    fun `una rosa vuota non fa esplodere niente`() {
        assertEquals(Tactics.DEFAULT, AiTactics.choose(carattere(), emptyList(), oggi))
    }

    @Test
    fun `gli infortunati non contano nello sbilanciamento`() {
        val squadra = rosa(attaccoForte = true)
        val senzaAttacco = squadra.map { p ->
            if (p.primaryPosition == Position.ATT) p.copy(injuredUntil = MatchDay(9)) else p
        }

        // Tolti gli attaccanti, la squadra non e' piu' sbilanciata in avanti.
        assertTrue(
            AiTactics.sbilanciamento(squadra.filterNot { it.isInjured(oggi) }) >
                AiTactics.sbilanciamento(senzaAttacco.filterNot { it.isInjured(oggi) }),
        )
    }

    @Test
    fun `la freschezza e la media degli undici che giocherebbero`() {
        val squadra = rosa(attaccoForte = true).map { it.withStamina(80) }
        assertEquals(0.8, AiTactics.freschezza(squadra), 0.01)
    }
}
