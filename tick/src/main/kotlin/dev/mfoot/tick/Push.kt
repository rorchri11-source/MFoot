package dev.mfoot.tick

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Fa suonare il telefono.
 *
 * ## Perche' esiste, dopo che era stato scartato
 *
 * All'inizio del progetto le notifiche push erano state escluse con una motivazione
 * buona: «un account Google, un progetto da configurare, un file di credenziali da tenere
 * fuori da un repository pubblico, e un servizio di qualcun altro acceso a nome tuo». Il
 * canale scelto era Telegram — gratis, gia' installato, niente da configurare.
 *
 * Il 2026-08-26 il proprietario ha detto la cosa che quel ragionamento non prevedeva:
 * **nel suo gruppo Telegram non lo usa nessuno**. Un canale che nessuno ha aperto non e'
 * un canale, e per un gioco asincrono restare senza mezzo di avviso significa dipendere
 * dal fatto che qualcuno si ricordi di aprire l'app.
 *
 * ## Perche' non c'e' l'SDK di Google
 *
 * Perche' il tick ha **una** dipendenza esterna, il driver del database, ed e' una
 * proprieta' che conviene tenere: un jar piccolo si costruisce in un minuto e si scarica
 * in un secondo, e i secondi qui sono il vincolo. L'SDK di Firebase ne porterebbe una
 * dozzina per fare due chiamate HTTP e una firma.
 *
 * Le due chiamate sono queste, e stanno in ottanta righe:
 *
 * 1. si firma un gettone con la chiave dell'account di servizio e lo si scambia con un
 *    permesso d'accesso valido un'ora;
 * 2. si manda il messaggio.
 *
 * La firma la fa `java.security`, che sta gia' dentro la macchina virtuale.
 *
 * ## Perche' non fallisce mai
 *
 * Una notifica non consegnata non deve fermare il tick, e non deve nemmeno far tornare
 * rosso il giro: il messaggio e' un **campanello**, mentre la verita' su cosa e' successo
 * sta nel database e ci resta. Se Firebase non risponde, quella riga resta da consegnare e
 * ci si riprova al giro dopo.
 */
class Push(private val chiaveJson: String?) {

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val credenziali: Credenziali? by lazy { leggiCredenziali() }

    val enabled: Boolean get() = credenziali != null

    /** Chi siamo, per Google. */
    private data class Credenziali(
        val email: String,
        val chiavePrivata: String,
        val progetto: String,
    )

    /**
     * Il permesso d'accesso, tenuto in memoria finche' vale.
     *
     * Dura un'ora e ottenerlo costa una firma piu' un viaggio verso Google. Rifarlo per
     * ogni notifica sarebbe il difetto di Telegram in un'altra forma: tante chiamate in
     * fila dove ne bastava una.
     */
    private var permesso: String? = null
    private var permessoScadeIl: Instant = Instant.EPOCH

    private fun leggiCredenziali(): Credenziali? {
        val testo = chiaveJson?.takeIf { it.isNotBlank() } ?: return null

        return runCatching {
            val root = JsonNode.parse(testo)
            val email = root["client_email"].strOrNull() ?: return null
            val chiave = root["private_key"].strOrNull() ?: return null
            val progetto = root["project_id"].strOrNull() ?: return null
            Credenziali(email, chiave, progetto)
        }.getOrElse {
            log("Chiave Firebase illeggibile (${it.message}): le notifiche restano spente.")
            null
        }
    }

