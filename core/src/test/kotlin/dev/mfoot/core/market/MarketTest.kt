package dev.mfoot.core.market

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.config.MarketConfig
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Contract
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val T0: Instant = Instant.parse("2026-09-01T20:00:00Z")

private fun testPlayer(
    id: Long = 1L,
    overall: Int = 78,
    isCustom: Boolean = false,
) = Player(
    id = PlayerId(id),
    firstName = "Marco", lastName = "Ferrero", nationality = "Italia",
    age = 24,
    primaryPosition = Position.ATT,
    attributes = Attributes.uniform(overall),
    potentialMin = 82, potentialMax = 85,
    isCustom = isCustom,
)

class AuctionRulesTest {

    private val config = MarketConfig(
        auctionDurationMinutes = 60,
        minimumRaise = 1,
        antiSnipeEnabled = true,
        antiSnipeSeconds = 60,
    )

    private val clubA = ClubId(1)
    private val clubB = ClubId(2)
    private val clubC = ClubId(3)

    private fun newAuction() = AuctionRules.open(
        id = 1L,
        target = AuctionTarget.ForPlayer(PlayerId(1)),
        startedBy = clubA,
        now = T0,
        config = config,
    )

    private fun bid(
        auction: Auction,
        club: ClubId,
        max: Int,
        credits: Int = 1000,
        at: Instant = T0.plusSeconds(60),
    ): BidResult = AuctionRules.placeBid(auction, club, max, credits, at, config)

    private fun accepted(result: BidResult): BidResult.Accepted {
        assertTrue(result is BidResult.Accepted, "offerta rifiutata: ${(result as? BidResult.Rejected)?.reason}")
        return result
    }

    // --------------------------------------------------------- offerta massima

    /**
     * Il meccanismo che elimina la pressione di stare col telefono in mano: dichiari il
     * tuo limite e il prezzo sale solo quanto serve.
     */
    @Test
    fun `chi dichiara molto paga solo quanto serve per superare il secondo`() {
        var a = newAuction()
        a = accepted(bid(a, clubA, 30)).auction
        val result = accepted(bid(a, clubB, 12))

        assertEquals(clubA, result.leader, "deve restare in testa chi ha il massimo piu' alto")
        assertEquals(13, result.newPrice, "13 = 12 del secondo piu' il rilancio minimo")
    }

    @Test
    fun `con una sola offerta si paga il prezzo di partenza`() {
        val a = newAuction()
        val result = accepted(bid(a, clubA, 50))
        assertEquals(1, result.newPrice)
        assertEquals(clubA, result.leader)
    }

    @Test
    fun `il prezzo non supera mai il massimo del capofila`() {
        var a = newAuction()
        a = accepted(bid(a, clubA, 20)).auction
        val result = accepted(bid(a, clubB, 20, at = T0.plusSeconds(120)))

        assertEquals(clubA, result.leader, "a parita' di massimo vince chi ha offerto prima")
        assertTrue(result.newPrice <= 20)
    }

    @Test
    fun `a parita di massimo vince chi ha offerto prima`() {
        var a = newAuction()
        a = accepted(bid(a, clubA, 25, at = T0.plusSeconds(10))).auction
        val result = accepted(bid(a, clubB, 25, at = T0.plusSeconds(20)))
        assertEquals(clubA, result.leader)
    }

    @Test
    fun `il capofila puo alzare il proprio tetto senza far salire il prezzo`() {
        var a = newAuction()
        a = accepted(bid(a, clubA, 20)).auction
        a = accepted(bid(a, clubB, 10)).auction
        val prezzoPrima = a.currentPrice(config)

        val result = accepted(bid(a, clubA, 40, at = T0.plusSeconds(200)))
        assertEquals(prezzoPrima, result.newPrice, "alzare il proprio tetto non deve far salire il prezzo")
    }

