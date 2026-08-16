package dev.mfoot.core.growth

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.match.MatchImportance
import dev.mfoot.core.match.PlayerMatchStats
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.Trait
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrowthEngineTest {

    private val config = ConfigPresets.sprint(16, 8, LocalDate.of(2026, 9, 1))

    private fun player(
        age: Int = 24,
        overall: Int = 70,
        potentialMin: Int = 85,
        potentialMax: Int = 88,
        position: Position = Position.ATT,
        traits: Set<Trait> = emptySet(),
        isCustom: Boolean = false,
        id: Long = 1L,
    ) = Player(
        id = PlayerId(id),
        firstName = "Marco",
        lastName = "Ferrero",
        nationality = "Italia",
        age = age,
        primaryPosition = position,
        attributes = Attributes.uniform(overall),
        potentialMin = potentialMin,
        potentialMax = potentialMax,
        traits = traits,
        isCustom = isCustom,
    )

    private fun goodMatch(id: Long = 1L) = PlayerMatchStats(
        playerId = PlayerId(id),
        minutesPlayed = 90,
        goals = 1,
        assists = 1,
        shots = 3,
        shotsOnTarget = 2,
        keyActions = 6,
    )

    private fun quietMatch(id: Long = 1L) = PlayerMatchStats(
        playerId = PlayerId(id), minutesPlayed = 90, keyActions = 3,
    )

    // ------------------------------------------------------------------------ base

    @Test
    fun `chi non gioca non cresce`() {
        val p = player()
        val outcome = GrowthEngine.processMatch(
            p, PlayerMatchStats(p.id, minutesPlayed = 0), GrowthContext(config),
        )
        assertEquals(0.0, outcome.xpGained)
        assertEquals(p, outcome.player)
    }

    @Test
    fun `una buona partita da piu esperienza di una anonima`() {
        val p = player()
        val buona = GrowthEngine.experienceFrom(p, goodMatch(), GrowthContext(config))
        val anonima = GrowthEngine.experienceFrom(p, quietMatch(), GrowthContext(config))
        assertTrue(buona > anonima, "buona $buona, anonima $anonima")
    }

    @Test
    fun `l'esperienza si accumula prima di sbloccare un attributo`() {
        var p = player(overall = 70)
        val primaPartita = GrowthEngine.processMatch(p, quietMatch(), GrowthContext(config))

        assertTrue(
            primaPartita.changes.isEmpty(),
            "una singola partita anonima non deve gia' far salire un attributo",
        )
        assertTrue(primaPartita.player.experience > 0, "l'esperienza non si e' accumulata")
    }

    @Test
    fun `quando cresce sale un attributo specifico e non l'overall generico`() {
        var p = player(overall = 65)
        var cambi = emptyList<AttributeChange>()

        repeat(20) {
            val outcome = GrowthEngine.processMatch(p, goodMatch(), GrowthContext(config))
            p = outcome.player
            if (outcome.changes.isNotEmpty()) cambi = outcome.changes
        }

        assertTrue(cambi.isNotEmpty(), "in venti buone partite non e' cresciuto nulla")
        assertEquals(1, cambi.first().delta, "deve salire di un punto alla volta")
        assertTrue(
            cambi.first().attr in Position.ATT.relevantAttributes,
            "e' salito un attributo che non c'entra col ruolo: ${cambi.first().attr}",
        )
    }

    // ------------------------------------------------------------------------- eta

    @Test
    fun `la fascia 22-26 cresce circa il doppio del plateau`() {
        val giovane = GrowthEngine.ageMultiplier(24, config.rules)
        val plateau = GrowthEngine.ageMultiplier(28, config.rules)
        assertTrue(giovane > plateau * 1.9, "24 anni: $giovane, 28 anni: $plateau")
    }

    @Test
    fun `dopo l'eta di declino l'esperienza diventa negativa`() {
        assertTrue(GrowthEngine.ageMultiplier(34, config.rules) < 0)
        assertTrue(GrowthEngine.ageMultiplier(30, config.rules) > 0)
    }

    @Test
    fun `un veterano perde attributi giocando`() {
        var p = player(age = 35, overall = 80, potentialMin = 80, potentialMax = 80)
        var perdite = 0

        repeat(40) {
            val outcome = GrowthEngine.processMatch(p, quietMatch(), GrowthContext(config))
            p = outcome.player
            perdite += outcome.changes.count { it.delta < 0 }
        }

        assertTrue(perdite > 0, "un trentacinquenne non ha perso niente in quaranta partite")
    }

    @Test
    fun `il declino colpisce prima gli attributi fisici`() {
        var p = player(age = 36, overall = 78, potentialMin = 78, potentialMax = 78)
        val perse = mutableListOf<dev.mfoot.core.model.Attr>()

        repeat(60) {
            val outcome = GrowthEngine.processMatch(p, quietMatch(), GrowthContext(config))
            p = outcome.player
            outcome.changes.filter { it.delta < 0 }.forEach { perse += it.attr }
        }

        assertTrue(perse.isNotEmpty())
        val fisici = perse.count {
            it == dev.mfoot.core.model.Attr.VELOCITA || it == dev.mfoot.core.model.Attr.FISICO
        }
        assertTrue(
            fisici > perse.size / 2,
            "il declino non sta colpendo gambe e fisico: $perse",
        )
    }

    // ------------------------------------------------------------------ potenziale

    @Test
    fun `nessuno supera mai il proprio tetto`() {
        var p = player(age = 23, overall = 70, potentialMin = 76, potentialMax = 76)

        repeat(300) {
            p = GrowthEngine.processMatch(p, goodMatch(), GrowthContext(config, coachStars = 5)).player
        }

        assertTrue(
            p.overall <= 76,
            "ha sfondato il tetto: overall ${p.overall} con potenziale 76",
        )
    }

    @Test
    fun `la crescita rallenta avvicinandosi al tetto`() {
        val lontano = player(overall = 60, potentialMin = 88, potentialMax = 88, id = 1L)
        val vicino = player(overall = 86, potentialMin = 88, potentialMax = 88, id = 1L)

        val xpLontano = GrowthEngine.experienceFrom(lontano, goodMatch(), GrowthContext(config))
        val xpVicino = GrowthEngine.experienceFrom(vicino, goodMatch(), GrowthContext(config))

        assertTrue(xpLontano > xpVicino * 2, "lontano $xpLontano, vicino $xpVicino")
    }

    @Test
    fun `il tetto e stabile fra due letture`() {
        val p = player(potentialMin = 80, potentialMax = 90)
        assertEquals(GrowthEngine.ceilingOf(p), GrowthEngine.ceilingOf(p))
        assertTrue(GrowthEngine.ceilingOf(p) in 80..90)
    }

    // ------------------------------------------------------------------- ritmo

    /**
     * **Il test che protegge dal problema piu' probabile.**
     *
     * Con due partite al giorno una stagione da 38 giornate dura diciannove giorni.
     * Se la crescita fosse troppo generosa, un player custom da 65 sarebbe a 90 in due
     * settimane e il gioco finirebbe. Se fosse troppo avara, non si sentirebbe mai.
     */
    @Test
    fun `in una stagione un ventitreenne cresce di una quantita sensata`() {
        var p = player(age = 23, overall = 70, potentialMin = 88, potentialMax = 88)
        val partenza = p.overall

        repeat(38) {
            p = GrowthEngine.processMatch(p, goodMatch(), GrowthContext(config, coachStars = 3)).player
        }

        val crescita = p.overall - partenza
        assertTrue(
            crescita in 3..16,
            "in una stagione da titolare con buone prestazioni e' cresciuto di $crescita punti " +
                "(da $partenza a ${p.overall}): fuori dalla fascia sensata 3-16",
        )
    }

    @Test
    fun `il player custom cresce molto piu in fretta dei generati`() {
        var custom = player(age = 20, overall = 65, potentialMin = 88, potentialMax = 88, isCustom = true)
        var normale = player(age = 20, overall = 65, potentialMin = 88, potentialMax = 88, id = 2L)

        repeat(38) {
            custom = GrowthEngine.processMatch(custom, goodMatch(), GrowthContext(config)).player
            normale = GrowthEngine.processMatch(normale, goodMatch(2L), GrowthContext(config, )).player
        }

        assertTrue(
            custom.overall > normale.overall,
            "custom ${custom.overall}, generato ${normale.overall}: il giocatore del " +
                "proprietario deve recuperare il divario iniziale",
        )
    }

    @Test
    fun `un allenatore da cinque stelle accelera visibilmente la crescita`() {
        var conTop = player(age = 23, overall = 68, id = 1L)
        var conScarso = player(age = 23, overall = 68, id = 1L)

        repeat(30) {
            conTop = GrowthEngine.processMatch(conTop, goodMatch(), GrowthContext(config, coachStars = 5)).player
            conScarso = GrowthEngine.processMatch(conScarso, goodMatch(), GrowthContext(config, coachStars = 1)).player
        }

        assertTrue(
            conTop.overall > conScarso.overall,
            "5 stelle: ${conTop.overall}, 1 stella: ${conScarso.overall}",
        )
    }

    // --------------------------------------------------------------- amichevoli

    /**
     * Se le amichevoli facessero crescere, due amici compiacenti potrebbero
     * concordarne quindici al giorno e far esplodere le rose in un pomeriggio.
     */
    @Test
    fun `le amichevoli non fanno crescere quando la lega non lo prevede`() {
        val p = player()
        val outcome = GrowthEngine.processMatch(
            p, goodMatch(), GrowthContext(config, importance = MatchImportance.AMICHEVOLE),
        )
        assertEquals(0.0, outcome.xpGained)
        assertEquals(p, outcome.player)
    }

    @Test
    fun `le amichevoli fanno crescere se la lega lo prevede`() {
        val permissiva = config.copy(rules = config.rules.copy(friendliesCountForGrowth = true))
        val outcome = GrowthEngine.processMatch(
            player(), goodMatch(),
            GrowthContext(permissiva, importance = MatchImportance.AMICHEVOLE),
        )
        assertTrue(outcome.xpGained > 0)
    }

    @Test
    fun `una partita di primavera vale meno di una di prima squadra`() {
        val p = player(age = 19)
        val prima = GrowthEngine.experienceFrom(p, goodMatch(), GrowthContext(config))
        val primavera = GrowthEngine.experienceFrom(
            p, goodMatch(), GrowthContext(config, isYouthMatch = true),
        )
        assertTrue(primavera < prima, "primavera $primavera, prima squadra $prima")
    }

    @Test
    fun `il tratto talento precoce accelera la crescita`() {
        val p = player(age = 19)
        val conTratto = player(age = 19, traits = setOf(Trait.TALENTO_PRECOCE))

        assertTrue(
            GrowthEngine.experienceFrom(conTratto, goodMatch(), GrowthContext(config)) >
                GrowthEngine.experienceFrom(p, goodMatch(), GrowthContext(config)),
        )
    }

    @Test
    fun `la soglia cresce con l'overall`() {
        assertTrue(GrowthEngine.thresholdFor(85) > GrowthEngine.thresholdFor(60) * 1.5)
    }
}

