package dev.mfoot.core.market

import dev.mfoot.core.ai.AiPersonality
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Money
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.rng.MathX

/**
 * Una proposta di scambio fra due club.
 *
 * ## Perche' i soldi hanno un segno
 *
 * [cash] positivo significa che chi propone **aggiunge** denaro; negativo che ne chiede.
 * Con due campi separati — "offro tanto" e "chiedo tanto" — si potrebbero riempire tutti e
 * due insieme, e nessuno saprebbe dire cosa vuol dire una proposta che offre venti milioni
 * e ne chiede trenta. Un numero solo con un segno non ha stati assurdi.
 */
data class TradeOffer(
    val id: Long = 0,
    val from: ClubId,
    val to: ClubId,
    /** Giocatori che chi propone cede. */
    val offered: List<PlayerId> = emptyList(),
    /** Giocatori che chi propone chiede. */
    val wanted: List<PlayerId> = emptyList(),
    /** Positivo: chi propone aggiunge denaro. Negativo: ne chiede. */
    val cash: Int = 0,
    val message: String = "",
    val status: TradeStatus = TradeStatus.PROPOSTA,
) {
    init {
        require(from != to) { "un club non può scambiare con se stesso" }
    }

    /** Una proposta vuota non e' una proposta. */
    val isEmpty: Boolean get() = offered.isEmpty() && wanted.isEmpty() && cash == 0

    /** Quanti giocatori entrano e quanti escono, per chi riceve la proposta. */
    fun squadDeltaFor(club: ClubId): Int = when (club) {
        to -> offered.size - wanted.size
        from -> wanted.size - offered.size
        else -> 0
    }
}

enum class TradeStatus(val label: String) {
    PROPOSTA("In attesa"),
    ACCETTATA("Accettata"),
    RIFIUTATA("Rifiutata"),
    RITIRATA("Ritirata"),
    SCADUTA("Scaduta"),

    /**
     * Le e stata opposta una controproposta.
     *
     * Non e ne accettata ne rifiutata: e andata avanti, e la risposta e una proposta
     * nuova nella direzione opposta. Distinguerlo serve a raccontare la trattativa invece
     * di mostrare un rifiuto dove c e stato un rilancio.
     */
    CONTROPROPOSTA("Controproposta"),
}

/** Perche' una proposta e' stata rifiutata. Serve a dirlo a chi l'ha fatta. */
enum class TradeVerdict(val label: String) {
    ACCETTO("Accetto"),
    VALORE_INSUFFICIENTE("Non mi conviene"),
    ROSA_TROPPO_CORTA("Resterei sotto il minimo"),
    NON_VENDO("Non lo cedo"),
    SOLDI_INSUFFICIENTI("Non ho abbastanza denaro"),
}

data class TradeAnswer(val verdict: TradeVerdict, val reason: String) {
    val accepted: Boolean get() = verdict == TradeVerdict.ACCETTO
}

/**
 * Come un club gestito dal computer risponde a uno scambio.
 *
 * ## Il principio: non fare l'affare, non farsi fregare
 *
 * Un'AI che accetta ogni scambio vantaggioso al centesimo diventa un bancomat: si capisce
 * in due giorni come spremerla e la lega si svuota di senso. Un'AI che rifiuta tutto e'
 * peggio — tanto vale non avere gli scambi. La regola qui e' la stessa che userebbe una
 * persona ragionevole: **accetto se ci guadagno abbastanza da giustificare il fastidio**,
 * dove "abbastanza" dipende dal carattere.
 *
 * ## Perche' il valore non basta
 *
 * Due giocatori possono valere lo stesso e non essere affatto lo stesso affare. Un club con
 * un portiere solo non cede il portiere nemmeno per il doppio; un club che sta sotto il
 * minimo di rosa non accetta scambi che gli tolgono uomini, perche' non scenderebbe in
 * campo. Il valore e' il punto di partenza, non la risposta.
 */
object TradeEvaluator {

