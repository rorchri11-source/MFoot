package dev.mfoot.core.tick

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.market.Auction
import dev.mfoot.core.market.AuctionStatus
import dev.mfoot.core.market.AuctionTarget
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.PlayerId
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cosa succede a quello che era in scadenza e non e' stato fatto.
 *
 * ## Il difetto che questi test difendono
 *
 * La finestra del tick e' `(ultimo giro, adesso]`. Un'asta scaduta **prima** dell'ultimo
 * giro ne restava fuori, e siccome `last_processed_at` avanza comunque a fine giro, quel
 * "fuori" era definitivo: l'asta non si chiudeva piu'. Se ne accumulavano — sessantasette
 * aperte insieme — e ognuna sembrava durare un giorno.
 *
 * Il risveglio delle AI aveva gia' la protezione. Le aste, le partite e le trattative no.
 */
class WorldTickRecuperoTest {

    private val config = ConfigPresets.sprint()
    private val adesso: Instant = Instant.parse("2026-09-01T20:00:00Z")

    private fun asta(id: Long, scadeA: Instant) = Auction(
        id = id,
        target = AuctionTarget.ForPlayer(PlayerId(id)),
        startedBy = ClubId(1),
        startedAt = scadeA.minus(Duration.ofMinutes(15)),
        endsAt = scadeA,
        startingPrice = 1,
        status = AuctionStatus.APERTA,
    )

    private fun giro(aste: List<Auction>, ultimoGiro: Instant) = WorldTick.run(
        TickInput(
            now = adesso,
            lastProcessedAt = ultimoGiro,
            today = MatchDay(1),
            config = config,
            openAuctions = aste,
        ),
    )

    @Test
    fun `un'asta scaduta dentro la finestra si chiude`() {
        val esito = giro(
            listOf(asta(1, adesso.minus(Duration.ofMinutes(2)))),
            ultimoGiro = adesso.minus(Duration.ofMinutes(5)),
        )

        assertTrue(esito.effects.any { it is TickEffect.ChiudiAsta })
    }

    @Test
    fun `un'asta scaduta prima dell'ultimo giro si chiude lo stesso`() {
        // E' il caso che restava fuori. Se ne accumulavano finche' il listino non era
        // pieno di aste che nessuno chiudeva piu'.
        val esito = giro(
            listOf(asta(2, adesso.minus(Duration.ofHours(30)))),
            ultimoGiro = adesso.minus(Duration.ofMinutes(5)),
        )

        assertTrue(
            esito.effects.any { it is TickEffect.ChiudiAsta },
            "un'asta scaduta ieri e' rimasta aperta: nessun giro futuro la chiudera' mai",
        )
    }

    @Test
    fun `un'asta che deve ancora scadere non si tocca`() {
        val esito = giro(
            listOf(asta(3, adesso.plus(Duration.ofMinutes(10)))),
            ultimoGiro = adesso.minus(Duration.ofMinutes(5)),
        )

        assertTrue(
            esito.effects.none { it is TickEffect.ChiudiAsta },
            "aggiudicata un'asta ancora aperta",
        )
    }

    @Test
    fun `recuperando un intervallo perso non si chiude chi scade dopo`() {
        // `now` puo' essere nel passato quando si recupera: chiudere un'asta che scade
        // dopo quel momento vorrebbe dire aggiudicarla prima del tempo.
        val esito = WorldTick.run(
            TickInput(
                now = adesso.minus(Duration.ofHours(2)),
                lastProcessedAt = adesso.minus(Duration.ofHours(3)),
                today = MatchDay(1),
                config = config,
                openAuctions = listOf(asta(4, adesso.minus(Duration.ofMinutes(30)))),
            ),
        )

        assertTrue(esito.effects.none { it is TickEffect.ChiudiAsta })
    }
}
