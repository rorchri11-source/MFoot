package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter

/**
 * Una lega a cui si e' iscritti, vista da fuori.
 *
 * E' volutamente povera: nome, quanti sono, se ci si ha un club. Basta a rispondere alla
 * domanda per cui esiste questa schermata — *in quale delle mie leghe sto guardando?* —
 * senza caricare milletrecento giocatori per ognuna.
 */
data class LeagueCard(
    val id: Long,
    val name: String,
    val status: String,
    val currentMatchDay: Int,
    val accessCode: String?,
    val nickname: String,
    val isAdmin: Boolean,
    val members: Int,
    val myClubName: String?,
    /** E' quella aperta adesso? */
    val current: Boolean,
) {
    val statoLeggibile: String
        get() = when (status) {
            "setup" -> "in preparazione"
            "mercato" -> "mercato aperto"
            "in_corso" -> "campionato in corso"
            "conclusa" -> "conclusa"
            else -> status
        }
}

/**
 * Che lega e' un codice, vista da chi non ci e' ancora dentro.
 *
 * Il minimo per riconoscere la lega dell'amico da quella sbagliata: il nome, quante
 * persone ci sono, a che punto e' la stagione. Non l'id, non i nomi degli iscritti, non
 * la configurazione — quelli si vedono da dentro.
 */
data class LeaguePreview(
    val name: String,
    val members: Int,
    val clubs: Int,
    val status: String,
    val matchDay: Int,
    val createdOn: String?,
) {
    /** La riga sotto al nome: chi c'e' e a che punto siamo. */
    val riassunto: String
        get() = buildString {
            append(members).append(if (members == 1) " iscritto" else " iscritti")
            append(" · ").append(clubs).append(if (clubs == 1) " club" else " club")
            append(" · ")
            append(
                when (status) {
                    "setup" -> "in preparazione"
                    "mercato" -> "mercato aperto"
                    "in_corso" -> "campionato in corso, giornata $matchDay"
                    "conclusa" -> "conclusa"
                    else -> status
                },
            )
        }
}

/**
 * Le leghe di cui si fa parte.
 *
 * ## Perche' questa schermata e' una correzione e non una comodita'
 *
 * Il codice d'accesso non e' mai stato univoco, e chi prova il gioco crea tre o quattro
 * leghe di fila riusando lo stesso codice. Da quel momento due amici che digitano lo
 * stesso codice possono finire in due leghe diverse: ognuno vede la squadra dell'altro
 * «da qualche parte» e non ne vede mai le mosse, perche' le mosse succedono in un altro
 * mondo. Sembrano due partite diverse, ed e' esattamente quello che sono.
 *
 * `0022` impedisce che ricapiti. Questa schermata serve a chi ci e' gia' dentro: fa vedere
 * *tutte* le leghe in cui si e' iscritti, quante persone ci sono in ognuna e in quale si
 * sta guardando adesso. Con quell'elenco davanti, «siamo nella stessa lega?» smette di
 * essere una domanda senza risposta.
 *
 * L'app apre sempre **una** lega alla volta: quella salvata in [Session]. Prima di questa
 * schermata, se la lega salvata era quella sbagliata, non c'era nessun modo di accorgersene
 * — ne' di cambiarla senza reinserire un codice che magari nemmeno si ricordava.
 */
object MyLeaguesRepository {

    /**
     * Che lega apre questo codice, **prima** di entrarci.
     *
     * ## Perche' esiste
     *
     * Perche' due amici hanno giocato in due leghe diverse convinti di essere nella
     * stessa, e nessuno dei due poteva accorgersene: si digita un codice e si e' dentro,
     * senza che niente dica dove. Con l'anteprima il pulsante smette di dire «Entra» e
     * comincia a dire «Entra in Lega dei Bar» — e un codice sbagliato si vede nel momento
     * in cui lo si scrive, non tre giorni dopo.
     *
     * Restituisce null quando il codice non apre niente. Un errore di rete lo restituisce
     * come [ApiResult.Error], perche' sono due cose diverse: «questo codice non esiste» e
     * «non ho potuto chiedere».
     */
    suspend fun peek(code: String): ApiResult<LeaguePreview?> {
        val payload = JsonWriter(128)
            .beginObject()
            .field("p_access_code", code.trim())
            .endObject()
            .toString()

        return SupabaseApi.rpc("peek_league", payload).then { body ->
            val node = JsonNode.parse(body).let { if (it.asList().isNotEmpty()) it[0] else it }
            if (!node["found"].bool(false)) {
                ApiResult.Ok(null)
            } else {
                ApiResult.Ok(
                    LeaguePreview(
                        name = node["name"].str("Lega"),
                        members = node["members"].int(0),
                        clubs = node["clubs"].int(0),
                        status = node["status"].str("setup"),
                        matchDay = node["match_day"].int(0),
                        // Solo la data, senza l'ora: serve a distinguere due leghe che si
                        // chiamano uguale, non a fare le pulci ai minuti.
                        createdOn = node["created_at"].strOrNull()?.take(10),
                    ),
                )
            }
        }.mancaLaFunzione()
    }

