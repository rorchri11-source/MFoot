package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import java.time.Duration
import java.time.Instant

/**
 * Un giocatore in vendita a prezzo fisso.
 *
 * [seller] null significa svincolato: non lo vende nessuno e i crediti non vanno a nessun
 * club. La distinzione conta per chi guarda — comprare da un rivale e' una notizia, e
 * raccogliere uno svincolato no.
 */
data class ListingView(
    val id: Long,
    val playerId: Long,
    val seller: Long?,
    val price: Int,
) {
    val isFreeAgent: Boolean get() = seller == null
}

/**
 * Un acquisto ancora dentro la finestra di contestazione.
 *
 * [contestableUntil] e' l'ora in cui diventa definitivo, ed e' nota dal primo istante:
 * chi ha comprato sa gia' quando sara' al sicuro, chi vuole contestare sa quanto tempo
 * ha. Un conto alla rovescia che si allunga da solo toglierebbe a tutti e due la
 * certezza.
 */
data class PurchaseView(
    val id: Long,
    val playerId: Long,
    val buyer: Long,
    val seller: Long?,
    val price: Int,
    val contestableUntil: Instant,
    val status: String,
    val auctionId: Long?,
) {
    val contestato: Boolean get() = status == "CONTESTATO"

    fun aperto(now: Instant = Instant.now()): Boolean =
        (status == "IN_FINESTRA" || status == "CONTESTATO") && now.isBefore(contestableUntil)

    /** Quanto manca, su una riga sola: `11h 42m`, `3m`. */
    fun tempoRimasto(now: Instant = Instant.now()): String {
        val left = Duration.between(now, contestableUntil)
        if (left.isNegative || left.isZero) return "in chiusura"
        val h = left.toHours()
        val m = left.toMinutes() % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${left.seconds}s"
        }
    }
}

/**
 * Il listino e la finestra di contestazione.
 *
 * ## Perche' questo file esiste accanto a [AuctionRepository]
 *
 * Perche' sono due mercati diversi, e tenerli separati e' la ragione per cui si capisce
 * quale si sta usando. Dal 2026-08-24 **si compra a prezzo fisso**: l'asta e' l'eccezione
 * che nasce solo quando qualcuno contesta, e in quel caso torna a passare da
 * `AuctionRepository` — anti-snipe, massimi segreti e blocco fondi sono gia' li'.
 */
/** Cosa e' successo comprando: quanto e' costato, e fino a quando si puo' contestare. */
data class Acquisto(val prezzo: Int, val contestabileFino: Instant?)

object MarketRepository {

    /**
     * Chi e' in vendita adesso, in tutta la lega.
     *
     * `target_type` distingue i giocatori dallo staff, e il filtro non e' facoltativo:
     * `players` e `staff` hanno sequenze separate, quindi il giocatore 7 e l'allenatore 7
     * esistono tutti e due. Senza il filtro, un allenatore in vendita comparirebbe come
     * cartellino del prezzo sopra la scheda di un giocatore qualsiasi.
     */
    suspend fun listings(
        leagueId: Long,
        tipo: String = "player",
    ): List<ListingView> {
        val path = "/rest/v1/listings?select=id,player_id,seller_club_id,price" +
            "&league_id=eq.$leagueId&status=eq.APERTO&target_type=eq.$tipo"

        return when (val esito = SupabaseApi.get(path)) {
            // Un listino illeggibile non e' un motivo per non aprire il mercato: si vedono
            // le aste e basta, come prima che il listino esistesse.
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().map { riga ->
                ListingView(
                    id = riga["id"].long(0),
                    playerId = riga["player_id"].long(0),
                    seller = riga["seller_club_id"].long(0).takeIf { it != 0L },
                    price = riga["price"].int(0),
                )
            }
        }
    }