class StaminaEngineTest {

    private val engine = ConfigPresets.sprint().engine

    private fun player(age: Int = 25, stamina: Int = 50, traits: Set<Trait> = emptySet()) = Player(
        id = PlayerId(1),
        firstName = "Luca", lastName = "Bianchi", nationality = "Italia",
        age = age,
        primaryPosition = Position.CC,
        attributes = Attributes.uniform(70),
        potentialMin = 75, potentialMax = 78,
        stamina = stamina,
        traits = traits,
    )

    @Test
    fun `il recupero non supera mai il massimo`() {
        val recuperato = StaminaEngine.recover(player(stamina = 95), 5, engine)
        assertEquals(Player.MAX_STAMINA, recuperato.stamina)
    }

    @Test
    fun `un preparatore da cinque stelle recupera molto piu di uno da una`() {
        val conTop = StaminaEngine.recoveryAmount(player(), 5, engine)
        val conScarso = StaminaEngine.recoveryAmount(player(), 1, engine)
        assertTrue(conTop > conScarso * 1.8, "5 stelle: $conTop, 1 stella: $conScarso")
    }

    @Test
    fun `senza preparatore si recupera comunque ma male`() {
        val senza = StaminaEngine.recoveryAmount(player(), 0, engine)
        val con = StaminaEngine.recoveryAmount(player(), 3, engine)
        assertTrue(senza > 0, "senza preparatore non si recupera niente")
        assertTrue(senza < con)
    }

