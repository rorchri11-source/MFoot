package dev.mfoot.core.json

/**
 * Scrittore JSON che produce testo direttamente, senza costruire oggetti.
 *
 * ## Perche' non una libreria
 *
 * Sta in `core`, che non ha dipendenze: e' cosi' che la stessa libreria gira identica sul
 * telefono e sul server, senza doversi chiedere quale versione di quale parser ha vinto la
 * risoluzione delle dipendenze da una parte o dall'altra.
 *
 * Il motivo pratico e' la memoria. Con milletrecento giocatori, ognuno con un oggetto
 * annidato per i dodici attributi, un albero di oggetti JSON occupa decine di megabyte
 * prima ancora di diventare testo — e poi il testo va comunque prodotto, e poi convertito
 * in byte: tre copie della stessa cosa. Sull'emulatore il sistema ha ucciso l'app per
 * memoria esaurita a 162 MB. Scrivendo su uno `StringBuilder` si passa direttamente ai
 * ~400 KB che servono davvero.
 *
 * Non e' un writer generico: si fida di chi lo chiama sull'ordine delle chiamate. In
 * cambio non alloca niente.
 */
class JsonWriter(initialCapacity: Int = 8 * 1024) {

    private val sb = StringBuilder(initialCapacity)
    private var needsComma = false

    fun beginObject() = apply {
        separator()
        sb.append('{')
        needsComma = false
    }

    fun endObject() = apply {
        sb.append('}')
        needsComma = true
    }

    fun beginArray() = apply {
        separator()
        sb.append('[')
        needsComma = false
    }

    fun endArray() = apply {
        sb.append(']')
        needsComma = true
    }

    /** Apre un oggetto come valore di una chiave. */
    fun objectField(name: String) = apply {
        key(name)
        sb.append('{')
        needsComma = false
    }

    fun arrayField(name: String) = apply {
        key(name)
        sb.append('[')
        needsComma = false
    }

    fun field(name: String, value: String?) = apply {
        key(name)
        if (value == null) sb.append("null") else quoted(value)
        needsComma = true
    }

    fun field(name: String, value: Int) = apply {
        key(name); sb.append(value); needsComma = true
    }

    fun field(name: String, value: Long) = apply {
        key(name); sb.append(value); needsComma = true
    }

    fun field(name: String, value: Double) = apply {
        key(name); number(value); needsComma = true
    }

    fun field(name: String, value: Boolean) = apply {
        key(name); sb.append(value); needsComma = true
    }

    /** Un elemento di array. */
    fun value(text: String) = apply {
        separator(); quoted(text); needsComma = true
    }

    fun value(number: Int) = apply {
        separator(); sb.append(number); needsComma = true
    }

    fun value(number: Double) = apply {
        separator(); number(number); needsComma = true
    }

    /** JSON gia' pronto, inserito cosi' com'e'. */
    fun rawField(name: String, json: String) = apply {
        key(name); sb.append(json); needsComma = true
    }

    override fun toString(): String = sb.toString()

    val length: Int get() = sb.length

    private fun key(name: String) {
        separator()
        quoted(name)
        sb.append(':')
    }

    private fun separator() {
        if (needsComma) sb.append(',')
        needsComma = false
    }

    /**
     * I decimali senza notazione esponenziale.
     *
     * `1.0E-4` e' JSON valido e Postgres lo legge, ma un file di configurazione va anche
     * letto da un essere umano quando qualcosa non torna.
     */
    private fun number(value: Double) {
        if (value.isNaN() || value.isInfinite()) {
            sb.append("null")
            return
        }
        if (value == StrictMath.floor(value) && StrictMath.abs(value) < 1e15) {
            sb.append(value.toLong()).append(".0")
        } else {
            sb.append(java.math.BigDecimal(value).toPlainString().trimEnd('0').trimEnd('.'))
        }
    }

    /**
     * Escape secondo RFC 8259.
     *
     * I nomi generati sono innocui, ma un nickname scritto dall'utente puo' contenere
     * qualsiasi cosa: senza escape, una virgoletta rompe l'intero caricamento.
     */
    private fun quoted(text: String) {
        sb.append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (c < ' ') {
                    sb.append("\\u").append(HEX[(c.code shr 12) and 0xF])
                        .append(HEX[(c.code shr 8) and 0xF])
                        .append(HEX[(c.code shr 4) and 0xF])
                        .append(HEX[c.code and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}
