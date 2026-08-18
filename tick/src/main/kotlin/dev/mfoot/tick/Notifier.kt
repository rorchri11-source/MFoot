package dev.mfoot.tick

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Manda i messaggi. E' l'unico pezzo del tick che parla con qualcosa fuori dal database.
 *
 * ## Perche' e' la cosa che mancava di piu'
 *
 * Il tick accumulava righe in `notifications` da mesi, e nessuno le consegnava. Le due
 * variabili d'ambiente per Telegram erano **dichiarate e mai usate da nessuna riga di
 * codice**.
 *
 * Per un gioco asincrono non e' una funzionalita' mancante: e' il canale di consegna.
 * Un'asta chiude alle 23 e non lo sa nessuno; arriva una proposta di scambio e resta li'.
 * Il gioco chiede di ricordarsi di aprirlo — e con dodici amici significa che in tre
 * giocano e gli altri si perdono per strada.
 *
 * ## Perche' Telegram e non le notifiche push
 *
 * Le push vere vorrebbero dire Firebase: un account Google, un progetto da configurare, un
 * file di credenziali da tenere fuori da un repository pubblico, e un servizio di qualcun
 * altro acceso a nome tuo. Il vincolo del progetto e' l'opposto: costo zero, nessuna carta
 * di credito, niente di proprio lasciato in funzione.
 *
 * Un bot di Telegram e' gratis, si crea in due minuti, scrive nel gruppo dove gli amici
 * gia' si scrivono, e non richiede che nessuno installi niente di nuovo. Per una lega fra
 * amici e' la risposta giusta, non il ripiego.
 *
 * ## Perche' non fallisce mai
 *
 * Una notifica non consegnata non deve fermare il tick. Se Telegram e' irraggiungibile, la
 * riga resta `delivered = false` e ci riprova il giro dopo: e' esattamente il
 * comportamento che si vuole, e costa niente perche' il tick ripassa comunque ogni cinque
 * minuti.
 */
class Notifier(private val config: TickEnvironment) {

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    val enabled: Boolean get() = config.notificationsEnabled

    /**
     * @return true se il messaggio e' partito. False significa "riprova piu' tardi", non
     *   "e' andato perso".
     */
    fun send(text: String): Boolean {
        val token = config.telegramToken ?: return false
        val chat = config.telegramChat ?: return false
        if (text.isBlank()) return true

        return runCatching {
            val body = JsonBody.obj(
                "chat_id" to chat,
                // Telegram taglia a 4096 caratteri e risponde con un errore. Meglio un
                // messaggio troncato che nessun messaggio.
                "text" to text.take(3900),
                "parse_mode" to "HTML",
                "disable_web_page_preview" to true,
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot$token/sendMessage"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val ok = response.statusCode() in 200..299
            if (!ok) log("Telegram ha risposto ${response.statusCode()}: ${response.body().take(200)}")
            ok
        }.getOrElse { errore ->
            log("Telegram irraggiungibile (${errore.message}): riprovo al prossimo giro.")
            false
        }
    }
}

/**
 * Il minimo JSON che serve per una chiamata sola.
 *
 * Non vale una dipendenza: il tick ne ha zero oltre al driver del database, ed e' una
 * proprieta' che conviene tenere. `core` ha gia' un `JsonWriter`, ma e' fatto per
 * costruire documenti grandi in un buffer riusabile; qui servono quattro campi.
 */
internal object JsonBody {
    fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(",", "{", "}") { (name, value) ->
            "\"${escape(name)}\":" + when (value) {
                null -> "null"
                is Boolean, is Number -> value.toString()
                else -> "\"${escape(value.toString())}\""
            }
        }

    private fun escape(text: String): String = buildString(text.length + 8) {
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
