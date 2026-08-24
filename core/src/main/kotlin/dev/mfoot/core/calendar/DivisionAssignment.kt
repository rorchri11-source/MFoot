package dev.mfoot.core.calendar

import dev.mfoot.core.model.ClubId

/**
 * Un club nel momento in cui gli si assegna una divisione di partenza.
 *
 * [strength] e' quanto vale la rosa: serve solo a ordinare le AI fra loro, perche' gli
 * umani non si ordinano — vanno tutti in prima divisione per regola.
 */
data class ClubToPlace(
    val id: ClubId,
    /** Ha un proprietario in carne e ossa. */
    val isHuman: Boolean,
    /** E' la Primavera di qualcuno. */
    val isSecondTeam: Boolean,
    val strength: Long,
)

/** Cosa non torna nell'assegnazione, detto prima di scriverla. */
data class PlacementWarning(val message: String)

/** Dove va ognuno, piu' quello che c'e' da sapere prima di confermare. */
data class Placement(
    val levels: Map<ClubId, Int>,
    val warnings: List<PlacementWarning> = emptyList(),
)

/**
 * Chi parte in quale serie.
 *
 * ## La regola, e da dove viene
 *
 * L'ha dettata il proprietario della lega: **i club umani partono tutti in prima
 * divisione**. Le seconde squadre non entrano nel conto e partono dall'ultima. Le AI
 * riempiono i posti che restano, dalla piu' forte alla piu' debole.
 *
 * Prima si distribuivano tutti a serpentina in base alla forza, umani e AI mescolati.
 * Era una regola ragionevole per un campionato vero e sbagliata per una lega fra amici:
 * chi si iscrive vuole giocare contro gli altri amici, non ritrovarsi in Serie B contro
 * otto squadre del computer perche' la sua rosa iniziale valeva tre punti di meno.
 *
 * ## La regola vale per l'inizio
 *
 * Se poi un umano retrocede giocando, retrocede davvero: quello lo decide [SeasonEnd], e
 * un campionato in cui non si puo' scendere non e' un campionato. Questa funzione risponde
 * solo alla domanda «da dove si parte».
 *
 * ## Perche' restituisce anche degli avvisi
 *
 * Perche' il caso in cui gli umani non ci stanno tutti in prima divisione **esiste** — dodici
 * amici e una Serie A da dieci — e va detto a chi sta per premere, non risolto di nascosto.
 * Qui si sceglie di far entrare comunque tutti gli umani, allargando di fatto la prima
 * divisione, e di dirlo: la regola del proprietario e' che gli umani stanno in prima, e una
 * funzione che ne lasciasse fuori due per rispettare una dimensione avrebbe rispettato il
 * numero e tradito la regola.
 */
object DivisionAssignment {

    /**
     * @param sizes quante squadre per divisione, dalla prima in giu'. Lista vuota o piu'
     *   corta di [divisions]: le divisioni mancanti si dividono il resto in parti uguali.
     */
    fun initial(
        clubs: List<ClubToPlace>,
        divisions: Int,
        sizes: List<Int> = emptyList(),
    ): Placement {
        if (clubs.isEmpty()) return Placement(emptyMap())
        if (divisions <= 1) return Placement(clubs.associate { it.id to 1 })

        val avvisi = ArrayList<PlacementWarning>(2)
        val livelli = LinkedHashMap<ClubId, Int>(clubs.size)

        val seconde = clubs.filter { it.isSecondTeam }
        val prime = clubs.filterNot { it.isSecondTeam }
        val umani = prime.filter { it.isHuman }
        val ai = prime.filterNot { it.isHuman }.sortedByDescending { it.strength }

        val capienze = capienze(prime.size, divisions, sizes)

        // 1. Gli umani, tutti in prima. Nessun ordinamento: non c'e' niente da ordinare.
        umani.forEach { livelli[it.id] = 1 }

        if (umani.size > capienze[0]) {
            avvisi += PlacementWarning(
                "Hai ${umani.size} club di giocatori veri e la prima divisione ne prevede " +
                    "${capienze[0]}. Ci entrano tutti lo stesso — la regola è che i " +
                    "giocatori partono dalla massima serie — ma la prima divisione sarà " +
                    "da ${umani.size} squadre. Se non è quello che vuoi, cambia le " +
                    "dimensioni prima di assegnare.",
            )
        }

        // 2. Le AI riempiono cio' che resta, livello per livello, dalla piu' forte.
        var indice = 0
        for (livello in 1..divisions) {
            val gia = if (livello == 1) umani.size else 0
            val posti = (capienze[livello - 1] - gia).coerceAtLeast(0)
            repeat(posti) {
                if (indice < ai.size) livelli[ai[indice++].id] = livello
            }
        }

        // Le AI avanzate vanno nell'ultima divisione: e' l'unico posto in cui aggiungerne
        // una non toglie niente a nessuno.
        while (indice < ai.size) livelli[ai[indice++].id] = divisions

        // 3. Le seconde squadre, sempre in fondo. Non entrano nelle capienze: sono un
        //    campionato loro, e contarle fra i posti della prima divisione vorrebbe dire
        //    togliere un posto a un amico per farlo a una Primavera.
        seconde.forEach { livelli[it.id] = divisions }

        val vuote = (1..divisions).filter { livello -> livelli.none { it.value == livello } }
        if (vuote.isNotEmpty()) {
            avvisi += PlacementWarning(
                "Nessuna squadra in ${if (vuote.size == 1) "divisione" else "divisioni"} " +
                    vuote.joinToString(", ") + ". Servono più club, o meno divisioni.",
            )
        }

        return Placement(livelli, avvisi)
    }

    /**
     * Quante squadre stanno in ogni divisione.
     *
     * Le dimensioni scelte dall'admin vincono. Dove non le ha scelte si divide in parti
     * uguali, con il resto distribuito dall'alto: con undici club in tre divisioni vengono
     * 4, 4, 3, che e' come le distribuirebbe una persona.
     */
    private fun capienze(quanti: Int, divisions: Int, sizes: List<Int>): List<Int> {
        val base = quanti / divisions
        val resto = quanti % divisions

        return (0 until divisions).map { indice ->
            sizes.getOrNull(indice)?.takeIf { it > 0 } ?: (base + if (indice < resto) 1 else 0)
        }
    }
}
