package dev.mfoot.core.calendar

import dev.mfoot.core.model.ClubId

/**
 * Una partita in programma, ridotta a cio' che serve per sapere se un turno e' finito.
 *
 * Non e' [Fixture] perche' qui non servono ne' l'orario ne' l'identificativo: servono il
 * turno, i due club, l'accoppiamento e se si e' giocata. Chiedere un `Fixture` intero
 * obbligherebbe il server a costruire oggetti pieni di campi che questa regola non guarda.
 */
data class FixtureState(
    val round: Int,
    val home: ClubId,
    val away: ClubId,
    val tieId: String? = null,
    val played: Boolean = false,
)

/**
 * Cosa deve succedere adesso in una competizione: aspettare, scrivere il turno nuovo, o
 * dichiararla finita.
 *
 * ## Perche' questo file esiste
 *
 * Perche' senza, una coppa gioca gli ottavi e si ferma per sempre. [FixtureGenerator]
 * sapeva gia' costruire il turno successivo dai vincitori — con i suoi test, dal primo
 * giorno — e **non lo chiamava nessuno**: ne' il server, ne' l'app, ne' una funzione del
 * database. Il pezzo mancante non era il calcolo, era la domanda "il turno e' finito?".
 *
 * Vale identico per i playoff e i playout delle divisioni, che nascevano come tabellone a
 * quattro e non arrivavano mai alla finale.
 *
 * ## Perche' il turno si riconosce dall'accoppiamento e non dal numero
 *
 * Perche' con l'andata e ritorno le due gare di uno stesso confronto stanno in due turni
 * diversi — devono, o il risolutore le mette alla stessa ora. Il `tieId` invece e' lo
 * stesso per tutte e due, quindi raggruppando per accoppiamento la regola funziona
 * identica in gara secca e in doppia sfida, senza sapere quale delle due sta guardando.
 *
 * ## Perche' non si passa il seme del sorteggio
 *
 * Perche' i gironi si **rileggono dalle partite**: due squadre stanno nello stesso girone
 * se si sono incontrate. Ricalcolarli rimescolando i partecipanti col seme richiederebbe
 * di conservare quel seme da qualche parte — non lo si salva — e basterebbe un seme
 * diverso per qualificare squadre che non hanno mai giocato insieme.
 */
object CompetitionProgress {

    sealed interface Next {
        /** Il turno in corso non e' ancora finito. Non si tocca niente. */
        data object Attendi : Next

        /**
         * I turni da scrivere nel calendario: uno, o due con l'andata e ritorno.
         *
         * Chi chiama deve dargli le date. Qui non ci sono orari di proposito: quando si
         * gioca dipende dal periodo della competizione e dagli impegni degli altri
         * tornei, che sono cose che questa regola non ha motivo di conoscere.
         */
        data class Turno(val rounds: List<Round>) : Next

        /** Non c'e' piu' niente da giocare. [winner] e' null se non e' deducibile. */
        data class Finita(val winner: ClubId?) : Next
    }

    /**
     * Il passo successivo di una competizione.
     *
     * [fixtures] devono arrivare **nell'ordine in cui sono state scritte**: e' l'ordine
     * del tabellone, ed e' quello che decide chi affronta chi al turno dopo. Riordinarle
     * per data o per nome rimescolerebbe il sorteggio a ogni turno.
     */
    fun next(
        competition: Competition,
        fixtures: List<FixtureState>,
        results: List<FixtureResult>,
    ): Next {
        if (fixtures.isEmpty()) return Next.Attendi

        return when (competition.type) {
            CompetitionType.GIRONE -> girone(competition, fixtures, results)

            CompetitionType.ELIMINAZIONE_DIRETTA ->
                tabellone(competition, fixtures, fixtures, competition.participants, results)

            CompetitionType.GIRONI_PIU_ELIMINAZIONE -> gironiPoiTabellone(competition, fixtures, results)
        }
    }

    // ------------------------------------------------------------------------ girone

    /**
     * Un campionato non genera niente: finisce e basta.
     *
     * Le promozioni e le retrocessioni non si decidono qui — sono [SeasonEnd], che ragiona
     * su tutte le divisioni insieme e non su una competizione per volta.
     */
    private fun girone(
        competition: Competition,
        fixtures: List<FixtureState>,
        results: List<FixtureResult>,
    ): Next {
        if (fixtures.any { !it.played }) return Next.Attendi
        return Next.Finita(Standings.compute(competition, results).firstOrNull()?.club)
    }

    // --------------------------------------------------------------------- tabellone

