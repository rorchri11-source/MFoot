package dev.mfoot.core.market

import dev.mfoot.core.config.MarketConfig
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.StaffId
import java.time.Duration
import java.time.Instant

/** Cosa e' in vendita. Giocatori e staff seguono le stesse regole d'asta. */
sealed interface AuctionTarget {
    data class ForPlayer(val playerId: PlayerId) : AuctionTarget
    data class ForStaff(val staffId: StaffId) : AuctionTarget
}

enum class AuctionStatus { APERTA, AGGIUDICATA, DESERTA, ANNULLATA }

/**
 * Un'offerta.
 *
 * [maxAmount] e' l'**offerta massima automatica**: quanto quel club e' disposto a
 * pagare al massimo. Non e' visibile agli altri. Il prezzo corrente sale solo quanto
 * serve per superare il secondo miglior massimo, esattamente come su eBay.
 *
 * E' il meccanismo che elimina la pressione di controllare il telefono ogni ora:
 * dichiari il tuo limite e vai a dormire, il sistema difende la tua posizione.
 */
data class Bid(
    val club: ClubId,
    val maxAmount: Int,
    val placedAt: Instant,
) {
    init {
        require(maxAmount > 0) { "l'offerta massima deve essere positiva" }
    }
}

data class Auction(
    val id: Long,
    val target: AuctionTarget,
    val startedBy: ClubId,
    val startedAt: Instant,
    val endsAt: Instant,
    val startingPrice: Int = 1,
    val bids: List<Bid> = emptyList(),
    val status: AuctionStatus = AuctionStatus.APERTA,
    /** Quante volte l'anti-snipe ha gia' prolungato l'asta. */
    val extensions: Int = 0,
) {

    /** Le offerte che contano: per ogni club vale solo il massimo piu' alto dichiarato. */
    val standingBids: List<Bid>
        get() = bids.groupBy { it.club }
            .map { (_, clubBids) -> clubBids.maxByOrNull { it.maxAmount }!! }
            .sortedWith(compareByDescending<Bid> { it.maxAmount }.thenBy { it.placedAt })

    val leader: ClubId? get() = standingBids.firstOrNull()?.club

    /**
     * Il prezzo che il capofila sta effettivamente pagando adesso.
     *
     * Sale solo quanto basta a superare il secondo: chi dichiara 30 contro un rivale a
     * 12 paga 13, non 30. E' quello che rende sicuro dichiarare il proprio vero limite.
     */
    fun currentPrice(config: MarketConfig): Int {
        val standing = standingBids
        return when {
            standing.isEmpty() -> startingPrice
            standing.size == 1 -> startingPrice
            else -> minOf(
                standing[1].maxAmount + config.minimumRaise,
                standing[0].maxAmount,
            )
        }
    }

    fun bidOf(club: ClubId): Bid? = standingBids.firstOrNull { it.club == club }

    fun hasExpired(now: Instant): Boolean = !now.isBefore(endsAt)

    fun secondsRemaining(now: Instant): Long =
        Duration.between(now, endsAt).seconds.coerceAtLeast(0)

    val isOpen: Boolean get() = status == AuctionStatus.APERTA
}

/** Esito di un tentativo di offerta. */
sealed interface BidResult {

    /**
     * @param outbidClub il club che era in testa e ora non lo e' piu': va avvisato
     *        subito, ed e' l'**unica** notifica immediata che l'asta deve produrre.
     *        Notificare ogni rilancio significherebbe quaranta ping a sera.
     */
    data class Accepted(
        val auction: Auction,
        val newPrice: Int,
        val leader: ClubId,
        val outbidClub: ClubId?,
        val extended: Boolean,
    ) : BidResult

    data class Rejected(val reason: String) : BidResult
}

data class AuctionOutcome(
    val auction: Auction,
    val winner: ClubId?,
    val price: Int,
    /** Club che avevano fondi impegnati e vanno liberati. */
    val clubsToRelease: List<ClubId>,
)

/**
 * Le regole d'asta.
 *
 * ## Le tre cose che tengono in piedi una lega
 *
 * 1. **Offerta massima automatica** — chi lavora o studia non deve perdere un giocatore
 *    solo perche' non aveva il telefono in mano.
 * 2. **Anti-snipe** — un rilancio negli ultimi secondi prolunga l'asta, cosi' vince chi
 *    valuta di piu' e non chi ha il dito piu' veloce.
 * 3. **Blocco fondi** — chi offre 20 ha 20 crediti impegnati. Senza questo un club puo'
 *    vincere cinque aste con i soldi per una, e a quel punto la lega e' rotta e la
 *    serata finisce a litigare.
 */
object AuctionRules {

