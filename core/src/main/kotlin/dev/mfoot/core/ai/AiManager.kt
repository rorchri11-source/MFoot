package dev.mfoot.core.ai

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.market.Auction
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.Lineup
import dev.mfoot.core.match.LineupSlot
import dev.mfoot.core.model.Club
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position
import dev.mfoot.core.rng.MathX
import dev.mfoot.core.world.PotentialEstimator

/** Quanto interessa un giocatore a una AI, e fino a dove si spingerebbe. */
data class TargetAppeal(
    val player: Player,
    /** 0 = non interessa, 1 = lo vuole moltissimo. */
    val appeal: Double,
    /** Tetto massimo in crediti. Non viene superato in nessuna circostanza. */
    val ceiling: Int,
    val reason: String,
) {
    val isInterested: Boolean get() = appeal > 0.15 && ceiling >= 1
}

/**
 * Le decisioni di un club AI.
 *
 * ## Le regole che l'utente ha chiesto
 *
 * L'AI deve essere competitiva — puo' benissimo intromettersi in un duello fra due
 * amici — ma non deve **mai** comportarsi come uno sciame. Le regole anti-sciame sono
 * distribuite fra questa classe e [AiScheduler]:
 *
 * 1. **Risvegli scaglionati** ([AiScheduler]) — un'AI che dorme non sa che l'asta esiste.
 * 2. **Si scansano da sole** — l'appetibilita' cala per ogni AI gia' impegnata sullo
 *    stesso giocatore: la seconda ci pensa, la terza quasi mai, la quarta mai.
 * 3. **Tetto di azioni giornaliere** ([AiScheduler]).
 * 4. **Tetto di notifiche** — imposto dal server, non da qui.
 * 5. **Non insistono** — dopo un rifiuto stanno ferme per N giornate.
 * 6. **Non barano mai** — valutano sulla forbice stimata, non sui valori veri.
 *
 * ## Perche' tutto e' in percentuale del budget
 *
 * L'AI non conosce nessun numero assoluto. Ragiona sempre in frazione del proprio
 * disponibile, quindi si adatta da sola a una lega povera come a una ricca, senza che
 * l'admin debba tarare niente.
 */
object AiManager {

    /**
     * Quanto interessa questo giocatore, e fino a quanto si spingerebbe.
     *
     * @param competingAi quante altre AI stanno gia' puntando questo giocatore
     */
    fun evaluate(
        state: AiState,
        club: Club,
        squad: List<Player>,
        player: Player,
        config: LeagueConfig,
        competingAi: Int = 0,
    ): TargetAppeal {
        val personality = state.personality

        // Regola 6: la stima usa la stessa incertezza che ha un umano. Un'AI che
        // leggesse i potenziali veri comprerebbe sempre i giovani giusti e sembrerebbe
        // truccata.
        val estimate = PotentialEstimator.estimate(
            player = player,
            observerId = club.id.value,
            minutesObserved = 0,
            scoutAccuracy = 0.0,
        )
        val estimatedValue = Valuation.estimatedValue(player, estimate, config)

        var appeal = qualityAppeal(player, estimate)
        appeal *= needFactor(squad, player.primaryPosition, config)
        appeal *= youthFit(personality, player, config)
        appeal += personality.obsessionBonusFor(player.primaryPosition)
        if (personality.preferredNationality == player.nationality &&
            AiObsession.CONNAZIONALI in personality.obsessions
        ) {
            appeal += 0.2
        }

        appeal = appeal.coerceIn(0.0, 1.5)

        // Sotto la rosa minima, comprare non e' una preferenza: e' un obbligo.
        //
        // Il moltiplicatore in [needFactor] diceva gia' di volerlo, e non bastava. Misurato
        // su otto club: dopo dieci acquisti avevano preso i giocatori forti, il listino
        // rimasto non interessava piu' nessuno, e si fermavano con **sessanta milioni in
        // cassa** e otto caselle vuote. Non a corto di soldi — a corto di interesse. E una
        // squadra sotto il minimo non scende in campo, quindi il campionato non partiva.
        //
        // Il pavimento vale solo finche' manca qualcuno. Sopra il minimo l'AI torna
        // liberissima di non voler comprare, che e' il comportamento giusto: e' li' che
        // "non mi interessa" e' una decisione invece che una resa.
        if (squad.size < config.setup.minSquadSize) {
            appeal = maxOf(appeal, OBBLIGO_DI_ROSA)
        }

        // Un portiere, quando non se ne ha nessuno, batte qualunque altra cosa.
        //
        // Sta **dopo** il limite superiore e non fra i moltiplicatori perche' li' non
        // funzionava: il gradimento e' tagliato a 1,5, e sia il fuoriclasse di movimento sia
        // il portiere ci arrivavano lo stesso: il portiere non passava mai davanti. Misurato
        // su otto club: uno arrivava a diciotto giocatori senza nessuno fra i pali.
        //
        // Non e' un gusto, e' un requisito. Con tre difensori invece di quattro si gioca;
        // senza portiere ci va un giocatore di movimento, che vale quaranta punti di malus,
        // e la partita e' persa prima del fischio d'inizio.
        val senzaPortiere = squad.none { it.primaryPosition.isGoalkeeper }
        if (senzaPortiere && player.primaryPosition.isGoalkeeper) {
            appeal = PORTIERE_MANCANTE
        }

        // Regola 2 dell'anti-sciame, e va applicata **per ultima**: piu' AI sono gia' sopra
        // a questo giocatore, meno interessa.
        //
        // L'ordine non e' un dettaglio. Messa prima dei due pavimenti qui sopra, la
        // penalita' veniva cancellata: durante l'allestimento ogni club e' sotto il minimo,
        // quindi ogni club restava interessato a tutto, e venti squadre si sarebbero buttate
        // sullo stesso fuoriclasse — esattamente lo sciame che tutte queste regole esistono
        // per impedire.
        //
        // Messa dopo, le due cose convivono: chi ha bisogno di giocatori resta interessato
        // **a qualcosa**, ma non a cio' su cui c'e' gia' la fila. Che e' come si comporta
        // chi fa mercato sul serio.
        appeal *= crowdingFactor(competingAi, config)

        val ceiling = ceilingFor(personality, club, estimatedValue, appeal, config, squad.size)

        return TargetAppeal(
            player = player,
            appeal = appeal,
            ceiling = ceiling,
            reason = reasonFor(personality, player, squad, competingAi),
        )
    }

