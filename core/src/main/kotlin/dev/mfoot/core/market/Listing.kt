package dev.mfoot.core.market

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.config.MarketConfig
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import java.time.Duration
import java.time.Instant

/**
 * Il mercato senza aste: si compra a prezzo fisso, e chi vuole contesta.
 *
 * ## La regola, decisa il 2026-08-24
 *
 * Si compra a prezzo fisso e **il giocatore e' tuo nello stesso istante**: niente tick,
 * niente attesa, niente rilanci. Per dodici ore l'acquisto resta contestabile, e **solo
 * se qualcuno contesta** nasce un'asta.
 *
 * ## Perche' l'asta come rito obbligatorio non funzionava
 *
 * Non per come era fatta — l'offerta massima automatica e l'anti-snipe sono buone regole —
 * ma per **quanto durava**. Il tick impiega otto minuti a giro e viene cancellato nel 59%
 * delle esecuzioni: la cadenza vera e' fra venti e quaranta minuti. Un'asta da un'ora con
 * tre rilanci diventa mezza giornata, e una rosa da diciotto uomini tre settimane.
 *
 * Come **eccezione** invece l'asta funziona benissimo: protegge dall'affare troppo buono
 * senza tassare i novanta acquisti banali che non interessano a nessuno.
 *
 * ## Perche' la contestazione tiene in piedi il prezzo libero
 *
 * Il prezzo lo scrive chi vende, da un credito a tutto il budget. Da solo sarebbe una
 * porta aperta al favore fra amici: due che si mettono d'accordo spostano un 88 per
 * niente. Ma un prezzo fuori mercato **e' la definizione stessa dell'affare troppo
 * buono**, ed e' esattamente cio' che la contestazione punisce. Le due regole non sono
 * indipendenti: separate, nessuna delle due sarebbe difendibile.
 */

enum class ListingStatus { APERTO, VENDUTO, RITIRATO }

/**
 * Un giocatore in vendita a prezzo fisso.
 *
 * [seller] null significa **svincolato**: non lo vende nessuno, il prezzo e' quello di
 * mercato e i crediti non vanno a nessun club. Distinguerlo da una vendita vera conta,
 * perche' l'incasso di una cessione e' un'entrata per qualcuno.
 */
data class Listing(
    val id: Long,
    val playerId: PlayerId,
    val seller: ClubId?,
    val price: Int,
    val listedAt: Instant,
    val status: ListingStatus = ListingStatus.APERTO,
) {
    init {
        require(price >= 1) { "il prezzo di listino deve essere di almeno 1 credito" }
    }

    val isOpen: Boolean get() = status == ListingStatus.APERTO
    val isFreeAgent: Boolean get() = seller == null
}

enum class PurchaseStatus {
    /** Comprato, e ancora contestabile. */
    IN_FINESTRA,

    /** La finestra e' passata senza opposizioni: e' suo per sempre. */
    CONFERMATO,

    /** Qualcuno si e' opposto: c'e' un'asta aperta, e decidera' lei. */
    CONTESTATO,

    /** L'asta l'ha vinta un altro: il giocatore se n'e' andato e i crediti sono tornati. */
    REVOCATO,
}

/**
 * Un acquisto, con la sua finestra.
 *
 * [contestableUntil] e' l'ora esatta in cui il giocatore diventa definitivo, ed e' nota
 * **dal primo istante**: chi compra sa gia' quando sara' al sicuro. L'asta di
 * contestazione scade insieme alla finestra, non un'ora dopo l'ultimo rilancio, e questa
 * e' la ragione — un'asta che si allunga da sola toglierebbe l'unica certezza che rende
 * accettabile comprare senza aspettare.
 */
data class Purchase(
    val id: Long,
    val playerId: PlayerId,
    val buyer: ClubId,
    val seller: ClubId?,
    val price: Int,
    val boughtAt: Instant,
    val contestableUntil: Instant,
    val status: PurchaseStatus = PurchaseStatus.IN_FINESTRA,
    /** L'asta nata dalla contestazione, se qualcuno si e' opposto. */
    val auctionId: Long? = null,
) {
    fun isContestable(now: Instant): Boolean =
        (status == PurchaseStatus.IN_FINESTRA || status == PurchaseStatus.CONTESTATO) &&
            now.isBefore(contestableUntil)

    fun secondsLeft(now: Instant): Long =
        Duration.between(now, contestableUntil).seconds.coerceAtLeast(0)
}

sealed interface BuyResult {
    data class Done(val purchase: Purchase, val listing: Listing) : BuyResult
    data class Rejected(val reason: String) : BuyResult
}

