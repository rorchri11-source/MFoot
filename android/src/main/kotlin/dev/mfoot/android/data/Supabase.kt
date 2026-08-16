package dev.mfoot.android.data

import dev.mfoot.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** Esito di un tentativo di raggiungere Supabase, in una forma mostrabile all'utente. */
sealed interface ConnectionStatus {
    data object NotConfigured : ConnectionStatus

    /** L'esempio della guida e' stato copiato senza sostituire i segnaposto. */
    data object Placeholder : ConnectionStatus
    data object Checking : ConnectionStatus
    data class Connected(val millis: Long) : ConnectionStatus
    data class Failed(val reason: String) : ConnectionStatus

    /** Deve stare su una riga sola accanto al conteggio: niente frasi. */
    val label: String
        get() = when (this) {
            NotConfigured -> "non collegato"
            Placeholder -> "url da compilare"
            Checking -> "collegamento…"
            is Connected -> "collegato"
            is Failed -> "errore"
        }

    /** Il dettaglio esteso, per quando servira' una schermata di diagnostica. */
    val detail: String
        get() = when (this) {
            NotConfigured -> "Credenziali Supabase assenti in local.properties"
            Placeholder -> "In local.properties c'e' ancora il segnaposto dell'esempio, " +
                "non l'indirizzo del tuo progetto"
            Checking -> "Verifica del collegamento in corso"
            is Connected -> "Collegato in ${millis}ms"
            is Failed -> reason
        }
}

/**
 * Client minimo per le API REST di Supabase.
 *
 * Volutamente senza SDK: le operazioni che servono sono richieste HTTP con due
 * intestazioni, e una dipendenza in meno e' una cosa in meno che puo' rompersi in fase
 * di build Android. Se un giorno serviranno realtime o storage si valutera' l'SDK vero.
 *
 * Le credenziali arrivano da `local.properties` tramite BuildConfig e non stanno nel
 * repository, che e' pubblico.
 */
object Supabase {

    private const val TIMEOUT_MS = 12_000

    val url: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key: String get() = BuildConfig.SUPABASE_KEY

    val isConfigured: Boolean get() = url.isNotBlank() && key.isNotBlank()

    /**
     * L'errore piu' probabile in assoluto: si copia la riga d'esempio dalla guida e ci si
     * dimentica di sostituire il nome del progetto. Senza questo controllo l'app dice
     * solo "errore" e non si capisce dove guardare.
     */
    private val isPlaceholder: Boolean
        get() = url.contains("IL-TUO-PROGETTO", ignoreCase = true) ||
            url.contains("YOUR-PROJECT", ignoreCase = true) ||
            key.contains("...")

    /**
     * Verifica che il tubo funzioni.
     *
     * Interroga una tabella qualsiasi chiedendo zero righe: quello che interessa non e'
     * il contenuto ma il **codice di risposta**. Un 200 significa che URL e chiave sono
     * giusti e che le Row Level Security rispondono; un 401 che la chiave e' sbagliata;
     * un errore di rete che non si arriva proprio.
     */
    suspend fun checkConnection(): ConnectionStatus = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext ConnectionStatus.NotConfigured
        if (isPlaceholder) return@withContext ConnectionStatus.Placeholder

        val started = System.currentTimeMillis()
        try {
            val connection = (URL("$url/rest/v1/leagues?select=id&limit=0").openConnection()
                as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("apikey", key)
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
            }

            val code = connection.responseCode
            val elapsed = System.currentTimeMillis() - started
            val body = runCatching {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            }.getOrDefault("")
            connection.disconnect()

            when {
                code in 200..299 -> ConnectionStatus.Connected(elapsed)
                code == 401 || code == 403 -> ConnectionStatus.Failed("Chiave rifiutata ($code)")
                code == 404 -> ConnectionStatus.Failed("Tabelle non trovate: lo schema SQL non e' stato eseguito")
                else -> ConnectionStatus.Failed("Errore $code: ${body.take(80)}")
            }
        } catch (e: Exception) {
            ConnectionStatus.Failed(e.message?.take(80) ?: "Rete non raggiungibile")
        }
    }
}
