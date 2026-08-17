package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode
import java.time.Duration
import java.time.Instant

/**
 * Una riga del registro: una cosa che e' successa nella lega.
 *
 * [clubId] null significa "riguarda tutta la lega" — una giornata giocata, il mercato
 * aperto — mentre valorizzato significa che riguarda un club solo.
 */
data class NotificationRow(
    val id: Long,
    val clubId: Long?,
    val kind: String,
    val urgency: String,
    val body: String,
    val createdAt: Instant?,
) {
    val isImmediate: Boolean get() = urgency == "immediata"

    /**
     * Quanto tempo e' passato, a parole.
     *
     * Un orario assoluto ("17:42") non risponde alla domanda che si fa leggendo un
     * registro, che e' *quanto e' vecchia questa riga*. Con il tick che gira ogni cinque
     * minuti, "3 minuti" e "ieri" sono le due risposte che contano.
     */
    fun quando(now: Instant): String {
        val at = createdAt ?: return "—"
        val minuti = Duration.between(at, now).toMinutes()
        return when {
            minuti < 1 -> "ora"
            minuti < 60 -> "$minuti min"
            minuti < 60 * 24 -> "${minuti / 60} h"
            minuti < 60 * 24 * 2 -> "ieri"
            else -> "${minuti / (60 * 24)} giorni"
        }
    }
}

/**
 * Il registro di cosa ha fatto il tick e cosa ha fatto l'admin.
 *
 * ## Perche' non arriva anche `tick_state.last_run_notes`
 *
 * Perche' non e' leggibile. Su `tick_state` le Row Level Security sono attive e non esiste
 * nessuna policy di `select`: dal client quella tabella e' vuota per costruzione, e
 * chiederla produrrebbe un elenco sempre vuoto senza nessun errore che spieghi perche'.
 * Le note del tick servono davvero, ma prima serve la policy lato database.
 */
object NotificationRepository {

    /**
     * Un tetto alle righe.
     *
     * Il registro serve a capire cosa e' successo *di recente*: con una lega attiva le
     * notifiche si contano a migliaia dopo una settimana, e scaricarle tutte per mostrarne
     * le prime venti costerebbe piu' del caricamento del mondo.
     */
    private const val LIMIT = 200

    suspend fun recent(leagueId: Long): ApiResult<List<NotificationRow>> {
        val path = "/rest/v1/notifications?select=id,club_id,kind,urgency,body,created_at" +
            "&league_id=eq.$leagueId&order=created_at.desc&limit=$LIMIT"

        return SupabaseApi.get(path).then { body ->
            ApiResult.Ok(JsonNode.parse(body).asList().map { row ->
                NotificationRow(
                    id = row["id"].long(0),
                    clubId = row["club_id"].long(0).takeIf { it > 0 },
                    kind = row["kind"].str(""),
                    urgency = row["urgency"].str("riepilogo"),
                    body = row["body"].str(""),
                    createdAt = row["created_at"].strOrNull()?.let(::parseInstant),
                )
            })
        }
    }

    /**
     * Postgres scrive il fuso come `+00:00`, e `Instant.parse` vuole `Z`.
     *
     * Senza la normalizzazione ogni riga del registro perderebbe la data e mostrerebbe un
     * trattino: l'errore non si vedrebbe come errore, solo come un registro inutile.
     */
    private fun parseInstant(raw: String): Instant? = runCatching {
        val normalized = raw.trim().let {
            when {
                it.endsWith("Z") -> it
                it.contains('+') -> it.substringBeforeLast('+') + "Z"
                else -> "${it}Z"
            }
        }
        Instant.parse(normalized)
    }.getOrNull()
}