    /**
     * Il tabellone, dal turno in corso al successivo.
     *
     * [primoTurno] e' chi era in gara al primo turno del tabellone: i partecipanti per una
     * coppa, le qualificate per un mondiale. Serve **solo** a ritrovare chi ha riposato al
     * primo turno, che e' l'unico caso in cui un club passa senza comparire in nessuna
     * partita.
     */
    private fun tabellone(
        competition: Competition,
        tutte: List<FixtureState>,
        tabellone: List<FixtureState>,
        primoTurno: List<ClubId>,
        results: List<FixtureResult>,
    ): Next {
        if (tabellone.isEmpty()) return Next.Attendi

        // Gli accoppiamenti, nell'ordine in cui sono comparsi. `LinkedHashMap` non e' un
        // dettaglio: e' l'ordine del tabellone, e da quello dipende chi si incontra dopo.
        val accoppiamenti = LinkedHashMap<String, MutableList<FixtureState>>()
        tabellone.forEach { f ->
            accoppiamenti.getOrPut(f.tieId ?: chiave(f)) { mutableListOf() } += f
        }

        val turnoDi = accoppiamenti.mapValues { (_, gare) -> gare.minOf { it.round } }

        // Si ripercorre il tabellone **in avanti**, dal primo turno all'ultimo, invece di
        // guardare solo quello in corso. E' l'unico modo di portarsi dietro chi ha
        // riposato: un turno di riposo non lascia nessuna partita da cui dedurlo, quindi
        // chi si fermasse all'ultimo turno perderebbe per strada chi aveva saltato il
        // primo. Costa un giro su una lista di poche decine di elementi.
        var passate = primoTurno
        for (turno in turnoDi.values.distinct().sorted()) {
            val ties = accoppiamenti.filterKeys { turnoDi[it] == turno }
            val gare = ties.values.flatten()
            if (gare.any { !it.played }) return Next.Attendi

            // Chi ha riposato va **davanti**: al turno dopo giochera' lui e riposera' un
            // altro. In coda, con un numero dispari che resta dispari, la stessa squadra
            // salterebbe ogni singolo turno e arriverebbe in finale senza giocare.
            passate = riposate(passate, gare) + vincitori(ties, results)
        }

        if (passate.size < 2) return Next.Finita(passate.firstOrNull())

        val nuovi = FixtureGenerator.nextKnockoutRound(competition, passate, tutte.maxOf { it.round })
        return if (nuovi.isEmpty()) Next.Finita(passate.firstOrNull()) else Next.Turno(nuovi)
    }

    /**
     * Chi ha vinto ogni accoppiamento, nell'ordine del tabellone.
     *
     * Un accoppiamento senza risultati non produce nessun vincitore e viene saltato: e' la
     * stessa prudenza di [SeasonEnd.advance] — inventare un passaggio del turno per una
     * partita di cui non si ha l'esito e' il modo piu' rapido di far vincere una coppa a
     * chi non ha giocato.
     */
    private fun vincitori(
        accoppiamenti: Map<String, List<FixtureState>>,
        results: List<FixtureResult>,
    ): List<ClubId> = accoppiamenti.values.mapNotNull { gare ->
        val esiti = gare.mapNotNull { gara ->
            results.firstOrNull { it.home == gara.home && it.away == gara.away }
        }
        if (esiti.isEmpty()) null else Standings.tieWinner(esiti)
    }

    /**
     * Chi ha passato il turno senza giocare.
     *
     * Succede quando le squadre in gara sono dispari: [FixtureGenerator] accoppia a due a
     * due e l'ultima resta fuori. E' un turno di riposo, non una dimenticanza — ma se
     * nessuno lo raccogliesse quella squadra sparirebbe dalla coppa a meta' strada.
     */
    private fun riposate(inGara: List<ClubId>, gare: List<FixtureState>): List<ClubId> {
        val scese = gare.flatMap { listOf(it.home, it.away) }.toSet()
        return inGara.filterNot { it in scese }
    }

    private fun chiave(f: FixtureState): String {
        val a = minOf(f.home.value, f.away.value)
        val b = maxOf(f.home.value, f.away.value)
        return "r${f.round}-$a-$b"
    }

    // ------------------------------------------------------- gironi + eliminazione