    @Test
    fun `una terza offerta piu alta ribalta la leadership`() {
        var a = newAuction()
        a = accepted(bid(a, clubA, 30)).auction
        a = accepted(bid(a, clubB, 12)).auction
        val result = accepted(bid(a, clubC, 45, at = T0.plusSeconds(300)))

        assertEquals(clubC, result.leader)
        assertEquals(31, result.newPrice, "31 = i 30 di A piu' il rilancio minimo")
        assertEquals(clubA, result.outbidClub, "chi e' stato superato va avvisato")
    }

    // ------------------------------------------------------------- blocco fondi

    /**
     * Senza questo controllo un club vince cinque aste con i soldi per una, e a quel
     * punto la lega e' rotta e la serata finisce a litigare.
     */
    @Test
    fun `non si puo offrire piu di quanto si ha`() {
        val a = newAuction()
        val result = bid(a, clubA, 100, credits = 50)
        assertTrue(result is BidResult.Rejected)
        assertTrue(result.reason.contains("insufficienti"))
    }

    @Test
    fun `alzare il tetto impegna solo la differenza`() {
        var a = newAuction()
        a = accepted(bid(a, clubA, 40, credits = 50)).auction
        // Ha gia' 40 impegnati, gliene restano 10: portare il tetto a 48 costa 8.
        val result = bid(a, clubA, 48, credits = 10, at = T0.plusSeconds(120))
        assertTrue(result is BidResult.Accepted, "doveva bastare la differenza")
    }

    @Test
    fun `i crediti impegnati si sommano fra tutte le aste aperte`() {
        var a1 = newAuction()
        var a2 = AuctionRules.open(2L, AuctionTarget.ForPlayer(PlayerId(2)), clubA, T0, config)
        a1 = accepted(bid(a1, clubA, 30)).auction
        a2 = accepted(AuctionRules.placeBid(a2, clubA, 25, 1000, T0.plusSeconds(60), config)).auction

        assertEquals(55, AuctionRules.committedCredits(clubA, listOf(a1, a2)))
    }

    @Test
    fun `non si puo abbassare la propria offerta massima`() {
        var a = newAuction()
        a = accepted(bid(a, clubA, 40)).auction
        val result = bid(a, clubA, 20, at = T0.plusSeconds(120))
        assertTrue(result is BidResult.Rejected)
    }

    // ---------------------------------------------------------------- anti-snipe

    /**
     * Vince chi valuta di piu', non chi ha il dito piu' veloce.
     */
    @Test
    fun `un rilancio all'ultimo secondo prolunga l'asta`() {
        var a = newAuction()
        a = accepted(bid(a, clubA, 20)).auction
        val scadenzaPrima = a.endsAt

        val result = accepted(bid(a, clubB, 30, at = a.endsAt.minusSeconds(10)))

        assertTrue(result.extended, "l'anti-snipe non e' scattato")
        assertTrue(result.auction.endsAt.isAfter(scadenzaPrima))
        assertEquals(1, result.auction.extensions)
    }

    @Test
    fun `un rilancio a meta asta non la prolunga`() {
        var a = newAuction()
        val result = accepted(bid(a, clubA, 20, at = T0.plusSeconds(600)))
        assertTrue(!result.extended)
    }

    @Test
    fun `l'anti-snipe si puo disattivare`() {
        val senza = config.copy(antiSnipeEnabled = false)
        val a = AuctionRules.open(1L, AuctionTarget.ForPlayer(PlayerId(1)), clubA, T0, senza)
        val result = AuctionRules.placeBid(a, clubA, 20, 1000, a.endsAt.minusSeconds(5), senza)
        assertTrue(result is BidResult.Accepted)
        assertTrue(!result.extended)
    }

    // ------------------------------------------------------------------ chiusura

    @Test
    fun `l'asta si aggiudica a chi resta in testa`() {
        var a = newAuction()
        a = accepted(bid(a, clubA, 30)).auction
        a = accepted(bid(a, clubB, 18)).auction

        val outcome = AuctionRules.close(a, a.endsAt, config)

        assertEquals(clubA, outcome.winner)
        assertEquals(19, outcome.price)
        assertEquals(AuctionStatus.AGGIUDICATA, outcome.auction.status)
    }

