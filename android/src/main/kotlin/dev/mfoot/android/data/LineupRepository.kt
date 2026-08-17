package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.TacticalPressing
import dev.mfoot.core.match.TacticalStance
import dev.mfoot.core.match.TacticalTempo
import dev.mfoot.core.match.TacticalWidth
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.model.Position

/**
 * La formazione salvata, come sta sul database.
 *
 * Solo numeri e nomi: i giocatori li ritrova chi chiama, incrociando con la rosa. Tenere
 * qui gli oggetti [dev.mfoot.core.model.Player] vorrebbe dire una seconda copia della rosa
 * che invecchia per conto suo, e un titolare venduto continuerebbe a comparire in campo.
 */
data class SavedLineup(
    val formation: Formation,
    /** Le caselle nell'ordine del modulo: id del giocatore, o null se vuota. */
    val eleven: List<Long?>,
    val bench: List<Long>,
    val tactics: Tactics,
    val captainId: Long?,
    val penaltyTakerId: Long?,
)

/**
 * Legge e scrive la propria formazione.
 *
 * ## L'unica scrittura diretta del gioco
 *
 * Tutto il resto passa da funzioni SQL con `security definer`, perche' tocca cose che il
 * proprietario non deve poter decidere: crediti, contratti, risultati. La formazione e'
 * l'eccezione legittima — appartiene a chi ha il club — e le Row Level Security la
 * proteggono gia': `write_own_lineup` lascia scrivere solo la riga del proprio club.
 *
 * L'autorita' resta comunque al tick: se qui finisse un giocatore di un'altra squadra, il
 * server lo scarterebbe leggendo la rosa vera. Scrivere la riga non vuol dire schierare
 * chiunque.
 */
object LineupRepository {

    suspend fun read(clubId: Long): ApiResult<SavedLineup?> {
        val path = "/rest/v1/lineups?select=formation,slots,bench,tactics,captain_id," +
            "penalty_taker_id&club_id=eq.$clubId"

        return SupabaseApi.get(path).then { body ->
            val row = JsonNode.parse(body).asList().firstOrNull()
                ?: return@then ApiResult.Ok(null)

            val formation = row["formation"].strOrNull()
                ?.let { name -> Formation.entries.firstOrNull { it.name == name } }
                ?: Formation.F_4_3_3

            // Le caselle arrivano nell'ordine in cui sono state salvate, ognuna con il suo
            // ruolo. Si rimettono al posto giusto del modulo invece di fidarsi
            // dell'ordine: un salvataggio fatto con un modulo diverso da quello attuale
            // deve comunque ritrovare i suoi uomini.
            val eleven = arrayOfNulls<Long>(formation.positions.size)
            row["slots"].asList().forEach { slot ->
                val id = slot["player_id"].long(0).takeIf { it != 0L } ?: return@forEach
                val position = slot["position"].strOrNull()
                    ?.let { name -> Position.entries.firstOrNull { it.name == name } }

                val index = position
                    ?.let { p ->
                        eleven.indices.firstOrNull {
                            eleven[it] == null && formation.positions[it] == p
                        }
                    }
                    ?: eleven.indices.firstOrNull { eleven[it] == null }
                    ?: return@forEach

                eleven[index] = id
            }

            ApiResult.Ok(
                SavedLineup(
                    formation = formation,
                    eleven = eleven.toList(),
                    bench = row["bench"].asList().map { it.long(0) }.filter { it != 0L },
                    tactics = row["tactics"].let { t ->
                        Tactics(
                            stance = t["stance"].enum(TacticalStance.EQUILIBRATO),
                            width = t["width"].enum(TacticalWidth.NORMALE),
                            tempo = t["tempo"].enum(TacticalTempo.NORMALE),
                            pressing = t["pressing"].enum(TacticalPressing.MEDIO),
                        )
                    },
                    captainId = row["captain_id"].long(0).takeIf { it != 0L },
                    penaltyTakerId = row["penalty_taker_id"].long(0).takeIf { it != 0L },
                ),
            )
        }
    }

    /**
     * Salva la formazione.
     *
     * Le caselle vuote non si scrivono. Un `player_id` nullo nel JSON costringerebbe il
     * server a distinguere fra "casella vuota" e "chiave sbagliata", e il tick riempie
     * comunque i buchi da solo: mandare meno di undici e' un'informazione completa, non
     * una mancanza.
     */
    suspend fun save(leagueId: Long, clubId: Long, lineup: SavedLineup): ApiResult<Unit> {
        val w = JsonWriter(2 * 1024)
        w.beginArray()
        w.beginObject()
        w.field("club_id", clubId)
        w.field("league_id", leagueId)
        w.field("formation", lineup.formation.name)

        w.arrayField("slots")
        lineup.eleven.forEachIndexed { index, id ->
            if (id == null) return@forEachIndexed
            w.beginObject()
            w.field("player_id", id)
            w.field("position", lineup.formation.positions[index].name)
            w.endObject()
        }
        w.endArray()

        w.arrayField("bench")
        lineup.bench.forEach { w.value(it) }
        w.endArray()

        w.objectField("tactics")
        w.field("stance", lineup.tactics.stance.name)
        w.field("width", lineup.tactics.width.name)
        w.field("tempo", lineup.tactics.tempo.name)
        w.field("pressing", lineup.tactics.pressing.name)
        w.endObject()

        // Scritti come interi, non come testo: la colonna e' `bigint` e un "17" fra
        // virgolette e' un cast che a volte passa e a volte no.
        w.nullableId("captain_id", lineup.captainId)
        w.nullableId("penalty_taker_id", lineup.penaltyTakerId)
        w.endObject()
        w.endArray()

        return SupabaseApi.upsert("lineups", w.toString())
    }

    /** Un identificativo che puo' mancare: intero se c'e', `null` nudo se non c'e'. */
    private fun JsonWriter.nullableId(name: String, id: Long?) {
        if (id == null) field(name, null as String?) else field(name, id)
    }
}
