package dev.mfoot.core.match

import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.model.Reparto
import dev.mfoot.core.world.WorldGenerator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * I test che dicono se il gioco e' **divertente**, non se funziona.
 *
 * Un motore puo' girare perfettamente e produrre un campionato gia' deciso al sorteggio.
 * Questi numeri sono stati misurati facendo girare migliaia di partite (vedi
 * [BalanceReportTest]) e fissati qui: se una modifica futura li rompe, si vede subito.
 *
 * Gli intervalli sono volutamente larghi. Non servono a inseguire il decimale, servono a
 * intercettare le rotture vere — il tipo di regressione per cui la squadra piu' forte
 * comincia a vincere il 90% delle partite e nessuno se ne accorge per due settimane.
 *
 * ## Perche' ogni prova gira due volte
 *
 * Perche' i motori sono due. Il livello dei duelli sostituisce **il decisore dell'azione**,
 * e riscrivere un motore tarato su migliaia di partite senza un collaudo vorrebbe dire
 * scoprire fra un mese che il campionato si e' rotto. Questo file e' la ragione per cui la
 * riscrittura si poteva fare: la banda misurata non e' un ricordo, e' un test che fallisce.
 *
 * Quando il motore vecchio verra' rimosso, `perOgniMotore` torna a una riga sola.
 */
class MatchBalanceTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    private val conDuelli: LeagueConfig =
        config.copy(engine = config.engine.copy(duelliAttivi = true))

    private fun perOgniMotore(prova: (String, LeagueConfig) -> Unit) {
        prova("motore vecchio", config.copy(engine = config.engine.copy(duelliAttivi = false)))
        prova("duelli", conDuelli)
    }

    private companion object {
        const val CAMPIONE = 1200
    }

    /**
     * Fra squadre di pari forza il vantaggio del campo deve esserci ma non decidere.
     * Riferimento del calcio vero: circa 45% casa, 27% pari, 28% trasferta.
     */
    @Test
    fun `fra squadre pari il campo pesa quanto nel calcio vero`() = perOgniMotore { nome, cfg ->
        val report = BalanceHarness.run(world, cfg, 75, 75, CAMPIONE)

        assertTrue(
            report.homeWinRate in 0.38..0.52,
            "[$nome] vittorie in casa ${report.homeWinRate}\n${report.describe(nome)}",
        )
        assertTrue(
            report.drawRate in 0.20..0.35,
            "[$nome] pareggi ${report.drawRate}\n${report.describe(nome)}",
        )
        assertTrue(
            report.awayWinRate in 0.20..0.35,
            "[$nome] vittorie fuori ${report.awayWinRate}\n${report.describe(nome)}",
        )
    }

    @Test
    fun `si segna quanto in una partita vera`() = perOgniMotore { nome, cfg ->
        val report = BalanceHarness.run(world, cfg, 75, 75, CAMPIONE)
        assertTrue(
            report.goalsPerMatch in 2.2..3.3,
            "[$nome] gol a partita ${report.goalsPerMatch}\n${report.describe(nome)}",
        )
    }

    /**
     * **Il test piu' importante del progetto.**
     *
     * Se la squadra piu' forte vincesse sempre, il campionato sarebbe deciso al mercato
     * e non varrebbe la pena giocarlo. Se vincesse a caso, costruire la rosa non
     * servirebbe a niente. La misura giusta e' la media fra casa e trasferta, per
     * togliere di mezzo il vantaggio del campo.
     */
    @Test
    fun `dieci punti di overall pesano ma non decidono`() = perOgniMotore { nome, cfg ->
        val inCasa = BalanceHarness.run(world, cfg, 80, 70, CAMPIONE)
        val inTrasferta = BalanceHarness.run(world, cfg, 70, 80, CAMPIONE)

        val vittorieDelPiuForte = (inCasa.homeWinRate + inTrasferta.awayWinRate) / 2.0

        assertTrue(
            vittorieDelPiuForte in 0.55..0.72,
            "[$nome] la squadra con +10 di overall vince il " +
                "${(vittorieDelPiuForte * 100).toInt()}% delle volte: fuori dalla fascia " +
                "sana 55-72%\n" +
                inCasa.describe("80 in casa") + "\n" + inTrasferta.describe("80 in trasferta"),
        )
    }

    @Test
    fun `un divario piccolo lascia aperta la partita`() = perOgniMotore { nome, cfg ->
        val report = BalanceHarness.run(world, cfg, 78, 73, CAMPIONE)
        assertTrue(
            report.awayWinRate > 0.08,
            "[$nome] con soli 5 punti di scarto la sfavorita vince solo " +
                "${report.awayWinRate}: troppo poco per tenere viva una lega",
        )
    }

    /**
     * Anche un divario enorme deve lasciare la porta socchiusa: e' la partita che poi
     * si racconta per settimane.
     */
    @Test
    fun `anche il divario enorme lascia spazio alla sorpresa`() = perOgniMotore { nome, cfg ->
        val report = BalanceHarness.run(world, cfg, 85, 65, 1000)
        assertTrue(
            report.awayWinRate + report.drawRate > 0.05,
            "[$nome] contro una squadra di venti punti superiore non si strappa mai " +
                "nemmeno un pari",
        )
        assertTrue(
            report.homeWinRate < 0.95,
            "[$nome] il divario grande e' diventato una certezza matematica",
        )
    }

    /**
     * Se un assetto fosse sempre il migliore, la scelta tattica non esisterebbe: tutti
     * sceglierebbero quello e basta.
     */
    @Test
    fun `nessun assetto tattico domina gli altri`() = perOgniMotore { nome, cfg ->
        val catenaccio = BalanceHarness.run(
            world, cfg, 75, 75, 1000, homeTactics = Tactics.CATENACCIO,
        )
        val arrembante = BalanceHarness.run(
            world, cfg, 75, 75, 1000, homeTactics = Tactics.ARREMBANTE,
        )

        val distanza = StrictMath.abs(catenaccio.homeWinRate - arrembante.homeWinRate)
        assertTrue(
            distanza < 0.15,
            "[$nome] catenaccio ${catenaccio.homeWinRate} contro arrembante " +
                "${arrembante.homeWinRate}: uno dei due assetti e' semplicemente sbagliato " +
                "da scegliere",
        )
    }

    @Test
    fun `l'assetto difensivo fa segnare meno di quello offensivo`() = perOgniMotore { nome, cfg ->
        val catenaccio = BalanceHarness.run(
            world, cfg, 75, 75, 1000, homeTactics = Tactics.CATENACCIO,
        )
        val arrembante = BalanceHarness.run(
            world, cfg, 75, 75, 1000, homeTactics = Tactics.ARREMBANTE,
        )
        assertTrue(
            arrembante.goalsPerMatch > catenaccio.goalsPerMatch,
            "[$nome] le tattiche non stanno cambiando il tipo di partita",
        )
    }

    /**
     * L'allenatore deve contare, altrimenti l'asta per i cinque stelle non ha senso.
     * Ma non deve contare piu' della rosa, o il gioco diventa "compra l'allenatore".
     */
    @Test
    fun `l'allenatore conta ma meno della rosa`() = perOgniMotore { nome, cfg ->
        val report = BalanceHarness.run(
            world, cfg, 75, 75, 1000, homeCoachStars = 5, awayCoachStars = 1,
        )
        assertTrue(
            report.homeWinRate > 0.47,
            "[$nome] quattro stelle di differenza sull'allenatore non si sentono: " +
                "${report.homeWinRate}",
        )
        assertTrue(
            report.homeWinRate < 0.65,
            "[$nome] l'allenatore pesa troppo: ${report.homeWinRate}. Il gioco diventerebbe " +
                "'vince chi si compra l'allenatore'",
        )
    }

    @Test
    fun `il possesso resta realistico`() = perOgniMotore { nome, cfg ->
        val report = BalanceHarness.run(world, cfg, 75, 75, 500)
        assertTrue(
            report.averagePossessionHome in 0.44..0.56,
            "[$nome] possesso casa ${report.averagePossessionHome}",
        )
    }

    @Test
    fun `la percentuale di conversione dei tiri e credibile`() = perOgniMotore { nome, cfg ->
        val report = BalanceHarness.run(world, cfg, 75, 75, 1000)
        assertTrue(
            report.conversionRate in 0.07..0.20,
            "[$nome] conversione ${report.conversionRate}: nel calcio vero sta intorno " +
                "al 10-12%",
        )
    }

    @Test
    fun `si tira quanto in una partita vera`() = perOgniMotore { nome, cfg ->
        val report = BalanceHarness.run(world, cfg, 75, 75, 1000)
        assertTrue(
            report.shotsPerMatch in 18.0..32.0,
            "[$nome] tiri a partita ${report.shotsPerMatch}\n${report.describe(nome)}",
        )
    }

    @Test
    fun `i risultati sono vari e non sempre lo stesso`() = perOgniMotore { nome, cfg ->
        val report = BalanceHarness.run(world, cfg, 75, 75, CAMPIONE)
        assertTrue(report.biggestWin >= 3, "[$nome] in $CAMPIONE partite mai una goleada")
        assertTrue(report.biggestWin <= 12, "[$nome] scarto massimo assurdo: ${report.biggestWin}")
    }

    // ------------------------------------------------------------------ chi la mette

    /**
     * **Non segnano solo gli attaccanti.**
     *
     * La misura che nasce dalla frase del proprietario — *«gol solo da quelli forti,
     * dall'attacco e basta»* — e che i numeri d'insieme non vedevano: un motore puo'
     * produrre 2,6 gol a partita facendoli segnare tutti al centravanti.
     */
    @Test
    fun `segnano tutti i reparti`() = perOgniMotore { nome, cfg ->
        val report = BalanceHarness.run(world, cfg, 75, 75, CAMPIONE)

        assertTrue(
            report.goalShare(Reparto.ATTACCO) in 0.55..0.78,
            "[$nome] l'attacco fa il ${(report.goalShare(Reparto.ATTACCO) * 100).toInt()}% " +
                "dei gol\n${report.describe(nome)}",
        )
        assertTrue(
            report.goalShare(Reparto.CENTROCAMPO) > 0.12,
            "[$nome] il centrocampo non segna\n${report.describe(nome)}",
        )
        assertTrue(
            report.goalShare(Reparto.DIFESA) > 0.06,
            "[$nome] la difesa non segna mai: e' il difetto di prima, tornato\n" +
                report.describe(nome),
        )
    }

    /**
     * Un motore in cui segnano sempre gli stessi cinque produce un capocannoniere gia'
     * scritto a settembre.
     */
    @Test
    fun `i marcatori sono tanti e diversi`() = perOgniMotore { nome, cfg ->
        val report = BalanceHarness.run(world, cfg, 75, 75, CAMPIONE)
        assertTrue(
            report.distinctScorers >= 16,
            "[$nome] solo ${report.distinctScorers} marcatori diversi su ${report.squadSize}",
        )
    }
}

