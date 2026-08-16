package dev.mfoot.core.world

import dev.mfoot.core.config.CustomPlayerConfig
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomPlayerBuilderTest {

    private val config = CustomPlayerConfig()

    private fun draft(position: Position = Position.ATT) = CustomPlayerBuilder.Draft(
        firstName = "Rocco",
        lastName = "Ferrero",
        nationality = "Italia",
        position = position,
        age = 18,
    )

    @Test
    fun `un progetto appena aperto vale esattamente l'overall di base`() {
        Position.entries.forEach { position ->
            assertEquals(
                config.baseOverall,
                CustomPlayerBuilder.overallOf(draft(position), config),
                "$position non parte da ${config.baseOverall}",
            )
        }
    }

    @Test
    fun `un progetto appena aperto non ha speso niente`() {
        assertEquals(0, CustomPlayerBuilder.totalCost(draft(), config))
        assertEquals(config.skillBudget, CustomPlayerBuilder.remaining(draft(), config))
    }

    /**
     * Un attaccante non deve saper parare e un portiere non deve saper tirare.
     *
     * Senza questa separazione, schierare il proprio custom fuori ruolo diventerebbe una
     * furbizia — un portiere costruito con attributi da movimento — invece che la scelta
     * disperata che deve restare.
     */
    @Test
    fun `gli attributi del mestiere sbagliato restano bassi`() {
        val attaccante = CustomPlayerBuilder.baseAttributes(Position.ATT, config)
        Attr.goalkeeper.forEach {
            assertEquals(config.wrongSideBase, attaccante[it], "un attaccante parte con $it alto")
        }

        val portiere = CustomPlayerBuilder.baseAttributes(Position.POR, config)
        assertEquals(config.wrongSideBase, portiere[Attr.TIRO])
        assertEquals(config.baseOverall, portiere[Attr.PARATA])
    }

    @Test
    fun `alzare un attributo costa di piu' man mano che sale`() {
        val costi = listOf(60, 68, 72, 82, 90).map { CustomPlayerBuilder.costOfPointAt(it, config) }
        costi.zipWithNext().forEach { (basso, alto) ->
            assertTrue(alto >= basso, "il costo non cresce con il livello: $costi")
        }
        assertTrue(costi.last() > costi.first(), "un punto a 90 costa quanto uno a 60")
    }

    @Test
    fun `il budget non si puo' sforare`() {
        var d = draft()
        // Si insiste ben oltre il budget: la funzione deve semplicemente smettere di dare.
        repeat(400) { d = CustomPlayerBuilder.raise(d, Attr.TIRO, config) }
        repeat(400) { d = CustomPlayerBuilder.raise(d, Attr.VELOCITA, config) }

        assertTrue(
            CustomPlayerBuilder.totalCost(d, config) <= config.skillBudget,
            "speso ${CustomPlayerBuilder.totalCost(d, config)} su ${config.skillBudget}",
        )
        assertTrue(CustomPlayerBuilder.remaining(d, config) >= 0)
    }

    @Test
    fun `le stelle costano quanto dice la configurazione`() {
        val d = draft().copy(weakFoot = 5, skillStars = 5)
        assertEquals(config.maxStarSpend, CustomPlayerBuilder.costOfStars(d, config))
        assertEquals(80, CustomPlayerBuilder.costOfStars(d, config))
    }

    /**
     * Spendere tutto in stelle deve lasciare pochissimo per gli attributi.
     *
     * È il compromesso centrale della schermata: un giocatore ambidestro e tecnicissimo
     * resta scarso, e uno forte non sa calciare col piede debole. Se entrambe le cose
     * fossero possibili, non ci sarebbe nessuna scelta da fare.
     */
    @Test
    fun `chi compra tutte le stelle resta quasi senza punti`() {
        val d = draft().copy(weakFoot = 5, skillStars = 5)
        val rimasti = CustomPlayerBuilder.remaining(d, config)

        assertEquals(config.skillBudget - config.maxStarSpend, rimasti)
        assertTrue(rimasti < config.skillBudget / 4, "restano ancora $rimasti punti su ${config.skillBudget}")
    }

    /**
     * Il tetto misurato, non ipotizzato.
     *
     * Spende avidamente sull'attributo che pesa di piu' nel ruolo, che e' la strategia
     * migliore possibile. Il risultato deve restare **lontano** dai fuoriclasse del mondo
     * generato, che stanno a 87-93: il custom e' un progetto, non un acquisto.
     */
    @Test
    fun `nemmeno la costruzione piu' spregiudicata avvicina i fuoriclasse`() {
        val migliori = Position.entries.associateWith { position ->
            var d = draft(position)
            // Sempre sull'attributo con il rapporto peso/prezzo migliore: la scelta
            // ottimale, non una a caso.
            while (true) {
                val scelta = position.ovrWeights.keys
                    .filter { CustomPlayerBuilder.canRaise(d, it, config) }
                    .maxByOrNull { attr ->
                        val costo = CustomPlayerBuilder.costOfNextPoint(d, attr, config) ?: 99
                        (position.ovrWeights[attr] ?: 0.0) / costo
                    } ?: break
                d = CustomPlayerBuilder.raise(d, scelta, config)
            }
            CustomPlayerBuilder.overallOf(d, config)
        }

        migliori.forEach { (position, overall) ->
            assertTrue(
                overall <= 79,
                "in $position il custom esce a $overall: e' gia' un titolare di alta classifica",
            )
            assertTrue(
                overall > config.baseOverall,
                "in $position spendere tutto il budget non migliora niente",
            )
        }
    }

    /**
     * Il portiere rende di piu' a parità di budget, ed è bene saperlo.
     *
     * Il suo overall dipende da quattro attributi invece che da sei, quindi ogni punto
     * speso pesa di più. Non è un difetto del builder: è come sono fatti i ruoli. Il test
     * fissa il numero perché resti visibile — se un giorno i portieri custom diventassero
     * la scelta obbligata, si saprà da dove ripartire.
     */
    @Test
    fun `il portiere resta il ruolo piu' redditizio, entro un margine noto`() {
        fun migliore(position: Position): Int {
            var d = draft(position)
            while (true) {
                val scelta = position.ovrWeights.keys
                    .filter { CustomPlayerBuilder.canRaise(d, it, config) }
                    .maxByOrNull { attr ->
                        val costo = CustomPlayerBuilder.costOfNextPoint(d, attr, config) ?: 99
                        (position.ovrWeights[attr] ?: 0.0) / costo
                    } ?: break
                d = CustomPlayerBuilder.raise(d, scelta, config)
            }
            return CustomPlayerBuilder.overallOf(d, config)
        }

        val portiere = migliore(Position.POR)
        val migliorMovimento = Position.outfield.maxOf(::migliore)

        assertTrue(
            portiere - migliorMovimento <= 4,
            "il portiere custom esce a $portiere contro $migliorMovimento: " +
                "e' diventato la scelta obbligata",
        )
    }

    @Test
    fun `un progetto senza nome non passa`() {
        val d = draft().copy(firstName = "", lastName = "")
        assertFalse(CustomPlayerBuilder.isValid(d, config))
        assertTrue(CustomPlayerBuilder.problems(d, config).any { it.contains("nome") })
    }

    @Test
    fun `un progetto fuori budget viene rifiutato anche se costruito a mano`() {
        // Il caso che conta: non un errore dell'interfaccia, ma qualcuno che compone la
        // richiesta a mano per presentarsi con un fuoriclasse il primo giorno.
        val imbroglio = draft().copy(
            increments = Position.ATT.ovrWeights.keys.associateWith { 30 },
            weakFoot = 5,
            skillStars = 5,
        )

        assertFalse(CustomPlayerBuilder.isValid(imbroglio, config))
        assertFailsWith<IllegalArgumentException> {
            CustomPlayerBuilder.build(imbroglio, PlayerId(1), config)
        }
    }

    @Test
    fun `l'eta' deve stare nella finestra decisa dall'admin`() {
        assertFalse(CustomPlayerBuilder.isValid(draft().copy(age = 30), config))
        assertFalse(CustomPlayerBuilder.isValid(draft().copy(age = 10), config))
        assertTrue(CustomPlayerBuilder.isValid(draft().copy(age = config.maxAge), config))
    }

    @Test
    fun `cambiare ruolo azzera i punti spesi`() {
        var d = draft(Position.ATT)
        repeat(10) { d = CustomPlayerBuilder.raise(d, Attr.TIRO, config) }
        assertTrue(CustomPlayerBuilder.totalCost(d, config) > 0)

        val cambiato = CustomPlayerBuilder.withPosition(d, Position.DC)
        assertEquals(0, CustomPlayerBuilder.totalCost(cambiato, config))
        assertEquals(config.baseOverall, CustomPlayerBuilder.overallOf(cambiato, config))
    }

    @Test
    fun `il giocatore costruito e' marcato come custom e ha un tetto piu' alto`() {
        var d = draft()
        repeat(20) { d = CustomPlayerBuilder.raise(d, Attr.TIRO, config) }
        val player = CustomPlayerBuilder.build(d, PlayerId(7), config)

        assertTrue(player.isCustom)
        assertEquals(player.overall + config.potentialBonus, player.potentialMax)
        assertTrue(player.potentialMax <= config.potentialCeiling)
        assertEquals("Rocco Ferrero", player.fullName)
    }

    @Test
    fun `un attributo non supera mai il massimo della scala`() {
        val generoso = CustomPlayerConfig(skillBudget = 10_000)
        var d = draft()
        repeat(500) { d = CustomPlayerBuilder.raise(d, Attr.TIRO, generoso) }

        assertEquals(Attributes.MAX, CustomPlayerBuilder.attributesOf(d, generoso)[Attr.TIRO])
        assertTrue(CustomPlayerBuilder.isValid(d, generoso))
    }
}