    @Test
    fun `un'asta senza offerte resta deserta`() {
        // Un'asta davvero senza offerte esiste solo quando si **vende**: chi apre per
        // comprare parte in testa al prezzo base, quindi la sua non puo' essere vuota.
        val invenduto = AuctionRules.open(
            id = 9L,
            target = AuctionTarget.ForPlayer(PlayerId(9)),
            startedBy = clubA,
            now = T0,
            config = config,
            vendendo = true,
        )
        val outcome = AuctionRules.close(invenduto, T0.plusSeconds(7200), config)
        assertNull(outcome.winner)
        assertEquals(AuctionStatus.DESERTA, outcome.auction.status)
    }

    /**
     * Chi apre un'asta per comprare ha gia' offerto il prezzo base.
     *
     * Senza, l'asta nasceva senza nessuno in testa: se nessun altro la guardava scadeva
     * deserta e chi l'aveva aperta restava a mani vuote, avendo consumato uno dei suoi
     * slot per un'ora. Ed era proprio il caso piu' frequente, perche' un'asta la si apre
     * su chi si vuole.
     */
    @Test
    fun `chi apre un'asta per comprare e' subito in testa al prezzo base`() {
        val a = AuctionRules.open(
            id = 7L,
            target = AuctionTarget.ForPlayer(PlayerId(7)),
            startedBy = clubA,
            now = T0,
            config = config,
            startingPrice = 5,
        )

        assertEquals(clubA, a.leader)
        assertEquals(5, a.bidOf(clubA)?.maxAmount)
        assertEquals(5, a.currentPrice(config))

        // E se non la guarda nessuno, se lo prende lui invece di restare deserta.
        val outcome = AuctionRules.close(a, T0.plusSeconds(7200), config)
        assertEquals(clubA, outcome.winner)
        assertEquals(AuctionStatus.AGGIUDICATA, outcome.auction.status)
    }

    /** Chi vende non offre su se' stesso: quell'asta nasce vuota davvero. */
    @Test
    fun `chi mette in vendita un proprio giocatore non offre`() {
        val a = AuctionRules.open(
            id = 8L,
            target = AuctionTarget.ForPlayer(PlayerId(8)),
            startedBy = clubA,
            now = T0,
            config = config,
            vendendo = true,
        )

        assertNull(a.leader)
        assertNull(a.bidOf(clubA))
    }

    /**
     * Se i fondi non venissero liberati, i crediti resterebbero bloccati per sempre e i
     * club si troverebbero progressivamente senza soldi senza capire perche'.
     */
    @Test
    fun `alla chiusura tutti i partecipanti vanno liberati dai fondi impegnati`() {
        var a = newAuction()
        a = accepted(bid(a, clubA, 30)).auction
        a = accepted(bid(a, clubB, 18)).auction
        // Il prezzo e' gia' salito a 19, quindi C deve offrire almeno 20.
        a = accepted(bid(a, clubC, 24, at = T0.plusSeconds(180))).auction

        val outcome = AuctionRules.close(a, a.endsAt, config)
        assertEquals(setOf(clubA, clubB, clubC), outcome.clubsToRelease.toSet())
    }

    @Test
    fun `un'asta ancora aperta non si chiude`() {
        val outcome = AuctionRules.close(newAuction(), T0.plusSeconds(60), config)
        assertNull(outcome.winner)
        assertEquals(AuctionStatus.APERTA, outcome.auction.status)
    }

    @Test
    fun `dopo la scadenza non si puo piu offrire`() {
        val a = newAuction()
        val result = bid(a, clubA, 20, at = a.endsAt.plusSeconds(1))
        assertTrue(result is BidResult.Rejected)
    }

    @Test
    fun `annullare un'asta libera tutti`() {
        var a = newAuction()
        a = accepted(bid(a, clubA, 30)).auction
        val outcome = AuctionRules.cancel(a)
        assertEquals(AuctionStatus.ANNULLATA, outcome.auction.status)
        assertEquals(listOf(clubA), outcome.clubsToRelease)
    }

