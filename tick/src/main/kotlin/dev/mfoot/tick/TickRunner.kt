package dev.mfoot.tick

import dev.mfoot.core.ai.AiInitiative
import dev.mfoot.core.ai.AiMarket
import dev.mfoot.core.ai.AiManager
import dev.mfoot.core.ai.AiMove
import dev.mfoot.core.ai.AiTurn
import dev.mfoot.core.ai.SquadAction
import dev.mfoot.core.ai.AiObsession
import dev.mfoot.core.ai.AiPersonality
import dev.mfoot.core.ai.AiScheduler
import dev.mfoot.core.ai.AiState
import dev.mfoot.core.ai.AiTactics
import dev.mfoot.core.calendar.CalendarSolver
import dev.mfoot.core.calendar.Competition
import dev.mfoot.core.calendar.CompetitionProgress
import dev.mfoot.core.calendar.CompetitionType
import dev.mfoot.core.calendar.Fixture
import dev.mfoot.core.calendar.FixtureGenerator
import dev.mfoot.core.calendar.FixtureResult
import dev.mfoot.core.calendar.FixtureState
import dev.mfoot.core.calendar.Round
import dev.mfoot.core.config.CalendarConfig
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
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.market.Auction
import dev.mfoot.core.market.AuctionRules
import dev.mfoot.core.market.AuctionTarget
import dev.mfoot.core.market.Bid
import dev.mfoot.core.market.ContestRules
import dev.mfoot.core.market.Listing
import dev.mfoot.core.market.Purchase
import dev.mfoot.core.market.PurchaseStatus
import dev.mfoot.core.market.Negotiation
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.market.OfferStatus
import dev.mfoot.core.market.OfferTerms
import dev.mfoot.core.market.TradeEvaluator
import dev.mfoot.core.market.TradeOffer
import dev.mfoot.core.match.AutoLineup
import dev.mfoot.core.match.ConditionalOrder
import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.Lineup
import dev.mfoot.core.match.LineupFitter
import dev.mfoot.core.match.LineupSlot
import dev.mfoot.core.match.MatchClock
import dev.mfoot.core.match.MatchDuty
import dev.mfoot.core.match.MatchEngine
import dev.mfoot.core.match.MatchResult
import dev.mfoot.core.match.OrderJson
import dev.mfoot.core.match.SetPieces
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
import dev.mfoot.core.rng.DeterministicRandom
import dev.mfoot.core.rng.MathX
import dev.mfoot.core.world.PotentialEstimator
import dev.mfoot.core.world.Scouting
import dev.mfoot.core.model.Trait
import dev.mfoot.core.tick.PausedFixture
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

    /**
     * Qualcosa e' andato storto, e il giro non puo' dirsi riuscito.
     *
     * ## Perche' esiste
     *
     * Perche' senza, non esisteva. Ogni lega viene elaborata in una transazione a se' e un
     * suo errore viene catturato, riportato indietro e messo in [failures] — cosa giusta,
     * perche' una lega rotta non deve fermare le altre. Ma poi `main` restituiva **zero
     * comunque**, quindi su GitHub l'esecuzione risultava **verde**.
     *
     * L'effetto: una lega che fallisce a ogni singolo giro — per un dato incoerente, una
     * migrazione mancante, un vincolo violato — resta ferma per giorni mentre il registro
     * delle esecuzioni e' una fila ininterrotta di spunte verdi. Nessuno va a leggere il
     * log di un giro riuscito.
     *
     * Il prezzo di questa scelta e' che una lega problematica tinge di rosso anche i giri
     * in cui tutte le altre sono andate bene. E' il prezzo giusto: un verde che non
     * significa niente e' peggio di un rosso che si impara a leggere.
     */
    val failed: Boolean get() = failures.isNotEmpty()

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
    /** Le notifiche sul telefono. Spento se manca la chiave: vedi [Push]. */
    private val push: Push = Push(env.fcmKey, env.fcmToken, env.fcmProject),
    /**
     * Il tempo che questo giro ha prima di essere ucciso da fuori. Vedi [TickBudget]:
     * senza, il tick veniva interrotto a transazione aperta e perdeva tutto.
     */
    private val budget: TickBudget = TickBudget(Instant.now(), java.time.Duration.ofSeconds(900)),
) {

    /** Dove sono finiti i secondi di questo giro. Si stampa alla fine. */
    private val cronometro = Cronometro()

    /*
     * Le due domande che si ripetevano centinaia di volte per giro.
     *
     * Il nome del club e le stelle dell'allenatore non cambiano **dentro** un giro, ma
     * venivano richiesti al database una volta per giocatore: `applyMatchAftermath` ne
     * faceva due per ogni giocatore sceso in campo, cioe' una cinquantina per partita.
     * Ognuna e' un'andata e ritorno verso Supabase — decine di millisecondi da un runner
     * di GitHub — e sono i millisecondi che facevano superare il timeout.
     *
     * Si svuotano a ogni lega: dentro una lega niente li invalida, fra due leghe non c'e'
     * niente da conservare.
     */
    private val nomiDeiClub = HashMap<Long, String>()
    private val stelleAllenatore = HashMap<Long, Int>()

    /*
     * IL MERCATO, LETTO UNA VOLTA INVECE CHE OTTANTA
     *
     * `compraDalListino` chiamava `loadListings` a ogni mossa: due query, di cui una che
     * riporta indietro **tutti** gli svincolati della lega — un migliaio di righe — e
     * un'altra che ricarica altrettanti giocatori. Andava bene finche' un'AI faceva una
     * mossa per risveglio. Da quando ne fa fino a otto, e con dieci club, sono ottanta
     * letture dello stesso identico elenco dentro lo stesso giro.
     *
     * E' una regressione introdotta insieme alla correzione delle AI ferme, ed e' il
     * genere di costo che non si vede scrivendo il codice: la funzione era gia' li' e
     * sembrava gratis.
     *
     * Qui il listino, le aste e gli acquisti contestabili si leggono **una volta** e
     * restano finche' qualcuno non li tocca. `mercatoCambiato()` li butta via, e lo chiama
     * ogni scrittura: e' volutamente grossolano — invalida tutti e tre anche quando ne
     * cambia uno — perche' un'invalidazione fine sbagliata farebbe comprare due volte lo
     * stesso giocatore, e il costo di sbagliare e' incomparabilmente piu' alto del costo
     * di una rilettura.
     */
    private var listinoInCache: List<Pair<Listing, Player>>? = null
    private var asteInCache: List<Auction>? = null
    private var acquistiInCache: List<Purchase>? = null

    /** I club della lega e chi di loro e' del computer: dentro un giro non cambiano mai. */
    private var clubInCache: List<ClubId>? = null
    private val eDelComputer = HashMap<Long, Boolean>()

    /**
     * Questo errore vale la pena riprovarlo?
     *
     * I due codici di PostgreSQL per «due transazioni si sono pestate i piedi»:
     * `40P01` e' il blocco incrociato, `40001` il fallimento di serializzazione. Sono gli
     * unici due che si risolvono da soli riprovando — tutto il resto (un vincolo violato,
     * una colonna che non c'e') si ripeterebbe identico.
     *
     * Si guarda anche la causa: JDBC incarta spesso l'errore vero dentro un altro.
     */
    private fun eUnIntoppoDiConcorrenza(e: Throwable?): Boolean {
        var corrente = e
        var passi = 0
        while (corrente != null && passi++ < 5) {
            val stato = (corrente as? java.sql.SQLException)?.sqlState
            if (stato == "40P01" || stato == "40001") return true
            corrente = corrente.cause
        }
        return false
    }

    /** Qualcuno ha scritto sul mercato: le liste in memoria non valgono piu'. */
    private fun mercatoCambiato() {
        listinoInCache = null
        asteInCache = null
        acquistiInCache = null
    }

    private fun svuotaLaCache() {
        nomiDeiClub.clear()
        stelleAllenatore.clear()
        clubInCache = null
        eDelComputer.clear()
        mercatoCambiato()
    }

    fun runAllLeagues(now: Instant): TickSummary {
        val summaries = mutableListOf<LeagueSummary>()
        val failures = mutableListOf<String>()

        for (league in loadActiveLeagues()) {
            // Il budget si controlla **fra** una lega e l'altra, mai dentro: una lega
            // lasciata a meta' e' una transazione annullata, cioe' esattamente il danno
            // che il budget esiste per evitare.
            if (budget.scaduto) {
                failures += "lega ${league.id} '${league.name}': saltata, tempo esaurito " +
                    "(${budget.descrivi()}). Riprende al giro prossimo."
                log("Tempo esaurito prima della lega ${league.id}: ${budget.descrivi()}")
                break
            }

            svuotaLaCache()

            try {
                summaries += runLeague(league, now)
                if (env.dryRun) connection.rollback() else connection.commit()
            } catch (e: Exception) {
                connection.rollback()

                /*
                 * UN INTOPPO FRA DUE PROCESSI NON E' UN GUASTO
                 *
                 * Visto il 2026-08-26, giro 425: `deadlock detected`. Il tick stava
                 * scrivendo su una lega mentre qualcun altro — un telefono che compra, il
                 * database che chiude qualcosa — toccava le stesse tabelle nell'ordine
                 * opposto. Postgres ne sceglie uno e lo annulla: e' come deve funzionare,
                 * non e' un errore del gioco.
                 *
                 * La lega era gia' tornata indietro per intero e sarebbe stata ripresa al
                 * giro dopo. Ma il giro dopo, sul piano gratuito, puo' voler dire
                 * quaranta minuti — e nel frattempo quel campionato sta fermo mentre gli
                 * altri quattordici vanno avanti.
                 *
                 * Un solo tentativo in piu': l'altro processo ha finito, l'ordine di
                 * blocco non si ripete quasi mai, e se si ripete allora e' un problema
                 * vero e va segnalato come tale.
                 */
                if (eUnIntoppoDiConcorrenza(e) && !env.dryRun) {
                    log("Lega ${league.id}: intoppo fra due processi, riprovo subito.")
                    try {
                        summaries += runLeague(league, now)
                        connection.commit()
                        continue
                    } catch (secondo: Exception) {
                        connection.rollback()
                        failures += "lega ${league.id} '${league.name}': ${secondo.message}"
                        log("Lega ${league.id} annullata anche al secondo tentativo: ${secondo.message}")
                        continue
                    }
                }

                failures += "lega ${league.id} '${league.name}': ${e.message}"
                log("Lega ${league.id} annullata e riportata indietro: ${e.message}")
            }
        }

        log("Tempi: ${cronometro.riepilogo()} (${budget.descrivi()})")
        return TickSummary(summaries, failures)
    }

    /**
     * I tempi del giro, dove si possono leggere senza scaricare i registri di GitHub.
     *
     * ## Perche' non basta stamparli
     *
     * Perche' il registro di un'esecuzione di GitHub Actions si scarica solo con un token,
     * e chi gestisce la lega guarda il database. Scritti in `tick_state.last_run_notes` si
     * leggono dal Table Editor di Supabase in due tocchi, e restano li' fino al giro dopo.
     *
     * E' la differenza fra sapere che un giro dura sette minuti e sapere **di cosa** sono
     * fatti: senza il secondo dato, ottimizzare significa indovinare.
     */
    private fun tempiDelGiro(): String = "tempi: ${cronometro.riepilogo()}"

    // --------------------------------------------------------- c'e' qualcosa da fare?

    /**
     * Tredici domande in una query sola.
     *
     * Ognuna e' un `exists`, e Postgres si ferma alla prima che risponde di si': su un
     * giro a vuoto costa una manciata di millisecondi e restituisce **una riga con un
     * booleano**, che in traffico e' nulla.
     *
     * L'elenco copre tutto cio' che puo' richiedere lavoro. Vale la pena leggerlo come
     * indice di quello che il tick fa davvero:
     */
    private fun qualcosaDaFare(league: LeagueRow, now: Instant, state: TickStateRow): Boolean {
        val oggi = league.currentMatchDay

        // Il riepilogo giornaliero non ha una riga da guardare: e' l'orologio a deciderlo.
        // Si controlla in Kotlin per non infilare la logica dell'orario dentro la query.
        val riepilogoDovuto = state.lastDigestAt == null ||
            java.time.Duration.between(state.lastDigestAt, now).toHours() >= 20

        if (riepilogoDovuto && notifier.enabled) return true

        return connection.prepareStatement(
            """
            select
                -- 1. una partita da cominciare
                exists (select 1 from fixtures
                        where league_id = ? and not played and resume_at is null
                          and kickoff is not null and kickoff <= now())
                -- 2. o una da riprendere dopo l'intervallo
             or exists (select 1 from fixtures
                        where league_id = ? and not played and resume_at is not null
                          and resume_at <= now())
                -- 3. un'asta scaduta
             or exists (select 1 from auctions
                        where league_id = ? and status = 'APERTA' and ends_at <= now())
                -- 4. una finestra di contestazione chiusa
             or exists (select 1 from purchases
                        where league_id = ? and status = 'IN_FINESTRA'
                          and contestable_until <= now())
                -- 5. un'AI con l'orario arrivato
             or exists (select 1 from ai_states
                        where league_id = ? and next_wake_at <= now())
                -- 6. un osservatore rientrato
             or exists (select 1 from scouting_missions
                        where league_id = ? and status = 'IN_CORSO' and ready_at <= now())
                -- 7. una proposta che aspetta la risposta di un club del computer
             or exists (select 1 from trades t join clubs c on c.id = t.to_club
                        where t.league_id = ? and t.status = 'PROPOSTA' and c.is_ai)
                -- 8. una trattativa scaduta
             or exists (select 1 from negotiations
                        where league_id = ? and status in ('IN_ATTESA', 'CONTROPROPOSTA')
                          and expires_at <= now())
                -- 9. un contratto in scadenza
             or exists (select 1 from contracts where league_id = ? and expires_on <= ?)
                -- 10. un prestito da restituire
             or exists (select 1 from loans where league_id = ? and active and ends_on <= ?)
                -- 11. un ragazzo che non si e' ancora allenato in questa giornata
             or exists (select 1 from contracts co join clubs cl on cl.id = co.club_id
                        where co.league_id = ? and cl.parent_club_id is not null
                          and coalesce(co.trained_on, -1) < ?)
                -- 12. un colloquio aperto che tocca a un club del computer chiudere
             or exists (select 1 from conversations cv join clubs c on c.id = cv.club_id
                        where cv.league_id = ? and cv.status = 'APERTA' and c.is_ai)
                -- 13. uno svincolato che non e' ancora a listino
             or exists (select 1 from players p
                        where p.league_id = ? and p.age >= ?
                          and not exists (select 1 from contracts c where c.player_id = p.id)
                          and not exists (select 1 from listings l
                                          where l.player_id = p.id and l.target_type = 'player'
                                            and l.status = 'APERTO'))
                -- 14. una competizione che ha finito tutte le sue partite e aspetta il
                --     turno successivo (o di essere dichiarata conclusa)
                --
                --     `finished_at is null` non e' un dettaglio: senza, una coppa vinta a
                --     ottobre terrebbe questa condizione vera per sempre, e la guardia
                --     smetterebbe di far risparmiare un solo giro.
             or exists (select 1 from competitions c
                        where c.league_id = ? and c.kind = 'UFFICIALE' and c.finished_at is null
                          and exists (select 1 from fixtures f where f.competition_id = c.id)
                          and not exists (select 1 from fixtures f
                                          where f.competition_id = c.id and not f.played))
            """.trimIndent(),
        ).use { st ->
            var i = 1
            repeat(8) { st.setLong(i++, league.id) }
            st.setLong(i++, league.id); st.setInt(i++, oggi)   // 9
            st.setLong(i++, league.id); st.setInt(i++, oggi)   // 10
            st.setLong(i++, league.id); st.setInt(i++, oggi)   // 11
            st.setLong(i++, league.id)                          // 12
            st.setLong(i++, league.id); st.setInt(i++, ETA_MINIMA_A_LISTINO) // 13
            st.setLong(i++, league.id)                          // 14
            st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
    }

    /**
     * Segna che il giro e' passato e non ha trovato niente.
     *
     * **Non tocca `last_processed_at`**, di proposito: se la guardia avesse dimenticato un
     * caso, avanzare la finestra lo perderebbe per sempre. Cosi' invece il primo giro che
     * trova qualcos'altro da fare recupera tutto l'intervallo, come fa gia' con i giri che
     * GitHub salta.
     */
    private fun segnaGiroAVuoto(leagueId: Long) {
        connection.prepareStatement(
            """
            insert into tick_state (league_id, last_run_at, last_run_notes)
            values (?, now(), 'niente da fare')
            on conflict (league_id) do update
              set last_run_at = now(), last_run_notes = 'niente da fare'
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeUpdate()
        }
    }

    // ------------------------------------------------------------------- una lega

    private fun runLeague(league: LeagueRow, now: Instant): LeagueSummary {
        val state = loadTickState(league.id)

        /*
         * PRIMA DI TUTTO: C'E' QUALCOSA DA FARE?
         *
         * ## Perche' questa domanda vale piu' di qualunque ottimizzazione
         *
         * Perche' la risposta e' quasi sempre no. Fra una partita e l'altra passano ore, e
         * un'AI si sveglia qualche volta al giorno: su un giro ogni cinque minuti — la
         * cadenza decisa il 2026-08-25 — la stragrande maggioranza non trova niente da
         * fare. Eppure caricava comunque milleduecento giocatori, i contratti, le aste, le
         * formazioni, gli stati delle AI, e poi scopriva che il piano era vuoto.
         *
         * Due conseguenze, e la seconda e' quella che ha imposto questa guardia:
         *
         * 1. **Il tempo.** Un giro a vuoto costava quanto un giro pieno. Con la guardia
         *    esce in un paio di secondi, e la media dei giri scende sotto i due minuti
         *    anche se quelli pieni ne durano tre.
         *
         * 2. **Il traffico.** Supabase, piano gratuito: cinque gigabyte in uscita al mese.
         *    Con una trentina di giri al giorno ne erano gia' andati 1,22. A 288 giri —
         *    cioe' uno ogni cinque minuti — la stessa lettura ripetuta dieci volte piu'
         *    spesso sfonderebbe il tetto in una settimana. La cadenza veloce e' possibile
         *    **solo** se i giri a vuoto costano quasi niente.
         *
         * ## Cosa succede se questa domanda sbaglia
         *
         * Sbagliare per eccesso — dire «c'e' qualcosa» quando non c'e' — costa un giro
         * normale, cioe' niente.
         *
         * Sbagliare per difetto — dimenticare un caso — **non perde** quel lavoro: lo
         * rimanda al primo giro che trova qualcos'altro da fare, e `last_processed_at` non
         * avanza, quindi la finestra si recupera per intero. E' la stessa proprieta' per
         * cui il tick sopravvive ai giri saltati da GitHub.
         *
         * Per questo la guardia non avanza `last_processed_at`: segna solo di essere
         * passata.
         */
        if (!cronometro.fase("guardia") { qualcosaDaFare(league, now, state) }) {
            segnaGiroAVuoto(league.id)
            return LeagueSummary(league.id, league.name, 0, 0, 0, listOf("niente da fare"))
        }

        // Le finestre di contestazione scadute senza opposizioni si chiudono da sole,
        // prima di ogni altra cosa: da quel momento quei giocatori sono definitivi, e
        // tutto quello che viene dopo — mercato delle AI compreso — deve saperlo.
        cronometro.fase("contestazioni") { confermaAcquistiScaduti(league) }

        // E il listino degli svincolati si allinea prima che chiunque compri: le AI di
        // questo giro devono vedere lo stesso mercato che vede l'app.
        cronometro.fase("listino") { aggiornaListinoSvincolati(league) }

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
            pausedFixtures = loadPausedFixtures(league.id),
            aiStates = loadAiStates(league.id),
            settledMatchDays = state.settledMatchDays,
            lastDigestAt = state.lastDigestAt,
        )

        val plan = cronometro.fase("pianificazione") { WorldTick.run(input) }
        var applied = 0
        var pending = 0
        val notes = plan.notes.toMutableList()

        var settled: MatchDay? = null

        /*
         * Quanto costa una partita, per decidere se c'e' tempo di cominciarla.
         *
         * Non e' una stima a occhio: una partita simulata scrive il risultato, le presenze
         * di tutti e ventidue piu' le panchine, la crescita di chi ha giocato e i premi.
         * Trenta secondi e' la fascia alta misurata, e la fascia alta e' il numero giusto:
         * sbagliare per eccesso rimanda una partita di un giro, sbagliare per difetto
         * annulla tutto il giro.
         */
        val costoPartita = java.time.Duration.ofSeconds(30)

        for (effect in plan.effects) {
            when (effect) {
                is TickEffect.ChiudiAsta -> {
                    closeAuction(league, effect.auctionId)
                    applied++
                }

                is TickEffect.RiprendiPartita -> {
                    if (!budget.consentito(costoPartita)) {
                        pending++
                        notes += "Partita ${effect.fixture.id}: ripresa rinviata, tempo esaurito."
                    } else if (cronometro.fase("partite") { resumeMatch(league, effect.fixture, notes) }) {
                        applied++
                    } else {
                        pending++
                        notes += "Partita ${effect.fixture.id}: ripresa rinviata."
                    }
                }

                is TickEffect.SimulaPartita -> {
                    // Il rinvio per mancanza di tempo e' identico al rinvio per rosa
                    // insufficiente, e non e' un caso: il tick e' costruito per riprendere
                    // dall'ultimo punto elaborato, quindi una partita non cominciata e'
                    // semplicemente una partita che si gioca al giro dopo.
                    if (!budget.consentito(costoPartita)) {
                        pending++
                        notes += "Partita ${effect.fixture.id} rinviata: tempo del giro esaurito."
                    } else if (cronometro.fase("partite") { playMatch(league, effect.fixture, notes) }) {
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
                    cronometro.fase("mercato AI") { wakeAi(league, effect.clubId, now, today) }
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
                    recoverStamina(league, now)
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

        // Il recupero e' tempo che passa, quindi si accredita a ogni giro e non solo
        // quando cambia la giornata. Con le partite ogni due ore il vecchio aggancio alla
        // giornata lasciava le gambe ferme per mezza giornata e poi restituiva tutto in
        // blocco; e in una lega senza partite in calendario non recuperava **mai**.
        // Chiamarlo qui non raddoppia niente: sotto l'ora accreditata non fa nulla.
        cronometro.fase("recupero") { recoverStamina(league, now) }

        // Le coppe camminano da sole. Sta **subito dopo le partite** perche' e' l'ultima
        // partita di un turno a farlo finire: aspettare il giro successivo vorrebbe dire
        // che ogni turno di coppa parte con cinque minuti di ritardo sul dovuto.
        notes += cronometro.fase("competizioni") { avanzaLeCompetizioni(league) }

        // Le proposte di scambio si guardano a ogni giro, non al risveglio dell'AI.
        //
        // Il risveglio scaglionato serve a impedire lo sciame sul mercato: e' una difesa
        // contro venti club che si buttano sullo stesso giocatore. Una proposta di scambio
        // e' l'opposto — arriva **da una persona, a un club solo** — e farla aspettare fino
        // a domani mattina perche' quel club dorme non protegge nessuno: fa solo credere
        // che l'avversario ti stia ignorando.
        notes += cronometro.fase("scambi") { rispondiAgliScambi(league) }

        // Le promesse si controllano dopo aver giocato, non prima: e' l'ultima partita del
        // giro a poter completare un "titolare per tre partite", e rimandare al giro
        // successivo vorrebbe dire dichiarare tradita una promessa appena mantenuta.
        notes += cronometro.fase("promesse") { verificaLePromesse(league) }

        // I colloqui si aprono per ultimi, dopo le partite e dopo le promesse: e' l'unico
        // momento in cui i fatti della giornata sono tutti scritti. Aprirli prima
        // significherebbe dire a un giocatore "hai giocato male" prima di sapere come ha
        // giocato, e non dirgli niente della promessa che gli hai appena tradito.
        notes += cronometro.fase("colloqui") { apriIColloqui(league) }

        // I club del computer rispondono subito ai propri: aspettare non avrebbe senso,
        // e un colloquio aperto blocca quello successivo.
        notes += cronometro.fase("colloqui AI") { rispondiAiColloqui(league) }

        // La Primavera si allena una volta per giornata: la colonna `trained_on` sul
        // contratto e cio che impedisce dodici allenamenti l ora.
        notes += cronometro.fase("primavera") { allenaLaPrimavera(league, MatchDay(league.currentMatchDay)) }

        // E chi ha compiuto gli anni sale: e la scadenza che rende la Primavera una
        // scelta invece di un deposito.
        notes += cronometro.fase("promozioni") { promuoviChiEFuoriEta(league) }

        // Gli osservatori tornati dal viaggio.
        notes += cronometro.fase("osservatori") { risolviLeMissioni(league, now) }

        // Lo scouting dopo le partite: i minuti visti sono appena cambiati.
        notes += cronometro.fase("scouting") { aggiornaLoScouting(league, dopoUnaPartita = settled != null) }

        // La consegna per ultima, quando tutto quello che e' successo in questo giro e'
        // gia' scritto: cosi' una notifica non puo' raccontare un fatto che la transazione
        // annullera' un attimo dopo.
        notes += cronometro.fase("notifiche") { consegnaLeNotifiche(league, riepilogo) }

        // I tempi in coda alle note: `last_run_notes` e' troncato a duemila caratteri e
        // quello che conta di piu' non deve essere la prima cosa a cadere.
        saveTickState(
            league.id,
            plan.processedUpTo,
            (notes + tempiDelGiro()).joinToString(" | "),
            settled,
        )

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

    // ---------------------------------------------------------------------- primavera

    /**
     * I giovani in Primavera si allenano.
     *
     * ## Perche' e' il pezzo che rende la Primavera una cosa
     *
     * Senza, e' un magazzino: ci si parcheggia un diciassettenne e lo si ritrova
     * diciassettenne. Tutta la crescita del gioco passa dalle partite, e chi non scende in
     * campo non ne vede niente — quindi mandare un ragazzo in Primavera sarebbe solo un
     * modo di rinunciarci.
     *
     * ## Perche' una volta per giornata e non a ogni giro
     *
     * Il tick passa ogni cinque minuti. Allenare a ogni passaggio farebbe crescere un
     * ragazzo di dodici allenamenti l'ora, cioe' piu' in un pomeriggio che in una stagione
     * di partite. E' lo stesso difetto per cui le promesse si mantenevano da sole in un
     * quarto d'ora: l'unita' di tempo del gioco e' la **giornata**, e tutto quello che
     * cresce deve crescere con quella.
     */
    private fun allenaLaPrimavera(league: LeagueRow, oggi: MatchDay): List<String> {
        if (!league.config.rules.youthTeamEnabled) return emptyList()

        val giovani = caricaPrimavera(league.id, oggi)
        if (giovani.isEmpty()) return emptyList()

        var cresciuti = 0
        connection.prepareStatement(
            """
            update players
            set experience = ?, attributes = ?::jsonb, overall = ?
            where id = ?
            """.trimIndent(),
        ).use { st ->
            for ((player, clubId) in giovani) {
                val esito = GrowthEngine.trainYouth(
                    player,
                    GrowthContext(league.config, coachStarsOf(clubId), isYouthMatch = true),
                )

                st.setDouble(1, esito.player.experience)
                st.setString(2, MatchJson.attributes(esito.player))
                st.setInt(3, esito.player.overall)
                st.setLong(4, player.id.value)
                st.addBatch()
                if (esito.changes.isNotEmpty()) cresciuti++
            }
            st.executeBatch()
        }

        connection.prepareStatement(
            "update contracts set trained_on = ? where club_id in " +
                "(select id from clubs where league_id = ? and parent_club_id is not null)",
        ).use { st ->
            st.setInt(1, oggi.value)
            st.setLong(2, league.id)
            st.executeUpdate()
        }

        return if (cresciuti > 0) {
            listOf("$cresciuti giovani sono cresciuti in Primavera.")
        } else {
            emptyList()
        }
    }

    /** I giovani che non si sono ancora allenati in questa giornata. */
    private fun caricaPrimavera(leagueId: Long, oggi: MatchDay): List<Pair<Player, ClubId>> {
        val out = mutableListOf<Pair<Player, ClubId>>()
        connection.prepareStatement(
            """
            select p.*, c.club_id as squadra, cl.parent_club_id as padre
            from players p
            join contracts c on c.player_id = p.id
            join clubs cl on cl.id = c.club_id
            where p.league_id = ? and cl.parent_club_id is not null
              and coalesce(c.trained_on, -1) < ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setInt(2, oggi.value)
            st.executeQuery().use { rs ->
                while (rs.next()) out += readPlayer(rs) to ClubId(rs.getLong("squadra"))
            }
        }
        return out
    }

    // ------------------------------------------------------------- lo staff dell'AI

    /**
     * I club del computer si prendono lo staff che avanza, e mandano gli osservatori in giro.
     *
     * ## Perche' non passano dalle aste
     *
     * Perche' un'AI che si mettesse a competere anche sullo staff raddoppierebbe il rumore
     * del mercato per una decisione che nessuno vede. Prende quindi solo dal **fondo del
     * listino** — fino a tre stelle — e i quattro e cinque stelle restano liberi per le
     * aste, dove la guerra vale la pena di essere fatta.
     *
     * ## Perche' devono comunque scoutare
     *
     * Perche' gli under 20 si trovano solo cosi'. Senza, gli otto club del computer
     * starebbero a guardare mentre gli umani si prendono ogni talento del mondo, e in tre
     * stagioni la lega sarebbe decisa.
     */
    private fun staffEMissioniDellAi(league: LeagueRow, club: Club): Boolean {
        val clubId = club.id
        val mancante = ruoloMancante(clubId)

        // Se manca un ruolo, lo si assume a prezzo fisso: la stessa strada che percorre un
        // umano, con lo stesso prezzo. Prima erano due strade diverse — un'asta sui quattro
        // e cinque stelle, e il fondo del listino preso **gratis** — e nessuna delle due
        // era quella degli umani.
        if (mancante != null && assumiStaffAi(league, club, mancante)) return true

        val osservatore = osservatoreLibero(clubId) ?: return false
        // Paese e ruolo deterministici sul club e sull osservatore: due AI non partono
        // mai per lo stesso paese nello stesso momento, e la stessa AI non ci torna due
        // volte di fila.
        val rng = DeterministicRandom(
            league.config.setup.worldSeed * 131L + clubId.value * 17L + osservatore.first,
        )
        val paesi = league.config.world.nationalities
        val paese = paesi[rng.nextInt(paesi.size)]
        val ruolo = Position.entries[rng.nextInt(Position.entries.size)]

        // La durata la decide [Scouting], in `core`, dalla configurazione della lega.
        //
        // Qui c'era la formula scritta a mano — `8 + (5 - stelle) * 10` **ore** — che era
        // una terza copia della stessa regola: una in SQL, una qui, e nessuna delle due
        // toccabile dall'admin. Dal 2026-08-25 il massimo e' due ore e i due estremi
        // stanno in `rules.scoutMinutesBest/Worst`.
        val minuti = Scouting.missionMinutes(osservatore.second, league.config.rules)

        connection.prepareStatement(
            """
            insert into scouting_missions (league_id, club_id, staff_id, country, position, ready_at)
            values (?, ?, ?, ?, ?, now() + make_interval(mins => ?))
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setLong(2, clubId.value)
            st.setLong(3, osservatore.first)
            st.setString(4, paese)
            st.setString(5, ruolo.name)
            st.setInt(6, minuti)
            st.executeUpdate()
        }
        return true
    }

    /**
     * Il club del computer assume, a prezzo fisso come chiunque altro.
     *
     * ## Perche' non apre piu' un'asta
     *
     * Perche' dal 2026-08-25 lo staff **non si batte all'asta**, si assume e basta: la
     * regola vale per gli umani e non poteva valere solo per loro. Un'AI che continuasse a
     * indire aste su una merce che tutti gli altri comprano con un tocco creerebbe una
     * seconda economia parallela, con prezzi diversi, sulla stessa cosa.
     *
     * E quelle aste facevano un danno concreto: `targetId` di un'asta sullo staff e' un id
     * della tabella `staff`, che ha una sequenza sua. Un'asta aperta sull'allenatore numero
     * 7 spegneva il pulsante «Metti all'asta» sul **giocatore** numero 7 nell'app, e nessuno
     * capiva perche'.
     *
     * ## Perche' adesso paga
     *
     * Perche' prima no. `assumiDalFondo` faceva `update staff set club_id = ...` e
     * nient'altro: i club del computer si prendevano allenatori e preparatori **gratis**,
     * mentre un umano li pagava. Non era una scelta di bilanciamento, era una riga
     * mancante.
     *
     * ## Perche' fino a tre stelle e non oltre
     *
     * Perche' il tetto di spesa dell'AI durante l'allestimento serve alla rosa, e un
     * allenatore da cinque stelle costa quanto un titolare. Prende il meglio che puo'
     * permettersi restando sotto un ventesimo della cassa: cosi' l'organigramma si riempie
     * senza che il computer si giochi il mercato su un preparatore.
     */
    private fun assumiStaffAi(league: LeagueRow, club: Club, ruolo: String): Boolean {
        val disponibili = club.credits - club.committedCredits
        // Un ventesimo della cassa: lo staff non deve mai competere con la rosa.
        val tetto = disponibili / 20

        val candidati = mutableListOf<Pair<Long, Int>>()
        connection.prepareStatement(
            """
            select id, stars from staff
            where league_id = ? and club_id is null and role = ?
            order by stars desc limit 10
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setString(2, ruolo)
            st.executeQuery().use { rs ->
                while (rs.next()) candidati += rs.getLong("id") to rs.getInt("stars")
            }
        }

        // Il migliore che sta dentro il tetto. La lista e' gia' ordinata per stelle, quindi
        // il primo che passa il filtro e' quello.
        val scelto = candidati.firstOrNull { (_, stelle) ->
            Valuation.staffPrice(stelle, league.config) <= tetto
        } ?: return false

        val prezzo = Valuation.staffPrice(scelto.second, league.config)

        // `and club_id is null` non e' pleonastico: fra la lettura e la scrittura un'altra
        // AI dello stesso giro puo' averlo gia' preso, e due club con lo stesso allenatore
        // e' esattamente il genere di corsa critica che la transazione per lega esiste per
        // impedire.
        val preso = connection.prepareStatement(
            "update staff set club_id = ? where id = ? and club_id is null",
        ).use { st ->
            st.setLong(1, club.id.value)
            st.setLong(2, scelto.first)
            st.executeUpdate() > 0
        }
        if (!preso) return false

        connection.prepareStatement("update clubs set credits = credits - ? where id = ?")
            .use { st ->
                st.setInt(1, prezzo)
                st.setLong(2, club.id.value)
                st.executeUpdate()
            }

        log("Lega ${league.id}: ${club.name} assume un $ruolo da ${scelto.second} stelle per $prezzo.")
        return true
    }


    /** Ruolo e stelle di un membro dello staff, per valutarne l asta. */
    private fun staffInAsta(staffId: StaffId): Pair<String, Int>? =
        connection.prepareStatement("select role, stars from staff where id = ?").use { st ->
            st.setLong(1, staffId.value)
            st.executeQuery().use { rs ->
                if (rs.next()) rs.getString("role") to rs.getInt("stars") else null
            }
        }

    /** Un ruolo dello staff che questo club non ha ancora. */
    private fun ruoloMancante(clubId: ClubId): String? =
        connection.prepareStatement(
            """
            select r.ruolo from (values ('ALLENATORE'),('PREPARATORE'),('OSSERVATORE')) as r(ruolo)
            where not exists (
                select 1 from staff s where s.club_id = ? and s.role = r.ruolo
            )
            limit 1
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }


    /** Un osservatore di questo club che non e' gia' in viaggio. */
    private fun osservatoreLibero(clubId: ClubId): Pair<Long, Int>? =
        connection.prepareStatement(
            """
            select s.id, s.stars from staff s
            where s.club_id = ? and s.role = 'OSSERVATORE'
              and not exists (
                  select 1 from scouting_missions m
                  where m.staff_id = s.id and m.status = 'IN_CORSO'
              )
            order by s.stars desc limit 1
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("id") to rs.getInt("stars") else null
            }
        }

    // ------------------------------------------------------------ missioni di scouting

    /**
     * Gli osservatori tornati dal viaggio.
     *
     * ## Perche' il risultato lo decide il tick
     *
     * Perche' "trovami un giovane con buon potenziale" e' una domanda sul **potenziale
     * vero**, che non lascia mai il server. Il database sa soltanto quando la missione
     * scade; chi la risolve e' questo pezzo, che i valori veri li ha in mano.
     *
     * ## Come pescano le stelle
     *
     * Non sull'overall: un diciassettenne forte oggi e' un diciassettenne che non
     * migliorera' molto. Le stelle pescano sul **potenziale**, ed e' per questo che un
     * cinque stelle riporta uno da 52 che arrivera' a 88 e un una stella uno da 58 che si
     * ferma a 64. Chi guarda i due elenchi senza sapere le stelle sceglierebbe il secondo.
     */
    private fun risolviLeMissioni(league: LeagueRow, now: Instant): List<String> {
        data class Missione(
            val id: Long,
            val clubId: ClubId,
            val country: String,
            val position: String,
            val stars: Int,
        )

        val scadute = mutableListOf<Missione>()
        connection.prepareStatement(
            """
            select m.id, m.club_id, m.country, m.position, s.stars
            from scouting_missions m
            join staff s on s.id = m.staff_id
            where m.league_id = ? and m.status = 'IN_CORSO' and m.ready_at <= ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setTimestamp(2, java.sql.Timestamp.from(now))
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    scadute += Missione(
                        id = rs.getLong("id"),
                        clubId = ClubId(rs.getLong("club_id")),
                        country = rs.getString("country"),
                        position = rs.getString("position"),
                        stars = rs.getInt("stars"),
                    )
                }
            }
        }
        if (scadute.isEmpty()) return emptyList()

        val note = mutableListOf<String>()
        for (m in scadute) {
            val candidati = giovaniLiberi(league.id, m.country, m.position)
            if (candidati.isEmpty()) {
                chiudiMissione(m.id, "A_VUOTO", null)
                notify(
                    league.id, padreDi(m.clubId),
                    "L'osservatore torna dal ${m.country} a mani vuote: non c'è rimasto " +
                        "nessun ${m.position} sotto i vent'anni.",
                    kind = "scouting", urgency = "riepilogo",
                )
                note += "missione a vuoto in ${m.country}."
                continue
            }

            // Quanto in alto pesca, sul potenziale. A cinque stelle il migliore che c'e';
            // a una, uno a caso fra i peggiori.
            val ordinati = candidati.sortedByDescending { it.potentialMax }
            val ampiezza = MathX.lerp(1.0, 0.12, (m.stars - 1) / 4.0)
            val finestra = StrictMath.round(ordinati.size * ampiezza).toInt().coerceAtLeast(1)
            val rng = DeterministicRandom(league.config.setup.worldSeed * 61L + m.id)
            val scelto = ordinati[rng.nextInt(finestra)]

            assignPlayer(league, scelto.id, primaveraDi(m.clubId), price = 0)
            chiudiMissione(m.id, "CONCLUSA", scelto.id.value)

            notify(
                league.id, padreDi(m.clubId),
                "Dal ${m.country}: ${scelto.shortName}, ${scelto.age} anni, " +
                    "${scelto.primaryPosition.short}. è in Primavera.",
                kind = "scouting", urgency = "immediata",
            )
            note += "${scelto.shortName} trovato in ${m.country}."
        }
        return note
    }

    /**
     * Gli under 20 liberi di quel paese e di quel ruolo.
     *
     * Sono l'unico modo di prendere un giovane: le aste li rifiutano. Se il paese e' finito
     * la missione torna a vuoto, ed e' un esito che deve poter succedere — altrimenti "vai
     * in Brasile" e' una decorazione e la mappa del mondo non conta niente.
     */
    private fun giovaniLiberi(leagueId: Long, country: String, position: String): List<Player> {
        val out = mutableListOf<Player>()
        connection.prepareStatement(
            """
            select p.* from players p
            left join contracts c on c.player_id = p.id
            where p.league_id = ? and c.player_id is null and not p.is_custom
              and p.age < 20 and p.nationality = ? and p.primary_position = ?
            limit 60
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setString(2, country)
            st.setString(3, position)
            st.executeQuery().use { rs -> while (rs.next()) out += readPlayer(rs) }
        }
        return out
    }

    /** Dove finisce chi viene trovato: nella Primavera, fondandola se non c'e'. */
    private fun primaveraDi(clubId: ClubId): ClubId {
        val padre = padreDi(clubId)
        connection.prepareStatement(
            "select id from clubs where parent_club_id = ?",
        ).use { st ->
            st.setLong(1, padre.value)
            st.executeQuery().use { rs -> if (rs.next()) return ClubId(rs.getLong(1)) }
        }
        // Nessuna Primavera: resta in prima squadra. Meglio un giocatore in piu' del
        // previsto che un giocatore trovato e perso.
        return padre
    }

    private fun padreDi(clubId: ClubId): ClubId =
        connection.prepareStatement("select coalesce(parent_club_id, id) from clubs where id = ?")
            .use { st ->
                st.setLong(1, clubId.value)
                st.executeQuery().use { rs -> if (rs.next()) ClubId(rs.getLong(1)) else clubId }
            }

    private fun chiudiMissione(id: Long, stato: String, playerId: Long?) {
        connection.prepareStatement(
            "update scouting_missions set status = ?, found_player_id = ?, closed_at = now() " +
                "where id = ?",
        ).use { st ->
            st.setString(1, stato)
            if (playerId == null) st.setNull(2, java.sql.Types.BIGINT) else st.setLong(2, playerId)
            st.setLong(3, id)
            st.executeUpdate()
        }
    }

    // ----------------------------------------------------------------------- scouting

    /**
     * Aggiorna quanto ogni club umano sa dei giocatori che gli interessano.
     *
     * ## Perche' il conto sta qui e non sul telefono
     *
     * Perche' stringere la forbice vuol dire avvicinarla al valore vero, e il valore vero
     * non lascia mai il server: `players_public` non contiene i potenziali proprio per
     * questo. Un client che calcolasse la stima ristretta dovrebbe prima ricevere la
     * verita', e allora tanto varrebbe mostrarla.
     *
     * ## Chi si osserva
     *
     * I propri giocatori, di cui si accumulano minuti guardandoli giocare, e chi e'
     * all'asta, su cui lavorano solo gli osservatori. Non tutti i milletrecento del mondo:
     * la forbice serve a decidere se tenere un ragazzo o se puntarci sopra, e su chi non si
     * puo' ne' schierare ne' comprare non serve a niente.
     *
     * ## Solo per i club umani
     *
     * L'AI non legge questa tabella: valuta con `PotentialEstimator` al momento di
     * decidere, e a conoscenza zero. E' voluto — un'AI che sapesse piu' di te sarebbe
     * un'AI che bara — ed evita di calcolare venti volte qualcosa che nessuno guarda.
     */
    private fun aggiornaLoScouting(league: LeagueRow, dopoUnaPartita: Boolean): List<String> {
        val clubUmani = loadHumanClubIds(league.id)
        if (clubUmani.isEmpty()) return emptyList()

        // Si ricalcola dopo una partita — perche' allora i minuti visti sono cambiati — e
        // quando mancano righe, cioe' dopo un acquisto o all'apertura di un'asta nuova.
        // Ogni cinque minuti sarebbe lavoro sprecato: la conoscenza non cambia da sola.
        if (!dopoUnaPartita && !mancanoStimeDaCalcolare(league.id)) return emptyList()

        // I giocatori all'asta si leggono **una volta**, non una per club umano: era una
        // query per ogni asta moltiplicata per ogni club, dentro due cicli annidati.
        val inAsta = loadPlayerRows(
            loadOpenAuctions(league.id)
                .mapNotNull { (it.target as? AuctionTarget.ForPlayer)?.playerId?.value },
        ).values.map { it.player }
        var scritte = 0

        connection.prepareStatement(
            """
            insert into scouting (club_id, player_id, league_id, est_min, est_max,
                                  knowledge, updated_at)
            values (?, ?, ?, ?, ?, ?, now())
            on conflict (club_id, player_id) do update
              set est_min = excluded.est_min,
                  est_max = excluded.est_max,
                  knowledge = excluded.knowledge,
                  updated_at = now()
            """.trimIndent(),
        ).use { st ->
            for (clubId in clubUmani) {
                val precisione = precisioneOsservatori(clubId)
                val minutiVisti = minutiVistiDa(clubId)

                val squad = loadSquad(league.id, clubId)
                val suoi = squad.mapTo(HashSet()) { it.id }
                val altri = inAsta.filterNot { it.id in suoi }

                for (player in squad + altri) {
                    val minuti = minutiVisti[player.id.value] ?: 0
                    val stima = PotentialEstimator.estimate(
                        player = player,
                        observerId = clubId.value,
                        minutesObserved = minuti,
                        scoutAccuracy = precisione,
                    )
                    val conoscenza = PotentialEstimator.knowledge(minuti, precisione)

                    st.setLong(1, clubId.value)
                    st.setLong(2, player.id.value)
                    st.setLong(3, league.id)
                    st.setInt(4, stima.first)
                    st.setInt(5, stima.last)
                    st.setInt(6, StrictMath.round(conoscenza * 100).toInt())
                    st.addBatch()
                    scritte++
                }
            }
            st.executeBatch()
        }

        return if (scritte > 0) listOf("$scritte stime di scouting aggiornate.") else emptyList()
    }

    private fun loadHumanClubIds(leagueId: Long): List<ClubId> {
        val out = mutableListOf<ClubId>()
        connection.prepareStatement(
            "select id from clubs where league_id = ? and owner_user_id is not null",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> while (rs.next()) out += ClubId(rs.getLong(1)) }
        }
        return out
    }

    /**
     * Quanto valgono gli osservatori di questo club, da 0 a 1.
     *
     * Un solo osservatore a cinque stelle non deve bastare a sapere tutto: pesa 0,8, e
     * `PotentialEstimator` gli da' comunque meno peso dei minuti visti. Guardare un
     * ragazzo giocare venti partite deve valere piu' che pagare qualcuno perche' lo
     * guardi per te.
     */
    private fun precisioneOsservatori(clubId: ClubId): Double {
        val stelle = connection.prepareStatement(
            "select max(stars) from staff where club_id = ? and role = 'OSSERVATORE'",
        ).use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1).takeIf { !rs.wasNull() } ?: 0 else 0
            }
        }
        if (stelle <= 1) return 0.0
        return (stelle - 1) / 4.0 * 0.8
    }

    /**
     * Quanti minuti questo club ha visto giocare ognuno dei suoi.
     *
     * Le presenze rendono possibile la domanda: prima esisteva solo `players.minutes_observed`,
     * che sono i minuti giocati **in totale** da quel giocatore, per chiunque. Un giocatore
     * comprato ieri con duemila minuti alle spalle sarebbe stato "conosciutissimo" dal suo
     * nuovo club, che non lo ha mai visto in campo.
     */
    private fun minutiVistiDa(clubId: ClubId): Map<Long, Int> {
        val out = mutableMapOf<Long, Int>()
        connection.prepareStatement(
            "select player_id, sum(minutes) from appearances where club_id = ? group by player_id",
        ).use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs -> while (rs.next()) out[rs.getLong(1)] = rs.getInt(2) }
        }
        return out
    }

    /** C'e' qualcuno, fra i tesserati dei club umani, per cui non esiste ancora una stima? */
    private fun mancanoStimeDaCalcolare(leagueId: Long): Boolean =
        connection.prepareStatement(
            """
            select 1
            from contracts c
            join clubs cl on cl.id = c.club_id
            where cl.league_id = ? and cl.owner_user_id is not null
              and not exists (
                  select 1 from scouting s
                  where s.club_id = c.club_id and s.player_id = c.player_id
              )
            limit 1
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { it.next() }
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
        if (env.dryRun) return emptyList()

        val note = mutableListOf<String>()

        // Il telefono per primo, ed e' il canale che conta: Telegram nel gruppo del
        // proprietario non lo usa nessuno, detto da lui il 2026-08-26.
        if (push.enabled && league.config.notifications.immediateEnabled) {
            note += mandaSuiTelefoni(league)
        }

        if (!notifier.enabled) return note

        /*
         * LE IMMEDIATE PARTONO INSIEME, NON UNA PER UNA
         *
         * Erano fino a venti richieste HTTPS separate verso Telegram, in fila, ognuna con
         * quindici secondi di timeout. Due costi, e il secondo e' peggiore del primo:
         *
         * - il tempo. Telegram limita a circa venti messaggi al minuto **per chat**:
         *   superata la soglia risponde 429 e le richieste rallentano fino a fermarsi. Un
         *   giro con venti notifiche poteva passare piu' tempo ad aspettare Telegram che a
         *   far girare il mondo — e da quando le AI si muovono davvero, venti notifiche in
         *   un giro non sono un caso limite.
         * - il gruppo. Venti messaggi di fila su una chat dove leggono dodici amici e'
         *   esattamente il rumore che il sistema a due canali esiste per evitare.
         *
         * Un messaggio solo con dentro tutte le notizie del giro resta **immediato** — e'
         * lo stesso giro, la stessa manciata di secondi — e si legge meglio.
         *
         * Si segna consegnato solo se il messaggio e' partito: se Telegram non risponde le
         * righe restano li' e ci riprova il giro dopo.
         */
        if (league.config.notifications.immediateEnabled) {
            val immediate = caricaDaConsegnare(league.id, "immediata", limite = 20)
            if (immediate.isNotEmpty()) {
                val testo = if (immediate.size == 1) {
                    "<b>${escapeHtml(league.name)}</b>\n${immediate.first().perIlGruppo()}"
                } else {
                    buildString {
                        append("<b>${escapeHtml(league.name)}</b>\n")
                        immediate.forEach { append("\n• ").append(it.perIlGruppo()) }
                    }
                }

                if (notifier.send(testo)) {
                    immediate.forEach { segnaConsegnata(it.id) }
                    note += "${immediate.size} notifiche mandate in un messaggio"
                }
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

    /**
     * Le notifiche immediate, sul telefono di chi le riguarda.
     *
     * ## Un messaggio per persona, non uno per evento
     *
     * E' la stessa lezione pagata con Telegram, applicata prima di sbagliarla di nuovo:
     * cinque cose successe nello stesso giro sono **un** messaggio con cinque righe, non
     * cinque messaggi. Chi riceve cinque vibrazioni di fila silenzia l'app, e a quel punto
     * il canale e' morto come lo era prima.
     *
     * ## Chi riceve cosa
     *
     * Una notifica con un club dentro va **al proprietario di quel club**: una proposta di
     * scambio riguarda chi la riceve, non tutta la lega. Una notifica senza club — una
     * giornata giocata, il mercato che apre — va a tutti gli iscritti.
     *
     * Le Row Level Security non c'entrano qui: il tick gira come proprietario del
     * database. E' questa query a decidere chi vede cosa, ed e' il motivo per cui il testo
     * non viene mai mandato a chi non lo riguarda.
     *
     * ## Cosa succede se non c'e' nessuno da avvisare
     *
     * La riga viene segnata consegnata lo stesso. Nessuno ha registrato un telefono, quindi
     * non c'e' niente da riprovare: lasciarla in attesa vorrebbe dire riprovarla a ogni
     * giro per sempre, e la tabella crescerebbe senza che nessuno la legga.
     */
    private fun mandaSuiTelefoni(league: LeagueRow): List<String> {
        val immediate = caricaDaConsegnare(league.id, "immediata", limite = 40)
        if (immediate.isEmpty()) return emptyList()

        val testi = immediate.associate { it.id to it.perIlGruppo() }

        // gettone -> gli id delle notifiche che quel telefono deve ricevere
        val perTelefono = mutableMapOf<String, MutableList<Long>>()

        connection.prepareStatement(
            """
            select n.id, d.token
            from notifications n
            join clubs c on c.id = n.club_id
            join device_tokens d on d.user_id = c.owner_user_id
            where n.id = any(?)
            union
            select n.id, d.token
            from notifications n
            join league_members m on m.league_id = n.league_id
            join device_tokens d on d.user_id = m.user_id
            where n.id = any(?) and n.club_id is null
            """.trimIndent(),
        ).use { st ->
            val ids = connection.createArrayOf("bigint", immediate.map { it.id }.toTypedArray())
            st.setArray(1, ids)
            st.setArray(2, ids)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    perTelefono.getOrPut(rs.getString("token")) { mutableListOf() } += rs.getLong("id")
                }
            }
        }

        val consegnate = mutableSetOf<Long>()
        var telefoni = 0

        perTelefono.forEach { (gettone, quali) ->
            val righe = quali.mapNotNull { testi[it] }
            if (righe.isEmpty()) return@forEach

            val testo = if (righe.size == 1) righe.first() else righe.joinToString("\n") { "• $it" }

            if (push.manda(gettone, league.name, testo)) {
                consegnate += quali
                telefoni++
            }
        }

        // Chi non ha nessun telefono registrato non riceve niente, e non c'e' niente da
        // riprovare: si segna e si va avanti.
        val senzaDestinatario = immediate.map { it.id }
            .filterNot { id -> perTelefono.values.any { id in it } }
        consegnate += senzaDestinatario

        consegnate.forEach(::segnaConsegnata)
        pulisciGettoniMorti()

        return if (telefoni > 0) {
            listOf("${consegnate.size} notifiche mandate a $telefoni telefoni.")
        } else {
            emptyList()
        }
    }

    /**
     * Toglie i gettoni che Firebase ha dichiarato morti.
     *
     * App disinstallata, dati cancellati, gettone ruotato: sono le tre cose normali della
     * vita di un telefono, e un gettone morto ritentato a ogni giro e' una chiamata HTTPS
     * sprecata per sempre.
     */
    private fun pulisciGettoniMorti() {
        if (push.gettoniMorti.isEmpty()) return

        connection.prepareStatement("delete from device_tokens where token = any(?)").use { st ->
            st.setArray(1, connection.createArrayOf("text", push.gettoniMorti.toTypedArray()))
            val quanti = st.executeUpdate()
            if (quanti > 0) log("$quanti gettoni morti tolti dal database.")
        }
        push.gettoniMorti.clear()
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
            where p.league_id = ?
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
                continue
            }

            // Prima di dire no, si prova a dire **quanto** mancava.
            //
            // Un rifiuto secco non insegna niente: chi lo riceve non sa se ha sbagliato di
            // poco o di tanto, e riprova alla cieca o smette di provarci. La controproposta
            // porta con se' l'informazione che mancava, ed e' la differenza fra un mercato
            // e un distributore automatico.
            val contro = TradeEvaluator.counter(
                offer = trade.offer,
                personality = stato.personality,
                squad = squad,
                availableCredits = club.availableCredits,
                config = league.config,
                offeredValues = valori,
            )

            if (contro != null) {
                chiudiScambio(trade.id, "CONTROPROPOSTA", contro.message)
                salvaControproposta(league.id, trade.id, contro, trade.kind)
                notify(
                    league.id, trade.from,
                    "${club.name} ha fatto una controproposta.",
                    kind = "scambio", urgency = "immediata",
                )
                note += "${club.name} contropropone."
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
                    ?: return chiudiConNota(trade, club, "il giocatore non c'è più")

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

                val motivo = AiInitiative.friendlyRefusal(
                    stato, squad, league.config,
                    giornateAllaProssimaPartita(league.id, trade.to, league.currentMatchDay),
                )
                // Il motivo vero, non uno per tutti: era «abbiamo le gambe pesanti» anche
                // su una squadra fresca che rifiutava per il calendario.
                if (motivo != null) return chiudiConNota(trade, club, motivo)

                // E la distanza dalle altre partite, che fra la proposta e la risposta puo'
                // essere cambiata: l'admin scrive una giornata di campionato e all'improvviso
                // quell'amichevole si sovrappone. Le ore le decide la lega, e la regola e' la
                // stessa che applicano il calendario e l'app.
                val ore = league.config.calendar.minHoursBetweenMatches
                if (ore > 0 && haUnaPartitaVicina(league.id, listOf(trade.from, trade.to), quando, ore)) {
                    return chiudiConNota(trade, club, "a quell'ora una delle due gioca già")
                }

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

    /**
     * Uno di questi club ha gia' una partita entro [ore] da quel momento?
     *
     * Il conto lo fa il database perche' e' li' che stanno le partite, ma la **regola** —
     * quante ore, e che valga nei due sensi — e' `KickoffRules.troppoVicine` in `core`,
     * usata identica dal calendario e dall'app.
     */
    private fun haUnaPartitaVicina(
        leagueId: Long,
        clubs: List<ClubId>,
        quando: String,
        ore: Int,
    ): Boolean = connection.prepareStatement(
        """
        select 1 from fixtures
        where league_id = ? and not played and kickoff is not null
          and kickoff > ?::timestamptz - make_interval(hours => ?)
          and kickoff < ?::timestamptz + make_interval(hours => ?)
          and (home_club_id = any(?) or away_club_id = any(?))
        limit 1
        """.trimIndent(),
    ).use { st ->
        val ids = connection.createArrayOf("bigint", clubs.map { it.value }.toTypedArray())
        st.setLong(1, leagueId)
        st.setString(2, quando); st.setInt(3, ore)
        st.setString(4, quando); st.setInt(5, ore)
        st.setArray(6, ids); st.setArray(7, ids)
        st.executeQuery().use { rs -> rs.next() }
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

    /**
     * Scrive la controproposta come proposta nuova, legata a quella a cui risponde.
     *
     * `replies_to` non e ornamento: e cio che permette di leggere una trattativa come una
     * conversazione invece che come due proposte scollegate che si somigliano.
     */
    private fun salvaControproposta(
        leagueId: Long,
        rispondeA: Long,
        offerta: dev.mfoot.core.market.TradeOffer,
        kind: String,
    ) {
        connection.prepareStatement(
            """
            insert into trades (league_id, from_club, to_club, offered, wanted, cash,
                                message, kind, replies_to)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setLong(2, offerta.from.value)
            st.setLong(3, offerta.to.value)
            st.setArray(4, connection.createArrayOf("bigint", offerta.offered.map { it.value }.toTypedArray()))
            st.setArray(5, connection.createArrayOf("bigint", offerta.wanted.map { it.value }.toTypedArray()))
            st.setInt(6, offerta.cash)
            st.setString(7, offerta.message)
            st.setString(8, kind)
            st.setLong(9, rispondeA)
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
        // Chiudere un.asta tocca aste, acquisti e a volte il listino: quello che sta in
        // memoria non vale piu..
        mercatoCambiato()

        val auction = loadAuction(auctionId) ?: return
        if (!auction.isOpen) return

        // Un'asta nata da una contestazione **non puo' passare di qui**: il giocatore ha
        // gia' cambiato squadra al momento dell'acquisto, quindi `proprietarioDi` restituisce
        // chi ha comprato mentre `started_by` e' chi ha contestato. Il controllo qui sotto
        // vedrebbe due club diversi, concluderebbe che il giocatore e' stato ceduto durante
        // l'asta — che e' esattamente cosa **non** e' successo — e annullerebbe tutto.
        loadPurchaseByAuction(auctionId)?.let { purchase ->
            closeContestation(league, auction, purchase)
            return
        }

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
                "La vendita è saltata: ti avrebbe lasciato sotto il minimo di rosa.",
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

    // ------------------------------------------------------- la finestra di dodici ore

    /** L'acquisto legato a un'asta, se quell'asta e' nata da una contestazione. */
    private fun loadPurchaseByAuction(auctionId: Long): Purchase? =
        connection.prepareStatement(
            "select id, player_id, buyer_club_id, seller_club_id, price, bought_at, " +
                "contestable_until, status, auction_id from purchases where auction_id = ?",
        ).use { st ->
            st.setLong(1, auctionId)
            st.executeQuery().use { rs -> if (rs.next()) readPurchase(rs) else null }
        }

    private fun readPurchase(rs: java.sql.ResultSet): Purchase = Purchase(
        id = rs.getLong("id"),
        playerId = PlayerId(rs.getLong("player_id")),
        buyer = ClubId(rs.getLong("buyer_club_id")),
        seller = rs.getLong("seller_club_id").takeIf { !rs.wasNull() }?.let { ClubId(it) },
        price = rs.getInt("price"),
        boughtAt = rs.getTimestamp("bought_at").toInstant(),
        contestableUntil = rs.getTimestamp("contestable_until").toInstant(),
        status = PurchaseStatus.valueOf(rs.getString("status")),
        auctionId = rs.getLong("auction_id").takeIf { !rs.wasNull() },
    )

    /**
     * Chiude una contestazione.
     *
     * ## I tre esiti, e perche' muovono soldi diversi
     *
     * **Nessuno ha superato chi aveva comprato**: l'acquisto si conferma e si paga solo la
     * differenza fra il prezzo d'asta e quello gia' versato — mai il prezzo intero due volte.
     *
     * **Ha vinto un altro**: il giocatore cambia squadra, e chi aveva comprato **riprende i
     * crediti interi**. Ha perso il giocatore, non i soldi: e' la regola dettata il
     * 2026-08-24, ed e' cio' che rende accettabile comprare sapendo di poter essere
     * contestati.
     *
     * **Il venditore incassa il prezzo finale, non quello di listino.** Aveva gia' preso il
     * prezzo di vendita al momento dell'acquisto; qui riceve la differenza. Il conto torna:
     * chi vende incassa quanto il giocatore e' valso davvero, che e' il motivo per cui la
     * contestazione ripaga anche chi ha messo un prezzo troppo basso in buona fede.
     */
    private fun closeContestation(league: LeagueRow, auction: Auction, purchase: Purchase) {
        val esito = ContestRules.settle(purchase, auction, Instant.now(), league.config.market)

        // I fondi impegnati si liberano per tutti, chi ha comprato compreso: la sua offerta
        // e' stata inserita da `contest_purchase` e va sciolta come le altre.
        liberaFondi(auction)

        when (esito) {
            is ContestRules.Settlement.Confermato -> {
                if (esito.extraDaPagare > 0) {
                    addebita(purchase.buyer, esito.extraDaPagare)
                    purchase.seller?.let { accredita(it, esito.extraDaPagare) }
                }
                segnaAcquisto(purchase.id, PurchaseStatus.CONFERMATO)
                chiudiAstaContestata(auction.id, purchase.buyer, purchase.price + esito.extraDaPagare)
                notify(
                    league.id, purchase.buyer,
                    if (esito.extraDaPagare > 0) {
                        "Contestazione respinta: te lo tieni, hai pagato ${esito.extraDaPagare} in più."
                    } else {
                        "Nessuno ha contestato: è tuo."
                    },
                    kind = "asta", urgency = "immediata",
                )
            }

            is ContestRules.Settlement.Revocato -> {
                accredita(purchase.buyer, esito.daRimborsare)
                addebita(esito.vincitore, esito.prezzo)
                // Al venditore la differenza: aveva gia' incassato il prezzo di listino.
                purchase.seller?.let { accredita(it, esito.prezzo - purchase.price) }

                assignPlayer(league, purchase.playerId, esito.vincitore, esito.prezzo)
                segnaAcquisto(purchase.id, PurchaseStatus.REVOCATO)
                chiudiAstaContestata(auction.id, esito.vincitore, esito.prezzo)

                notify(
                    league.id, purchase.buyer,
                    "Te l'hanno soffiato: ${esito.daRimborsare} crediti ti sono tornati.",
                    kind = "asta", urgency = "immediata",
                )
                notify(
                    league.id, esito.vincitore,
                    "Contestazione vinta: è tuo per ${esito.prezzo} crediti.",
                    kind = "asta", urgency = "immediata",
                )
            }
        }

        log("Contestazione sull'acquisto ${purchase.id} chiusa.")
    }

    /**
     * Conferma gli acquisti la cui finestra e' passata senza che nessuno si opponesse.
     *
     * Non passa dal pianificatore come le aste, e non e' una scorciatoia: non c'e' niente
     * da decidere. E' una scadenza oggettiva su una riga, non produce notifiche e non
     * muove un credito — l'unica cosa che cambia e' che da quel momento il giocatore non
     * si puo' piu' contestare.
     */
    private fun confermaAcquistiScaduti(league: LeagueRow) {
        mercatoCambiato()
        connection.prepareStatement(
            "update purchases set status = 'CONFERMATO' " +
                "where league_id = ? and status = 'IN_FINESTRA' and contestable_until <= now()",
        ).use { st ->
            st.setLong(1, league.id)
            val quanti = st.executeUpdate()
            if (quanti > 0) log("$quanti acquisti confermati: finestra chiusa senza opposizioni.")
        }
    }

    private fun segnaAcquisto(purchaseId: Long, status: PurchaseStatus) {
        connection.prepareStatement("update purchases set status = ? where id = ?").use { st ->
            st.setString(1, status.name)
            st.setLong(2, purchaseId)
            st.executeUpdate()
        }
    }

    private fun chiudiAstaContestata(auctionId: Long, winner: ClubId, price: Int) {
        connection.prepareStatement(
            "update auctions set status = 'AGGIUDICATA', winner_club_id = ?, final_price = ? " +
                "where id = ?",
        ).use { st ->
            st.setLong(1, winner.value)
            st.setInt(2, price)
            st.setLong(3, auctionId)
            st.executeUpdate()
        }
    }

    private fun addebita(club: ClubId, quanto: Int) {
        if (quanto <= 0) return
        connection.prepareStatement("update clubs set credits = credits - ? where id = ?").use { st ->
            st.setInt(1, quanto)
            st.setLong(2, club.value)
            st.executeUpdate()
        }
    }

    private fun accredita(club: ClubId, quanto: Int) {
        if (quanto <= 0) return
        connection.prepareStatement("update clubs set credits = credits + ? where id = ?").use { st ->
            st.setInt(1, quanto)
            st.setLong(2, club.value)
            st.executeUpdate()
        }
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
            "select count(*) from contracts where league_id = ? and club_id = ?",
        ).use { st ->
            st.setLong(1, leagueId)
            st.setLong(2, clubId.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    /**
     * I club che **trattano**: le prime squadre.
     *
     * Le Primavere non propongono scambi ne' chiedono amichevoli. Non hanno un portafoglio
     * con cui pagare un conguaglio, e una seconda squadra che tratta per conto suo sarebbe
     * un secondo interlocutore con lo stesso proprietario — cioe' il modo piu' semplice di
     * spostarsi un giocatore da una tasca all'altra fingendo che sia un mercato.
     */
    /** I club della lega. Dentro un giro non ne nascono e non ne muoiono. */
    private fun loadClubIds(leagueId: Long): List<ClubId> =
        clubInCache ?: leggiClubIds(leagueId).also { clubInCache = it }

    private fun leggiClubIds(leagueId: Long): List<ClubId> {
        val out = mutableListOf<ClubId>()
        connection.prepareStatement(
            "select id from clubs where league_id = ? and parent_club_id is null order by id",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> while (rs.next()) out += ClubId(rs.getLong("id")) }
        }
        return out
    }

    /**
     * Chi ha compiuto gli anni sale in prima squadra.
     *
     * ## Perche' e' la regola che rende la Primavera una scelta
     *
     * Senza, la seconda squadra e' un deposito: ci si mette un ragazzo e ci resta per
     * sempre, e non c'e' nessun momento in cui bisogna decidere qualcosa. Con la scadenza,
     * prima o poi ognuno torna indietro e bisogna avergli fatto posto.
     *
     * Se la prima squadra e' piena non si promuove d'ufficio — si sfonderebbe un limite che
     * il resto del gioco fa rispettare — ma si avvisa. La decisione di chi far uscire resta
     * di chi gioca.
     */
    private fun promuoviChiEFuoriEta(league: LeagueRow): List<String> {
        val limite = league.config.rules.youthMaxAge
        val note = mutableListOf<String>()

        data class Cresciuto(val playerId: Long, val nome: String, val figlio: Long, val padre: Long)

        val cresciuti = mutableListOf<Cresciuto>()
        connection.prepareStatement(
            """
            select p.id, p.first_name, p.last_name, c.club_id, cl.parent_club_id
            from contracts c
            join clubs cl on cl.id = c.club_id
            join players p on p.id = c.player_id
            where cl.league_id = ? and cl.parent_club_id is not null and p.age > ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setInt(2, limite)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    cresciuti += Cresciuto(
                        playerId = rs.getLong("id"),
                        nome = "${rs.getString("first_name").first()}. ${rs.getString("last_name")}",
                        figlio = rs.getLong("club_id"),
                        padre = rs.getLong("parent_club_id"),
                    )
                }
            }
        }
        if (cresciuti.isEmpty()) return emptyList()

        for (c in cresciuti) {
            val inPrima = squadSize(league.id, ClubId(c.padre))
            if (inPrima >= league.config.setup.maxSquadSize) {
                notify(
                    league.id, ClubId(c.padre),
                    "${c.nome} ha superato l'età della Primavera, ma la prima squadra è " +
                        "piena: liberane una casella.",
                    kind = "primavera", urgency = "immediata",
                )
                continue
            }

            connection.prepareStatement(
                "update contracts set club_id = ? where player_id = ?",
            ).use { st ->
                st.setLong(1, c.padre)
                st.setLong(2, c.playerId)
                st.executeUpdate()
            }
            notify(
                league.id, ClubId(c.padre),
                "${c.nome} è cresciuto: è in prima squadra.",
                kind = "primavera", urgency = "riepilogo",
            )
            note += "${c.nome} promosso dalla Primavera."
        }
        return note
    }

    // ------------------------------------------------------------- le competizioni

    /**
     * Fa camminare le competizioni: finito un turno, nasce il successivo.
     *
     * ## Il difetto che questa funzione chiude
     *
     * Una coppa giocava gli ottavi e si fermava li' per sempre. Non per un errore di
     * calcolo: [FixtureGenerator.nextKnockoutRound] era corretto e provato dal primo
     * giorno, e **non lo chiamava nessuno** — ne' qui, ne' nell'app, ne' in una funzione
     * del database. Mancava il collegamento, non la regola.
     *
     * Valeva identico per i playoff e i playout delle divisioni, che nascono come
     * tabellone a quattro e non arrivavano mai alla finale.
     *
     * ## Perche' la decisione non e' qui dentro
     *
     * Chi e' passato, chi ha riposato e quali sono gli accoppiamenti nuovi lo dice
     * [CompetitionProgress] in `core`, con le sue prove. Qui restano solo le tre cose che
     * `core` non puo' fare: leggere, dare le date, e scrivere.
     */
    private fun avanzaLeCompetizioni(league: LeagueRow): List<String> {
        val note = mutableListOf<String>()

        caricaCompetizioniAperte(league).forEach { (competition, calendario) ->
            val partite = leggiStatoPartite(competition.id)
            if (partite.isEmpty()) return@forEach

            when (val next = CompetitionProgress.next(competition, partite, leggiEsiti(competition.id))) {
                CompetitionProgress.Next.Attendi -> Unit

                is CompetitionProgress.Next.Finita -> {
                    chiudiCompetizione(competition.id, next.winner)
                    val chi = next.winner?.let { clubNameOf(it) }
                    note += if (chi != null) "${competition.name}: ha vinto $chi." else "${competition.name} e' finita."
                    notify(
                        league.id, null,
                        if (chi != null) "${competition.name}: ha vinto $chi." else "${competition.name} e' finita.",
                        kind = "competizione", urgency = "riepilogo",
                    )
                }

                is CompetitionProgress.Next.Turno -> {
                    val scritte = scriviIlTurno(league, competition, calendario, next.rounds)
                    if (scritte == 0) {
                        // Non si segna niente e non si chiude niente: al prossimo giro ci
                        // riprova. Un turno che non entra nel calendario e' un problema di
                        // date, e le date si possono ancora cambiare.
                        note += "${competition.name}: il turno nuovo non entra nel periodo."
                    } else {
                        note += "${competition.name}: ${next.rounds.first().label}, $scritte partite."
                    }
                }
            }
        }

        return note
    }

    /**
     * Le competizioni ufficiali ancora aperte, con il calendario con cui erano state
     * create.
     *
     * Le amichevoli restano fuori: la loro competizione e' un contenitore senza turni e
     * senza classifica, e chiedere «a che punto e' il tabellone» non vorrebbe dire niente.
     */
    private fun caricaCompetizioniAperte(league: LeagueRow): List<Pair<Competition, CalendarConfig>> {
        val out = mutableListOf<Pair<Competition, CalendarConfig>>()
        connection.prepareStatement(
            """
            select id, name, type, config, participants
            from competitions
            where league_id = ? and kind = 'UFFICIALE' and finished_at is null
            order by id
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val tipo = runCatching { CompetitionType.valueOf(rs.getString("type")) }.getOrNull()
                        ?: continue
                    val iscritte = (rs.getArray("participants")?.array as? Array<*>)
                        ?.mapNotNull { (it as? Number)?.toLong() }
                        ?.map(::ClubId)
                        .orEmpty()
                    if (iscritte.size < 2) continue

                    val config = JsonNode.parse(rs.getString("config") ?: "{}")
                    val andataRitorno = config["doubleRound"].bool(false)

                    out += Competition(
                        id = CompetitionId(rs.getLong("id")),
                        name = rs.getString("name"),
                        type = tipo,
                        participants = iscritte,
                        doubleRound = andataRitorno,
                        // Nel tabellone «andata e ritorno» si chiama doppia sfida ed e' la
                        // stessa casella dell'app: erano due campi diversi, e il risultato
                        // era un interruttore che nella coppa non faceva niente.
                        twoLeggedKnockout = andataRitorno && tipo != CompetitionType.GIRONE,
                    ) to calendarioDi(league, config)
                }
            }
        }
        return out
    }

    /**
     * Il calendario con cui giocare i turni nuovi.
     *
     * Le fasce orarie e il tetto giornaliero sono quelli che l'admin ha scelto quando ha
     * creato la competizione — stanno nel suo `config` — e non quelli della lega: due
     * tornei nella stessa lega possono benissimo giocarsi in ore diverse, ed e' proprio il
     * caso della coppa infrasettimanale.
     */
    private fun calendarioDi(league: LeagueRow, config: JsonNode): CalendarConfig {
        val fasce = config["kickoffSlots"].asList().mapNotNull { nodo ->
            nodo.strOrNull()?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
        }
        return league.config.calendar.copy(
            kickoffSlots = fasce.ifEmpty { league.config.calendar.kickoffSlots },
            matchesPerDayPerClub = config["matchesPerDayPerClub"]
                .int(league.config.calendar.matchesPerDayPerClub),
        )
    }

    private fun leggiStatoPartite(competitionId: CompetitionId): List<FixtureState> {
        val out = mutableListOf<FixtureState>()
        connection.prepareStatement(
            // **In ordine di id**, cioe' nell'ordine in cui sono state scritte: e' l'ordine
            // del tabellone, e da quello dipende chi affronta chi al turno dopo.
            """
            select round, home_club_id, away_club_id, tie_id, played
            from fixtures where competition_id = ? order by id
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, competitionId.value)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out += FixtureState(
                        round = rs.getInt("round"),
                        home = ClubId(rs.getLong("home_club_id")),
                        away = ClubId(rs.getLong("away_club_id")),
                        tieId = rs.getString("tie_id"),
                        played = rs.getBoolean("played"),
                    )
                }
            }
        }
        return out
    }

    private fun leggiEsiti(competitionId: CompetitionId): List<FixtureResult> {
        val out = mutableListOf<FixtureResult>()
        connection.prepareStatement(
            """
            select f.id, f.home_club_id, f.away_club_id, r.home_goals, r.away_goals
            from fixtures f join match_results r on r.fixture_id = f.id
            where f.competition_id = ? order by f.id
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, competitionId.value)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out += FixtureResult(
                        fixtureId = rs.getLong("id"),
                        competitionId = competitionId,
                        home = ClubId(rs.getLong("home_club_id")),
                        away = ClubId(rs.getLong("away_club_id")),
                        homeGoals = rs.getInt("home_goals"),
                        awayGoals = rs.getInt("away_goals"),
                    )
                }
            }
        }
        return out
    }

    /**
     * Da' le date al turno nuovo e lo scrive. Torna quante partite sono state scritte.
     *
     * ## Perche' un turno per volta invece di tutti insieme
     *
     * Perche' l'andata e il ritorno devono stare a distanza. Passati insieme al
     * risolutore finirebbero nelle due fasce dello stesso giorno — sono due turni
     * consecutivi e lui riempie le fasce in ordine — cioe' andata alle 18:30 e ritorno
     * alle 21. Scritti uno per volta, ognuno riparte da [CalendarConfig.cupRoundGapDays]
     * giorni dopo il precedente.
     *
     * ## Perche' il risolutore deve sapere del campionato
     *
     * Perche' il campionato ha gia' le sue partite scritte, e il risolutore non le ha
     * generate lui: senza passargliele piazzerebbe la coppa sopra una giornata di
     * campionato, con la stessa squadra impegnata due volte alla stessa ora.
     */
    private fun scriviIlTurno(
        league: LeagueRow,
        competition: Competition,
        calendario: CalendarConfig,
        rounds: List<Round>,
    ): Int {
        val gap = league.config.calendar.cupRoundGapDays.coerceAtLeast(1).toLong()
        val occupati = impegniInCalendario(league)

        var giorno = LocalDate.now(league.config.calendar.timeZone).plusDays(gap)
        var giornata = league.currentMatchDay + 1
        var scritte = 0

        rounds.forEach { round ->
            val periodo = calendario.copy(startDate = giorno, endDate = giorno.plusDays(FINESTRA_TURNO))
            val schedule = CalendarSolver.schedule(
                rounds = listOf(round),
                config = league.config.copy(calendar = periodo),
                firstMatchDay = MatchDay(giornata),
                occupati = occupati,
            )
            if (schedule.fixtures.isEmpty()) return scritte

            schedule.fixtures.forEach { f ->
                scriviPartita(league, competition, f, periodo)
                f.kickoff?.let { quando ->
                    occupati += quando to f.home
                    occupati += quando to f.away
                }
                scritte++
            }

            avvisaChiGioca(league, competition, schedule.fixtures)

            giornata = schedule.lastMatchDay.value + 1
            giorno = (schedule.fixtures.mapNotNull { it.kickoff?.toLocalDate() }.maxOrNull() ?: giorno)
                .plusDays(gap)
        }
        return scritte
    }

    /**
     * Le partite gia' in programma, come coppie ora-club.
     *
     * Solo quelle non giocate: cio' che e' gia' successo non impegna piu' nessuno, e
     * portarsi dietro una stagione intera renderebbe questo conto piu' caro del turno che
     * deve collocare.
     */
    private fun impegniInCalendario(league: LeagueRow): MutableSet<Pair<java.time.LocalDateTime, ClubId>> {
        val out = mutableSetOf<Pair<java.time.LocalDateTime, ClubId>>()
        connection.prepareStatement(
            """
            select home_club_id, away_club_id, kickoff from fixtures
            where league_id = ? and not played and kickoff is not null
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    // In **ora di lega**: le fasce con cui ragiona il risolutore sono ore
                    // locali, e confrontarle con l'ora UTC del database sposterebbe ogni
                    // impegno di due ore d'estate — cioe' non ne riconoscerebbe nessuno.
                    val quando = league.config.calendar.localOf(rs.getTimestamp("kickoff").toInstant())
                    out += quando to ClubId(rs.getLong("home_club_id"))
                    out += quando to ClubId(rs.getLong("away_club_id"))
                }
            }
        }
        return out
    }

    private fun scriviPartita(
        league: LeagueRow,
        competition: Competition,
        fixture: Fixture,
        calendario: CalendarConfig,
    ) {
        connection.prepareStatement(
            """
            insert into fixtures (league_id, competition_id, round, round_label,
                                  home_club_id, away_club_id, match_day, kickoff,
                                  tie_id, is_second_leg)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setLong(2, competition.id.value)
            st.setInt(3, fixture.round)
            st.setString(4, fixture.roundLabel)
            st.setLong(5, fixture.home.value)
            st.setLong(6, fixture.away.value)
            st.setInt(7, fixture.matchDay.value)
            st.setTimestamp(8, Timestamp.from(calendario.instantOf(fixture.kickoff!!)))
            if (fixture.tieId == null) st.setNull(9, java.sql.Types.VARCHAR) else st.setString(9, fixture.tieId)
            st.setBoolean(10, fixture.isSecondLeg)
            st.executeUpdate()
        }
    }

    /**
     * Chi gioca il turno nuovo lo deve sapere, e con l'ora.
     *
     * Un tabellone che avanza in silenzio e' indistinguibile da un tabellone fermo: era la
     * cosa che, dal telefono, faceva sembrare la coppa rotta anche quando non lo era.
     */
    private fun avvisaChiGioca(
        league: LeagueRow,
        competition: Competition,
        fixtures: List<Fixture>,
    ) {
        fixtures.forEach { f ->
            // [Fixture.kickoff] e' gia' ora di lega — e' quella che il risolutore ha
            // scelto fra le fasce dell'admin — quindi si scrive com'e'. La conversione a
            // istante serve solo al database.
            val quando = f.kickoff
                ?.let { "%02d/%02d alle %02d:%02d".format(it.dayOfMonth, it.monthValue, it.hour, it.minute) }
                ?: "data da definire"

            listOf(f.home to f.away, f.away to f.home).forEach { (club, avversario) ->
                notify(
                    league.id, club,
                    "${competition.name} · ${f.roundLabel}: contro ${clubNameOf(avversario)}, $quando.",
                    kind = "competizione", urgency = "immediata",
                )
            }
        }
    }

    /**
     * Quanti giorni si guardano avanti per far entrare un turno.
     *
     * Due settimane: abbastanza perche' anche una coppa con poche fasce orarie e un
     * campionato fitto trovi dove infilarsi, poco abbastanza perche' un turno che non ci
     * sta in due settimane sia un problema di configurazione da segnalare invece di una
     * partita fissata fra due mesi.
     */
    private val FINESTRA_TURNO = 14L

    private fun chiudiCompetizione(competitionId: CompetitionId, winner: ClubId?) {
        connection.prepareStatement(
            "update competitions set finished_at = now(), winner_club_id = ? where id = ?",
        ).use { st ->
            if (winner == null) st.setNull(1, java.sql.Types.BIGINT) else st.setLong(1, winner.value)
            st.setLong(2, competitionId.value)
            st.executeUpdate()
        }
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
    /**
     * Gioca il primo tempo e apre la finestra dei cambi.
     *
     * La partita **non** viene segnata come giocata: resta li', con `resume_at` che dice
     * quando riprende, ed e' `WorldTick.halfTimesDue` a rimetterla in fila al giro giusto.
     * Nel frattempo chi c'e' puo' cambiare formazione, assetto e incarichi, e chi non c'e'
     * non viene tagliato fuori — i suoi ordini condizionali girano lo stesso.
     */
    private fun giocaPrimoTempo(
        league: LeagueRow,
        fixture: Fixture,
        home: TeamSetup,
        away: TeamSetup,
        seed: Long,
        finestraMinuti: Int,
    ): Boolean {
        val primo = MatchEngine.simulateFirstHalf(home, away, league.config, seed)

        // LA RIPRESA SI CONTA DAL FISCHIO D'INIZIO, NON DA ADESSO
        //
        // Dal 2026-08-29 il primo tempo si **guarda** per quarantacinque minuti veri, poi
        // c'e' l'intervallo. La ripresa cade quindi a `kickoff + 45 + pausa`, ed e' una cosa
        // diversa da «fra venti minuti»: il tick arriva quando arriva — fino a cinque
        // minuti dopo l'orario, perche' `pg_cron` bussa a quella cadenza — e contando da
        // adesso ogni ritardo del server si sommerebbe alla partita. Un fischio d'inizio
        // preso con quattro minuti di ritardo avrebbe spostato la fine di quattro minuti,
        // e la partita sarebbe finita a un'ora che nessuno aveva letto da nessuna parte.
        //
        // Se la partita non ha un orario — non dovrebbe succedere — si ricade su adesso,
        // che e' il comportamento di prima.
        val inizio = fixture.kickoff?.toInstant(ZoneOffset.UTC) ?: Instant.now()
        val ripresa = MatchClock.ripresaDi(inizio, finestraMinuti)

        connection.prepareStatement(
            "update fixtures set resume_at = ?, first_half = ?::jsonb where id = ?",
        ).use { st ->
            st.setTimestamp(1, Timestamp.from(ripresa))
            // Il primo tempo viaggia insieme agli schieramenti: per quarantacinque minuti
            // reali e' l'unica cosa che esiste di questa partita, e senza di lui chi apre
            // l'app durante il primo tempo vedrebbe un campo vuoto.
            st.setString(2, HalfTimeJson.write(home, away, primo))
            st.setLong(3, fixture.id)
            st.executeUpdate()
        }

        val parziale = "${primo.homeGoals}-${primo.awayGoals}"
        listOf(fixture.home, fixture.away).forEach { club ->
            notify(
                league.id, club,
                "Intervallo: $parziale. Hai $finestraMinuti minuti per cambiare qualcosa.",
                kind = "partita", urgency = "immediata",
            )
        }
        log("Partita ${fixture.id} all'intervallo sul $parziale, riprende alle $ripresa.")
        return true
    }

    /**
     * Riprende una partita ferma al 45'.
     *
     * ## Perche' si ri-simula il primo tempo invece di conservarlo
     *
     * Perche' il motore e' deterministico e costa microsecondi: stesso seed e stessi
     * ingressi danno lo stesso identico primo tempo. Conservarlo vorrebbe dire
     * serializzare timeline, statistiche e schieramenti — un secondo formato da tenere
     * allineato al motore per sempre, e un modo nuovo di far divergere quello che si vede
     * da quello che e' successo.
     *
     * Gli **ingressi** invece si conservano ([HalfTimeJson]), perche' quelli sono cambiati:
     * e' esattamente cio' che il manager ha fatto nella finestra.
     */
    private fun resumeMatch(
        league: LeagueRow,
        fixture: Fixture,
        notes: MutableList<String>,
    ): Boolean {
        val salvato = connection.prepareStatement(
            "select first_half from fixtures where id = ?",
        ).use { st ->
            st.setLong(1, fixture.id)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

        // Senza gli schieramenti di partenza non si puo' ricostruire niente: si rigioca
        // tutta la partita da capo, che e' peggio di un intervallo ma molto meglio di una
        // partita che resta a meta' per sempre.
        if (salvato == null) {
            connection.prepareStatement(
                "update fixtures set resume_at = null where id = ?",
            ).use { st -> st.setLong(1, fixture.id); st.executeUpdate() }
            notes += "Partita ${fixture.id}: intervallo illeggibile, rigiocata per intero."
            return playMatch(league, fixture, notes)
        }

        val node = JsonNode.parse(salvato)
        val today = MatchDay(fixture.matchDay.value)
        val rosa = loadSquad(league.id, fixture.home).associateBy { it.id.value } +
            loadSquad(league.id, fixture.away).associateBy { it.id.value }

        val primoHome = HalfTimeJson.readTeam(node["home"], rosa)
        val primoAway = HalfTimeJson.readTeam(node["away"], rosa)
        if (primoHome == null || primoAway == null) {
            connection.prepareStatement(
                "update fixtures set resume_at = null where id = ?",
            ).use { st -> st.setLong(1, fixture.id); st.executeUpdate() }
            notes += "Partita ${fixture.id}: schieramenti del primo tempo incompleti, rigiocata."
            return playMatch(league, fixture, notes)
        }

        val seed = league.config.setup.worldSeed * 31L + fixture.id
        val intervallo = MatchEngine.simulateFirstHalf(primoHome, primoAway, league.config, seed)

        // I setup **di adesso**: e' qui che entrano i cambi fatti nella finestra. Se
        // nessuno ha toccato niente si rilegge la stessa formazione e non cambia nulla.
        val secondoHome = buildTeam(league, fixture.home, today, notes) ?: primoHome
        val secondoAway = buildTeam(league, fixture.away, today, notes) ?: primoAway

        val result = MatchEngine.simulateSecondHalf(
            intervallo, league.config, secondoHome, secondoAway,
        )

        connection.prepareStatement(
            "update fixtures set resume_at = null, first_half = null where id = ?",
        ).use { st -> st.setLong(1, fixture.id); st.executeUpdate() }

        salvaEsito(league, fixture, result, seed, secondoHome, secondoAway)
        return true
    }

    private fun playMatch(
        league: LeagueRow,
        fixture: Fixture,
        notes: MutableList<String>,
    ): Boolean {
        val today = MatchDay(fixture.matchDay.value)
        val home = buildTeam(league, fixture.home, today, notes) ?: return false
        val away = buildTeam(league, fixture.away, today, notes) ?: return false

        val seed = league.config.setup.worldSeed * 31L + fixture.id

        // La finestra dell'intervallo: si gioca il primo tempo e si aspetta.
        //
        // E' l'unico momento in cui una partita asincrona diventa una partita che si
        // guarda — e finora non esisteva, malgrado il motore sapesse gia' fermarsi al 45'
        // e la configurazione prevedesse la finestra.
        val finestra = league.config.calendar.halfTimeWindowMinutes
        if (finestra > 0) {
            return giocaPrimoTempo(league, fixture, home, away, seed, finestra)
        }

        val result = MatchEngine.simulate(home, away, league.config, seed)
        salvaEsito(league, fixture, result, seed, home, away)
        return true
    }

    /**
     * Scrive il risultato e tutto quello che ne consegue.
     *
     * Estratta da `playMatch` quando e' arrivata la finestra dell'intervallo: la partita
     * puo' finire per due strade — tutta in un colpo, oppure in due tempi — e quello che
     * succede **dopo** il fischio finale deve essere identico in tutte e due. Duplicarla
     * avrebbe voluto dire, prima o poi, una strada che paga i premi e una che se li
     * dimentica.
     */
    private fun salvaEsito(
        league: LeagueRow,
        fixture: Fixture,
        result: MatchResult,
        seed: Long,
        home: TeamSetup,
        away: TeamSetup,
    ) {
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

        /*
         * Chi ha giocato, letto in **una** query invece di una per giocatore.
         *
         * Prima qui dentro c'era `loadPlayerRow(playerId)` dentro il ciclo: una andata e
         * ritorno verso il database per ognuno dei ventidue in campo, piu' una seconda per
         * le stelle dell'allenatore. Una cinquantina di viaggi per partita, ognuno con la
         * latenza fra un runner di GitHub e Supabase. Su un calendario da otto partite
         * erano minuti interi, ed erano i minuti che facevano superare il timeout.
         */
        val inCampo = result.stats.filterValues { it.minutesPlayed > 0 }.keys.map { it.value }
        val righe = loadPlayerRows(inCampo)

        update.use { st ->
            result.stats.forEach { (playerId, stats) ->
                if (stats.minutesPlayed <= 0) return@forEach
                val row = righe[playerId.value] ?: return@forEach

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
     * 2. Un'azione per risveglio **a rosa completa**, con un tetto giornaliero. Anche
     *    trovando dieci occasioni, ne coglie una.
     * 3. Piu' AI sono gia' su un obiettivo, meno appetibile diventa: la seconda ci pensa,
     *    la terza quasi mai, la quarta mai.
     * 4. Il prossimo risveglio e' scaglionato a caso, quindi non si riallineano mai.
     *
     * ## Perche' a rosa incompleta la difesa numero 2 si toglie
     *
     * Misurato il 2026-08-25: dopo mezza giornata reale, cinque club su dieci avevano
     * qualche giocatore e **nessuno** ne aveva piu' di tre.
     *
     * Il conto lo spiega senza bisogno di ipotesi. Un'azione per risveglio, un risveglio
     * per giro di tick, e un giro di tick ogni cinquanta minuti veri: sono dodici acquisti
     * al giorno nel caso perfetto. Ma i giri non erano perfetti — due su tre venivano
     * uccisi dal timeout e annullati per intero (vedi [TickBudget]) — quindi restavano
     * quattro acquisti al giorno, e non tutti andavano a buon fine. Sedici giocatori a
     * quel ritmo sono quattro giorni per club.
     *
     * La difesa numero 2 era pensata per il mercato a regime, dove ogni mossa di un'AI
     * arriva sul telefono di qualcuno. Un club che sta ancora riempiendo caselle vuote non
     * disturba nessuno: compra svincolati a prezzo di listino, che e' una transazione fra
     * lui e il nulla. Qui quindi si continua finche' la rosa non e' legale, con tre freni
     * che restano: il tetto giornaliero di [AiScheduler], i crediti, e il budget di tempo
     * del giro.
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
        var stato = state
        var rosa = squad
        var azioni = 0

        // Quante mosse si concedono in questo risveglio: lo decide [AiTurn], in `core`,
        // dove c'e' una prova che il conto regge.
        val consentite = AiTurn.movesPerWake(squad.size, league.config)

        while (azioni < consentite) {
            if (!AiScheduler.hasActionsLeft(stato, now, league.config.ai, sprint = allestimento)) break

            // Il budget del giro vale anche qui: meglio due acquisti scritti che cinque
            // annullati insieme a tutto il resto della lega.
            if (!budget.consentito(java.time.Duration.ofSeconds(10))) break

            var fatto = false
            for (mossa in AiTurn.order(rosa.size, league.config)) {
                fatto = when (mossa) {
                    AiMove.APRI_ASTA -> tryOpenAuction(league, stato, club, rosa)
                    AiMove.OFFRI -> tryBid(league, stato, club, rosa, today)
                    AiMove.METTI_IN_VENDITA -> mettiInVendita(league, stato, club, rosa)
                    AiMove.GESTISCI_ROSA -> tieniInOrdineLaRosa(league, stato, club, rosa, today)
                    AiMove.PROPONI_SCAMBIO -> proponiUnoScambio(league, stato, club, rosa, today)
                    AiMove.COMPRA_A_LISTINO -> compraDalListino(league, stato, club, rosa)
                    AiMove.CONTESTA -> contestaUnAcquisto(league, stato, club, rosa)
                    AiMove.METTI_A_LISTINO -> mettiSulListino(league, stato, club, rosa)
                    AiMove.OFFRI_CREDITI -> offriCreditiPerUnGiocatore(league, stato, club, rosa, today)
                    AiMove.PROPONI_PRESTITO -> proponiUnPrestito(league, stato, club, rosa, today)
                    AiMove.CHIEDI_AMICHEVOLE -> chiediUnAmichevole(league, stato, club, rosa) ||
                        staffEMissioniDellAi(league, club)
                }
                if (fatto) break
            }

            if (!fatto) break

            // Ha scritto qualcosa: listino, aste e acquisti in memoria non valgono piu'.
            // Si arriva qui **solo** dopo una mossa riuscita, ed e' cio' che rende il
            // risparmio possibile: fra un tentativo fallito e il successivo il mercato non
            // e' cambiato, quindi rileggerlo sarebbe lavoro buttato.
            mercatoCambiato()

            stato = AiScheduler.recordAction(stato, now)
            azioni++

            // La rosa e' cambiata: la mossa successiva deve vedere il giocatore appena
            // preso, o l'AI comprerebbe cinque portieri di fila senza accorgersene.
            rosa = loadSquad(league.id, clubId)
            if (rosa.size >= league.config.setup.minSquadSize) break
        }

        /*
         * Chi scende in campo, e come.
         *
         * Prima nessun club del computer scriveva una formazione: il tick gliene costruiva
         * una al volo per giocare e la buttava. Da fuori la conseguenza era quella
         * segnalata — «non schierano, non fanno nessuna scelta tecnica» — ed era letterale:
         * aprendo il campo di un'AI non c'era niente da vedere, perche' non c'era niente.
         *
         * Adesso la formazione **si salva**, con modulo, tattica e i cinque incarichi. Non
         * e' cosmetica: e' l'unico modo perche' quelle scelte esistano fuori dal minuto in
         * cui si gioca, e perche' chi affronta quel club possa studiarlo.
         */
        if (rosa.size >= Formation.PLAYERS_ON_PITCH) {
            schieraLAi(league, stato, club, rosa, today)
        }

        val next = AiScheduler.scheduleNext(stato, now, league.config.setup.worldSeed, sprint = allestimento)

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

    /**
     * Il club del computer sceglie modulo, undici, panchina, assetto e incarichi — e li
     * **scrive**.
     *
     * ## Perche' scriverli cambia qualcosa
     *
     * Prima la formazione di un'AI nasceva dentro `buildTeam` un istante prima del fischio
     * d'inizio e moriva un istante dopo. Funzionava — le partite si giocavano — ma
     * significava tre cose, tutte segnalate dal proprietario come «le AI sono rotte»:
     *
     * - aprendo il campo di un club avversario non c'era **niente** da vedere, perche' non
     *   c'era niente di salvato;
     * - l'assetto era [Tactics.DEFAULT] per tutti e dieci, cioe' nessuna scelta tecnica;
     * - gli incarichi da palla ferma non esistevano fuori dai novanta minuti, quindi
     *   «chi tira i rigori del Mangao» era una domanda senza risposta.
     *
     * ## Perche' si riscrive a ogni risveglio e non una volta sola
     *
     * Perche' la rosa cambia: chi ha appena comprato tre giocatori deve schierarli, e chi
     * si e' infortunato non puo' restare titolare. Costa una `insert ... on conflict` per
     * club per risveglio, che rispetto a una partita simulata e' nulla.
     *
     * ## Perche' non tocca mai la formazione di una persona
     *
     * Perche' viene chiamata solo da [wakeAi], e [wakeAi] gira solo sui club che hanno una
     * riga in `ai_states`. Un club umano non ne ha una, quindi non passa mai di qui.
     */
    private fun schieraLAi(
        league: LeagueRow,
        state: AiState,
        club: Club,
        squad: List<Player>,
        today: MatchDay,
    ) {
        val tattica = AiTactics.choose(state.personality, squad, today)
        val modulo = AutoLineup.bestFormation(squad, today)

        // Gli incarichi li mette dentro [AutoLineup.build] chiamando [SetPieces]: sono gli
        // stessi criteri che l'app mostra a chi sceglie a mano, quindi il capitano di
        // un'AI e' scelto come lo sceglierebbe una persona informata.
        val lineup = AutoLineup.build(squad, modulo, today) ?: return

        connection.prepareStatement(
            """
            insert into lineups (club_id, league_id, formation, slots, bench, tactics,
                                 captain_id, penalty_taker_id, corner_taker_id,
                                 free_kick_taker_id, long_ball_taker_id, updated_at)
            values (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, now())
            on conflict (club_id) do update
              set formation = excluded.formation,
                  slots = excluded.slots,
                  bench = excluded.bench,
                  tactics = excluded.tactics,
                  captain_id = excluded.captain_id,
                  penalty_taker_id = excluded.penalty_taker_id,
                  corner_taker_id = excluded.corner_taker_id,
                  free_kick_taker_id = excluded.free_kick_taker_id,
                  long_ball_taker_id = excluded.long_ball_taker_id,
                  updated_at = now()
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, club.id.value)
            st.setLong(2, league.id)
            st.setString(3, modulo.name)
            st.setString(4, LineupJson.writeSlots(lineup))
            st.setArray(
                5,
                connection.createArrayOf("bigint", lineup.bench.map { it.id.value }.toTypedArray()),
            )
            st.setString(6, LineupJson.writeTactics(tattica))

            // L'accoppiamento incarico-colonna e' scritto per esteso, non dedotto
            // dall'ordine di dichiarazione dell'enum: aggiungere un incarico in mezzo a
            // [MatchDuty] scambierebbe in silenzio il capitano con il rigorista.
            listOf(
                MatchDuty.CAPITANO,
                MatchDuty.RIGORISTA,
                MatchDuty.ANGOLI,
                MatchDuty.PUNIZIONI,
                MatchDuty.LANCI_LUNGHI,
            ).forEachIndexed { indice, duty ->
                val id = SetPieces.idFor(lineup, duty)?.value
                if (id == null) st.setNull(7 + indice, java.sql.Types.BIGINT)
                else st.setLong(7 + indice, id)
            }
            st.executeUpdate()
        }
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
    // ------------------------------------------------------- le AI e il listino

    /**
     * I giocatori indicati, in un colpo solo.
     *
     * Una query per tutto il listino invece di una per riga: `loadSquad` fa gia' una
     * query per club ed e' chiamata da sette punti — e' il difetto che rende il tick
     * lento otto minuti a giro. Non si aggiunge un altro ciclo di andate e ritorno.
     */
    private fun loadPlayersByIds(leagueId: Long, ids: List<Long>): Map<Long, Player> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, Player>(ids.size)
        connection.prepareStatement(
            "select p.* from players p where p.league_id = ? and p.id = any(?)",
        ).use { st ->
            st.setLong(1, leagueId)
            st.setArray(2, connection.createArrayOf("bigint", ids.toTypedArray()))
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val player = readPlayer(rs)
                    out[player.id.value] = player
                }
            }
        }
        return out
    }

    /**
     * Il listino aperto della lega, con i giocatori gia. agganciati.
     *
     * Due query di cui una riporta un migliaio di righe: e. la lettura piu. cara del giro,
     * ed era ripetuta a ogni mossa di ogni AI. Adesso vale finche. nessuno tocca il mercato.
     */
    private fun loadListings(leagueId: Long): List<Pair<Listing, Player>> =
        listinoInCache ?: leggiListino(leagueId).also { listinoInCache = it }

    private fun leggiListino(leagueId: Long): List<Pair<Listing, Player>> {
        val righe = mutableListOf<Triple<Long, Long?, Pair<Long, Int>>>()
        connection.prepareStatement(
            // Solo i giocatori: lo staff sta nella stessa tabella con un target_type suo,
            // e players e staff hanno sequenze di id separate — senza il filtro un'AI
            // comprerebbe un allenatore credendo di prendere un centrocampista.
            "select id, player_id, seller_club_id, price from listings " +
                "where league_id = ? and status = 'APERTO' and target_type = 'player'",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    righe += Triple(
                        rs.getLong("id"),
                        rs.getLong("seller_club_id").takeIf { !rs.wasNull() },
                        rs.getLong("player_id") to rs.getInt("price"),
                    )
                }
            }
        }
        if (righe.isEmpty()) return emptyList()

        val giocatori = loadPlayersByIds(leagueId, righe.map { it.third.first })
        return righe.mapNotNull { (id, seller, coppia) ->
            val player = giocatori[coppia.first] ?: return@mapNotNull null
            Listing(
                id = id,
                playerId = player.id,
                seller = seller?.let { ClubId(it) },
                price = coppia.second,
                listedAt = Instant.now(),
            ) to player
        }
    }

    /**
     * Mette a listino gli svincolati, e toglie dal listino chi non lo e' piu'.
     *
     * ## Il buco che questa funzione chiude
     *
     * La regola del 2026-08-24 dice: «sul listino ci vanno **gli svincolati** e chi il
     * proprietario mette in vendita». La seconda meta' funzionava — `list_player` — e la
     * prima no: `listings` si riempiva solo quando qualcuno vendeva, quindi un giocatore
     * senza contratto non era comprabile a prezzo fisso e restava raggiungibile **solo
     * all'asta**. Cioe' esattamente la cosa da cui il listino serve a scappare, per la
     * meta' piu' numerosa del mercato.
     *
     * Trovato provando le funzioni sul database vero, non leggendo il codice.
     *
     * ## Perche' il prezzo lo mette il tick e non il database
     *
     * Perche' il prezzo di uno svincolato e' il suo **valore di mercato**, e quel conto e'
     * `Valuation.marketValue` — una curva con esponente 7,5, l'eta' e il potenziale, tarata
     * da un test che stampa il listino. Riscriverla in SQL vorrebbe dire due listini che si
     * separano al primo ritocco. Il tick ha `core` in mano: il conto lo fa una volta sola,
     * con la regola vera.
     *
     * ## Gli under 20 restano fuori
     *
     * Regola di `0019`: si trovano mandandoci un osservatore. A prezzo fisso un fuoriclasse
     * di diciotto anni sarebbe di chi ha piu' soldi e basta.
     */
    private fun aggiornaListinoSvincolati(league: LeagueRow) {
        if (!league.config.market.instantBuyEnabled) return
        mercatoCambiato()

        // Chi e' a listino come svincolato ma nel frattempo ha trovato squadra esce: la
        // riga resterebbe comprabile, e due club si contenderebbero un giocatore che ha
        // gia' un contratto.
        connection.prepareStatement(
            """
            update listings set status = 'RITIRATO'
            where league_id = ? and status = 'APERTO' and target_type = 'player'
              and seller_club_id is null
              and player_id in (select player_id from contracts where league_id = ?)
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setLong(2, league.id)
            st.executeUpdate()
        }

        val daMettere = mutableListOf<Player>()
        connection.prepareStatement(
            """
            select p.* from players p
            where p.league_id = ?
              and p.age >= ?
              and not exists (select 1 from contracts c where c.player_id = p.id)
              and not exists (
                  select 1 from listings l
                  where l.player_id = p.id and l.target_type = 'player' and l.status = 'APERTO'
              )
              and not exists (
                  select 1 from auctions a
                  where a.target_type = 'player' and a.target_id = p.id and a.status = 'APERTA'
              )
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setInt(2, ETA_MINIMA_A_LISTINO)
            st.executeQuery().use { rs -> while (rs.next()) daMettere += readPlayer(rs) }
        }

        if (daMettere.isEmpty()) return

        connection.prepareStatement(
            "insert into listings (league_id, player_id, seller_club_id, price, target_type) " +
                "values (?, ?, null, ?, 'player')",
        ).use { st ->
            daMettere.forEach { player ->
                st.setLong(1, league.id)
                st.setLong(2, player.id.value)
                st.setInt(3, Valuation.marketValue(player, league.config).coerceAtLeast(1))
                st.addBatch()
            }
            st.executeBatch()
        }

        log("Listino: ${daMettere.size} svincolati messi in vendita al valore di mercato.")
    }

    /** Sotto questa eta' uno svincolato si trova solo con gli osservatori, non a listino. */
    private val ETA_MINIMA_A_LISTINO = 20

    /**
     * Compra dal listino, subito.
     *
     * ## Perche' questa mossa cambia il ritmo della lega
     *
     * Perche' e' l'unica che non ha bisogno di un altro giro di tick. Aprire un'asta e
     * aspettare che chiuda costa due risvegli e un'ora di orologio; qui il giocatore entra
     * in rosa dentro questa transazione. E' la correzione della lamentela «le AI ci mettono
     * settimane a riempire la rosa», che non era una loro lentezza: era la strada che
     * avevano a disposizione.
     */
    private fun compraDalListino(
        league: LeagueRow,
        state: AiState,
        club: Club,
        squad: List<Player>,
    ): Boolean {
        val listino = loadListings(league.id)
        if (listino.isEmpty()) return false

        val (listing, player) = AiMarket.playerToBuy(state, club, squad, listino, league.config)
            ?: return false

        // La riga si blocca prima di toccarla: due AI che si svegliano nello stesso giro
        // devono trovarne una sola disponibile.
        val preso = connection.prepareStatement(
            "update listings set status = 'VENDUTO' where id = ? and status = 'APERTO'",
        ).use { st ->
            st.setLong(1, listing.id)
            st.executeUpdate() > 0
        }
        if (!preso) {
            // Zero righe toccate vuol dire che quella riga non e' piu' aperta: qualcun
            // altro l'ha presa. E' l'unico caso in cui una mossa **fallita** dimostra che
            // il listino in memoria e' vecchio, quindi va buttato anche qui.
            mercatoCambiato()
            return false
        }

        addebita(club.id, listing.price)
        listing.seller?.let { accredita(it, listing.price) }
        assignPlayer(league, player.id, club.id, listing.price)

        val finestra = league.config.market.contestWindowHours
        connection.prepareStatement(
            """
            insert into purchases (league_id, player_id, buyer_club_id, seller_club_id,
                                   price, contestable_until)
            values (?, ?, ?, ?, ?, now() + make_interval(hours => ?))
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setLong(2, player.id.value)
            st.setLong(3, club.id.value)
            if (listing.seller != null) st.setLong(4, listing.seller!!.value) else st.setNull(4, java.sql.Types.BIGINT)
            st.setInt(5, listing.price)
            st.setInt(6, finestra)
            st.executeUpdate()
        }

        listing.seller?.let { venditore ->
            notify(
                league.id, venditore,
                "${clubNameOf(club.id)} ha comprato ${player.shortName} per ${listing.price}.",
                kind = "mercato", urgency = "immediata",
            )
        }
        log("${clubNameOf(club.id)} compra ${player.shortName} a listino per ${listing.price}.")
        return true
    }

    /**
     * Contesta l'acquisto di qualcun altro.
     *
     * Solo su chi voleva davvero e solo se e' stato pagato troppo poco: la regola sta in
     * [AiMarket], con le sue prove. Se contestassero tutto, il listino tornerebbe a essere
     * un'asta continua — cioe' la cosa da cui serve a scappare.
     */
    private fun contestaUnAcquisto(
        league: LeagueRow,
        state: AiState,
        club: Club,
        squad: List<Player>,
    ): Boolean {
        val aperti = loadOpenPurchases(league.id).filter { it.buyer != club.id }
        if (aperti.isEmpty()) return false

        val giocatori = loadPlayersByIds(league.id, aperti.map { it.playerId.value })

        for (purchase in aperti) {
            val player = giocatori[purchase.playerId.value] ?: continue
            val massimo = AiMarket.contestBid(state, club, squad, purchase, player, league.config)
                ?: continue

            val auctionId = purchase.auctionId ?: apriAstaDiContestazione(league, purchase, club)

            // L'offerta passa da `place_bid` come tutte le altre: anti-snipe, blocco fondi
            // e prezzo corrente stanno gia' li' dentro, e riscriverli qui vorrebbe dire due
            // regole d'asta che si separano al primo ritocco.
            val accettata = connection.prepareStatement("select place_bid(?, ?, ?)").use { st ->
                st.setLong(1, auctionId)
                st.setLong(2, club.id.value)
                st.setInt(3, massimo)
                st.executeQuery().use { rs ->
                    rs.next() && JsonNode.parse(rs.getString(1))["ok"].bool(false)
                }
            }
            if (!accettata) continue

            notify(
                league.id, purchase.buyer,
                "${clubNameOf(club.id)} ha contestato il tuo acquisto di ${player.shortName}.",
                kind = "asta", urgency = "immediata",
            )
            log("${clubNameOf(club.id)} contesta l'acquisto ${purchase.id} a $massimo.")
            return true
        }
        return false
    }

    /** Gli acquisti ancora contestabili. Riletti solo dopo una scrittura sul mercato. */
    private fun loadOpenPurchases(leagueId: Long): List<Purchase> =
        acquistiInCache ?: leggiAcquistiAperti(leagueId).also { acquistiInCache = it }

    private fun leggiAcquistiAperti(leagueId: Long): List<Purchase> {
        val out = mutableListOf<Purchase>()
        connection.prepareStatement(
            "select id, player_id, buyer_club_id, seller_club_id, price, bought_at, " +
                "contestable_until, status, auction_id from purchases " +
                "where league_id = ? and status in ('IN_FINESTRA','CONTESTATO') " +
                "and contestable_until > now()",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> while (rs.next()) out += readPurchase(rs) }
        }
        return out
    }

    /**
     * Fa nascere l'asta di contestazione, con dentro gia' chi ha comprato.
     *
     * La regola sta in `core` (`ContestRules.open`) e la fa anche `contest_purchase` in
     * SQL: chi ha comprato entra al prezzo che ha pagato e i suoi crediti risultano
     * impegnati, esattamente come quelli di chiunque altro.
     */
    private fun apriAstaDiContestazione(
        league: LeagueRow,
        purchase: Purchase,
        contestante: Club,
    ): Long {
        val auctionId = connection.prepareStatement(
            """
            insert into auctions (league_id, target_type, target_id, started_by,
                                  ends_at, starting_price)
            values (?, 'player', ?, ?, ?, ?) returning id
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setLong(2, purchase.playerId.value)
            st.setLong(3, contestante.id.value)
            st.setTimestamp(4, java.sql.Timestamp.from(purchase.contestableUntil))
            st.setInt(5, purchase.price)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }

        connection.prepareStatement(
            "insert into bids (auction_id, club_id, max_amount, placed_at) values (?, ?, ?, ?)",
        ).use { st ->
            st.setLong(1, auctionId)
            st.setLong(2, purchase.buyer.value)
            st.setInt(3, purchase.price)
            st.setTimestamp(4, java.sql.Timestamp.from(purchase.boughtAt))
            st.executeUpdate()
        }
        connection.prepareStatement(
            "update clubs set committed_credits = committed_credits + ? where id = ?",
        ).use { st ->
            st.setInt(1, purchase.price)
            st.setLong(2, purchase.buyer.value)
            st.executeUpdate()
        }
        connection.prepareStatement(
            "update purchases set status = 'CONTESTATO', auction_id = ? where id = ?",
        ).use { st ->
            st.setLong(1, auctionId)
            st.setLong(2, purchase.id)
            st.executeUpdate()
        }

        return auctionId
    }

    /** Mette in vendita a listino chi non serve piu', al prezzo che chiede l'AI. */
    private fun mettiSulListino(
        league: LeagueRow,
        state: AiState,
        club: Club,
        squad: List<Player>,
    ): Boolean {
        val (daCedere, _) = AiInitiative.playerToSell(state, squad, league.config) ?: return false

        // Uno gia' in vendita o gia' all'asta non si rimette in vendita.
        val libero = connection.prepareStatement(
            """
            select 1 from listings
             where player_id = ? and target_type = 'player' and status = 'APERTO'
            union all
            select 1 from auctions where target_type = 'player' and target_id = ? and status = 'APERTA'
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, daCedere.id.value)
            st.setLong(2, daCedere.id.value)
            st.executeQuery().use { rs -> !rs.next() }
        }
        if (!libero) return false

        val prezzo = AiMarket.askingPrice(daCedere, state.personality, league.config)
        connection.prepareStatement(
            "insert into listings (league_id, player_id, seller_club_id, price, target_type) " +
                "values (?, ?, ?, ?, 'player')",
        ).use { st ->
            st.setLong(1, league.id)
            st.setLong(2, daCedere.id.value)
            st.setLong(3, club.id.value)
            st.setInt(4, prezzo)
            st.executeUpdate()
        }

        log("${clubNameOf(club.id)} mette in vendita ${daCedere.shortName} a $prezzo.")
        return true
    }

    /**
     * Offre crediti a un altro club per un suo giocatore.
     *
     * ## La mossa che mancava del tutto
     *
     * `AiInitiative.proposeTrade` sa proporre solo giocatore contro giocatore. Il
     * conguaglio in crediti esiste nel modello dal principio — `TradeOffer.cash` ha il
     * segno — e non veniva mai usato da solo: in tutta la vita di una lega nessuna AI ha
     * mai chiesto «quanto vuoi per il tuo attaccante?», che e' la cosa piu' normale che
     * possa fare un club.
     */
    private fun offriCreditiPerUnGiocatore(
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

        val bersaglio = altri[(club.id.value + today.value + 1).toInt().mod(altri.size)]
        val suaRosa = loadSquad(league.id, bersaglio)
        if (suaRosa.size <= league.config.setup.minSquadSize) return false

        // Il migliore fra quelli che le interessano davvero.
        val scelta = suaRosa
            .mapNotNull { player ->
                AiMarket.cashOffer(state, club, squad, player, league.config)
                    ?.let { player to it }
            }
            .maxByOrNull { it.second } ?: return false

        val (player, crediti) = scelta

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
            st.setArray(4, connection.createArrayOf("bigint", emptyArray<Long>()))
            st.setArray(5, connection.createArrayOf("bigint", arrayOf(player.id.value)))
            st.setInt(6, crediti)
            st.setString(
                7,
                "Ti offro $crediti crediti per ${player.shortName}. Nessuno scambio, " +
                    "solo soldi.",
            )
            st.executeUpdate()
        }

        notify(
            league.id, bersaglio,
            "${clubNameOf(club.id)} ti offre $crediti crediti per ${player.shortName}.",
            kind = "scambio", urgency = "immediata",
        )
        log("${clubNameOf(club.id)} offre $crediti per ${player.shortName}.")
        return true
    }

    /**
     * «Il mio attaccante non gioca mai, lo prendi in prestito?»
     *
     * L'unica mossa delle AI che apre un discorso invece di chiudere una transazione:
     * arriva con un messaggio scritto, e chi la riceve puo' rispondere. Le AI sapevano
     * gia' **rispondere** a un prestito e non proporne mai uno — che e' la parte visibile
     * del «non fanno mai il primo passo».
     */
    private fun proponiUnPrestito(
        league: LeagueRow,
        state: AiState,
        club: Club,
        squad: List<Player>,
        today: MatchDay,
    ): Boolean {
        if (!league.config.market.loansEnabled) return false

        val ragazzo = AiInitiative.playerToLoanOut(squad, league.config) ?: return false

        val altri = loadClubIds(league.id)
            .filter { it != club.id && state.canActOn(it, today) }
            .filterNot { haGiaUnaPropostaAperta(club.id, it) }
        if (altri.isEmpty()) return false

        val bersaglio = altri[(club.id.value + ragazzo.id.value).toInt().mod(altri.size)]
        val giornate = league.config.market.minLoanMatchDays
            .coerceAtLeast(league.config.market.maxLoanMatchDays / 2)

        val terms = JsonWriter(256).apply {
            beginObject()
            field("matchDays", giornate)
            field("fee", 0)
            field("wagePaidByBorrower", true)
            field("canPlayAgainstOwner", false)
            endObject()
        }.toString()

        connection.prepareStatement(
            """
            insert into trades (league_id, from_club, to_club, offered, wanted, cash,
                                message, kind, terms)
            values (?, ?, ?, ?, ?, 0, ?, 'PRESTITO', ?::jsonb)
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, league.id)
            st.setLong(2, club.id.value)
            st.setLong(3, bersaglio.value)
            st.setArray(4, connection.createArrayOf("bigint", arrayOf(ragazzo.id.value)))
            st.setArray(5, connection.createArrayOf("bigint", emptyArray<Long>()))
            st.setString(
                6,
                "${ragazzo.shortName} da noi non gioca mai e ha ancora da crescere. " +
                    "Te lo diamo in prestito per $giornate giornate: stipendio a carico " +
                    "vostro, nessun canone.",
            )
            st.setString(7, terms)
            st.executeUpdate()
        }

        notify(
            league.id, bersaglio,
            "${clubNameOf(club.id)} ti offre ${ragazzo.shortName} in prestito.",
            kind = "scambio", urgency = "immediata",
        )
        log("${clubNameOf(club.id)} propone ${ragazzo.shortName} in prestito.")
        return true
    }

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

        // Giornate **mancanti**, non la giornata assoluta. Prima ci finiva il numero di
        // giornata: a inizio stagione valeva 1 o 2 e il controllo «almeno due giornate
        // libere» diceva no anche con il calendario vuoto, mentre a stagione avanzata
        // valeva trenta e diceva sempre si', partita di domani compresa.
        val prossima = giornateAllaProssimaPartita(league.id, club.id, league.currentMatchDay)
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
    /**
     * Quante **giornate mancano** alla prossima partita vera di questo club.
     *
     * Restituisce gia' la differenza, non la giornata assoluta. Prima tornava
     * `min(match_day)` e chi chiamava ci sottraeva la giornata corrente per conto suo, con
     * due conseguenze: il valore di ripiego 99 diventava `99 - giornata`, cioe' un numero
     * che calava col passare della stagione fino a mentire; e una partita **arretrata** —
     * non giocata e con la giornata gia' passata — produceva un negativo, che e' un modo
     * involontario di dire «gioco fra meno di zero giorni».
     *
     * Zero significa oggi. Non scende mai sotto zero: una partita che andava giocata ieri
     * e' comunque una partita che incombe, non una gia' passata.
     */
    private fun giornateAllaProssimaPartita(leagueId: Long, clubId: ClubId, oggi: Int): Int =
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
                if (!rs.next()) return@use SENZA_PARTITE
                val prossima = rs.getInt(1)
                if (rs.wasNull()) SENZA_PARTITE else (prossima - oggi).coerceAtLeast(0)
            }
        }

    /** Nessuna partita in programma: qualunque numero grande va bene, purche' non menta. */
    private val SENZA_PARTITE = 99

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

        // Le aste dello staff si valutano a parte: non c'e' un giocatore da stimare, c'e'
        // un ruolo che manca o non manca. Senza questo ramo un'AI apriva l'asta per un
        // allenatore e poi non ci offriva sopra nemmeno lei, e andava deserta.
        val staffScelta = auctions
            .mapNotNull { asta ->
                val target = (asta.target as? AuctionTarget.ForStaff) ?: return@mapNotNull null
                val (ruolo, stelle) = staffInAsta(target.staffId) ?: return@mapNotNull null
                if (ruoloMancante(club.id) != ruolo) return@mapNotNull null

                // Quanto vale un ruolo che manca: una frazione del disponibile che cresce
                // con le stelle. Chi ha disciplina si ferma prima.
                val tetto = StrictMath.round(
                    club.availableCredits * MathX.lerp(0.14, 0.05, state.personality.budgetDiscipline) *
                        stelle,
                ).toInt()
                if (tetto < asta.currentPrice(league.config.market) + league.config.market.minimumRaise) {
                    return@mapNotNull null
                }
                asta to tetto
            }
            .maxByOrNull { it.second }

        if (staffScelta != null) {
            val (asta, tetto) = staffScelta
            val ok = connection.prepareStatement("select place_bid(?, ?, ?)").use { st ->
                st.setLong(1, asta.id)
                st.setLong(2, club.id.value)
                st.setInt(3, tetto)
                st.executeQuery().use { rs ->
                    rs.next() && JsonNode.parse(rs.getString(1))["ok"].bool(false)
                }
            }
            if (ok) return true
        }

        // I giocatori all'asta, tutti in una query. Era `loadPlayerRow` dentro il ciclo:
        // un'andata e ritorno per asta, moltiplicata per ogni AI e per ogni mossa.
        val allAsta = loadPlayerRows(
            auctions.mapNotNull { (it.target as? AuctionTarget.ForPlayer)?.playerId?.value },
        )

        val candidates = auctions.mapNotNull { auction ->
            val target = (auction.target as? AuctionTarget.ForPlayer) ?: return@mapNotNull null
            val player = allAsta[target.playerId.value]?.player ?: return@mapNotNull null

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

        // Il tetto della lega, non solo quello del club.
        //
        // Otto club per sei aste fanno quarantotto, e a quel punto il listino e' una parete
        // in cui non si trova niente: e' successo davvero, sessantasette aperte insieme.
        // Questo e' il numero che decide quante cose si riescono a seguire.
        val inLega = countOpenAuctions(league.id)
        val spazioInLega = (market.maxOpenAuctionsPerLeague - inLega).coerceAtLeast(0)
        if (spazioInLega <= 0) return false

        var quante = minOf(AiTurn.auctionsToOpen(squad.size, aperte, league.config), spazioInLega)
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
        if (countOpenAuctions(league.id) >= league.config.market.maxOpenAuctionsPerLeague) return false

        val (giocatore, base) = AiInitiative.playerToSell(state, squad, league.config)
            ?: return false

        // Un giocatore in prestito non e' suo da vendere: alla scadenza deve tornare.
        if (inPrestito(giocatore.id)) return false

        val giaInAsta = loadOpenAuctions(league.id)
            .mapNotNull { (it.target as? AuctionTarget.ForPlayer)?.playerId?.value }
            .toSet()
        if (giocatore.id.value in giaInAsta) return false

        // Qui il club **vende un suo** giocatore: non offre, o comprerebbe da se' stesso.
        apriAsta(
            league.id, giocatore.id.value, club.id, base,
            league.config.market.auctionDurationMinutes,
            vendendo = true,
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

    /**
     * Apre un'asta per conto di un club dell'AI.
     *
     * ## Chi apre per comprare ha gia' offerto il prezzo base
     *
     * La stessa regola di `AuctionRules.open` in `core` e di `start_auction` sul
     * database. Qui mancava come mancava la': l'asta nasceva senza nessuno in testa, e
     * quella aperta da un'AI su un giocatore che voleva poteva scadere **deserta** con
     * l'AI stessa che non aveva mai offerto.
     *
     * C'era anche un secondo effetto, piu' silenzioso: la routine che apre le aste teneva
     * il conto dell'impegno **in memoria** (`impegnato += appeal.ceiling`) mentre sul
     * database non risultava impegnato niente, perche' l'impegno si legge dalle offerte.
     * Al giro dopo il conto ripartiva da zero e lo stesso club poteva riaprire aste che
     * insieme valevano piu' della sua cassa.
     *
     * Quando [vendendo] e' vero il club possiede gia' il giocatore: e' il venditore, e
     * un'offerta sua sarebbe comprare da se' stesso.
     */
    private fun apriAsta(
        leagueId: Long,
        playerId: Long,
        startedBy: ClubId,
        base: Int,
        durataMinuti: Int,
        vendendo: Boolean = false,
    ) {
        val auctionId = connection.prepareStatement(
            """
            insert into auctions (league_id, target_type, target_id, started_by, ends_at,
                                  starting_price, current_price, status)
            values (?, 'player', ?, ?, now() + make_interval(mins => ?), ?, ?, 'APERTA')
            returning id
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setLong(2, playerId)
            st.setLong(3, startedBy.value)
            st.setInt(4, durataMinuti)
            st.setInt(5, base)
            st.setInt(6, base)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        } ?: return

        if (vendendo) return

        connection.prepareStatement(
            "insert into bids (auction_id, club_id, max_amount) values (?, ?, ?)",
        ).use { st ->
            st.setLong(1, auctionId)
            st.setLong(2, startedBy.value)
            st.setInt(3, base)
            st.executeUpdate()
        }

        connection.prepareStatement(
            "update clubs set committed_credits = committed_credits + ? where id = ?",
        ).use { st ->
            st.setInt(1, base)
            st.setLong(2, startedBy.value)
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

    /** Quante aste sono aperte in tutta la lega. */
    private fun countOpenAuctions(leagueId: Long): Int =
        connection.prepareStatement(
            "select count(*) from auctions where league_id = ? and status = 'APERTA'",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
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
              and p.age >= 20
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

    /** Se questo club e' del computer. Chiesto una volta per club dentro `tryBid`. */
    private fun isAi(clubId: ClubId): Boolean = eDelComputer.getOrPut(clubId.value) {
        connection.prepareStatement("select is_ai from clubs where id = ?").use { st ->
            st.setLong(1, clubId.value)
            st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
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
            "Un contratto è scaduto: il giocatore è tornato svincolato.",
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

        notify(league.id, loan.borrowerClub, "Un prestito è finito: il giocatore è tornato al suo club.",
            kind = "prestito", urgency = "riepilogo")
        notify(league.id, loan.ownerClub, "Ti è tornato un giocatore dal prestito.",
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

        // Gli stipendi della Primavera li paga il club padre.
        //
        // La seconda squadra non ha portafoglio: nasce a zero e non incassa mai. Senza
        // questo `coalesce(parent_club_id, id)` i suoi giocatori non costerebbero niente a
        // nessuno, e riempirla di ragazzi sarebbe gratis — che e' il modo piu' rapido di
        // svuotare di senso un settore giovanile.
        connection.prepareStatement(
            """
            update clubs c
            set credits = ${floor}c.credits - coalesce((
                select sum(greatest(1, round(p.overall * p.overall * ?)))
                from contracts ct
                join players p on p.id = ct.player_id
                join clubs pagante on pagante.id = ct.club_id
                where coalesce(pagante.parent_club_id, pagante.id) = c.id
            ), 0)$close
            where c.league_id = ? and c.parent_club_id is null
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
    private fun recoverStamina(league: LeagueRow, now: Instant) {
        // LE ORE VERE PASSATE DALL'ULTIMA VOLTA
        //
        // Il recupero era «34 punti per giornata», e una giornata e' una fascia oraria del
        // calendario: quanto tempo valesse dipendeva da quante fasce l'admin aveva messo in
        // un giorno. Con due erano dodici ore, con quattro sei — lo stesso riposo pagava il
        // doppio in una lega e la meta' in un'altra, e non c'era modo di accorgersene.
        //
        // Adesso e' una quantita' di tempo, come lo e' il riposo. Il tetto di dodici ore
        // per giro serve a chi torna dopo tre giorni: si recupera tutto lo stesso, ma senza
        // che una sola query regali quattrocento punti a chiunque.
        val ore = oreDallUltimoRecupero(league.id, now)
        if (ore <= 0.0) return

        val base = league.config.engine.staminaRecoveryPerHour * ore

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

        segnaRecupero(league.id, now)
    }

    /**
     * Quante ore reali sono passate dall'ultimo recupero.
     *
     * Zero quando non ne e' passata nemmeno una: il tick gira ogni cinque minuti, e
     * accreditare un ottavo d'ora dodici volte l'ora sarebbe lo stesso risultato con dodici
     * volte le scritture — piu' il minimo di un punto che, applicato a ogni giro,
     * regalerebbe dodici punti l'ora a tutti indipendentemente dalla configurazione.
     *
     * Alla prima volta in assoluto non si recupera niente e si segna il momento: senza,
     * una lega appena creata vedrebbe accreditarsi le ore dall'inizio dei tempi.
     */
    private fun oreDallUltimoRecupero(leagueId: Long, now: Instant): Double {
        val ultimo = connection.prepareStatement(
            "select last_stamina_at from tick_state where league_id = ?",
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1)?.toInstant() else null }
        }

        if (ultimo == null) {
            segnaRecupero(leagueId, now)
            return 0.0
        }

        val ore = java.time.Duration.between(ultimo, now).toMinutes() / 60.0
        return if (ore < 1.0) 0.0 else ore.coerceAtMost(ORE_MASSIME_PER_GIRO)
    }

    /** Il tetto per giro: chi torna dopo tre giorni recupera comunque, ma non in un colpo. */
    private val ORE_MASSIME_PER_GIRO = 12.0

    private fun segnaRecupero(leagueId: Long, now: Instant) {
        connection.prepareStatement(
            """
            insert into tick_state (league_id, last_stamina_at) values (?, ?)
            on conflict (league_id) do update set last_stamina_at = excluded.last_stamina_at
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.setTimestamp(2, Timestamp.from(now))
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
            // Gli ordini condizionali erano completi in `core` dal primo giorno e la
            // colonna li aspettava: da qui in avanti arrivano fino al motore, ed e' cio'
            // che permette di preparare una partita che si gioca mentre si lavora.
            orders = repaired.orders,
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
        val cornerTakerId: Long? = null,
        val freeKickTakerId: Long? = null,
        val longBallTakerId: Long? = null,
        /** Gli ordini condizionali, ancora come li ha scritti l'app. */
        val orders: String? = null,
    )

    /** Quello che si e' riusciti a ricostruire, piu' cosa e' stato corretto d'ufficio. */
    private data class RepairedLineup(
        val lineup: Lineup,
        val tactics: Tactics,
        val orders: List<ConditionalOrder>,
        val problems: List<String>,
    )

    /**
     * La formazione salvata.
     *
     * ## Perche' due query e non una
     *
     * Le tre colonne degli incarichi da palla ferma arrivano dalla migrazione `0027`. Su un
     * database dove non e' ancora stata eseguita, chiederle fa fallire l'intera `select` —
     * e qui dentro un fallimento non riguarda una schermata: **annullerebbe la transazione
     * della lega**, cioe' fermerebbe le partite di tutti finche' qualcuno non se ne accorge.
     *
     * Si prova la lettura completa; se il database e' indietro si ricade su quella di
     * sempre, e gli incarichi li sceglie il motore come faceva prima che esistessero.
     */
    private fun loadSavedLineup(clubId: ClubId): SavedLineupRow? =
        runCatching { readLineupRow(clubId, conIncarichi = true) }
            .getOrElse { readLineupRow(clubId, conIncarichi = false) }

    private fun readLineupRow(clubId: ClubId, conIncarichi: Boolean): SavedLineupRow? {
        val colonne = buildString {
            append("formation, slots, bench, tactics, captain_id, penalty_taker_id, orders")
            if (conIncarichi) {
                append(", corner_taker_id, free_kick_taker_id, long_ball_taker_id")
            }
        }

        return connection.prepareStatement(
            "select $colonne from lineups where club_id = ?",
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
                    cornerTakerId = if (conIncarichi) {
                        rs.getLong("corner_taker_id").takeIf { !rs.wasNull() }
                    } else null,
                    freeKickTakerId = if (conIncarichi) {
                        rs.getLong("free_kick_taker_id").takeIf { !rs.wasNull() }
                    } else null,
                    longBallTakerId = if (conIncarichi) {
                        rs.getLong("long_ball_taker_id").takeIf { !rs.wasNull() }
                    } else null,
                    orders = rs.getString("orders"),
                )
            }
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
            problems += "$scartati titolari salvati non sono più disponibili"
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

        // Gli incarichi: quello scelto se e' ancora in campo, altrimenti il piu' adatto.
        // La scelta la fa [SetPieces], che e' lo stesso codice con cui l'app li mostra:
        // due criteri diversi vorrebbero dire un rigorista sullo schermo e un altro nel
        // tabellino, e chi guarda penserebbe che il gioco non ascolti.
        val base = Lineup(formation = formation, slots = slots, bench = bench)
        val salvati = mapOf(
            MatchDuty.CAPITANO to saved.captainId,
            MatchDuty.RIGORISTA to saved.penaltyTakerId,
            MatchDuty.ANGOLI to saved.cornerTakerId,
            MatchDuty.PUNIZIONI to saved.freeKickTakerId,
            MatchDuty.LANCI_LUNGHI to saved.longBallTakerId,
        )
        val conIncarichi = MatchDuty.entries.fold(base) { lineup, duty ->
            val scelto = salvati[duty]
                ?.let { id -> eleven.firstOrNull { it.id.value == id }?.id }
                ?: SetPieces.best(SetPieces.candidates(lineup, duty), duty)?.id
            SetPieces.assign(lineup, duty, scelto)
        }

        return RepairedLineup(
            lineup = conIncarichi,
            tactics = LineupJson.tactics(saved.tactics),
            orders = OrderJson.read(JsonNode.parse(saved.orders ?: "[]")),
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
            where c.club_id = ? and p.league_id = ?
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

    /**
     * Gli stessi dati di [loadPlayerRow] per molti giocatori, in una query sola.
     *
     * Esiste perche' la versione a uno per volta, chiamata dentro un ciclo, era la spesa
     * piu' grande dell'intero giro: non per quanto lavora il database — quasi niente — ma
     * per quante volte ci si va.
     */
    private fun loadPlayerRows(ids: List<Long>): Map<Long, PlayerRow> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, PlayerRow>(ids.size)

        connection.prepareStatement(
            "select p.*, c.club_id from players p " +
                "left join contracts c on c.player_id = p.id where p.id = any(?)",
        ).use { st ->
            st.setArray(1, connection.createArrayOf("bigint", ids.toTypedArray()))
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val player = readPlayer(rs)
                    out[player.id.value] = PlayerRow(player, ClubId(rs.getLong("club_id")))
                }
            }
        }
        return out
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
        stelleAllenatore.getOrPut(clubId.value) {
            connection.prepareStatement(
                "select max(stars) from staff where club_id = ? and role = 'ALLENATORE'",
            ).use { st ->
                st.setLong(1, clubId.value)
                st.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1).takeIf { !rs.wasNull() && it > 0 } ?: 3 else 3
                }
            }
        }

    private fun clubNameOf(clubId: ClubId): String =
        nomiDeiClub.getOrPut(clubId.value) {
            connection.prepareStatement("select name from clubs where id = ?").use { st ->
                st.setLong(1, clubId.value)
                st.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else "Club ${clubId.value}"
                }
            }
        }

    /**
     * Le partite ancora da cominciare.
     *
     * `resume_at is null` esclude quelle **gia' cominciate e ferme all'intervallo**: sono
     * ancora `not played`, e senza questo filtro verrebbero pianificate come se dovessero
     * partire adesso — cioe' il primo tempo si rigiocherebbe da capo mentre il secondo
     * aspetta. Le riprese le raccoglie [loadPausedFixtures], su un'altra strada.
     *
     * La colonna arriva dalla migrazione `0029`: qui si puo' chiedere perche' e' il tick,
     * che gira su un database di cui l'amministratore controlla le migrazioni, e non
     * l'app installata su cinque telefoni diversi.
     */
    private fun loadPendingFixtures(leagueId: Long): List<Fixture> {
        val out = mutableListOf<Fixture>()
        connection.prepareStatement(
            """
            select id, competition_id, round, round_label, home_club_id, away_club_id,
                   match_day, kickoff, tie_id, is_second_leg
            from fixtures
            where league_id = ? and not played and kickoff is not null
              and resume_at is null
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

    /** Le partite ferme all'intervallo, con l'ora in cui riprendono. */
    private fun loadPausedFixtures(leagueId: Long): List<PausedFixture> {
        val out = mutableListOf<PausedFixture>()
        connection.prepareStatement(
            """
            select id, competition_id, round, round_label, home_club_id, away_club_id,
                   match_day, kickoff, tie_id, is_second_leg, resume_at
            from fixtures
            where league_id = ? and not played and resume_at is not null
            order by resume_at
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, leagueId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out += PausedFixture(
                        fixture = Fixture(
                            id = rs.getLong("id"),
                            competitionId = CompetitionId(rs.getLong("competition_id")),
                            round = rs.getInt("round"),
                            roundLabel = rs.getString("round_label"),
                            home = ClubId(rs.getLong("home_club_id")),
                            away = ClubId(rs.getLong("away_club_id")),
                            matchDay = MatchDay(rs.getInt("match_day")),
                            kickoff = rs.getTimestamp("kickoff")?.toInstant()
                                ?.atZone(ZoneOffset.UTC)?.toLocalDateTime(),
                            tieId = rs.getString("tie_id"),
                            isSecondLeg = rs.getBoolean("is_second_leg"),
                        ),
                        resumeAt = rs.getTimestamp("resume_at").toInstant(),
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

    /** Le aste aperte. Rilette solo dopo che qualcuno ha scritto sul mercato. */
    private fun loadOpenAuctions(leagueId: Long): List<Auction> =
        asteInCache ?: leggiAsteAperte(leagueId).also { asteInCache = it }

    private fun leggiAsteAperte(leagueId: Long): List<Auction> {
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
