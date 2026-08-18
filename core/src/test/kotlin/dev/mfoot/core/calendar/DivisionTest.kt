package dev.mfoot.core.calendar

import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.CompetitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Promozioni, retrocessioni e spareggi.
 *
 * ## Perche' questi test contano piu' della media
 *
 * A fine stagione questa e' l'unica cosa che venti amici guarderanno, e non c'e' modo di
 * rimediare a un errore: se la Serie A retrocede una squadra di troppo, l'anno dopo la
 * Serie A ha diciannove club e nessuno sa piu' come rimetterla a posto. E' una delle poche
 * parti del gioco dove sbagliare non si corregge, si ricomincia.
 */
class DivisionTest {

    private fun clubs(from: Int, count: Int): List<ClubId> =
        (from until from + count).map { ClubId(it.toLong()) }

    /** Tre divisioni da sei: la forma tipica di una lega da diciotto. */
    private val tre = listOf(
        Division(1, "Serie A", clubs(1, 6)),
        Division(2, "Serie B", clubs(7, 6)),
        Division(3, "Serie C", clubs(13, 6)),
    )

    private fun classificheIdentiche(divisioni: List<Division>): Map<Int, List<ClubId>> =
        divisioni.associate { it.level to it.clubs }

    private fun esiti(
        divisioni: List<Division> = tre,
        rules: DivisionRules = DivisionRules(),
    ): Map<Int, List<SeasonOutcome>> =
        SeasonEnd.settle(divisioni, classificheIdentiche(divisioni), rules)
            .groupBy { it.level }
            .mapValues { (_, fates) -> fates.sortedBy { it.position }.map { it.outcome } }

    // --------------------------------------------------------------- le due estremita'

    /**
     * Dalla massima divisione non si sale, e dall'ultima non si scende.
     *
     * Non e' un caso particolare: e' la definizione di primo e ultimo livello. Trattarli
     * come gli altri produrrebbe un promosso al livello zero — cioe' un club che sparisce
     * dalla lega — e un retrocesso al livello quattro, che non esiste.
     */
    @Test
    fun `dalla prima divisione non si sale`() {
        val serieA = esiti().getValue(1)

        assertEquals(SeasonOutcome.CAMPIONE, serieA.first())
        assertFalse(
            serieA.contains(SeasonOutcome.PROMOSSO) || serieA.contains(SeasonOutcome.PLAYOFF),
            "dalla Serie A si sale: $serieA",
        )
    }

    @Test
    fun `dall ultima divisione non si scende`() {
        val serieC = esiti().getValue(3)

        assertFalse(
            serieC.contains(SeasonOutcome.RETROCESSO) || serieC.contains(SeasonOutcome.PLAYOUT),
            "dalla Serie C si retrocede, e sotto non c'e' niente: $serieC",
        )
    }

    // ------------------------------------------------------------------- il caso tipico

    /**
     * In una divisione da sei, con le regole predefinite, **nessuno** finisce a mani vuote.
     *
     * Prima sale, seconda e terza ai playoff, quarta e quinta ai playout, sesta giu'
     * diretta. E' esattamente il punto delle divisioni: in un girone unico da venti, quindici
     * club non hanno piu' niente da giocare da novembre.
     *
     * Il playout sta **sopra** la retrocessione diretta, come in Serie B: ultima giu' senza
     * appello, le due sopra si giocano il secondo posto disponibile.
     */
    @Test
    fun `in una divisione di mezzo ogni posizione decide qualcosa`() {
        val serieB = esiti().getValue(2)

        assertEquals(
            listOf(
                SeasonOutcome.CAMPIONE,
                SeasonOutcome.PLAYOFF,
                SeasonOutcome.PLAYOFF,
                SeasonOutcome.PLAYOUT,
                SeasonOutcome.PLAYOUT,
                SeasonOutcome.RETROCESSO,
            ),
            serieB,
        )
        assertFalse(serieB.contains(SeasonOutcome.RESTA))
    }

