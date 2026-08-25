package dev.mfoot.core.ai

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.market.Listing
import dev.mfoot.core.market.Purchase
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.model.Club
import dev.mfoot.core.model.Player
import dev.mfoot.core.rng.MathX
import dev.mfoot.core.world.PotentialEstimator

/**
 * Cosa fa un club del computer sul **listino**, che prima del 2026-08-24 non esisteva.
 *
 * ## Perche' e' la mossa che sblocca tutto
 *
 * Il proprietario si lamentava di due cose insieme: poche aste, e AI troppo lente a
 * riempire la rosa. Erano la stessa cosa. Un'AI che vuole quindici giocatori doveva aprire
 * quindici aste da un'ora l'una e vincerle tutte, con il tick che passa ogni venti o
 * quaranta minuti: settimane reali. Comprando a listino la stessa rosa si completa in un
 * pomeriggio, e le aste tornano a essere quello che devono essere — l'eccezione su cui ci
 * si accapiglia.
 *
 * ## La regola che tiene onesta la contestazione
 *
 * Un'AI **non contesta tutto**: contesta quello che voleva davvero e che qualcun altro sta
 * pagando troppo poco. Se contestassero ogni acquisto si tornerebbe a fare aste ogni
 * giorno, cioe' esattamente la cosa da cui il listino serve a scappare.
 */
object AiMarket {

    /**
     * Cosa comprare dal listino, o null se niente vale il prezzo.
     *
     * Non e' «il piu' forte che posso permettermi»: e' il piu' **desiderato**, e il
     * desiderio passa da [AiManager.evaluate] — che tiene conto del ruolo scoperto, delle
     * fissazioni del club e della stima di potenziale, mai del valore vero.
     */
    fun playerToBuy(
        state: AiState,
        club: Club,
        squad: List<Player>,
        listino: List<Pair<Listing, Player>>,
        config: LeagueConfig,
    ): Pair<Listing, Player>? {
        if (!config.market.instantBuyEnabled) return null
        if (squad.size >= config.setup.maxSquadSize) return null

        val disponibili = club.availableCredits.coerceAtLeast(0)
        if (disponibili <= 0) return null

        return listino
            .asSequence()
            // I propri no, e nemmeno chi e' gia' in rosa.
            .filter { (listing, player) ->
                listing.seller != club.id && squad.none { it.id == player.id }
            }
            .filter { (listing, _) -> listing.price <= disponibili }
            .mapNotNull { coppia ->
                val (listing, player) = coppia
                val appeal = AiManager.evaluate(state, club, squad, player, config)
                if (!appeal.isInterested) return@mapNotNull null
                // Il tetto vale come in asta: sopra quello non si compra, per quanto
                // comodo sia comprare subito. E' cio' che impedisce a un'AI di svuotare
                // la cassa sul primo nome che le piace.
                if (listing.price > appeal.ceiling) return@mapNotNull null

                Candidato(
                    listing = listing,
                    player = player,
                    appeal = appeal.appeal,
                    // Quanti ne ha gia' in quel ruolo. E' il bisogno vero, e serve
                    // proprio quando il gradimento non lo dice piu' (vedi sotto).
                    giaInRuolo = squad.count { it.primaryPosition == player.primaryPosition },
                    // A parita' di tutto si preferisce l'affare: tetto alto, prezzo basso.
                    convenienza = appeal.ceiling.toDouble() / listing.price.coerceAtLeast(1),
                )
            }
            .sortedWith(
                compareByDescending<Candidato> { it.appeal }
                    .thenBy { it.giaInRuolo }
                    .thenByDescending { it.convenienza }
                    // A parita' esatta decide l'id: due giri dello stesso mondo devono
                    // comprare lo stesso giocatore.
                    .thenBy { it.player.id.value },
            )
            .firstOrNull()
            ?.let { it.listing to it.player }
    }

