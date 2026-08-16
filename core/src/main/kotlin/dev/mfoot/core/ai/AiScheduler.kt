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

    /** Primo risveglio, sparso dentro la finestra di attivita' del club. */
    fun initialWake(
        personality: AiPersonality,
        from: Instant,
        seed: Long = 0L,
    ): Instant {
        val rng = DeterministicRandom(seed * 131L + personality.clubId.value)
        return nextWakeInWindow(personality, from, rng)
    }

    /**
     * Programma il prossimo risveglio.
     *
     * Il contatore delle azioni giornaliere si azzera al cambio di giorno: e' il tetto
     * che impedisce a una singola AI di svegliarsi e fare venti cose di fila.
     */
    fun scheduleNext(state: AiState, now: Instant, seed: Long = 0L): AiState {
        val rng = DeterministicRandom(seed * 977L + state.clubId.value * 31L + now.epochSecond)
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val resetActions = state.actionDay != today

        return state.copy(
            nextWakeAt = nextWakeInWindow(state.personality, now, rng),
            actionsToday = if (resetActions) 0 else state.actionsToday,
            actionDay = today,
        )
    }

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
    fun hasActionsLeft(state: AiState, now: Instant, config: AiConfig): Boolean {
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val used = if (state.actionDay == today) state.actionsToday else 0
        return used < config.maxMarketActionsPerDay
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
     * Se la finestra di oggi e' gia' passata si punta a quella di domani: e' cosi' che
     * un'AI "serale" resta davvero serale invece di svegliarsi alle quattro del mattino.
     */
    private fun nextWakeInWindow(
        personality: AiPersonality,
        from: Instant,
        rng: DeterministicRandom,
    ): Instant {
        val zoned = from.atZone(ZoneOffset.UTC)
        val hour = zoned.hour

        // Distribuzione dei risvegli dentro la finestra, in minuti dall'inizio.
        val windowMinutes = personality.activeHours * 60
        val offsetMinutes = rng.nextInt(windowMinutes.coerceAtLeast(1))

        val baseDay = if (hour >= personality.activeToHour) {
            zoned.toLocalDate().plusDays(1)
        } else {
            zoned.toLocalDate()
        }

        val candidate = baseDay.atStartOfDay(ZoneOffset.UTC)
            .plusHours(personality.activeFromHour.toLong())
            .plusMinutes(offsetMinutes.toLong())
            .toInstant()

        // Non si programma mai un risveglio nel passato.
        return if (candidate.isAfter(from)) {
            candidate
        } else {
            candidate.plusSeconds(24 * 3600)
        }
    }
}
