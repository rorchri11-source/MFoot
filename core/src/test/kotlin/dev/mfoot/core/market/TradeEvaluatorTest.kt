package dev.mfoot.core.market

import dev.mfoot.core.ai.AiPersonalityGenerator
import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Come un club gestito dal computer risponde a una proposta di scambio.
 *
 * ## Perche' questi test contano
 *
 * Un'AI che accetta ogni scambio conveniente al centesimo diventa un bancomat: in due
 * giorni si capisce come spremerla, e la lega perde senso. Un'AI che rifiuta tutto e'
 * peggio ancora — tanto vale non avere gli scambi. La distanza fra le due cose e' fatta di
 * numeri, e i numeri vanno fissati qui invece che scoperti quando qualcuno si e' gia'
 * preso mezzo campionato con una proposta furba.
 */
class TradeEvaluatorTest {

    private val config = ConfigPresets.sprint(10, 8, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    private val io = ClubId(1)
    private val altro = ClubId(2)

    /** Il carattere piu' esigente: quello che chiede il margine piu' alto. */
    private val prudente = AiPersonalityGenerator
        .generate(ClubId(1), config.setup.worldSeed, config.ai)
        .copy(marketAggression = 0.0)

    /** Il carattere che si accontenta di poco. */
    private val spregiudicato = prudente.copy(marketAggression = 1.0)

    /** Una rosa completa e sensata: portieri, difensori, centrocampisti, attaccanti. */
    private fun rosa(): List<Player> {
        val voluti = listOf(
            Position.POR to 2, Position.TD to 2, Position.DC to 4, Position.TS to 2,
            Position.MED to 2, Position.CC to 3, Position.AD to 2, Position.AS to 2,
            Position.ATT to 3,
        )
        val usati = mutableSetOf<Long>()
        return voluti.flatMap { (ruolo, quanti) ->
            world.players
                .filter { it.primaryPosition == ruolo && it.id.value !in usati }
                .take(quanti)
                .onEach { usati += it.id.value }
        }
    }

    private fun valore(p: Player) = Valuation.marketValue(p, config)

    private fun rispondi(
        offer: TradeOffer,
        squad: List<Player> = rosa(),
        credits: Int = 50_000,
        personality: dev.mfoot.core.ai.AiPersonality = spregiudicato,
        offerti: List<Player> = emptyList(),
    ) = TradeEvaluator.evaluate(
        offer = offer,
        personality = personality,
        squad = squad,
        availableCredits = credits,
        config = config,
        offeredValues = offerti.associate { it.id to valore(it) },
    )

    // ------------------------------------------------------------------ il caso normale

    /**
     * Uno scambio generoso si accetta.
     *
     * Se nemmeno il doppio del valore basta, gli scambi non esistono e la funzione e'
     * decorativa.
     */
    @Test
    fun `una proposta molto conveniente viene accettata`() {
        val squad = rosa()
        val mio = squad.first { it.primaryPosition == Position.CC }
        val suo = world.players.first { it.id !in squad.map(Player::id) && it.overall > mio.overall }

        val risposta = rispondi(
            TradeOffer(from = altro, to = io, offered = listOf(suo.id), wanted = listOf(mio.id)),
            squad = squad,
            offerti = listOf(suo),
        )

        assertTrue(risposta.accepted, "rifiutata: ${risposta.reason}")
    }

    /**
     * Uno scambio alla pari secca si rifiuta.
     *
     * E' il caso che separa un'AI ragionevole da un bancomat: cambiare due giocatori
     * equivalenti e' un fastidio senza guadagno, e nessuno lo farebbe davvero.
     */
    @Test
    fun `uno scambio alla pari non basta`() {
        val squad = rosa()
        val mio = squad.first { it.primaryPosition == Position.CC }
        // Un giocatore di valore quasi identico, preso da fuori rosa.
        val suo = world.players
            .filter { it.id !in squad.map(Player::id) }
            .minBy { kotlin.math.abs(valore(it) - valore(mio)) }

        val risposta = rispondi(
            TradeOffer(from = altro, to = io, offered = listOf(suo.id), wanted = listOf(mio.id)),
            squad = squad,
            offerti = listOf(suo),
        )

        assertFalse(risposta.accepted, "accettata alla pari: e' un bancomat")
        assertEquals(TradeVerdict.VALORE_INSUFFICIENTE, risposta.verdict)
    }

    /** Il carattere decide quanto margine serve, e la differenza si deve vedere. */
    @Test
    fun `il prudente chiede piu' del spregiudicato`() {
        val squad = rosa()
        val mio = squad.first { it.primaryPosition == Position.CC }
        val candidati = world.players.filter { it.id !in squad.map(Player::id) }

        // Il valore minimo che fa accettare, per i due caratteri.
        fun sogliaDi(p: dev.mfoot.core.ai.AiPersonality): Int =
            candidati.sortedBy { valore(it) }.first { suo ->
                rispondi(
                    TradeOffer(from = altro, to = io, offered = listOf(suo.id), wanted = listOf(mio.id)),
                    squad = squad,
                    personality = p,
                    offerti = listOf(suo),
                ).accepted
            }.let(::valore)

        assertTrue(
            sogliaDi(prudente) > sogliaDi(spregiudicato),
            "i due caratteri chiedono la stessa cifra: la personalita' non conta",
        )
    }

    // ------------------------------------------------------------- i rifiuti di principio

    /**
     * Sotto il minimo di rosa non si scende **per nessuna cifra**.
     *
     * Una squadra sotto il minimo non gioca, e le sue partite si rinviano. Accettare un
     * affare d'oro che porta li' significa vincere lo scambio e perdere il campionato: e'
     * il genere di scelta che nessuna persona farebbe e che un'AI ingenua fa volentieri.
     */
    @Test
    fun `non si accetta uno scambio che porta sotto il minimo di rosa`() {
        val minima = rosa().take(config.setup.minSquadSize)
        val due = minima.filter { !it.primaryPosition.isGoalkeeper }.take(2)
        val fuoriclasse = world.players
            .filter { it.id !in minima.map(Player::id) }
            .maxBy { it.overall }

        val risposta = rispondi(
            TradeOffer(
                from = altro,
                to = io,
                offered = listOf(fuoriclasse.id),
                wanted = due.map { it.id },
            ),
            squad = minima,
            offerti = listOf(fuoriclasse),
        )

        assertEquals(TradeVerdict.ROSA_TROPPO_CORTA, risposta.verdict, risposta.reason)
    }

    /** L'ultimo portiere non si cede: senza, si prendono quattro gol a partita. */
    @Test
    fun `l ultimo portiere non si cede per nessuna cifra`() {
        val conUnPortiere = rosa().filter { !it.primaryPosition.isGoalkeeper } +
            rosa().first { it.primaryPosition.isGoalkeeper }
        val portiere = conUnPortiere.first { it.primaryPosition.isGoalkeeper }
        val fuoriclasse = world.players
            .filter { it.id !in conUnPortiere.map(Player::id) }
            .maxBy { it.overall }

        val risposta = rispondi(
            TradeOffer(
                from = altro,
                to = io,
                offered = listOf(fuoriclasse.id),
                wanted = listOf(portiere.id),
            ),
            squad = conUnPortiere,
            offerti = listOf(fuoriclasse),
        )

        assertEquals(TradeVerdict.NON_VENDO, risposta.verdict, risposta.reason)
    }

    /** Con due portieri se ne puo' cedere uno: il divieto vale per l'ultimo, non per il ruolo. */
    @Test
    fun `con due portieri uno si puo' cedere`() {
        val squad = rosa()
        val portiere = squad.first { it.primaryPosition.isGoalkeeper }
        val fuoriclasse = world.players
            .filter { it.id !in squad.map(Player::id) }
            .maxBy { it.overall }

        val risposta = rispondi(
            TradeOffer(
                from = altro,
                to = io,
                offered = listOf(fuoriclasse.id),
                wanted = listOf(portiere.id),
            ),
            squad = squad,
            offerti = listOf(fuoriclasse),
        )

        assertTrue(
            risposta.verdict != TradeVerdict.NON_VENDO,
            "rifiutato per il ruolo pur avendo due portieri: ${risposta.reason}",
        )
    }

    @Test
    fun `non si accetta di pagare piu' di quanto si ha`() {
        val squad = rosa()
        val mio = squad.first { it.primaryPosition == Position.CC }
        val suo = world.players.filter { it.id !in squad.map(Player::id) }.maxBy { it.overall }

        val risposta = rispondi(
            TradeOffer(
                from = altro,
                to = io,
                offered = listOf(suo.id),
                wanted = listOf(mio.id),
                // Chiedono a me trenta milioni, ne ho uno.
                cash = -30_000,
            ),
            squad = squad,
            credits = 1_000,
            offerti = listOf(suo),
        )

        assertEquals(TradeVerdict.SOLDI_INSUFFICIENTI, risposta.verdict, risposta.reason)
    }

    /**
     * Un giocatore che non ho piu' non si cede: la proposta e' vecchia.
     *
     * Succede davvero — si propone uno scambio, l'altro nel frattempo vende quel giocatore
     * all'asta — e accettarla a meta' produrrebbe uno scambio che nessuno dei due ha
     * concordato.
     */
    @Test
    fun `una proposta su un giocatore che non ho piu' viene rifiutata`() {
        val squad = rosa()
        val estraneo = world.players.first { it.id !in squad.map(Player::id) }
        val suo = world.players.filter { it.id !in squad.map(Player::id) }.maxBy { it.overall }

        val risposta = rispondi(
            TradeOffer(
                from = altro,
                to = io,
                offered = listOf(suo.id),
                wanted = listOf(estraneo.id),
            ),
            squad = squad,
            offerti = listOf(suo),
        )

        assertEquals(TradeVerdict.NON_VENDO, risposta.verdict, risposta.reason)
    }

    @Test
    fun `una proposta vuota viene rifiutata`() {
        val risposta = rispondi(TradeOffer(from = altro, to = io))
        assertFalse(risposta.accepted)
    }

    // ------------------------------------------------------------------------- soldi

    /** Aggiungere denaro rende una proposta accettabile: e' il senso del conguaglio. */
    @Test
    fun `il conguaglio in denaro puo' chiudere l affare`() {
        val squad = rosa()
        val mio = squad.first { it.primaryPosition == Position.CC }
        val suo = world.players
            .filter { it.id !in squad.map(Player::id) }
            .minBy { kotlin.math.abs(valore(it) - valore(mio)) }

        val secco = TradeOffer(
            from = altro, to = io,
            offered = listOf(suo.id), wanted = listOf(mio.id),
        )
        assertFalse(rispondi(secco, squad = squad, offerti = listOf(suo)).accepted)

        // Lo stesso scambio con il doppio del valore in contanti sopra.
        val conSoldi = secco.copy(cash = valore(mio))
        assertTrue(
            rispondi(conSoldi, squad = squad, offerti = listOf(suo)).accepted,
            "nemmeno raddoppiando in contanti l'affare si chiude",
        )
    }

    // ------------------------------------------------------------------------ vincoli

    @Test
    fun `un club non puo' scambiare con se stesso`() {
        val errore = runCatching { TradeOffer(from = io, to = io) }.exceptionOrNull()
        assertTrue(errore is IllegalArgumentException)
    }

    @Test
    fun `il conteggio della rosa dopo lo scambio guarda i due lati`() {
        val offerta = TradeOffer(
            from = altro,
            to = io,
            offered = listOf(PlayerId(1), PlayerId(2)),
            wanted = listOf(PlayerId(3)),
        )

        // Chi riceve guadagna un giocatore, chi propone ne perde uno.
        assertEquals(1, offerta.squadDeltaFor(io))
        assertEquals(-1, offerta.squadDeltaFor(altro))
        assertEquals(0, offerta.squadDeltaFor(ClubId(99)))
    }
}
