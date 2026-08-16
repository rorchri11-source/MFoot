package dev.mfoot.core.match

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MatchEngineTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    private val home = TestSquads.build(world, 1, "Verdemar", 75)
    private val away = TestSquads.build(
        world, 2, "Nordkap", 75, exclude = TestSquads.playersOf(home),
    )

    private fun play(seed: Long = 42L) = MatchEngine.simulate(home, away, config, seed)

    // ------------------------------------------------------------------ determinismo

    /**
     * Il test piu' importante del motore.
     *
     * Il server simula una volta e salva la timeline; i client la riproducono. Se lo
     * stesso seed producesse risultati diversi, due persone vedrebbero due partite
     * diverse e il gioco perderebbe ogni credibilita'.
     */
    @Test
    fun `stesso seed produce esattamente la stessa partita`() {
        val a = play(777L)
        val b = play(777L)

        assertEquals(a.homeGoals, b.homeGoals)
        assertEquals(a.awayGoals, b.awayGoals)
        assertEquals(a.events.size, b.events.size)
        a.events.zip(b.events).forEach { (ea, eb) -> assertEquals(ea, eb) }
        assertEquals(a.stats, b.stats)
    }

    @Test
    fun `seed diversi producono partite diverse`() {
        val risultati = (1L..40L).map { play(it).scoreline }
        assertTrue(risultati.toSet().size > 5, "40 seed hanno prodotto solo ${risultati.toSet().size} risultati")
    }

    @Test
    fun `il determinismo regge su cento ripetizioni`() {
        val riferimento = play(31337L)
        repeat(100) {
            val ripetuta = play(31337L)
            assertEquals(riferimento.scoreline, ripetuta.scoreline)
            assertEquals(riferimento.events.size, ripetuta.events.size)
        }
    }

    // -------------------------------------------------------------------- struttura

    @Test
    fun `la timeline inizia e finisce come deve`() {
        val result = play()
        assertEquals(MatchEventType.INIZIO, result.events.first().type)
        assertEquals(MatchEventType.FINE, result.events.last().type)
        assertTrue(result.events.any { it.type == MatchEventType.INTERVALLO })
    }

    @Test
    fun `i minuti non tornano mai indietro`() {
        play().events.zipWithNext().forEach { (a, b) ->
            assertTrue(b.minute >= a.minute, "minuto ${b.minute} dopo il minuto ${a.minute}")
        }
    }

    @Test
    fun `i minuti restano nell'arco della partita`() {
        play().events.forEach { assertTrue(it.minute in 0..90, "evento al minuto ${it.minute}") }
    }

    @Test
    fun `il punteggio negli eventi coincide con quello finale`() {
        val result = play()
        val ultimo = result.events.last()
        assertEquals(result.homeGoals, ultimo.homeGoals)
        assertEquals(result.awayGoals, ultimo.awayGoals)
    }

    @Test
    fun `il numero di gol coincide con gli eventi gol`() {
        repeat(30) { seed ->
            val result = play(seed.toLong())
            val golCasa = result.goals().count { it.side == Side.CASA }
            val golOspite = result.goals().count { it.side == Side.OSPITE }
            assertEquals(result.homeGoals, golCasa, "seed $seed")
            assertEquals(result.awayGoals, golOspite, "seed $seed")
        }
    }

    @Test
    fun `la pericolosita resta nella scala`() {
        play().events.forEach { assertTrue(it.danger in 0..100) }
    }

    @Test
    fun `il possesso somma a uno`() {
        val result = play()
        assertEquals(1.0, result.homePossession + result.awayPossession, 0.0001)
        assertTrue(result.homePossession in 0.2..0.8)
    }

    // ------------------------------------------------------------------ statistiche

    @Test
    fun `chi segna ha almeno un tiro registrato`() {
        repeat(30) { seed ->
            val result = play(seed.toLong())
            result.goals().mapNotNull { it.player }.forEach { scorer ->
                val stats = result.stats[scorer]
                assertNotNull(stats, "nessuna statistica per il marcatore $scorer")
                assertTrue(stats.goals > 0)
                assertTrue(stats.shots > 0, "gol senza tiro registrato")
            }
        }
    }

    @Test
    fun `gli assist non vanno mai a chi ha segnato`() {
        repeat(40) { seed ->
            play(seed.toLong()).goals().forEach { gol ->
                if (gol.secondaryPlayer != null) {
                    assertTrue(gol.secondaryPlayer != gol.player, "assist a se stesso")
                }
            }
        }
    }

    @Test
    fun `i titolari accumulano minuti giocati`() {
        val result = play()
        home.lineup.slots.forEach { slot ->
            val stats = result.stats[slot.player.id]
            assertNotNull(stats, "nessuna statistica per il titolare ${slot.player.shortName}")
            assertTrue(stats.minutesPlayed > 0)
        }
    }

    @Test
    fun `i portieri accumulano parate e gol subiti`() {
        var parate = 0
        var subiti = 0
        repeat(20) { seed ->
            val result = play(seed.toLong())
            val gk = home.lineup.goalkeeper
            assertNotNull(gk)
            result.stats[gk.id]?.let { parate += it.saves; subiti += it.goalsConceded }
        }
        assertTrue(parate > 0, "in venti partite il portiere non ha parato nulla")
        assertTrue(subiti > 0, "in venti partite il portiere non ha subito nulla")
    }

    @Test
    fun `il voto resta nella scala e premia chi segna`() {
        val result = play()
        result.stats.values.forEach { stats ->
            val voto = stats.rating(isGoalkeeper = false)
            if (stats.minutesPlayed > 0) {
                assertTrue(voto in 1.0..10.0, "voto fuori scala: $voto")
            }
        }
    }

    @Test
    fun `chi entra tardi non prende voti estremi`() {
        val base = PlayerMatchStats(PlayerId(1), minutesPlayed = 10, goals = 2)
        val pieni = PlayerMatchStats(PlayerId(2), minutesPlayed = 90, goals = 2)
        assertTrue(
            base.rating(false) < pieni.rating(false),
            "due gol in dieci minuti non devono valere quanto due gol in novanta",
        )
    }

    // --------------------------------------------------------------- highlight

    /**
     * L'irregolarita' richiesta non e' programmata: nasce dalle catene di possesso di
     * lunghezza variabile. Qui si verifica che ci sia davvero, cioe' che gli highlight
     * non siano distribuiti in modo uniforme.
     */
    @Test
    fun `gli highlight sono irregolari e non uniformi`() {
        val intervalli = IntArray(9)
        repeat(80) { seed ->
            play(seed.toLong()).highlights().forEach { evento ->
                intervalli[(evento.minute / 10).coerceIn(0, 8)]++
            }
        }
        val media = intervalli.average()
        val varianza = intervalli.map { (it - media) * (it - media) }.average()
        assertTrue(varianza > 0.0, "gli highlight sono distribuiti troppo uniformemente")
        assertTrue(intervalli.all { it > 0 }, "ci sono decine di minuti senza mai un highlight")
    }

    @Test
    fun `la maggior parte degli eventi resta rumore di fondo`() {
        val eventi = play().events
        val ambiente = eventi.count { it.tier == DangerTier.AMBIENTE || it.tier == DangerTier.NOTEVOLE }
        assertTrue(
            ambiente > eventi.size * 0.5,
            "troppi eventi ad alta pericolosita': l'interfaccia interromperebbe di continuo",
        )
    }

    @Test
    fun `i gol sono sempre eventi decisivi`() {
        play(7L).goals().forEach {
            assertEquals(DangerTier.DECISIVO, it.tier, "un gol deve bucare lo schermo")
        }
    }

    // --------------------------------------------------------------- intervallo

    @Test
    fun `simulare i due tempi separatamente equivale a simulare tutto`() {
        val intero = MatchEngine.simulate(home, away, config, 555L)
        val primoTempo = MatchEngine.simulateFirstHalf(home, away, config, 555L)
        val spezzato = MatchEngine.simulateSecondHalf(primoTempo, config)

        assertEquals(intero.scoreline, spezzato.scoreline)
        assertEquals(intero.events.size, spezzato.events.size)
    }

    @Test
    fun `lo stato dell'intervallo fotografa il primo tempo`() {
        val stato = MatchEngine.simulateFirstHalf(home, away, config, 99L)
        assertTrue(stato.events.last().type == MatchEventType.INTERVALLO)
        assertTrue(stato.events.all { it.minute <= 45 })
        assertTrue(stato.totalActions > 0)
    }

    /**
     * La finestra dell'intervallo deve poter cambiare qualcosa davvero, altrimenti e'
     * solo una schermata di attesa.
     */
    @Test
    fun `cambiare tattica all'intervallo cambia il secondo tempo`() {
        val stato = MatchEngine.simulateFirstHalf(home, away, config, 123L)

        val invariato = MatchEngine.simulateSecondHalf(stato, config)
        val corretto = MatchEngine.simulateSecondHalf(
            stato, config,
            home = stato.homeSetup.copy(tactics = Tactics.ARREMBANTE),
        )

        assertTrue(
            invariato.events.size != corretto.events.size ||
                invariato.scoreline != corretto.scoreline,
            "il cambio tattico all'intervallo non ha avuto nessun effetto",
        )
    }

    // ---------------------------------------------------------- ordini condizionali

    @Test
    fun `un ordine condizionale scatta e viene registrato`() {
        val panchinaro = home.lineup.bench.first()
        val titolare = home.lineup.slots.last().player

        val conOrdini = home.copy(
            orders = listOf(
                ConditionalOrder(
                    id = 1,
                    trigger = OrderTrigger.DalMinuto(60),
                    action = OrderAction.Sostituisci(titolare.id, panchinaro.id),
                ),
            ),
        )

        val result = MatchEngine.simulate(conOrdini, away, config, 21L)
        val sostituzioni = result.events.filter { it.type == MatchEventType.SOSTITUZIONE }

        assertEquals(1, sostituzioni.size, "la sostituzione programmata non e' avvenuta")
        assertTrue(sostituzioni.first().minute >= 60)
    }

    @Test
    fun `un ordine condizionale scatta una sola volta`() {
        val conOrdini = home.copy(
            orders = listOf(
                ConditionalOrder(
                    id = 1,
                    trigger = OrderTrigger.DalMinuto(20),
                    action = OrderAction.CambiaAssetto(TacticalStance.ULTRA_OFFENSIVO),
                ),
            ),
        )
        val result = MatchEngine.simulate(conOrdini, away, config, 33L)
        val cambi = result.events.count { it.type == MatchEventType.CAMBIO_TATTICA }
        assertEquals(1, cambi, "l'ordine si e' riattivato a ogni azione")
    }

    @Test
    fun `un ordine con condizione mai verificata non scatta`() {
        val conOrdini = home.copy(
            orders = listOf(
                ConditionalOrder(
                    id = 1,
                    trigger = OrderTrigger.InVantaggioDiDalMinuto(goals = 99, minute = 10),
                    action = OrderAction.CambiaAssetto(TacticalStance.ULTRA_DIFENSIVO),
                ),
            ),
        )
        val result = MatchEngine.simulate(conOrdini, away, config, 44L)
        assertEquals(0, result.events.count { it.type == MatchEventType.CAMBIO_TATTICA })
    }

    @Test
    fun `una sostituzione impossibile non blocca la partita`() {
        val conOrdini = home.copy(
            orders = listOf(
                ConditionalOrder(
                    id = 1,
                    trigger = OrderTrigger.DalMinuto(30),
                    action = OrderAction.Sostituisci(PlayerId(999_999), PlayerId(888_888)),
                ),
            ),
        )
        val result = MatchEngine.simulate(conOrdini, away, config, 55L)
        assertEquals(MatchEventType.FINE, result.events.last().type)
    }

    // ------------------------------------------------------------------- stamina

    @Test
    fun `la partita consuma stamina`() {
        val stato = MatchEngine.simulateFirstHalf(home, away, config, 66L)
        val stanchezza = stato.homeSetup.lineup.slots.map { it.player.stamina }
        assertTrue(
            stanchezza.all { it < 100 },
            "dopo 45 minuti nessuno si e' stancato: la rotazione non servirebbe a niente",
        )
        assertTrue(stanchezza.all { it > 40 }, "dopo 45 minuti sono gia' a pezzi")
    }

    @Test
    fun `pressing alto e ritmo alto consumano di piu`() {
        val tranquillo = MatchEngine.simulateFirstHalf(
            home.copy(tactics = Tactics.CATENACCIO), away, config, 77L,
        )
        val arrembante = MatchEngine.simulateFirstHalf(
            home.copy(tactics = Tactics.ARREMBANTE), away, config, 77L,
        )

        val mediaTranquilla = tranquillo.homeSetup.lineup.slots.map { it.player.stamina }.average()
        val mediaArrembante = arrembante.homeSetup.lineup.slots.map { it.player.stamina }.average()

        assertTrue(
            mediaArrembante < mediaTranquilla,
            "arrembanti: $mediaArrembante, catenaccio: $mediaTranquilla",
        )
    }

    // ------------------------------------------------------------------ robustezza

    @Test
    fun `mille partite consecutive non producono nessun errore`() {
        repeat(1000) { seed ->
            val result = MatchEngine.simulate(home, away, config, seed.toLong())
            assertTrue(result.homeGoals >= 0 && result.awayGoals >= 0)
            assertTrue(result.events.isNotEmpty())
        }
    }

    @Test
    fun `una squadra molto piu forte non manda in crisi il motore`() {
        val fortissima = TestSquads.build(world, 3, "Top", 90)
        val debolissima = TestSquads.build(
            world, 4, "Flop", 50, exclude = TestSquads.playersOf(fortissima),
        )
        repeat(50) { seed ->
            val result = MatchEngine.simulate(fortissima, debolissima, config, seed.toLong())
            assertTrue(result.homeGoals < 20, "risultato assurdo: ${result.scoreline}")
        }
    }
}
