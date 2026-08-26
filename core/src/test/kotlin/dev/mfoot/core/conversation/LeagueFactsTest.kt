package dev.mfoot.core.conversation

import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.Trait
import dev.mfoot.core.model.Attributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Le regole che decidono quando un giocatore ha diritto di parlarti.
 *
 * Il difetto che questi test difendono e' quello che si vedeva giocando: quattro colloqui
 * di fila con lo stesso giocatore, +5 di morale ogni volta, e argomenti che non c'entravano
 * niente con quello che gli era successo.
 */
class LeagueFactsTest {

    private val oggi = MatchDay(20)

    // ------------------------------------------------------------------ un colloquio nasce

    @Test
    fun `tre panchine di fila aprono un discorso, e la causa dice quali`() {
        val storia = storia(
            recent = listOf(
                panchina(19), panchina(18), panchina(17), giocata(16, voto = 6.5),
            ),
        )

        val trigger = LeagueFacts.trigger(giocatore(morale = 60), storia, oggi)

        assertEquals(ConversationTopic.PANCHINA_PROLUNGATA, trigger?.topic)
        assertTrue(trigger!!.cause.contains("17a"), "la causa deve dire quali giornate: ${trigger.cause}")
        assertTrue(trigger.cause.contains("19a"))
    }

    @Test
    fun `due panchine non bastano`() {
        val storia = storia(recent = listOf(panchina(19), panchina(18), giocata(17, voto = 6.4)))

        assertNull(LeagueFacts.trigger(giocatore(morale = 70), storia, oggi))
    }

    @Test
    fun `due voti bassi su tre partite aprono il discorso sul rendimento`() {
        val storia = storia(
            recent = listOf(giocata(19, voto = 4.8), giocata(18, voto = 6.9), giocata(17, voto = 5.1)),
        )

        val trigger = LeagueFacts.trigger(giocatore(morale = 70), storia, oggi)

        assertEquals(ConversationTopic.PRESTAZIONI_SCARSE, trigger?.topic)
    }

    @Test
    fun `una grande partita e' un motivo per parlare quanto una brutta`() {
        val storia = storia(
            recent = listOf(giocata(19, voto = 8.4), giocata(18, voto = 6.5), giocata(17, voto = 6.8)),
        )

        val trigger = LeagueFacts.trigger(giocatore(morale = 75), storia, oggi)

        assertEquals(ConversationTopic.GRANDE_PRESTAZIONE, trigger?.topic)
    }

    // ------------------------------------------------- il difetto che si vedeva giocando

    @Test
    fun `chi e' appena arrivato non si lamenta di quanto gioca`() {
        // Il caso esatto che si vedeva in partita: comprato ieri, morale mediocre come
        // capita a chiunque, e il gioco gli faceva chiedere piu' spazio prima che avesse
        // messo piede in campo.
        val appena = storia(joinedOn = MatchDay(19), recent = emptyList())

        val trigger = LeagueFacts.trigger(giocatore(morale = 34), appena, oggi)

        assertEquals(ConversationTopic.NUOVO_ARRIVO, trigger?.topic)
    }

    @Test
    fun `chi sta bene e gioca non ha niente da dire`() {
        val storia = storia(
            recent = listOf(
                giocata(19, voto = 7.0, titolare = true),
                giocata(18, voto = 6.8, titolare = true),
                giocata(17, voto = 6.6, titolare = true),
            ),
        )

        assertNull(LeagueFacts.trigger(giocatore(morale = 72), storia, oggi))
    }

    // ------------------------------------------------------------------------ precedenze

    @Test
    fun `una promessa tradita viene prima di qualunque altra cosa`() {
        val storia = storia(
            brokenPromise = true,
            recent = listOf(panchina(19), panchina(18), panchina(17)),
        )

        val trigger = LeagueFacts.trigger(giocatore(morale = 12), storia, oggi)

        assertEquals(ConversationTopic.PROMESSA_TRADITA, trigger?.topic)
    }