sealed interface ContestResult {
    /** L'asta appena nata, con dentro gia' due offerte: chi ha comprato e chi contesta. */
    data class Opened(val auction: Auction, val purchase: Purchase) : ContestResult
    data class Rejected(val reason: String) : ContestResult
}

/** Chi puo' mettere in vendita cosa, e a che prezzo. */
object ListingRules {

    /**
     * Il prezzo che l'app propone quando si mette in vendita.
     *
     * E' un **suggerimento**, non un limite: il prezzo lo scrive il proprietario. Serve a
     * non far partire da zero chi non ha idea di quanto valga il suo terzino, e a rendere
     * evidente quando qualcuno mette in vendita a un decimo del valore — che e' il caso in
     * cui gli altri contestano.
     */
    fun suggestedPrice(player: Player, config: LeagueConfig): Int =
        Valuation.marketValue(player, config)

    /**
     * Perche' questo giocatore non si puo' mettere in vendita, o null se si puo'.
     *
     * Il player custom e' l'unico escluso davvero: e' il giocatore che il proprietario ha
     * costruito, non si vende e non si svincola. Puo' pero' essere prestato, e quella
     * regola vive altrove.
     */
    fun rejection(player: Player, price: Int, config: LeagueConfig): String? = when {
        player.isCustom -> "${player.shortName} è il tuo giocatore: non si vende."
        price < config.market.minListingPrice ->
            "Il prezzo minimo è ${config.market.minListingPrice}."
        else -> null
    }

    fun open(
        id: Long,
        player: Player,
        seller: ClubId?,
        price: Int,
        now: Instant,
    ): Listing = Listing(
        id = id,
        playerId = player.id,
        seller = seller,
        price = price,
        listedAt = now,
    )
}

/** L'acquisto immediato. */
object PurchaseRules {

    /**
     * Compra, o spiega perche' no.
     *
     * @param availableCredits crediti gia' al netto di quelli impegnati nelle aste
     * @param squadSize quanti giocatori ha gia' in rosa chi compra
     */
    fun buy(
        id: Long,
        listing: Listing,
        buyer: ClubId,
        availableCredits: Int,
        squadSize: Int,
        now: Instant,
        config: LeagueConfig,
    ): BuyResult {
        if (!config.market.instantBuyEnabled) {
            return BuyResult.Rejected("In questa lega si compra solo all'asta.")
        }
        if (!listing.isOpen) {
            return BuyResult.Rejected("Non è più in vendita.")
        }
        if (listing.seller == buyer) {
            return BuyResult.Rejected("È già tuo.")
        }
        if (availableCredits < listing.price) {
            return BuyResult.Rejected(
                "Ti servono ${listing.price} crediti, ne hai $availableCredits.",
            )
        }
        if (squadSize >= config.setup.maxSquadSize) {
            return BuyResult.Rejected(
                "Hai già ${config.setup.maxSquadSize} giocatori: prima devi liberare un posto.",
            )
        }

        return BuyResult.Done(
            purchase = Purchase(
                id = id,
                playerId = listing.playerId,
                buyer = buyer,
                seller = listing.seller,
                price = listing.price,
                boughtAt = now,
                contestableUntil = contestableUntil(now, config.market),
            ),
            listing = listing.copy(status = ListingStatus.VENDUTO),
        )
    }

    /** Quando l'acquisto diventa definitivo. */
    fun contestableUntil(now: Instant, config: MarketConfig): Instant =
        now.plusSeconds(config.contestWindowHours * 3600L)
}

/** La contestazione: l'unica cosa che fa nascere un'asta. */
object ContestRules {

    /**
     * Il minimo per contestare: **deve superare il prezzo pagato**.
     *
     * Contestare e' gia' un'offerta, e un'offerta pari al prezzo non toglierebbe niente a
     * nessuno — sarebbe solo un modo gratuito di far perdere dodici ore a chi ha comprato.
     */
    fun minimumContest(purchase: Purchase, config: MarketConfig): Int =
        purchase.price + config.minimumRaise

