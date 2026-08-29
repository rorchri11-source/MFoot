package dev.mfoot.core.match

import dev.mfoot.core.config.EngineConfig
import dev.mfoot.core.model.BandWeights
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Zone
import dev.mfoot.core.model.bigMatchBonus
import dev.mfoot.core.rng.MathX

/**
 * La forza di una squadra in ciascuna delle nove zone.
 *
 * E' l'unica cosa che il motore di simulazione deve sapere per far girare la partita:
 * i singoli giocatori tornano in gioco solo per attribuire gli eventi.
 */
data class ZoneStrength(
    val ratings: Map<Zone, Double>,
    /** Quanti uomini gravitano su ogni zona: serve a pesare chi tocca la palla. */
    val presence: Map<Zone, Double>,
    /** Peso di ciascun giocatore su ciascuna zona, per estrarre chi tocca il pallone. */
    val contributions: Map<Zone, List<PlayerWeight>>,
    /** Tutto quello che non dipende dal singolo: campo, allenatore, assetto, inerzia. */
    val contesto: Map<Zone, ZoneContext> = emptyMap(),
) {
    fun rating(zone: Zone): Double = ratings[zone] ?: EMPTY_ZONE_RATING

    fun contesto(zone: Zone): ZoneContext = contesto[zone] ?: ZoneContext.NEUTRO

    companion object {
        /** Quanto vale una zona in cui non c'e' nessuno. */
        const val EMPTY_ZONE_RATING = 22.0
    }
}

data class PlayerWeight(val playerId: PlayerId, val weight: Double)

/**
 * Quello che vale per tutta la zona, chiunque ci sia dentro.
 *
 * Esiste per un motivo solo: il livello dei duelli deve poter applicare a un **singolo
 * giocatore** gli stessi identici modificatori che il rating di zona applica alla media —
 * vantaggio del campo, allenatore, assetto, pressing, inerzia, quanti uomini ci sono. Se
 * fossero due formule diverse, spostare il vantaggio del campo aggiusterebbe una delle due
 * e romperebbe l'altra, e nessuno se ne accorgerebbe fino alla prossima taratura.
 *
 * `rating = qualita' * fattore + bonus` e' esattamente la formula di prima, riscritta in
 * due pezzi riusabili invece che in una riga sola.
 */
data class ZoneContext(val fattore: Double, val bonus: Double) {
    /** Quanto vale li' un giocatore che vale [qualita]. */
    fun applica(qualita: Double): Double = qualita * fattore + bonus

    companion object {
        val NEUTRO = ZoneContext(fattore = 1.0, bonus = 0.0)
    }
}

/**
 * Costruisce i rating di zona a partire dalla formazione schierata.
 *
 * ## Come si compone una zona
 *
 * Ogni giocatore contribuisce a una o piu' zone secondo il ruolo in cui e' schierato,
 * e lo fa con gli attributi che contano **a quell'altezza di campo** — non con il suo
 * overall. Un difensore centrale spinto a centrocampo porta li' la sua tecnica e il suo
 * passaggio, che sono mediocri: e' il motivo per cui un regista arretrato regge e un
 * centravanti no.
 *
 * ## Perche' conta anche quanti sono
 *
 * Il rating finale combina **qualita'** (media pesata di chi c'e') e **presenza**
 * (quanti uomini gravitano li'). Senza il secondo fattore, giocare a tre dietro non
 * costerebbe niente; con il solo secondo fattore, ammassare gente varrebbe piu' che
 * avere buoni giocatori. La presenza pesa, ma poco: e' un correttivo, non il driver.
 */
object ZoneRatings {

    /** Presenza media di una zona: dieci giocatori di movimento su nove zone. */
    private const val REFERENCE_PRESENCE = 10.0 / 9.0

    /** Quota del rating che dipende dalla presenza invece che dalla qualita'. */
    private const val PRESENCE_INFLUENCE = 0.20

    /** Oltre questo, ammassare uomini smette di dare vantaggio. */
    private const val MAX_PRESENCE_FACTOR = 1.30

    /** Sotto questa presenza la zona e' considerata sguarnita. */
    private const val DESERTED_THRESHOLD = 0.12

