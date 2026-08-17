package dev.mfoot.core.ai

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.market.AuctionRules
import dev.mfoot.core.market.AuctionTarget
import dev.mfoot.core.match.Formation
import dev.mfoot.core.model.Club
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.world.GeneratedWorld
import dev.mfoot.core.world.WorldGenerator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val T0: Instant = Instant.parse("2026-09-01T08:00:00Z")

class AiPersonalityTest {

    private val config = ConfigPresets.sprint().ai

    @Test
    fun `la personalita e riproducibile dal seed`() {
        val a = AiPersonalityGenerator.generate(ClubId(3), 12345L, config)
        val b = AiPersonalityGenerator.generate(ClubId(3), 12345L, config)
        assertEquals(a, b)
    }

    @Test
    fun `club diversi hanno personalita diverse`() {
        val personalita = (1..20).map {
            AiPersonalityGenerator.generate(ClubId(it.toLong()), 999L, config)
        }
        assertTrue(personalita.toSet().size > 15, "le personalita' si somigliano troppo")
    }

    /**
     * La regola anti-sciame piu' importante di tutte.
     *
     * Se tutte le AI vivessero di sera, alle 21 se ne sveglierebbero venticinque insieme
     * e avremmo ricreato esattamente lo sciame che stiamo cercando di evitare.
     */
    @Test
    fun `gli orari di attivita sono sparsi sull'arco della giornata`() {
        val orari = (1..25).map {
            AiPersonalityGenerator.generate(ClubId(it.toLong()), 777L, config).activeFromHour
        }
        assertTrue(orari.toSet().size >= 8, "solo ${orari.toSet().size} fasce orarie diverse su 25 club")
        assertTrue(orari.min() < 12, "nessun club attivo di mattina")
        assertTrue(orari.max() > 16, "nessun club attivo di sera")
    }

    @Test
    fun `la finestra di attivita e sempre valida`() {
        (1..50).forEach {
            val p = AiPersonalityGenerator.generate(ClubId(it.toLong()), 42L, config)
            assertTrue(p.activeToHour > p.activeFromHour)
            assertTrue(p.activeFromHour in 0..23 && p.activeToHour in 0..23)
            assertTrue(p.isAwakeAt(p.activeFromHour))
            assertTrue(!p.isAwakeAt(p.activeToHour))
        }
    }

    @Test
    fun `la difficolta della lega alza l'aggressivita`() {
        val facile = AiPersonalityGenerator.generate(
            ClubId(1), 5L, config.copy(difficultyMultiplier = 0.5),
        )
        val difficile = AiPersonalityGenerator.generate(
            ClubId(1), 5L, config.copy(difficultyMultiplier = 1.5),
        )
        assertTrue(difficile.marketAggression > facile.marketAggression)
    }

    @Test
    fun `le fissazioni danno un bonus solo al ruolo giusto`() {
        val p = AiPersonalityGenerator.generate(ClubId(1), 1L, config)
            .copy(obsessions = setOf(AiObsession.PORTIERE_FORTE))
        assertTrue(p.obsessionBonusFor(Position.POR) > 0)
        assertEquals(0.0, p.obsessionBonusFor(Position.ATT))
    }
}

class AiSchedulerTest {

    private val config = ConfigPresets.sprint().ai

    private fun state(clubId: Long = 1L, seed: Long = 100L): AiState {
        val personality = AiPersonalityGenerator.generate(ClubId(clubId), seed, config)
        return AiState(personality, AiScheduler.initialWake(personality, T0, seed))
    }

    @Test
    fun `il primo risveglio cade dentro la finestra di attivita`() {
        (1..30).forEach { id ->
            val s = state(id.toLong())
            val ora = s.nextWakeAt.atZone(ZoneOffset.UTC).hour
            assertTrue(
                s.personality.isAwakeAt(ora),
                "il club $id si sveglia alle $ora ma vive fra " +
                    "${s.personality.activeFromHour} e ${s.personality.activeToHour}",
            )
        }
    }

