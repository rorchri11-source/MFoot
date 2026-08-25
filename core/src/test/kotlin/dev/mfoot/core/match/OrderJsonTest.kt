package dev.mfoot.core.match

import dev.mfoot.core.model.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Andata e ritorno degli ordini condizionali.
 *
 * ## Perche' questo test conta piu' di quanto sembri
 *
 * Perche' scrittura e lettura vivono in due processi diversi — l'app scrive, il tick legge
 * — e se divergono **non succede niente di visibile**: nessun errore, nessun avviso. Il
 * manager prepara «se sono sotto dal 60' passa a offensivo», la partita si gioca senza che
 * l'ordine scatti mai, e non c'e' modo di accorgersene guardando l'app.
 *
 * E' lo stesso motivo per cui `ConfigJsonTest` esiste: li' un difetto uguale avrebbe fatto
 * tornare in silenzio le regole della lega ai valori di serie.
 */
class OrderJsonTest {

    private val tuttiGliOrdini = listOf(
        ConditionalOrder(
            id = 1,
            trigger = OrderTrigger.SottoDalMinuto(60),
            action = OrderAction.CambiaAssetto(TacticalStance.ULTRA_OFFENSIVO),
        ),
        ConditionalOrder(
            id = 2,
            trigger = OrderTrigger.InVantaggioDiDalMinuto(goals = 2, minute = 70),
            action = OrderAction.CambiaRitmo(TacticalTempo.LENTO),
            priority = 3,
        ),
        ConditionalOrder(
            id = 3,
            trigger = OrderTrigger.PariDalMinuto(75),
            action = OrderAction.CambiaPressing(TacticalPressing.ALTO),
        ),
        ConditionalOrder(
            id = 4,
            trigger = OrderTrigger.DalMinuto(46),
            action = OrderAction.CambiaAmpiezza(TacticalWidth.LARGO),
        ),
        ConditionalOrder(
            id = 5,
            trigger = OrderTrigger.GiocatoreAmmonito(PlayerId(77)),
            action = OrderAction.Sostituisci(out = PlayerId(77), entra = PlayerId(88)),
        ),
        ConditionalOrder(
            id = 6,
            trigger = OrderTrigger.StaminaSotto(PlayerId(91), threshold = 35),
            action = OrderAction.Sostituisci(out = PlayerId(91), entra = PlayerId(92)),
        ),
        ConditionalOrder(
            id = 7,
            trigger = OrderTrigger.QualcunoInRiserva(30),
            action = OrderAction.CambiaRitmo(TacticalTempo.ALTO),
        ),
    )

    @Test
    fun `ogni ordine torna indietro identico`() {
        val riletti = OrderJson.read(OrderJson.write(tuttiGliOrdini))

        assertEquals(tuttiGliOrdini.size, riletti.size, "si sono persi degli ordini per strada")
        tuttiGliOrdini.zip(riletti).forEach { (originale, riletto) ->
            assertEquals(originale, riletto, "l'ordine ${originale.id} non e' tornato uguale")
        }
    }

    @Test
    fun `ogni tipo di condizione e ogni tipo di azione e' coperto`() {
        // Se qualcuno aggiunge una condizione nuova a `OrderTrigger` senza insegnarla a
        // `OrderJson`, questo test resta verde — ma la lista qui sopra invecchia, e allora
        // almeno che sia esplicito quanti tipi si stanno coprendo.
        val condizioni = tuttiGliOrdini.map { it.trigger::class }.toSet()
        val azioni = tuttiGliOrdini.map { it.action::class }.toSet()

        assertEquals(7, condizioni.size, "non tutte le condizioni sono nel test")
        assertEquals(5, azioni.size, "non tutte le azioni sono nel test")
    }

    @Test
    fun `una lista vuota resta vuota`() {
        assertEquals(emptyList(), OrderJson.read(OrderJson.write(emptyList())))
    }

    @Test
    fun `un ordine incomprensibile si salta senza far cadere gli altri`() {
        val json = """
            [
              {"id":1,"priority":0,"trigger":{"tipo":"SOTTO_DAL","minuto":60},
               "azione":{"tipo":"ASSETTO","valore":"OFFENSIVO"}},
              {"id":2,"priority":0,"trigger":{"tipo":"DA_UNA_VERSIONE_FUTURA"},
               "azione":{"tipo":"ASSETTO","valore":"OFFENSIVO"}},
              {"id":3,"priority":0,"trigger":{"tipo":"DAL_MINUTO","minuto":80},
               "azione":{"tipo":"RITMO","valore":"ALTO"}}
            ]
        """.trimIndent()

        val letti = OrderJson.read(json)

        assertEquals(2, letti.size, "un ordine sconosciuto ha portato via anche gli altri")
        assertEquals(listOf(1, 3), letti.map { it.id })
    }

    @Test
    fun `due ordini con lo stesso id non fanno esplodere il piano partita`() {
        val json = """
            [
              {"id":1,"priority":0,"trigger":{"tipo":"DAL_MINUTO","minuto":60},
               "azione":{"tipo":"RITMO","valore":"ALTO"}},
              {"id":1,"priority":0,"trigger":{"tipo":"DAL_MINUTO","minuto":70},
               "azione":{"tipo":"RITMO","valore":"LENTO"}}
            ]
        """.trimIndent()

        val letti = OrderJson.read(json)

        // `TeamSetup` ha un require sugli id ripetuti: se arrivasse un duplicato dal
        // database, l'eccezione fermerebbe la partita di tutta la lega dentro al tick.
        assertEquals(1, letti.size)
        assertTrue(
            runCatching {
                TeamSetup(
                    clubId = dev.mfoot.core.model.ClubId(1),
                    name = "Prova",
                    lineup = Lineup(
                        formation = Formation.F_4_3_3,
                        slots = emptyList<LineupSlot>().ifEmpty {
                            // Sette e' il minimo regolamentare.
                            List(7) { i ->
                                LineupSlot(
                                    dev.mfoot.core.model.Player(
                                        id = PlayerId(i.toLong() + 1),
                                        firstName = "G",
                                        lastName = "P$i",
                                        nationality = "it",
                                        age = 25,
                                        primaryPosition = dev.mfoot.core.model.Position.CC,
                                        attributes = dev.mfoot.core.model.Attributes.uniform(60),
                                        potentialMin = 60,
                                        potentialMax = 60,
                                    ),
                                    dev.mfoot.core.model.Position.CC,
                                )
                            }
                        },
                    ),
                    orders = letti,
                )
            }.isSuccess,
        )
    }
}