    @Test
    fun `i giovani recuperano piu in fretta dei veterani`() {
        val giovane = StaminaEngine.recoveryAmount(player(age = 20), 3, engine)
        val veterano = StaminaEngine.recoveryAmount(player(age = 35), 3, engine)
        assertTrue(giovane > veterano, "giovane $giovane, veterano $veterano")
    }

    @Test
    fun `il tratto instancabile aiuta anche a recuperare`() {
        val normale = StaminaEngine.recoveryAmount(player(), 3, engine)
        val instancabile = StaminaEngine.recoveryAmount(
            player(traits = setOf(Trait.INSTANCABILE)), 3, engine,
        )
        assertTrue(instancabile > normale)
    }

    /**
     * La verifica che il sistema faccia il suo mestiere: giocare due partite in un
     * giorno deve costare, altrimenti la rosa profonda e la Primavera non servono.
     */
    @Test
    fun `dopo una partita non si torna freschi in una sola giornata`() {
        val dopoPartita = player(stamina = 62)
        val dopoRiposo = StaminaEngine.recover(dopoPartita, 3, engine)
        assertTrue(
            dopoRiposo.stamina < Player.MAX_STAMINA,
            "una giornata di riposo ricarica tutto: la rotazione sarebbe inutile",
        )
    }

