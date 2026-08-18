package dev.mfoot.core.ai

import dev.mfoot.core.config.AiConfig
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.rng.DeterministicRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Lo stato di risveglio di un club AI.
 *
 * [nextWakeAt] e' il campo piu' importante di tutto il sistema AI: il World Tick non
 * scorre tutte le AI a ogni giro, sveglia solo quelle il cui orario e' arrivato.
 * Un'AI che dorme **non sa nemmeno che l'asta esiste**, quindi non puo' parteciparvi.
 */
data class AiState(
    val personality: AiPersonality,
    val nextWakeAt: Instant,
    val actionsToday: Int = 0,
    val actionDay: LocalDate? = null,
    /** Club a cui non fare offerte fino a una certa giornata, dopo un rifiuto. */
    val refusalCooldowns: Map<ClubId, MatchDay> = emptyMap(),
    /** Giocatori su cui questa AI ha gia' rinunciato dopo essere stata superata. */
    val abandonedTargets: Set<Long> = emptySet(),
) {
    val clubId: ClubId get() = personality.clubId

    fun isDue(now: Instant): Boolean = !now.isBefore(nextWakeAt)

    fun canActOn(club: ClubId, today: MatchDay): Boolean =
        refusalCooldowns[club]?.let { today >= it } ?: true
}

/**
 * Decide **quando** ogni AI si sveglia.
 *
 * ## Il problema che risolve
 *
 * Scritta male, l'AI fa questo:
 * ```
 * 20:14:00   25 AI valutano lo stesso giocatore
 * 20:14:01   25 rilanci
 * 20:14:02   25 notifiche sul telefono dell'utente
 * ```
 *
 * Scritta cosi', fa questo:
 * ```
 * 20:14   "Verdemar" apre un'asta      -> 1 notifica
 * 20:41   "Nordkap" rilancia
 * 22:30   Nordkap rilancia ancora
 * 23:15   Nordkap tocca il suo tetto e molla
 * ```
 *
 * Due AI, non venticinque. Spalmate su ore, non su secondi.
 */
object AiScheduler {

    /** Ogni quanto si sveglia un'AI che deve ancora completare la rosa. */
    const val SPRINT_MINUTI_MIN = 15
    const val SPRINT_MINUTI_MAX = 40

    /**
     * Quante cose puo' fare in un giorno un'AI in sprint.
     *
     * Alto di proposito. Il tetto normale — due azioni — esiste per non sommergere di
     * notifiche chi gioca, ed e' giusto **a campionato in corso**. Durante il mercato
     * iniziale il problema e' l'opposto: con due azioni al giorno e un solo risveglio,
     * arrivare a diciotto giocatori richiede settimane reali, e la lega resta ferma
     * aspettando che i computer finiscano di fare la spesa.
     */
    const val SPRINT_AZIONI_AL_GIORNO = 12

    /**
     * Sta correndo per completare la rosa?
     *
     * La discriminante e' la **dimensione della rosa** e non lo stato della lega: e' la
     * stessa regola che governa gia' il tetto di spesa in [AiManager], e tenerne una sola
     * evita che le due si separino al primo ritocco. Un'AI in sprint compra fino ad avere
     * una rosa legale e si ferma da sola.
     */
    fun isSprinting(squadSize: Int, minSquadSize: Int): Boolean = squadSize < minSquadSize

    /** Primo risveglio, sparso dentro la finestra di attivita' del club. */
    fun initialWake(
        personality: AiPersonality,
        from: Instant,
        seed: Long = 0L,
        sprint: Boolean = false,
    ): Instant {
        val rng = DeterministicRandom(seed * 131L + personality.clubId.value)
        return if (sprint) sprintWake(from, rng) else nextWakeInWindow(personality, from, rng)
    }

    /**
     * Programma il prossimo risveglio.
     *
     * Il contatore delle azioni giornaliere si azzera al cambio di giorno: e' il tetto
     * che impedisce a una singola AI di svegliarsi e fare venti cose di fila.
     */
    fun scheduleNext(
        state: AiState,
        now: Instant,
        seed: Long = 0L,
        sprint: Boolean = false,
    ): AiState {
        val rng = DeterministicRandom(seed * 977L + state.clubId.value * 31L + now.epochSecond)
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val resetActions = state.actionDay != today

        return state.copy(
            nextWakeAt = if (sprint) {
                sprintWake(now, rng)
            } else {
                nextWakeInWindow(state.personality, now, rng)
            },
            actionsToday = if (resetActions) 0 else state.actionsToday,
            actionDay = today,
        )
    }

