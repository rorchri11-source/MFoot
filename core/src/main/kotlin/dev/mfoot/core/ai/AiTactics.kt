package dev.mfoot.core.ai

import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.Reparto
import dev.mfoot.core.match.TacticalPressing
import dev.mfoot.core.match.TacticalStance
import dev.mfoot.core.match.TacticalTempo
import dev.mfoot.core.match.TacticalWidth
import dev.mfoot.core.match.Tactics

/**
 * L'assetto che un club del computer sceglie per se'.
 *
 * ## Perche' questo file non esisteva, e cosa si vedeva
 *
 * Perche' nessuno gliel'aveva mai chiesto. Il tick costruiva la formazione dell'AI al
 * volo con [dev.mfoot.core.match.AutoLineup] e le dava [Tactics.DEFAULT] — equilibrato,
 * normale, normale, medio — poi buttava tutto. Dieci club, dieci assetti identici, e
 * nessuno di quei dieci aveva mai preso una decisione tecnica.
 *
 * La segnalazione del proprietario era esattamente questa: «non schierano o fanno nessuna
 * tattica o scelta tecnica». Non era un'impressione, era la descrizione del codice.
 *
 * ## Le tre cose che decidono, in ordine di peso
 *
 * 1. **Chi ha in rosa.** Una squadra con l'attacco piu' forte della difesa gioca in
 *    avanti; il contrario si chiude. E' la scelta che un allenatore vero fa per prima, e
 *    l'unica che rende l'assetto una conseguenza invece di un'etichetta.
 * 2. **Quanto e' fresco.** Con due partite al giorno la stamina e' la risorsa piu' scarsa
 *    che esista. Una rosa a terra che pressa alto arriva alla sera in ginocchio, e questo
 *    e' il freno che glielo impedisce. Vale piu' del carattere: un club aggressivo con la
 *    rosa esausta rallenta comunque.
 * 3. **Il carattere.** [AiPersonality] rende i club riconoscibili, e le fissazioni
 *    inclinano l'assetto in un verso o nell'altro. Inclinano soltanto: se il carattere
 *    pesasse piu' della rosa, si otterrebbero club che giocano all'attacco senza
 *    attaccanti, che e' il modo in cui un'AI smette di sembrare una persona.
 *
 * ## Perche' e' una funzione pura
 *
 * Perche' cosi' si puo' provare. Il difetto precedente delle AI — l'ordine delle mosse
 * scritto dentro il tick — e' sopravvissuto per mesi proprio perche' viveva dove serviva
 * una connessione al database per guardarlo. Qui entrano una rosa e un carattere, esce un
 * assetto, e la prova non chiede niente a nessuno.
 */
object AiTactics {

    /**
     * L'assetto per questa partita.
     *
     * @param squad la rosa intera: gli infortunati si tolgono qui dentro, perche' un
     *   attacco fortissimo tutto in infermeria non e' un attacco forte.
     */
    fun choose(
        personality: AiPersonality,
        squad: List<Player>,
        today: MatchDay = MatchDay(0),
    ): Tactics {
        val disponibili = squad.filterNot { it.isInjured(today) }
        if (disponibili.isEmpty()) return Tactics.DEFAULT

        val sbilanciamento = sbilanciamento(disponibili)
        val freschezza = freschezza(disponibili)

        return Tactics(
            stance = stance(personality, sbilanciamento),
            width = width(personality, disponibili),
            tempo = tempo(personality, freschezza),
            pressing = pressing(personality, freschezza),
        )
    }

    /**
     * Quanto l'attacco supera la difesa, da -1 a +1.
     *
     * Si confrontano i **tre migliori** per reparto e non le medie: chi scende in campo
     * sono quelli, e una media trascinata in basso da sei riserve direbbe che la squadra e'
     * debole ovunque quando invece ha un undici squilibrato.
     */
    fun sbilanciamento(squad: List<Player>): Double {
        val attacco = mediaDeiMigliori(squad, Reparto.ATTACCO)
        val difesa = mediaDeiMigliori(squad, Reparto.DIFESA)
        if (attacco == null || difesa == null) return 0.0
        return ((attacco - difesa) / 12.0).coerceIn(-1.0, 1.0)
    }

