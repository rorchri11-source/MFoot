package dev.mfoot.core.objectives

import dev.mfoot.core.config.LeagueConfig

/**
 * La situazione di un club nel momento in cui gli si assegnano gli obiettivi.
 *
 * Tutto quello che serve per decidere **cosa chiedergli**, e nient'altro. La forza si
 * esprime come posizione nella graduatoria della propria divisione ([strengthRank]) e non
 * come overall medio, perche' un obiettivo e' sempre relativo: 78 di media e' una squadra
 * da titolo in Serie B e una da salvezza in Serie A, e chiedere la stessa cosa a entrambe
 * sarebbe chiedere due cose diverse.
 */
data class ClubStanding(
    /** 1 = la piu' forte della sua divisione. */
    val strengthRank: Int,
    val teamsInDivision: Int,
    /** 1 e' la massima divisione. */
    val divisionLevel: Int,
    val divisionCount: Int,
    /** L'overall del giocatore creato dal proprietario, 0 se non ce l'ha. */
    val customOverall: Int = 0,
    /** Il miglior overall della rosa. */
    val bestOverall: Int = 0,
    /** C'e' una coppa in calendario? Senza, l'obiettivo «vinci la coppa» sarebbe una beffa. */
    val hasCup: Boolean = false,
    /** Ha fondato la Primavera? */
    val hasYouth: Boolean = false,
)

/**
 * Chi decide cosa chiedere a ogni club, e quanto pagarlo.
 *
 * ## Perche' li assegna il programma e non l'amministratore
 *
 * Perche' l'amministratore e' uno dei concorrenti. Obiettivi scelti a mano da una persona
 * che gioca nella stessa lega sono un premio in crediti deciso da un avversario, e non c'e'
 * modo di renderli credibili — nemmeno quando sono onesti, che e' il caso quasi sempre.
 * Assegnati da una regola scritta, uguale per tutti e leggibile da tutti, il sospetto non
 * si pone.
 *
 * ## Tre obiettivi, e perche' proprio tre
 *
 * Uno **di classifica**, che e' quello per cui si gioca la domenica. Uno **di sviluppo**,
 * che si raggiunge facendo giocare i giovani invece dei pronti — cioe' facendo la cosa
 * che il resto del gioco rende costosa. Uno **lungo**, che dura piu' di una stagione e
 * serve a dare un motivo per non svendere tutto a giugno.
 *
 * Uno solo trasformerebbe la stagione in una scommessa secca. Cinque diventerebbero un
 * elenco che nessuno legge.
 *
 * ## I premi non sono numeri fissi
 *
 * Sono percentuali del budget di partenza, come tutto il resto dell'economia. Un premio da
 * "20.000" in una lega da centomila crediti e' un incentivo; nella stessa lega col budget
 * moltiplicato per dieci e' mancia. La configurazione decide le percentuali, e il valore
 * segue l'economia da solo.
 */
object ObjectiveBoard {

    /**
     * I tre obiettivi di un club per la stagione che comincia.
     *
     * Restituisce una lista vuota se gli obiettivi sono spenti nella configurazione: e'
     * piu' onesto di tre obiettivi che nessuno paghera' mai.
     */
    fun forClub(club: ClubStanding, config: LeagueConfig): List<Objective> {
        val c = config.objectives
        if (!c.enabled) return emptyList()

        val budget = config.economy.startingCredits
        fun premio(percento: Int) = (budget.toLong() * percento / 100).toInt().coerceAtLeast(0)

        val classifica = diClassifica(club, premio(c.leagueRewardPercent))

        return listOfNotNull(
            classifica,
            diSviluppo(club, premio(c.developmentRewardPercent)),
            diLungoTermine(club, classifica, premio(c.longTermRewardPercent), c.longTermSeasons),
        )
    }

    /**
     * Cosa si chiede in campionato, secondo quanto vale la squadra.
     *
     * Le soglie sono terzi: il primo terzo della graduatoria deve puntare a vincere, il
     * secondo a stare nella meta' buona, il terzo a salvarsi. Chiedere il titolo a
     * chiunque produrrebbe undici obiettivi falliti su dodici e un premio che nessuno
     * insegue; chiedere la salvezza a tutti pagherebbe per non fare niente.
     */
    private fun diClassifica(club: ClubStanding, premio: Int): Objective {
        val terzo = (club.teamsInDivision + 2) / 3
        val eForte = club.strengthRank <= terzo
        val eDebole = club.strengthRank > club.teamsInDivision - terzo

        return when {
            // Chi non e' in massima divisione ed e' forte punta a salire: vincere la Serie
            // B e non salire non esiste, ma «sali» dice cosa succede davvero.
            eForte && club.divisionLevel > 1 ->
                Objective(ObjectiveKind.SALI_DI_DIVISIONE, reward = premio)

            eForte -> Objective(ObjectiveKind.VINCI_LA_DIVISIONE, reward = premio)

            eDebole && club.divisionLevel < club.divisionCount ->
                Objective(ObjectiveKind.NON_RETROCEDERE, reward = premio)

            // Ultima divisione, o girone unico: sotto non c'e' niente da cui salvarsi, e
            // «non retrocedere» sarebbe un premio regalato. Si chiede di non finire in
            // fondo, che e' la stessa domanda dove la retrocessione non esiste.
            eDebole -> Objective(
                ObjectiveKind.ARRIVA_ENTRO_IL,
                target = (club.teamsInDivision - 2).coerceAtLeast(1),
                reward = premio,
            )

            else -> Objective(
                ObjectiveKind.ARRIVA_ENTRO_IL,
                target = ((club.teamsInDivision + 1) / 2).coerceAtLeast(1),
                reward = premio,
            )
        }
    }

