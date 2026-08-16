package dev.mfoot.android

import android.app.Application
import dev.mfoot.android.data.Session

/**
 * Esiste per una riga sola: dare a [Session] un contesto prima che qualcuno lo usi.
 *
 * L'alternativa — passare il Context fin dentro il client HTTP — spargerebbe un dettaglio
 * di Android in codice che non ha nessun motivo di conoscerlo.
 */
class MFootApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.bind(this)
    }
}
