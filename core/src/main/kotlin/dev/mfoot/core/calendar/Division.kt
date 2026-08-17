package dev.mfoot.core.calendar

import dev.mfoot.core.config.DivisionsConfig
import dev.mfoot.core.model.ClubId

/**
 * Una divisione: Serie A, Serie B, e cosi' via.
 *
 * ## Perche' servono
 *
 * Con venti amici in un solo campionato, quindici sanno entro novembre che non vinceranno
 * niente e smettono di aprire l'app. Con quattro divisioni da cinque, ognuno ha sempre due
 * cose per cui giocare: salire, e non scendere. E' la struttura che tiene in vita un
 * campionato lungo, non un dettaglio di realismo.
 *
 * [level] 1 e' la massima. Un numero e non un nome perche' le promozioni e le retrocessioni
 * si ragionano per livello — si sale al livello meno uno — mentre il nome e' solo
 * un'etichetta che l'admin puo' cambiare a piacere.
 */
data class Division(
    val level: Int,
    val name: String,
    /** I club iscritti, nell'ordine in cui compaiono in classifica a inizio stagione. */
    val clubs: List<ClubId>,
) {
    init {
        require(level >= 1) { "il livello di una divisione parte da 1, non da $level" }
        require(clubs.toSet().size == clubs.size) {
            "'$name': lo stesso club compare piu' volte"
        }
    }

    val isTop: Boolean get() = level == 1
}

/**
 * Quante salgono e quante scendono, e come.
 *
 * ## Perche' le regole stanno **fra** due divisioni e non dentro una
 *
 * Le promozioni della Serie B sono le retrocessioni della Serie A: sono lo stesso numero
 * guardato dai due lati. Tenerli in due posti diversi vorrebbe dire poter configurare tre
 * promozioni dalla B e due retrocessioni dalla A — e a fine stagione la Serie A si
 * troverebbe con un club in piu' ogni anno, senza che nessuno capisca da dove arriva.
 *
 * Qui il numero e' scritto una volta e vale nei due sensi, che e' anche il modo in cui
 * funziona il calcio vero.
 */
data class DivisionRules(
    /** Quante salgono direttamente dalla divisione di sotto. */
    val directPromotions: Int = 1,
    /**
     * Quante si giocano ai playoff **l'ultimo posto disponibile**.
     *
     * Zero disattiva i playoff. Due significa una finale secca fra la terza e la quarta;
     * quattro, semifinali e finale.
     */
    val playoffSlots: Int = 2,
    /** Quante scendono direttamente dalla divisione di sopra. */
    val directRelegations: Int = 1,
    /** Quante si giocano la salvezza al playout. Stesso ragionamento dei playoff. */
    val playoutSlots: Int = 2,
) {
    init {
        require(directPromotions >= 0 && directRelegations >= 0) {
            "promozioni e retrocessioni non possono essere negative"
        }
        require(playoffSlots == 0 || playoffSlots >= 2) {
            "un playoff fra meno di due squadre non e' un playoff"
        }
        require(playoutSlots == 0 || playoutSlots >= 2) {
            "un playout fra meno di due squadre non e' un playout"
        }
    }

    /**
     * Quanti posti cambiano mano in tutto, fra un livello e l'altro.
     *
     * I playoff assegnano **un** posto, non uno per partecipante: e' la parte che si
     * sbaglia piu' facilmente leggendo la configurazione, e da cui dipende che il numero di
     * club per divisione resti costante.
     */
    val totalMoves: Int
        get() = directPromotions + if (playoffSlots > 0) 1 else 0

    val totalDrops: Int
        get() = directRelegations + if (playoutSlots > 0) 1 else 0

    /** Le due cifre coincidono? Se no, le divisioni cambiano dimensione ogni stagione. */
    val isBalanced: Boolean get() = totalMoves == totalDrops

    companion object {
        /**
         * Le regole scelte dall'admin.
         *
         * La conversione sta qui e non nella configurazione perche' e' il regolamento a
         * sapere cosa farne: [DivisionsConfig] e' un modulo da compilare, questo e' cio' che
         * decide chi sale e chi scende. Se la configurazione producesse le regole da sola,
         * ogni vincolo nuovo del regolamento andrebbe ricopiato in un file di impostazioni.
         */
        fun of(config: DivisionsConfig) = DivisionRules(
            directPromotions = config.directPromotions,
            playoffSlots = config.playoffSlots,
            directRelegations = config.directRelegations,
            playoutSlots = config.playoutSlots,
        )
    }
}

/**
 * Cosa succede a un club a fine stagione.
 *
 * Gli spareggi sono un esito a se' e non "promosso, forse": finita la stagione regolare il
 * club **sa** di dover giocare ancora, e mostrargli "salvo" o "retrocesso" prima che quelle
 * partite si giochino sarebbe una bugia.
 */
enum class SeasonOutcome(val label: String) {
    CAMPIONE("Campione"),
    PROMOSSO("Promosso"),
    PLAYOFF("Ai playoff"),
    RESTA("Resta"),
    PLAYOUT("Ai playout"),
    RETROCESSO("Retrocesso"),
}

/** L'esito di un club, con la posizione da cui arriva. */
data class ClubFate(
    val club: ClubId,
    val level: Int,
    val position: Int,
    val outcome: SeasonOutcome,
)