    /**
     * Il risveglio di chi ha una rosa da completare.
     *
     * Fuori dalla finestra oraria del club: un'AI "serale" con nove caselle vuote non ha
     * il lusso di aspettare stasera, perche' finche' non ha una rosa legale il campionato
     * non parte per nessuno.
     */
    private fun sprintWake(from: Instant, rng: DeterministicRandom): Instant =
        from.plusSeconds(60L * rng.nextIntInclusive(SPRINT_MINUTI_MIN, SPRINT_MINUTI_MAX))

    /** Registra un'azione compiuta, per il tetto giornaliero. */
    fun recordAction(state: AiState, now: Instant): AiState {
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val sameDay = state.actionDay == today
        return state.copy(
            actionsToday = if (sameDay) state.actionsToday + 1 else 1,
            actionDay = today,
        )
    }

    /**
     * Ha ancora azioni disponibili oggi?
     *
     * Il tetto vale per club: una singola AI non puo' monopolizzare il mercato, e
     * l'insieme delle AI non puo' produrre piu' movimento di quanto un umano riesca
     * a seguire.
     */
    fun hasActionsLeft(
        state: AiState,
        now: Instant,
        config: AiConfig,
        sprint: Boolean = false,
    ): Boolean {
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val used = if (state.actionDay == today) state.actionsToday else 0
        val tetto = if (sprint) {
            maxOf(SPRINT_AZIONI_AL_GIORNO, config.maxMarketActionsPerDay)
        } else {
            config.maxMarketActionsPerDay
        }
        return used < tetto
    }

    /** Le AI da svegliare adesso. Il World Tick chiama solo questa. */
    fun due(states: List<AiState>, now: Instant): List<AiState> =
        states.filter { it.isDue(now) }

    /**
     * Quanto aspetta prima di rispondere a un rilancio.
     *
     * Un'AI che risponde in cinquanta millisecondi e' insopportabile: fa capire che si
     * sta giocando contro un programma e toglie ogni tensione all'asta. I club pazienti
     * aspettano di piu'.
     */
    fun rebidDelaySeconds(
        personality: AiPersonality,
        config: AiConfig,
        seed: Long = 0L,
    ): Long {
        val rng = DeterministicRandom(seed * 7727L + personality.clubId.value)
        val min = config.minRebidDelayMinutes * 60.0
        val max = config.maxRebidDelayMinutes * 60.0
        // I pazienti stanno nella meta' alta dell'intervallo, gli impulsivi in quella bassa.
        val skewed = min + (max - min) * (personality.patience * 0.6 + rng.nextDouble() * 0.4)
        return skewed.toLong().coerceAtLeast(1L)
    }

    /**
     * Il prossimo momento di risveglio dentro la finestra di attivita' del club.
     *
     * ## Perche' i risvegli sono piu' d'uno al giorno
     *
     * La versione precedente ne calcolava **uno solo**: un istante a caso dentro la
     * finestra di oggi. Dopo aver agito, il candidato successivo cadeva prima di adesso e
     * veniva spinto a domani. Risultato: ogni AI si svegliava una volta al giorno, faceva
     * al massimo due cose e tornava a dormire. Per arrivare a diciotto giocatori servivano
     * settimane reali, e nessuno capiva perche' il mercato non si muovesse.
     *
     * `personality.checksPerDay` esisteva gia' e non lo leggeva nessuno. Adesso la finestra
     * si divide in altrettante fasce e il risveglio cade una volta per fascia, con uno
     * scarto casuale dentro: un'AI che "guarda il mercato tre volte al giorno" lo fa
     * davvero, e non a orari prevedibili.
     *
     * Se la finestra di oggi e' finita si passa a quella di domani: e' cosi' che un'AI
     * serale resta serale invece di svegliarsi alle quattro del mattino.
     */
    private fun nextWakeInWindow(
        personality: AiPersonality,
        from: Instant,
        rng: DeterministicRandom,
    ): Instant {
        val zoned = from.atZone(ZoneOffset.UTC)
        val controlli = personality.checksPerDay.coerceIn(1, 8)
        val minutiFinestra = (personality.activeHours * 60).coerceAtLeast(controlli)
        val passo = minutiFinestra / controlli

        // Oggi e domani bastano: la finestra piu' corta e' di due ore, quindi il prossimo
        // risveglio non puo' mai cadere oltre domani sera.
        for (giorno in 0L..1L) {
            val base = zoned.toLocalDate().plusDays(giorno)
                .atStartOfDay(ZoneOffset.UTC)
                .plusHours(personality.activeFromHour.toLong())

            for (fascia in 0 until controlli) {
                val offset = fascia.toLong() * passo + rng.nextInt(passo.coerceAtLeast(1))
                val candidato = base.plusMinutes(offset).toInstant()
                if (candidato.isAfter(from)) return candidato
            }
        }

        // Non si arriva mai qui, ma un risveglio nel passato bloccherebbe l'AI per sempre.
        return from.plusSeconds(3600)
    }
}