    /**
     * L'obiettivo che parla di crescita.
     *
     * Chi ha il proprio giocatore lo porta avanti: e' il cuore del gioco, nasce debole di
     * proposito, e un premio per farlo crescere e' un premio per schierarlo quando
     * schierarlo costa punti. Chi non ce l'ha ancora — perche' il club e' nato senza — si
     * vede chiedere di far crescere qualcun altro.
     *
     * ## Un traguardo, non un incremento
     *
     * Il traguardo e' il **prossimo multiplo di cinque**: da 66 si chiede 70, da 71 si
     * chiede 75, da 88 si chiede 90. La distanza e' la stessa di un «+5», ma quello che si
     * legge cambia del tutto — «porta il tuo giocatore a 70» e' un posto dove arrivare,
     * «porta il tuo giocatore a 71» e' un compito di aritmetica.
     *
     * E siccome il premio si paga a ogni scalino, chi arriva a 90 ha incassato a 70, 75,
     * 80, 85 e 90: la scommessa lunga paga mentre la si fa, invece di pagare una volta
     * sola dopo tre stagioni o di non pagare affatto.
     *
     * ## Perche' non si chiede direttamente 90
     *
     * Perche' un obiettivo di stagione che a giugno risulta fallito per forza non e' un
     * obiettivo: e' una condanna con la data sopra. Chiedere 90 a un custom da 65
     * significa quattro stagioni di «fallito» prima di un «raggiunto». Gli scalini
     * raccontano lo stesso percorso dicendo la verita' a ogni tappa.
     */
    private fun diSviluppo(club: ClubStanding, premio: Int): Objective =
        if (club.customOverall > 0) {
            Objective(
                ObjectiveKind.FAI_CRESCERE_IL_TUO,
                target = prossimoScalino(club.customOverall),
                reward = premio,
            )
        } else {
            Objective(
                ObjectiveKind.PORTA_UN_GIOCATORE_A,
                target = prossimoScalino(club.bestOverall.coerceAtLeast(55)),
                reward = premio,
            )
        }

    /**
     * Il prossimo multiplo di cinque, **strettamente sopra** dove si e' adesso.
     *
     * Lo «strettamente» conta: chi e' esattamente a 70 si deve sentir chiedere 75, non 70.
     * Un obiettivo gia' raggiunto nel momento in cui viene assegnato e' un premio regalato,
     * ed e' il genere di svista che si scopre solo quando qualcuno incassa senza far niente.
     */
    internal fun prossimoScalino(overall: Int): Int = (overall / PASSO_DI_CRESCITA + 1) * PASSO_DI_CRESCITA

    /**
     * L'obiettivo che dura piu' di una stagione.
     *
     * Dove ci sono le divisioni e' restare su per piu' anni, che e' il modo in cui una lega
     * misura una squadra seria. Dove non ce ne sono — girone unico — non esiste una
     * retrocessione da evitare, e il posto lo prende la Primavera: far giocare i propri
     * ragazzi in prima squadra e' l'altra cosa che si costruisce in due stagioni e non in
     * una.
     *
     * Senza Primavera fondata, niente terzo obiettivo: uno che chiede di lanciare ragazzi
     * a chi non ha un settore giovanile e' un premio impossibile con l'aria di essere
     * possibile.
     *
     * ## Perche' guarda anche l'obiettivo di classifica
     *
     * Perche' a una squadra debole di prima divisione si e' gia' chiesto di non
     * retrocedere quest'anno. Ripeterlo come obiettivo lungo sarebbe corretto — sono due
     * traguardi diversi, uno e due stagioni — e si leggerebbe come un errore: due righe
     * con la stessa frase. Chi si e' gia' visto chiedere la salvezza riceve invece
     * qualcosa che parla di costruire, non di sopravvivere.
     */
    private fun diLungoTermine(
        club: ClubStanding,
        classifica: Objective,
        premio: Int,
        stagioni: Int,
    ): Objective? {
        val gia = classifica.kind == ObjectiveKind.NON_RETROCEDERE
        val puoRetrocedere = club.divisionCount > 1 && club.divisionLevel < club.divisionCount

        return when {
            puoRetrocedere && !gia ->
                Objective(ObjectiveKind.NON_RETROCEDERE, reward = premio, seasons = stagioni)

            club.hasCup -> Objective(ObjectiveKind.VINCI_LA_COPPA, reward = premio)

            club.hasYouth ->
                Objective(ObjectiveKind.LANCIA_DALLA_PRIMAVERA, target = RAGAZZI_DA_LANCIARE, reward = premio)

            // Nessuna coppa, nessuna Primavera, e la salvezza gia' chiesta: meglio due
            // obiettivi veri che un terzo messo li' per fare numero.
            else -> null
        }
    }

    /** Quanti punti di overall si chiede di guadagnare in una stagione. */
    private const val PASSO_DI_CRESCITA = 5

    /** Quanti ragazzi della Primavera devono scendere in campo in prima squadra. */
    private const val RAGAZZI_DA_LANCIARE = 3
}
