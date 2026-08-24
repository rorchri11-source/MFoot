package dev.mfoot.core.market

import dev.mfoot.core.config.MarketConfig
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Contract
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import java.time.Instant

/**
 * I termini di un'offerta.
 *
 * Un'offerta non e' solo una cifra: e' un pacchetto. La **clausola rescissoria** in
 * particolare e' merce di scambio — "accetto 17 invece di 18, ma tu gli metti una
 * clausola a 25". Chi la subisce rischia di perdere il giocatore, chi la ottiene ha
 * un'opzione d'acquisto.
 */
data class OfferTerms(
    val credits: Int,
    /** Giocatori offerti in contropartita. */
    val playersOffered: List<PlayerId> = emptyList(),
    /** Clausola che l'acquirente si impegna a mettere sul nuovo contratto. */
    val releaseClause: Int? = null,
    val contractMatchDays: Int,
) {
    init {
        require(credits >= 0) { "i crediti offerti non possono essere negativi" }
        require(contractMatchDays > 0) { "il contratto deve durare almeno una giornata" }
    }

    fun describe(): String = buildString {
        append("$credits crediti")
        if (playersOffered.isNotEmpty()) append(" + ${playersOffered.size} giocatori")
        releaseClause?.let { append(", clausola $it") }
    }
}

enum class OfferStatus { IN_ATTESA, ACCETTATA, RIFIUTATA, CONTROPROPOSTA, SCADUTA, RITIRATA }

/**
 * Una trattativa fra due club.
 *
 * Il ping-pong e' esplicito nella [history]: offro 15, ne vuoi 20, 18, 17 con clausola.
 * Ogni offerta ha una **scadenza**, altrimenti le trattative restano appese per sempre
 * e nessuno chiude mai niente.
 */
data class Negotiation(
    val id: Long,
    val playerId: PlayerId,
    val buyer: ClubId,
    val seller: ClubId,
    val terms: OfferTerms,
    /** Di chi e' il turno di rispondere. */
    val awaiting: ClubId,
    val expiresAt: Instant,
    val status: OfferStatus = OfferStatus.IN_ATTESA,
    val history: List<OfferTerms> = emptyList(),
) {
    init {
        require(buyer != seller) { "un club non può trattare con se stesso" }
    }

    val isOpen: Boolean
        get() = status == OfferStatus.IN_ATTESA || status == OfferStatus.CONTROPROPOSTA

    fun hasExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    val rounds: Int get() = history.size + 1
}

sealed interface NegotiationResult {
    data class Updated(val negotiation: Negotiation) : NegotiationResult
    data class Concluded(
        val negotiation: Negotiation,
        val price: Int,
        val newContract: Contract,
    ) : NegotiationResult
    data class Rejected(val reason: String) : NegotiationResult
}

/**
 * Le regole delle trattative dirette e dei prestiti.
 *
 * Il giocatore creato dal proprietario e' l'unico caso speciale: non si vende e non si
 * svincola, ma **si presta**. Mandarlo a giocare titolare in un club piu' debole per
 * farlo crescere e' esattamente quello che succede ai giovani veri.
 */
object NegotiationRules {

    fun open(
        id: Long,
        player: Player,
        buyer: ClubId,
        seller: ClubId,
        terms: OfferTerms,
        buyerAvailableCredits: Int,
        now: Instant,
        config: MarketConfig,
    ): NegotiationResult {
        transferBlock(player)?.let { return NegotiationResult.Rejected(it) }

        if (terms.credits > buyerAvailableCredits) {
            return NegotiationResult.Rejected(
                "Crediti insufficienti: offerti ${terms.credits}, disponibili $buyerAvailableCredits.",
            )
        }
        if (terms.releaseClause != null && !config.releaseClausesEnabled) {
            return NegotiationResult.Rejected("Le clausole rescissorie sono disattivate in questa lega.")
        }
        if (terms.playersOffered.isNotEmpty() && !config.swapsEnabled) {
            return NegotiationResult.Rejected("Gli scambi sono disattivati in questa lega.")
        }

        return NegotiationResult.Updated(
            Negotiation(
                id = id,
                playerId = player.id,
                buyer = buyer,
                seller = seller,
                terms = terms,
                awaiting = seller,
                expiresAt = now.plusSeconds(config.negotiationExpiryMinutes * 60L),
            ),
        )
    }

