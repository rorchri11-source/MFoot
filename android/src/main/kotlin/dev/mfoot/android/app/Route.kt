package dev.mfoot.android.app

/**
 * Le sei sezioni del regolamento.
 *
 * Sta nel pacchetto della navigazione e non accanto alle schermate che la disegnano
 * perche' e' il **parametro di una rotta**: se vivesse fra le impostazioni, la pila del
 * tasto indietro dipenderebbe dalla presentazione, e la freccia delle dipendenze deve
 * andare nel verso opposto.
 */
enum class SettingsSection(val label: String) {
    SQUADRE("Squadre e rose"),
    DIVISIONI("Divisioni"),
    ECONOMIA("Economia"),
    MERCATO("Mercato"),
    PARTITA("Partita"),
    CRESCITA("Crescita e giocatori"),
    CUSTOM("Il tuo giocatore"),
}

/** Cosa si guarda della propria squadra. */
enum class TabSquadra(val label: String) {
    ROSA("Rosa"),
    CAMPO("Campo"),
    STAFF("Staff"),
    SPOGLIATOIO("Spogliatoio"),
    INFERMERIA("Infermeria"),
}

/** Cosa si guarda del mercato. */
enum class TabMercato(val label: String) {
    ASTE("Aste"),
    SVINCOLATI("Svincolati"),
    LISTONE("Listone"),
    CONCLUSE("Concluse"),
    TRATTATIVE("Trattative"),
    OSSERVATORI("Osservatori"),
}

/** Cosa si guarda della lega. */
enum class TabLega(val label: String) {
    CLASSIFICA("Classifica"),
    SQUADRE("Squadre"),
}

/**
 * Dove si e' dentro la lega.
 *
 * ## Perche' un tipo e non una stringa
 *
 * Meta' delle rotte porta con se' un dato: quale giocatore, quale asta, quale club. Con
 * una stringa quel dato finirebbe in un campo a parte dello stato, e niente garantirebbe
 * piu' che i due siano coerenti — si arriverebbe alla scheda di un giocatore con dentro
 * ancora il giocatore di prima. Qui il dato e' dentro la destinazione: non possono
 * separarsi.
 *
 * ## Perche' cinque posti invece di sedici voci
 *
 * La versione precedente era un elenco piatto: cinque schede in basso piu' undici voci nel
 * menu. Quattro di quelle undici — `Aste`, `Svincolati`, `Listone`, `Scambi` — portavano
 * **allo stesso composable** con un filtro diverso, e nessuna delle quattro lo diceva.
 *
 * Adesso sono cinque **posti**, e ognuno risponde a una domanda sola: cosa e' successo,
 * come sta la mia squadra, cosa c'e' sul mercato, quando gioco, a che punto siamo. Dentro
 * ogni posto ci sono le stesse schermate di prima, raggiunte con dei chip invece che con
 * una voce di menu ciascuna.
 *
 * Nel menu restano le cose che si fanno due volte a stagione.
 */
sealed interface Route {

    /**
     * Il nome della rotta: la voce nel menu e il titolo in cima.
     *
     * Uno solo, non due elenchi. Con le etichette del menu scritte da una parte e i
     * titoli delle schermate dall'altra, prima o poi divergono, e chi tocca "Listone" e
     * si ritrova un titolo diverso non sa piu' se ha aperto quello che voleva.
     */
    val label: String
        get() = when (this) {
            Casa -> "Casa"
            is Squadra -> tab.label
            is Mercato -> tab.label
            Calendario -> "Calendario"
            is Lega -> tab.label
            ProfiloLega -> "Profilo lega"
            Partecipanti -> "Partecipanti"
            MieLeghe -> "Le mie leghe"
            Opzioni -> "Regolamento e opzioni"
            is Regolamento -> sezione.label
            Mercati -> "Finestre di mercato"
            Competizioni -> "Competizioni"
            RegistroAdmin -> "Registro attivita'"
            is Rosa -> "Rosa"
            is Giocatore -> row.player.fullName
            is Offerta -> auction.label
        }

