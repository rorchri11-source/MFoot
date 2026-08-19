package dev.mfoot.android.app

import dev.mfoot.android.data.ClubInfo
import dev.mfoot.android.data.CompetitionInfo
import dev.mfoot.android.data.TableView
import dev.mfoot.core.calendar.CompetitionType
import dev.mfoot.core.calendar.Schedule
import dev.mfoot.core.config.CalendarConfig
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.config.ConfigValidator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * La competizione che l'admin sta componendo.
 *
 * Ogni modifica ricalcola il calendario in locale con `core`: e' quello che permette di
 * mostrare *prima* quante partite escono, in che giorni e con quali avvisi. Un pulsante
 * che genera al buio e si scopre dopo non e' una decisione, e' una scommessa.
 */
data class CompetitionDraft(
    val name: String = "",
    val type: CompetitionType = CompetitionType.GIRONE,
    val doubleRound: Boolean = false,
    /** Id dei club iscritti. Di partenza ci sono tutti. */
    val participants: Set<Long> = emptySet(),
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate = LocalDate.now().plusDays(14),
    /** I giorni della settimana in cui non si gioca. */
    val restWeekdays: Set<DayOfWeek> = emptySet(),
    val kickoffSlots: List<LocalTime> = listOf(LocalTime.of(18, 30), LocalTime.of(21, 0)),
    /** L'anteprima, ricalcolata a ogni modifica. */
    val schedule: Schedule? = null,
    val busy: String? = null,
    val errore: String? = null,
) {

    /** Quante giornate servono, dato il numero di squadre e se c'e' il ritorno. */
    val matchDaysNeeded: Int
        get() {
            val squadre = participants.size
            if (squadre < 2) return 0
            val andata = when (type) {
                CompetitionType.GIRONE -> ConfigValidator.roundRobinMatchDays(squadre)
                // In un tabellone ogni turno dimezza: log2 arrotondato per eccesso.
                CompetitionType.ELIMINAZIONE_DIRETTA -> {
                    var turni = 0
                    var rimasti = squadre
                    while (rimasti > 1) { rimasti = (rimasti + 1) / 2; turni++ }
                    turni
                }
                CompetitionType.GIRONI_PIU_ELIMINAZIONE -> ConfigValidator.roundRobinMatchDays(squadre)
            }
            return if (doubleRound && supportsDoubleRound) andata * 2 else andata
        }

    /** I giorni del periodo in cui si puo' davvero giocare, tolti quelli buca. */
    val playableDays: Int
        get() {
            if (endDate.isBefore(startDate)) return 0
            var day = startDate
            var count = 0
            while (!day.isAfter(endDate)) {
                if (day.dayOfWeek !in restWeekdays) count++
                day = day.plusDays(1)
            }
            return count
        }

    /**
     * Le partite al giorno **non si impostano: si calcolano**.
     *
     * E' il numero di giornate da giocare diviso i giorni disponibili. Chiederlo
     * all'admin significherebbe fargli fare a mano una divisione che il programma sa
     * fare, e sbagliarla vuol dire o una stagione che non ci sta nel periodo o giornate
     * ammassate senza motivo.
     *
     * Con venti squadre e diciannove giornate in dieci giorni vengono due partite al
     * giorno; nello stesso periodo con dodici squadre ne basta una.
     */
    val matchesPerDayPerClub: Int
        get() {
            val giorni = playableDays
            if (giorni <= 0 || matchDaysNeeded <= 0) return 1
            return ((matchDaysNeeded + giorni - 1) / giorni).coerceIn(1, 4)
        }

    /** Il periodo basta per le partite richieste? */
    val fits: Boolean get() = matchDaysNeeded <= playableDays * matchesPerDayPerClub

    val calendar: CalendarConfig
        get() = CalendarConfig(
            startDate = startDate,
            endDate = endDate,
            matchesPerDayPerClub = matchesPerDayPerClub,
            restWeekdays = restWeekdays,
            kickoffSlots = kickoffSlots,
        )

    val ready: Boolean
        get() = name.isNotBlank() && participants.size >= 2 &&
            schedule != null && schedule.fixtures.isNotEmpty()

    /**
     * Il ritorno ha senso solo dove esiste.
     *
     * In un campionato significa andata e ritorno; in un tabellone, doppia sfida.
     * Mostrarlo come opzione libera in ogni formato porterebbe a chiedersi cosa faccia.
     */
    val supportsDoubleRound: Boolean get() = type != CompetitionType.GIRONI_PIU_ELIMINAZIONE
}

