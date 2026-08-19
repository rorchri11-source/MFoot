package dev.mfoot.core.ai

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.market.TradeEvaluator
import dev.mfoot.core.market.TradeVerdict
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.Club
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Contract
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quello che un club gestito dal computer fa di propria iniziativa.
 *
 * Il difetto che questi test difendono: una lega in cui, se gli amici non giocavano, non
 * succedeva assolutamente niente.
 */
class AiInitiativeTest {

    private val config = ConfigPresets.classica()
    private val oggi = MatchDay(10)

    // ------------------------------------------------------------------ proporre scambi

    @Test
    fun `chiede il ruolo che gli manca, non il giocatore piu' forte che vede`() {
        // Ho un portiere solo e attaccanti in abbondanza. Lui ha due portieri e un
        // fuoriclasse in attacco. Un'AI che guardasse l'overall chiederebbe il fuoriclasse.
        val mia = rosa(portieri = 1, difensori = 5, centrocampisti = 6, attaccanti = 6)
        val sua = rosa(
            portieri = 3, difensori = 5, centrocampisti = 5, attaccanti = 5,
            base = 200, overallExtra = 0,
        ) + giocatore(299, Position.ATT, overall = 88)

        val offerta = AiInitiative.proposeTrade(
            stato(), club(crediti = 200_000), mia, ClubId(2), sua, config,
        )

        assertNotNull(offerta, "non ha proposto niente pur avendo un portiere solo")
        val chiesto = sua.first { it.id == offerta.wanted.single() }
        assertTrue(
            chiesto.primaryPosition.isGoalkeeper,
            "ha chiesto un ${chiesto.primaryPosition}, non il portiere che gli manca",
        )
    }

    @Test
    fun `offre chi gli avanza, non chi gli serve`() {
        val mia = rosa(portieri = 1, difensori = 5, centrocampisti = 6, attaccanti = 6)
        val sua = rosa(portieri = 3, difensori = 5, centrocampisti = 5, attaccanti = 5, base = 200)

        val offerta = AiInitiative.proposeTrade(
            stato(), club(crediti = 200_000), mia, ClubId(2), sua, config,
        )

        val ceduto = mia.first { it.id == assertNotNull(offerta).offered.single() }
        assertTrue(
            !ceduto.primaryPosition.isGoalkeeper,
            "sta offrendo il suo unico portiere per prenderne un altro",
        )
    }

    @Test
    fun `una proposta che si puo' permettere e' anche una che verrebbe accettata`() {
        // La prova che conta: le AI si propongono cose sensate fra loro invece di
        // collezionare rifiuti. Il sovrapprezzo esiste per questo.
        val mia = rosa(portieri = 1, difensori = 5, centrocampisti = 6, attaccanti = 6)
        val sua = rosa(portieri = 3, difensori = 5, centrocampisti = 5, attaccanti = 5, base = 200)

        val offerta = assertNotNull(
            AiInitiative.proposeTrade(stato(), club(crediti = 400_000), mia, ClubId(2), sua, config),
        )

        val valori = offerta.offered.associateWith { id ->
            Valuation.marketValue(mia.first { it.id == id }, config)
        }
        val risposta = TradeEvaluator.evaluate(
            offer = offerta,
            personality = personalita(),
            squad = sua,
            availableCredits = 200_000,
            config = config,
            offeredValues = valori,
        )

        assertEquals(
            TradeVerdict.ACCETTO,
            risposta.verdict,
            "l'AI ha costruito una proposta che un'altra AI rifiuta: ${risposta.reason}",
        )
    }

    @Test
    fun `chi ha la rosa al minimo non propone scambi`() {
        val minima = rosa(portieri = 2, difensori = 4, centrocampisti = 4, attaccanti = 3)
        val sua = rosa(portieri = 3, difensori = 5, centrocampisti = 5, attaccanti = 5, base = 200)
        val stretto = config.copy(setup = config.setup.copy(minSquadSize = minima.size))

        assertNull(
            AiInitiative.proposeTrade(stato(), club(crediti = 400_000), minima, ClubId(2), sua, stretto),
        )
    }