    /**
     * Il formato dei mondiali: prima i gironi, poi il tabellone fra le qualificate.
     *
     * Le partite del tabellone si riconoscono dal `tieId`, che la fase a gironi non ha.
     * E' l'unico segno che distingue le due fasi senza affidarsi all'etichetta scritta,
     * che e' testo e come tale si puo' cambiare.
     */
    private fun gironiPoiTabellone(
        competition: Competition,
        fixtures: List<FixtureState>,
        results: List<FixtureResult>,
    ): Next {
        val faseFinale = fixtures.filter { it.tieId != null }
        val gironi = fixtures.filter { it.tieId == null }

        if (faseFinale.isNotEmpty()) {
            // Il primo turno del tabellone lo hanno giocato le qualificate, nello stesso
            // ordine incrociato con cui erano state accoppiate: ricalcolarlo qui e' cio'
            // che permette di ritrovare chi ha riposato anche in questa fase.
            val primoTurno = incrociate(qualificate(competition, gironi, results))
            return tabellone(competition, fixtures, faseFinale, primoTurno, results)
        }

        if (gironi.isEmpty() || gironi.any { !it.played }) return Next.Attendi

        val passate = incrociate(qualificate(competition, gironi, results))
        if (passate.size < 2) return Next.Finita(passate.firstOrNull())

        val nuovi = FixtureGenerator.nextKnockoutRound(competition, passate, fixtures.maxOf { it.round })
        return if (nuovi.isEmpty()) Next.Finita(passate.firstOrNull()) else Next.Turno(nuovi)
    }

    /**
     * Le qualificate, ordinate **per posizione e poi per girone**.
     *
     * Prima tutte le prime, poi tutte le seconde. E' l'ordine che serve a [incrociate] per
     * non far incontrare subito due squadre dello stesso girone: se si prendessero girone
     * per girone — A1, A2, B1, B2 — l'accoppiamento a due a due metterebbe la prima contro
     * la seconda dello stesso gruppo, cioe' farebbe rigiocare una partita appena giocata.
     */
    private fun qualificate(
        competition: Competition,
        gironi: List<FixtureState>,
        results: List<FixtureResult>,
    ): List<ClubId> {
        val gruppi = gruppiDaiRisultati(gironi)
        if (gruppi.isEmpty()) return emptyList()

        return (0 until competition.qualifiersPerGroup).flatMap { posizione ->
            gruppi.mapNotNull { gruppo ->
                Standings.compute(competition, results, gruppo).getOrNull(posizione)?.club
            }
        }
    }

    /**
     * I gironi, riletti dalle partite: due squadre stanno insieme se si sono incontrate.
     *
     * Sono le componenti connesse del grafo degli incontri. Con un girone all'italiana
     * ogni gruppo e' completamente connesso, quindi il conto e' esatto e non serve
     * conoscere ne' il sorteggio ne' quante squadre per gruppo erano previste.
     */
    fun gruppiDaiRisultati(gironi: List<FixtureState>): List<List<ClubId>> {
        val vicini = LinkedHashMap<ClubId, MutableSet<ClubId>>()
        gironi.forEach { f ->
            vicini.getOrPut(f.home) { linkedSetOf() } += f.away
            vicini.getOrPut(f.away) { linkedSetOf() } += f.home
        }

        val visti = mutableSetOf<ClubId>()
        val gruppi = mutableListOf<List<ClubId>>()

        vicini.keys.forEach { partenza ->
            if (partenza in visti) return@forEach
            val gruppo = mutableListOf<ClubId>()
            val coda = ArrayDeque(listOf(partenza))
            visti += partenza
            while (coda.isNotEmpty()) {
                val club = coda.removeFirst()
                gruppo += club
                vicini[club].orEmpty().forEach { altro ->
                    if (visti.add(altro)) coda += altro
                }
            }
            gruppi += gruppo
        }
        return gruppi
    }

    /**
     * L'ordine del tabellone: prima contro ultima, seconda contro penultima.
     *
     * E' lo schema di [SeasonEnd.pairings] applicato alle qualificate, e fa due cose in
     * una: premia chi ha vinto il proprio girone dandogli l'ultima delle seconde, e — dato
     * che le prime stanno tutte davanti e le seconde tutte dietro — impedisce che due
     * squadre dello stesso girone si ritrovino subito, tranne quando i gironi sono uno
     * solo e non c'e' modo di evitarlo.
     *
     * Con un numero dispari l'ultima resta in fondo e riposa, che e' lo stesso posto in
     * cui la mette [FixtureGenerator].
     */
    private fun incrociate(ordinate: List<ClubId>): List<ClubId> {
        val out = ArrayList<ClubId>(ordinate.size)
        var alto = 0
        var basso = ordinate.size - 1
        while (alto < basso) {
            out += ordinate[alto]
            out += ordinate[basso]
            alto++
            basso--
        }
        if (alto == basso) out += ordinate[alto]
        return out
    }
}
