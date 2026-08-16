package dev.mfoot.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AttributesTest {

    @Test
    fun `uniform assegna lo stesso valore a tutti`() {
        val a = Attributes.uniform(70)
        Attr.entries.forEach { assertEquals(70, a[it]) }
    }

    @Test
    fun `with tronca ai limiti validi`() {
        val a = Attributes.uniform(70)
        assertEquals(Attributes.MAX, a.with(Attr.TIRO, 500)[Attr.TIRO])
        assertEquals(Attributes.MIN, a.with(Attr.TIRO, -20)[Attr.TIRO])
    }

    @Test
    fun `with non muta l'originale`() {
        val original = Attributes.uniform(70)
        val modified = original.with(Attr.TIRO, 90)

        assertEquals(70, original[Attr.TIRO], "l'oggetto originale e' stato mutato")
        assertEquals(90, modified[Attr.TIRO])
    }

    @Test
    fun `plus somma e sottrae`() {
        val a = Attributes.uniform(70)
        assertEquals(75, a.plus(Attr.TIRO, 5)[Attr.TIRO])
        assertEquals(65, a.plus(Attr.TIRO, -5)[Attr.TIRO])
    }

    @Test
    fun `weightedMean pesa correttamente`() {
        val a = Attributes.of(default = 40, Attr.TIRO to 90, Attr.DIFESA to 30)
        val mean = a.weightedMean(mapOf(Attr.TIRO to 0.75, Attr.DIFESA to 0.25))
        assertEquals(0.75 * 90 + 0.25 * 30, mean, 0.0001)
    }

    @Test
    fun `weightedMean su mappa vuota non esplode`() {
        assertEquals(0.0, Attributes.uniform(70).weightedMean(emptyMap()))
    }

    @Test
    fun `equals e hashCode confrontano i contenuti`() {
        assertEquals(Attributes.uniform(70), Attributes.uniform(70))
        assertEquals(Attributes.uniform(70).hashCode(), Attributes.uniform(70).hashCode())
        assertNotEquals(Attributes.uniform(70), Attributes.uniform(71))
    }
}

class ZoneTest {

    @Test
    fun `mirror specchia sia fascia che altezza`() {
        // Chi attacca sulla propria sinistra affronta la destra difensiva avversaria.
        assertEquals(Zone.DIF_DX, Zone.ATT_SX.mirror())
        assertEquals(Zone.DIF_SX, Zone.ATT_DX.mirror())
        assertEquals(Zone.DIF_C, Zone.ATT_C.mirror())
        assertEquals(Zone.MID_DX, Zone.MID_SX.mirror())
    }

    @Test
    fun `mirror e la sua stessa inversa`() {
        Zone.entries.forEach { zone ->
            assertEquals(zone, zone.mirror().mirror(), "mirror non e' involutivo per $zone")
        }
    }

    @Test
    fun `advance sale verso l'attacco e si ferma in area`() {
        assertEquals(Zone.MID_C, Zone.DIF_C.advance())
        assertEquals(Zone.ATT_C, Zone.MID_C.advance())
        assertEquals(null, Zone.ATT_C.advance())
    }

    @Test
    fun `advance conserva la fascia`() {
        assertEquals(Zone.MID_SX, Zone.DIF_SX.advance())
        assertEquals(Zone.ATT_DX, Zone.MID_DX.advance())
    }

    @Test
    fun `ci sono esattamente nove zone e tre offensive`() {
        assertEquals(9, Zone.entries.size)
        assertEquals(3, Zone.attacking.size)
    }
}

class PositionTest {

    @Test
    fun `i pesi di zona sommano a uno per i giocatori di movimento`() {
        Position.outfield.forEach { pos ->
            val sum = pos.zoneWeights.values.sum()
            assertEquals(1.0, sum, 0.0001, "i pesi di zona di ${pos.short} sommano a $sum")
        }
    }

    @Test
    fun `il portiere non occupa zone di campo`() {
        assertTrue(Position.POR.zoneWeights.isEmpty())
    }