    /**
     * Quanto in piu' del valore di mercato deve valere uno scambio per essere accettato.
     *
     * Chi ha poca disciplina si accontenta del cinque per cento, chi ne ha molta ne vuole
     * trenta. Nessuno accetta la parita' secca: uno scambio alla pari e' un fastidio senza
     * guadagno, e le persone vere infatti non lo fanno.
     */
    private const val MARGINE_MIN = 0.05
    private const val MARGINE_MAX = 0.30

    /**
     * @param offeredValues quanto valgono i giocatori **offerti**, che appartengono
     *   all'altro club e quindi non stanno in [squad]. Li calcola chi chiama, che ha il
     *   mondo in mano: farli cercare qui dentro vorrebbe dire passare tutto il listino a
     *   una funzione che deve solo dire si' o no.
     */
    fun evaluate(
        offer: TradeOffer,
        personality: AiPersonality,
        squad: List<Player>,
        availableCredits: Int,
        config: LeagueConfig,
        offeredValues: Map<PlayerId, Int>,
    ): TradeAnswer {
        if (offer.isEmpty) {
            return TradeAnswer(TradeVerdict.VALORE_INSUFFICIENTE, "Non mi hai offerto niente.")
        }

        val byId = squad.associateBy { it.id }
        val ceduti = offer.wanted.mapNotNull { byId[it] }

        // Un giocatore che non ho non lo posso cedere: la proposta e' vecchia, e va
        // rifiutata invece di essere accettata a meta'.
        if (ceduti.size != offer.wanted.size) {
            return TradeAnswer(
                TradeVerdict.NON_VENDO,
                "Uno dei giocatori che chiedi non è più in rosa.",
            )
        }

        // I soldi che dovrei mettere io: `cash` negativo significa che me li chiedono.
        val esborso = if (offer.cash < 0) -offer.cash else 0
        if (esborso > availableCredits) {
            return TradeAnswer(
                TradeVerdict.SOLDI_INSUFFICIENTI,
                "Mi chiedi più denaro di quanto ne abbia libero.",
            )
        }

        val minimo = config.setup.minSquadSize
        val dopo = squad.size + offer.squadDeltaFor(offer.to)
        if (dopo < minimo) {
            return TradeAnswer(
                TradeVerdict.ROSA_TROPPO_CORTA,
                "Resterei con $dopo giocatori e me ne servono $minimo per scendere in campo.",
            )
        }

        // Un ruolo che resterebbe scoperto non si svuota per nessuna cifra.
        val scoperto = ruoloScoperto(squad, ceduti)
        if (scoperto != null) {
            return TradeAnswer(
                TradeVerdict.NON_VENDO,
                "Resterei senza nessuno che sappia fare il ${scoperto.short}.",
            )
        }

        val valoreRicevuto = offer.offered.sumOf { offeredValues[it] ?: 0 } +
            maxOf(offer.cash, 0)
        val valoreCeduto = ceduti.sumOf { Valuation.marketValue(it, config) } + esborso

        val margine = MathX.lerp(MARGINE_MAX, MARGINE_MIN, personality.marketAggression)
        val soglia = valoreCeduto * (1.0 + margine)

        return if (valoreRicevuto >= soglia) {
            TradeAnswer(TradeVerdict.ACCETTO, "Affare fatto.")
        } else {
            TradeAnswer(
                TradeVerdict.VALORE_INSUFFICIENTE,
                "Quello che offri non basta per quello che chiedi.",
            )
        }
    }

