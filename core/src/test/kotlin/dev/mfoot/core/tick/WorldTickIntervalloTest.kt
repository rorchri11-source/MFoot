package dev.mfoot.core.tick

import dev.mfoot.core.model.CompetitionId
import dev.mfoot.core.calendar.Fixture
import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La finestra dell'intervallo.
 *
 * ## Perche' questi test esistono prima ancora del codice che li usa
 *
 * Perche' la ripresa di una partita ferma al 45' cade **esattamente** nella trappola che
 * ha gia' prodotto il difetto piu' costoso di questo progetto (numero dieci in `STATO.md`):
 * insegnare al tick che «una cosa scaduta fuori finestra va fatta comunque» e' giusto per
 * un'asta e disastroso per una partita, perche' il calcio d'inizio resta per sempre nel
 * passato e la stessa partita verrebbe rigiocata a ogni giro.
 *
 * La ripresa somiglia a un'asta — se non avviene adesso non avverra' mai — ma vive dentro
 * una partita. Questi test fissano il confine fra i due comportamenti.
 */
class WorldTickIntervalloTest {

    private val config = ConfigPresets.sprint()
    private val adesso: Instant = Instant.parse("2026-09-01T21:00:00Z")

    private fun partita(id: Long, kickoff: Instant) = Fixture(
        id = id,
        competitionId = CompetitionId(1),
        round = 1,
        roundLabel = "1ª giornata",
        home = ClubId(1),
        away = ClubId(2),
        matchDay = MatchDay(1),
        kickoff = LocalDateTime.ofInstant(kickoff, ZoneOffset.UTC),
    )

    private fun giro(
        pendenti: List<Fixture> = emptyList(),
        inPausa: List<PausedFixture> = emptyList(),
        ultimoGiro: Instant = adesso.minus(Duration.ofMinutes(10)),
    ) = WorldTick.run(
        TickInput(
            now = adesso,
            lastProcessedAt = ultimoGiro,
            today = MatchDay(1),
            config = config,
            pendingFixtures = pendenti,
            pausedFixtures = inPausa,
        ),
    )

    @Test
    fun `una partita ferma all'intervallo riprende quando la finestra e' finita`() {
        val esito = giro(
            inPausa = listOf(
                PausedFixture(
                    partita(1, adesso.minus(Duration.ofMinutes(50))),
                    resumeAt = adesso.minus(Duration.ofMinutes(1)),
                ),
            ),
        )

        assertEquals(1, esito.effects.count { it is TickEffect.RiprendiPartita })
    }

    @Test
    fun `mentre la finestra e' aperta non riprende`() {
        val esito = giro(
            inPausa = listOf(
                PausedFixture(
                    partita(1, adesso.minus(Duration.ofMinutes(46))),
                    // Mancano ancora due minuti ai cambi.
                    resumeAt = adesso.plus(Duration.ofMinutes(2)),
                ),
            ),
        )

        assertTrue(
            esito.effects.none { it is TickEffect.RiprendiPartita },
            "ha ripreso la partita mentre il manager stava ancora facendo i cambi",
        )
    }

    /**
     * Il caso che vale per le aste e **non** per il primo tempo.
     *
     * Una ripresa saltata non torna: la partita resterebbe a meta' per sempre, con il
     * risultato del primo tempo e nessun modo di chiuderla. Qui recuperare e' obbligatorio,
     * ed e' sicuro — appena il secondo tempo e' andato la partita e' `played` e non compare
     * piu' fra quelle in pausa.
     */
    @Test
    fun `una ripresa dimenticata da ore si recupera lo stesso`() {
        val esito = giro(
            inPausa = listOf(
                PausedFixture(
                    partita(1, adesso.minus(Duration.ofHours(6))),
                    resumeAt = adesso.minus(Duration.ofHours(5)),
                ),
            ),
            ultimoGiro = adesso.minus(Duration.ofMinutes(10)),
        )

        assertEquals(
            1, esito.effects.count { it is TickEffect.RiprendiPartita },
            "una partita ferma all'intervallo da cinque ore non e' stata ripresa",
        )
    }

    /**
     * E il confine opposto, che e' la meta' importante.
     *
     * Il primo tempo si gioca **solo** se il calcio d'inizio cade nella finestra. Se
     * qualcuno un giorno facesse recuperare anche quello «per simmetria», tornerebbe il
     * difetto numero dieci: la stessa partita rigiocata a ogni giro, per sempre.
     */
    @Test
    fun `il primo tempo di una partita vecchia non si recupera`() {
        val esito = giro(
            pendenti = listOf(partita(2, adesso.minus(Duration.ofHours(6)))),
            ultimoGiro = adesso.minus(Duration.ofMinutes(10)),
        )

        assertTrue(
            esito.effects.none { it is TickEffect.SimulaPartita },
            "ha rigiocato una partita il cui calcio d'inizio era fuori finestra",
        )
    }

    @Test
    fun `una partita in pausa non viene anche simulata da capo`() {
        val ferma = partita(3, adesso.minus(Duration.ofMinutes(3)))
        val esito = giro(
            // Il chiamante non la mette fra le pendenti: e' gia' cominciata.
            inPausa = listOf(PausedFixture(ferma, resumeAt = adesso.minus(Duration.ofSeconds(30)))),
        )

        assertTrue(esito.effects.none { it is TickEffect.SimulaPartita })
        assertEquals(1, esito.effects.count { it is TickEffect.RiprendiPartita })
    }

    @Test
    fun `senza partite in pausa non succede niente`() {
        assertTrue(giro().effects.none { it is TickEffect.RiprendiPartita })
    }
}
