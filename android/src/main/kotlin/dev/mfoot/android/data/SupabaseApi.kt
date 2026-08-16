package dev.mfoot.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** Esito di una chiamata: o il risultato, o un messaggio che si puo' mostrare a schermo. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Error(val message: String) : ApiResult<Nothing>
}

/**
 * Le chiamate a Supabase che servono all'app.
 *
 * Niente SDK: sono richieste HTTP con due intestazioni, e una dipendenza in meno e' una
 * cosa in meno che puo' rompersi in fase di build Android.
 */
object SupabaseApi {

    private const val TIMEOUT_MS = 30_000

    /** Il token dell'utente corrente. Null finche' non si e' fatto l'accesso. */
    @Volatile
    var accessToken: String? = null
        private set

    val isSignedIn: Boolean get() = accessToken != null

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
            is ApiResult.Ok -> {
                val token = runCatching {
                    JSONObject(response.value).optString("access_token").takeIf { it.isNotBlank() }
                }.getOrNull()

                if (token == null) {
                    ApiResult.Error("Risposta di accesso senza token.")
                } else {
                    accessToken = token
                    ApiResult.Ok(token)
                }
            }
        }
    }

    /** Crea la lega e carica il mondo in un'unica transazione lato database. */
    suspend fun createLeague(payload: JSONObject): ApiResult<Long> =
        withContext(Dispatchers.IO) {
            when (val response = rpc("create_league", payload)) {
                is ApiResult.Error -> response
                is ApiResult.Ok -> {
                    val id = response.value.trim().trim('"').toLongOrNull()
                    if (id == null) {
                        ApiResult.Error("Risposta inattesa: ${response.value.take(120)}")
                    } else {
                        ApiResult.Ok(id)
                    }
                }
            }
        }

    suspend fun joinLeague(accessCode: String, nickname: String): ApiResult<Long> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("p_access_code", accessCode)
                .put("p_nickname", nickname)

            when (val response = rpc("join_league", payload)) {
                is ApiResult.Error -> response
                is ApiResult.Ok -> {
                    val id = response.value.trim().trim('"').toLongOrNull()
                    if (id == null) ApiResult.Error("Codice non riconosciuto.")
                    else ApiResult.Ok(id)
                }
            }
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

    private fun rpc(function: String, payload: JSONObject): ApiResult<String> =
        request("/rest/v1/rpc/$function", "POST", payload.toString())

    /**
     * Una richiesta HTTP verso Supabase.
     *
     * @param wantHeader se valorizzato, restituisce quell'intestazione invece del corpo:
     *        serve per i conteggi, che Supabase mette in `content-range`.
     */
    private fun request(
        path: String,
        method: String,
        body: String? = null,
        authenticated: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
        wantHeader: String? = null,
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
                val bearer = if (authenticated) accessToken ?: Supabase.key else Supabase.key
                setRequestProperty("Authorization", "Bearer $bearer")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }

            val code = connection.responseCode
            val text = runCatching {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            }.getOrDefault("")
            val header = wantHeader?.let { connection.getHeaderField(it) }
            connection.disconnect()

            if (code in 200..299) {
                ApiResult.Ok(header ?: text)
            } else {
                ApiResult.Error(readableError(code, text))
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

/** Comodita' per costruire array JSON senza cerimonie. */
fun <T> Iterable<T>.toJsonArray(transform: (T) -> JSONObject): JSONArray =
    JSONArray().also { array -> forEach { array.put(transform(it)) } }
