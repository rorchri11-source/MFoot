package dev.mfoot.core.model

enum class Reparto { PORTIERE, DIFESA, CENTROCAMPO, ATTACCO }

/**
 * Ruolo in campo.
 *
 * Ogni ruolo porta con se' due tabelle di pesi, ed e' importante non confonderle:
 *
 * - [ovrWeights] dice **quanto vale un giocatore in quel ruolo**. Serve per l'overall,
 *   per la generazione del mondo e per la valutazione di mercato.
 * - [zoneWeights] dice **dove sta in campo**. Serve al motore per costruire i rating
 *   delle nove zone.
 *
 * Un terzino sinistro da 78 e' forte *come terzino sinistro*, e contribuisce per il 70%
 * a `DIF_SX` e per il 30% a `MID_SX`. Le due cose sono indipendenti.
 */
enum class Position(
    val label: String,
    val short: String,
    val reparto: Reparto,
    val ovrWeights: Map<Attr, Double>,
    val zoneWeights: Map<Zone, Double>,
) {
    POR(
        "Portiere", "POR", Reparto.PORTIERE,
        ovrWeights = mapOf(
            Attr.PARATA to 0.40,
            Attr.RIFLESSI to 0.32,
            Attr.USCITA to 0.20,
            Attr.POSIZIONAMENTO to 0.08,
        ),
        // Il portiere non contribuisce alle zone: interviene nella risoluzione del tiro.
        zoneWeights = emptyMap(),
    ),

    TD(
        "Terzino destro", "TD", Reparto.DIFESA,
        ovrWeights = mapOf(
            Attr.DIFESA to 0.25,
            Attr.VELOCITA to 0.18,
            Attr.FISICO to 0.15,
            Attr.INTERCETTAZIONE to 0.15,
            Attr.POSIZIONAMENTO to 0.12,
            Attr.PASSAGGIO to 0.08,
            Attr.DRIBBLING to 0.07,
        ),
        zoneWeights = mapOf(Zone.DIF_DX to 0.70, Zone.MID_DX to 0.30),
    ),

    DC(
        "Difensore centrale", "DC", Reparto.DIFESA,
        ovrWeights = mapOf(
            Attr.DIFESA to 0.34,
            Attr.POSIZIONAMENTO to 0.22,
            Attr.INTERCETTAZIONE to 0.20,
            Attr.FISICO to 0.18,
            Attr.PASSAGGIO to 0.06,
        ),
        zoneWeights = mapOf(Zone.DIF_C to 0.84, Zone.DIF_SX to 0.08, Zone.DIF_DX to 0.08),
    ),

    TS(
        "Terzino sinistro", "TS", Reparto.DIFESA,
        ovrWeights = mapOf(
            Attr.DIFESA to 0.25,
            Attr.VELOCITA to 0.18,
            Attr.FISICO to 0.15,
            Attr.INTERCETTAZIONE to 0.15,
            Attr.POSIZIONAMENTO to 0.12,
            Attr.PASSAGGIO to 0.08,
            Attr.DRIBBLING to 0.07,
        ),
        zoneWeights = mapOf(Zone.DIF_SX to 0.70, Zone.MID_SX to 0.30),
    ),

    MED(
        "Mediano", "MED", Reparto.CENTROCAMPO,
        ovrWeights = mapOf(
            Attr.INTERCETTAZIONE to 0.25,
            Attr.PASSAGGIO to 0.20,
            Attr.FISICO to 0.18,
            Attr.DIFESA to 0.17,
            Attr.POSIZIONAMENTO to 0.12,
            Attr.TECNICA to 0.08,
        ),
        zoneWeights = mapOf(Zone.MID_C to 0.68, Zone.DIF_C to 0.32),
    ),

    CC(
        "Centrocampista centrale", "CC", Reparto.CENTROCAMPO,
        ovrWeights = mapOf(
            Attr.PASSAGGIO to 0.26,
            Attr.TECNICA to 0.22,
            Attr.FISICO to 0.16,
            Attr.INTERCETTAZIONE to 0.14,
            Attr.DRIBBLING to 0.12,
            Attr.POSIZIONAMENTO to 0.10,
        ),
        zoneWeights = mapOf(Zone.MID_C to 0.72, Zone.ATT_C to 0.16, Zone.DIF_C to 0.12),
    ),

    TRQ(
        "Trequartista", "TRQ", Reparto.CENTROCAMPO,
        ovrWeights = mapOf(
            Attr.TECNICA to 0.26,
            Attr.PASSAGGIO to 0.22,
            Attr.DRIBBLING to 0.20,
            Attr.TIRO to 0.16,
            Attr.VELOCITA to 0.10,
            Attr.POSIZIONAMENTO to 0.06,
        ),
        zoneWeights = mapOf(Zone.ATT_C to 0.58, Zone.MID_C to 0.42),
    ),

    AD(
        "Ala destra", "AD", Reparto.ATTACCO,
        ovrWeights = mapOf(
            Attr.DRIBBLING to 0.26,
            Attr.VELOCITA to 0.24,
            Attr.TECNICA to 0.18,
            Attr.PASSAGGIO to 0.14,
            Attr.TIRO to 0.13,
            Attr.FISICO to 0.05,
        ),
        zoneWeights = mapOf(Zone.ATT_DX to 0.70, Zone.MID_DX to 0.30),
    ),

    AS(
        "Ala sinistra", "AS", Reparto.ATTACCO,
        ovrWeights = mapOf(
            Attr.DRIBBLING to 0.26,
            Attr.VELOCITA to 0.24,
            Attr.TECNICA to 0.18,
            Attr.PASSAGGIO to 0.14,
            Attr.TIRO to 0.13,
            Attr.FISICO to 0.05,
        ),
        zoneWeights = mapOf(Zone.ATT_SX to 0.70, Zone.MID_SX to 0.30),
    ),

    SP(
        "Seconda punta", "SP", Reparto.ATTACCO,
        ovrWeights = mapOf(
            Attr.TIRO to 0.24,
            Attr.DRIBBLING to 0.20,
            Attr.TECNICA to 0.20,
            Attr.VELOCITA to 0.16,
            Attr.PASSAGGIO to 0.12,
            Attr.FISICO to 0.08,
        ),
        zoneWeights = mapOf(Zone.ATT_C to 0.66, Zone.MID_C to 0.34),
    ),

    ATT(
        "Attaccante", "ATT", Reparto.ATTACCO,
        ovrWeights = mapOf(
            Attr.TIRO to 0.34,
            Attr.POSIZIONAMENTO to 0.18,
            Attr.FISICO to 0.16,
            Attr.VELOCITA to 0.14,
            Attr.TECNICA to 0.12,
            Attr.DRIBBLING to 0.06,
        ),
        zoneWeights = mapOf(Zone.ATT_C to 0.84, Zone.ATT_SX to 0.08, Zone.ATT_DX to 0.08),
    );

    val isGoalkeeper: Boolean get() = this == POR

    /** Gli attributi che contano per questo ruolo: sono quelli che la crescita fa salire. */
    val relevantAttributes: List<Attr> get() = ovrWeights.keys.toList()

    /** Overall del giocatore *se schierato in questo ruolo*. */
    fun overallOf(attributes: Attributes): Int =
        StrictMath.round(attributes.weightedMean(ovrWeights)).toInt()
            .coerceIn(Attributes.MIN, Attributes.MAX)

    companion object {
        val outfield: List<Position> = entries.filterNot { it.isGoalkeeper }

        fun byShort(short: String): Position? =
            entries.firstOrNull { it.short.equals(short, ignoreCase = true) }
    }
}

