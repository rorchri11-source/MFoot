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
    /**
     * Quante squadre diverse sono dentro quest'asta.
     *
     * Diverso da [bidCount], ed e' la differenza che conta: sette offerte fatte da una
     * persona sola che alza la sua asticella sono una coda, sette offerte fatte da quattro
     * club sono una gara. Guardando solo il totale delle offerte le due si leggono uguali.
     */
    val bidders: Int = 0,
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
            val righe = JsonNode.parse(body).asList()
            val ids = righe.map { it["id"].long(0) }
            val quantiClub = bidders(ids)

            myMaxBids(ids).then { mine ->
                ApiResult.Ok(
                    righe.map { row ->
                        val id = row["id"].long(0)
                        AuctionView(
                            id = id,
                            targetType = row["target_type"].str("player"),
                            targetId = row["target_id"].long(0),
                            startedBy = row["started_by"].long(0),
                            endsAt = Istanti.parse(row["ends_at"].str("")) ?: Instant.EPOCH,
                            currentPrice = row["current_price"].int(0),
                            bidCount = row["bid_count"].int(0),
                            leaderClubId = row["leader_club_id"].long(0).takeIf { it > 0 },
                            myMax = mine[id],
                            startingPrice = row["starting_price"].int(1),
                            bidders = quantiClub[id] ?: 0,
                        )
                    },
                )
            }
        }
    }

    /**
     * Le proprie offerte massime, **sulle aste che si stanno guardando**.
     *
     * ## Perche' l'elenco delle aste va passato, e non basta chiederle tutte
     *
     * Le Row Level Security fanno il filtro sul contenuto: chiedendo tutte le offerte, il
     * database restituisce le proprie. Fin qui e' il punto — la riservatezza non dipende
     * da cosa chiede il client.
     *
     * Poi pero' le aste concluse hanno smesso di essere segrete: da `0020` le offerte di
     * chiunque su un'asta chiusa sono leggibili da tutta la lega, ed e' giusto, perche' e'
     * la parte piu' bella dell'asta. Ma «tutte le offerte» ha smesso di voler dire «le
     * mie»: e' diventato *ogni offerta di ogni club su ogni asta gia' finita*.
     *
     * PostgREST tronca a mille righe e non lo dice. Con un mercato vivo quelle mille righe
     * si riempiono di storia — le aste vecchie stanno in fondo alla tabella, quindi
     * arrivano per prime — e le proprie offerte sulle aste **aperte** finivano oltre il
     * taglio. Effetto visto: aste su cui si era offerto che comparivano come se non si
     * fosse mai offerto, senza il distintivo «in testa» e senza il massimo gia' dichiarato.
     *
     * Si chiedono quindi solo le offerte delle aste in elenco, a blocchi: il filtro sta
     * nella domanda, non nella speranza che la risposta ci stia.
     */
    private suspend fun myMaxBids(auctionIds: List<Long>): ApiResult<Map<Long, Int>> {
        if (auctionIds.isEmpty()) return ApiResult.Ok(emptyMap())

        val massimi = HashMap<Long, Int>(auctionIds.size)
        // A blocchi: un `in.(...)` con dentro trecento id diventa una URL che qualche
        // proxy taglia, e una richiesta tagliata torna come "nessuna offerta".
        auctionIds.chunked(80).forEach { blocco ->
            val path = "/rest/v1/bids?select=auction_id,max_amount" +
                "&auction_id=in.(${blocco.joinToString(",")})"

            when (val esito = SupabaseApi.get(path)) {
                is ApiResult.Error -> return esito
                is ApiResult.Ok -> JsonNode.parse(esito.value).asList().forEach { row ->
                    val asta = row["auction_id"].long(0)
                    val importo = row["max_amount"].int(0)
                    massimi[asta] = maxOf(massimi[asta] ?: 0, importo)
                }
            }
        }

        return ApiResult.Ok(massimi)
    }

    /**
     * Quanti club diversi sono dentro ogni asta.
     *
     * Passa dalla vista pubblica, che espone chi ha offerto ma mai quanto era disposto a
     * spendere. Se la vista non c'e' — database senza la migrazione `0023` — si torna a
     * zero per tutte, e l'elenco mostra solo il numero di offerte come faceva prima:
     * un'informazione in meno, non una schermata rotta.
     */
    private suspend fun bidders(auctionIds: List<Long>): Map<Long, Int> {
        if (auctionIds.isEmpty()) return emptyMap()

        val quanti = HashMap<Long, MutableSet<Long>>(auctionIds.size)
        auctionIds.chunked(80).forEach { blocco ->
            val path = "/rest/v1/auction_bids_public?select=auction_id,club_id" +
                "&auction_id=in.(${blocco.joinToString(",")})"

            when (val esito = SupabaseApi.get(path)) {
                is ApiResult.Error -> return emptyMap()
                is ApiResult.Ok -> JsonNode.parse(esito.value).asList().forEach { row ->
                    quanti.getOrPut(row["auction_id"].long(0)) { HashSet() }
                        .add(row["club_id"].long(0))
                }
            }
        }
        return quanti.mapValues { it.value.size }
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
     * La cronologia pubblica di un'asta: chi ha offerto e dove ha portato il prezzo.
     *
     * ## Cosa c'e' e cosa non c'e'
     *
     * C'e' il nome del club e il prezzo **pubblico** raggiunto dopo la sua offerta. Non
     * c'e' il massimo dichiarato, che resta segreto fino alla chiusura: e' quello che
     * permette di dichiarare il proprio limite e andare a dormire.
     *
     * Restituisce una lista vuota se la vista non c'e': una lega su un database senza la
     * migrazione `0023` vede l'asta come la vedeva prima, non una schermata rotta.
     */
    suspend fun history(auctionId: Long): List<BidEvent> {
        val path = "/rest/v1/auction_bids_public?select=club_id,club_name,club_short," +
            "public_price,placed_at&auction_id=eq.$auctionId&order=placed_at.desc"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().map { row ->
                BidEvent(
                    clubId = row["club_id"].long(0),
                    clubName = row["club_name"].str("?"),
                    clubShort = row["club_short"].str("?"),
                    publicPrice = row["public_price"].int(0).takeIf { it > 0 },
                    placedAt = Istanti.parse(row["placed_at"].str("")),
                )
            }
        }
    }

    // La scadenza si legge con [Istanti]. La versione precedente tagliava lo scostamento e
    // ci appiccicava una `Z`, spostando la chiusura di ogni asta di due ore: il conto alla
    // rovescia diceva "due ore e dieci" quando ne mancavano dieci minuti.
}