    /**
     * E' una voce di SETUP, cioe' roba da amministratore?
     *
     * Serve solo a nascondere le voci a chi non e' admin. La difesa vera resta lato
     * database, che rifiuta la chiamata: nascondere un pulsante e' cortesia verso chi non
     * e' admin, non sicurezza. Confondere le due cose significa costruire un'app che si
     * apre con un proxy HTTP.
     */
    val isSetup: Boolean
        get() = this is Opzioni || this is Regolamento || this is Mercati ||
            this is Competizioni

    // `ProfiloLega` e `Partecipanti` erano qui dentro, cioe' visibili solo all'admin. Era
    // un errore di categoria: non configurano niente, **raccontano** — che lega e' questa,
    // chi c'e' dentro, chi si e' iscritto e non ha ancora fondato. Sono precisamente le
    // due schermate a cui serve rispondere quando un amico dice «io ti vedo e tu no», e
    // l'amico in questione quasi mai e' l'amministratore.

    /**
     * E' uno dei cinque posti della barra in basso?
     *
     * I posti sono **destinazioni**, non passi di un percorso: toccarli azzera la pila
     * invece di impilarsi. Senza questa distinzione, dopo venti tocchi sulla barra il tasto
     * indietro dovrebbe ripercorrere venti schermate per uscire dall'app.
     */
    val isTab: Boolean
        get() = this is Casa || this is Squadra || this is Mercato ||
            this is Calendario || this is Lega

    /**
     * Due rotte sono lo **stesso posto** anche con una scheda diversa.
     *
     * Serve alla barra in basso, che deve restare accesa su "Squadra" mentre si passa da
     * Rosa a Campo. Senza, cambiare chip spegnerebbe la voce della barra e sembrerebbe di
     * essere usciti da dove si e'.
     */
    fun samePlace(other: Route): Boolean = when {
        this is Squadra && other is Squadra -> true
        this is Mercato && other is Mercato -> true
        this is Lega && other is Lega -> true
        else -> this == other
    }

    // ------------------------------------------------------------------- i cinque posti

    data object Casa : Route

    /**
     * La propria squadra.
     *
     * L'interruttore fra prima squadra e Primavera **non e' qui**: e' uno stato, non una
     * destinazione. Deve restare dov'e' passando da Rosa a Campo, e una rotta che lo
     * portasse con se' lo farebbe tornare alla prima squadra a ogni chip toccato.
     */
    data class Squadra(val tab: TabSquadra = TabSquadra.ROSA) : Route

    data class Mercato(val tab: TabMercato = TabMercato.ASTE) : Route

    data object Calendario : Route

    data class Lega(val tab: TabLega = TabLega.CLASSIFICA) : Route

    // ------------------------------------------------------------- il menu, per le rare

    data object ProfiloLega : Route
    data object Partecipanti : Route

    /**
     * Tutte le leghe di cui si fa parte, con quella aperta adesso in evidenza.
     *
     * Non e' una voce di setup: e' la risposta a «siamo nella stessa partita?», ed e' una
     * domanda che si fa chi non e' amministratore almeno quanto chi lo e'.
     */
    data object MieLeghe : Route

    /**
     * L'elenco delle sezioni del regolamento.
     *
     * Distinto da [Regolamento] di proposito: la voce del menu apre un elenco, e ogni riga
     * dell'elenco apre una sezione. Con una sola rotta bisognerebbe scegliere
     * arbitrariamente una sezione di partenza, e il tasto indietro dall'ultima riporterebbe
     * fuori dal regolamento invece che all'elenco.
     */
    data object Opzioni : Route
    data class Regolamento(val sezione: SettingsSection) : Route
    data object Mercati : Route
    data object Competizioni : Route
    data object RegistroAdmin : Route

    // ------------------------------------------------------- destinazioni con un dato

    /** La rosa di un club qualsiasi, aperta dall'elenco delle squadre. */
    data class Rosa(val clubId: Long) : Route

    data class Giocatore(val row: PlayerRow) : Route
    data class Offerta(val auction: AuctionRow) : Route
}