    /**
     * Il conto delle retrocessioni parte **dal fondo**, non dall'alto.
     *
     * Con divisioni di dimensioni diverse — ed e' normale che lo siano, dieci amici non si
     * dividono in gruppi uguali — contare dall'alto darebbe posizioni diverse a parita' di
     * regole: l'ultimo di una divisione da quattro sarebbe salvo e l'ultimo di una da otto
     * retrocesso.
     */
    @Test
    fun `le retrocessioni si contano dal fondo anche con divisioni disuguali`() {
        val disuguali = listOf(
            Division(1, "Serie A", clubs(1, 8)),
            Division(2, "Serie B", clubs(9, 4)),
            // Serve una terza divisione perche' la Serie B sia davvero di mezzo: dall'ultima
            // non si retrocede, e il test non misurerebbe niente.
            Division(3, "Serie C", clubs(13, 6)),
        )
        val esiti = SeasonEnd.settle(disuguali, classificheIdentiche(disuguali), DivisionRules())

        // Otto club: ottava giu' diretta, settima e sesta ai playout, la quinta e' salva.
        val serieA = esiti.filter { it.level == 1 }.sortedBy { it.position }
        assertEquals(SeasonOutcome.RETROCESSO, serieA[7].outcome)
        assertEquals(SeasonOutcome.PLAYOUT, serieA[6].outcome)
        assertEquals(SeasonOutcome.PLAYOUT, serieA[5].outcome)
        assertEquals(SeasonOutcome.RESTA, serieA[4].outcome)

        // Quattro club nella divisione sotto, stesse regole: la quarta giu' diretta e la
        // distanza dal fondo vale come sopra. Ma qui le due fasce si sovrappongono — la
        // terza sarebbe insieme ai playoff e ai playout — e vince la promozione: chi ha
        // fatto meglio non si gioca la salvezza.
        val serieB = esiti.filter { it.level == 2 }.sortedBy { it.position }
        assertEquals(SeasonOutcome.RETROCESSO, serieB[3].outcome)
        assertEquals(SeasonOutcome.PLAYOFF, serieB[2].outcome)
    }

    // ------------------------------------------------- la domanda dei venti amici

    /**
     * «Ma come, 18-20 retrocesse?»
     *
     * E' la domanda giusta e la risposta e' che con una divisione sola sarebbe cosi': su
     * venti club, chi resta fuori dalle prime posizioni non ha piu' niente da giocare da
     * novembre. Con quattro divisioni da cinque, ogni club ha sempre due cose per cui
     * giocare — salire e non scendere — e le posizioni che non decidono niente sono due per
     * divisione invece di quindici.
     *
     * Questo test misura proprio quello: quanti club finiscono la stagione con "resta", che
     * e' l'esito che significa "la tua stagione non ha deciso niente".
     */
    @Test
    fun `spezzare la lega in divisioni riduce i club senza niente da giocare`() {
        val unaSola = listOf(Division(1, "Unica", clubs(1, 20)))
        val quattro = (1..4).map { level ->
            Division(level, "Serie $level", clubs(1 + (level - 1) * 5, 5))
        }

        val senzaNiente = { divisioni: List<Division> ->
            SeasonEnd.settle(divisioni, classificheIdentiche(divisioni), DivisionRules())
                .count { it.outcome == SeasonOutcome.RESTA }
        }

        val conUna = senzaNiente(unaSola)
        val conQuattro = senzaNiente(quattro)

        // Con una divisione sola non si sale e non si scende: **nessuno** ha niente in
        // gioco tranne il primo posto.
        assertEquals(19, conUna)
        assertTrue(
            conQuattro < conUna / 2,
            "quattro divisioni lasciano $conQuattro club senza niente in gioco, " +
                "una sola ne lascia $conUna: non e' un miglioramento",
        )
    }

    // --------------------------------------------------------------- l'equilibrio

    /**
     * Se quante salgono e quante scendono non coincidono, le divisioni cambiano dimensione.
     *
     * E' l'errore di configurazione piu' facile da fare e il piu' difficile da notare:
     * funziona per una stagione e alla seconda la Serie A ha un club in piu' ogni anno,
     * senza che nessuno sappia da dove arriva. [DivisionRules.isBalanced] esiste per poterlo
     * dire prima.
     */
    @Test
    fun `regole equilibrate e non`() {
        assertTrue(DivisionRules().isBalanced)
        // Una che sale diretta piu' una dai playoff fa due; due che scendono dirette senza
        // playout fanno due. Torna.
        assertTrue(
            DivisionRules(
                directPromotions = 1,
                playoffSlots = 2,
                directRelegations = 2,
                playoutSlots = 0,
            ).isBalanced,
        )
        // Tre che salgono e due che scendono: la divisione di sopra si gonfia.
        assertFalse(
            DivisionRules(
                directPromotions = 3,
                playoffSlots = 0,
                directRelegations = 2,
                playoutSlots = 0,
            ).isBalanced,
        )
    }

