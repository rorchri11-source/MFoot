package dev.mfoot.core.market

import dev.mfoot.core.config.EconomyConfig
import dev.mfoot.core.config.MarketConfig
import dev.mfoot.core.config.RulesConfig
import dev.mfoot.core.growth.MoraleEngine
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Contract
import dev.mfoot.core.model.Loan
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.rng.MathX

sealed interface ContractAction {
    data class Renewed(val contract: Contract, val cost: Int) : ContractAction
    data class Released(val playerId: dev.mfoot.core.model.PlayerId, val reason: String) : ContractAction
    data class Refused(val reason: String) : ContractAction
}

/**
 * Scadenze, rinnovi, svincoli e prestiti.
 *
 * Tutte le durate sono in **giornate di gioco**, mai in giorni reali. Con due partite al
 * giorno una stagione dura una decina di giorni: un contratto "di due settimane"
 * durerebbe piu' di un campionato intero.
 */
object ContractRules {

    /**
     * Stipendio per giornata.
     *
     * Cresce piu' che linearmente con l'overall, cosi' una rosa di fuoriclasse pesa sul
     * bilancio in modo sproporzionato: e' l'unico freno naturale all'accumulo.
     */
    fun wageFor(player: Player, economy: EconomyConfig): Int {
        if (!economy.wagesEnabled) return 0
        val overall = player.overall.toDouble()
        return StrictMath.round(overall * overall * economy.wageFactor).toInt().coerceAtLeast(1)
    }

    fun renewalCost(contract: Contract, economy: EconomyConfig): Int =
        contract.renewalCost(economy.renewalCostFraction)

    /**
     * Tenta il rinnovo.
     *
     * Un giocatore lasciato in tribuna tutta la stagione **puo' rifiutare**: e'
     * l'aggancio fra lo spogliatoio e il mercato, e rende la gestione del morale una
     * cosa che si paga davvero invece di un numero decorativo.
     */
    fun renew(
        player: Player,
        contract: Contract,
        today: MatchDay,
        availableCredits: Int,
        economy: EconomyConfig,
        market: MarketConfig,
        rules: RulesConfig,
    ): ContractAction {
        val cost = renewalCost(contract, economy)

        if (availableCredits < cost) {
            return ContractAction.Refused("Servono $cost crediti per il rinnovo, ne hai $availableCredits.")
        }
        if (!MoraleEngine.wouldAcceptRenewal(player, rules)) {
            return ContractAction.Refused(
                "${player.shortName} non se la sente di rinnovare: il morale e' a ${player.morale}.",
            )
        }

        return ContractAction.Renewed(
            contract.renewed(today, market.defaultContractMatchDays, cost),
            cost,
        )
    }

    /**
     * Svincolo.
     *
     * Il giocatore creato dal proprietario non si puo' svincolare: e' la regola che
     * lo rende davvero suo.
     */
    fun release(player: Player): ContractAction =
        NegotiationRules.transferBlock(player)?.let { ContractAction.Refused(it) }
            ?: ContractAction.Released(player.id, "svincolato")

    /**
     * Cosa fare dei contratti scaduti a fine giornata.
     *
     * Il World Tick chiama questa funzione anche se tutti hanno il telefono spento: chi
     * non decide si ritrova i giocatori svincolati, che e' la conseguenza corretta.
     */
    fun expiredOn(contracts: List<Contract>, today: MatchDay): List<Contract> =
        contracts.filter { it.isExpired(today) }

    fun expiringWithin(
        contracts: List<Contract>,
        today: MatchDay,
        matchDays: Int,
    ): List<Contract> = contracts.filter { it.expiresWithin(today, matchDays) }

    // ------------------------------------------------------------------- prestiti

    sealed interface LoanResult {
        data class Agreed(val loan: Loan, val totalFee: Int) : LoanResult
        data class Refused(val reason: String) : LoanResult
    }

    /**
     * Prestito.
     *
     * A differenza della cessione, il player custom **si puo'** prestare: il proprietario
     * lo manda a giocare titolare altrove per farlo crescere, e alla scadenza torna.
     * Il World Tick lo restituisce da solo, anche a telefoni spenti.
     */
    fun agreeLoan(
        player: Player,
        owner: ClubId,
        borrower: ClubId,
        from: MatchDay,
        matchDays: Int,
        feePerMatchDay: Int,
        borrowerAvailableCredits: Int,
        config: MarketConfig,
        wagePaidByBorrower: Boolean = true,
        canPlayAgainstOwner: Boolean = false,
        recallable: Boolean = false,
    ): LoanResult {
        if (!config.loansEnabled) {
            return LoanResult.Refused("I prestiti sono disattivati in questa lega.")
        }
        if (owner == borrower) {
            return LoanResult.Refused("Un club non puo' prestare a se stesso.")
        }
        if (matchDays !in config.minLoanMatchDays..config.maxLoanMatchDays) {
            return LoanResult.Refused(
                "La durata deve stare fra ${config.minLoanMatchDays} e " +
                    "${config.maxLoanMatchDays} giornate.",
            )
        }

        val total = feePerMatchDay * matchDays
        if (total > borrowerAvailableCredits) {
            return LoanResult.Refused(
                "Il prestito costa $total crediti in tutto, disponibili $borrowerAvailableCredits.",
            )
        }

        return LoanResult.Agreed(
            Loan(
                playerId = player.id,
                ownerClub = owner,
                borrowerClub = borrower,
                startsOn = from,
                endsOn = from + matchDays,
                feePerMatchDay = feePerMatchDay,
                wagePaidByBorrower = wagePaidByBorrower,
                canPlayAgainstOwner = canPlayAgainstOwner,
                recallable = recallable,
            ),
            total,
        )
    }

    /** I prestiti da chiudere in questa giornata: il World Tick restituisce i giocatori. */
    fun expiredLoans(loans: List<Loan>, today: MatchDay): List<Loan> =
        loans.filter { it.isExpired(today) }

    /**
     * Canone consigliato per un prestito, come frazione del valore di mercato.
     *
     * Serve all'interfaccia per proporre una cifra sensata e all'AI per non fare
     * offerte assurde in nessuna delle due direzioni.
     */
    fun suggestedLoanFee(marketValue: Int, matchDays: Int): Int {
        val perMatchDay = MathX.remap(matchDays.toDouble(), 2.0, 38.0, 0.06, 0.02) * marketValue
        return StrictMath.round(perMatchDay).toInt().coerceAtLeast(1)
    }
}
