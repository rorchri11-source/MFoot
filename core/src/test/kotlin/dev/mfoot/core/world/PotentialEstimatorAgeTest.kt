package dev.mfoot.core.world

import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L'età è la prima cosa che un utente legge su una scheda.
 *
 * Prima di questi test la stima ignorava l'età e mostrava sempre ±13 punti: la scheda
 * arrivava a promettere che un trentatreenne da 80 potesse diventare un 89. È il tipo di
 * informazione falsa su cui poi si fanno le aste — e il bug è saltato fuori solo
 * guardando i numeri veri usciti dal generatore, non quelli inventati per i test.
 */
class PotentialEstimatorAgeTest {

    private fun player(age: Int, overall: Int, potMin: Int, potMax: Int, id: Long = 1L) = Player(
        id = PlayerId(id),
        firstName = "Marco", lastName = "Ferrero", nationality = "Italia",
        age = age,
        primaryPosition = Position.CC,
        attributes = Attributes.uniform(overall),
        potentialMin = potMin,
        potentialMax = potMax,
    )

    private fun spread(p: Player): Int {
        val e = PotentialEstimator.estimate(p, observerId = 1L)
        return e.last - e.first
    }

    @Test
    fun `un veterano non ha nessun margine di crescita`() {
        assertEquals(0.0, DevelopmentCurve.remainingUpside(33))
        assertEquals(0.0, DevelopmentCurve.remainingUpside(28))
        assertEquals(0.0, DevelopmentCurve.remainingUpside(27))
        assertTrue(DevelopmentCurve.remainingUpside(20) > 0.0)
    }

    @Test
    fun `il margine cresce quanto piu si e giovani`() {
        val margini = listOf(16, 18, 20, 22, 24, 26).map { DevelopmentCurve.remainingUpside(it) }
        margini.zipWithNext().forEach { (piuGiovane, piuVecchio) ->
            assertTrue(piuGiovane > piuVecchio, "margini non decrescenti: $margini")
        }
        assertTrue(margini.first() <= 1.0)
    }

    /** Il caso concreto trovato nel campione: B. Coelho, 33 anni, overall 80. */
    @Test
    fun `la scheda di un trentatreenne non promette una crescita di nove punti`() {
        val veterano = player(age = 33, overall = 80, potMin = 81, potMax = 87)
        val stima = PotentialEstimator.estimate(veterano, observerId = 1L)

        assertTrue(
            stima.last - veterano.overall <= 4,
            "la stima dice che a 33 anni puo' arrivare a ${stima.last} partendo da 80",
        )
    }

    /** Gli altri due casi del campione: 28 anni, gia' al proprio massimo. */
    @Test
    fun `un ventottenne al suo massimo non viene dato in crescita fino a novantanove`() {
        listOf(
            player(age = 28, overall = 91, potMin = 91, potMax = 93, id = 1L),
            player(age = 28, overall = 86, potMin = 86, potMax = 88, id = 2L),
        ).forEach { p ->
            val stima = PotentialEstimator.estimate(p, observerId = 1L)
            assertTrue(
                stima.last < 99,
                "${p.shortName}: stima fino a ${stima.last}, cioe' quasi il massimo assoluto",
            )
            assertTrue(
                stima.last - p.overall <= 5,
                "${p.shortName}: gli si attribuiscono ancora ${stima.last - p.overall} punti di crescita",
            )
        }
    }

    /**
     * Il rovescio della medaglia: sui giovanissimi l'incertezza deve restare grande,
     * altrimenti sparisce la scommessa che è il cuore del mercato.
     */
    @Test
    fun `su un sedicenne l'incertezza resta ampia`() {
        val ragazzino = player(age = 16, overall = 42, potMin = 69, potMax = 87)
        assertTrue(
            spread(ragazzino) >= 12,
            "forbice di soli ${spread(ragazzino)} punti su un sedicenne: non c'e' piu' niente da scoprire",
        )
    }

    /**
     * Il confronto va fatto a **potenziale** costante, non a overall costante: un
     * sedicenne da 70 e un trentenne da 70 non sono lo stesso giocatore a eta' diverse,
     * sono due carriere completamente diverse. L'overall di ciascuno si ricava dalla
     * curva di sviluppo, che e' esattamente come li genera il mondo.
     */
    @Test
    fun `la forbice si stringe con l'eta a parita di potenziale`() {
        val potenziale = 84
        val ampiezze = listOf(16, 20, 24, 28, 33).map { age ->
            val overall = DevelopmentCurve.currentOverall(potenziale, age)
            spread(player(age = age, overall = overall, potMin = potenziale - 3, potMax = potenziale + 3))
        }

        ampiezze.zipWithNext().forEach { (giovane, vecchio) ->
            assertTrue(giovane >= vecchio, "la forbice non si stringe con l'eta': $ampiezze")
        }
        assertTrue(
            ampiezze.first() > ampiezze.last() * 2,
            "fra un sedicenne e un trentatreenne la differenza e' troppo debole: $ampiezze",
        )
    }

    @Test
    fun `lo scouting stringe comunque la forbice sui giovani`() {
        val giovane = player(age = 18, overall = 55, potMin = 80, potMax = 86)
        val alBuio = PotentialEstimator.estimate(giovane, 1L)
        val osservato = PotentialEstimator.estimate(giovane, 1L, minutesObserved = 1800, scoutAccuracy = 0.75)

        assertTrue((osservato.last - osservato.first) < (alBuio.last - alBuio.first))
    }

    /**
     * La prova che il segreto puo' restare sul server.
     *
     * L'app legge `players_public`, dove i potenziali veri non ci sono: al loro posto
     * mette un segnaposto. Se la stima pubblica cambiasse al variare dei valori veri,
     * quel segnaposto falserebbe ogni scheda — e peggio, chi confrontasse la stima
     * dell'app con quella del server potrebbe dedurre per differenza cio' che non deve
     * sapere. Due giocatori identici in tutto cio' che e' pubblico devono produrre la
     * stessa identica forbice, quali che siano i loro potenziali reali.
     */
    @Test
    fun `la stima pubblica non dipende dai potenziali veri`() {
        val fenomeno = player(age = 19, overall = 61, potMin = 88, potMax = 93, id = 77L)
        val bidone = player(age = 19, overall = 61, potMin = 61, potMax = 63, id = 77L)

        assertEquals(
            PotentialEstimator.publicEstimate(fenomeno, observerId = 4L),
            PotentialEstimator.publicEstimate(bidone, observerId = 4L),
            "la stima pubblica lascia trapelare il potenziale vero",
        )
    }

    @Test
    fun `osservatori diversi vedono stime diverse dello stesso giocatore`() {
        val giovane = player(age = 18, overall = 58, potMin = 78, potMax = 86, id = 12L)
        val stime = (1L..8L).map { PotentialEstimator.publicEstimate(giovane, it) }.toSet()

        assertTrue(stime.size > 1, "tutti i club vedono la stessa forbice: nessun affare e' possibile")
    }

    @Test
    fun `la stima resta comunque sopra l'overall attuale e dentro la scala`() {
        listOf(16, 22, 28, 35).forEach { age ->
            val p = player(age = age, overall = 75, potMin = 80, potMax = 85)
            val stima = PotentialEstimator.estimate(p, 1L)
            assertTrue(stima.first >= p.overall, "a $age anni la stima parte sotto l'overall")
            assertTrue(stima.last <= 99)
            assertTrue(stima.first <= stima.last)
        }
    }
}
