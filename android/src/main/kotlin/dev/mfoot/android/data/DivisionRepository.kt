package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.model.ClubId

/**
 * Scrive in quale divisione gioca ogni club.
 *
 * ## Perche' il calcolo non sta sul database
 *
 * Verrebbe naturale far decidere al server chi sale e chi scende: ha i risultati, e una
 * funzione SQL girerebbe senza che nessuno apra l'app.
 *
 * Sarebbe pero' la **seconda** implementazione dello stesso regolamento. I criteri di
 * spareggio li sceglie l'admin e vivono in `core` con i loro test; promozioni, playoff e
 * playout stanno in `SeasonEnd` con altri ventinove. Riscriverli in SQL vorrebbe dire due
 * regolamenti che si separano al primo ritocco, e la classifica mostrata nell'app non
 * corrisponderebbe piu' a chi retrocede davvero.
 *
 * Cosi' invece il conto lo fa lo stesso codice che disegna l'anteprima nella schermata
 * Divisioni — quindi cio' che si vede prima e' esattamente cio' che succede — e al database
 * resta l'invariante che nessun calcolo garantisce da solo: che le divisioni restino della
 * dimensione giusta e che nessuna resti vuota.
 */
object DivisionRepository {

    /**
     * In quale divisione gioca ogni club — letto **a parte**, e senza mai far fallire nulla.
     *
     * ## Perche' non sta nella lettura principale della lega
     *
     * Perche' l'ho provato e ha rotto tutto. Aggiungere `division_level` all'elenco delle
     * colonne di `clubs` sembrava la cosa ovvia, ed e' bastato a rendere l'app **inservibile
     * su ogni database senza la migrazione**: PostgREST rifiuta l'intera query per una
     * colonna che non esiste, quindi non si leggeva piu' la lega — non una schermata, tutto.
     *
     * E' una differenza che vale la pena ricordare: una tabella nuova rompe solo la
     * schermata che la usa, una **colonna** nuova rompe ogni query in cui compare. Qui la si
     * chiede da sola, e se la risposta e' un errore vuol dire che quella lega non ha ancora
     * le divisioni: tutti al primo livello, che e' esattamente la verita'.
     */
    suspend fun levels(leagueId: Long): Map<Long, Int> {
        val path = "/rest/v1/clubs?select=id,division_level&league_id=eq.$leagueId"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> emptyMap()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList()
                .associate { it["id"].long(0) to it["division_level"].int(1) }
        }
    }

    suspend fun assign(leagueId: Long, levels: Map<ClubId, Int>): ApiResult<Unit> {
        val w = JsonWriter(2 * 1024)
        w.beginObject()
        w.field("p_league_id", leagueId)
        w.arrayField("p_assignments")
        levels.forEach { (club, level) ->
            w.beginObject()
            w.field("club_id", club.value)
            w.field("level", level)
            w.endObject()
        }
        w.endArray()
        w.endObject()

        return SupabaseApi.rpc("assign_divisions", w.toString()).then { body ->
            val node = JsonNode.parse(body).let { if (it.asList().isNotEmpty()) it[0] else it }
            if (node["ok"].bool(false)) {
                ApiResult.Ok(Unit)
            } else {
                ApiResult.Error(node["reason"].str("Assegnazione rifiutata."))
            }
        }.mapMissingColumn()
    }

    /** Come per gli scambi: l'errore di PostgREST diventa la migrazione che manca. */
    private fun ApiResult<Unit>.mapMissingColumn(): ApiResult<Unit> = when {
        this is ApiResult.Error &&
            (message.contains("assign_divisions") || message.contains("division_level")) ->
            ApiResult.Error(
                "Le divisioni hanno bisogno della migrazione 0009_divisions.sql, che non è " +
                    "ancora stata applicata a questo database.",
            )
        else -> this
    }
}

/**
 * Chi e' la Primavera di chi.
 *
 * ## Perche' una lettura a parte, come le divisioni
 *
 * Per la stessa ragione, imparata nello stesso modo: `parent_club_id` e' una **colonna
 * nuova**, e una colonna nuova dentro la SELECT principale dei club rende l'app
 * inservibile su ogni database che non ha ancora la migrazione — PostgREST rifiuta
 * l'intera query, quindi non si legge piu' la lega, non una schermata.
 *
 * Chiesta da sola, al peggio fallisce lei: la lega non ha seconde squadre, che e'
 * esattamente cio' che era vero prima.
 */
object YouthRepository {

    /** Club figlio → club padre. Chi non compare e' una prima squadra. */
    suspend fun parents(leagueId: Long): Map<Long, Long> {
        val path = "/rest/v1/clubs?select=id,parent_club_id" +
            "&league_id=eq.$leagueId&parent_club_id=not.is.null"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> emptyMap()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList()
                .associate { it["id"].long(0) to it["parent_club_id"].long(0) }
        }
    }

    /** Fonda la seconda squadra. Parte dall'ultima divisione. */
    suspend fun create(parentClubId: Long): ApiResult<Unit> {
        val w = JsonWriter(96)
        w.beginObject()
        w.field("p_parent", parentClubId)
        w.endObject()

        return SupabaseApi.rpc("create_youth_club", w.toString()).then(::esito).mapMissing()
    }

    /** Promuove in prima squadra, o manda giu' in Primavera. */
    suspend fun move(playerId: Long, promote: Boolean): ApiResult<Unit> {
        val w = JsonWriter(128)
        w.beginObject()
        w.field("p_player_id", playerId)
        w.field("p_promote", promote)
        w.endObject()

        return SupabaseApi.rpc("move_between_squads", w.toString()).then(::esito).mapMissing()
    }

    private fun esito(body: String): ApiResult<Unit> {
        val node = JsonNode.parse(body).let { if (it.asList().isNotEmpty()) it[0] else it }
        return if (node["ok"].bool(false)) ApiResult.Ok(Unit)
        else ApiResult.Error(node["reason"].str("Non si può fare."))
    }

    private fun ApiResult<Unit>.mapMissing(): ApiResult<Unit> = when {
        this is ApiResult.Error &&
            listOf("create_youth_club", "move_between_squads").any { message.contains(it) } ->
            ApiResult.Error(
                "La seconda squadra ha bisogno della migrazione 0018_seconda_squadra.sql, " +
                    "che non è ancora stata applicata a questo database.",
            )
        else -> this
    }
}
