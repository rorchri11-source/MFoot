package dev.mfoot.core.calendar

import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.CompetitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Le prove del pezzo che mancava: far camminare una competizione da un turno all'altro.
 *
 * Il difetto che queste prove chiudono non era un calcolo sbagliato — `nextKnockoutRound`
 * era corretto e provato dal primo giorno — ma il fatto che **non lo chiamasse nessuno**.
 * Qui si prova la domanda che non veniva mai fatta: il turno e' finito, e chi e' passato?
 */
class CompetitionProgressTest {

    private fun clubs(n: Int) = (1..n).map { ClubId(it.toLong()) }

    private fun coppa(n: Int, twoLegged: Boolean = false) = Competition(
        id = CompetitionId(7),
        name = "Coppa",
        type = CompetitionType.ELIMINAZIONE_DIRETTA,
        participants = clubs(n),
        twoLeggedKnockout = twoLegged,
    )

    /** Le partite di un turno, con l'esito gia' deciso da chi chiama. */
    private fun gare(rounds: List<Round>, vince: (Pairing) -> ClubId?): Pair<List<FixtureState>, List<FixtureResult>> {
        val stati = mutableListOf<FixtureState>()
        val esiti = mutableListOf<FixtureResult>()
        var id = 1L
        rounds.forEach { round ->
            round.pairings.forEach { p ->
                stati += FixtureState(round.number, p.home, p.away, p.tieId, played = true)
                val casa = if (vince(p) == p.home) 2 else 0
                val fuori = if (vince(p) == p.away) 2 else 0
                esiti += FixtureResult(id++, round.competitionId, p.home, p.away, casa, fuori)
            }
        }
        return stati to esiti
    }

    /** Vince sempre chi ha l'id piu' basso: un esito arbitrario ma ripetibile. */
    private val vinceIlMinore: (Pairing) -> ClubId =
        { if (it.home.value < it.away.value) it.home else it.away }

    // ------------------------------------------------------------------- il turno

    @Test
    fun `finche' una partita del turno non e' giocata non si genera niente`() {
        val competition = coppa(8)
        val ottavi = FixtureGenerator.knockout(competition)
        val (stati, esiti) = gare(ottavi, vinceIlMinore)
        val aMeta = stati.mapIndexed { i, s -> if (i == 0) s.copy(played = false) else s }

        assertEquals(
            CompetitionProgress.Next.Attendi,
            CompetitionProgress.next(competition, aMeta, esiti),
            "con una partita ancora da giocare il turno dopo non esiste",
        )
    }

    @Test
    fun `finito un turno nasce il successivo, con l'etichetta giusta`() {
        val competition = coppa(8)
        val (stati, esiti) = gare(FixtureGenerator.knockout(competition), vinceIlMinore)

        val next = CompetitionProgress.next(competition, stati, esiti)
        val turno = assertIs<CompetitionProgress.Next.Turno>(next)

        assertEquals(1, turno.rounds.size)
        assertEquals("Semifinali", turno.rounds.first().label)
        assertEquals(2, turno.rounds.first().pairings.size)
        assertEquals(2, turno.rounds.first().number, "il turno nuovo viene dopo il primo")
    }

    @Test
    fun `la coppa arriva alla finale e poi dichiara il vincitore`() {
        val competition = coppa(4)

        var stati = emptyList<FixtureState>()
        var esiti = emptyList<FixtureResult>()
        var rounds = FixtureGenerator.knockout(competition)
        val etichette = mutableListOf<String>()

        // Si gioca fino a quando la competizione dice che non c'e' piu' niente.
        var giri = 0
        while (giri++ < 10) {
            etichette += rounds.map { it.label }
            val (nuoviStati, nuoviEsiti) = gare(rounds, vinceIlMinore)
            stati = stati + nuoviStati
            esiti = esiti + nuoviEsiti

            when (val next = CompetitionProgress.next(competition, stati, esiti)) {
                is CompetitionProgress.Next.Turno -> rounds = next.rounds
                is CompetitionProgress.Next.Finita -> {
                    assertEquals(ClubId(1), next.winner, "vince sempre il minore, quindi il numero uno")
                    assertEquals(listOf("Semifinali", "Finale"), etichette)
                    return
                }
                CompetitionProgress.Next.Attendi -> error("tutte le partite sono giocate")
            }
        }
        error("la coppa non e' mai finita")
    }

