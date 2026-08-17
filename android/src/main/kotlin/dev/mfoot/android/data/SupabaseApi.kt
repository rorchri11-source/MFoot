package dev.mfoot.android.data

import dev.mfoot.core.json.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** Esito di una chiamata: o il risultato, o un messaggio che si puo' mostrare a schermo. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Error(val message: String) : ApiResult<Nothing>
}

/** Scorciatoia per incatenare chiamate senza una piramide di `when`. */
inline fun <T, R> ApiResult<T>.then(block: (T) -> ApiResult<R>): ApiResult<R> = when (this) {
    is ApiResult.Ok -> block(value)
    is ApiResult.Error -> this
}

/**
 * Le chiamate a Supabase che servono all'app.
 *
 * Niente SDK: sono richieste HTTP con due intestazioni, e una dipendenza in meno e' una
 * cosa in meno che puo' rompersi in fase di build Android.
 */
object SupabaseApi {

    private const val TIMEOUT_MS = 30_000

    // -------------------------------------------------------------------------- accesso

    /**
     * Garantisce che ci sia un token valido, nel modo meno distruttivo possibile.
     *
     * L'ordine conta. Un token fresco si usa; uno scaduto si rinnova; un accesso anonimo
     * nuovo si crea **solo se non c'e' proprio nessuna identita' da recuperare**. Se il
     * rinnovo fallisse per un problema di rete e l'app reagisse creando un utente nuovo,
     * l'utente si ritroverebbe fuori dalla propria lega per una connessione ballerina, e
     * senza modo di rientrare: l'identita' anonima precedente non e' piu' raggiungibile
     * da nessuno.
     */
    suspend fun ensureSession(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        if (Session.isFresh) return@withContext ApiResult.Ok(Unit)

        if (Session.canRefresh) {
            return@withContext when (val refreshed = refresh()) {
                is ApiResult.Ok -> ApiResult.Ok(Unit)
                is ApiResult.Error ->
                    // Il server ha detto che quel refresh token non vale piu': l'identita'
                    // e' persa comunque, tanto vale ripartire. Qualsiasi altro errore
                    // (rete, timeout, 500) si propaga: il token potrebbe essere ancora buono.
                    if (refreshed.message.contains("invalid", ignoreCase = true) ||
                        refreshed.message.contains("(400)") ||
                        refreshed.message.contains("(401)")
                    ) {
                        signInAnonymously().then { ApiResult.Ok(Unit) }
                    } else {
                        refreshed
                    }
            }
        }

        signInAnonymously().then { ApiResult.Ok(Unit) }
    }

    /**
     * Accesso anonimo.
     *
     * Nessuna email, nessuna password, nessun messaggio di conferma da aspettare: si
     * apre l'app e si e' dentro. E' la scelta giusta per una lega fra amici, dove
     * chiedere un indirizzo a venti persone e' attrito puro.
     *
     * Richiede che l'accesso anonimo sia attivo su Supabase: se e' spento, il messaggio
     * lo dice esplicitamente invece di lasciare un errore incomprensibile.
     */
    suspend fun signInAnonymously(): ApiResult<String> = withContext(Dispatchers.IO) {
        val response = request(
            path = "/auth/v1/signup",
            method = "POST",
            body = "{}",
            authenticated = false,
        )

        when (response) {
            is ApiResult.Error -> {
                // Supabase risponde sia con un codice sia con un testo in inglese, e
                // quale dei due arrivi qui dipende da come e' fatto il JSON. Si guardano
                // entrambi, o il messaggio utile non compare mai.
                val disabled = response.message.contains("anonymous_provider_disabled") ||
                    response.message.contains("Anonymous sign-ins are disabled", ignoreCase = true)

                if (disabled) {
                    ApiResult.Error(
                        "Accesso anonimo disattivato. Su Supabase: Authentication → " +
                            "Sign In / Providers → Anonymous sign-ins.",
                    )
                } else {
                    response
                }
            }

            is ApiResult.Ok ->
                if (Session.saveTokens(response.value)) {
                    ApiResult.Ok(Session.accessToken.orEmpty())
                } else {
                    ApiResult.Error("Risposta di accesso senza token.")
                }
        }
    }

    /** Rinnova il token senza cambiare identita'. */
    private fun refresh(): ApiResult<String> {
        val token = Session.refreshToken ?: return ApiResult.Error("Nessun token da rinnovare.")
        val body = JsonWriter(256).beginObject().field("refresh_token", token).endObject().toString()

        return request(
            path = "/auth/v1/token?grant_type=refresh_token",
            method = "POST",
            body = body,
            authenticated = false,
            allowRetry = false,
        ).then { response ->
            if (Session.saveTokens(response)) {
                ApiResult.Ok(Session.accessToken.orEmpty())
            } else {
                ApiResult.Error("Rinnovo senza token.")
            }
        }
    }

    // ------------------------------------------------------------------------ operazioni

