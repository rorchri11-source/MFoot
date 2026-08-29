package dev.mfoot.core.match

import java.time.Duration
import java.time.Instant

/**
 * A che punto e' una partita, adesso.
 *
 * ## Perche' e' una regola e non un contatore dentro la schermata
 *
 * Perche' dal 2026-08-29 la partita dura **novanta minuti veri**, e il minuto non e' piu'
 * qualcosa che l'app fa avanzare: e' una proprieta' dell'orologio. Due telefoni che aprono
 * la stessa partita nello stesso istante devono vedere lo stesso minuto — se lo contasse
 * ognuno per conto suo, chi ha aperto l'app piu' tardi vedrebbe una partita piu' indietro,
 * e a quel punto «hai visto che gol al 78'?» non vorrebbe dire niente.
 *
 * E' anche cio' che rende il tempo reale **gratuito**: non serve nessuna richiesta durante
 * i novanta minuti. Il server simula una volta e salva la timeline, il telefono la legge e
 * chiede a questa funzione a che riga guardare.
 *
 * ## La forma della partita
 *
 * ```
 * inizio            45'              ripresa            90'
 *   |----primo tempo--|---intervallo---|---secondo tempo--|
 *   0              45 min         45+pausa            90+pausa
 * ```
 *
 * L'intervallo e' tempo reale che passa senza che il minuto avanzi: e' la finestra in cui
 * si cambia formazione, ed e' anche il momento in cui il server gioca il secondo tempo.
 */
object MatchClock {

    /** Quanti minuti reali dura un tempo. Sono anche i minuti di gioco: e' il punto. */
    const val MINUTI_PER_TEMPO = 45

    /** Il minuto a cui finisce la partita. */
    const val FINE = 90

    /** In che fase si trova una partita. */
    enum class Fase {
        /** Non e' ancora cominciata. */
        DA_GIOCARE,
        PRIMO_TEMPO,

        /**
         * I quindici o venti minuti in cui non si gioca.
         *
         * Non e' un dettaglio di realismo: e' l'unica finestra in cui una partita
         * asincrona diventa una partita a cui si partecipa.
         */
        INTERVALLO,
        SECONDO_TEMPO,
        FINITA,
    }

    /**
     * Dove sta la partita, e a che minuto.
     *
     * [attesaRipresa] e' vero quando l'orologio e' gia' nel secondo tempo ma il server non
     * l'ha ancora giocato — succede perche' il tick passa ogni cinque minuti, non ogni
     * secondo. Va distinto dall'intervallo: sono due attese diverse e chi guarda ha il
     * diritto di sapere quale delle due sta vivendo.
     */
    data class Stato(
        val fase: Fase,
        val minuto: Int,
        val attesaRipresa: Boolean = false,
    ) {
        val inGioco: Boolean get() = fase == Fase.PRIMO_TEMPO || fase == Fase.SECONDO_TEMPO
    }

    /**
     * Lo stato di una partita in diretta.
     *
     * @param kickoff quando comincia.
     * @param riprendeAlle quando riparte dopo l'intervallo. Null finche' il server non ha
     *   giocato il primo tempo: in quel caso l'intervallo si stima con [pausaMinuti], che
     *   e' esattamente cio' che il server usera'.
     * @param secondoTempoPronto se la timeline del secondo tempo e' gia' disponibile.
     */
    fun stato(
        kickoff: Instant,
        now: Instant,
        riprendeAlle: Instant?,
        pausaMinuti: Int,
        secondoTempoPronto: Boolean,
    ): Stato {
        if (now.isBefore(kickoff)) return Stato(Fase.DA_GIOCARE, 0)

        val trascorsi = minutiFra(kickoff, now)
        if (trascorsi < MINUTI_PER_TEMPO) return Stato(Fase.PRIMO_TEMPO, trascorsi)

        val ripresa = riprendeAlle ?: kickoff.plusSeconds((MINUTI_PER_TEMPO + pausaMinuti) * 60L)
        if (now.isBefore(ripresa)) return Stato(Fase.INTERVALLO, MINUTI_PER_TEMPO)

        // L'orologio dice secondo tempo, il server puo' non essere ancora passato. Il
        // minuto resta fermo al 45': mostrare il 61' di una partita di cui non si conosce
        // niente dopo il 45' vorrebbe dire un campo fermo con un cronometro che corre.
        if (!secondoTempoPronto) return Stato(Fase.INTERVALLO, MINUTI_PER_TEMPO, attesaRipresa = true)

        val nelSecondo = minutiFra(ripresa, now)
        if (nelSecondo >= MINUTI_PER_TEMPO) return Stato(Fase.FINITA, FINE)
        return Stato(Fase.SECONDO_TEMPO, MINUTI_PER_TEMPO + nelSecondo)
    }

    /**
     * L'ora in cui il server deve rigiocare: fine del primo tempo **reale** piu' la pausa.
     *
     * Si conta dal fischio d'inizio e non da adesso. Il tick arriva quando arriva — fino a
     * cinque minuti dopo l'orario, perche' `pg_cron` bussa a quella cadenza — e contando da
     * «adesso» ogni ritardo del server si sommerebbe alla partita: un fischio d'inizio in
     * ritardo di quattro minuti sposterebbe la ripresa di quattro minuti, e la partita
     * finirebbe a un'ora che non e' quella che nessuno aveva letto.
     */
    fun ripresaDi(kickoff: Instant, pausaMinuti: Int): Instant =
        kickoff.plusSeconds((MINUTI_PER_TEMPO + pausaMinuti.coerceAtLeast(0)) * 60L)

    /** Quando finisce, pausa compresa: serve a sapere se una partita occupa una serata. */
    fun fineDi(kickoff: Instant, pausaMinuti: Int): Instant =
        ripresaDi(kickoff, pausaMinuti).plusSeconds(MINUTI_PER_TEMPO * 60L)

    /** Quanto dura in tutto, in minuti reali. */
    fun durataMinuti(pausaMinuti: Int): Int = FINE + pausaMinuti.coerceAtLeast(0)

    private fun minutiFra(da: Instant, a: Instant): Int =
        Duration.between(da, a).toMinutes().coerceAtLeast(0L).toInt()
}
