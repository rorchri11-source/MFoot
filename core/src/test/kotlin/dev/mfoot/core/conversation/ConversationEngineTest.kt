package dev.mfoot.core.conversation

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.Trait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationEngineTest {

    private val rules = ConfigPresets.sprint().rules
    private val today = MatchDay(5)

    private fun player(morale: Int = 40, traits: Set<Trait> = emptySet()) = Player(
        id = PlayerId(1),
        firstName = "Marco", lastName = "Ferrero", nationality = "Italia",
        age = 25,
        primaryPosition = Position.ATT,
        attributes = Attributes.uniform(75),
        potentialMin = 78, potentialMax = 82,
        morale = morale,
        traits = traits,
    )

    private fun optionWithTone(topic: ConversationTopic, tone: ConversationTone) =
        ConversationEngine.optionsFor(topic).first { it.tone == tone }

    // -------------------------------------------------------------------- opzioni

    @Test
    fun `ogni argomento offre almeno tre opzioni`() {
        ConversationTopic.entries.forEach { topic ->
            val opzioni = ConversationEngine.optionsFor(topic)
            assertTrue(opzioni.size >= 3, "${topic.label} ha solo ${opzioni.size} opzioni")
            assertTrue(opzioni.all { it.text.isNotBlank() })
        }
    }

    @Test
    fun `chi vuole andarsene puo essere trattenuto con una promessa`() {
        val opzioni = ConversationEngine.optionsFor(ConversationTopic.RICHIESTA_CESSIONE)
        assertTrue(opzioni.any { it.createsPromise != null })
    }

    // --------------------------------------------------------------------- esiti

    @Test
    fun `incoraggiare alza il morale`() {
        val outcome = ConversationEngine.resolve(
            player(), ConversationTopic.MORALE_BASSO,
            optionWithTone(ConversationTopic.MORALE_BASSO, ConversationTone.INCORAGGIA),
            today, rules,
        )
        assertTrue(outcome.moraleDelta > 0, "delta ${outcome.moraleDelta}")
        assertTrue(outcome.reply.isNotBlank())
    }

    @Test
    fun `rimproverare abbassa il morale`() {
        val outcome = ConversationEngine.resolve(
            player(), ConversationTopic.PRESTAZIONI_SCARSE,
            optionWithTone(ConversationTopic.PRESTAZIONI_SCARSE, ConversationTone.RIMPROVERA),
            today, rules,
        )
        assertTrue(outcome.moraleDelta < 0)
    }

    /**
     * Il cuore del sistema: se tutti reagissero allo stesso modo, tanto varrebbe un
     * pulsante "alza morale" e i tratti sarebbero decorazione.
     */
    @Test
    fun `il testa calda reagisce molto peggio al rimprovero`() {
        val option = optionWithTone(ConversationTopic.PRESTAZIONI_SCARSE, ConversationTone.RIMPROVERA)

        val calmo = ConversationEngine.resolve(
            player(), ConversationTopic.PRESTAZIONI_SCARSE, option, today, rules,
        ).moraleDelta
        val calda = ConversationEngine.resolve(
            player(traits = setOf(Trait.TESTA_CALDA)),
            ConversationTopic.PRESTAZIONI_SCARSE, option, today, rules,
        ).moraleDelta

        assertTrue(calda < calmo, "normale $calmo, testa calda $calda")
    }

    @Test
    fun `l'uomo spogliatoio accetta la panchina se gliela spieghi`() {
        val option = optionWithTone(ConversationTopic.POCO_MINUTAGGIO, ConversationTone.SPIEGA)

        val normale = ConversationEngine.resolve(
            player(), ConversationTopic.POCO_MINUTAGGIO, option, today, rules,
        ).moraleDelta
        val spogliatoio = ConversationEngine.resolve(
            player(traits = setOf(Trait.UOMO_SPOGLIATOIO)),
            ConversationTopic.POCO_MINUTAGGIO, option, today, rules,
        ).moraleDelta

        assertTrue(spogliatoio > normale, "normale $normale, uomo spogliatoio $spogliatoio")
    }

    @Test
    fun `all'ambizioso le pacche sulle spalle non bastano`() {
        val incoraggia = optionWithTone(ConversationTopic.MORALE_BASSO, ConversationTone.INCORAGGIA)
        val prometti = ConversationEngine.optionsFor(ConversationTopic.MORALE_BASSO)
            .first { it.createsPromise != null }

        val ambizioso = player(traits = setOf(Trait.AMBIZIOSO))
        val conPacca = ConversationEngine.resolve(
            ambizioso, ConversationTopic.MORALE_BASSO, incoraggia, today, rules,
        ).moraleDelta
        val conPromessa = ConversationEngine.resolve(
            ambizioso, ConversationTopic.MORALE_BASSO, prometti, today, rules,
        ).moraleDelta

        assertTrue(conPromessa > conPacca * 2, "pacca $conPacca, promessa $conPromessa")
    }

    @Test
    fun `il testa calda risponde bene alla sfida`() {
        val sfida = optionWithTone(ConversationTopic.PRESTAZIONI_SCARSE, ConversationTone.SFIDA)
        val rimprovero = optionWithTone(ConversationTopic.PRESTAZIONI_SCARSE, ConversationTone.RIMPROVERA)
        val p = player(traits = setOf(Trait.TESTA_CALDA))

        assertTrue(
            ConversationEngine.resolve(p, ConversationTopic.PRESTAZIONI_SCARSE, sfida, today, rules).moraleDelta >
                ConversationEngine.resolve(p, ConversationTopic.PRESTAZIONI_SCARSE, rimprovero, today, rules).moraleDelta,
        )
    }

    @Test
    fun `il morale resta nella scala anche dopo un disastro`() {
        val outcome = ConversationEngine.resolve(
            player(morale = 3, traits = setOf(Trait.TESTA_CALDA)),
            ConversationTopic.PRESTAZIONI_SCARSE,
            optionWithTone(ConversationTopic.PRESTAZIONI_SCARSE, ConversationTone.RIMPROVERA),
            today, rules,
        )
        assertTrue(outcome.player.morale in 0..100)
    }

    @Test
    fun `con le conversazioni disattivate non succede niente`() {
        val spente = rules.copy(conversationsEnabled = false)
        val outcome = ConversationEngine.resolve(
            player(), ConversationTopic.MORALE_BASSO,
            optionWithTone(ConversationTopic.MORALE_BASSO, ConversationTone.INCORAGGIA),
            today, spente,
        )
        assertEquals(0, outcome.moraleDelta)
    }

    // ------------------------------------------------------------------ promesse

    @Test
    fun `una promessa nasce con scadenza e obiettivo`() {
        val prometti = ConversationEngine.optionsFor(ConversationTopic.MORALE_BASSO)
            .first { it.createsPromise != null }
        val outcome = ConversationEngine.resolve(
            player(), ConversationTopic.MORALE_BASSO, prometti, today, rules,
        )

        val promessa = outcome.promise
        assertNotNull(promessa)
        assertTrue(promessa.deadline > today)
        assertTrue(promessa.target > 0)
        assertEquals(0, promessa.progress)
    }

    @Test
    fun `le opzioni senza promessa non creano debiti`() {
        val outcome = ConversationEngine.resolve(
            player(), ConversationTopic.MORALE_BASSO,
            optionWithTone(ConversationTopic.MORALE_BASSO, ConversationTone.INCORAGGIA),
            today, rules,
        )
        assertNull(outcome.promise)
    }

    @Test
    fun `schierare titolare fa progredire la promessa`() {
        val promessa = Promise(
            PlayerId(1), PromiseType.TITOLARE_PER_PARTITE,
            madeOn = today, deadline = today + 6, target = 3,
        )
        val dopoUna = ConversationEngine.recordMatch(promessa, wasStarter = true)
        val dopoPanchina = ConversationEngine.recordMatch(dopoUna, wasStarter = false)

        assertEquals(1, dopoUna.progress)
        assertEquals(1, dopoPanchina.progress, "la panchina non deve far progredire la promessa")
    }

    @Test
    fun `la promessa si chiude come mantenuta al raggiungimento`() {
        var promessa = Promise(
            PlayerId(1), PromiseType.TITOLARE_PER_PARTITE,
            madeOn = today, deadline = today + 6, target = 3,
        )
        repeat(3) { promessa = ConversationEngine.recordMatch(promessa, wasStarter = true) }

        assertEquals(PromiseStatus.MANTENUTA, ConversationEngine.status(promessa, today + 4))
    }

    @Test
    fun `la promessa scaduta e non raggiunta e tradita`() {
        val promessa = Promise(
            PlayerId(1), PromiseType.TITOLARE_PER_PARTITE,
            madeOn = today, deadline = today + 6, target = 3,
        )
        assertEquals(PromiseStatus.IN_CORSO, ConversationEngine.status(promessa, today + 3))
        assertEquals(PromiseStatus.TRADITA, ConversationEngine.status(promessa, today + 6))
    }

    /**
     * Tradire deve costare piu' del doppio di quanto mantenere faccia guadagnare,
     * altrimenti promettere diventa un pulsante gratuito per alzare il morale.
     */
    @Test
    fun `tradire una promessa costa molto piu di quanto mantenerla guadagni`() {
        val p = player(morale = 60)
        val mantenuta = ConversationEngine.closePromise(p, PromiseStatus.MANTENUTA).moraleDelta
        val tradita = ConversationEngine.closePromise(p, PromiseStatus.TRADITA).moraleDelta

        assertTrue(mantenuta > 0)
        assertTrue(tradita < 0)
        assertTrue(
            StrictMath.abs(tradita) > mantenuta * 2,
            "mantenuta +$mantenuta, tradita $tradita: promettere sarebbe quasi gratis",
        )
    }

    @Test
    fun `una promessa in corso non muove niente`() {
        val outcome = ConversationEngine.closePromise(player(), PromiseStatus.IN_CORSO)
        assertEquals(0, outcome.moraleDelta)
    }

    @Test
    fun `solo una promessa convince chi vuole andarsene a restare`() {
        val prometti = ConversationEngine.optionsFor(ConversationTopic.RICHIESTA_CESSIONE)
            .first { it.createsPromise != null }
        val rimprovero = optionWithTone(ConversationTopic.RICHIESTA_CESSIONE, ConversationTone.RIMPROVERA)

        val conPromessa = ConversationEngine.resolve(
            player(morale = 20), ConversationTopic.RICHIESTA_CESSIONE, prometti, today, rules,
        )
        val conRimprovero = ConversationEngine.resolve(
            player(morale = 20), ConversationTopic.RICHIESTA_CESSIONE, rimprovero, today, rules,
        )

        assertTrue(conPromessa.transferRequestWithdrawn)
        assertTrue(!conRimprovero.transferRequestWithdrawn)
    }

    @Test
    fun `la promessa si descrive in modo leggibile`() {
        val promessa = Promise(
            PlayerId(1), PromiseType.TITOLARE_PER_PARTITE,
            madeOn = today, deadline = today + 6, target = 3, progress = 1,
        )
        val testo = promessa.describe()
        assertTrue(testo.contains("3"))
        assertTrue(testo.contains("1"))
    }
}
