package dev.mfoot.core.model

/**
 * I singoli attributi di un giocatore, scala 1-99.
 *
 * Gli attributi da portiere hanno senso solo per i portieri: nei giocatori di movimento
 * restano bassi e non entrano nel calcolo dell'overall (vedi [PositionProfile]).
 */
enum class Attr(val label: String, val goalkeeperOnly: Boolean = false) {
    TIRO("Tiro"),
    DRIBBLING("Dribbling"),
    TECNICA("Tecnica"),
    PASSAGGIO("Passaggio"),
    FISICO("Fisico"),
    VELOCITA("Velocità"),
    DIFESA("Difesa"),
    INTERCETTAZIONE("Intercettazione"),
    POSIZIONAMENTO("Posizionamento"),
    PARATA("Parata", goalkeeperOnly = true),
    USCITA("Uscita", goalkeeperOnly = true),
    RIFLESSI("Riflessi", goalkeeperOnly = true);

    companion object {
        val outfield: List<Attr> = entries.filterNot { it.goalkeeperOnly }
        val goalkeeper: List<Attr> = entries.filter { it.goalkeeperOnly }
    }
}

/**
 * Insieme immutabile degli attributi di un giocatore.
 *
 * Internamente e' un `IntArray` indicizzato sull'ordinale di [Attr]: la crescita deve
 * poter alzare "un attributo scelto fra quelli del ruolo" senza un `when` su dodici casi,
 * e la generazione del mondo ne crea qualche migliaio per lega.
 */
class Attributes private constructor(private val v: IntArray) {

    init {
        require(v.size == SIZE) { "attesi $SIZE attributi, ricevuti ${v.size}" }
    }

    operator fun get(attr: Attr): Int = v[attr.ordinal]

    /** Copia con [attr] portato a [value], troncato all'intervallo valido. */
    fun with(attr: Attr, value: Int): Attributes {
        val copy = v.copyOf()
        copy[attr.ordinal] = value.coerceIn(MIN, MAX)
        return Attributes(copy)
    }

    /** Copia con [attr] aumentato di [delta] (anche negativo, per il declino). */
    fun plus(attr: Attr, delta: Int): Attributes = with(attr, this[attr] + delta)

    /** Media semplice degli attributi indicati. Restituisce 0.0 se la lista e' vuota. */
    fun mean(attrs: Collection<Attr>): Double =
        if (attrs.isEmpty()) 0.0 else attrs.sumOf { this[it] }.toDouble() / attrs.size

    /** Media pesata: e' cosi' che si calcola l'overall e il rating di una zona. */
    fun weightedMean(weights: Map<Attr, Double>): Double {
        var num = 0.0
        var den = 0.0
        for ((attr, w) in weights) {
            num += this[attr] * w
            den += w
        }
        return if (den == 0.0) 0.0 else num / den
    }

    fun toMap(): Map<Attr, Int> = Attr.entries.associateWith { this[it] }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Attributes && v.contentEquals(other.v))

    override fun hashCode(): Int = v.contentHashCode()

    override fun toString(): String =
        Attr.entries.joinToString(", ", "Attributes(", ")") { "${it.label}=${this[it]}" }

    companion object {
        const val MIN = 1
        const val MAX = 99
        private val SIZE = Attr.entries.size

        /** Tutti gli attributi allo stesso valore. Comodo nei test. */
        fun uniform(value: Int): Attributes =
            Attributes(IntArray(SIZE) { value.coerceIn(MIN, MAX) })

        /** Costruisce dagli attributi indicati; quelli non citati partono da [default]. */
        fun of(default: Int = 40, vararg pairs: Pair<Attr, Int>): Attributes {
            val arr = IntArray(SIZE) { default.coerceIn(MIN, MAX) }
            for ((attr, value) in pairs) arr[attr.ordinal] = value.coerceIn(MIN, MAX)
            return Attributes(arr)
        }

        fun fromMap(values: Map<Attr, Int>, default: Int = 40): Attributes {
            val arr = IntArray(SIZE) { default.coerceIn(MIN, MAX) }
            for ((attr, value) in values) arr[attr.ordinal] = value.coerceIn(MIN, MAX)
            return Attributes(arr)
        }
    }
}
