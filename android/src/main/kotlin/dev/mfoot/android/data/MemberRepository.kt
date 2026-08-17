package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode

/**
 * Una persona nella lega.
 *
 * [clubName] e' null per chi e' entrato ma non ha ancora fondato: e' un'informazione, non
 * un dato mancante, ed e' anche la piu' utile che l'admin possa leggere in quella
 * schermata — sono le persone da spronare prima che la lega parta.
 */
data class MemberInfo(
    val userId: String,
    val nickname: String,
    val isAdmin: Boolean,
    val clubName: String?,
    val isMe: Boolean,
)

/**
 * Le persone della lega.
 *
 * ## Perche' si legge `league_members` e non `clubs`
 *
 * I club portano gia' `owner_name`, quindi sarebbe stato gratis ricavare l'elenco da
 * quelli. Ma chi e' entrato con il codice e non ha ancora fondato **non ha un club**, e
 * sparirebbe: proprio la persona di cui l'admin ha bisogno di sapere che esiste e che sta
 * bloccando la partenza della lega. L'iscrizione e' la verita' su chi c'e'; il club e'
 * solo cio' che quella persona ha fatto finora.
 */
object MemberRepository {

    suspend fun list(leagueId: Long): ApiResult<List<MemberInfo>> {
        val path = "/rest/v1/league_members?select=user_id,nickname,is_admin" +
            "&league_id=eq.$leagueId&order=is_admin.desc,nickname"

        return SupabaseApi.get(path).then { body ->
            ApiResult.Ok(JsonNode.parse(body).asList().map { row ->
                MemberInfo(
                    userId = row["user_id"].str(""),
                    nickname = row["nickname"].str("?"),
                    isAdmin = row["is_admin"].bool(false),
                    clubName = null,
                    isMe = false,
                )
            })
        }
    }

    /**
     * Attacca a ogni persona il suo club.
     *
     * L'accoppiamento si fa qui e non con una join di PostgREST perche' fra
     * `league_members` e `clubs` non c'e' una chiave dichiarata — il legame passa da
     * `owner_user_id`, che e' un campo libero verso `auth.users`. Chiederlo al database
     * significherebbe una vista in piu' da mantenere per unire due elenchi che il telefono
     * ha gia' entrambi in memoria.
     */
    fun withClubs(members: List<MemberInfo>, clubs: List<ClubInfo>, me: String?): List<MemberInfo> {
        val byOwner = clubs.associateBy { it.ownerUserId }
        return members.map {
            it.copy(clubName = byOwner[it.userId]?.name, isMe = it.userId == me)
        }
    }
}