/**
 * Un rilancio, come lo vede la lega mentre l'asta e' ancora aperta.
 *
 * Il [publicPrice] e' il prezzo a cui l'asta e' arrivata **dopo** questa offerta, cioe' il
 * numero che tutti hanno visto in cima. Non e' il massimo di chi ha offerto: quello si
 * scopre a chiusura.
 */
data class BidEvent(
    val clubId: Long,
    val clubName: String,
    val clubShort: String,
    val publicPrice: Int?,
    val placedAt: java.time.Instant?,
)

/** Un'offerta a fine asta: chi, e fino a quanto si era spinto. */
data class BidRow(val clubId: Long, val maxAmount: Int)

/** Un'asta conclusa, con dentro tutte le offerte. */
data class ClosedAuction(
    val id: Long,
    val targetId: Long,
    val targetType: String,
    val status: String,
    val winnerClubId: Long?,
    val finalPrice: Int?,
    val bids: List<BidRow>,
) {
    val esito: String
        get() = when (status) {
            "AGGIUDICATA" -> "Aggiudicata"
            "DESERTA" -> "Deserta"
            else -> "Annullata"
        }
}

/**
 * Le aste finite, con chi ha offerto quanto.
 *
 * ## Perche' solo a fine asta
 *
 * Le offerte sono massimi segreti: si dichiara fin dove si e' disposti a spingersi e il
 * sistema difende la posizione da solo. Vederli mentre l'asta e' aperta cancellerebbe la
 * meccanica — sapendo che il capofila si ferma a diciotto, si offre diciotto e cento e si
 * vince sempre. Non sarebbe un'asta, sarebbe una coda.
 *
 * A fine asta non c'e' piu' niente da proteggere, e scoprire chi si era spinto fino a dove
 * e' la parte piu' divertente: finora spariva senza che nessuno la vedesse.
 */
object ClosedAuctionRepository {

    suspend fun recent(leagueId: Long, limit: Int = 30): List<ClosedAuction> {
        val path = "/rest/v1/auctions?select=id,target_id,target_type,status,winner_club_id," +
            "final_price,bids(club_id,max_amount)" +
            "&league_id=eq.$leagueId&status=neq.APERTA&order=id.desc&limit=$limit"

        return when (val esito = SupabaseApi.get(path)) {
            // Migrazione non applicata: le offerte altrui restano invisibili e la
            // schermata mostra un elenco vuoto invece di rompersi.
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().map { row ->
                ClosedAuction(
                    id = row["id"].long(0),
                    targetId = row["target_id"].long(0),
                    targetType = row["target_type"].str("player"),
                    status = row["status"].str("DESERTA"),
                    winnerClubId = row["winner_club_id"].long(0).takeIf { it > 0 },
                    finalPrice = row["final_price"].int(0).takeIf { it > 0 },
                    // In ordine decrescente: chi si e' spinto piu' in alto sta in cima, ed
                    // e' l'unica sequenza in cui la classifica di un'asta si legge.
                    bids = row["bids"].asList()
                        .map { BidRow(it["club_id"].long(0), it["max_amount"].int(0)) }
                        .sortedByDescending { it.maxAmount },
                )
            }
        }
    }
}
