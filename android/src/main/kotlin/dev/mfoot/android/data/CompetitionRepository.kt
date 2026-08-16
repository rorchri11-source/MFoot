package dev.mfoot.android.data

import dev.mfoot.core.calendar.CalendarSolver
import dev.mfoot.core.calendar.Competition
import dev.mfoot.core.calendar.CompetitionType
import dev.mfoot.core.calendar.FixtureGenerator
import dev.mfoot.core.calendar.Schedule
import dev.mfoot.core.config.CalendarConfig
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.CompetitionId
import dev.mfoot.core.model.MatchDay
import java.time.ZoneOffset

/** Una competizione gia' creata, come compare nella lista dell'admin. */
data class CompetitionInfo(
    val id: Long,
    val name: String,
    val type: CompetitionType,
    val participants: List<Long>,
    val fixtures: Int,
    val played: Int,
) {
    val canDelete: Boolean get() = played == 0
    val isFinished: Boolean get() = fixtures > 0 && played == fixtures
}

/**
 * Le competizioni della lega.
 *
 * ## Il calendario si calcola sul telefono
 *
 * `core` contiene gia' le tabelle di Berger, il tabellone a eliminazione e il risolutore
 * di vincoli, testati. Calcolare qui vuol dire che l'admin **vede** il calendario prima
 * di confermarlo — quante partite, in quali giorni, con quali avvisi — invece di premere
 * un pulsante e scoprire dopo cosa e' successo.
 */
object CompetitionRepository {

    suspend fun list(leagueId: Long): ApiResult<List<CompetitionInfo>> {
        val path = "/rest/v1/competitions?select=id,name,type,participants," +
            "fixtures(id,played)&league_id=eq.$leagueId&order=id"

        return SupabaseApi.get(path).then { body ->
            ApiResult.Ok(
                JsonNode.parse(body).asList().map { row ->
                    val fixtures = row["fixtures"].asList()
                    CompetitionInfo(
                        id = row["id"].long(0),
                        name = row["name"].str("Competizione"),
                        type = row["type"].enum(CompetitionType.GIRONE),
                        participants = row["participants"].asList().map { it.long(0) },
                        fixtures = fixtures.size,
                        played = fixtures.count { it["played"].bool(false) },
                    )
                },
            )
        }
    }

    /**
     * Prepara il calendario **senza scrivere niente**.
     *
     * E' l'anteprima che l'admin approva. Restituisce anche gli avvisi del risolutore:
     * se il periodo scelto e' troppo corto per le partite richieste, e' bene saperlo
     * prima e non a stagione iniziata.
     */
    fun preview(
        participants: List<Long>,
        type: CompetitionType,
        doubleRound: Boolean,
        calendar: CalendarConfig,
        config: LeagueConfig,
        seed: Long,
    ): Schedule {
        val competition = Competition(
            id = CompetitionId(0),
            name = "anteprima",
            type = type,
            participants = participants.map(::ClubId),
            doubleRound = doubleRound,
        )
        val rounds = FixtureGenerator.generate(competition, seed)
        return CalendarSolver.schedule(
            rounds = rounds,
            config = config.copy(calendar = calendar),
            firstMatchDay = MatchDay(1),
        )
    }

    suspend fun create(
        leagueId: Long,
        name: String,
        type: CompetitionType,
        doubleRound: Boolean,
        participants: List<Long>,
        calendar: CalendarConfig,
        schedule: Schedule,
    ): ApiResult<Long> {
        val w = JsonWriter(16 * 1024)
        w.beginObject()
        w.field("p_league_id", leagueId)
        w.field("p_name", name.trim())
        w.field("p_type", type.name)

        // Le impostazioni con cui e' stata generata restano attaccate alla competizione:
        // servono a rigenerare o verificare il calendario senza indovinare.
        w.objectField("p_config")
        w.field("doubleRound", doubleRound)
        w.field("startDate", calendar.startDate.toString())
        w.field("endDate", calendar.endDate.toString())
        w.field("matchesPerDayPerClub", calendar.matchesPerDayPerClub)
        w.arrayField("kickoffSlots")
        calendar.kickoffSlots.forEach { w.value(it.toString()) }
        w.endArray()
        w.endObject()

        w.arrayField("p_participants")
        participants.forEach { w.value(it.toInt()) }
        w.endArray()

        w.arrayField("p_fixtures")
        schedule.fixtures.forEach { f ->
            w.beginObject()
            w.field("round", f.round)
            w.field("label", f.roundLabel)
            w.field("home", f.home.value)
            w.field("away", f.away.value)
            w.field("matchDay", f.matchDay.value)
            w.field("kickoff", f.kickoff?.toInstant(ZoneOffset.UTC)?.toString())
            w.field("tieId", f.tieId)
            w.field("secondLeg", f.isSecondLeg)
            w.endObject()
        }
        w.endArray()
        w.endObject()

        return SupabaseApi.rpc("create_competition", w.toString()).then { body ->
            val id = body.trim().trim('"').toLongOrNull()
            if (id == null) ApiResult.Error("Risposta inattesa: ${body.take(120)}")
            else ApiResult.Ok(id)
        }
    }

    suspend fun delete(competitionId: Long): ApiResult<Unit> {
        val payload = JsonWriter(128)
            .beginObject().field("p_competition_id", competitionId).endObject().toString()
        return SupabaseApi.rpc("delete_competition", payload).then { ApiResult.Ok(Unit) }
    }
}
