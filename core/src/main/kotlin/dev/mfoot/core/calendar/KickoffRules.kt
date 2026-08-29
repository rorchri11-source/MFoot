package dev.mfoot.core.calendar

import dev.mfoot.core.config.CalendarConfig
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Le regole su **quando** si puo' fissare una partita.
 *
 * ## Perche' esistono, e perche' stanno in `core`
 *
 * Perche' un orario gia' passato non e' un orario: e' un errore che il sistema accetta
 * volentieri e che poi non sa piu' cosa farsene. Si sceglieva «oggi alle 15» alle 18, la
 * proposta partiva, e il rifiuto arrivava dal database — quando arrivava. Sul calendario
 * di una competizione era peggio: la prima giornata finiva in un momento gia' trascorso e
 * il tick la trattava come una partita da recuperare, cioe' la giocava subito, senza che
 * nessuno avesse schierato niente.
 *
 * La regola vale identica in due posti — l'amichevole si combina dall'app, il calendario lo
 * scrive l'admin — e in un terzo, il server, che deve rifiutare comunque perche' il
 * telefono non e' una difesa. Tre implementazioni della stessa frase sono tre occasioni di
 * scriverla diversa: qui e' una sola, con i suoi test, e le schermate la chiamano.
 *
 * ## Il margine
 *
 * Non basta «maggiore di adesso». Fissare una partita fra dieci secondi e' vero e inutile:
 * nessuno fa in tempo a schierare, e chi riceve la proposta non fa in tempo a leggerla.
 * [MARGINE_MINUTI] e' il tempo minimo che deve restare, ed e' quello che rende l'orario una
 * cosa su cui ci si puo' organizzare.
 */
object KickoffRules {

    /**
     * Quanto deve mancare a una partita perche' abbia senso fissarla.
     *
     * Quindici minuti: il tempo di leggere una notifica e aprire la formazione. Piu' corto
     * e' un appuntamento a cui l'altro non puo' arrivare; piu' lungo impedirebbe la cosa
     * che rende belle le amichevoli, cioe' combinarne una per stasera.
     */
    const val MARGINE_MINUTI: Long = 15

    /** Questo momento e' abbastanza in la' da poterci fissare una partita? */
    fun isPlayable(kickoff: LocalDateTime, now: LocalDateTime): Boolean =
        !kickoff.isBefore(now.plusMinutes(MARGINE_MINUTI))

    /**
     * Due partite dello stesso club sono troppo vicine?
     *
     * ## Perche' e' una distanza e non un tetto giornaliero
     *
     * Perche' il tetto giornaliero non sa che ore sono. `matchesPerDayPerClub` accettava
     * due partite alle 20:30 e alle 21:00 — sono due, nella stessa giornata, quindi
     * andava bene — e dal 2026-08-29 una partita **occupa 110 minuti reali**: quelle due
     * si sovrapporrebbero per un'ora e venti.
     *
     * La distanza si misura fra i due fischi d'inizio e vale nei due sensi: non conta
     * quale delle due sia stata fissata prima.
     *
     * @param oreMinime da `CalendarConfig.minHoursBetweenMatches`.
     */
    fun troppoVicine(uno: LocalDateTime, altro: LocalDateTime, oreMinime: Int): Boolean {
        if (oreMinime <= 0) return false
        val minuti = java.time.Duration.between(uno, altro).toMinutes()
        return StrictMath.abs(minuti) < oreMinime * 60L
    }

    /**
     * Perche' non si puo' giocare a quest'ora avendo gia' queste partite, o null se si puo'.
     *
     * [impegni] sono i fischi d'inizio delle altre partite dello stesso club, in ora di
     * lega. La frase dice **quando si potrebbe**, non solo che non si puo': chi ha appena
     * scelto un orario sa che ora ha scelto, e quello che non sa e' quale sarebbe libero.
     */
    fun problemaDiDistanza(
        kickoff: LocalDateTime,
        impegni: List<LocalDateTime>,
        oreMinime: Int,
    ): String? {
        val scontro = impegni.firstOrNull { troppoVicine(kickoff, it, oreMinime) } ?: return null
        val libero = scontro.plusHours(oreMinime.toLong())
        return "Gioca già alle ${ora(scontro)}: fra due partite devono passare $oreMinime ore. " +
            "Dalle ${ora(libero)} in poi va bene."
    }

    /** Come sopra, ragionando su istanti veri invece che su ore di lega. */
    fun isPlayable(kickoff: Instant, now: Instant): Boolean =
        !kickoff.isBefore(now.plusSeconds(MARGINE_MINUTI * 60))

