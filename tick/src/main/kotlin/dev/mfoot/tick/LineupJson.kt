package dev.mfoot.tick

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.Lineup
import dev.mfoot.core.match.TacticalPressing
import dev.mfoot.core.match.TacticalStance
import dev.mfoot.core.match.TacticalTempo
import dev.mfoot.core.match.TacticalWidth
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.model.Position

/**
 * La formazione salvata dal proprietario, come sta sulla tabella `lineups`.
 *
 * ## Perche' niente qui lancia un'eccezione
 *
 * Questo JSON lo ha scritto il telefono di qualcuno, giorni prima, con una versione
 * dell'app che magari non e' quella di adesso. Se una chiave mancante o rinominata
 * facesse fallire la lettura, il tick annullerebbe l'intera transazione della lega: una
 * formazione scritta male in un club fermerebbe il campionato di tutti gli altri.
 *
 * Quindi ogni lettura ha un ripiego e la parte incomprensibile viene semplicemente
 * ignorata. Chi chiama vede meno titolari di undici e li completa da solo.
 */
object LineupJson {

    /** Un titolare come lo ha salvato il client: chi, e in quale casella del modulo. */
    data class SavedSlot(val playerId: Long, val position: Position?)

    /**
     * I titolari salvati, nell'ordine in cui il client li ha scritti.
     *
     * Il ruolo e' facoltativo: se manca o non esiste piu' fra i [Position], chi chiama
     * mette il giocatore nella prima casella libera del modulo. Perdere il ruolo esatto
     * di un titolare e' molto meno grave che non schierarlo.
     */
    fun slots(raw: String?): List<SavedSlot> {
        val root = parse(raw) ?: return emptyList()
        return root.asList().mapNotNull { node ->
            // Lo schema dichiara `player_id`, ma tutto il resto del JSON di gioco e' in
            // camelCase: accettare le due forme costa una riga e evita che una
            // formazione venga ignorata in silenzio solo per un trattino basso.
            val id = node["player_id"].long(0L).takeIf { it != 0L }
                ?: node["playerId"].long(0L).takeIf { it != 0L }
                ?: return@mapNotNull null

            val position = node["position"].strOrNull()
                ?.let { name -> Position.entries.firstOrNull { it.name == name } }

            SavedSlot(id, position)
        }
    }

    /** La panchina salvata: solo numeri, quindi basta leggerli. */
    fun bench(raw: String?): List<Long> {
        val root = parse(raw) ?: return emptyList()
        return root.asList().map { it.long(0L) }.filter { it != 0L }
    }

    /**
     * Il modulo salvato.
     *
     * Si accetta sia il nome dell'enum (`F_4_3_3`, che e' quello che scrive il database
     * come valore predefinito) sia l'etichetta mostrata all'utente (`4-3-3`): il client
     * puo' legittimamente salvare l'una o l'altra e indovinare quale sarebbe un modo di
     * far giocare la squadra con un modulo che nessuno ha scelto.
     */
    fun formation(name: String?): Formation? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return Formation.entries.firstOrNull { it.name == trimmed } ?: Formation.byLabel(trimmed)
    }

    /**
     * L'assetto tattico.
     *
     * Un assetto mancante non e' un errore da segnalare: vale l'equilibrato, che e'
     * esattamente quello che si aspetta chi non ha toccato niente.
     */
    fun tactics(raw: String?): Tactics {
        val root = parse(raw) ?: return Tactics.DEFAULT
        return Tactics(
            stance = root["stance"].enum(TacticalStance.EQUILIBRATO),
            width = root["width"].enum(TacticalWidth.NORMALE),
            tempo = root["tempo"].enum(TacticalTempo.NORMALE),
            pressing = root["pressing"].enum(TacticalPressing.MEDIO),
        )
    }

    /**
     * I titolari, nel formato che legge [slots].
     *
     * Serve ai club del computer, che dalla loro prima riga di codice non avevano mai
     * scritto una formazione: il tick gliene costruiva una al volo per giocare e la
     * buttava. Chi apriva il campo di un'AI non trovava niente, e la segnalazione
     * «non schierano, non fanno nessuna scelta tecnica» era la descrizione esatta.
     *
     * Si scrive `player_id`, la forma dichiarata dallo schema, non la variante camelCase
     * che [slots] tollera in lettura: tollerare in entrata e' prudenza, scrivere due
     * dialetti sarebbe crearne uno nuovo.
     */
    fun writeSlots(lineup: Lineup): String {
        val w = JsonWriter(1024)
        w.beginArray()
        lineup.slots.forEach { slot ->
            w.beginObject()
            w.field("player_id", slot.player.id.value)
            w.field("position", slot.position.name)
            w.endObject()
        }
        w.endArray()
        return w.toString()
    }

    /** L'assetto, nel formato che legge [tactics]. */
    fun writeTactics(tactics: Tactics): String {
        val w = JsonWriter(256)
        w.beginObject()
        w.field("stance", tactics.stance.name)
        w.field("width", tactics.width.name)
        w.field("tempo", tactics.tempo.name)
        w.field("pressing", tactics.pressing.name)
        w.endObject()
        return w.toString()
    }

    /** Null anche quando il testo non e' JSON valido: vedi il commento in testa. */
    private fun parse(raw: String?): JsonNode? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { JsonNode.parse(text) }.getOrNull()
    }
}
