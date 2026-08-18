package dev.mfoot.core.ai

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.model.Club
import dev.mfoot.core.model.ClubId
import dev.mfoot.core.model.Player
import dev.mfoot.core.world.WorldGenerator
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Il **ritmo** del mercato: quante aste sono aperte insieme, e in quanti giri le rose si
 * riempiono.
 *
 * ## Perche' serviva un secondo test sul mercato
 *
 * `MarketSimulationTest` misura *dove va a finire* il mercato: alla fine ogni club ha una
 * rosa legale, un portiere, qualche stella. Tutte le sue asserzioni erano verdi mentre il
 * mercato, nella lega vera, faceva **una sola asta per volta**.
 *
 * Il difetto non stava in nessuna decisione: stava nel **ciclo** che le mette in fila.
 *
 * ```kotlin
 * val acted = tryBid(...) || tryOpenAuction(...)
 * ```
 *
 * Un `||` in corto circuito. Se esisteva anche una sola asta su cui offrire, l'AI offriva e
 * non ne apriva nessuna: sei slot liberi, nove caselle vuote, risveglio finito. Appena
 * nasceva un'asta, tutti si buttavano su quella.
 *
 * Il ciclo viveva dentro `TickRunner`, che ha bisogno di una connessione al database, e
 * quindi non era provato da niente. Adesso l'ordine delle mosse sta in [AiTurn], e questo
 * test lo esercita.
 *
 * ## Cosa simula e cosa no
 *
 * Un giro vale "ogni club ha avuto un risveglio". Le aste durano `DURATA_GIRI` giri e si
 * aggiudicano a chi le ha volute di piu'. Non ci sono orari veri ne' rilanci uno per uno:
 * quello che si misura qui e' **quante aste esistono contemporaneamente**, che e' la
 * domanda a cui nessun test rispondeva.
 */
class MarketRhythmTest {