    /**
     * Fino a quanto si spinge questa AI.
     *
     * Il tetto viene calcolato **una volta sola** all'inizio e non viene mai superato:
     * l'AI non si fa prendere dalla foga. E' quello che permette a un umano di battere
     * un'AI semplicemente valutando il giocatore di piu' di quanto lo valuti lei.
     *
     * ## Il tetto sa quante caselle restano
     *
     * Era una frazione del disponibile e basta — fra il 18% e il 45% a seconda del
     * carattere — e non sapeva niente di quanti giocatori mancassero alla rosa. In una lega
     * vera ha prodotto otto club con la rosa vuota che tenevano aste su giocatori da 86,
     * uno con **42 milioni impegnati su cento e un solo giocatore in squadra**. Nessuna
     * partita si poteva giocare, perche' nessuno arrivava al minimo di rosa.
     *
     * Il conto giusto parte da li': con cento milioni e diciotto caselle, la media per
     * casella e' 5,5 milioni. Lo **sforo** dice quanto ci si permette di superarla su un
     * singolo colpo, e resta la leva del carattere: chi ha poca disciplina arriva a
     * quattro volte la media, chi ne ha molta si ferma a due. Nessun carattere autorizza a
     * rovinarsi.
     *
     * Man mano che la rosa si riempie le caselle calano e la media sale da sola, quindi il
     * fuoriclasse si puo' ancora prendere — piu' avanti, quando il grosso e' a posto. E'
     * la forma di rosa che si voleva: un paio di stelle e tanti onesti.
     *
     * ## La riserva
     *
     * Sotto al tetto c'e' un pavimento: quello che serve a comprare **almeno** le caselle
     * che restano al prezzo minimo possibile. Senza, spendendo sempre il massimo si arriva
     * a diciassette giocatori e zero crediti, che e' lo stesso identico difetto di prima
     * spostato di qualche acquisto.
     *
     * ## Chi ha gia' la rosa a posto
     *
     * Torna alle vecchie regole. Sopra il minimo non c'e' nessun obbligo da proteggere, e
     * spendere molto su un rinforzo e' una scelta legittima invece che un suicidio. Il
     * confine e' la rosa, come dappertutto nel gioco.
     */
    fun ceilingFor(
        personality: AiPersonality,
        club: Club,
        estimatedValue: Int,
        appeal: Double,
        config: LeagueConfig,
        squadSize: Int = Int.MAX_VALUE,
    ): Int {
        val available = club.availableCredits.coerceAtLeast(0)
        if (available <= 0) return 0

        val desired = estimatedValue * (0.75 + personality.marketAggression * 0.55) * appeal

        val mancanti = config.setup.minSquadSize - squadSize
        val hardCap = if (mancanti <= 0) {
            // Rosa a posto: vale il tetto per carattere di sempre.
            available * MathX.lerp(0.45, 0.18, personality.budgetDiscipline)
        } else {
            val mediaPerCasella = available.toDouble() / mancanti
            val sforo = MathX.lerp(SFORO_MAX, SFORO_MIN, personality.budgetDiscipline)
            val riserva = (mancanti - 1).toDouble() * config.market.minimumRaise

            // La quota equa e' sempre spendibile, e il `maxOf` che la protegge non e' una
            // pezza: e' l'unica cosa che impedisce al club di bloccarsi.
            //
            // Spendendo esattamente la media per casella, il disponibile e le caselle
            // calano insieme e **la media resta identica** — quindi si puo' ripetere fino
            // all'ultimo posto senza restare mai a secco. Senza questo pavimento, verso
            // fine mercato la riserva supera il disponibile, il tetto diventa zero e il club
            // smette di comprare del tutto: e' stato misurato, si fermavano a quattordici
            // giocatori con trecentomila in tasca e mille giocatori liberi sul mercato.
            maxOf(mediaPerCasella, minOf(mediaPerCasella * sforo, available - riserva))
        }

        return StrictMath.round(minOf(desired, hardCap)).toInt().coerceIn(0, available)
    }

