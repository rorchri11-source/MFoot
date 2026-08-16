package dev.mfoot.android.data

/**
 * Scrittore JSON che produce testo direttamente, senza costruire oggetti.
 *
 * ## Perche' non `org.json`
 *
 * Con milletrecento giocatori, ognuno con un oggetto annidato per i dodici attributi,
 * l'albero di `JSONObject` occupa decine di megabyte prima ancora di diventare testo — e
 * poi il testo va comunque prodotto, e poi convertito in byte: tre copie della stessa
 * cosa. Sull'emulatore il sistema ha ucciso l'app per memoria esaurita a 162 MB.
 *
 * Scrivendo su uno `StringBuilder` si passa direttamente ai ~400 KB che servono davvero.
 *
 * Non e' un JSON writer generico: fa solo quello che serve al caricamento del mondo, e
 * si fida di chi lo chiama sull'ordine delle chiamate. In cambio non alloca niente.
 */
class JsonWriter(initialCapacity: Int = 512 * 1024) {

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
        key(name); sb.append(value); needsComma = true
    }

    fun field(name: String, value: Boolean) = apply {
        key(name); sb.append(value); needsComma = true
    }

    /** Un elemento di array che e' una semplice stringa. */
    fun value(text: String) = apply {
        separator()
        quoted(text)
        needsComma = true
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
                '' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }
}