/**
 * Classifica e calendario di una competizione.
 *
 * Aperta da chiunque, non solo dall'admin: e' la schermata che si guarda piu' spesso di
 * tutte, ed e' quella che rende una lega un campionato invece di una serie di partite.
 */
data class TableState(
    val competitions: List<CompetitionInfo>,
    val selectedId: Long?,
    val view: TableView? = null,
    val clubs: List<ClubInfo> = emptyList(),
    val myClubId: Long? = null,
    val tab: TableTab = TableTab.CLASSIFICA,
    val errore: String? = null,
    /**
     * Il fuso della lega.
     *
     * Gli orari si mostrano in ora di lega e non in ora del telefono: una partita e' un
     * appuntamento fra persone, e "alle nove" deve voler dire la stessa cosa per tutti.
     */
    val zone: java.time.ZoneId = java.time.ZoneId.of("Europe/Rome"),
) {
    fun clubName(id: Long): String = clubs.firstOrNull { it.id == id }?.name ?: "Club #$id"

    /** L'ora di lega di una partita, o null se non e' ancora programmata. */
    fun oraDi(match: dev.mfoot.android.data.MatchRow): java.time.LocalDateTime? =
        match.kickoff?.let { java.time.LocalDateTime.ofInstant(it, zone) }

    fun kitOf(id: Long): dev.mfoot.android.ui.kit.Kit? = clubs.firstOrNull { it.id == id }?.kit
}

/**
 * Le due domande che si fanno a questa schermata.
 *
 * Erano una schermata sola, con la classifica in cima e il calendario sotto, e a ragione la
 * barra in basso aveva due voci che aprivano la stessa identica cosa. Sono due domande
 * diverse: "a che punto siamo" si guarda una volta al giorno, "quando gioco" si guarda
 * prima di schierare. Metterle in fila obbliga a scorrere una classifica da venti righe
 * ogni volta che si vuole sapere l'orario della prossima partita.
 */
enum class TableTab(val label: String) {
    CLASSIFICA("Classifica"),
    CALENDARIO("Calendario"),
}

/** La schermata delle competizioni: quelle esistenti e quella in costruzione. */
data class CompetitionsState(
    val leagueId: Long,
    val clubs: List<ClubInfo>,
    val existing: List<CompetitionInfo> = emptyList(),
    /** Non null quando si sta creando: e' la differenza fra elenco e modulo. */
    val draft: CompetitionDraft? = null,
    val avviso: String? = null,
    val errore: String? = null,
) {
    fun clubName(id: Long): String = clubs.firstOrNull { it.id == id }?.name ?: "Club #$id"
}

/**
 * Le modifiche al regolamento in attesa di essere salvate.
 *
 * [bozza] null significa nessuna modifica pendente, ed e' anche cio' che tiene spento il
 * pulsante di salvataggio: un pulsante attivo che non fa niente insegna a non fidarsi.
 */
data class SettingsEdit(
    val bozza: LeagueConfig? = null,
    val busy: String? = null,
    val errore: String? = null,
) {
    val dirty: Boolean get() = bozza != null
}

/**
 * Le letture della scrivania dell'admin: partecipanti e registro del tick.
 *
 * Vive accanto allo stato della lega e non dentro perche' si legge di rado: infilarla nello
 * snapshot iniziale vorrebbe dire due richieste in piu' a ogni avvio dell'app per dati che
 * quasi nessuno guarda.
 *
 * [tickLetto] distingue "non ho ancora chiesto" da "ho chiesto e il tick non ha mai girato",
 * che sono due cose diversissime: la prima e' un caricamento, la seconda un problema.
 */
data class DeskState(
    val members: List<dev.mfoot.android.data.MemberInfo>? = null,
    val tick: dev.mfoot.android.data.TickInfo? = null,
    val tickLetto: Boolean = false,
    val errore: String? = null,
)

/**
 * Gli scambi: quelli ricevuti, quelli mandati, e quello che si sta componendo.
 *
 * [bozza] non nullo e' la differenza fra "guardo le proposte" e "ne sto scrivendo una", ed
 * e' anche cio' che decide se la schermata mostra un elenco o un modulo.
 */
data class TradesState(
    val trades: List<dev.mfoot.android.data.TradeRow> = emptyList(),
    val letto: Boolean = false,
    val bozza: TradeDraft? = null,
    val busy: String? = null,
    val avviso: String? = null,
    val errore: String? = null,
) {
    fun ricevute(myClubId: Long?) = trades.filter { it.isIncoming(myClubId) && it.isPending }
    fun mandate(myClubId: Long?) = trades.filter { !it.isIncoming(myClubId) && it.isPending }
    fun concluse() = trades.filterNot { it.isPending }
}

