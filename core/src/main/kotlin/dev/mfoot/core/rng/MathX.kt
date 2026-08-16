package dev.mfoot.core.rng

/**
 * Funzioni matematiche per i percorsi decisionali del motore.
 *
 * Tutto passa da `StrictMath`, che per contratto produce risultati **bit-identici** su
 * qualsiasi implementazione della JVM. `Math.exp` puo' invece usare intrinseci del
 * processore e differire di un ULP fra macchine diverse: abbastanza per far finire una
 * partita 2-1 sul server e 2-2 sul telefono.
 *
 * Regola: dentro `core`, nessuna chiamata diretta a `Math.*` o a `kotlin.math.*` per
 * exp / ln / pow / sqrt nei percorsi che influenzano un risultato.
 */
object MathX {

    fun exp(x: Double): Double = StrictMath.exp(x)

    fun ln(x: Double): Double = StrictMath.log(x)

    fun sqrt(x: Double): Double = StrictMath.sqrt(x)

    fun pow(base: Double, exponent: Double): Double = StrictMath.pow(base, exponent)

    /**
     * Sigmoide logistica: trasforma una differenza di forza in una probabilita'.
     *
     * [k] regola quanto conta la differenza. k piccolo = il piu' forte vince quasi sempre,
     * k grande = tutto diventa casuale. E' la manopola principale del bilanciamento:
     * vedi la fase di taratura a forza bruta.
     */
    fun sigmoid(delta: Double, k: Double): Double {
        require(k > 0.0) { "k deve essere positivo, era $k" }
        return 1.0 / (1.0 + exp(-delta / k))
    }

    /** Interpolazione lineare fra [a] e [b]. */
    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t.coerceIn(0.0, 1.0)

    /**
     * Rimappa [value] dall'intervallo [inMin, inMax] a [outMin, outMax], con clamp.
     * Usata ovunque per convertire attributi 0-100 in moltiplicatori.
     */
    fun remap(value: Double, inMin: Double, inMax: Double, outMin: Double, outMax: Double): Double {
        if (inMax == inMin) return outMin
        val t = ((value - inMin) / (inMax - inMin)).coerceIn(0.0, 1.0)
        return outMin + t * (outMax - outMin)
    }
}
