package dev.mfoot.core.match

import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Zone

enum class Side { CASA, OSPITE;

    fun other(): Side = if (this == CASA) OSPITE else CASA
}

/**
 * Tipo di evento e sua pericolosita' di base.
 *
 * La **pericolosita' 0-100** e' il canale con cui il motore dice all'interfaccia quanto
 * far rumore. Serve a ottenere i "90 minuti interi con highlight irregolari": la
 * maggioranza degli eventi resta rumore di fondo, e ogni tanto qualcosa buca lo schermo.
 *
 * L'irregolarita' non e' programmata da nessuna parte: nasce dal fatto che le catene di
 * possesso hanno lunghezza variabile. Capitano quindici minuti di nulla seguiti da tre
 * occasioni in due minuti, esattamente come in una partita vera.
 */
enum class MatchEventType(val baseDanger: Int, val label: String) {
    INIZIO(50, "Inizio"),
    AVANZAMENTO(8, "Avanzamento"),
    PALLA_PERSA(14, "Palla persa"),
    CONTRASTO(18, "Contrasto"),
    FALLO(28, "Fallo"),
    ANGOLO(40, "Angolo"),

    // ------------------------------------------------------------------ i duelli
    //
    // Sei tipi nuovi, tutti a pericolosita' bassa **di proposito**: sono il tessuto della
    // partita, non l'highlight. Servono a una cosa sola, che il motore vecchio non poteva
    // dare — dire due nomi invece di uno. «Rossi porta avanti l'azione» era tutto quello
    // che si poteva scrivere quando l'esito lo decideva la media di due reparti: non
    // esisteva nessun avversario da nominare, perche' non c'era nessun avversario.

    /** «Rossi salta Bianchi sulla fascia» */
    DRIBBLING_RIUSCITO(16, "Dribbling"),

    /** «Bianchi lo chiude in scivolata» */
    DRIBBLING_FALLITO(14, "Dribbling fallito"),

    /** «Rossi brucia Bianchi in velocita'» */
    SCATTO(15, "Scatto"),

    /** «Verdi anticipa di testa» */
    ANTICIPO(16, "Anticipo"),

    /** «Neri apre per Rossi dentro l'area» */
    PASSAGGIO_FILTRANTE(20, "Passaggio filtrante"),

    /** «Cross di Rossi, testa di Verdi» */
    CROSS(24, "Cross"),

    /**
     * Fuorigioco: non esisteva, e nel calcio vero capita quattro volte a partita.
     *
     * Mancava del tutto — nemmeno come voce nelle statistiche — ed e una delle cose che
     * si contano guardando: una squadra che ne prende otto sta giocando alta e sbagliata.
     */
    FUORIGIOCO(22, "Fuorigioco"),

    /**
     * Tiro murato: un quarto delle conclusioni finisce addosso a un difensore.
     *
     * Contano come tiri e non arrivano al portiere. Senza, ogni conclusione era o gol o
     * parata o fuori, e il portiere risultava impegnato il doppio del vero.
     */
    TIRO_MURATO(30, "Tiro murato"),
    TIRO_FUORI(48, "Tiro fuori"),
    PARATA(62, "Parata"),
    PALO(76, "Palo"),
    GOL(97, "Gol"),
    AMMONIZIONE(46, "Ammonizione"),
    ESPULSIONE(88, "Espulsione"),
    RIGORE_ASSEGNATO(86, "Rigore"),
    RIGORE_SEGNATO(98, "Rigore segnato"),
    RIGORE_SBAGLIATO(84, "Rigore sbagliato"),
    SOSTITUZIONE(32, "Sostituzione"),
    CAMBIO_TATTICA(24, "Cambio tattica"),
    INFORTUNIO(74, "Infortunio"),
    INTERVALLO(50, "Intervallo"),
    FINE(70, "Fine partita");
}

/**
 * Un evento della timeline.
 *
 * La partita viene simulata **una volta sola dal server**, che salva questa lista. I
 * client la rileggono e la riproducono con un timer locale: nessun polling, nessun
 * costo durante i novanta minuti, e chi apre l'app al sessantesimo salta direttamente
 * al sessantesimo perche' e' gia' tutto li'.
 */
data class MatchEvent(
    val minute: Int,
    val type: MatchEventType,
    val side: Side,
    val danger: Int,
    val zone: Zone? = null,
    val player: PlayerId? = null,
    /** Assistman, portiere che para, giocatore che entra: dipende dal tipo. */
    val secondaryPlayer: PlayerId? = null,
    val description: String = "",
    val homeGoals: Int = 0,
    val awayGoals: Int = 0,
) {
    /** Livello di reazione richiesto all'interfaccia. */
    val tier: DangerTier get() = DangerTier.of(danger)
}

/**
 * Le quattro soglie con cui l'interfaccia decide quanto interrompere.
 *
 * Corrispondono a quattro trattamenti visivi diversi: dal semplice spostamento della
 * palla fino all'animazione a schermo pieno con notifica push.
 */
enum class DangerTier(val range: IntRange, val label: String) {
    AMBIENTE(0..20, "Ambiente"),
    NOTEVOLE(21..50, "Notevole"),
    OCCASIONE(51..80, "Occasione"),
    DECISIVO(81..100, "Decisivo");

    companion object {
        fun of(danger: Int): DangerTier =
            entries.firstOrNull { danger in it.range } ?: DECISIVO
    }
}

/**
 * Statistiche individuali di una partita.
 *
 * Sono l'input diretto del sistema di crescita: gol, assist, parate e voto sono
 * esattamente cio' che fa guadagnare esperienza. Emergono da sole dalla simulazione,
 * senza bisogno di modellarle a parte.
 */
