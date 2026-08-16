package dev.mfoot.core.rng

/**
 * Generatore pseudo-casuale deterministico (xorshift64*).
 *
 * Non usiamo `kotlin.random.Random` perche' il suo algoritmo non e' parte del contratto
 * pubblico della stdlib: potrebbe cambiare fra versioni, e con esso tutti i mondi e tutte
 * le partite gia' salvate. Qui l'algoritmo e' scritto a mano su aritmetica `Long`, quindi
 * lo stesso seed produce la stessa sequenza su qualsiasi piattaforma e per sempre.
 *
 * Vedi anche [MathX]: per le funzioni trascendenti nei percorsi decisionali usiamo
 * `StrictMath`, l'unica garantita bit-identica fra JVM diverse.
 */
class DeterministicRandom(seed: Long) {

    private var state: Long = if (seed == 0L) GOLDEN else seed

    fun nextLong(): Long {
        var x = state
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        state = x
        return x * MULTIPLIER
    }

    /** Double uniforme in [0, 1). Usa i 53 bit alti, come da prassi IEEE-754. */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / TWO_POW_53

    /** Double uniforme in [min, max). */
    fun nextDouble(min: Double, max: Double): Double = min + nextDouble() * (max - min)

    /** Int uniforme in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound deve essere positivo, era $bound" }
        return (nextDouble() * bound).toInt().coerceAtMost(bound - 1)
    }

    /** Int uniforme in [min, max] — estremi inclusi, comodo per eta' e attributi. */
    fun nextIntInclusive(min: Int, max: Int): Int {
        require(max >= min) { "intervallo vuoto: [$min, $max]" }
        return min + nextInt(max - min + 1)
    }

    fun nextBoolean(): Boolean = nextLong() < 0L

    /** True con probabilita' [p]. p <= 0 e' sempre false, p >= 1 sempre true. */
    fun chance(p: Double): Boolean = when {
        p <= 0.0 -> false
        p >= 1.0 -> true
        else -> nextDouble() < p
    }

    /**
     * Normale standard approssimata con Irwin-Hall (somma di 12 uniformi meno 6).
     *
     * Preferita a Box-Muller perche' usa solo somme: nessuna funzione trascendente,
     * quindi nessun rischio di divergenza di un ULP fra piattaforme.
     */
    fun nextGaussian(): Double {
        var sum = 0.0
        repeat(12) { sum += nextDouble() }
        return sum - 6.0
    }

    /** Normale con media e deviazione date, troncata a [min, max]. */
    fun nextGaussian(mean: Double, stdDev: Double, min: Double, max: Double): Double =
        (mean + nextGaussian() * stdDev).coerceIn(min, max)

    fun <T> pick(items: List<T>): T {
        require(items.isNotEmpty()) { "non posso pescare da una lista vuota" }
        return items[nextInt(items.size)]
    }

    /** Estrazione pesata. I pesi devono essere non negativi e non tutti nulli. */
    fun <T> pickWeighted(items: List<T>, weight: (T) -> Double): T {
        require(items.isNotEmpty()) { "non posso pescare da una lista vuota" }
        val total = items.sumOf { weight(it).coerceAtLeast(0.0) }
        require(total > 0.0) { "tutti i pesi sono nulli" }
        var roll = nextDouble() * total
        for (item in items) {
            roll -= weight(item).coerceAtLeast(0.0)
            if (roll <= 0.0) return item
        }
        return items.last()
    }

    fun <T> shuffled(items: List<T>): List<T> {
        val out = items.toMutableList()
        for (i in out.indices.reversed()) {
            val j = nextInt(i + 1)
            val tmp = out[i]; out[i] = out[j]; out[j] = tmp
        }
        return out
    }

    /**
     * Deriva un generatore figlio indipendente ma riproducibile.
     *
     * Serve per isolare i flussi: generare il mondo con `fork(1)` e le partite con
     * `fork(2)` significa che aggiungere una chiamata random nella generazione del mondo
     * non sposta i risultati delle partite gia' giocate.
     */
    fun fork(salt: Long): DeterministicRandom =
        DeterministicRandom(state xor (salt * GOLDEN))

    private companion object {
        const val GOLDEN = -0x61c8864680b583ebL          // 0x9E3779B97F4A7C15
        const val MULTIPLIER = 0x2545F4914F6CDD1DL
        const val TWO_POW_53 = 9007199254740992.0        // 2^53
    }
}
