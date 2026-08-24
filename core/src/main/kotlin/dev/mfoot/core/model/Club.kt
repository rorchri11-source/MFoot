package dev.mfoot.core.model

/** Motivo grafico della maglia. L'editor maglia del client lavora su questi campi. */
enum class KitPattern(val label: String) {
    TINTA_UNITA("Tinta unita"),
    STRISCE_VERTICALI("Strisce verticali"),
    STRISCE_ORIZZONTALI("Strisce orizzontali"),
    META_VERTICALE("Metà e metà"),
    DIAGONALE("Banda diagonale"),
    SCACCHI("Scacchi"),
    BANDA_CENTRALE("Banda centrale"),
}

/**
 * Maglia personalizzata di un club.
 *
 * I colori sono interi 0xRRGGBB: rappresentazione compatta, senza dipendenze da librerie
 * grafiche, cosi' `core` resta puro e il client la interpreta come preferisce.
 */
data class Kit(
    val primary: Int,
    val secondary: Int,
    val detail: Int,
    val numberColor: Int,
    val pattern: KitPattern,
) {
    companion object {
        val DEFAULT = Kit(
            primary = 0xFFFFFF,
            secondary = 0x1A1A1A,
            detail = 0x1A1A1A,
            numberColor = 0x1A1A1A,
            pattern = KitPattern.TINTA_UNITA,
        )
    }
}

/**
 * Un club della lega, umano o gestito dall'AI.
 *
 * ## Il blocco fondi
 *
 * [committedCredits] sono i crediti gia' impegnati in aste in corso e offerte pendenti.
 * Ogni decisione di spesa deve guardare [availableCredits], mai [credits]: senza questa
 * distinzione un club puo' vincere cinque aste con i soldi per una, e a quel punto la
 * lega e' rotta e la serata finisce a litigare.
 */
data class Club(
    val id: ClubId,
    val name: String,
    val shortName: String,
    val kit: Kit = Kit.DEFAULT,
    val isAi: Boolean = false,
    /** Nickname del proprietario umano, null per i club AI. */
    val ownerName: String? = null,
    val credits: Int = 0,
    val committedCredits: Int = 0,
    /** Rosa della prima squadra. */
    val squad: List<PlayerId> = emptyList(),
    /** Rosa della Primavera. */
    val youthSquad: List<PlayerId> = emptyList(),
    val staff: List<StaffId> = emptyList(),
    /** Il giocatore unico creato dal proprietario. Non vendibile, non svincolabile. */
    val customPlayerId: PlayerId? = null,
) {

    init {
        require(committedCredits >= 0) { "crediti impegnati negativi: $committedCredits" }
    }

    /** Quanto il club puo' realmente spendere adesso. */
    val availableCredits: Int get() = credits - committedCredits

    val totalSquadSize: Int get() = squad.size + youthSquad.size

    fun owns(playerId: PlayerId): Boolean =
        playerId in squad || playerId in youthSquad

    /** Impegna crediti per un'asta o un'offerta. Fallisce se non ce ne sono abbastanza. */
    fun commit(amount: Int): Club {
        require(amount >= 0) { "importo negativo: $amount" }
        require(amount <= availableCredits) {
            "$name non ha abbastanza crediti: servono $amount, disponibili $availableCredits"
        }
        return copy(committedCredits = committedCredits + amount)
    }

    /** Libera crediti impegnati, per esempio quando un'asta si perde o un'offerta scade. */
    fun release(amount: Int): Club =
        copy(committedCredits = (committedCredits - amount).coerceAtLeast(0))

    /** Conclude una spesa: i crediti impegnati diventano crediti spesi. */
    fun settle(amount: Int): Club = copy(
        credits = credits - amount,
        committedCredits = (committedCredits - amount).coerceAtLeast(0),
    )

    fun earn(amount: Int): Club = copy(credits = credits + amount)

    override fun toString(): String = "$name (${if (isAi) "AI" else ownerName ?: "?"})"
}
