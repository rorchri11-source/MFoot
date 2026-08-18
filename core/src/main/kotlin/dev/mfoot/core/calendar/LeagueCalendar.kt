package dev.mfoot.core.calendar

import java.time.LocalDate
import java.time.LocalDateTime

/** Che cosa succede in un giorno. Il colore nella griglia dipende da questo e basta. */
enum class CalendarEventKind {
    PARTITA_TUA,
    PARTITA_ALTRUI,
    AMICHEVOLE,
    ASTA,
    SCADENZA_CONTRATTO,
    SCADENZA_PROMESSA,
}

/**
 * Una cosa che succede, con l'ora se ce l'ha.
 *
 * [at] e' null per gli eventi che vivono a giornate e non a orologio — un contratto scade
 * "alla quattordicesima", non alle 21:00 — e la schermata lo dice invece di inventarsi
 * mezzanotte.
 */
data class CalendarEvent(
    val kind: CalendarEventKind,
    val at: LocalDateTime?,
    val title: String,
    val detail: String = "",
    /** Gia' successo: una partita giocata mostra il risultato al posto dell'ora. */
    val done: Boolean = false,
)

/** Una partita, come la conosce chi disegna il calendario. */
data class CalendarMatch(
    val matchDay: Int,
    /** Ora **di lega**: la conversione dal momento vero e' gia' avvenuta. */
    val kickoff: LocalDateTime?,
    val homeName: String,
    val awayName: String,
    val mine: Boolean,
    val friendly: Boolean = false,
    val played: Boolean = false,
    val scoreline: String = "",
)

/** Un'asta che chiude. */
data class CalendarAuction(val endsAt: LocalDateTime, val playerName: String, val price: String)

/** Qualcosa che scade a una certa giornata. */
data class CalendarDeadline(val matchDay: Int, val what: String)

/**
 * Il calendario del mese, costruito una volta e poi solo guardato.
 *
 * ## Perche' sta in `core`
 *
 * Perche' il pezzo difficile non e' disegnare la griglia: e' decidere **in che giorno cade
 * la quattordicesima giornata**. Contratti, promesse e prestiti scadono a giornate, che
 * sono l'unita' di tempo del gioco; le partite hanno una data, che e' l'unita' di tempo
 * delle persone. Le due si incontrano solo qui, e la regola con cui si incontrano si
 * testa senza un telefono acceso.
 *
 * ## Cosa succede alle giornate non ancora programmate
 *
 * Non compaiono. Se il calendario arriva fino alla ventesima e un contratto scade alla
 * ventiquattresima, quella data **non esiste ancora**: dipende da quando l'admin
 * programmera' il resto della stagione. Inventarla vorrebbe dire mettere un pallino rosso
 * su un giorno a caso, e chi lo vede si organizzerebbe su un'informazione falsa.
 */
object LeagueCalendar {

    /**
     * In che giorno cade ogni giornata.
     *
     * La prima partita di quella giornata: se la quattordicesima si gioca su due fasce
     * orarie, la giornata e' il giorno in cui comincia.
     */
    fun dateOfMatchDay(matches: List<CalendarMatch>): Map<Int, LocalDate> =
        matches
            .mapNotNull { m -> m.kickoff?.let { m.matchDay to it.toLocalDate() } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, date) -> date.min() }

    /**
     * Tutti gli eventi, giorno per giorno.
     *
     * Dentro ogni giorno l'ordine e' quello dell'orologio, e chi non ha un'ora va in
     * fondo: le scadenze valgono per tutta la giornata, e metterle a mezzanotte le
     * farebbe sembrare la prima cosa che succede.
     */
    fun build(
        matches: List<CalendarMatch>,
        auctions: List<CalendarAuction> = emptyList(),
        contracts: List<CalendarDeadline> = emptyList(),
        promises: List<CalendarDeadline> = emptyList(),
    ): Map<LocalDate, List<CalendarEvent>> {
        val giorni = mutableMapOf<LocalDate, MutableList<CalendarEvent>>()
        val quando = dateOfMatchDay(matches)

        fun aggiungi(date: LocalDate, event: CalendarEvent) {
            giorni.getOrPut(date) { mutableListOf() } += event
        }

        matches.forEach { m ->
            val ora = m.kickoff ?: return@forEach
            aggiungi(
                ora.toLocalDate(),
                CalendarEvent(
                    kind = when {
                        m.friendly -> CalendarEventKind.AMICHEVOLE
                        m.mine -> CalendarEventKind.PARTITA_TUA
                        else -> CalendarEventKind.PARTITA_ALTRUI
                    },
                    at = ora,
                    title = "${m.homeName} — ${m.awayName}",
                    detail = if (m.played) m.scoreline else "giornata ${m.matchDay}",
                    done = m.played,
                ),
            )
        }

        auctions.forEach { a ->
            aggiungi(
                a.endsAt.toLocalDate(),
                CalendarEvent(
                    kind = CalendarEventKind.ASTA,
                    at = a.endsAt,
                    title = "Asta: ${a.playerName}",
                    detail = a.price,
                ),
            )
        }

        contracts.forEach { c ->
            val date = quando[c.matchDay] ?: return@forEach
            aggiungi(
                date,
                CalendarEvent(
                    kind = CalendarEventKind.SCADENZA_CONTRATTO,
                    at = null,
                    title = "Contratto in scadenza: ${c.what}",
                    detail = "giornata ${c.matchDay}",
                ),
            )
        }

        promises.forEach { p ->
            val date = quando[p.matchDay] ?: return@forEach
            aggiungi(
                date,
                CalendarEvent(
                    kind = CalendarEventKind.SCADENZA_PROMESSA,
                    at = null,
                    title = "Promessa in scadenza: ${p.what}",
                    detail = "giornata ${p.matchDay}",
                ),
            )
        }

        return giorni.mapValues { (_, eventi) ->
            eventi.sortedWith(compareBy({ it.at == null }, { it.at }))
        }
    }

    /**
     * I colori da mostrare nella cella, senza ripetizioni e in ordine di importanza.
     *
     * Tre al massimo. Una cella con sette pallini non dice piu' cose di una con tre: dice
     * la stessa cosa in modo illeggibile, ed e' il motivo per cui il numero e' fisso qui
     * invece che deciso dalla larghezza dello schermo.
     */
    fun dots(events: List<CalendarEvent>, max: Int = 3): List<CalendarEventKind> =
        events.map { it.kind }
            .distinct()
            .sortedBy { PRIORITA.indexOf(it) }
            .take(max)

    private val PRIORITA = listOf(
        CalendarEventKind.PARTITA_TUA,
        CalendarEventKind.SCADENZA_PROMESSA,
        CalendarEventKind.SCADENZA_CONTRATTO,
        CalendarEventKind.ASTA,
        CalendarEventKind.AMICHEVOLE,
        CalendarEventKind.PARTITA_ALTRUI,
    )
}