    @Test
    fun `chi vuole andarsene lo dice prima di lamentarsi della panchina`() {
        val storia = storia(recent = listOf(panchina(19), panchina(18), panchina(17)))

        val trigger = LeagueFacts.trigger(giocatore(morale = 15), storia, oggi)

        assertEquals(ConversationTopic.RICHIESTA_CESSIONE, trigger?.topic)
    }

    @Test
    fun `il contratto in scadenza si segnala prima che scada`() {
        val storia = storia(
            contractEndsOn = MatchDay(24),
            recent = listOf(giocata(19, voto = 6.7, titolare = true)),
        )

        val trigger = LeagueFacts.trigger(giocatore(morale = 70), storia, oggi)

        assertEquals(ConversationTopic.CONTRATTO_IN_SCADENZA, trigger?.topic)
        assertTrue(trigger!!.cause.contains("4"), "deve dire quante giornate: ${trigger.cause}")
    }

    // ---------------------------------------------------------------- convocazione libera

    @Test
    fun `si puo' convocare chi non ha mai parlato`() {
        assertTrue(LeagueFacts.puoiConvocare(storia(), oggi))
    }

    @Test
    fun `non si puo' riconvocare subito`() {
        val appenaSentito = storia(lastConversationOn = MatchDay(19))

        assertTrue(!LeagueFacts.puoiConvocare(appenaSentito, oggi))
        assertEquals(2, LeagueFacts.attesaResidua(appenaSentito, oggi))
    }

    @Test
    fun `dopo l'attesa si puo' di nuovo`() {
        val vecchio = storia(lastConversationOn = MatchDay(17))

        assertTrue(LeagueFacts.puoiConvocare(vecchio, oggi))
        assertEquals(0, LeagueFacts.attesaResidua(vecchio, oggi))
    }

    // -------------------------------------------------------------- il rubinetto e' chiuso

    @Test
    fun `una convocazione a vuoto rende molto meno di un colloquio vero`() {
        val player = giocatore(morale = 50)
        val opzione = ConversationEngine
            .optionsFor(ConversationTopic.MORALE_BASSO)
            .first { it.tone == ConversationTone.INCORAGGIA }

        val vero = ConversationEngine.resolve(
            player, ConversationTopic.MORALE_BASSO, opzione, oggi, regole, spontanea = false,
        )
        val vuoto = ConversationEngine.resolve(
            player, ConversationTopic.MORALE_BASSO, opzione, oggi, regole, spontanea = true,
        )

        assertTrue(
            vuoto.moraleDelta * 2 <= vero.moraleDelta,
            "convocare a vuoto rendeva ${vuoto.moraleDelta} contro ${vero.moraleDelta}: " +
                "troppo simile, il rubinetto resta aperto",
        )
    }

    @Test
    fun `un ambizioso convocato per niente non la prende bene`() {
        val ambizioso = giocatore(morale = 60, traits = setOf(Trait.AMBIZIOSO))
        val opzione = ConversationEngine
            .optionsFor(ConversationTopic.MORALE_BASSO)
            .first { it.tone == ConversationTone.INCORAGGIA }

        val esito = ConversationEngine.resolve(
            ambizioso, ConversationTopic.MORALE_BASSO, opzione, oggi, regole, spontanea = true,
        )

        assertTrue(esito.moraleDelta <= 0, "un ambizioso chiamato a vuoto ha guadagnato ${esito.moraleDelta}")
    }

    @Test
    fun `nessun argomento offre due volte la stessa risposta`() {
        ConversationTopic.entries.forEach { topic ->
            val opzioni = ConversationEngine.optionsFor(topic)
            assertEquals(
                opzioni.size,
                opzioni.map { it.text }.toSet().size,
                "$topic ha due opzioni con lo stesso testo",
            )
        }
    }

    // ------------------------------------ con chi si e' appena parlato non si riparla

