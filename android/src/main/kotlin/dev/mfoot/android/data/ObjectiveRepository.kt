package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.objectives.Objective
import dev.mfoot.core.objectives.ObjectiveKind
import dev.mfoot.core.objectives.ObjectiveStatus

/**
 * Un obiettivo come sta sul database: la regola, piu' com'e' finita.
 *
 * [objective] e' la stessa struttura di `core`, ricostruita: cosi' la descrizione da
 * mostrare la produce il regolamento e non la schermata, e non esistono due modi di
 * scrivere la stessa richiesta.
 */
data class ObjectiveRow(
    val id: Long,
    val clubId: Long,
    val season: Int,
    val objective: Objective,
    val status: ObjectiveStatus,
    /** Quanto e' stato effettivamente pagato. Zero su un obiettivo fallito. */
    val paid: Int,
) {
    val descrizione: String get() = objective.descrizione
    val premio: Int get() = objective.reward
}

/**
 * Gli obiettivi di stagione.
 *
 * ## Perche' si leggono quelli di tutti, non solo i propri
 *
 * Perche' spiegano il mercato. Sapere che l'avversario ha in ballo un premio grosso se non
 * retrocede spiega perche' a marzo compra un difensore invece di vendere, e senza quella
 * informazione le sue mosse sembrano casuali. Le Row Level Security li aprono a tutta la
 * lega di proposito.
 */
object ObjectiveRepository {

    /** Tutti gli obiettivi della lega, i piu' recenti per primi. */
    suspend fun all(leagueId: Long): List<ObjectiveRow> {
        val path = "/rest/v1/club_objectives?select=id,club_id,season,kind,target,reward," +
            "seasons,status,paid&league_id=eq.$leagueId&order=season.desc,id"

        return when (val esito = SupabaseApi.get(path)) {
            // La migrazione non c'e': la lega non ha obiettivi, che e' esattamente cio'
            // che era vero prima. Meglio una sezione che non compare di una schermata che
            // si rompe.
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().mapNotNull { riga ->
                val kind = ObjectiveKind.entries
                    .firstOrNull { it.name == riga["kind"].str("") }
                    ?: return@mapNotNull null

                ObjectiveRow(
                    id = riga["id"].long(0),
                    clubId = riga["club_id"].long(0),
                    season = riga["season"].int(1),
                    objective = Objective(
                        kind = kind,
                        target = riga["target"].int(0),
                        reward = riga["reward"].int(0),
                        seasons = riga["seasons"].int(1).coerceAtLeast(1),
                    ),
                    status = ObjectiveStatus.entries
                        .firstOrNull { it.name == riga["status"].str("") }
                        ?: ObjectiveStatus.IN_CORSO,
                    paid = riga["paid"].int(0),
                )
            }
        }
    }

    /**
     * Chi ha giocato almeno un minuto per questo club.
     *
     * ## Perche' «almeno un minuto» e non «e' in rosa»
     *
     * Perche' l'obiettivo che la usa chiede di **far giocare** i ragazzi, non di
     * tesserarli. Tenere tre diciottenni in panchina tutta la stagione e' esattamente la
     * cosa che l'obiettivo esiste per rendere insufficiente: comprarli costa poco, farli
     * scendere in campo costa punti, e il premio paga la seconda.
     *
     * Torna gli id, non un conteggio: chi chiama ha gia' le eta' in memoria e sa quali
     * contare.
     */
    suspend fun chiHaGiocato(clubId: Long): Set<Long> {
        val path = "/rest/v1/appearances?select=player_id&club_id=eq.$clubId&minutes=gt.0"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> emptySet()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList()
                .map { it["player_id"].long(0) }
                .toSet()
        }
    }

    /** Assegna gli obiettivi a tutta la lega in un colpo solo. Solo l'amministratore. */
    suspend fun assign(
        leagueId: Long,
        season: Int,
        items: List<Pair<Long, Objective>>,
    ): ApiResult<Int> {
        val w = JsonWriter(4 * 1024)
        w.beginObject()
        w.field("p_league_id", leagueId)
        w.field("p_season", season)
        w.arrayField("p_items")
        items.forEach { (clubId, o) ->
            w.beginObject()
            w.field("club_id", clubId)
            w.field("kind", o.kind.name)
            w.field("target", o.target)
            w.field("reward", o.reward)
            w.field("seasons", o.seasons)
            w.endObject()
        }
        w.endArray()
        w.endObject()

        return SupabaseApi.rpc("assign_objectives", w.toString()).then { body ->
            val node = JsonNode.parse(body).let { if (it.asList().isNotEmpty()) it[0] else it }
            if (node["ok"].bool(false)) {
                ApiResult.Ok(node["assegnati"].int(0))
            } else {
                ApiResult.Error(node["reason"].str("Assegnazione rifiutata."))
            }
        }.mancaLaMigrazione()
    }

    /** Chiude un obiettivo col verdetto calcolato da `core`, e paga se raggiunto. */
    suspend fun settle(objectiveId: Long, status: ObjectiveStatus): ApiResult<Int> {
        val w = JsonWriter(128)
        w.beginObject()
        w.field("p_objective_id", objectiveId)
        w.field("p_status", status.name)
        w.endObject()

        return SupabaseApi.rpc("settle_objective", w.toString()).then { body ->
            val node = JsonNode.parse(body).let { if (it.asList().isNotEmpty()) it[0] else it }
            if (node["ok"].bool(false)) {
                ApiResult.Ok(node["paid"].int(0))
            } else {
                ApiResult.Error(node["reason"].str("Chiusura rifiutata."))
            }
        }.mancaLaMigrazione()
    }

    /** Come per le divisioni: l'errore di PostgREST diventa la migrazione che manca. */
    private fun <T> ApiResult<T>.mancaLaMigrazione(): ApiResult<T> = when {
        this is ApiResult.Error &&
            listOf("assign_objectives", "settle_objective", "club_objectives")
                .any { message.contains(it) } ->
            ApiResult.Error(
                "Gli obiettivi hanno bisogno della migrazione 0024_obiettivi.sql, che non " +
                    "e' ancora stata applicata a questo database.",
            )
        else -> this
    }
}