    @Test
    fun `senza denaro non si propone niente`() {
        val mia = rosa(portieri = 1, difensori = 5, centrocampisti = 6, attaccanti = 6)
        val sua = rosa(portieri = 3, difensori = 5, centrocampisti = 5, attaccanti = 5, base = 200)

        assertNull(
            AiInitiative.proposeTrade(stato(), club(crediti = 0), mia, ClubId(2), sua, config),
        )
    }

    // ---------------------------------------------------------------------- amichevoli

    @Test
    fun `chi ha appena giocato non chiede un'amichevole`() {
        val stanchi = rosa(portieri = 2, difensori = 5, centrocampisti = 5, attaccanti = 4)
            .map { it.withStamina(45) }

        assertTrue(
            !AiInitiative.wantsFriendly(stato(aggressivita = 0.9), stanchi, config, 4),
            "un'amichevole con le gambe cosi' e' un modo di arrivare rotti alla partita vera",
        )
    }

    @Test
    fun `chi gioca fra poco non chiede un'amichevole`() {
        val freschi = rosa(portieri = 2, difensori = 5, centrocampisti = 5, attaccanti = 4)

        assertTrue(!AiInitiative.wantsFriendly(stato(aggressivita = 0.9), freschi, config, 1))
    }

    @Test
    fun `una squadra riposata e ferma la chiede`() {
        val freschi = rosa(portieri = 2, difensori = 5, centrocampisti = 5, attaccanti = 4)

        assertTrue(AiInitiative.wantsFriendly(stato(aggressivita = 0.9), freschi, config, 5))
    }

    // ------------------------------------------------------------------------ prestiti

    @Test
    fun `accetta un prestito che entra nei due che schiera`() {
        // La regola vecchia chiedeva che fosse piu' forte del titolare, cioe' esattamente il
        // giocatore che nessuno presta: ogni proposta veniva rifiutata e i prestiti
        // sembravano rotti invece che severi.
        val squadra = rosa(portieri = 2, difensori = 5, centrocampisti = 5, attaccanti = 4)
        val nelRuolo = squadra.filter { it.primaryPosition == Position.CC }
            .map { it.overall }.sortedDescending()
        val utile = giocatore(900, Position.CC, overall = nelRuolo[1] + 3)

        assertTrue(
            AiInitiative.answerLoan(
                stato(), club(crediti = 200_000), squadra, utile,
                matchDays = 10, feePerMatchDay = 0, config = config,
            ),
            "ha rifiutato un giocatore che sarebbe il suo secondo in quel ruolo",
        )
    }

    @Test
    fun `rifiuta chi non giocherebbe comunque`() {
        val squadra = rosa(portieri = 2, difensori = 5, centrocampisti = 5, attaccanti = 4)
        val nelRuolo = squadra.filter { it.primaryPosition == Position.CC }
            .map { it.overall }.sortedDescending()
        val panchinaro = giocatore(901, Position.CC, overall = nelRuolo.last() - 2)

        assertTrue(
            !AiInitiative.answerLoan(
                stato(), club(crediti = 200_000), squadra, panchinaro,
                matchDays = 10, feePerMatchDay = 0, config = config,
            ),
            "un prestito che siede in panchina costa un affitto e una casella",
        )
    }

    @Test
    fun `un ruolo scoperto si accetta senza pensarci`() {
        val senzaPunte = rosa(portieri = 2, difensori = 6, centrocampisti = 6, attaccanti = 1)
        val punta = giocatore(902, Position.ATT, overall = 58)

        assertTrue(
            AiInitiative.answerLoan(
                stato(), club(crediti = 200_000), senzaPunte, punta,
                matchDays = 10, feePerMatchDay = 0, config = config,
            ),
        )
    }

    // ------------------------------------------------------------------- la propria rosa