/**
 * La fine della stagione, divisione per divisione.
 *
 * Non scrive niente e non genera partite: dice **chi fa cosa**. Le partite di spareggio le
 * crea chi chiama, con lo stesso generatore di tabelloni delle coppe, e questo e' voluto —
 * qui dentro non c'e' nessun calendario da sbagliare, solo il regolamento.
 */
object SeasonEnd {

    /**
     * Gli esiti di tutti i club.
     *
     * @param standings per ogni livello, la classifica finale in ordine di posizione.
     */
    fun settle(
        divisions: List<Division>,
        standings: Map<Int, List<ClubId>>,
        rules: DivisionRules,
    ): List<ClubFate> {
        val ordinate = divisions.sortedBy { it.level }
        val ultimo = ordinate.lastOrNull()?.level ?: return emptyList()

        return ordinate.flatMap { division ->
            // Senza classifica per questo livello non si inventa un ordine: si usa quello
            // di iscrizione, che almeno e' stabile e visibile a tutti.
            val classifica = standings[division.level] ?: division.clubs

            classifica.mapIndexed { index, club ->
                ClubFate(
                    club = club,
                    level = division.level,
                    position = index + 1,
                    outcome = outcomeOf(
                        position = index + 1,
                        size = classifica.size,
                        isTop = division.isTop,
                        isBottom = division.level == ultimo,
                        rules = rules,
                    ),
                )
            }
        }
    }

    /**
     * L'esito di una singola posizione.
     *
     * ## Le due estremita' non hanno dove andare
     *
     * Dalla massima divisione non si sale e dall'ultima non si scende: non e' un caso
     * particolare da gestire, e' la definizione di primo e ultimo livello. Trattarle come
     * le altre produrrebbe un promosso che va al livello zero, cioe' scompare dalla lega.
     *
     * ## Perche' il conto parte dal fondo
     *
     * Le retrocessioni si contano dall'ultimo posto in su, non dal primo in giu'. Con un
     * numero di club che cambia da una divisione all'altra — ed e' normale che cambi, dieci
     * amici non si dividono in gruppi uguali — contarle dall'alto darebbe posizioni
     * diverse in ogni divisione a parita' di regole.
     */
    private fun outcomeOf(
        position: Int,
        size: Int,
        isTop: Boolean,
        isBottom: Boolean,
        rules: DivisionRules,
    ): SeasonOutcome {
        val dalFondo = size - position + 1

        // La promozione vince sulla retrocessione: in una divisione molto piccola le due
        // fasce potrebbero sovrapporsi, e un club primo in classifica non puo' retrocedere
        // per nessuna ragione al mondo.
        if (!isTop) {
            if (position <= rules.directPromotions) {
                return if (position == 1) SeasonOutcome.CAMPIONE else SeasonOutcome.PROMOSSO
            }
            val finePlayoff = rules.directPromotions + rules.playoffSlots
            if (rules.playoffSlots > 0 && position <= finePlayoff) return SeasonOutcome.PLAYOFF
        } else if (position == 1) {
            return SeasonOutcome.CAMPIONE
        }

        if (!isBottom) {
            if (dalFondo <= rules.directRelegations) return SeasonOutcome.RETROCESSO
            val finePlayout = rules.directRelegations + rules.playoutSlots
            if (rules.playoutSlots > 0 && dalFondo <= finePlayout) return SeasonOutcome.PLAYOUT
        }

        return SeasonOutcome.RESTA
    }

    /**
     * Gli accoppiamenti di uno spareggio: primo contro ultimo, secondo contro penultimo.
     *
     * E' lo schema del tabellone tennistico, e premia chi ha fatto meglio in stagione
     * regolare dandogli l'avversario piu' debole. Accoppiare a caso renderebbe le ultime
     * giornate senza senso: se il piazzamento non cambia niente, non c'e' motivo di
     * giocarle.
     *
     * Con un numero dispari di partecipanti il primo passa il turno — e' lo stesso
     * meccanismo del bye nelle coppe, e sta qui perche' una divisione da cinque con tre
     * squadre ai playoff e' una situazione normale, non un errore da rifiutare.
     */
    fun pairings(qualificate: List<ClubId>): List<Pairing> {
        if (qualificate.size < 2) return emptyList()

        val coppie = mutableListOf<Pairing>()
        var alto = 0
        var basso = qualificate.size - 1
        while (alto < basso) {
            // La meglio piazzata gioca in casa: nel doppio confronto e' anche lei ad avere
            // il ritorno, che con [Standings.tieWinner] e' il vantaggio in caso di parita'.
            coppie += Pairing(home = qualificate[alto], away = qualificate[basso])
            alto++
            basso--
        }
        return coppie
    }

    /**
     * Chi passa il turno, dato l'esito degli accoppiamenti.
     *
     * Un accoppiamento senza partite giocate non produce nessun vincitore: si aspetta.
     * Inventare un passaggio del turno per una partita non giocata e' il modo piu' rapido
     * di far salire in Serie A una squadra che non ha vinto niente.
     */
    fun advance(coppie: List<Pairing>, results: List<FixtureResult>): List<ClubId> =
        coppie.mapNotNull { coppia ->
            val gare = results.filter {
                (it.home == coppia.home && it.away == coppia.away) ||
                    (it.home == coppia.away && it.away == coppia.home)
            }
            if (gare.isEmpty()) null else Standings.tieWinner(gare)
        }
}
