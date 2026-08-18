package dev.mfoot.tick

import dev.mfoot.core.ai.AiInitiative
import dev.mfoot.core.ai.AiManager
import dev.mfoot.core.ai.AiMove
import dev.mfoot.core.ai.AiTurn
import dev.mfoot.core.ai.SquadAction
import dev.mfoot.core.ai.AiObsession
import dev.mfoot.core.ai.AiPersonality
import dev.mfoot.core.ai.AiScheduler
import dev.mfoot.core.ai.AiState
import dev.mfoot.core.calendar.CalendarSolver
import dev.mfoot.core.calendar.Competition
import dev.mfoot.core.calendar.CompetitionType
import dev.mfoot.core.calendar.Fixture
import dev.mfoot.core.calendar.FixtureGenerator
import dev.mfoot.core.config.ConfigJson
import dev.mfoot.core.conversation.AppearanceFact
import dev.mfoot.core.conversation.ConversationEngine
import dev.mfoot.core.conversation.ConversationTopic
import dev.mfoot.core.conversation.ConversationTrigger
import dev.mfoot.core.conversation.LeagueFacts
import dev.mfoot.core.conversation.PlayerHistory
import dev.mfoot.core.conversation.Promise
import dev.mfoot.core.conversation.PromiseStatus
import dev.mfoot.core.conversation.PromiseType
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.growth.GrowthContext
import dev.mfoot.core.growth.GrowthEngine
import dev.mfoot.core.growth.MoraleEngine
import dev.mfoot.core.growth.TeamOutcome
import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.market.Auction
import dev.mfoot.core.market.AuctionRules
import dev.mfoot.core.market.AuctionTarget
import dev.mfoot.core.market.Bid
import dev.mfoot.core.market.Negotiation
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.market.OfferStatus
import dev.mfoot.core.market.OfferTerms
import dev.mfoot.core.market.TradeEvaluator
import dev.mfoot.core.market.TradeOffer
import dev.mfoot.core.match.AutoLineup
import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.Lineup
import dev.mfoot.core.match.LineupFitter
import dev.mfoot.core.match.LineupSlot
import dev.mfoot.core.match.MatchEngine
import dev.mfoot.core.match.MatchResult
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.match.TeamSetup
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.Club
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.CompetitionId
import dev.mfoot.core.model.Contract
import dev.mfoot.core.model.Loan
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.StaffId
import dev.mfoot.core.model.Trait
import dev.mfoot.core.tick.TickEffect
import dev.mfoot.core.tick.TickInput
import dev.mfoot.core.tick.WorldTick
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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
    private val notifier: Notifier = Notifier(env),
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
            pendingFixtures = loadPendingFixtures(league.id),
            aiStates = loadAiStates(league.id),
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

                is TickEffect.SimulaPartita -> {
                    if (playMatch(league, effect.fixture, notes)) {
                        applied++
                    } else {
                        // La partita resta da giocare: al prossimo giro ci si riprova.
                        // Meglio un rinvio che un risultato inventato con nove uomini.
                        pending++
                        notes += "Partita ${effect.fixture.id} rinviata: rosa insufficiente."
                    }
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

                is TickEffect.SvegliaAi -> {
                    wakeAi(league, effect.clubId, now, today)
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
                    // Resta il riepilogo periodico da mandare ai proprietari: pianificato
                    // correttamente, ancora da scrivere. Le promesse non passano piu' di
                    // qui — si controllano in fondo al giro, dopo le partite, perche' e'
                    // l'ultima partita a poterne completare una.
                    pending++
                }
            }
        }

        // Il riepilogo era l'ultimo effetto pianificato e mai applicato. Adesso parte.
        val riepilogo = plan.effects.filterIsInstance<TickEffect.InviaRiepilogo>().isNotEmpty()

        // Le proposte di scambio si guardano a ogni giro, non al risveglio dell'AI.
        //
        // Il risveglio scaglionato serve a impedire lo sciame sul mercato: e' una difesa
        // contro venti club che si buttano sullo stesso giocatore. Una proposta di scambio
        // e' l'opposto — arriva **da una persona, a un club solo** — e farla aspettare fino
        // a domani mattina perche' quel club dorme non protegge nessuno: fa solo credere
        // che l'avversario ti stia ignorando.
        notes += rispondiAgliScambi(league)

        // Le promesse si controllano dopo aver giocato, non prima: e' l'ultima partita del
        // giro a poter completare un "titolare per tre partite", e rimandare al giro
        // successivo vorrebbe dire dichiarare tradita una promessa appena mantenuta.
        notes += verificaLePromesse(league)

        // I colloqui si aprono per ultimi, dopo le partite e dopo le promesse: e' l'unico
        // momento in cui i fatti della giornata sono tutti scritti. Aprirli prima
        // significherebbe dire a un giocatore "hai giocato male" prima di sapere come ha
        // giocato, e non dirgli niente della promessa che gli hai appena tradito.
        notes += apriIColloqui(league)

        // I club del computer rispondono subito ai propri: aspettare non avrebbe senso,
        // e un colloquio aperto blocca quello successivo.
        notes += rispondiAiColloqui(league)

        // La consegna per ultima, quando tutto quello che e' successo in questo giro e'
        // gia' scritto: cosi' una notifica non puo' raccontare un fatto che la transazione
        // annullera' un attimo dopo.
        notes += consegnaLeNotifiche(league, riepilogo)

        saveTickState(league.id, plan.processedUpTo, notes.joinToString(" | "), settled)

        return LeagueSummary(league.id, league.name, plan.effects.size, applied, pending, notes)
    }

    // ----------------------------------------------------------------------- promesse

    /**
     * Controlla le promesse aperte, e le chiude quando c'e' da chiuderle.
     *
     * ## Perche' questo pezzo e' il senso di tutto il resto
     *
     * Senza, promettere il posto da titolare sarebbe un pulsante gratuito: alza il morale,
     * non costa niente, si preme su tutta la rosa. La conseguenza — molto peggiore
     * dell'aumento se non la mantieni — e' cio' che rende la promessa una decisione, e puo'
     * arrivare solo da qui: da qualcuno che conta le partite anche quando i telefoni sono
     * spenti.
     *
     * Le tre operazioni per ogni promessa — avanzare, valutare, chiudere — non si possono
     * separare: una promessa avanzata e non valutata resterebbe aperta oltre la scadenza, e
     * al giro dopo verrebbe dichiarata tradita per un ritardo del server invece che per una
     * scelta del manager.
     */
    private fun verificaLePromesse(league: LeagueRow): List<String> {
        val aperte = loadOpenPromises(league.id)
        if (aperte.isEmpty()) return emptyList()

        val oggi = MatchDay(league.currentMatchDay)
        val note = mutableListOf<String>()

        for ((id, promessa, clubId) in aperte) {
            val fatte = partiteDaTitolare(promessa, clubId)
            val avanzata = promessa.copy(progress = fatte)
            if (avanzata.progress != promessa.progress) salvaProgresso(id, avanzata.progress)

            val stato = ConversationEngine.status(avanzata, oggi)
            if (stato == PromiseStatus.IN_CORSO) continue

            val player = loadPlayerRow(promessa.playerId)?.player ?: continue
            val esito = ConversationEngine.closePromise(player, stato)

            salvaMorale(promessa.playerId, esito.player.morale)
            chiudiPromessa(id, stato.name)

            note += "${player.shortName}: promessa ${stato.name.lowercase()} " +
                "(${if (esito.moraleDelta >= 0) "+" else ""}${esito.moraleDelta} morale)."
        }
        return note
    }

    private fun loadOpenPromises(leagueId: Long): List<Triple<Long, Promise, ClubId>> {
        val out = mutableListOf<Triple<Long, Promise, ClubId>>()
        connection.prepareStatement(
            "select id, club_id, player_id, type, made_on, deadline, target, progress " +
                "from promises where league_id = ? and status = 'IN_CORSO'",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out += Triple(
                        rs.getLong("id"),
                        Promise(
                            playerId = PlayerId(rs.getLong("player_id")),
                            type = runCatching { PromiseType.valueOf(rs.getString("type")) }
                                .getOrDefault(PromiseType.TITOLARE_PER_PARTITE),
                            madeOn = MatchDay(rs.getInt("made_on")),
                            deadline = MatchDay(rs.getInt("deadline")),
                            target = rs.getInt("target"),
                            progress = rs.getInt("progress"),
                        ),
                        ClubId(rs.getLong("club_id")),
                    )
                }
            }
        }
        return out
    }

    /**
     * E' sceso in campo dal primo minuto, nell'ultima partita giocata dal suo club?
     *
     * Si guarda la formazione salvata e non i minuti giocati, perche' i tabellini per
     * giocatore non vengono conservati: quello che il gioco sa e' chi era negli undici, ed
     * e' anche esattamente cio' che era stato promesso.
     */
    /**
     * Quante partite da titolare ha giocato da quando gliel'hai promesso.
     *
     * ## Perche' si conta invece di incrementare
     *
     * La versione precedente faceva due cose sbagliate. Leggeva `lineups`, che tiene la
     * formazione **attuale** e non quella con cui si e' giocato: cambiavi undici dopo la
     * partita e il conto cambiava con te. E incrementava un contatore a ogni giro del
     * tick — che passa ogni cinque minuti — quindi una promessa da tre partite si
     * chiudeva in un quarto d'ora, senza che si giocasse niente. Il sistema che doveva
     * rendere costoso promettere era il modo piu' rapido di alzare il morale gratis.
     *
     * Contare le presenze rende l'operazione idempotente per costruzione: eseguirla mille
     * volte da' mille volte lo stesso numero, perche' la risposta sta nei fatti e non in
     * quante volte si e' guardato.
     *
     * `made_on` e' esclusa: la partita gia' giocata quando la promessa e' stata fatta non
     * puo' valere come partita promessa.
     */
    private fun partiteDaTitolare(promessa: Promise, club: ClubId): Int =
        connection.prepareStatement(
            """
            select count(*) from appearances
            where player_id = ? and club_id = ? and started
              and match_day > ? and match_day <= ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, promessa.playerId.value)
            st.setLong(2, club.value)
            st.setInt(3, promessa.madeOn.value)
            st.setInt(4, promessa.deadline.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun salvaProgresso(id: Long, progress: Int) {
        connection.prepareStatement("update promises set progress = ? where id = ?").use { st ->
            st.setInt(1, progress)
            st.setLong(2, id)
            st.executeUpdate()
        }
    }

    private fun chiudiPromessa(id: Long, stato: String) {
        connection.prepareStatement(
            "update promises set status = ?, closed_at = now() where id = ?",
        ).use { st ->
            st.setString(1, stato)
            st.setLong(2, id)
            st.executeUpdate()
        }
    }

    private fun salvaMorale(player: PlayerId, morale: Int) {
        connection.prepareStatement("update players set morale = ? where id = ?").use { st ->
            st.setInt(1, morale.coerceIn(0, 100))
            st.setLong(2, player.value)
            st.executeUpdate()
        }
    }

    // ---------------------------------------------------------------------- notifiche

    /**
     * Consegna quello che e' successo.
     *
     * ## Perche' due canali e non uno
     *
     * Le **immediate** partono subito e da sole: un'asta che chiude, uno scambio che ti
     * arriva, una promessa tradita. Sono cose a cui si puo' voler rispondere, e un'ora
     * dopo non servono piu'.
     *
     * Il **riepilogo** raccoglie tutto il resto in un messaggio solo, una volta al giorno
     * all'ora scelta dall'admin. E' la meta' del sistema che protegge dalla disinstallazione:
     * con venti club che si muovono, un messaggio per evento vuol dire cinquanta messaggi
     * al giorno e un gruppo silenziato entro mercoledi'.
     *
     * ## Perche' si segna consegnato solo se e' partito
     *
     * Perche' se Telegram non risponde la riga deve restare li' e riprovarci. Il tick
     * ripassa fra cinque minuti: non c'e' niente da recuperare a mano, e una notifica in
     * ritardo di cinque minuti e' incomparabilmente meglio di una persa.
     */
    private fun consegnaLeNotifiche(league: LeagueRow, riepilogo: Boolean): List<String> {
        if (!notifier.enabled) return emptyList()
        if (env.dryRun) return emptyList()

        val note = mutableListOf<String>()

        if (league.config.notifications.immediateEnabled) {
            val immediate = caricaDaConsegnare(league.id, "immediata", limite = 20)
            for (riga in immediate) {
                if (!notifier.send("<b>${escapeHtml(league.name)}</b>\n${riga.perIlGruppo()}")) break
                segnaConsegnata(riga.id)
                note += "notifica mandata"
            }
        }

        if (!riepilogo) return note

        val arretrate = caricaDaConsegnare(league.id, "riepilogo", limite = 60)
        if (arretrate.isEmpty()) {
            // Anche senza niente da dire il riepilogo si segna come fatto, altrimenti il
            // pianificatore lo riproverebbe a ogni giro fino a mezzanotte.
            segnaRiepilogoInviato(league.id)
            return note
        }

        val corpo = buildString {
            append("<b>${escapeHtml(league.name)}</b> — riepilogo\n")
            arretrate.forEach { append("\n• ${it.perIlGruppo()}") }
        }

        if (notifier.send(corpo)) {
            arretrate.forEach { segnaConsegnata(it.id) }
            segnaRiepilogoInviato(league.id)
            note += "riepilogo mandato (${arretrate.size} righe)"
        }
        return note
    }

    private inner class NotificaDaMandare(
        val id: Long,
        val kind: String,
        val body: String,
        val clubName: String?,
    ) {
        /**
         * Come si scrive questa notizia in un gruppo dove leggono tutti.
         *
         * ## Perche' non si manda il testo com'e'
         *
         * Perche' le Row Level Security nascondono le trattative altrui **di proposito**:
         * sapere che il Montesole ha offerto trenta milioni per il tuo centravanti e'
         * un'informazione di mercato che vale, e in una lega fra amici uno sguardo alle
         * trattative degli altri rovina il gioco piu' di qualunque squilibrio di bilancio.
         *
         * Mandare quel testo nel gruppo di Telegram butterebbe via quella protezione dalla
         * porta di servizio: il database custodisce il segreto e il bot lo racconta a tutti.
         *
         * Per le trattative il gruppo riceve quindi solo **un colpetto sulla spalla** — c'e'
         * qualcosa da leggere, e chi di dovere sa cos'e'. Tutto il resto — aste, risultati,
         * scadenze — e' gia' pubblico dentro la lega e passa com'e'.
         */
        fun perIlGruppo(): String {
            val chi = clubName?.let { "<b>${escapeHtml(it)}</b>: " } ?: ""
            return if (kind in RISERVATE) {
                chi + "hai una proposta da leggere in Trattative."
            } else {
                chi + escapeHtml(body)
            }
        }
    }

    /** I tipi il cui contenuto non deve finire in un gruppo dove leggono tutti. */
    private val RISERVATE = setOf("scambio", "prestito", "amichevole")

    private fun caricaDaConsegnare(
        leagueId: Long,
        urgency: String,
        limite: Int,
    ): List<NotificaDaMandare> {
        val out = mutableListOf<NotificaDaMandare>()
        connection.prepareStatement(
            """
            select n.id, n.kind, n.body, c.name as club_name
            from notifications n
            left join clubs c on c.id = n.club_id
            where n.league_id = ? and n.urgency = ? and not n.delivered
            order by n.created_at limit ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setString(2, urgency)
            st.setInt(3, limite)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out += NotificaDaMandare(
                        id = rs.getLong("id"),
                        kind = rs.getString("kind") ?: "",
                        body = rs.getString("body") ?: "",
                        clubName = rs.getString("club_name"),
                    )
                }
            }
        }
        return out
    }

    private fun segnaConsegnata(id: Long) {
        connection.prepareStatement("update notifications set delivered = true where id = ?")
            .use { st -> st.setLong(1, id); st.executeUpdate() }
    }

    private fun segnaRiepilogoInviato(leagueId: Long) {
        connection.prepareStatement("update tick_state set last_digest_at = now() where league_id = ?")
            .use { st -> st.setLong(1, leagueId); st.executeUpdate() }
    }

    /** Telegram interpreta l'HTML: un nome di club con una parentesi angolare romperebbe il messaggio. */
    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ----------------------------------------------------------------------- colloqui

    /**
     * Apre i colloqui che i fatti giustificano.
     *
     * ## Perche' li apre il server e non l'app
     *
     * Perche' i fatti li vede solo qui. "Tre panchine di fila" e' una domanda sulle
     * presenze di tre partite, e chiederla dal telefono vorrebbe dire scaricare lo storico
     * di tutta la rosa a ogni apertura della schermata — o, com'era prima, non chiederla
     * affatto e indovinare l'argomento da una soglia sul morale.
     *
     * ## Perche' anche per i club dell'AI
     *
     * Perche' un'AI deve poter gestire il proprio spogliatoio come lo gestisci tu:
     * altrimenti le sue rose finiscono la stagione con il morale a terra e le partite le
     * perde per un motivo che nessuno ha scelto.
     */
    private fun apriIColloqui(league: LeagueRow): List<String> {
        if (!league.config.rules.conversationsEnabled) return emptyList()

        val oggi = MatchDay(league.currentMatchDay)
        val aperti = colloquiAperti(league.id)
        val ultimoColloquio = ultimiColloqui(league.id)
        val tradite = promesseTraditeSenzaColloquio(league.id)
        val presenze = presenzeRecenti(league.id, oggi)
        val capitani = capitani(league.id)
        val sconfitte = sconfitteConsecutive(league.id)

        var aperte = 0
        val nuovi = mutableListOf<Triple<Long, Long, ConversationTrigger>>()

        caricaRosePerColloqui(league.id).forEach { (player, contratto) ->
            if (player.id.value in aperti) return@forEach

            val storia = PlayerHistory(
                playerId = player.id,
                joinedOn = MatchDay(contratto.signedOn),
                contractEndsOn = MatchDay(contratto.expiresOn),
                isInjured = player.injuredUntil?.let { it.value >= oggi.value } ?: false,
                recent = presenze[player.id.value].orEmpty(),
                brokenPromise = player.id.value in tradite,
                isCaptain = capitani[contratto.clubId] == player.id.value,
                teamLosingStreak = sconfitte[contratto.clubId] ?: 0,
                lastConversationOn = ultimoColloquio[player.id.value]?.let(::MatchDay),
            )

            val trigger = LeagueFacts.trigger(player, storia, oggi) ?: return@forEach
            nuovi += Triple(player.id.value, contratto.clubId, trigger)
        }

        if (nuovi.isEmpty()) return emptyList()

        connection.prepareStatement(
            """
            insert into conversations (league_id, club_id, player_id, topic, cause, opened_on)
            values (?, ?, ?, ?, ?, ?)
            on conflict do nothing
            """.trimIndent(),
        ).use { st ->
            nuovi.forEach { (playerId, clubId, trigger) ->
                st.setLong(1, league.id)
                st.setLong(2, clubId)
                st.setLong(3, playerId)
                st.setString(4, trigger.topic.name)
                st.setString(5, trigger.cause)
                st.setInt(6, oggi.value)
                st.addBatch()
            }
            aperte = st.executeBatch().count { it > 0 }
        }

        return if (aperte > 0) listOf("$aperte colloqui aperti nello spogliatoio.") else emptyList()
    }

    /**
     * I club dell'AI parlano con i propri giocatori.
     *
     * ## Perche' serve
     *
     * Perche' i colloqui li apre il tick per tutti, e un club gestito dal computer non ha
     * nessuno che li chiuda. Senza questo pezzo, le sue conversazioni si accumulerebbero
     * aperte per sempre — l'indice ne ammette una per giocatore, quindi bloccherebbero
     * anche quelle nuove — e il morale delle sue rose scenderebbe per tutta la stagione
     * senza che niente lo risollevi. Alla decima giornata giocheresti contro squadre col
     * morale a terra per un motivo che nessuno ha scelto.
     *
     * ## Perche' non promette mai
     *
     * Le opzioni che creano una promessa rendono di piu' sul momento, e un'AI che le
     * scegliesse sempre farebbe la figura del furbo per due giornate e poi crollerebbe
     * tutta insieme quando il tick le dichiara tradite. Le lascia agli umani, per cui
     * mantenere la parola e' una decisione e non un tiro di dado.
     */
    private fun rispondiAiColloqui(league: LeagueRow): List<String> {
        val aperti = connection.prepareStatement(
            """
            select cv.id, cv.player_id, cv.topic, cv.spontaneous
            from conversations cv
            join clubs c on c.id = cv.club_id
            where cv.league_id = ? and cv.status = 'APERTA' and c.is_ai
            limit 40
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.executeQuery().use { rs ->
                val out = mutableListOf<Triple<Long, Long, String>>()
                while (rs.next()) {
                    out += Triple(rs.getLong("id"), rs.getLong("player_id"), rs.getString("topic"))
                }
                out
            }
        }
        if (aperti.isEmpty()) return emptyList()

        var chiusi = 0
        for ((id, playerId, topicName) in aperti) {
            val topic = ConversationTopic.entries.firstOrNull { it.name == topicName } ?: continue
            val player = loadPlayerRow(PlayerId(playerId))?.player ?: continue

            // Sceglie l'opzione che le va meglio con **quel** giocatore: e' la stessa
            // tabella dei tratti che ha davanti un umano, applicata senza esitare.
            val migliore = ConversationEngine.optionsFor(topic)
                .filter { it.createsPromise == null }
                .maxByOrNull { opzione ->
                    ConversationEngine.resolve(
                        player, topic, opzione, MatchDay(league.currentMatchDay),
                        league.config.rules,
                    ).moraleDelta
                } ?: continue

            val esito = ConversationEngine.resolve(
                player, topic, migliore, MatchDay(league.currentMatchDay), league.config.rules,
            )

            salvaMorale(PlayerId(playerId), esito.player.morale)
            connection.prepareStatement(
                """
                update conversations
                set status = 'CHIUSA', tone = ?, morale_delta = ?, closed_at = now()
                where id = ?
                """.trimIndent(),
            ).use { st ->
                st.setString(1, migliore.tone.name)
                st.setInt(2, esito.moraleDelta)
                st.setLong(3, id)
                st.executeUpdate()
            }
            chiusi++
        }

        return if (chiusi > 0) listOf("$chiusi colloqui gestiti dai club del computer.") else emptyList()
    }

    private data class ContrattoBreve(val clubId: Long, val signedOn: Int, val expiresOn: Int)

    private fun caricaRosePerColloqui(leagueId: Long): List<Pair<Player, ContrattoBreve>> {
        val out = mutableListOf<Pair<Player, ContrattoBreve>>()
        connection.prepareStatement(
            """
            select p.*, c.club_id as contract_club, c.signed_on, c.expires_on
            from players p
            join contracts c on c.player_id = p.id
            where p.league_id = ? and c.squad = 'prima'
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out += readPlayer(rs) to ContrattoBreve(
                        clubId = rs.getLong("contract_club"),
                        signedOn = rs.getInt("signed_on"),
                        expiresOn = rs.getInt("expires_on"),
                    )
                }
            }
        }
        return out
    }

    private fun colloquiAperti(leagueId: Long): Set<Long> {
        val out = mutableSetOf<Long>()
        connection.prepareStatement(
            "select player_id from conversations where league_id = ? and status = 'APERTA'",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> while (rs.next()) out += rs.getLong(1) }
        }
        return out
    }

    private fun ultimiColloqui(leagueId: Long): Map<Long, Int> {
        val out = mutableMapOf<Long, Int>()
        connection.prepareStatement(
            "select player_id, max(opened_on) from conversations where league_id = ? group by player_id",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> while (rs.next()) out[rs.getLong(1)] = rs.getInt(2) }
        }
        return out
    }

    /**
     * Le promesse tradite di cui non si e' ancora parlato.
     *
     * La condizione non e' "chiusa da poco" ma "senza un colloquio aperto dopo la
     * scadenza": e' l'unica forma che regge il tick che ripassa ogni cinque minuti senza
     * riaprire ogni volta lo stesso discorso.
     */
    private fun promesseTraditeSenzaColloquio(leagueId: Long): Set<Long> {
        val out = mutableSetOf<Long>()
        connection.prepareStatement(
            """
            select pr.player_id from promises pr
            where pr.league_id = ? and pr.status = 'TRADITA'
              and not exists (
                  select 1 from conversations c
                  where c.player_id = pr.player_id
                    and c.topic = 'PROMESSA_TRADITA'
                    and c.opened_on >= pr.deadline
              )
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> while (rs.next()) out += rs.getLong(1) }
        }
        return out
    }

    /** Le ultime otto giornate, che coprono ogni regola di [LeagueFacts]. */
    private fun presenzeRecenti(leagueId: Long, oggi: MatchDay): Map<Long, List<AppearanceFact>> {
        val out = mutableMapOf<Long, MutableList<AppearanceFact>>()
        connection.prepareStatement(
            """
            select player_id, match_day, started, minutes, rating, goals, injured
            from appearances
            where league_id = ? and match_day > ?
            order by player_id, match_day desc
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setInt(2, oggi.value - 8)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out.getOrPut(rs.getLong("player_id")) { mutableListOf() } += AppearanceFact(
                        matchDay = MatchDay(rs.getInt("match_day")),
                        started = rs.getBoolean("started"),
                        minutes = rs.getInt("minutes"),
                        rating = rs.getDouble("rating"),
                        goals = rs.getInt("goals"),
                        injured = rs.getBoolean("injured"),
                    )
                }
            }
        }
        return out
    }

    private fun capitani(leagueId: Long): Map<Long, Long> {
        val out = mutableMapOf<Long, Long>()
        connection.prepareStatement(
            "select club_id, captain_id from lineups where league_id = ? and captain_id is not null",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> while (rs.next()) out[rs.getLong(1)] = rs.getLong(2) }
        }
        return out
    }

    /** Quante sconfitte di fila ha ogni club, contando dall'ultima partita all'indietro. */
    private fun sconfitteConsecutive(leagueId: Long): Map<Long, Int> {
        val esiti = mutableMapOf<Long, MutableList<Boolean>>()
        connection.prepareStatement(
            """
            select f.home_club_id, f.away_club_id, r.home_goals, r.away_goals
            from fixtures f join match_results r on r.fixture_id = f.id
            where f.league_id = ? and f.played
            order by f.match_day desc
            limit 400
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val casa = rs.getLong("home_club_id")
                    val fuori = rs.getLong("away_club_id")
                    val gc = rs.getInt("home_goals")
                    val gf = rs.getInt("away_goals")
                    esiti.getOrPut(casa) { mutableListOf() } += gc < gf
                    esiti.getOrPut(fuori) { mutableListOf() } += gf < gc
                }
            }
        }
        return esiti.mapValues { (_, sconfitte) -> sconfitte.takeWhile { it }.size }
    }

    // ------------------------------------------------------------------------- scambi

    /**
     * Le AI rispondono alle proposte ricevute.
     *
     * Il verdetto lo da' [TradeEvaluator], che sta in `core` ed e' testato: qui c'e' solo il
     * trasporto — leggere la proposta, chiedere, scrivere l'esito. Se la decisione vivesse
     * qui, il regolamento degli scambi sarebbe verificabile solo con un database davanti.
     */
    private fun rispondiAgliScambi(league: LeagueRow): List<String> {
        val note = mutableListOf<String>()

        for (trade in loadPendingTrades(league.id)) {
            val stato = loadAiState(trade.to) ?: continue
            val club = loadClub(trade.to) ?: continue
            val squad = loadSquad(league.id, trade.to)

            // Prestiti e amichevoli hanno regole loro. Farli passare dal valutatore degli
            // scambi darebbe risposte senza senso: un prestito non e' un acquisto piccolo,
            // e un'amichevole non ha nessun valore da confrontare.
            if (trade.kind != "SCAMBIO") {
                note += rispondiAUnaTrattativa(league, trade, stato, club, squad)
                continue
            }

            // I giocatori offerti stanno nella rosa dell'altro club: il valutatore vede solo
            // la propria, quindi glieli si porta gia' valutati.
            val offerti = loadSquad(league.id, trade.from).filter { it.id in trade.offer.offered }
            val valori = offerti.associate { it.id to Valuation.marketValue(it, league.config) }

            val risposta = TradeEvaluator.evaluate(
                offer = trade.offer,
                personality = stato.personality,
                squad = squad,
                availableCredits = club.availableCredits,
                config = league.config,
                offeredValues = valori,
            )

            if (risposta.accepted) {
                applicaScambio(trade, risposta.reason)
                note += "${club.name} accetta uno scambio."
            } else {
                chiudiScambio(trade.id, "RIFIUTATA", risposta.reason)
                note += "${club.name} rifiuta: ${risposta.verdict.label}."
            }
        }
        return note
    }

    private data class PendingTrade(
        val id: Long,
        val from: ClubId,
        val to: ClubId,
        val offer: TradeOffer,
        val kind: String = "SCAMBIO",
        val terms: JsonNode = JsonNode.parse("{}"),
    )

    /**
     * La risposta a un prestito o a un'amichevole.
     *
     * Accettare passa dalle stesse scritture della funzione SQL — riga in `loans`,
     * contratto spostato, oppure una partita in calendario — perche' l'esito deve essere
     * identico che ad accettare sia una persona o un computer. Due strade che producono
     * stati diversi si scoprono a stagione finita.
     */
    private fun rispondiAUnaTrattativa(
        league: LeagueRow,
        trade: PendingTrade,
        stato: AiState,
        club: Club,
        squad: List<Player>,
    ): String {
        when (trade.kind) {
            "PRESTITO" -> {
                val playerId = trade.offer.offered.firstOrNull()
                    ?: return chiudiConNota(trade, club, "proposta vuota")
                val player = loadPlayerRow(playerId)?.player
                    ?: return chiudiConNota(trade, club, "il giocatore non c'e' piu'")

                val giornate = trade.terms["matchDays"].int(0)
                val canone = trade.terms["fee"].int(0)

                val si = AiInitiative.answerLoan(
                    stato, club, squad, player, giornate, canone, league.config,
                )
                if (!si) return chiudiConNota(trade, club, "non ci serve alle sue condizioni")

                connection.prepareStatement(
                    """
                    insert into loans (league_id, player_id, owner_club_id, borrower_club_id,
                                       starts_on, ends_on, fee_per_match_day,
                                       wage_paid_by_borrower, can_play_against_owner, active)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, true)
                    """.trimIndent(),
                ).use { st ->
                    st.setLong(1, league.id)
                    st.setLong(2, playerId.value)
                    st.setLong(3, trade.from.value)
                    st.setLong(4, trade.to.value)
                    st.setInt(5, league.currentMatchDay)
                    st.setInt(6, league.currentMatchDay + maxOf(1, giornate))
                    st.setInt(7, canone)
                    st.setBoolean(8, trade.terms["wagePaidByBorrower"].bool(true))
                    st.setBoolean(9, trade.terms["canPlayAgainstOwner"].bool(false))
                    st.executeUpdate()
                }
                spostaContratto(playerId, trade.from, trade.to)
                chiudiScambio(trade.id, "ACCETTATA", "Ci sta bene.")
                return "${club.name} accetta un prestito."
            }

            "AMICHEVOLE" -> {
                val quando = trade.terms["kickoff"].strOrNull()
                    ?: return chiudiConNota(trade, club, "orario mancante")

                val si = AiInitiative.answerFriendly(
                    stato, squad, league.config,
                    giornateAllaProssimaPartita(league.id, trade.to) - league.currentMatchDay,
                )
                if (!si) return chiudiConNota(trade, club, "abbiamo le gambe pesanti")

                val competizione = competizioneAmichevoli(league.id)
                connection.prepareStatement(
                    """
                    insert into fixtures (league_id, competition_id, round, round_label,
                                          home_club_id, away_club_id, match_day, kickoff)
                    values (?, ?, 0, 'Amichevole', ?, ?, 0, ?::timestamptz)
                    """.trimIndent(),
                ).use { st ->
                    st.setLong(1, league.id)
                    st.setLong(2, competizione)
                    st.setLong(3, trade.from.value)
                    st.setLong(4, trade.to.value)
                    st.setString(5, quando)
                    st.executeUpdate()
                }
                chiudiScambio(trade.id, "ACCETTATA", "Ci stiamo.")
                return "${club.name} accetta un'amichevole."
            }

            else -> return chiudiConNota(trade, club, "proposta di tipo sconosciuto")
        }
    }

    private fun chiudiConNota(trade: PendingTrade, club: Club, motivo: String): String {
        chiudiScambio(trade.id, "RIFIUTATA", motivo.replaceFirstChar { it.uppercase() } + ".")
        return "${club.name} rifiuta: $motivo."
    }

    /** La competizione nascosta delle amichevoli, creata alla prima che se ne gioca. */
    private fun competizioneAmichevoli(leagueId: Long): Long {
        connection.prepareStatement(
            "select id from competitions where league_id = ? and kind = 'AMICHEVOLE' limit 1",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> if (rs.next()) return rs.getLong(1) }
        }

        return connection.prepareStatement(
            """
            insert into competitions (league_id, name, type, config, participants, kind)
            values (?, 'Amichevoli', 'GIRONE', '{}'::jsonb, '{}', 'AMICHEVOLE')
            returning id
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    private fun loadPendingTrades(leagueId: Long): List<PendingTrade> {
        val out = mutableListOf<PendingTrade>()
        connection.prepareStatement(
            """
            select t.id, t.from_club, t.to_club, t.offered, t.wanted, t.cash,
                   t.kind, t.terms
            from trades t
            join clubs c on c.id = t.to_club
            where t.league_id = ? and t.status = 'PROPOSTA' and c.is_ai
            order by t.created_at
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val from = ClubId(rs.getLong("from_club"))
                    val to = ClubId(rs.getLong("to_club"))
                    out += PendingTrade(
                        id = rs.getLong("id"),
                        from = from,
                        to = to,
                        offer = TradeOffer(
                            id = rs.getLong("id"),
                            from = from,
                            to = to,
                            offered = ids(rs.getArray("offered")),
                            wanted = ids(rs.getArray("wanted")),
                            cash = rs.getInt("cash"),
                        ),
                        kind = rs.getString("kind") ?: "SCAMBIO",
                        terms = JsonNode.parse(rs.getString("terms") ?: "{}"),
                    )
                }
            }
        }
        return out
    }

    private fun ids(array: java.sql.Array?): List<PlayerId> =
        (array?.array as? Array<*>)?.mapNotNull { (it as? Number)?.let { n -> PlayerId(n.toLong()) } }
            ?: emptyList()

    /**
     * Sposta giocatori e denaro, e chiude la proposta.
     *
     * Tutto nella stessa transazione del giro: o lo scambio avviene intero o non avviene.
     * A meta' lascerebbe un club senza il giocatore e senza i soldi, e nessuno potrebbe
     * accorgersene guardando il risultato.
     */
    private fun applicaScambio(trade: PendingTrade, motivo: String) {
        trade.offer.offered.forEach { spostaContratto(it, trade.from, trade.to) }
        trade.offer.wanted.forEach { spostaContratto(it, trade.to, trade.from) }

        if (trade.offer.cash != 0) {
            connection.prepareStatement(
                "update clubs set credits = credits - ? where id = ?",
            ).use { st ->
                st.setInt(1, trade.offer.cash)
                st.setLong(2, trade.from.value)
                st.executeUpdate()
            }
            connection.prepareStatement(
                "update clubs set credits = credits + ? where id = ?",
            ).use { st ->
                st.setInt(1, trade.offer.cash)
                st.setLong(2, trade.to.value)
                st.executeUpdate()
            }
        }

        chiudiScambio(trade.id, "ACCETTATA", motivo)
    }

    private fun spostaContratto(player: PlayerId, da: ClubId, a: ClubId) {
        connection.prepareStatement(
            "update contracts set club_id = ? where player_id = ? and club_id = ?",
        ).use { st ->
            st.setLong(1, a.value)
            st.setLong(2, player.value)
            st.setLong(3, da.value)
            st.executeUpdate()
        }
    }

    private fun chiudiScambio(id: Long, stato: String, motivo: String) {
        connection.prepareStatement(
            "update trades set status = ?, answer = ?, answered_at = now() where id = ?",
        ).use { st ->
            st.setString(1, stato)
            st.setString(2, motivo)
            st.setLong(3, id)
            st.executeUpdate()
        }
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

        // Chi vende, se e' una vendita e non uno svincolato.
        //
        // Il venditore non ha una colonna sua: e' `started_by`, e lo si riconosce dal fatto
        // che il giocatore ha ancora un contratto **con quel club**. Se nel frattempo e'
        // finito altrove — uno scambio chiuso mentre l'asta era aperta — l'asta si annulla:
        // completarla lo farebbe esistere in due rose contemporaneamente, e non c'e' modo
        // di accorgersene guardando il risultato.
        val venditore = (auction.target as? AuctionTarget.ForPlayer)
            ?.let { proprietarioDi(it.playerId) }

        if (venditore != null && venditore != auction.startedBy) {
            liberaFondi(auction)
            connection.prepareStatement(
                "update auctions set status = 'ANNULLATA' where id = ?",
            ).use { it.setLong(1, auctionId); it.executeUpdate() }
            log("Asta $auctionId annullata: il giocatore ha cambiato squadra nel frattempo.")
            return
        }

        // E non si vende scendendo sotto il minimo di rosa. Il controllo c'e' gia'
        // all'apertura, ma fra le due passa un'ora e in mezzo si puo' aver ceduto altro.
        if (venditore != null && squadSize(league.id, venditore) - 1 < league.config.setup.minSquadSize) {
            liberaFondi(auction)
            connection.prepareStatement(
                "update auctions set status = 'ANNULLATA' where id = ?",
            ).use { it.setLong(1, auctionId); it.executeUpdate() }
            notify(
                league.id, venditore,
                "La vendita e' saltata: ti avrebbe lasciato sotto il minimo di rosa.",
                kind = "asta", urgency = "immediata",
            )
            return
        }

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

        // E il prezzo va al venditore, se c'era un venditore. E' l'unica differenza fra
        // vendere e regalare, ed e' il motivo per cui prima non si poteva mettere all'asta
        // un giocatore sotto contratto: `assignPlayer` sovrascriveva il contratto e basta,
        // quindi il giocatore si sarebbe spostato gratis.
        if (venditore != null) {
            connection.prepareStatement(
                "update clubs set credits = credits + ? where id = ?",
            ).use { st ->
                st.setInt(1, outcome.price)
                st.setLong(2, venditore.value)
                st.executeUpdate()
            }
            notify(
                league.id, venditore,
                "Venduto per ${outcome.price} crediti.",
                kind = "asta", urgency = "immediata",
            )
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

    /** Di chi e' adesso, o null se e' svincolato. */
    private fun proprietarioDi(playerId: PlayerId): ClubId? =
        connection.prepareStatement("select club_id from contracts where player_id = ?").use { st ->
            st.setLong(1, playerId.value)
            st.executeQuery().use { rs -> if (rs.next()) ClubId(rs.getLong(1)) else null }
        }

    /** Sblocca i fondi impegnati da tutti gli offerenti. Serve quando un'asta si annulla. */
    private fun liberaFondi(auction: Auction) {
        connection.prepareStatement(
            "update clubs set committed_credits = greatest(0, committed_credits - ?) where id = ?",
        ).use { st ->
            auction.bids.forEach { bid ->
                st.setInt(1, bid.maxAmount)
                st.setLong(2, bid.club.value)
                st.addBatch()
            }
            st.executeBatch()
        }
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

    private fun squadSize(leagueId: Long, clubId: ClubId): Int =
        connection.prepareStatement(
            "select count(*) from contracts where league_id = ? and club_id = ? and squad = 'prima'",
        ).use { st ->
            st.setLong(1, leagueId)
            st.setLong(2, clubId.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun loadClubIds(leagueId: Long): List<ClubId> {
        val out = mutableListOf<ClubId>()
        connection.prepareStatement("select id from clubs where league_id = ? order by id").use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> while (rs.next()) out += ClubId(rs.getLong("id")) }
        }
        return out
    }

    // ------------------------------------------------------------------- la partita

    /**
     * Gioca una partita e scrive tutto quello che ne consegue.
     *
     * ## La timeline si salva intera
     *
     * Il client la legge **una volta** e la riproduce con il proprio orologio: nessun
     * polling, costo zero durante i novanta minuti, e chi apre l'app al sessantesimo
     * salta direttamente al sessantesimo. E' la decisione che rende accettabile una
     * griglia di cinque minuti su un backend gratuito.
     *
     * ## Il seed e' la partita
     *
     * Deriva da id della partita e seed della lega, quindi rigiocarla da' esattamente lo
     * stesso risultato. Se una transazione fallisce a meta' e il tick ripassa, non esce
     * un risultato diverso.
     *
     * @param notes il registro del giro: ci finisce ogni formazione salvata che e' stata
     *   corretta d'ufficio, cosi' chi apre il registro admin capisce perche' e' scesa in
     *   campo una squadra diversa da quella che aveva impostato.
     * @return false se la partita non si e' potuta giocare: rosa insufficiente, tipicamente.
     */
    private fun playMatch(
        league: LeagueRow,
        fixture: Fixture,
        notes: MutableList<String>,
    ): Boolean {
        val today = MatchDay(fixture.matchDay.value)
        val home = buildTeam(league, fixture.home, today, notes) ?: return false
        val away = buildTeam(league, fixture.away, today, notes) ?: return false

        val seed = league.config.setup.worldSeed * 31L + fixture.id
        val result = MatchEngine.simulate(home, away, league.config, seed)

        connection.prepareStatement(
            """
            insert into match_results (fixture_id, league_id, home_goals, away_goals, seed,
                                       timeline, player_stats, home_possession)
            values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            on conflict (fixture_id) do nothing
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, fixture.id)
            st.setLong(2, league.id)
            st.setInt(3, result.homeGoals)
            st.setInt(4, result.awayGoals)
            st.setLong(5, seed)
            st.setString(6, MatchJson.timeline(result))
            st.setString(7, MatchJson.playerStats(result))
            st.setDouble(8, result.homePossession)
            st.executeUpdate()
        }

        connection.prepareStatement("update fixtures set played = true where id = ?").use { st ->
            st.setLong(1, fixture.id)
            st.executeUpdate()
        }

        salvaPresenze(league, fixture, result, home, away)
        applyMatchAftermath(league, result, fixture)
        awardPrizes(league, fixture, result)

        // La giornata del gioco avanza con le partite giocate, non con l'orologio: e'
        // l'unita' con cui si misurano contratti, stipendi e crescita.
        connection.prepareStatement(
            "update leagues set current_match_day = greatest(current_match_day, ?) where id = ?",
        ).use { st ->
            st.setInt(1, fixture.matchDay.value)
            st.setLong(2, league.id)
            st.executeUpdate()
        }

        log("Lega ${league.id}: ${home.name} ${result.scoreline} ${away.name}")
        return true
    }

    /**
     * Chi ha giocato, e chi no.
     *
     * ## Perche' si scrive una riga anche per chi non e' sceso in campo
     *
     * Perche' la domanda vera non e' "quanto ha giocato", e' **"da quanto non gioca"**. Se
     * la panchina non lasciasse traccia, "tre partite senza scendere in campo" sarebbe
     * indistinguibile da "tre partite di cui non so niente" — e la seconda e' la
     * condizione normale di chiunque sia arrivato la settimana scorsa. Un colloquio
     * aperto sulla seconda invece che sulla prima e' esattamente l'evento incoerente che
     * il gioco produceva finora.
     *
     * ## Perche' titolare non si deduce dai minuti
     *
     * `PlayerMatchStats.started` risponde `minutesPlayed > 0`, che e' vero anche per chi
     * entra all'ottantesimo. Chi era in campo al fischio d'inizio lo sa solo la formazione
     * con cui la partita e' cominciata, ed e' qui che si ha ancora in mano.
     */
    private fun salvaPresenze(
        league: LeagueRow,
        fixture: Fixture,
        result: MatchResult,
        home: TeamSetup,
        away: TeamSetup,
    ) {
        connection.prepareStatement(
            """
            insert into appearances (fixture_id, player_id, league_id, club_id, match_day,
                                     started, minutes, goals, assists, yellow, red,
                                     injured, rating)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (fixture_id, player_id) do nothing
            """.trimIndent(),
        ).use { st ->
            listOf(home, away).forEach { team ->
                val titolari = team.lineup.playerIds
                loadSquad(league.id, team.clubId).forEach { player ->
                    val stats = result.stats[player.id]
                    val minuti = stats?.minutesPlayed ?: 0

                    st.setLong(1, fixture.id)
                    st.setLong(2, player.id.value)
                    st.setLong(3, league.id)
                    st.setLong(4, team.clubId.value)
                    st.setInt(5, fixture.matchDay.value)
                    st.setBoolean(6, player.id in titolari)
                    st.setInt(7, minuti)
                    st.setInt(8, stats?.goals ?: 0)
                    st.setInt(9, stats?.assists ?: 0)
                    st.setInt(10, stats?.yellowCards ?: 0)
                    st.setInt(11, stats?.redCards ?: 0)
                    st.setBoolean(12, stats?.injured ?: false)
                    // Il voto di chi non gioca non esiste: uno zero si distingue, un sei
                    // di comodo falserebbe ogni media.
                    st.setDouble(
                        13,
                        if (minuti > 0 && stats != null) {
                            stats.rating(player.primaryPosition.isGoalkeeper)
                        } else {
                            0.0
                        },
                    )
                    st.addBatch()
                }
            }
            st.executeBatch()
        }
    }

    /**
     * Minuti giocati, stanchezza, crescita e morale.
     *
     * Si applica ai soli giocatori che sono scesi in campo. Chi e' rimasto in panchina
     * paga comunque il morale, ma quello lo gestisce la giornata, non la partita.
     */
    private fun applyMatchAftermath(league: LeagueRow, result: MatchResult, fixture: Fixture) {
        val outcomes = mapOf(
            fixture.home to when {
                result.homeGoals > result.awayGoals -> TeamOutcome.VITTORIA
                result.homeGoals < result.awayGoals -> TeamOutcome.SCONFITTA
                else -> TeamOutcome.PAREGGIO
            },
            fixture.away to when {
                result.awayGoals > result.homeGoals -> TeamOutcome.VITTORIA
                result.awayGoals < result.homeGoals -> TeamOutcome.SCONFITTA
                else -> TeamOutcome.PAREGGIO
            },
        )

        val update = connection.prepareStatement(
            """
            update players
            set stamina = greatest(0, least(100, ?)),
                morale = greatest(0, least(100, ?)),
                experience = ?,
                attributes = ?::jsonb,
                overall = ?,
                minutes_observed = minutes_observed + ?
            where id = ?
            """.trimIndent(),
        )

        update.use { st ->
            result.stats.forEach { (playerId, stats) ->
                if (stats.minutesPlayed <= 0) return@forEach
                val row = loadPlayerRow(playerId) ?: return@forEach

                // La stamina l'ha gia' consumata il motore durante la partita: qui si
                // salva quello che ne resta, non si sottrae una seconda volta.
                // La stanchezza l'ha gia' calcolata il motore minuto per minuto: qui si
                // sottrae quella spesa, non se ne inventa dell'altra.
                val tired = row.player.withStamina(row.player.stamina - stats.staminaSpent)

                val grown = GrowthEngine.processMatch(
                    player = tired,
                    stats = stats,
                    context = GrowthContext(league.config, coachStarsOf(row.clubId)),
                ).player

                val outcome = outcomes[row.clubId] ?: TeamOutcome.PAREGGIO
                val moraled = MoraleEngine
                    .afterMatch(grown, stats, outcome, league.config.rules)
                    .player

                st.setInt(1, moraled.stamina)
                st.setInt(2, moraled.morale)
                st.setDouble(3, moraled.experience)
                st.setString(4, MatchJson.attributes(moraled))
                st.setInt(5, moraled.overall)
                st.setInt(6, stats.minutesPlayed)
                st.setLong(7, playerId.value)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    /** I premi partita: vittoria e pareggio, come li ha impostati l'admin. */
    private fun awardPrizes(league: LeagueRow, fixture: Fixture, result: MatchResult) {
        val economy = league.config.economy
        val (homePrize, awayPrize) = when {
            result.homeGoals > result.awayGoals -> economy.winPrize to 0
            result.awayGoals > result.homeGoals -> 0 to economy.winPrize
            else -> economy.drawPrize to economy.drawPrize
        }

        connection.prepareStatement("update clubs set credits = credits + ? where id = ?").use { st ->
            listOf(fixture.home to homePrize, fixture.away to awayPrize)
                .filter { it.second > 0 }
                .forEach { (club, prize) ->
                    st.setInt(1, prize)
                    st.setLong(2, club.value)
                    st.addBatch()
                }
            st.executeBatch()
        }
    }

    // ------------------------------------------------------------------ risveglio AI

    /**
     * Un'AI si sveglia, guarda il mercato, forse fa una cosa, e torna a dormire.
     *
     * ## L'anti-sciame vive qui
     *
     * Il requisito e' esplicito: le AI devono essere avversari veri, ma **non uno
     * sciame**. Venticinque club che rilanciano tutti insieme sullo stesso giocatore, o
     * che si svegliano allo stesso minuto, trasformerebbero il mercato in rumore e
     * l'applicazione in qualcosa da disinstallare.
     *
     * Le difese sono quattro, e nessuna basta da sola:
     *
     * 1. Si sveglia solo chi ha l'orario arrivato. Un'AI che dorme non sa nemmeno che
     *    l'asta esiste — non e' che decide di non partecipare, proprio non la vede.
     * 2. Un'azione per risveglio, con un tetto giornaliero. Anche trovando dieci
     *    occasioni, ne coglie una.
     * 3. Piu' AI sono gia' su un obiettivo, meno appetibile diventa: la seconda ci pensa,
     *    la terza quasi mai, la quarta mai.
     * 4. Il prossimo risveglio e' scaglionato a caso, quindi non si riallineano mai.
     */
    private fun wakeAi(league: LeagueRow, clubId: ClubId, now: Instant, today: MatchDay) {
        val state = loadAiState(clubId) ?: return
        val club = loadClub(clubId) ?: return

        val squad = loadSquad(league.id, clubId)

        /*
         * Il mercato iniziale non e' il mercato a regime.
         *
         * Il tetto di azioni giornaliere esiste per proteggere l'umano: dalle notifiche,
         * dai rilanci a raffica, da venticinque club che gli si buttano addosso sullo
         * stesso giocatore. Nessuna di quelle cose sta succedendo mentre un club deve
         * ancora comporre il suo primo undici in una rosa vuota — sta solo riempiendo
         * caselle prima del fischio d'inizio.
         *
         * Applicare il tetto anche qui vorrebbe dire otto club AI a due acquisti al
         * giorno: nove giorni prima che il campionato possa cominciare. La difesa contro
         * lo sciame resta comunque intera, perche' e' l'affollamento a spegnere
         * l'interesse, non il conteggio delle azioni.
         *
         * ## Perche' la rosa e non lo stato della lega
         *
         * Sembrerebbe naturale legare tutto questo a `status = 'mercato'`, e sarebbe un
         * errore: la lega passa a `in_corso` **quando l'admin crea la prima competizione**,
         * che e' esattamente il momento in cui le rose devono riempirsi in fretta. Legato
         * allo stato, il mercato veloce si spegnerebbe proprio al fischio d'inizio e i club
         * arriverebbero alla prima giornata in nove.
         *
         * Legato alla rosa, invece, la regola si spiega da sola: un club che non puo'
         * schierare una squadra legale ha il mercato veloce, chiunque sia e in qualunque
         * momento della stagione — anche a marzo, dopo aver venduto mezza rosa.
         */
        val allestimento = squad.size < league.config.setup.minSquadSize

        if (!allestimento && !AiScheduler.hasActionsLeft(state, now, league.config.ai)) {
            saveAiState(clubId, AiScheduler.scheduleNext(state, now, league.config.setup.worldSeed))
            return
        }

        // L'ordine delle mosse lo decide [AiTurn], in `core`, dove si puo' provare.
        //
        // Era scritto qui, come `tryBid(...) || tryOpenAuction(...)`, e quel corto circuito
        // era il difetto piu' costoso del mercato: se esisteva **anche una sola** asta su
        // cui offrire, l'AI offriva e non ne apriva nessuna. Sei slot liberi, nove caselle
        // vuote, risveglio finito — e appena nasceva un'asta tutti si mettevano in fila su
        // quella. La simulazione del ritmo lo misura: cinque aste aperte in tutta la lega
        // al terzo giro, e club fermi fra uno e nove giocatori dopo venti.
        //
        // Nessun test lo prendeva perche' viveva dentro questa funzione, che ha bisogno di
        // una connessione al database.
        var acted = false
        for (mossa in AiTurn.order(squad.size, league.config)) {
            acted = when (mossa) {
                AiMove.APRI_ASTA -> tryOpenAuction(league, state, club, squad)
                AiMove.OFFRI -> tryBid(league, state, club, squad, today)
                AiMove.METTI_IN_VENDITA -> mettiInVendita(league, state, club, squad)
                AiMove.GESTISCI_ROSA -> tieniInOrdineLaRosa(league, state, club, squad, today)
                AiMove.PROPONI_SCAMBIO -> proponiUnoScambio(league, state, club, squad, today)
                AiMove.CHIEDI_AMICHEVOLE -> chiediUnAmichevole(league, state, club, squad)
            }
            if (acted) break
        }

        val after = if (acted) AiScheduler.recordAction(state, now) else state
        val next = AiScheduler.scheduleNext(after, now, league.config.setup.worldSeed)

        saveAiState(
            clubId,
            if (allestimento) {
                // Durante l'allestimento si torna presto, ma non tutti insieme: lo
                // scaglionamento resta, e' solo compresso. Il seed lo deriva dal club,
                // quindi otto AI non si risvegliano mai nello stesso istante.
                val jitter = 60L + (clubId.value * 37L) % 240L
                next.copy(nextWakeAt = now.plusSeconds(jitter))
            } else {
                next
            },
        )
    }

    // ------------------------------------------------------- l'AI fa il primo passo

    /**
     * Rinnova a chi serve, saluta chi non gioca.
     *
     * ## Perche' un'AI deve farlo
     *
     * Perche' altrimenti le sue rose si svuotano da sole: i contratti scadono, i giocatori
     * tornano svincolati e a meta' stagione ci si ritrova a giocare contro squadre di
     * dodici. Nessuno vede succedere niente — non c'e' una notifica per "il computer non ha
     * rinnovato" — e il campionato diventa piatto senza che si capisca perche'.
     */
    private fun tieniInOrdineLaRosa(
        league: LeagueRow,
        state: AiState,
        club: Club,
        squad: List<Player>,
        today: MatchDay,
    ): Boolean {
        val contratti = loadContractsOf(club.id)
        val azioni = AiInitiative.squadHousekeeping(
            state, club, squad, contratti, league.config, today,
        )
        if (azioni.isEmpty()) return false

        // Una per risveglio. Il tetto delle azioni esiste per non far succedere venti cose
        // fra un'occhiata all'app e l'altra, e vale anche per le cose che non si vedono.
        when (val azione = azioni.first()) {
            is SquadAction.Rinnova -> {
                connection.prepareStatement(
                    """
                    update contracts set signed_on = ?, expires_on = ?
                    where player_id = ? and club_id = ?
                    """.trimIndent(),
                ).use { st ->
                    st.setInt(1, today.value)
                    st.setInt(2, today.value + league.config.market.defaultContractMatchDays)
                    st.setLong(3, azione.playerId.value)
                    st.setLong(4, club.id.value)
                    st.executeUpdate()
                }
                connection.prepareStatement(
                    "update clubs set credits = credits - ? where id = ?",
                ).use { st ->
                    st.setInt(1, azione.cost)
                    st.setLong(2, club.id.value)
                    st.executeUpdate()
                }
            }

            is SquadAction.Svincola -> expireContract(league, azione.playerId, club.id)
        }
        return true
    }

    /**
     * Propone uno scambio a qualcuno.
     *
     * ## Perche' il bersaglio si sceglie a caso fra chi non ha appena rifiutato
     *
     * Perche' valutare tutte le rose di tutti i club a ogni risveglio, per venti club,
     * ogni cinque minuti, e' lavoro che quasi sempre finisce in "niente da proporre". Una
     * squadra per volta, presa a caso, produce dopo qualche giro lo stesso risultato a un
     * ventesimo del costo — e somiglia di piu' a come si guarda davvero in giro.
     *
     * `refusalCooldowns` esisteva gia' per le trattative e vale anche qui: chi ti ha appena
     * detto di no non va richiamato domani.
     */
    private fun proponiUnoScambio(
        league: LeagueRow,
        state: AiState,
        club: Club,
        squad: List<Player>,
        today: MatchDay,
    ): Boolean {
        if (!league.config.market.swapsEnabled) return false

        val altri = loadClubIds(league.id)
            .filter { it != club.id && state.canActOn(it, today) }
            .filterNot { haGiaUnaPropostaAperta(club.id, it) }
        if (altri.isEmpty()) return false

        val bersaglio = altri[(club.id.value + today.value).toInt().mod(altri.size)]
        val suaRosa = loadSquad(league.id, bersaglio)
        if (suaRosa.isEmpty()) return false

        val offerta = AiInitiative.proposeTrade(
            state, club, squad, bersaglio, suaRosa, league.config,
        ) ?: return false

        connection.prepareStatement(
            """
            insert into trades (league_id, from_club, to_club, offered, wanted, cash,
                                message, kind)
            values (?, ?, ?, ?, ?, ?, ?, 'SCAMBIO')
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setLong(2, club.id.value)
            st.setLong(3, bersaglio.value)
            st.setArray(4, connection.createArrayOf("bigint", offerta.offered.map { it.value }.toTypedArray()))
            st.setArray(5, connection.createArrayOf("bigint", offerta.wanted.map { it.value }.toTypedArray()))
            st.setInt(6, offerta.cash)
            st.setString(7, offerta.message)
            st.executeUpdate()
        }

        notify(
            league.id, bersaglio,
            "${clubNameOf(club.id)} ti ha proposto uno scambio.",
            kind = "scambio",
            urgency = "immediata",
        )
        return true
    }

    /**
     * Chiede un'amichevole.
     *
     * Sempre a due giorni di distanza e in prima serata: un'amichevole proposta per fra
     * dieci minuti non la accetta nessuno, e una per fra tre settimane non la ricorda
     * nessuno.
     */
    private fun chiediUnAmichevole(
        league: LeagueRow,
        state: AiState,
        club: Club,
        squad: List<Player>,
    ): Boolean {
        if (!league.config.rules.friendliesEnabled) return false

        val prossima = giornateAllaProssimaPartita(league.id, club.id)
        if (!AiInitiative.wantsFriendly(state, squad, league.config, prossima)) return false

        val altri = loadClubIds(league.id).filter { it != club.id }
        if (altri.isEmpty()) return false
        val bersaglio = altri[(club.id.value * 7L).toInt().mod(altri.size)]
        if (haGiaUnaPropostaAperta(club.id, bersaglio)) return false

        val quando = java.time.LocalDate.now(league.config.calendar.timeZone)
            .plusDays(2)
            .atTime(21, 0)
            .atZone(league.config.calendar.timeZone)
            .toInstant()

        connection.prepareStatement(
            """
            insert into trades (league_id, from_club, to_club, offered, wanted, cash,
                                message, kind, terms)
            values (?, ?, ?, '{}', '{}', 0, ?, 'AMICHEVOLE', ?::jsonb)
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setLong(2, club.id.value)
            st.setLong(3, bersaglio.value)
            st.setString(4, "Ci va di giocare? Abbiamo le gambe fresche.")
            st.setString(5, """{"kickoff":"$quando"}""")
            st.executeUpdate()
        }

        notify(
            league.id, bersaglio,
            "${clubNameOf(club.id)} ti ha chiesto un'amichevole.",
            kind = "amichevole",
            urgency = "riepilogo",
        )
        return true
    }

    /** Una proposta aperta per volta verso lo stesso club: due sarebbero insistenza. */
    private fun haGiaUnaPropostaAperta(from: ClubId, to: ClubId): Boolean =
        connection.prepareStatement(
            "select 1 from trades where from_club = ? and to_club = ? and status = 'PROPOSTA' limit 1",
        ).use { st ->
            st.setLong(1, from.value)
            st.setLong(2, to.value)
            st.executeQuery().use { it.next() }
        }

    /** Quante giornate mancano alla prossima partita di questo club. Grande se non ce n'e'. */
    private fun giornateAllaProssimaPartita(leagueId: Long, clubId: ClubId): Int =
        connection.prepareStatement(
            """
            select min(match_day) from fixtures
            where league_id = ? and not played and match_day > 0
              and (home_club_id = ? or away_club_id = ?)
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setLong(2, clubId.value)
            st.setLong(3, clubId.value)
            st.executeQuery().use { rs ->
                if (!rs.next()) return@use 99
                val prossima = rs.getInt(1)
                if (rs.wasNull()) 99 else prossima
            }
        }

    private fun loadContractsOf(clubId: ClubId): List<Contract> {
        val out = mutableListOf<Contract>()
        connection.prepareStatement(
            """
            select player_id, club_id, signed_on, expires_on, wage_per_match_day,
                   price_paid, release_clause
            from contracts where club_id = ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, clubId.value)
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

    /**
     * Cerca l'asta piu' interessante e ci offre sopra. Una sola, la migliore.
     *
     * @return true se ha davvero offerto.
     */
    private fun tryBid(
        league: LeagueRow,
        state: AiState,
        club: Club,
        squad: List<Player>,
        today: MatchDay,
    ): Boolean {
        val auctions = loadOpenAuctions(league.id).filter { it.id !in state.abandonedTargets }
        if (auctions.isEmpty()) return false

        val candidates = auctions.mapNotNull { auction ->
            val target = (auction.target as? AuctionTarget.ForPlayer) ?: return@mapNotNull null
            val player = loadPlayerRow(target.playerId)?.player ?: return@mapNotNull null

            // Quante altre AI sono gia' impegnate qui: e' il numero che spegne lo sciame.
            val competing = auction.bids.map { it.club }.distinct().count { it != club.id && isAi(it) }

            val appeal = AiManager.evaluate(state, club, squad, player, league.config, competing)
            val max = AiManager.decideBid(state, club, auction, appeal, league.config)
                ?: return@mapNotNull null

            Triple(auction, max, appeal.appeal)
        }

        // Una sola azione per risveglio: quella che vale di piu'. Un'AI che offrisse su
        // tutte le aste aperte in un colpo solo e' esattamente lo sciame da evitare.
        val scelta = candidates.maxByOrNull { it.third } ?: return false
        val auction = scelta.first
        val max = scelta.second

        // L'offerta passa dalla stessa funzione che usa l'app: stesso lock, stessi
        // controlli sui fondi. Un'AI che scrivesse direttamente nella tabella potrebbe
        // spendere crediti che non ha, e nessuno se ne accorgerebbe.
        val ok = connection.prepareStatement("select place_bid(?, ?, ?)").use { st ->
            st.setLong(1, auction.id)
            st.setLong(2, club.id.value)
            st.setInt(3, max)
            st.executeQuery().use { rs ->
                rs.next() && JsonNode.parse(rs.getString(1))["ok"].bool(false)
            }
        }

        if (ok) {
            log("Lega ${league.id}: ${club.name} offre fino a $max sull'asta ${auction.id}.")
        }
        return ok
    }

    /**
     * Un'AI mette all'asta uno svincolato che le serve.
     *
     * ## Perche' devono poterlo fare
     *
     * `start_auction` chiede un proprietario umano, ed e' giusto: e' la funzione che
     * chiama l'app. Ma se solo gli umani potessero aprire aste, i club AI non
     * comprerebbero mai nessuno — potrebbero solo rilanciare su quello che gli umani
     * hanno gia' messo in vendita. Resterebbero con la rosa vuota, il campionato non
     * potrebbe iniziare, e la lega sarebbe bloccata in attesa di qualcosa che non
     * succede.
     *
     * Qui il tick scrive direttamente, perche' e' lui l'autorita': gli stessi controlli
     * che la funzione fa per gli umani sono rifatti in Kotlin.
     *
     * ## Due mercati, non uno
     *
     * Il tetto di tre aste per club e la durata di un'ora esistono per una ragione buona:
     * a stagione in corso proteggono l'umano dalle notifiche e dai duelli a raffica.
     * Durante l'allestimento servono i numeri opposti, perche' dieci club AI con tre aste
     * a testa da un'ora non riempiranno mai centottanta caselle: sarebbero nove giorni di
     * attesa prima che il campionato possa cominciare, e nessuno arriva a vederlo.
     *
     * Con i valori dell'allestimento il conto e' un altro: 10 club AI per 6 aste da 15
     * minuti fanno 60 aggiudicazioni ogni quarto d'ora, cioe' 180 caselle in tre quarti
     * d'ora. La penalita' di affollamento resta intera e non e' toccata qui: e' quella a
     * impedire che venti AI si buttino sullo stesso giocatore, e non ha niente a che
     * vedere con quante aste sono aperte. Un'AI apre sei aste su sei ruoli scoperti, non
     * sei offerte sullo stesso obiettivo.
     *
     * Il confine fra i due mercati e' la rosa, non lo stato della lega: chi non arriva al
     * minimo compra in fretta, chiunque sia e in qualunque mese. Il perche' e' spiegato per
     * esteso in [wakeAi].
     */
    private fun tryOpenAuction(
        league: LeagueRow,
        state: AiState,
        club: Club,
        squad: List<Player>,
    ): Boolean {
        if (club.availableCredits < 1) return false
        if (!AiTurn.canBuy(squad.size, league.config)) return false

        val allestimento = squad.size < league.config.setup.minSquadSize
        val market = league.config.market
        val aperte = countOpenAuctionsBy(club.id)

        var quante = AiTurn.auctionsToOpen(squad.size, aperte, league.config)
        if (quante <= 0) return false

        val durataMinuti = if (allestimento) {
            market.initialAuctionDurationMinutes
        } else {
            market.auctionDurationMinutes
        }

        // Un giocatore per cui esiste gia' un'asta non si rimette all'asta.
        val giaInAsta = loadOpenAuctions(league.id)
            .mapNotNull { (it.target as? AuctionTarget.ForPlayer)?.playerId?.value }
            .toMutableSet()

        // Quanto ha gia' rischiato sulle aste che ha aperto.
        //
        // Aprire non impegna niente, ma vincere costa. Senza questo conto un club con
        // centomila in cassa apre sei aste da cinquantamila, ne vince due, e le altre
        // quattro hanno tolto quattro giocatori dal listino per un quarto d'ora e sono
        // andate deserte: il mercato sembra pieno e non si muove niente.
        var impegnato = impegnoSulleProprieAste(club.id)
        val migliorePerRuolo = squad.groupBy { it.primaryPosition }
            .mapValues { (_, giocatori) -> giocatori.maxOf { it.overall } }

        var aperteOra = 0

        while (quante > 0) {
            val disponibile = club.availableCredits - impegnato
            if (disponibile < 1) break

            val candidato = loadFreeAgents(league.id, giaInAsta, limit = 40)
                .map { it to AiManager.evaluate(state, club, squad, it, league.config, competingAi = 0) }
                .filter { (_, appeal) -> appeal.isInterested && appeal.ceiling <= disponibile }
                // A rosa completa non basta che piaccia: deve **migliorare** il reparto.
                // Comprare il quarto centrocampista da 68 avendone tre da 70 e' il modo in
                // cui una squadra spende tutto senza diventare piu' forte di un punto.
                .filter { (p, _) ->
                    AiTurn.migliora(
                        squad.size, p.overall, migliorePerRuolo[p.primaryPosition], league.config,
                    )
                }
                .maxByOrNull { (_, appeal) -> appeal.appeal }
                ?: break

            val (player, appeal) = candidato
            // Base bassa: il prezzo lo deve fare l'asta, non chi la apre. Aprire gia'
            // vicino al proprio tetto vorrebbe dire dichiarare quanto si e' disposti a
            // spendere.
            val base = 1.coerceAtLeast(appeal.ceiling / 5)
            apriAsta(league.id, player.id.value, club.id, base, durataMinuti)

            giaInAsta += player.id.value
            impegnato += appeal.ceiling
            aperteOra++
            quante--

            log(
                "Lega ${league.id}: ${club.name} mette all'asta ${player.shortName}, " +
                    "base $base, durata $durataMinuti minuti.",
            )
        }

        return aperteOra > 0
    }

    /**
     * Mette all'asta uno dei propri, quando ne ha uno che non gli serve.
     *
     * ## Perche' e' la mossa che tiene vivo il mercato
     *
     * Perche' le aste esistevano **solo per gli svincolati**: il giorno in cui l'ultimo
     * senza contratto trovava squadra, il listino restava vuoto per il resto della
     * stagione. Nessuno vendeva, quindi nessuno comprava, quindi non succedeva piu' niente
     * fino a giugno. Un club che vende e' l'unica fonte di offerta nuova dopo la prima
     * settimana.
     */
    private fun mettiInVendita(
        league: LeagueRow,
        state: AiState,
        club: Club,
        squad: List<Player>,
    ): Boolean {
        val aperte = countOpenAuctionsBy(club.id)
        if (aperte >= league.config.market.maxParallelAuctionsPerClub) return false

        val (giocatore, base) = AiInitiative.playerToSell(state, squad, league.config)
            ?: return false

        // Un giocatore in prestito non e' suo da vendere: alla scadenza deve tornare.
        if (inPrestito(giocatore.id)) return false

        val giaInAsta = loadOpenAuctions(league.id)
            .mapNotNull { (it.target as? AuctionTarget.ForPlayer)?.playerId?.value }
            .toSet()
        if (giocatore.id.value in giaInAsta) return false

        apriAsta(
            league.id, giocatore.id.value, club.id, base,
            league.config.market.auctionDurationMinutes,
        )

        notify(
            league.id, null,
            "${club.name} mette sul mercato ${giocatore.shortName} (${giocatore.overall}).",
            kind = "asta",
            urgency = "riepilogo",
        )
        log("Lega ${league.id}: ${club.name} vende ${giocatore.shortName}, base $base.")
        return true
    }

    private fun apriAsta(
        leagueId: Long,
        playerId: Long,
        startedBy: ClubId,
        base: Int,
        durataMinuti: Int,
    ) {
        connection.prepareStatement(
            """
            insert into auctions (league_id, target_type, target_id, started_by, ends_at,
                                  starting_price, current_price, status)
            values (?, 'player', ?, ?, now() + make_interval(mins => ?), ?, ?, 'APERTA')
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setLong(2, playerId)
            st.setLong(3, startedBy.value)
            st.setInt(4, durataMinuti)
            st.setInt(5, base)
            st.setInt(6, base)
            st.executeUpdate()
        }
    }

    /** Quanto un club ha gia' offerto in tutto sulle aste ancora aperte. */
    private fun impegnoSulleProprieAste(clubId: ClubId): Int =
        connection.prepareStatement(
            """
            select coalesce(sum(b.max_amount), 0)
            from bids b join auctions a on a.id = b.auction_id
            where b.club_id = ? and a.status = 'APERTA'
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun inPrestito(playerId: PlayerId): Boolean =
        connection.prepareStatement(
            "select 1 from loans where player_id = ? and active limit 1",
        ).use { st ->
            st.setLong(1, playerId.value)
            st.executeQuery().use { it.next() }
        }

    private fun countOpenAuctionsBy(clubId: ClubId): Int =
        connection.prepareStatement(
            "select count(*) from auctions where started_by = ? and status = 'APERTA'",
        ).use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    /**
     * Gli svincolati piu' forti, esclusi quelli gia' in asta.
     *
     * Il limite non e' pigrizia: valutare milletrecento giocatori a ogni risveglio, per
     * ogni AI, ogni cinque minuti, e' lavoro che non cambia nessuna decisione. I migliori
     * disponibili sono dove guarda anche un umano.
     */
    private fun loadFreeAgents(leagueId: Long, exclude: Set<Long>, limit: Int): List<Player> {
        val out = mutableListOf<Player>()
        connection.prepareStatement(
            """
            select p.* from players p
            left join contracts c on c.player_id = p.id
            where p.league_id = ? and c.player_id is null and not p.is_custom
            order by p.overall desc
            limit ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setInt(2, limit + exclude.size)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val player = readPlayer(rs)
                    if (player.id.value !in exclude) out += player
                }
            }
        }
        return out.take(limit)
    }

    private fun isAi(clubId: ClubId): Boolean =
        connection.prepareStatement("select is_ai from clubs where id = ?").use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }

    private fun loadAiStates(leagueId: Long): List<AiState> {
        val out = mutableListOf<AiState>()
        connection.prepareStatement(
            "select club_id, personality, next_wake_at, actions_today, action_day, " +
                "abandoned_targets from ai_states where league_id = ?",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> while (rs.next()) out += readAiState(rs) }
        }
        return out
    }

    private fun loadAiState(clubId: ClubId): AiState? =
        connection.prepareStatement(
            "select club_id, personality, next_wake_at, actions_today, action_day, " +
                "abandoned_targets from ai_states where club_id = ?",
        ).use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs -> if (rs.next()) readAiState(rs) else null }
        }

    private fun readAiState(rs: java.sql.ResultSet): AiState {
        val p = JsonNode.parse(rs.getString("personality"))
        val clubId = ClubId(rs.getLong("club_id"))

        return AiState(
            personality = AiPersonality(
                clubId = clubId,
                marketAggression = p["marketAggression"].double(0.5),
                youthPreference = p["youthPreference"].double(0.5),
                budgetDiscipline = p["budgetDiscipline"].double(0.5),
                patience = p["patience"].double(0.5),
                activeFromHour = p["activeFromHour"].int(9),
                activeToHour = p["activeToHour"].int(23),
                checksPerDay = p["checksPerDay"].int(2),
                obsessions = p["obsessions"].asList()
                    .mapNotNull { o -> AiObsession.entries.firstOrNull { it.name == o.str("") } }
                    .toSet(),
            ),
            nextWakeAt = rs.getTimestamp("next_wake_at").toInstant(),
            actionsToday = rs.getInt("actions_today"),
            actionDay = rs.getDate("action_day")?.toLocalDate(),
            abandonedTargets = (rs.getArray("abandoned_targets")?.array as? Array<*>)
                ?.mapNotNull { (it as? Number)?.toLong() }?.toSet() ?: emptySet(),
        )
    }

    private fun saveAiState(clubId: ClubId, state: AiState) {
        connection.prepareStatement(
            "update ai_states set next_wake_at = ?, actions_today = ?, action_day = ?, " +
                "abandoned_targets = ? where club_id = ?",
        ).use { st ->
            st.setTimestamp(1, Timestamp.from(state.nextWakeAt))
            st.setInt(2, state.actionsToday)
            st.setDate(3, state.actionDay?.let { java.sql.Date.valueOf(it) })
            st.setArray(4, connection.createArrayOf("bigint", state.abandonedTargets.toTypedArray()))
            st.setLong(5, clubId.value)
            st.executeUpdate()
        }
    }

    private fun loadClub(clubId: ClubId): Club? =
        connection.prepareStatement(
            "select id, name, short_name, is_ai, credits, committed_credits from clubs where id = ?",
        ).use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                Club(
                    id = ClubId(rs.getLong("id")),
                    name = rs.getString("name"),
                    shortName = rs.getString("short_name"),
                    isAi = rs.getBoolean("is_ai"),
                    credits = rs.getInt("credits"),
                    committedCredits = rs.getInt("committed_credits"),
                )
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
    /**
     * @param club null quando la notizia riguarda **tutta la lega** — un giocatore messo
     *   sul mercato lo vedono tutti, ed e' la colonna `club_id` nulla che lo dice. Prima
     *   il parametro non era annullabile e una notizia di lega non si poteva scrivere.
     */
    private fun notify(
        leagueId: Long,
        club: ClubId?,
        body: String,
        kind: String = "asta",
        urgency: String = "immediata",
    ) {
        connection.prepareStatement(
            "insert into notifications (league_id, club_id, kind, urgency, body) values (?, ?, ?, ?, ?)",
        ).use { st ->
            st.setLong(1, leagueId)
            if (club == null) st.setNull(2, java.sql.Types.BIGINT) else st.setLong(2, club.value)
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
        val status: String,
    )

    private data class TickStateRow(
        val lastProcessedAt: Instant?,
        val lastDigestAt: Instant?,
        val settledMatchDays: Set<Int>,
    )

    private fun loadActiveLeagues(): List<LeagueRow> {
        val out = mutableListOf<LeagueRow>()
        connection.prepareStatement(
            "select id, name, current_match_day, config, status from leagues " +
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
                        status = rs.getString("status"),
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

    // -------------------------------------------------------- rose e formazioni

    private data class PlayerRow(val player: Player, val clubId: ClubId)

    /**
     * La squadra pronta a giocare.
     *
     * ## Prima quella salvata, poi quella automatica
     *
     * Chi ha impostato la formazione a mano deve vedere in campo la sua, altrimenti la
     * schermata del campo e' un giocattolo. Ma [AutoLineup] non e' il ripiego per l'AI:
     * e' la rete che tiene in piedi il calendario. Con due partite al giorno prima o poi
     * qualcuno si dimentica di schierare, e il campionato non puo' fermarsi perche' una
     * persona e' andata a cena.
     *
     * Le due cose convivono: si parte da quella salvata e si tappano i buchi. Una
     * formazione vecchia — con un titolare venduto tre giorni fa o infortunato ieri —
     * **non** e' un motivo per rifiutare di giocare. Sarebbe il difetto peggiore
     * possibile: un club che perde a tavolino per una cessione andata a buon fine.
     *
     * Restituisce null solo se la rosa non basta davvero: la partita resta da giocare e
     * il tick lo segnala, invece di far uscire un risultato inventato.
     */
    private fun buildTeam(
        league: LeagueRow,
        clubId: ClubId,
        today: MatchDay,
        notes: MutableList<String>,
    ): TeamSetup? {
        val squad = loadSquad(league.id, clubId)
        if (squad.size < Formation.PLAYERS_ON_PITCH) return null

        val name = clubNameOf(clubId)
        val coachStars = coachStarsOf(clubId)

        val saved = loadSavedLineup(clubId)
            ?: return AutoLineup.setup(clubId, name, squad, today, coachStars)

        // Qualunque cosa vada storta dentro la riparazione si traduce nella formazione
        // automatica, non in un'eccezione: un'eccezione qui annullerebbe la transazione
        // dell'intera lega, e il campionato di venti club si fermerebbe per il file JSON
        // di uno solo.
        val repaired = runCatching { repairLineup(saved, squad, today) }
            .getOrElse { failure ->
                notes += "$name: formazione salvata illeggibile (${failure.message}), " +
                    "schierata quella automatica."
                null
            }
            ?: return AutoLineup.setup(clubId, name, squad, today, coachStars)

        repaired.problems.forEach { notes += "$name: $it" }

        return TeamSetup(
            clubId = clubId,
            name = name,
            lineup = repaired.lineup,
            tactics = repaired.tactics,
            coachStars = coachStars,
        )
    }

    private data class SavedLineupRow(
        val formation: String?,
        val slots: String?,
        val bench: List<Long>,
        val tactics: String?,
        val captainId: Long?,
        val penaltyTakerId: Long?,
    )

    /** Quello che si e' riusciti a ricostruire, piu' cosa e' stato corretto d'ufficio. */
    private data class RepairedLineup(
        val lineup: Lineup,
        val tactics: Tactics,
        val problems: List<String>,
    )

    private fun loadSavedLineup(clubId: ClubId): SavedLineupRow? =
        connection.prepareStatement(
            "select formation, slots, bench, tactics, captain_id, penalty_taker_id " +
                "from lineups where club_id = ?",
        ).use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                SavedLineupRow(
                    formation = rs.getString("formation"),
                    slots = rs.getString("slots"),
                    bench = (rs.getArray("bench")?.array as? Array<*>)
                        ?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList(),
                    tactics = rs.getString("tactics"),
                    captainId = rs.getLong("captain_id").takeIf { !rs.wasNull() },
                    penaltyTakerId = rs.getLong("penalty_taker_id").takeIf { !rs.wasNull() },
                )
            }
        }

    /**
     * Trasforma la formazione salvata in una schierabile, completando quello che manca.
     *
     * Null solo se non ci sono undici giocatori sani: e' lo stesso caso in cui
     * [AutoLineup] si arrende, e vale il rinvio della partita.
     */
    private fun repairLineup(
        saved: SavedLineupRow,
        squad: List<Player>,
        today: MatchDay,
    ): RepairedLineup? {
        val available = squad.filterNot { it.isInjured(today) }
        if (available.size < Formation.PLAYERS_ON_PITCH) return null

        val problems = mutableListOf<String>()
        val formation = LineupJson.formation(saved.formation)
            ?: AutoLineup.bestFormation(squad, today).also {
                problems += "modulo salvato non riconosciuto, usato ${it.label}"
            }

        val byId = available.associateBy { it.id.value }
        val chosen = arrayOfNulls<Player>(formation.positions.size)
        val taken = HashSet<Long>(Formation.PLAYERS_ON_PITCH)
        var scartati = 0

        for (slot in LineupJson.slots(saved.slots)) {
            // Venduto, svincolato, in prestito altrove o infortunato: in tutti i casi non
            // c'e' e non serve distinguere, serve rimpiazzarlo.
            val player = byId[slot.playerId]
            if (player == null || slot.playerId in taken) {
                if (player == null) scartati++
                continue
            }

            val index = freeSlotFor(formation, chosen, slot.position) ?: continue
            chosen[index] = player
            taken += slot.playerId
        }

        if (scartati > 0) {
            problems += "$scartati titolari salvati non sono piu' disponibili"
        }

        val buchi = chosen.count { it == null }
        if (buchi > 0) problems += "$buchi caselle completate automaticamente"

        // Il completamento lo fa [LineupFitter], che e' lo stesso codice che gira sul
        // telefono quando si preme "completa": se lo rifacessimo qui, il campo mostrerebbe
        // una squadra e il tabellino ne racconterebbe un'altra.
        val completata = LineupFitter.fillHoles(formation, chosen.toList(), available, today)
        val slots = completata.withIndex().mapNotNull { (index, player) ->
            player?.let { LineupSlot(it, formation.positions[index]) }
        }
        if (slots.size < Formation.PLAYERS_ON_PITCH) return null
        val eleven = slots.map { it.player }

        // La panchina salvata prima, poi i migliori che restano: senza riserve gli ordini
        // condizionali e le sostituzioni per stanchezza non avrebbero nessuno da fare
        // entrare, e chi si e' dimenticato di comporla giocherebbe in dieci al 60'.
        val inCampo = eleven.mapTo(HashSet()) { it.id.value }
        val bench = (
            saved.bench.mapNotNull { byId[it] } +
                LineupFitter.bench(completata, available, LineupFitter.DEFAULT_BENCH, today)
            )
            .filterNot { it.id.value in inCampo }
            .distinctBy { it.id.value }
            .take(LineupFitter.DEFAULT_BENCH)

        return RepairedLineup(
            lineup = Lineup(
                formation = formation,
                slots = slots,
                bench = bench,
                // Un capitano ceduto non blocca la partita: si ricade sulla stessa scelta
                // che farebbe la formazione automatica.
                captainId = saved.captainId
                    ?.let { id -> eleven.firstOrNull { it.id.value == id }?.id }
                    ?: eleven.maxByOrNull { it.overall + it.age }?.id,
                penaltyTakerId = saved.penaltyTakerId
                    ?.let { id -> eleven.firstOrNull { it.id.value == id }?.id }
                    ?: eleven.maxByOrNull { it.attributes[Attr.TIRO] }?.id,
            ),
            tactics = LineupJson.tactics(saved.tactics),
            problems = problems,
        )
    }

    /**
     * La casella libera dove mettere un titolare salvato.
     *
     * Si cerca prima una casella del ruolo indicato, poi qualunque casella libera. Il
     * ruolo del modulo vince su quello salvato perche' e' il modulo a dire quante caselle
     * esistono: un salvataggio con tre attaccanti in un 4-4-2 va accomodato, non rifiutato.
     */
    private fun freeSlotFor(
        formation: Formation,
        chosen: Array<Player?>,
        position: Position?,
    ): Int? {
        if (position != null) {
            val exact = formation.positions.indices.firstOrNull { index ->
                chosen[index] == null && formation.positions[index] == position
            }
            if (exact != null) return exact
        }
        return chosen.indices.firstOrNull { chosen[it] == null }
    }

    private fun loadSquad(leagueId: Long, clubId: ClubId): List<Player> {
        val out = mutableListOf<Player>()
        connection.prepareStatement(
            """
            select p.* from players p
            join contracts c on c.player_id = p.id
            where c.club_id = ? and p.league_id = ? and c.squad = 'prima'
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, clubId.value)
            st.setLong(2, leagueId)
            st.executeQuery().use { rs -> while (rs.next()) out += readPlayer(rs) }
        }
        return out
    }

    private fun loadPlayerRow(playerId: PlayerId): PlayerRow? {
        connection.prepareStatement(
            "select p.*, c.club_id from players p " +
                "left join contracts c on c.player_id = p.id where p.id = ?",
        ).use { st ->
            st.setLong(1, playerId.value)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                return PlayerRow(readPlayer(rs), ClubId(rs.getLong("club_id")))
            }
        }
    }

    private fun readPlayer(rs: java.sql.ResultSet): Player {
        val attributes = JsonNode.parse(rs.getString("attributes"))
        val values = Attr.entries.associateWith { attributes[it.name].int(40) }

        return Player(
            id = PlayerId(rs.getLong("id")),
            firstName = rs.getString("first_name"),
            lastName = rs.getString("last_name"),
            nationality = rs.getString("nationality"),
            age = rs.getInt("age"),
            primaryPosition = Position.valueOf(rs.getString("primary_position")),
            secondaryPositions = (rs.getArray("secondary_positions")?.array as? Array<*>)
                ?.mapNotNull { name -> Position.entries.firstOrNull { it.name == name } }
                ?: emptyList(),
            attributes = Attributes.fromMap(values),
            weakFoot = rs.getInt("weak_foot"),
            skillStars = rs.getInt("skill_stars"),
            potentialMin = rs.getInt("potential_min"),
            potentialMax = rs.getInt("potential_max"),
            traits = (rs.getArray("traits")?.array as? Array<*>)
                ?.mapNotNull { name -> Trait.entries.firstOrNull { it.name == name } }
                ?.toSet() ?: emptySet(),
            stamina = rs.getInt("stamina"),
            morale = rs.getInt("morale"),
            form = rs.getInt("form"),
            experience = rs.getDouble("experience"),
            isCustom = rs.getBoolean("is_custom"),
            injuredUntil = rs.getInt("injured_until").takeIf { !rs.wasNull() }?.let(::MatchDay),
        )
    }

    /**
     * Le stelle dell'allenatore.
     *
     * Un club senza allenatore non e' un errore: si parte cosi'. Vale come un tre
     * stelle, cioe' la media — assumerne uno migliora, non avere nessuno non affonda.
     */
    private fun coachStarsOf(clubId: ClubId): Int =
        connection.prepareStatement(
            "select max(stars) from staff where club_id = ? and role = 'ALLENATORE'",
        ).use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1).takeIf { !rs.wasNull() && it > 0 } ?: 3 else 3
            }
        }

    private fun clubNameOf(clubId: ClubId): String =
        connection.prepareStatement("select name from clubs where id = ?").use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "Club ${clubId.value}" }
        }

    private fun loadPendingFixtures(leagueId: Long): List<Fixture> {
        val out = mutableListOf<Fixture>()
        connection.prepareStatement(
            """
            select id, competition_id, round, round_label, home_club_id, away_club_id,
                   match_day, kickoff, tie_id, is_second_leg
            from fixtures
            where league_id = ? and not played and kickoff is not null
            order by kickoff
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out += Fixture(
                        id = rs.getLong("id"),
                        competitionId = CompetitionId(rs.getLong("competition_id")),
                        round = rs.getInt("round"),
                        roundLabel = rs.getString("round_label"),
                        home = ClubId(rs.getLong("home_club_id")),
                        away = ClubId(rs.getLong("away_club_id")),
                        matchDay = MatchDay(rs.getInt("match_day")),
                        // In UTC, non nel fuso della macchina.
                        //
                        // `toLocalDateTime()` converte nel fuso predefinito della JVM, e
                        // `WorldTick` poi rilegge quel valore come se fosse UTC: le due
                        // cose si annullano solo se la macchina gira a UTC. Su GitHub
                        // Actions e' cosi', quindi funzionava; eseguito da un portatile
                        // italiano, il tick avrebbe giocato ogni partita con due ore di
                        // ritardo. Un difetto che si presenta solo fuori dal server e'
                        // peggio di uno che si presenta sempre.
                        kickoff = rs.getTimestamp("kickoff")?.toInstant()
                            ?.atZone(ZoneOffset.UTC)?.toLocalDateTime(),
                        tieId = rs.getString("tie_id"),
                        isSecondLeg = rs.getBoolean("is_second_leg"),
                    )
                }
            }
        }
        return out
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
