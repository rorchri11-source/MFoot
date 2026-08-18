package dev.mfoot.core.conversation

import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId

/**
 * Una partita, dal punto di vista di un giocatore.
 *
 * [minutes] a zero significa che non e' sceso in campo: e' l'informazione che serve piu'
 * spesso, e senza una riga anche per chi resta fuori sarebbe indistinguibile dal non
 * saperne niente.
 */
data class AppearanceFact(
    val matchDay: MatchDay,
    val started: Boolean,
    val minutes: Int,
    val rating: Double,
    val goals: Int = 0,
    val injured: Boolean = false,
) {
    val played: Boolean get() = minutes > 0
}

/**
 * Tutto quello che si sa di un giocatore al momento di decidere se ha qualcosa da dire.
 *
 * [recent] arriva **dalla piu' recente alla piu' vecchia**: e' l'ordine in cui si guarda
 * uno storico, ed evita di doverlo invertire in ognuna delle regole qui sotto.
 */
data class PlayerHistory(
    val playerId: PlayerId,
    /** La giornata in cui e' arrivato in questa squadra. */
    val joinedOn: MatchDay,
    val contractEndsOn: MatchDay,
    val isInjured: Boolean,
    val recent: List<AppearanceFact> = emptyList(),
    /** Il tick ha appena chiuso una sua promessa come tradita. */
    val brokenPromise: Boolean = false,
    val isCaptain: Boolean = false,
    /** Sconfitte consecutive della squadra. Serve solo al capitano. */
    val teamLosingStreak: Int = 0,
    /** Giornata dell'ultimo colloquio chiuso, se ce n'e' stato uno. */
    val lastConversationOn: MatchDay? = null,
)

/** Un colloquio che ha diritto di esistere, e il fatto che lo giustifica. */
data class ConversationTrigger(
    val topic: ConversationTopic,
    /** Testo pronto da mostrare: "Tre panchine di fila: 12a, 13a, 14a". */
    val cause: String,
)

/**
 * Chi ha qualcosa da dirti, e perche'.
 *
 * ## Il difetto che questo file chiude
 *
 * Prima l'argomento del colloquio si ricavava da una soglia sul morale, ricalcolata a ogni
 * apertura della schermata. Parlavi, il morale saliva, la soglia cambiava e compariva
 * l'argomento successivo: quattro colloqui di fila con lo stesso giocatore, +5 ogni volta,
 * perche' niente ricordava che avevi gia' parlato. E un giocatore comprato ieri con morale
 * 34 ti chiedeva piu' spazio prima di aver messo piede in campo, perche' il gioco non
 * guardava cosa gli fosse successo: guardava un numero.
 *
 * Qui un colloquio nasce da un **fatto**, e il fatto e' scritto accanto. Se non e'
 * successo niente, non c'e' niente di cui parlare.
 *
 * ## Perche' sta in `core` e non nel tick
 *
 * Perche' e' regolamento, non idraulica. "Tre panchine di fila aprono un discorso" e' una
 * regola del gioco quanto il fuorigioco, e le regole si testano senza un database davanti.
 */
object LeagueFacts {

    /** Quante partite senza scendere in campo prima che diventi un discorso. */
    const val PANCHINE_PER_LAMENTARSI = 3

    /** Sotto questo voto la prestazione conta come brutta. */
    const val VOTO_BRUTTO = 5.5

    /** Da qui in su e' stata la sua partita. */
    const val VOTO_GRANDE = 7.8

    /** Giornate di preavviso sul contratto. */
    const val PREAVVISO_CONTRATTO = 6

    /** Giornate fra una convocazione a piacere e la successiva. */
    const val ATTESA_FRA_CONVOCAZIONI = 3

