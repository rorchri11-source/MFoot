package dev.mfoot.android.data

import dev.mfoot.core.calendar.Competition
import dev.mfoot.core.calendar.CompetitionType
import dev.mfoot.core.calendar.FixtureResult
import dev.mfoot.core.calendar.StandingRow
import dev.mfoot.core.calendar.Standings
import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.CompetitionId
import java.time.Instant

/** Una partita in calendario, giocata o no. */
data class MatchRow(
    val id: Long,
    val competitionId: Long,
    val round: Int,
    val roundLabel: String,
    val homeClubId: Long,
    val awayClubId: Long,
    val matchDay: Int,
    /** Il momento vero. L ora di lega si ricava con `config.calendar.localOf`. */
    val kickoff: Instant?,
    val played: Boolean,
    val homeGoals: Int?,
    val awayGoals: Int?,
    /**
     * Perche' non si e' potuta giocare.
     *
     * Il server lo sapeva e lo teneva nelle proprie note: dal telefono una partita rinviata
     * era indistinguibile da una non ancora arrivata. *Chiesto il 2026-08-30.*
     */
    val problema: String? = null,
) {
    /** La partita e' conclusa davvero quando il server l'ha giocata E sono trascorsi i 110 minuti reali (45+20+45). */
    fun conclusa(now: Instant = Instant.now(), pausaMinuti: Int = 20): Boolean =
        played && (kickoff == null || now.isAfter(dev.mfoot.core.match.MatchClock.fineDi(kickoff, pausaMinuti)))

    val scoreline: String get() =
        if (conclusa() && homeGoals != null && awayGoals != null) "$homeGoals - $awayGoals" else "–"

    /** Doveva giocarsi e non si e' giocata, e c'e' un motivo scritto. */
    val bloccata: Boolean get() = !played && problema != null
}

/** Classifica e calendario di una competizione, pronti da mostrare. */
data class TableView(
    val competition: CompetitionInfo,
    val rows: List<StandingRow>,
    val matches: List<MatchRow>,
) {
    val played: List<MatchRow> get() = matches.filter { it.conclusa() }
    val upcoming: List<MatchRow> get() = matches.filterNot { it.conclusa() }

    /** Il prossimo turno da giocare: e' quello che si vuole vedere aprendo la schermata. */
    val nextRound: Int? get() = upcoming.minByOrNull { it.round }?.round
}

/**
 * La classifica.
 *
 * ## Perche' si calcola sul telefono
 *
 * Il database conserva i **risultati**, non la classifica: punti, differenza reti e
 * ordine sono una funzione dei risultati e dei criteri di spareggio scelti dall'admin.
 * Salvarla sarebbe un secondo posto dove la stessa verita' puo' andare alla deriva — e
 * basterebbe un risultato corretto a mano perche' i due non tornino piu'.
 *
 * `Standings` in `core` applica i criteri **nell'ordine indicato dall'admin**: e' una
 * delle cose su cui in una lega fra amici si litiga volentieri, e averla scritta nella
 * configurazione chiude la discussione prima che cominci.
 */
object TableRepository {

    suspend fun load(leagueId: Long, competition: CompetitionInfo): ApiResult<TableView> =
        matches(leagueId, competition.id).then { matches ->
            val model = Competition(
                id = CompetitionId(competition.id),
                name = competition.name,
                type = competition.type,
                participants = competition.participants.map(::ClubId),
            )

            val results = matches
                .filter { it.conclusa() && it.homeGoals != null && it.awayGoals != null }
                .map {
                    FixtureResult(
                        fixtureId = it.id,
                        competitionId = model.id,
                        home = ClubId(it.homeClubId),
                        away = ClubId(it.awayClubId),
                        homeGoals = it.homeGoals!!,
                        awayGoals = it.awayGoals!!,
                    )
                }

            ApiResult.Ok(
                TableView(
                    competition = competition,
                    // La classifica ha senso solo dove si fanno punti. In un tabellone a
                    // eliminazione conta chi passa il turno, e una tabella di punti
                    // sarebbe una risposta a una domanda che nessuno ha fatto.
                    rows = if (competition.type == CompetitionType.ELIMINAZIONE_DIRETTA) {
                        emptyList()
                    } else {
                        Standings.compute(model, results)
                    },
                    matches = matches,
                ),
            )
        }

    private suspend fun matches(leagueId: Long, competitionId: Long): ApiResult<List<MatchRow>> {
        // `problema` si chiede in un primo tentativo e, se il database e' indietro, si
        // riprova senza: PostgREST per una colonna che non esiste rifiuta l'intera query, e
        // un calendario che sparisce e' molto peggio di un calendario che non spiega i
        // rinvii.
        val coda = "&league_id=eq.$leagueId&competition_id=eq.$competitionId" +
            "&order=match_day,kickoff"
        val comune = "/rest/v1/fixtures?select=id,competition_id,round,round_label," +
            "home_club_id,away_club_id,match_day,kickoff,played," +
            "match_results(home_goals,away_goals)"

        val esito = SupabaseApi.get("$comune,problema$coda").let {
            if (it is ApiResult.Error) SupabaseApi.get("$comune$coda") else it
        }

        return esito.then { body ->
            ApiResult.Ok(
                JsonNode.parse(body).asList().map { row ->
                    // PostgREST annida la relazione uno-a-uno come array di una riga.
                    val result = row["match_results"].let { if (it.isArray) it[0] else it }
                    MatchRow(
                        id = row["id"].long(0),
                        competitionId = row["competition_id"].long(0),
                        round = row["round"].int(0),
                        roundLabel = row["round_label"].str(""),
                        homeClubId = row["home_club_id"].long(0),
                        awayClubId = row["away_club_id"].long(0),
                        matchDay = row["match_day"].int(0),
                        kickoff = row["kickoff"].strOrNull()?.let(::parseKickoff),
                        played = row["played"].bool(false),
                        homeGoals = result["home_goals"].takeIf { it.exists }?.int(0),
                        awayGoals = result["away_goals"].takeIf { it.exists }?.int(0),
                        problema = row["problema"].strOrNull()?.takeIf { it.isNotBlank() },
                    )
                },
            )
        }
    }

    /**
     * L'orario resta un **istante**, e diventa un'ora solo quando si mostra.
     *
     * ## Perche' non si converte qui
     *
     * Prima questa funzione faceva due cose sbagliate insieme. Tagliava lo scostamento di
     * fuso e ci appiccicava una `Z`, buttando via due ore; poi convertiva nell'ora del
     * telefono, che e' la domanda sbagliata. Una partita e' un appuntamento fra persone: si
     * fissa alle nove **di casa della lega**, e deve leggersi alle nove su ogni telefono,
     * non alle dieci su quello di chi e' in vacanza.
     *
     * L'ora giusta la sa solo chi conosce il fuso della lega, che sta nella
     * configurazione. Qui si conserva il momento e basta.
     */
    private fun parseKickoff(raw: String): Instant? = Istanti.parse(raw)
}
