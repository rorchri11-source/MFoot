package dev.mfoot.android.data

import android.util.JsonReader
import android.util.JsonToken
import dev.mfoot.android.ui.kit.Crest
import dev.mfoot.android.ui.kit.Kit
import dev.mfoot.core.config.ConfigJson
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.Trait

/** La lega come la vede chi ci sta dentro. */
data class LeagueInfo(
    val id: Long,
    val name: String,
    val status: String,
    val currentMatchDay: Int,
    val config: LeagueConfig,
    /**
     * Sono io l'amministratore?
     *
     * Decide chi vede la creazione delle competizioni. Non e' una difesa — quella la fa
     * il database, che rifiuta la chiamata a chi non e' admin — ma nascondere un pulsante
     * che darebbe sempre errore e' l'unico modo di non far sembrare l'app rotta.
     */
    val isAdmin: Boolean = false,
)

/** Un club della lega. Il proprio si riconosce da [isMine]. */
data class ClubInfo(
    val id: Long,
    val name: String,
    val shortName: String,
    val isAi: Boolean,
    val isMine: Boolean,
    /** L'identificativo del proprietario: serve a legare un club alla persona. */
    val ownerUserId: String? = null,
    val ownerName: String?,
    val credits: Int,
    val committedCredits: Int,
    val customPlayerId: Long?,
    /**
     * La maglia scelta dal proprietario.
     *
     * Sta dentro il club e non in una lettura a parte perche' e' un suo attributo come il
     * nome: ovunque si mostri una squadra si vuole mostrare la sua maglia, e una lettura
     * separata darebbe un elenco che compare grigio e si colora mezzo secondo dopo.
     */
    val kit: Kit = Kit.DEFAULT,
    /**
     * Lo stemma.
     *
     * Viaggia dentro lo stesso JSON della maglia, e non e' pigrizia: `create_club` salva
     * quel blocco cosi' com'e', quindi aggiungere lo stemma li' dentro non ha richiesto
     * nessuna modifica al database ne' una migrazione da far incollare a qualcuno.
     */
    val crest: Crest = Crest.DEFAULT,
    /** In quale divisione gioca. 1 e' la massima; con una sola divisione vale sempre 1. */
    val divisionLevel: Int = 1,
    /**
     * Il club di cui questa e la Primavera, se lo e.
     *
     * Null significa prima squadra. E la colonna che rende la seconda squadra un club vero
     * invece di un magazzino dentro una rosa sola.
     */
    val parentClubId: Long? = null,
) {
    /** Quello che si puo' davvero spendere: i crediti impegnati nelle aste sono gia' via. */
    val available: Int get() = credits - committedCredits
}

/**
 * Il contratto di un giocatore, per quel poco che serve all'interfaccia.
 *
 * Non e' [dev.mfoot.core.model.Contract]: quello e' il modello di gioco, con `signedOn`,
 * `pricePaid` e la clausola, e vive in `core` dove servono per le regole. Qui bastano le
 * due cose che cambiano una decisione mentre si guarda una scheda — **quando scade** e
 * **quanto costa a giornata** — e portarsi dietro il resto significherebbe leggere colonne
 * che nessuna schermata mostra.
 */
data class ContractInfo(
    val clubId: Long,
    val squad: String,
    /** Giornata in cui scade. Si confronta con `league.currentMatchDay`. */
    val expiresOn: Int,
    val wagePerMatchDay: Int,
) {
    val isYouth: Boolean get() = squad == "primavera"

    fun matchDaysLeft(today: Int): Int = (expiresOn - today).coerceAtLeast(0)
}

/** Tutto quello che serve per disegnare l'app, letto in un colpo solo. */
data class LeagueSnapshot(
    val league: LeagueInfo,
    val clubs: List<ClubInfo>,
    val players: List<Player>,
    /** Chi possiede chi. I giocatori che non compaiono sono svincolati. */
    val clubOfPlayer: Map<Long, Long>,
    /** Chi sta in Primavera: si allena, non gioca, e non conta per la prima squadra. */
    val youth: Set<Long> = emptySet(),
    /**
     * Scadenza e stipendio, per giocatore.
     *
     * In coda e con un default perche' e' arrivata dopo: uno snapshot costruito senza
     * questa mappa resta valido e mostra semplicemente una scheda senza contratto,
     * invece di non compilare.
     */
    val contracts: Map<Long, ContractInfo> = emptyMap(),
) {
    /**
     * La propria prima squadra.
     *
     * Con la Primavera i club propri sono due, e "il primo che risulta mio" darebbe una
     * risposta diversa a ogni caricamento a seconda di come il database ha ordinato le
     * righe. La prima squadra e quella senza padre.
     */
    val myClub: ClubInfo? get() = clubs.firstOrNull { it.isMine && it.parentClubId == null }

    /** La propria Primavera, se e stata fondata. */
    val myYouthClub: ClubInfo?
        get() = myClub?.let { prima -> clubs.firstOrNull { it.parentClubId == prima.id } }

    fun freeAgents(): List<Player> = players.filter { it.id.value !in clubOfPlayer }

    fun squadOf(clubId: Long): List<Player> =
        players.filter { clubOfPlayer[it.id.value] == clubId }
}

