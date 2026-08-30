package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import java.time.Instant

/** Un membro dello staff, come sta sul database. */
data class StaffMember(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val nationality: String,
    val role: String,
    val stars: Int,
    val clubId: Long?,
) {
    val shortName: String get() = "${firstName.firstOrNull() ?: ' '}. $lastName"

    val roleLabel: String
        get() = when (role) {
            "ALLENATORE" -> "Allenatore"
            "PREPARATORE" -> "Preparatore"
            else -> "Osservatore"
        }

    /**
     * Cosa fa, in una riga.
     *
     * I numeri sono quelli veri di `Staff` in `core`: dirli invece di dire "migliora la
     * crescita" e' la differenza fra scegliere e tirare a indovinare.
     */
    val effetto: String
        get() = when (role) {
            "ALLENATORE" -> "crescita ×${CRESCITA[stars - 1]}"
            "PREPARATORE" -> "recupero ×${RECUPERO[stars - 1]}"
            else -> "stringe la forbice del ${(SCOUTING[stars - 1] * 100).toInt()}%"
        }

    private companion object {
        val CRESCITA = listOf("0,60", "0,80", "1,00", "1,35", "1,80")
        val RECUPERO = listOf("0,70", "0,85", "1,00", "1,25", "1,55")
        val SCOUTING = listOf(0.15, 0.30, 0.45, 0.60, 0.75)
    }
}

/** Una missione in corso o conclusa. */
data class ScoutingMission(
    val id: Long,
    val staffId: Long,
    val country: String,
    val position: String,
    val readyAt: Instant?,
    val status: String,
    val foundPlayerId: Long?,
) {
    val inCorso: Boolean get() = status == "IN_CORSO"

    fun quando(now: Instant): String {
        val quando = readyAt ?: return "—"
        val minuti = java.time.Duration.between(now, quando).toMinutes()
        return when {
            minuti <= 0 -> "sta rientrando"
            minuti < 60 -> "fra $minuti min"
            minuti < 60 * 24 -> "fra ${minuti / 60} h"
            else -> "fra ${minuti / (60 * 24)} giorni"
        }
    }
}

/**
 * Lo staff: chi lavora per te, chi e' libero, e dove sono i tuoi osservatori.
 *
 * ## Perche' lo staff si assume a prezzo fisso, e non si batte all'asta
 *
 * Il ragionamento originale era il contrario: un allenatore da cinque stelle vale il
 * triplo di uno da una sulla crescita, quindi a prezzo fisso se lo prenderebbe chi apre
 * l'app per primo, e un'asta trasforma quella differenza in una decisione.
 *
 * Regge in teoria e non ha retto alla prova. L'asta costa un giro di server per aprirsi e
 * un altro per chiudersi, e il server gira quando gli pare: dal telefono lo staff non si
 * prendeva mai, e il proprietario ha dovuto segnalarlo due volte. La differenza fra uno e
 * cinque stelle la esprime adesso il **prezzo** — venticinque volte, con
 * [dev.mfoot.core.market.Valuation.staffPrice] — che e' una decisione altrettanto vera e
 * non ha bisogno che giri niente.
 *
 * Resta la vendita: chi ha uno staff e ne trova uno migliore lo mette a listino al prezzo
 * di sempre e incassa lui.
 */
object StaffRepository {