/**
 * Le prove che hanno senso **solo** col motore a duelli.
 *
 * Stanno a parte perche' col motore vecchio i contatori sono tutti a zero: non
 * fallirebbero, direbbero il falso.
 */
class DuelliBalanceTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
        .let { it.copy(engine = it.engine.copy(duelliAttivi = true)) }
    private val world = WorldGenerator.generate(config)

    @Test
    fun `si dribbla quanto in una partita vera`() {
        val report = BalanceHarness.run(world, config, 75, 75, 800)
        assertTrue(
            report.dribblesPerMatch in 8.0..30.0,
            "dribbling riusciti a partita ${report.dribblesPerMatch}\n${report.describe("duelli")}",
        )
        assertTrue(
            report.dribbleSuccess in 0.35..0.62,
            "dribbling riusciti ${report.dribbleSuccess}: nel calcio vero circa uno su due",
        )
    }

    @Test
    fun `i passaggi arrivano quanto nel calcio vero`() {
        val report = BalanceHarness.run(world, config, 75, 75, 800)
        assertTrue(
            report.passAccuracy in 0.68..0.88,
            "precisione dei passaggi ${report.passAccuracy}\n${report.describe("duelli")}",
        )
    }

    /**
     * Se i duelli fossero pochi, il motore sarebbe quello vecchio con un nome diverso.
     */
    @Test
    fun `una partita e' fatta di duelli, non di due tiri di dado`() {
        val report = BalanceHarness.run(world, config, 75, 75, 200)
        assertTrue(
            report.duelsPerMatch > 120.0,
            "solo ${report.duelsPerMatch} duelli a partita",
        )
    }
}