    /**
     * Crea la lega e carica il mondo in un'unica transazione lato database.
     *
     * Il payload arriva gia' come testo: costruirlo come albero di oggetti faceva
     * uccidere l'app dal sistema per memoria esaurita. Vedi [WorldUpload].
     */
    suspend fun createLeague(payload: String): ApiResult<Long> =
        withContext(Dispatchers.IO) {
            request("/rest/v1/rpc/create_league", "POST", payload).then { it.asId() }
        }

    suspend fun joinLeague(accessCode: String, nickname: String): ApiResult<Long> =
        withContext(Dispatchers.IO) {
            val payload = JsonWriter(256)
                .beginObject()
                .field("p_access_code", accessCode)
                .field("p_nickname", nickname)
                .endObject()
                .toString()

            when (val response = request("/rest/v1/rpc/join_league", "POST", payload)) {
                is ApiResult.Error -> response
                is ApiResult.Ok -> response.value.asId("Codice non riconosciuto.")
            }
        }

    /**
     * Fonda il club e il giocatore custom, in una transazione sola.
     *
     * Il payload porta i **punti spesi**, non gli attributi finali: e' il database a
     * ricalcolare base, costi e overall. Mandare gli attributi gia' fatti vorrebbe dire
     * fidarsi di chi li ha calcolati, e chi li ha calcolati e' il telefono di un
     * giocatore.
     */
    suspend fun createClub(payload: String): ApiResult<ClubCreated> =
        withContext(Dispatchers.IO) {
            request("/rest/v1/rpc/create_club", "POST", payload).then { body ->
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@then ApiResult.Error("Risposta inattesa: ${body.take(120)}")
                ApiResult.Ok(
                    ClubCreated(
                        clubId = json.optLong("club_id"),
                        playerId = json.optLong("player_id"),
                        overall = json.optInt("overall"),
                        spent = json.optInt("spent"),
                    ),
                )
            }
        }

    data class ClubCreated(
        val clubId: Long,
        val playerId: Long,
        /** L'overall **ricalcolato dal server**: e' quello che vale. */
        val overall: Int,
        val spent: Int,
    )

    /** Una GET su PostgREST: percorso e filtri gia' pronti, risposta grezza. */
    suspend fun get(path: String): ApiResult<String> = withContext(Dispatchers.IO) {
        request(path, "GET")
    }

    /**
     * Una GET letta **in streaming**, senza mai avere l'intera risposta in memoria.
     *
     * E' l'unico modo accettabile di leggere la lista dei giocatori. Milletrecento righe
     * sono ~400 KB di testo, ma trasformarle prima in stringa e poi in albero di oggetti
     * ne costa decine di megabyte: e' esattamente il percorso che ha fatto uccidere l'app
     * per memoria esaurita in fase di caricamento. Qui il JSON viene consumato a pezzi e
     * ogni riga diventa subito un oggetto di gioco.
     *
     * Il lettore vive solo dentro [parse]: dopo, la connessione e' chiusa.
     */
    suspend fun <T> stream(
        path: String,
        extraHeaders: Map<String, String> = emptyMap(),
        parse: (android.util.JsonReader) -> T,
    ): ApiResult<T> =
        withContext(Dispatchers.IO) { streamOnce(path, extraHeaders, parse, allowRetry = true) }

    private fun <T> streamOnce(
        path: String,
        extraHeaders: Map<String, String>,
        parse: (android.util.JsonReader) -> T,
        allowRetry: Boolean,
    ): ApiResult<T> {
        if (!Supabase.isConfigured) {
            return ApiResult.Error("Credenziali Supabase assenti in local.properties.")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("${Supabase.url}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("apikey", Supabase.key)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${Session.accessToken ?: Supabase.key}")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val code = connection.responseCode
            when {
                // 206 e' la risposta normale quando si chiede un intervallo di righe.
                code in 200..299 ->
                    android.util.JsonReader(connection.inputStream.bufferedReader())
                        .use { ApiResult.Ok(parse(it)) }

                code == 401 && allowRetry && Session.canRefresh ->
                    when (refresh()) {
                        is ApiResult.Ok -> streamOnce(path, extraHeaders, parse, allowRetry = false)
                        is ApiResult.Error -> ApiResult.Error("Sessione scaduta.")
                    }

                else -> {
                    val body = runCatching {
                        connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                    }.getOrNull().orEmpty()
                    ApiResult.Error(readableError(code, body))
                }
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message?.take(140) ?: "Rete non raggiungibile")
        } finally {
            connection?.disconnect()
        }
    }

    /** Una chiamata a funzione SQL, con il corpo gia' serializzato. */
    suspend fun rpc(function: String, payload: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            request("/rest/v1/rpc/$function", "POST", payload)
        }

