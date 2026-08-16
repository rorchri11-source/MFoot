package dev.mfoot.core.match

import dev.mfoot.core.config.ConfigPresets
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
 */
class MatchBalanceTest {

    private val config = ConfigPresets.sprint(20, 12, LocalDate.of(2026, 9, 1))
    private val world = WorldGenerator.generate(config)

    private companion object {
        const val CAMPIONE = 1500
    }

    /**
     * Fra squadre di pari forza il vantaggio del campo deve esserci ma non decidere.
     * Riferimento del calcio vero: circa 45% casa, 27% pari, 28% trasferta.
     */
    @Test
    fun `fra squadre pari il campo pesa quanto nel calcio vero`() {
        val report = BalanceHarness.run(world, config, 75, 75, CAMPIONE)

        assertTrue(
            report.homeWinRate in 0.38..0.52,
            "vittorie in casa ${report.homeWinRate}\n${report.describe("pari")}",
        )
        assertTrue(
            report.drawRate in 0.20..0.35,
            "pareggi ${report.drawRate}\n${report.describe("pari")}",
        )
        assertTrue(
            report.awayWinRate in 0.20..0.35,
            "vittorie fuori ${report.awayWinRate}\n${report.describe("pari")}",
        )
    }

    @Test
    fun `si segna quanto in una partita vera`() {
        val report = BalanceHarness.run(world, config, 75, 75, CAMPIONE)
        assertTrue(
            report.goalsPerMatch in 2.2..3.3,
            "gol a partita ${report.goalsPerMatch}\n${report.describe("pari")}",
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
    fun `dieci punti di overall pesano ma non decidono`() {
        val inCasa = BalanceHarness.run(world, config, 80, 70, CAMPIONE)
        val inTrasferta = BalanceHarness.run(world, config, 70, 80, CAMPIONE)

        val vittorieDelPiuForte = (inCasa.homeWinRate + inTrasferta.awayWinRate) / 2.0

        assertTrue(
            vittorieDelPiuForte in 0.55..0.72,
            "la squadra con +10 di overall vince il ${(vittorieDelPiuForte * 100).toInt()}% " +
                "delle volte: fuori dalla fascia sana 55-72%\n" +
                inCasa.describe("80 in casa") + "\n" + inTrasferta.describe("80 in trasferta"),
        )
    }

    @Test
    fun `un divario piccolo lascia aperta la partita`() {
        val report = BalanceHarness.run(world, config, 78, 73, CAMPIONE)
        assertTrue(
            report.awayWinRate > 0.08,
            "con soli 5 punti di scarto la sfavorita vince solo il ${report.awayWinRate}: " +
                "troppo poco per tenere viva una lega",
        )
    }

    /**
     * Anche un divario enorme deve lasciare la porta socchiusa: e' la partita che poi
     * si racconta per settimane.
     */
    @Test
    fun `anche il divario enorme lascia spazio alla sorpresa`() {
        val report = BalanceHarness.run(world, config, 85, 65, 1000)
        assertTrue(
            report.awayWinRate + report.drawRate > 0.05,
            "contro una squadra di venti punti superiore non si strappa mai nemmeno un pari",
        )
        assertTrue(
            report.homeWinRate < 0.95,
            "il divario grande e' diventato una certezza matematica",
        )
    }

    /**
     * Se un assetto fosse sempre il migliore, la scelta tattica non esisterebbe: tutti
     * sceglierebbero quello e basta.
     */
    @Test
    fun `nessun assetto tattico domina gli altri`() {
        val catenaccio = BalanceHarness.run(
            world, config, 75, 75, 1000, homeTactics = Tactics.CATENACCIO,
        )
        val arrembante = BalanceHarness.run(
            world, config, 75, 75, 1000, homeTactics = Tactics.ARREMBANTE,
        )

        val distanza = StrictMath.abs(catenaccio.homeWinRate - arrembante.homeWinRate)
        assertTrue(
            distanza < 0.15,
            "catenaccio ${catenaccio.homeWinRate} contro arrembante ${arrembante.homeWinRate}: " +
                "uno dei due assetti e' semplicemente sbagliato da scegliere",
        )
    }

    @Test
    fun `l'assetto difensivo fa segnare meno di quello offensivo`() {
        val catenaccio = BalanceHarness.run(
            world, config, 75, 75, 1000, homeTactics = Tactics.CATENACCIO,
        )
        val arrembante = BalanceHarness.run(
            world, config, 75, 75, 1000, homeTactics = Tactics.ARREMBANTE,
        )
        assertTrue(
            arrembante.goalsPerMatch > catenaccio.goalsPerMatch,
            "le tattiche non stanno cambiando il tipo di partita",
        )
    }

    /**
     * L'allenatore deve contare, altrimenti l'asta per i cinque stelle non ha senso.
     * Ma non deve contare piu' della rosa, o il gioco diventa "compra l'allenatore".
     */
    @Test
    fun `l'allenatore conta ma meno della rosa`() {
        val report = BalanceHarness.run(
            world, config, 75, 75, 1000, homeCoachStars = 5, awayCoachStars = 1,
        )
        assertTrue(
            report.homeWinRate > 0.47,
            "quattro stelle di differenza sull'allenatore non si sentono: ${report.homeWinRate}",
        )
        assertTrue(
            report.homeWinRate < 0.65,
            "l'allenatore pesa troppo: ${report.homeWinRate}. Il gioco diventerebbe " +
                "'vince chi si compra l'allenatore'",
        )
    }

    @Test
    fun `il possesso resta realistico`() {
        val report = BalanceHarness.run(world, config, 75, 75, 500)
        assertTrue(
            report.averagePossessionHome in 0.44..0.56,
            "possesso casa ${report.averagePossessionHome}",
        )
    }

    @Test
    fun `la percentuale di conversione dei tiri e credibile`() {
        val report = BalanceHarness.run(world, config, 75, 75, 1000)
        assertTrue(
            report.conversionRate in 0.07..0.20,
            "conversione ${report.conversionRate}: nel calcio vero sta intorno al 10-12%",
        )
    }

    @Test
    fun `i risultati sono vari e non sempre lo stesso`() {
        val report = BalanceHarness.run(world, config, 75, 75, CAMPIONE)
        assertTrue(report.biggestWin >= 3, "in 1500 partite non e' mai uscita una goleada")
        assertTrue(report.biggestWin <= 12, "scarto massimo assurdo: ${report.biggestWin}")
    }
}
