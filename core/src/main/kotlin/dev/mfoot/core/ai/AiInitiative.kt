package dev.mfoot.core.ai

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.market.ContractRules
import dev.mfoot.core.market.TradeOffer
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.model.Club
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Contract
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.rng.MathX

/** Una cosa che un club gestito dal computer decide di fare sulla propria rosa. */
sealed interface SquadAction {
    /** Rinnovare a chi serve ancora. */
    data class Rinnova(val playerId: PlayerId, val cost: Int, val reason: String) : SquadAction

    /** Lasciar andare chi non gioca e costa. */
    data class Svincola(val playerId: PlayerId, val reason: String) : SquadAction
}

/**
 * Quello che un club gestito dal computer fa **di propria iniziativa**.
 *
 * ## Il difetto che questo file chiude
 *
 * Fino a qui un'AI sapeva fare tre cose: aprire un'asta, rilanciare, rispondere a uno
 * scambio. Tutte e tre **reattive** o rivolte al mercato dei svincolati. Il risultato era
 * una lega in cui, se gli amici non giocavano, non succedeva niente: nessuno ti proponeva
 * mai niente, e le rose dei computer invecchiavano senza che nessuno le sistemasse.
 *
 * Un'AI deve poter fare quello che fai tu. Qui ci sono le tre cose che le mancavano:
 * proporre uno scambio, chiedere un'amichevole, tenere in ordine la propria rosa.
 *
 * ## Perche' non decide anche *quando*
 *
 * Il momento lo sceglie [AiScheduler], e i tetti restano quelli. Il problema che
 * risolvevano — venticinque club che si muovono nello stesso secondo — non sparisce perche'
 * adesso hanno piu' cose da fare: peggiora.
 */
object AiInitiative {

    /**
     * Quanto in piu' del valore bisogna mettere sul piatto perche' una proposta sia
     * appetibile.
     *
     * Una proposta al valore esatto viene rifiutata da chiunque: cedere un giocatore costa
     * fastidio, e nessuno lo fa in pareggio. Le AI lo sanno di se stesse — e' la stessa
     * soglia che applicano quando ricevono — quindi propongono gia' sopra invece di
     * collezionare rifiuti.
     */
    const val SOVRAPPREZZO_MIN = 1.08
    const val SOVRAPPREZZO_MAX = 1.30

    /** Sotto questa distanza dal proprio ruolo peggiore, non vale la pena muoversi. */
    const val SOGLIA_BISOGNO = 1.15

    /**
     * Uno scambio da proporre a questo club, o null se non c'e' niente da chiedergli.
     *
     * ## Perche' sempre uno contro uno, piu' denaro
     *
     * Perche' e' l'unica forma che non puo' mandare nessuna delle due rose sotto il
     * minimo, e quindi non ha bisogno di sapere quanti giocatori abbia l'altro. Proposte
     * piu' ricche sono possibili dal lato umano — la funzione SQL le accetta — ma un'AI che
     * le costruisse dovrebbe ragionare su combinazioni, e a venti club per lega quel calcolo
     * costa piu' di quanto la lega ci guadagni.
     *
     * ## Perche' non chiede il migliore che vede
     *
     * Chiede chi **le serve**: il ruolo piu' scoperto della propria rosa. Un'AI che
     * chiedesse sempre il piu' forte manderebbe venti proposte per lo stesso fuoriclasse e
     * si comporterebbe da elenco ordinato per overall invece che da squadra.
     */
    fun proposeTrade(
        state: AiState,
        club: Club,
        mySquad: List<Player>,
        theirClub: ClubId,
        theirSquad: List<Player>,
        config: LeagueConfig,
    ): TradeOffer? {
        if (!config.market.swapsEnabled) return null
        if (mySquad.size <= config.setup.minSquadSize) return null
        if (theirSquad.size <= config.setup.minSquadSize) return null

        val personality = state.personality
        val ruolo = ruoloPiuScoperto(mySquad, config) ?: return null

        // Chi ha lui in quel ruolo, e che a lui avanza.
        val candidati = theirSquad
            .filter { it.primaryPosition == ruolo }
            .filter { quantiIn(theirSquad, ruolo) > minimoPer(ruolo, config) }
            .sortedByDescending { it.overall }

        val bersaglio = candidati.firstOrNull() ?: return null

        // Chi mi avanza, e che valga meno di quello che chiedo: uno scambio in cui cedo il
        // migliore per prendere il peggiore non lo propone nessuno.
        val valoreBersaglio = Valuation.marketValue(bersaglio, config)
        val moneta = mySquad
            .filterNot { it.id == bersaglio.id }
            .filter { quantiIn(mySquad, it.primaryPosition) > minimoPer(it.primaryPosition, config) }
            .filter { Valuation.marketValue(it, config) < valoreBersaglio }
            .maxByOrNull { Valuation.marketValue(it, config) }
            ?: return null

        val valoreMoneta = Valuation.marketValue(moneta, config)

        // Il conguaglio: la differenza, piu' il sovrapprezzo che rende la proposta
        // accettabile. Chi e' impaziente mette di piu'.
        val sovrapprezzo = MathX.lerp(SOVRAPPREZZO_MAX, SOVRAPPREZZO_MIN, personality.budgetDiscipline)
        val conguaglio = StrictMath.round((valoreBersaglio * sovrapprezzo) - valoreMoneta).toInt()

        if (conguaglio <= 0) return null
        if (conguaglio > club.availableCredits) return null

        // Non si svuota la cassa per uno scambio: restare senza denaro a meta' mercato
        // significa non poter piu' partecipare a nessuna asta.
        val tetto = StrictMath.round(
            club.availableCredits * MathX.lerp(0.55, 0.25, personality.budgetDiscipline),
        ).toInt()
        if (conguaglio > tetto) return null

        return TradeOffer(
            from = club.id,
            to = theirClub,
            offered = listOf(moneta.id),
            wanted = listOf(bersaglio.id),
            cash = conguaglio,
            message = "Ci servirebbe ${bersaglio.shortName}. Ti offriamo ${moneta.shortName} " +
                "e la differenza.",
        )
    }

