package dev.mfoot.core.world

import dev.mfoot.core.rng.MathX

/**
 * Quanta parte del proprio potenziale un giocatore ha gia' realizzato, data l'eta'.
 *
 * ## Perche' questo modello e' meglio di "genera un overall e poi un'eta'"
 *
 * Se overall ed eta' si estraessero indipendentemente, il mondo si riempirebbe di
 * diciassettenni gia' da 88 e di trentacinquenni da 90: assurdo, e soprattutto il
 * meccanismo dello scouting non avrebbe senso.
 *
 * Invece si genera prima il **potenziale** (il tetto che quel giocatore puo'
 * raggiungere) e da li' discende l'overall attuale. Il risultato viene giusto da solo:
 * i giovani sono grezzi ma possono diventare qualcosa, i venticinquenni sono vicini al
 * loro massimo, i veterani stanno scendendo. Ed e' esattamente cio' che rende
 * interessante puntare all'asta su un diciannovenne.
 */
object DevelopmentCurve {

    /**
     * Frazione del potenziale realizzata a una data eta'.
     *
     * Il picco e' fra 27 e 28: prima si sale, dopo si scende. Il declino accelera
     * progressivamente, cosi' un trentaquattrenne perde piu' in fretta di un
     * trentaduenne.
     */
    private val byAge: Map<Int, Double> = mapOf(
        16 to 0.560, 17 to 0.610, 18 to 0.670, 19 to 0.730, 20 to 0.790,
        21 to 0.840, 22 to 0.885, 23 to 0.925, 24 to 0.955, 25 to 0.978,
        26 to 0.992, 27 to 1.000, 28 to 1.000, 29 to 0.992, 30 to 0.982,
        31 to 0.968, 32 to 0.948, 33 to 0.920, 34 to 0.885, 35 to 0.845,
        36 to 0.800, 37 to 0.755, 38 to 0.710, 39 to 0.665, 40 to 0.620,
    )

    private val minAge = byAge.keys.min()
    private val maxAge = byAge.keys.max()

    fun realizedFraction(age: Int): Double =
        byAge[age.coerceIn(minAge, maxAge)] ?: 1.0

    /**
     * Quanto margine di crescita resta, da 0 (nessuno) a 1 (tutto).
     *
     * Usato per decidere quanto e' larga la forbice di potenziale: piu' margine resta,
     * meno si puo' sapere di come andra' a finire.
     *
     * **Solo verso l'alto.** Superato il picco il valore e' zero, non "un po'": un
     * trentatreenne non ha nessun margine di crescita, sta calando. Senza questo taglio,
     * la formula basata sulla sola distanza dal picco restituirebbe un margine positivo
     * anche per i veterani, e la scheda finirebbe per promettere che un
     * trentatreenne da 80 puo' arrivare a 89.
     */
    fun remainingUpside(age: Int): Double =
        if (age >= PEAK_AGE) 0.0
        else ((1.0 - realizedFraction(age)) / 0.44).coerceIn(0.0, 1.0)

    /** L'eta' oltre la quale non si cresce piu'. */
    const val PEAK_AGE = 27

    /** L'eta' e' ancora in fase di crescita? */
    fun isGrowing(age: Int): Boolean = age < 28

    /** L'eta' e' gia' in fase di declino? */
    fun isDeclining(age: Int): Boolean = age > 28

    /**
     * Overall attuale a partire dal potenziale e dall'eta'.
     *
     * [noise] permette di avere giocatori in anticipo o in ritardo sulla tabella di
     * marcia: due diciannovenni con lo stesso potenziale non devono avere lo stesso
     * overall, o il mondo sembra uscito da un foglio di calcolo.
     */
    fun currentOverall(potential: Int, age: Int, noise: Double = 0.0): Int {
        val fraction = (realizedFraction(age) + noise).coerceIn(0.35, 1.0)
        return StrictMath.round(potential * fraction).toInt().coerceIn(1, 99)
    }

    /**
     * Ampiezza della forbice di potenziale reale, in punti di overall.
     *
     * Un diciassettenne puo' davvero finire ovunque; un ventottenne e' gia' arrivato.
     * La forbice si stringe con l'eta' e non e' mai zero: nemmeno il gioco sa
     * esattamente dove si fermera' la crescita.
     */
    fun potentialSpread(age: Int, minSpread: Int, maxSpread: Int): Int {
        val upside = remainingUpside(age).coerceIn(0.0, 1.0)
        return StrictMath.round(MathX.lerp(minSpread.toDouble(), maxSpread.toDouble(), upside)).toInt()
    }
}