    // ------------------------------------------------------------ aste parallele

    @Test
    fun `un club non puo aprire piu aste del limite`() {
        val aperte = (1..3).map {
            AuctionRules.open(it.toLong(), AuctionTarget.ForPlayer(PlayerId(it.toLong())), clubA, T0, config)
        }
        assertNull(AuctionRules.canStartAuction(clubA, aperte.take(2), config))
        assertTrue(AuctionRules.canStartAuction(clubA, aperte, config) != null)
    }

    @Test
    fun `le aste altrui non contano nel proprio limite`() {
        val altrui = (1..5).map {
            AuctionRules.open(it.toLong(), AuctionTarget.ForPlayer(PlayerId(it.toLong())), clubB, T0, config)
        }
        assertNull(AuctionRules.canStartAuction(clubA, altrui, config))
    }
}

class NegotiationRulesTest {

    private val config = ConfigPresets.sprint().market
    private val buyer = ClubId(1)
    private val seller = ClubId(2)
    private val today = MatchDay(5)

    private fun terms(credits: Int = 15, clause: Int? = null) =
        OfferTerms(credits = credits, releaseClause = clause, contractMatchDays = 19)

    private fun openNegotiation(credits: Int = 15) = NegotiationRules.open(
        1L, testPlayer(), buyer, seller, terms(credits), 100, T0, config,
    )

    @Test
    fun `si apre una trattativa e la palla passa al venditore`() {
        val result = openNegotiation()
        assertTrue(result is NegotiationResult.Updated)
        assertEquals(seller, result.negotiation.awaiting)
        assertEquals(1, result.negotiation.rounds)
    }

    @Test
    fun `non si puo offrire piu di quanto si ha`() {
        val result = NegotiationRules.open(
            1L, testPlayer(), buyer, seller, terms(500), 100, T0, config,
        )
        assertTrue(result is NegotiationResult.Rejected)
    }

    /** Il ping-pong che l'utente aveva descritto: offro 15, ne voglio 20, 18, 17. */
    @Test
    fun `la controproposta alterna il turno e conserva lo storico`() {
        var n = (openNegotiation() as NegotiationResult.Updated).negotiation

        n = (NegotiationRules.counter(n, seller, terms(20), T0.plusSeconds(60), config)
            as NegotiationResult.Updated).negotiation
        assertEquals(buyer, n.awaiting)

        n = (NegotiationRules.counter(n, buyer, terms(18), T0.plusSeconds(120), config)
            as NegotiationResult.Updated).negotiation
        assertEquals(seller, n.awaiting)

        assertEquals(3, n.rounds)
        assertEquals(18, n.terms.credits)
        assertEquals(listOf(15, 20), n.history.map { it.credits })
    }

    @Test
    fun `non si puo rispondere quando non tocca a te`() {
        val n = (openNegotiation() as NegotiationResult.Updated).negotiation
        val result = NegotiationRules.counter(n, buyer, terms(20), T0.plusSeconds(60), config)
        assertTrue(result is NegotiationResult.Rejected)
    }

    @Test
    fun `accettare produce il contratto con i termini pattuiti`() {
        val n = (openNegotiation(17) as NegotiationResult.Updated).negotiation
        val result = NegotiationRules.accept(n, seller, today, T0.plusSeconds(60))

        assertTrue(result is NegotiationResult.Concluded)
        assertEquals(17, result.price)
        assertEquals(buyer, result.newContract.clubId)
        assertEquals(today, result.newContract.signedOn)
        assertEquals(today + 19, result.newContract.expiresOn)
    }

