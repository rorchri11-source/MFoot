package dev.mfoot.core.match

import dev.mfoot.core.config.EngineConfig
import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.rng.MathX

/**
 * Le cinque contese con cui si decide un'azione.
 *
 * ## Perche' esistono
 *
 * Prima, un'azione era **un numero contro un numero**: la media di un reparto contro la
 * media del reparto specchiato, dentro una sigmoide. [ZoneRatings] schiaccia gli undici
 * in un rating per zona *prima* che accada qualcosa, e i nomi arrivavano dopo, appiccicati
 * a un esito gia' deciso.
 *
 * Conseguenza misurata leggendo il codice: nessuno dei dodici attributi decideva mai un
 * episodio individuale. `DRIBBLING`, `VELOCITA`, `DIFESA` e `INTERCETTAZIONE` entravano
 * solo dentro [dev.mfoot.core.model.BandWeights], cioe' dentro la media. Due giocatori
 * con lo stesso overall giocavano la stessa identica partita.
 *
 * ## Perche' sono cinque e non una
 *
 * Perche' non si vincono tutte allo stesso modo. Detta dal proprietario il 2026-08-29:
 *
 * > *Nella corsa la velocita' e' quasi decisiva — chi e' piu' veloce ci arriva prima,
 * > punto. Nel contrasto e nel dribbling conta di piu' il caso, perche' c'entrano la
 * > posizione, il rimbalzo e l'arbitro.*
 *
 * Quindi ogni contesa ha la **sua** pendenza ([EngineConfig.kCorsa] e sorelle): `k` piccolo
 * vuol dire che essere piu' forte decide, `k` grande che decide il caso. Sono cinque
 * manopole da tarare separatamente, ed e' un bene: la pendenza del dribbling si legge nei
 * dribbling riusciti a partita, non nei gol.
 */
enum class Duello(val etichetta: String) {
    /** Lo scatto sul filtrante, il pallone sporco, il taglio alle spalle. */
    CORSA("scatto"),

    /** Saltare l'uomo palla al piede. */
    DRIBBLING("dribbling"),

    /** Reggere il pallone addosso a un difensore. */
    CONTRASTO("contrasto"),

    /** Il cross, la palla lunga, il pallone che si vince di testa. */
    AEREO("duello aereo"),

    /** Far arrivare la palla dove non c'e' ancora nessuno. */
    PASSAGGIO("passaggio"),
}

/** Da che parte di una contesa si sta: chi ha la palla o chi la vuole. */
enum class Lato { ATTACCO, DIFESA }

/**
 * Chi vince una contesa.
 *
 * ## Perche' i pesi stanno qui e non in configurazione
 *
 * Stessa ragione gia' scritta in [Conclusioni]: non sono una manopola della lega, sono
 * **cosa vuol dire fare quella cosa**. Che un dribbling dipenda dal dribbling non lo
 * decide l'admin, e' la descrizione del gesto. Le pendenze e le probabilita' di partenza
 * invece sono manopole vere e stanno in [EngineConfig], come tutto il resto.
 *
 * ## Nessun attributo nuovo
 *
 * Tutte e cinque le contese usano i dodici che esistono gia'. Aggiungerne uno
 * rigenererebbe il mondo — vincolo gia' scritto in `docs/REGOLE.md` a proposito degli
 * incarichi. Il punto non era che mancasse un numero: era che quelli che c'erano non
 * decidevano niente.
 */
object Duelli {

    private val corsa = mapOf(
        Attr.VELOCITA to 0.60,
        Attr.POSIZIONAMENTO to 0.40,
    )

    private val aereo = mapOf(
        Attr.FISICO to 0.55,
        Attr.POSIZIONAMENTO to 0.45,
    )

    private val dribblaChi = mapOf(
        Attr.DRIBBLING to 0.45,
        Attr.TECNICA to 0.30,
        Attr.VELOCITA to 0.25,
    )

    private val dribblaContro = mapOf(
        Attr.DIFESA to 0.40,
        Attr.POSIZIONAMENTO to 0.35,
        Attr.VELOCITA to 0.25,
    )

