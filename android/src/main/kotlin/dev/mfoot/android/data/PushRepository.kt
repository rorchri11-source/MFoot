package dev.mfoot.android.data

import com.google.firebase.messaging.FirebaseMessaging
import dev.mfoot.core.json.JsonWriter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Il telefono si presenta al server, così il server sa dove bussare.
 *
 * ## Perche' questo file e' arrivato tardi
 *
 * Per mesi il gioco ha scritto ogni evento nella tabella `notifications` e non lo ha mai
 * detto a nessuno. L'unica uscita prevista era Telegram, e il 2026-08-26 il proprietario
 * ha detto la cosa che quel progetto non aveva previsto: **nel suo gruppo Telegram non lo
 * usa nessuno**.
 *
 * Per un gioco asincrono e' il difetto peggiore che ci sia: tutto funziona, e nessuno se
 * ne accorge. Un'asta chiude alle 23 e lo scopri il giorno dopo.
 *
 * ## Perche' si registra a ogni avvio e non una volta sola
 *
 * Perche' Firebase **ruota i gettoni quando gli pare**: dopo una reinstallazione, dopo una
 * cancellazione dei dati, o senza motivo apparente. Un gettone vecchio non da' errore —
 * smette semplicemente di consegnare, in silenzio, e non c'e' modo di accorgersene se non
 * notando che non arriva piu' niente.
 *
 * Una chiamata a ogni apertura costa una riga scritta e toglie di mezzo tutta quella
 * classe di guasti.
 *
 * ## Perche' niente qui puo' far fallire l'avvio
 *
 * Perche' le notifiche sono un di piu': se Firebase non risponde, se il permesso e' negato,
 * se il telefono non ha i servizi Google, il gioco deve funzionare identico. Ogni errore
 * qui dentro finisce in un `false` e in nient'altro.
 */
object PushRepository {

    /**
     * Chiede il gettone a Firebase e lo manda al server.
     *
     * @return true solo se il server ha confermato la registrazione.
     */
    suspend fun registra(): Boolean {
        val gettone = gettone() ?: return false

        val w = JsonWriter(256)
        w.beginObject()
        w.field("p_token", gettone)
        w.endObject()

        return when (SupabaseApi.rpc("register_device", w.toString())) {
            is ApiResult.Error -> false
            is ApiResult.Ok -> true
        }
    }

    /**
     * Il gettone di questa installazione.
     *
     * `FirebaseMessaging.getToken()` restituisce un `Task`, che e' il modo di Google di
     * dire «forse fra un po'». Qui diventa una sospensione normale, e qualunque cosa vada
     * storta diventa `null` invece di un'eccezione che risalirebbe fino all'avvio.
     */
    private suspend fun gettone(): String? = runCatching {
        suspendCancellableCoroutine { continuazione ->
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { esito ->
                    continuazione.resume(if (esito.isSuccessful) esito.result else null)
                }
        }
    }.getOrNull()
}
