package dev.mfoot.android.data

import dev.mfoot.core.conversation.ConversationTopic
import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter

/** Un discorso aperto con un giocatore, con il fatto che lo ha aperto. */
data class OpenConversation(
    val id: Long,
    val playerId: Long,
    val topic: ConversationTopic,
    val cause: String,
    val spontaneous: Boolean,
)

/**
 * Lo storico dei colloqui di una squadra.
 *
 * Tiene insieme le due domande che la schermata si fa: **chi vuole parlarti adesso**, e
 * **da quanto non parli** con ognuno degli altri. La seconda serve all'attesa fra due
 * convocazioni a piacere, e si ricava dallo stesso elenco: chiederla al database una
 * seconda volta sarebbe una richiesta in piu' per un dato che si ha gia' in mano.
 */
data class Spogliatoio(
    val aperti: List<OpenConversation>,
    val ultimoColloquio: Map<Long, Int>,
) {
    fun apertoPer(playerId: Long): OpenConversation? = aperti.firstOrNull { it.playerId == playerId }

    companion object {
        val VUOTO = Spogliatoio(emptyList(), emptyMap())
    }
}

/**
 * I colloqui: leggerli, convocare, rispondere.
 *
 * ## Perche' l'argomento arriva dal server
 *
 * Prima lo decideva il telefono, da una soglia sul morale, ricalcolandolo a ogni apertura
 * della schermata: parlavi, il morale saliva, la soglia cambiava e compariva il colloquio
 * successivo. Adesso il colloquio e' una riga che qualcuno ha aperto per un motivo, e il
 * motivo viaggia con lei — cosi' la schermata puo' dire *perche'* quel giocatore vuole
 * parlarti, che e' l'unica cosa che rende la scelta della risposta una decisione.
 */
object ConversationRepository {

    private const val LIMITE = 300

    suspend fun load(clubId: Long): ApiResult<Spogliatoio> {
        val path = "/rest/v1/conversations?select=id,player_id,topic,cause,spontaneous," +
            "status,opened_on&club_id=eq.$clubId&order=opened_on.desc&limit=$LIMITE"

        return SupabaseApi.get(path).then { body ->
            val righe = JsonNode.parse(body).asList()

            val aperti = righe.mapNotNull { row ->
                if (row["status"].str("") != "APERTA") return@mapNotNull null
                val topic = ConversationTopic.entries
                    .firstOrNull { it.name == row["topic"].str("") }
                    ?: return@mapNotNull null

                OpenConversation(
                    id = row["id"].long(0),
                    playerId = row["player_id"].long(0),
                    topic = topic,
                    cause = row["cause"].str(""),
                    spontaneous = row["spontaneous"].bool(false),
                )
            }

            // L'elenco arriva gia' dal piu' recente: il primo che si incontra per ogni
            // giocatore e' il suo ultimo colloquio.
            val ultimo = mutableMapOf<Long, Int>()
            righe.forEach { row ->
                val id = row["player_id"].long(0)
                if (id !in ultimo) ultimo[id] = row["opened_on"].int(0)
            }

            ApiResult.Ok(Spogliatoio(aperti, ultimo))
        }.mapMissingTable()
    }

    /** Convocare qualcuno che non aveva niente da dire. */
    suspend fun convoca(playerId: Long, topic: ConversationTopic): ApiResult<Long> {
        val w = JsonWriter(128)
        w.beginObject()
        w.field("p_player_id", playerId)
        w.field("p_topic", topic.name)
        w.endObject()

        return SupabaseApi.rpc("open_conversation", w.toString()).then { body ->
            val node = risposta(body)
            if (node["ok"].bool(false)) ApiResult.Ok(node["id"].long(0))
            else ApiResult.Error(node["reason"].str("Non puoi convocarlo adesso."))
        }
    }

    /**
     * Rispondere, che chiude il discorso e paga il morale in un colpo solo.
     *
     * Erano due scritture separate — morale da una parte, colloquio dall'altra — e
     * potevano scollarsi: un colloquio chiuso senza effetto, o un effetto applicato due
     * volte allo stesso discorso. Adesso e' una transazione sola dentro il database.
     */
    suspend fun rispondi(conversationId: Long, tone: String, moraleDelta: Int): ApiResult<Int> {
        val w = JsonWriter(160)
        w.beginObject()
        w.field("p_conversation_id", conversationId)
        w.field("p_tone", tone)
        w.field("p_morale_delta", moraleDelta)
        w.endObject()

        return SupabaseApi.rpc("answer_conversation", w.toString()).then { body ->
            val node = risposta(body)
            if (node["ok"].bool(false)) ApiResult.Ok(node["morale"].int(0))
            else ApiResult.Error(node["reason"].str("Il discorso non è più aperto."))
        }
    }

    private fun risposta(body: String): JsonNode =
        JsonNode.parse(body).let { if (it.asList().isNotEmpty()) it[0] else it }

    /** L'errore tecnico diventa la migrazione che manca, che e' cio' che si puo' fare. */
    private fun ApiResult<Spogliatoio>.mapMissingTable(): ApiResult<Spogliatoio> = when {
        this is ApiResult.Error && message.contains("conversations") -> ApiResult.Error(
            "Lo spogliatoio ha bisogno della migrazione 0013_colloqui.sql, che non è " +
                "ancora stata applicata a questo database.",
        )
        else -> this
    }
}