    /** Il database senza la migrazione: si dice quale, invece dell'errore di PostgREST. */
    private fun <T> ApiResult<T>.mancaLaFunzione(): ApiResult<T> = when {
        this is ApiResult.Error && message.contains("peek_league") ->
            ApiResult.Error(
                "L'anteprima ha bisogno della migrazione 0025_entrare_sapendo_dove.sql, " +
                    "che non e' ancora stata applicata a questo database.",
            )
        else -> this
    }

    suspend fun mine(currentLeagueId: Long?): ApiResult<List<LeagueCard>> {
        val me = Session.userId

        // Le Row Level Security fanno il filtro: si vedono solo le leghe di cui si e'
        // membri. Chiedere "tutte" e' quindi gia' chiedere "le mie".
        val leghe = SupabaseApi.get(
            "/rest/v1/leagues?select=id,name,status,current_match_day&order=id",
        )
        if (leghe is ApiResult.Error) return leghe

        val righeLeghe = JsonNode.parse((leghe as ApiResult.Ok).value).asList()
        if (righeLeghe.isEmpty()) return ApiResult.Ok(emptyList())

        // Stessa storia: arrivano i membri di tutte le leghe di cui si fa parte, e da li'
        // si contano le persone e si trova il proprio soprannome lega per lega.
        val membri = when (val esito = SupabaseApi.get(
            "/rest/v1/league_members?select=league_id,user_id,nickname,is_admin",
        )) {
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList()
        }

        val club = when (val esito = SupabaseApi.get(
            "/rest/v1/clubs?select=id,name,league_id,owner_user_id",
        )) {
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList()
        }

        val codici = accessCodes()

        return ApiResult.Ok(
            righeLeghe.map { riga ->
                val id = riga["id"].long(0)
                val suoi = membri.filter { it["league_id"].long(0) == id }
                val mio = suoi.firstOrNull { it["user_id"].str("") == me }

                LeagueCard(
                    id = id,
                    name = riga["name"].str("Lega"),
                    status = riga["status"].str("setup"),
                    currentMatchDay = riga["current_match_day"].int(0),
                    accessCode = codici[id],
                    nickname = mio?.get("nickname")?.str("") ?: "",
                    isAdmin = mio?.get("is_admin")?.bool(false) ?: false,
                    members = suoi.size,
                    myClubName = club
                        .firstOrNull {
                            it["league_id"].long(0) == id && it["owner_user_id"].str("") == me
                        }
                        ?.get("name")?.strOrNull(),
                    current = id == currentLeagueId,
                )
            },
        )
    }

    /**
     * Il codice d'accesso in chiaro, dove il database ce l'ha.
     *
     * ## Perche' una lettura a parte, e non una colonna in piu' nella prima
     *
     * Per la stessa ragione delle divisioni e del club padre, imparata nello stesso modo:
     * `access_code` e' una colonna aggiunta da `0022`, e una colonna che non esiste dentro
     * una SELECT fa rifiutare a PostgREST **l'intera query**. Messa nell'elenco delle
     * leghe, su un database senza la migrazione non si leggerebbe piu' nemmeno il nome
     * delle proprie leghe: la schermata sarebbe vuota invece che priva di un dettaglio.
     *
     * Chiesta da sola, al peggio fallisce lei, e i codici semplicemente non compaiono.
     */
    private suspend fun accessCodes(): Map<Long, String> =
        when (val esito = SupabaseApi.get("/rest/v1/leagues?select=id,access_code")) {
            is ApiResult.Error -> emptyMap()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList()
                .mapNotNull { row ->
                    row["access_code"].strOrNull()?.let { row["id"].long(0) to it }
                }
                .toMap()
        }

    /** Cambia il codice d'accesso. Solo l'amministratore, e solo verso uno libero. */
    suspend fun setAccessCode(leagueId: Long, code: String): ApiResult<String> {
        val payload = JsonWriter(160)
            .beginObject()
            .field("p_league_id", leagueId)
            .field("p_code", code)
            .endObject()
            .toString()

        return SupabaseApi.rpc("set_access_code", payload).then { body ->
            val node = JsonNode.parse(body).let { if (it.asList().isNotEmpty()) it[0] else it }
            if (node["ok"].bool(false)) {
                ApiResult.Ok(node["code"].str(code))
            } else {
                ApiResult.Error(node["reason"].str("Non si puo' cambiare il codice."))
            }
        }
    }
}
