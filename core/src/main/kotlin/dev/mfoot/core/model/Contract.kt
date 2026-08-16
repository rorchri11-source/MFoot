package dev.mfoot.core.model

/**
 * Contratto di un giocatore con un club.
 *
 * Le scadenze sono in **giornate di gioco**, mai in date reali. Con un ritmo di due
 * partite al giorno una stagione dura una decina di giorni: un contratto "di due
 * settimane" durerebbe piu' di un campionato intero. Contando in giornate, l'admin puo'
 * cambiare quanto dura una giornata nel mondo reale senza che nulla si rompa.
 */
data class Contract(
    val playerId: PlayerId,
    val clubId: ClubId,
    val signedOn: MatchDay,
    val expiresOn: MatchDay,
    val wagePerMatchDay: Int,
    /** Quanto e' stato pagato per averlo: il rinnovo costa una frazione di questo. */
    val pricePaid: Int,
    /**
     * Clausola rescissoria: chiunque la paga si prende il giocatore senza trattativa.
     *
     * Modellata come voce del contratto e non come proprieta' del giocatore, perche' e'
     * merce di scambio in trattativa: "accetto 17 invece di 18, ma tu gli metti una
     * clausola a 25". Chi la subisce rischia di perderlo, chi la ottiene ha un'opzione.
     */
    val releaseClause: Int? = null,
) {

    init {
        require(expiresOn >= signedOn) { "contratto che scade prima di iniziare" }
        require(wagePerMatchDay >= 0) { "stipendio negativo" }
    }

    fun isExpired(on: MatchDay): Boolean = on >= expiresOn

    fun matchDaysLeft(on: MatchDay): Int = (expiresOn.value - on.value).coerceAtLeast(0)

    fun expiresWithin(on: MatchDay, matchDays: Int): Boolean =
        matchDaysLeft(on) in 0..matchDays

    /** Costo del rinnovo: una frazione configurabile di quanto e' stato pagato. */
    fun renewalCost(fraction: Double): Int =
        StrictMath.round(pricePaid * fraction).toInt().coerceAtLeast(1)

    fun renewed(from: MatchDay, forMatchDays: Int, cost: Int): Contract = copy(
        signedOn = from,
        expiresOn = from + forMatchDays,
        pricePaid = cost,
    )
}

/**
 * Prestito attivo.
 *
 * Il player custom non puo' essere venduto ne' svincolato, ma **puo'** essere prestato:
 * mandarlo a giocare titolare in un club piu' debole per farlo crescere e' esattamente
 * quello che succede ai giovani veri, ed e' una delle mosse piu' interessanti del gioco.
 */
data class Loan(
    val playerId: PlayerId,
    val ownerClub: ClubId,
    val borrowerClub: ClubId,
    val startsOn: MatchDay,
    val endsOn: MatchDay,
    val feePerMatchDay: Int,
    val wagePaidByBorrower: Boolean = true,
    val canPlayAgainstOwner: Boolean = false,
    /** Se il proprietario puo' richiamarlo prima della scadenza. */
    val recallable: Boolean = false,
) {

    init {
        require(endsOn > startsOn) { "prestito che finisce prima di cominciare" }
        require(ownerClub != borrowerClub) { "un club non puo' prestare a se stesso" }
        require(feePerMatchDay >= 0) { "canone negativo" }
    }

    fun isActive(on: MatchDay): Boolean = on >= startsOn && on < endsOn

    fun isExpired(on: MatchDay): Boolean = on >= endsOn

    fun totalFee(): Int = feePerMatchDay * (endsOn.value - startsOn.value)

    /** Il club per cui il giocatore scende in campo in questa giornata. */
    fun effectiveClub(on: MatchDay): ClubId = if (isActive(on)) borrowerClub else ownerClub
}
