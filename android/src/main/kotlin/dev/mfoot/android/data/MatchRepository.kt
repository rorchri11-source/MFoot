package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import java.time.Instant

/** Un momento della partita, come sta nella timeline salvata. */
data class MatchMoment(
    val minute: Int,
    val type: String,
    val homeSide: Boolean,
    val danger: Int,
    val text: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val playerId: Long?,
    /**
     * In quale delle nove zone si trova la palla, dal punto di vista di chi attacca.
     *
     * `DIF_SX`, `MID_C`, `ATT_DX`… E' quello che permette di **disegnare** l'azione invece
     * di elencarla: il motore ragiona su quella griglia dal primo giorno e la scrive nella
     * timeline, e per mesi non l'ha letta nessuno.
     */
    val zone: String? = null,
) {
    val isGoal: Boolean get() = type == "GOL" || type == "RIGORE_SEGNATO"

    /** Quanto pesa: sotto questa soglia e' rumore di gioco, non un momento da mostrare. */
    val isNotable: Boolean get() = danger >= 40 || isGoal
}

/** Come ha giocato un singolo giocatore. */
data class MatchRating(
    val playerId: Long,
    /** Di che squadra era in campo: serve a dividere le due formazioni. */
    val clubId: Long = 0L,
    val started: Boolean,
    val minutes: Int,
    val goals: Int,
    val assists: Int,
    val yellow: Int,
    val red: Int,
    val rating: Double,
    /** Il ruolo in cui ha giocato questa partita: serve a disegnarlo sul campo. */
    val position: String? = null,
)

/**
 * Cosa ha fatto in campo, oltre a quello che e' finito in porta.
 *
 * Di un difensore centrale il tabellino diceva soltanto quanti cartellini aveva preso: un
 * grande centrale e un centrale scarso producevano lo stesso identico foglio.
 */
data class DuelliPartita(
    val vinti: Int = 0,
    val persi: Int = 0,
    val dribbling: Int = 0,
    val dribblingTentati: Int = 0,
    val dribblingSubiti: Int = 0,
    val passaggi: Int = 0,
    val passaggiTentati: Int = 0,
) {
    val duelli: Int get() = vinti + persi

    /** Su cento duelli, quanti ne ha vinti. Null se non ne ha giocati abbastanza. */
    val percentualeDuelli: Int?
        get() = if (duelli < 4) null else (vinti * 100) / duelli

    /** Precisione nei passaggi. Null sotto i cinque tentativi: non direbbe niente. */
    val precisione: Int?
        get() = if (passaggiTentati < 5) null else (passaggi * 100) / passaggiTentati

    val niente: Boolean get() = duelli == 0 && dribblingTentati == 0 && passaggiTentati == 0
}

/** Una partita giocata, pronta da rivedere. */
data class PlayedMatch(
    val fixtureId: Long,
    val homeClubId: Long,
    val awayClubId: Long,
    val matchDay: Int,
    val kickoff: Instant?,
    /** Quando riparte dopo l'intervallo. Null se il primo tempo non e' ancora finito. */
    val riprendeAlle: Instant? = null,
    /**
     * Falso quando si sta guardando **solo il primo tempo**.
     *
     * E' la differenza fra una partita e un parziale, e va detta: con i soli quarantacinque
     * minuti il punteggio non e' il risultato, le pagelle non ci sono, e il secondo tempo
     * arrivera' quando il server lo avra' giocato.
     */
    val completa: Boolean = true,
    val homeGoals: Int,
    val awayGoals: Int,
    val homePossession: Double,
    /** Tiri totali, come li conta il motore: comprendono angoli e punizioni. */
    val homeShots: Int = 0,
    val awayShots: Int = 0,
    /** I moduli con cui si e giocato, per disegnare il campo del tabellino. */
    val homeFormation: String? = null,
    val awayFormation: String? = null,
    val moments: List<MatchMoment>,
    val ratings: List<MatchRating>,
    /**
     * Cosa ha fatto ciascuno, oltre a quello che e' finito in porta.
     *
     * Arriva da `match_results.player_stats`, che e' `jsonb` **dal primo schema**: sei
     * chiavi in piu' dentro un JSON non sono sei colonne in piu'. Non passa da
     * `appearances`, che si legge con una `select` a lista esplicita — ed e' precisamente
     * la lettura condivisa che PostgREST fa esplodere per intero se una colonna non c'e'.
     *
     * Vuota per le partite giocate col motore vecchio: quelle i duelli non li avevano.
     */
    val duelli: Map<Long, DuelliPartita> = emptyMap(),
) {
    val scoreline: String get() = "$homeGoals - $awayGoals"

    /**
     * Quante volte un tipo di evento e' capitato per parte.
     *
     * ## Perche' si contano gli eventi invece di salvare i totali
     *
     * Perche' i totali sarebbero un secondo posto in cui la stessa verita' puo' sbagliarsi.
     * La timeline **e'** la partita: se dice quattro angoli, quattro angoli sono. Salvare
     * anche un contatore vorrebbe dire poterlo trovare a tre, e non sapere a quale dei due
     * credere.
     *
     * I tiri fanno eccezione e arrivano dal motore: comprendono conclusioni che non
     * generano un evento a se'.
     */
    fun conta(vararg tipi: String): Pair<Int, Int> {
        val presi = moments.filter { it.type in tipi }
        return presi.count { it.homeSide } to presi.count { !it.homeSide }
    }

    /** Chi ha giocato dall'inizio, e chi e' entrato dopo. */
    val titolari: List<MatchRating> get() = ratings.filter { it.started }
    val subentrati: List<MatchRating> get() = ratings.filter { !it.started && it.minutes > 0 }
}

