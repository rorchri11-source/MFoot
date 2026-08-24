package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.market.TradeStatus
import java.time.Instant

/**
 * Una proposta di scambio come sta sul database.
 *
 * Gli identificativi dei giocatori restano numeri: i giocatori veri li ha gia' in mano chi
 * disegna la schermata, e portarseli dietro qui vorrebbe dire una seconda copia della rosa
 * che invecchia per conto suo.
 */
data class TradeRow(
    val id: Long,
    val fromClub: Long,
    val toClub: Long,
    val offered: List<Long>,
    val wanted: List<Long>,
    val cash: Int,
    val message: String,
    val status: TradeStatus,
    val answer: String,
    val createdAt: Instant?,
    val kind: TradeKind = TradeKind.SCAMBIO,
    val terms: TradeTerms = TradeTerms(),
) {
    fun isIncoming(myClubId: Long?): Boolean = toClub == myClubId
    val isPending: Boolean get() = status == TradeStatus.PROPOSTA

    /** Il giocatore prestato: per un prestito ce n'e' uno solo, ed e' fra gli offerti. */
    val loanedPlayer: Long? get() = if (kind == TradeKind.PRESTITO) offered.firstOrNull() else null
}

/**
 * Proporre, accettare, rifiutare, ritirare.
 *
 * ## Perche' tutto passa da funzioni SQL
 *
 * Accettare uno scambio e' l'unica operazione del gioco che sposta **giocatori e denaro fra
 * due club insieme**. O riesce tutta o non riesce per niente: una scrittura a meta'
 * lascerebbe un club senza il giocatore e senza i soldi, e nessuno se ne accorgerebbe
 * guardando il risultato. Solo il database sa fare quella promessa.
 *
 * E i controlli si rifanno la' anche se l'app li ha gia' fatti: fra la proposta e la
 * risposta possono passare giorni, e in mezzo il giocatore chiesto puo' essere finito
 * all'asta.
 */
object TradeRepository {

    suspend fun list(leagueId: Long): ApiResult<List<TradeRow>> {
        // Le Row Level Security mostrano solo le proposte in cui si e' coinvolti, quindi
        // qui non serve nessun filtro sul club: quello che torna e' gia' il proprio.
        val path = "/rest/v1/trades?select=id,from_club,to_club,offered,wanted,cash,message," +
            "status,answer,created_at,kind,terms&league_id=eq.$leagueId&order=created_at.desc"

        return SupabaseApi.get(path).mapMissingTable().then { body ->
            ApiResult.Ok(
                JsonNode.parse(body).asList().map { row ->
                    TradeRow(
                        id = row["id"].long(0),
                        fromClub = row["from_club"].long(0),
                        toClub = row["to_club"].long(0),
                        offered = row["offered"].asList().map { it.long(0) },
                        wanted = row["wanted"].asList().map { it.long(0) },
                        cash = row["cash"].int(0),
                        message = row["message"].str(""),
                        status = row["status"].enum(TradeStatus.PROPOSTA),
                        answer = row["answer"].str(""),
                        createdAt = row["created_at"].strOrNull()?.let(Istanti::parse),
                        kind = row["kind"].enum(TradeKind.SCAMBIO),
                        terms = row["terms"].let { t ->
                            TradeTerms(
                                matchDays = t["matchDays"].int(0),
                                fee = t["fee"].int(0),
                                wagePaidByBorrower = t["wagePaidByBorrower"].bool(true),
                                canPlayAgainstOwner = t["canPlayAgainstOwner"].bool(false),
                                kickoff = t["kickoff"].strOrNull()?.let(Istanti::parse),
                            )
                        },
                    )
                },
            )
        }
    }

    suspend fun propose(
        fromClub: Long,
        toClub: Long,
        offered: List<Long>,
        wanted: List<Long>,
        cash: Int,
        message: String,
    ): ApiResult<Unit> {
        val w = JsonWriter(1024)
        w.beginObject()
        w.field("p_from_club", fromClub)
        w.field("p_to_club", toClub)
        w.arrayField("p_offered")
        offered.forEach { w.value(it) }
        w.endArray()
        w.arrayField("p_wanted")
        wanted.forEach { w.value(it) }
        w.endArray()
        w.field("p_cash", cash)
        w.field("p_message", message)
        w.endObject()

        return SupabaseApi.rpc("propose_trade", w.toString()).mapMissingTable().then(::esito)
    }