/**
 * Quali attributi contano in una data altezza di campo.
 *
 * Distinto da [Position.ovrWeights] di proposito: un difensore centrale spinto a
 * centrocampo contribuisce con i suoi attributi *da centrocampo*, non con il suo overall
 * da difensore. E' il motivo per cui un regista arretrato regge e un centravanti no.
 */
object BandWeights {

    private val defensive = mapOf(
        Attr.DIFESA to 0.34,
        Attr.INTERCETTAZIONE to 0.26,
        Attr.POSIZIONAMENTO to 0.22,
        Attr.FISICO to 0.18,
    )

    private val midfield = mapOf(
        Attr.TECNICA to 0.28,
        Attr.PASSAGGIO to 0.28,
        Attr.FISICO to 0.22,
        Attr.INTERCETTAZIONE to 0.22,
    )

    private val attacking = mapOf(
        Attr.DRIBBLING to 0.28,
        Attr.TIRO to 0.26,
        Attr.VELOCITA to 0.24,
        Attr.TECNICA to 0.22,
    )

    fun forBand(band: Band): Map<Attr, Double> = when (band) {
        Band.DIF -> defensive
        Band.MID -> midfield
        Band.ATT -> attacking
    }

    /** Quanto vale questo giocatore in quella altezza di campo, scala 1-99. */
    fun rate(attributes: Attributes, band: Band): Double =
        attributes.weightedMean(forBand(band))
}
