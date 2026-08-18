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
            Dashboard -> "Dashboard"
            Squadre -> "Squadre"
            Calendario -> "Calendario"
            Classifica -> "Classifica"
            Campo -> "Campo"
            ProfiloLega -> "Profilo lega"
            Partecipanti -> "Partecipanti"
            Opzioni -> "Regolamento e opzioni"
            is Regolamento -> sezione.label
            Competizioni -> "Competizioni"
            Mercati -> "Mercati"
            Scambi -> "Trattative"
            Aste -> "Aste"
            Svincolati -> "Svincolati"
            Listone -> "Listone"
            Infermeria -> "Infermeria"
            Spogliatoio -> "Spogliatoio"
            RegistroAdmin -> "Registro admin"
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
        get() = this is ProfiloLega || this is Partecipanti || this is Opzioni ||
            this is Regolamento || this is Competizioni || this is Mercati

    /**
     * E' una delle cinque voci della barra in basso?
     *
     * Le voci della barra sono **destinazioni**, non passi di un percorso: toccarle azzera
     * la pila invece di impilarsi. Senza questa distinzione, dopo venti tocchi sulla barra
     * il tasto indietro dovrebbe ripercorrere venti schermate per uscire dall'app.
     */
    val isTab: Boolean
        get() = this is Dashboard || this is Squadre || this is Calendario ||
            this is Classifica || this is Campo

    // ------------------------------------------------------------------------- tab bar

    data object Dashboard : Route
    data object Squadre : Route
    data object Calendario : Route
    data object Classifica : Route
    data object Campo : Route

    // ---------------------------------------------------------------------------- setup

    data object ProfiloLega : Route
    data object Partecipanti : Route

    /**
     * L'elenco delle sei sezioni del regolamento.
     *
     * Distinto da [Regolamento] di proposito: la voce del drawer apre un elenco, e ogni
     * riga dell'elenco apre una sezione. Con una sola rotta bisognerebbe scegliere
     * arbitrariamente una sezione di partenza, e il tasto indietro dall'ultima sezione
     * riporterebbe fuori dal regolamento invece che all'elenco.
     */
    data object Opzioni : Route
    data class Regolamento(val sezione: SettingsSection) : Route
    data object Competizioni : Route
    data object Mercati : Route

    // ---------------------------------------------------------------------------- gioca

    data object Aste : Route

    /** Le proposte di scambio: le propone chiunque abbia un club, non solo l.admin. */
    data object Scambi : Route
    data object Svincolati : Route
    data object Listone : Route
    data object Infermeria : Route

    /** Chi ha qualcosa da dirti, e cosa rispondergli. */
    data object Spogliatoio : Route
    data object RegistroAdmin : Route

    // ------------------------------------------------------- destinazioni con un dato

    /** La rosa di un club qualsiasi, aperta dall'elenco delle squadre. */
    data class Rosa(val clubId: Long) : Route

    data class Giocatore(val row: PlayerRow) : Route
    data class Offerta(val auction: AuctionRow) : Route
}
