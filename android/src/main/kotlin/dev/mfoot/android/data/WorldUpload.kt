package dev.mfoot.android.data

import dev.mfoot.core.ai.AiPersonality
import dev.mfoot.core.ai.AiPersonalityGenerator
import dev.mfoot.core.config.ConfigJson
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Staff
import dev.mfoot.core.rng.DeterministicRandom
import dev.mfoot.core.world.GeneratedWorld

/**
 * Traduce il mondo generato nel corpo della chiamata a `create_league`.
 *
 * ## Perche' si scrive testo e non oggetti
 *
 * Il primo tentativo costruiva un albero di `JSONObject`: milletrecento giocatori, ognuno
 * con un oggetto annidato per i dodici attributi. Il JSON finale sono ~400 KB, ma
 * l'albero in memoria ne occupava cinquanta volte tanti, e il sistema ha ucciso l'app per
 * memoria esaurita a 162 MB. Scrivendo direttamente il testo si resta sui kilobyte che
 * servono davvero.
 *
 * ## Perche' le chiavi sono corte
 *
 * `fn` invece di `firstName`. Con milletrecento righe, nomi di campo descrittivi
 * gonfiano il caricamento di decine di kilobyte per niente. Il significato sta nella
 * funzione SQL che li rilegge, non sul filo.
 */
object WorldUpload {

    fun buildPayload(
        world: GeneratedWorld,
        config: LeagueConfig,
        leagueName: String,
        accessCode: String,
        nickname: String,
    ): String {
        val w = JsonWriter(estimatedSize(world))

        w.beginObject()
        w.field("p_name", leagueName)
        w.field("p_access_code", accessCode)
        w.field("p_nickname", nickname)
        w.field("p_seed", config.setup.worldSeed)

        // La configurazione la serializza `core`, la stessa libreria che la rilegge sul
        // server: se scrittura e lettura divergessero, le regole scelte dall'admin
        // tornerebbero ai valori di serie senza che nessuno se ne accorga.
        w.objectField("p_config")
        ConfigJson.writeInto(w, config)
        w.endObject()

        writePlayers(w, world.players)
        writeStaff(w, world.staff)
        writeAiClubs(w, config)

        w.endObject()
        return w.toString()
    }

    /** ~360 byte a giocatore piu' un margine: evita che lo StringBuilder si ridimensioni. */
    private fun estimatedSize(world: GeneratedWorld): Int =
        world.players.size * 360 + world.staff.size * 120 + 16384

    private fun writePlayers(w: JsonWriter, players: List<Player>) {
        w.arrayField("p_players")
        players.forEach { p ->
            w.beginObject()
            w.field("fn", p.firstName)
            w.field("ln", p.lastName)
            w.field("nat", p.nationality)
            w.field("age", p.age)
            w.field("pos", p.primaryPosition.name)

            w.arrayField("sec")
            p.secondaryPositions.forEach { w.value(it.name) }
            w.endArray()

            w.objectField("attr")
            Attr.entries.forEach { w.field(it.name, p.attributes[it]) }
            w.endObject()

            w.field("wf", p.weakFoot)
            w.field("sk", p.skillStars)
            // I potenziali veri partono verso il database ma non tornano mai indietro:
            // il client legge la vista players_public, che li omette.
            w.field("pmin", p.potentialMin)
            w.field("pmax", p.potentialMax)

            w.arrayField("traits")
            p.traits.forEach { w.value(it.name) }
            w.endArray()

            w.field("ovr", p.overall)
            w.endObject()
        }
        w.endArray()
    }

    private fun writeStaff(w: JsonWriter, staff: List<Staff>) {
        w.arrayField("p_staff")
        staff.forEach { s ->
            w.beginObject()
            w.field("fn", s.firstName)
            w.field("ln", s.lastName)
            w.field("nat", s.nationality)
            w.field("role", s.role.name)
            w.field("stars", s.stars)
            w.endObject()
        }
        w.endArray()
    }

    /**
     * I club dell'AI, con il carattere gia' generato.
     *
     * Nasce qui e non nel database perche' la personalita' viene dal seed della lega:
     * generandola sul telefono resta riproducibile, e il tick puo' verificarla.
     */
    private fun writeAiClubs(w: JsonWriter, config: LeagueConfig) {
        val rng = DeterministicRandom(config.setup.worldSeed * 7L + 13L)

        w.arrayField("p_ai_clubs")
        repeat(config.setup.aiClubs) { index ->
            val personality = AiPersonalityGenerator.generate(
                ClubId(index + 1L), config.setup.worldSeed, config.ai,
            )
            val name = clubName(rng)

            w.beginObject()
            w.field("name", name)
            w.field("short", shortNameOf(name))
            w.objectField("personality")
            writePersonality(w, personality)
            w.endObject()
            w.endObject()
        }
        w.endArray()
    }

    private fun writePersonality(w: JsonWriter, p: AiPersonality) {
        w.field("marketAggression", p.marketAggression)
        w.field("youthPreference", p.youthPreference)
        w.field("budgetDiscipline", p.budgetDiscipline)
        w.field("patience", p.patience)
        w.field("activeFromHour", p.activeFromHour)
        w.field("activeToHour", p.activeToHour)
        w.field("checksPerDay", p.checksPerDay)
        w.arrayField("obsessions")
        p.obsessions.forEach { w.value(it.name) }
        w.endArray()
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