/**
 * Legge la lega dal database.
 *
 * ## Il potenziale non arriva, e non e' una mancanza
 *
 * I giocatori si leggono da `players_public`, una vista che **non contiene**
 * `potential_min` e `potential_max`. I due campi di [Player] vengono riempiti con
 * l'overall attuale: un segnaposto, non un dato. Va usata solo
 * `PotentialEstimator.publicEstimate`, che a conoscenza zero non li guarda affatto — c'e'
 * un test in `core` che lo dimostra.
 *
 * Quando i club inizieranno ad accumulare minuti visti e lavoro degli osservatori, la
 * stima ristretta dovra' arrivare gia' calcolata dal server. E' l'unico modo perche' la
 * conoscenza cresca senza che il segreto possa essere dedotto per differenza.
 */
object LeagueRepository {

    /**
     * Righe per pagina.
     *
     * Mille e' il tetto che PostgREST impone di suo su Supabase: chiederne di piu' non
     * servirebbe a niente, perche' taglierebbe comunque li'.
     */
    private const val PAGE_SIZE = 1000

    /** Le colonne della vista pubblica, nell'ordine in cui non importa che arrivino. */
    private const val PLAYER_COLUMNS =
        "id,first_name,last_name,nationality,age,primary_position,secondary_positions," +
            "attributes,weak_foot,skill_stars,traits,stamina,morale,form,is_custom," +
            "injured_until,overall,minutes_observed"

    /**
     * Legge tutto quello che serve per aprire l'app su una lega.
     *
     * Quattro richieste in fila e non una sola: PostgREST non fa join annidati profondi
     * senza complicare parecchio la query, e quattro chiamate da qualche decina di
     * millisecondi l'una restano ampiamente sotto la soglia in cui l'attesa si nota.
     */
    suspend fun snapshot(leagueId: Long): ApiResult<LeagueSnapshot> =
        readLeague(leagueId).then { info ->
            readClubs(leagueId).then { clubs ->
                readPlayers(leagueId).then { players ->
                    readContracts(leagueId).then { contratti ->
                        ApiResult.Ok(
                            LeagueSnapshot(
                                league = info,
                                clubs = clubs,
                                players = players,
                                clubOfPlayer = contratti.mapValues { it.value.clubId },
                                youth = contratti.filterValues { it.isYouth }.keys,
                                contracts = contratti,
                            ),
                        )
                    }
                }
            }
        }

    private suspend fun readLeague(leagueId: Long): ApiResult<LeagueInfo> {
        val path = "/rest/v1/leagues?select=id,name,status,current_match_day,config" +
            "&id=eq.$leagueId&limit=1"
        val admin = amIAdmin(leagueId)

        return SupabaseApi.get(path).then { body ->
            val row = JsonNode.parse(body)[0]
            if (!row.exists) {
                // Non e' "lega inesistente": e' quasi sempre "non sei un membro", perche'
                // le Row Level Security nascondono le righe altrui invece di rifiutare.
                ApiResult.Error("Lega non visibile: forse non ne fai parte.")
            } else {
                ApiResult.Ok(
                    LeagueInfo(
                        id = row["id"].long(leagueId),
                        name = row["name"].str("Lega"),
                        status = row["status"].str("setup"),
                        currentMatchDay = row["current_match_day"].int(0),
                        config = ConfigJson.read(row["config"]),
                        isAdmin = admin,
                    ),
                )
            }
        }
    }

    private suspend fun amIAdmin(leagueId: Long): Boolean {
        val me = Session.userId ?: return false
        val path = "/rest/v1/league_members?select=is_admin" +
            "&league_id=eq.$leagueId&user_id=eq.$me&limit=1"

        return when (val response = SupabaseApi.get(path)) {
            is ApiResult.Error -> false
            is ApiResult.Ok -> JsonNode.parse(response.value)[0]["is_admin"].bool(false)
        }
    }

