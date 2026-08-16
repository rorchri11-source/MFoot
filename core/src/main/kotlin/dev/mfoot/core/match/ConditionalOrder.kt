package dev.mfoot.core.match

import dev.mfoot.core.model.PlayerId

/**
 * Lo stato della partita al momento in cui si valuta un ordine.
 * I gol sono sempre visti dal punto di vista della squadra che ha dato l'ordine.
 */
data class OrderContext(
    val minute: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val lineup: Lineup,
    val bookedPlayers: Set<PlayerId>,
) {
    val goalDifference: Int get() = goalsFor - goalsAgainst
    val isLosing: Boolean get() = goalDifference < 0
    val isDrawing: Boolean get() = goalDifference == 0
}

/**
 * Condizione che fa scattare un ordine.
 *
 * Gli ordini condizionali sono il modo in cui il manager ha voce in capitolo **senza
 * dover essere davanti al telefono alle 21**. Chi prepara bene la partita e' premiato,
 * chi quella sera lavora non viene tagliato fuori: le sue regole girano lo stesso.
 *
 * E' anche cio' che permette al server di pre-calcolare la partita in due sole
 * simulazioni (primo tempo e secondo tempo) invece di restare acceso a fare tick.
 */
sealed interface OrderTrigger {

    fun matches(context: OrderContext): Boolean

    /** Descrizione leggibile, per l'interfaccia. */
    fun describe(): String

    data class SottoDalMinuto(val minute: Int) : OrderTrigger {
        override fun matches(context: OrderContext) =
            context.minute >= minute && context.isLosing

        override fun describe() = "Se sono sotto dal $minute'"
    }

    data class InVantaggioDiDalMinuto(val goals: Int, val minute: Int) : OrderTrigger {
        override fun matches(context: OrderContext) =
            context.minute >= minute && context.goalDifference >= goals

        override fun describe() = "Se vinco di $goals dal $minute'"
    }

    data class PariDalMinuto(val minute: Int) : OrderTrigger {
        override fun matches(context: OrderContext) =
            context.minute >= minute && context.isDrawing

        override fun describe() = "Se sono in pareggio dal $minute'"
    }

    data class DalMinuto(val minute: Int) : OrderTrigger {
        override fun matches(context: OrderContext) = context.minute >= minute
        override fun describe() = "Al $minute'"
    }

    data class GiocatoreAmmonito(val playerId: PlayerId) : OrderTrigger {
        override fun matches(context: OrderContext) =
            playerId in context.bookedPlayers && context.lineup.contains(playerId)

        override fun describe() = "Se il giocatore prende un giallo"
    }

    data class StaminaSotto(val playerId: PlayerId, val threshold: Int) : OrderTrigger {
        override fun matches(context: OrderContext): Boolean {
            val player = context.lineup.slotOf(playerId)?.player ?: return false
            return player.stamina < threshold
        }

        override fun describe() = "Se scende sotto $threshold di stamina"
    }

    /** Scatta quando un qualsiasi titolare e' in riserva: utile all'AI per turnare. */
    data class QualcunoInRiserva(val threshold: Int) : OrderTrigger {
        override fun matches(context: OrderContext) =
            context.lineup.slots.any { it.player.stamina < threshold }

        override fun describe() = "Se qualcuno scende sotto $threshold di stamina"
    }
}

/** Cosa succede quando l'ordine scatta. */
sealed interface OrderAction {

    fun describe(): String

    data class Sostituisci(val out: PlayerId, val entra: PlayerId) : OrderAction {
        override fun describe() = "Sostituzione"
    }

    data class CambiaAssetto(val stance: TacticalStance) : OrderAction {
        override fun describe() = "Passa a ${stance.label.lowercase()}"
    }

    data class CambiaRitmo(val tempo: TacticalTempo) : OrderAction {
        override fun describe() = "Ritmo ${tempo.label.lowercase()}"
    }

    data class CambiaPressing(val pressing: TacticalPressing) : OrderAction {
        override fun describe() = "Pressing ${pressing.label.lowercase()}"
    }

    data class CambiaAmpiezza(val width: TacticalWidth) : OrderAction {
        override fun describe() = "Gioco ${width.label.lowercase()}"
    }
}

/**
 * Un ordine condizionale completo.
 *
 * Scatta **una volta sola**: senza questo vincolo, "se sono sotto dal 70' passa a
 * offensivo" si riattiverebbe a ogni azione dal 70' in poi, e una sostituzione
 * verrebbe tentata all'infinito.
 */
data class ConditionalOrder(
    val id: Int,
    val trigger: OrderTrigger,
    val action: OrderAction,
    /** Ordini con priorita' piu' bassa vengono valutati prima. */
    val priority: Int = 0,
) {
    fun describe(): String = "${trigger.describe()} -> ${action.describe()}"
}

/**
 * Il piano partita di una squadra: formazione, tattica e ordini condizionali.
 *
 * E' tutto ciò che il motore riceve da un lato del campo.
 */
data class TeamSetup(
    val clubId: dev.mfoot.core.model.ClubId,
    val name: String,
    val lineup: Lineup,
    val tactics: Tactics = Tactics.DEFAULT,
    val orders: List<ConditionalOrder> = emptyList(),
    val coachStars: Int = 3,
) {
    init {
        require(coachStars in 1..5) { "stelle allenatore fuori scala: $coachStars" }
        require(orders.map { it.id }.toSet().size == orders.size) {
            "ci sono ordini condizionali con lo stesso id"
        }
    }

    val sortedOrders: List<ConditionalOrder> get() = orders.sortedBy { it.priority }
}
