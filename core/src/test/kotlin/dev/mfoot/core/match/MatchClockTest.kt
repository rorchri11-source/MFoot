package dev.mfoot.core.match

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L'orologio della partita in tempo reale.
 *
 * Dal 2026-08-29 il minuto non e' piu' un contatore che l'app fa avanzare: e' una funzione
 * dell'ora. Queste prove difendono la proprieta' che rende accettabile il tempo reale —
 * **due telefoni aperti nello stesso istante vedono lo stesso minuto** — e il caso che
 * altrimenti si sarebbe visto solo giocando: l'orologio entra nel secondo tempo prima che
 * il server lo abbia giocato, perche' il tick passa ogni cinque minuti.
 */
class MatchClockTest {

    private val inizio: Instant = Instant.parse("2026-09-01T21:00:00Z")
    private val pausa = 20

    private fun stato(dopoMinuti: Long, pronto: Boolean = true, ripresa: Instant? = null) =
        MatchClock.stato(
            kickoff = inizio,
            now = inizio.plusSeconds(dopoMinuti * 60),
            riprendeAlle = ripresa ?: MatchClock.ripresaDi(inizio, pausa),
            pausaMinuti = pausa,
            secondoTempoPronto = pronto,
        )

    @Test
    fun `prima del fischio d'inizio non e' cominciata`() {
        val prima = MatchClock.stato(inizio, inizio.minusSeconds(60), null, pausa, false)
        assertEquals(MatchClock.Fase.DA_GIOCARE, prima.fase)
        assertEquals(0, prima.minuto)
    }

    @Test
    fun `nel primo tempo il minuto e' il tempo passato`() {
        assertEquals(0, stato(0).minuto)
        assertEquals(23, stato(23).minuto)
        assertEquals(MatchClock.Fase.PRIMO_TEMPO, stato(23).fase)
        assertEquals(44, stato(44).minuto)
    }

    @Test
    fun `l'intervallo tiene il minuto fermo al quarantacinquesimo`() {
        listOf(45L, 50L, 64L).forEach { minuti ->
            val s = stato(minuti)
            assertEquals(MatchClock.Fase.INTERVALLO, s.fase, "al minuto $minuti")
            assertEquals(45, s.minuto, "al minuto $minuti")
        }
    }

    @Test
    fun `dopo l'intervallo riparte dal quarantacinquesimo`() {
        // 45 di gioco + 20 di pausa = la ripresa. Un minuto dopo si e' al 46'.
        assertEquals(MatchClock.Fase.SECONDO_TEMPO, stato(66).fase)
        assertEquals(46, stato(66).minuto)
        assertEquals(80, stato(100).minuto)
    }

    @Test
    fun `a novanta minuti di gioco e' finita`() {
        val s = stato(45 + 20 + 45L)
        assertEquals(MatchClock.Fase.FINITA, s.fase)
        assertEquals(90, s.minuto)
    }

    /**
     * Il caso che si sarebbe visto solo giocando.
     *
     * Il tick passa ogni cinque minuti: fra la fine dell'intervallo e il momento in cui il
     * server gioca il secondo tempo passa un po' di tempo, e in quel po' l'orologio direbbe
     * «61'» di una partita di cui non si conosce niente dopo il 45'. Il cronometro
     * correrebbe sopra un campo fermo.
     */
    @Test
    fun `se il server non ha ancora giocato il secondo tempo, il minuto non corre`() {
        val s = stato(70, pronto = false)

        assertEquals(45, s.minuto, "il minuto resta al 45' finche' non c'e' niente da mostrare")
        assertTrue(s.attesaRipresa, "e si sa che e' un'attesa diversa dall'intervallo")
        assertEquals(MatchClock.Fase.INTERVALLO, s.fase)
    }

    /**
     * La ripresa si conta dal fischio d'inizio, non da quando il server e' passato.
     *
     * Contandola da «adesso», ogni ritardo del tick si sommerebbe alla partita: un fischio
     * d'inizio raccolto con quattro minuti di ritardo sposterebbe la fine di quattro minuti,
     * e la partita finirebbe a un'ora che nessuno aveva letto da nessuna parte.
     */
    @Test
    fun `la ripresa e la fine dipendono solo dall'inizio e dalla pausa`() {
        assertEquals(inizio.plusSeconds(65 * 60), MatchClock.ripresaDi(inizio, pausa))
        assertEquals(inizio.plusSeconds(110 * 60), MatchClock.fineDi(inizio, pausa))
        assertEquals(110, MatchClock.durataMinuti(pausa))
    }

    @Test
    fun `senza l'ora di ripresa dal server la si stima con la pausa della lega`() {
        val senza = MatchClock.stato(inizio, inizio.plusSeconds(50 * 60), null, pausa, false)
        assertEquals(MatchClock.Fase.INTERVALLO, senza.fase)
        assertTrue(!senza.attesaRipresa, "dentro la pausa stimata non si sta aspettando il server")
    }
}
