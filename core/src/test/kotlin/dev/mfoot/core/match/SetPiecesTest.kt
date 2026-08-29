package dev.mfoot.core.match

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.Trait
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Gli incarichi: capitano, rigorista, angoli, punizioni, calci lunghi.
 *
 * La regola che questi test difendono e' una sola: **un incarico deve muovere un numero.**
 * Un capitano che non cambia niente e un battitore d'angoli che non cambia niente sono
 * caselle da riempire per niente, ed e' esattamente cio' che c'era prima del 2026-08-24 —
 * `captain_id` esisteva nel database dal primo schema e non veniva letto da nessuno.
 */
class SetPiecesTest {

    private companion object {
        /** Vedi la nota sul test degli angoli: sotto le duemila partite non si misura. */
        const val CAMPIONE_ANGOLI = 2500
    }

    private fun giocatore(
        id: Long,
        position: Position = Position.CC,
        age: Int = 25,
        tiro: Int = 60,
        tecnica: Int = 60,
        passaggio: Int = 60,
        fisico: Int = 60,
        posizionamento: Int = 60,
        traits: Set<Trait> = emptySet(),
    ) = Player(
        id = PlayerId(id),
        firstName = "G$id",
        lastName = "Prova$id",
        nationality = "it",
        age = age,
        primaryPosition = position,
        attributes = Attributes.of(
            default = 55,
            Attr.TIRO to tiro,
            Attr.TECNICA to tecnica,
            Attr.PASSAGGIO to passaggio,
            Attr.FISICO to fisico,
            Attr.POSIZIONAMENTO to posizionamento,
        ),
        potentialMin = 60,
        potentialMax = 60,
        traits = traits,
    )

    private fun undici(): List<Player> = listOf(
        giocatore(1, Position.POR),
        giocatore(2, Position.TD), giocatore(3, Position.DC),
        giocatore(4, Position.DC), giocatore(5, Position.TS),
        giocatore(6, Position.MED), giocatore(7, Position.CC), giocatore(8, Position.CC),
        giocatore(9, Position.AD), giocatore(10, Position.ATT), giocatore(11, Position.AS),
    )

    // ------------------------------------------------------------------ i cinque campi

    @Test
    fun `ogni incarico ha il suo campo, e assign e idFor si corrispondono`() {
        var lineup = Lineup.of(Formation.F_4_3_3, undici())

        MatchDuty.entries.forEach { duty ->
            lineup = SetPieces.assign(lineup, duty, PlayerId(7))
            assertEquals(
                PlayerId(7), SetPieces.idFor(lineup, duty),
                "l'incarico ${duty.label} non torna indietro dal campo in cui e' stato scritto",
            )
        }

        // Cinque incarichi distinti: assegnarne uno non deve sovrascriverne un altro.
        MatchDuty.entries.forEach { duty ->
            assertEquals(PlayerId(7), SetPieces.idFor(lineup, duty))
        }
    }

    @Test
    fun `un incarico si puo' togliere`() {
        val lineup = SetPieces.assign(
            Lineup.of(Formation.F_4_3_3, undici()),
            MatchDuty.ANGOLI,
            PlayerId(9),
        )
        assertEquals(PlayerId(9), SetPieces.idFor(lineup, MatchDuty.ANGOLI))

        val senza = SetPieces.assign(lineup, MatchDuty.ANGOLI, null)
        assertEquals(null, SetPieces.idFor(senza, MatchDuty.ANGOLI))
    }

    // ------------------------------------------------- il designato, e chi lo sostituisce

    @Test
    fun `il designato conta solo se e' in campo`() {
        val panchinaro = giocatore(99, Position.ATT, tiro = 99, tecnica = 99)
        val lineup = Lineup.of(Formation.F_4_3_3, undici(), bench = listOf(panchinaro))
            .let { SetPieces.assign(it, MatchDuty.RIGORISTA, PlayerId(99)) }

        // E' il piu' bravo della rosa e siede in panchina: non calcia.
        assertEquals(null, SetPieces.designated(lineup, MatchDuty.RIGORISTA))

        val chiCalcia = SetPieces.taker(lineup, MatchDuty.RIGORISTA)
        assertNotNull(chiCalcia)
        assertTrue(
            lineup.contains(chiCalcia.id),
            "chi calcia il rigore deve essere in campo, invece e' ${chiCalcia.id}",
        )
    }

    @Test
    fun `senza designato calcia il piu' adatto, non il primo della lista`() {
        val squadra = undici().toMutableList()
        // Il numero 8 e' lo specialista da fermo: tiro e tecnica sopra tutti.
        squadra[7] = giocatore(8, Position.CC, tiro = 92, tecnica = 88)

        val lineup = Lineup.of(Formation.F_4_3_3, squadra)

        assertEquals(PlayerId(8), SetPieces.taker(lineup, MatchDuty.RIGORISTA)?.id)
        assertEquals(PlayerId(8), SetPieces.taker(lineup, MatchDuty.PUNIZIONI)?.id)
    }

