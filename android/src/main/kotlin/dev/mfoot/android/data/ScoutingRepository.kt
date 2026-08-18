package dev.mfoot.android.data

import dev.mfoot.core.json.JsonNode

/** Cosa il tuo club sa del potenziale di un giocatore. */
data class Scouted(val range: IntRange, val knowledge: Int) {
    /**
     * Quanto lo conosci, a parole.
     *
     * "Poco" accanto a una forbice larga dice una cosa diversa da "bene" accanto alla
     * stessa forbice: nel primo caso puo' ancora stringersi, nel secondo quello e' il
     * giocatore — imprevedibile per natura, non per ignoranza.
     */
    val label: String
        get() = when {
            knowledge >= 70 -> "lo conosci bene"
            knowledge >= 40 -> "lo conosci abbastanza"
            knowledge >= 15 -> "lo conosci poco"
            else -> "non lo conosci"
        }
}

/**
 * Le stime ristrette, calcolate dal server.
 *
 * ## Perche' arrivano gia' fatte
 *
 * Perche' stringere la forbice significa avvicinarla al **valore vero**, e il valore vero
 * non lascia mai il server: la vista `players_public` non contiene i potenziali proprio per
 * questo. Se il telefono calcolasse la stima ristretta dovrebbe prima ricevere la verita',
 * e a quel punto tanto varrebbe mostrarla.
 *
 * Qui arriva un intervallo e nient'altro: non c'e' modo di dedurre il segreto per
 * differenza, perche' non si riceve nessuno scarto da un valore noto.
 *
 * ## Cosa succede se manca
 *
 * Si ricade sulla stima pubblica a conoscenza zero, che e' quella che l'app ha sempre
 * mostrato. Nessuna schermata si rompe: la forbice resta larga, che e' la verita' —
 * quel giocatore non lo si conosce.
 */
object ScoutingRepository {

    suspend fun load(clubId: Long): Map<Long, Scouted> {
        val path = "/rest/v1/scouting?select=player_id,est_min,est_max,knowledge" +
            "&club_id=eq.$clubId&limit=2000"

        return when (val esito = SupabaseApi.get(path)) {
            // Un fallimento qui non e' un guasto: e' una lega su cui la migrazione dello
            // scouting non e' ancora stata applicata, e il gioco funziona lo stesso.
            is ApiResult.Error -> emptyMap()

            is ApiResult.Ok -> JsonNode.parse(esito.value).asList().associate { row ->
                val min = row["est_min"].int(0)
                val max = row["est_max"].int(0)
                row["player_id"].long(0) to Scouted(
                    range = min..maxOf(min, max),
                    knowledge = row["knowledge"].int(0),
                )
            }
        }
    }
}
