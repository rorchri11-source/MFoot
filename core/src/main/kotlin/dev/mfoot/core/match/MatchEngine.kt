package dev.mfoot.core.match

import dev.mfoot.core.config.EngineConfig
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Lane
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Zone
import dev.mfoot.core.model.cardFactor
import dev.mfoot.core.model.foulFactor
import dev.mfoot.core.model.injuryFactor
import dev.mfoot.core.model.staminaFactor
import dev.mfoot.core.rng.DeterministicRandom
import dev.mfoot.core.rng.MathX

/**
 * Lo stato della partita all'intervallo.
 *
 * Il server simula il primo tempo, salva questo, apre la finestra per cambi e correzioni
 * e poi simula il secondo tempo. Due simulazioni per partita invece di un tick continuo:
 * chi c'e' all'intervallo ha voce in capitolo, chi non c'e' non viene tagliato fuori
 * perche' i suoi ordini condizionali girano comunque.
 */
data class HalfTimeState(
    val seed: Long,
    val homeGoals: Int,
    val awayGoals: Int,
    val events: List<MatchEvent>,
    val stats: Map<PlayerId, PlayerMatchStats>,
    val homeSetup: TeamSetup,
    val awaySetup: TeamSetup,
    val homeBooked: Set<PlayerId>,
    val awayBooked: Set<PlayerId>,
    val homeFiredOrders: Set<Int>,
    val awayFiredOrders: Set<Int>,
    val homePossessionActions: Int,
    val totalActions: Int,
    val momentum: Double,
    val importance: MatchImportance,
    val homeShots: Int,
    val awayShots: Int,
    val homeXg: Double,
    val awayXg: Double,
)

/**
 * Il motore di simulazione.
 *
 * ## Come funziona
 *
 * La partita e' una sequenza di ~120 azioni. A ogni azione si confronta il rating della
 * zona in cui si trova la palla con quello della zona avversaria che la fronteggia, e
 * una sigmoide trasforma la differenza in una probabilita' di avanzare. In zona
 * offensiva si conclude.
 *
 * ## Perche' gli highlight sono irregolari
 *
 * Non c'e' nessun codice che decide "adesso un'occasione". Le catene di possesso hanno
 * lunghezza variabile per costruzione, quindi capitano quindici minuti di nulla seguiti
 * da tre occasioni in due minuti. E' emergente, ed e' esattamente cio' che rende una
 * partita simulata degna di essere guardata.
 *
 * ## Determinismo
 *
 * Stesso seed e stessi input producono lo stesso identico risultato. Il server simula
 * una volta e salva la timeline; i client la riproducono senza rieseguire niente.
 * I due tempi usano flussi casuali separati ([DeterministicRandom.fork]), cosi' lo
 * stato dell'intervallo non deve trasportare lo stato del generatore.
 */
object MatchEngine {

    private const val STREAM_FIRST_HALF = 1L
    private const val STREAM_SECOND_HALF = 2L

    private const val HALF_MINUTES = 45
    private const val FULL_MINUTES = 90

    fun simulate(
        home: TeamSetup,
        away: TeamSetup,
        config: LeagueConfig,
        seed: Long,
        importance: MatchImportance = MatchImportance.CAMPIONATO,
    ): MatchResult {
        val halfTime = simulateFirstHalf(home, away, config, seed, importance)
        return simulateSecondHalf(halfTime, config)
    }

    fun simulateFirstHalf(
        home: TeamSetup,
        away: TeamSetup,
        config: LeagueConfig,
        seed: Long,
        importance: MatchImportance = MatchImportance.CAMPIONATO,
    ): HalfTimeState {
        val sim = Simulation(
            home = home,
            away = away,
            config = config,
            seed = seed,
            importance = importance,
            rng = DeterministicRandom(seed).fork(STREAM_FIRST_HALF),
        )
        sim.emitKickoff()
        sim.runMinutes(0, HALF_MINUTES)
        sim.emitHalfTime()
        return sim.toHalfTimeState()
    }

    /**
     * Riprende dallo stato dell'intervallo. I setup possono essere stati modificati dai
     * manager nella finestra: se non lo sono, si riparte con quelli di prima.
     */
    fun simulateSecondHalf(
        state: HalfTimeState,
        config: LeagueConfig,
        home: TeamSetup = state.homeSetup,
        away: TeamSetup = state.awaySetup,
    ): MatchResult {
        val sim = Simulation(
            home = home,
            away = away,
            config = config,
            seed = state.seed,
            importance = state.importance,
            rng = DeterministicRandom(state.seed).fork(STREAM_SECOND_HALF),
            restored = state,
        )
        sim.runMinutes(HALF_MINUTES, FULL_MINUTES)
        sim.emitFullTime()
        return sim.toResult()
    }

    // =====================================================================================

