package dev.mfoot.core.ai

import dev.mfoot.core.config.LeagueConfig

/** Le cose che un club gestito dal computer puo' fare quando si sveglia. */
enum class AiMove {
    /**
     * Comprare dal listino, a prezzo fisso e subito.
     *
     * Dal 2026-08-24 e' la prima mossa di un club a cui manca gente, e sostituisce la
     * fila di aste che rendeva il completamento di una rosa lungo settimane reali.
     */
    COMPRA_A_LISTINO,

    /**
     * Contestare l'acquisto di qualcun altro.
     *
     * Solo quando quel giocatore lo voleva davvero **ed** e' stato pagato troppo poco:
     * se contestassero tutto, si tornerebbe a fare aste ogni giorno.
     */
    CONTESTA,

    /** Mettere in vendita a listino chi non serve piu'. */
    METTI_A_LISTINO,

    /** Offrire crediti a un altro club per un suo giocatore. */
    OFFRI_CREDITI,

    /** Proporre in prestito un giovane che qui non gioca mai. */
    PROPONI_PRESTITO,

    /** Mettere all'asta uno svincolato che serve, o un proprio giocatore che non serve. */
    APRI_ASTA,

    /** Rilanciare su un'asta gia' aperta. */
    OFFRI,

    /**
     * Mettere all'asta un proprio giocatore che non serve.
     *
     * Distinta da [APRI_ASTA] perche' e' l'unica mossa che **crea offerta nuova** dopo che
     * gli svincolati sono finiti. Senza, il mercato muore il giorno in cui l'ultimo
     * senza contratto trova squadra, e per il resto della stagione non succede piu' niente.
     */
    METTI_IN_VENDITA,

    /** Rinnovi e svincoli. */
    GESTISCI_ROSA,

    PROPONI_SCAMBIO,
    CHIEDI_AMICHEVOLE,
}

/**
 * In che ordine un'AI prova le cose, e quante aste apre in un colpo solo.
 *
 * ## Perche' questo file esiste
 *
 * Perche' l'ordine era scritto dentro il tick, cosi':
 *
 * ```kotlin
 * val acted = tryBid(...) || tryOpenAuction(...)
 * ```
 *
 * Un `||` in corto circuito. Se esisteva **anche una sola** asta su cui valesse la pena
 * offrire, l'AI offriva e non apriva niente: sei slot d'asta liberi, nove caselle vuote in
 * rosa, e il risveglio finiva li'. Appena nasceva un'asta, tutti offrivano su quella —
 * poche aste con molti rilanci invece di molte aste parallele, che e' precisamente cio' che
 * si vedeva giocando.
 *
 * Il difetto e' sopravvissuto perche' **non era provabile**: viveva dentro una funzione che
 * ha bisogno di una connessione al database, e i test del mercato simulano le decisioni di
 * [AiManager] — dato un giocatore, quanto lo voglio — non il ciclo che le mette in fila.
 * Ogni decisione era giusta e l'insieme non funzionava.
 *
 * Qui e' una funzione pura, e c'e' una simulazione che la esercita.
 */
object AiTurn {

    /**
     * Cosa provare, in ordine.
     *
     * ## La regola
     *
     * **Rosa sotto il minimo: prima aprire, poi offrire.** Aprire un'asta crea offerta;
     * rilanciare si limita a contendersi quella che c'e' gia'. Quando tutti i club sono
     * corti, quello che manca al mercato e' l'offerta, e un'AI che rilancia invece di
     * aprire toglie a se stessa l'unica cosa che le serve.
     *
     * **Rosa a posto: prima offrire, poi aprire.** Rilanciare su un'asta che esiste costa
     * meno che crearne una, e a stagione in corso il mercato deve muoversi piano.
     *
     * Le iniziative — scambi, amichevoli, ordine in rosa — restano in fondo e solo a rosa
     * completa: un club che deve ancora arrivare a undici titolari non ha niente da
     * proporre a nessuno, perche' quello che gli avanza non avanza, gli manca.
     */
    fun order(squadSize: Int, config: LeagueConfig): List<AiMove> {
        // Dal 2026-08-24 il listino viene **prima di tutto** per chi ha la rosa corta:
        // comprare a prezzo fisso e' immediato, mentre un'asta costa un giro di tick per
        // aprirsi e un altro per chiudersi. Era la ragione per cui i club del computer
        // restavano fermi fra uno e nove giocatori dopo venti risvegli.
        val listinoPrima = config.market.instantBuyEnabled

        return if (squadSize < config.setup.minSquadSize) {
            listOfNotNull(
                AiMove.COMPRA_A_LISTINO.takeIf { listinoPrima },
                AiMove.APRI_ASTA,
                AiMove.OFFRI,
            )
        } else {
            listOfNotNull(
                // Contestare scade: la finestra dura dodici ore e non torna. Viene prima
                // di ogni altra cosa perche' e' l'unica mossa che ha una scadenza vera.
                AiMove.CONTESTA.takeIf { listinoPrima },
                AiMove.OFFRI,
                AiMove.COMPRA_A_LISTINO.takeIf { listinoPrima },
                AiMove.GESTISCI_ROSA,
                AiMove.PROPONI_SCAMBIO,
                AiMove.OFFRI_CREDITI.takeIf { listinoPrima },
                AiMove.PROPONI_PRESTITO,
                AiMove.APRI_ASTA,
                AiMove.METTI_A_LISTINO.takeIf { listinoPrima },
                AiMove.METTI_IN_VENDITA,
                AiMove.CHIEDI_AMICHEVOLE,
            )
        }
    }