    /** Quanto e' riposata la rosa, da 0 a 1. Conta chi giocherebbe, non chi c'e'. */
    fun freschezza(squad: List<Player>): Double {
        val undici = squad.sortedByDescending { it.overall }.take(11)
        if (undici.isEmpty()) return 1.0
        return (undici.sumOf { it.stamina } / undici.size / 100.0).coerceIn(0.0, 1.0)
    }

    private fun mediaDeiMigliori(squad: List<Player>, reparto: Reparto): Double? {
        val migliori = squad.filter { it.primaryPosition.reparto == reparto }
            .sortedByDescending { it.overall }
            .take(3)
        return if (migliori.isEmpty()) null else migliori.sumOf { it.overall }.toDouble() / migliori.size
    }

    private fun stance(personality: AiPersonality, sbilanciamento: Double): TacticalStance {
        // Il carattere vale la meta' della rosa: inclina, non decide.
        val fissazione = when {
            AiObsession.ATTACCO in personality.obsessions -> 0.5
            AiObsession.DIFESA in personality.obsessions -> -0.5
            else -> 0.0
        }
        val audacia = (personality.marketAggression - 0.6) * 0.4
        val punteggio = sbilanciamento + fissazione * 0.5 + audacia

        return when {
            punteggio <= -0.55 -> TacticalStance.ULTRA_DIFENSIVO
            punteggio <= -0.18 -> TacticalStance.DIFENSIVO
            punteggio < 0.18 -> TacticalStance.EQUILIBRATO
            punteggio < 0.55 -> TacticalStance.OFFENSIVO
            else -> TacticalStance.ULTRA_OFFENSIVO
        }
    }

    /**
     * Stretto o largo, deciso da chi si ha sulle fasce.
     *
     * Le ali le rende utili solo il gioco largo, e senza ali il gioco largo produce due
     * terzini spediti in avanti a crossare per nessuno. E' la stessa ragione per cui
     * `AutoLineup` sceglie il modulo dalla rosa invece di imporre il 4-3-3.
     */
    private fun width(personality: AiPersonality, squad: List<Player>): TacticalWidth {
        val ali = squad.count { it.primaryPosition in ESTERNI && it.overall >= 60 }
        return when {
            ali >= 3 -> TacticalWidth.LARGO
            ali <= 1 -> TacticalWidth.STRETTO
            personality.marketAggression > 0.7 -> TacticalWidth.LARGO
            else -> TacticalWidth.NORMALE
        }
    }

    /**
     * Il ritmo, frenato dalla stanchezza.
     *
     * Sotto il sessanta per cento di stamina si va piano comunque: alzare il ritmo con la
     * rosa a terra e' il modo piu' rapido di arrivare alla seconda partita della giornata
     * senza gambe, e nessun carattere lo giustifica.
     */
    private fun tempo(personality: AiPersonality, freschezza: Double): TacticalTempo = when {
        freschezza < 0.60 -> TacticalTempo.LENTO
        freschezza < 0.78 -> TacticalTempo.NORMALE
        personality.marketAggression > 0.65 -> TacticalTempo.ALTO
        personality.patience > 0.7 -> TacticalTempo.LENTO
        else -> TacticalTempo.NORMALE
    }

    /** Il pressing costa piu' del ritmo, quindi il freno scatta prima. */
    private fun pressing(personality: AiPersonality, freschezza: Double): TacticalPressing = when {
        freschezza < 0.65 -> TacticalPressing.BASSO
        freschezza < 0.82 -> TacticalPressing.MEDIO
        AiObsession.DIFESA in personality.obsessions -> TacticalPressing.MEDIO
        personality.marketAggression > 0.6 -> TacticalPressing.ALTO
        else -> TacticalPressing.MEDIO
    }

    /** Chi gioca sulle fasce: i due terzini e le due ali. */
    private val ESTERNI = setOf(Position.TD, Position.TS, Position.AD, Position.AS)
}
