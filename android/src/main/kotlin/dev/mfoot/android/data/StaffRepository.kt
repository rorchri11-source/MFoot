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
    val ownerClubId: Long? = null,
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
    val foundPlayerId: Long? = null,
    /**
     * I ragazzi portati da questa missione: fino a tre.
     *
     * In arrivo con la migrazione `0031`: fino ad allora questa lista resta vuota e si
     * legge `found_player_id`, che ne teneva uno solo.
     */
    val foundPlayerIds: List<Long> = emptyList(),
) {
    val inCorso: Boolean get() = status == "IN_CORSO" && !scaduta()
    val daValutare: Boolean get() = status == "IN_CORSO" && scaduta()
    val accettata: Boolean get() = status == "ACCETTATA"
    val rifiutata: Boolean get() = status == "RIFIUTATA"

    fun scaduta(now: Instant = Instant.now()): Boolean =
        readyAt != null && !now.isBefore(readyAt)

    /** Tutti i ragazzi trovati: quelli nuovi se ci sono, altrimenti il singolo di prima. */
    val trovati: List<Long>
        get() = foundPlayerIds.ifEmpty { listOfNotNull(foundPlayerId) }

    /** I ruoli chiesti: dal 2026-08-30 possono essere piu' di uno, separati da virgola. */
    val ruoli: List<String>
        get() = position.split(',').map { it.trim() }.filter { it.isNotEmpty() }

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
 * Chi lavora per te, e chi e' libero.
 *
 * ## Perche' non si compra piu' all'asta, dal 2026-08-30
 *
 * Perche' all'asta non li prendeva nessuno. Prima ogni membro dello staff nasceva senza
 * contratto e chi lo voleva doveva aprire un'asta e aspettare dodici ore: per un allenatore
 * che ti serve **adesso** per allenare la partita di stasera, dodici ore volevano dire non
 * prenderlo mai, e il proprietario ha dovuto segnalarlo due volte. La differenza fra uno e
 * cinque stelle la esprime adesso il **prezzo** — venticinque volte, con
 * [dev.mfoot.core.market.Valuation.staffPrice] — che e' una decisione altrettanto vera e
 * non ha bisogno che giri niente.
 *
 * Resta la vendita: chi ha uno staff e ne trova uno migliore lo mette a listino al prezzo
 * di sempre e incassa lui.
 */
object StaffRepository {

    suspend fun all(leagueId: Long): List<StaffMember> {
        val pathCompleto = "/rest/v1/staff?select=id,first_name,last_name,nationality,role,stars," +
            "club_id,owner_club_id&league_id=eq.$leagueId&order=stars.desc&limit=1000"
        val pathSenza = "/rest/v1/staff?select=id,first_name,last_name,nationality,role,stars," +
            "club_id&league_id=eq.$leagueId&order=stars.desc&limit=1000"

        val esito = SupabaseApi.get(pathCompleto).let {
            if (it is ApiResult.Error) SupabaseApi.get(pathSenza) else it
        }

        return when (esito) {
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
                    ownerClubId = row["owner_club_id"].long(0).takeIf { it > 0 },
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
            "status,found_player_id&club_id=in.($lista)&order=ready_at&limit=200"

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
     * Chi ha portato ciascuna missione, quando sono piu' di uno.
     *
     * Lettura a parte, stessa ragione della proprieta' dello staff: al peggio torna vuota
     * e si vede un ragazzo solo per missione, invece di non vedere piu' nessuna missione.
     */
    suspend fun finds(clubIds: List<Long>): Map<Long, List<Long>> {
        if (clubIds.isEmpty()) return emptyMap()
        val lista = clubIds.joinToString(",")
        val path = "/rest/v1/scouting_missions?select=id,found_player_ids" +
            "&club_id=in.($lista)&limit=200"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> emptyMap()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().mapNotNull { row ->
                val id = row["id"].long(0)
                val trovati = row["found_player_ids"].asList().map { it.long(0) }.filter { it > 0 }
                if (id > 0 && trovati.isNotEmpty()) id to trovati else null
            }.toMap()
        }
    }

    /** Accetta uno o piu' dei ragazzi trovati: entrano in Primavera. */
    suspend fun accept(missionId: Long, playerIds: List<Long>): ApiResult<Unit> {
        val w = JsonWriter(256)
        w.beginObject()
        w.field("p_mission_id", missionId)
        w.arrayField("p_player_ids")
        playerIds.forEach { w.value(it) }
        w.endArray()
        w.endObject()
        return SupabaseApi.rpc("accept_scouting", w.toString()).then(::esito).mapMissing()
    }

    /** Rifiuta: restano liberi per chiunque. E' anche il primo passo del «ri-scouta». */
    suspend fun reject(missionId: Long): ApiResult<Unit> {
        val w = JsonWriter(64)
        w.beginObject()
        w.field("p_mission_id", missionId)
        w.endObject()
        return SupabaseApi.rpc("reject_scouting", w.toString()).then(::esito).mapMissing()
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
