package dev.mfoot.core.objectives

/**
 * Cosa chiede la societa' a chi allena.
 *
 * ## Perche' un elenco chiuso e non un testo libero
 *
 * Perche' un obiettivo che nessuno puo' verificare non e' un obiettivo, e' un buon
 * proposito. «Fai bene» non si paga: qualcuno dovrebbe decidere a mano se e' stato
 * raggiunto, e in una lega fra amici quel qualcuno e' l'amministratore, cioe' uno dei
 * concorrenti. Ognuna di queste voci si risolve con un confronto fra numeri che il
 * database ha gia'.
 */
enum class ObjectiveKind(val label: String) {

    /** Finire primi nella propria divisione. */
    VINCI_LA_DIVISIONE("Vinci la divisione"),

    /** Finire entro una certa posizione. Il classico «salvezza tranquilla». */
    ARRIVA_ENTRO_IL("Arriva entro il posto"),

    /** Non retrocedere, per un numero di stagioni di fila. */
    NON_RETROCEDERE("Non retrocedere"),

    /** Salire di divisione. Ha senso solo se sopra c'e' qualcosa. */
    SALI_DI_DIVISIONE("Sali di divisione"),

    /** Vincere una competizione a eliminazione. */
    VINCI_LA_COPPA("Vinci la coppa"),

    /** Portare un giocatore qualsiasi della rosa a un certo overall. */
    PORTA_UN_GIOCATORE_A("Porta un giocatore a"),

    /**
     * Portare **il proprio** giocatore a un certo overall.
     *
     * E' l'obiettivo che parla del cuore del gioco: il custom nasce debole di proposito, e
     * farlo crescere e' la scommessa lunga di ogni lega.
     */
    FAI_CRESCERE_IL_TUO("Porta il tuo giocatore a"),

    /**
     * Far scendere in campo in prima squadra un certo numero di ragazzi.
     *
     * «Ragazzo» e' chi sta sotto l'eta' massima della Primavera scritta nel regolamento, e
     * «far giocare» vuol dire almeno un minuto: tenerne tre in panchina tutta la stagione
     * e' esattamente la scorciatoia che questo obiettivo esiste per non pagare.
     */
    LANCIA_DALLA_PRIMAVERA("Fai giocare i giovani"),
}

/** Com'e' finita. */
enum class ObjectiveStatus {
    /** Non ancora deciso: la stagione non e' finita, o le stagioni richieste non sono passate. */
    IN_CORSO,
    RAGGIUNTO,
    FALLITO,
}

/**
 * Un obiettivo assegnato a un club, con il premio che vale.
 *
 * ## Il premio si paga solo se si raggiunge
 *
 * Non esistono premi parziali, ed e' una scelta. Un obiettivo che paga meta' se arrivi
 * vicino e' un obiettivo che non cambia nessuna decisione: si fa la stagione che si sarebbe
 * fatta comunque e si incassa quello che capita. Tutto o niente rende costoso il rischio —
 * vendere il centravanti a gennaio, mandare in campo il diciottenne — che e' esattamente
 * cio' che un obiettivo dovrebbe far pesare.
 *
 * [seasons] vale solo per gli obiettivi che durano piu' di una stagione, come «non
 * retrocedere per due anni»: per tutti gli altri e' uno.
 */
data class Objective(
    val kind: ObjectiveKind,
    /** Il numero che serve: la posizione, l'overall, quanti ragazzi. Ignorato dove non serve. */
    val target: Int = 0,
    /** Il premio in crediti. Zero e' legittimo: un obiettivo d'onore. */
    val reward: Int = 0,
    /** Per quante stagioni di fila deve valere. Uno per quasi tutti. */
    val seasons: Int = 1,
) {

    init {
        require(seasons >= 1) { "un obiettivo dura almeno una stagione, ne sono state chieste $seasons" }
        require(reward >= 0) { "un premio non puo' essere negativo" }
    }

    /**
     * L'obiettivo scritto come lo si legge nell'app.
     *
     * Sta qui e non nella schermata perche' lo usano in due — l'app e il riepilogo che il
     * tick manda su Telegram — e due frasi diverse per la stessa regola sono il modo piu'
     * rapido di far credere che siano due regole.
     */
    val descrizione: String
        get() = when (kind) {
            ObjectiveKind.VINCI_LA_DIVISIONE -> "Vinci il campionato"
            ObjectiveKind.ARRIVA_ENTRO_IL -> "Arriva almeno ${target}º"
            ObjectiveKind.NON_RETROCEDERE ->
                if (seasons > 1) "Non retrocedere per $seasons stagioni" else "Non retrocedere"
            ObjectiveKind.SALI_DI_DIVISIONE -> "Sali di divisione"
            ObjectiveKind.VINCI_LA_COPPA -> "Vinci la coppa"
            ObjectiveKind.PORTA_UN_GIOCATORE_A -> "Porta un giocatore a $target di overall"
            ObjectiveKind.FAI_CRESCERE_IL_TUO -> "Porta il tuo giocatore a $target di overall"
            ObjectiveKind.LANCIA_DALLA_PRIMAVERA ->
                "Fai scendere in campo $target giovani in prima squadra"
        }

    /** Si puo' decidere prima della fine della stagione? */
    val decidibileSubito: Boolean
        get() = kind == ObjectiveKind.PORTA_UN_GIOCATORE_A ||
            kind == ObjectiveKind.FAI_CRESCERE_IL_TUO ||
            kind == ObjectiveKind.LANCIA_DALLA_PRIMAVERA
}