    /** Gli acquisti ancora contestabili. */
    suspend fun purchases(leagueId: Long): List<PurchaseView> {
        val path = "/rest/v1/purchases?select=id,player_id,buyer_club_id,seller_club_id," +
            "price,contestable_until,status,auction_id&league_id=eq.$leagueId" +
            "&status=in.(IN_FINESTRA,CONTESTATO)&order=contestable_until.asc"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().mapNotNull { riga ->
                val until = riga["contestable_until"].strOrNull()
                    ?.let { runCatching { Instant.parse(normalizza(it)) }.getOrNull() }
                    ?: return@mapNotNull null

                PurchaseView(
                    id = riga["id"].long(0),
                    playerId = riga["player_id"].long(0),
                    buyer = riga["buyer_club_id"].long(0),
                    seller = riga["seller_club_id"].long(0).takeIf { it != 0L },
                    price = riga["price"].int(0),
                    contestableUntil = until,
                    status = riga["status"].str("IN_FINESTRA"),
                    auctionId = riga["auction_id"].long(0).takeIf { it != 0L },
                )
            }
        }
    }

    /**
     * Postgres scrive `2026-08-25T09:00:00+00:00`, `Instant.parse` vuole la `Z`.
     *
     * Tagliare lo scostamento e appiccicare una `Z` **butta via le ore senza fallire**: e'
     * il difetto che questo progetto ha gia' pagato in tre copie — aste che chiudevano due
     * ore dopo il conto alla rovescia, partite che comparivano piu' tardi, e il registro
     * che diceva «mai» accanto a un giro appena avvenuto. Qui lo scostamento si **somma**,
     * non si butta.
     */
    private fun normalizza(iso: String): String {
        if (iso.endsWith("Z")) return iso
        val segno = iso.lastIndexOfAny(charArrayOf('+', '-'))
        // Il `-` della data non e' uno scostamento: si guarda solo dopo l'ora.
        if (segno <= 10) return iso + "Z"
        return iso
    }

    /** Mette in vendita un proprio giocatore al prezzo che decide il proprietario. */
    suspend fun list(playerId: Long, price: Int): ApiResult<Unit> {
        val w = JsonWriter(128)
        w.beginObject()
        w.field("p_player_id", playerId)
        w.field("p_price", price)
        w.endObject()
        return SupabaseApi.rpc("list_player", w.toString()).then { ApiResult.Ok(Unit) }
    }

    suspend fun unlist(playerId: Long): ApiResult<Unit> {
        val w = JsonWriter(64)
        w.beginObject()
        w.field("p_player_id", playerId)
        w.endObject()
        return SupabaseApi.rpc("unlist_player", w.toString()).then { ApiResult.Ok(Unit) }
    }

    /**
     * Compra a prezzo fisso.
     *
     * Il server risponde con `ok` e l'ora in cui l'acquisto diventa definitivo: e' la
     * stessa informazione che il conto alla rovescia mostrera' per dodici ore.
     */
    suspend fun buy(playerId: Long): ApiResult<Acquisto> {
        val w = JsonWriter(64)
        w.beginObject()
        w.field("p_player_id", playerId)
        w.endObject()

        return SupabaseApi.rpc("buy_player", w.toString()).then { body ->
            val row = JsonNode.parse(body)
            if (row["ok"].bool(false)) {
                ApiResult.Ok(
                    Acquisto(
                        // Il prezzo **pagato**, non quello previsto: su uno svincolato lo
                        // calcola il server, e dirlo com'e' stato e' l'unico modo perche'
                        // il numero sullo schermo resti credibile.
                        prezzo = row["price"].int(0),
                        contestabileFino = row["contestable_until"].strOrNull()
                            ?.let { runCatching { Instant.parse(normalizza(it)) }.getOrNull() },
                    ),
                )
            } else {
                ApiResult.Error(row["reason"].str("Acquisto rifiutato."))
            }
        }
    }

    /** Svincola: gratis, e lo sa tutta la lega. */
    suspend fun release(playerId: Long): ApiResult<Unit> {
        val w = JsonWriter(64)
        w.beginObject()
        w.field("p_player_id", playerId)
        w.endObject()

        return SupabaseApi.rpc("release_player", w.toString()).then { body ->
            val row = JsonNode.parse(body)
            if (row["ok"].bool(false)) ApiResult.Ok(Unit)
            else ApiResult.Error(row["reason"].str("Non si può svincolare."))
        }
    }

    /**
     * Quanto costa uno svincolato, chiesto al server.
     *
     * ## Perche' non lo calcola il telefono, che saprebbe farlo
     *
     * Perche' il valore che l'app calcola da sola usa la **stima** del potenziale — la
     * forbice larga, diversa per ogni club — mentre il prezzo vero nasce dal potenziale
     * reale, che non esce mai dal server. Scrivere sul pulsante un numero e addebitarne un
     * altro e' il modo piu' rapido di far smettere di fidarsi dei numeri.
     *
     * Una chiamata, e solo quando si apre una scheda: non mille per disegnare una lista.
     */
    suspend fun freeAgentPrice(playerId: Long): Int? {
        val w = JsonWriter(64)
        w.beginObject()
        w.field("p_player_id", playerId)
        w.endObject()

        return when (val esito = SupabaseApi.rpc("free_agent_price", w.toString())) {
            is ApiResult.Error -> null
            is ApiResult.Ok -> esito.value.trim().trim('"').toIntOrNull()
        }
    }

    /** Mette in vendita un membro dello staff. */
    suspend fun listStaff(staffId: Long, price: Int): ApiResult<Unit> {
        val w = JsonWriter(128)
        w.beginObject()
        w.field("p_staff_id", staffId)
        w.field("p_price", price)
        w.endObject()
        return SupabaseApi.rpc("list_staff", w.toString()).then { ApiResult.Ok(Unit) }
    }

    /**
     * Compra un membro dello staff.
     *
     * Senza finestra di contestazione, di proposito: un preparatore atletico in piu' non
     * ribalta una stagione, e dodici ore d'attesa su ogni assunzione renderebbero lo staff
     * piu' faticoso dei giocatori — il contrario di quello che serve, visto che finora non
     * lo comprava nessuno.
     */
    suspend fun buyStaff(staffId: Long): ApiResult<Unit> {
        val w = JsonWriter(64)
        w.beginObject()
        w.field("p_staff_id", staffId)
        w.endObject()

        return SupabaseApi.rpc("buy_staff", w.toString()).then { body ->
            val row = JsonNode.parse(body)
            if (row["ok"].bool(false)) ApiResult.Ok(Unit)
            else ApiResult.Error(row["reason"].str("Acquisto rifiutato."))
        }
    }

    // ------------------------------------------------------------ l'amministratore

    /**
     * Gli interventi dell'amministratore.
     *
     * Tre funzioni strette invece di una che fa tutto, ed e' deliberato: l'admin e' uno
     * dei concorrenti — e' la ragione per cui gli obiettivi li decide una regola in `core`
     * e non lui — e questo e' l'unico punto del gioco dove quella separazione non c'e'.
     * Meno cose puo' fare da qui, meglio e'.
     */
    suspend fun adminAssegna(playerId: Long, clubId: Long): ApiResult<Unit> =
        chiamaAdmin("admin_assign_player") {
            field("p_player_id", playerId)
            field("p_club_id", clubId)
        }

    suspend fun adminSvincola(playerId: Long): ApiResult<Unit> =
        chiamaAdmin("admin_release_player") { field("p_player_id", playerId) }

    /** Il delta, non il totale: «+300» non puo' cancellare quello che il club ha guadagnato. */
    suspend fun adminCrediti(clubId: Long, delta: Int): ApiResult<Unit> =
        chiamaAdmin("admin_adjust_credits") {
            field("p_club_id", clubId)
            field("p_delta", delta)
        }

    private suspend fun chiamaAdmin(
        funzione: String,
        corpo: JsonWriter.() -> Unit,
    ): ApiResult<Unit> {
        val w = JsonWriter(128)
        w.beginObject()
        w.corpo()
        w.endObject()

        return SupabaseApi.rpc(funzione, w.toString()).then { body ->
            val row = JsonNode.parse(body)
            if (row["ok"].bool(false)) ApiResult.Ok(Unit)
            else ApiResult.Error(row["reason"].str("Operazione rifiutata."))
        }
    }

    /**
     * Contesta un acquisto: e' gia' un'offerta, e impegna i crediti nello stesso momento.
     *
     * Non esiste contestare per dispetto — se vinci, paghi. E' la regola decisa il
     * 2026-08-24 insieme al prezzo libero: le due si tengono in piedi a vicenda.
     */
    suspend fun contest(purchaseId: Long, maxAmount: Int): ApiResult<Unit> {
        val w = JsonWriter(128)
        w.beginObject()
        w.field("p_purchase_id", purchaseId)
        w.field("p_max_amount", maxAmount)
        w.endObject()

        return SupabaseApi.rpc("contest_purchase", w.toString()).then { body ->
            val row = JsonNode.parse(body)
            if (row["ok"].bool(false)) ApiResult.Ok(Unit)
            else ApiResult.Error(row["reason"].str("Contestazione rifiutata."))
        }
    }
}
