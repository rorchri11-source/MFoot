package dev.mfoot.core.ai

import dev.mfoot.core.config.AiConfig
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.Reparto
import dev.mfoot.core.rng.DeterministicRandom

/** Le fissazioni di un club AI: servono a renderlo riconoscibile, non piu' intelligente. */
enum class AiObsession(val label: String) {
    PORTIERE_FORTE("Vuole sempre un portiere di livello"),
    TALENTI("Ama i giovani di prospettiva"),
    ESPERIENZA("Si fida solo dei giocatori già fatti"),
    ATTACCO("Costruisce dall'attacco"),
    DIFESA("Prima non prenderle"),
    CONNAZIONALI("Predilige una nazionalità"),
}

/**
 * Il carattere di un club AI.
 *
 * ## Perche' un carattere e non un'intelligenza
 *
 * L'obiettivo non e' un'AI che gioca bene: e' un'AI che sembra **una persona diversa
 * dalle altre**. Venticinque club con lo stesso cervello si comportano come un unico
 * organismo, e il risultato e' lo sciame che rende insopportabile giocare.
 *
 * Con una personalita' generata, i club diventano riconoscibili — "quello li' si compra
 * sempre i giovani" — e i loro interessi si spargono naturalmente su giocatori diversi
 * invece di accalcarsi tutti sullo stesso.
 *
 * ## Gli orari sono la parte piu' importante
 *
 * [activeFromHour] e [activeToHour] fanno si' che ogni AI "viva" in un momento diverso
 * della giornata. Un'AI che dorme non sa nemmeno che l'asta esiste. E' questo, piu' di
 * ogni altra cosa, a trasformare uno sciame in venticinque individui.
 */
data class AiPersonality(
    val clubId: ClubId,
    /** Quanto si spinge sui prezzi. */
    val marketAggression: Double,
    /** 0 = solo giocatori pronti, 1 = solo prospetti. */
    val youthPreference: Double,
    /** Quanto tiene da parte invece di spendere tutto. */
    val budgetDiscipline: Double,
    /** Quanto aspetta prima di rispondere a un rilancio. */
    val patience: Double,
    val obsessions: Set<AiObsession>,
    val preferredNationality: String? = null,
    /** Fascia oraria in cui questo club "vive". */
    val activeFromHour: Int,
    val activeToHour: Int,
    /** Quante volte al giorno si sveglia a guardare il mercato. */
    val checksPerDay: Int,
) {
    init {
        require(activeFromHour in 0..23 && activeToHour in 0..23) { "orari non validi" }
        require(activeToHour > activeFromHour) { "la finestra di attività deve avere durata positiva" }
    }

    val activeHours: Int get() = activeToHour - activeFromHour

    fun isAwakeAt(hour: Int): Boolean = hour in activeFromHour until activeToHour

    /** Quanto interessa un giocatore di quel ruolo, per via delle fissazioni. */
    fun obsessionBonusFor(position: Position): Double {
        var bonus = 0.0
        if (AiObsession.PORTIERE_FORTE in obsessions && position.isGoalkeeper) bonus += 0.35
        if (AiObsession.ATTACCO in obsessions && position.reparto == Reparto.ATTACCO) bonus += 0.25
        if (AiObsession.DIFESA in obsessions && position.reparto == Reparto.DIFESA) bonus += 0.25
        return bonus
    }

    fun describe(): String = buildString {
        append("Attivo ${activeFromHour}-${activeToHour}, ")
        append("controlla $checksPerDay volte al giorno. ")
        if (obsessions.isNotEmpty()) append(obsessions.joinToString("; ") { it.label })
    }
}

object AiPersonalityGenerator {

    /**
     * Genera il carattere di un club AI.
     *
     * Deterministico dal seed della lega piu' l'id del club: rigenerando la stessa lega
     * si ottengono gli stessi avversari, il che rende i test riproducibili e permette
     * di rigiocare un campionato identico.
     */
    fun generate(clubId: ClubId, leagueSeed: Long, config: AiConfig): AiPersonality {
        val rng = DeterministicRandom(leagueSeed * 31L + clubId.value * 7919L)

        // Le finestre di attivita' sono sparse sull'intera giornata di proposito: se
        // fossero tutte serali, alle 21 si sveglierebbero venticinque club insieme e
        // avremmo ricreato lo sciame che stiamo cercando di evitare.
        val from = rng.nextIntInclusive(7, 20)
        val duration = rng.nextIntInclusive(2, 5)

        val obsessions = buildSet {
            if (rng.chance(0.45)) add(rng.pick(AiObsession.entries))
            if (rng.chance(0.20)) add(rng.pick(AiObsession.entries))
        }

        return AiPersonality(
            clubId = clubId,
            marketAggression = rng.nextDouble(0.25, 1.0) * config.difficultyMultiplier,
            youthPreference = rng.nextDouble(0.0, 1.0),
            budgetDiscipline = rng.nextDouble(0.3, 1.0),
            patience = rng.nextDouble(0.0, 1.0),
            obsessions = obsessions,
            activeFromHour = from,
            activeToHour = minOf(from + duration, 23),
            checksPerDay = rng.nextIntInclusive(
                config.minWakeupsPerDay,
                config.maxWakeupsPerDay.coerceAtLeast(config.minWakeupsPerDay),
            ),
        )
    }
}
