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
    if (config.dryRun) log("MODALITA' DI PROVA: nessuna scrittura sul database.")

    val exitCode = try {
        connect(config).use { connection ->
            connection.autoCommit = false
            val runner = TickRunner(connection, config)
            val summary = runner.runAllLeagues(startedAt)
            log(summary.describe())
            0
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

private fun connect(config: TickEnvironment): Connection {
    Class.forName("org.postgresql.Driver")
    return DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword)
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
    val dbUser: String,
    val dbPassword: String,
    val telegramToken: String? = null,
    val telegramChat: String? = null,
    val dryRun: Boolean = false,
) {
    val notificationsEnabled: Boolean
        get() = telegramToken != null && telegramChat != null

    companion object {
        fun fromEnv(getenv: (String) -> String? = System::getenv): TickEnvironment {
            fun required(name: String): String = getenv(name)?.takeIf { it.isNotBlank() }
                ?: error(
                    "Variabile d'ambiente $name mancante. " +
                        "Su GitHub va impostata in Settings > Secrets and variables > Actions.",
                )

            return TickEnvironment(
                dbUrl = required("MFOOT_DB_URL"),
                dbUser = required("MFOOT_DB_USER"),
                dbPassword = required("MFOOT_DB_PASSWORD"),
                telegramToken = getenv("MFOOT_TELEGRAM_TOKEN")?.takeIf { it.isNotBlank() },
                telegramChat = getenv("MFOOT_TELEGRAM_CHAT")?.takeIf { it.isNotBlank() },
                dryRun = getenv("MFOOT_DRY_RUN")?.equals("true", ignoreCase = true) ?: false,
            )
        }
    }
}