    @Test
    fun `chi riposa passa il turno invece di sparire`() {
        // Cinque squadre: due accoppiamenti e una che resta fuori. Era il caso in cui la
        // quinta usciva dalla coppa senza aver perso niente.
        val competition = coppa(5)
        val primo = FixtureGenerator.knockout(competition)
        assertEquals(2, primo.first().pairings.size, "due gare, la quinta riposa")

        val (stati, esiti) = gare(primo, vinceIlMinore)
        val turno = assertIs<CompetitionProgress.Next.Turno>(
            CompetitionProgress.next(competition, stati, esiti),
        )

        val inCampo = turno.rounds.flatMap { r -> r.pairings.flatMap { listOf(it.home, it.away) } }
        assertTrue(
            ClubId(5) in inCampo,
            "chi ha riposato deve ritrovarsi nel turno dopo: era passato, non eliminato",
        )
    }

    @Test
    fun `con l'andata e ritorno il turno sono due, e passa chi segna di piu' in totale`() {
        val competition = coppa(4, twoLegged = true)
        val turni = FixtureGenerator.knockout(competition)

        assertEquals(2, turni.size, "andata e ritorno sono due turni, o finiscono alla stessa ora")
        assertEquals(turni[0].pairings.map { it.tieId }, turni[1].pairings.map { it.tieId })

        val (stati, esiti) = gare(turni, vinceIlMinore)
        val next = CompetitionProgress.next(competition, stati, esiti)
        val finale = assertIs<CompetitionProgress.Next.Turno>(next)

        assertEquals("Finale", finale.rounds.first().label)
        assertEquals(1, finale.rounds.size, "la finale resta in gara secca")
    }

    // ---------------------------------------------------------------- il campionato

    @Test
    fun `un campionato non genera turni, finisce e basta`() {
        val campionato = Competition(
            id = CompetitionId(1),
            name = "Serie A",
            type = CompetitionType.GIRONE,
            participants = clubs(4),
        )
        val (stati, esiti) = gare(FixtureGenerator.roundRobin(campionato), vinceIlMinore)

        val next = CompetitionProgress.next(campionato, stati, esiti)
        val finita = assertIs<CompetitionProgress.Next.Finita>(next)
        assertEquals(ClubId(1), finita.winner, "chi vince sempre e' primo in classifica")
    }

    // ------------------------------------------------------- gironi + eliminazione

    private fun mondiale() = Competition(
        id = CompetitionId(9),
        name = "Mondiale",
        type = CompetitionType.GIRONI_PIU_ELIMINAZIONE,
        participants = clubs(8),
        groupCount = 2,
        qualifiersPerGroup = 2,
    )

    @Test
    fun `i gironi si rileggono dalle partite senza conoscere il sorteggio`() {
        val competition = mondiale()
        val (stati, _) = gare(FixtureGenerator.generate(competition), vinceIlMinore)

        val gruppi = CompetitionProgress.gruppiDaiRisultati(stati)
        assertEquals(2, gruppi.size)
        assertTrue(gruppi.all { it.size == 4 })
        assertEquals(8, gruppi.flatten().toSet().size, "ogni squadra in un girone solo")
    }

    @Test
    fun `finiti i gironi nasce il tabellone, e non si rigioca una partita del girone`() {
        val competition = mondiale()
        val (stati, esiti) = gare(FixtureGenerator.generate(competition), vinceIlMinore)

        val turno = assertIs<CompetitionProgress.Next.Turno>(
            CompetitionProgress.next(competition, stati, esiti),
        )

        val semifinali = turno.rounds.first()
        assertEquals("Semifinali", semifinali.label)
        assertEquals(2, semifinali.pairings.size)

        val gruppi = CompetitionProgress.gruppiDaiRisultati(stati)
        semifinali.pairings.forEach { p ->
            val insieme = gruppi.any { p.home in it && p.away in it }
            assertTrue(!insieme, "prima e seconda dello stesso girone non si rincontrano subito")
        }
    }

    @Test
    fun `il tabellone dei mondiali prosegue fino alla finale`() {
        val competition = mondiale()
        var stati = emptyList<FixtureState>()
        var esiti = emptyList<FixtureResult>()
        var rounds = FixtureGenerator.generate(competition)

        var giri = 0
        while (giri++ < 10) {
            val (nuoviStati, nuoviEsiti) = gare(rounds, vinceIlMinore)
            stati = stati + nuoviStati
            esiti = esiti + nuoviEsiti

            when (val next = CompetitionProgress.next(competition, stati, esiti)) {
                is CompetitionProgress.Next.Turno -> rounds = next.rounds
                is CompetitionProgress.Next.Finita -> {
                    assertTrue(next.winner != null, "un mondiale finito ha un vincitore")
                    return
                }
                CompetitionProgress.Next.Attendi -> error("tutto giocato, non si aspetta niente")
            }
        }
        error("il tabellone non e' mai finito")
    }
}