/**
 * La partita gia' giocata, letta una volta sola.
 *
 * ## Perche' la timeline sta tutta sul database
 *
 * Il tick salva i novanta minuti **interi** al momento della simulazione. Il telefono la
 * scarica una volta e la riproduce con il proprio orologio: nessun polling, costo zero
 * durante la partita, e chi apre l'app al sessantesimo salta direttamente al sessantesimo.
 *
 * E' la decisione che rende accettabile far girare un mondo su una griglia di cinque
 * minuti e un backend gratuito.
 *
 * ## Perche' e' arrivata cosi' tardi
 *
 * Perche' la timeline si scriveva da mesi e **nessuno la leggeva**. Il risultato di una
 * partita era `2-1`, e tutto quello che ci sta intorno — moduli, ordini condizionali,
 * stamina, giocatori fuori ruolo — non era osservabile da nessuna parte. In un manageriale
 * la partita e' il momento in cui il resto acquista senso: senza, schierare la formazione
 * e' compilare un modulo e sperare.
 */
/**
 * Una partita ferma all'intervallo.
 *
 * Dura pochi minuti ed e' l'unico momento in cui una partita asincrona diventa una
 * partita che si guarda: chi c'e' cambia qualcosa, chi non c'e' non viene tagliato fuori
 * perche' i suoi ordini condizionali girano lo stesso.
 */
data class Intervallo(
    val fixtureId: Long,
    val home: Long,
    val away: Long,
    val riprendeAlle: Instant,
) {
    fun aperto(now: Instant = Instant.now()): Boolean = now.isBefore(riprendeAlle)

    fun tempoRimasto(now: Instant = Instant.now()): String {
        val secondi = java.time.Duration.between(now, riprendeAlle).seconds
        if (secondi <= 0) return "si riprende"
        val m = secondi / 60
        return if (m > 0) "${m}m ${secondi % 60}s" else "${secondi}s"
    }
}

object MatchRepository {

