package dev.mfoot.core.model

/** Fascia verticale del campo. */
enum class Lane {
    SX, C, DX;

    fun mirror(): Lane = when (this) {
        SX -> DX
        C -> C
        DX -> SX
    }
}

/** Altezza del campo rispetto alla squadra che attacca. */
enum class Band {
    DIF, MID, ATT;

    fun mirror(): Band = when (this) {
        DIF -> ATT
        MID -> MID
        ATT -> DIF
    }

    /** La fascia successiva avanzando verso la porta avversaria, o null se sei gia' in area. */
    fun advance(): Band? = when (this) {
        DIF -> MID
        MID -> ATT
        ATT -> null
    }
}

/**
 * Le nove zone del campo: tre fasce per tre altezze.
 *
 * E' la griglia su cui gira tutto il motore di simulazione. Rende esplicito il concetto
 * di "overall delle fasce": se il terzino sinistro avversario e' scarso, `ATT_DX` batte
 * `DIF_SX` e le azioni passano visibilmente da li'.
 */
enum class Zone(val lane: Lane, val band: Band) {
    DIF_SX(Lane.SX, Band.DIF),
    DIF_C(Lane.C, Band.DIF),
    DIF_DX(Lane.DX, Band.DIF),
    MID_SX(Lane.SX, Band.MID),
    MID_C(Lane.C, Band.MID),
    MID_DX(Lane.DX, Band.MID),
    ATT_SX(Lane.SX, Band.ATT),
    ATT_C(Lane.C, Band.ATT),
    ATT_DX(Lane.DX, Band.ATT);

    /**
     * La zona avversaria che si oppone a questa.
     *
     * Chi attacca sulla propria sinistra affronta la destra difensiva avversaria, quindi
     * si specchiano sia la fascia sia l'altezza: `ATT_SX` -> `DIF_DX`.
     */
    fun mirror(): Zone = of(lane.mirror(), band.mirror())

    /** La zona in cui si arriva avanzando, o null se si e' gia' in zona offensiva. */
    fun advance(): Zone? = band.advance()?.let { of(lane, it) }

    val isAttacking: Boolean get() = band == Band.ATT

    companion object {
        private val byKey: Map<Pair<Lane, Band>, Zone> = entries.associateBy { it.lane to it.band }

        fun of(lane: Lane, band: Band): Zone =
            byKey.getValue(lane to band)

        val attacking: List<Zone> = entries.filter { it.band == Band.ATT }
    }
}