    /**
     * Scrive una riga su una tabella, sostituendola se c'e' gia'.
     *
     * ## Perche' non passa da una funzione SQL
     *
     * Quasi tutte le scritture del gioco vanno per forza da `security definer`, perche'
     * toccano cose che il proprietario non deve poter decidere da solo: i crediti, i
     * contratti, i risultati. La formazione no — e' l'unica cosa che appartiene davvero a
     * chi ha il club, e le Row Level Security la lasciano scrivere direttamente a lui e a
     * nessun altro (`write_own_lineup`).
     *
     * Resta comunque il server ad avere l'ultima parola: se in formazione finisse un
     * giocatore altrui, il tick lo scarterebbe perche' non e' nella rosa. Poter scrivere la
     * riga non vuol dire poter schierare chiunque.
     */
    suspend fun upsert(table: String, payload: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            request(
                path = "/rest/v1/$table",
                method = "POST",
                body = payload,
                // `merge-duplicates` trasforma l'insert in un upsert sulla chiave primaria:
                // senza, salvare due volte la stessa formazione darebbe un errore di
                // chiave duplicata al secondo salvataggio.
                extraHeaders = mapOf(
                    "Prefer" to "resolution=merge-duplicates,return=minimal",
                    "Content-Profile" to "public",
                ),
            ).then { ApiResult.Ok(Unit) }
        }

    /** Quante righe ci sono in una tabella: serve a verificare che il carico sia arrivato. */
    suspend fun count(table: String, leagueId: Long): ApiResult<Int> =
        withContext(Dispatchers.IO) {
            val response = request(
                path = "/rest/v1/$table?select=id&league_id=eq.$leagueId",
                method = "GET",
                extraHeaders = mapOf("Prefer" to "count=exact", "Range" to "0-0"),
                wantHeader = "content-range",
            )
            when (response) {
                is ApiResult.Error -> response
                is ApiResult.Ok -> {
                    val total = response.value.substringAfter('/', "").trim().toIntOrNull()
                    if (total == null) ApiResult.Error("Conteggio non leggibile.")
                    else ApiResult.Ok(total)
                }
            }
        }

    /** PostgREST restituisce gli scalari come testo nudo, virgolette comprese. */
    private fun String.asId(onFailure: String = "Risposta inattesa."): ApiResult<Long> {
        val id = trim().trim('"').toLongOrNull()
        return if (id == null) ApiResult.Error(onFailure) else ApiResult.Ok(id)
    }

    // ----------------------------------------------------------------------------- rete

    /**
     * Una richiesta HTTP verso Supabase.
     *
     * @param wantHeader se valorizzato, restituisce quell'intestazione invece del corpo:
     *        serve per i conteggi, che Supabase mette in `content-range`.
     * @param allowRetry al primo 401 rinnova il token e riprova una volta sola. Va spento
     *        sulla chiamata di rinnovo stessa, o un token rifiutato genera ricorsione.
     */
    private fun request(
        path: String,
        method: String,
        body: String? = null,
        authenticated: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
        wantHeader: String? = null,
        allowRetry: Boolean = true,
    ): ApiResult<String> {
        if (!Supabase.isConfigured) {
            return ApiResult.Error("Credenziali Supabase assenti in local.properties.")
        }

        return try {
            val connection = (URL("${Supabase.url}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("apikey", Supabase.key)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                // Con un token si agisce come utente; senza, come chiave pubblicabile.
                val bearer = if (authenticated) Session.accessToken ?: Supabase.key else Supabase.key
                setRequestProperty("Authorization", "Bearer $bearer")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    // In streaming, a blocchi: senza, HttpURLConnection accumula tutto
                    // il corpo in memoria per calcolare Content-Length, e con un
                    // caricamento da centinaia di kilobyte e' un'altra copia di troppo.
                    setChunkedStreamingMode(16 * 1024)
                    outputStream.bufferedWriter().use { it.write(body) }
                }
            }

            val code = connection.responseCode
            val text = runCatching {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            }.getOrDefault("")
            val header = wantHeader?.let { connection.getHeaderField(it) }
            connection.disconnect()

            when {
                code in 200..299 -> ApiResult.Ok(header ?: text)

                // Il token e' scaduto mentre l'app era aperta: si rinnova e si riprova,
                // in silenzio. Farlo qui evita di ripetere il controllo in ogni chiamata.
                code == 401 && allowRetry && authenticated && Session.canRefresh ->
                    when (refresh()) {
                        is ApiResult.Ok -> request(
                            path, method, body, authenticated, extraHeaders, wantHeader,
                            allowRetry = false,
                        )
                        is ApiResult.Error -> ApiResult.Error(readableError(code, text))
                    }

                else -> ApiResult.Error(readableError(code, text))
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message?.take(140) ?: "Rete non raggiungibile")
        }
    }

    /** Supabase restituisce JSON: meglio estrarne il messaggio che mostrare il grezzo. */
    private fun readableError(code: Int, body: String): String {
        val parsed = runCatching {
            val json = JSONObject(body)
            json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("msg").takeIf { it.isNotBlank() }
                ?: json.optString("error_description").takeIf { it.isNotBlank() }
                ?: json.optString("error_code").takeIf { it.isNotBlank() }
        }.getOrNull()

        return parsed?.let { "$it ($code)" } ?: "Errore $code: ${body.take(120)}"
    }
}
