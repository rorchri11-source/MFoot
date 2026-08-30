package dev.mfoot.core.match

import dev.mfoot.core.config.LeagueConfig

/**
 * Che partita ci si aspetta, prima che si giochi.
 *
 * ## Perche' si simula invece di stimare
 *
 * Perche' una formula a parte sarebbe **un secondo motore**, e due motori divergono al
 * primo ritocco: si tarerebbe la partita e resterebbe il pronostico a raccontare un gioco
 * che non esiste piu'. Qui si fa girare `MatchEngine` qualche centinaio di volte sui due
 * undici veri — gli stessi giocatori, la stessa stanchezza, le stesse tattiche — e si
 * contano gli esiti. Il pronostico **e'** il motore, non una sua imitazione.
 *
 * Costa poco: sul banco di prova una partita si simula in poco piu' di un millisecondo, e
 * trecento stanno sotto il secondo. Su un telefono qualche volta tanto, che e' comunque il
 * tempo di aprire una schermata.
 *
 * ## Perche' i semi vengono dalla partita
 *
 * Perche' due telefoni devono vedere **lo stesso numero**. Un pronostico che cambia a ogni
 * apertura non e' un pronostico, e due amici che leggono 46% e 51% sulla stessa partita
 * smettono di credere a tutti e due. Derivando i semi dall'identificativo della partita, il
 * conto e' identico ovunque e sempre.
 *
 * Cambia solo se cambia qualcosa di vero — una formazione, uno stato di forma — ed e'
 * giusto che cambi: e' quello che il pronostico sta misurando.
 */
object Pronostico {

    /**
     * Le tre probabilita', in percentuale intera, che sommano sempre a cento.
     *
     * Intere perche' un decimale su un numero che ha tre punti di margine d'errore e'
     * precisione finta.
     */
    data class Esito(val casa: Int, val pari: Int, val ospite: Int) {
        init {
            require(casa + pari + ospite == 100) {
                "le tre probabilita' devono fare cento, fanno ${casa + pari + ospite}"
            }
        }

        // QUI C'ERA UNA `favorita`, ED E' STATA TOLTA
        //
        // Diceva «casa» quando la casa superava l'ospite di sei punti. Misurando: fra due
        // squadre **identiche** il pronostico e' 47-26-27, perche' il vantaggio del campo
        // vale vent'anni di scarto percentuale. Quindi «favorita» avrebbe detto «casa» in
        // quasi ogni partita equilibrata — vero, e inutile.
        //
        // Le tre percentuali dicono gia' tutto. Una scorciatoia che non aggiunge niente e'
        // solo un altro posto in cui sbagliare.
    }

    /**
     * Simula la partita [quante] volte e conta come finisce.
     *
     * [fixtureId] non e' decorazione: e' quello che rende il conto identico su ogni
     * telefono.
     */
    fun calcola(
        home: TeamSetup,
        away: TeamSetup,
        config: LeagueConfig,
        fixtureId: Long,
        quante: Int = config.engine.simulazioniPronostico,
        importance: MatchImportance = MatchImportance.CAMPIONATO,
    ): Esito {
        val volte = quante.coerceIn(20, 2000)
        var casa = 0
        var pari = 0
        var ospite = 0

        repeat(volte) { index ->
            val result = MatchEngine.simulate(
                home = home,
                away = away,
                config = config,
                seed = fixtureId * SALE + index,
                importance = importance,
            )
            when (result.winner) {
                Side.CASA -> casa++
                Side.OSPITE -> ospite++
                null -> pari++
            }
        }

        return percentuali(casa, pari, ospite, volte)
    }

    /**
     * Da conteggi a percentuali intere che fanno cento.
     *
     * Il resto dell'arrotondamento va a chi lo merita di piu' — il metodo dei resti piu'
     * grandi — invece che al primo della lista: arrotondando ciascuno per conto suo si
     * ottiene 33+33+33, e novantanove non e' una probabilita'.
     */
    internal fun percentuali(casa: Int, pari: Int, ospite: Int, totale: Int): Esito {
        if (totale <= 0) return Esito(34, 33, 33)

        val esatte = listOf(casa, pari, ospite).map { it * 100.0 / totale }
        val intere = esatte.map { StrictMath.floor(it).toInt() }.toMutableList()
        var mancano = 100 - intere.sum()

        val ordine = esatte.indices.sortedByDescending { esatte[it] - intere[it] }
        var i = 0
        while (mancano > 0) {
            intere[ordine[i % ordine.size]]++
            mancano--
            i++
        }

        return Esito(intere[0], intere[1], intere[2])
    }

    /** Mescola l'identificativo della partita: partite vicine non devono avere semi vicini. */
    private const val SALE = 7919L
}
