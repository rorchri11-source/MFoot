package dev.mfoot.core.ai

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.model.Club
import dev.mfoot.core.model.Money
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Il mercato iniziale, dal principio alla fine.
 *
 * ## Perche' questo test esiste
 *
 * In una lega vera otto club gestiti dal computer sono rimasti con **zero, uno o due
 * giocatori in rosa** mentre tenevano aste su fuoriclasse da 86, uno con quarantadue
 * milioni impegnati su cento. Nessuna partita si e' potuta giocare, per giorni.
 *
 * Tutti i test dell'AI erano verdi, e lo sono rimasti per tutto il tempo. Il motivo e' che
 * provavano **una decisione alla volta**: "questa AI fa un'offerta?", "il tetto rispetta il
 * carattere?". Nessuno faceva la domanda che conta davvero, che non riguarda una decisione
 * ma il risultato di mille decisioni messe in fila:
 *
 * > finito il mercato, i club hanno una squadra?
 *
 * Questo test la fa. Non riproduce il tick — chiama le stesse funzioni pure che il tick
 * chiama, cosi' se un domani divergessero sarebbe il test a diventare inutile, non a
 * mentire.
 *
 * ## Cosa non simula, di proposito
 *
 * Niente orari, niente risvegli scaglionati, niente durate d'asta reali. Quelle cose
 * decidono *quanto* ci mette il mercato, e sono gia' fissate altrove; qui interessa *dove
 * va a finire*. Un giro del ciclo vale "ogni club ha avuto un'occasione di agire".
 */
class MarketSimulationTest {