    /**
     * Vuole giocare un'amichevole?
     *
     * Ha senso quando la squadra e' **ferma e riposata**: nessuna partita in vista, gambe
     * fresche. Con le gambe stanche un'amichevole e' solo un modo di arrivare rotti alla
     * partita vera, e infatti chi ha appena giocato dice di no.
     */
    fun wantsFriendly(
        state: AiState,
        squad: List<Player>,
        config: LeagueConfig,
        matchDaysUntilNextMatch: Int,
    ): Boolean {
        if (!config.rules.friendliesEnabled) return false
        if (squad.size < config.setup.minSquadSize) return false
        if (matchDaysUntilNextMatch < 2) return false

        val staminaMedia = squad.map { it.stamina }.average()
        if (staminaMedia < 70.0) return false

        // I temerari chiedono amichevoli piu' spesso dei prudenti: e' l'unico posto in cui
        // l'aggressivita' di mercato si vede fuori dal mercato.
        return state.personality.marketAggression > 0.45
    }

    /**
     * Chi mettere sul mercato, o null se non c'e' nessuno da vendere.
     *
     * ## Perche' un'AI deve vendere
     *
     * Perche' senza, il mercato muore il giorno in cui l'ultimo svincolato trova squadra.
     * Le aste esistevano solo per chi non aveva contratto: finito l'allestimento, il
     * listino era vuoto per il resto della stagione e non succedeva piu' niente. Un club
     * che vende e' l'unica fonte di offerta nuova dopo la prima settimana.
     *
     * ## Chi si vende
     *
     * Chi sta **sotto la mediana della rosa in un ruolo dove si abbonda**. Non il peggiore
     * in assoluto: il peggiore potrebbe essere l'unico portiere di riserva, e venderlo
     * significa giocare la prossima partita con un difensore fra i pali.
     *
     * @return il giocatore e il prezzo di partenza, che e' una frazione del valore perche'
     *   il prezzo lo deve fare l'asta e non chi la apre.
     */
    fun playerToSell(
        state: AiState,
        squad: List<Player>,
        config: LeagueConfig,
    ): Pair<Player, Int>? {
        if (squad.size <= config.setup.minSquadSize) return null

        val mediana = squad.map { it.overall }.sorted()[squad.size / 2]

        val candidato = squad
            .filter { it.overall < mediana }
            .filter { quantiIn(squad, it.primaryPosition) > minimoPer(it.primaryPosition, config) }
            // Non i giovani su cui si sta scommettendo: sono il motivo per cui esiste la
            // forbice di potenziale, e venderli a diciannove anni perche' oggi valgono
            // poco e' il modo di non vederla mai chiudersi.
            .filterNot { it.age <= config.rules.peakAgeStart && it.potentialMax > mediana + 4 }
            .minByOrNull { it.overall }
            ?: return null

        val valore = Valuation.marketValue(candidato, config)
        // Base bassa: chi apre un'asta partendo dal proprio prezzo dichiara quanto vale
        // per lui, e nessuno rilancia sopra.
        val base = 1.coerceAtLeast(
            StrictMath.round(valore * MathX.lerp(0.25, 0.45, state.personality.budgetDiscipline))
                .toInt(),
        )
        return candidato to base
    }