    /**
     * Manda un messaggio a un telefono.
     *
     * @return true se Firebase l'ha accettato. False significa «riprova», non «perso».
     */
    fun manda(gettone: String, titolo: String, testo: String): Boolean {
        val c = credenziali ?: return false
        val accesso = permesso() ?: return false

        val corpo = JsonWriter(1024).apply {
            beginObject()
            objectField("message")
            field("token", gettone)
            // **Dati e non «notifica»**: una notifica disegnata da Firebase non viene
            // mostrata quando l'app e' in primo piano, ed e' proprio il momento in cui
            // serve. Vedi `MFootMessaging`.
            objectField("data")
            field("title", titolo)
            field("body", testo.take(900))
            endObject()
            objectField("android")
            field("priority", "high")
            endObject()
            endObject()
            endObject()
        }.toString()

        return runCatching {
            val richiesta = HttpRequest.newBuilder()
                .uri(URI.create("https://fcm.googleapis.com/v1/projects/${c.progetto}/messages:send"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer $accesso")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build()

            val risposta = http.send(richiesta, HttpResponse.BodyHandlers.ofString())
            when {
                risposta.statusCode() in 200..299 -> true
                // 404 e 403 su un gettone vuol dire che quel telefono non esiste piu':
                // app disinstallata, dati cancellati, gettone ruotato. Non e' un guasto e
                // non ha senso riprovare — chi chiama lo cancella.
                risposta.statusCode() == 404 || risposta.statusCode() == 403 -> {
                    log("Gettone morto (${risposta.statusCode()}): lo tolgo.")
                    gettoniMorti += gettone
                    false
                }
                else -> {
                    log("Firebase ha risposto ${risposta.statusCode()}: ${risposta.body().take(200)}")
                    false
                }
            }
        }.getOrElse {
            log("Firebase irraggiungibile (${it.message}): riprovo al prossimo giro.")
            false
        }
    }

    /** I gettoni che Firebase ha dichiarato morti in questo giro. Chi chiama li cancella. */
    val gettoniMorti = mutableSetOf<String>()

    /** Il permesso d'accesso, chiesto solo quando serve. */
    private fun permesso(): String? {
        val valido = permesso
        if (valido != null && Instant.now().isBefore(permessoScadeIl)) return valido

        val c = credenziali ?: return null

        return runCatching {
            val richiesta = HttpRequest.newBuilder()
                .uri(URI.create("https://oauth2.googleapis.com/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=" + URLEncoder.encode(
                            "urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8",
                        ) + "&assertion=" + URLEncoder.encode(gettoneFirmato(c), "UTF-8"),
                    ),
                )
                .build()

            val risposta = http.send(richiesta, HttpResponse.BodyHandlers.ofString())
            if (risposta.statusCode() !in 200..299) {
                log("Google ha rifiutato la chiave (${risposta.statusCode()}): ${risposta.body().take(200)}")
                return null
            }

            val root = JsonNode.parse(risposta.body())
            val accesso = root["access_token"].strOrNull() ?: return null
            // Un minuto di margine: un permesso che scade fra dieci secondi e' gia' scaduto
            // quando la richiesta arriva a Google.
            permessoScadeIl = Instant.now().plusSeconds(root["expires_in"].int(3600) - 60L)
            permesso = accesso
            accesso
        }.getOrElse {
            log("Non riesco a farmi autorizzare da Google (${it.message}).")
            null
        }
    }

    /**
     * Il gettone firmato che si scambia con il permesso d'accesso.
     *
     * E' un JWT: due pezzi di JSON in base64 e una firma RSA. La chiave privata arriva
     * dall'account di servizio in formato PEM, con le intestazioni e gli a capo che vanno
     * tolti prima di decodificarla.
     */
    private fun gettoneFirmato(c: Credenziali): String {
        val adesso = Instant.now().epochSecond

        val intestazione = """{"alg":"RS256","typ":"JWT"}"""
        val richieste = JsonWriter(512).apply {
            beginObject()
            field("iss", c.email)
            field("scope", "https://www.googleapis.com/auth/firebase.messaging")
            field("aud", "https://oauth2.googleapis.com/token")
            field("exp", adesso + 3600)
            field("iat", adesso)
            endObject()
        }.toString()

        val daFirmare = base64url(intestazione.toByteArray()) + "." + base64url(richieste.toByteArray())

        val pem = c.chiavePrivata
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()

        val chiave = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))

        val firma = Signature.getInstance("SHA256withRSA").apply {
            initSign(chiave)
            update(daFirmare.toByteArray())
        }.sign()

        return "$daFirmare.${base64url(firma)}"
    }

    /** Base64 nella variante per URL, senza riempimento: e' quella che vuole un JWT. */
    private fun base64url(dati: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(dati)
}
