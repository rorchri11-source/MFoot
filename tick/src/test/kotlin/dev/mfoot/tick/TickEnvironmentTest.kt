package dev.mfoot.tick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La configurazione deve fallire **subito e con un messaggio comprensibile**.
 *
 * Un tick che parte a metà e scrive dati incompleti è molto peggio di uno che non parte:
 * il primo lascia una lega in uno stato incoerente che qualcuno dovrà poi sbrogliare a
 * mano, il secondo si limita a non fare niente e il giro dopo recupera.
 */
class TickEnvironmentTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    private val jdbcConCredenziali =
        "jdbc:postgresql://aws-0-eu-central-1.pooler.supabase.com:5432/postgres" +
            "?user=postgres.abcdefgh&password=segretissima"

    private val jdbcSenzaCredenziali =
        "jdbc:postgresql://aws-0-eu-central-1.pooler.supabase.com:5432/postgres"

    @Test
    fun `l'url di Supabase con le credenziali dentro basta da solo`() {
        val config = TickEnvironment.fromEnv(env("MFOOT_DB_URL" to jdbcConCredenziali))

        assertEquals(jdbcConCredenziali, config.dbUrl)
        assertNull(config.dbUser, "con le credenziali nell'URL non servono i segreti separati")
        assertNull(config.dbPassword)
    }

    @Test
    fun `un url senza credenziali richiede utente e password`() {
        val config = TickEnvironment.fromEnv(
            env(
                "MFOOT_DB_URL" to jdbcSenzaCredenziali,
                "MFOOT_DB_USER" to "postgres.abcdefgh",
                "MFOOT_DB_PASSWORD" to "segretissima",
            ),
        )
        assertEquals("postgres.abcdefgh", config.dbUser)
        assertEquals("segretissima", config.dbPassword)
    }

    @Test
    fun `senza url non si parte`() {
        val errore = assertFailsWith<IllegalStateException> {
            TickEnvironment.fromEnv(env())
        }
        assertTrue(errore.message!!.contains("MFOOT_DB_URL"))
        assertTrue(errore.message!!.contains("Secrets"), "il messaggio deve dire dove impostarlo")
    }

    @Test
    fun `un url senza credenziali e senza segreti spiega cosa manca`() {
        val errore = assertFailsWith<IllegalStateException> {
            TickEnvironment.fromEnv(env("MFOOT_DB_URL" to jdbcSenzaCredenziali))
        }
        assertTrue(errore.message!!.contains("MFOOT_DB_USER"))
        assertTrue(errore.message!!.contains("JDBC"), "deve indicare l'alternativa piu' semplice")
    }

    /**
     * L'errore più probabile in assoluto: si copia la stringa da Supabase e ci si
     * dimentica di sostituire il segnaposto. Senza questo controllo il fallimento
     * arriverebbe come un'autenticazione rifiutata, che non dice cosa è successo.
     */
    @Test
    fun `il segnaposto della password non sostituito viene intercettato`() {
        val conSegnaposto = "jdbc:postgresql://host:5432/postgres" +
            "?user=postgres.abcdefgh&password=[YOUR-PASSWORD]"

        val errore = assertFailsWith<IllegalStateException> {
            TickEnvironment.fromEnv(env("MFOOT_DB_URL" to conSegnaposto))
        }
        assertTrue(errore.message!!.contains("[YOUR-PASSWORD]"))
    }

    @Test
    fun `i valori vuoti contano come mancanti`() {
        val errore = assertFailsWith<IllegalStateException> {
            TickEnvironment.fromEnv(env("MFOOT_DB_URL" to "   "))
        }
        assertTrue(errore.message!!.contains("MFOOT_DB_URL"))
    }

    @Test
    fun `le notifiche sono attive solo con token e chat insieme`() {
        val soloToken = TickEnvironment.fromEnv(
            env("MFOOT_DB_URL" to jdbcConCredenziali, "MFOOT_TELEGRAM_TOKEN" to "abc"),
        )
        assertTrue(!soloToken.notificationsEnabled)

        val entrambi = TickEnvironment.fromEnv(
            env(
                "MFOOT_DB_URL" to jdbcConCredenziali,
                "MFOOT_TELEGRAM_TOKEN" to "abc",
                "MFOOT_TELEGRAM_CHAT" to "123",
            ),
        )
        assertTrue(entrambi.notificationsEnabled)
    }

    @Test
    fun `la modalita di prova si attiva solo con true`() {
        fun dryRun(value: String?) = TickEnvironment.fromEnv(
            env(*listOfNotNull(
                "MFOOT_DB_URL" to jdbcConCredenziali,
                value?.let { "MFOOT_DRY_RUN" to it },
            ).toTypedArray()),
        ).dryRun

        assertTrue(dryRun("true"))
        assertTrue(dryRun("TRUE"))
        assertTrue(!dryRun("false"))
        assertTrue(!dryRun(null), "senza la variabile si scrive davvero")
        assertTrue(!dryRun(""), "un valore vuoto non deve attivare la prova per sbaglio")
    }
}
