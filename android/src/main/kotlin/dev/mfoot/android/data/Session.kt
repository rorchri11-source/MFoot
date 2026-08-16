package dev.mfoot.android.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * L'identita' dell'utente, salvata su disco.
 *
 * ## Perche' e' importante piu' di quanto sembri
 *
 * L'accesso e' anonimo: niente email, niente password. Il vantaggio e' che si apre l'app e
 * si e' dentro. Il prezzo e' che **l'unica prova di chi sei e' il refresh token su questo
 * telefono**: se va perso, l'utente non e' "disconnesso", e' una persona diversa, e il suo
 * club resta nella lega senza nessuno che possa piu' reclamarlo.
 *
 * Per questo il token si salva subito dopo l'accesso e non solo alla chiusura dell'app, e
 * per questo l'app non rifa' mai un accesso anonimo se ne ha gia' uno valido: sarebbe il
 * modo piu' rapido di perdere una squadra.
 *
 * ## Cosa NON sta qui
 *
 * Niente dati di gioco. Questo file risponde solo a "chi sono, in quale lega, con quale
 * club": tutto il resto si rilegge dal database, che e' la fonte autorevole.
 */
object Session {

    private const val PREFS = "mfoot.session"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_USER = "user_id"
    private const val KEY_LEAGUE = "league_id"
    private const val KEY_CLUB = "club_id"
    private const val KEY_NICKNAME = "nickname"

    /**
     * Margine sulla scadenza.
     *
     * Un token che scade fra dieci secondi e' gia' scaduto: fra il controllo e l'arrivo
     * della richiesta al server passa tempo, e un 401 a meta' di un caricamento da
     * quattrocento kilobyte costa molto piu' di un rinnovo anticipato.
     */
    private const val EXPIRY_MARGIN_MS = 120_000L

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Da chiamare una volta all'avvio, prima di qualsiasi chiamata di rete. */
    fun bind(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    // ------------------------------------------------------------------------- identita'

    val accessToken: String? get() = prefs?.getString(KEY_ACCESS, null)
    val refreshToken: String? get() = prefs?.getString(KEY_REFRESH, null)
    val userId: String? get() = prefs?.getString(KEY_USER, null)

    /** C'e' un token e non e' scaduto: si puo' chiamare il database senza rinnovarlo. */
    val isFresh: Boolean
        get() {
            val p = prefs ?: return false
            if (p.getString(KEY_ACCESS, null) == null) return false
            return System.currentTimeMillis() < p.getLong(KEY_EXPIRES, 0L) - EXPIRY_MARGIN_MS
        }

    /** C'e' di che rinnovare: l'identita' non e' persa anche se il token e' vecchio. */
    val canRefresh: Boolean get() = refreshToken != null

    /**
     * Salva la risposta di Supabase all'accesso o al rinnovo.
     *
     * Restituisce false se la risposta non conteneva un token: meglio accorgersene qui che
     * scoprire piu' tardi di aver salvato una sessione vuota.
     */
    fun saveTokens(authResponse: String): Boolean {
        val p = prefs ?: return false
        val json = runCatching { JSONObject(authResponse) }.getOrNull() ?: return false
        val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return false
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
        val expiresIn = json.optLong("expires_in", 3600L)
        val user = json.optJSONObject("user")?.optString("id")?.takeIf { it.isNotBlank() }

        p.edit()
            .putString(KEY_ACCESS, access)
            .apply {
                // Il rinnovo restituisce sempre un refresh token nuovo, ma se un giorno
                // non arrivasse, cancellare quello vecchio significherebbe buttare via
                // l'identita'. Si sovrascrive solo se c'e' qualcosa con cui sostituirlo.
                if (refresh != null) putString(KEY_REFRESH, refresh)
                if (user != null) putString(KEY_USER, user)
            }
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + expiresIn * 1000L)
            .commit()

        return true
    }

    // ----------------------------------------------------------------------- appartenenza

    var leagueId: Long?
        get() = prefs?.getLong(KEY_LEAGUE, 0L)?.takeIf { it > 0 }
        set(value) {
            prefs?.edit()?.apply {
                if (value == null) remove(KEY_LEAGUE) else putLong(KEY_LEAGUE, value)
            }?.commit()
        }

    var clubId: Long?
        get() = prefs?.getLong(KEY_CLUB, 0L)?.takeIf { it > 0 }
        set(value) {
            prefs?.edit()?.apply {
                if (value == null) remove(KEY_CLUB) else putLong(KEY_CLUB, value)
            }?.commit()
        }

    var nickname: String?
        get() = prefs?.getString(KEY_NICKNAME, null)
        set(value) {
            prefs?.edit()?.putString(KEY_NICKNAME, value)?.commit()
        }

    /**
     * Dimentica tutto.
     *
     * Non e' un "esci": e' un "diventa un'altra persona", perche' senza il refresh token
     * l'utente anonimo di prima non e' piu' raggiungibile da nessuno. Va offerto solo con
     * un avviso esplicito.
     */
    fun clear() {
        prefs?.edit()?.clear()?.commit()
    }
}