    fun compute(
        lineup: Lineup,
        tactics: Tactics,
        coachStars: Int,
        isHome: Boolean,
        importance: MatchImportance,
        engine: EngineConfig,
        momentum: Double = 0.0,
    ): ZoneStrength {
        val weighted = mutableMapOf<Zone, Double>()
        val presence = mutableMapOf<Zone, Double>()
        val contributions = mutableMapOf<Zone, MutableList<PlayerWeight>>()

        for (slot in lineup.outfield) {
            for ((zone, weight) in slot.position.zoneWeights) {
                val individual = individualRating(slot, zone, importance, engine)
                weighted[zone] = (weighted[zone] ?: 0.0) + weight * individual
                presence[zone] = (presence[zone] ?: 0.0) + weight
                contributions.getOrPut(zone) { mutableListOf() }
                    .add(PlayerWeight(slot.player.id, weight))
            }
        }

        val coachBonus = coachBonus(coachStars)
        val homeBonus = if (isHome) engine.homeAdvantage else 0.0

        val contesto = Zone.entries.associateWith { zone ->
            val bodies = presence[zone] ?: 0.0
            val tacticalFactor = tactics.stance.factorFor(zone.band) *
                tactics.width.factorFor(zone.lane)
            val pressingBonus = if (zone.band == dev.mfoot.core.model.Band.MID) {
                tactics.pressing.midfieldBonus
            } else {
                0.0
            }

            ZoneContext(
                fattore = presenceFactor(bodies) * tacticalFactor,
                bonus = homeBonus + coachBonus + pressingBonus + momentum,
            )
        }

        val ratings = Zone.entries.associateWith { zone ->
            val bodies = presence[zone] ?: 0.0
            if (bodies < DESERTED_THRESHOLD) {
                ZoneStrength.EMPTY_ZONE_RATING
            } else {
                val quality = (weighted[zone] ?: 0.0) / bodies
                contesto.getValue(zone).applica(quality).coerceAtLeast(1.0)
            }
        }

        return ZoneStrength(
            ratings = ratings,
            presence = Zone.entries.associateWith { presence[it] ?: 0.0 },
            contributions = contributions.mapValues { it.value.toList() },
            contesto = contesto,
        )
    }

    /**
     * Quanto rende questo giocatore in questa zona, scala 1-99.
     *
     * Tutti i modificatori sono **additivi** sulla stessa scala degli attributi, cosi'
     * restano leggibili: "-8 per la stanchezza" si capisce, "x0,91" no.
     */
    private fun individualRating(
        slot: LineupSlot,
        zone: Zone,
        importance: MatchImportance,
        engine: EngineConfig,
    ): Double = (
        BandWeights.rate(slot.player.attributes, zone.band) * slot.fitness +
            condizione(slot.player, importance, engine)
        ).coerceIn(1.0, 99.0)

    /**
     * In che condizioni si presenta oggi: stanchezza, morale, forma, e il tratto di chi
     * cresce nelle partite che contano.
     *
     * Additiva sulla stessa scala degli attributi, cosi' resta leggibile — «-8 per la
     * stanchezza» si capisce, «x0,91» no.
     *
     * Estratta perche' la usano in due: il rating di zona, che la applica alla media dei
     * reparti, e il livello dei duelli, che la applica al singolo. Se fossero due formule
     * separate, il giorno che si ritocca il peso della forma se ne aggiusterebbe una sola.
     */
    fun condizione(
        player: Player,
        importance: MatchImportance,
        engine: EngineConfig,
    ): Double {
        val staminaPenalty = staminaPenalty(player, engine)
        val moralePenalty = (player.morale - 50) * engine.moraleWeight
        val formBonus = player.form * engine.formWeight
        val bigMatch = if (importance.isBig) player.traits.bigMatchBonus() else 0.0

        return -staminaPenalty + moralePenalty + formBonus + bigMatch
    }

    /**
     * La stanchezza non pesa finche' si sta sopra la soglia di comfort, poi morde
     * sempre di piu'. E' quello che rende impossibile schierare gli stessi undici due
     * volte al giorno, e quindi quello che rende necessarie la rosa profonda e la
     * Primavera.
     */
    fun staminaPenalty(player: Player, engine: EngineConfig): Double {
        val threshold = engine.staminaComfortThreshold
        if (player.stamina >= threshold) return 0.0
        val deficit = (threshold - player.stamina).toDouble() / threshold
        return deficit * deficit * engine.maxStaminaPenalty
    }

    /** Da 1 stella (-2,5) a 5 stelle (+2,5), lineare. */
    fun coachBonus(stars: Int): Double =
        MathX.remap(stars.coerceIn(1, 5).toDouble(), 1.0, 5.0, -2.5, 2.5)

    private fun presenceFactor(bodies: Double): Double {
        val ratio = bodies / REFERENCE_PRESENCE
        return ((1.0 - PRESENCE_INFLUENCE) + PRESENCE_INFLUENCE * ratio)
            .coerceIn(0.55, MAX_PRESENCE_FACTOR)
    }

    /**
     * Overall complessivo di una squadra schierata, media dei nove rating di zona.
     * Serve per le classifiche, le valutazioni dell'AI e i test di bilanciamento.
     */
    fun teamOverall(strength: ZoneStrength): Double =
        Zone.entries.map { strength.rating(it) }.average()
}