    @Test
    fun `rinnova a chi vale e saluta chi non gioca`() {
        val squadra = rosa(portieri = 2, difensori = 5, centrocampisti = 5, attaccanti = 4)
        val forte = squadra.maxByOrNull { it.overall }!!
        val debole = squadra.minByOrNull { it.overall }!!

        // Con margine sul minimo: sotto il minimo si rinnova a tutti, ed e' giusto cosi'
        // — una rosa illegale non scende in campo, e chi non gioca vale piu' di nessuno.
        val largo = config.copy(setup = config.setup.copy(minSquadSize = squadra.size - 3))

        val contratti = listOf(
            contratto(forte.id, scadenza = 13),
            contratto(debole.id, scadenza = 13),
        )

        val azioni = AiInitiative.squadHousekeeping(
            stato(), club(crediti = 500_000), squadra, contratti, largo, oggi,
        )

        assertTrue(
            azioni.any { it is SquadAction.Rinnova && it.playerId == forte.id },
            "non ha rinnovato al migliore: $azioni",
        )
        assertTrue(
            azioni.any { it is SquadAction.Svincola && it.playerId == debole.id },
            "ha tenuto chi non gioca: $azioni",
        )
    }

    @Test
    fun `non svincola nessuno se la rosa resterebbe sotto il minimo`() {
        val squadra = rosa(portieri = 2, difensori = 4, centrocampisti = 4, attaccanti = 3)
        val stretto = config.copy(setup = config.setup.copy(minSquadSize = squadra.size))
        val contratti = squadra.map { contratto(it.id, scadenza = 13) }

        val azioni = AiInitiative.squadHousekeeping(
            stato(), club(crediti = 500_000), squadra, contratti, stretto, oggi,
        )

        assertTrue(
            azioni.none { it is SquadAction.Svincola },
            "ha svincolato con la rosa gia' al minimo: $azioni",
        )
    }

    @Test
    fun `senza contratti in scadenza non fa niente`() {
        val squadra = rosa(portieri = 2, difensori = 5, centrocampisti = 5, attaccanti = 4)
        val lontani = squadra.map { contratto(it.id, scadenza = 90) }

        assertTrue(
            AiInitiative.squadHousekeeping(
                stato(), club(crediti = 500_000), squadra, lontani, config, oggi,
            ).isEmpty(),
        )
    }

    // ------------------------------------------------------------------------- strumenti

    private fun personalita(aggressivita: Double = 0.6) = AiPersonality(
        clubId = ClubId(1),
        marketAggression = aggressivita,
        youthPreference = 0.5,
        budgetDiscipline = 0.5,
        patience = 0.5,
        obsessions = emptySet(),
        activeFromHour = 9,
        activeToHour = 13,
        checksPerDay = 3,
    )

    private fun stato(aggressivita: Double = 0.6) = AiState(
        personality = personalita(aggressivita),
        nextWakeAt = Instant.EPOCH,
    )

    private fun club(crediti: Int) = Club(
        id = ClubId(1),
        name = "Prova",
        shortName = "PRV",
        ownerName = "computer",
        credits = crediti,
    )

    private fun contratto(player: PlayerId, scadenza: Int) = Contract(
        playerId = player,
        clubId = ClubId(1),
        signedOn = MatchDay(1),
        expiresOn = MatchDay(scadenza),
        wagePerMatchDay = 100,
        pricePaid = 20_000,
    )

    /**
     * Una rosa con una composizione precisa.
     *
     * Gli overall salgono con l'indice, cosi' "il migliore" e "il peggiore" sono
     * prevedibili senza doverli cercare.
     */
    private fun rosa(
        portieri: Int,
        difensori: Int,
        centrocampisti: Int,
        attaccanti: Int,
        base: Int = 100,
        overallExtra: Int = 0,
    ): List<Player> {
        var id = base
        val out = mutableListOf<Player>()
        listOf(
            Position.POR to portieri,
            Position.DC to difensori,
            Position.CC to centrocampisti,
            Position.ATT to attaccanti,
        ).forEach { (ruolo, quanti) ->
            repeat(quanti) {
                out += giocatore(id, ruolo, overall = 55 + (id % 20) + overallExtra)
                id++
            }
        }
        return out
    }

    private fun giocatore(id: Int, ruolo: Position, overall: Int) = Player(
        id = PlayerId(id.toLong()),
        firstName = "Gio",
        lastName = "N$id",
        nationality = "Italia",
        age = 25,
        primaryPosition = ruolo,
        attributes = Attributes.uniform(overall),
        potentialMin = overall,
        potentialMax = overall + 4,
    )
}