    @Test
    fun `il risveglio non viene mai programmato nel passato`() {
        (1..30).forEach { id ->
            assertTrue(state(id.toLong()).nextWakeAt.isAfter(T0))
        }
    }

    /**
     * Il cuore dell'anti-sciame: il World Tick non scorre tutte le AI, sveglia solo
     * quelle il cui orario e' arrivato. Chi dorme non sa nemmeno che l'asta esiste.
     */
    @Test
    fun `a una data ora si sveglia solo una minoranza dei club`() {
        val stati = (1..25).map { state(it.toLong()) }
        val alle10 = AiScheduler.due(stati, T0.plusSeconds(2 * 3600))

        assertTrue(
            alle10.size < stati.size / 2,
            "alle 10 si sono svegliati ${alle10.size} club su 25: e' uno sciame",
        )
    }

    @Test
    fun `i risvegli sono distribuiti nel tempo`() {
        val orari = (1..25).map { state(it.toLong()).nextWakeAt.epochSecond }
        assertTrue(
            orari.toSet().size > 20,
            "solo ${orari.toSet().size} momenti diversi su 25 club",
        )
    }

    @Test
    fun `programmare il prossimo risveglio lo sposta in avanti`() {
        val s = state()
        val dopo = AiScheduler.scheduleNext(s, s.nextWakeAt, 100L)
        assertTrue(dopo.nextWakeAt.isAfter(s.nextWakeAt))
    }

    // ------------------------------------------------------ tetto azioni

    @Test
    fun `il tetto di azioni giornaliere viene rispettato`() {
        var s = state()
        val now = T0.plusSeconds(3600)

        repeat(config.maxMarketActionsPerDay) {
            assertTrue(AiScheduler.hasActionsLeft(s, now, config))
            s = AiScheduler.recordAction(s, now)
        }
        assertTrue(
            !AiScheduler.hasActionsLeft(s, now, config),
            "l'AI ha superato il tetto di ${config.maxMarketActionsPerDay} azioni al giorno",
        )
    }

    @Test
    fun `il contatore delle azioni si azzera il giorno dopo`() {
        var s = state()
        repeat(config.maxMarketActionsPerDay) { s = AiScheduler.recordAction(s, T0) }
        assertTrue(!AiScheduler.hasActionsLeft(s, T0, config))

        val domani = T0.plusSeconds(30 * 3600)
        assertTrue(AiScheduler.hasActionsLeft(s, domani, config))
    }

    // ------------------------------------------------------- ritardo umano

    /**
     * Un'AI che risponde in cinquanta millisecondi fa capire che si sta giocando contro
     * un programma e toglie ogni tensione all'asta.
     */
    @Test
    fun `il ritardo di rilancio e sempre di minuti non di secondi`() {
        (1..30).forEach { id ->
            val p = AiPersonalityGenerator.generate(ClubId(id.toLong()), 3L, config)
            val delay = AiScheduler.rebidDelaySeconds(p, config, seed = id.toLong())
            assertTrue(
                delay >= config.minRebidDelayMinutes * 60L,
                "il club $id rilancia dopo soli $delay secondi",
            )
            assertTrue(delay <= config.maxRebidDelayMinutes * 60L)
        }
    }

    @Test
    fun `i club pazienti aspettano piu di quelli impulsivi`() {
        val base = AiPersonalityGenerator.generate(ClubId(1), 1L, config)
        val paziente = base.copy(patience = 1.0)
        val impulsivo = base.copy(patience = 0.0)

        assertTrue(
            AiScheduler.rebidDelaySeconds(paziente, config, 1L) >
                AiScheduler.rebidDelaySeconds(impulsivo, config, 1L),
        )
    }

    // ---------------------------------------------------------- cooldown

    @Test
    fun `dopo un rifiuto l'AI non riprova per un po`() {
        val s = state().copy(refusalCooldowns = mapOf(ClubId(9) to MatchDay(15)))
        assertTrue(!s.canActOn(ClubId(9), MatchDay(10)))
        assertTrue(s.canActOn(ClubId(9), MatchDay(15)))
        assertTrue(s.canActOn(ClubId(8), MatchDay(10)))
    }
}