    suspend fun respond(tradeId: Long, accept: Boolean, answer: String = ""): ApiResult<Unit> {
        val w = JsonWriter(256)
        w.beginObject()
        w.field("p_trade_id", tradeId)
        w.field("p_accept", accept)
        w.field("p_answer", answer)
        w.endObject()

        return SupabaseApi.rpc("respond_trade", w.toString()).mapMissingTable().then(::esito)
    }

    suspend fun withdraw(tradeId: Long): ApiResult<Unit> {
        val w = JsonWriter(128)
        w.beginObject()
        w.field("p_trade_id", tradeId)
        w.endObject()

        return SupabaseApi.rpc("withdraw_trade", w.toString()).mapMissingTable().then(::esito)
    }

    /**
     * "Could not find the table 'public.trades'" diventa una frase che dice cosa fare.
     *
     * E' un messaggio di PostgREST, corretto e inutile: chi lo legge non sa che esiste una
     * migrazione da incollare, e conclude che l'app e' rotta. La causa e' sempre la stessa e
     * la soluzione e' una riga, quindi tanto vale scriverla.
     */
    private fun ApiResult<String>.mapMissingTable(): ApiResult<String> = when {
        this !is ApiResult.Error -> this
        // `kind` e `terms` arrivano con 0014: se mancano quelle, la tabella c'e' ma la
        // SELECT no, e mandare qualcuno a cercare la migrazione sbagliata gli fa perdere
        // il pomeriggio.
        message.contains("kind") || message.contains("terms") ->
            ApiResult.Error(
                "Le trattative hanno bisogno della migrazione 0014_trattative.sql, che non " +
                    "è ancora stata applicata a questo database.",
            )
        MANCA_LA_TABELLA.any { message.contains(it) } ->
            ApiResult.Error(
                "Gli scambi hanno bisogno della migrazione 0008_trades.sql, che non è " +
                    "ancora stata applicata a questo database.",
            )
        else -> this
    }

    private val MANCA_LA_TABELLA =
        listOf("public.trades", "propose_trade", "respond_trade", "withdraw_trade")

    /**
     * Le funzioni rispondono `{ok, reason}` invece di lanciare un errore SQL.
     *
     * Un rifiuto — "non hai quel denaro", "l'ha gia' ceduto" — non e' un guasto: e' una
     * risposta di gioco che va letta e mostrata. Farla passare per un errore HTTP
     * significherebbe dire all'utente "qualcosa e' andato storto" quando invece il sistema
     * ha funzionato benissimo e la risposta e' no.
     */
    private fun esito(body: String): ApiResult<Unit> {
        val node = JsonNode.parse(body).let { if (it.asList().isNotEmpty()) it[0] else it }
        return if (node["ok"].bool(false)) {
            ApiResult.Ok(Unit)
        } else {
            ApiResult.Error(node["reason"].str("Proposta rifiutata."))
        }
    }
}

/**
 * Il tipo di trattativa.
 *
 * Sta accanto a [TradeRow] e non dentro `core` perche' e' una distinzione di **trasporto**:
 * il motore non ha bisogno di sapere che prestiti e amichevoli viaggiano nella stessa
 * tabella degli scambi, e infatti in `core` restano tre cose diverse con regole diverse.
 */
enum class TradeKind(val label: String) {
    SCAMBIO("Scambio"),
    PRESTITO("Prestito"),
    AMICHEVOLE("Amichevole"),
}

/**
 * Le condizioni che dipendono dal tipo.
 *
 * Tutti i campi hanno un valore anche quando non si applicano — un'amichevole non ha una
 * durata — perche' la schermata legge solo quelli del proprio tipo, e una struttura di
 * campi opzionali costringerebbe ogni lettura a un punto interrogativo che non risponde a
 * niente.
 */
data class TradeTerms(
    val matchDays: Int = 0,
    val fee: Int = 0,
    val wagePaidByBorrower: Boolean = true,
    val canPlayAgainstOwner: Boolean = false,
    val kickoff: Instant? = null,
)