    /**
     * Solo i club.
     *
     * Serve dopo ogni offerta: i crediti impegnati cambiano, i giocatori no. Rileggere
     * tutto il mondo per aggiornare un numero costerebbe quattrocento kilobyte.
     */
    suspend fun clubs(leagueId: Long): ApiResult<List<ClubInfo>> = readClubs(leagueId)

    /**
     * Lo stato della lega e basta: due colonne, una riga.
     *
     * E' la lettura piu' economica che esista qui dentro, ed e' quella che il giro
     * automatico fa per prima. La giornata di campionato e' la spia che dice se il server
     * ha giocato: quando cambia, attributi, stamina, infortuni e presenze sono tutti
     * diversi da prima e vale la pena rileggere il mondo. Finche' non cambia, non c'e'
     * niente in quei quattrocento kilobyte che sia cambiato.
     */
    suspend fun stato(leagueId: Long): ApiResult<Pair<String, Int>> {
        val path = "/rest/v1/leagues?select=status,current_match_day&id=eq.$leagueId&limit=1"

        return SupabaseApi.get(path).then { body ->
            val row = JsonNode.parse(body)[0]
            if (!row.exists) {
                ApiResult.Error("Lega non visibile.")
            } else {
                ApiResult.Ok(row["status"].str("setup") to row["current_match_day"].int(0))
            }
        }
    }

    /**
     * Chi possiede chi, da solo.
     *
     * Poche centinaia di righe da tre colonne: e' la lettura che fa comparire un acquisto
     * altrui senza rileggere i giocatori. Un'asta chiusa o uno scambio accettato cambiano
     * **questa** tabella, non gli attributi di nessuno.
     */
    suspend fun contracts(leagueId: Long): ApiResult<Map<Long, ContractInfo>> =
        readContracts(leagueId)

    /**
     * I club **completi**: divisione e club padre compresi.
     *
     * ## Il difetto che questa funzione esiste per non far tornare
     *
     * `division_level` e `parent_club_id` si leggono a parte — sono colonne aggiunte da una
     * migrazione, e infilarle nella SELECT principale rende l'app inservibile su un
     * database che non ce l'ha ancora (vedi [DivisionRepository]). Finche' quel montaggio
     * viveva nel ViewModel, pero', esisteva **un solo posto** che lo faceva: il caricamento
     * completo della lega.
     *
     * Poi bastava un'offerta all'asta. `aggiornaAste` rileggeva solo i club, per aggiornare
     * i crediti impegnati, e rimetteva nello stato una lista in cui `parentClubId` era
     * tornato null per tutti. Da quel momento `myYouthClub` non trovava piu' niente: la
     * Primavera spariva dall'app, l'interruttore fra le due squadre svaniva e ricompariva
     * il pulsante «fonda la Primavera» — che poi il server rifiutava, giustamente, dicendo
     * che ce l'hai gia'. Stessa sorte per le divisioni, tutte riportate al primo livello.
     *
     * Adesso il montaggio sta qui, dove sta la lettura: non esiste piu' un modo di
     * ottenere dei club a meta'.
     */
    private suspend fun readClubs(leagueId: Long): ApiResult<List<ClubInfo>> =
        readClubRows(leagueId).then { clubs ->
            val livelli = DivisionRepository.levels(leagueId)
            val padri = YouthRepository.parents(leagueId)
            ApiResult.Ok(
                clubs.map {
                    it.copy(divisionLevel = livelli[it.id] ?: 1, parentClubId = padri[it.id])
                },
            )
        }

    private suspend fun readClubRows(leagueId: Long): ApiResult<List<ClubInfo>> {
        val path = "/rest/v1/clubs?select=id,name,short_name,is_ai,owner_user_id,owner_name," +
            "credits,committed_credits,custom_player_id,kit&league_id=eq.$leagueId&order=name"
        val me = Session.userId

        return SupabaseApi.get(path).then { body ->
            ApiResult.Ok(
                JsonNode.parse(body).asList().map { row ->
                    val owner = row["owner_user_id"].strOrNull()
                    ClubInfo(
                        id = row["id"].long(0),
                        name = row["name"].str("?"),
                        shortName = row["short_name"].str("?"),
                        isAi = row["is_ai"].bool(false),
                        isMine = owner != null && owner == me,
                        ownerUserId = owner,
                        ownerName = row["owner_name"].strOrNull(),
                        credits = row["credits"].int(0),
                        committedCredits = row["committed_credits"].int(0),
                        customPlayerId = row["custom_player_id"].long(0).takeIf { it > 0 },
                        kit = readKit(row["kit"], row["id"].long(0)),
                        crest = readCrest(row["kit"]["crest"], row["id"].long(0)),
                    )
                },
            )
        }
    }