    @Test
    fun `isFresh confronta con la soglia di comfort`() {
        assertTrue(StaminaEngine.isFresh(player(stamina = 90), engine))
        assertTrue(!StaminaEngine.isFresh(player(stamina = 30), engine))
    }

    @Test
    fun `matchDaysToFullRecovery e coerente`() {
        assertEquals(0, StaminaEngine.matchDaysToFullRecovery(player(stamina = 100), 3, engine))
        assertTrue(StaminaEngine.matchDaysToFullRecovery(player(stamina = 20), 1, engine) > 1)
    }
}

class MoraleEngineTest {

    private val rules = ConfigPresets.sprint().rules

    private fun player(morale: Int = 60, traits: Set<Trait> = emptySet(), isCustom: Boolean = false) = Player(
        id = PlayerId(1),
        firstName = "Andrea", lastName = "Costa", nationality = "Italia",
        age = 25,
        primaryPosition = Position.CC,
        attributes = Attributes.uniform(70),
        potentialMin = 75, potentialMax = 78,
        morale = morale,
        traits = traits,
        isCustom = isCustom,
    )

    private fun stats(minutes: Int = 90, goals: Int = 0) =
        PlayerMatchStats(PlayerId(1), minutesPlayed = minutes, goals = goals, keyActions = 4)

    @Test
    fun `giocare e vincere alza il morale`() {
        val change = MoraleEngine.afterMatch(player(), stats(), TeamOutcome.VITTORIA, rules)
        assertTrue(change.delta > 0, "delta ${change.delta}")
        assertTrue(change.reasons.isNotEmpty())
    }

    @Test
    fun `restare in panchina abbassa il morale`() {
        val change = MoraleEngine.afterMatch(player(), stats(minutes = 0), TeamOutcome.VITTORIA, rules)
        assertTrue(change.delta < 0, "delta ${change.delta}")
    }

