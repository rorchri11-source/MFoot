package dev.mfoot.android.app

import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.LineupFitter
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player

/**
 * La formazione che si sta componendo.
 *
 * ## Perche' gli undici sono una lista e non una mappa
 *
 * Le caselle di un modulo sono undici posizioni ordinate, e la casella numero 4 esiste
 * anche quando e' vuota. Con una mappa da casella a giocatore, "vuota" e "inesistente"
 * diventerebbero la stessa cosa, e il campo non saprebbe piu' quanti cerchi tratteggiati
 * disegnare — che e' esattamente l'informazione che si guarda per capire cosa manca.
 *
 * ## Perche' vive nel ViewModel
 *
 * Comporre una formazione e' un lavoro lungo: si prova un modulo, si sposta un uomo, si
 * cambia idea. Perdere tutto per una rotazione dello schermo sarebbe insopportabile, e
 * salvare a ogni tocco vorrebbe dire mandare al database venti formazioni intermedie che
 * nessuno ha mai voluto schierare.
 */
data class LineupEdit(
    val formation: Formation = Formation.F_4_3_3,
    /** Un elemento per casella del modulo, nell'ordine del modulo. Null = casella vuota. */
    val eleven: List<Player?> = List(Formation.PLAYERS_ON_PITCH) { null },
    val bench: List<Player> = emptyList(),
    val tactics: Tactics = Tactics.DEFAULT,
    val captainId: Long? = null,
    val penaltyTakerId: Long? = null,
    /** La casella su cui si sta scegliendo chi mettere. Null = non si sta scegliendo. */
    val picking: Int? = null,
    /**
     * Come stava quando e' stata caricata o salvata l'ultima volta.
     *
     * Serve solo a rispondere a "c'e' qualcosa da salvare?". Tenere la copia e' piu' onesto
     * di un booleano `modificata` messo a mano: un flag lo si dimentica di alzare in un
     * ramo, e il pulsante resta spento su una modifica vera.
     */
    val salvata: Snapshot? = null,
    val busy: String? = null,
    val errore: String? = null,
) {

    /** Cio' che finisce nel database: solo numeri, per confrontarli senza sorprese. */
    data class Snapshot(
        val formation: Formation,
        val eleven: List<Long?>,
        val bench: List<Long>,
        val tactics: Tactics,
        val captainId: Long?,
        val penaltyTakerId: Long?,
    )

    val snapshot: Snapshot
        get() = Snapshot(
            formation = formation,
            eleven = eleven.map { it?.id?.value },
            bench = bench.map { it.id.value },
            tactics = tactics,
            captainId = captainId,
            penaltyTakerId = penaltyTakerId,
        )

    val dirty: Boolean get() = salvata != snapshot

    val schierati: Int get() = eleven.count { it != null }
    val completa: Boolean get() = schierati == formation.positions.size

    /** Chi e' in campo, per non riproporlo nella lista di chi si puo' mettere. */
    val inCampo: Set<Long> get() = eleven.mapNotNull { it?.id?.value }.toSet()

    fun with(index: Int, player: Player?): LineupEdit {
        val nuovi = eleven.toMutableList()
        // Un giocatore in due caselle e' l'errore piu' facile da fare e il piu' difficile
        // da vedere: si trascina un uomo e non ci si accorge che era gia' altrove.
        if (player != null) {
            nuovi.indices.forEach { if (nuovi[it]?.id == player.id) nuovi[it] = null }
        }
        nuovi[index] = player
        return copy(eleven = nuovi, picking = null).senzaAssenti()
    }

    /**
     * Cambia modulo tenendo in campo gli stessi uomini.
     *
     * Svuotare il campo a ogni cambio renderebbe impossibile la cosa che si fa piu' spesso
     * qui dentro: provare il 4-3-3, provare il 3-5-2, tornare indietro.
     */
    fun withFormation(nuovo: Formation): LineupEdit {
        if (nuovo == formation) return this
        return copy(
            formation = nuovo,
            eleven = LineupFitter.fit(nuovo, eleven.filterNotNull()),
            picking = null,
        ).senzaAssenti()
    }

    /** Riempie i buchi senza spostare chi c'e' gia'. */
    fun completa(squad: List<Player>, today: MatchDay): LineupEdit =
        copy(
            eleven = LineupFitter.fillHoles(formation, eleven, squad, today),
            picking = null,
        ).conPanchina(squad, today).senzaAssenti()

    fun conPanchina(squad: List<Player>, today: MatchDay): LineupEdit =
        copy(bench = LineupFitter.bench(eleven, squad, today = today))

    /**
     * Toglie fascia e rigori a chi non e' piu' in campo.
     *
     * Un capitano in panchina non e' un errore che il server rifiuta — lo ignora e ne
     * sceglie un altro — ma vederlo indicato sullo schermo e poi diverso nel tabellino
     * fa sembrare che il gioco non ascolti.
     */
    private fun senzaAssenti(): LineupEdit {
        val presenti = inCampo
        return copy(
            captainId = captainId?.takeIf { it in presenti },
            penaltyTakerId = penaltyTakerId?.takeIf { it in presenti },
        )
    }
}