    /**
     * La maglia salvata, con il ripiego a quella predefinita.
     *
     * Un colore illeggibile non fa fallire la lettura della lega: si mostra la maglia
     * bianca. Il contrario — un club che sparisce dall'elenco perche' qualcuno ha salvato
     * un motivo che questa versione dell'app non conosce — sarebbe molto peggio di una
     * maglia sbagliata.
     */
    private fun readKit(node: JsonNode, clubId: Long): Kit {
        // Nessun colore salvato: e' un club nato dentro `create_league`, cioe' una squadra
        // gestita dal computer. Gliene si da' una ricavata dall'id invece della bianca
        // predefinita, altrimenti otto avversari sono otto maglie identiche.
        if (node["primary"].strOrNull() == null) return Kit.forClub(clubId)

        val d = Kit.forClub(clubId)
        return Kit(
            pattern = node["pattern"].enum(d.pattern),
            primary = colore(node["primary"].strOrNull(), d.primary),
            secondary = colore(node["secondary"].strOrNull(), d.secondary),
            detail = colore(node["detail"].strOrNull(), d.detail),
            number = node["number"].int(0).takeIf { it > 0 },
        )
    }

    /** Lo stemma salvato, o quello ricavato dall'id per chi non ne ha scelto uno. */
    private fun readCrest(node: JsonNode, clubId: Long): Crest {
        val d = Crest.forClub(clubId)
        if (node["field"].strOrNull() == null) return d
        return Crest(
            shape = node["shape"].enum(d.shape),
            symbol = node["symbol"].enum(d.symbol),
            band = node["band"].enum(d.band),
            field = colore(node["field"].strOrNull(), d.field),
            trim = colore(node["trim"].strOrNull(), d.trim),
            emblem = colore(node["emblem"].strOrNull(), d.emblem),
        )
    }

    /**
     * Da `#RRGGBB` a intero con l'alfa piena.
     *
     * L'alfa va rimessa qui: il database salva sei cifre esadecimali, e un colore senza
     * alfa in Compose e' completamente trasparente. Una maglia invisibile e' esattamente il
     * genere di difetto che si scambia per "la maglia non si e' salvata".
     */
    private fun colore(hex: String?, fallback: Long): Long {
        val pulito = hex?.trim()?.removePrefix("#") ?: return fallback
        val valore = pulito.toLongOrNull(16) ?: return fallback
        return 0xFF000000L or (valore and 0xFFFFFF)
    }

    /**
     * Salva le regole della lega.
     *
     * Passa da una funzione SQL e non da un update diretto: le Row Level Security su
     * `leagues` non permettono la scrittura a nessun client, e a ragione — la
     * configurazione decide budget, premi e infortuni, cioe' l'equilibrio della stagione.
     * Se il telefono potesse scriverla, chiunque sappia comporre una richiesta HTTP si
     * darebbe un budget da un miliardo senza che gli altri lo sapessero mai.
     */
    suspend fun updateConfig(leagueId: Long, config: LeagueConfig): ApiResult<Unit> {
        val w = JsonWriter(8 * 1024)
        w.beginObject()
        w.field("p_league_id", leagueId)
        w.objectField("p_config")
        ConfigJson.writeInto(w, config)
        w.endObject()
        w.endObject()

        return SupabaseApi.rpc("update_league_config", w.toString()).then { ApiResult.Ok(Unit) }
    }