    private val config = ConfigPresets.sprint(10, 8, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    /** Un club a inizio mercato: soldi pieni, rosa vuota. */
    private fun club(id: Long) = Club(
        id = ClubId(id),
        name = "Club $id",
        shortName = "C$id",
        isAi = true,
        credits = config.economy.startingCredits,
    )

    /**
     * Il mercato in miniatura.
     *
     * Ogni giro, ogni club prende **il giocatore libero che gli interessa di piu'** e lo
     * paga il proprio tetto. Chi lo vuole di piu' lo prende: e' l'esito di un'asta con
     * offerta massima automatica, senza simulare i rilanci uno per uno.
     *
     * L'obiettivo si sceglie per **gradimento**, non per prezzo: e' cosi' che lo sceglie
     * `TickRunner.tryOpenAuction`. La prima versione di questo test sceglieva il piu' caro
     * che si potesse permettere, e non era una semplificazione innocua — significava che
     * la simulazione misurava un'AI diversa da quella che gira davvero, e i suoi risultati
     * non dicevano niente sulla lega vera.
     *
     * Pagare sempre il proprio tetto invece resta volutamente pessimistico: in un'asta con
     * offerta massima si paga il secondo prezzo piu' il rilancio, quindi meno. Se le rose si
     * riempiono pagando il massimo ogni volta, a maggior ragione si riempiono davvero.
     */
    private fun simula(giri: Int = 60): List<Squadra> {
        val squadre = (1..8L).map { id ->
            Squadra(
                club = club(id),
                personality = AiPersonalityGenerator.generate(
                    ClubId(id),
                    config.setup.worldSeed,
                    config.ai,
                ),
            )
        }
        val liberi = world.players.toMutableList()

        repeat(giri) {
            // Chi ha piu' bisogno agisce per primo: e' l'effetto dello scaglionamento dei
            // risvegli, che nel tempo da' a tutti lo stesso numero di occasioni.
            val ordine = squadre.sortedBy { it.rosa.size }

            for (squadra in ordine) {
                if (squadra.rosa.size >= config.setup.minSquadSize) continue

                val obiettivo = liberi
                    .asSequence()
                    .map { p -> p to squadra.valuta(p) }
                    .filter { (_, a) -> a.isInterested && a.ceiling <= squadra.club.availableCredits }
                    .maxByOrNull { (_, a) -> a.appeal }
                    ?: continue

                val (player, appeal) = obiettivo
                val prezzo = minOf(appeal.ceiling, squadra.club.availableCredits)
                if (prezzo <= 0) continue

                liberi.remove(player)
                squadra.compra(player, prezzo)
            }
        }
        return squadre
    }

    private inner class Squadra(var club: Club, val personality: AiPersonality) {
        val rosa = mutableListOf<Player>()
        val speso = mutableListOf<Int>()

        fun valuta(player: Player): TargetAppeal {
            val state = AiState(
                personality = personality,
                nextWakeAt = java.time.Instant.EPOCH,
            )
            return AiManager.evaluate(state, club, rosa, player, config, competingAi = 0)
        }

        fun compra(player: Player, prezzo: Int) {
            rosa += player
            speso += prezzo
            club = club.copy(credits = club.credits - prezzo)
        }
    }

    // --------------------------------------------------------------- il test che serviva

    /**
     * Finito il mercato, **ogni** club ha una squadra schierabile.
     *
     * E' l'asserzione che sarebbe caduta prima della correzione, ed e' l'unica che dice se
     * il gioco si puo' giocare.
     */
    @Test
    fun `ogni club arriva al minimo di rosa`() {
        val squadre = simula()
        val minimo = config.setup.minSquadSize

        val incomplete = squadre.filter { it.rosa.size < minimo }
        assertTrue(
            incomplete.isEmpty(),
            "${incomplete.size} club su ${squadre.size} non arrivano a $minimo giocatori: " +
                incomplete.joinToString { "${it.club.name} con ${it.rosa.size}" },
        )
    }

    @Test
    fun `nessun club finisce con i crediti sotto zero`() {
        simula().forEach {
            assertTrue(
                it.club.credits >= 0,
                "${it.club.name} ha ${it.club.credits} crediti: ha speso piu' di quanto aveva",
            )
        }
    }

    /**
     * Ogni club ha un portiere.
     *
     * Con la rosa piena ma senza portiere si scende in campo con un attaccante fra i pali,
     * e la partita e' persa prima di cominciare. E' il ruolo che l'AI puo' piu' facilmente
     * dimenticare, perche' un portiere non ha mai l'overall piu' alto del listino.
     */
    @Test
    fun `ogni club ha almeno un portiere`() {
        simula().forEach { squadra ->
            assertTrue(
                squadra.rosa.any { it.primaryPosition.isGoalkeeper },
                "${squadra.club.name} non ha nessun portiere in ${squadra.rosa.size} giocatori",
            )
        }
    }

    /**
     * La forma della rosa: un paio di stelle e tanti onesti.
     *
     * E' la scelta di gioco, e questo test la **misura** invece di darla per buona. Se lo
     * sforo fosse troppo basso le rose uscirebbero piatte, tutti uguali e senza nessuno per
     * cui valga la pena guardare la partita; troppo alto e si torna al difetto di partenza.
     *
     * "Stella" qui vuol dire: costata almeno il doppio della media di quella rosa.
     */
    @Test
    fun `le rose hanno qualche stella e molti onesti`() {
        val squadre = simula()

        val conStelle = squadre.count { squadra ->
            val media = squadra.speso.average()
            squadra.speso.any { it > media * 2 }
        }
        assertTrue(
            conStelle >= squadre.size / 2,
            "solo $conStelle club su ${squadre.size} hanno un giocatore che spicca: " +
                "le rose escono tutte uguali",
        )

        // E il contrario: nessuno deve aver buttato il patrimonio su un uomo solo.
        //
        // Il confronto e' con il **budget di partenza**, non con quanto si e' speso in
        // tutto: e' la stessa misura del difetto vero, dove un club aveva impegnato
        // quarantadue milioni su cento. Rapportare al totale speso direbbe un'altra cosa —
        // un club prudente che spende poco e tiene il resto in cassa risulterebbe
        // squilibrato proprio perche' e' stato prudente.
        val budget = config.economy.startingCredits
        squadre.forEach { squadra ->
            val massimo = squadra.speso.max()
            assertTrue(
                massimo <= budget * 0.25,
                "${squadra.club.name} ha messo ${Money(massimo).formatShort()} su un " +
                    "giocatore solo, con un budget di ${Money(budget).formatShort()}",
            )
        }
    }

    // -------------------------------------------------------------- il tetto per casella

    /**
     * Il tetto sul primo acquisto e' la media per casella moltiplicata per lo sforo.
     *
     * Con cento milioni e sedici caselle la media e' 6,2 milioni: il tetto deve stare
     * dentro il quadruplo, che e' lo sforo massimo concesso al carattere piu' spregiudicato.
     * Prima era il 45% del budget — quarantacinque milioni — e da li' e' venuto tutto il
     * resto.
     */
    @Test
    fun `a rosa vuota il tetto e' una frazione della media per casella`() {
        val vuoto = club(1)
        val minimo = config.setup.minSquadSize
        val mediaPerCasella = config.economy.startingCredits.toDouble() / minimo

        val migliore = world.players.maxBy { it.overall }
        val valore = Valuation.marketValue(migliore, config)

        (1..8L).forEach { id ->
            val personality = AiPersonalityGenerator.generate(
                ClubId(id),
                config.setup.worldSeed,
                config.ai,
            )
            val tetto = AiManager.ceilingFor(
                personality = personality,
                club = vuoto,
                estimatedValue = valore,
                appeal = 1.5,
                config = config,
                squadSize = 0,
            )
            assertTrue(
                tetto <= mediaPerCasella * 4,
                "con la rosa vuota il club $id arriva a $tetto, oltre il quadruplo della " +
                    "media per casella (${mediaPerCasella.toInt()})",
            )
        }
    }

    /**
     * A rosa completa il tetto torna quello di sempre.
     *
     * Sopra il minimo non c'e' nessun obbligo da proteggere: spendere molto su un rinforzo
     * e' una scelta legittima. Se il vincolo restasse acceso, un club a rosa piena non
     * potrebbe piu' comprare niente di importante per tutta la stagione.
     */
    @Test
    fun `con la rosa completa il vincolo per casella si spegne`() {
        val ricco = club(1)
        val personality = AiPersonalityGenerator.generate(
            ClubId(1),
            config.setup.worldSeed,
            config.ai,
        )
        val valore = Valuation.marketValue(world.players.maxBy { it.overall }, config)

        val aRosaVuota = AiManager.ceilingFor(personality, ricco, valore, 1.5, config, squadSize = 0)
        val aRosaPiena = AiManager.ceilingFor(
            personality, ricco, valore, 1.5, config,
            squadSize = config.setup.minSquadSize,
        )

        assertTrue(
            aRosaPiena > aRosaVuota,
            "a rosa piena il tetto ($aRosaPiena) non e' piu' alto che a rosa vuota ($aRosaVuota)",
        )
    }

    /**
     * Restano sempre i soldi per le caselle che mancano.
     *
     * Il tetto da solo non basta: spendendo il massimo a ogni colpo si arriva a
     * diciassette giocatori e zero crediti, cioe' lo stesso difetto spostato piu' avanti.
     * Questa e' la riserva.
     */
    @Test
    fun `il tetto lascia sempre da comprare le caselle che restano`() {
        val personality = AiPersonalityGenerator.generate(
            ClubId(1),
            config.setup.worldSeed,
            config.ai,
        )
        val valore = Valuation.marketValue(world.players.maxBy { it.overall }, config)
        val minimo = config.setup.minSquadSize

        // Un club quasi al verde con ancora tre caselle da riempire.
        val allaFrutta = club(1).copy(credits = config.market.minimumRaise * 10)
        val tetto = AiManager.ceilingFor(
            personality, allaFrutta, valore, 1.5, config,
            squadSize = minimo - 3,
        )

        val restano = allaFrutta.availableCredits - tetto
        assertTrue(
            restano >= config.market.minimumRaise * 2,
            "dopo aver speso $tetto restano $restano: non bastano per le altre due caselle",
        )
    }
}