    /**
     * L'unico argomento che questo giocatore ha davvero, o null se sta bene.
     *
     * Uno solo, il piu' urgente. Un elenco di quattro cose da dirsi non e' una
     * conversazione: e' un modulo da compilare, e chi lo compila sceglie sempre la voce
     * che rende di piu'.
     *
     * L'ordine e' quello con cui una persona affronterebbe davvero lo spogliatoio: prima
     * si rimedia a un torto fatto, poi si accoglie chi arriva, poi si tiene chi vuole
     * andarsene. I complimenti vengono per ultimi, perche' possono aspettare.
     */
    fun trigger(player: Player, history: PlayerHistory, today: MatchDay): ConversationTrigger? {
        if (history.brokenPromise) {
            return ConversationTrigger(
                ConversationTopic.PROMESSA_TRADITA,
                "Gli avevi promesso qualcosa e non e' arrivata.",
            )
        }

        // Chi e' appena arrivato non ha ancora nessuno storico: qualunque altra regola,
        // applicata a lui, parlerebbe di partite che non ha giocato.
        if (history.recent.isEmpty() && today.value - history.joinedOn.value <= 2) {
            return ConversationTrigger(
                ConversationTopic.NUOVO_ARRIVO,
                "E' arrivato da poco e non ha ancora giocato.",
            )
        }

        if (player.morale < SOGLIA_CESSIONE) {
            return ConversationTrigger(
                ConversationTopic.RICHIESTA_CESSIONE,
                "Morale a ${player.morale}: sta chiedendo di andarsene.",
            )
        }

        if (history.isInjured) {
            return ConversationTrigger(
                ConversationTopic.INFORTUNIO,
                "E' fermo per infortunio.",
            )
        }

        val ultima = history.recent.firstOrNull()

        // Rientro: ha rigiocato, ma nelle partite prima era fuori.
        if (ultima != null && ultima.played && !ultima.injured &&
            history.recent.drop(1).take(3).any { it.injured }
        ) {
            return ConversationTrigger(
                ConversationTopic.RIENTRO,
                "Prima partita dopo l'infortunio.",
            )
        }

        val panchine = history.recent.takeWhile { !it.played }
        if (panchine.size >= PANCHINE_PER_LAMENTARSI) {
            val giornate = panchine.take(PANCHINE_PER_LAMENTARSI)
                .reversed()
                .joinToString(", ") { "${it.matchDay.value}a" }
            return ConversationTrigger(
                ConversationTopic.PANCHINA_PROLUNGATA,
                "${panchine.size} partite senza scendere in campo: $giornate.",
            )
        }

        val giocate = history.recent.filter { it.played }.take(3)
        val brutte = giocate.filter { it.rating < VOTO_BRUTTO }
        if (giocate.size >= 3 && brutte.size >= 2) {
            val voti = brutte.joinToString(", ") { formatta(it.rating) }
            return ConversationTrigger(
                ConversationTopic.PRESTAZIONI_SCARSE,
                "${brutte.size} voti sotto ${formatta(VOTO_BRUTTO)} nelle ultime 3: $voti.",
            )
        }

        if (player.morale < SOGLIA_MORALE_BASSO) {
            return ConversationTrigger(
                ConversationTopic.MORALE_BASSO,
                "Morale a ${player.morale}.",
            )
        }

        val giornateAllaScadenza = history.contractEndsOn.value - today.value
        if (giornateAllaScadenza in 0..PREAVVISO_CONTRATTO) {
            return ConversationTrigger(
                ConversationTopic.CONTRATTO_IN_SCADENZA,
                if (giornateAllaScadenza == 0) {
                    "Il contratto scade adesso."
                } else {
                    "Il contratto scade fra $giornateAllaScadenza giornate."
                },
            )
        }

        if (history.isCaptain && history.teamLosingStreak >= 2) {
            return ConversationTrigger(
                ConversationTopic.CAPITANO,
                "${history.teamLosingStreak} sconfitte di fila: la fascia pesa.",
            )
        }

        if (ultima != null && ultima.played &&
            (ultima.rating >= VOTO_GRANDE || ultima.goals >= 2)
        ) {
            val come = if (ultima.goals >= 2) {
                "${ultima.goals} gol nella ${ultima.matchDay.value}a"
            } else {
                "${formatta(ultima.rating)} nella ${ultima.matchDay.value}a"
            }
            return ConversationTrigger(ConversationTopic.GRANDE_PRESTAZIONE, come)
        }

        if (player.morale < SOGLIA_POCO_SPAZIO && giocate.isNotEmpty() &&
            giocate.none { it.started }
        ) {
            return ConversationTrigger(
                ConversationTopic.POCO_MINUTAGGIO,
                "Entra sempre a partita in corso.",
            )
        }

        return null
    }

    /**
     * Si puo' convocare questo giocatore adesso?
     *
     * La convocazione a piacere esiste perche' un manager deve poter parlare a chi vuole.
     * L'attesa esiste perche' senza si potrebbe farlo ogni cinque minuti, e sarebbe di
     * nuovo il pulsante "alza morale" con un nome diverso.
     */
    fun puoiConvocare(history: PlayerHistory, today: MatchDay): Boolean =
        attesaResidua(history.lastConversationOn?.value, today.value) == 0

    /** Quante giornate mancano prima di poterlo riconvocare. */
    fun attesaResidua(history: PlayerHistory, today: MatchDay): Int =
        attesaResidua(history.lastConversationOn?.value, today.value)

    /**
     * La stessa attesa, per chi ha in mano solo due numeri.
     *
     * La schermata dello spogliatoio conosce l'ultima giornata in cui si e' parlato e
     * quella di oggi, e nient'altro: costringerla a costruire una [PlayerHistory] finta con
     * sei campi inventati per leggerne uno solo sarebbe un modo di nascondere che il conto
     * e' una sottrazione.
     */
    fun attesaResidua(ultimoColloquio: Int?, oggi: Int): Int {
        val ultimo = ultimoColloquio ?: return 0
        return (ATTESA_FRA_CONVOCAZIONI - (oggi - ultimo)).coerceAtLeast(0)
    }

    /**
     * Di cosa si parla quando sei tu a convocarlo e lui non aveva niente da dire.
     *
     * Non e' un argomento vuoto: e' il piu' generico che regga una conversazione. Rende
     * poco proprio perche' non nasce da niente.
     */
    fun argomentoDiCortesia(player: Player): ConversationTopic = when {
        player.morale < SOGLIA_MORALE_BASSO -> ConversationTopic.MORALE_BASSO
        else -> ConversationTopic.RINNOVO
    }

    private const val SOGLIA_CESSIONE = 20
    private const val SOGLIA_MORALE_BASSO = 35
    private const val SOGLIA_POCO_SPAZIO = 55

    private fun formatta(voto: Double): String {
        val arrotondato = StrictMath.round(voto * 10.0) / 10.0
        return arrotondato.toString().replace('.', ',')
    }
}