    @Test
    fun `segnare vale piu che giocare e basta`() {
        val conGol = MoraleEngine.afterMatch(player(), stats(goals = 1), TeamOutcome.VITTORIA, rules).delta
        val senza = MoraleEngine.afterMatch(player(), stats(), TeamOutcome.VITTORIA, rules).delta
        assertTrue(conGol > senza)
    }

    @Test
    fun `essere sostituiti presto pesa`() {
        val presto = MoraleEngine.afterMatch(player(), stats(minutes = 30), TeamOutcome.PAREGGIO, rules).delta
        val intera = MoraleEngine.afterMatch(player(), stats(minutes = 90), TeamOutcome.PAREGGIO, rules).delta
        assertTrue(presto < intera)
    }

    /**
     * Se tutti reagissero allo stesso modo, i tratti sarebbero decorazione e leggere la
     * scheda di un giocatore non servirebbe a niente.
     */
    @Test
    fun `il testa calda oscilla piu dell'uomo spogliatoio`() {
        val calda = MoraleEngine.afterMatch(
            player(traits = setOf(Trait.TESTA_CALDA)), stats(minutes = 0),
            TeamOutcome.SCONFITTA, rules,
        ).delta
        val spogliatoio = MoraleEngine.afterMatch(
            player(traits = setOf(Trait.UOMO_SPOGLIATOIO)), stats(minutes = 0),
            TeamOutcome.SCONFITTA, rules,
        ).delta

        assertTrue(calda < spogliatoio, "testa calda $calda, uomo spogliatoio $spogliatoio")
    }

    @Test
    fun `il morale resta nella scala`() {
        val bassissimo = MoraleEngine.afterMatch(
            player(morale = 2), stats(minutes = 0), TeamOutcome.SCONFITTA, rules,
        )
        assertTrue(bassissimo.player.morale >= 0)

        val altissimo = MoraleEngine.afterMatch(
            player(morale = 98), stats(goals = 3), TeamOutcome.VITTORIA, rules,
        )
        assertTrue(altissimo.player.morale <= 100)
    }

    @Test
    fun `chi ha morale basso puo chiedere la cessione`() {
        assertTrue(MoraleEngine.wantsToLeave(player(morale = 10), rules))
        assertTrue(!MoraleEngine.wantsToLeave(player(morale = 70), rules))
    }

    @Test
    fun `il fedele resiste piu a lungo dell'ambizioso`() {
        val morale = rules.lowMoraleThreshold
        assertTrue(!MoraleEngine.wantsToLeave(player(morale = morale - 5, traits = setOf(Trait.FEDELE)), rules))
        assertTrue(MoraleEngine.wantsToLeave(player(morale = morale + 5, traits = setOf(Trait.AMBIZIOSO)), rules))
    }

    /** Il giocatore creato dal proprietario non puo' andarsene: e' la regola del gioco. */
    @Test
    fun `il player custom non chiede mai la cessione`() {
        assertTrue(!MoraleEngine.wantsToLeave(player(morale = 1, isCustom = true), rules))
    }

    @Test
    fun `chi e scontento puo rifiutare il rinnovo`() {
        assertTrue(!MoraleEngine.wouldAcceptRenewal(player(morale = 10), rules))
        assertTrue(MoraleEngine.wouldAcceptRenewal(player(morale = 80), rules))
    }

    @Test
    fun `i leader alzano il morale di tutta la rosa`() {
        val conLeader = MoraleEngine.leadershipBonus(
            listOf(player(traits = setOf(Trait.LEADER)), player(), player()),
        )
        val senza = MoraleEngine.leadershipBonus(listOf(player(), player(), player()))
        assertTrue(conLeader > senza)
    }

    @Test
    fun `il rinnovo tira su il morale`() {
        assertTrue(MoraleEngine.afterRenewal(player()).delta > 0)
    }

    @Test
    fun `una cessione rifiutata brucia`() {
        assertTrue(MoraleEngine.afterRefusedTransfer(player()).delta < 0)
    }
}
