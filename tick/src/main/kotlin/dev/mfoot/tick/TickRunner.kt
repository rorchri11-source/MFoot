package dev.mfoot.tick

import dev.mfoot.core.config.ConfigJson
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.market.Auction
import dev.mfoot.core.market.AuctionRules
import dev.mfoot.core.market.AuctionTarget
import dev.mfoot.core.market.Bid
import dev.mfoot.core.market.Negotiation
import dev.mfoot.core.market.OfferStatus
import dev.mfoot.core.market.OfferTerms
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Contract
import dev.mfoot.core.model.Loan
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
        val today = MatchDay(league.currentMatchDay)
        val input = TickInput(
            now = now,
            lastProcessedAt = state.lastProcessedAt,
            today = today,
            config = league.config,
            openAuctions = loadOpenAuctions(league.id),
            // Senza queste liste il pianificatore era cieco: contratti e prestiti non
            // scadevano mai, e le trattative restavano aperte per sempre. Il codice per
            // applicarli non era mai stato raggiunto perche' non veniva nemmeno pianificato.
            openNegotiations = loadOpenNegotiations(league.id),
            activeContracts = loadExpiringContracts(league.id, today),
            activeLoans = loadExpiringLoans(league.id, today),
            settledMatchDays = state.settledMatchDays,
            lastDigestAt = state.lastDigestAt,
        )

        val plan = WorldTick.run(input)
        var applied = 0
        var pending = 0
        val notes = plan.notes.toMutableList()

        var settled: MatchDay? = null

        for (effect in plan.effects) {
            when (effect) {
                is TickEffect.ChiudiAsta -> {
                    closeAuction(league, effect.auctionId)
                    applied++
                }

                is TickEffect.ScadiContratto -> {
                    expireContract(league, effect.playerId, effect.clubId)
                    applied++
                }

                is TickEffect.RestituisciPrestito -> {
                    returnLoan(league, effect.loan)
                    applied++
                }

                is TickEffect.ScadiTrattativa -> {
                    expireNegotiation(effect.negotiationId)
                    applied++
                }

                is TickEffect.DistribuisciCrediti -> {
                    distributeIncome(league, effect.amount)
                    settled = effect.matchDay
                    applied++
                }

                is TickEffect.PagaStipendi -> {
                    payWages(league)
                    settled = effect.matchDay
                    applied++
                }

                is TickEffect.RecuperaStamina -> {
                    recoverStamina(league)
                    settled = effect.matchDay
                    applied++
                }

                else -> {
                    // Restano le partite, il risveglio delle AI e la verifica delle
                    // promesse: pianificati correttamente, ancora da scrivere a database.
                    pending++
                }
            }
        }

        if (pending > 0) {
            notes += "Da implementare: " + plan.effects
                .filter { it is TickEffect.SimulaPartita || it is TickEffect.SvegliaAi ||
                    it is TickEffect.VerificaPromesse || it is TickEffect.InviaRiepilogo }
                .groupingBy { it::class.simpleName }.eachCount()
                .entries.joinToString(", ") { "${it.key} x${it.value}" }
        }

        saveTickState(league.id, plan.processedUpTo, notes.joinToString(" | "), settled)

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

    // --------------------------------------------------------- scadenze e movimenti

    /**
     * Un contratto scaduto libera il giocatore.
     *
     * Il giocatore custom fa eccezione e non se ne va mai: e' l'unico che non si puo'
     * vendere ne' svincolare. Lasciarlo scadere significherebbe che il proprietario apre
     * l'app e scopre che il giocatore che si e' costruito e' finito sul mercato di tutti,
     * il che sarebbe il modo piu' rapido di far smettere qualcuno di giocare.
     */
    private fun expireContract(league: LeagueRow, playerId: PlayerId, clubId: ClubId) {
        val isCustom = connection.prepareStatement(
            "select is_custom from players where id = ?",
        ).use { st ->
            st.setLong(1, playerId.value)
            st.executeQuery().use { rs -> rs.next() && rs.getBoolean("is_custom") }
        }

        if (isCustom) {
            // Si rinnova d'ufficio, gratis: non e' merce, e' il giocatore di qualcuno.
            connection.prepareStatement(
                "update contracts set signed_on = ?, expires_on = ? where player_id = ?",
            ).use { st ->
                st.setInt(1, league.currentMatchDay)
                st.setInt(2, league.currentMatchDay + league.config.market.defaultContractMatchDays)
                st.setLong(3, playerId.value)
                st.executeUpdate()
            }
            return
        }

        connection.prepareStatement("delete from contracts where player_id = ?").use { st ->
            st.setLong(1, playerId.value)
            st.executeUpdate()
        }

        notify(
            league.id, clubId,
            "Un contratto e' scaduto: il giocatore e' tornato svincolato.",
            kind = "contratto",
            urgency = "riepilogo",
        )
    }

    /** Il prestito finisce: il giocatore torna a chi lo possiede davvero. */
    private fun returnLoan(league: LeagueRow, loan: dev.mfoot.core.model.Loan) {
        connection.prepareStatement(
            "update loans set active = false where player_id = ? and active",
        ).use { st ->
            st.setLong(1, loan.playerId.value)
            st.executeUpdate()
        }

        connection.prepareStatement(
            "update contracts set club_id = ? where player_id = ?",
        ).use { st ->
            st.setLong(1, loan.ownerClub.value)
            st.setLong(2, loan.playerId.value)
            st.executeUpdate()
        }

        notify(league.id, loan.borrowerClub, "Un prestito e' finito: il giocatore e' tornato al suo club.",
            kind = "prestito", urgency = "riepilogo")
        notify(league.id, loan.ownerClub, "Ti e' tornato un giocatore dal prestito.",
            kind = "prestito", urgency = "riepilogo")
    }

    private fun expireNegotiation(negotiationId: Long) {
        connection.prepareStatement(
            "update negotiations set status = 'SCADUTA' where id = ? and status in ('IN_ATTESA', 'CONTROPROPOSTA')",
        ).use { st ->
            st.setLong(1, negotiationId)
            st.executeUpdate()
        }
    }

    // ------------------------------------------------------------------- economia

    /**
     * Le entrate ricorrenti.
     *
     * Vanno a tutti i club, AI comprese: un'AI senza entrate resterebbe indietro di
     * giornata in giornata e dopo una settimana sarebbe un avversario finto.
     */
    private fun distributeIncome(league: LeagueRow, amount: Int) {
        if (amount <= 0) return
        connection.prepareStatement(
            "update clubs set credits = credits + ? where league_id = ?",
        ).use { st ->
            st.setInt(1, amount)
            st.setLong(2, league.id)
            st.executeUpdate()
        }
    }

    /**
     * Gli stipendi.
     *
     * Si calcolano dall'overall con la formula della configurazione, in una query sola:
     * portarsi in memoria tutte le rose di venti club per moltiplicare due numeri
     * sarebbe lavoro sprecato ogni cinque minuti, per sempre.
     *
     * Il saldo puo' andare sotto zero solo se l'admin lo consente. Altrimenti si ferma a
     * zero: un club in rosso perpetuo non potrebbe piu' fare niente e la sua stagione
     * finirebbe li' senza che nessuno gliel'abbia detto.
     */
    private fun payWages(league: LeagueRow) {
        val economy = league.config.economy
        if (!economy.wagesEnabled) return

        val floor = if (economy.negativeBalanceAllowed) "" else "greatest(0, "
        val close = if (economy.negativeBalanceAllowed) "" else ")"

        connection.prepareStatement(
            """
            update clubs c
            set credits = ${floor}c.credits - coalesce((
                select sum(greatest(1, round(p.overall * p.overall * ?)))
                from contracts ct join players p on p.id = ct.player_id
                where ct.club_id = c.id
            ), 0)$close
            where c.league_id = ?
            """.trimIndent(),
        ).use { st ->
            st.setDouble(1, economy.wageFactor)
            st.setLong(2, league.id)
            st.executeUpdate()
        }
    }

    /**
     * Il recupero di fine giornata.
     *
     * Il moltiplicatore del preparatore e' il motivo per cui lo staff a stelle conta:
     * con due partite al giorno, chi ha un cinque stelle rimette in campo la stessa
     * squadra e chi non ce l'ha deve ruotare. La formula sta in `core` ([StaminaEngine]),
     * ma applicarla giocatore per giocatore vorrebbe dire leggere e riscrivere qualche
     * migliaio di righe: qui si traduce nella stessa curva, in una query.
     */
    private fun recoverStamina(league: LeagueRow) {
        val base = league.config.engine.staminaRecoveryPerMatchDay

        connection.prepareStatement(
            """
            update players p
            set stamina = least(100, p.stamina + greatest(1, round(? * coalesce((
                select case max(s.stars)
                    when 5 then 1.35 when 4 then 1.2 when 3 then 1.0
                    when 2 then 0.9 else 0.8 end
                from staff s
                join contracts ct on ct.club_id = s.club_id
                where s.club_id = (select club_id from contracts where player_id = p.id)
                  and s.role = 'PREPARATORE'
            ), 0.8) * case
                when p.age <= 23 then 1.15
                when p.age >= 31 then 0.85
                else 1.0 end)::integer))
            where p.league_id = ? and p.stamina < 100
            """.trimIndent(),
        ).use { st ->
            st.setDouble(1, base)
            st.setLong(2, league.id)
            st.executeUpdate()
        }
    }

    // ------------------------------------------------------------------ notifiche

    /**
     * Solo le cose che richiedono una decisione con scadenza arrivano subito. Tutto il
     * resto finisce nel riepilogo giornaliero: un ping per ogni evento in una lega da
     * venticinque club porta alla disinstallazione in tre giorni.
     */
    private fun notify(
        leagueId: Long,
        club: ClubId,
        body: String,
        kind: String = "asta",
        urgency: String = "immediata",
    ) {
        connection.prepareStatement(
            "insert into notifications (league_id, club_id, kind, urgency, body) values (?, ?, ?, ?, ?)",
        ).use { st ->
            st.setLong(1, leagueId)
            st.setLong(2, club.value)
            st.setString(3, kind)
            st.setString(4, urgency)
            st.setString(5, body)
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
            "select id, name, current_match_day, config from leagues " +
                "where status in ('mercato', 'in_corso')",
        ).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val raw = rs.getString("config")
                    out += LeagueRow(
                        id = rs.getLong("id"),
                        name = rs.getString("name"),
                        // Le regole della lega le ha decise l'admin e stanno nel database.
                        // Usare i valori predefiniti — come faceva questo codice — voleva
                        // dire far girare il mondo con regole diverse da quelle scelte,
                        // in silenzio: stipendi, durate dei contratti, cadenza delle
                        // entrate, tutto sbagliato senza un solo errore a segnalarlo.
                        config = runCatching { ConfigJson.read(raw.orEmpty()) }
                            .getOrElse { LeagueConfig() },
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

    /**
     * @param settled la giornata appena liquidata, se ne e' stata liquidata una.
     *
     * Registrarla e' cio' che impedisce di pagare gli stipendi due volte. Il tick puo'
     * rigirare sulla stessa finestra — succede ogni volta che una transazione fallisce a
     * meta' — e senza questo elenco ogni ripasso ripeterebbe l'addebito.
     */
    private fun saveTickState(
        leagueId: Long,
        processedUpTo: Instant,
        notes: String,
        settled: MatchDay?,
    ) {
        connection.prepareStatement(
            """
            insert into tick_state (league_id, last_processed_at, last_run_at, last_run_notes,
                                    settled_match_days)
            values (?, ?, now(), ?, case when ?::integer is null then '{}'::integer[]
                                         else array[?::integer] end)
            on conflict (league_id) do update
              set last_processed_at = excluded.last_processed_at,
                  last_run_at = excluded.last_run_at,
                  last_run_notes = excluded.last_run_notes,
                  settled_match_days = case
                      when ?::integer is null then tick_state.settled_match_days
                      else array(select distinct unnest(
                              tick_state.settled_match_days || array[?::integer]))
                  end
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setTimestamp(2, Timestamp.from(processedUpTo))
            st.setString(3, notes.take(2000))
            listOf(4, 5, 6, 7).forEach { index ->
                if (settled == null) st.setNull(index, java.sql.Types.INTEGER)
                else st.setInt(index, settled.value)
            }
            st.executeUpdate()
        }
    }

    // ---------------------------------------------------- cosa sta per scadere

    /**
     * Solo i contratti gia' scaduti, non tutti.
     *
     * Il pianificatore ha bisogno di sapere cosa e' in scadenza, non di avere in mano
     * ogni contratto della lega: con venti club e rose da trenta sarebbero seicento
     * righe lette ogni cinque minuti per scartarne quasi tutte.
     */
    private fun loadExpiringContracts(leagueId: Long, today: MatchDay): List<Contract> {
        val out = mutableListOf<Contract>()
        connection.prepareStatement(
            """
            select player_id, club_id, signed_on, expires_on, wage_per_match_day,
                   price_paid, release_clause
            from contracts where league_id = ? and expires_on <= ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setInt(2, today.value)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out += Contract(
                        playerId = PlayerId(rs.getLong("player_id")),
                        clubId = ClubId(rs.getLong("club_id")),
                        signedOn = MatchDay(rs.getInt("signed_on")),
                        expiresOn = MatchDay(rs.getInt("expires_on")),
                        wagePerMatchDay = rs.getInt("wage_per_match_day"),
                        pricePaid = rs.getInt("price_paid"),
                        releaseClause = rs.getInt("release_clause").takeIf { !rs.wasNull() },
                    )
                }
            }
        }
        return out
    }

    private fun loadExpiringLoans(leagueId: Long, today: MatchDay): List<Loan> {
        val out = mutableListOf<Loan>()
        connection.prepareStatement(
            """
            select player_id, owner_club_id, borrower_club_id, starts_on, ends_on,
                   fee_per_match_day, wage_paid_by_borrower, can_play_against_owner, recallable
            from loans where league_id = ? and active and ends_on <= ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setInt(2, today.value)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out += Loan(
                        playerId = PlayerId(rs.getLong("player_id")),
                        ownerClub = ClubId(rs.getLong("owner_club_id")),
                        borrowerClub = ClubId(rs.getLong("borrower_club_id")),
                        startsOn = MatchDay(rs.getInt("starts_on")),
                        endsOn = MatchDay(rs.getInt("ends_on")),
                        feePerMatchDay = rs.getInt("fee_per_match_day"),
                        wagePaidByBorrower = rs.getBoolean("wage_paid_by_borrower"),
                        canPlayAgainstOwner = rs.getBoolean("can_play_against_owner"),
                        recallable = rs.getBoolean("recallable"),
                    )
                }
            }
        }
        return out
    }

    private fun loadOpenNegotiations(leagueId: Long): List<Negotiation> {
        val out = mutableListOf<Negotiation>()
        connection.prepareStatement(
            """
            select id, player_id, buyer_club_id, seller_club_id, awaiting_club_id,
                   expires_at, status
            from negotiations
            where league_id = ? and status in ('IN_ATTESA', 'CONTROPROPOSTA')
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out += Negotiation(
                        id = rs.getLong("id"),
                        playerId = PlayerId(rs.getLong("player_id")),
                        buyer = ClubId(rs.getLong("buyer_club_id")),
                        seller = ClubId(rs.getLong("seller_club_id")),
                        // Le condizioni non servono per farla scadere: qui interessa solo
                        // *quando* scade. Leggerle vorrebbe dire deserializzare un jsonb
                        // per buttarlo via.
                        terms = OfferTerms(credits = 0, contractMatchDays = 1),
                        awaiting = ClubId(rs.getLong("awaiting_club_id")),
                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        status = OfferStatus.valueOf(rs.getString("status")),
                    )
                }
            }
        }
        return out
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