    /**
     * Registra un'offerta.
     *
     * @param maxAmount il massimo che il club e' disposto a pagare, non il rilancio
     * @param availableCredits crediti liberi del club, gia' al netto degli impegni
     */
    fun placeBid(
        auction: Auction,
        club: ClubId,
        maxAmount: Int,
        availableCredits: Int,
        now: Instant,
        config: MarketConfig,
    ): BidResult {
        if (!auction.isOpen) return BidResult.Rejected("L'asta non è più aperta.")
        if (auction.hasExpired(now)) return BidResult.Rejected("L'asta è già scaduta.")
        if (maxAmount <= 0) return BidResult.Rejected("L'offerta deve essere di almeno 1 credito.")

        val previousOwnBid = auction.bidOf(club)
        // Si impegnano solo i crediti in piu' rispetto a quanto gia' bloccato.
        val additionalCommitment = maxAmount - (previousOwnBid?.maxAmount ?: 0)
        if (additionalCommitment > availableCredits) {
            return BidResult.Rejected(
                "Crediti insufficienti: servono altri $additionalCommitment crediti, " +
                    "disponibili $availableCredits.",
            )
        }
        if (previousOwnBid != null && maxAmount <= previousOwnBid.maxAmount) {
            return BidResult.Rejected("Puoi solo alzare la tua offerta massima.")
        }

        val previousLeader = auction.leader
        val minimumAcceptable = minimumBid(auction, club, config)
        if (maxAmount < minimumAcceptable) {
            return BidResult.Rejected("L'offerta minima è $minimumAcceptable crediti.")
        }

        val withBid = auction.copy(bids = auction.bids + Bid(club, maxAmount, now))
        val extended = shouldExtend(auction, now, config)
        val updated = if (extended) {
            withBid.copy(
                endsAt = auction.endsAt.plusSeconds(config.antiSnipeSeconds.toLong()),
                extensions = auction.extensions + 1,
            )
        } else {
            withBid
        }

        val newLeader = updated.leader ?: club
        return BidResult.Accepted(
            auction = updated,
            newPrice = updated.currentPrice(config),
            leader = newLeader,
            // Solo un cambio di capofila e' una notizia: gli altri rilanci sono rumore.
            outbidClub = previousLeader?.takeIf { it != newLeader },
            extended = extended,
        )
    }

    /**
     * Il minimo accettabile per questo club.
     *
     * Deve superare il **prezzo corrente**, non il massimo del capofila: quel massimo e'
     * segreto, e chiedere di batterlo renderebbe impossibile offrire. E' anche il punto
     * dell'offerta massima automatica — si dichiara quanto si e' disposti a pagare e si
     * scopre solo dopo se bastava.
     *
     * L'unica eccezione e' il capofila che vuole alzare il proprio tetto: li' il numero
     * da superare e' il suo, e lo conosce.
     */
    fun minimumBid(auction: Auction, club: ClubId, config: MarketConfig): Int {
        val standing = auction.standingBids
        if (standing.isEmpty()) return auction.startingPrice
        if (standing.first().club == club) return standing.first().maxAmount + config.minimumRaise
        return auction.currentPrice(config) + config.minimumRaise
    }

    /** Un rilancio negli ultimi secondi allunga l'asta invece di chiuderla. */
    fun shouldExtend(auction: Auction, now: Instant, config: MarketConfig): Boolean =
        config.antiSnipeEnabled &&
            auction.secondsRemaining(now) <= config.antiSnipeSeconds

    /**
     * Chiude l'asta e assegna.
     *
     * Restituisce anche i club a cui vanno liberati i fondi: il chiamante (il World Tick)
     * deve fare entrambe le cose nella **stessa transazione**, o due offerte simultanee
     * possono lasciare crediti bloccati per sempre.
     */
    fun close(auction: Auction, now: Instant, config: MarketConfig): AuctionOutcome {
        if (!auction.hasExpired(now) && auction.isOpen) {
            return AuctionOutcome(auction, null, 0, emptyList())
        }

        val winner = auction.leader
        val allBidders = auction.standingBids.map { it.club }

        return if (winner == null) {
            AuctionOutcome(
                auction.copy(status = AuctionStatus.DESERTA),
                null, 0, allBidders,
            )
        } else {
            AuctionOutcome(
                auction.copy(status = AuctionStatus.AGGIUDICATA),
                winner,
                auction.currentPrice(config),
                allBidders,
            )
        }
    }

    fun cancel(auction: Auction): AuctionOutcome = AuctionOutcome(
        auction.copy(status = AuctionStatus.ANNULLATA),
        null, 0,
        auction.standingBids.map { it.club },
    )

    /**
     * Puo' questo club aprire una nuova asta?
     *
     * Il tetto di aste parallele esiste perche' senza, un solo club potrebbe aprire
     * venti aste insieme e bloccare tutto il mercato per tutti.
     */
    fun canStartAuction(
        club: ClubId,
        openAuctions: List<Auction>,
        config: MarketConfig,
    ): String? {
        val mine = openAuctions.count { it.isOpen && it.startedBy == club }
        return if (mine >= config.maxParallelAuctionsPerClub) {
            "Hai già ${config.maxParallelAuctionsPerClub} aste aperte."
        } else {
            null
        }
    }

    /** Quanti crediti restano impegnati per un club fra tutte le aste aperte. */
    fun committedCredits(club: ClubId, openAuctions: List<Auction>): Int =
        openAuctions.filter { it.isOpen }.sumOf { it.bidOf(club)?.maxAmount ?: 0 }

    fun open(
        id: Long,
        target: AuctionTarget,
        startedBy: ClubId,
        now: Instant,
        config: MarketConfig,
        startingPrice: Int = 1,
    ): Auction = Auction(
        id = id,
        target = target,
        startedBy = startedBy,
        startedAt = now,
        endsAt = now.plusSeconds(config.auctionDurationMinutes * 60L),
        startingPrice = startingPrice.coerceAtLeast(1),
    )
}