/**
 * Com'e' andata una stagione, per un club.
 *
 * Sono i numeri che servono a giudicare gli obiettivi e nient'altro: non e' un riassunto
 * della stagione, e' il verbale che l'arbitro degli obiettivi deve leggere. Metterci dentro
 * gol fatti e subiti «che magari servono» vorrebbe dire che qualcuno prima o poi ci
 * scriverebbe un obiettivo sopra senza sapere se quei numeri sono affidabili.
 */
data class ClubSeason(
    /** Posizione finale nella propria divisione. 1 e' il primo. */
    val position: Int,
    /** Quante squadre c'erano in quella divisione. */
    val teamsInDivision: Int,
    /** Il livello di divisione **all'inizio** della stagione. 1 e' la massima. */
    val divisionLevel: Int,
    val promoted: Boolean = false,
    val relegated: Boolean = false,
    val cupWon: Boolean = false,
    /** Il miglior overall della rosa a fine stagione. */
    val bestOverall: Int = 0,
    /** L'overall del giocatore creato dal proprietario, se ce l'ha. */
    val customOverall: Int = 0,
    /** Quanti ragazzi arrivati dalla Primavera hanno giocato in prima squadra. */
    val youthPlayed: Int = 0,
    /** La stagione e' finita davvero? Un verdetto su una stagione a meta' non vale. */
    val finished: Boolean = true,
)

/**
 * Decide se un obiettivo e' stato raggiunto.
 *
 * ## Perche' prende una lista di stagioni e non una sola
 *
 * Perche' «non retrocedere per due anni» non e' giudicabile guardando un anno, e perche'
 * deve poter dire **fallito** appena la retrocessione avviene, senza aspettare la seconda
 * stagione. Un obiettivo pluriennale che resta in sospeso dopo essere gia' fallito e' una
 * promessa che il gioco non mantiene: il proprietario continua a giocare per un premio che
 * non prendera' mai.
 */
object ObjectiveEngine {

    /**
     * Il verdetto, date le stagioni trascorse **da quando l'obiettivo e' stato assegnato**,
     * dalla piu' vecchia alla piu' recente.
     */
    fun status(objective: Objective, seasons: List<ClubSeason>): ObjectiveStatus {
        if (seasons.isEmpty()) return ObjectiveStatus.IN_CORSO

        return when (objective.kind) {
            // Quelli che si possono decidere in qualsiasi momento guardano l'ultima
            // fotografia disponibile, finita o no: un giocatore arrivato a 90 ci e'
            // arrivato, e far aspettare la fine della stagione per dirlo sarebbe solo
            // burocrazia.
            ObjectiveKind.PORTA_UN_GIOCATORE_A ->
                raggiuntoSe(seasons.any { it.bestOverall >= objective.target }, seasons)

            ObjectiveKind.FAI_CRESCERE_IL_TUO ->
                raggiuntoSe(seasons.any { it.customOverall >= objective.target }, seasons)

            ObjectiveKind.LANCIA_DALLA_PRIMAVERA ->
                raggiuntoSe(seasons.any { it.youthPlayed >= objective.target }, seasons)

            // Non retrocedere e' l'unico che fallisce **prima** della scadenza: appena
            // succede, e' successo.
            ObjectiveKind.NON_RETROCEDERE -> when {
                seasons.any { it.relegated } -> ObjectiveStatus.FALLITO
                seasons.count { it.finished } >= objective.seasons -> ObjectiveStatus.RAGGIUNTO
                else -> ObjectiveStatus.IN_CORSO
            }

            ObjectiveKind.VINCI_LA_DIVISIONE -> suStagioneChiusa(seasons) { it.position == 1 }
            ObjectiveKind.ARRIVA_ENTRO_IL -> suStagioneChiusa(seasons) { it.position <= objective.target }
            ObjectiveKind.SALI_DI_DIVISIONE -> suStagioneChiusa(seasons) { it.promoted }
            ObjectiveKind.VINCI_LA_COPPA -> suStagioneChiusa(seasons) { it.cupWon }
        }
    }

    /**
     * Il premio da pagare: il premio pieno se raggiunto, zero altrimenti.
     *
     * Esiste come funzione invece che come `if` sparso nelle schermate perche' «zero se non
     * ce la fai» e' una **regola**, ed e' la regola che rende l'obiettivo una scommessa.
     */
    fun reward(objective: Objective, status: ObjectiveStatus): Int =
        if (status == ObjectiveStatus.RAGGIUNTO) objective.reward else 0

    private fun raggiuntoSe(condizione: Boolean, seasons: List<ClubSeason>): ObjectiveStatus = when {
        condizione -> ObjectiveStatus.RAGGIUNTO
        // Non ancora, ma c'e' ancora tempo: si boccia solo quando le stagioni sono finite.
        seasons.count { it.finished } == 0 -> ObjectiveStatus.IN_CORSO
        else -> ObjectiveStatus.FALLITO
    }

    private fun suStagioneChiusa(
        seasons: List<ClubSeason>,
        condizione: (ClubSeason) -> Boolean,
    ): ObjectiveStatus {
        val chiuse = seasons.filter { it.finished }
        return when {
            chiuse.isEmpty() -> ObjectiveStatus.IN_CORSO
            chiuse.any(condizione) -> ObjectiveStatus.RAGGIUNTO
            else -> ObjectiveStatus.FALLITO
        }
    }
}