data class PlayerMatchStats(
    val playerId: PlayerId,
    val minutesPlayed: Int = 0,
    val goals: Int = 0,
    val assists: Int = 0,
    val shots: Int = 0,
    val shotsOnTarget: Int = 0,
    val saves: Int = 0,
    val goalsConceded: Int = 0,
    val tackles: Int = 0,
    val keyActions: Int = 0,
    val fouls: Int = 0,
    val yellowCards: Int = 0,
    val redCards: Int = 0,
    val staminaSpent: Int = 0,
    val injured: Boolean = false,

    /**
     * Le sei che raccontano cosa ha fatto davvero, non solo cosa e' finito in porta.
     *
     * Prima di queste, di un difensore centrale il tabellino diceva soltanto quanti
     * cartellini aveva preso: un grande centrale e un centrale scarso producevano lo
     * stesso identico foglio. Sono anche il modo in cui si legge la taratura di ogni
     * singola contesa — la pendenza del dribbling si vede in [dribblesCompleted] a
     * partita, non nei gol.
     *
     * Viaggiano dentro `match_results.player_stats`, che e' gia' `jsonb`: un campo in piu'
     * nel JSON non e' una colonna in piu'. Non si toccano le colonne di `appearances`, che
     * l'app legge con una `select` a lista esplicita — e PostgREST, per una colonna che
     * non c'e', rifiuta l'intera query.
     */
    val duelsWon: Int = 0,
    val duelsLost: Int = 0,
    /** Dribbling riusciti, e quanti ne ha provati: due campi perche' il tasso serve. */
    val dribblesCompleted: Int = 0,
    val dribblesAttempted: Int = 0,
    /** Quante volte **lui** e' stato saltato. */
    val dribblesSuffered: Int = 0,
    val passesCompleted: Int = 0,
    val passesAttempted: Int = 0,
) {
    val started: Boolean get() = minutesPlayed > 0

    /** Quanti duelli ha giocato in tutto. Zero se la partita e' del motore vecchio. */
    val duels: Int get() = duelsWon + duelsLost

    /** Percentuale di duelli vinti, o null se non ne ha giocato nessuno. */
    val duelSuccess: Double? get() = if (duels == 0) null else duelsWon.toDouble() / duels

    /** Precisione nei passaggi, o null se non ne ha tentato nessuno. */
    val passAccuracy: Double?
        get() = if (passesAttempted == 0) null else passesCompleted.toDouble() / passesAttempted

    /**
     * Voto 1-10.
     *
     * Parte da 6 e si muove sui contributi concreti. Un portiere viene giudicato sulle
     * parate e sui gol subiti, un giocatore di movimento su gol, assist e contrasti.
     * Chi gioca pochi minuti non puo' prendere voti estremi in nessuna direzione.
     */
    fun rating(isGoalkeeper: Boolean): Double {
        if (minutesPlayed == 0) return 0.0

        var rating = 6.0
        rating += goals * 1.35
        rating += assists * 0.85
        rating += keyActions * 0.16
        rating += tackles * 0.07
        rating -= fouls * 0.06

        // I duelli. Un centrale che ne vince dodici deve prendere piu' di 6, e prima non
        // poteva: l'unica voce che lo riguardava era `tackles * 0.07`. Con il motore
        // vecchio questi contatori sono tutti a zero e il voto resta identico a prima.
        rating += duelsWon * 0.05
        rating -= duelsLost * 0.035
        rating += dribblesCompleted * 0.10
        rating -= dribblesSuffered * 0.08
        if (passesAttempted >= 5) {
            // Sopra o sotto l'80%, che e' la precisione media di chi gioca a calcio.
            rating += (passesCompleted.toDouble() / passesAttempted - 0.80) * 2.0
        }
        rating -= yellowCards * 0.35
        rating -= redCards * 1.6

        if (isGoalkeeper) {
            rating += saves * 0.32
            rating -= goalsConceded * 0.45
        }

        // Chi entra all'ottantesimo non puo' prendere 9 ne' 3.
        val exposure = (minutesPlayed / 90.0).coerceIn(0.0, 1.0)
        return (6.0 + (rating - 6.0) * (0.45 + 0.55 * exposure)).coerceIn(1.0, 10.0)
    }
}

/**
 * Il risultato completo di una partita.
 *
 * Contiene tutto quello che serve al server per aggiornare il mondo e al client per
 * riprodurre la partita: non richiede di rieseguire la simulazione.
 */
data class MatchResult(
    val homeGoals: Int,
    val awayGoals: Int,
    val events: List<MatchEvent>,
    val stats: Map<PlayerId, PlayerMatchStats>,
    val homePossession: Double,
    val seed: Long,
    val homeShots: Int = 0,
    val awayShots: Int = 0,
    val homeXg: Double = 0.0,
    val awayXg: Double = 0.0,
) {
    val awayPossession: Double get() = 1.0 - homePossession

    val scoreline: String get() = "$homeGoals-$awayGoals"

    val winner: Side? get() = when {
        homeGoals > awayGoals -> Side.CASA
        awayGoals > homeGoals -> Side.OSPITE
        else -> null
    }

    val isDraw: Boolean get() = homeGoals == awayGoals

    /** Solo gli eventi che l'interfaccia deve mostrare come highlight. */
    fun highlights(minimumDanger: Int = 51): List<MatchEvent> =
        events.filter { it.danger >= minimumDanger }

    fun goals(): List<MatchEvent> =
        events.filter { it.type == MatchEventType.GOL || it.type == MatchEventType.RIGORE_SEGNATO }
}
