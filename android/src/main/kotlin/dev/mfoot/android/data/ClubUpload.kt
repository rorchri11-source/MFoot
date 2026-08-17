package dev.mfoot.android.data

import dev.mfoot.android.ui.kit.Kit
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.world.CustomPlayerBuilder

/**
 * Il corpo della chiamata a `create_club`.
 *
 * Si mandano le **scelte**, non i risultati: il ruolo, i punti spesi su ogni attributo,
 * le stelle. Attributi finali e overall li ricalcola il database dalla configurazione
 * della lega. E' la stessa ragione per cui un modulo di iscrizione non chiede all'utente
 * di scrivere da solo quanto deve pagare.
 */
object ClubUpload {

    fun payload(
        leagueId: Long,
        clubName: String,
        clubShort: String,
        kit: Kit,
        draft: CustomPlayerBuilder.Draft,
    ): String {
        val w = JsonWriter(1024)
        w.beginObject()
        w.field("p_league_id", leagueId)
        w.field("p_name", clubName.trim())
        w.field("p_short", clubShort.trim())

        // Il motivo viaggia col nome dell'enum e non con un numero: se un giorno si
        // aggiunge un motivo in mezzo alla lista, le maglie salvate non cambiano da sole.
        w.objectField("p_kit")
        w.field("pattern", kit.pattern.name)
        w.field("primary", hex(kit.primary))
        w.field("secondary", hex(kit.secondary))
        w.field("detail", hex(kit.detail))
        kit.number?.let { w.field("number", it) }
        w.endObject()

        w.objectField("p_player")
        w.field("fn", draft.firstName.trim())
        w.field("ln", draft.lastName.trim())
        w.field("nat", draft.nationality.trim())
        w.field("age", draft.age)
        w.field("pos", draft.position.name)

        w.arrayField("sec")
        draft.secondaryPositions.forEach { w.value(it.name) }
        w.endArray()

        w.objectField("inc")
        draft.increments.forEach { (attr, points) -> if (points > 0) w.field(attr.name, points) }
        w.endObject()

        w.field("wf", draft.weakFoot)
        w.field("sk", draft.skillStars)
        w.endObject()

        w.endObject()
        return w.toString()
    }

    /** I colori viaggiano come testo: un intero con l'alfa non significa niente in SQL. */
    private fun hex(color: Long): String =
        "#%06X".format(color and 0xFFFFFF)
}