    /**
     * La clausola e' merce di scambio: si accetta meno soldi in cambio di un'opzione
     * futura sul giocatore, che e' proprio il ragionamento sensato sui giovani.
     */
    @Test
    fun `la clausola concordata finisce sul contratto`() {
        val n = NegotiationRules.open(
            1L, testPlayer(), buyer, seller, terms(17, clause = 25), 100, T0, config,
        ) as NegotiationResult.Updated

        val result = NegotiationRules.accept(n.negotiation, seller, today, T0.plusSeconds(60))
        assertTrue(result is NegotiationResult.Concluded)
        assertEquals(25, result.newContract.releaseClause)
    }

    @Test
    fun `una trattativa scaduta non si puo chiudere`() {
        val n = (openNegotiation() as NegotiationResult.Updated).negotiation
        val result = NegotiationRules.accept(n, seller, today, n.expiresAt.plusSeconds(1))
        assertTrue(result is NegotiationResult.Rejected)
    }

    @Test
    fun `una trattativa scaduta viene marcata come tale`() {
        val n = (openNegotiation() as NegotiationResult.Updated).negotiation
        assertEquals(OfferStatus.SCADUTA, NegotiationRules.expire(n).status)
    }

    @Test
    fun `rifiutare chiude la trattativa`() {
        val n = (openNegotiation() as NegotiationResult.Updated).negotiation
        val result = NegotiationRules.reject(n, seller) as NegotiationResult.Updated
        assertEquals(OfferStatus.RIFIUTATA, result.negotiation.status)
        assertTrue(!result.negotiation.isOpen)
    }

    // -------------------------------------------------------- clausola e custom

    @Test
    fun `pagare la clausola prende il giocatore senza trattativa`() {
        val contract = Contract(
            playerId = PlayerId(1), clubId = seller,
            signedOn = MatchDay(0), expiresOn = MatchDay(19),
            wagePerMatchDay = 2, pricePaid = 15, releaseClause = 25,
        )
        val result = NegotiationRules.triggerReleaseClause(
            testPlayer(), contract, buyer, 100, today, 19, config,
        )

        assertTrue(result is NegotiationResult.Concluded)
        assertEquals(25, result.price)
        assertEquals(buyer, result.newContract.clubId)
    }

    @Test
    fun `senza clausola non si puo forzare l'acquisto`() {
        val contract = Contract(
            playerId = PlayerId(1), clubId = seller,
            signedOn = MatchDay(0), expiresOn = MatchDay(19),
            wagePerMatchDay = 2, pricePaid = 15,
        )
        val result = NegotiationRules.triggerReleaseClause(
            testPlayer(), contract, buyer, 100, today, 19, config,
        )
        assertTrue(result is NegotiationResult.Rejected)
    }

    /** La regola che rende il giocatore del proprietario davvero suo. */
    @Test
    fun `il player custom non si puo comprare in nessun modo`() {
        val custom = testPlayer(isCustom = true)

        val trattativa = NegotiationRules.open(
            1L, custom, buyer, seller, terms(), 100, T0, config,
        )
        assertTrue(trattativa is NegotiationResult.Rejected)
        assertTrue(trattativa.reason.contains("prestato"))

        val contract = Contract(
            playerId = custom.id, clubId = seller,
            signedOn = MatchDay(0), expiresOn = MatchDay(19),
            wagePerMatchDay = 2, pricePaid = 15, releaseClause = 10,
        )
        assertTrue(
            NegotiationRules.triggerReleaseClause(custom, contract, buyer, 100, today, 19, config)
                is NegotiationResult.Rejected,
            "nemmeno la clausola deve poter strappare il giocatore del proprietario",
        )
    }
}

class ContractRulesTest {

    private val preset = ConfigPresets.sprint()
    private val economy = preset.economy
    private val market = preset.market
    private val rules = preset.rules
    private val today = MatchDay(10)

    private fun contract(price: Int = 40, expires: Int = 19) = Contract(
        playerId = PlayerId(1), clubId = ClubId(1),
        signedOn = MatchDay(0), expiresOn = MatchDay(expires),
        wagePerMatchDay = 2, pricePaid = price,
    )

    @Test
    fun `il rinnovo costa la frazione configurata`() {
        assertEquals(20, ContractRules.renewalCost(contract(40), economy))
    }

