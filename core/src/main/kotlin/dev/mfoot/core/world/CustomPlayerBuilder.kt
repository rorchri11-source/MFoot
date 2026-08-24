package dev.mfoot.core.world

import dev.mfoot.core.config.CustomPlayerConfig
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.Trait

/**
 * Il giocatore che il proprietario costruisce da zero.
 *
 * ## Perche' esiste
 *
 * E' l'unico giocatore che non si puo' vendere ne' svincolare, solo prestare, e che deve
 * scendere in campo titolare. In un mondo di nomi inventati serve qualcuno per cui si
 * tifi davvero: non lo si e' comprato all'asta, lo si e' fatto.
 *
 * ## Il conto lo tiene il server, non l'interfaccia
 *
 * Le funzioni qui dentro sono pure e stanno in `core`, quindi girano identiche sul
 * telefono mentre si trascina uno slider e nel database quando si conferma. Non e' un
 * dettaglio: se il budget lo controllasse solo l'app, chiunque sapesse comporre una
 * richiesta HTTP potrebbe presentarsi con un 93 al primo giorno.
 */
object CustomPlayerBuilder {

    /**
     * Il progetto in corso di costruzione.
     *
     * [increments] contiene i punti spesi **sopra la base**, non i valori finali: cosi'
     * cambiare la base o gli scaglioni di costo non manda in pezzi un progetto a meta'.
     */
    data class Draft(
        val firstName: String = "",
        val lastName: String = "",
        val nationality: String = "",
        val position: Position = Position.CC,
        val secondaryPositions: List<Position> = emptyList(),
        val increments: Map<Attr, Int> = emptyMap(),
        val weakFoot: Int = 1,
        val skillStars: Int = 1,
        val age: Int = 18,
        val traits: Set<Trait> = emptySet(),
    )

    // ------------------------------------------------------------------------- attributi

    /** Da dove si parte: nel ruolo scelto l'overall e' esattamente quello di base. */
    fun baseAttributes(position: Position, config: CustomPlayerConfig): Attributes {
        val values = Attr.entries.associateWith { attr ->
            when {
                attr in position.ovrWeights -> config.baseOverall
                attr.goalkeeperOnly != position.isGoalkeeper -> config.wrongSideBase
                else -> config.offRoleBase
            }
        }
        return Attributes.fromMap(values)
    }

    fun attributesOf(draft: Draft, config: CustomPlayerConfig): Attributes {
        var attributes = baseAttributes(draft.position, config)
        draft.increments.forEach { (attr, points) ->
            if (points != 0) attributes = attributes.plus(attr, points)
        }
        return attributes
    }

    fun overallOf(draft: Draft, config: CustomPlayerConfig): Int =
        draft.position.overallOf(attributesOf(draft, config))

    // ---------------------------------------------------------------------------- costi

    /**
     * Quanto costa il prossimo punto su [attr].
     *
     * Serve all'interfaccia tanto quanto al calcolo: mostrare "+1 = 3 punti" accanto allo
     * slider e' cio' che rende comprensibile perche' il budget si svuota cosi' in fretta.
     * Restituisce null se quell'attributo e' gia' al massimo della scala.
     */
    fun costOfNextPoint(draft: Draft, attr: Attr, config: CustomPlayerConfig): Int? {
        val current = attributesOf(draft, config)[attr]
        if (current >= Attributes.MAX) return null
        return costOfPointAt(current, config)
    }

    /** Il costo del punto che porta un attributo da [from] a `from + 1`. */
    fun costOfPointAt(from: Int, config: CustomPlayerConfig): Int =
        config.costTiers.firstOrNull { from < it.upTo }?.cost
            ?: config.costTiers.lastOrNull()?.cost
            ?: 1

    /** Quanto costano gli incrementi su un singolo attributo. */
    fun costOfAttribute(draft: Draft, attr: Attr, config: CustomPlayerConfig): Int {
        val base = baseAttributes(draft.position, config)[attr]
        val points = draft.increments[attr] ?: 0
        if (points <= 0) return 0
        return (0 until points).sumOf { costOfPointAt(base + it, config) }
    }

    /** Le stelle costano a scaglione fisso: e' la parte del budget piu' facile da capire. */
    fun costOfStars(draft: Draft, config: CustomPlayerConfig): Int =
        ((draft.weakFoot - config.startingStars) + (draft.skillStars - config.startingStars))
            .coerceAtLeast(0) * config.starCost

