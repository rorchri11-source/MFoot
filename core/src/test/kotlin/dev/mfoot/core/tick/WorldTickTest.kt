package dev.mfoot.core.tick

import dev.mfoot.core.ai.AiPersonalityGenerator
import dev.mfoot.core.ai.AiState
import dev.mfoot.core.calendar.Fixture
import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.config.IncomeCadence
import dev.mfoot.core.market.AuctionRules
import dev.mfoot.core.market.AuctionTarget
import dev.mfoot.core.market.Negotiation
import dev.mfoot.core.market.OfferTerms
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.CompetitionId
import dev.mfoot.core.model.Contract
import dev.mfoot.core.model.Loan
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.PlayerId
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldTickTest {

    private val config = ConfigPresets.sprint()
    private val now: Instant = Instant.parse("2026-09-05T21:00:00Z")
    private val fiveMinutesAgo: Instant = now.minusSeconds(300)

    private fun input(
        lastProcessedAt: Instant? = fiveMinutesAgo,
        today: MatchDay = MatchDay(3),
        settled: Set<Int> = setOf(3),
        block: TickInput.() -> TickInput = { this },
    ) = TickInput(
        now = now,
        lastProcessedAt = lastProcessedAt,
        today = today,
        config = config,
        settledMatchDays = settled,
    ).block()

    private fun fixture(at: LocalDateTime, id: Long = 1L) = Fixture(
        id = id,
        competitionId = CompetitionId(1),
        round = 1,
        roundLabel = "Giornata 1",
        home = ClubId(1),
        away = ClubId(2),
        matchDay = MatchDay(3),
        kickoff = at,
    )

    private fun utc(instant: Instant): LocalDateTime =
        LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    // ------------------------------------------------------------------ finestra

    @Test
    fun `senza niente in scadenza il piano e vuoto`() {
        val output = WorldTick.run(input())
        assertTrue(output.isEmpty, "effetti inattesi: ${output.effects}")
        assertEquals(now, output.processedUpTo)
    }

    @Test
    fun `il momento di elaborazione avanza sempre a adesso`() {
        assertEquals(now, WorldTick.run(input()).processedUpTo)
    }

    @Test
    fun `al primissimo avvio non si recupera dall'inizio dei tempi`() {
        val output = WorldTick.run(
            input(lastProcessedAt = null) {
                copy(pendingFixtures = listOf(fixture(utc(now.minusSeconds(86_400)))))
            },
        )
        assertTrue(
            output.count<TickEffect.SimulaPartita>() == 0,
            "al primo avvio ha provato a simulare una partita di ieri",
        )
    }

    /**
     * La proprieta' che tiene in piedi l'architettura a costo zero: se GitHub ritarda o
     * salta un'esecuzione, al giro dopo si recupera tutto l'intervallo perso.
     */
    @Test
    fun `un tick saltato recupera tutto quello che era rimasto indietro`() {
        val output = WorldTick.run(
            input(lastProcessedAt = now.minusSeconds(3600)) {
                copy(
                    pendingFixtures = listOf(
                        fixture(utc(now.minusSeconds(2400)), id = 1L),
                        fixture(utc(now.minusSeconds(1200)), id = 2L),
                        fixture(utc(now.minusSeconds(120)), id = 3L),
                    ),
                )
            },
        )
        assertEquals(3, output.count<TickEffect.SimulaPartita>(), "non ha recuperato le partite perse")
    }

    @Test
    fun `il recupero e limitato per non andare in timeout`() {
        val output = WorldTick.run(
            input(lastProcessedAt = now.minusSeconds(60L * 24 * 3600)) {
                copy(pendingFixtures = listOf(fixture(utc(now.minusSeconds(30L * 24 * 3600)))))
            },
        )
        assertEquals(0, output.count<TickEffect.SimulaPartita>())
        assertTrue(output.notes.any { it.contains("Recupero limitato") })
    }

    /** Ogni momento appartiene a una sola finestra: mai contato due volte. */
    @Test
    fun `una partita gia elaborata non viene rifatta al giro dopo`() {
        val kickoff = now.minusSeconds(120)
        val primoGiro = WorldTick.run(
            input(lastProcessedAt = now.minusSeconds(300)) {
                copy(pendingFixtures = listOf(fixture(utc(kickoff))))
            },
        )
        assertEquals(1, primoGiro.count<TickEffect.SimulaPartita>())

        val secondoGiro = WorldTick.run(
            TickInput(
                now = now.plusSeconds(300),
                lastProcessedAt = primoGiro.processedUpTo,
                today = MatchDay(3),
                config = config,
                pendingFixtures = listOf(fixture(utc(kickoff))),
                settledMatchDays = setOf(3),
            ),
        )
        assertEquals(
            0, secondoGiro.count<TickEffect.SimulaPartita>(),
            "la stessa partita e' stata pianificata due volte",
        )
    }

    // ------------------------------------------------------------------ partite

    @Test
    fun `una partita in orario viene simulata`() {
        val output = WorldTick.run(
            input { copy(pendingFixtures = listOf(fixture(utc(now.minusSeconds(60))))) },
        )
        assertEquals(1, output.count<TickEffect.SimulaPartita>())
    }

    @Test
    fun `una partita futura non viene toccata`() {
        val output = WorldTick.run(
            input { copy(pendingFixtures = listOf(fixture(utc(now.plusSeconds(3600))))) },
        )
        assertEquals(0, output.count<TickEffect.SimulaPartita>())
    }

    @Test
    fun `una partita senza orario viene ignorata`() {
        val senzaOrario = fixture(utc(now)).copy(kickoff = null)
        val output = WorldTick.run(input { copy(pendingFixtures = listOf(senzaOrario)) })
        assertEquals(0, output.count<TickEffect.SimulaPartita>())
    }

    // --------------------------------------------------------------------- aste

    @Test
    fun `un'asta scaduta viene chiusa`() {
        val asta = AuctionRules.open(
            1L, AuctionTarget.ForPlayer(PlayerId(1)), ClubId(1),
            now.minusSeconds(5500), config.market,
        )
        val output = WorldTick.run(input { copy(openAuctions = listOf(asta)) })
        assertEquals(1, output.count<TickEffect.ChiudiAsta>())
    }

    @Test
    fun `un'asta ancora in corso resta aperta`() {
        val asta = AuctionRules.open(
            1L, AuctionTarget.ForPlayer(PlayerId(1)), ClubId(1), now, config.market,
        )
        val output = WorldTick.run(input { copy(openAuctions = listOf(asta)) })
        assertEquals(0, output.count<TickEffect.ChiudiAsta>())
    }

    // --------------------------------------------------------------- trattative

    @Test
    fun `una trattativa scaduta viene chiusa`() {
        val trattativa = Negotiation(
            id = 1L, playerId = PlayerId(1), buyer = ClubId(1), seller = ClubId(2),
            terms = OfferTerms(15, contractMatchDays = 19),
            awaiting = ClubId(2),
            expiresAt = now.minusSeconds(60),
        )
        val output = WorldTick.run(input { copy(openNegotiations = listOf(trattativa)) })
        assertEquals(1, output.count<TickEffect.ScadiTrattativa>())
    }

    // ------------------------------------------------------ contratti e prestiti

    /**
     * Contratti e prestiti scadono per **giornata di gioco**, non per orologio: e' cosi'
     * che il ritmo reale resta configurabile senza rompere niente.
     */
    @Test
    fun `un contratto scaduto per giornata viene chiuso`() {
        val contratto = Contract(
            playerId = PlayerId(1), clubId = ClubId(1),
            signedOn = MatchDay(0), expiresOn = MatchDay(3),
            wagePerMatchDay = 2, pricePaid = 20,
        )
        val output = WorldTick.run(
            input(today = MatchDay(3)) { copy(activeContracts = listOf(contratto)) },
        )
        assertEquals(1, output.count<TickEffect.ScadiContratto>())
    }

    @Test
    fun `un contratto ancora valido non viene toccato`() {
        val contratto = Contract(
            playerId = PlayerId(1), clubId = ClubId(1),
            signedOn = MatchDay(0), expiresOn = MatchDay(19),
            wagePerMatchDay = 2, pricePaid = 20,
        )
        val output = WorldTick.run(input { copy(activeContracts = listOf(contratto)) })
        assertEquals(0, output.count<TickEffect.ScadiContratto>())
    }

    @Test
    fun `un prestito scaduto restituisce il giocatore`() {
        val prestito = Loan(
            playerId = PlayerId(1), ownerClub = ClubId(1), borrowerClub = ClubId(2),
            startsOn = MatchDay(1), endsOn = MatchDay(3), feePerMatchDay = 2,
        )
        val output = WorldTick.run(
            input(today = MatchDay(3)) { copy(activeLoans = listOf(prestito)) },
        )
        assertEquals(1, output.count<TickEffect.RestituisciPrestito>())
    }

    // ----------------------------------------------------------------------- AI

    /**
     * L'anti-sciame visto dal tick: si svegliano solo le AI il cui orario e' arrivato,
     * non tutte quante.
     */
    @Test
    fun `si svegliano solo le AI dovute`() {
        val dovuta = AiState(
            AiPersonalityGenerator.generate(ClubId(1), 1L, config.ai),
            nextWakeAt = now.minusSeconds(60),
        )
        val addormentata = AiState(
            AiPersonalityGenerator.generate(ClubId(2), 2L, config.ai),
            nextWakeAt = now.plusSeconds(7200),
        )

        val output = WorldTick.run(input { copy(aiStates = listOf(dovuta, addormentata)) })

        assertEquals(1, output.count<TickEffect.SvegliaAi>())
        assertEquals(
            ClubId(1),
            output.effects.filterIsInstance<TickEffect.SvegliaAi>().first().clubId,
        )
    }

    @Test
    fun `un'AI rimasta indietro viene comunque svegliata`() {
        val arretrata = AiState(
            AiPersonalityGenerator.generate(ClubId(1), 1L, config.ai),
            nextWakeAt = now.minusSeconds(7200),
        )
        val output = WorldTick.run(input { copy(aiStates = listOf(arretrata)) })
        assertEquals(1, output.count<TickEffect.SvegliaAi>(), "un'AI dimenticata non deve restare ferma per sempre")
    }

    @Test
    fun `venticinque AI non si svegliano tutte insieme`() {
        val stati = (1..25).map { id ->
            val p = AiPersonalityGenerator.generate(ClubId(id.toLong()), 500L, config.ai)
            AiState(p, dev.mfoot.core.ai.AiScheduler.initialWake(p, now, 500L))
        }
        val output = WorldTick.run(input { copy(aiStates = stati) })
        assertTrue(
            output.count<TickEffect.SvegliaAi>() < 5,
            "si sono svegliate ${output.count<TickEffect.SvegliaAi>()} AI in un colpo: e' uno sciame",
        )
    }

    // ------------------------------------------------------------ fine giornata

    @Test
    fun `una giornata non ancora liquidata produce le operazioni di fine giornata`() {
        val output = WorldTick.run(input(settled = emptySet()))

        assertEquals(1, output.count<TickEffect.RecuperaStamina>())
        assertEquals(1, output.count<TickEffect.VerificaPromesse>())
        assertEquals(1, output.count<TickEffect.PagaStipendi>())
        assertEquals(1, output.count<TickEffect.DistribuisciCrediti>())
    }

    /**
     * Senza questo controllo, un tick che rigira sulla stessa finestra pagherebbe gli
     * stipendi due volte — ed e' esattamente quello che succede se una transazione
     * fallisce a meta'.
     */
    @Test
    fun `una giornata gia liquidata non viene pagata due volte`() {
        val output = WorldTick.run(input(today = MatchDay(3), settled = setOf(3)))
        assertEquals(0, output.count<TickEffect.PagaStipendi>())
        assertEquals(0, output.count<TickEffect.DistribuisciCrediti>())
    }

    @Test
    fun `con gli stipendi disattivati non si paga niente`() {
        val senzaStipendi = config.copy(economy = config.economy.copy(wagesEnabled = false))
        val output = WorldTick.run(
            input(settled = emptySet()) { copy(config = senzaStipendi) },
        )
        assertEquals(0, output.count<TickEffect.PagaStipendi>())
    }

    /**
     * La settimana è di calendario, non di giornate.
     *
     * È l'unica misura del sistema che guarda l'orologio invece del contatore delle
     * giornate, ed è voluto: uno stipendio "a settimana" lo si pensa in lunedì, e
     * tradurlo in sette giornate di gioco lo farebbe arrivare quando capita.
     */
    @Test
    fun `la cadenza settimanale paga quando si attraversa un lunedì`() {
        val settimanale = config.copy(
            economy = config.economy.copy(incomeCadence = IncomeCadence.PER_SETTIMANA),
        )

        // Domenica 6 settembre 2026 alle 23:50 → lunedì 7 alle 00:10.
        val attraversaLunedi = WorldTick.run(
            input(settled = emptySet()) {
                copy(
                    config = settimanale,
                    lastProcessedAt = Instant.parse("2026-09-06T23:50:00Z"),
                    now = Instant.parse("2026-09-07T00:10:00Z"),
                )
            },
        )

        // Martedì 8, cinque minuti: nessun confine attraversato.
        val dentroLaSettimana = WorldTick.run(
            input(settled = emptySet()) {
                copy(
                    config = settimanale,
                    lastProcessedAt = Instant.parse("2026-09-08T10:00:00Z"),
                    now = Instant.parse("2026-09-08T10:05:00Z"),
                )
            },
        )

        assertEquals(1, attraversaLunedi.count<TickEffect.DistribuisciCrediti>())
        assertEquals(0, dentroLaSettimana.count<TickEffect.DistribuisciCrediti>())
    }

    @Test
    fun `la cadenza mensile paga quando cambia il mese`() {
        val mensile = config.copy(
            economy = config.economy.copy(incomeCadence = IncomeCadence.PER_MESE),
        )

        val cambiaMese = WorldTick.run(
            input(settled = emptySet()) {
                copy(
                    config = mensile,
                    lastProcessedAt = Instant.parse("2026-09-30T23:55:00Z"),
                    now = Instant.parse("2026-10-01T00:05:00Z"),
                )
            },
        )
        val stessoMese = WorldTick.run(
            input(settled = emptySet()) {
                copy(
                    config = mensile,
                    lastProcessedAt = Instant.parse("2026-10-15T10:00:00Z"),
                    now = Instant.parse("2026-10-15T10:05:00Z"),
                )
            },
        )

        assertEquals(1, cambiaMese.count<TickEffect.DistribuisciCrediti>())
        assertEquals(0, stessoMese.count<TickEffect.DistribuisciCrediti>())
    }

    /** `MAI` esiste per le leghe dove i soldi si guadagnano solo sul campo. */
    @Test
    fun `la cadenza mai non paga niente`() {
        val mai = config.copy(economy = config.economy.copy(incomeCadence = IncomeCadence.MAI))
        val output = WorldTick.run(input(settled = emptySet()) { copy(config = mai) })

        assertEquals(0, output.count<TickEffect.DistribuisciCrediti>())
    }

    @Test
    fun `senza entrate ricorrenti non si distribuisce niente`() {
        val senzaEntrate = config.copy(economy = config.economy.copy(recurringIncome = 0))
        val output = WorldTick.run(
            input(settled = emptySet()) { copy(config = senzaEntrate) },
        )
        assertEquals(0, output.count<TickEffect.DistribuisciCrediti>())
    }

    // ---------------------------------------------------------------- riepilogo

    @Test
    fun `il riepilogo parte all'orario configurato`() {
        val digestHour = config.notifications.dailyDigestHour
        val momento = now.atZone(ZoneOffset.UTC).toLocalDate()
            .atTime(digestHour).toInstant(ZoneOffset.UTC)

        val output = WorldTick.run(
            TickInput(
                now = momento.plusSeconds(60),
                lastProcessedAt = momento.minusSeconds(240),
                today = MatchDay(3),
                config = config,
                settledMatchDays = setOf(3),
            ),
        )
        assertEquals(1, output.count<TickEffect.InviaRiepilogo>())
    }

    @Test
    fun `il riepilogo non parte due volte nello stesso giorno`() {
        val digestHour = config.notifications.dailyDigestHour
        val momento = now.atZone(ZoneOffset.UTC).toLocalDate()
            .atTime(digestHour).toInstant(ZoneOffset.UTC)

        val output = WorldTick.run(
            TickInput(
                now = momento.plusSeconds(60),
                lastProcessedAt = momento.minusSeconds(240),
                today = MatchDay(3),
                config = config,
                settledMatchDays = setOf(3),
                lastDigestAt = momento,
            ),
        )
        assertEquals(0, output.count<TickEffect.InviaRiepilogo>())
    }

    // ------------------------------------------------------------------- ordine

    /**
     * L'ordine non e' cosmetico: un'asta chiusa prima di una partita puo' cambiare chi
     * scende in campo in quella partita.
     */
    @Test
    fun `gli effetti escono in ordine cronologico`() {
        val asta = AuctionRules.open(
            1L, AuctionTarget.ForPlayer(PlayerId(1)), ClubId(1),
            // Il preset Sprint usa aste da 90 minuti: per farla scadere dentro la
            // finestra bisogna averla aperta poco piu' di 90 minuti fa.
            now.minusSeconds(5500), config.market,
        )
        val output = WorldTick.run(
            input(settled = emptySet()) {
                copy(
                    pendingFixtures = listOf(fixture(utc(now.minusSeconds(60)))),
                    openAuctions = listOf(asta),
                )
            },
        )

        output.effects.zipWithNext().forEach { (a, b) ->
            assertTrue(
                !b.dueAt.isBefore(a.dueAt),
                "effetti fuori ordine: ${a.dueAt} prima di ${b.dueAt}",
            )
        }
    }

    @Test
    fun `un mondo pieno di scadenze produce tutti gli effetti insieme`() {
        val asta = AuctionRules.open(
            1L, AuctionTarget.ForPlayer(PlayerId(1)), ClubId(1),
            // Il preset Sprint usa aste da 90 minuti: per farla scadere dentro la
            // finestra bisogna averla aperta poco piu' di 90 minuti fa.
            now.minusSeconds(5500), config.market,
        )
        val output = WorldTick.run(
            input(today = MatchDay(3), settled = emptySet()) {
                copy(
                    pendingFixtures = listOf(fixture(utc(now.minusSeconds(60)))),
                    openAuctions = listOf(asta),
                    activeContracts = listOf(
                        Contract(PlayerId(1), ClubId(1), MatchDay(0), MatchDay(3), 2, 20),
                    ),
                    activeLoans = listOf(
                        Loan(PlayerId(2), ClubId(1), ClubId(2), MatchDay(1), MatchDay(3), 2),
                    ),
                )
            },
        )

        assertEquals(1, output.count<TickEffect.SimulaPartita>())
        assertEquals(1, output.count<TickEffect.ChiudiAsta>())
        assertEquals(1, output.count<TickEffect.ScadiContratto>())
        assertEquals(1, output.count<TickEffect.RestituisciPrestito>())
        assertEquals(1, output.count<TickEffect.RecuperaStamina>())
    }
}