    @Test
    fun `il rinnovo riparte da oggi per la durata standard`() {
        val result = ContractRules.renew(
            testPlayer(), contract(), today, 100, economy, market, rules,
        )
        assertTrue(result is ContractAction.Renewed)
        assertEquals(today, result.contract.signedOn)
        assertEquals(today + market.defaultContractMatchDays, result.contract.expiresOn)
    }

    @Test
    fun `senza crediti il rinnovo non si fa`() {
        val result = ContractRules.renew(
            testPlayer(), contract(), today, 5, economy, market, rules,
        )
        assertTrue(result is ContractAction.Refused)
    }

    /** L'aggancio fra spogliatoio e mercato: il morale si paga davvero. */
    @Test
    fun `un giocatore scontento rifiuta il rinnovo`() {
        val scontento = testPlayer().withMorale(8)
        val result = ContractRules.renew(
            scontento, contract(), today, 1000, economy, market, rules,
        )
        assertTrue(result is ContractAction.Refused)
        assertTrue(result.reason.contains("morale"))
    }

    @Test
    fun `lo stipendio cresce piu che linearmente con l'overall`() {
        val scarso = ContractRules.wageFor(testPlayer(overall = 60), economy)
        val forte = ContractRules.wageFor(testPlayer(overall = 90), economy)
        assertTrue(forte > scarso * 2, "scarso $scarso, forte $forte")
    }

    @Test
    fun `con gli stipendi disattivati non si paga niente`() {
        val senza = economy.copy(wagesEnabled = false)
        assertEquals(0, ContractRules.wageFor(testPlayer(), senza))
    }

    @Test
    fun `il player custom non si puo svincolare`() {
        assertTrue(ContractRules.release(testPlayer(isCustom = true)) is ContractAction.Refused)
        assertTrue(ContractRules.release(testPlayer()) is ContractAction.Released)
    }

    @Test
    fun `i contratti scaduti si riconoscono per giornata`() {
        val contratti = listOf(contract(expires = 5), contract(expires = 15))
        assertEquals(1, ContractRules.expiredOn(contratti, MatchDay(10)).size)
        assertEquals(2, ContractRules.expiredOn(contratti, MatchDay(20)).size)
    }

    @Test
    fun `si sa in anticipo chi sta per scadere`() {
        val contratti = listOf(contract(expires = 12), contract(expires = 30))
        assertEquals(1, ContractRules.expiringWithin(contratti, MatchDay(10), 3).size)
    }

    // -------------------------------------------------------------- prestiti

    @Test
    fun `il prestito si concorda e costa il canone per le giornate`() {
        val result = ContractRules.agreeLoan(
            testPlayer(), ClubId(1), ClubId(2),
            from = today, matchDays = 5, feePerMatchDay = 3,
            borrowerAvailableCredits = 100, config = market,
        )
        assertTrue(result is ContractRules.LoanResult.Agreed)
        assertEquals(15, result.totalFee)
        assertEquals(today + 5, result.loan.endsOn)
    }

    @Test
    fun `senza crediti il prestito non si fa`() {
        val result = ContractRules.agreeLoan(
            testPlayer(), ClubId(1), ClubId(2),
            from = today, matchDays = 10, feePerMatchDay = 5,
            borrowerAvailableCredits = 20, config = market,
        )
        assertTrue(result is ContractRules.LoanResult.Refused)
    }

    @Test
    fun `la durata del prestito rispetta i limiti della lega`() {
        val troppoLungo = ContractRules.agreeLoan(
            testPlayer(), ClubId(1), ClubId(2),
            from = today, matchDays = 999, feePerMatchDay = 1,
            borrowerAvailableCredits = 10000, config = market,
        )
        assertTrue(troppoLungo is ContractRules.LoanResult.Refused)
    }

