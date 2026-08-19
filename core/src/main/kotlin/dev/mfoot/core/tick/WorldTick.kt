package dev.mfoot.core.tick

import dev.mfoot.core.ai.AiState
import dev.mfoot.core.calendar.Fixture
import dev.mfoot.core.config.IncomeCadence
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.market.Auction
import dev.mfoot.core.market.Negotiation
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Contract
import dev.mfoot.core.model.Loan
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.PlayerId
import java.time.Instant
import java.time.ZoneOffset

/**
 * Una cosa che deve succedere nel mondo.
 *
 * Il tick **non applica niente**: produce un piano. Chi lo esegue (il programma che
 * parla con il database) applica gli effetti in una sola transazione. Questa separazione
 * e' quello che rende il tick testabile senza database e, soprattutto, quello che rende
 * possibile applicare tutto o niente.
 */
sealed interface TickEffect {

    /** Quando questa cosa sarebbe dovuta succedere. Determina l'ordine di esecuzione. */
    val dueAt: Instant

    data class SimulaPartita(val fixture: Fixture, override val dueAt: Instant) : TickEffect

    data class ChiudiAsta(val auctionId: Long, override val dueAt: Instant) : TickEffect

    data class ScadiTrattativa(val negotiationId: Long, override val dueAt: Instant) : TickEffect

    data class ScadiContratto(
        val playerId: PlayerId,
        val clubId: ClubId,
        val matchDay: MatchDay,
        override val dueAt: Instant,
    ) : TickEffect

    data class RestituisciPrestito(val loan: Loan, override val dueAt: Instant) : TickEffect

    data class SvegliaAi(val clubId: ClubId, override val dueAt: Instant) : TickEffect

    data class DistribuisciCrediti(
        val matchDay: MatchDay,
        val amount: Int,
        override val dueAt: Instant,
    ) : TickEffect

    data class PagaStipendi(val matchDay: MatchDay, override val dueAt: Instant) : TickEffect

    data class RecuperaStamina(val matchDay: MatchDay, override val dueAt: Instant) : TickEffect

    data class VerificaPromesse(val matchDay: MatchDay, override val dueAt: Instant) : TickEffect

    data class InviaRiepilogo(override val dueAt: Instant) : TickEffect
}

/**
 * Lo stato del mondo che serve al tick per decidere. Nient'altro.
 *
 * Volutamente minimale: il tick non ha bisogno delle rose complete ne' degli attributi
 * dei giocatori, solo di sapere *cosa e' in scadenza*. Il carico di lavoro sul database
 * resta piccolo anche con venti club.
 */
data class TickInput(
    val now: Instant,
    /**
     * Fino a quando era stato elaborato l'ultimo giro.
     *
     * Null al primissimo avvio. Se GitHub ritarda o salta un'esecuzione, questo valore
     * resta indietro e il tick recupera automaticamente tutto l'intervallo perso.
     */
    val lastProcessedAt: Instant?,
    val today: MatchDay,
    val config: LeagueConfig,
    /** Partite programmate e non ancora giocate. */
    val pendingFixtures: List<Fixture> = emptyList(),
    val openAuctions: List<Auction> = emptyList(),
    val openNegotiations: List<Negotiation> = emptyList(),
    val activeContracts: List<Contract> = emptyList(),
    val activeLoans: List<Loan> = emptyList(),
    val aiStates: List<AiState> = emptyList(),
    /** Giornate gia' liquidate: evita di pagare due volte se il tick rigira. */
    val settledMatchDays: Set<Int> = emptySet(),
    val lastDigestAt: Instant? = null,
)

data class TickOutput(
    val effects: List<TickEffect>,
    /** Da salvare come nuovo `lastProcessedAt`. */
    val processedUpTo: Instant,
    val notes: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = effects.isEmpty()

    fun <T : TickEffect> of(type: Class<T>): List<T> =
        effects.filterIsInstance(type)

    inline fun <reified T : TickEffect> count(): Int = effects.count { it is T }
}