    /**
     * Un giocatore del listino con i tre numeri su cui si decide.
     *
     * ## Perche' il ruolo viene prima della convenienza, ed e' una cosa misurata
     *
     * Perche' quando la rosa e' sotto il minimo **il gradimento smette di distinguere**.
     * `AiManager.evaluate` alza al pavimento (0,2) qualunque giocatore per un club a cui
     * manca gente — giustamente: chi non ha diciotto uomini non puo' rispondere «no
     * grazie» a tutto il listino. Ma l'effetto collaterale e' che un attaccante da 77 con
     * l'attacco vuoto e un difensore da 85 con otto difensori in rosa escono **con lo
     * stesso identico 0,2**.
     *
     * L'ho visto misurando: a quel punto decideva la convenienza, cioe' il tetto piu' alto,
     * cioe' sempre il giocatore piu' forte. Un club con otto difensori centrali e zero
     * attaccanti comprava il nono difensore.
     */
    private data class Candidato(
        val listing: Listing,
        val player: Player,
        val appeal: Double,
        val giaInRuolo: Int,
        val convenienza: Double,
    )

    /**
     * Quanto offrire per contestare un acquisto altrui, o null per lasciar perdere.
     *
     * ## Le tre condizioni, e perche' sono tre
     *
     * **Gli interessa**, con la stessa soglia di sempre.
     *
     * **Lo vuole abbastanza da arrivarci**: il suo tetto copre il prezzo pagato piu' il
     * rilancio. Il tetto e' proporzionale al gradimento e alla disciplina di bilancio, e
     * quindi fa da solo il lavoro che una soglia scelta a mano farebbe male.
     *
     * **E' stato pagato poco.** Sotto una frazione di quanto l'AI lo valuta: e' la
     * definizione di affare troppo buono, ed e' l'unica cosa che il prezzo libero puo'
     * produrre di storto — compreso il favore fra due amici che si accordano.
     */
    fun contestBid(
        state: AiState,
        club: Club,
        squad: List<Player>,
        purchase: Purchase,
        player: Player,
        config: LeagueConfig,
    ): Int? {
        if (config.market.contestWindowHours <= 0) return null
        if (purchase.buyer == club.id || purchase.seller == club.id) return null
        if (squad.size >= config.setup.maxSquadSize) return null

        val appeal = AiManager.evaluate(state, club, squad, player, config)
        if (!appeal.isInterested) return null

        // La misura di «lo voleva davvero» e' **il tetto**, non una soglia di gradimento
        // scelta a occhio.
        //
        // Ci sono arrivato sbagliando: la prima versione chiedeva `appeal >= 0,45`, e
        // misurando i valori veri il gradimento in una lega generata sta fra 0,1 e 0,2 —
        // quel numero non era severo, era **irraggiungibile**, e nessuna AI avrebbe mai
        // contestato niente. Il tetto invece e' gia' proporzionale al gradimento, al
        // valore stimato e alla disciplina di bilancio del club: se copre il prezzo piu'
        // il rilancio, quell'AI quel giocatore lo vuole per davvero, qualunque sia la
        // scala dei numeri in quella lega.
        val minimo = purchase.price + config.market.minimumRaise
        if (minimo > appeal.ceiling) return null
        if (minimo > club.availableCredits) return null

        // Quanto **questa AI** pensa che valga, con la sua incertezza sul potenziale.
        val stima = Valuation.estimatedValue(
            player,
            PotentialEstimator.estimate(player, club.id.value, 0, 0.0),
            config,
        )
        if (purchase.price > stima * AFFARE_TROPPO_BUONO) return null

        return appeal.ceiling.coerceAtMost(club.availableCredits)
    }