    /**
     * Le partite ferme all'intervallo.
     *
     * ## Perche' e' una lettura tutta sua
     *
     * `resume_at` arriva dalla migrazione `0029`, ed e' la trappola che questo progetto ha
     * gia' pagato due volte: una colonna nuova dentro una SELECT condivisa fa rifiutare
     * **l'intera query** a PostgREST su un database non ancora migrato. Chiesta qui, al
     * peggio torna vuota e l'app si comporta come prima che l'intervallo esistesse.
     */
    suspend fun intervalliAperti(leagueId: Long): List<Intervallo> {
        val path = "/rest/v1/fixtures?select=id,home_club_id,away_club_id,resume_at" +
            "&league_id=eq.$leagueId&played=is.false&resume_at=not.is.null"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().mapNotNull { riga ->
                val quando = riga["resume_at"].strOrNull()
                    ?.let { runCatching { Instant.parse(if (it.endsWith("Z")) it else it + "Z") }.getOrNull() }
                    ?: return@mapNotNull null
                Intervallo(
                    fixtureId = riga["id"].long(0),
                    home = riga["home_club_id"].long(0),
                    away = riga["away_club_id"].long(0),
                    riprendeAlle = quando,
                )
            }
        }
    }

    /**
     * La partita, finita o **in corso**.
     *
     * ## Le due sorgenti, e perche' sono due
     *
     * `match_results` nasce solo al fischio finale, ed e' giusto: e' la riga che significa
     * «giocata», e scriverla a meta' vorrebbe dire una partita che entra in classifica
     * all'intervallo. Ma dal 2026-08-29 la partita si guarda **mentre si gioca**, e per
     * quarantacinque minuti reali quella riga non esiste ancora.
     *
     * Quindi: se c'e' il risultato si legge quello; se no si legge il primo tempo dentro
     * `fixtures.first_half`, che il server scrive al 45'. Prima, in tutto quel tempo,
     * l'unica risposta possibile era «non e' ancora stata giocata» — su una partita che si
     * stava giocando in quel momento.
     *
     * `first_half` viene chiesto **in questa stessa query** e non a parte: e' una colonna
     * che esiste dalla migrazione `0029`, quindi e' gia' insieme a `resume_at` fra quelle
     * che un database aggiornato ha di sicuro. Se manca, questa lettura fallisce da sola e
     * si vede la partita solo a fine gara, che e' il comportamento di prima.
     */
    suspend fun load(fixtureId: Long): ApiResult<PlayedMatch> {
        val path = "/rest/v1/fixtures?select=id,home_club_id,away_club_id,match_day,kickoff," +
            "resume_at,first_half," +
            "match_results(home_goals,away_goals,timeline,player_stats,home_possession," +
            "home_formation,away_formation)" +
            "&id=eq.$fixtureId&limit=1"

        return SupabaseApi.get(path).then { body ->
            val row = JsonNode.parse(body)[0]
            if (!row.exists) return@then ApiResult.Error("Partita non trovata.")

            val result = row["match_results"].let { if (it.isArray) it[0] else it }
            val primoTempo = row["first_half"]["live"]

            // Il finale vince sul parziale: appena il secondo tempo e' scritto, quello che
            // conta e' la partita intera.
            val dati = when {
                result.exists -> result
                primoTempo.exists -> primoTempo
                else -> return@then ApiResult.Error("Questa partita non è ancora cominciata.")
            }
            val timeline = if (result.exists) dati["timeline"] else dati

            ApiResult.Ok(
                PlayedMatch(
                    fixtureId = row["id"].long(0),
                    homeClubId = row["home_club_id"].long(0),
                    awayClubId = row["away_club_id"].long(0),
                    matchDay = row["match_day"].int(0),
                    kickoff = row["kickoff"].strOrNull()?.let(Istanti::parse),
                    riprendeAlle = row["resume_at"].strOrNull()?.let(Istanti::parse),
                    completa = result.exists,
                    homeGoals = dati["home_goals"].int(dati["homeGoals"].int(0)),
                    awayGoals = dati["away_goals"].int(dati["awayGoals"].int(0)),
                    homePossession = dati["home_possession"].double(dati["homePossession"].double(0.5)),
                    homeShots = timeline["homeShots"].int(0),
                    awayShots = timeline["awayShots"].int(0),
                    homeFormation = dati["home_formation"].strOrNull(),
                    awayFormation = dati["away_formation"].strOrNull(),
                    moments = timeline["events"].asList().map { e ->
                        MatchMoment(
                            minute = e["minute"].int(0),
                            type = e["type"].str(""),
                            homeSide = e["side"].str("CASA") == "CASA",
                            danger = e["danger"].int(0),
                            text = e["text"].str(""),
                            homeGoals = e["homeGoals"].int(0),
                            awayGoals = e["awayGoals"].int(0),
                            playerId = e["player"].long(0).takeIf { it > 0 },
                            // La zona in cui sta la palla: e' cio' che permette di
                            // disegnare l'azione invece di elencarla. Il server la scrive
                            // dal primo giorno e nessuno la leggeva.
                            zone = e["zone"].strOrNull(),
                        )
                    },
                    ratings = emptyList(),
                    duelli = leggiDuelli(result["player_stats"]),
                ),
            )
        }
    }

    /**
     * I duelli, dal JSON che il server scrive gia'.
     *
     * Le chiavi sono gli identificativi dei giocatori. Le partite giocate col motore
     * vecchio non hanno queste voci e restituiscono zero — che e' la verita': i duelli
     * quel giorno non si giocavano.
     */
    private fun leggiDuelli(node: JsonNode): Map<Long, DuelliPartita> {
        if (!node.exists) return emptyMap()
        val fuori = mutableMapOf<Long, DuelliPartita>()
        for (chiave in node.keys()) {
            val id = chiave.toLongOrNull() ?: continue
            val s = node[chiave]
            val d = DuelliPartita(
                vinti = s["duelliVinti"].int(0),
                persi = s["duelliPersi"].int(0),
                dribbling = s["dribbling"].int(0),
                dribblingTentati = s["dribblingTentati"].int(0),
                dribblingSubiti = s["dribblingSubiti"].int(0),
                passaggi = s["passaggi"].int(0),
                passaggiTentati = s["passaggiTentati"].int(0),
            )
            if (!d.niente) fuori[id] = d
        }
        return fuori
    }

    /**
     * Le pagelle.
     *
     * Lettura separata perche' arrivano da `appearances`, che e' una tabella diversa con
     * una policy diversa, e perche' una partita si puo' rivedere anche senza: se le
     * presenze mancano — una partita giocata prima che la tabella esistesse — il replay
     * funziona lo stesso e le pagelle semplicemente non ci sono.
     */
    suspend fun ratings(fixtureId: Long): List<MatchRating> {
        val path = "/rest/v1/appearances?select=player_id,started,minutes,goals,assists," +
            "yellow,red,rating,club_id,position&fixture_id=eq.$fixtureId&order=rating.desc"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> emptyList()
            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().map { row ->
                MatchRating(
                    playerId = row["player_id"].long(0),
                    clubId = row["club_id"].long(0),
                    started = row["started"].bool(false),
                    minutes = row["minutes"].int(0),
                    goals = row["goals"].int(0),
                    assists = row["assists"].int(0),
                    yellow = row["yellow"].int(0),
                    red = row["red"].int(0),
                    rating = row["rating"].double(0.0),
                    position = row["position"].strOrNull(),
                )
            }
        }
    }
}