    private val reggeIlPallone = mapOf(
        Attr.FISICO to 0.55,
        Attr.TECNICA to 0.45,
    )

    private val loStacca = mapOf(
        Attr.DIFESA to 0.40,
        Attr.INTERCETTAZIONE to 0.35,
        Attr.FISICO to 0.25,
    )

    private val serve = mapOf(
        Attr.PASSAGGIO to 0.60,
        Attr.TECNICA to 0.40,
    )

    private val legge = mapOf(
        Attr.INTERCETTAZIONE to 0.70,
        Attr.POSIZIONAMENTO to 0.30,
    )

    /**
     * Gli attributi che contano in questa contesa, da questo lato.
     *
     * La corsa e il duello aereo sono simmetrici: chi scappa e chi insegue fanno la stessa
     * identica cosa, e chi salta e' chi salta. Le altre tre no — dribblare e far dribblare
     * sono mestieri diversi, ed e' il motivo per cui un terzino lento con un gran senso
     * della posizione regge contro un'ala e viene bruciato dall'altra.
     */
    fun pesi(duello: Duello, lato: Lato): Map<Attr, Double> = when (duello) {
        Duello.CORSA -> corsa
        Duello.AEREO -> aereo
        Duello.DRIBBLING -> if (lato == Lato.ATTACCO) dribblaChi else dribblaContro
        Duello.CONTRASTO -> if (lato == Lato.ATTACCO) reggeIlPallone else loStacca
        Duello.PASSAGGIO -> if (lato == Lato.ATTACCO) serve else legge
    }

    /** Quanto vale questo giocatore in questa contesa, da questo lato. Scala 1-99. */
    fun valore(duello: Duello, lato: Lato, attributes: Attributes): Double =
        attributes.weightedMean(pesi(duello, lato))

    /** La pendenza: quanto conta essere piu' forte. Piccola = decide la forza. */
    fun pendenza(duello: Duello, engine: EngineConfig): Double = when (duello) {
        Duello.CORSA -> engine.kCorsa
        Duello.DRIBBLING -> engine.kDribbling
        Duello.CONTRASTO -> engine.kContrasto
        Duello.AEREO -> engine.kAereo
        Duello.PASSAGGIO -> engine.kPassaggio
    }

    /** Quanto spesso la spunta chi ha la palla, a parita' di valore. */
    fun equilibrio(duello: Duello, engine: EngineConfig): Double = when (duello) {
        Duello.CORSA -> engine.equilibrioCorsa
        Duello.DRIBBLING -> engine.equilibrioDribbling
        Duello.CONTRASTO -> engine.equilibrioContrasto
        Duello.AEREO -> engine.equilibrioAereo
        Duello.PASSAGGIO -> engine.equilibrioPassaggio
    }

    /**
     * La probabilita' che la spunti chi ha la palla.
     *
     * Logistica sui **logit**, non sigmoide semplice, e la differenza conta: cosi'
     * [equilibrio] fissa dove sta la contesa a parita' di valore — un passaggio riesce
     * quattro volte su cinque, un dribbling meno di una su due — e [pendenza] decide solo
     * quanto la superiorita' sposta quel punto. Con una sigmoide centrata a zero le due
     * cose sarebbero la stessa manopola, e non si potrebbe avere un passaggio facile e
     * insieme molto sensibile alla qualita'.
     *
     * Il risultato resta dentro `(0, 1)` da solo, senza tagli: non esiste il difensore
     * imbattibile ne' l'ala che passa sempre.
     */
    fun esito(duello: Duello, attacco: Double, difesa: Double, engine: EngineConfig): Double {
        val base = equilibrio(duello, engine).coerceIn(0.01, 0.99)
        val k = pendenza(duello, engine)
        require(k > 0.0) { "la pendenza di $duello dev'essere positiva, era $k" }
        val logit = MathX.ln(base / (1.0 - base)) + (attacco - difesa) / k
        return 1.0 / (1.0 + MathX.exp(-logit))
    }
}
