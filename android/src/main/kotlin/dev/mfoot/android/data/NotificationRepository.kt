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
    /**
     * Di cosa parla: la partita, l'asta, lo scambio, la missione.
     *
     * Nullo quando non c'e' niente da aprire — un riepilogo di giornata non porta da
     * nessuna parte — e nullo su ogni notifica scritta prima del 2026-08-30.
     */
    val targetId: Long? = null,
) {
    val isImmediate: Boolean get() = urgency == "immediata"

    /**
     * Dove porta, se porta da qualche parte.
     *
     * Il tipo decide la schermata, il bersaglio decide **quale** cosa aprire. Senza
     * bersaglio si va comunque nella sezione giusta: e' meno preciso ma e' sempre meglio
     * di una riga che non fa niente.
     */
    val apribile: Boolean
        get() = kind in setOf(
            "partita", "asta", "scambio", "amichevole", "scouting",
            "mercato", "contratto", "primavera", "competizione", "prestito",
        )

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

    /**
     * Le notifiche recenti.
     *
     * ## Perche' si prova due volte
     *
     * `target_id` — di cosa parla la notifica — e' arrivata il 2026-08-30, ed e' quello
     * che rende toccabile una riga. PostgREST pero' per una colonna che non esiste rifiuta
     * **l'intera query**: chiederla e basta vorrebbe dire che una lega col database
     * indietro non vede piu' nessuna notifica, invece di vederle senza poterle toccare.
     *
     * Qui il primo tentativo la chiede; se fallisce si riprova senza. Un giro in piu' solo
     * sui database vecchi, e chi e' aggiornato paga una query sola. E' la stessa difesa
     * usata per la proprieta' dello staff, applicata dove una lettura a parte sarebbe
     * costata piu' di un ritentativo.
     */
    suspend fun recent(leagueId: Long): ApiResult<List<NotificationRow>> {
        val coda = "&league_id=eq.$leagueId&order=created_at.desc&limit=$LIMIT"
        val conBersaglio =
            "/rest/v1/notifications?select=id,club_id,kind,urgency,body,created_at,target_id$coda"
        val senza =
            "/rest/v1/notifications?select=id,club_id,kind,urgency,body,created_at$coda"

        val esito = SupabaseApi.get(conBersaglio).let {
            if (it is ApiResult.Error) SupabaseApi.get(senza) else it
        }

        return esito.then { body ->
            ApiResult.Ok(JsonNode.parse(body).asList().map { row ->
                NotificationRow(
                    id = row["id"].long(0),
                    clubId = row["club_id"].long(0).takeIf { it > 0 },
                    kind = row["kind"].str(""),
                    urgency = row["urgency"].str("riepilogo"),
                    body = row["body"].str(""),
                    createdAt = row["created_at"].strOrNull()?.let(::parseInstant),
                    targetId = row["target_id"].long(0).takeIf { it > 0 },
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
    private fun parseInstant(raw: String): Instant? = Istanti.parse(raw)
}
