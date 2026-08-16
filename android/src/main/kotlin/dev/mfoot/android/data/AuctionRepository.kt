package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import java.time.Duration
import java.time.Instant

/**
 * Un'asta come la vede chi la guarda.
 *
 * Il [currentPrice] e' pubblico e arriva dal server. La [myMax] e' la **propria** offerta
 * massima: quella degli altri non si vede, ed e' la regola su cui si regge tutto il
 * mercato. Se si vedessero i massimi altrui, l'asta diventerebbe un esercizio di lettura
 * invece che una scommessa.
 */
data class AuctionView(
    val id: Long,
    val targetType: String,
    val targetId: Long,
    val startedBy: Long,
    val endsAt: Instant,
    val currentPrice: Int,
    val bidCount: Int,
    val leaderClubId: Long?,
    val myMax: Int?,
    val startingPrice: Int,
) {
    fun isLeading(myClubId: Long?): Boolean = leaderClubId != null && leaderClubId == myClubId

    val hasMyBid: Boolean get() = myMax != null

    /** Quanto manca, in una forma che sta su una riga: `2h 14m`, `47s`. */
    fun timeLeft(now: Instant = Instant.now()): String {
        val left = Duration.between(now, endsAt)
        if (left.isNegative || left.isZero) return "in chiusura"
        val h = left.toHours()
        val m = left.toMinutes() % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${left.seconds}s"
        }
    }

    /**
     * L'offerta minima accettabile.
     *
     * Si batte il **prezzo**, non il massimo del capofila: quello e' segreto, e chiedere
     * di superarlo renderebbe impossibile offrire.
     */
    fun minimumBid(minimumRaise: Int): Int =
        if (bidCount == 0) startingPrice else currentPrice + minimumRaise
}

/** Le aste della lega, con le proprie offerte gia' agganciate. */
object AuctionRepository {

    suspend fun openAuctions(leagueId: Long): ApiResult<List<AuctionView>> {
        val path = "/rest/v1/auctions?select=id,target_type,target_id,started_by,ends_at," +
            "starting_price,current_price,bid_count,leader_club_id" +
            "&league_id=eq.$leagueId&status=eq.APERTA&order=ends_at"

        return SupabaseApi.get(path).then { body ->
            myMaxBids().then { mine ->
                ApiResult.Ok(
                    JsonNode.parse(body).asList().map { row ->
                        val id = row["id"].long(0)
                        AuctionView(
                            id = id,
                            targetType = row["target_type"].str("player"),
                            targetId = row["target_id"].long(0),
                            startedBy = row["started_by"].long(0),
                            endsAt = Instant.parse(normalizeInstant(row["ends_at"].str(""))),
                            currentPrice = row["current_price"].int(0),
                            bidCount = row["bid_count"].int(0),
                            leaderClubId = row["leader_club_id"].long(0).takeIf { it > 0 },
                            myMax = mine[id],
                            startingPrice = row["starting_price"].int(1),
                        )
                    },
                )
            }
        }
    }

    /**
     * Le proprie offerte massime.
     *
     * Le Row Level Security fanno gia' il filtro: questa query chiede *tutte* le offerte
     * e il database restituisce solo quelle del proprio club. Non e' una svista, e' il
     * punto — la riservatezza non dipende da cosa chiede il client.
     */
    private suspend fun myMaxBids(): ApiResult<Map<Long, Int>> =
        SupabaseApi.get("/rest/v1/bids?select=auction_id,max_amount").then { body ->
            ApiResult.Ok(
                JsonNode.parse(body).asList()
                    .groupBy { it["auction_id"].long(0) }
                    .mapValues { (_, rows) -> rows.maxOf { it["max_amount"].int(0) } },
            )
        }

    suspend fun startAuction(
        leagueId: Long,
        targetId: Long,
        startingPrice: Int,
        targetType: String = "player",
    ): ApiResult<Long> {
        val payload = JsonWriter(256)
            .beginObject()
            .field("p_league_id", leagueId)
            .field("p_target_type", targetType)
            .field("p_target_id", targetId)
            .field("p_starting_price", startingPrice)
            .endObject()
            .toString()

        return SupabaseApi.rpc("start_auction", payload).then { body ->
            val id = body.trim().trim('"').toLongOrNull()
            if (id == null) ApiResult.Error("Risposta inattesa dall'asta.") else ApiResult.Ok(id)
        }
    }

    /**
     * L'offerta massima.
     *
     * Si dichiara il proprio limite e si va a dormire: il sistema difende la posizione da
     * solo, alzando il prezzo quanto basta. E' cio' che rende accettabile che il mondo
     * giri su una griglia di cinque minuti — e cio' che evita di dover controllare il
     * telefono ogni ora.
     */
    suspend fun bid(auctionId: Long, clubId: Long, maxAmount: Int): ApiResult<BidOutcome> {
        val payload = JsonWriter(256)
            .beginObject()
            .field("p_auction_id", auctionId)
            .field("p_club_id", clubId)
            .field("p_max_amount", maxAmount)
            .endObject()
            .toString()

        return SupabaseApi.rpc("place_bid", payload).then { body ->
            val node = JsonNode.parse(body)
            if (!node["ok"].bool(false)) {
                ApiResult.Error(node["reason"].str("Offerta rifiutata."))
            } else {
                ApiResult.Ok(
                    BidOutcome(
                        currentPrice = node["current_price"].int(0),
                        youLead = node["you_lead"].bool(false),
                    ),
                )
            }
        }
    }

    data class BidOutcome(val currentPrice: Int, val youLead: Boolean)

    /**
     * Postgres scrive `+00:00`, `Instant.parse` vuole `Z`.
     *
     * Sembra un dettaglio da niente: senza questa riga ogni asta solleva un'eccezione al
     * momento di leggerne la scadenza, e la schermata del mercato resta vuota senza
     * spiegare perche'.
     */
    private fun normalizeInstant(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.endsWith("Z") -> trimmed
            trimmed.contains('+') -> trimmed.substringBeforeLast('+') + "Z"
            else -> "${trimmed}Z"
        }
    }
}