class AiManagerTest {

    private val config = ConfigPresets.sprint(16, 8, LocalDate.of(2026, 9, 1))
    private val world: GeneratedWorld = WorldGenerator.generate(config)

    private fun state(clubId: Long = 1L) = AiState(
        AiPersonalityGenerator.generate(ClubId(clubId), 555L, config.ai),
        T0,
    )

    /** Le cifre sono in migliaia: 100_000 = 100M, la scala della lega di riferimento. */
    private fun club(credits: Int = 100_000, committed: Int = 0) = Club(
        id = ClubId(1), name = "Verdemar", shortName = "VDM",
        isAi = true, credits = credits, committedCredits = committed,
    )

    private fun squadOf(size: Int): List<Player> = world.players.take(size)

    /**
     * Una rosa che **non ha nessuno** nel ruolo indicato.
     *
     * Serve ai test che verificano cosa fa l'AI quando un giocatore le interessa: con la
     * rosa dei sedici piu' forti il centrocampo e' gia' pieno, l'appetibilita' scende sotto
     * la soglia di interesse, e il test finirebbe per misurare il disinteresse invece della
     * decisione. Il buco in rosa e' la premessa, non un dettaglio.
     */
    private fun squadWithout(position: Position, size: Int): List<Player> =
        world.players.filter { it.primaryPosition != position }.take(size)

    private fun someTarget(): Player =
        world.players.first { it.overall in 74..80 && !it.isGoalkeeper }

    // ------------------------------------------------------------ valutazione

    @Test
    fun `un giocatore forte interessa piu di uno scarso`() {
        val s = state()
        val c = club()
        val squad = squadOf(18)

        val forte = world.players.filter { it.overall >= 82 }.first()
        val scarso = world.players.filter { it.overall <= 60 }.first()

        val appealForte = AiManager.evaluate(s, c, squad, forte, config).appeal
        val appealScarso = AiManager.evaluate(s, c, squad, scarso, config).appeal

        assertTrue(appealForte > appealScarso, "forte $appealForte, scarso $appealScarso")
    }

    @Test
    fun `un ruolo scoperto vale piu di uno gia coperto`() {
        val s = state()
        val c = club()
        val target = someTarget()

        val rosaSenzaQuelRuolo = world.players
            .filter { it.primaryPosition != target.primaryPosition }.take(18)
        val rosaPienaDiQuelRuolo = world.players
            .filter { it.primaryPosition == target.primaryPosition }.take(6) +
            world.players.filter { it.primaryPosition != target.primaryPosition }.take(12)

        val scoperto = AiManager.evaluate(s, c, rosaSenzaQuelRuolo, target, config).appeal
        val coperto = AiManager.evaluate(s, c, rosaPienaDiQuelRuolo, target, config).appeal

        assertTrue(scoperto > coperto, "scoperto $scoperto, coperto $coperto")
    }

    /**
     * Il tetto e' il tetto. E' quello che rende l'AI un avversario battibile:
     * chi valuta il giocatore piu' di lei, lo prende.
     */
    @Test
    fun `il tetto non supera mai i crediti disponibili`() {
        val s = state()
        val c = club(credits = 30_000, committed = 21_000)

        world.players.take(50).forEach { player ->
            val appeal = AiManager.evaluate(s, c, squadOf(16), player, config)
            assertTrue(
                appeal.ceiling <= c.availableCredits,
                "tetto ${appeal.ceiling} contro ${c.availableCredits} disponibili",
            )
        }
    }

    @Test
    fun `un club senza crediti non punta a niente`() {
        val s = state()
        val c = club(credits = 15_000, committed = 15_000)
        val appeal = AiManager.evaluate(s, c, squadOf(16), someTarget(), config)
        assertEquals(0, appeal.ceiling)
        assertTrue(!appeal.isInterested)
    }

