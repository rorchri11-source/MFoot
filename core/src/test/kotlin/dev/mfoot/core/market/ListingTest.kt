package dev.mfoot.core.market

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Il mercato a prezzo fisso e la finestra di contestazione.
 *
 * Le regole vivono qui perche' le usano **tre posti diversi**: l'app quando compri, il
 * database quando registra, il tick quando la finestra si chiude. La lezione e' gia'
 * stata pagata con l'apertura delle aste — la stessa regola scritta in SQL e in Kotlin si
 * era separata, e un'asta nasceva con un'offerta da una parte e senza dall'altra.
 */
class ListingTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
    private val ora = Instant.parse("2026-08-25T21:00:00Z")

    private val venditore = ClubId(1)
    private val compratore = ClubId(2)
    private val terzo = ClubId(3)

    private fun giocatore(id: Long = 10, custom: Boolean = false) = Player(
        id = PlayerId(id),
        firstName = "M",
        lastName = "Ferrara",
        nationality = "it",
        age = 24,
        primaryPosition = Position.ATT,
        attributes = Attributes.uniform(70),
        potentialMin = 70,
        potentialMax = 78,
        isCustom = custom,
    )

    private fun listing(prezzo: Int = 34, seller: ClubId? = venditore) = Listing(
        id = 1,
        playerId = PlayerId(10),
        seller = seller,
        price = prezzo,
        listedAt = ora,
    )

    private fun compra(
        prezzo: Int = 34,
        crediti: Int = 100,
        rosa: Int = 18,
        seller: ClubId? = venditore,
    ): BuyResult = PurchaseRules.buy(
        id = 1,
        listing = listing(prezzo, seller),
        buyer = compratore,
        availableCredits = crediti,
        squadSize = rosa,
        now = ora,
        config = config,
    )

    // ------------------------------------------------------------------- il listino

    @Test
    fun `il giocatore custom non si mette in vendita`() {
        val motivo = ListingRules.rejection(giocatore(custom = true), 40, config)
        assertTrue(motivo != null && motivo.contains("non si vende"))
    }

    @Test
    fun `un prezzo sotto il minimo non passa`() {
        assertTrue(ListingRules.rejection(giocatore(), 0, config) != null)
        assertNull(ListingRules.rejection(giocatore(), 1, config))
    }

    @Test
    fun `il prezzo e' libero fino a tutto il budget`() {
        // Il prezzo lo scrive il proprietario: nessun tetto, nessuna forchetta. E' la
        // decisione del 2026-08-24, e il correttivo e' la contestazione, non un limite.
        assertNull(ListingRules.rejection(giocatore(), 9_999, config))
    }

    // ------------------------------------------------------------------- l'acquisto

    @Test
    fun `si compra e il giocatore e' subito tuo`() {
        val esito = compra()
        assertTrue(esito is BuyResult.Done)
        esito as BuyResult.Done

        assertEquals(compratore, esito.purchase.buyer)
        assertEquals(34, esito.purchase.price)
        assertEquals(ListingStatus.VENDUTO, esito.listing.status)
        assertEquals(PurchaseStatus.IN_FINESTRA, esito.purchase.status)
    }

    @Test
    fun `la finestra dura quanto dice la configurazione, e si sa da subito`() {
        val esito = compra() as BuyResult.Done
        val attesa = ora.plusSeconds(config.market.contestWindowHours * 3600L)

        assertEquals(attesa, esito.purchase.contestableUntil)
        assertEquals(12 * 3600L, esito.purchase.secondsLeft(ora))
    }

    @Test
    fun `senza crediti non si compra`() {
        val esito = compra(prezzo = 80, crediti = 40)
        assertTrue(esito is BuyResult.Rejected && esito.reason.contains("servono"))
    }

    @Test
    fun `con la rosa piena non si compra`() {
        val esito = compra(rosa = config.setup.maxSquadSize)
        assertTrue(esito is BuyResult.Rejected && esito.reason.contains("liberare un posto"))
    }

    @Test
    fun `non si compra da se' stessi`() {
        val esito = PurchaseRules.buy(
            id = 1,
            listing = listing(seller = compratore),
            buyer = compratore,
            availableCredits = 100,
            squadSize = 18,
            now = ora,
            config = config,
        )
        assertTrue(esito is BuyResult.Rejected)
    }

    @Test
    fun `con l'acquisto immediato spento si torna alle aste`() {
        val spento = config.copy(market = config.market.copy(instantBuyEnabled = false))
        val esito = PurchaseRules.buy(
            id = 1, listing = listing(), buyer = compratore,
            availableCredits = 100, squadSize = 18, now = ora, config = spento,
        )
        assertTrue(esito is BuyResult.Rejected && esito.reason.contains("all'asta"))
    }

    // --------------------------------------------------------------- la contestazione

    private fun acquisto(): Purchase = (compra() as BuyResult.Done).purchase

    @Test
    fun `chi ha comprato e' gia' in testa, al prezzo che ha pagato`() {
        val esito = ContestRules.open(
            auctionId = 7, purchase = acquisto(), contestante = terzo,
            maxAmount = 50, now = ora.plusSeconds(600),
        )
        assertTrue(esito is ContestResult.Opened)
        esito as ContestResult.Opened

        val offerta = esito.auction.bidOf(compratore)
        assertTrue(offerta != null, "chi ha comprato deve entrare nell'asta senza rioffrire")
        assertEquals(34, offerta.maxAmount)
    }

    /** Il rilancio minimo della lega: i prezzi dei test si scalano su questo. */
    private val rilancio = config.market.minimumRaise

    @Test
    fun `il prezzo sale solo quanto serve, e il massimo resta segreto`() {
        val esito = ContestRules.open(
            auctionId = 7, purchase = acquisto(), contestante = terzo,
            // Un massimo molto alto: quello che conta e' che non lo paghi tutto.
            maxAmount = 34 + rilancio * 10, now = ora.plusSeconds(600),
        ) as ContestResult.Opened

        assertEquals(terzo, esito.auction.leader)
        assertEquals(
            34 + rilancio, esito.auction.currentPrice(config.market),
            "chi contesta deve pagare un rilancio sopra il prezzo pagato, non il proprio massimo",
        )
    }

    @Test
    fun `l'asta di contestazione scade insieme alla finestra`() {
        val acquisto = acquisto()
        val esito = ContestRules.open(
            auctionId = 7, purchase = acquisto, contestante = terzo,
            maxAmount = 50, now = ora.plusSeconds(3600),
        ) as ContestResult.Opened

        assertEquals(
            acquisto.contestableUntil, esito.auction.endsAt,
            "chi compra deve sapere fin dall'inizio l'ora in cui il giocatore è suo",
        )
    }

    @Test
    fun `contestare costa almeno un rilancio sopra il prezzo pagato`() {
        val acquisto = acquisto()
        val minimo = ContestRules.minimumContest(acquisto, config.market)
        assertEquals(34 + config.market.minimumRaise, minimo)

        val troppoPoco = ContestRules.rejection(
            acquisto, terzo, maxAmount = 34, availableCredits = 100,
            squadSize = 18, now = ora, config = config,
        )
        assertTrue(troppoPoco != null && troppoPoco.contains("almeno"))
    }

    @Test
    fun `chi ha comprato non contesta se stesso, e chi ha venduto nemmeno`() {
        val acquisto = acquisto()

        assertTrue(
            ContestRules.rejection(
                acquisto, compratore, 60, 100, 18, ora, config,
            )?.contains("sei già in testa") == true,
        )
        assertTrue(
            ContestRules.rejection(
                acquisto, venditore, 60, 100, 18, ora, config,
            )?.contains("venduto") == true,
        )
    }

    @Test
    fun `passata la finestra non si contesta piu'`() {
        val acquisto = acquisto()
        val tardi = acquisto.contestableUntil.plusSeconds(1)

        assertTrue(
            ContestRules.rejection(acquisto, terzo, 60, 100, 18, tardi, config)
                ?.contains("finito") == true,
        )
    }

    @Test
    fun `senza contestazioni si conferma e non si muove un credito`() {
        val esito = ContestRules.settle(acquisto(), null, ora.plusSeconds(13 * 3600), config.market)

        assertTrue(esito is ContestRules.Settlement.Confermato)
        assertEquals(PurchaseStatus.CONFERMATO, esito.purchase.status)
        assertEquals(0, (esito as ContestRules.Settlement.Confermato).extraDaPagare)
    }

    @Test
    fun `se chi ha comprato tiene il giocatore paga solo la differenza`() {
        val acquisto = acquisto()
        val contestazione = 34 + rilancio

        // Il terzo contesta al minimo; chi ha comprato rilancia molto piu' su e resta in
        // testa, ma paghera' solo quanto serve a superare il rivale.
        val aperta = ContestRules.open(
            7, acquisto, terzo, contestazione, ora.plusSeconds(60),
        ) as ContestResult.Opened

        val conRilancio = AuctionRules.placeBid(
            auction = aperta.auction, club = compratore,
            maxAmount = contestazione + rilancio * 5,
            availableCredits = rilancio * 100, now = ora.plusSeconds(120),
            config = config.market,
        )
        assertTrue(conRilancio is BidResult.Accepted, "il rilancio di chi ha comprato è stato rifiutato")

        val esito = ContestRules.settle(
            aperta.purchase,
            (conRilancio as BidResult.Accepted).auction,
            acquisto.contestableUntil.plusSeconds(1),
            config.market,
        )

        assertTrue(esito is ContestRules.Settlement.Confermato)
        esito as ContestRules.Settlement.Confermato
        assertEquals(
            contestazione + rilancio - 34, esito.extraDaPagare,
            "chi tiene il giocatore deve pagare la differenza, non il prezzo intero",
        )
    }

    @Test
    fun `se lo perde riprende i crediti interi`() {
        val acquisto = acquisto()
        val aperta = ContestRules.open(
            7, acquisto, terzo, maxAmount = 90, now = ora.plusSeconds(60),
        ) as ContestResult.Opened

        val esito = ContestRules.settle(
            aperta.purchase, aperta.auction,
            acquisto.contestableUntil.plusSeconds(1), config.market,
        )

        assertTrue(esito is ContestRules.Settlement.Revocato)
        esito as ContestRules.Settlement.Revocato
        assertEquals(terzo, esito.vincitore)
        assertEquals(
            34, esito.daRimborsare,
            "ha perso il giocatore, non i soldi: i crediti tornano interi",
        )
        assertEquals(PurchaseStatus.REVOCATO, esito.purchase.status)
    }

    @Test
    fun `una sola asta per acquisto`() {
        val acquisto = acquisto()
        val prima = ContestRules.open(7, acquisto, terzo, 50, ora) as ContestResult.Opened

        val seconda = ContestRules.open(8, prima.purchase, ClubId(4), 60, ora)
        assertTrue(
            seconda is ContestResult.Rejected,
            "il secondo che contesta entra nell'asta aperta, non ne apre un'altra",
        )
    }

    @Test
    fun `con la finestra a zero ore non si contesta nulla`() {
        val senzaFinestra = config.copy(market = config.market.copy(contestWindowHours = 0))
        val acquisto = (
            PurchaseRules.buy(
                1, listing(), compratore, 100, 18, ora, senzaFinestra,
            ) as BuyResult.Done
            ).purchase

        assertTrue(
            ContestRules.rejection(acquisto, terzo, 60, 100, 18, ora, senzaFinestra)
                ?.contains("non si contestano") == true,
        )
    }
}
