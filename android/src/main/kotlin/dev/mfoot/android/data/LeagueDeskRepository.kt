package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Un iscritto alla lega.
 *
 * Il club puo' mancare: chi entra col codice e' un partecipante prima ancora di aver fondato
 * la squadra, ed e' proprio quello che l'admin vuole vedere nell'elenco — chi manca
 * all'appello.
 */
data class MemberInfo(
    val userId: String,
    val nickname: String,
    val isAdmin: Boolean,
    val joinedAt: Instant?,
    val club: ClubInfo?,
) {
    val hasClub: Boolean get() = club != null
}

/** L'ultimo giro del tick, come lo racconta il server. */
data class TickInfo(
    val lastRunAt: Instant?,
    val lastProcessedAt: Instant?,
    val notes: String,
    val settledMatchDays: List<Int>,
) {
    /**
     * Le note del tick spezzate in righe leggibili.
     *
     * Il tick le concatena separandole con " | " perche' finiscono in una colonna sola. Qui
     * tornano righe, che e' la forma in cui erano state pensate.
     */
    val righe: List<String>
        get() = notes.split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Le letture della scrivania dell'admin: chi c'e', cosa ha fatto il server.
 *
 * Stanno in un file a parte da [LeagueRepository] perche' servono a schermate che si aprono
 * di rado. Metterle nello snapshot iniziale vorrebbe dire due richieste in piu' a ogni
 * avvio dell'app per dati che quasi nessuno guarda.
 */
object LeagueDeskRepository {

    /**
     * Gli iscritti, con il club di ognuno.
     *
     * ## Perche' si legge `league_members` e non `clubs`
     *
     * I club portano gia' `owner_name`, quindi ricavare l'elenco da quelli sarebbe stato
     * gratis. Ma chi e' entrato con il codice e non ha ancora fondato **non ha un club**, e
     * sparirebbe: proprio la persona di cui l'admin ha bisogno di sapere che esiste, perche'
     * e' quella che tiene ferma la partenza della lega. L'iscrizione e' la verita' su chi
     * c'e'; il club e' solo cio' che quella persona ha fatto finora.
     *
     * L'accoppiamento fra i due elenchi si fa sul telefono e non con una join di PostgREST
     * perche' fra `league_members` e `clubs` non c'e' una chiave dichiarata: il legame passa
     * da `owner_user_id`, che punta a `auth.users`. Chiederlo al database significherebbe
     * una vista in piu' da mantenere per unire due elenchi che il telefono ha gia' in
     * memoria tutti e due.
     */
    suspend fun members(leagueId: Long, clubs: List<ClubInfo>): ApiResult<List<MemberInfo>> {
        val path = "/rest/v1/league_members?select=user_id,nickname,is_admin,joined_at" +
            "&league_id=eq.$leagueId&order=joined_at"

        return SupabaseApi.get(path).then { body ->
            ApiResult.Ok(
                JsonNode.parse(body).asList().map { row ->
                    val id = row["user_id"].str("")
                    MemberInfo(
                        userId = id,
                        nickname = row["nickname"].str("senza nome"),
                        isAdmin = row["is_admin"].bool(false),
                        joinedAt = row["joined_at"].strOrNull()?.let(::istante),
                        club = clubs.firstOrNull { it.ownerUserId == id },
                    )
                },
            )
        }
    }

    /**
     * Lo stato del tick.
     *
     * Restituisce null quando la riga non c'e': significa che il tick non ha **mai** girato
     * su questa lega, che e' un'informazione preziosa e diversa da "ha girato e non ha
     * fatto niente". Confonderle vorrebbe dire cercare un difetto nel gioco quando il
     * problema e' che il server non parte.
     */
    suspend fun tick(leagueId: Long): ApiResult<TickInfo?> {
        val path = "/rest/v1/tick_state?select=last_run_at,last_processed_at,last_run_notes," +
            "settled_match_days&league_id=eq.$leagueId&limit=1"

        return SupabaseApi.get(path).then { body ->
            val row = JsonNode.parse(body)[0]
            if (!row.exists) {
                ApiResult.Ok(null)
            } else {
                ApiResult.Ok(
                    TickInfo(
                        lastRunAt = row["last_run_at"].strOrNull()?.let(::istante),
                        lastProcessedAt = row["last_processed_at"].strOrNull()?.let(::istante),
                        notes = row["last_run_notes"].str(""),
                        settledMatchDays = row["settled_match_days"].asList().map { it.int(0) },
                    ),
                )
            }
        }
    }

    /** Sta in [Istanti], perche' averne due copie e' come una e' rimasta rotta. */
    internal fun istante(testo: String): Instant? = Istanti.parse(testo)
}