    @Test
    fun `il designato batte il piu' adatto perche' la scelta del manager vince`() {
        val squadra = undici().toMutableList()
        squadra[7] = giocatore(8, Position.CC, tiro = 92, tecnica = 88)

        val lineup = SetPieces.assign(
            Lineup.of(Formation.F_4_3_3, squadra),
            MatchDuty.RIGORISTA,
            PlayerId(10),
        )

        assertEquals(
            PlayerId(10), SetPieces.taker(lineup, MatchDuty.RIGORISTA)?.id,
            "se il manager ha scelto, il motore non deve sapere di saperne di piu'",
        )
    }

    @Test
    fun `il portiere batte i calci lunghi ma non gli angoli`() {
        val lineup = Lineup.of(Formation.F_4_3_3, undici())

        val angoli = SetPieces.candidates(lineup, MatchDuty.ANGOLI)
        assertTrue(
            angoli.none { it.primaryPosition.isGoalkeeper },
            "il portiere non puo' battere un angolo: e' dall'altra parte del campo",
        )

        val lunghi = SetPieces.candidates(lineup, MatchDuty.LANCI_LUNGHI)
        assertTrue(
            lunghi.any { it.primaryPosition.isGoalkeeper },
            "il rinvio lungo e' il mestiere del portiere: deve poterlo fare",
        )
    }

    // ---------------------------------------------------------------- gli attributi giusti

    @Test
    fun `ogni incarico guarda gli attributi che dice di guardare`() {
        val crossatore = giocatore(20, passaggio = 90, tecnica = 85, tiro = 40, fisico = 50)
        val bomber = giocatore(21, passaggio = 45, tecnica = 60, tiro = 92, fisico = 60)
        val armadio = giocatore(22, passaggio = 70, tecnica = 40, tiro = 40, fisico = 92)

        assertEquals(
            crossatore.id,
            SetPieces.best(listOf(crossatore, bomber, armadio), MatchDuty.ANGOLI)?.id,
            "gli angoli li batte chi crossa, non chi tira",
        )
        assertEquals(
            bomber.id,
            SetPieces.best(listOf(crossatore, bomber, armadio), MatchDuty.PUNIZIONI)?.id,
            "la punizione la calcia chi tira",
        )
        assertEquals(
            armadio.id,
            SetPieces.best(listOf(crossatore, bomber, armadio), MatchDuty.LANCI_LUNGHI)?.id,
            "il lancio lungo lo fa chi ha il fisico per farlo",
        )
    }

    @Test
    fun `il rigorista nato viene preferito a parita' di tiro`() {
        val normale = giocatore(30, tiro = 80, tecnica = 70)
        val freddo = giocatore(31, tiro = 80, tecnica = 70, traits = setOf(Trait.RIGORISTA))

        assertEquals(
            freddo.id,
            SetPieces.best(listOf(normale, freddo), MatchDuty.RIGORISTA)?.id,
        )
    }

    @Test
    fun `la fascia va all'esperienza, non al piu' forte`() {
        val fenomeno = giocatore(40, age = 19, tiro = 90, tecnica = 90, passaggio = 90, fisico = 90)
        val veterano = giocatore(41, age = 32, tiro = 62, tecnica = 62, passaggio = 62, fisico = 62)

        assertEquals(
            veterano.id,
            SetPieces.best(listOf(fenomeno, veterano), MatchDuty.CAPITANO)?.id,
            "un ventenne piu' forte non guida meglio di un trentaduenne: la fascia non e' l'overall",
        )
    }

    @Test
    fun `il tratto Leader pesa sulla fascia`() {
        val chiunque = giocatore(50, age = 28)
        val leader = giocatore(51, age = 28, traits = setOf(Trait.LEADER))

        assertTrue(
            SetPieces.aptitude(leader, MatchDuty.CAPITANO) >
                SetPieces.aptitude(chiunque, MatchDuty.CAPITANO),
        )
    }

    @Test
    fun `la leadership sta fra zero e uno e cresce col capitano`() {
        val squadra = undici().toMutableList()
        val conGiovane = Lineup.of(Formation.F_4_3_3, squadra)
            .let { SetPieces.assign(it, MatchDuty.CAPITANO, PlayerId(7)) }

        squadra[6] = giocatore(7, Position.CC, age = 33, traits = setOf(Trait.LEADER))
        val conVeterano = Lineup.of(Formation.F_4_3_3, squadra)
            .let { SetPieces.assign(it, MatchDuty.CAPITANO, PlayerId(7)) }

        val debole = SetPieces.leadership(conGiovane)
        val forte = SetPieces.leadership(conVeterano)

        assertTrue(debole in 0.0..1.0 && forte in 0.0..1.0, "leadership fuori scala: $debole / $forte")
        assertTrue(forte > debole, "un capitano vero deve valere piu' di uno qualsiasi")
    }