/** Prestiti e amichevoli: la stessa casella, funzioni diverse. */
object DealRepository {

    suspend fun proposeLoan(
        fromClub: Long,
        toClub: Long,
        playerId: Long,
        matchDays: Int,
        fee: Int,
        wagePaidByBorrower: Boolean,
        canPlayAgainstOwner: Boolean,
        message: String,
    ): ApiResult<Unit> {
        val w = JsonWriter(512)
        w.beginObject()
        w.field("p_from_club", fromClub)
        w.field("p_to_club", toClub)
        w.field("p_player_id", playerId)
        w.field("p_match_days", matchDays)
        w.field("p_fee", fee)
        w.field("p_wage_paid_by_borrower", wagePaidByBorrower)
        w.field("p_can_play_against_owner", canPlayAgainstOwner)
        w.field("p_message", message)
        w.endObject()

        return SupabaseApi.rpc("propose_loan", w.toString()).mapMissingDeal().then(::esitoDeal)
    }

    suspend fun proposeFriendly(
        fromClub: Long,
        toClub: Long,
        kickoff: Instant,
        message: String,
    ): ApiResult<Unit> {
        val w = JsonWriter(384)
        w.beginObject()
        w.field("p_from_club", fromClub)
        w.field("p_to_club", toClub)
        w.field("p_kickoff", kickoff.toString())
        w.field("p_message", message)
        w.endObject()

        return SupabaseApi.rpc("propose_friendly", w.toString()).mapMissingDeal().then(::esitoDeal)
    }

    /** Accetta o rifiuta un prestito o un'amichevole. Gli scambi passano da `respond`. */
    suspend fun respond(tradeId: Long, accept: Boolean, answer: String = ""): ApiResult<Unit> {
        val w = JsonWriter(256)
        w.beginObject()
        w.field("p_trade_id", tradeId)
        w.field("p_accept", accept)
        w.field("p_answer", answer)
        w.endObject()

        return SupabaseApi.rpc("respond_deal", w.toString()).mapMissingDeal().then(::esitoDeal)
    }

    private fun ApiResult<String>.mapMissingDeal(): ApiResult<String> = when {
        this is ApiResult.Error &&
            listOf("propose_loan", "propose_friendly", "respond_deal").any { message.contains(it) } ->
            ApiResult.Error(
                "Prestiti e amichevoli hanno bisogno della migrazione 0014_trattative.sql, " +
                    "che non è ancora stata applicata a questo database.",
            )
        else -> this
    }

    private fun esitoDeal(body: String): ApiResult<Unit> {
        val node = JsonNode.parse(body).let { if (it.asList().isNotEmpty()) it[0] else it }
        return if (node["ok"].bool(false)) ApiResult.Ok(Unit)
        else ApiResult.Error(node["reason"].str("Proposta rifiutata."))
    }
}

/**
 * La controproposta: gli stessi giocatori, un'altra cifra.
 *
 * Sta accanto agli scambi e non dentro `TradeRepository` perche' e' l'unica operazione che
 * **crea una proposta rispondendo a una**: le due righe restano legate da `replies_to`, e
 * quel legame e' cio' che permette di leggere una trattativa come una conversazione invece
 * che come due proposte scollegate.
 */
object CounterRepository {

    suspend fun counter(tradeId: Long, cash: Int, message: String): ApiResult<Unit> {
        val w = JsonWriter(512)
        w.beginObject()
        w.field("p_trade_id", tradeId)
        w.field("p_cash", cash)
        w.field("p_message", message)
        w.endObject()

        return SupabaseApi.rpc("counter_trade", w.toString()).then { body ->
            val node = JsonNode.parse(body).let { if (it.asList().isNotEmpty()) it[0] else it }
            if (node["ok"].bool(false)) ApiResult.Ok(Unit)
            else ApiResult.Error(node["reason"].str("Controproposta rifiutata."))
        }.mapMissing()
    }

    private fun ApiResult<Unit>.mapMissing(): ApiResult<Unit> = when {
        this is ApiResult.Error && message.contains("counter_trade") -> ApiResult.Error(
            "Le controproposte hanno bisogno della migrazione 0021_controproposte.sql, " +
                "che non è ancora stata applicata a questo database.",
        )
        else -> this
    }
}
