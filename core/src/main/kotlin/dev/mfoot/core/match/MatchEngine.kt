package dev.mfoot.core.match

import dev.mfoot.core.config.EngineConfig
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Zone
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
            val scaled = engine.actionsPerMatch * tempoFactor * (minutes / FULL_MINUTES.toDouble())
            return StrictMath.round(scaled).toInt().coerceAtLeast(1)
        }

        private fun step(minute: Int) {
            totalActions++
            if (possession == Side.CASA) homePossessionActions++

            val attacker = if (possession == Side.CASA) homeStrength else awayStrength
            val defender = if (possession == Side.CASA) awayStrength else homeStrength
            val attackerSetup = if (possession == Side.CASA) home else away
            val defenderSetup = if (possession == Side.CASA) away else home

            val toucher = pickToucher(attacker, zone)

            val delta = attacker.rating(zone) - defender.rating(zone.mirror())
            val advanceChance = MathX.sigmoid(delta, engine.sigmoidK)

            // Il fallo puo' interrompere qualsiasi azione; il pressing alto ne produce di piu'.
            val foulChance = engine.foulChance * defenderSetup.tactics.pressing.foulMultiplier
            if (rng.chance(foulChance)) {
                resolveFoul(minute, defenderSetup)
                return
            }

            if (config.rules.injuriesEnabled && rng.chance(injuryChanceFor(toucher, attackerSetup))) {
                resolveInjury(minute, toucher, attackerSetup)
                return
            }

            val shotChance = engine.shotChanceInAttackingZone *
                attackerSetup.tactics.stance.shotChanceFactor
            if (zone.isAttacking && rng.chance(shotChance)) {
                resolveShot(minute, toucher, defenderSetup)
                return
            }

            if (rng.chance(advanceChance)) {
                val next = zone.advance()
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

        // ------------------------------------------------------------ risoluzioni

        private fun resolveTurnover(minute: Int, defenderSetup: TeamSetup) {
            val defendingStrength = if (possession == Side.CASA) awayStrength else homeStrength
            val winner = pickToucher(defendingStrength, zone.mirror())
            bump(winner) { it.copy(tackles = it.tackles + 1) }

            // Un attacco che si spegne in area avversaria produce spesso un angolo.
            if (zone.isAttacking && rng.chance(engine.cornerChanceOnLostAttack)) {
                emit(
                    minute, MatchEventType.ANGOLO, possession, zone = zone,
                    description = "Angolo per ${teamName(possession)}",
                )
                // Sul corner si resta in zona offensiva: la palla e' ancora pericolosa.
                lastToucher = null
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
            val offender = pickToucher(defendingStrength, zone.mirror())
            val offendingSide = possession.other()
            bump(offender) { it.copy(fouls = it.fouls + 1) }

            // Fallo da rigore: solo in area centrale, e raro.
            if (zone == Zone.ATT_C && rng.chance(0.075)) {
                resolvePenalty(minute, offender)
                return
            }

            if (config.rules.suspensionsEnabled && rng.chance(engine.redCardChanceOnFoul)) {
                sendOff(minute, offender, offendingSide)
                return
            }

            if (config.rules.suspensionsEnabled && rng.chance(engine.yellowCardChanceOnFoul)) {
                book(minute, offender, offendingSide)
                return
            }

            emit(
                minute, MatchEventType.FALLO, offendingSide, zone = zone,
                player = offender,
                description = "Fallo di ${nameOf(offender)}",
            )
        }

        private fun resolveShot(minute: Int, shooter: PlayerId, defenderSetup: TeamSetup) {
            val player = playerOf(shooter) ?: return
            val goalkeeper = defenderSetup.lineup.slots
                .firstOrNull { it.position.isGoalkeeper }?.player

            val xg = expectedGoals(player, zone)
            recordShot(xg)
            bump(shooter) { it.copy(shots = it.shots + 1) }

            val goalChance = goalkeeper?.let { xg * keeperFactor(it) } ?: (xg * 1.6)
            val roll = rng.nextDouble()

            when {
                roll < goalChance -> scoreGoal(minute, shooter, MatchEventType.GOL)
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

        private fun scoreGoal(minute: Int, scorer: PlayerId, type: MatchEventType) {
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

            val description = buildString {
                append("GOL! ").append(nameOf(scorer))
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
            return engine.injuryChancePerAction * severity * fatigue * player.traits.injuryFactor()
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
            return engine.actionsPerMatch * tempoFactor
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
                momentum = if (isHome) momentum else -momentum,
            )

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

        private fun choosePenaltyTaker(setup: TeamSetup): PlayerId {
            setup.lineup.penaltyTakerId?.let { designated ->
                if (setup.lineup.contains(designated)) return designated
            }
            // Altrimenti il migliore fra chi e' in campo, con i rigoristi nati favoriti.
            return setup.lineup.outfield.maxByOrNull { slot ->
                val p = slot.player
                (p.attributes[Attr.TIRO] * 0.6 + p.attributes[Attr.TECNICA] * 0.4) *
                    p.traits.fold(1.0) { acc, t -> acc * t.penaltyTakerWeight }
            }?.player?.id ?: setup.lineup.slots.first().player.id
        }

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