    /** I playoff assegnano **un** posto, non uno per partecipante. */
    @Test
    fun `i playoff valgono un posto solo`() {
        assertEquals(2, DivisionRules(directPromotions = 1, playoffSlots = 4).totalMoves)
        assertEquals(1, DivisionRules(directPromotions = 1, playoffSlots = 0).totalMoves)
    }

    // ------------------------------------------------------ playoff senza retrocessioni

    @Test
    fun `senza playoff le posizioni di mezzo restano`() {
        val senza = DivisionRules(playoffSlots = 0, playoutSlots = 0)
        assertEquals(
            listOf(
                SeasonOutcome.CAMPIONE,
                SeasonOutcome.RESTA,
                SeasonOutcome.RESTA,
                SeasonOutcome.RESTA,
                SeasonOutcome.RESTA,
                SeasonOutcome.RETROCESSO,
            ),
            esiti(rules = senza).getValue(2),
        )
    }

    /**
     * In una divisione piccolissima la promozione batte la retrocessione.
     *
     * Con quattro club, due promozioni e due retrocessioni, le due fasce si sovrappongono e
     * la seconda in classifica sarebbe insieme promossa e retrocessa. Chi arriva davanti
     * non puo' scendere per nessuna ragione al mondo: e' la regola che salva questa
     * configurazione dall'assurdo invece di rifiutarla.
     */
    @Test
    fun `in una divisione minuscola chi arriva davanti non retrocede`() {
        val piccole = listOf(
            Division(1, "A", clubs(1, 4)),
            Division(2, "B", clubs(5, 4)),
            Division(3, "C", clubs(9, 4)),
        )
        val strette = DivisionRules(
            directPromotions = 2,
            playoffSlots = 0,
            directRelegations = 2,
            playoutSlots = 0,
        )
        val serieB = SeasonEnd.settle(piccole, classificheIdentiche(piccole), strette)
            .filter { it.level == 2 }
            .sortedBy { it.position }

        assertEquals(SeasonOutcome.CAMPIONE, serieB[0].outcome)
        assertEquals(SeasonOutcome.PROMOSSO, serieB[1].outcome)
        assertEquals(SeasonOutcome.RETROCESSO, serieB[2].outcome)
        assertEquals(SeasonOutcome.RETROCESSO, serieB[3].outcome)
    }

    // ------------------------------------------------------------ la prima divisione

    /**
     * La serpentina distribuisce la forza, non la concentra.
     *
     * Con l'ordine di forza in ingresso, spezzare a blocchi darebbe una Serie C fatta solo
     * di deboli: si comincerebbe gia' sapendo come finisce. A serpentina ogni divisione
     * riceve una squadra forte, una media e una debole.
     */
    @Test
    fun `la divisione iniziale distribuisce le squadre a serpentina`() {
        val forza = clubs(1, 9)
        val livelli = SeasonEnd.split(forza, 3)

        // Prime tre: una per divisione.
        assertEquals(1, livelli.getValue(ClubId(1)))
        assertEquals(2, livelli.getValue(ClubId(2)))
        assertEquals(3, livelli.getValue(ClubId(3)))
        // Il giro dopo risale: quarta in C, quinta in B, sesta in A.
        assertEquals(3, livelli.getValue(ClubId(4)))
        assertEquals(2, livelli.getValue(ClubId(5)))
        assertEquals(1, livelli.getValue(ClubId(6)))
    }

    @Test
    fun `ogni divisione riceve lo stesso numero di squadre`() {
        val livelli = SeasonEnd.split(clubs(1, 18), 3)
        val perLivello = livelli.values.groupingBy { it }.eachCount()

        assertEquals(mapOf(1 to 6, 2 to 6, 3 to 6), perLivello)
    }

    @Test
    fun `con una sola divisione stanno tutti al primo livello`() {
        val livelli = SeasonEnd.split(clubs(1, 7), 1)
        assertTrue(livelli.values.all { it == 1 })
    }

    // ------------------------------------------------------- applicare gli esiti