    suspend fun all(leagueId: Long): List<StaffMember> {
        val path = "/rest/v1/staff?select=id,first_name,last_name,nationality,role,stars," +
            "club_id&league_id=eq.$leagueId&order=stars.desc&limit=1000"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().map { row ->
                StaffMember(
                    id = row["id"].long(0),
                    firstName = row["first_name"].str(""),
                    lastName = row["last_name"].str(""),
                    nationality = row["nationality"].str(""),
                    role = row["role"].str("OSSERVATORE"),
                    stars = row["stars"].int(1).coerceIn(1, 5),
                    clubId = row["club_id"].long(0).takeIf { it > 0 },
                )
            }
        }
    }

    /**
     * Di chi e' ciascun membro dello staff.
     *
     * ## Perche' e' una lettura a parte
     *
     * `owner_club_id` e' arrivata il 2026-08-30. Chiederla dentro [all] vorrebbe dire che
     * una lega col database indietro non apre piu' la schermata dello staff: PostgREST per
     * una colonna che non esiste rifiuta **l'intera query**, non il campo. La schermata non
     * perderebbe le celle — sparirebbe.
     *
     * E' la trappola gia' pagata con `clubs.division_level`, con `clubs.parent_club_id`, e
     * il 2026-08-29 con `match_results.home_formation`, che ha tenuto ferme le partite.
     *
     * Qui, al peggio, fallisce da sola: la mappa torna vuota, le celle non si disegnano, e
     * lo staff si vede come si vedeva prima.
     */
    suspend fun ownership(leagueId: Long): Map<Long, Long> {
        val path = "/rest/v1/staff?select=id,owner_club_id" +
            "&league_id=eq.$leagueId&owner_club_id=not.is.null&limit=1000"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> emptyMap()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().mapNotNull { row ->
                val id = row["id"].long(0)
                val owner = row["owner_club_id"].long(0)
                if (id > 0 && owner > 0) id to owner else null
            }.toMap()
        }
    }

    suspend fun missions(clubIds: List<Long>): List<ScoutingMission> {
        if (clubIds.isEmpty()) return emptyList()
        val lista = clubIds.joinToString(",")
        val path = "/rest/v1/scouting_missions?select=id,staff_id,country,position,ready_at," +
            "status,found_player_id&club_id=in.($lista)&order=ready_at&limit=100"

        return when (val esito = SupabaseApi.get(path)) {
            // Migrazione non ancora applicata: nessuna missione, che e' la verita'.
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().map { row ->
                ScoutingMission(
                    id = row["id"].long(0),
                    staffId = row["staff_id"].long(0),
                    country = row["country"].str(""),
                    position = row["position"].str(""),
                    readyAt = row["ready_at"].strOrNull()?.let(Istanti::parse),
                    status = row["status"].str("IN_CORSO"),
                    foundPlayerId = row["found_player_id"].long(0).takeIf { it > 0 },
                )
            }
        }
    }

    /** Sposta un membro dello staff fra prima squadra e Primavera. */
    suspend fun assign(staffId: Long, clubId: Long): ApiResult<Unit> {
        val w = JsonWriter(128)
        w.beginObject()
        w.field("p_staff_id", staffId)
        w.field("p_club_id", clubId)
        w.endObject()
        return SupabaseApi.rpc("assign_staff", w.toString()).then(::esito).mapMissing()
    }

    /**
     * Svuota una cella senza cedere nessuno.
     *
     * Non esisteva, perche' non esisteva la panchina: prima l'unico modo di togliere un
     * allenatore da una cella era metterci qualcun altro, e quel qualcun altro liberava il
     * primo sul mercato.
     */
    suspend fun bench(staffId: Long): ApiResult<Unit> {
        val w = JsonWriter(64)
        w.beginObject()
        w.field("p_staff_id", staffId)
        w.endObject()
        return SupabaseApi.rpc("bench_staff", w.toString()).then(::esito).mapMissing()
    }

    /** Manda un osservatore a cercare. */
    suspend fun send(staffId: Long, country: String, position: String): ApiResult<Unit> {
        val w = JsonWriter(192)
        w.beginObject()
        w.field("p_staff_id", staffId)
        w.field("p_country", country)
        w.field("p_position", position)
        w.endObject()
        return SupabaseApi.rpc("send_scout", w.toString()).then(::esito).mapMissing()
    }

    private fun esito(body: String): ApiResult<Unit> {
        val node = JsonNode.parse(body).let { if (it.asList().isNotEmpty()) it[0] else it }
        return if (node["ok"].bool(false)) ApiResult.Ok(Unit)
        else ApiResult.Error(node["reason"].str("Non si può fare."))
    }

    private fun ApiResult<Unit>.mapMissing(): ApiResult<Unit> = when {
        this is ApiResult.Error &&
            listOf("assign_staff", "send_scout").any { message.contains(it) } ->
            ApiResult.Error(
                "Lo staff ha bisogno della migrazione 0019_staff_e_scouting.sql, che non " +
                    "è ancora stata applicata a questo database.",
            )
        else -> this
    }
}