    private class Simulation(
        var home: TeamSetup,
        var away: TeamSetup,
        val config: LeagueConfig,
        val seed: Long,
        val importance: MatchImportance,
        val rng: DeterministicRandom,
        restored: HalfTimeState? = null,
    ) {
        val engine: EngineConfig = config.engine

        var homeGoals = restored?.homeGoals ?: 0
        var awayGoals = restored?.awayGoals ?: 0
        val events = (restored?.events ?: emptyList()).toMutableList()
        val stats = (restored?.stats ?: emptyMap()).toMutableMap()
        val homeBooked = (restored?.homeBooked ?: emptySet()).toMutableSet()
        val awayBooked = (restored?.awayBooked ?: emptySet()).toMutableSet()
        val homeFired = (restored?.homeFiredOrders ?: emptySet()).toMutableSet()
        val awayFired = (restored?.awayFiredOrders ?: emptySet()).toMutableSet()

        var homePossessionActions = restored?.homePossessionActions ?: 0
        var totalActions = restored?.totalActions ?: 0
        var momentum = restored?.momentum ?: 0.0
        var homeShots = restored?.homeShots ?: 0
        var awayShots = restored?.awayShots ?: 0
        var homeXg = restored?.homeXg ?: 0.0
        var awayXg = restored?.awayXg ?: 0.0

        /** Chi ha la palla in questo momento. */
        var possession: Side = Side.CASA

        /** Zona della palla, dal punto di vista di chi la possiede. */
        var zone: Zone = Zone.MID_C

        /** Ultimo giocatore che ha toccato: e' lui a prendersi l'assist. */
        var lastToucher: PlayerId? = null

        var homeStrength = computeStrength(home, isHome = true)
        var awayStrength = computeStrength(away, isHome = false)

        /** I rating si ricalcolano ogni tanto per riflettere la stanchezza accumulata. */
        var actionsSinceRefresh = 0

        /**
         * Stanchezza accumulata ma non ancora scalata, in frazioni di punto.
         *
         * Serve perche' la stamina e' un intero mentre il consumo per azione vale ~0,26:
         * scalandolo e riarrotondando a ogni azione il valore resterebbe fermo per
         * sempre, e la rotazione della rosa non servirebbe a niente.
         */
        val staminaDebt = mutableMapOf<PlayerId, Double>()

        /** Da che minuto ciascun giocatore e' in campo, per contare i minuti giocati. */
        val onPitchSince = mutableMapOf<PlayerId, Int>()

        /** Quanto e' in palla oggi ciascuno. Estratta una volta e tenuta. */
        private val giornate = mutableMapOf<PlayerId, Double>()

        init {
            // Nel secondo tempo si riparte dal 45': i minuti del primo sono gia' contati.
            val startMinute = if (restored != null) HALF_MINUTES else 0
            (home.lineup.slots + away.lineup.slots).forEach {
                onPitchSince[it.player.id] = startMinute
            }
        }

        // ------------------------------------------------------------------ ciclo

        fun runMinutes(fromMinute: Int, toMinute: Int) {
            val actions = actionsFor(toMinute - fromMinute)
            repeat(actions) { index ->
                val minute = fromMinute + (index * (toMinute - fromMinute)) / actions
                evaluateOrders(minute)
                step(minute)
                drainStamina(minute)
                decayMomentum()
                maybeRefreshStrengths()
            }
        }

        /**
         * Il ritmo di gioco delle due squadre allunga o accorcia la partita in numero di
         * azioni: piu' azioni significa piu' occasioni per entrambe, quindi piu' varianza.
         * E' un vantaggio per chi e' piu' debole e un rischio per chi e' avanti.
         */
        private fun actionsFor(minutes: Int): Int {
            val tempoFactor = (home.tactics.tempo.actionMultiplier +
                away.tactics.tempo.actionMultiplier) / 2.0
            val scaled = azioniPerPartita * tempoFactor * (minutes / FULL_MINUTES.toDouble())
            return StrictMath.round(scaled).toInt().coerceAtLeast(1)
        }

        /**
         * Quante azioni dura una partita.
         *
         * Col motore a duelli sono piu' del doppio, e non e' una manopola da tarare a
         * gusto: e' aritmetica. Prima un'azione era **una** decisione — passi o perdi —
         * mentre adesso una catena di possesso e' fatta di episodi, e per arrivare in
         * porta ne servono diversi. Con lo stesso numero si giocherebbe una partita lunga
         * un terzo.
         */
        private val azioniPerPartita: Int
            get() = if (engine.duelliAttivi) engine.actionsPerMatchDuelli else engine.actionsPerMatch

        /**
         * Rimette in scala una probabilita' **per azione**.
         *
         * Falli, infortuni e fuorigioco erano tarati su 118 azioni. Con i duelli le azioni
         * sono piu' del doppio, quindi le stesse probabilita' produrrebbero il doppio dei
         * falli e il doppio degli infortuni senza che nessuno abbia deciso niente. Una
         * riga sola invece di cinque manopole nuove: sono la stessa identica cosa espressa
         * su una partita piu' lunga.
         */
        private fun perAzione(p: Double): Double =
            if (engine.duelliAttivi) {
                p * engine.actionsPerMatch.toDouble() / engine.actionsPerMatchDuelli
            } else {
                p
            }

        private fun step(minute: Int) {
            totalActions++
            if (possession == Side.CASA) homePossessionActions++

            val attacker = if (possession == Side.CASA) homeStrength else awayStrength
            val defender = if (possession == Side.CASA) awayStrength else homeStrength
            val attackerSetup = if (possession == Side.CASA) home else away
            val defenderSetup = if (possession == Side.CASA) away else home

            val toucher = pickToucher(attacker, zone)

            // Il fallo puo' interrompere qualsiasi azione; il pressing alto ne produce di piu'.
            val foulChance = perAzione(engine.foulChance) *
                defenderSetup.tactics.pressing.foulMultiplier
            if (rng.chance(foulChance)) {
                resolveFoul(minute, defenderSetup)
                return
            }

            if (config.rules.injuriesEnabled && rng.chance(injuryChanceFor(toucher, attackerSetup))) {
                resolveInjury(minute, toucher, attackerSetup)
                return
            }

            val leva = attackerSetup.tactics.stance.shotChanceFactor
            val shotChance = if (engine.duelliAttivi) {
                engine.shotChanceDuelli * (1.0 + (leva - 1.0) * engine.smorzamentoAssetto)
            } else {
                engine.shotChanceInAttackingZone * leva
            }
            // Fuorigioco: l'azione muore prima di diventare una conclusione. Non esisteva
            // affatto — nemmeno come evento — e nel calcio vero capita quattro volte a
            // partita. Chi gioca alto ne prende di piu': la linea difensiva avversaria
            // alza la probabilita'.
            if (zone.isAttacking && rng.chance(perAzione(engine.offsideChance))) {
                emit(
                    minute, MatchEventType.FUORIGIOCO, possession, zone = zone, player = toucher,
                    description = "${nameOf(toucher)} parte in fuorigioco",
                )
                possession = possession.other()
                zone = Zone.DIF_C
                lastToucher = null
                return
            }

            if (zone.isAttacking && rng.chance(shotChance)) {
                resolveShot(minute, toucher, defenderSetup)
                return
            }

            // Da qui in poi si decide se l'azione va avanti. E' l'unico punto in cui i due
            // motori si separano: tutto quello che sta sopra — falli, infortuni,
            // fuorigioco, conclusioni — e' identico.
            if (engine.duelliAttivi) {
                risolviDuello(minute, toucher, attacker, defender, attackerSetup, defenderSetup)
                return
            }

            val delta = attacker.rating(zone) - defender.rating(zone.mirror())
            val advanceChance = MathX.sigmoid(delta, engine.sigmoidK)

            if (rng.chance(advanceChance)) {
                val next = avanza(zone, attackerSetup.tactics)
                if (next == null) {
                    // Non si puo' andare oltre: si conclude.
                    resolveShot(minute, toucher, defenderSetup)
                } else {
                    lastToucher = toucher
                    bump(toucher) { it.copy(keyActions = it.keyActions + 1) }
                    zone = next
                    emit(
                        minute, MatchEventType.AVANZAMENTO, possession,
                        zone = next, player = toucher,
                        description = "${nameOf(toucher)} porta avanti l'azione",
                    )
                }
            } else {
                resolveTurnover(minute, defenderSetup)
            }
        }

        // ------------------------------------------------------------------ i duelli

        /**
         * Un'azione decisa da una contesa fra due giocatori con un nome.
         *
         * ## Cosa sostituisce
         *
         * Due righe: `rating(zona) - rating(zona specchiata)` dentro una sigmoide. Il
         * problema non era che fossero sbagliate, era che **decidevano prima che ci fosse
         * qualcuno**. [ZoneRatings] schiaccia gli undici in una media, e da li' in poi i
         * nomi sono decorazione applicata a un esito gia' scelto: e' il motivo per cui due
         * giocatori con lo stesso overall giocavano la stessa identica partita, e per cui
         * `DRIBBLING`, `VELOCITA`, `DIFESA` e `INTERCETTAZIONE` non vincevano mai niente.
         *
         * ## I modificatori restano gli stessi
         *
         * Vantaggio del campo, allenatore, assetto, pressing, inerzia, quanti uomini
         * gravitano li', stanchezza, morale, forma: tutto passa da [ZoneContext] e da
         * [ZoneRatings.condizione], che sono **le stesse funzioni** che compongono il
         * rating di zona. Se fossero due formule separate, il giorno che si ritocca il
         * vantaggio del campo se ne aggiusterebbe una sola.
         */
        private fun risolviDuello(
            minute: Int,
            toucherId: PlayerId,
            attacker: ZoneStrength,
            defender: ZoneStrength,
            attackerSetup: TeamSetup,
            defenderSetup: TeamSetup,
        ) {
            val slot = slotDi(attackerSetup, toucherId)
            if (slot == null) {
                resolveTurnover(minute, defenderSetup)
                return
            }

            val duello = Intenzioni.scegli(slot.player, zone.band, attackerSetup.tactics, rng)

            // Il passaggio non si gioca contro chi ti sta addosso, ma contro chi legge dove
            // stai per mandarla: la zona che conta e' quella d'arrivo.
            val bersaglio = if (duello == Duello.PASSAGGIO) (zone.advance() ?: zone) else zone
            val zonaDifesa = bersaglio.mirror()

            val avversarioId = pickToucher(defender, zonaDifesa)
            val avversario = slotDi(defenderSetup, avversarioId)

            // Sul cross chi ha la palla non salta: la mette in mezzo, e a saltare e' chi
            // attacca l'area. Senza questa riga i cross li incornerebbe l'ala che li batte,
            // che e' lo stesso difetto per cui i corner li segnava sempre lo stesso uomo.
            val contende = if (duello == Duello.AEREO) incornatore(attackerSetup) ?: slot else slot

            val spintaAttacco = spintaDi(possession, minute)
            val spintaDifesa = spintaDi(possession.other(), minute)

            val forzaAttacco =
                forzaInDuello(contende, duello, Lato.ATTACCO, bersaglio, attacker, spintaAttacco)
            val forzaDifesa = avversario
                ?.let { forzaInDuello(it, duello, Lato.DIFESA, zonaDifesa, defender, spintaDifesa) }
                ?: ZoneStrength.EMPTY_ZONE_RATING

            val vinto = rng.chance(Duelli.esito(duello, forzaAttacco, forzaDifesa, engine))

            // Il passaggio non entra nel conto dei duelli: nel calcio un passaggio non e' un
            // duello, ed e' la giocata piu' frequente di tutte. Contarlo gonfierebbe la voce
            // «duelli vinti» fino a renderla la stessa cosa di «palloni toccati».
            if (duello != Duello.PASSAGGIO) {
                bump(contende.player.id) {
                    if (vinto) {
                        it.copy(duelsWon = it.duelsWon + 1)
                    } else {
                        it.copy(duelsLost = it.duelsLost + 1)
                    }
                }
                avversario?.let { a ->
                    bump(a.player.id) {
                        if (vinto) {
                            it.copy(duelsLost = it.duelsLost + 1)
                        } else {
                            it.copy(duelsWon = it.duelsWon + 1)
                        }
                    }
                }
            }

            if (duello == Duello.DRIBBLING) {
                bump(contende.player.id) { it.copy(dribblesAttempted = it.dribblesAttempted + 1) }
            }
            if (duello == Duello.PASSAGGIO) {
                bump(contende.player.id) { it.copy(passesAttempted = it.passesAttempted + 1) }
            }

            if (vinto) {
                duelloVinto(minute, duello, contende, avversario, defenderSetup)
            } else {
                duelloPerso(minute, duello, contende, avversario, defenderSetup)
            }
        }

        private fun duelloVinto(
            minute: Int,
            duello: Duello,
            chi: LineupSlot,
            avversario: LineupSlot?,
            defenderSetup: TeamSetup,
        ) {
            val id = chi.player.id

            when (duello) {
                // Reggere il pallone non fa guadagnare campo: fa restare la palla dov'e'.
                // E' il mestiere del centravanti di peso, ed e' giusto che costi un'azione.
                Duello.CONTRASTO -> {
                    lastToucher = id
                    return
                }

                // Il cross vinto in area **e'** l'occasione: non si avanza, si conclude.
                Duello.AEREO -> if (zone.isAttacking) {
                    emit(
                        minute, MatchEventType.CROSS, possession, zone = zone, player = id,
                        secondaryPlayer = avversario?.player?.id,
                        description = "Cross in mezzo, ci arriva ${nameOf(id)}",
                    )
                    lastToucher = id
                    resolveShot(minute, id, defenderSetup)
                    return
                }

                else -> Unit
            }

            if (duello == Duello.PASSAGGIO) {
                bump(id) { it.copy(passesCompleted = it.passesCompleted + 1) }
            }
            if (duello == Duello.DRIBBLING) {
                bump(id) { it.copy(dribblesCompleted = it.dribblesCompleted + 1) }
                avversario?.let { a ->
                    bump(a.player.id) { it.copy(dribblesSuffered = it.dribblesSuffered + 1) }
                }
            }

            val tattica = if (possession == Side.CASA) home.tactics else away.tactics
            val next = avanza(zone, tattica)
            lastToucher = id
            bump(id) { it.copy(keyActions = it.keyActions + 1) }

            if (next == null) {
                // Gia' in area: la palla ci resta e gira. Il motore vecchio qui concludeva,
                // e aveva ragione — arrivarci era raro. Coi duelli si sta in zona d'attacco
                // per piu' episodi di fila, e far diventare un tiro **ogni** duello vinto
                // li' dentro produceva quarantatre' tiri a partita invece di ventiquattro.
                // A concludere ci pensa il tiro di dado in cima all'azione.
                return
            }
            zone = next

            // Non ogni episodio riuscito diventa una riga di cronaca. Un passaggio dentro
            // la propria meta' campo e' rumore, e con piu' del doppio delle azioni di prima
            // la timeline diventerebbe illeggibile — e pesante, visto che viaggia dentro
            // `match_results.timeline` e la rilegge ogni telefono. Si racconta quello che
            // ha due nomi e un merito: chi salta l'uomo, chi va via in velocita', chi apre
            // il gioco dentro l'ultimo terzo.
            when (duello) {
                Duello.DRIBBLING -> emit(
                    minute, MatchEventType.DRIBBLING_RIUSCITO, possession, zone = next,
                    player = id, secondaryPlayer = avversario?.player?.id,
                    description = avversario
                        ?.let { "${nameOf(id)} salta ${nameOf(it.player.id)}" }
                        ?: "${nameOf(id)} salta l'uomo",
                )

                Duello.CORSA -> emit(
                    minute, MatchEventType.SCATTO, possession, zone = next,
                    player = id, secondaryPlayer = avversario?.player?.id,
                    description = avversario
                        ?.let { "${nameOf(id)} brucia ${nameOf(it.player.id)} in velocita'" }
                        ?: "${nameOf(id)} va via in velocita'",
                )

                Duello.AEREO -> emit(
                    minute, MatchEventType.CROSS, possession, zone = next, player = id,
                    description = "${nameOf(id)} apre il gioco lungo",
                )

                Duello.PASSAGGIO -> if (next.isAttacking) {
                    emit(
                        minute, MatchEventType.PASSAGGIO_FILTRANTE, possession, zone = next,
                        player = id,
                        description = "${nameOf(id)} apre il gioco dentro l'ultimo terzo",
                    )
                }

                Duello.CONTRASTO -> Unit
            }
        }

        private fun duelloPerso(
            minute: Int,
            duello: Duello,
            chi: LineupSlot,
            avversario: LineupSlot?,
            defenderSetup: TeamSetup,
        ) {
            val vincitore = avversario?.player?.id
            if (vincitore != null) {
                bump(vincitore) { it.copy(tackles = it.tackles + 1) }

                val tipo = when (duello) {
                    Duello.DRIBBLING -> MatchEventType.DRIBBLING_FALLITO
                    Duello.PASSAGGIO, Duello.AEREO -> MatchEventType.ANTICIPO
                    else -> MatchEventType.CONTRASTO
                }
                val racconto = when (duello) {
                    Duello.DRIBBLING -> "${nameOf(vincitore)} chiude su ${nameOf(chi.player.id)}"
                    Duello.PASSAGGIO -> "${nameOf(vincitore)} legge il passaggio e intercetta"
                    Duello.AEREO -> "${nameOf(vincitore)} anticipa di testa"
                    Duello.CORSA -> "${nameOf(vincitore)} arriva prima su ${nameOf(chi.player.id)}"
                    Duello.CONTRASTO -> "${nameOf(vincitore)} gli porta via il pallone"
                }
                emit(
                    minute, tipo, possession.other(), zone = zone.mirror(),
                    player = vincitore, secondaryPlayer = chi.player.id,
                    description = racconto,
                )
            }

            // L'angolo su attacco spento resta com'era, e con lui tutta la catena delle
            // palle inattive: [resolveTurnover] emetterebbe un secondo evento di recupero
            // sopra quello appena scritto, quindi qui si cambia possesso a mano.
            if (zone.isAttacking && rng.chance(engine.cornerChanceOnLostAttack)) {
                val attackerSetup = if (possession == Side.CASA) home else away
                emit(
                    minute, MatchEventType.ANGOLO, possession, zone = zone,
                    player = SetPieces.taker(attackerSetup.lineup, MatchDuty.ANGOLI)?.id,
                    description = "Angolo per ${teamName(possession)}",
                )
                resolveCorner(minute, attackerSetup, defenderSetup)
                return
            }

            possession = possession.other()
            zone = zone.mirror()
            lastToucher = null
        }

        /**
         * Quanto vale questo giocatore in questa contesa, qui e adesso.
         *
         * La formula ricalca riga per riga quella del rating di zona — `(qualita' +
         * condizione) * fattore + bonus` — perche' deve valere la stessa cosa applicata a
         * uno invece che a undici. L'unica sostituzione e' la **qualita'**: al posto della
         * media di reparto di `BandWeights` c'e' quanto vale lui *in quel gesto*.
         */
        private fun forzaInDuello(
            slot: LineupSlot,
            duello: Duello,
            lato: Lato,
            inZone: Zone,
            strength: ZoneStrength,
            spinta: Double,
        ): Double {
            val qualita = Duelli.valore(duello, lato, slot.player.attributes) * slot.fitness
            val condizione = ZoneRatings.condizione(slot.player, importance, engine) +
                giornataDi(slot.player) + spinta
            return strength.contesto(inZone).applica(qualita + condizione).coerceIn(1.0, 99.0)
        }

        /** Quanto e' in palla oggi. Estratta una volta sola e tenuta: vedi [Carattere]. */
        private fun giornataDi(player: Player): Double =
            giornate.getOrPut(player.id) { Carattere.giornata(player, seed, engine) }

        private fun spintaDi(side: Side, minute: Int): Double {
            val scarto = if (side == Side.CASA) homeGoals - awayGoals else awayGoals - homeGoals
            val setup = if (side == Side.CASA) home else away
            return Carattere.spintaDiRimonta(setup.lineup, scarto, minute, engine)
        }

        private fun slotDi(setup: TeamSetup, id: PlayerId): LineupSlot? =
            setup.lineup.slots.firstOrNull { it.player.id == id }

        /**
         * Dove arriva l'azione andando avanti, corsia compresa.
         *
         * Sostituisce `Zone.advance()`, che conservava la corsia. Insieme a `mirror()`, che
         * manda il centro nel centro, e alle ripartenze tutte centrali, quello bastava a
         * rendere la corsia centrale **assorbente**: la palla nasceva in `MID_C` e non ne
         * usciva mai piu'. Sei zone su nove restavano vuote per tutta la partita.
         */
        private fun avanza(from: Zone, tactics: Tactics): Zone? {
            val band = from.band.advance() ?: return null
            return Zone.of(prossimaCorsia(from.lane, tactics), band)
        }

        /**
         * Da che parte si sviluppa adesso.
         *
         * Quasi sempre dov'era; ogni tanto si allarga o rientra; il cambio di fronte da una
         * fascia all'altra e' raro, perche' nel calcio si passa quasi sempre dal centro.
         * La larghezza tattica pesa qui — ed e' il primo posto in cui pesa davvero.
         */
        private fun prossimaCorsia(attuale: Lane, tactics: Tactics): Lane =
            rng.pickWeighted(Lane.entries) { lane ->
                val base = when {
                    lane == attuale -> engine.pesoStessaCorsia
                    attuale == Lane.C || lane == Lane.C -> 1.0
                    else -> engine.pesoCorsiaOpposta
                }
                base * tactics.width.factorFor(lane)
            }

        /**
         * Chi attacca l'area sul cross.
         *
         * Stessi pesi dei colpi di testa di [Conclusioni]: un centrale vale 5,5 contro l'1
         * di un terzino. Sceglierlo col solo stacco migliore darebbe di nuovo il difetto
         * dei corner — un uomo solo che si prende tutti i palloni alti della stagione.
         */
        private fun incornatore(setup: TeamSetup): LineupSlot? =
            setup.lineup.outfield.takeIf { it.isNotEmpty() }?.let { candidati ->
                rng.pickWeighted(candidati) { slot ->
                    Conclusioni.peso(TipoConclusione.DI_TESTA, slot.position) *
                        MathX.remap(staccoDi(slot.player), 40.0, 95.0, 0.6, 1.6)
                }
            }

        // ------------------------------------------------------------ risoluzioni

        private fun resolveTurnover(minute: Int, defenderSetup: TeamSetup) {
            val defendingStrength = if (possession == Side.CASA) awayStrength else homeStrength
            val winner = pickToucher(defendingStrength, zone.mirror())
            bump(winner) { it.copy(tackles = it.tackles + 1) }

            // Un attacco che si spegne in area avversaria produce spesso un angolo.
            if (zone.isAttacking && rng.chance(engine.cornerChanceOnLostAttack)) {
                val attackerSetup = if (possession == Side.CASA) home else away
                emit(
                    minute, MatchEventType.ANGOLO, possession, zone = zone,
                    player = SetPieces.taker(attackerSetup.lineup, MatchDuty.ANGOLI)?.id,
                    description = "Angolo per ${teamName(possession)}",
                )
                resolveCorner(minute, attackerSetup, defenderSetup)
                return
            }

            emit(
                minute, MatchEventType.CONTRASTO, possession.other(), zone = zone.mirror(),
                player = winner,
                description = "${nameOf(winner)} recupera palla",
            )

            possession = possession.other()
            zone = zone.mirror()
            lastToucher = null
        }

        private fun resolveFoul(minute: Int, defenderSetup: TeamSetup) {
            val defendingStrength = if (possession == Side.CASA) awayStrength else homeStrength
            val offender = pickFalloso(defendingStrength, zone.mirror(), defenderSetup)
            val offendingSide = possession.other()
            bump(offender) { it.copy(fouls = it.fouls + 1) }

            // Chi va in ritardo si prende anche piu' cartellini: e' la seconda meta' del
            // tratto. Senza, una testa calda commetteva piu' falli ma li pagava come tutti.
            val cartellino = playerOf(offender)?.traits?.cardFactor() ?: 1.0

            // Fallo da rigore: solo in area centrale, e raro.
            if (zone == Zone.ATT_C && rng.chance(0.075)) {
                resolvePenalty(minute, offender)
                return
            }

            if (config.rules.suspensionsEnabled &&
                rng.chance(engine.redCardChanceOnFoul * cartellino)
            ) {
                sendOff(minute, offender, offendingSide)
                return
            }

            if (config.rules.suspensionsEnabled &&
                rng.chance(engine.yellowCardChanceOnFoul * cartellino)
            ) {
                book(minute, offender, offendingSide)
                return
            }

            emit(
                minute, MatchEventType.FALLO, offendingSide, zone = zone,
                player = offender,
                description = "Fallo di ${nameOf(offender)}",
            )

            // Fallo vicino all'area: si batte, e a batterla e' chi il manager ha
            // designato. Prima di oggi un fallo in zona offensiva finiva qui, e le
            // punizioni non esistevano nel gioco.
            if (zone.isAttacking && rng.chance(engine.freeKickShotChance)) {
                resolveFreeKick(minute, defenderSetup)
            }
        }

        /**
         * Una conclusione in azione.
         *
         * ## Chi tira non e' chi ha la palla
         *
         * Era cosi', ed era il difetto: [toccante] e' chi ha portato l'azione, e alle zone
         * d'attacco contribuiscono solo punte, esterni e trequartista. Un difensore
         * centrale non poteva concludere **mai** — nemmeno su calcio d'angolo, che nel
         * calcio vero e' il modo in cui i difensori segnano.
         *
         * Adesso si sceglie prima **che conclusione e'** e poi chi la prende, con i pesi
         * per ruolo di [Conclusioni]. Chi aveva la palla diventa il primo candidato
         * all'assist, che e' il suo mestiere.
         */
        private fun resolveShot(minute: Int, toccante: PlayerId, defenderSetup: TeamSetup) {
            val attackerSetup = if (possession == Side.CASA) home else away
            val tipo = scegliTipo()
            val shooter = scegliTiratore(attackerSetup, tipo) ?: toccante
            val player = playerOf(shooter) ?: return

            // Chi ha portato l'azione serve l'assist, se non e' lui a concludere. Se ha
            // concluso lui, l'assist lo cerca fra i compagni con i pesi da rifinitore.
            lastToucher = if (toccante != shooter) toccante else scegliAssist(attackerSetup, shooter)

            resolveAttempt(minute, shooter, defenderSetup, xgDi(tipo, player), tipo)
        }

        /** Che conclusione e', pescata sui pesi della configurazione. */
        private fun scegliTipo(): TipoConclusione {
            val tipi = TipoConclusione.entries
            return rng.pickWeighted(tipi) { Conclusioni.pesoTipo(it, engine) }
        }

        /**
         * Chi la prende, fra **tutti gli undici** e non solo fra chi sta in zona.
         *
         * Il peso del ruolo dice chi ci arriva, la qualita' dice quanto e' bravo a
         * finalizzare: un centrale con un buon stacco attacca i corner piu' di un terzino,
         * e un centrocampista con il tiro tira da fuori piu' di un difensore.
         */
        private fun scegliTiratore(setup: TeamSetup, tipo: TipoConclusione): PlayerId? {
            val inCampo = setup.lineup.outfield
            if (inCampo.isEmpty()) return null

            return rng.pickWeighted(inCampo) { slot ->
                val ruolo = Conclusioni.peso(tipo, slot.position)
                val abilita = if (Conclusioni.diTesta(tipo)) staccoDi(slot.player) else tiroDi(slot.player)
                ruolo * (0.5 + abilita / 90.0)
            }.player.id
        }

        /** Chi serve il pallone, quando a concludere e' stato chi lo portava. */
        private fun scegliAssist(setup: TeamSetup, shooter: PlayerId): PlayerId? {
            val altri = setup.lineup.outfield.filter { it.player.id != shooter }
            if (altri.isEmpty()) return null
            return rng.pickWeighted(altri) { slot ->
                Conclusioni.pesoAssist(slot.position) *
                    (0.5 + slot.player.attributes[Attr.PASSAGGIO] / 90.0)
            }.player.id
        }

        /** Quanto e' pericolosa questa conclusione, per questo tiratore. */
        private fun xgDi(tipo: TipoConclusione, shooter: Player): Double {
            val base = Conclusioni.xgBase(tipo, engine)
            val abilita = if (Conclusioni.diTesta(tipo)) staccoDi(shooter) else tiroDi(shooter)
            val qualita = MathX.remap(abilita, 40.0, 95.0, engine.finishingMin, engine.finishingMax)

            // Il piede debole non c'entra con i colpi di testa.
            val piede = if (Conclusioni.diTesta(tipo) || !rng.chance(0.33)) {
                1.0
            } else {
                1.0 - engine.weakFootPenaltyPerStar * (5 - shooter.weakFoot)
            }

            return (base * qualita * piede).coerceIn(0.01, 0.85)
        }

        /** Quanto uno vale al tiro: e' il tiro, con un po' di tecnica. */
        private fun tiroDi(player: Player): Double =
            player.attributes[Attr.TIRO] * 0.7 + player.attributes[Attr.TECNICA] * 0.3

        /**
         * L'angolo, che fino al 2026-08-24 non produceva niente.
         *
         * Chi batte decide **se** il pallone arriva; chi stacca decide **cosa** ne esce.
         * Sono due uomini diversi e due attributi diversi — passaggio e tecnica da una
         * parte, fisico e posizionamento dall'altra — ed e' il motivo per cui l'incarico
         * del battitore ha senso di esistere: senza, il corner sarebbe di nuovo una
         * proprieta' della squadra invece che di una persona.
         *
         * L'assist va a chi ha crossato, come nel calcio.
         */
        private fun resolveCorner(minute: Int, attackerSetup: TeamSetup, defenderSetup: TeamSetup) {
            // Senza nessuno in campo non c'e' angolo da battere: caso impossibile con il
            // minimo regolamentare di sette, ma il motore non deve fidarsi di un invariante
            // che vive in un'altra classe.
            val battitore = SetPieces.taker(attackerSetup.lineup, MatchDuty.ANGOLI) ?: return
            val qualita = SetPieces.aptitude(battitore, MatchDuty.ANGOLI)
            val arriva = MathX.remap(
                qualita, 40.0, 95.0,
                engine.cornerConversionMin, engine.cornerConversionMax,
            )

            if (!rng.chance(arriva)) {
                // Allontanata: la difesa spazza e riparte.
                possession = possession.other()
                zone = zone.mirror()
                lastToucher = null
                return
            }

            // CHI LA INCORNA NON E' SEMPRE LO STESSO
            //
            // Era `maxWithOrNull { staccoDi }`: il miglior stacco della squadra attaccava
            // **tutti** i corner della stagione, e segnava lui tutti i gol di testa. Nel
            // calcio in area ci salgono in sei, e chi ci arriva cambia ogni volta.
            //
            // Il peso e' quello dei colpi di testa: il centrale ha 5,5 contro l'1 del
            // terzino, e la punta 10. E' la riga che fa segnare qualche gol a stagione a un
            // difensore — cosa che prima non poteva succedere per costruzione.
            val incornatore = rng.pickWeighted(attackerSetup.lineup.outfield) { slot ->
                Conclusioni.peso(TipoConclusione.DI_TESTA, slot.position) *
                    (0.5 + staccoDi(slot.player) / 90.0)
            }.player

            // Sul corner si conclude sempre dal centro dell'area, chiunque abbia crossato.
            zone = Zone.ATT_C
            lastToucher = battitore.id.takeIf { it != incornatore.id }
            resolveAttempt(
                minute, incornatore.id, defenderSetup,
                xgDi(TipoConclusione.DI_TESTA, incornatore), TipoConclusione.DI_TESTA,
            )
        }

        /**
         * La punizione dal limite.
         *
         * Nasce da un fallo gia' emesso in zona offensiva: l'evento del fallo resta dov'e'
         * e questa e' la conclusione che ne segue, cosi' la cronaca racconta i due momenti
         * come li racconterebbe una radio.
         */
        private fun resolveFreeKick(minute: Int, defenderSetup: TeamSetup) {
            val setup = if (possession == Side.CASA) home else away
            val battitore = SetPieces.taker(setup.lineup, MatchDuty.PUNIZIONI) ?: return
            val abilita = SetPieces.aptitude(battitore, MatchDuty.PUNIZIONI)
            val xg = MathX.remap(abilita, 40.0, 95.0, engine.freeKickXgMin, engine.freeKickXgMax)

            // Nessun assist su punizione diretta: il pallone lo mette dentro chi calcia.
            lastToucher = null
            resolveAttempt(minute, battitore.id, defenderSetup, xg)
        }

        /** Quanto uno vale in mischia: e' il colpo di testa, dedotto da cio' che esiste. */
        private fun staccoDi(player: Player): Double =
            player.attributes[Attr.FISICO] * 0.58 + player.attributes[Attr.POSIZIONAMENTO] * 0.42

        private fun xgDiTesta(player: Player): Double {
            val quality = MathX.remap(staccoDi(player), 40.0, 95.0, 0.55, 1.75)
            return (engine.baseXgCentral * quality).coerceIn(0.01, 0.75)
        }

        /**
         * Una conclusione, da qualunque cosa nasca: azione, angolo o punizione.
         *
         * Esiste separata da [resolveShot] perche' l'xG di un colpo di testa e quello di
         * una punizione non si calcolano dal tiro del giocatore — e prima del 2026-08-24
         * non esisteva nessun modo di concludere che non fosse un tiro in azione.
         */
        private fun resolveAttempt(
            minute: Int,
            shooter: PlayerId,
            defenderSetup: TeamSetup,
            xg: Double,
            tipo: TipoConclusione? = null,
        ) {
            val goalkeeper = defenderSetup.lineup.slots
                .firstOrNull { it.position.isGoalkeeper }?.player

            recordShot(xg)
            bump(shooter) { it.copy(shots = it.shots + 1) }

            // TIRO MURATO
            //
            // Un quarto delle conclusioni finisce addosso a un difensore e non arriva mai
            // al portiere. Senza, ogni tiro era gol, parata o fuori — e il portiere
            // risultava impegnato circa il doppio del vero, con parate a raffica in ogni
            // partita. Il tiro conta lo stesso: e' stato tentato.
            if (rng.chance(engine.blockedShotChance)) {
                emit(
                    minute, MatchEventType.TIRO_MURATO, possession, zone = zone, player = shooter,
                    description = "Murato il tiro di ${nameOf(shooter)}",
                )
                // Spesso rimane li' e diventa un angolo: e' il modo in cui i corner
                // nascono davvero.
                if (rng.chance(engine.cornerChanceOnLostAttack)) {
                    val attaccante = if (possession == Side.CASA) home else away
                    emit(
                        minute, MatchEventType.ANGOLO, possession, zone = zone,
                        player = SetPieces.taker(attaccante.lineup, MatchDuty.ANGOLI)?.id,
                        description = "Angolo per ${teamName(possession)}",
                    )
                    resolveCorner(minute, attaccante, defenderSetup)
                } else {
                    turnoverAfterShot()
                }
                return
            }

            // L'xG PUBBLICATO E' PER TENTATIVO, QUI IL TIRO E' GIA' PASSATO DAL MURO
            //
            // I valori di [Conclusioni.xgBase] sono quelli misurati nel calcio vero, e
            // valgono **per conclusione tentata** — murate comprese. Qui invece il muro e'
            // gia' stato superato: applicarli tali e quali toglierebbe una seconda volta
            // il quarto di tiri che il muro ha gia' tolto, e i gol scenderebbero a meta'.
            //
            // Misurato: con la correzione mancante la media era 1,53 gol a partita contro i
            // 2,7 del calcio vero, e i pareggi salivano al 38%.
            val perTentativo = xg / (1.0 - engine.blockedShotChance).coerceAtLeast(0.05)
            val goalChance = goalkeeper?.let { perTentativo * keeperFactor(it) } ?: (perTentativo * 1.6)
            val roll = rng.nextDouble()

            when {
                roll < goalChance -> scoreGoal(minute, shooter, MatchEventType.GOL, tipo)
                roll < goalChance + POST_CHANCE -> {
                    bump(shooter) { it.copy(shotsOnTarget = it.shotsOnTarget + 1) }
                    emit(
                        minute, MatchEventType.PALO, possession, zone = zone, player = shooter,
                        description = "${nameOf(shooter)} colpisce il palo!",
                    )
                    turnoverAfterShot()
                }
                roll < goalChance + POST_CHANCE + OFF_TARGET_CHANCE -> {
                    emit(
                        minute, MatchEventType.TIRO_FUORI, possession, zone = zone, player = shooter,
                        description = "${nameOf(shooter)} calcia fuori",
                    )
                    turnoverAfterShot()
                }
                else -> {
                    bump(shooter) { it.copy(shotsOnTarget = it.shotsOnTarget + 1) }
                    goalkeeper?.let { gk -> bump(gk.id) { it.copy(saves = it.saves + 1) } }
                    emit(
                        minute, MatchEventType.PARATA, possession, zone = zone,
                        player = shooter, secondaryPlayer = goalkeeper?.id,
                        description = goalkeeper?.let { "Para ${nameOf(it.id)} su ${nameOf(shooter)}" }
                            ?: "Tiro parato",
                    )
                    turnoverAfterShot()
                }
            }
        }

        private fun resolvePenalty(minute: Int, offender: PlayerId) {
            val attackingSetup = if (possession == Side.CASA) home else away
            emit(
                minute, MatchEventType.RIGORE_ASSEGNATO, possession, zone = Zone.ATT_C,
                player = offender,
                description = "Rigore per ${teamName(possession)}!",
            )

            val taker = choosePenaltyTaker(attackingSetup)
            bump(taker) { it.copy(shots = it.shots + 1) }

            val skill = playerOf(taker)?.let { p ->
                (p.attributes[Attr.TIRO] * 0.6 + p.attributes[Attr.TECNICA] * 0.4)
            } ?: 60.0
            val chance = MathX.remap(skill, 45.0, 95.0, 0.66, 0.88)

            if (rng.chance(chance)) {
                scoreGoal(minute, taker, MatchEventType.RIGORE_SEGNATO)
            } else {
                bump(taker) { it.copy(shotsOnTarget = it.shotsOnTarget + 1) }
                emit(
                    minute, MatchEventType.RIGORE_SBAGLIATO, possession, zone = Zone.ATT_C,
                    player = taker,
                    description = "${nameOf(taker)} sbaglia il rigore!",
                )
                turnoverAfterShot()
            }
        }

        private fun resolveInjury(minute: Int, playerId: PlayerId, setup: TeamSetup) {
            bump(playerId) { it.copy(injured = true) }
            emit(
                minute, MatchEventType.INFORTUNIO, possession, zone = zone, player = playerId,
                description = "${nameOf(playerId)} resta a terra",
            )
            // La sostituzione dell'infortunato la decidono gli ordini condizionali o
            // il manager all'intervallo: il motore non schiera al posto suo.
        }

        private fun scoreGoal(
            minute: Int,
            scorer: PlayerId,
            type: MatchEventType,
            tipo: TipoConclusione? = null,
        ) {
            val assist = lastToucher?.takeIf { it != scorer }
            bump(scorer) { it.copy(goals = it.goals + 1, shotsOnTarget = it.shotsOnTarget + 1) }
            assist?.let { a -> bump(a) { it.copy(assists = it.assists + 1) } }

            val defenderSetup = if (possession == Side.CASA) away else home
            defenderSetup.lineup.slots.firstOrNull { it.position.isGoalkeeper }?.player?.let { gk ->
                bump(gk.id) { it.copy(goalsConceded = it.goalsConceded + 1) }
            }

            if (possession == Side.CASA) homeGoals++ else awayGoals++

            // Chi subisce riceve una spinta: e' il meccanismo che genera le rimonte,
            // cioe' le partite di cui si parla il giorno dopo.
            momentum += if (possession == Side.CASA) -engine.momentumStrength else engine.momentumStrength

            // Il tipo di conclusione entra nella frase: «di testa», «da fuori». E la
            // differenza fra un tabellino e una telecronaca.
            val description = buildString {
                append("GOL! ").append(nameOf(scorer))
                tipo?.let { append(" ").append(it.etichetta) }
                if (assist != null) append(", assist di ").append(nameOf(assist))
            }
            emit(minute, type, possession, zone = zone, player = scorer, secondaryPlayer = assist,
                description = description)

            // Si riparte dal centro, palla a chi ha subito.
            possession = possession.other()
            zone = Zone.MID_C
            lastToucher = null
            refreshStrengths()
        }

        private fun turnoverAfterShot() {
            possession = possession.other()
            zone = Zone.DIF_C
            lastToucher = null
        }

        private fun book(minute: Int, playerId: PlayerId, side: Side) {
            val booked = if (side == Side.CASA) homeBooked else awayBooked
            if (playerId in booked) {
                // Secondo giallo: espulsione.
                sendOff(minute, playerId, side)
                return
            }
            booked += playerId
            bump(playerId) { it.copy(yellowCards = it.yellowCards + 1) }
            emit(
                minute, MatchEventType.AMMONIZIONE, side, zone = zone, player = playerId,
                description = "Ammonito ${nameOf(playerId)}",
            )
        }

        private fun sendOff(minute: Int, playerId: PlayerId, side: Side) {
            val setup = if (side == Side.CASA) home else away
            // Sotto il minimo regolamentare non si espelle: la partita finirebbe sospesa,
            // che in un gioco fra amici sarebbe solo una serata rovinata. Si degrada a
            // fallo semplice, senza richiamare `book` per non innescare una ricorsione.
            val reduced = setup.lineup.sendOff(playerId) ?: run {
                emit(
                    minute, MatchEventType.FALLO, side, zone = zone, player = playerId,
                    description = "Fallo di ${nameOf(playerId)}",
                )
                return
            }

            bump(playerId) { it.copy(redCards = it.redCards + 1) }
            closeMinutes(playerId, minute)
            val remaining = reduced.slots.size
            emit(
                minute, MatchEventType.ESPULSIONE, side, zone = zone, player = playerId,
                description = "Espulso ${nameOf(playerId)}! ${teamName(side)} in $remaining",
            )
            if (side == Side.CASA) {
                home = home.copy(lineup = reduced)
            } else {
                away = away.copy(lineup = reduced)
            }
            refreshStrengths()
        }

        // ------------------------------------------------------------- probabilita

        /**
         * xG del tiro: la zona centrale vale molto piu' delle fasce, e concludere di
         * piede debole costa. E' quello che rende preziosi gli attaccanti centrali veri
         * invece di premiare chiunque arrivi in area.
         */
        private fun expectedGoals(shooter: Player, from: Zone): Double {
            val base = if (from.lane == dev.mfoot.core.model.Lane.C) {
                engine.baseXgCentral
            } else {
                engine.baseXgWide
            }
            val finishing = shooter.attributes[Attr.TIRO] * 0.7 +
                shooter.attributes[Attr.TECNICA] * 0.3
            val quality = MathX.remap(finishing, 40.0, 95.0, 0.55, 1.95)

            // Circa un tiro su tre arriva sul piede sbagliato.
            val wrongFoot = rng.chance(0.33)
            val weakFootMalus = if (wrongFoot) {
                1.0 - engine.weakFootPenaltyPerStar * (5 - shooter.weakFoot)
            } else {
                1.0
            }

            return (base * quality * weakFootMalus).coerceIn(0.01, 0.75)
        }

        /** Un portiere forte abbassa le probabilita' di gol, uno scarso le alza. */
        private fun keeperFactor(goalkeeper: Player): Double {
            val skill = goalkeeper.attributes[Attr.PARATA] * 0.55 +
                goalkeeper.attributes[Attr.RIFLESSI] * 0.45
            val fatigue = ZoneRatings.staminaPenalty(goalkeeper, engine)
            return MathX.remap(skill - fatigue, 40.0, 95.0, 1.30, 0.68)
        }

        private fun injuryChanceFor(playerId: PlayerId, setup: TeamSetup): Double {
            val player = playerOf(playerId) ?: return 0.0
            val severity = when (config.rules.injurySeverity) {
                dev.mfoot.core.config.InjurySeverity.LIEVE -> 0.5
                dev.mfoot.core.config.InjurySeverity.NORMALE -> 1.0
                dev.mfoot.core.config.InjurySeverity.REALISTICA -> 1.6
            }
            // Stanchi e fragili si fanno male molto piu' spesso.
            val fatigue = 1.0 + (100 - player.stamina) / 100.0
            return perAzione(engine.injuryChancePerAction) * severity * fatigue *
                player.traits.injuryFactor()
        }

        // ------------------------------------------------------------------ ordini

        private fun evaluateOrders(minute: Int) {
            evaluateOrdersFor(Side.CASA, minute)
            evaluateOrdersFor(Side.OSPITE, minute)
        }

        private fun evaluateOrdersFor(side: Side, minute: Int) {
            val setup = if (side == Side.CASA) home else away
            val fired = if (side == Side.CASA) homeFired else awayFired
            val booked = if (side == Side.CASA) homeBooked else awayBooked

            val context = OrderContext(
                minute = minute,
                goalsFor = if (side == Side.CASA) homeGoals else awayGoals,
                goalsAgainst = if (side == Side.CASA) awayGoals else homeGoals,
                lineup = setup.lineup,
                bookedPlayers = booked,
            )

            for (order in setup.sortedOrders) {
                if (order.id in fired) continue
                if (!order.trigger.matches(context)) continue
                if (applyAction(side, order.action, minute)) {
                    fired += order.id
                }
            }
        }

        /** @return true se l'ordine e' stato applicato davvero. */
        private fun applyAction(side: Side, action: OrderAction, minute: Int): Boolean {
            val setup = if (side == Side.CASA) home else away

            val updated = when (action) {
                is OrderAction.Sostituisci -> {
                    val newLineup = setup.lineup.substitute(action.out, action.entra)
                        ?: return false
                    closeMinutes(action.out, minute)
                    onPitchSince[action.entra] = minute
                    emit(
                        minute, MatchEventType.SOSTITUZIONE, side, player = action.entra,
                        secondaryPlayer = action.out,
                        description = "${teamName(side)}: entra ${nameOf(action.entra)}, " +
                            "esce ${nameOf(action.out)}",
                    )
                    setup.copy(lineup = newLineup)
                }

                is OrderAction.CambiaAssetto -> {
                    if (setup.tactics.stance == action.stance) return false
                    emitTacticChange(minute, side, action.stance.label)
                    setup.copy(tactics = setup.tactics.copy(stance = action.stance))
                }

                is OrderAction.CambiaRitmo -> {
                    if (setup.tactics.tempo == action.tempo) return false
                    emitTacticChange(minute, side, "ritmo ${action.tempo.label.lowercase()}")
                    setup.copy(tactics = setup.tactics.copy(tempo = action.tempo))
                }

                is OrderAction.CambiaPressing -> {
                    if (setup.tactics.pressing == action.pressing) return false
                    emitTacticChange(minute, side, "pressing ${action.pressing.label.lowercase()}")
                    setup.copy(tactics = setup.tactics.copy(pressing = action.pressing))
                }

                is OrderAction.CambiaAmpiezza -> {
                    if (setup.tactics.width == action.width) return false
                    emitTacticChange(minute, side, "gioco ${action.width.label.lowercase()}")
                    setup.copy(tactics = setup.tactics.copy(width = action.width))
                }
            }

            if (side == Side.CASA) home = updated else away = updated
            refreshStrengths()
            return true
        }

        private fun emitTacticChange(minute: Int, side: Side, what: String) {
            emit(
                minute, MatchEventType.CAMBIO_TATTICA, side,
                description = "${teamName(side)} passa a $what",
            )
        }

        // ------------------------------------------------------------------ stamina

        /**
         * La stanchezza si accumula durante la partita e comincia a pesare sui rating
         * quando si scende sotto la soglia di comfort. Con due partite al giorno e' la
         * risorsa piu' scarsa che esista.
         */
        private fun drainStamina(minute: Int) {
            home = home.copy(lineup = drainLineup(home, minute))
            away = away.copy(lineup = drainLineup(away, minute))
        }

        private fun drainLineup(setup: TeamSetup, minute: Int): Lineup {
            var lineup = setup.lineup
            val perAction = engine.staminaDrainPerMinute * (FULL_MINUTES.toDouble() / totalActionsTarget())

            for (slot in lineup.slots) {
                val player = slot.player
                // Chi ha piu' fisico regge meglio; i tratti pesano ancora di piu'.
                val resilience = MathX.remap(
                    player.attributes[Attr.FISICO].toDouble(), 40.0, 95.0, 1.25, 0.72,
                )
                val drain = (perAction * resilience *
                    setup.tactics.staminaMultiplier *
                    player.traits.staminaFactor()).coerceAtLeast(0.0)

                val debt = (staminaDebt[player.id] ?: 0.0) + drain
                val wholePoints = debt.toInt()
                staminaDebt[player.id] = debt - wholePoints

                if (wholePoints > 0 && player.stamina > 0) {
                    val updated = player.withStamina(player.stamina - wholePoints)
                    lineup = lineup.withUpdatedPlayer(updated)
                    bump(player.id) { it.copy(staminaSpent = it.staminaSpent + wholePoints) }
                }
            }
            return lineup
        }

        private fun totalActionsTarget(): Double {
            val tempoFactor = (home.tactics.tempo.actionMultiplier +
                away.tactics.tempo.actionMultiplier) / 2.0
            return azioniPerPartita * tempoFactor
        }

        private fun decayMomentum() {
            momentum *= engine.momentumDecayPerAction
            if (StrictMath.abs(momentum) < 0.05) momentum = 0.0
        }

        // ------------------------------------------------------------------- rating

        private fun computeStrength(setup: TeamSetup, isHome: Boolean): ZoneStrength =
            ZoneRatings.compute(
                lineup = setup.lineup,
                tactics = setup.tactics,
                coachStars = setup.coachStars,
                isHome = isHome,
                importance = importance,
                engine = engine,
                momentum = (if (isHome) momentum else -momentum) + resistenza(setup, isHome),
            )

        /**
         * Quanto il capitano tiene su la squadra, e **solo quando serve**.
         *
         * Passa dal canale dell'inerzia psicologica perche' e' la stessa cosa: una spinta
         * che nasce dalla testa e non dalle gambe. Ma dove il momentum arriva dopo un gol
         * a chiunque, questa arriva **solo a chi e' sotto** e solo a chi ha in campo un
         * uomo capace di guidare: e' il costo di lasciare il capitano in panchina, ed e'
         * cio' che rende la fascia una decisione invece di un titolo.
         *
         * Nessun malus per chi non ce l'ha: la squadra senza capitano designato gioca
         * esattamente come prima del 2026-08-24, perche' [SetPieces.taker] le mette
         * comunque in campo il piu' adatto fra gli undici.
         */
        private fun resistenza(setup: TeamSetup, isHome: Boolean): Double {
            val scarto = if (isHome) homeGoals - awayGoals else awayGoals - homeGoals
            if (scarto >= 0) return 0.0
            return SetPieces.leadership(setup.lineup) * engine.captainResilience
        }

        fun refreshStrengths() {
            homeStrength = computeStrength(home, isHome = true)
            awayStrength = computeStrength(away, isHome = false)
            actionsSinceRefresh = 0
        }

        private fun maybeRefreshStrengths() {
            actionsSinceRefresh++
            if (actionsSinceRefresh >= REFRESH_EVERY) refreshStrengths()
        }

        // ------------------------------------------------------------------ utility

        /**
         * Chi tocca la palla in questa zona, pesato sulla presenza.
         *
         * In `ATT_C` la punta esce spesso e il mediano quasi mai: e' da qui che emergono
         * le statistiche individuali senza doverle modellare a parte.
         */
        private fun pickToucher(strength: ZoneStrength, inZone: Zone): PlayerId {
            val candidates = strength.contributions[inZone]
            if (candidates.isNullOrEmpty()) {
                // Zona deserta: tocca chiunque sia in campo.
                val setup = if (strength === homeStrength) home else away
                return setup.lineup.outfield.firstOrNull()?.player?.id
                    ?: setup.lineup.slots.first().player.id
            }
            return rng.pickWeighted(candidates) { it.weight }.playerId
        }

        /**
         * Chi commette il fallo.
         *
         * Come [pickToucher], ma il peso tiene conto di chi va in ritardo. `TESTA_CALDA`
         * diceva *«colleziona cartellini»* e non ne collezionava nessuno: dentro la partita
         * il tratto non muoveva un solo numero, e chi lo aveva prendeva esattamente gli
         * stessi gialli di chiunque altro.
         */
        private fun pickFalloso(
            strength: ZoneStrength,
            inZone: Zone,
            setup: TeamSetup,
        ): PlayerId {
            val candidates = strength.contributions[inZone]
            if (candidates.isNullOrEmpty()) return pickToucher(strength, inZone)
            return rng.pickWeighted(candidates) { pw ->
                pw.weight * (setup.lineup.playerById(pw.playerId)?.traits?.foulFactor() ?: 1.0)
            }.playerId
        }

        /**
         * Chi va sul dischetto.
         *
         * La scelta stava scritta qui dentro, e da qui non la vedeva nessuno: l'app non
         * aveva modo di mostrare *chi* avrebbe calciato, e la stessa regola sarebbe
         * dovuta esistere una seconda volta per scriverlo in una schermata. Adesso e' una
         * funzione pura in [SetPieces], usata identica dal motore e dalla formazione.
         */
        private fun choosePenaltyTaker(setup: TeamSetup): PlayerId =
            SetPieces.taker(setup.lineup, MatchDuty.RIGORISTA)?.id
                ?: setup.lineup.slots.first().player.id

        private fun playerOf(id: PlayerId): Player? =
            home.lineup.playerById(id) ?: away.lineup.playerById(id)

        private fun nameOf(id: PlayerId): String = playerOf(id)?.shortName ?: "?"

        private fun teamName(side: Side): String = if (side == Side.CASA) home.name else away.name

        private fun recordShot(xg: Double) {
            if (possession == Side.CASA) {
                homeShots++; homeXg += xg
            } else {
                awayShots++; awayXg += xg
            }
        }

        private fun bump(id: PlayerId, update: (PlayerMatchStats) -> PlayerMatchStats) {
            val current = stats[id] ?: PlayerMatchStats(id)
            stats[id] = update(current)
        }

        private fun emit(
            minute: Int,
            type: MatchEventType,
            side: Side,
            zone: Zone? = null,
            player: PlayerId? = null,
            secondaryPlayer: PlayerId? = null,
            description: String = "",
            dangerOverride: Int? = null,
        ) {
            events += MatchEvent(
                minute = minute,
                type = type,
                side = side,
                danger = dangerOverride ?: type.baseDanger,
                zone = zone,
                player = player,
                secondaryPlayer = secondaryPlayer,
                description = description,
                homeGoals = homeGoals,
                awayGoals = awayGoals,
            )
        }

        fun emitKickoff() {
            emit(0, MatchEventType.INIZIO, Side.CASA,
                description = "${home.name} - ${away.name}: si comincia")
        }

        fun emitHalfTime() {
            closeAllMinutes(HALF_MINUTES)
            emit(HALF_MINUTES, MatchEventType.INTERVALLO, Side.CASA,
                description = "Intervallo: $homeGoals-$awayGoals")
        }

        fun emitFullTime() {
            closeAllMinutes(FULL_MINUTES)
            emit(FULL_MINUTES, MatchEventType.FINE, Side.CASA,
                description = "Finita: ${home.name} $homeGoals-$awayGoals ${away.name}")
        }

        /**
         * Chiude il conteggio dei minuti per chi lascia il campo.
         *
         * Va chiamato anche su espulsi e sostituiti, non solo al fischio finale: senza
         * questo, un giocatore uscito al 30' risulterebbe con zero minuti giocati e non
         * guadagnerebbe nessuna esperienza per quella mezz'ora.
         */
        private fun closeMinutes(playerId: PlayerId, atMinute: Int) {
            val since = onPitchSince.remove(playerId) ?: return
            val played = (atMinute - since).coerceAtLeast(0)
            if (played > 0) {
                bump(playerId) { it.copy(minutesPlayed = it.minutesPlayed + played) }
            }
        }

        private fun closeAllMinutes(atMinute: Int) {
            onPitchSince.keys.toList().forEach { closeMinutes(it, atMinute) }
        }

        // ------------------------------------------------------------------- output

        fun toHalfTimeState() = HalfTimeState(
            seed = seed,
            homeGoals = homeGoals,
            awayGoals = awayGoals,
            events = events.toList(),
            stats = stats.toMap(),
            homeSetup = home,
            awaySetup = away,
            homeBooked = homeBooked.toSet(),
            awayBooked = awayBooked.toSet(),
            homeFiredOrders = homeFired.toSet(),
            awayFiredOrders = awayFired.toSet(),
            homePossessionActions = homePossessionActions,
            totalActions = totalActions,
            momentum = momentum,
            importance = importance,
            homeShots = homeShots,
            awayShots = awayShots,
            homeXg = homeXg,
            awayXg = awayXg,
        )

        fun toResult() = MatchResult(
            homeGoals = homeGoals,
            awayGoals = awayGoals,
            events = events.toList(),
            stats = stats.toMap(),
            homePossession = if (totalActions == 0) 0.5
            else homePossessionActions.toDouble() / totalActions,
            seed = seed,
            homeShots = homeShots,
            awayShots = awayShots,
            homeXg = homeXg,
            awayXg = awayXg,
        )

        companion object {
            const val POST_CHANCE = 0.045
            const val OFF_TARGET_CHANCE = 0.40
            const val REFRESH_EVERY = 8
        }
    }
}