    /**
     * Un giovane da mandare a giocare altrove, o null.
     *
     * ## Perche' un'AI dovrebbe farlo
     *
     * Perche' e' la mossa piu' normale del calcio e finora nessuna AI la faceva: sapevano
     * **rispondere** a un prestito ([answerLoan]) e non proporne uno. Un ragazzo con una
     * forbice di crescita larga che sta in fondo alla rosa non cresce, e chi lo tiene lo sa.
     *
     * E' anche l'unica mossa che apre un discorso invece di una transazione: arriva con un
     * messaggio, e chi la riceve puo' rispondere. «Il mio attaccante non gioca mai, lo
     * prendi in prestito?» e' precisamente la cosa che il proprietario ha chiesto il
     * 2026-08-24 quando ha detto che le AI non fanno mai il primo passo.
     *
     * Chi ha margine di crescita **e** sta sotto la mediana: se e' gia' forte lo si tiene,
     * se e' scarso e senza prospettive non interessa a nessuno.
     */
    fun playerToLoanOut(
        squad: List<Player>,
        config: LeagueConfig,
    ): Player? {
        if (squad.size <= config.setup.minSquadSize) return null
        val mediana = squad.map { it.overall }.sorted()[squad.size / 2]

        return squad
            .filterNot { it.isCustom }
            .filter { it.age <= config.rules.peakAgeStart }
            .filter { it.overall < mediana }
            // Deve avere qualcosa da guadagnarci: un prestito serve a far crescere, e chi
            // non ha piu' margine sta altrettanto bene in panchina qui.
            .filter { it.potentialMax > it.overall + MARGINE_UTILE }
            .maxByOrNull { it.potentialMax - it.overall }
    }

    /** Quanti punti di crescita rendono utile mandarlo a giocare. */
    private const val MARGINE_UTILE = 4

    /**
     * Accetta un prestito?
     *
     * ## Perche' non si riusa il valutatore degli scambi
     *
     * Perche' un prestito non e' un acquisto piccolo: e' **un giocatore che non diventa
     * tuo**. Alla scadenza torna indietro, quindi non ha senso pagarlo come si paga un
     * acquisto, e nemmeno rifiutarlo perche' "non conviene sul valore". La domanda giusta
     * e' una sola: mi rende adesso piu' di quello che ho, e riesco a pagarne l'affitto?
     */
    fun answerLoan(
        state: AiState,
        club: Club,
        squad: List<Player>,
        player: Player,
        matchDays: Int,
        feePerMatchDay: Int,
        config: LeagueConfig,
    ): Boolean {
        if (!config.market.loansEnabled) return false
        if (squad.size >= config.setup.maxSquadSize) return false

        val costo = matchDays * feePerMatchDay
        val tetto = StrictMath.round(
            club.availableCredits * MathX.lerp(0.35, 0.12, state.personality.budgetDiscipline),
        ).toInt()
        if (costo > tetto) return false

        // Deve **giocare**, non essere il migliore.
        //
        // ## Il difetto che questa riga chiude
        //
        // La regola era `player.overall > il migliore che ho in quel ruolo`. Sembrava
        // sensata e rifiutava quasi tutto: per prestare un centrocampista a un club serviva
        // che fosse piu' forte del suo titolare, cioe' esattamente il giocatore che nessuno
        // presta. Chiunque provasse a proporre un prestito si vedeva rispondere di no ogni
        // volta, e la funzione sembrava rotta invece che severa.
        //
        // Un prestito non e' un acquisto: torna indietro, non costa un cartellino, e serve
        // a **coprire un buco**. La domanda giusta non e' "e' il mio migliore" ma "entra
        // nei due che schiero in quel ruolo" — titolare o primo cambio.
        val nelRuolo = squad.filter { it.primaryPosition == player.primaryPosition }
            .map { it.overall }
            .sortedDescending()

        // Nessuno in quel ruolo: si accetta senza pensarci, e' un buco vero.
        val secondo = nelRuolo.getOrNull(1) ?: return true
        return player.overall > secondo
    }

    /** Accetta un'amichevole? Le stesse condizioni con cui la chiederebbe. */
    fun answerFriendly(
        state: AiState,
        squad: List<Player>,
        config: LeagueConfig,
        matchDaysUntilNextMatch: Int,
    ): Boolean = wantsFriendly(state, squad, config, matchDaysUntilNextMatch)