/**
 * Il battito del mondo.
 *
 * ## Cosa risolve
 *
 * Il requisito piu' forte del progetto: *"il gioco deve girare anche quando nessuno lo
 * sta usando, anche a telefoni spenti"*. Se tutti spengono per tre giorni, al ritorno
 * devono trovare le giornate giocate, le aste concluse, i contratti scaduti e le AI che
 * si sono mosse.
 *
 * ## Recupero, non tempo reale
 *
 * Il tick non chiede "cosa succede adesso" ma **"cosa sarebbe dovuto succedere fra
 * l'ultimo giro e adesso"**. E' una differenza sostanziale: significa che puo' girare
 * ogni minuto o ogni mezz'ora, essere ritardato o saltato del tutto, e il risultato non
 * cambia. Nell'architettura a costo zero, dove il cron di GitHub non e' puntuale, e'
 * proprio questa proprieta' a tenere in piedi tutto.
 *
 * ## Idempotenza
 *
 * Un'asta non deve poter essere assegnata due volte perche' il processo e' ripartito a
 * meta'. Ogni effetto e' calcolato da uno stato che il chiamante aggiorna nella stessa
 * transazione: se la transazione fallisce, `lastProcessedAt` non avanza e il giro
 * successivo rifa' esattamente le stesse cose.
 */
object WorldTick {

    /** Al primo avvio non si recupera all'infinito: si guarda solo l'ultima ora. */
    private const val FIRST_RUN_LOOKBACK_SECONDS = 3600L

    /**
     * Tetto di sicurezza sul recupero. Se il tick e' fermo da giorni, si elabora al
     * massimo una settimana per volta, cosi' un singolo giro non prova a simulare
     * trecento partite e va in timeout.
     */
    private const val MAX_CATCHUP_SECONDS = 7 * 24 * 3600L

    /**
     * Calcola cosa deve succedere. Non applica niente: restituisce il piano.
     */
    fun run(input: TickInput): TickOutput {
        val from = windowStart(input)
        val to = input.now
        val notes = mutableListOf<String>()

        if (!to.isAfter(from)) {
            return TickOutput(emptyList(), input.lastProcessedAt ?: to, listOf("Nulla da elaborare."))
        }

        // Il divario va misurato sull'ultimo tick **reale**, non sulla finestra gia'
        // troncata: quella per costruzione non supera mai il tetto, quindi non
        // segnalerebbe mai niente.
        val realGap = input.lastProcessedAt?.let { to.epochSecond - it.epochSecond } ?: 0L
        if (realGap > MAX_CATCHUP_SECONDS) {
            notes += "Recupero limitato: il tick era fermo da ${realGap / 3600} ore, " +
                "elaborata solo l'ultima settimana."
        }

        val effects = buildList {
            addAll(matchesDue(input, from, to))
            addAll(auctionsDue(input, from, to))
            addAll(negotiationsDue(input, from, to))
            addAll(loansDue(input))
            addAll(contractsDue(input))
            addAll(aiWakeups(input, from, to))
            addAll(matchDayUpkeep(input))
            addAll(digest(input, from, to))
        }

        // L'ordine cronologico non e' cosmetico: un'asta che si chiude prima di una
        // partita puo' cambiare chi scende in campo in quella partita.
        return TickOutput(
            effects = effects.sortedBy { it.dueAt },
            processedUpTo = to,
            notes = notes,
        )
    }

    /**
     * L'inizio della finestra da elaborare.
     *
     * Al primissimo avvio si guarda solo indietro di un'ora: senza questo limite, la
     * prima esecuzione proverebbe a recuperare dall'inizio dei tempi.
     */
    private fun windowStart(input: TickInput): Instant {
        val last = input.lastProcessedAt
            ?: return input.now.minusSeconds(FIRST_RUN_LOOKBACK_SECONDS)
        val cap = input.now.minusSeconds(MAX_CATCHUP_SECONDS)
        return if (last.isBefore(cap)) cap else last
    }