/** Quanto ha fatto un giocatore, da inizio stagione. */
data class Carriera(
    val presenze: Int,
    val daTitolare: Int,
    val minuti: Int,
    val gol: Int,
    val assist: Int,
    val gialli: Int,
    val rossi: Int,
    val mediaVoto: Double,
) {
    val vuota: Boolean get() = presenze == 0

    companion object {
        val NESSUNA = Carriera(0, 0, 0, 0, 0, 0, 0, 0.0)

        fun da(righe: List<MatchRating>): Carriera {
            // Solo chi e' sceso in campo: le presenze contengono una riga anche per chi e'
            // rimasto fuori — serve a sapere da quanto non gioca — e contarla come partita
            // giocata falserebbe media voto e minuti.
            val giocate = righe.filter { it.minutes > 0 }
            if (giocate.isEmpty()) return NESSUNA

            return Carriera(
                presenze = giocate.size,
                daTitolare = giocate.count { it.started },
                minuti = giocate.sumOf { it.minutes },
                gol = giocate.sumOf { it.goals },
                assist = giocate.sumOf { it.assists },
                gialli = giocate.sumOf { it.yellow },
                rossi = giocate.sumOf { it.red },
                mediaVoto = giocate.map { it.rating }.average(),
            )
        }
    }
}

/**
 * La storia di un giocatore, da `appearances`.
 *
 * ## Perche' non c'era
 *
 * Perche' fino a ieri non esisteva la tabella: la formazione salvata era una riga per club,
 * sovrascritta, e di chi avesse giocato la settimana scorsa non restava traccia. Adesso
 * resta, e la scheda puo' dire "quattordici presenze, media 6,4" invece di soli attributi.
 */
object CareerRepository {

    suspend fun of(playerId: Long): Carriera {
        val path = "/rest/v1/appearances?select=started,minutes,goals,assists,yellow,red," +
            "rating&player_id=eq.$playerId&limit=200"

        return when (val esito = SupabaseApi.get(path)) {
            is ApiResult.Error -> Carriera.NESSUNA
            is ApiResult.Ok -> Carriera.da(
                JsonNode.parse(esito.value).asList().map { row ->
                    MatchRating(
                        playerId = playerId,
                        started = row["started"].bool(false),
                        minutes = row["minutes"].int(0),
                        goals = row["goals"].int(0),
                        assists = row["assists"].int(0),
                        yellow = row["yellow"].int(0),
                        red = row["red"].int(0),
                        rating = row["rating"].double(0.0),
                    )
                },
            )
        }
    }
}
