package dev.mfoot.android.data

import dev.mfoot.core.calendar.CalendarAuction
import dev.mfoot.core.calendar.CalendarDeadline
import dev.mfoot.core.calendar.CalendarMatch
import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.model.Money
import java.time.LocalDateTime
import java.time.ZoneId

/** Tutto quello che finisce nella griglia del mese, gia' in ora di lega. */
data class CalendarData(
    val matches: List<CalendarMatch>,
    val auctions: List<CalendarAuction>,
    val contracts: List<CalendarDeadline>,
    val promises: List<CalendarDeadline>,
)

/**
 * Le quattro letture che riempiono il calendario.
 *
 * ## Perche' quattro richieste e non una vista
 *
 * Una vista che unisse partite, aste, contratti e promesse sarebbe una vista in piu' da
 * mantenere per una schermata che si apre qualche volta al giorno, e ognuna delle quattro
 * ha regole di lettura diverse — le partite le vede tutta la lega, i contratti e le
 * promesse solo il proprietario. Tenerle separate significa che ognuna passa dalla sua
 * policy senza che nessuno debba scrivere una `security definer` per rimetterle insieme.
 *
 * ## Perche' la conversione di fuso avviene qui
 *
 * Perche' e' l'unico punto che conosce sia il momento vero letto dal database sia il fuso
 * della lega. Piu' avanti nessuno dei due e' piu' disponibile insieme, e la schermata
 * finirebbe per usare l'ora del telefono — che e' esattamente il difetto di prima.
 */
object CalendarRepository {

    suspend fun load(
        leagueId: Long,
        myClubId: Long?,
        zone: ZoneId,
        clubName: (Long) -> String,
        playerName: (Long) -> String,
        /** Le competizioni che non fanno classifica: le amichevoli hanno un colore loro. */
        friendlyCompetitions: Set<Long> = emptySet(),
    ): ApiResult<CalendarData> {
        val partite = fixtures(leagueId, myClubId, zone, clubName, friendlyCompetitions)
        if (partite is ApiResult.Error) return ApiResult.Error(partite.message)

        val aste = auctions(leagueId, zone, playerName)
        val contratti = if (myClubId == null) ApiResult.Ok(emptyList()) else contracts(myClubId, playerName)
        val promesse = if (myClubId == null) ApiResult.Ok(emptyList()) else promises(myClubId, playerName)

        return ApiResult.Ok(
            CalendarData(
                matches = (partite as ApiResult.Ok).value,
                // Aste, contratti e promesse non fanno fallire il calendario: senza le
                // partite la griglia e' vuota e va detto, senza i pallini arancioni e'
                // ancora un calendario. Un errore su una lettura secondaria che svuotasse
                // tutta la schermata sarebbe un difetto peggiore di quello che segnala.
                auctions = (aste as? ApiResult.Ok)?.value.orEmpty(),
                contracts = (contratti as? ApiResult.Ok)?.value.orEmpty(),
                promises = (promesse as? ApiResult.Ok)?.value.orEmpty(),
            ),
        )
    }

    private suspend fun fixtures(
        leagueId: Long,
        myClubId: Long?,
        zone: ZoneId,
        clubName: (Long) -> String,
        friendlyCompetitions: Set<Long>,
    ): ApiResult<List<CalendarMatch>> {
        val path = "/rest/v1/fixtures?select=competition_id,home_club_id,away_club_id," +
            "match_day,kickoff,played,match_results(home_goals,away_goals)" +
            "&league_id=eq.$leagueId&order=kickoff"

        return SupabaseApi.get(path).then { body ->
            ApiResult.Ok(
                JsonNode.parse(body).asList().mapNotNull { row ->
                    val quando = row["kickoff"].strOrNull()?.let(Istanti::parse) ?: return@mapNotNull null
                    val result = row["match_results"].let { if (it.isArray) it[0] else it }
                    val casa = row["home_club_id"].long(0)
                    val fuori = row["away_club_id"].long(0)
                    val giocata = row["played"].bool(false)

                    CalendarMatch(
                        matchDay = row["match_day"].int(0),
                        kickoff = LocalDateTime.ofInstant(quando, zone),
                        homeName = clubName(casa),
                        awayName = clubName(fuori),
                        mine = myClubId != null && (casa == myClubId || fuori == myClubId),
                        friendly = row["competition_id"].long(0) in friendlyCompetitions,
                        played = giocata,
                        scoreline = if (giocata && result["home_goals"].exists) {
                            "${result["home_goals"].int(0)} - ${result["away_goals"].int(0)}"
                        } else {
                            ""
                        },
                    )
                },
            )
        }
    }

    private suspend fun auctions(
        leagueId: Long,
        zone: ZoneId,
        playerName: (Long) -> String,
    ): ApiResult<List<CalendarAuction>> {
        val path = "/rest/v1/auctions?select=target_id,target_type,ends_at,starting_price" +
            "&league_id=eq.$leagueId&status=eq.APERTA&order=ends_at"

        return SupabaseApi.get(path).then { body ->
            ApiResult.Ok(
                JsonNode.parse(body).asList().mapNotNull { row ->
                    if (row["target_type"].str("") != "player") return@mapNotNull null
                    val quando = row["ends_at"].strOrNull()?.let(Istanti::parse) ?: return@mapNotNull null

                    CalendarAuction(
                        endsAt = LocalDateTime.ofInstant(quando, zone),
                        playerName = playerName(row["target_id"].long(0)),
                        price = "da ${Money(row["starting_price"].int(0)).format()}",
                    )
                },
            )
        }
    }

    private suspend fun contracts(
        clubId: Long,
        playerName: (Long) -> String,
    ): ApiResult<List<CalendarDeadline>> {
        val path = "/rest/v1/contracts?select=player_id,expires_on&club_id=eq.$clubId"

        return SupabaseApi.get(path).then { body ->
            ApiResult.Ok(
                JsonNode.parse(body).asList().map { row ->
                    CalendarDeadline(
                        matchDay = row["expires_on"].int(0),
                        what = playerName(row["player_id"].long(0)),
                    )
                },
            )
        }
    }

    private suspend fun promises(
        clubId: Long,
        playerName: (Long) -> String,
    ): ApiResult<List<CalendarDeadline>> {
        val path = "/rest/v1/promises?select=player_id,deadline,type" +
            "&club_id=eq.$clubId&status=eq.IN_CORSO"

        return SupabaseApi.get(path).then { body ->
            ApiResult.Ok(
                JsonNode.parse(body).asList().map { row ->
                    CalendarDeadline(
                        matchDay = row["deadline"].int(0),
                        what = playerName(row["player_id"].long(0)),
                    )
                },
            )
        }
    }
}
