package dev.mfoot.core.match

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Trait
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I tre tratti che dentro i novanta minuti non pesavano niente.
 *
 * Ogni prova qui misura una promessa che il gioco faceva e non manteneva. Non servono a
 * verificare che il codice giri: servono a verificare che la parola scritta nella scheda
 * del giocatore corrisponda a qualcosa che succede in campo.
 */
class CarattereTest {

    private val config: LeagueConfig = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
        .let { it.copy(engine = it.engine.copy(duelliAttivi = true)) }
    private val world = WorldGenerator.generate(config)
    private val engine = config.engine

    private fun giocatore(traits: Set<Trait>): Player =
        world.players.first().copy(traits = traits)

    // ------------------------------------------------------------------ la giornata

    /**
     * *«Un giorno domina, quello dopo sparisce»* — la descrizione di `INCOSTANTE`. Prima
     * di questa funzione quel giocatore faceva la stessa identica partita ogni volta.
     */
    @Test
    fun `l'incostante ha giornate molto piu' diverse fra loro`() {
        val normale = giocatore(emptySet())
        val incostante = giocatore(setOf(Trait.INCOSTANTE))

        val scartoNormale = scartoDelleGiornate(normale)
        val scartoIncostante = scartoDelleGiornate(incostante)

        assertTrue(
            scartoIncostante > scartoNormale * 1.6,
            "l'incostante oscilla $scartoIncostante contro $scartoNormale di uno normale: " +
                "il tratto non si sente",
        )
    }

    @Test
    fun `chi non e' incostante oscilla poco`() {
        val scarto = scartoDelleGiornate(giocatore(emptySet()))
        assertTrue(
            scarto in 1.5..3.5,
            "un giocatore normale oscilla di $scarto punti a partita: " +
                "o non si sente per niente, o decide la partita",
        )
    }

    /**
     * La ragione per cui la giornata nasce dal seme e non dal flusso: il secondo tempo
     * ricostruisce il primo risimulandolo, e un giocatore in palla al 44' deve essere in
     * palla al 46'.
     */
    @Test
    fun `la stessa partita da' sempre la stessa giornata`() {
        val player = giocatore(setOf(Trait.INCOSTANTE))
        assertEquals(
            Carattere.giornata(player, 12345L, engine),
            Carattere.giornata(player, 12345L, engine),
        )
    }

    @Test
    fun `partite diverse danno giornate diverse`() {
        val player = giocatore(emptySet())
        assertTrue(
            Carattere.giornata(player, 1L, engine) != Carattere.giornata(player, 2L, engine),
            "la giornata non dipende dalla partita: sarebbe una seconda forma, non una giornata",
        )
    }

    @Test
    fun `nessuna giornata ribalta un giocatore`() {
        val incostante = giocatore(setOf(Trait.INCOSTANTE))
        val estreme = (1L..4000L).map { Carattere.giornata(incostante, it, engine) }
        assertTrue(
            estreme.all { StrictMath.abs(it) <= 12.0 },
            "una giornata vale piu' di dodici punti: cambierebbe chi e' quel giocatore",
        )
    }

    private fun scartoDelleGiornate(player: Player): Double {
        val giornate = (1L..3000L).map { Carattere.giornata(player, it, engine) }
        val media = giornate.average()
        return StrictMath.sqrt(giornate.sumOf { (it - media) * (it - media) } / giornate.size)
    }

    // ---------------------------------------------------------------- chi trascina

    private fun squadra(traits: List<Set<Trait>>): Lineup {
        val setup = TestSquads.build(world, 1, "Prova", 75)
        var lineup = setup.lineup
        traits.forEachIndexed { index, t ->
            val slot = lineup.outfield[index]
            lineup = lineup.withUpdatedPlayer(slot.player.copy(traits = t))
        }
        return lineup
    }

