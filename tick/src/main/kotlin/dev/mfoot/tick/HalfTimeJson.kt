package dev.mfoot.tick

import dev.mfoot.core.json.JsonNode
import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.Lineup
import dev.mfoot.core.match.LineupSlot
import dev.mfoot.core.match.MatchDuty
import dev.mfoot.core.match.OrderJson
import dev.mfoot.core.match.SetPieces
import dev.mfoot.core.match.TacticalPressing
import dev.mfoot.core.match.TacticalStance
import dev.mfoot.core.match.TacticalTempo
import dev.mfoot.core.match.TacticalWidth
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.match.TeamSetup
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position

/**
 * Com'erano schierate le due squadre al fischio d'inizio.
 *
 * ## Perche' basta questo, e non serve lo stato dell'intervallo
 *
 * Il motore e' deterministico: stesso seed e stessi ingressi, stesso identico primo tempo.
 * Alla ripresa si ri-simulano i quarantacinque minuti invece di conservarne il risultato,
 * e cosi' non esiste un secondo formato — con timeline, statistiche e schieramenti — da
 * tenere allineato al motore per sempre.
 *
 * Quello che **non** si puo' ricalcolare e' come stavano le squadre prima dei cambi: nella
 * finestra il manager le ha cambiate, ed e' precisamente il punto di avere una finestra.
 *
 * ## Perche' ci sono dentro stamina, morale e forma
 *
 * Perche' sono gli unici valori di un giocatore che si muovono da soli fra un giro di tick
 * e l'altro — il recupero li tocca. Ricaricando i giocatori dal database senza di loro, il
 * primo tempo ri-simulato divergerebbe da quello che gli spettatori hanno gia' visto: stesso
 * seed, ingressi diversi, partita diversa. Un gol che sparisce fra il 45' e il 46'.
 */
object HalfTimeJson {

    fun write(home: TeamSetup, away: TeamSetup): String {
        val w = JsonWriter(4 * 1024)
        w.beginObject()
        w.objectField("home")
        writeTeam(w, home)
        w.endObject()
        w.objectField("away")
        writeTeam(w, away)
        w.endObject()
        w.endObject()
        return w.toString()
    }

    private fun writeTeam(w: JsonWriter, setup: TeamSetup) {
        w.field("club", setup.clubId.value)
        w.field("name", setup.name)
        w.field("coach", setup.coachStars)
        w.field("formation", setup.lineup.formation.name)

        w.arrayField("slots")
        setup.lineup.slots.forEach { slot ->
            w.beginObject()
            w.field("id", slot.player.id.value)
            w.field("pos", slot.position.name)
            w.field("stamina", slot.player.stamina)
            w.field("morale", slot.player.morale)
            w.field("form", slot.player.form)
            w.endObject()
        }
        w.endArray()

        w.arrayField("bench")
        setup.lineup.bench.forEach { player ->
            w.beginObject()
            w.field("id", player.id.value)
            w.field("stamina", player.stamina)
            w.field("morale", player.morale)
            w.field("form", player.form)
            w.endObject()
        }
        w.endArray()

        w.objectField("tactics")
        w.field("stance", setup.tactics.stance.name)
        w.field("width", setup.tactics.width.name)
        w.field("tempo", setup.tactics.tempo.name)
        w.field("pressing", setup.tactics.pressing.name)
        w.endObject()

        w.objectField("duties")
        MatchDuty.entries.forEach { duty ->
            val id = SetPieces.idFor(setup.lineup, duty)
            if (id == null) w.field(duty.name, null as String?) else w.field(duty.name, id.value)
        }
        w.endObject()

        w.rawField("orders", OrderJson.write(setup.orders))
    }

    /**
     * Ricostruisce uno schieramento.
     *
     * @param rosa i giocatori come stanno **adesso** nel database: da qui si prendono gli
     *        attributi, che durante una partita non cambiano. Stamina, morale e forma
     *        vengono invece dal salvataggio, per ritrovare esattamente lo stato di partenza.
     * @return null se manca troppa gente per rimettere in piedi la squadra: allora la
     *         partita si rigioca da capo, che e' meglio di un secondo tempo con otto uomini.
     */
    fun readTeam(node: JsonNode, rosa: Map<Long, Player>): TeamSetup? {
        val formation = node["formation"].strOrNull()
            ?.let { name -> Formation.entries.firstOrNull { it.name == name } }
            ?: return null

        val slots = node["slots"].asList().mapNotNull { riga ->
            val player = rosa[riga["id"].long(0)] ?: return@mapNotNull null
            val position = riga["pos"].strOrNull()
                ?.let { name -> Position.entries.firstOrNull { it.name == name } }
                ?: return@mapNotNull null
            LineupSlot(comeStava(player, riga), position)
        }
        if (slots.size < Lineup.MIN_PLAYERS_ON_PITCH) return null

        val bench = node["bench"].asList().mapNotNull { riga ->
            rosa[riga["id"].long(0)]?.let { comeStava(it, riga) }
        }

        val base = Lineup(formation = formation, slots = slots, bench = bench)
        val conIncarichi = MatchDuty.entries.fold(base) { lineup, duty ->
            val id = node["duties"][duty.name].long(0).takeIf { it != 0L }
            SetPieces.assign(lineup, duty, id?.let { PlayerId(it) })
        }

        return TeamSetup(
            clubId = ClubId(node["club"].long(0)),
            name = node["name"].str("?"),
            lineup = conIncarichi,
            // Letto campo per campo e non riusando `LineupJson.tactics`: quella prende una
            // stringa JSON, e `JsonNode.toString()` di un oggetto gia' analizzato
            // restituisce la forma Kotlin di una mappa — `{stance=..., }` — che non e'
            // JSON e si sarebbe silenziosamente riletta come tattica di serie.
            tactics = Tactics(
                stance = node["tactics"]["stance"].enum(TacticalStance.EQUILIBRATO),
                width = node["tactics"]["width"].enum(TacticalWidth.NORMALE),
                tempo = node["tactics"]["tempo"].enum(TacticalTempo.NORMALE),
                pressing = node["tactics"]["pressing"].enum(TacticalPressing.MEDIO),
            ),
            orders = OrderJson.read(node["orders"]),
            coachStars = node["coach"].int(3).coerceIn(1, 5),
        )
    }

    private fun comeStava(player: Player, riga: JsonNode): Player = player.copy(
        stamina = riga["stamina"].int(player.stamina),
        morale = riga["morale"].int(player.morale),
        form = riga["form"].int(player.form),
    )
}