    /**
     * La controproposta: «non a queste condizioni, ma cosi' si'».
     *
     * ## Perche' un'AI deve saper controproporre
     *
     * Perche' senza, una trattativa e' prendere o lasciare, e il novanta per cento delle
     * proposte finisce in un no secco che non insegna niente. Chi la riceve non sa se ha
     * sbagliato di poco o di tanto, e riprova alla cieca — o smette di provarci.
     *
     * Con la controproposta il rifiuto porta con se' l'informazione che mancava: **quanto**
     * ci mancava. E' la differenza fra un mercato e un distributore automatico.
     *
     * ## Cosa cambia e cosa no
     *
     * Cambiano **solo i soldi**. I giocatori restano quelli: se A vuole il mio centravanti
     * e mi offre il suo terzino, la risposta sensata e' "ci aggiungi qualcosa", non "invece
     * del terzino dammi il portiere" — che sarebbe una proposta diversa, non una risposta a
     * questa.
     *
     * ## Quando non c'e' nessuna cifra che vada bene
     *
     * Quando il no non riguarda il prezzo: un giocatore che non ho piu', un ruolo che
     * resterebbe scoperto, una rosa che scenderebbe sotto il minimo. Per quelli non esiste
     * un numero, e restituire null e' l'unica risposta onesta.
     */
    fun counter(
        offer: TradeOffer,
        personality: AiPersonality,
        squad: List<Player>,
        availableCredits: Int,
        config: LeagueConfig,
        offeredValues: Map<PlayerId, Int>,
    ): TradeOffer? {
        val risposta = evaluate(offer, personality, squad, availableCredits, config, offeredValues)
        if (risposta.accepted) return null
        // Solo il prezzo si contratta. Sugli altri rifiuti non c'e' cifra che tenga.
        if (risposta.verdict != TradeVerdict.VALORE_INSUFFICIENTE) return null

        val byId = squad.associateBy { it.id }
        val ceduti = offer.wanted.mapNotNull { byId[it] }
        if (ceduti.size != offer.wanted.size) return null

        val valoreGiocatoriRicevuti = offer.offered.sumOf { offeredValues[it] ?: 0 }
        val valoreCeduti = ceduti.sumOf { Valuation.marketValue(it, config) }

        val margine = MathX.lerp(MARGINE_MAX, MARGINE_MIN, personality.marketAggression)

        // La cifra che rende l'affare accettabile, risolta rispetto a `cash`.
        //
        // Ricevuto = giocatori offerti + max(cash, 0); ceduto = miei giocatori +
        // max(-cash, 0). Chiedendo denaro (`cash` positivo per chi propone) il conto e'
        // lineare, quindi basta isolarlo invece di cercarlo per tentativi.
        val servono = valoreCeduti * (1.0 + margine) - valoreGiocatoriRicevuti

        // Per eccesso, non al piu' vicino.
        //
        // Arrotondando si finisce sotto meta' delle volte: la cifra chiesta manca di un
        // soldo, l'altro la paga per intero e si sente rispondere di nuovo di no. Una
        // controproposta che il suo stesso autore poi rifiuta e' peggio di un rifiuto
        // secco, perche' fa perdere anche un giro.
        val richiesta = StrictMath.ceil(servono).toInt()

        // Se serve meno di zero l'affare era gia' buono, e ci sarebbe stato un ACCETTO.
        if (richiesta <= 0) return null

        // I due lati si scambiano, perche' adesso a proporre e' chi aveva ricevuto.
        //
        // Non e' un dettaglio contabile: la controproposta e' **una proposta nuova, nella
        // direzione opposta**, e deve finire nella casella "ricevute" dell'altro. Con i
        // lati invariati si presenterebbe come una proposta di chi l'aveva gia' mandata, e
        // resterebbe nella casella sbagliata ad aspettare una risposta da chi l'ha scritta.
        //
        // Anche il segno del denaro segue: `cash` positivo vuol dire "chi propone
        // aggiunge", e adesso chi propone e' l'altro — quindi la cifra si **chiede**.
        return TradeOffer(
            from = offer.to,
            to = offer.from,
            offered = offer.wanted,
            wanted = offer.offered,
            cash = -richiesta,
            message = "Non a queste condizioni. Con ${Money(richiesta).format()} sopra, sì.",
        )
    }

    /**
     * Il ruolo che resterebbe senza nessuno.
     *
     * Solo la porta, per ora: e' l'unico ruolo di cui serve **almeno uno**, e senza si
     * prendono quattro gol a partita. Con tre difensori invece di quattro si gioca.
     */
    private fun ruoloScoperto(squad: List<Player>, ceduti: List<Player>) =
        squad.firstOrNull { it.primaryPosition.isGoalkeeper }
            ?.takeIf { portiere ->
                squad.count { it.primaryPosition.isGoalkeeper } ==
                    ceduti.count { it.primaryPosition.isGoalkeeper } && ceduti.contains(portiere)
            }
            ?.primaryPosition
}