/**
 * La proposta che si sta scrivendo.
 *
 * ## Perche' il denaro e' un numero con un segno
 *
 * Positivo: ci metto dei soldi sopra. Negativo: ne chiedo. Con due campi — "offro" e
 * "chiedo" — si potrebbero riempire tutti e due, e una proposta che offre venti milioni e
 * ne chiede trenta non vuol dire niente. E' lo stesso ragionamento del modello in `core`, e
 * vale la pena ripeterlo qui perche' e' l'unico punto in cui qualcuno potrebbe pensare di
 * "migliorare" l'interfaccia con due caselle.
 */
data class TradeDraft(
    val withClub: Long,
    /** Miei, che cedo. */
    val offered: Set<Long> = emptySet(),
    /** Suoi, che chiedo. */
    val wanted: Set<Long> = emptySet(),
    val cash: Int = 0,
    val message: String = "",

    /**
     * Che trattativa e'.
     *
     * Le tre convivono nella stessa bozza invece che in tre schermate perche' il gesto
     * iniziale e' lo stesso — scegli una squadra, poi decidi cosa proporle — e separarle
     * costringerebbe a tornare indietro per accorgersi di aver aperto quella sbagliata.
     */
    val kind: dev.mfoot.android.data.TradeKind = dev.mfoot.android.data.TradeKind.SCAMBIO,

    /** Prestito: durata in giornate, e quanto paga per giornata chi lo prende. */
    val loanMatchDays: Int = 10,
    val loanFee: Int = 0,
    val wagePaidByBorrower: Boolean = true,
    val canPlayAgainstOwner: Boolean = false,

    /** Amichevole: quando, in ora di lega. */
    val friendlyAt: java.time.LocalDateTime? = null,

    /**
     * La proposta a cui questa risponde, se e una controproposta.
     *
     * Cambia dove va a finire: una controproposta passa da `counter_trade`, che chiude
     * anche quella vecchia. Mandarla come proposta nuova lascerebbe due trattative aperte
     * sulle stesse persone, e nessuna delle due saprebbe di essere la risposta all altra.
     */
    val rispondeA: Long? = null,
) {
    val isEmpty: Boolean
        get() = when (kind) {
            dev.mfoot.android.data.TradeKind.SCAMBIO ->
                offered.isEmpty() && wanted.isEmpty() && cash == 0
            // Un prestito ha senso solo con **un** giocatore: due giocatori con una durata
            // sola sarebbero due prestiti travestiti da uno, e alla scadenza tornerebbero
            // insieme anche se nel frattempo uno dei due e' stato girato altrove.
            dev.mfoot.android.data.TradeKind.PRESTITO -> offered.size != 1
            dev.mfoot.android.data.TradeKind.AMICHEVOLE -> friendlyAt == null
        }
}

/**
 * I due gesti dell'admin sulle divisioni: comporre la scala, e chiudere la stagione.
 *
 * Sono rari e pesanti — riscrivono dove gioca ogni club — quindi hanno uno stato loro con
 * il proprio "sto lavorando": mescolarli allo stato della lega vorrebbe dire un avviso di
 * lavorazione che compare in schermate che non c'entrano niente.
 */
data class DivisionsAdmin(
    val busy: String? = null,
    val avviso: String? = null,
    val errore: String? = null,
)

/**
 * Lo spogliatoio: chi si sta ascoltando e come e' andata.
 *
 * [rispostaUltima] resta a schermo dopo aver parlato invece di sparire: la risposta del
 * giocatore e' l'unica cosa che dice se la scelta e' stata giusta, e farla lampeggiare
 * mezzo secondo vorrebbe dire non farla leggere.
 */
data class SpogliatoioState(
    val conPlayerId: Long? = null,
    val rispostaUltima: String? = null,
    val deltaUltimo: Int = 0,
    val avviso: String? = null,
    /** I discorsi aperti e quando si e' parlato l'ultima volta con ognuno. */
    val spogliatoio: dev.mfoot.android.data.Spogliatoio =
        dev.mfoot.android.data.Spogliatoio.VUOTO,
    /** Falso finche' la prima lettura non e' tornata: "nessuno" e "non lo so ancora". */
    val letto: Boolean = false,
    val inCorso: Boolean = false,
)
