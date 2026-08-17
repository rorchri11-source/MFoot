package dev.mfoot.core.match

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chi va in quale casella.
 *
 * I casi qui dentro sono quelli che un proprietario incontra davvero in una serata: cambia
 * modulo, preme "completa", scopre un infortunato. Nessuno di questi deve produrre una
 * squadra assurda, e "completa" premuto sul telefono deve dare la stessa squadra che
 * darebbe il server.
 */
class LineupFitterTest {

    private val config = ConfigPresets.sprint(16, 8, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    /** Una rosa con una copertura sensata dei ruoli, come sarebbe una rosa vera. */
    private fun balancedSquad(): List<Player> {
        val wanted = listOf(
            Position.POR to 2, Position.TD to 2, Position.DC to 4, Position.TS to 2,
            Position.MED to 2, Position.CC to 3, Position.TRQ to 1,
            Position.AD to 2, Position.AS to 2, Position.ATT to 2,
        )
        val used = mutableSetOf<Long>()
        return wanted.flatMap { (position, count) ->
            world.players
                .filter { it.primaryPosition == position && it.id.value !in used }
                .take(count)
                .onEach { used += it.id.value }
        }
    }

    private fun empty(formation: Formation): List<Player?> =
        List(formation.positions.size) { null }

    // ------------------------------------------------------------------------ riempire

    @Test
    fun `riempie tutte le caselle di un modulo`() {
        val schierati = LineupFitter.fit(Formation.F_4_3_3, balancedSquad())

        assertEquals(11, schierati.size)
        assertTrue(schierati.all { it != null }, "una casella e' rimasta vuota")
        assertEquals(11, schierati.mapNotNull { it?.id?.value }.distinct().size)
    }

    /**
     * Il portiere e' il ruolo che sa fare in pochi, e va assegnato per primo.
     *
     * E' il difetto piu' visibile che questa classe puo' avere: un attaccante fra i pali
     * si nota al primo tiro in porta, e chi guarda pensa che il gioco sia rotto.
     */
    @Test
    fun `mette un portiere vero in porta`() {
        val schierati = LineupFitter.fit(Formation.F_4_3_3, balancedSquad())
        val portiere = schierati.first()

        assertNotNull(portiere)
        assertTrue(
            portiere.primaryPosition.isGoalkeeper,
            "in porta e' finito ${portiere.shortName}, che gioca ${portiere.primaryPosition}",
        )
    }

    /**
     * Con **un solo** giocatore in rosa, quello va nel suo ruolo e non in porta.
     *
     * E' il caso del primo ingresso: si fonda il club, in rosa c'e' solo il giocatore che
     * sei tu, e il campo lo mostrava fra i pali con overall 1. Il difetto stava
     * nell'ordine di assegnazione: "prima i ruoli che sanno fare in pochi" con la rosa
     * vuota diventa "prima i ruoli che nessuno sa fare", e il portiere e' il primo di
     * quelli.
     *
     * I test con la rosa completa non lo vedevano e non potevano vederlo: quando tutti i
     * ruoli hanno candidati, l'ordine sbagliato non fa danni.
     */
    @Test
    fun `con un solo giocatore in rosa lo mette nel suo ruolo, non in porta`() {
        val attaccante = balancedSquad().first { it.primaryPosition == Position.ATT }
        val schierati = LineupFitter.fit(Formation.F_4_3_3, listOf(attaccante))

        assertEquals(1, schierati.count { it != null })

        val casella = schierati.indexOfFirst { it != null }
        val ruolo = Formation.F_4_3_3.positions[casella]
        assertEquals(
            Position.ATT,
            ruolo,
            "l'unico giocatore in rosa e' finito a fare il $ruolo",
        )
    }

    /**
     * I ruoli che qualcuno sa fare vengono serviti **prima** di quelli scoperti.
     *
     * Con quattro centrali e due terzini e nessun portiere, i sei devono occupare le
     * caselle di difesa; solo chi avanza va in porta o a centrocampo. L'ordine sbagliato
     * faceva il contrario — mandava i primi nelle caselle che nessuno sapeva fare — e la
     * difesa restava vuota mentre un centrale faceva l'ala.
     *
     * Che un difensore finisca in porta quando di portieri non ce n'e' nessuno non e' un
     * difetto: una squadra senza portiere prende gol a ogni tiro, e il server nella stessa
     * situazione farebbe la stessa scelta.
     */
    @Test
    fun `i ruoli coperti vengono riempiti prima di quelli scoperti`() {
        val difensori = balancedSquad()
            .filter { it.primaryPosition in setOf(Position.TD, Position.DC) }
            .take(6)
        val schierati = LineupFitter.fit(Formation.F_4_3_3, difensori)
        val positions = Formation.F_4_3_3.positions

        // Le caselle di difesa che questi sei sanno davvero fare.
        val difesa = positions.indices.filter {
            positions[it] in setOf(Position.TD, Position.DC)
        }
        difesa.forEach { index ->
            val player = schierati[index]
            assertNotNull(player, "la casella ${positions[index]} e' rimasta vuota")
            assertTrue(
                player.canPlay(positions[index]),
                "in ${positions[index]} c'e' ${player.shortName}, che gioca ${player.primaryPosition}",
            )
        }
    }

    /**
     * Con meno giocatori che caselle non si inventa nessuno: restano dei buchi.
     *
     * Restituire una lista corta, o ripetere qualcuno, sarebbe molto peggio di un null:
     * chi chiama deve poter mostrare le caselle vuote sul campo.
     */
    @Test
    fun `una rosa corta lascia caselle vuote invece di ripetere qualcuno`() {
        val sette = balancedSquad().take(7)
        val schierati = LineupFitter.fit(Formation.F_4_3_3, sette)

        assertEquals(11, schierati.size)
        assertEquals(7, schierati.count { it != null })
        assertEquals(7, schierati.mapNotNull { it?.id?.value }.distinct().size)
    }

    @Test
    fun `gli infortunati non vengono schierati`() {
        val squad = balancedSquad()
        val giornata = MatchDay(5)
        val fuori = squad.map { it.copy(injuredUntil = MatchDay(9)) }

        assertTrue(LineupFitter.fit(Formation.F_4_3_3, fuori, giornata).all { it == null })
        assertTrue(LineupFitter.fit(Formation.F_4_3_3, squad, giornata).all { it != null })
    }

    // ------------------------------------------------------- completare senza spostare

    /**
     * "Completa" riempie i vuoti e **non tocca** chi c'e' gia'.
     *
     * E' la promessa del pulsante. Se completando si spostassero anche i titolari gia'
     * scelti, chi ha messo il proprio giocatore da trequartista se lo ritroverebbe
     * terzino, e non premerebbe mai piu' quel pulsante.
     */
    @Test
    fun `completare non sposta chi e' gia' in campo`() {
        val squad = balancedSquad()
        val attaccante = squad.first { it.primaryPosition == Position.ATT }

        // L'attaccante messo a mano in una casella di difesa: una scelta discutibile, ma
        // e' una scelta, e va rispettata.
        val current = empty(Formation.F_4_3_3).toMutableList()
        current[2] = attaccante

        val completata = LineupFitter.fillHoles(Formation.F_4_3_3, current, squad)

        assertEquals(attaccante.id, completata[2]?.id)
        assertTrue(completata.all { it != null })
        assertEquals(11, completata.mapNotNull { it?.id?.value }.distinct().size)
    }

    @Test
    fun `completare non schiera due volte lo stesso giocatore`() {
        val squad = balancedSquad()
        val current = empty(Formation.F_4_3_3).toMutableList()
        current[9] = squad.first { it.primaryPosition == Position.POR }

        val completata = LineupFitter.fillHoles(Formation.F_4_3_3, current, squad)

        assertEquals(11, completata.mapNotNull { it?.id?.value }.distinct().size)
    }

    @Test
    fun `una formazione gia' completa resta identica`() {
        val squad = balancedSquad()
        val prima = LineupFitter.fit(Formation.F_4_3_3, squad)
        val dopo = LineupFitter.fillHoles(Formation.F_4_3_3, prima, squad)

        assertEquals(prima.map { it?.id }, dopo.map { it?.id })
    }

    @Test
    fun `un numero di caselle sbagliato viene rifiutato subito`() {
        val errore = runCatching {
            LineupFitter.fillHoles(Formation.F_4_3_3, empty(Formation.F_3_5_2).dropLast(1), emptyList())
        }.exceptionOrNull()

        assertNotNull(errore)
        assertTrue(errore is IllegalArgumentException)
    }

    // ------------------------------------------------------------------ cambio modulo

    /**
     * Cambiare modulo non deve svuotare il campo.
     *
     * E' il gesto che si fa piu' spesso su questa schermata: si prova il 4-3-3, si prova
     * il 3-5-2, si torna indietro. Se ogni prova azzerasse gli undici, nessuno ne
     * proverebbe due.
     */
    @Test
    fun `cambiare modulo tiene in campo gli stessi undici`() {
        val squad = balancedSquad()
        val conTre = LineupFitter.fit(Formation.F_4_3_3, squad)
        val rimessi = LineupFitter.fit(Formation.F_3_5_2, conTre.filterNotNull())

        assertEquals(11, rimessi.count { it != null })
        assertEquals(
            conTre.mapNotNull { it?.id?.value }.toSet(),
            rimessi.mapNotNull { it?.id?.value }.toSet(),
        )
    }

    // ---------------------------------------------------------------------- panchina

    @Test
    fun `la panchina prende chi non e' in campo`() {
        val squad = balancedSquad()
        val eleven = LineupFitter.fit(Formation.F_4_3_3, squad)
        val bench = LineupFitter.bench(eleven, squad)

        val inCampo = eleven.mapNotNull { it?.id?.value }.toSet()
        assertTrue(bench.none { it.id.value in inCampo }, "un titolare e' anche in panchina")
        assertEquals(minOf(LineupFitter.DEFAULT_BENCH, squad.size - 11), bench.size)
    }

    @Test
    fun `in panchina non vanno gli infortunati`() {
        val squad = balancedSquad()
        val giornata = MatchDay(5)
        val eleven = LineupFitter.fit(Formation.F_4_3_3, squad, giornata)
        val riserve = squad.filterNot { p -> eleven.any { it?.id == p.id } }
        val conInfortuni = eleven.filterNotNull() +
            riserve.map { it.copy(injuredUntil = MatchDay(9)) }

        assertEquals(0, LineupFitter.bench(eleven, conInfortuni, today = giornata).size)
    }

    /**
     * La panchina e' ordinata: il primo cambio deve essere il piu' utile.
     *
     * Una panchina in ordine sparso non e' sbagliata nel motore — le sostituzioni le
     * decide la partita — ma sullo schermo dice al proprietario chi entrerebbe per primo,
     * e mentire su quello e' peggio che tacere.
     */
    @Test
    fun `la panchina e' ordinata per resa`() {
        val squad = balancedSquad()
        val eleven = LineupFitter.fit(Formation.F_4_3_3, squad)
        val bench = LineupFitter.bench(eleven, squad)

        val rese = bench.map { LineupFitter.fitness(it, it.primaryPosition) }
        assertEquals(rese.sortedDescending(), rese)
    }

    // ------------------------------------------------------- accordo con la formazione automatica

    /**
     * Premere "completa" da' la stessa squadra che schiererebbe il server da solo.
     *
     * E' il motivo per cui questa classe esiste. Due algoritmi diversi che rispondono alla
     * stessa domanda producono la squadra A sul telefono e la squadra B nel tabellino, e
     * chi gioca non ha nessun modo di capire quale delle due ha davvero giocato.
     */
    @Test
    fun `completare da zero coincide con la formazione automatica`() {
        val squad = balancedSquad()
        val formation = Formation.F_4_3_3

        val automatica = AutoLineup.build(squad, formation, MatchDay(1))
        assertNotNull(automatica)

        val completata = LineupFitter.fit(formation, squad, MatchDay(1))

        assertEquals(
            automatica.slots.map { it.player.id.value },
            completata.map { it?.id?.value },
        )
    }

    /**
     * L'accordo vale anche con la rosa stanca, che e' dove le due formule divergevano.
     *
     * Il test qui sopra da solo non dimostrava niente: un mondo appena generato ha tutti a
     * stamina piena, e due formule che si separano solo sotto quarantacinque danno
     * ovviamente lo stesso risultato. La divergenza c'era, e passava.
     *
     * Qui meta' rosa e' a pezzi, quindi la penalita' per stanchezza entra davvero nel
     * conto e cambia chi viene scelto. Se un giorno le due formule si separassero di
     * nuovo, e' questo test a cadere.
     */
    @Test
    fun `l accordo con la formazione automatica regge anche a rosa stanca`() {
        val formation = Formation.F_4_3_3
        val stanca = balancedSquad().mapIndexed { index, player ->
            // Alternati: cosi' in ogni reparto c'e' sia chi e' fresco sia chi e' sotto
            // soglia, e la scelta fra i due dipende dalla penalita'.
            if (index % 2 == 0) player.copy(stamina = 30) else player
        }

        val automatica = AutoLineup.build(stanca, formation, MatchDay(1))
        assertNotNull(automatica)

        val completata = LineupFitter.fit(formation, stanca, MatchDay(1))

        assertEquals(
            automatica.slots.map { it.player.id.value },
            completata.map { it?.id?.value },
        )
        assertEquals(
            automatica.bench.map { it.id.value },
            LineupFitter.bench(completata, stanca).map { it.id.value },
        )
    }
}