    /**
     * La valutazione si adatta da sola a qualsiasi economia l'admin abbia impostato,
     * perche' l'AI non conosce nessun numero assoluto.
     */
    @Test
    fun `il tetto scala con il budget della lega`() {
        val s = state()
        val target = someTarget()

        val ricca = config.copy(economy = config.economy.copy(startingCredits = 400_000))
        val povera = config.copy(economy = config.economy.copy(startingCredits = 20_000))

        val tettoRicco = AiManager.evaluate(s, club(credits = 400_000), squadOf(16), target, ricca).ceiling
        val tettoPovero = AiManager.evaluate(s, club(credits = 20_000), squadOf(16), target, povera).ceiling

        assertTrue(tettoRicco > tettoPovero * 5, "ricco $tettoRicco, povero $tettoPovero")
    }

    // -------------------------------------------------------------- anti-sciame

    /**
     * Regola anti-sciame numero 2: la seconda AI ci pensa, la terza quasi mai, la
     * quarta mai. Risultato naturale: 1-3 AI per asta invece di venticinque.
     */
    @Test
    fun `l'interesse crolla quando ci sono gia altre AI sul giocatore`() {
        val s = state()
        val c = club()
        val squad = squadOf(16)
        val target = someTarget()

        val appealSolo = AiManager.evaluate(s, c, squad, target, config, competingAi = 0).appeal
        val appealDue = AiManager.evaluate(s, c, squad, target, config, competingAi = 1).appeal
        val appealQuattro = AiManager.evaluate(s, c, squad, target, config, competingAi = 3).appeal

        assertTrue(appealDue < appealSolo)
        assertTrue(appealQuattro < appealDue)
        assertTrue(
            appealQuattro < appealSolo * 0.25,
            "con tre AI gia' in gara l'interesse deve quasi sparire: $appealQuattro contro $appealSolo",
        )
    }

    @Test
    fun `il fattore di affollamento e monotono decrescente`() {
        val valori = (0..5).map { AiManager.crowdingFactor(it, config) }
        valori.zipWithNext().forEach { (a, b) -> assertTrue(b < a) }
        assertEquals(1.0, valori.first())
    }

    @Test
    fun `dopo due volte superata l'AI molla il giocatore`() {
        var s = state()
        s = AiManager.abandonAfterOutbids(s, auctionId = 7L, timesOutbid = 1)
        assertTrue(7L !in s.abandonedTargets, "dopo un solo sorpasso non deve gia' mollare")

        s = AiManager.abandonAfterOutbids(s, auctionId = 7L, timesOutbid = 2)
        assertTrue(7L in s.abandonedTargets)
    }

    // ------------------------------------------------------------------- aste

    @Test
    fun `l'AI dichiara il proprio tetto e non un rilancio minimo`() {
        val s = state()
        val c = club()
        val target = someTarget()
        // La rosa non ha nessuno in quel ruolo: e' la premessa perche' il giocatore
        // interessi davvero. Con il centrocampo pieno il test misurerebbe il disinteresse.
        val appeal = AiManager.evaluate(s, c, squadWithout(target.primaryPosition, 16), target, config)

        val auction = AuctionRules.open(
            1L, AuctionTarget.ForPlayer(target.id), ClubId(2), T0, config.market,
        )
        val bid = AiManager.decideBid(s, c, auction, appeal, config)

        assertNotNull(bid)
        assertEquals(appeal.ceiling, bid)
    }

    @Test
    fun `l'AI passa quando il prezzo ha superato il suo tetto`() {
        val s = state()
        val c = club()
        val target = someTarget()
        val appeal = AiManager.evaluate(s, c, squadWithout(target.primaryPosition, 16), target, config)

        var auction = AuctionRules.open(
            1L, AuctionTarget.ForPlayer(target.id), ClubId(2), T0, config.market,
        )
        // Un umano dichiara molto piu' del tetto dell'AI.
        val rilancio = AuctionRules.placeBid(
            auction, ClubId(9), appeal.ceiling + 5_000, 400_000, T0.plusSeconds(60), config.market,
        )
        assertTrue(rilancio is dev.mfoot.core.market.BidResult.Accepted)
        auction = rilancio.auction
        // Un secondo umano spinge il prezzo corrente oltre il tetto dell'AI.
        val secondo = AuctionRules.placeBid(
            auction, ClubId(10), appeal.ceiling + 2_000, 400_000, T0.plusSeconds(120), config.market,
        )
        assertTrue(secondo is dev.mfoot.core.market.BidResult.Accepted)

        assertNull(
            AiManager.decideBid(s, c, secondo.auction, appeal, config),
            "l'AI ha superato il proprio tetto: non deve mai farsi prendere dalla foga",
        )
    }

