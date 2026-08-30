package dev.mfoot.android.app

import dev.mfoot.android.data.MatchMoment
import dev.mfoot.android.data.PlayedMatch
import dev.mfoot.core.match.MatchClock
import java.time.Instant

/**
 * La partita che si sta guardando.
 *
 * ## Le due modalita', e perche' non sono la stessa
 *
 * **In diretta** la partita si sta giocando adesso: il minuto non e' qualcosa che l'app fa
 * avanzare, e' una proprieta' dell'orologio. Due telefoni aperti nello stesso istante
 * devono vedere lo stesso minuto — se lo contasse ognuno per conto suo, chi apre l'app piu'
 * tardi vedrebbe una partita piu' indietro, e «hai visto che gol al 78'?» non vorrebbe dire
 * niente. Non si mette in pausa e non si salta avanti: non si mette in pausa una partita.
 *
 * **In differita** la partita e' finita: torna la riproduzione accelerata di sempre, sei
 * minuti di gioco al secondo, con pausa e salto alla fine. Quello che si vuole rivedere e'
 * *come e' andata*, e novanta minuti per raccontarlo sarebbero novanta minuti.
 *
 * ## Perche' il minuto resta comunque nello stato
 *
 * Perche' in differita si puo' mettere in pausa e saltare, e con il minuto dentro la
 * composizione ruotare lo schermo la farebbe ricominciare dall'inizio.
 */
data class MatchState(
    val partita: PlayedMatch? = null,
    val homeName: String = "",
    val awayName: String = "",
    /** Il minuto a cui e' arrivata la riproduzione. */
    val minuto: Int = 0,
    val inCorso: Boolean = false,
    /** Minuti di gioco per minuto reale: 1 (X1), 2 (X2), 3 (X3), 10 (X10). Vale in differita/replay. */
    val velocita: Int = 10,
    /**
     * La partita si sta giocando **adesso**.
     *
     * Deciso quando si apre e non ricalcolato a ogni giro: una partita che finisce mentre
     * la si guarda deve restare in diretta fino in fondo, non trasformarsi in un replay a
     * meta' del secondo tempo.
     */
    val diretta: Boolean = false,
    /** La fase dell'orologio: serve a distinguere l'intervallo dall'attesa del server. */
    val fase: MatchClock.Fase = MatchClock.Fase.FINITA,
    /** L'orologio dice secondo tempo ma il server non l'ha ancora giocato. */
    val attesaRipresa: Boolean = false,
    val caricamento: Boolean = true,
    val errore: String? = null,
    /** Quale scheda si sta guardando. */
    val scheda: MatchTab = MatchTab.CAMPO,
) {
    /** Quello che e' gia' successo, dal piu' recente. */
    val accaduto: List<MatchMoment>
        get() = partita?.moments.orEmpty()
            .filter { it.minute <= minuto && it.isNotable }
            .asReversed()

    val golCasa: Int get() = ultimo?.homeGoals ?: 0
    val golFuori: Int get() = ultimo?.awayGoals ?: 0

    /**
     * Il punteggio **a questo minuto**, non quello finale.
     *
     * Ogni evento porta con se' il punteggio di quel momento: senza, guardare una partita
     * gia' finita mostrerebbe il risultato definitivo fin dal primo minuto, e non ci
     * sarebbe niente da guardare.
     */
    private val ultimo: MatchMoment?
        get() = partita?.moments?.lastOrNull { it.minute <= minuto }

    /**
     * L'ultima azione, per disegnarla.
     *
     * Qui passano **tutti** gli eventi, non solo quelli notevoli: la telecronaca elenca i
     * momenti che contano, il campo mostra dov'e' la palla, e la palla sta da qualche parte
     * anche durante i quindici minuti in cui non succede niente.
     */
    val azione: MatchMoment?
        get() = partita?.moments?.lastOrNull { it.minute <= minuto }

    val finita: Boolean get() = partita != null && minuto >= MatchClock.FINE

    /** In diretta l'intervallo e' un'attesa vera, e va detto invece di mostrare un campo fermo. */
    val inIntervallo: Boolean get() = diretta && fase == MatchClock.Fase.INTERVALLO

    /**
     * Cosa sta succedendo, in una riga.
     *
     * Null quando si gioca: durante la partita il posto del cronometro lo prende il
     * cronometro, e una scritta che dice «primo tempo» accanto al 23' non aggiunge niente.
     */
    val avviso: String?
        get() = when {
            !diretta -> null
            fase == MatchClock.Fase.DA_GIOCARE -> "Non è ancora cominciata."
            attesaRipresa -> "Intervallo finito: il campo si riapre appena il server gioca il secondo tempo."
            fase == MatchClock.Fase.INTERVALLO -> "Intervallo. Si riprende fra poco."
            else -> null
        }

    companion object {
        /**
         * Lo stato di apertura, deciso dall'orologio.
         *
         * @param pausaMinuti la finestra dell'intervallo della lega: serve a stimare la
         *   ripresa finche' il server non l'ha scritta.
         */
        fun apri(
            partita: PlayedMatch,
            homeName: String,
            awayName: String,
            pausaMinuti: Int,
            now: Instant = Instant.now(),
        ): MatchState {
            val kickoff = partita.kickoff
            val stato = kickoff?.let {
                MatchClock.stato(
                    kickoff = it,
                    now = now,
                    riprendeAlle = partita.riprendeAlle,
                    pausaMinuti = pausaMinuti,
                    secondoTempoPronto = partita.completa,
                )
            }

            // In diretta finche' l'orologio non dice che e' finita (90 minuti veri + pausa intervallo).
            // Una partita senza orario — non dovrebbe esistere — si guarda in differita a fine gara.
            val inDiretta = stato != null && stato.fase != MatchClock.Fase.FINITA

            return MatchState(
                partita = partita,
                homeName = homeName,
                awayName = awayName,
                minuto = if (inDiretta) stato.minuto else MatchClock.FINE,
                inCorso = inDiretta,
                diretta = inDiretta,
                fase = stato?.fase ?: MatchClock.Fase.FINITA,
                attesaRipresa = stato?.attesaRipresa ?: false,
                caricamento = false,
            )
        }
    }
}

/**
 * Le quattro domande che si fanno a una partita.
 *
 * ## Perche' quattro schede e non una pagina sola
 *
 * Perche' erano una pagina sola — campo, poi telecronaca, poi pagelle in fondo — e la
 * risposta a «com'e' andata» stava a due schermate di distanza da quella a «chi ha giocato
 * bene». Segnalato dal proprietario con la frase che chiude ogni discussione:
 * *«interfaccia partita molto brutta e non si capisce niente»*.
 *
 * Le quattro sono quelle che una persona chiede davvero, nell'ordine in cui le chiede:
 * cosa sta succedendo, cosa e' successo di importante, chi ha dominato, chi ha giocato.
 */
enum class MatchTab(val label: String) {
    /** Il campo con la palla: e' quello che si guarda mentre si gioca. */
    CAMPO("Campo"),

    /** Gol, cambi ed espulsioni in fila: la partita in dieci righe. */
    RIASSUNTO("Riassunto"),

    /** Le statistiche a confronto, con le barre. */
    NUMERI("Numeri"),

    /** Le pagelle, divise per squadra. */
    FORMAZIONI("Formazioni"),
}
