package dev.mfoot.core.match

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.model.PlayerId

/**
 * Gli ordini condizionali, scritti e riletti.
 *
 * ## Perche' sta in `core` e non nell'app
 *
 * Perche' li **scrive** l'app e li **legge** il server, e sono la stessa cosa. Due
 * serializzazioni scritte in due posti diversi divergono al primo ritocco — basta
 * aggiungere un tipo di condizione da una parte — e il risultato non e' un errore: e' un
 * ordine che l'app mostra e il tick ignora, cioe' un manager convinto di aver preparato
 * la partita e una squadra che non fa niente.
 *
 * E' lo stesso motivo per cui [dev.mfoot.core.config.ConfigJson] sta in `core` con la sua
 * prova di andata e ritorno.
 *
 * ## Il formato
 *
 * ```json
 * [{"id":1,"priority":0,
 *   "trigger":{"tipo":"SOTTO_DAL","minuto":60},
 *   "azione":{"tipo":"CAMBIA_ASSETTO","valore":"OFFENSIVO"}}]
 * ```
 *
 * I nomi dei tipi sono stringhe scelte, non `Class.simpleName`: rinominare una classe
 * Kotlin non deve rendere illeggibili gli ordini gia' salvati sul database di chi sta
 * giocando.
 */
object OrderJson {

    // --------------------------------------------------------------------- scrittura

    fun write(orders: List<ConditionalOrder>): String {
        val w = JsonWriter(512)
        w.beginArray()
        orders.forEach { order ->
            w.beginObject()
            w.field("id", order.id)
            w.field("priority", order.priority)
            w.objectField("trigger")
            writeTrigger(w, order.trigger)
            w.endObject()
            w.objectField("azione")
            writeAction(w, order.action)
            w.endObject()
            w.endObject()
        }
        w.endArray()
        return w.toString()
    }

    private fun writeTrigger(w: JsonWriter, trigger: OrderTrigger) {
        when (trigger) {
            is OrderTrigger.SottoDalMinuto -> {
                w.field("tipo", "SOTTO_DAL")
                w.field("minuto", trigger.minute)
            }

            is OrderTrigger.InVantaggioDiDalMinuto -> {
                w.field("tipo", "VANTAGGIO_DAL")
                w.field("gol", trigger.goals)
                w.field("minuto", trigger.minute)
            }

            is OrderTrigger.PariDalMinuto -> {
                w.field("tipo", "PARI_DAL")
                w.field("minuto", trigger.minute)
            }

            is OrderTrigger.DalMinuto -> {
                w.field("tipo", "DAL_MINUTO")
                w.field("minuto", trigger.minute)
            }

            is OrderTrigger.GiocatoreAmmonito -> {
                w.field("tipo", "AMMONITO")
                w.field("giocatore", trigger.playerId.value)
            }

            is OrderTrigger.StaminaSotto -> {
                w.field("tipo", "STAMINA_SOTTO")
                w.field("giocatore", trigger.playerId.value)
                w.field("soglia", trigger.threshold)
            }

            is OrderTrigger.QualcunoInRiserva -> {
                w.field("tipo", "RISERVA")
                w.field("soglia", trigger.threshold)
            }
        }
    }

    private fun writeAction(w: JsonWriter, action: OrderAction) {
        when (action) {
            is OrderAction.Sostituisci -> {
                w.field("tipo", "SOSTITUISCI")
                w.field("esce", action.out.value)
                w.field("entra", action.entra.value)
            }

            is OrderAction.CambiaAssetto -> {
                w.field("tipo", "ASSETTO")
                w.field("valore", action.stance.name)
            }

            is OrderAction.CambiaRitmo -> {
                w.field("tipo", "RITMO")
                w.field("valore", action.tempo.name)
            }

            is OrderAction.CambiaPressing -> {
                w.field("tipo", "PRESSING")
                w.field("valore", action.pressing.name)
            }

            is OrderAction.CambiaAmpiezza -> {
                w.field("tipo", "AMPIEZZA")
                w.field("valore", action.width.name)
            }
        }
    }

    // ----------------------------------------------------------------------- lettura

    /**
     * Rilegge gli ordini, **saltando quelli che non si capiscono**.
     *
     * Un ordine scritto da una versione piu' nuova dell'app non deve impedire di giocare
     * la partita: si perde quell'ordine, non la formazione. E' la stessa scelta che il
     * progetto fa ovunque — un dato incomprensibile vale come dato assente, mai come
     * errore che ferma tutto.
     */
    fun read(node: JsonNode): List<ConditionalOrder> =
        node.asList().mapNotNull { row ->
            val trigger = readTrigger(row["trigger"]) ?: return@mapNotNull null
            val action = readAction(row["azione"]) ?: return@mapNotNull null
            ConditionalOrder(
                id = row["id"].int(0),
                trigger = trigger,
                action = action,
                priority = row["priority"].int(0),
            )
        }
            // Due ordini con lo stesso id fanno fallire `TeamSetup`, e un `require` che
            // esplode dentro il tick ferma la partita di tutti: si tiene il primo.
            .distinctBy { it.id }

    fun read(json: String): List<ConditionalOrder> = read(JsonNode.parse(json))

    private fun readTrigger(node: JsonNode): OrderTrigger? = when (node["tipo"].strOrNull()) {
        "SOTTO_DAL" -> OrderTrigger.SottoDalMinuto(node["minuto"].int(60))
        "VANTAGGIO_DAL" -> OrderTrigger.InVantaggioDiDalMinuto(
            goals = node["gol"].int(1),
            minute = node["minuto"].int(60),
        )
        "PARI_DAL" -> OrderTrigger.PariDalMinuto(node["minuto"].int(60))
        "DAL_MINUTO" -> OrderTrigger.DalMinuto(node["minuto"].int(60))
        "AMMONITO" -> node["giocatore"].long(0).takeIf { it != 0L }
            ?.let { OrderTrigger.GiocatoreAmmonito(PlayerId(it)) }
        "STAMINA_SOTTO" -> node["giocatore"].long(0).takeIf { it != 0L }
            ?.let { OrderTrigger.StaminaSotto(PlayerId(it), node["soglia"].int(40)) }
        "RISERVA" -> OrderTrigger.QualcunoInRiserva(node["soglia"].int(40))
        else -> null
    }

    private fun readAction(node: JsonNode): OrderAction? = when (node["tipo"].strOrNull()) {
        "SOSTITUISCI" -> {
            val esce = node["esce"].long(0)
            val entra = node["entra"].long(0)
            if (esce == 0L || entra == 0L) null
            else OrderAction.Sostituisci(PlayerId(esce), PlayerId(entra))
        }
        "ASSETTO" -> OrderAction.CambiaAssetto(node["valore"].enum(TacticalStance.EQUILIBRATO))
        "RITMO" -> OrderAction.CambiaRitmo(node["valore"].enum(TacticalTempo.NORMALE))
        "PRESSING" -> OrderAction.CambiaPressing(node["valore"].enum(TacticalPressing.MEDIO))
        "AMPIEZZA" -> OrderAction.CambiaAmpiezza(node["valore"].enum(TacticalWidth.NORMALE))
        else -> null
    }
}
