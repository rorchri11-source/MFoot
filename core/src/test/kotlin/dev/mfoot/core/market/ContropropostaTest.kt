package dev.mfoot.core.market

import dev.mfoot.core.ai.AiPersonality
import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Non a queste condizioni, ma cosi' si'».
 *
 * ## Perche' serve
 *
 * Senza controproposta una trattativa e' prendere o lasciare, e quasi ogni proposta finisce
 * in un no secco che non insegna niente: chi lo riceve non sa se ha sbagliato di poco o di
 * tanto, e riprova alla cieca o smette di provarci.
 */
class ContropropostaTest {

    private val config = ConfigPresets.classica()
    private val mio = ClubId(1)
    private val altro = ClubId(2)

    private fun personalita(aggressivita: Double = 0.5) = AiPersonality(
        clubId = altro,
        marketAggression = aggressivita,
        youthPreference = 0.5,
        budgetDiscipline = 0.5,
        patience = 0.5,
        obsessions = emptySet(),
        activeFromHour = 9,
        activeToHour = 13,
        checksPerDay = 3,
    )

    private fun giocatore(id: Int, overall: Int, ruolo: Position = Position.CC) = Player(
        id = PlayerId(id.toLong()),
        firstName = "Gio",
        lastName = "N$id",
        nationality = "Italia",
        age = 25,
        primaryPosition = ruolo,
        attributes = Attributes.uniform(overall),
        potentialMin = overall,
        potentialMax = overall,
    )

    /** Una rosa larga, con un portiere di scorta: nessun rifiuto per ruolo scoperto. */
    private fun rosa(target: Player): List<Player> =
        listOf(target) +
            (1..3).map { giocatore(500 + it, 60, Position.POR) } +
            (1..14).map { giocatore(600 + it, 62) }

    @Test
    fun `a un'offerta troppo bassa risponde con la cifra che basterebbe`() {
        val mioPezzo = giocatore(10, 80)
        val suoPezzo = giocatore(20, 66)

        val offerta = TradeOffer(
            from = mio, to = altro,
            offered = listOf(suoPezzo.id),
            wanted = listOf(mioPezzo.id),
            cash = 0,
        )

        val contro = TradeEvaluator.counter(
            offer = offerta,
            personality = personalita(),
            squad = rosa(mioPezzo),
            availableCredits = 200_000,
            config = config,
            offeredValues = mapOf(suoPezzo.id to Valuation.marketValue(suoPezzo, config)),
        )

        assertNotNull(contro, "ha detto no senza dire quanto mancava")
        assertTrue(contro.cash < 0, "una controproposta che non chiede denaro non chiede niente")
    }

    @Test
    fun `la controproposta arriva dall'altra parte`() {
        // E' una proposta nuova nella direzione opposta: deve finire nella casella
        // "ricevute" di chi aveva proposto, non restare in quella di chi risponde.
        val mioPezzo = giocatore(11, 80)
        val suoPezzo = giocatore(21, 66)

        val offerta = TradeOffer(
            from = mio, to = altro,
            offered = listOf(suoPezzo.id),
            wanted = listOf(mioPezzo.id),
        )

        val contro = assertNotNull(
            TradeEvaluator.counter(
                offerta, personalita(), rosa(mioPezzo), 200_000, config,
                mapOf(suoPezzo.id to Valuation.marketValue(suoPezzo, config)),
            ),
        )

        assertEquals(altro, contro.from)
        assertEquals(mio, contro.to)
        assertEquals(listOf(mioPezzo.id), contro.offered)
        assertEquals(listOf(suoPezzo.id), contro.wanted)
    }

    @Test
    fun `la cifra chiesta rende l'affare accettabile davvero`() {
        // La prova che conta: se accetto la sua controproposta, la accetterebbe.
        val mioPezzo = giocatore(12, 82)
        val suoPezzo = giocatore(22, 64)
        val squadra = rosa(mioPezzo)
        val valori = mapOf(suoPezzo.id to Valuation.marketValue(suoPezzo, config))

        val offerta = TradeOffer(
            from = mio, to = altro,
            offered = listOf(suoPezzo.id),
            wanted = listOf(mioPezzo.id),
        )

        val contro = assertNotNull(
            TradeEvaluator.counter(offerta, personalita(), squadra, 200_000, config, valori),
        )

        // Rigirata nella forma originale: gli stessi giocatori, con il denaro che ha
        // chiesto. E' quello che succede se chi ha ricevuto la controproposta la accetta.
        val riproposta = offerta.copy(cash = -contro.cash)
        val risposta = TradeEvaluator.evaluate(
            riproposta, personalita(), squadra, 200_000, config, valori,
        )

        assertEquals(
            TradeVerdict.ACCETTO,
            risposta.verdict,
            "ha chiesto una cifra e poi ha rifiutato lo stesso: ${risposta.reason}",
        )
    }

    @Test
    fun `a un affare gia' buono non si contropropone niente`() {
        val mioPezzo = giocatore(13, 60)
        val suoPezzo = giocatore(23, 85)

        val offerta = TradeOffer(
            from = mio, to = altro,
            offered = listOf(suoPezzo.id),
            wanted = listOf(mioPezzo.id),
        )

        assertNull(
            TradeEvaluator.counter(
                offerta, personalita(), rosa(mioPezzo), 200_000, config,
                mapOf(suoPezzo.id to Valuation.marketValue(suoPezzo, config)),
            ),
            "ha controproposto su uno scambio che avrebbe accettato",
        )
    }

    @Test
    fun `su un no che non riguarda il prezzo non si contratta`() {
        // Il portiere unico non si cede per nessuna cifra: restituire un numero
        // vorrebbe dire promettere un affare che poi si rifiuta comunque.
        val portiere = giocatore(14, 70, Position.POR)
        val suoPezzo = giocatore(24, 90)
        val squadra = listOf(portiere) + (1..17).map { giocatore(700 + it, 62) }

        val offerta = TradeOffer(
            from = mio, to = altro,
            offered = listOf(suoPezzo.id),
            wanted = listOf(portiere.id),
        )

        assertNull(
            TradeEvaluator.counter(
                offerta, personalita(), squadra, 200_000, config,
                mapOf(suoPezzo.id to Valuation.marketValue(suoPezzo, config)),
            ),
        )
    }
}