    /**
     * Quante volte la media per casella si puo' spendere su un giocatore solo.
     *
     * Quattro per chi ha poca disciplina, due per chi ne ha tanta. Il valore centrale, tre,
     * e' quello misurato: con cento milioni e diciotto caselle fa un tetto di sedici e
     * mezzo sul primo acquisto — bastano per tre o quattro pezzi pregiati, e poi la media
     * cala e tocca riempire con giocatori onesti.
     */
    private const val SFORO_MAX = 4.0
    private const val SFORO_MIN = 2.0

    /**
     * Il gradimento minimo di un giocatore qualsiasi, quando la rosa non e' completa.
     *
     * Sta appena sopra la soglia di [TargetAppeal.isInterested], che e' 0,15: quanto basta
     * perche' un club a cui manca gente non possa mai rispondere "no grazie" a tutto il
     * listino. Piu' alto, e le AI pagherebbero i mediocri come se li volessero davvero.
     */
    private const val OBBLIGO_DI_ROSA = 0.2

    /**
     * Il gradimento di un portiere per chi non ne ha nessuno.
     *
     * Sopra il limite superiore di 1,5 di proposito: deve battere **qualunque** altro
     * giocatore, compreso il fuoriclasse assoluto del listino. Restando sotto il limite
     * pareggerebbe soltanto, e a parita' vince chi costa di piu' — cioe' mai il portiere.
     */
    private const val PORTIERE_MANCANTE = 1.8

    /**
     * L'offerta massima da dichiarare in un'asta, o null per passare.
     *
     * Restituisce sempre il proprio **tetto**, non un rilancio minimo: e' il modo
     * corretto di usare l'offerta massima automatica, e impedisce all'AI di partecipare
     * a una guerra di rilanci al centesimo.
     */
    fun decideBid(
        state: AiState,
        club: Club,
        auction: Auction,
        appeal: TargetAppeal,
        config: LeagueConfig,
    ): Int? {
        if (!appeal.isInterested) return null
        if (auction.id in state.abandonedTargets.map { it }) return null

        val currentPrice = auction.currentPrice(config.market)
        val minimum = currentPrice + config.market.minimumRaise

        // Regola: il tetto e' il tetto. Se il prezzo lo ha superato, si molla.
        if (minimum > appeal.ceiling) return null
        if (appeal.ceiling > club.availableCredits) return null

        return appeal.ceiling
    }

    /**
     * Dopo essere stata superata due volte sullo stesso giocatore, l'AI lascia perdere.
     *
     * Serve a evitare i duelli infiniti che riempiono di notifiche l'umano dall'altra
     * parte, ma anche a rendere l'AI un avversario battibile: chi insiste, vince.
     */
    fun abandonAfterOutbids(state: AiState, auctionId: Long, timesOutbid: Int): AiState =
        if (timesOutbid >= 2) {
            state.copy(abandonedTargets = state.abandonedTargets + auctionId)
        } else {
            state
        }

    // ---------------------------------------------------------------- formazione

