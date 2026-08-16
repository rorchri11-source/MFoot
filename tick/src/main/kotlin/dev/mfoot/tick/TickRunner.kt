package dev.mfoot.tick

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.market.Auction
import dev.mfoot.core.market.AuctionRules
import dev.mfoot.core.market.AuctionTarget
import dev.mfoot.core.market.Bid
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.StaffId
import dev.mfoot.core.tick.TickEffect
import dev.mfoot.core.tick.TickInput
import dev.mfoot.core.tick.WorldTick
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

data class LeagueSummary(
    val leagueId: Long,
    val name: String,
    val planned: Int,
    val applied: Int,
    val pending: Int,
    val notes: List<String>,
)

data class TickSummary(val leagues: List<LeagueSummary>, val failures: List<String>) {
    fun describe(): String = buildString {
        if (leagues.isEmpty() && failures.isEmpty()) {
            append("Nessuna lega attiva.")
            return@buildString
        }
        leagues.forEach { l ->
            appendLine("Lega ${l.leagueId} '${l.name}': ${l.planned} effetti previsti, " +
                "${l.applied} applicati, ${l.pending} ancora da implementare")
            l.notes.forEach { appendLine("    $it") }
        }
        failures.forEach { appendLine("  FALLITA: $it") }
    }.trimEnd()
}

/**
 * Esegue un giro di tick su tutte le leghe attive.
 *
 * ## Una transazione per lega
 *
 * Ogni lega viene elaborata in una transazione a se'. Se qualcosa fallisce, quella lega
 * torna indietro per intero e `last_processed_at` non avanza: al giro successivo si
 * rifanno esattamente le stesse cose. Le altre leghe non ne risentono.
 *
 * E' il motivo per cui un'asta non puo' essere assegnata due volte: o passa tutto
 * (vincitore, addebito, liberazione degli altri, contratto) o non passa niente.
 */
