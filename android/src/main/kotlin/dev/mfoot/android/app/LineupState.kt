package dev.mfoot.android.app

import dev.mfoot.core.match.ConditionalOrder
import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.LineupFitter
import dev.mfoot.core.match.MatchDuty
import dev.mfoot.core.match.OrderAction
import dev.mfoot.core.match.OrderTrigger
import dev.mfoot.core.match.SetPieces
import dev.mfoot.core.match.TacticalPressing
import dev.mfoot.core.match.TacticalStance
import dev.mfoot.core.match.TacticalTempo
import dev.mfoot.core.match.TacticalWidth
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId

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
/**
 * La formazione di **un'altra** squadra, in sola lettura.
 *
 * ## Perche' e' una cosa a parte da [LineupEdit]
 *
 * Perche' non e' un editor con i pulsanti spenti. `LineupEdit` porta con se' mezza dozzina
 * di concetti che qui non esistono — cosa si sta scegliendo, cosa c'e' da salvare, com'era
 * prima — e riusarlo vorrebbe dire tenerli tutti a null e sperare che nessuna schermata li
 * guardi. Sono due domande diverse: «come schiero» e «come schiera lui».
 *
 * ## Perche' si vede, che non e' ovvio
 *
 * Perche' e' una lega fra amici e il modulo dell'avversario non e' un segreto: e' scritto
 * nel database, lo leggono gia' il tick e la classifica, e chi gioca lo scoprirebbe
 * comunque a partita finita guardando le presenze. Nasconderlo non proteggeva niente e
 * toglieva l'unica cosa che rende interessante preparare una partita — vedere che gioca
 * con tre attaccanti e decidere di conseguenza.
 *
 * [suPrevisione] distingue le due situazioni che vanno tenute separate: una formazione
 * **scelta** dal proprietario, e quella che il server schiererebbe da solo per uno che non
 * ha scelto niente. La seconda e' un'ipotesi, e va detto.
 */
data class FormazioneAltrui(
    val clubId: Long? = null,
    val formation: Formation = Formation.F_4_3_3,
    /** Un elemento per casella, nell'ordine del modulo. */
    val eleven: List<Player?> = emptyList(),
    val bench: List<Player> = emptyList(),
    val tactics: Tactics? = null,
    /** Nessuno ha schierato: quello che si vede e' cio' che scenderebbe in campo da solo. */
    val suPrevisione: Boolean = false,
    val letto: Boolean = false,
    val errore: String? = null,
)

