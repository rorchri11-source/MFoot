package dev.mfoot.tick

import dev.mfoot.core.json.JsonWriter
import dev.mfoot.core.match.MatchResult
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Player

/**
 * La partita, nella forma in cui viene salvata.
 *
 * ## Perche' la timeline si salva intera
 *
 * Il client la legge **una volta sola** e la riproduce con il proprio orologio. Nessun
 * polling durante i novanta minuti, costo zero sul backend gratuito, e chi apre l'app al
 * sessantesimo salta direttamente al sessantesimo invece di guardare un caricamento.
 *
 * E' anche cio' che rende il replay gratuito: la partita di ieri e' lo stesso identico
 * documento di quella in corso.
 */
object MatchJson {

    fun timeline(result: MatchResult): String {
        val w = JsonWriter(16 * 1024)
        w.beginObject()
        w.field("homeGoals", result.homeGoals)
        w.field("awayGoals", result.awayGoals)
        w.field("homePossession", result.homePossession)
        w.field("homeShots", result.homeShots)
        w.field("awayShots", result.awayShots)
        w.field("homeXg", result.homeXg)
        w.field("awayXg", result.awayXg)

        w.arrayField("events")
        result.events.forEach { event ->
            w.beginObject()
            w.field("minute", event.minute)
            w.field("type", event.type.name)
            w.field("side", event.side.name)
            // La pericolosita' e' cio' che decide quanto l'interfaccia deve alzare la
            // voce: un tiro da fuori e un rigore non possono essere disegnati uguali.
            w.field("danger", event.danger)
            w.field("text", event.description)
            w.field("homeGoals", event.homeGoals)
            w.field("awayGoals", event.awayGoals)
            event.zone?.let { w.field("zone", it.name) }
            event.player?.let { w.field("player", it.value) }
            event.secondaryPlayer?.let { w.field("second", it.value) }
            w.endObject()
        }
        w.endArray()

        w.endObject()
        return w.toString()
    }

    fun playerStats(result: MatchResult): String {
        val w = JsonWriter(8 * 1024)
        w.beginObject()
        result.stats.forEach { (playerId, s) ->
            w.objectField(playerId.value.toString())
            w.field("min", s.minutesPlayed)
            w.field("gol", s.goals)
            w.field("assist", s.assists)
            w.field("tiri", s.shots)
            w.field("inPorta", s.shotsOnTarget)
            w.field("parate", s.saves)
            w.field("subiti", s.goalsConceded)
            w.field("contrasti", s.tackles)
            w.field("gialli", s.yellowCards)
            w.field("rossi", s.redCards)
            w.field("infortunato", s.injured)
            // I duelli. Vanno qui e non in `appearances` di proposito: `player_stats` e'
            // gia' `jsonb`, quindi sei chiavi in piu' non sono sei colonne in piu'.
            // `appearances` invece l'app la legge con una `select` a lista esplicita, e
            // PostgREST per una colonna che non esiste rifiuta l'intera query — non la
            // riga, tutta la SELECT. Chi apre una partita vecchia non trova queste chiavi
            // e legge zero, che e' la verita'.
            w.field("duelliVinti", s.duelsWon)
            w.field("duelliPersi", s.duelsLost)
            w.field("dribbling", s.dribblesCompleted)
            w.field("dribblingTentati", s.dribblesAttempted)
            w.field("dribblingSubiti", s.dribblesSuffered)
            w.field("passaggi", s.passesCompleted)
            w.field("passaggiTentati", s.passesAttempted)
            w.endObject()
        }
        w.endObject()
        return w.toString()
    }

    /** Gli attributi nella stessa forma con cui li scrive l'app: chiave maiuscola, intero. */
    fun attributes(player: Player): String {
        val w = JsonWriter(512)
        w.beginObject()
        Attr.entries.forEach { w.field(it.name, player.attributes[it]) }
        w.endObject()
        return w.toString()
    }
}