    @Test
    fun `i pesi di overall sommano a uno`() {
        Position.entries.forEach { pos ->
            val sum = pos.ovrWeights.values.sum()
            assertEquals(1.0, sum, 0.0001, "i pesi di overall di ${pos.short} sommano a $sum")
        }
    }

    @Test
    fun `il portiere usa solo attributi da portiere piu il posizionamento`() {
        val used = Position.POR.ovrWeights.keys
        val allowed = Attr.goalkeeper.toSet() + Attr.POSIZIONAMENTO
        assertTrue(used.all { it in allowed }, "il portiere usa attributi da movimento: $used")
    }

    @Test
    fun `i giocatori di movimento non usano attributi da portiere`() {
        Position.outfield.forEach { pos ->
            val gkAttrs = pos.ovrWeights.keys.filter { it.goalkeeperOnly }
            assertTrue(gkAttrs.isEmpty(), "${pos.short} usa attributi da portiere: $gkAttrs")
        }
    }

    @Test
    fun `terzino destro e sinistro sono simmetrici`() {
        assertEquals(Position.TD.ovrWeights, Position.TS.ovrWeights)

        // Stessi pesi, fascia opposta: DIF_DX/MID_DX contro DIF_SX/MID_SX.
        val destroRibaltato = Position.TD.zoneWeights
            .mapKeys { (zone, _) -> Zone.of(zone.lane.mirror(), zone.band) }
        assertEquals(destroRibaltato, Position.TS.zoneWeights)
    }

    @Test
    fun `l'overall di un attaccante premia il tiro`() {
        val tiratore = Attributes.of(default = 50, Attr.TIRO to 95)
        val difensore = Attributes.of(default = 50, Attr.DIFESA to 95)

        assertTrue(
            Position.ATT.overallOf(tiratore) > Position.ATT.overallOf(difensore),
            "un attaccante che tira bene deve valere piu' di uno che difende bene",
        )
    }

    @Test
    fun `l'overall resta nei limiti validi`() {
        Position.entries.forEach { pos ->
            assertEquals(Attributes.MAX, pos.overallOf(Attributes.uniform(99)))
            assertTrue(pos.overallOf(Attributes.uniform(1)) >= Attributes.MIN)
        }
    }
}

class BandWeightsTest {

    @Test
    fun `un difensore vale piu in difesa che in attacco`() {
        val difensore = Attributes.of(
            default = 45,
            Attr.DIFESA to 88, Attr.INTERCETTAZIONE to 84,
            Attr.POSIZIONAMENTO to 82, Attr.FISICO to 80,
        )
        assertTrue(
            BandWeights.rate(difensore, Band.DIF) > BandWeights.rate(difensore, Band.ATT),
            "il rating difensivo dovrebbe superare quello offensivo",
        )
    }

    @Test
    fun `ogni altezza di campo ha i suoi attributi`() {
        Band.entries.forEach { band ->
            val weights = BandWeights.forBand(band)
            assertTrue(weights.isNotEmpty(), "nessun peso per $band")
            assertTrue(weights.keys.none { it.goalkeeperOnly }, "$band usa attributi da portiere")
        }
    }
}

class PlayerTest {

    private fun player(
        position: Position = Position.ATT,
        secondary: List<Position> = emptyList(),
        overall: Int = 75,
    ) = Player(
        id = PlayerId(1),
        firstName = "Marco",
        lastName = "Ferrero",
        nationality = "Italia",
        age = 24,
        primaryPosition = position,
        secondaryPositions = secondary,
        attributes = Attributes.uniform(overall),
        potentialMin = 78,
        potentialMax = 85,
    )

    @Test
    fun `shortName abbrevia il nome`() {
        assertEquals("M. Ferrero", player().shortName)
    }

    @Test
    fun `nel ruolo naturale non c'e penalita`() {
        val p = player(Position.ATT)
        assertEquals(p.overall, p.overallAt(Position.ATT))
        assertEquals(1.0, p.positionalFitness(Position.ATT))
    }

    @Test
    fun `il ruolo secondario costa poco`() {
        val p = player(Position.ATT, secondary = listOf(Position.SP))
        assertTrue(p.overallAt(Position.SP) > p.overallAt(Position.DC))
        assertTrue(p.positionalFitness(Position.SP) > 0.9)
    }