    /**
     * Chi sale sale, chi scende scende, e i numeri tornano.
     *
     * E' l'invariante che conta: se una stagione la Serie A finisse con un club in piu' e
     * la C con uno in meno, l'anno dopo nessuno saprebbe rimetterle a posto.
     */
    @Test
    fun `applicare gli esiti non cambia la dimensione delle divisioni`() {
        val prima = SeasonEnd.settle(tre, classificheIdentiche(tre), DivisionRules())

        // Gli spareggi li vince chi e' arrivato davanti: e' l'esito piu' probabile e serve
        // solo a far muovere qualcuno.
        val playoff = prima.filter { it.outcome == SeasonOutcome.PLAYOFF }
            .groupBy { it.level }
            .mapNotNull { (_, gruppo) -> gruppo.minByOrNull { it.position }?.club }
            .toSet()
        val playout = prima.filter { it.outcome == SeasonOutcome.PLAYOUT }
            .groupBy { it.level }
            .mapNotNull { (_, gruppo) -> gruppo.minByOrNull { it.position }?.club }
            .toSet()

        val dopo = SeasonEnd.apply(prima, playoff, playout)

        assertEquals(
            tre.associate { it.level to it.clubs.size },
            dopo.values.groupingBy { it }.eachCount(),
            "le divisioni hanno cambiato dimensione: $dopo",
        )
    }

    @Test
    fun `il campione della massima divisione resta dov e`() {
        val fates = listOf(ClubFate(ClubId(1), 1, 1, SeasonOutcome.CAMPIONE))
        assertEquals(1, SeasonEnd.apply(fates).getValue(ClubId(1)))
    }

    @Test
    fun `chi vince il playoff sale, chi lo perde resta`() {
        val fates = listOf(
            ClubFate(ClubId(10), 2, 2, SeasonOutcome.PLAYOFF),
            ClubFate(ClubId(11), 2, 3, SeasonOutcome.PLAYOFF),
        )
        val dopo = SeasonEnd.apply(fates, playoffWinners = setOf(ClubId(10)))

        assertEquals(1, dopo.getValue(ClubId(10)))
        assertEquals(2, dopo.getValue(ClubId(11)))
    }

    @Test
    fun `chi vince il playout resta, chi lo perde scende`() {
        val fates = listOf(
            ClubFate(ClubId(20), 1, 5, SeasonOutcome.PLAYOUT),
            ClubFate(ClubId(21), 1, 6, SeasonOutcome.PLAYOUT),
        )
        val dopo = SeasonEnd.apply(fates, playoutWinners = setOf(ClubId(20)))

        assertEquals(1, dopo.getValue(ClubId(20)))
        assertEquals(2, dopo.getValue(ClubId(21)))
    }

    /**
     * Senza spareggi giocati, chi era in bilico non si muove.
     *
     * E' il caso che si presenta subito dopo la stagione regolare, ed e' importante che
     * produca uno stato coerente: muovere chi non ha ancora giocato lo spareggio vorrebbe
     * dire promuovere una squadra che potrebbe perderlo.
     */
    @Test
    fun `senza spareggi giocati chi e' in bilico resta dov e`() {
        val fates = listOf(
            ClubFate(ClubId(30), 2, 2, SeasonOutcome.PLAYOFF),
            ClubFate(ClubId(31), 1, 5, SeasonOutcome.PLAYOUT),
        )
        val dopo = SeasonEnd.apply(fates)

        assertEquals(2, dopo.getValue(ClubId(30)))
        assertEquals(2, dopo.getValue(ClubId(31)))
    }

    // ------------------------------------------------------------------ accoppiamenti

    /**
     * Primo contro ultimo: il piazzamento in stagione regolare deve valere qualcosa.
     *
     * Accoppiando a caso, le ultime giornate diventerebbero senza senso — se arrivare terzi
     * o quarti non cambia l'avversario, non c'e' motivo di giocarle.
     */
    @Test
    fun `gli spareggi accoppiano il migliore col peggiore`() {
        val quattro = clubs(1, 4)
        val coppie = SeasonEnd.pairings(quattro)

        assertEquals(2, coppie.size)
        assertEquals(ClubId(1), coppie[0].home)
        assertEquals(ClubId(4), coppie[0].away)
        assertEquals(ClubId(2), coppie[1].home)
        assertEquals(ClubId(3), coppie[1].away)
    }