    @Test
    fun `chi e' avanti o in parita' non spinge`() {
        val conLeader = squadra(List(3) { setOf(Trait.LEADER) })
        assertEquals(
            0.0,
            Carattere.spintaDiRimonta(conLeader, scarto = 0, minute = 85, engine = engine),
        )
        assertEquals(
            0.0,
            Carattere.spintaDiRimonta(conLeader, scarto = 2, minute = 85, engine = engine),
        )
    }

    @Test
    fun `prima del finale non spinge nessuno`() {
        val conLeader = squadra(List(3) { setOf(Trait.LEADER) })
        assertEquals(
            0.0,
            Carattere.spintaDiRimonta(conLeader, scarto = -1, minute = 60, engine = engine),
        )
    }

    /**
     * *«Trascina la squadra»*. Prima l'unica spinta esistente passava dal capitano, quindi
     * un leader senza fascia non trascinava nessuno e la parola non voleva dire niente.
     */
    @Test
    fun `sotto nel finale, chi ha leader spinge e chi non ne ha no`() {
        val senza = squadra(List(3) { emptySet() })
        val con = squadra(List(3) { setOf(Trait.LEADER) })

        val spintaSenza = Carattere.spintaDiRimonta(senza, scarto = -1, minute = 85, engine = engine)
        val spintaCon = Carattere.spintaDiRimonta(con, scarto = -1, minute = 85, engine = engine)

        assertEquals(0.0, spintaSenza, "una squadra senza trascinatori sta spingendo lo stesso")
        assertTrue(spintaCon > 1.0, "tre leader in campo valgono solo $spintaCon")
    }

    @Test
    fun `la spinta ha un tetto, o basterebbe riempire la squadra di leader`() {
        val undiciLeader = squadra(List(10) { setOf(Trait.LEADER, Trait.UOMO_SPOGLIATOIO) })
        val spinta = Carattere.spintaDiRimonta(
            undiciLeader, scarto = -1, minute = 85, engine = engine,
        )
        assertTrue(
            spinta <= engine.spintaLeaderMassima,
            "dieci trascinatori valgono $spinta: sopra il tetto di ${engine.spintaLeaderMassima}",
        )
    }

    // ---------------------------------------------------------------- la testa calda

    /**
     * *«Colleziona cartellini»*. Prima ne prendeva esattamente quanti chiunque altro: il
     * tratto, dentro la partita, non muoveva un solo numero.
     *
     * Si misura giocando, perche' e' l'unico dei tre che vive dove i falli si assegnano e
     * non in una funzione a parte.
     */
    @Test
    fun `una testa calda prende piu' cartellini di un giocatore identico senza il tratto`() {
        val pulita = TestSquads.build(world, 1, "Casa", 75)
        val avversaria = TestSquads.build(
            world, 2, "Ospite", 75, exclude = TestSquads.playersOf(pulita),
        )

        // Lo stesso identico giocatore, nella stessa identica squadra: cambia solo il tratto.
        val bersaglio = pulita.lineup.outfield.first { !it.position.isGoalkeeper }.player
        val calda = pulita.copy(
            lineup = pulita.lineup.withUpdatedPlayer(
                bersaglio.copy(traits = bersaglio.traits + Trait.TESTA_CALDA),
            ),
        )

        val senza = cartelliniDi(bersaglio, pulita, avversaria)
        val con = cartelliniDi(bersaglio, calda, avversaria)

        assertTrue(
            con > senza,
            "col tratto prende $con cartellini, senza $senza: il tratto non si sente",
        )
        assertTrue(
            con > senza * 1.5,
            "col tratto prende $con cartellini contro $senza: troppo poco per accorgersene",
        )
    }

    private fun cartelliniDi(player: Player, home: TeamSetup, away: TeamSetup): Int {
        var totale = 0
        repeat(600) { index ->
            val result = MatchEngine.simulate(
                home = home, away = away, config = config, seed = 7_000_000L + index,
            )
            result.stats[player.id]?.let { totale += it.yellowCards + it.redCards }
        }
        return totale
    }
}