    @Test
    fun `schierare un attaccante in porta e punito duramente`() {
        val p = player(Position.ATT)
        assertTrue(
            p.positionalFitness(Position.POR) < 0.5,
            "mettere un attaccante in porta deve essere disperazione, non furbizia",
        )
    }

    @Test
    fun `un potenziale incoerente viene rifiutato subito`() {
        assertFailsWith<IllegalArgumentException> {
            player().copy(potentialMin = 90, potentialMax = 70)
        }
    }

    @Test
    fun `le stelle fuori scala vengono rifiutate`() {
        assertFailsWith<IllegalArgumentException> { player().copy(weakFoot = 6) }
        assertFailsWith<IllegalArgumentException> { player().copy(skillStars = 0) }
    }

    @Test
    fun `stamina e morale restano nei limiti`() {
        val p = player()
        assertEquals(100, p.withStamina(500).stamina)
        assertEquals(0, p.withStamina(-10).stamina)
        assertEquals(100, p.withMorale(500).morale)
        assertEquals(5, p.withForm(99).form)
        assertEquals(-5, p.withForm(-99).form)
    }

    @Test
    fun `isInjured confronta la giornata`() {
        val p = player().copy(injuredUntil = MatchDay(10))
        assertTrue(p.isInjured(MatchDay(8)))
        assertTrue(p.isInjured(MatchDay(10)))
        assertTrue(!p.isInjured(MatchDay(11)))
    }
}

class ClubTest {

    private val club = Club(
        id = ClubId(1),
        name = "Verdemar",
        shortName = "VDM",
        credits = 100,
    )

    @Test
    fun `i crediti disponibili escludono quelli impegnati`() {
        val impegnato = club.commit(30)
        assertEquals(100, impegnato.credits)
        assertEquals(30, impegnato.committedCredits)
        assertEquals(70, impegnato.availableCredits)
    }

    /**
     * Senza il blocco fondi un club puo' vincere cinque aste con i soldi per una:
     * e' il bug che rompe una lega e fa litigare venti persone.
     */
    @Test
    fun `non si possono impegnare piu crediti di quelli disponibili`() {
        assertFailsWith<IllegalArgumentException> { club.commit(150) }
        assertFailsWith<IllegalArgumentException> { club.commit(60).commit(60) }
    }

    @Test
    fun `release libera i crediti impegnati senza spenderli`() {
        val dopo = club.commit(40).release(40)
        assertEquals(100, dopo.credits)
        assertEquals(0, dopo.committedCredits)
        assertEquals(100, dopo.availableCredits)
    }

    @Test
    fun `settle trasforma l'impegno in spesa`() {
        val dopo = club.commit(40).settle(40)
        assertEquals(60, dopo.credits)
        assertEquals(0, dopo.committedCredits)
    }

    @Test
    fun `release non porta l'impegno sotto zero`() {
        assertEquals(0, club.commit(10).release(999).committedCredits)
    }

    @Test
    fun `owns guarda entrambe le rose`() {
        val c = club.copy(squad = listOf(PlayerId(1)), youthSquad = listOf(PlayerId(2)))
        assertTrue(c.owns(PlayerId(1)))
        assertTrue(c.owns(PlayerId(2)))
        assertTrue(!c.owns(PlayerId(3)))
        assertEquals(2, c.totalSquadSize)
    }
}

class ContractTest {

    private val contract = Contract(
        playerId = PlayerId(1),
        clubId = ClubId(1),
        signedOn = MatchDay(0),
        expiresOn = MatchDay(19),
        wagePerMatchDay = 2,
        pricePaid = 40,
    )

    @Test
    fun `la scadenza si misura in giornate`() {
        assertTrue(!contract.isExpired(MatchDay(18)))
        assertTrue(contract.isExpired(MatchDay(19)))
        assertEquals(9, contract.matchDaysLeft(MatchDay(10)))
        assertEquals(0, contract.matchDaysLeft(MatchDay(25)))
    }