    /**
     * Cosa fare dei contratti in scadenza.
     *
     * ## Perche' serve
     *
     * Senza, le rose dei computer si svuotano da sole: i contratti scadono, i giocatori
     * diventano svincolati e l'AI li ricompra all'asta a prezzo pieno se le va bene, o
     * resta sotto il minimo se non le va. Nessuno lo vede succedere, e a meta' stagione ci
     * si ritrova a giocare contro squadre di dodici.
     *
     * ## La regola
     *
     * Si rinnova a chi vale **almeno quanto la meta' della rosa** e a chi serve per non
     * scendere sotto il minimo. Si lascia andare chi sta sotto la mediana **e** costa piu'
     * di quanto renda, ma solo se la rosa resta comunque legale.
     */
    fun squadHousekeeping(
        state: AiState,
        club: Club,
        squad: List<Player>,
        contracts: List<Contract>,
        config: LeagueConfig,
        today: MatchDay,
    ): List<SquadAction> {
        if (squad.isEmpty()) return emptyList()

        val inScadenza = ContractRules.expiringWithin(contracts, today, PREAVVISO)
        if (inScadenza.isEmpty()) return emptyList()

        val byId = squad.associateBy { it.id }
        val mediana = squad.map { it.overall }.sorted()[squad.size / 2]
        var rosaPrevista = squad.size
        var denaro = club.availableCredits
        val azioni = mutableListOf<SquadAction>()

        // Prima i migliori: se il denaro finisce, deve finire dopo aver tenuto chi conta.
        val ordinati = inScadenza
            .mapNotNull { contratto -> byId[contratto.playerId]?.let { contratto to it } }
            .sortedByDescending { (_, player) -> player.overall }

        for ((contratto, player) in ordinati) {
            val costo = ContractRules.renewalCost(contratto, config.economy)
            val serveDavvero = rosaPrevista <= config.setup.minSquadSize
            val eBravo = player.overall >= mediana

            when {
                (serveDavvero || eBravo) && costo <= denaro -> {
                    denaro -= costo
                    azioni += SquadAction.Rinnova(
                        player.id,
                        costo,
                        if (serveDavvero) "Serve per la rosa minima." else "Vale il rinnovo.",
                    )
                }

                // Sotto la mediana e la rosa regge senza: si saluta. Tenerlo costerebbe
                // uno stipendio per un posto in panchina che serve a un giovane.
                !eBravo && rosaPrevista > config.setup.minSquadSize -> {
                    rosaPrevista--
                    azioni += SquadAction.Svincola(player.id, "Non rientra nei piani.")
                }

                // Non ci sono i soldi. Non e' una scelta: e' un contratto che scade e
                // basta, e il tick lo fara' scadere per conto suo.
                else -> Unit
            }
        }

        return azioni
    }

    // ------------------------------------------------------------------------- interni

    /** Giornate di preavviso: le stesse con cui il giocatore apre il discorso sul rinnovo. */
    private const val PREAVVISO = 6

    private fun ruoloPiuScoperto(squad: List<Player>, config: LeagueConfig): Position? {
        if (squad.isEmpty()) return null

        return Position.entries
            .map { ruolo -> ruolo to scopertura(squad, ruolo, config) }
            .filter { (_, quanto) -> quanto >= SOGLIA_BISOGNO }
            .maxByOrNull { (_, quanto) -> quanto }
            ?.first
    }

    /** Quanto manca in questo ruolo: 1 vuol dire "a posto", sopra vuol dire "scoperto". */
    private fun scopertura(squad: List<Player>, ruolo: Position, config: LeagueConfig): Double {
        val minimo = minimoPer(ruolo, config)
        val quanti = quantiIn(squad, ruolo)
        return if (quanti >= minimo) 1.0 else minimo.toDouble() / quanti.coerceAtLeast(1)
    }

    private fun quantiIn(squad: List<Player>, ruolo: Position): Int =
        squad.count { it.primaryPosition == ruolo }

    /**
     * Quanti ne servono per ruolo: un titolare e un cambio.
     *
     * Uguale per tutti i ruoli, portiere compreso. Verrebbe voglia di distinguere — piu'
     * centrocampisti, meno portieri — ma il conto vero lo fa gia' `AiManager.needFactor`
     * sulla formazione scelta; qui serve solo a sapere **chi avanza**, e per quella
     * domanda "ne ho piu' di due" e' una risposta buona quanto un modulo intero.
     */
    private fun minimoPer(ruolo: Position, config: LeagueConfig): Int = 2
}
