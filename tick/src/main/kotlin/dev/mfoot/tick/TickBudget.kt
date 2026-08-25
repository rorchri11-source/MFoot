package dev.mfoot.tick

import java.time.Duration
import java.time.Instant

/**
 * Il tempo che questo giro ha a disposizione prima che GitHub lo ammazzi.
 *
 * ## Il difetto che questo file corregge, misurato
 *
 * Il giro del 2026-08-25 aveva `timeout-minutes: 10` sul lavoro di GitHub Actions, e su
 * venti esecuzioni consecutive **tredici** finivano `cancelled` a dieci minuti e venti
 * secondi esatti dall'avvio. Non era un guasto casuale: era il cronometro del runner che
 * uccideva il processo.
 *
 * E il processo ucciso non lasciava niente. `TickRunner` elabora **una lega per
 * transazione** e fa `commit` solo alla fine di `runLeague`: se il runner stacca la spina
 * a meta', Postgres annulla tutto quello che quel giro aveva fatto. Partite simulate,
 * acquisti delle AI, stipendi, colloqui — tutto indietro, e `last_processed_at` fermo.
 *
 * Da fuori si vedeva questo: «le AI comprano una volta al giorno». Non erano lente: due
 * volte su tre il loro acquisto veniva annullato da un cronometro.
 *
 * ## Perche' un budget e non solo un timeout piu' lungo
 *
 * Il timeout piu' lungo serve e c'e' (venti minuti), ma da solo sposta il problema:
 * qualunque numero si scelga, una lega abbastanza grande prima o poi lo supera, e il
 * giorno in cui succede si perde di nuovo tutto senza capire perche'.
 *
 * Il budget invece rende la fine **una decisione del tick**. Quando il tempo sta per
 * scadere il tick smette di *cominciare* cose nuove, chiude quello che ha in mano e fa
 * `commit`. Il lavoro fatto resta scritto, e il giro dopo riprende da li': il tick e' gia'
 * costruito per recuperare gli intervalli persi, e questa e' esattamente quella strada.
 *
 * ## Perche' non interrompe a meta'
 *
 * Perche' un'operazione a meta' e' peggio di una non fatta: un'asta assegnata senza
 * addebito, una partita salvata senza presenze. [consentito] si chiede **prima** di
 * iniziare qualcosa, mai durante. L'atomicita' resta quella di sempre.
 */
class TickBudget(
    private val startedAt: Instant,
    /** Quanto dura in tutto il giro, riserva compresa. */
    private val totale: Duration,
    /**
     * Il margine che si tiene da parte per chiudere.
     *
     * Non e' arbitrario: dopo l'ultima cosa fatta restano il `commit`, la consegna delle
     * notifiche e lo spegnimento della JVM. Senza riserva il budget scadrebbe proprio
     * mentre si scrive, che e' il caso che si sta cercando di evitare.
     */
    private val riserva: Duration = Duration.ofSeconds(45),
    private val orologio: () -> Instant = Instant::now,
) {

    /** Quanto e' passato dall'avvio. */
    fun trascorso(): Duration = Duration.between(startedAt, orologio())

    /** Quanto resta prima della riserva. Mai negativo. */
    fun rimanente(): Duration {
        val resto = totale.minus(riserva).minus(trascorso())
        return if (resto.isNegative) Duration.ZERO else resto
    }

    /**
     * C'e' ancora tempo per cominciare qualcosa?
     *
     * @param costoStimato quanto si prevede che duri. Chi non lo sa passa zero, e allora
     *   la domanda diventa semplicemente «siamo ancora fuori dalla riserva?».
     *
     * Il controllo su [scaduto] non e' ridondante: senza, chiamarla con costo zero dentro
     * la riserva risponderebbe «si'» — zero secondi rimasti sono comunque `>= 0` — cioe'
     * darebbe il via libera proprio nel momento in cui il budget esiste per non darlo.
     */
    fun consentito(costoStimato: Duration = Duration.ZERO): Boolean =
        !scaduto && rimanente() >= costoStimato

    /** Finito il tempo utile. */
    val scaduto: Boolean get() = rimanente().isZero

    fun descrivi(): String =
        "${trascorso().toSeconds()}s usati, ${rimanente().toSeconds()}s utili rimasti"

    companion object {
        /**
         * Il budget preso dall'ambiente.
         *
         * `MFOOT_BUDGET_SECONDS` lo decide il file del workflow, dove sta anche
         * `timeout-minutes`: i due numeri devono muoversi insieme, e tenerli nello stesso
         * file e' l'unico modo perche' resti vero.
         */
        fun fromEnv(
            startedAt: Instant,
            getenv: (String) -> String? = System::getenv,
        ): TickBudget {
            val secondi = getenv("MFOOT_BUDGET_SECONDS")?.toLongOrNull()?.takeIf { it > 0 }
                ?: DEFAULT_SECONDS
            return TickBudget(startedAt, Duration.ofSeconds(secondi))
        }

        /**
         * Quindici minuti, contro i venti di `timeout-minutes`.
         *
         * Il divario di cinque minuti e' la differenza fra «il tick decide di fermarsi» e
         * «il runner lo ammazza». Deve restare largo: comprende la costruzione del jar,
         * che avviene prima che questo cronometro parta.
         */
        const val DEFAULT_SECONDS = 900L
    }
}

/**
 * Cronometra le fasi del giro e le stampa.
 *
 * ## Perche' esisteva un problema di velocita' che nessuno sapeva dove fosse
 *
 * Perche' il registro diceva solo «Terminato in 405000 ms». Quattrocento secondi di cosa,
 * non lo diceva nessuno, e senza quel dato l'unica strada e' indovinare quale pezzo sia
 * lento — che e' come si finisce per ottimizzare la funzione sbagliata.
 *
 * Il costo di misurare e' una `System.nanoTime` per fase: nulla, rispetto a una qualunque
 * andata e ritorno verso il database.
 */
class Cronometro {

    private val tempi = LinkedHashMap<String, Long>()

    /** Esegue [blocco] misurandolo, e restituisce quello che ha prodotto. */
    fun <T> fase(nome: String, blocco: () -> T): T {
        val inizio = System.nanoTime()
        try {
            return blocco()
        } finally {
            tempi[nome] = (tempi[nome] ?: 0L) + (System.nanoTime() - inizio)
        }
    }

    /** Le fasi in ordine di durata, dalla piu' lenta. Solo quelle sopra la soglia. */
    fun riepilogo(sogliaMillis: Long = 200): String =
        tempi.entries
            .map { it.key to it.value / 1_000_000 }
            .filter { it.second >= sogliaMillis }
            .sortedByDescending { it.second }
            .joinToString(", ") { "${it.first} ${it.second}ms" }
            .ifEmpty { "tutte le fasi sotto ${sogliaMillis}ms" }

    fun totaleMillis(): Long = tempi.values.sum() / 1_000_000
}