data class LineupEdit(
    val formation: Formation = Formation.F_4_3_3,
    /** Un elemento per casella del modulo, nell'ordine del modulo. Null = casella vuota. */
    val eleven: List<Player?> = List(Formation.PLAYERS_ON_PITCH) { null },
    val bench: List<Player> = emptyList(),
    val tactics: Tactics = Tactics.DEFAULT,
    val captainId: Long? = null,
    val penaltyTakerId: Long? = null,
    /** Chi batte angoli, punizioni e palloni lunghi. Null = sceglie il motore. */
    val cornerTakerId: Long? = null,
    val freeKickTakerId: Long? = null,
    val longBallTakerId: Long? = null,
    /**
     * Gli ordini condizionali: cosa fare senza essere davanti al telefono.
     *
     * Erano completi in `core` dal primo giorno, con la colonna del database che li
     * aspettava, e nessuna schermata li mostrava.
     */
    val orders: List<ConditionalOrder> = emptyList(),
    /** L'incarico che si sta assegnando, o null. */
    val assegnando: MatchDuty? = null,
    /** Vero mentre si sta componendo un ordine condizionale nuovo. */
    val nuovoOrdine: OrdineInComposizione? = null,
    /** La casella su cui si sta scegliendo chi mettere. Null = non si sta scegliendo. */
    /**
     * Di quale delle due squadre e la formazione.
     *
     * Senza, salvare mentre l interruttore e sulla Primavera scriverebbe sulla riga della
     * prima squadra: `lineups` ha una riga per club, e sbagliare club vuol dire schierare
     * undici ragazzi al posto della prima squadra.
     */
    val clubId: Long? = null,
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
        val cornerTakerId: Long?,
        val freeKickTakerId: Long?,
        val longBallTakerId: Long?,
        val orders: List<ConditionalOrder>,
    )

    val snapshot: Snapshot
        get() = Snapshot(
            formation = formation,
            eleven = eleven.map { it?.id?.value },
            bench = bench.map { it.id.value },
            tactics = tactics,
            captainId = captainId,
            penaltyTakerId = penaltyTakerId,
            cornerTakerId = cornerTakerId,
            freeKickTakerId = freeKickTakerId,
            longBallTakerId = longBallTakerId,
            orders = orders,
        )

    /** L'uomo che ha questo incarico, se e' stato scelto e se e' ancora in campo. */
    fun incaricato(duty: MatchDuty): Player? {
        val id = idIncarico(duty) ?: return null
        return eleven.filterNotNull().firstOrNull { it.id.value == id }
    }

    /**
     * Gli incarichi di un giocatore, per la sua scheda.
     *
     * Perche' la scheda e' il posto dove si decide: si guarda il tiro di uno e si capisce
     * che i rigori dovrebbe calciarli lui. Vederci scritto che gia' li calcia — o che non
     * li calcia — chiude il cerchio senza tornare al campo a controllare.
     */
    fun incarichiDi(playerId: Long): List<MatchDuty> =
        MatchDuty.entries.filter { idIncarico(it) == playerId }

    fun idIncarico(duty: MatchDuty): Long? = when (duty) {
        MatchDuty.CAPITANO -> captainId
        MatchDuty.RIGORISTA -> penaltyTakerId
        MatchDuty.ANGOLI -> cornerTakerId
        MatchDuty.PUNIZIONI -> freeKickTakerId
        MatchDuty.LANCI_LUNGHI -> longBallTakerId
    }

    fun conIncarico(duty: MatchDuty, playerId: Long?): LineupEdit = when (duty) {
        MatchDuty.CAPITANO -> copy(captainId = playerId)
        MatchDuty.RIGORISTA -> copy(penaltyTakerId = playerId)
        MatchDuty.ANGOLI -> copy(cornerTakerId = playerId)
        MatchDuty.PUNIZIONI -> copy(freeKickTakerId = playerId)
        MatchDuty.LANCI_LUNGHI -> copy(longBallTakerId = playerId)
    }.copy(assegnando = null)

    /**
     * Chi puo' ricevere un incarico, ordinato dal piu' adatto.
     *
     * L'ordine e' quello di [SetPieces], cioe' **lo stesso criterio con cui il motore
     * sceglierebbe da solo**: chi guarda la lista vede in cima l'uomo che calcerebbe
     * comunque, e capisce cosa sta cambiando quando ne sceglie un altro.
     */
    fun candidati(duty: MatchDuty): List<Player> {
        // Il portiere e' quello schierato in porta, non quello che di ruolo lo sarebbe:
        // se uno mette il secondo portiere terzino, quell'uomo puo' battere gli angoli.
        val ammessi = eleven.mapIndexedNotNull { index, player ->
            when {
                player == null -> null
                duty == MatchDuty.CAPITANO || duty == MatchDuty.LANCI_LUNGHI -> player
                formation.positions.getOrNull(index)?.isGoalkeeper == true -> null
                else -> player
            }
        }
        return ammessi.sortedByDescending { SetPieces.aptitude(it, duty) }
    }

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
        val inRosa = presenti + bench.map { it.id.value }
        return copy(
            captainId = captainId?.takeIf { it in presenti },
            penaltyTakerId = penaltyTakerId?.takeIf { it in presenti },
            cornerTakerId = cornerTakerId?.takeIf { it in presenti },
            freeKickTakerId = freeKickTakerId?.takeIf { it in presenti },
            longBallTakerId = longBallTakerId?.takeIf { it in presenti },
            // Un ordine che parla di un uomo che non c'e' piu' non e' un ordine: e' una
            // riga che resta sullo schermo e non scatta mai.
            orders = orders.filter { ordine -> ordine.riguarda().all { it in inRosa } },
        )
    }
}

