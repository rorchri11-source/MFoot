package dev.mfoot.core.model

/**
 * Una cifra di denaro, in **migliaia**.
 *
 * ## Perche' il migliaio e non l'euro, e perche' non un decimale
 *
 * `700` = 700K, `1500` = 1,5M, `100000` = 100M. Nessuna cifra del gioco ha senso sotto il
 * migliaio, quindi tenere tre zeri in piu' su ogni intero significherebbe solo occasioni
 * di sbagliare.
 *
 * Un budget in `Double` porterebbe arrotondamenti che non tornano fra client e server, e
 * un'asta in cui il prezzo mostrato non coincide con quello addebitato e' il difetto
 * peggiore possibile in un gioco di soldi. Con un `Int` la somma di due cifre e' la stessa
 * su qualunque macchina, per sempre.
 *
 * ## Perche' e' una value class
 *
 * A runtime resta un `Int` senza allocazione, ma il compilatore impedisce di passare un
 * numero di giornate dove serve un prezzo. Sul database la colonna resta un intero e non
 * cambia nome: cambia solo cosa quell'intero significa.
 */
@JvmInline
value class Money(val thousands: Int) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(thousands + other.thousands)

    operator fun minus(other: Money): Money = Money(thousands - other.thousands)

    /**
     * Il risultato si arrotonda al migliaio piu' vicino.
     *
     * Il troncamento renderebbe `meta' di 3` uguale a `1`, e una divisione di premi
     * finirebbe per perdere crediti per strada senza che nessuno capisca dove.
     */
    operator fun times(factor: Double): Money =
        Money(StrictMath.round(thousands * factor).toInt())

    operator fun unaryMinus(): Money = Money(-thousands)

    fun coerceAtLeast(other: Money): Money = if (thousands >= other.thousands) this else other

    fun coerceAtMost(other: Money): Money = if (thousands <= other.thousands) this else other

    override fun compareTo(other: Money): Int = thousands.compareTo(other.thousands)

    /**
     * La forma leggibile: `0`, `450K`, `1M`, `1,5M`, `18,5M`, `120M`, `1,25Mrd`.
     *
     * Il separatore decimale e' la virgola perche' il gioco e' in italiano, e il numero
     * di decimali cresce con l'unita': su un milione il decimo di milione e' l'ordine di
     * grandezza di un'offerta, su un miliardo lo e' il centesimo.
     */
    fun format(): String = render(millionDecimals = 1, billionDecimals = 2)

    /**
     * Forma compatta per le liste strette: la parte decimale sopravvive solo quando la
     * parte intera e' una cifra sola.
     *
     * `1,5M` resta `1,5M`, `700K` resta `700K`, ma `18,5M` diventa `19M`: in una colonna
     * accanto al nome e al ruolo la mezza cifra costa piu' spazio di quanta informazione
     * porti.
     */
    fun formatShort(): String = render(millionDecimals = 1, billionDecimals = 1, onlyIfSingleDigit = true)

    override fun toString(): String = format()

    // ------------------------------------------------------------------ formattazione

    private fun render(
        millionDecimals: Int,
        billionDecimals: Int,
        onlyIfSingleDigit: Boolean = false,
    ): String {
        if (thousands == 0) return "0"
        val negative = thousands < 0
        // Il valore assoluto su Long: `Int.MIN_VALUE` non ha un opposto rappresentabile.
        val abs = StrictMath.abs(thousands.toLong())
        val body = when {
            abs < PER_MILLION -> "${abs}K"
            abs < PER_BILLION -> scaled(abs, PER_MILLION, millionDecimals, onlyIfSingleDigit)
            else -> scaled(abs, PER_BILLION, billionDecimals, onlyIfSingleDigit)
        }
        return if (negative) "-$body" else body
    }

    private fun scaled(abs: Long, divisor: Long, decimals: Int, onlyIfSingleDigit: Boolean): String {
        // Se il decimale non verra' mostrato, va **arrotondato via, non tagliato**: 18,5M
        // in forma compatta e' 19M, non 18M. Troncare farebbe apparire ogni cifra un po'
        // piu' piccola del vero, e su una colonna di prezzi l'errore si nota.
        val effectiveDecimals =
            if (onlyIfSingleDigit && abs / divisor >= 10) 0 else decimals

        val factor = POW10[effectiveDecimals]
        val units = (abs * factor + divisor / 2) / divisor
        val whole = units / factor
        val frac = (units % factor).toInt()

        // L'arrotondamento puo' far scattare l'unita' successiva: 999.999 migliaia
        // arrotondati al decimo di milione fanno 1000,0M, che si scrive 1Mrd.
        if (whole >= 1000 && divisor == PER_MILLION) {
            return scaled(abs, PER_BILLION, decimals, onlyIfSingleDigit)
        }

        val suffix = if (divisor == PER_MILLION) "M" else "Mrd"
        val showFraction = frac != 0 && !(onlyIfSingleDigit && whole >= 10)
        if (!showFraction) return "$whole$suffix"

        val digits = frac.toString().padStart(effectiveDecimals, '0').trimEnd('0')
        return "$whole,$digits$suffix"
    }

    companion object {
        val ZERO = Money(0)

        private const val PER_MILLION = 1_000L
        private const val PER_BILLION = 1_000_000L
        private val POW10 = longArrayOf(1L, 10L, 100L, 1_000L)

        fun thousands(value: Int): Money = Money(value)

        fun millions(value: Double): Money = Money(StrictMath.round(value * PER_MILLION).toInt())

        /**
         * Legge quello che l'utente scrive nel campo del denaro: `1,5M`, `1.5M`, `1500`,
         * `700K`, `700k`, `1,25Mrd`. Null se non e' un numero.
         *
         * ## Perche' accetta tutte queste forme
         *
         * Perche' le scrivono tutte. Chi viene dal fantacalcio digita `1500`, chi pensa in
         * milioni digita `1,5M`, chi ha la tastiera americana digita `1.5M`. Rifiutare una
         * di queste forme significa un utente che non capisce cosa ha sbagliato e che
         * rinuncia a fare l'offerta.
         *
         * Un numero senza unita' e' in **migliaia**, come ogni cifra del sistema: `1500`
         * vale 1,5M e non 1500 euro.
         */
        fun parse(text: String): Money? {
            val clean = text.trim().replace(" ", "")
            if (clean.isEmpty()) return null

            val negative = clean.startsWith("-")
            val unsigned = clean.removePrefix("-").removePrefix("+")
            if (unsigned.isEmpty()) return null

            val upper = unsigned.uppercase()
            val (digits, multiplier) = when {
                upper.endsWith("MRD") -> upper.dropLast(3) to PER_BILLION
                upper.endsWith("M") -> upper.dropLast(1) to PER_MILLION
                upper.endsWith("K") -> upper.dropLast(1) to 1L
                else -> upper to 1L
            }
            if (digits.isEmpty()) return null

            // La virgola e il punto sono lo stesso separatore: nessuno deve indovinare
            // quale si aspetta il campo.
            val normalized = digits.replace(',', '.')
            if (normalized.count { it == '.' } > 1) return null
            if (normalized.any { !it.isDigit() && it != '.' }) return null

            val value = normalized.toDoubleOrNull() ?: return null
            val thousands = StrictMath.round(value * multiplier)
            if (thousands > Int.MAX_VALUE) return null

            return Money((if (negative) -thousands else thousands).toInt())
        }
    }
}