    /** Controproposta: la palla torna all'altro, la scadenza riparte. */
    fun counter(
        negotiation: Negotiation,
        from: ClubId,
        terms: OfferTerms,
        now: Instant,
        config: MarketConfig,
    ): NegotiationResult {
        if (!negotiation.isOpen) return NegotiationResult.Rejected("La trattativa è chiusa.")
        if (negotiation.hasExpired(now)) return NegotiationResult.Rejected("La trattativa è scaduta.")
        if (negotiation.awaiting != from) return NegotiationResult.Rejected("Non tocca a te rispondere.")

        return NegotiationResult.Updated(
            negotiation.copy(
                terms = terms,
                awaiting = if (from == negotiation.buyer) negotiation.seller else negotiation.buyer,
                status = OfferStatus.CONTROPROPOSTA,
                expiresAt = now.plusSeconds(config.negotiationExpiryMinutes * 60L),
                history = negotiation.history + negotiation.terms,
            ),
        )
    }

    fun accept(
        negotiation: Negotiation,
        from: ClubId,
        today: MatchDay,
        now: Instant,
    ): NegotiationResult {
        if (!negotiation.isOpen) return NegotiationResult.Rejected("La trattativa è chiusa.")
        if (negotiation.hasExpired(now)) return NegotiationResult.Rejected("La trattativa è scaduta.")
        if (negotiation.awaiting != from) return NegotiationResult.Rejected("Non tocca a te rispondere.")

        val contract = Contract(
            playerId = negotiation.playerId,
            clubId = negotiation.buyer,
            signedOn = today,
            expiresOn = today + negotiation.terms.contractMatchDays,
            wagePerMatchDay = 0,
            pricePaid = negotiation.terms.credits,
            releaseClause = negotiation.terms.releaseClause,
        )

        return NegotiationResult.Concluded(
            negotiation.copy(status = OfferStatus.ACCETTATA),
            negotiation.terms.credits,
            contract,
        )
    }

    fun reject(negotiation: Negotiation, from: ClubId): NegotiationResult {
        if (!negotiation.isOpen) return NegotiationResult.Rejected("La trattativa è chiusa.")
        if (negotiation.awaiting != from) return NegotiationResult.Rejected("Non tocca a te rispondere.")
        return NegotiationResult.Updated(negotiation.copy(status = OfferStatus.RIFIUTATA))
    }

    fun expire(negotiation: Negotiation): Negotiation =
        if (negotiation.isOpen) negotiation.copy(status = OfferStatus.SCADUTA) else negotiation

    /**
     * Pagamento diretto della clausola rescissoria.
     *
     * Non c'e' trattativa: chi la paga si prende il giocatore, e il proprietario puo'
     * solo prenderne atto. E' proprio questo che rende la clausola una concessione
     * pesante da fare al tavolo.
     */
    fun triggerReleaseClause(
        player: Player,
        contract: Contract,
        buyer: ClubId,
        buyerAvailableCredits: Int,
        today: MatchDay,
        contractMatchDays: Int,
        config: MarketConfig,
    ): NegotiationResult {
        transferBlock(player)?.let { return NegotiationResult.Rejected(it) }

        if (!config.releaseClausesEnabled) {
            return NegotiationResult.Rejected("Le clausole rescissorie sono disattivate.")
        }
        val clause = contract.releaseClause
            ?: return NegotiationResult.Rejected("Questo giocatore non ha una clausola rescissoria.")
        if (buyerAvailableCredits < clause) {
            return NegotiationResult.Rejected("Servono $clause crediti, ne hai $buyerAvailableCredits.")
        }
        if (buyer == contract.clubId) {
            return NegotiationResult.Rejected("Il giocatore è già tuo.")
        }

        return NegotiationResult.Concluded(
            Negotiation(
                id = 0,
                playerId = player.id,
                buyer = buyer,
                seller = contract.clubId,
                terms = OfferTerms(clause, contractMatchDays = contractMatchDays),
                awaiting = buyer,
                expiresAt = Instant.MAX,
                status = OfferStatus.ACCETTATA,
            ),
            clause,
            Contract(
                playerId = player.id,
                clubId = buyer,
                signedOn = today,
                expiresOn = today + contractMatchDays,
                wagePerMatchDay = 0,
                pricePaid = clause,
            ),
        )
    }

    /**
     * Il giocatore del proprietario non si vende e non si svincola.
     * @return il motivo del blocco, o null se il trasferimento e' possibile.
     */
    fun transferBlock(player: Player): String? =
        if (player.isCustom) {
            "${player.shortName} è il giocatore creato dal proprietario: " +
                "non può essere ceduto, solo prestato."
        } else {
            null
        }
}