    @Test
    fun `expiresWithin avvisa in anticipo`() {
        assertTrue(contract.expiresWithin(MatchDay(17), 3))
        assertTrue(!contract.expiresWithin(MatchDay(10), 3))
    }

    @Test
    fun `il rinnovo costa una frazione del prezzo pagato`() {
        assertEquals(20, contract.renewalCost(0.5))
        assertEquals(40, contract.renewalCost(1.0))
    }

    @Test
    fun `il rinnovo costa comunque almeno un credito`() {
        assertEquals(1, contract.copy(pricePaid = 1).renewalCost(0.1))
    }

    @Test
    fun `un contratto che scade prima di iniziare viene rifiutato`() {
        assertFailsWith<IllegalArgumentException> {
            contract.copy(signedOn = MatchDay(10), expiresOn = MatchDay(5))
        }
    }
}

class LoanTest {

    private val loan = Loan(
        playerId = PlayerId(1),
        ownerClub = ClubId(1),
        borrowerClub = ClubId(2),
        startsOn = MatchDay(5),
        endsOn = MatchDay(12),
        feePerMatchDay = 3,
    )

    @Test
    fun `il prestito e attivo solo nella sua finestra`() {
        assertTrue(!loan.isActive(MatchDay(4)))
        assertTrue(loan.isActive(MatchDay(5)))
        assertTrue(loan.isActive(MatchDay(11)))
        assertTrue(!loan.isActive(MatchDay(12)))
        assertTrue(loan.isExpired(MatchDay(12)))
    }

    @Test
    fun `il club effettivo cambia durante il prestito`() {
        assertEquals(ClubId(1), loan.effectiveClub(MatchDay(4)))
        assertEquals(ClubId(2), loan.effectiveClub(MatchDay(8)))
        assertEquals(ClubId(1), loan.effectiveClub(MatchDay(20)))
    }

    @Test
    fun `il canone totale copre tutte le giornate`() {
        assertEquals(21, loan.totalFee())
    }

    @Test
    fun `un club non puo prestare a se stesso`() {
        assertFailsWith<IllegalArgumentException> { loan.copy(borrowerClub = ClubId(1)) }
    }
}

class StaffTest {

    private fun staff(role: StaffRole, stars: Int) =
        Staff(StaffId(1), "Luca", "Bianchi", "Italia", role, stars)

    @Test
    fun `un allenatore da cinque stelle fa crescere molto piu di uno da una`() {
        val scarso = staff(StaffRole.ALLENATORE, 1).growthMultiplier
        val top = staff(StaffRole.ALLENATORE, 5).growthMultiplier
        assertTrue(top > scarso * 2.5, "la differenza fra 1 e 5 stelle e' troppo piccola: $scarso vs $top")
    }

    @Test
    fun `la scala non e lineare e premia i top`() {
        val d12 = staff(StaffRole.ALLENATORE, 2).growthMultiplier - staff(StaffRole.ALLENATORE, 1).growthMultiplier
        val d45 = staff(StaffRole.ALLENATORE, 5).growthMultiplier - staff(StaffRole.ALLENATORE, 4).growthMultiplier
        assertTrue(d45 > d12, "il salto 4->5 deve valere piu' del salto 1->2")
    }

    @Test
    fun `ogni ruolo influenza solo il suo sistema`() {
        assertEquals(1.0, staff(StaffRole.PREPARATORE, 5).growthMultiplier)
        assertEquals(1.0, staff(StaffRole.ALLENATORE, 5).recoveryMultiplier)
        assertEquals(0.0, staff(StaffRole.ALLENATORE, 5).scoutingAccuracy)
    }

    @Test
    fun `nemmeno l'osservatore migliore da la certezza`() {
        assertTrue(
            staff(StaffRole.OSSERVATORE, 5).scoutingAccuracy < 1.0,
            "il rischio all'asta non deve mai sparire del tutto",
        )
    }

    @Test
    fun `le stelle fuori scala vengono rifiutate`() {
        assertFailsWith<IllegalArgumentException> { staff(StaffRole.ALLENATORE, 0) }
        assertFailsWith<IllegalArgumentException> { staff(StaffRole.ALLENATORE, 6) }
    }
}
