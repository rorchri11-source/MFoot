package dev.mfoot.core.objectives

import dev.mfoot.core.config.ConfigPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObjectiveEngineTest {

    private fun stagione(
        position: Int = 8,
        teams: Int = 16,
        level: Int = 1,
        promoted: Boolean = false,
        relegated: Boolean = false,
        cupWon: Boolean = false,
        best: Int = 0,
        custom: Int = 0,
        youth: Int = 0,
        finished: Boolean = true,
    ) = ClubSeason(
        position = position,
        teamsInDivision = teams,
        divisionLevel = level,
        promoted = promoted,
        relegated = relegated,
        cupWon = cupWon,
        bestOverall = best,
        customOverall = custom,
        youthPlayed = youth,
        finished = finished,
    )

    // ------------------------------------------------------------------- di classifica

    @Test
    fun `chi vince la divisione raggiunge l'obiettivo`() {
        val obiettivo = Objective(ObjectiveKind.VINCI_LA_DIVISIONE, reward = 100)
        assertEquals(
            ObjectiveStatus.RAGGIUNTO,
            ObjectiveEngine.status(obiettivo, listOf(stagione(position = 1))),
        )
        assertEquals(100, ObjectiveEngine.reward(obiettivo, ObjectiveStatus.RAGGIUNTO))
    }

    @Test
    fun `chi arriva secondo ha fallito, e non prende niente`() {
        val obiettivo = Objective(ObjectiveKind.VINCI_LA_DIVISIONE, reward = 100)
        val esito = ObjectiveEngine.status(obiettivo, listOf(stagione(position = 2)))

        assertEquals(ObjectiveStatus.FALLITO, esito)
        assertEquals(0, ObjectiveEngine.reward(obiettivo, esito))
    }

    @Test
    fun `arrivare entro l'ottavo si giudica sulla posizione`() {
        val obiettivo = Objective(ObjectiveKind.ARRIVA_ENTRO_IL, target = 8)

        assertEquals(ObjectiveStatus.RAGGIUNTO, ObjectiveEngine.status(obiettivo, listOf(stagione(position = 8))))
        assertEquals(ObjectiveStatus.FALLITO, ObjectiveEngine.status(obiettivo, listOf(stagione(position = 9))))
    }

    /** Una stagione a meta' non e' un verdetto: e' una classifica provvisoria. */
    @Test
    fun `su una stagione non finita non si decide niente`() {
        val obiettivo = Objective(ObjectiveKind.VINCI_LA_DIVISIONE)
        assertEquals(
            ObjectiveStatus.IN_CORSO,
            ObjectiveEngine.status(obiettivo, listOf(stagione(position = 12, finished = false))),
        )
    }

    // ------------------------------------------------------------------- pluriennali

    @Test
    fun `non retrocedere per due anni resta in corso dopo il primo`() {
        val obiettivo = Objective(ObjectiveKind.NON_RETROCEDERE, seasons = 2)
        assertEquals(
            ObjectiveStatus.IN_CORSO,
            ObjectiveEngine.status(obiettivo, listOf(stagione(position = 14))),
        )
    }

    @Test
    fun `non retrocedere per due anni si raggiunge al secondo`() {
        val obiettivo = Objective(ObjectiveKind.NON_RETROCEDERE, seasons = 2)
        assertEquals(
            ObjectiveStatus.RAGGIUNTO,
            ObjectiveEngine.status(obiettivo, listOf(stagione(position = 14), stagione(position = 11))),
        )
    }

    /**
     * Il caso che rende utile il tipo `FALLITO` invece di un booleano: dopo la
     * retrocessione l'obiettivo e' morto, e continuare a mostrarlo «in corso» vorrebbe dire
     * far giocare qualcuno per un premio che non prendera' mai.
     */
    @Test
    fun `una retrocessione lo fa fallire subito, senza aspettare la seconda stagione`() {
        val obiettivo = Objective(ObjectiveKind.NON_RETROCEDERE, seasons = 2)
        assertEquals(
            ObjectiveStatus.FALLITO,
            ObjectiveEngine.status(obiettivo, listOf(stagione(position = 18, relegated = true))),
        )
    }

    // --------------------------------------------------------------------- di crescita

    @Test
    fun `portare il proprio giocatore a novanta si decide appena ci arriva`() {
        val obiettivo = Objective(ObjectiveKind.FAI_CRESCERE_IL_TUO, target = 90)
        assertEquals(
            ObjectiveStatus.RAGGIUNTO,
            ObjectiveEngine.status(obiettivo, listOf(stagione(custom = 90, finished = false))),
        )
    }

    @Test
    fun `se non ci arriva entro fine stagione ha fallito`() {
        val obiettivo = Objective(ObjectiveKind.FAI_CRESCERE_IL_TUO, target = 90)
        assertEquals(
            ObjectiveStatus.FALLITO,
            ObjectiveEngine.status(obiettivo, listOf(stagione(custom = 88))),
        )
    }

    @Test
    fun `a stagione in corso e ancora sotto, resta in corso`() {
        val obiettivo = Objective(ObjectiveKind.FAI_CRESCERE_IL_TUO, target = 90)
        assertEquals(
            ObjectiveStatus.IN_CORSO,
            ObjectiveEngine.status(obiettivo, listOf(stagione(custom = 88, finished = false))),
        )
    }

    @Test
    fun `lanciare tre ragazzi si conta sui minuti giocati`() {
        val obiettivo = Objective(ObjectiveKind.LANCIA_DALLA_PRIMAVERA, target = 3)

        assertEquals(ObjectiveStatus.RAGGIUNTO, ObjectiveEngine.status(obiettivo, listOf(stagione(youth = 3))))
        assertEquals(ObjectiveStatus.FALLITO, ObjectiveEngine.status(obiettivo, listOf(stagione(youth = 2))))
    }

    @Test
    fun `senza nemmeno una stagione non si decide`() {
        assertEquals(
            ObjectiveStatus.IN_CORSO,
            ObjectiveEngine.status(Objective(ObjectiveKind.VINCI_LA_COPPA), emptyList()),
        )
    }
}