    /**
     * Perche' questo club non puo' contestare, o null se puo'.
     */
    fun rejection(
        purchase: Purchase,
        club: ClubId,
        maxAmount: Int,
        availableCredits: Int,
        squadSize: Int,
        now: Instant,
        config: LeagueConfig,
    ): String? {
        val minimo = minimumContest(purchase, config.market)
        return when {
            config.market.contestWindowHours <= 0 ->
                "In questa lega gli acquisti non si contestano."

            club == purchase.buyer ->
                "L'hai comprato tu: sei già in testa."

            // Chi vende non puo' ricomprarsi il proprio giocatore per far salire il
            // prezzo a chi lo ha appena preso: sarebbe un rilancio sulle proprie merci.
            club == purchase.seller ->
                "L'hai venduto tu: non puoi contestarne la vendita."

            !purchase.isContestable(now) ->
                "Il tempo per contestare è finito."

            maxAmount < minimo ->
                "Per contestare devi offrire almeno $minimo."

            availableCredits < maxAmount ->
                "Crediti insufficienti: ne hai $availableCredits."

            squadSize >= config.setup.maxSquadSize ->
                "Hai già ${config.setup.maxSquadSize} giocatori in rosa."

            else -> null
        }
    }

    /**
     * Apre l'asta di contestazione.
     *
     * ## Chi ha comprato e' gia' in testa, al prezzo che ha pagato
     *
     * E' la regola dettata il 2026-08-24 — chi apre un'asta per comprare ha gia' offerto
     * il prezzo base — applicata qui: chi ha comprato non deve rioffrire su un giocatore
     * che era gia' suo. Entra nell'asta con la sua offerta, e se nessuno lo supera se lo
     * tiene al prezzo di prima.
     *
     * ## E scade insieme alla finestra
     *
     * Non un'ora dopo l'ultimo rilancio: alla scadenza delle dodici ore. Cosi' chi compra
     * conosce fin dall'inizio l'ora esatta in cui il giocatore e' suo per sempre.
     * L'anti-snipe resta e prolunga solo quella scadenza — vince chi valuta di piu', non
     * chi ha il dito piu' veloce.
     */
    fun open(
        auctionId: Long,
        purchase: Purchase,
        contestante: ClubId,
        maxAmount: Int,
        now: Instant,
    ): ContestResult {
        if (purchase.status == PurchaseStatus.CONTESTATO) {
            return ContestResult.Rejected("C'è già un'asta aperta su questo acquisto.")
        }

        val auction = Auction(
            id = auctionId,
            target = AuctionTarget.ForPlayer(purchase.playerId),
            startedBy = contestante,
            startedAt = now,
            endsAt = purchase.contestableUntil,
            startingPrice = purchase.price,
            bids = listOf(
                Bid(purchase.buyer, purchase.price, purchase.boughtAt),
                Bid(contestante, maxAmount, now),
            ),
        )

        return ContestResult.Opened(
            auction = auction,
            purchase = purchase.copy(status = PurchaseStatus.CONTESTATO, auctionId = auctionId),
        )
    }

    /**
     * Cosa succede quando la finestra si chiude.
     *
     * Tre esiti, e vanno distinti perche' muovono soldi diversi: nessuno ha contestato
     * (non si muove niente), ha vinto chi aveva comprato (paga la differenza fra il prezzo
     * d'asta e quello che aveva gia' pagato), ha vinto un altro (il giocatore cambia
     * squadra, chi aveva comprato **riprende i crediti interi** — ha perso il giocatore,
     * non i soldi).
     */
    fun settle(
        purchase: Purchase,
        auction: Auction?,
        now: Instant,
        config: MarketConfig,
    ): Settlement {
        if (auction == null || purchase.status != PurchaseStatus.CONTESTATO) {
            return Settlement.Confermato(purchase.copy(status = PurchaseStatus.CONFERMATO))
        }

        val outcome = AuctionRules.close(auction, now, config)
        val winner = outcome.winner

        return when (winner) {
            null, purchase.buyer -> Settlement.Confermato(
                purchase = purchase.copy(status = PurchaseStatus.CONFERMATO),
                // Il prezzo puo' essere salito: chi ha comprato paga la differenza.
                extraDaPagare = (outcome.price - purchase.price).coerceAtLeast(0),
                auction = outcome.auction,
            )

            else -> Settlement.Revocato(
                purchase = purchase.copy(status = PurchaseStatus.REVOCATO),
                vincitore = winner,
                prezzo = outcome.price,
                daRimborsare = purchase.price,
                auction = outcome.auction,
            )
        }
    }

    sealed interface Settlement {
        val purchase: Purchase

        data class Confermato(
            override val purchase: Purchase,
            val extraDaPagare: Int = 0,
            val auction: Auction? = null,
        ) : Settlement

        data class Revocato(
            override val purchase: Purchase,
            val vincitore: ClubId,
            val prezzo: Int,
            val daRimborsare: Int,
            val auction: Auction,
        ) : Settlement
    }
}