    /**
     * Chi possiede chi, e chi sta in Primavera.
     *
     * `squad` esiste dal primo schema, quindi chiederlo non rischia di rompere niente su
     * nessun database: e la colonna che dice se un giocatore fa parte della prima squadra
     * o del settore giovanile, e senza di essa la Primavera resta invisibile all app.
     */
    /**
     * I contratti, con scadenza e stipendio.
     *
     * ## Perche' queste due colonne si possono chiedere qui dentro
     *
     * La regola del progetto e' che una colonna **aggiunta da una migrazione** non entra
     * mai in una SELECT condivisa: PostgREST rifiuta l'intera query su un database che non
     * ce l'ha ancora, e a quel punto non si legge piu' la lega — non una schermata, tutto.
     * E' gia' costato due volte, con `clubs.division_level` e `clubs.parent_club_id`.
     *
     * `expires_on` e `wage_per_match_day` non sono in quel caso: stanno nella `create table`
     * di [`0001_schema.sql`], cioe' esistono in ogni database che ha la tabella `contracts`.
     * Un database senza di loro sarebbe un database senza contratti.
     */
    /**
     * Chi appartiene a chi, **a pagine**.
     *
     * ## La stessa trappola dei giocatori, su una tabella diversa
     *
     * PostgREST tronca ogni risposta a mille righe e restituisce comunque un 200. Per i
     * giocatori il problema era gia' noto e risolto (vedi [readPlayers]); qui no, e la
     * conseguenza sarebbe peggiore: un contratto che non arriva non e' un giocatore che
     * manca dall'elenco, e' un giocatore che **risulta svincolato**. Comparirebbe fra
     * quelli da prendere, con un pulsante «Compra» che il server rifiuta.
     *
     * Con sedici club a ventotto giocatori piu' le Primavere si superano le mille righe
     * prima della fine del primo mercato, quindi non e' un caso di scuola: e' il giorno
     * in cui la lega si riempie.
     */
    private suspend fun readContracts(leagueId: Long): ApiResult<Map<Long, ContractInfo>> {
        val tutti = HashMap<Long, ContractInfo>(1200)
        var from = 0

        while (true) {
            val path = "/rest/v1/contracts?select=player_id,club_id,squad,expires_on," +
                "wage_per_match_day&league_id=eq.$leagueId&order=player_id.asc"

            val pagina = SupabaseApi.get(
                path = path,
                extraHeaders = mapOf("Range" to "$from-${from + PAGE_SIZE - 1}"),
            ).then { body ->
                ApiResult.Ok(
                    JsonNode.parse(body).asList().associate { riga ->
                        riga["player_id"].long(0) to ContractInfo(
                            clubId = riga["club_id"].long(0),
                            squad = riga["squad"].str("prima"),
                            expiresOn = riga["expires_on"].int(0),
                            wagePerMatchDay = riga["wage_per_match_day"].int(0),
                        )
                    },
                )
            }

            when (pagina) {
                is ApiResult.Error -> return if (from == 0) pagina else ApiResult.Ok(tutti)
                is ApiResult.Ok -> {
                    tutti += pagina.value
                    if (pagina.value.size < PAGE_SIZE) return ApiResult.Ok(tutti)
                    from += PAGE_SIZE
                }
            }
        }
    }

    /**
     * I giocatori, letti in streaming e **a pagine**.
     *
     * PostgREST tronca ogni risposta a mille righe e non lo dice: restituisce un 200 con
     * le prime mille e basta. Un mondo da milletrecento giocatori arrivava quindi
     * mutilato, e i trecento mancanti non erano gli ultimi in classifica ma quelli sotto
     * una certa soglia di overall — cioe' esattamente i giovani su cui si scommette.
     * Nessun errore, nessun avviso: solo giocatori che non esistevano.
     *
     * Si legge finche' una pagina torna piu' corta della richiesta.
     */
    private suspend fun readPlayers(leagueId: Long): ApiResult<List<Player>> {
        val all = ArrayList<Player>(1400)
        var from = 0

        while (true) {
            val path = "/rest/v1/players_public?select=$PLAYER_COLUMNS&league_id=eq.$leagueId" +
                "&order=overall.desc,id.asc"
            val page = SupabaseApi.stream(
                path = path,
                extraHeaders = mapOf("Range" to "$from-${from + PAGE_SIZE - 1}"),
            ) { reader ->
                val players = ArrayList<Player>(PAGE_SIZE)
                var rows = 0
                reader.beginArray()
                while (reader.hasNext()) {
                    rows++
                    readPlayer(reader)?.let(players::add)
                }
                reader.endArray()
                // Si contano le righe **arrivate**, non quelle tenute: una riga scartata
                // perche' incoerente farebbe altrimenti credere che la pagina sia finita,
                // e tutto il resto del mondo sparirebbe senza un errore.
                rows to players
            }

            when (page) {
                is ApiResult.Error -> return page
                is ApiResult.Ok -> {
                    val (rows, players) = page.value
                    all.addAll(players)
                    if (rows < PAGE_SIZE) return ApiResult.Ok(all)
                    from += PAGE_SIZE
                }
            }
        }
    }

