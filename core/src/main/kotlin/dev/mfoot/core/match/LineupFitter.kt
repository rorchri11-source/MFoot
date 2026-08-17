package dev.mfoot.core.match

import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position

/**
 * Chi va in quale casella.
 *
 * ## Perche' sta in `core` e non nell'app
 *
 * La stessa domanda viene fatta da tre parti diverse, e devono rispondere allo stesso modo:
 *
 * - il telefono, quando si cambia modulo e i dieci giocatori gia' scelti vanno risistemati
 *   nelle caselle nuove;
 * - il telefono, quando si preme "completa" su una formazione con dei buchi;
 * - il server, quando la formazione salvata ha un titolare venduto e la partita si gioca
 *   comunque.
 *
 * Se ognuno avesse la sua versione, premere "completa" e lasciar fare al server darebbero
 * due squadre diverse — e chi gioca lo scoprirebbe leggendo il tabellino, senza capire
 * perche'. Le tre risposte vengono da qui.
 *
 * ## L'ordine di assegnazione non e' un dettaglio
 *
 * Le caselle si riempiono partendo da quelle con **meno candidati veri**, non da sinistra
 * a destra. Assegnare il portiere per ultimo significa metterci chi e' avanzato, e un
 * giocatore di movimento fra i pali vale quaranta punti di malus: la squadra sembrerebbe
 * rotta senza che il proprietario capisca da cosa.
 */
object LineupFitter {

    /**
     * Sistema [players] nelle caselle di [formation], ognuno dove rende di piu'.
     *
     * La lista che esce e' lunga quanto le caselle del modulo, nell'ordine del modulo, con
     * un null dove non c'era piu' nessuno da mettere. Giocatori in piu' rispetto alle
     * caselle restano fuori: chi chiama li ritrova per differenza e li mette in panchina.
     */
    fun fit(
        formation: Formation,
        players: List<Player>,
        today: MatchDay = MatchDay(0),
    ): List<Player?> = fillHoles(
        formation = formation,
        current = arrayOfNulls<Player>(formation.positions.size).toList(),
        candidates = players,
        today = today,
    )

    /**
     * Tiene chi c'e' gia' e riempie il resto pescando da [candidates].
     *
     * E' l'operazione di "completa": nessun titolare gia' scelto viene spostato, perche'
     * chi ha messo il proprio giocatore in un ruolo preciso non deve vederselo spostare
     * altrove da un pulsante che prometteva solo di riempire i vuoti.
     *
     * [current] deve essere lungo quanto le caselle del modulo. I candidati gia' presenti
     * in [current] non vengono usati due volte, quindi si puo' passare tranquillamente
     * l'intera rosa.
     */
    fun fillHoles(
        formation: Formation,
        current: List<Player?>,
        candidates: List<Player>,
        today: MatchDay = MatchDay(0),
    ): List<Player?> {
        require(current.size == formation.positions.size) {
            "il modulo ${formation.label} ha ${formation.positions.size} caselle, " +
                "ne sono arrivate ${current.size}"
        }

        val out = current.toMutableList()
        val taken = out.filterNotNull().mapTo(HashSet()) { it.id.value }
        val disponibili = candidates.filterNot { it.isInjured(today) || it.id.value in taken }
            .toMutableList()

        val buchi = out.indices.filter { out[it] == null }
        val candidatiPer = buchi.associateWith { index ->
            disponibili.count { it.canPlay(formation.positions[index]) }
        }

        // Prima i ruoli che sanno fare in pochi — ma **dopo** quelli che nessuno sa fare.
        //
        // Sembra un cavillo e non lo e'. Con la rosa incompleta, "meno candidati" diventa
        // "zero candidati", e ordinando per solo numero il portiere finisce primo perche'
        // nessuno sa fare il portiere: gli si assegna l'unico giocatore in rosa, che magari
        // e' un attaccante. E' esattamente cio' che questa regola doveva impedire.
        //
        // Un ruolo che nessuno copre non ha niente da proteggere: si riempie per ultimo con
        // chi resta, o si lascia vuoto. Un ruolo con due candidati veri si', e va prima.
        val ordine = buchi.sortedWith(
            compareBy(
                { if (candidatiPer.getValue(it) == 0) 1 else 0 },
                { candidatiPer.getValue(it) },
            ),
        )

        for (index in ordine) {
            val position = formation.positions[index]
            val best = disponibili.maxByOrNull { fitness(it, position) } ?: break
            disponibili.remove(best)
            out[index] = best
        }

        return out
    }

    /**
     * Chi resta fuori dagli undici, in ordine di quanto conviene averli pronti.
     *
     * La panchina non e' un elenco a caso: e' chi entra quando qualcuno si stanca o si fa
     * male, quindi va ordinata come si ordinerebbero i cambi.
     */
    fun bench(
        eleven: List<Player?>,
        squad: List<Player>,
        size: Int = DEFAULT_BENCH,
        today: MatchDay = MatchDay(0),
    ): List<Player> {
        val inCampo = eleven.filterNotNull().mapTo(HashSet()) { it.id.value }
        return squad
            .filterNot { it.isInjured(today) || it.id.value in inCampo }
            .sortedByDescending { fitness(it, it.primaryPosition) }
            .take(size)
    }

    /**
     * Quanto rende questo giocatore in questa casella, adesso.
     *
     * E' **l'unica** definizione di "chi e' il migliore per questo ruolo" del gioco: la usa
     * il campo sul telefono, la usa [AutoLineup] quando schiera da sola, la usa il tick
     * quando ripara una formazione salvata. Averne due voleva dire che "completa" sul
     * telefono e la squadra scesa in campo potevano non coincidere.
     *
     * La stanchezza pesa nella scelta e non solo in partita. Guardando solo l'overall si
     * rimetterebbero in campo sempre gli stessi undici fino a bruciarli, e con due partite
     * al giorno la stamina e' la risorsa piu' scarsa che ci sia. Sotto [TIRED_THRESHOLD] la
     * penalita' diventa netta: non un divieto — con la rosa decimata bisogna comunque
     * scendere in campo — ma abbastanza da far preferire un pari ruolo riposato.
     */
    fun fitness(player: Player, position: Position): Double {
        val tired = if (player.stamina < TIRED_THRESHOLD) TIRED_PENALTY else 1.0
        return player.overallAt(position) * (0.75 + 0.25 * player.stamina / 100.0) * tired
    }

    /** Sotto questa stamina un giocatore si schiera solo se non c'e' nessun altro. */
    const val TIRED_THRESHOLD = 45
    private const val TIRED_PENALTY = 0.75

    /** Quante riserve si portano, quando nessuno dice diversamente. */
    const val DEFAULT_BENCH = 7
}
