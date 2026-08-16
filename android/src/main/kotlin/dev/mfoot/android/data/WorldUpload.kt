package dev.mfoot.android.data

import dev.mfoot.core.ai.AiPersonality
import dev.mfoot.core.ai.AiPersonalityGenerator
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Staff
import dev.mfoot.core.rng.DeterministicRandom
import dev.mfoot.core.world.GeneratedWorld
import org.json.JSONArray
import org.json.JSONObject

/**
 * Traduce il mondo generato in JSON per la funzione `create_league`.
 *
 * Le chiavi sono corte di proposito (`fn`, `ln`, `pos`) invece che descrittive: con
 * milletrecento giocatori, nomi di campo lunghi gonfiano il caricamento di parecchie
 * decine di kilobyte per niente. Il significato sta nella funzione SQL che li rilegge,
 * non nel filo.
 */
object WorldUpload {

    fun buildPayload(
        world: GeneratedWorld,
        config: LeagueConfig,
        leagueName: String,
        accessCode: String,
        nickname: String,
    ): JSONObject = JSONObject()
        .put("p_name", leagueName)
        .put("p_access_code", accessCode)
        .put("p_config", configJson(config))
        .put("p_seed", config.setup.worldSeed)
        .put("p_nickname", nickname)
        .put("p_players", world.players.toJsonArray(::playerJson))
        .put("p_staff", world.staff.toJsonArray(::staffJson))
        .put("p_ai_clubs", aiClubsJson(config))

    /**
     * La configurazione della lega, in JSON.
     *
     * Non tutta: solo le sezioni che qualcuno legge davvero dall'altra parte — la
     * funzione SQL per i crediti iniziali e le regole d'asta, il tick per la cadenza
     * delle entrate. Il resto vive gia' nei preset e si ricava dal nome del preset.
     * Serializzare novanta campi che nessuno rilegge sarebbe solo peso sul filo.
     */
    private fun configJson(config: LeagueConfig): JSONObject = JSONObject().apply {
        put(
            "setup",
            JSONObject()
                .put("totalClubs", config.setup.totalClubs)
                .put("aiClubs", config.setup.aiClubs)
                .put("minSquadSize", config.setup.minSquadSize)
                .put("maxSquadSize", config.setup.maxSquadSize)
                .put("worldSeed", config.setup.worldSeed),
        )
        put(
            "economy",
            JSONObject()
                .put("startingCredits", config.economy.startingCredits)
                .put("recurringIncome", config.economy.recurringIncome)
                .put("incomeCadence", config.economy.incomeCadence.name)
                .put("renewalCostFraction", config.economy.renewalCostFraction)
                .put("wagesEnabled", config.economy.wagesEnabled),
        )
        put(
            "market",
            JSONObject()
                .put("auctionDurationMinutes", config.market.auctionDurationMinutes)
                .put("minimumRaise", config.market.minimumRaise)
                .put("antiSnipeEnabled", config.market.antiSnipeEnabled)
                .put("antiSnipeSeconds", config.market.antiSnipeSeconds)
                .put("defaultContractMatchDays", config.market.defaultContractMatchDays)
                .put("maxParallelAuctionsPerClub", config.market.maxParallelAuctionsPerClub),
        )
        put(
            "rules",
            JSONObject()
                .put("customMustStart", config.rules.customMustStart)
                .put("growthMultiplier", config.rules.growthMultiplier)
                .put("youthTeamEnabled", config.rules.youthTeamEnabled)
                .put("youthMaxAge", config.rules.youthMaxAge),
        )
    }

    private fun playerJson(player: Player): JSONObject = JSONObject().apply {
        put("fn", player.firstName)
        put("ln", player.lastName)
        put("nat", player.nationality)
        put("age", player.age)
        put("pos", player.primaryPosition.name)
        put("sec", JSONArray().also { arr -> player.secondaryPositions.forEach { arr.put(it.name) } })
        put("attr", JSONObject().also { obj -> Attr.entries.forEach { obj.put(it.name, player.attributes[it]) } })
        put("wf", player.weakFoot)
        put("sk", player.skillStars)
        // I potenziali veri partono verso il database ma non tornano mai indietro: il
        // client legge la vista players_public, che li omette.
        put("pmin", player.potentialMin)
        put("pmax", player.potentialMax)
        put("traits", JSONArray().also { arr -> player.traits.forEach { arr.put(it.name) } })
        put("ovr", player.overall)
    }

    private fun staffJson(staff: Staff): JSONObject = JSONObject().apply {
        put("fn", staff.firstName)
        put("ln", staff.lastName)
        put("nat", staff.nationality)
        put("role", staff.role.name)
        put("stars", staff.stars)
    }

    /**
     * I club dell'AI, con il carattere gia' generato.
     *
     * Nascono qui e non nel database perche' la personalita' viene dal seed della lega:
     * generandola sul telefono resta riproducibile, e il tick puo' verificarla.
     */
    private fun aiClubsJson(config: LeagueConfig): JSONArray {
        val rng = DeterministicRandom(config.setup.worldSeed * 7L + 13L)
        return JSONArray().also { array ->
            repeat(config.setup.aiClubs) { index ->
                val clubId = ClubId(index + 1L)
                val personality = AiPersonalityGenerator.generate(
                    clubId, config.setup.worldSeed, config.ai,
                )
                val name = clubName(rng)
                array.put(
                    JSONObject()
                        .put("name", name)
                        .put("short", shortNameOf(name))
                        .put("personality", personalityJson(personality)),
                )
            }
        }
    }

    private fun personalityJson(p: AiPersonality): JSONObject = JSONObject().apply {
        put("marketAggression", p.marketAggression)
        put("youthPreference", p.youthPreference)
        put("budgetDiscipline", p.budgetDiscipline)
        put("patience", p.patience)
        put("activeFromHour", p.activeFromHour)
        put("activeToHour", p.activeToHour)
        put("checksPerDay", p.checksPerDay)
        put("obsessions", JSONArray().also { arr -> p.obsessions.forEach { arr.put(it.name) } })
    }

    // Nomi di club inventati: stessa logica dei giocatori, nessuna licenza da rispettare.
    private val prefixes = listOf(
        "Verdemar", "Nordkap", "Astoria", "Ferrovia", "Montesole", "Calanque",
        "Ostmark", "Ribeira", "Valmarina", "Lindhof", "Peniche", "Aurora",
        "Stellante", "Kirkwall", "Doradal", "Vento", "Selvanova", "Portobello",
    )
    private val suffixes = listOf("FC", "United", "Athletic", "Sporting", "1908", "City", "Real", "AC")

    private fun clubName(rng: DeterministicRandom): String =
        "${rng.pick(prefixes)} ${rng.pick(suffixes)}"

    private fun shortNameOf(name: String): String =
        name.split(" ").joinToString("") { it.take(1) }.uppercase().take(3)
}