    /**
     * A quanto un'AI mette in vendita chi non le serve piu'.
     *
     * Sopra il valore, e non di poco: chi vende non ha fretta, e un prezzo pieno lascia
     * spazio a chi vuole davvero quel giocatore senza regalarlo. L'aggressivita' di
     * mercato del club decide quanto tira sul prezzo.
     */
    fun askingPrice(player: Player, personality: AiPersonality, config: LeagueConfig): Int {
        val valore = Valuation.marketValue(player, config)
        val ricarico = 1.0 + RICARICO_MIN +
            personality.marketAggression * (RICARICO_MAX - RICARICO_MIN)
        return StrictMath.round(valore * ricarico).toInt().coerceAtLeast(1)
    }

    /**
     * Quanti crediti offrire a un umano per un suo giocatore, o null se non interessa.
     *
     * ## Perche' non esisteva
     *
     * `AiInitiative.proposeTrade` sa proporre solo **giocatore contro giocatore**: il
     * conguaglio in crediti c'e' nel modello (`TradeOffer.cash` ha il segno) e non veniva
     * mai usato da solo. Il risultato e' che in tutta la vita di una lega nessuna AI ha mai
     * chiesto «quanto vuoi per il tuo attaccante?», che e' la cosa piu' normale che possa
     * fare un club.
     *
     * L'offerta e' generosa di proposito: sopra il valore stimato, perche' una proposta al
     * valore esatto la rifiuta chiunque — cedere un giocatore costa comunque qualcosa.
     */
    fun cashOffer(
        state: AiState,
        club: Club,
        squad: List<Player>,
        player: Player,
        config: LeagueConfig,
    ): Int? {
        if (squad.size >= config.setup.maxSquadSize) return null

        val appeal = AiManager.evaluate(state, club, squad, player, config)
        if (!appeal.isInterested) return null

        // Qui il tetto d'asta **non** e' il metro giusto, ed e' un errore che ho fatto
        // prima di misurare: quel tetto e' tarato su quanto si spera di pagare in una gara
        // al rialzo, e sta strutturalmente sotto il valore di mercato. Usarlo come limite
        // significava che nessuna AI avrebbe mai fatto un'offerta a nessuno.
        //
        // Per un giocatore che sta gia' in un'altra rosa la domanda e' un'altra: **quanto
        // serve perche' l'altro dica di si'**. La risposta la sa gia' `AiInitiative`, che
        // usa lo stesso sovrapprezzo quando valuta le proposte che riceve: un'offerta al
        // valore esatto la rifiuta chiunque, perche' cedere un giocatore costa comunque
        // fastidio.
        val valore = Valuation.marketValue(player, config)
        val sovrapprezzo = MathX.lerp(
            AiInitiative.SOVRAPPREZZO_MIN,
            AiInitiative.SOVRAPPREZZO_MAX,
            state.personality.marketAggression,
        )
        val offerta = StrictMath.round(valore * sovrapprezzo).toInt()

        if (offerta < 1) return null
        // E resta un limite di prudenza: nessun club si svuota la cassa per un giocatore
        // che nemmeno e' in vendita.
        val tetto = StrictMath.round(club.availableCredits * QUOTA_MASSIMA_PER_OFFERTA).toInt()
        if (offerta > tetto) return null

        return offerta
    }

    /**
     * Sotto questa frazione della propria stima, il prezzo pagato e' un affare.
     *
     * E' la condizione che distingue «qualcuno ha comprato» da «qualcuno ha comprato a un
     * prezzo che non sta in piedi» — compreso il favore fra due amici che si accordano,
     * che e' l'unico modo storto in cui il prezzo libero puo' finire.
     */
    private const val AFFARE_TROPPO_BUONO = 0.70

    /**
     * Quanta parte della cassa un'AI e' disposta a mettere su un'offerta a un altro club.
     *
     * Un terzo: sopra questa quota si tratta di un affare che decide la stagione, e quello
     * passa dalle aste o dal listino, non da una proposta a sorpresa a un club che nemmeno
     * lo stava vendendo.
     */
    private const val QUOTA_MASSIMA_PER_OFFERTA = 0.35

    private const val RICARICO_MIN = 0.10
    private const val RICARICO_MAX = 0.55
}