    /**
     * Le partite il cui orario di inizio e' caduto **dentro** la finestra.
     *
     * ## Perche' qui non vale il "in ritardo conta lo stesso" delle aste
     *
     * [scaduto] esiste perche' un'asta scaduta fuori finestra deve chiudersi comunque:
     * quello che conta e' che chiuda, non quando ci si accorge. Applicato alle partite,
     * pero', quel «comunque» diventa **rigioca tutto il passato**, e cancella le due
     * protezioni che il tick ha di proposito:
     *
     * - al primissimo avvio si guarda solo l'ultima ora, per non recuperare dall'inizio
     *   dei tempi. Con `scaduto`, la prima esecuzione di una lega appena creata simulava
     *   ogni partita gia' in calendario, tutte insieme;
     * - il recupero e' limitato a una settimana per non andare in timeout. Con `scaduto`,
     *   il tetto lo si scriveva nelle note e poi si simulava lo stesso un mese di partite.
     *
     * E soprattutto: una partita gia' pianificata al giro precedente veniva ripianificata a
     * ogni giro successivo, perche' il suo calcio d'inizio resta per sempre prima della
     * finestra. Restava fuori dai guai solo finche' il chiamante toglieva la partita
     * dall'elenco delle pendenti nella stessa transazione — cioe' l'idempotenza dipendeva
     * da chi chiama invece che dalla regola. Tre test lo dicevano gia', e fallivano.
     *
     * Il difetto e' arrivato con la correzione delle aste che non si chiudevano: giusta li',
     * copiata di qui.
     */
    private fun matchesDue(input: TickInput, from: Instant, to: Instant): List<TickEffect> =
        input.pendingFixtures
            .filter { it.kickoff != null }
            .filter { inWindow(it.kickoff!!.toInstant(ZoneOffset.UTC), from, to) }
            .map { TickEffect.SimulaPartita(it, it.kickoff!!.toInstant(ZoneOffset.UTC)) }

    private fun auctionsDue(input: TickInput, from: Instant, to: Instant): List<TickEffect> =
        input.openAuctions
            .filter { it.isOpen && scaduto(it.endsAt, from, to) }
            .map { TickEffect.ChiudiAsta(it.id, it.endsAt) }

    private fun negotiationsDue(input: TickInput, from: Instant, to: Instant): List<TickEffect> =
        input.openNegotiations
            .filter { it.isOpen && scaduto(it.expiresAt, from, to) }
            .map { TickEffect.ScadiTrattativa(it.id, it.expiresAt) }

    /**
     * I prestiti scaduti si chiudono per **giornata**, non per orologio: il giocatore
     * torna al proprietario quando la giornata di scadenza e' arrivata.
     */
    private fun loansDue(input: TickInput): List<TickEffect> =
        input.activeLoans
            .filter { it.isExpired(input.today) }
            .map { TickEffect.RestituisciPrestito(it, input.now) }

    private fun contractsDue(input: TickInput): List<TickEffect> =
        input.activeContracts
            .filter { it.isExpired(input.today) }
            .map { TickEffect.ScadiContratto(it.playerId, it.clubId, input.today, input.now) }

    /**
     * Le AI da svegliare.
     *
     * Il cuore dell'anti-sciame: non si scorrono tutte, solo quelle il cui orario e'
     * arrivato. Un'AI che dorme non sa nemmeno che l'asta esiste.
     */
    private fun aiWakeups(input: TickInput, from: Instant, to: Instant): List<TickEffect> =
        input.aiStates
            .filter { inWindow(it.nextWakeAt, from, to) || it.nextWakeAt.isBefore(from) }
            .filter { it.isDue(to) }
            .map { TickEffect.SvegliaAi(it.clubId, it.nextWakeAt) }

    /**
     * Le operazioni di fine giornata: crediti, stipendi, recupero stamina, promesse.
     *
     * [TickInput.settledMatchDays] evita di pagare due volte la stessa giornata se il
     * tick rigira sulla stessa finestra — che e' esattamente quello che succede se una
     * transazione fallisce a meta'.
     */
    private fun matchDayUpkeep(input: TickInput): List<TickEffect> {
        if (input.today.value in input.settledMatchDays) return emptyList()
        if (input.today.value <= 0) return emptyList()

        val economy = input.config.economy
        return buildList {
            add(TickEffect.RecuperaStamina(input.today, input.now))
            add(TickEffect.VerificaPromesse(input.today, input.now))

            if (economy.wagesEnabled) {
                add(TickEffect.PagaStipendi(input.today, input.now))
            }
            if (shouldPayIncome(input)) {
                add(TickEffect.DistribuisciCrediti(input.today, economy.recurringIncome, input.now))
            }
        }
    }