class TickRunner(
    private val connection: Connection,
    private val env: TickEnvironment,
) {

    fun runAllLeagues(now: Instant): TickSummary {
        val summaries = mutableListOf<LeagueSummary>()
        val failures = mutableListOf<String>()

        for (league in loadActiveLeagues()) {
            try {
                summaries += runLeague(league, now)
                if (env.dryRun) connection.rollback() else connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                failures += "lega ${league.id} '${league.name}': ${e.message}"
                log("Lega ${league.id} annullata e riportata indietro: ${e.message}")
            }
        }
        return TickSummary(summaries, failures)
    }

    // ------------------------------------------------------------------- una lega

    private fun runLeague(league: LeagueRow, now: Instant): LeagueSummary {
        val state = loadTickState(league.id)
        val input = TickInput(
            now = now,
            lastProcessedAt = state.lastProcessedAt,
            today = MatchDay(league.currentMatchDay),
            config = league.config,
            openAuctions = loadOpenAuctions(league.id),
            settledMatchDays = state.settledMatchDays,
            lastDigestAt = state.lastDigestAt,
        )

        val plan = WorldTick.run(input)
        var applied = 0
        var pending = 0
        val notes = plan.notes.toMutableList()

        for (effect in plan.effects) {
            when (effect) {
                is TickEffect.ChiudiAsta -> {
                    closeAuction(league, effect.auctionId)
                    applied++
                }
                else -> {
                    // Gli altri effetti sono gia' pianificati correttamente dal motore:
                    // qui manca solo il codice che li scrive a database.
                    pending++
                }
            }
        }

        if (pending > 0) {
            notes += "Da implementare: " + plan.effects
                .filterNot { it is TickEffect.ChiudiAsta }
                .groupingBy { it::class.simpleName }.eachCount()
                .entries.joinToString(", ") { "${it.key} x${it.value}" }
        }

        saveTickState(league.id, plan.processedUpTo, notes.joinToString(" | "))

        return LeagueSummary(league.id, league.name, plan.effects.size, applied, pending, notes)
    }

    // -------------------------------------------------------------- chiusura asta

    /**
     * Assegna l'asta e sistema i crediti.
     *
     * Le quattro operazioni — addebito al vincitore, liberazione dei fondi di tutti i
     * partecipanti, contratto, stato dell'asta — devono avvenire insieme o per niente.
     * Se si liberassero i fondi senza addebitare, il vincitore avrebbe il giocatore
     * gratis; se si addebitasse senza liberare, gli altri resterebbero con crediti
     * bloccati per sempre senza capire perche'.
     */
    private fun closeAuction(league: LeagueRow, auctionId: Long) {
        val auction = loadAuction(auctionId) ?: return
        if (!auction.isOpen) return

        val outcome = AuctionRules.close(auction, Instant.now(), league.config.market)

        // Prima si liberano i fondi impegnati da tutti, vincitore compreso.
        for (club in outcome.clubsToRelease) {
            val committed = auction.bidOf(club)?.maxAmount ?: continue
            connection.prepareStatement(
                "update clubs set committed_credits = greatest(0, committed_credits - ?) where id = ?",
            ).use { st ->
                st.setInt(1, committed)
                st.setLong(2, club.value)
                st.executeUpdate()
            }
        }

        val winner = outcome.winner
        if (winner == null) {
            connection.prepareStatement(
                "update auctions set status = 'DESERTA' where id = ?",
            ).use { it.setLong(1, auctionId); it.executeUpdate() }
            log("Asta $auctionId deserta.")
            return
        }

        // Poi si addebita il prezzo finale al vincitore.
        connection.prepareStatement(
            "update clubs set credits = credits - ? where id = ?",
        ).use { st ->
            st.setInt(1, outcome.price)
            st.setLong(2, winner.value)
            st.executeUpdate()
        }

        when (val target = auction.target) {
            is AuctionTarget.ForPlayer -> assignPlayer(league, target.playerId, winner, outcome.price)
            is AuctionTarget.ForStaff -> assignStaff(target.staffId, winner)
        }

        connection.prepareStatement(
            "update auctions set status = 'AGGIUDICATA', winner_club_id = ?, final_price = ? where id = ?",
        ).use { st ->
            st.setLong(1, winner.value)
            st.setInt(2, outcome.price)
            st.setLong(3, auctionId)
            st.executeUpdate()
        }

        notify(league.id, winner, "Ti sei aggiudicato l'asta per ${outcome.price} crediti.")
        log("Asta $auctionId aggiudicata al club ${winner.value} per ${outcome.price}.")
    }

    private fun assignPlayer(league: LeagueRow, playerId: PlayerId, club: ClubId, price: Int) {
        val duration = league.config.market.defaultContractMatchDays
        connection.prepareStatement(
            """
            insert into contracts (league_id, player_id, club_id, signed_on, expires_on,
                                   wage_per_match_day, price_paid)
            values (?, ?, ?, ?, ?, 0, ?)
            on conflict (player_id) do update
              set club_id = excluded.club_id,
                  signed_on = excluded.signed_on,
                  expires_on = excluded.expires_on,
                  price_paid = excluded.price_paid
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setLong(2, playerId.value)
            st.setLong(3, club.value)
            st.setInt(4, league.currentMatchDay)
            st.setInt(5, league.currentMatchDay + duration)
            st.setInt(6, price)
            st.executeUpdate()
        }
    }

    private fun assignStaff(staffId: StaffId, club: ClubId) {
        connection.prepareStatement("update staff set club_id = ? where id = ?").use { st ->
            st.setLong(1, club.value)
            st.setLong(2, staffId.value)
            st.executeUpdate()
        }
    }

    // ------------------------------------------------------------------ notifiche

    /**
     * Solo le cose che richiedono una decisione con scadenza arrivano subito. Tutto il
     * resto finisce nel riepilogo giornaliero: un ping per ogni evento in una lega da
     * venticinque club porta alla disinstallazione in tre giorni.
     */
    private fun notify(leagueId: Long, club: ClubId, body: String, urgency: String = "immediata") {
        connection.prepareStatement(
            "insert into notifications (league_id, club_id, kind, urgency, body) values (?, ?, 'asta', ?, ?)",
        ).use { st ->
            st.setLong(1, leagueId)
            st.setLong(2, club.value)
            st.setString(3, urgency)
            st.setString(4, body)
            st.executeUpdate()
        }
    }

    // ------------------------------------------------------------------ caricamento

    private data class LeagueRow(
        val id: Long,
        val name: String,
        val config: LeagueConfig,
        val currentMatchDay: Int,
    )

    private data class TickStateRow(
        val lastProcessedAt: Instant?,
        val lastDigestAt: Instant?,
        val settledMatchDays: Set<Int>,
    )

    private fun loadActiveLeagues(): List<LeagueRow> {
        val out = mutableListOf<LeagueRow>()
        connection.prepareStatement(
            "select id, name, current_match_day from leagues where status in ('mercato', 'in_corso')",
        ).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out += LeagueRow(
                        id = rs.getLong("id"),
                        name = rs.getString("name"),
                        // La configurazione viene dal database ma il motore ha gia' tutti
                        // i valori di default: finche' non c'e' il parser JSON si usa
                        // quella predefinita, che e' comunque valida.
                        config = LeagueConfig(),
                        currentMatchDay = rs.getInt("current_match_day"),
                    )
                }
            }
        }
        return out
    }

    private fun loadTickState(leagueId: Long): TickStateRow {
        connection.prepareStatement(
            "select last_processed_at, last_digest_at, settled_match_days from tick_state where league_id = ?",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    val settled = (rs.getArray("settled_match_days")?.array as? Array<*>)
                        ?.mapNotNull { (it as? Number)?.toInt() }?.toSet() ?: emptySet()
                    return TickStateRow(
                        rs.getTimestamp("last_processed_at")?.toInstant(),
                        rs.getTimestamp("last_digest_at")?.toInstant(),
                        settled,
                    )
                }
            }
        }
        return TickStateRow(null, null, emptySet())
    }

    private fun saveTickState(leagueId: Long, processedUpTo: Instant, notes: String) {
        connection.prepareStatement(
            """
            insert into tick_state (league_id, last_processed_at, last_run_at, last_run_notes)
            values (?, ?, now(), ?)
            on conflict (league_id) do update
              set last_processed_at = excluded.last_processed_at,
                  last_run_at = excluded.last_run_at,
                  last_run_notes = excluded.last_run_notes
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setTimestamp(2, Timestamp.from(processedUpTo))
            st.setString(3, notes.take(2000))
            st.executeUpdate()
        }
    }

    private fun loadOpenAuctions(leagueId: Long): List<Auction> {
        val auctions = mutableListOf<Auction>()
        connection.prepareStatement(
            """
            select id, target_type, target_id, started_by, started_at, ends_at,
                   starting_price, status, extensions
            from auctions where league_id = ? and status = 'APERTA'
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs ->
                while (rs.next()) auctions += readAuction(rs)
            }
        }
        return auctions.map { it.copy(bids = loadBids(it.id)) }
    }

    private fun loadAuction(auctionId: Long): Auction? {
        connection.prepareStatement(
            """
            select id, target_type, target_id, started_by, started_at, ends_at,
                   starting_price, status, extensions
            from auctions where id = ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, auctionId)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                return readAuction(rs).copy(bids = loadBids(auctionId))
            }
        }
    }

    private fun readAuction(rs: java.sql.ResultSet): Auction = Auction(
        id = rs.getLong("id"),
        target = if (rs.getString("target_type") == "player") {
            AuctionTarget.ForPlayer(PlayerId(rs.getLong("target_id")))
        } else {
            AuctionTarget.ForStaff(StaffId(rs.getLong("target_id")))
        },
        startedBy = ClubId(rs.getLong("started_by")),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        endsAt = rs.getTimestamp("ends_at").toInstant(),
        startingPrice = rs.getInt("starting_price"),
        status = dev.mfoot.core.market.AuctionStatus.valueOf(rs.getString("status")),
        extensions = rs.getInt("extensions"),
    )

    private fun loadBids(auctionId: Long): List<Bid> {
        val bids = mutableListOf<Bid>()
        connection.prepareStatement(
            "select club_id, max_amount, placed_at from bids where auction_id = ? order by placed_at",
        ).use { st ->
            st.setLong(1, auctionId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    bids += Bid(
                        club = ClubId(rs.getLong("club_id")),
                        maxAmount = rs.getInt("max_amount"),
                        placedAt = rs.getTimestamp("placed_at").toInstant(),
                    )
                }
            }
        }
        return bids
    }
}