class ObjectiveBoardTest {

    private val config = ConfigPresets.sprint()

    private fun club(
        rank: Int,
        teams: Int = 12,
        level: Int = 1,
        divisions: Int = 1,
        custom: Int = 0,
        best: Int = 70,
        cup: Boolean = false,
        youth: Boolean = false,
    ) = ClubStanding(
        strengthRank = rank,
        teamsInDivision = teams,
        divisionLevel = level,
        divisionCount = divisions,
        customOverall = custom,
        bestOverall = best,
        hasCup = cup,
        hasYouth = youth,
    )

    @Test
    fun `alla squadra piu' forte si chiede di vincere`() {
        val obiettivi = ObjectiveBoard.forClub(club(rank = 1), config)
        assertEquals(ObjectiveKind.VINCI_LA_DIVISIONE, obiettivi.first().kind)
    }

    @Test
    fun `a quella di meta' classifica si chiede la meta' classifica`() {
        val obiettivi = ObjectiveBoard.forClub(club(rank = 6, teams = 12), config)
        val primo = obiettivi.first()

        assertEquals(ObjectiveKind.ARRIVA_ENTRO_IL, primo.kind)
        assertEquals(6, primo.target)
    }

    @Test
    fun `alla piu' debole con una divisione sotto si chiede di non retrocedere`() {
        val obiettivi = ObjectiveBoard.forClub(club(rank = 12, teams = 12, level = 1, divisions = 2), config)
        assertEquals(ObjectiveKind.NON_RETROCEDERE, obiettivi.first().kind)
    }

    /**
     * Il caso che si sbaglia sempre: nell'ultima divisione non c'e' niente sotto, e «non
     * retrocedere» sarebbe un premio che si incassa restando fermi.
     */
    @Test
    fun `nell'ultima divisione non si chiede di non retrocedere`() {
        val obiettivi = ObjectiveBoard.forClub(club(rank = 12, teams = 12, level = 2, divisions = 2), config)
        assertEquals(ObjectiveKind.ARRIVA_ENTRO_IL, obiettivi.first().kind)
    }