    /**
     * Il difetto misurato il 2026-08-26 sul registro del server: «40 colloqui aperti nello
     * spogliatoio, 40 colloqui gestiti dai club del computer», **a ogni giro, in ogni
     * lega**. Il tick apriva quaranta colloqui, l'AI li chiudeva nello stesso giro, e al
     * giro dopo si riapriva tutto da capo.
     *
     * `lastConversationOn` c'era, chi chiama lo calcolava e lo passava, e dentro `trigger`
     * non lo leggeva nessuno: l'attesa valeva solo per la convocazione a mano.
     */
    @Test
    fun `con chi si e appena parlato non si riapre un colloquio`() {
        val storia = storia(
            recent = listOf(panchina(19), panchina(18), panchina(17)),
            lastConversationOn = oggi,
        )
        assertNull(LeagueFacts.trigger(giocatore(morale = 60), storia, oggi))
    }

    @Test
    fun `passata l attesa, il discorso si riapre`() {
        val recente = listOf(panchina(19), panchina(18), panchina(17))
        val dopo = MatchDay(oggi.value + LeagueFacts.ATTESA_FRA_CONVOCAZIONI)

        assertNull(
            LeagueFacts.trigger(
                giocatore(morale = 60),
                storia(recent = recente, lastConversationOn = oggi),
                oggi,
            ),
        )
        assertEquals(
            ConversationTopic.PANCHINA_PROLUNGATA,
            LeagueFacts.trigger(
                giocatore(morale = 60),
                storia(recent = recente, lastConversationOn = oggi),
                dopo,
            )?.topic,
        )
    }

    /**
     * Vale **anche** per la promessa tradita, che e' la cosa piu' urgente che possa
     * capitare in uno spogliatoio. Urgente vuol dire *subito*, non *di nuovo fra cinque
     * minuti*: un torto di cui si torna a discutere a ogni giro non e' un torto che pesa,
     * e' un promemoria che si impara a ignorare.
     */
    @Test
    fun `nemmeno una promessa tradita riapre un colloquio appena chiuso`() {
        val storia = storia(brokenPromise = true, lastConversationOn = oggi)
        assertNull(LeagueFacts.trigger(giocatore(morale = 60), storia, oggi))
    }

    @Test
    fun `chi non ha mai parlato non aspetta niente`() {
        val storia = storia(
            recent = listOf(panchina(19), panchina(18), panchina(17)),
            lastConversationOn = null,
        )
        assertEquals(
            ConversationTopic.PANCHINA_PROLUNGATA,
            LeagueFacts.trigger(giocatore(morale = 60), storia, oggi)?.topic,
        )
    }

    // ------------------------------------------------------------------------- strumenti

    private val regole = dev.mfoot.core.config.ConfigPresets.classica().rules

    private fun storia(
        joinedOn: MatchDay = MatchDay(1),
        contractEndsOn: MatchDay = MatchDay(60),
        isInjured: Boolean = false,
        recent: List<AppearanceFact> = emptyList(),
        brokenPromise: Boolean = false,
        isCaptain: Boolean = false,
        teamLosingStreak: Int = 0,
        lastConversationOn: MatchDay? = null,
    ) = PlayerHistory(
        playerId = PlayerId(1),
        joinedOn = joinedOn,
        contractEndsOn = contractEndsOn,
        isInjured = isInjured,
        recent = recent,
        brokenPromise = brokenPromise,
        isCaptain = isCaptain,
        teamLosingStreak = teamLosingStreak,
        lastConversationOn = lastConversationOn,
    )

    private fun panchina(giornata: Int) =
        AppearanceFact(MatchDay(giornata), started = false, minutes = 0, rating = 0.0)

    private fun giocata(giornata: Int, voto: Double, titolare: Boolean = true, gol: Int = 0) =
        AppearanceFact(MatchDay(giornata), titolare, minutes = 90, rating = voto, goals = gol)

    private fun giocatore(morale: Int, traits: Set<Trait> = emptySet()): Player =
        Player(
            id = PlayerId(1),
            firstName = "Aldo",
            lastName = "Rossi",
            age = 26,
            nationality = "IT",
            primaryPosition = Position.CC,
            attributes = Attributes.uniform(60),
            potentialMin = 60,
            potentialMax = 70,
            morale = morale,
            traits = traits,
        )
}
