package dev.mfoot.android.data

import dev.mfoot.core.json.JsonWriter

/**
 * Le scritture sui propri giocatori.
 *
 * ## Perche' passa da una funzione e non da un update
 *
 * `players` non e' scrivibile da nessun client, e a ragione: overall, potenziale e
 * attributi decidono chi vince le partite. Se il telefono potesse toccarli, un pomeriggio
 * di lavoro con un proxy HTTP basterebbe a costruirsi una squadra da novantacinque.
 *
 * Il morale e' l'unica eccezione sensata — cambia parlando, e parlare e' un'azione di
 * gioco — ma resta il database a decidere che si puo' cambiare **solo quello** e **solo ai
 * propri**.
 */
object PlayerRepository {

    suspend fun updateMorale(playerId: Long, morale: Int): ApiResult<Unit> {
        val w = JsonWriter(128)
        w.beginObject()
        w.field("p_player_id", playerId)
        w.field("p_morale", morale)
        w.endObject()

        return SupabaseApi.rpc("set_player_morale", w.toString())
            .then { ApiResult.Ok(Unit) }
            .mapMissingFunction()
    }

    /** Come per gli scambi: l'errore tecnico diventa la migrazione che manca. */
    private fun ApiResult<Unit>.mapMissingFunction(): ApiResult<Unit> = when {
        this is ApiResult.Error && message.contains("set_player_morale") -> ApiResult.Error(
            "I colloqui hanno bisogno della migrazione 0010_conversations.sql, che non e' " +
                "ancora stata applicata a questo database.",
        )
        else -> this
    }
}