    fun totalCost(draft: Draft, config: CustomPlayerConfig): Int =
        Attr.entries.sumOf { costOfAttribute(draft, it, config) } + costOfStars(draft, config)

    fun remaining(draft: Draft, config: CustomPlayerConfig): Int =
        config.skillBudget - totalCost(draft, config)

    /** Si puo' aggiungere un punto qui senza sforare? */
    fun canRaise(draft: Draft, attr: Attr, config: CustomPlayerConfig): Boolean {
        val cost = costOfNextPoint(draft, attr, config) ?: return false
        return cost <= remaining(draft, config)
    }

    fun raise(draft: Draft, attr: Attr, config: CustomPlayerConfig): Draft =
        if (!canRaise(draft, attr, config)) {
            draft
        } else {
            draft.copy(increments = draft.increments + (attr to (draft.increments[attr] ?: 0) + 1))
        }

    fun lower(draft: Draft, attr: Attr, config: CustomPlayerConfig): Draft {
        val points = draft.increments[attr] ?: 0
        if (points <= 0) return draft
        return draft.copy(increments = draft.increments + (attr to points - 1))
    }

    /** Cambiare ruolo azzera gli incrementi: le basi di partenza sono altre. */
    fun withPosition(draft: Draft, position: Position): Draft =
        if (position == draft.position) draft
        else draft.copy(position = position, increments = emptyMap(), secondaryPositions = emptyList())

    // -------------------------------------------------------------------------- verifica

    /**
     * Tutto quello che non va, in italiano, pronto da mostrare.
     *
     * Una lista invece di un booleano perche' l'interfaccia deve poter dire **cosa**
     * manca, e perche' il server usa la stessa funzione per rifiutare con un motivo.
     */
    fun problems(draft: Draft, config: CustomPlayerConfig): List<String> {
        val problems = mutableListOf<String>()

        if (draft.firstName.isBlank() || draft.lastName.isBlank()) {
            problems += "Serve nome e cognome."
        }
        if (draft.nationality.isBlank()) {
            problems += "Serve una nazionalità."
        }
        if (draft.age !in config.minAge..config.maxAge) {
            problems += "L'età deve stare fra ${config.minAge} e ${config.maxAge} anni."
        }
        if (draft.weakFoot !in 1..5 || draft.skillStars !in 1..5) {
            problems += "Le stelle vanno da 1 a 5."
        }
        if (draft.increments.values.any { it < 0 }) {
            problems += "Non si possono togliere punti sotto la base."
        }
        if (draft.position in draft.secondaryPositions) {
            problems += "Il ruolo secondario non può essere quello principale."
        }

        val speso = totalCost(draft, config)
        if (speso > config.skillBudget) {
            problems += "Hai speso $speso punti su ${config.skillBudget}."
        }

        val attributi = attributesOf(draft, config)
        if (Attr.entries.any { attributi[it] > Attributes.MAX }) {
            problems += "Un attributo supera il massimo della scala."
        }

        return problems
    }

    fun isValid(draft: Draft, config: CustomPlayerConfig): Boolean =
        problems(draft, config).isEmpty()

    // --------------------------------------------------------------------- costruzione

    /**
     * Il giocatore vero.
     *
     * Il potenziale non e' nascosto come per i generati: e' il **proprio** giocatore, e
     * sapere fin dove puo' arrivare e' esattamente la ragione per cui vale la pena
     * schierarlo quando ancora non e' pronto.
     */
    fun build(draft: Draft, id: PlayerId, config: CustomPlayerConfig): Player {
        require(isValid(draft, config)) {
            "progetto non valido: ${problems(draft, config).joinToString("; ")}"
        }

        val attributes = attributesOf(draft, config)
        val overall = draft.position.overallOf(attributes)
        val potential = (overall + config.potentialBonus).coerceAtMost(config.potentialCeiling)

        return Player(
            id = id,
            firstName = draft.firstName.trim(),
            lastName = draft.lastName.trim(),
            nationality = draft.nationality.trim(),
            age = draft.age,
            primaryPosition = draft.position,
            secondaryPositions = draft.secondaryPositions,
            attributes = attributes,
            weakFoot = draft.weakFoot,
            skillStars = draft.skillStars,
            potentialMin = potential,
            potentialMax = potential,
            traits = draft.traits,
            isCustom = true,
        )
    }
}