    /**
     * L'unica cosa che si puo' fare col giocatore del proprietario: mandarlo a giocare
     * titolare altrove per farlo crescere.
     */
    @Test
    fun `il player custom si puo prestare`() {
        val result = ContractRules.agreeLoan(
            testPlayer(isCustom = true), ClubId(1), ClubId(2),
            from = today, matchDays = 5, feePerMatchDay = 3,
            borrowerAvailableCredits = 100, config = market,
        )
        assertTrue(result is ContractRules.LoanResult.Agreed)
    }

    @Test
    fun `i prestiti scaduti si riconoscono per giornata`() {
        val loan = (ContractRules.agreeLoan(
            testPlayer(), ClubId(1), ClubId(2),
            from = MatchDay(5), matchDays = 5, feePerMatchDay = 1,
            borrowerAvailableCredits = 100, config = market,
        ) as ContractRules.LoanResult.Agreed).loan

        assertEquals(0, ContractRules.expiredLoans(listOf(loan), MatchDay(9)).size)
        assertEquals(1, ContractRules.expiredLoans(listOf(loan), MatchDay(10)).size)
    }

    @Test
    fun `il canone suggerito cresce col valore e cala con la durata`() {
        val breve = ContractRules.suggestedLoanFee(100, 3)
        val lungo = ContractRules.suggestedLoanFee(100, 30)
        assertTrue(breve > lungo, "breve $breve, lungo $lungo")
        assertTrue(ContractRules.suggestedLoanFee(200, 5) > ContractRules.suggestedLoanFee(50, 5))
    }
}

class ValuationTest {

    private val config = ConfigPresets.sprint()

    @Test
    fun `un fuoriclasse costa molto piu di un buon giocatore`() {
        val buono = Valuation.marketValue(testPlayer(overall = 75), config)
        val top = Valuation.marketValue(testPlayer(overall = 90), config)
        assertTrue(top > buono * 2, "buono $buono, top $top")
    }

    @Test
    fun `i prezzi si adattano al budget della lega`() {
        val ricca = config.copy(economy = config.economy.copy(startingCredits = 1000))
        val povera = config.copy(economy = config.economy.copy(startingCredits = 100))

        val prezzoRicco = Valuation.marketValue(testPlayer(overall = 88), ricca)
        val prezzoPovero = Valuation.marketValue(testPlayer(overall = 88), povera)

        assertTrue(prezzoRicco > prezzoPovero * 5, "e' il motivo per cui l'AI ragiona in percentuale")
    }

    @Test
    fun `a parita di overall il piu giovane vale di piu`() {
        val giovane = testPlayer(overall = 80).copy(age = 22, potentialMin = 88, potentialMax = 90)
        val maturo = testPlayer(overall = 80).copy(age = 30, potentialMin = 80, potentialMax = 80)
        assertTrue(
            Valuation.marketValue(giovane, config) > Valuation.marketValue(maturo, config),
        )
    }

    @Test
    fun `un vecchio con potenziale alto non vale di piu`() {
        val vecchio = testPlayer(overall = 75).copy(age = 34, potentialMin = 90, potentialMax = 92)
        val vecchioSenzaMargine = testPlayer(overall = 75).copy(age = 34, potentialMin = 75, potentialMax = 75)
        assertEquals(
            Valuation.marketValue(vecchioSenzaMargine, config),
            Valuation.marketValue(vecchio, config),
            "a 34 anni il potenziale residuo non e' credibile e non si deve pagare",
        )
    }

    @Test
    fun `nessuno vale meno di un credito`() {
        assertTrue(Valuation.marketValue(testPlayer(overall = 40), config) >= 1)
    }

    /** Il valore stimato usa la forbice, non i valori veri: e' la funzione che usa l'AI. */
    @Test
    fun `la stima ottimista vale piu di quella pessimista`() {
        val giovane = testPlayer(overall = 68).copy(age = 20, potentialMin = 85, potentialMax = 88)
        val ottimista = Valuation.estimatedValue(giovane, 84..90, config)
        val pessimista = Valuation.estimatedValue(giovane, 68..72, config)
        assertTrue(ottimista > pessimista)
    }
}
