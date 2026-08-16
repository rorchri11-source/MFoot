package dev.mfoot.core.model

enum class StaffRole(val label: String) {
    ALLENATORE("Allenatore"),
    PREPARATORE("Preparatore atletico"),
    OSSERVATORE("Osservatore"),
}

/**
 * Un membro dello staff, valutato da 1 a 5 stelle e acquistato all'asta come i giocatori.
 *
 * Le tre figure agganciano tre sistemi diversi:
 * - l'**allenatore** moltiplica la crescita,
 * - il **preparatore** moltiplica il recupero di stamina, che con due partite al giorno
 *   e' quello che decide se puoi turnare o se bruci la rosa,
 * - l'**osservatore** stringe la forbice del potenziale stimato, cioe' quanto vedi
 *   davvero prima di puntare all'asta.
 *
 * Le scale non sono lineari: la differenza fra 4 e 5 stelle e' molto piu' grande di
 * quella fra 1 e 2, cosi' i top valgono davvero la guerra all'asta.
 */
data class Staff(
    val id: StaffId,
    val firstName: String,
    val lastName: String,
    val nationality: String,
    val role: StaffRole,
    val stars: Int,
) {

    init {
        require(stars in 1..5) { "stelle fuori scala: $stars" }
    }

    val fullName: String get() = "$firstName $lastName"

    val shortName: String
        get() = if (firstName.isEmpty()) lastName else "${firstName.first()}. $lastName"

    /** Moltiplicatore sull'esperienza guadagnata dai giocatori. Solo per [StaffRole.ALLENATORE]. */
    val growthMultiplier: Double
        get() = if (role == StaffRole.ALLENATORE) GROWTH_BY_STARS[stars - 1] else 1.0

    /** Moltiplicatore sul recupero di stamina. Solo per [StaffRole.PREPARATORE]. */
    val recoveryMultiplier: Double
        get() = if (role == StaffRole.PREPARATORE) RECOVERY_BY_STARS[stars - 1] else 1.0

    /**
     * Frazione della forbice di potenziale che l'osservatore riesce a eliminare.
     * Solo per [StaffRole.OSSERVATORE]. Nemmeno 5 stelle arrivano alla certezza:
     * il rischio all'asta non deve mai sparire del tutto.
     */
    val scoutingAccuracy: Double
        get() = if (role == StaffRole.OSSERVATORE) SCOUTING_BY_STARS[stars - 1] else 0.0

    override fun toString(): String = "$shortName (${role.label}, ${"*".repeat(stars)})"

    companion object {
        private val GROWTH_BY_STARS = doubleArrayOf(0.60, 0.80, 1.00, 1.35, 1.80)
        private val RECOVERY_BY_STARS = doubleArrayOf(0.70, 0.85, 1.00, 1.25, 1.55)
        private val SCOUTING_BY_STARS = doubleArrayOf(0.15, 0.30, 0.45, 0.60, 0.75)
    }
}