    @Test
    fun `un giocatore che non interessa non fa partire nessuna offerta`() {
        val s = state()
        val c = club()
        val scarso = world.players.minBy { it.overall }
        val appeal = AiManager.evaluate(s, c, squadOf(25), scarso, config, competingAi = 4)

        val auction = AuctionRules.open(
            1L, AuctionTarget.ForPlayer(scarso.id), ClubId(2), T0, config.market,
        )
        assertNull(AiManager.decideBid(s, c, auction, appeal, config))
    }

    // -------------------------------------------------------------- formazione

    @Test
    fun `l'AI schiera undici giocatori con un portiere`() {
        val lineup = AiManager.chooseLineup(ClubId(1), "Verdemar", squadOf(20))
        assertNotNull(lineup)
        assertEquals(11, lineup.slots.size)
        assertNotNull(lineup.goalkeeper)
    }

    @Test
    fun `senza abbastanza giocatori non schiera niente`() {
        assertNull(AiManager.chooseLineup(ClubId(1), "Verdemar", squadOf(8)))
    }

    /**
     * Con due partite al giorno, un'AI che schiera sempre gli stessi undici si distrugge
     * la rosa da sola e a meta' stagione diventa un avversario ridicolo.
     */
    @Test
    fun `l'AI preferisce chi e fresco a parita di qualita`() {
        val base = world.players.filter { it.primaryPosition == Position.CC }.take(2)
        val stanco = base[0].withStamina(25)
        val fresco = base[1].withStamina(100).withAttributes(base[0].attributes)

        val rosa = world.players.take(20)
            .filter { it.id != base[0].id && it.id != base[1].id } + listOf(stanco, fresco)

        val lineup = AiManager.chooseLineup(ClubId(1), "Verdemar", rosa)
        assertNotNull(lineup)

        val titolari = lineup.slots.map { it.player.id }
        if (stanco.id in titolari && fresco.id !in titolari) {
            throw AssertionError("l'AI ha schierato lo stanco lasciando fuori il fresco identico")
        }
    }

    @Test
    fun `il giocatore obbligatorio finisce sempre in campo`() {
        val custom = world.players.first { it.primaryPosition == Position.ATT }
            .copy(id = PlayerId(999_999), isCustom = true)
        val rosa = world.players.take(20) + custom

        val lineup = AiManager.chooseLineup(
            ClubId(1), "Verdemar", rosa, Formation.F_4_3_3, mustStart = custom,
        )
        assertNotNull(lineup)
        assertTrue(lineup.contains(custom.id), "il giocatore obbligatorio non e' stato schierato")
    }

    @Test
    fun `la formazione rispetta i ruoli del modulo`() {
        val lineup = AiManager.chooseLineup(
            ClubId(1), "Verdemar", squadOf(22), Formation.F_3_5_2,
        )
        assertNotNull(lineup)
        assertEquals(
            Formation.F_3_5_2.positions.sortedBy { it.name },
            lineup.slots.map { it.position }.sortedBy { it.name },
        )
    }

    @Test
    fun `la panchina prende i migliori fra i rimasti`() {
        val lineup = AiManager.chooseLineup(ClubId(1), "Verdemar", squadOf(22))
        assertNotNull(lineup)
        assertTrue(lineup.bench.isNotEmpty())
        assertTrue(lineup.bench.size <= 7)
        assertTrue(lineup.bench.none { it.id in lineup.playerIds })
    }
}