    /**
     * Schiera la formazione migliore **rispettando la stamina**.
     *
     * Con due partite al giorno un'AI che schiera sempre gli stessi undici si distrugge
     * la rosa da sola e diventa un avversario ridicolo a meta' stagione. Turna come
     * dovrebbe fare un umano.
     */
    fun chooseLineup(
        clubId: ClubId,
        name: String,
        squad: List<Player>,
        formation: Formation = Formation.F_4_3_3,
        mustStart: Player? = null,
    ): Lineup? {
        if (squad.size < Formation.PLAYERS_ON_PITCH) return null

        val available = squad.toMutableList()
        val slots = mutableListOf<LineupSlot>()

        // Il giocatore obbligatorio (il custom del proprietario) va sistemato per primo,
        // nel ruolo in cui rende di piu' fra quelli previsti dal modulo.
        mustStart?.let { forced ->
            val position = formation.positions
                .maxByOrNull { forced.overallAt(it) } ?: forced.primaryPosition
            slots += LineupSlot(forced, position)
            available.remove(forced)
        }

        val remainingPositions = formation.positions.toMutableList()
        slots.forEach { remainingPositions.remove(it.position) }

        for (position in remainingPositions) {
            val best = available.maxByOrNull { score(it, position) } ?: break
            slots += LineupSlot(best, position)
            available.remove(best)
        }

        if (slots.size < Formation.PLAYERS_ON_PITCH) return null

        return Lineup(
            formation = formation,
            slots = slots.sortedBy { formation.positions.indexOf(it.position) },
            bench = available.sortedByDescending { it.overall }.take(7),
        )
    }

    /**
     * Quanto vale schierare questo giocatore qui, adesso.
     *
     * La stanchezza pesa in modo non lineare: un giocatore a 40 di stamina non vale
     * "un po' meno", vale molto meno, perche' crollera' nel finale.
     */
    private fun score(player: Player, position: Position): Double {
        val ability = player.overallAt(position).toDouble()
        val freshness = MathX.remap(player.stamina.toDouble(), 30.0, 90.0, 0.55, 1.0)
        val moraleFactor = MathX.remap(player.morale.toDouble(), 0.0, 100.0, 0.90, 1.05)
        return ability * freshness * moraleFactor
    }

    // ------------------------------------------------------------------ interni

    /** Quanto e' forte e promettente, sulla base della sola stima. */
    private fun qualityAppeal(player: Player, estimate: IntRange): Double {
        val now = Valuation.overallScore(player.overall.toDouble())
        val potential = Valuation.overallScore(((estimate.first + estimate.last) / 2).toDouble())
        return (now * 0.55 + potential * 0.45).coerceIn(0.0, 1.0)
    }

    /**
     * Quanto serve un giocatore di quel ruolo.
     *
     * Un club senza portieri di riserva deve volere un portiere molto piu' di un
     * quarto attaccante, altrimenti costruisce rose assurde.
     */
    private fun needFactor(squad: List<Player>, position: Position, config: LeagueConfig): Double {
        val inRole = squad.count { it.primaryPosition == position }
        val squadSize = squad.size
        val minSize = config.setup.minSquadSize

        val roleNeed = when (inRole) {
            0 -> 1.6
            1 -> 1.25
            2 -> 1.0
            3 -> 0.7
            else -> 0.4
        }
        // Sotto la rosa minima si compra qualsiasi cosa: e' un obbligo, non una scelta.
        val sizeNeed = if (squadSize < minSize) 1.4 else 1.0

        return roleNeed * sizeNeed
    }

    /** Quanto questo giocatore corrisponde al gusto giovani/pronti del club. */
    private fun youthFit(
        personality: AiPersonality,
        player: Player,
        config: LeagueConfig,
    ): Double {
        val isYoung = player.age <= config.rules.peakAgeStart
        val isVeteran = player.age >= config.rules.declineAge
        return when {
            isYoung -> MathX.lerp(0.75, 1.30, personality.youthPreference)
            isVeteran -> MathX.lerp(1.05, 0.55, personality.youthPreference)
            else -> 1.0
        }
    }

    /**
     * Regola anti-sciame numero 2.
     *
     * L'appetibilita' cala per ogni AI gia' impegnata sullo stesso giocatore. Con la
     * penalita' di default, la seconda AI ci pensa, la terza quasi mai e la quarta mai:
     * il risultato naturale e' 1-3 AI per asta invece di venticinque.
     */
    fun crowdingFactor(competingAi: Int, config: LeagueConfig): Double {
        if (competingAi <= 0) return 1.0
        return MathX.pow(1.0 - config.ai.crowdingPenalty, competingAi.toDouble())
    }

    private fun reasonFor(
        personality: AiPersonality,
        player: Player,
        squad: List<Player>,
        competingAi: Int,
    ): String = when {
        competingAi >= 3 -> "Troppa concorrenza, lascia perdere"
        squad.none { it.primaryPosition == player.primaryPosition } ->
            "Manca completamente un ${player.primaryPosition.label.lowercase()}"
        personality.youthPreference > 0.7 && player.age <= 22 -> "Punta sui giovani"
        AiObsession.PORTIERE_FORTE in personality.obsessions && player.isGoalkeeper ->
            "Vuole un portiere di livello"
        else -> "Rinforzo di rosa"
    }
}
