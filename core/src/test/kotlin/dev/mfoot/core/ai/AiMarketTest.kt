package dev.mfoot.core.ai

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.market.Listing
import dev.mfoot.core.market.Purchase
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.model.Club
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position
import dev.mfoot.core.world.WorldGenerator
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Le AI sul listino.
 *
 * ## Cosa difendono questi test
 *
 * Due lamentele del proprietario che erano la stessa cosa: **poche aste** e **AI troppo
 * lente a riempire la rosa**. Un club che vuole quindici giocatori doveva aprire quindici
 * aste da un'ora e vincerle tutte, con il tick che passa ogni venti o quaranta minuti.
 *
 * E la contromisura: un'AI che contestasse ogni acquisto conveniente rimetterebbe in piedi
 * l'asta continua da cui il listino serve a scappare.
 */
class AiMarketTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)
    private val ora: Instant = Instant.parse("2026-09-01T20:00:00Z")

    private fun state(clubId: Long = 1L) = AiState(
        AiPersonalityGenerator.generate(ClubId(clubId), 555L, config.ai),
        ora,
    )

    /**
     * Un club **senza fissazioni**, per i test in cui la domanda e' il bisogno di ruolo.
     *
     * ## Perche' serve, e cosa ho scoperto scrivendolo
     *
     * `AiPersonality.obsessionBonusFor` somma un bonus fisso (0,25 per «Prima non
     * prenderle») **dopo** i moltiplicatori, quindi non viene ridotto da un ruolo gia'
     * coperto. Sui gradimenti veri di una lega generata — fra 0,1 e 0,2 — quel bonus vale
     * piu' di qualunque bisogno: un club fissato con la difesa compra il nono difensore
     * centrale anche senza avere un attaccante.
     *
     * Non lo cambio da qui: e' una scelta di carattere che si vede giocando, e ritoccarla
     * andrebbe misurato su una stagione intera. Ma un test sul bisogno di ruolo deve
     * partire da un club che non ha fissazioni, o misura la fissazione.
     */
    private fun statoNeutro() = AiState(
        AiPersonality(
            clubId = ClubId(1),
            marketAggression = 0.5,
            youthPreference = 0.5,
            budgetDiscipline = 0.5,
            patience = 0.5,
            obsessions = emptySet(),
            activeFromHour = 9,
            activeToHour = 23,
            checksPerDay = 4,
        ),
        ora,
    )

    private fun club(credits: Int = 100_000, committed: Int = 0, id: Long = 1) = Club(
        id = ClubId(id), name = "Verdemar", shortName = "VDM",
        isAi = true, credits = credits, committedCredits = committed,
    )

    /** Una rosa senza nessuno in quel ruolo: il buco e' la premessa dell'interesse. */
    private fun rosaSenza(position: Position, size: Int): List<Player> =
        world.players.filter { it.primaryPosition != position }.take(size)

    /**
     * Il miglior attaccante che questo mondo contiene davvero.
     *
     * Non `first { ... }`: `world.players` non e' ordinata per overall, e prendere il primo
     * attaccante che capita vuol dire misurare l'interesse per un giocatore da 55 — cioe'
     * misurare il disinteresse, che e' un'altra cosa. Ed e' esattamente l'errore che ha
     * fatto fallire la prima versione di questi test.
     */
    private val puntaForte: Player =
        world.players.filter { it.primaryPosition == Position.ATT }.maxByOrNull { it.overall }!!

    /**
     * Il prezzo di un affare: sotto il tetto di un'AI a cui quel ruolo manca.
     *
     * Preso dal tetto vero invece che da una frazione del valore di mercato. In una lega
     * generata il tetto sta **molto sotto** il valore — il gradimento reale e' fra 0,1 e
     * 0,2 — e un test che usasse `valore / 2` misurerebbe soltanto che l'AI non compra
     * mai, senza dire perche'.
     */
    private fun prezzoDaAffare(player: Player, squad: List<Player>): Int {
        val tetto = AiManager.evaluate(statoNeutro(), club(), squad, player, config).ceiling
        return (tetto / 3).coerceAtLeast(1)
    }

    private fun inVendita(player: Player, prezzo: Int, venditore: Long? = 9) = Listing(
        id = player.id.value,
        playerId = player.id,
        seller = venditore?.let { ClubId(it) },
        price = prezzo,
        listedAt = ora,
    )

    // ------------------------------------------------------------------- comprare

    /**
     * Il ruolo scoperto guida l'acquisto.
     *
     * La rosa e' **satura di difensori centrali** e senza nessun attaccante, e i due
     * giocatori sul listino costano uguale. Con una rosa qualsiasi il test non direbbe
     * niente: il club di prova ha la fissazione per la difesa, e su gradimenti che nel
     * mondo vero valgono 0,1-0,2 un bonus da fissazione decide da solo — comportamento
     * giusto del sistema, e domanda sbagliata da parte del test.
     */
    @Test
    fun `compra per il ruolo che gli manca`() {
        val difensori = world.players.filter { it.primaryPosition == Position.DC }
        val squad = difensori.take(8) +
            world.players.filter { it.primaryPosition == Position.CC }.take(6)
        val difensore = difensori.maxByOrNull { it.overall }!!

        val prezzo = prezzoDaAffare(puntaForte, squad)

        val scelta = AiMarket.playerToBuy(
            statoNeutro(), club(), squad,
            listOf(
                inVendita(difensore, prezzo) to difensore,
                inVendita(puntaForte, prezzo) to puntaForte,
            ),
            config,
        )

        assertNotNull(scelta, "con un buco in attacco e due occasioni non ha comprato niente")
        assertEquals(
            Position.ATT, scelta.second.primaryPosition,
            "ha comprato il nono difensore centrale invece dell'unico attaccante",
        )
    }

    /**
     * Il difetto per cui nessuno vendeva mai niente a nessuno.
     *
     * Un umano mette in vendita al prezzo che l'app gli consiglia — che e'
     * `ListingRules.suggestedPrice`, cioe' il valore di mercato — e per mesi non e'
     * successo niente. Non era il ritmo delle AI: era che il tetto veniva moltiplicato per
     * il gradimento letto come se andasse da zero a uno, e sotto la rosa minima il
     * gradimento e' inchiodato a 0,2. Il tetto stava **cinque volte sotto** il prezzo
     * consigliato, e nessuna AI poteva comprare nemmeno volendo.
     */
    @Test
    fun `compra al prezzo che l'app consiglia a un umano`() {
        val squad = rosaSenza(Position.ATT, 14)
        val consigliato = Valuation.marketValue(puntaForte, config)

        assertNotNull(
            AiMarket.playerToBuy(
                statoNeutro(), club(), squad,
                listOf(inVendita(puntaForte, consigliato) to puntaForte),
                config,
            ),
            "al prezzo consigliato non ha comprato: e' il prezzo che l'app propone a chi vende, " +
                "quindi il listino resterebbe fermo per sempre",
        )
    }

    /**
     * L'affare si prende anche quando non serviva.
     *
     * Il gradimento non sa quanto costa: dice quanto quel giocatore servirebbe. Con la rosa
     * gia' piena di attaccanti un'AI rispondeva no a un fuoriclasse a un decimo del valore,
     * che e' l'unica cosa che nessuno che faccia mercato rifiuterebbe mai.
     */
    @Test
    fun `un affare vero lo prende anche in un ruolo che ha gia' coperto`() {
        // Senza escluderlo, `puntaForte` finirebbe **dentro** la rosa che dovrebbe
        // desiderarlo, e `playerToBuy` scarta chi si ha gia': il test misurerebbe quel
        // filtro invece dell'affare.
        val attaccanti = world.players
            .filter { it.primaryPosition == Position.ATT && it.id != puntaForte.id }
        val pieno = attaccanti.take(6) +
            world.players.filter { it.primaryPosition == Position.DC }.take(12)
        val svenduto = Valuation.marketValue(puntaForte, config) / 10

        assertNotNull(
            AiMarket.playerToBuy(
                statoNeutro(), club(), pieno,
                listOf(inVendita(puntaForte, svenduto.coerceAtLeast(1)) to puntaForte),
                config,
            ),
            "a un decimo del valore non l'ha preso: un affare e' un affare anche in un ruolo pieno",
        )
    }

    @Test
    fun `non compra sopra il proprio tetto, per quanto comodo sia`() {
        val squad = rosaSenza(Position.ATT, 14)
        val caro = inVendita(puntaForte, Valuation.marketValue(puntaForte, config) * 10)

        assertNull(
            AiMarket.playerToBuy(state(), club(), squad, listOf(caro to puntaForte), config),
            "ha comprato a dieci volte il valore: il tetto non sta reggendo",
        )
    }

    @Test
    fun `non compra quello che sta gia' vendendo lui`() {
        val squad = rosaSenza(Position.ATT, 14)
        val suo = inVendita(puntaForte, 1, venditore = 1)

        assertNull(
            AiMarket.playerToBuy(state(), club(id = 1), squad, listOf(suo to puntaForte), config),
        )
    }

    @Test
    fun `senza crediti non compra`() {
        val squad = rosaSenza(Position.ATT, 14)

        assertNull(
            AiMarket.playerToBuy(
                state(), club(credits = 100, committed = 100), squad,
                listOf(inVendita(puntaForte, 500) to puntaForte), config,
            ),
        )
    }

    @Test
    fun `con la rosa piena non compra`() {
        val piena = world.players.take(config.setup.maxSquadSize)

        assertNull(
            AiMarket.playerToBuy(
                state(), club(), piena,
                listOf(inVendita(puntaForte, 1) to puntaForte), config,
            ),
        )
    }

    @Test
    fun `con l'acquisto immediato spento il listino non esiste`() {
        val spento = config.copy(market = config.market.copy(instantBuyEnabled = false))
        val squad = rosaSenza(Position.ATT, 14)

        assertNull(
            AiMarket.playerToBuy(
                state(), club(), squad,
                listOf(inVendita(puntaForte, 1) to puntaForte), spento,
            ),
        )
        assertTrue(AiMove.COMPRA_A_LISTINO !in AiTurn.order(10, spento))
    }

    // ---------------------------------------------------------------- contestare

    private fun acquisto(player: Player, prezzo: Int, compratore: Long = 7) = Purchase(
        id = 1,
        playerId = player.id,
        buyer = ClubId(compratore),
        seller = ClubId(9),
        price = prezzo,
        boughtAt = ora,
        contestableUntil = ora.plusSeconds(12 * 3600),
    )

    @Test
    fun `contesta l'affare troppo buono su un giocatore che voleva`() {
        val squad = rosaSenza(Position.ATT, 14)
        val svenduto = acquisto(puntaForte, prezzoDaAffare(puntaForte, squad))

        val offerta = AiMarket.contestBid(state(), club(), squad, svenduto, puntaForte, config)

        assertNotNull(offerta, "un attaccante svenduto con l'attacco scoperto non l'ha mosso")
        assertTrue(offerta > svenduto.price, "l'offerta deve superare il prezzo pagato")
    }

    @Test
    fun `non contesta chi ha pagato il giusto`() {
        val squad = rosaSenza(Position.ATT, 14)
        val pieno = acquisto(puntaForte, Valuation.marketValue(puntaForte, config) * 2)

        assertNull(
            AiMarket.contestBid(state(), club(), squad, pieno, puntaForte, config),
            "sta contestando un acquisto pagato il doppio del valore: contesterebbe tutto",
        )
    }

    @Test
    fun `non contesta un giocatore che non le interessa`() {
        // Rosa gia' piena in quel ruolo. E con un portiere dentro, o scatterebbe la
        // regola del portiere mancante — che batte qualunque altra considerazione.
        val portieri = world.players.filter { it.primaryPosition == Position.POR }
        val squad = portieri.take(3) + world.players.filterNot { it.primaryPosition == Position.POR }.take(17)
        val riserva = portieri.minByOrNull { it.overall }!!

        assertNull(
            AiMarket.contestBid(state(), club(), squad, acquisto(riserva, 1), riserva, config),
            "sta contestando il quarto portiere: contesterebbe qualunque cosa costi poco",
        )
    }

    @Test
    fun `non contesta il proprio acquisto ne' la propria vendita`() {
        val squad = rosaSenza(Position.ATT, 14)
        val prezzo = prezzoDaAffare(puntaForte, squad)

        // Comprato da me.
        assertNull(
            AiMarket.contestBid(
                state(), club(id = 7), squad, acquisto(puntaForte, prezzo, compratore = 7),
                puntaForte, config,
            ),
        )
        // Venduto da me: il venditore e' il club 9.
        assertNull(
            AiMarket.contestBid(
                state(), club(id = 9), squad, acquisto(puntaForte, prezzo),
                puntaForte, config,
            ),
        )
    }

    @Test
    fun `senza finestra non si contesta`() {
        val senza = config.copy(market = config.market.copy(contestWindowHours = 0))
        val squad = rosaSenza(Position.ATT, 14)

        assertNull(
            AiMarket.contestBid(
                state(), club(), squad, acquisto(puntaForte, 1), puntaForte, senza,
            ),
        )
    }

    // ------------------------------------------------------- vendere e fare offerte

    @Test
    fun `chi vende chiede piu' del valore`() {
        val player = world.players.first()
        val personality = AiPersonalityGenerator.generate(ClubId(1), 555L, config.ai)

        assertTrue(
            AiMarket.askingPrice(player, personality, config) >
                Valuation.marketValue(player, config),
            "l'AI sta svendendo: chi non ha fretta non regala",
        )
    }

    @Test
    fun `offre crediti solo per chi vuole davvero, e sopra il valore`() {
        val squad = rosaSenza(Position.ATT, 14)

        val offerta = AiMarket.cashOffer(state(), club(), squad, puntaForte, config)
        assertNotNull(offerta, "un attaccante forte con l'attacco scoperto non le interessa?")
        assertTrue(offerta <= club().availableCredits)
        assertTrue(
            offerta > Valuation.marketValue(puntaForte, config),
            "un'offerta al valore esatto la rifiuta chiunque: deve esserci un sovrapprezzo",
        )

        // Con la cassa quasi vuota non si fanno offerte a nessuno.
        assertNull(
            AiMarket.cashOffer(
                state(), club(credits = 1_000, committed = 900), squad, puntaForte, config,
            ),
        )
    }

    @Test
    fun `l'ordine delle mosse mette il listino davanti quando la rosa e' corta`() {
        val corta = AiTurn.order(config.setup.minSquadSize - 5, config)
        assertEquals(
            AiMove.COMPRA_A_LISTINO, corta.first(),
            "a rosa corta la prima mossa deve essere comprare subito, non aprire un'asta",
        )

        // A rosa completa contestare viene prima di tutto: e' l'unica mossa che scade.
        val completa = AiTurn.order(config.setup.minSquadSize + 4, config)
        assertEquals(AiMove.CONTESTA, completa.first())
    }
}