    /**
     * Perche' quest'orario non va bene, in una frase da mostrare. Null se va bene.
     *
     * Il messaggio dice **cosa manca**, non «orario non valido»: chi ha appena toccato «oggi
     * alle 15» alle 18 e' perfettamente consapevole di che ora ha scelto, e quello che non
     * sa e' che il sistema non lo accetta.
     */
    fun problema(kickoff: LocalDateTime, now: LocalDateTime): String? = when {
        kickoff.isBefore(now) -> "Quell'ora è già passata: scegline una più avanti."
        !isPlayable(kickoff, now) ->
            "Mancano meno di $MARGINE_MINUTI minuti: nessuno dei due farebbe in tempo a schierare."
        else -> null
    }

    /**
     * Gli orari di una certa data su cui si puo' ancora contare.
     *
     * Serve alle schermate che mostrano dei pulsanti con l'ora: quelli passati vanno spenti,
     * non tolti. Toglierli farebbe ballare la fila di pulsanti sotto le dita di chi guarda
     * — «erano tre e adesso sono due, ho toccato qualcosa?» — mentre uno spento comunica
     * la cosa giusta, cioe' *quello c'e' ma non oggi*.
     */
    fun usableSlots(date: LocalDate, slots: List<LocalTime>, now: LocalDateTime): List<LocalTime> =
        slots.filter { isPlayable(LocalDateTime.of(date, it), now) }

    /**
     * Il primo momento in cui una competizione con questa configurazione scenderebbe in
     * campo, oppure null se non ne esiste nessuno nel periodo.
     */
    fun firstKickoff(calendar: CalendarConfig): LocalDateTime? {
        val slots = calendar.kickoffSlots.sorted()
        if (slots.isEmpty()) return null

        var giorno = calendar.startDate
        while (!giorno.isAfter(calendar.endDate)) {
            val libero = giorno.dayOfWeek !in calendar.restWeekdays && giorno !in calendar.restDates
            if (libero) return LocalDateTime.of(giorno, slots.first())
            giorno = giorno.plusDays(1)
        }
        return null
    }

    /**
     * Cosa non torna nelle date di una competizione, prima di scriverla.
     *
     * Torna un elenco e non un booleano perche' i motivi si sommano — un periodo al
     * contrario e una prima giornata nel passato sono due difetti diversi — e chi sta
     * compilando il modulo deve poterli sistemare tutti insieme invece di scoprirne uno per
     * volta a ogni tentativo.
     */
    fun problemiDiCalendario(calendar: CalendarConfig, now: LocalDateTime): List<String> {
        val problemi = ArrayList<String>(3)

        if (calendar.endDate.isBefore(calendar.startDate)) {
            problemi += "Il periodo finisce prima di cominciare."
        }
        if (calendar.kickoffSlots.isEmpty()) {
            problemi += "Nessun orario di inizio: non esiste una fascia in cui giocare."
        }

        val primo = firstKickoff(calendar)
        when {
            primo == null && problemi.isEmpty() ->
                problemi += "Nel periodo scelto non c'è nemmeno un giorno in cui si gioca."

            primo != null && !isPlayable(primo, now) ->
                problemi += "La prima partita cadrebbe il ${giorno(primo)} alle ${ora(primo)}, " +
                    "che è già passato: sposta l'inizio o togli gli orari più presti."
        }

        // LE FASCE TROPPO VICINE FRA LORO
        //
        // Dal 2026-08-29 una partita occupa 110 minuti reali. Due fasce a mezz'ora di
        // distanza non sono due occasioni di giocare: sono una partita che comincia mentre
        // la precedente e' ancora in corso. Il risolutore lo sa e semplicemente non ci
        // colloca niente, ma quello che si legge in quel caso e' «non c'e' stato spazio per
        // otto turni» — che descrive il sintomo e non la causa, e manda a cercare il difetto
        // nel periodo invece che negli orari.
        val ore = calendar.minHoursBetweenMatches
        val ordinate = calendar.kickoffSlots.sorted()
        val vicine = ordinate.zipWithNext().firstOrNull { (uno, due) ->
            java.time.Duration.between(uno, due).toMinutes() < ore * 60L
        }
        if (ore > 0 && vicine != null) {
            problemi += "Le ${vicine.first} e le ${vicine.second} sono troppo vicine: una partita " +
                "dura novanta minuti più l'intervallo, e fra due partite dello stesso club " +
                "devono passare $ore ore."
        }

        return problemi
    }

    private fun giorno(quando: LocalDateTime): String =
        "%02d/%02d".format(quando.dayOfMonth, quando.monthValue)

    private fun ora(quando: LocalDateTime): String =
        "%02d:%02d".format(quando.hour, quando.minute)
}
