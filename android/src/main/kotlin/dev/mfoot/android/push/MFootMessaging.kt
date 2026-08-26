package dev.mfoot.android.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.mfoot.android.MainActivity
import dev.mfoot.android.R
import dev.mfoot.android.data.PushRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Quello che succede quando il server bussa.
 *
 * ## Perche' il messaggio arriva come dati e non come «notifica»
 *
 * Firebase sa mandare due cose diverse, e la differenza conta. Una *notification message*
 * la disegna il sistema da solo, e quando l'app e' in primo piano **non la mostra**: chi
 * sta guardando la schermata non vede niente. Un *data message* arriva sempre qui dentro,
 * e siamo noi a decidere cosa farne.
 *
 * Serve la seconda, perche' un'asta che chiude mentre stai guardando il mercato e'
 * esattamente il momento in cui vuoi saperlo.
 *
 * ## Perche' non fa niente di piu' che mostrare
 *
 * Non scrive nel database, non aggiorna lo stato, non tiene niente in memoria. Il registro
 * completo di cosa e' successo sta in `notifications` e lo legge l'app quando si apre:
 * questa classe e' solo il campanello. Se una notifica si perde — telefono spento, permesso
 * negato, Firebase che scarta il messaggio — non si perde **niente**, perche' la verita'
 * sta nel database e non nel messaggio.
 */
class MFootMessaging : FirebaseMessagingService() {

    /**
     * Firebase ha cambiato il gettone di questa installazione.
     *
     * Succede dopo una reinstallazione, dopo una cancellazione dei dati, o quando decide
     * lui. Senza questa registrazione il vecchio gettone resta nel database e le notifiche
     * smettono di arrivare **senza nessun errore**: e' il tipo di guasto che non si scopre
     * mai, perche' il sintomo e' il silenzio.
     */
    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { PushRepository.registra() }
        }
    }

    override fun onMessageReceived(messaggio: RemoteMessage) {
        val testo = messaggio.data["body"]
            ?: messaggio.notification?.body
            ?: return

        val titolo = messaggio.data["title"]
            ?: messaggio.notification?.title
            ?: "MFoot"

        mostra(this, titolo, testo)
    }

    companion object {

        /**
         * Il canale delle notifiche.
         *
         * Da Android 8 ogni notifica deve appartenere a un canale, e il canale e' cio' che
         * l'utente puo' silenziare **senza silenziare tutto**. Uno solo: separare «aste» da
         * «partite» darebbe tre interruttori a chi ne vuole zero o uno.
         */
        private const val CANALE = "mfoot.partite"

        fun preparaIlCanale(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val canale = NotificationChannel(
                CANALE,
                "MFoot",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Aste, partite, scambi e scadenze della tua lega."
            }

            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(canale)
        }

        /**
         * Disegna la notifica.
         *
         * Toccarla apre l'app dove l'avevi lasciata: `singleTop` invece di una schermata
         * nuova, perche' chi tocca una notifica di MFoot vuole entrare in MFoot, non
         * ricominciare da capo.
         */
        fun mostra(context: Context, titolo: String, testo: String) {
            preparaIlCanale(context)

            val apri = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notifica = NotificationCompat.Builder(context, CANALE)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(titolo)
                // Il testo lungo si legge tutto aprendo la notifica: una riga tagliata a
                // meta' su un'asta che chiude e' peggio di nessuna notifica.
                .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
                .setContentText(testo)
                .setAutoCancel(true)
                .setContentIntent(apri)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            // Se il permesso non c'e' — su Android 13 e successivi si chiede, e si puo'
            // dire di no — questa chiamata lancia. Non e' un guasto: e' una scelta
            // dell'utente, e va rispettata in silenzio.
            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(System.currentTimeMillis().toInt(), notifica)
            }
        }
    }
}
