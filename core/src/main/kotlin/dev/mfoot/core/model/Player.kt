package dev.mfoot.core.model

/**
 * Un giocatore.
 *
 * Immutabile: i motori restituiscono copie aggiornate invece di mutare in loco. Costa
 * qualche allocazione in piu' ma rende impossibile la classe di bug in cui una
 * simulazione "di prova" sporca lo stato del mondo.
 *
 * ## Il potenziale e' nascosto
 *
 * [potentialMin] e [potentialMax] non devono **mai** arrivare al client cosi' come sono.
 * Il client riceve una forbice stimata che si stringe con i minuti giocati o con lo
 * scouting. E' il meccanismo che sostituisce l'emozione dei nomi reali: all'asta si
 * scommette, non si compra una certezza.
 *
 * Vale anche per l'AI: valuta i giocatori sulla stima, mai sul valore vero. Un'AI che
 * conoscesse i potenziali reali comprerebbe sempre i giovani giusti e sembrerebbe truccata.
 */
data class Player(
    val id: PlayerId,
    val firstName: String,
    val lastName: String,
    val nationality: String,
    val age: Int,
    val primaryPosition: Position,
    val secondaryPositions: List<Position> = emptyList(),
    val attributes: Attributes,
    /** Piede debole, 1-5 stelle. Nel budget del player custom ogni stella costa 10 punti. */
    val weakFoot: Int = 3,
    /** Tecnica, 1-5 stelle. Stessa economia del piede debole. */
    val skillStars: Int = 3,
    val potentialMin: Int,
    val potentialMax: Int,
    val traits: Set<Trait> = emptySet(),
    val stamina: Int = MAX_STAMINA,
    val morale: Int = DEFAULT_MORALE,
    /** Forma del momento, -5..+5. */
    val form: Int = 0,
    /** Esperienza accumulata verso il prossimo punto attributo. */
    val experience: Double = 0.0,
    /** Il giocatore unico creato dal proprietario del club: non vendibile ne' svincolabile. */
    val isCustom: Boolean = false,
    val injuredUntil: MatchDay? = null,
) {

    init {
        require(age > 0) { "eta' non valida: $age" }
        require(weakFoot in 1..5) { "piede debole fuori scala: $weakFoot" }
        require(skillStars in 1..5) { "stelle tecnica fuori scala: $skillStars" }
        require(potentialMin <= potentialMax) {
            "potenziale incoerente: min $potentialMin > max $potentialMax"
        }
    }

    val fullName: String get() = "$firstName $lastName"

    /** "M. Ferrero" — la forma usata nel feed della partita e nelle liste. */
    val shortName: String
        get() = if (firstName.isEmpty()) lastName else "${firstName.first()}. $lastName"

    /** Overall nel ruolo naturale. */
    val overall: Int get() = primaryPosition.overallOf(attributes)

    val isGoalkeeper: Boolean get() = primaryPosition.isGoalkeeper

    fun isInjured(on: MatchDay): Boolean = injuredUntil != null && injuredUntil >= on

    /** Sa giocare in [position] senza penalita' importanti? */
    fun canPlay(position: Position): Boolean =
        position == primaryPosition || position in secondaryPositions

    /**
     * Overall se schierato in [position], con la penalita' di adattamento.
     *
     * Un attaccante messo in difesa non e' semplicemente "meno utile": e' un buco.
     * La penalita' cresce con la distanza dal ruolo naturale, e schierare un giocatore
     * di movimento in porta (o viceversa) e' punito duramente perche' deve restare
     * una scelta disperata, non una furbizia.
     */
    fun overallAt(position: Position): Int {
        val base = position.overallOf(attributes)
        val penalty = when {
            position == primaryPosition -> 0
            position.isGoalkeeper != primaryPosition.isGoalkeeper -> 40
            position in secondaryPositions -> 3
            position.reparto == primaryPosition.reparto -> 8
            adjacentReparto(position.reparto, primaryPosition.reparto) -> 14
            else -> 22
        }
        return (base - penalty).coerceIn(Attributes.MIN, Attributes.MAX)
    }

    /**
     * Penalita' di adattamento come moltiplicatore, per i rating di zona.
     * 1.0 = nel suo ruolo, valori piu' bassi = fuori ruolo.
     */
    fun positionalFitness(position: Position): Double = when {
        position == primaryPosition -> 1.0
        position.isGoalkeeper != primaryPosition.isGoalkeeper -> 0.35
        position in secondaryPositions -> 0.95
        position.reparto == primaryPosition.reparto -> 0.88
        adjacentReparto(position.reparto, primaryPosition.reparto) -> 0.78
        else -> 0.65
    }

    fun withAttributes(newAttributes: Attributes): Player = copy(attributes = newAttributes)

    fun withStamina(value: Int): Player = copy(stamina = value.coerceIn(0, MAX_STAMINA))

    fun withMorale(value: Int): Player = copy(morale = value.coerceIn(0, MAX_MORALE))

    fun withForm(value: Int): Player = copy(form = value.coerceIn(-5, 5))

    override fun toString(): String =
        "$shortName (${primaryPosition.short}, $overall, ${age}a)"

    companion object {
        const val MAX_STAMINA = 100
        const val MAX_MORALE = 100
        const val DEFAULT_MORALE = 60

        /** Overall di partenza del player custom, prima di distribuire il budget abilita'. */
        const val CUSTOM_BASE_OVERALL = 65

        /** Punti abilita' che il proprietario distribuisce sul suo giocatore. */
        const val CUSTOM_SKILL_BUDGET = 100

        /** Costo in punti budget di una stella di piede debole o tecnica. */
        const val CUSTOM_STAR_COST = 10

        private fun adjacentReparto(a: Reparto, b: Reparto): Boolean {
            val order = listOf(Reparto.PORTIERE, Reparto.DIFESA, Reparto.CENTROCAMPO, Reparto.ATTACCO)
            return kotlin.math.abs(order.indexOf(a) - order.indexOf(b)) == 1
        }
    }
}