    /**
     * Legge una lista puntuale di giocatori per id (es. talenti appena scoperti dallo scouting).
     */
    suspend fun readSpecificPlayers(playerIds: List<Long>): List<Player> {
        if (playerIds.isEmpty()) return emptyList()
        val ids = playerIds.joinToString(",")
        val path = "/rest/v1/players_public?select=$PLAYER_COLUMNS&id=in.($ids)"
        val esito = SupabaseApi.stream(path) { reader ->
            val list = mutableListOf<Player>()
            reader.beginArray()
            while (reader.hasNext()) {
                readPlayer(reader)?.let(list::add)
            }
            reader.endArray()
            list
        }
        return when (esito) {
            is ApiResult.Ok -> esito.value
            is ApiResult.Error -> emptyList()
        }
    }

    /**
     * Una riga della vista pubblica.
     *
     * Restituisce null invece di lanciare se la riga e' incoerente: un giocatore rotto su
     * milletrecento non deve impedire di aprire la lega.
     */
    private fun readPlayer(reader: JsonReader): Player? {
        var id = 0L
        var firstName = ""
        var lastName = ""
        var nationality = ""
        var age = 0
        var position = Position.CC
        var secondary = emptyList<Position>()
        var attributes = Attributes.uniform(50)
        var weakFoot = 3
        var skillStars = 3
        var traits = emptySet<Trait>()
        var stamina = Player.MAX_STAMINA
        var morale = Player.DEFAULT_MORALE
        var form = 0
        var isCustom = false
        var injuredUntil: Int? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextLong()
                "first_name" -> firstName = reader.nextString()
                "last_name" -> lastName = reader.nextString()
                "nationality" -> nationality = reader.nextString()
                "age" -> age = reader.nextInt()
                "primary_position" -> position = reader.enumOr(Position.CC)
                "secondary_positions" -> secondary = reader.stringArray()
                    .mapNotNull { name -> Position.entries.firstOrNull { it.name == name } }
                "attributes" -> attributes = reader.readAttributes()
                "weak_foot" -> weakFoot = reader.nextInt()
                "skill_stars" -> skillStars = reader.nextInt()
                "traits" -> traits = reader.stringArray()
                    .mapNotNull { name -> Trait.entries.firstOrNull { it.name == name } }
                    .toSet()
                "stamina" -> stamina = reader.nextInt()
                "morale" -> morale = reader.nextInt()
                "form" -> form = reader.nextInt()
                "is_custom" -> isCustom = reader.nextBoolean()
                "injured_until" -> injuredUntil = reader.nextIntOrNull()
                // `overall` e `minutes_observed` arrivano ma non servono qui: l'overall lo
                // ricalcola il modello dagli attributi, ed e' un controllo gratuito che i
                // due mondi non siano andati alla deriva.
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (id == 0L || age <= 0) return null

        return runCatching {
            Player(
                id = PlayerId(id),
                firstName = firstName,
                lastName = lastName,
                nationality = nationality,
                age = age,
                primaryPosition = position,
                secondaryPositions = secondary,
                attributes = attributes,
                weakFoot = weakFoot,
                skillStars = skillStars,
                // Segnaposto: la vista pubblica non porta i potenziali veri. Vedi la
                // documentazione di questo oggetto.
                potentialMin = position.overallOf(attributes),
                potentialMax = position.overallOf(attributes),
                traits = traits,
                stamina = stamina,
                morale = morale,
                form = form,
                isCustom = isCustom,
                injuredUntil = injuredUntil?.let(::MatchDay),
            )
        }.getOrNull()
    }

    // ------------------------------------------------------------- aiutanti di streaming

    private fun JsonReader.readAttributes(): Attributes {
        val values = HashMap<Attr, Int>(16)
        beginObject()
        while (hasNext()) {
            val name = nextName()
            val attr = Attr.entries.firstOrNull { it.name == name }
            if (attr == null) skipValue() else values[attr] = nextInt()
        }
        endObject()
        return Attributes.fromMap(values)
    }

    private fun JsonReader.stringArray(): List<String> {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return emptyList()
        }
        val out = ArrayList<String>(4)
        beginArray()
        while (hasNext()) out.add(nextString())
        endArray()
        return out
    }

    private fun JsonReader.nextIntOrNull(): Int? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextInt()
        }

    private inline fun <reified E : Enum<E>> JsonReader.enumOr(fallback: E): E {
        val name = nextString()
        return enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}