    /** La meglio piazzata gioca in casa: e' il vantaggio che si e' guadagnata. */
    @Test
    fun `la meglio piazzata gioca in casa`() {
        SeasonEnd.pairings(clubs(1, 6)).forEach { coppia ->
            assertTrue(
                coppia.home.value < coppia.away.value,
                "in ${coppia.home}-${coppia.away} gioca in casa la peggio piazzata",
            )
        }
    }

    /** Con un numero dispari la prima passa il turno, come il bye nelle coppe. */
    @Test
    fun `con partecipanti dispari la prima passa il turno`() {
        val coppie = SeasonEnd.pairings(clubs(1, 5))

        assertEquals(2, coppie.size)
        val impegnate = coppie.flatMap { listOf(it.home, it.away) }
        assertFalse(ClubId(3) in impegnate, "la terza dovrebbe riposare, e' al centro")
        assertTrue(ClubId(1) in impegnate)
    }

    @Test
    fun `un solo partecipante non produce accoppiamenti`() {
        assertTrue(SeasonEnd.pairings(clubs(1, 1)).isEmpty())
        assertTrue(SeasonEnd.pairings(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------ passare il turno

    /**
     * Un accoppiamento senza partite giocate non promuove nessuno.
     *
     * E' il modo piu' rapido di far salire in Serie A una squadra che non ha vinto niente:
     * chiedere "chi ha passato il turno?" prima che si giochi e prendere per buona la
     * prima risposta.
     */
    @Test
    fun `senza partite giocate nessuno passa il turno`() {
        val coppie = SeasonEnd.pairings(clubs(1, 4))
        assertTrue(SeasonEnd.advance(coppie, emptyList()).isEmpty())
    }

    @Test
    fun `chi vince passa il turno`() {
        val coppie = SeasonEnd.pairings(clubs(1, 4))
        val risultati = listOf(
            FixtureResult(1, CompetitionId(9), ClubId(1), ClubId(4), 2, 0),
            FixtureResult(2, CompetitionId(9), ClubId(2), ClubId(3), 0, 1),
        )

        assertEquals(listOf(ClubId(1), ClubId(3)), SeasonEnd.advance(coppie, risultati))
    }

    /**
     * A parita' nel doppio confronto passa chi ha giocato in casa il ritorno.
     *
     * E' la meglio piazzata, e la regola piu' semplice da spiegare a venti amici: piu'
     * semplice dei gol in trasferta, e in una lega fra amici la semplicita' vale piu' della
     * fedelta' al regolamento UEFA.
     */
    @Test
    fun `nel doppio confronto in parita passa chi era in casa al ritorno`() {
        val coppie = SeasonEnd.pairings(clubs(1, 2))
        val risultati = listOf(
            // Andata in casa della seconda, ritorno in casa della prima.
            FixtureResult(1, CompetitionId(9), ClubId(2), ClubId(1), 1, 0),
            FixtureResult(2, CompetitionId(9), ClubId(1), ClubId(2), 1, 0),
        )

        assertEquals(listOf(ClubId(1)), SeasonEnd.advance(coppie, risultati))
    }

    // ------------------------------------------------------------------------ vincoli

    @Test
    fun `una divisione non accetta lo stesso club due volte`() {
        val errore = runCatching {
            Division(1, "A", listOf(ClubId(1), ClubId(2), ClubId(1)))
        }.exceptionOrNull()

        assertTrue(errore is IllegalArgumentException, "duplicato accettato: $errore")
    }

    @Test
    fun `il livello parte da uno`() {
        assertTrue(
            runCatching { Division(0, "A", clubs(1, 4)) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    @Test
    fun `uno spareggio fra meno di due squadre viene rifiutato`() {
        assertTrue(
            runCatching { DivisionRules(playoffSlots = 1) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { DivisionRules(playoutSlots = 1) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    /** Senza classifica non si inventa un ordine: vale quello di iscrizione. */
    @Test
    fun `senza classifica si usa l ordine di iscrizione`() {
        val esiti = SeasonEnd.settle(tre, emptyMap(), DivisionRules())

        assertEquals(18, esiti.size)
        assertEquals(ClubId(7), esiti.first { it.level == 2 && it.position == 1 }.club)
    }

    @Test
    fun `senza divisioni non c e niente da assegnare`() {
        assertTrue(SeasonEnd.settle(emptyList(), emptyMap(), DivisionRules()).isEmpty())
    }
}
