package dev.mfoot.core.world

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.config.SetupConfig
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.StaffRole
import dev.mfoot.core.model.Trait
import dev.mfoot.core.rng.DeterministicRandom
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldGeneratorTest {

    private val config = ConfigPresets.sprint(16, 8, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    // ------------------------------------------------------------------ determinismo

    @Test
    fun `lo stesso seed genera lo stesso mondo`() {
        val a = WorldGenerator.generate(config)
        val b = WorldGenerator.generate(config)

        assertEquals(a.players.size, b.players.size)
        a.players.zip(b.players).forEach { (pa, pb) ->
            assertEquals(pa, pb, "il giocatore ${pa.id} differisce fra due generazioni")
        }
        assertEquals(a.staff, b.staff)
    }

    @Test
    fun `seed diversi generano mondi diversi`() {
        val altro = config.copy(setup = config.setup.copy(worldSeed = 999L))
        val a = WorldGenerator.generate(config)
        val b = WorldGenerator.generate(altro)

        assertTrue(a.players != b.players, "due seed diversi hanno prodotto lo stesso mondo")
    }

    /**
     * I flussi sono separati: toccare la generazione dei giocatori non deve spostare
     * lo staff, altrimenti ogni ritocco al codice invaliderebbe le leghe in corso.
     */
    @Test
    fun `i flussi di giocatori e staff sono indipendenti`() {
        val root = DeterministicRandom(config.setup.worldSeed)
        assertTrue(root.fork(101L).nextLong() != root.fork(202L).nextLong())
    }

    // ------------------------------------------------------------------- dimensioni

    @Test
    fun `genera esattamente i giocatori richiesti`() {
        assertEquals(config.world.playerCount, world.players.size)
    }

    @Test
    fun `gli id dei giocatori sono unici e progressivi`() {
        val ids = world.players.map { it.id.value }
        assertEquals(ids.size, ids.toSet().size, "ci sono id duplicati")
        assertEquals((1L..world.players.size.toLong()).toList(), ids)
    }

    @Test
    fun `genera staff per tutti e tre i ruoli`() {
        val byRole = world.staffByRole()
        StaffRole.entries.forEach { role ->
            val count = byRole[role]?.size ?: 0
            assertTrue(
                count >= config.setup.totalClubs,
                "solo $count ${role.label} per ${config.setup.totalClubs} club",
            )
        }
    }

    // ------------------------------------------------------------------------- ruoli

    @Test
    fun `ci sono abbastanza portieri per tutti i club`() {
        assertTrue(
            world.goalkeepers.size >= config.setup.totalClubs * 2,
            "solo ${world.goalkeepers.size} portieri per ${config.setup.totalClubs} club",
        )
    }

    @Test
    fun `le quote dei ruoli sono rispettate entro un margine ragionevole`() {
        val byPosition = world.playersByPosition()
        val quotaSum = config.world.positionQuotas.values.sum()

        config.world.positionQuotas.forEach { (position, quota) ->
            val expected = world.players.size * quota / quotaSum
            val actual = (byPosition[position]?.size ?: 0).toDouble()
            val deviation = abs(actual - expected) / expected
            assertTrue(
                deviation < 0.15,
                "${position.short}: attesi ~${expected.toInt()}, generati ${actual.toInt()}",
            )
        }
    }

    @Test
    fun `ogni ruolo previsto e rappresentato`() {
        val byPosition = world.playersByPosition()
        config.world.positionQuotas.keys.forEach { position ->
            assertTrue(
                (byPosition[position]?.size ?: 0) > 0,
                "nessun giocatore generato per il ruolo ${position.short}",
            )
        }
    }

    // ---------------------------------------------------------------------- potenziale

    @Test
    fun `il potenziale non e mai sotto l'overall attuale`() {
        world.players.forEach { p ->
            assertTrue(
                p.potentialMin >= p.overall,
                "${p.shortName}: overall ${p.overall} ma potenziale minimo ${p.potentialMin}",
            )
        }
    }

    @Test
    fun `la forbice di potenziale e coerente`() {
        world.players.forEach { p ->
            assertTrue(p.potentialMin <= p.potentialMax, "${p.shortName}: forbice invertita")
            assertTrue(p.potentialMax <= 99, "${p.shortName}: potenziale oltre 99")
        }
    }

    /**
     * Un diciassettenne puo' davvero finire ovunque, un ventottenne e' gia' arrivato.
     * Se le due forbici fossero uguali, lo scouting non avrebbe niente da scoprire.
     */
    @Test
    fun `i giovani hanno forbici piu larghe dei maturi`() {
        val giovani = world.players.filter { it.age <= 19 }
        val maturi = world.players.filter { it.age in 27..29 }

        assertTrue(giovani.isNotEmpty() && maturi.isNotEmpty(), "campione insufficiente")

        val spreadGiovani = giovani.map { it.potentialMax - it.potentialMin }.average()
        val spreadMaturi = maturi.map { it.potentialMax - it.potentialMin }.average()

        assertTrue(
            spreadGiovani > spreadMaturi * 1.8,
            "forbice giovani ${"%.1f".format(spreadGiovani)} contro maturi ${"%.1f".format(spreadMaturi)}",
        )
    }

    // ---------------------------------------------------------------------------- eta

    @Test
    fun `l'eta resta nei limiti configurati`() {
        world.players.forEach { p ->
            assertTrue(
                p.age in config.world.minAge..config.world.maxAge,
                "${p.shortName} ha ${p.age} anni, fuori dai limiti",
            )
        }
    }

    @Test
    fun `l'eta media e da rosa credibile`() {
        val media = world.players.map { it.age }.average()
        assertTrue(media in 23.5..27.0, "eta' media anomala: ${"%.1f".format(media)}")
    }

    @Test
    fun `esistono sia giovanissimi che veterani`() {
        assertTrue(world.players.any { it.age <= 18 }, "nessun giovanissimo: la Primavera resterebbe vuota")
        assertTrue(world.players.any { it.age >= 33 }, "nessun veterano")
    }

    /**
     * La conseguenza piu' importante del modello "prima il potenziale, poi l'eta'":
     * i giovani sono mediamente piu' grezzi. Senza questo, il mondo si riempirebbe di
     * diciassettenni gia' fortissimi e lo scouting non servirebbe.
     */
    @Test
    fun `i giovani sono mediamente meno pronti dei giocatori nel pieno`() {
        val giovani = world.players.filter { it.age <= 19 }.map { it.overall }.average()
        val maturi = world.players.filter { it.age in 26..29 }.map { it.overall }.average()

        assertTrue(
            giovani < maturi - 8,
            "giovani ${"%.1f".format(giovani)} contro maturi ${"%.1f".format(maturi)}: troppo vicini",
        )
    }

    // ------------------------------------------------------------------------ overall

    @Test
    fun `la coda alta resta sottile`() {
        val fuoriclasse = world.players.count { it.overall >= 85 }
        val totale = world.players.size

        assertTrue(fuoriclasse > 0, "nessun giocatore forte: non ci sarebbe niente per cui litigare")
        assertTrue(
            fuoriclasse < totale * 0.03,
            "$fuoriclasse giocatori sopra 85 su $totale: troppi, le aste perderebbero tensione",
        )
    }

    @Test
    fun `l'overall resta nei limiti validi`() {
        world.players.forEach { p ->
            assertTrue(p.overall in 1..99, "${p.shortName} ha overall ${p.overall}")
        }
    }

    @Test
    fun `esistono giocatori validi in ogni fascia di qualita`() {
        val fasce = listOf(0..59, 60..69, 70..79, 80..99)
        fasce.forEach { fascia ->
            assertTrue(
                world.players.any { it.overall in fascia },
                "nessun giocatore nella fascia $fascia",
            )
        }
    }

    // ---------------------------------------------------------------------- attributi

    @Test
    fun `i portieri hanno attributi da portiere alti`() {
        world.goalkeepers.take(30).forEach { p ->
            val parata = p.attributes[Attr.PARATA]
            val tiro = p.attributes[Attr.TIRO]
            assertTrue(
                parata > tiro,
                "${p.shortName}: portiere con parata $parata e tiro $tiro",
            )
        }
    }

    @Test
    fun `gli attaccanti tirano meglio di quanto difendano`() {
        val attaccanti = world.players.filter { it.primaryPosition == Position.ATT }
        assertTrue(attaccanti.isNotEmpty())

        val mediaTiro = attaccanti.map { it.attributes[Attr.TIRO] }.average()
        val mediaDifesa = attaccanti.map { it.attributes[Attr.DIFESA] }.average()
        assertTrue(
            mediaTiro > mediaDifesa + 15,
            "attaccanti: tiro ${"%.1f".format(mediaTiro)}, difesa ${"%.1f".format(mediaDifesa)}",
        )
    }

    @Test
    fun `i difensori centrali difendono meglio di quanto tirino`() {
        val difensori = world.players.filter { it.primaryPosition == Position.DC }
        val mediaDifesa = difensori.map { it.attributes[Attr.DIFESA] }.average()
        val mediaTiro = difensori.map { it.attributes[Attr.TIRO] }.average()
        assertTrue(mediaDifesa > mediaTiro + 15)
    }

    @Test
    fun `i giocatori di movimento non hanno attributi da portiere alti`() {
        world.players.filterNot { it.isGoalkeeper }.take(100).forEach { p ->
            assertTrue(
                p.attributes[Attr.PARATA] < 55,
                "${p.shortName} (${p.primaryPosition.short}) ha parata ${p.attributes[Attr.PARATA]}",
            )
        }
    }

    @Test
    fun `le stelle restano nella scala 1-5`() {
        world.players.forEach { p ->
            assertTrue(p.weakFoot in 1..5, "${p.shortName}: piede debole ${p.weakFoot}")
            assertTrue(p.skillStars in 1..5, "${p.shortName}: tecnica ${p.skillStars}")
        }
    }

    // ------------------------------------------------------------------------- tratti

    @Test
    fun `una parte dei giocatori ha tratti ma non tutti`() {
        val conTratti = world.players.count { it.traits.isNotEmpty() }
        val quota = conTratti.toDouble() / world.players.size
        assertTrue(quota in 0.30..0.60, "quota giocatori con tratti: ${"%.2f".format(quota)}")
    }

    @Test
    fun `i tratti da giovane promessa non finiscono sui veterani`() {
        world.players.filter { it.age > 23 }.forEach { p ->
            val vietati = p.traits.intersect(Trait.youthOnly)
            assertTrue(vietati.isEmpty(), "${p.shortName} (${p.age} anni) ha $vietati")
        }
    }

    @Test
    fun `i tratti contraddittori non coesistono`() {
        world.players.forEach { p ->
            assertTrue(
                !(Trait.TALENTO_PRECOCE in p.traits && Trait.MATURAZIONE_TARDIVA in p.traits),
                "${p.shortName} e' insieme talento precoce e maturazione tardiva",
            )
            assertTrue(
                !(Trait.FRAGILE in p.traits && Trait.INSTANCABILE in p.traits),
                "${p.shortName} e' insieme fragile e instancabile",
            )
        }
    }

    // -------------------------------------------------------------------------- nomi

    @Test
    fun `le omonimie sono rare`() {
        val nomi = world.players.map { it.fullName }
        val duplicati = nomi.size - nomi.toSet().size
        assertTrue(
            duplicati < nomi.size * 0.02,
            "$duplicati omonimie su ${nomi.size} giocatori",
        )
    }

    @Test
    fun `ogni giocatore ha nome e cognome non vuoti`() {
        world.players.forEach { p ->
            assertTrue(p.firstName.isNotBlank(), "giocatore ${p.id} senza nome")
            assertTrue(p.lastName.isNotBlank(), "giocatore ${p.id} senza cognome")
        }
    }

    @Test
    fun `le nazionalita sono tutte fra quelle configurate`() {
        val ammesse = config.world.nationalities.toSet()
        world.players.forEach { p ->
            assertTrue(p.nationality in ammesse, "nazionalita' inattesa: ${p.nationality}")
        }
    }

    @Test
    fun `tutte le nazionalita configurate hanno una banca nomi`() {
        config.world.nationalities.forEach { nazione ->
            assertTrue(
                NameBank.supports(nazione),
                "manca la banca nomi per '$nazione': i giocatori avrebbero nomi dell'area sbagliata",
            )
        }
    }

    // ------------------------------------------------------------------- robustezza

    @Test
    fun `regge una lega piccola`() {
        val piccola = LeagueConfig(setup = SetupConfig(totalClubs = 4, aiClubs = 2))
        val w = WorldGenerator.generate(piccola)
        assertTrue(w.players.isNotEmpty())
        assertTrue(w.goalkeepers.size >= 4)
    }

    @Test
    fun `regge una lega da venti club`() {
        val grande = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
        val w = WorldGenerator.generate(grande)
        assertTrue(w.goalkeepers.size >= 20)
        assertTrue(w.players.size >= 20 * grande.setup.minSquadSize)
    }
}

class DevelopmentCurveTest {

    @Test
    fun `il picco e fra i 27 e i 28 anni`() {
        assertEquals(1.0, DevelopmentCurve.realizedFraction(27))
        assertEquals(1.0, DevelopmentCurve.realizedFraction(28))
        assertTrue(DevelopmentCurve.realizedFraction(26) < 1.0)
        assertTrue(DevelopmentCurve.realizedFraction(29) < 1.0)
    }

    @Test
    fun `la curva sale fino al picco`() {
        (16..27).zipWithNext().forEach { (a, b) ->
            assertTrue(
                DevelopmentCurve.realizedFraction(b) > DevelopmentCurve.realizedFraction(a),
                "la curva non sale fra $a e $b",
            )
        }
    }

    @Test
    fun `la curva scende dopo il picco`() {
        (28..40).zipWithNext().forEach { (a, b) ->
            assertTrue(
                DevelopmentCurve.realizedFraction(b) <= DevelopmentCurve.realizedFraction(a),
                "la curva non scende fra $a e $b",
            )
        }
    }

    @Test
    fun `il declino accelera con l'eta`() {
        val calo3233 = DevelopmentCurve.realizedFraction(32) - DevelopmentCurve.realizedFraction(33)
        val calo2930 = DevelopmentCurve.realizedFraction(29) - DevelopmentCurve.realizedFraction(30)
        assertTrue(calo3233 > calo2930, "il declino dovrebbe accelerare")
    }

    @Test
    fun `le eta fuori tabella non fanno esplodere niente`() {
        assertTrue(DevelopmentCurve.realizedFraction(5) > 0.0)
        assertTrue(DevelopmentCurve.realizedFraction(99) > 0.0)
    }

    @Test
    fun `un diciottenne con potenziale novanta e ancora grezzo`() {
        val overall = DevelopmentCurve.currentOverall(potential = 90, age = 18)
        assertTrue(overall in 55..65, "un diciottenne da 90 di potenziale esce a $overall")
    }

    @Test
    fun `a ventisette anni si e al proprio massimo`() {
        assertEquals(85, DevelopmentCurve.currentOverall(potential = 85, age = 27))
    }

    @Test
    fun `isGrowing e isDeclining coprono le fasce giuste`() {
        assertTrue(DevelopmentCurve.isGrowing(22))
        assertTrue(!DevelopmentCurve.isGrowing(30))
        assertTrue(DevelopmentCurve.isDeclining(33))
        assertTrue(!DevelopmentCurve.isDeclining(24))
    }

    @Test
    fun `la forbice si stringe con l'eta`() {
        val giovane = DevelopmentCurve.potentialSpread(17, 3, 18)
        val maturo = DevelopmentCurve.potentialSpread(28, 3, 18)
        assertTrue(giovane > maturo, "forbice giovane $giovane, matura $maturo")
        assertTrue(maturo >= 3, "la forbice non deve mai azzerarsi del tutto")
    }
}

class AttributeGeneratorTest {

    private val rng = DeterministicRandom(4242L)

    @Test
    fun `l'overall generato combacia con quello richiesto`() {
        Position.entries.forEach { position ->
            listOf(45, 60, 72, 85, 93).forEach { target ->
                val attributes = AttributeGenerator.generate(position, target, rng)
                assertEquals(
                    target,
                    position.overallOf(attributes),
                    "${position.short} con target $target",
                )
            }
        }
    }

    @Test
    fun `gli attributi restano nei limiti validi`() {
        repeat(200) {
            val attributes = AttributeGenerator.generate(Position.ATT, rng.nextIntInclusive(40, 95), rng)
            Attr.entries.forEach { attr ->
                assertTrue(attributes[attr] in 1..99, "$attr fuori scala: ${attributes[attr]}")
            }
        }
    }

    @Test
    fun `due giocatori dello stesso overall non sono identici`() {
        val a = AttributeGenerator.generate(Position.CC, 75, rng)
        val b = AttributeGenerator.generate(Position.CC, 75, rng)
        assertTrue(a != b, "il rumore non sta producendo varieta'")
    }

    @Test
    fun `il profilo del ruolo resta riconoscibile dopo la correzione`() {
        val punta = AttributeGenerator.generate(Position.ATT, 80, rng)
        assertTrue(
            punta[Attr.TIRO] > punta[Attr.DIFESA] + 20,
            "la correzione ha appiattito il profilo della punta",
        )
    }

    @Test
    fun `gli overall estremi non rompono la generazione`() {
        listOf(1, 5, 99).forEach { target ->
            val attributes = AttributeGenerator.generate(Position.CC, target, rng)
            Attr.entries.forEach { assertTrue(attributes[it] in 1..99) }
        }
    }

    @Test
    fun `i giocatori forti tendono ad avere piu stelle`() {
        val scarsi = (1..300).map { AttributeGenerator.generateStars(55, rng) }
        val forti = (1..300).map { AttributeGenerator.generateStars(90, rng) }

        val mediaScarsi = scarsi.map { it.second }.average()
        val mediaForti = forti.map { it.second }.average()
        assertTrue(mediaForti > mediaScarsi + 0.8, "$mediaScarsi contro $mediaForti")
    }

    @Test
    fun `le stelle restano nella scala`() {
        repeat(500) {
            val (weak, skill) = AttributeGenerator.generateStars(rng.nextIntInclusive(40, 95), rng)
            assertTrue(weak in 1..5 && skill in 1..5)
        }
    }
}

class PotentialEstimatorTest {

    private val world = WorldGenerator.generate(ConfigPresets.sprint(16, 8, LocalDate.of(2026, 9, 1)))
    private val giovane = world.players.filter { it.age <= 19 }.maxBy { it.potentialMax }

    @Test
    fun `la stessa stima e stabile fra due letture`() {
        val a = PotentialEstimator.estimate(giovane, observerId = 7L)
        val b = PotentialEstimator.estimate(giovane, observerId = 7L)
        assertEquals(a, b, "la stima balla fra due aperture della scheda")
    }

    @Test
    fun `osservatori diversi vedono stime diverse`() {
        val stime = (1L..12L).map { PotentialEstimator.estimate(giovane, it) }.toSet()
        assertTrue(stime.size > 1, "tutti i club vedono la stessa identica stima")
    }

    @Test
    fun `la conoscenza stringe la forbice`() {
        val alBuio = PotentialEstimator.estimate(giovane, 1L, minutesObserved = 0)
        val conosciuto = PotentialEstimator.estimate(
            giovane, 1L, minutesObserved = 1800, scoutAccuracy = 0.75,
        )
        val ampiezzaBuio = alBuio.last - alBuio.first
        val ampiezzaNota = conosciuto.last - conosciuto.first

        assertTrue(
            ampiezzaNota < ampiezzaBuio,
            "forbice al buio $ampiezzaBuio, con informazioni $ampiezzaNota",
        )
    }

    @Test
    fun `lo scouting da solo stringe la forbice`() {
        val senza = PotentialEstimator.estimate(giovane, 3L, scoutAccuracy = 0.0)
        val con = PotentialEstimator.estimate(giovane, 3L, scoutAccuracy = 0.75)
        assertTrue((con.last - con.first) < (senza.last - senza.first))
    }

    @Test
    fun `la stima non scende mai sotto l'overall attuale`() {
        world.players.take(200).forEach { p ->
            val stima = PotentialEstimator.estimate(p, 5L)
            assertTrue(
                stima.first >= p.overall,
                "${p.shortName}: overall ${p.overall}, stima parte da ${stima.first}",
            )
        }
    }

    @Test
    fun `la stima resta entro la scala valida`() {
        world.players.take(200).forEach { p ->
            val stima = PotentialEstimator.estimate(p, 9L, minutesObserved = 400)
            assertTrue(stima.first in 1..99 && stima.last in 1..99)
            assertTrue(stima.first <= stima.last)
        }
    }

    @Test
    fun `knowledge cresce con minuti e scouting e non supera uno`() {
        assertEquals(0.0, PotentialEstimator.knowledge(0, 0.0))
        assertTrue(PotentialEstimator.knowledge(900, 0.0) > 0.0)
        assertTrue(PotentialEstimator.knowledge(1800, 1.0) <= 1.0)
        assertTrue(
            PotentialEstimator.knowledge(1800, 0.5) > PotentialEstimator.knowledge(300, 0.5),
        )
    }

    /**
     * Se il centro della forbice coincidesse sempre con la verita', basterebbe leggere
     * il punto medio e lo scouting non servirebbe a niente.
     */
    @Test
    fun `la stima e distorta e non centrata sulla verita`() {
        val trueCenter = (giovane.potentialMin + giovane.potentialMax) / 2.0
        val centri = (1L..40L).map {
            val e = PotentialEstimator.estimate(giovane, it)
            (e.first + e.last) / 2.0
        }
        assertTrue(
            centri.any { abs(it - trueCenter) > 1.5 },
            "nessun osservatore si sbaglia: la stima e' troppo onesta",
        )
    }

    @Test
    fun `describe produce un'etichetta leggibile`() {
        val stima = PotentialEstimator.estimate(giovane, 1L)
        val testo = PotentialEstimator.describe(giovane, stima)
        assertTrue(testo.contains("${giovane.overall} ora"))
        assertTrue(testo.contains("potenziale"))
    }
}