    /**
     * Quante aste puo' aprire con i soldi che ha.
     *
     * ## Perche' non basta contare gli slot liberi
     *
     * Perche' aprire un'asta non impegna niente, ma vincerla costa. Un club con centomila
     * in cassa che apre sei aste da cinquantamila ne puo' vincere due: le altre quattro
     * hanno occupato uno slot, hanno tolto quattro giocatori dal listino per tutta la loro
     * durata, e poi sono andate deserte. Il mercato sembra pieno e non si muove niente.
     *
     * La simulazione del ritmo lo ha mostrato subito: due club su otto finivano con i
     * crediti sotto zero al primo giro con sei aste aperte insieme.
     */
    fun affordableAuctions(available: Int, tettoPerAsta: Int, slotLiberi: Int): Int {
        if (tettoPerAsta <= 0) return slotLiberi
        return minOf(slotLiberi, available / tettoPerAsta)
    }

    /**
     * Quante aste puo' aprire adesso, in questo risveglio.
     *
     * ## Perche' durante l'allestimento sono tutte insieme
     *
     * Perche' un'azione per risveglio, con un risveglio ogni due o tre minuti, vuol dire
     * venti minuti prima che un club abbia le sue sei aste aperte — e per allora le prime
     * sono gia' scadute. Il primo giro non arriva mai a regime, e il conto delle tre ondate
     * da un quarto d'ora resta sulla carta.
     *
     * ## Perche' non e' lo sciame
     *
     * Lo sciame che il progetto teme e' venti club che si buttano **sullo stesso
     * giocatore**, e la difesa e' la penalita' di affollamento sui rilanci: quella resta
     * intera e non c'entra niente con quante aste sono aperte. Sei aste di un club sono sei
     * ruoli scoperti, non sei offerte sullo stesso obiettivo.
     *
     * A regime torna una per volta: li' il mercato deve essere lento, e ogni asta e' una
     * notifica sul telefono di qualcuno.
     */
    fun auctionsToOpen(squadSize: Int, openAuctionsByMe: Int, config: LeagueConfig): Int {
        val allestimento = squadSize < config.setup.minSquadSize
        val tetto = if (allestimento) {
            config.market.initialParallelAuctionsPerClub
        } else {
            config.market.maxParallelAuctionsPerClub
        }

        val liberi = (tetto - openAuctionsByMe).coerceAtLeast(0)
        return if (allestimento) liberi else minOf(1, liberi)
    }

    /**
     * Puo' comprare adesso?
     *
     * Sotto il minimo e' un obbligo. Sopra e' permesso ma non dovuto: si compra solo per
     * **migliorare**, e la verifica che il candidato sia davvero un miglioramento la fa
     * [migliora]. Sopra il massimo non si compra affatto, perche' una rosa da trenta paga
     * trenta stipendi per schierarne undici.
     */
    fun canBuy(squadSize: Int, config: LeagueConfig): Boolean =
        squadSize < config.setup.maxSquadSize

    /**
     * Questo acquisto migliora davvero il reparto?
     *
     * A rosa completa la domanda non e' "quanto mi piace" ma "cambia qualcosa". Comprare
     * il quarto centrocampista da 68 quando se ne hanno gia' tre da 70 e' il modo in cui
     * una squadra spende tutto il bilancio senza diventare piu' forte di un punto.
     *
     * Sotto il minimo la domanda non si pone: qualunque giocatore e' meglio di una casella
     * vuota, perche' una rosa illegale non scende in campo.
     */
    fun migliora(
        squadSize: Int,
        overallCandidato: Int,
        miglioreNelRuolo: Int?,
        config: LeagueConfig,
    ): Boolean {
        if (squadSize < config.setup.minSquadSize) return true
        val daBattere = miglioreNelRuolo ?: return true
        return overallCandidato > daBattere
    }
}