    private val config = ConfigPresets.sprint(10, 8, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    /** Quanti giri dura un'asta prima di aggiudicarsi. */
    private val DURATA_GIRI = 3

    private class Asta(
        val target: Player,
        val apertaDa: Int,
        val scadeAlGiro: Int,
        /** Chi incassa, se e' una vendita. Null: era uno svincolato. */
        val venditore: Int? = null,
        val offerte: MutableMap<Int, Int> = mutableMapOf(),
    ) {
        var prezzoBase: Int = 0
    }

    private inner class Squadra(val indice: Int, var club: Club, val personality: AiPersonality) {
        val rosa = mutableListOf<Player>()

        val state get() = AiState(personality, Instant.EPOCH)

        fun valuta(player: Player, competing: Int) =
            AiManager.evaluate(state, club, rosa, player, config, competing)

        fun compra(player: Player, prezzo: Int) {
            rosa += player
            club = club.copy(credits = club.credits - prezzo)
        }
    }

    /** Il mercato giro per giro. Restituisce quante aste erano aperte a ogni giro. */
    private fun simula(giri: Int): Pair<List<Squadra>, List<Int>> {
        val squadre = (0 until 8).map { i ->
            Squadra(
                indice = i,
                club = Club(
                    id = ClubId(i + 1L),
                    name = "Club $i",
                    shortName = "C$i",
                    isAi = true,
                    credits = config.economy.startingCredits,
                ),
                personality = AiPersonalityGenerator.generate(
                    ClubId(i + 1L), config.setup.worldSeed, config.ai,
                ),
            )
        }

        val liberi = world.players.toMutableList()
        val aperte = mutableListOf<Asta>()
        val conteggio = mutableListOf<Int>()

        for (giro in 1..giri) {
            for (squadra in squadre.sortedBy { it.rosa.size }) {
                val mie = aperte.count { it.apertaDa == squadra.indice }

                for (mossa in AiTurn.order(squadra.rosa.size, config)) {
                    val fatto = when (mossa) {
                        AiMove.APRI_ASTA -> apri(squadra, liberi, aperte, mie, giro)
                        AiMove.OFFRI -> offri(squadra, aperte)
                        AiMove.METTI_IN_VENDITA -> vendi(squadra, aperte, mie, giro)
                        else -> false
                    }
                    if (fatto) break
                }
            }

            conteggio += aperte.size

            // Le aste scadute si aggiudicano a chi ha offerto di piu'.
            val scadute = aperte.filter { it.scadeAlGiro <= giro }
            aperte.removeAll(scadute)
            for (asta in scadute) {
                val vincitore = asta.offerte.maxByOrNull { it.value }
                if (vincitore == null) {
                    // Asta deserta. Un invenduto torna a chi lo aveva messo in vendita;
                    // uno svincolato torna sul listino. Senza questa riga la simulazione
                    // perdeva giocatori a ogni giro e le rose non potevano riempirsi — un
                    // difetto della prova, non del gioco.
                    if (asta.venditore != null) squadre[asta.venditore].rosa += asta.target
                    else liberi += asta.target
                    continue
                }
                squadre[vincitore.key].compra(asta.target, vincitore.value)
                // Il prezzo va al venditore: e' la differenza fra vendere e regalare.
                asta.venditore?.let { v ->
                    squadre[v].club = squadre[v].club.copy(
                        credits = squadre[v].club.credits + vincitore.value,
                    )
                }
            }
        }
        return squadre to conteggio
    }

    /** Apre tutte le aste che [AiTurn] consente in questo risveglio. */
    private fun apri(
        squadra: Squadra,
        liberi: MutableList<Player>,
        aperte: MutableList<Asta>,
        gia: Int,
        giro: Int,
    ): Boolean {
        val slot = AiTurn.auctionsToOpen(squadra.rosa.size, gia, config)
        if (slot <= 0) return false
        if (!AiTurn.canBuy(squadra.rosa.size, config)) return false

        var impegnato = impegnatoDi(squadra, aperte)
        val inAsta = aperte.map { it.target.id }.toSet()
        var aperteOra = 0

        repeat(slot) {
            val disponibile = squadra.club.availableCredits - impegnato
            if (disponibile <= 0) return@repeat

            val candidato = liberi.asSequence()
                .filter { it.id !in inAsta }
                .filter { p -> aperte.none { it.target.id == p.id } }
                .map { p -> p to squadra.valuta(p, competing = 0) }
                .filter { (_, a) -> a.isInterested && a.ceiling <= disponibile }
                .filter { (p, _) ->
                    AiTurn.migliora(
                        squadra.rosa.size,
                        p.overall,
                        squadra.rosa.filter { it.primaryPosition == p.primaryPosition }
                            .maxOfOrNull { it.overall },
                        config,
                    )
                }
                .maxByOrNull { (_, a) -> a.appeal }
                ?: return@repeat

            liberi.remove(candidato.first)
            val offerta = minOf(candidato.second.ceiling, disponibile)
            // Chi apre un'asta ha gia' la sua offerta dentro: l'ha aperta perche' lo
            // vuole. Nel tick succede al risveglio dopo, quando gli slot sono pieni e
            // `APRI_ASTA` cede il passo a `OFFRI`; qui si anticipa, perche' il ciclo
            // simulato non ha risvegli separati.
            aperte += Asta(candidato.first, squadra.indice, giro + DURATA_GIRI).apply {
                offerte[squadra.indice] = offerta
            }
            impegnato += offerta
            aperteOra++
        }
        return aperteOra > 0
    }

    /**
     * Mette all'asta uno dei propri, se ne ha uno che non gli serve.
     *
     * E' la mossa che tiene vivo il mercato dopo che gli svincolati sono finiti: senza,
     * l'offerta si esaurisce e non si apre piu' un'asta per il resto della stagione.
     */
    private fun vendi(
        squadra: Squadra,
        aperte: MutableList<Asta>,
        gia: Int,
        giro: Int,
    ): Boolean {
        if (gia >= config.market.maxParallelAuctionsPerClub) return false

        val (giocatore, base) = AiInitiative.playerToSell(squadra.state, squadra.rosa, config)
            ?: return false
        if (aperte.any { it.target.id == giocatore.id }) return false

        squadra.rosa.remove(giocatore)
        aperte += Asta(giocatore, squadra.indice, giro + DURATA_GIRI, venditore = squadra.indice)
            .apply { prezzoBase = base }
        return true
    }

    /**
     * Quanto ha gia' impegnato su aste aperte.
     *
     * E' il `committed_credits` del sistema vero: `place_bid` blocca i fondi al momento
     * dell'offerta, altrimenti un club potrebbe offrire il proprio massimo su sei aste e
     * vincerle tutte e sei. La prima versione di questa simulazione non lo modellava, e un
     * club finiva a meno cinquantaduemila: un difetto della prova, ma che nasconde quello
     * vero — se non lo si modella non si sa se il sistema regge.
     */
    private fun impegnatoDi(squadra: Squadra, aperte: List<Asta>): Int =
        aperte.sumOf { it.offerte[squadra.indice] ?: 0 }

    /** Offre sull'asta piu' interessante fra quelle aperte. Una sola, come fa il tick. */
    private fun offri(squadra: Squadra, aperte: List<Asta>): Boolean {
        val disponibile = squadra.club.availableCredits - impegnatoDi(squadra, aperte)
        if (disponibile <= 0) return false

        val scelta = aperte
            .filter { squadra.indice !in it.offerte }
            .filter { it.venditore != squadra.indice }
            .map { asta ->
                val competing = asta.offerte.keys.count { it != squadra.indice }
                asta to squadra.valuta(asta.target, competing)
            }
            .filter { (_, a) -> a.isInterested && a.ceiling <= disponibile }
            .maxByOrNull { (_, a) -> a.appeal }
            ?: return false

        scelta.first.offerte[squadra.indice] = minOf(scelta.second.ceiling, disponibile)
        return true
    }

    // ------------------------------------------------------------- il test che serviva

    /**
     * Al terzo giro il mercato ha molte aste aperte, non una.
     *
     * E' l'asserzione che sarebbe caduta prima della correzione: con il corto circuito, al
     * terzo giro c'era **una** asta aperta in tutta la lega, e sette club che si
     * rilanciavano sopra.
     */
    @Test
    fun `il mercato apre molte aste insieme, non una alla volta`() {
        val (_, aperte) = simula(giri = 6)

        assertTrue(
            aperte[2] >= 20,
            "al terzo giro c'erano solo ${aperte[2]} aste aperte in tutta la lega: " +
                "il mercato sta facendo la fila su una sola",
        )
    }

    @Test
    fun `il mercato non si spegne appena parte`() {
        val (_, aperte) = simula(giri = 6)

        assertTrue(
            aperte.take(4).all { it > 0 },
            "in uno dei primi giri non c'era nessuna asta aperta: $aperte",
        )
    }

    /**
     * Le rose si riempiono in poche ondate.
     *
     * Con sei aste per club e tre giri di durata, diciotto caselle sono tre ondate: qui si
     * concedono venti giri, che sono abbondantemente di piu'. Se non basta, vuol dire che
     * il ciclo si e' inceppato da qualche parte.
     */
    @Test
    fun `le rose arrivano al minimo in poche ondate`() {
        val (squadre, _) = simula(giri = 20)
        val minimo = config.setup.minSquadSize

        val corte = squadre.filter { it.rosa.size < minimo }
        assertTrue(
            corte.isEmpty(),
            "dopo venti giri ${corte.size} club sono sotto il minimo: " +
                corte.joinToString { "${it.club.name} ha ${it.rosa.size}" },
        )
    }

    /**
     * A rose piene il mercato rallenta ma non muore.
     *
     * Prima moriva davvero: `tryOpenAuction` cominciava con `if (squad.size >= minSquadSize)
     * return false`, quindi dal momento in cui un club arrivava alla rosa minima non apriva
     * piu' un'asta per il resto della stagione.
     */
    @Test
    fun `a rose piene il mercato rallenta invece di fermarsi`() {
        val (squadre, aperte) = simula(giri = 30)

        assertTrue(
            squadre.all { it.rosa.size >= config.setup.minSquadSize },
            "la simulazione non e' arrivata a rose piene, il resto non si puo' misurare",
        )

        val finali = aperte.takeLast(5)
        assertTrue(
            finali.any { it > 0 },
            "negli ultimi cinque giri non si e' aperta nessuna asta: il mercato e' morto",
        )
        assertTrue(
            finali.max() < aperte.take(6).max(),
            "a rose piene si aprono tante aste quanto durante l'allestimento: " +
                "${finali.max()} contro ${aperte.take(6).max()}",
        )
    }

    /** Nessuno spende quello che non ha, nemmeno con molte aste aperte insieme. */
    @Test
    fun `nessun club finisce con i crediti sotto zero`() {
        val (squadre, _) = simula(giri = 30)

        val rossi = squadre.filter { it.club.credits < 0 }
        assertTrue(
            rossi.isEmpty(),
            "club in rosso: " + rossi.joinToString { "${it.club.name} a ${it.club.credits}" },
        )
    }
}