    /**
     * La cadenza delle entrate la decide l'admin.
     *
     * Settimana e mese sono **di calendario reale**, non giornate di gioco: e' cosi' che
     * la gente pensa a uno stipendio, e tradurlo in giornate farebbe arrivare l'accredito
     * quando capita invece che il lunedi'. Sono anche le uniche due misure del sistema che
     * guardano l'orologio invece del contatore delle giornate, ed e' voluto.
     *
     * Il confine si riconosce dal giorno: si paga il primo tick del lunedi', o il primo
     * tick del primo del mese. Chi non c'era trova comunque l'accredito, perche' il tick
     * recupera la finestra perduta.
     */
    private fun shouldPayIncome(input: TickInput): Boolean {
        val economy = input.config.economy
        if (economy.recurringIncome <= 0) return false

        val from = windowStart(input).atZone(ZoneOffset.UTC).toLocalDate()
        val to = input.now.atZone(ZoneOffset.UTC).toLocalDate()

        return when (economy.incomeCadence) {
            IncomeCadence.PER_GIORNATA -> true
            IncomeCadence.PER_SETTIMANA -> crossedWeek(from, to)
            IncomeCadence.PER_MESE -> from.month != to.month || from.year != to.year
            IncomeCadence.FINE_COMPETIZIONE -> false
            IncomeCadence.MAI -> false
        }
    }

    /** Si e' passato un lunedi' fra l'ultimo giro e adesso? */
    private fun crossedWeek(from: java.time.LocalDate, to: java.time.LocalDate): Boolean {
        if (from == to) return false
        var day = from.plusDays(1)
        while (!day.isAfter(to)) {
            if (day.dayOfWeek == java.time.DayOfWeek.MONDAY) return true
            day = day.plusDays(1)
        }
        return false
    }

    /**
     * Il riepilogo giornaliero.
     *
     * Un ping per ogni evento in una lega da venticinque club porta alla disinstallazione
     * in tre giorni. Tutto quello che non richiede una decisione immediata finisce qui,
     * in un solo messaggio.
     */
    private fun digest(input: TickInput, from: Instant, to: Instant): List<TickEffect> {
        if (!input.config.notifications.immediateEnabled) return emptyList()

        val digestTime = input.config.notifications.dailyDigestHour
        val todayAt = to.atZone(ZoneOffset.UTC).toLocalDate()
            .atTime(digestTime)
            .toInstant(ZoneOffset.UTC)

        val alreadySent = input.lastDigestAt?.let { !it.isBefore(todayAt) } ?: false
        return if (!alreadySent && inWindow(todayAt, from, to)) {
            listOf(TickEffect.InviaRiepilogo(todayAt))
        } else {
            emptyList()
        }
    }

    /** Finestra aperta a sinistra e chiusa a destra: nessun momento viene contato due volte. */
    private fun inWindow(moment: Instant, from: Instant, to: Instant): Boolean =
        moment.isAfter(from) && !moment.isAfter(to)

    /**
     * E' ora, **o era ora e non e' successo**.
     *
     * ## Il difetto che questa riga chiude
     *
     * [inWindow] chiede che il momento cada dentro `(ultimo giro, adesso]`. Per un'asta
     * scaduta **prima** dell'ultimo giro la risposta e' no, e siccome `last_processed_at`
     * avanza comunque a fine giro, quella no e' definitiva: **l'asta resta aperta per
     * sempre**.
     *
     * Basta una volta perche' succeda — un giro che fallisce dopo aver pianificato, una
     * riga inserita con una scadenza gia' passata, un orologio del database leggermente
     * avanti rispetto a quello del server — e da li' in poi quell'asta non si chiude piu'.
     * Si accumulano: sessantasette aperte insieme, e ognuna sembra durare un giorno.
     *
     * Il risveglio delle AI aveva gia' questa protezione, scritta come
     * `|| it.nextWakeAt.isBefore(from)`. Qualcuno aveva incontrato lo stesso problema li' e
     * lo aveva corretto solo li'.
     *
     * ## Perche' non basta guardare "prima di adesso"
     *
     * Perche' il tick non chiede "cosa succede adesso" ma "cosa sarebbe dovuto succedere":
     * `to` puo' essere nel passato quando si recupera un intervallo perso, e chiudere
     * un'asta che scade dopo quel momento vorrebbe dire aggiudicarla prima del tempo.
     */
    private fun scaduto(moment: Instant, from: Instant, to: Instant): Boolean =
        inWindow(moment, from, to) || moment.isBefore(from)
}
