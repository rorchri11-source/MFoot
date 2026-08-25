package dev.mfoot.tick

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * Il programma che fa avanzare il mondo di MFoot.
 *
 * Parte, elabora quello che era in scadenza, salva, e termina. **Non** e' un servizio
 * sempre acceso: lo esegue GitHub Actions ogni cinque minuti, ed e' quello che permette
 * al gioco di girare a costo zero.
 *
 * ## Perche' funziona anche se non e' puntuale
 *
 * Il tick non chiede "cosa succede adesso" ma **"cosa sarebbe dovuto succedere fra
 * l'ultimo giro e adesso"**. Se GitHub ritarda l'esecuzione, o ne salta una del tutto,
 * al giro successivo si recupera l'intervallo perso in ordine e una volta sola.
 *
 * ## Configurazione
 *
 * Legge tutto da variabili d'ambiente, che su GitHub Actions arrivano dai *secrets* e
 * quindi non finiscono mai nel repository (che e' pubblico):
 *
 * | Variabile | Cosa contiene |
 * |---|---|
 * | `MFOOT_DB_URL` | URL JDBC del database Supabase |
 * | `MFOOT_DB_USER` | utente del database |
 * | `MFOOT_DB_PASSWORD` | password del database |
 * | `MFOOT_TELEGRAM_TOKEN` | token del bot per le notifiche (opzionale) |
 * | `MFOOT_TELEGRAM_CHAT` | chat a cui scrivere (opzionale) |
 * | `MFOOT_DRY_RUN` | se `true`, calcola il piano ma non scrive niente |
 */
fun main() {
    val startedAt = Instant.now()
    val config = TickEnvironment.fromEnv()

    log("MFoot World Tick - avvio $startedAt")
    if (config.dryRun) log("MODALITÀ DI PROVA: nessuna scrittura sul database.")

    // Il tempo che questo giro ha prima che GitHub lo ammazzi. Vedi [TickBudget]: senza,
    // due giri su tre venivano interrotti a transazione aperta e perdevano tutto.
    val budget = TickBudget.fromEnv(startedAt)

    val exitCode = try {
        connect(config).use { connection ->
            connection.autoCommit = false
            val runner = TickRunner(connection, config, budget = budget)
            val summary = runner.runAllLeagues(startedAt)
            log(summary.describe())
            // Una lega fallita rende rosso il giro. Vedi TickSummary.failed: prima
            // finivano tutte in un elenco che nessuno leggeva, sotto un'esecuzione verde.
            if (summary.failed) 2 else 0
        }
    } catch (e: Exception) {
        // Un tick fallito non e' un disastro: quello dopo recupera. Ma va registrato,
        // perche' un fallimento che si ripete e' un problema vero.
        log("ERRORE: ${e.message}")
        e.printStackTrace()
        1
    }

    log("Terminato in ${java.time.Duration.between(startedAt, Instant.now()).toMillis()} ms")
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}

/**
 * Apre la connessione accettando entrambe le forme.
 *
 * Supabase, con `Type = JDBC`, fornisce un URL che contiene gia' utente e password come
 * parametri. Pretendere comunque i due segreti separati costringerebbe a smontare quella
 * stringa a mano, che e' esattamente il genere di passaggio in cui si sbaglia.
 */
private fun connect(config: TickEnvironment): Connection {
    Class.forName("org.postgresql.Driver")
    return if (config.dbUser != null && config.dbPassword != null) {
        DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword)
    } else {
        DriverManager.getConnection(config.dbUrl)
    }
}

internal fun log(message: String) {
    println("[${Instant.now()}] $message")
}

/**
 * La configurazione del tick, presa dall'ambiente.
 *
 * Fallisce subito e con un messaggio chiaro se manca qualcosa: un tick che parte a meta'
 * e scrive dati incompleti sarebbe molto peggio di uno che non parte.
 */
data class TickEnvironment(
    val dbUrl: String,
    /** Facoltativo: serve solo se l'URL non contiene gia' le credenziali. */
    val dbUser: String? = null,
    val dbPassword: String? = null,
    val telegramToken: String? = null,
    val telegramChat: String? = null,
    val dryRun: Boolean = false,
) {
    val notificationsEnabled: Boolean
        get() = telegramToken != null && telegramChat != null

    companion object {
        fun fromEnv(getenv: (String) -> String? = System::getenv): TickEnvironment {
            fun optional(name: String): String? = getenv(name)?.takeIf { it.isNotBlank() }

            val url = optional("MFOOT_DB_URL") ?: error(
                "Variabile d'ambiente MFOOT_DB_URL mancante. " +
                    "Su GitHub va impostata in Settings > Secrets and variables > Actions.",
            )

            val user = optional("MFOOT_DB_USER")
            val password = optional("MFOOT_DB_PASSWORD")

            // Se l'URL non porta con se' le credenziali, devono arrivare dalle variabili.
            // Meglio accorgersene qui che con un errore di autenticazione dentro il tick.
            val urlHasCredentials = url.contains("user=") && url.contains("password=")
            if (!urlHasCredentials && (user == null || password == null)) {
                error(
                    "L'URL del database non contiene le credenziali, quindi servono anche " +
                        "MFOOT_DB_USER e MFOOT_DB_PASSWORD. In alternativa usa la stringa " +
                        "'Type: JDBC' di Supabase, che le include già.",
                )
            }
            if (urlHasCredentials && url.contains("[YOUR-PASSWORD]")) {
                error(
                    "Nell'URL c'è ancora il segnaposto [YOUR-PASSWORD]: " +
                        "va sostituito con la password vera del database.",
                )
            }

            return TickEnvironment(
                dbUrl = url,
                dbUser = user,
                dbPassword = password,
                telegramToken = optional("MFOOT_TELEGRAM_TOKEN"),
                telegramChat = optional("MFOOT_TELEGRAM_CHAT"),
                dryRun = getenv("MFOOT_DRY_RUN")?.equals("true", ignoreCase = true) ?: false,
            )
        }
    }
}