    @Test
    fun `a parita' esatta la scelta e' sempre la stessa`() {
        val gemelli = listOf(giocatore(60), giocatore(61))

        // Due simulazioni dello stesso mondo devono mettere sul dischetto lo stesso uomo,
        // altrimenti la partita non e' piu' riproducibile dal seed.
        repeat(5) {
            assertEquals(
                PlayerId(61),
                SetPieces.best(gemelli.shuffled(), MatchDuty.RIGORISTA)?.id,
            )
        }
    }

    // ------------------------------------------------------------- l'effetto sul motore

    /**
     * Il test che vale per tutti: **un incarico deve muovere un numero.**
     *
     * Due squadre identiche in tutto tranne che per chi batte gli angoli. Se il risultato
     * non cambia, l'incarico e' decorazione — che e' precisamente cio' che l'angolo era
     * fino al 2026-08-24, quando veniva emesso e la palla ripartiva.
     *
     * ## Perche' il campione e' cosi' grande
     *
     * Perche' l'effetto e' vero ma piccolo: gli angoli valgono fra i quattro e i sei
     * centesimi di gol a partita. A quattrocento partite spariva nel rumore, e il test
     * passava o falliva a seconda di quale seme capitava — il tipo di prova che da'
     * sicurezza senza misurare niente. Misurato: +0,057 a 1500 partite, +0,041 a 3000,
     * sempre nello stesso verso.
     */
    @Test
    fun `chi batte gli angoli cambia quanti gol si fanno`() {
        val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
        val world = WorldGenerator.generate(config)

        val conSpecialista = BalanceHarness.run(world, config, 75, 75, CAMPIONE_ANGOLI)

        // Stessa lega, angoli che non arrivano mai: e' il motore di prima del 2026-08-24.
        val senzaAngoli = config.copy(
            engine = config.engine.copy(cornerConversionMin = 0.0, cornerConversionMax = 0.0),
        )
        val conAngoliInutili = BalanceHarness.run(world, senzaAngoli, 75, 75, CAMPIONE_ANGOLI)

        assertTrue(
            conSpecialista.goalsPerMatch > conAngoliInutili.goalsPerMatch,
            "gli angoli non producono niente: ${conSpecialista.goalsPerMatch} contro " +
                "${conAngoliInutili.goalsPerMatch} gol a partita",
        )
    }

    /**
     * ...e non deve muoverlo troppo.
     *
     * I numeri di bilanciamento del progetto sono misurati su migliaia di partite, non
     * stimati: 2,77 gol a partita fra squadre pari. Angoli e punizioni aggiungono due modi
     * di segnare che prima non esistevano, e devono starci **dentro** quella fascia — se la
     * sfondassero, il gioco misurato per due mesi diventerebbe un altro gioco.
     */
    @Test
    fun `angoli e punizioni non sfondano il bilanciamento misurato`() {
        val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
        val world = WorldGenerator.generate(config)

        val report = BalanceHarness.run(world, config, 75, 75, 1500)

        assertTrue(
            report.goalsPerMatch in 2.5..3.0,
            "gol a partita fuori dalla fascia di riferimento: ${report.goalsPerMatch}",
        )
        assertTrue(
            report.homeWinRate > report.awayWinRate,
            "il vantaggio del campo e' sparito: casa ${report.homeWinRate}, ospite ${report.awayWinRate}",
        )
    }

    /**
     * Il capitano si sente **solo quando si e' sotto**.
     *
     * A squadre pari e per centinaia di partite, spegnere la resistenza del capitano non
     * deve cambiare quanti gol si fanno: la fascia non e' un bonus di forza, e' una
     * resistenza al crollo. Se cambiasse anche in parita', sarebbe un moltiplicatore
     * mascherato — e allora tanto varrebbe alzare l'overall.
     */
    @Test
    fun `il capitano non regala forza a chi sta vincendo`() {
        val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
        val world = WorldGenerator.generate(config)

        val conCapitano = BalanceHarness.run(world, config, 75, 75, 800)
        val senzaCapitano = BalanceHarness.run(
            world,
            config.copy(engine = config.engine.copy(captainResilience = 0.0)),
            75, 75, 800,
        )

        val scarto = StrictMath.abs(conCapitano.goalsPerMatch - senzaCapitano.goalsPerMatch)
        assertTrue(
            scarto < 0.2,
            "il capitano sposta i gol anche a partita in equilibrio ($scarto): " +
                "non e' piu' una resistenza, e' forza in piu'",
        )
    }
}
