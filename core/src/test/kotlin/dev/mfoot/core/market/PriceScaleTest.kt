package dev.mfoot.core.market

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.Money
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.world.DevelopmentCurve
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Quanto costa un giocatore, misurato.
 *
 * ## Il difetto che questo test esiste per impedire
 *
 * Un club gestito dall'AI aveva pagato **50 su un budget di 320** per un giocatore da 71:
 * il 15% del patrimonio per un onesto gregario. Non era un caso sfortunato, era la curva:
 * cubica su una scala che partiva da 40, quindi già a metà classifica i prezzi erano quelli
 * di un titolare.
 *
 * La taratura non si sceglie a intuito. Questo test stampa il listino e fallisce se un
 * prezzo esce dalla fascia decisa: chi cambia l'esponente della curva lo scopre subito.
 *
 * ## Perché le fasce sono queste
 *
 * Il fuoriclasse deve costare più di mezzo budget, così prenderne uno significa rinunciare
 * a mezza rosa — è quella rinuncia a rendere l'asta una decisione. Il gregario deve costare
 * l'uno per cento, perché di gregari servono dodici e con il due per cento a testa non si
 * completa la rosa. Fra i due estremi la curva deve essere ripida, non lineare.
 */
class PriceScaleTest {

    /** Budget 100M, cioè 100.000 migliaia: la scala di riferimento della lega. */
    private val config = ConfigPresets.sprint(16, 8, LocalDate.of(2026, 9, 1))
        .let { it.copy(economy = it.economy.copy(startingCredits = 100_000)) }

    /**
     * Un giocatore la cui età corrisponde al suo overall secondo la curva di sviluppo.
     *
     * Serve a misurare il prezzo del *livello*, non quello dell'età: un ventenne da 80 e un
     * trentaquattrenne da 80 hanno prezzi legittimamente diversi, e mescolarli renderebbe
     * il test una media senza significato.
     */
    private fun player(overall: Int, age: Int = 26, id: Long = 1L) = Player(
        id = PlayerId(id),
        firstName = "Test", lastName = "Overall$overall", nationality = "Italia",
        age = age,
        primaryPosition = Position.CC,
        attributes = Attributes.uniform(overall),
        potentialMin = overall,
        potentialMax = overall,
    )

    private data class Fascia(val overall: Int, val minPct: Double, val maxPct: Double)

    /** Le fasce approvate, in percentuale del budget iniziale. */
    private val listino = listOf(
        Fascia(overall = 90, minPct = 40.0, maxPct = 60.0),
        Fascia(overall = 80, minPct = 6.0, maxPct = 14.0),
        Fascia(overall = 71, minPct = 0.7, maxPct = 2.5),
        Fascia(overall = 60, minPct = 0.02, maxPct = 0.25),
    )

    @Test
    fun `il listino sta nelle fasce decise`() {
        val budget = config.economy.startingCredits.toDouble()
        val righe = StringBuilder("\nListino con budget ${Money(100_000).format()}:\n")
        val fuori = mutableListOf<String>()

        listino.forEach { fascia ->
            val prezzo = Valuation.marketValue(player(fascia.overall), config)
            val pct = prezzo / budget * 100.0

            righe.append(
                "  overall ${fascia.overall}: ${Money(prezzo).format()}" +
                    "  (${"%.2f".format(pct)}% del budget," +
                    " atteso ${fascia.minPct}-${fascia.maxPct}%)\n",
            )

            if (pct < fascia.minPct || pct > fascia.maxPct) {
                fuori += "overall ${fascia.overall} costa ${"%.2f".format(pct)}% " +
                    "invece di ${fascia.minPct}-${fascia.maxPct}%"
            }
        }

        println(righe)
        assertTrue(fuori.isEmpty(), "prezzi fuori fascia:\n  " + fuori.joinToString("\n  "))
    }

    /**
     * Il prezzo deve salire ripido, non in proporzione.
     *
     * Se un 90 costasse solo il doppio di un 80, la rosa migliore sarebbe sempre quella
     * che compra quantità invece di qualità, e i fuoriclasse non avrebbero senso di esistere.
     */
    @Test
    fun `la curva e ripida fra un buon titolare e un fuoriclasse`() {
        val titolare = Valuation.marketValue(player(80), config)
        val fuoriclasse = Valuation.marketValue(player(90), config)

        assertTrue(
            fuoriclasse >= titolare * 3,
            "un 90 costa ${Money(fuoriclasse).format()} contro ${Money(titolare).format()} " +
                "di un 80: troppo poco per essere una scelta",
        )
    }

    /**
     * Il giocatore che ti costruisci vale poco, e deve valere poco.
     *
     * Parte da 65 in un mondo dove i migliori stanno a 91: se il suo valore di mercato
     * fosse alto, il vincolo di schierarlo titolare sarebbe un regalo invece che il punto
     * debole strutturale intorno a cui costruire.
     */
    @Test
    fun `il custom appena creato vale una frazione minima del budget`() {
        val custom = Valuation.marketValue(player(65, age = 18), config)
        val pct = custom / config.economy.startingCredits.toDouble() * 100.0

        assertTrue(
            pct < 3.0,
            "un custom da 65 vale ${Money(custom).format()}, il ${"%.2f".format(pct)}% del budget",
        )
    }

    /**
     * Nessuna AI deve pagare il doppio di quanto una cosa vale.
     *
     * È il difetto originale visto sul campo: il tetto era generoso in percentuale del
     * budget disponibile senza guardare il valore del giocatore, quindi su un mediocre
     * offriva cifre da titolare.
     */
    @Test
    fun `il tetto delle AI resta vicino al valore di mercato`() {
        val casi = listOf(60, 65, 71, 75, 80, 88)

        casi.forEach { overall ->
            val p = player(overall, age = DevelopmentCurve.PEAK_AGE, id = overall.toLong())
            val valore = Valuation.marketValue(p, config)

            // Il moltiplicatore ammesso: strettissimo sui mediocri, un po' piu' largo sui
            // fuoriclasse, dove pagare un sovrapprezzo e' una scelta difendibile.
            val massimo = if (overall < 75) valore * 1.3 else valore * 2.0

            val stima = Valuation.estimatedValue(p, overall..overall, config)
            assertTrue(
                stima <= massimo,
                "per un $overall la stima e' ${Money(stima).format()} contro un valore di " +
                    "${Money(valore).format()}: piu' del tetto ammesso",
            )
        }
    }
}
