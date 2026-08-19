package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import java.time.Instant

/** Un momento della partita, come sta nella timeline salvata. */
data class MatchMoment(
    val minute: Int,
    val type: String,
    val homeSide: Boolean,
    val danger: Int,
    val text: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val playerId: Long?,
) {
    val isGoal: Boolean get() = type == "GOL" || type == "RIGORE_SEGNATO"

    /** Quanto pesa: sotto questa soglia e' rumore di gioco, non un momento da mostrare. */
    val isNotable: Boolean get() = danger >= 40 || isGoal
}

/** Come ha giocato un singolo giocatore. */
data class MatchRating(
    val playerId: Long,
    val started: Boolean,
    val minutes: Int,
    val goals: Int,
    val assists: Int,
    val yellow: Int,
    val red: Int,
    val rating: Double,
)

/** Una partita giocata, pronta da rivedere. */
data class PlayedMatch(
    val fixtureId: Long,
    val homeClubId: Long,
    val awayClubId: Long,
    val matchDay: Int,
    val kickoff: Instant?,
    val homeGoals: Int,
    val awayGoals: Int,
    val homePossession: Double,
    val moments: List<MatchMoment>,
    val ratings: List<MatchRating>,
) {
    val scoreline: String get() = "$homeGoals - $awayGoals"
}

/**
 * La partita gia' giocata, letta una volta sola.
 *
 * ## Perche' la timeline sta tutta sul database
 *
 * Il tick salva i novanta minuti **interi** al momento della simulazione. Il telefono la
 * scarica una volta e la riproduce con il proprio orologio: nessun polling, costo zero
 * durante la partita, e chi apre l'app al sessantesimo salta direttamente al sessantesimo.
 *
 * E' la decisione che rende accettabile far girare un mondo su una griglia di cinque
 * minuti e un backend gratuito.
 *
 * ## Perche' e' arrivata cosi' tardi
 *
 * Perche' la timeline si scriveva da mesi e **nessuno la leggeva**. Il risultato di una
 * partita era `2-1`, e tutto quello che ci sta intorno — moduli, ordini condizionali,
 * stamina, giocatori fuori ruolo — non era osservabile da nessuna parte. In un manageriale
 * la partita e' il momento in cui il resto acquista senso: senza, schierare la formazione
 * e' compilare un modulo e sperare.
 */
object MatchRepository {

    suspend fun load(fixtureId: Long): ApiResult<PlayedMatch> {
        val path = "/rest/v1/fixtures?select=id,home_club_id,away_club_id,match_day,kickoff," +
            "match_results(home_goals,away_goals,timeline,home_possession)" +
            "&id=eq.$fixtureId&limit=1"

        return SupabaseApi.get(path).then { body ->
            val row = JsonNode.parse(body)[0]
            if (!row.exists) return@then ApiResult.Error("Partita non trovata.")

            val result = row["match_results"].let { if (it.isArray) it[0] else it }
            if (!result.exists) {
                return@then ApiResult.Error("Questa partita non e' ancora stata giocata.")
            }

            val timeline = result["timeline"]

            ApiResult.Ok(
                PlayedMatch(
                    fixtureId = row["id"].long(0),
                    homeClubId = row["home_club_id"].long(0),
                    awayClubId = row["away_club_id"].long(0),
                    matchDay = row["match_day"].int(0),
                    kickoff = row["kickoff"].strOrNull()?.let(Istanti::parse),
                    homeGoals = result["home_goals"].int(0),
                    awayGoals = result["away_goals"].int(0),
                    homePossession = result["home_possession"].double(0.5),
                    moments = timeline["events"].asList().map { e ->
                        MatchMoment(
                            minute = e["minute"].int(0),
                            type = e["type"].str(""),
                            homeSide = e["side"].str("CASA") == "CASA",
                            danger = e["danger"].int(0),
                            text = e["text"].str(""),
                            homeGoals = e["homeGoals"].int(0),
                            awayGoals = e["awayGoals"].int(0),
                            playerId = e["player"].long(0).takeIf { it > 0 },
                        )
                    },
                    ratings = emptyList(),
                ),
            )
        }
    }

    /**
     * Le pagelle.
     *
     * Lettura separata perche' arrivano da `appearances`, che e' una tabella diversa con
     * una policy diversa, e perche' una partita si puo' rivedere anche senza: se le
     * presenze mancano — una partita giocata prima che la tabella esistesse — il replay
     * funziona lo stesso e le pagelle semplicemente non ci sono.
     */
    suspend fun ratings(fixtureId: Long): List<MatchRating> {
        val path = "/rest/v1/appearances?select=player_id,started,minutes,goals,assists," +
            "yellow,red,rating&fixture_id=eq.$fixtureId&order=rating.desc"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().map { row ->
                MatchRating(
                    playerId = row["player_id"].long(0),
                    started = row["started"].bool(false),
                    minutes = row["minutes"].int(0),
                    goals = row["goals"].int(0),
                    assists = row["assists"].int(0),
                    yellow = row["yellow"].int(0),
                    red = row["red"].int(0),
                    rating = row["rating"].double(0.0),
                )
            }
        }
    }
}

/** Quanto ha fatto un giocatore, da inizio stagione. */
data class Carriera(
    val presenze: Int,
    val daTitolare: Int,
    val minuti: Int,
    val gol: Int,
    val assist: Int,
    val gialli: Int,
    val rossi: Int,
    val mediaVoto: Double,
) {
    val vuota: Boolean get() = presenze == 0

    companion object {
        val NESSUNA = Carriera(0, 0, 0, 0, 0, 0, 0, 0.0)

        fun da(righe: List<MatchRating>): Carriera {
            // Solo chi e' sceso in campo: le presenze contengono una riga anche per chi e'
            // rimasto fuori — serve a sapere da quanto non gioca — e contarla come partita
            // giocata falserebbe media voto e minuti.
            val giocate = righe.filter { it.minutes > 0 }
            if (giocate.isEmpty()) return NESSUNA

            return Carriera(
                presenze = giocate.size,
                daTitolare = giocate.count { it.started },
                minuti = giocate.sumOf { it.minutes },
                gol = giocate.sumOf { it.goals },
                assist = giocate.sumOf { it.assists },
                gialli = giocate.sumOf { it.yellow },
                rossi = giocate.sumOf { it.red },
                mediaVoto = giocate.map { it.rating }.average(),
            )
        }
    }
}

/**
 * La storia di un giocatore, da `appearances`.
 *
 * ## Perche' non c'era
 *
 * Perche' fino a ieri non esisteva la tabella: la formazione salvata era una riga per club,
 * sovrascritta, e di chi avesse giocato la settimana scorsa non restava traccia. Adesso
 * resta, e la scheda puo' dire "quattordici presenze, media 6,4" invece di soli attributi.
 */
object CareerRepository {

    suspend fun of(playerId: Long): Carriera {
        val path = "/rest/v1/appearances?select=started,minutes,goals,assists,yellow,red," +
            "rating&player_id=eq.$playerId&limit=200"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> Carriera.NESSUNA
            is ApiResult.Ok -> Carriera.da(
                JsonNode.parse(esito.value).asList().map { row ->
                    MatchRating(
                        playerId = playerId,
                        started = row["started"].bool(false),
                        minutes = row["minutes"].int(0),
                        goals = row["goals"].int(0),
                        assists = row["assists"].int(0),
                        yellow = row["yellow"].int(0),
                        red = row["red"].int(0),
                        rating = row["rating"].double(0.0),
                    )
                },
            )
        }
    }
}