/** Di quali giocatori parla un ordine: serve per buttarlo via quando non ci sono piu'. */
private fun ConditionalOrder.riguarda(): List<Long> = buildList {
    when (val t = trigger) {
        is OrderTrigger.GiocatoreAmmonito -> add(t.playerId.value)
        is OrderTrigger.StaminaSotto -> add(t.playerId.value)
        else -> Unit
    }
    when (val a = action) {
        is OrderAction.Sostituisci -> { add(a.out.value); add(a.entra.value) }
        else -> Unit
    }
}

/**
 * Un ordine condizionale mentre lo si sta scrivendo.
 *
 * ## Perche' non si compone direttamente un [ConditionalOrder]
 *
 * Perche' a meta' strada non e' ancora un ordine valido: si e' scelta la condizione e non
 * l'azione, oppure una sostituzione senza sapere ancora chi entra. Un tipo separato lascia
 * esistere lo stato incompleto senza doverlo rappresentare con dei null dentro il modello
 * di gioco — che poi finirebbero nel database.
 */
data class OrdineInComposizione(
    val quando: Quando = Quando.SOTTO,
    val minuto: Int = 60,
    val cosa: Cosa = Cosa.ASSETTO,
    val stance: TacticalStance = TacticalStance.OFFENSIVO,
    val tempo: TacticalTempo = TacticalTempo.ALTO,
    val pressing: TacticalPressing = TacticalPressing.ALTO,
    val width: TacticalWidth = TacticalWidth.LARGO,
    val esce: Long? = null,
    val entra: Long? = null,
    /** Sotto quale stamina scatta, per la condizione [Quando.STANCO]. */
    val soglia: Int = 40,
) {
    enum class Quando(val label: String) {
        SOTTO("Se sono sotto"),
        PARI("Se sono in pareggio"),
        AVANTI("Se sono in vantaggio"),
        SEMPRE("Comunque vada"),
        STANCO("Se qualcuno e' in riserva"),
    }

    enum class Cosa(val label: String) {
        ASSETTO("Cambia atteggiamento"),
        RITMO("Cambia ritmo"),
        PRESSING("Cambia pressing"),
        AMPIEZZA("Cambia ampiezza"),
        SOSTITUZIONE("Fai un cambio"),
    }

    val completo: Boolean
        get() = cosa != Cosa.SOSTITUZIONE || (esce != null && entra != null)

    /** Null finche' non e' completo: e' l'unico modo di non salvare mezzi ordini. */
    fun costruisci(id: Int): ConditionalOrder? {
        val trigger = when (quando) {
            Quando.SOTTO -> OrderTrigger.SottoDalMinuto(minuto)
            Quando.PARI -> OrderTrigger.PariDalMinuto(minuto)
            Quando.AVANTI -> OrderTrigger.InVantaggioDiDalMinuto(goals = 1, minute = minuto)
            Quando.SEMPRE -> OrderTrigger.DalMinuto(minuto)
            Quando.STANCO -> OrderTrigger.QualcunoInRiserva(soglia)
        }
        val action = when (cosa) {
            Cosa.ASSETTO -> OrderAction.CambiaAssetto(stance)
            Cosa.RITMO -> OrderAction.CambiaRitmo(tempo)
            Cosa.PRESSING -> OrderAction.CambiaPressing(pressing)
            Cosa.AMPIEZZA -> OrderAction.CambiaAmpiezza(width)
            Cosa.SOSTITUZIONE -> {
                val out = esce ?: return null
                val inPlayer = entra ?: return null
                OrderAction.Sostituisci(PlayerId(out), PlayerId(inPlayer))
            }
        }
        return ConditionalOrder(id = id, trigger = trigger, action = action)
    }
}