    @Test
    fun `a una squadra forte di seconda divisione si chiede di salire`() {
        val obiettivi = ObjectiveBoard.forClub(club(rank = 1, level = 2, divisions = 2), config)
        assertEquals(ObjectiveKind.SALI_DI_DIVISIONE, obiettivi.first().kind)
    }

    @Test
    fun `chi ha il suo giocatore si vede chiedere cinque punti di crescita`() {
        val obiettivi = ObjectiveBoard.forClub(club(rank = 5, custom = 66), config)
        val crescita = obiettivi.first { it.kind == ObjectiveKind.FAI_CRESCERE_IL_TUO }

        assertEquals(71, crescita.target)
    }

    @Test
    fun `chi non ce l'ha si vede chiedere di far crescere qualcun altro`() {
        val obiettivi = ObjectiveBoard.forClub(club(rank = 5, custom = 0, best = 74), config)
        val crescita = obiettivi.first { it.kind == ObjectiveKind.PORTA_UN_GIOCATORE_A }

        assertEquals(79, crescita.target)
    }

    @Test
    fun `i premi sono una percentuale del budget di partenza`() {
        val obiettivi = ObjectiveBoard.forClub(club(rank = 1), config)
        val atteso = config.economy.startingCredits * config.objectives.leagueRewardPercent / 100

        assertEquals(atteso, obiettivi.first().reward)
    }

    @Test
    fun `raddoppiando il budget raddoppiano i premi`() {
        val ricca = config.copy(
            economy = config.economy.copy(startingCredits = config.economy.startingCredits * 2),
        )
        val povero = ObjectiveBoard.forClub(club(rank = 1), config).first().reward
        val ricco = ObjectiveBoard.forClub(club(rank = 1), ricca).first().reward

        assertEquals(povero * 2, ricco)
    }

    @Test
    fun `spenti nella configurazione, non se ne assegna nessuno`() {
        val senza = config.copy(objectives = config.objectives.copy(enabled = false))
        assertTrue(ObjectiveBoard.forClub(club(rank = 1), senza).isEmpty())
    }

    @Test
    fun `senza Primavera e senza coppa il girone unico ne da' due, non tre`() {
        val obiettivi = ObjectiveBoard.forClub(club(rank = 5, divisions = 1), config)
        assertEquals(2, obiettivi.size)
    }

    @Test
    fun `con la Primavera fondata il terzo obiettivo compare`() {
        val obiettivi = ObjectiveBoard.forClub(club(rank = 5, divisions = 1, youth = true), config)

        assertEquals(3, obiettivi.size)
        assertEquals(ObjectiveKind.LANCIA_DALLA_PRIMAVERA, obiettivi.last().kind)
    }

    /**
     * Alla squadra debole di prima divisione si e' gia' chiesta la salvezza: il terzo
     * obiettivo non deve ripetere la stessa frase con un numero di stagioni diverso.
     */
    @Test
    fun `chi ha gia' la salvezza come obiettivo non se la vede chiedere due volte`() {
        val obiettivi = ObjectiveBoard.forClub(
            club(rank = 12, teams = 12, level = 1, divisions = 2, youth = true),
            config,
        )

        assertEquals(1, obiettivi.count { it.kind == ObjectiveKind.NON_RETROCEDERE })
        assertEquals(ObjectiveKind.LANCIA_DALLA_PRIMAVERA, obiettivi.last().kind)
    }

    @Test
    fun `l'obiettivo lungo dura piu' di una stagione`() {
        val obiettivi = ObjectiveBoard.forClub(club(rank = 6, level = 1, divisions = 2), config)
        val lungo = obiettivi.last()

        assertEquals(ObjectiveKind.NON_RETROCEDERE, lungo.kind)
        assertEquals(config.objectives.longTermSeasons, lungo.seasons)
    }
}
