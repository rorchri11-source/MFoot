package dev.mfoot.core.match

import dev.mfoot.core.config.EngineConfig
import dev.mfoot.core.model.Position

/**
 * Di che tipo e' una conclusione.
 *
 * ## Perche' il tipo viene prima del tiratore
 *
 * Perche' decide **chi** puo' tirarla. Un colpo di testa su cross lo attacca un centrale
 * salito, un tiro da fuori lo prende un centrocampista, un'occasione limpida capita quasi
 * sempre a una punta. Scegliendo prima il giocatore e poi il tipo si otterrebbe l'unica
 * cosa che il calcio non fa: il centravanti che segna tutti i gol di testa **e** tutti
 * quelli da trenta metri.
 *
 * ## Il difetto che questa distinzione toglie
 *
 * Prima chi concludeva era **chi aveva la palla in zona d'attacco**, e alle zone d'attacco
 * contribuiscono solo le punte, gli esterni offensivi e il trequartista — un centrocampista
 * per il 16%, i difensori per niente. Il risultato, segnalato dal proprietario: *«gol solo
 * da quelli forti, dall'attacco e basta»*. Un difensore centrale non poteva segnare
 * nemmeno su calcio d'angolo, che nel calcio vero e' il modo in cui i difensori segnano.
 */
enum class TipoConclusione(val etichetta: String) {
    DA_FUORI("tiro da fuori"),
    IN_AREA("conclusione in area"),
    DI_TESTA("colpo di testa"),
    LIMPIDA("occasione limpida"),
    RIPARTENZA("ripartenza"),
    PUNIZIONE("punizione"),
}

/**
 * Chi tira, chi serve, e quanto vale una conclusione.
 *
 * ## Perche' i pesi stanno qui e non in configurazione
 *
 * Perche' non sono una manopola della lega: sono **cosa vuol dire giocare in quel ruolo**.
 * Che un centrale attacchi i corner piu' di un terzino non e' una scelta dell'admin, e'
 * la descrizione del calcio. I numeri che l'admin puo' toccare — quanto vale un tiro da
 * fuori, quanto spesso capita — stanno in [EngineConfig] come tutti gli altri.
 *
 * ## Da dove vengono
 *
 * Dalle tabelle del Match Simulator, il simulatore che il proprietario ha indicato come
 * metro il 2026-08-29. Sono state rimappate sui ruoli di MFoot: il suo `ST` e' il nostro
 * `ATT`, `CDM` e' `MED`, `CAM` e' `TRQ`.
 */
object Conclusioni {

    /**
     * Quanto e' probabile che tocchi a questo ruolo, per tipo di conclusione.
     *
     * La riga dei colpi di testa e' quella che cambia la faccia del gioco: il centrale ha
     * 5,5 contro l'1 del terzino, ed e' il motivo per cui in una stagione un difensore
     * segna qualche gol invece di nessuno.
     */
    fun peso(tipo: TipoConclusione, position: Position): Double = when (tipo) {
        TipoConclusione.DI_TESTA -> when (position) {
            Position.ATT -> 10.0
            Position.SP -> 7.0
            Position.DC -> 5.5
            Position.TRQ -> 3.0
            Position.AD, Position.AS -> 2.5
            Position.CC, Position.MED -> 2.0
            Position.TD, Position.TS -> 1.0
            Position.POR -> 0.02
        }

        TipoConclusione.DA_FUORI -> when (position) {
            Position.CC -> 8.0
            Position.TRQ -> 7.0
            Position.MED -> 5.0
            Position.AD, Position.AS, Position.ATT, Position.SP -> 4.0
            Position.TD, Position.TS -> 2.0
            Position.DC -> 1.5
            Position.POR -> 0.05
        }

        TipoConclusione.PUNIZIONE -> when (position) {
            Position.TRQ -> 8.0
            Position.CC -> 6.0
            Position.AD, Position.AS -> 4.0
            Position.ATT, Position.SP -> 3.0
            Position.TD, Position.TS, Position.MED -> 2.0
            Position.DC -> 0.5
            Position.POR -> 0.05
        }

        // In area, occasione limpida e ripartenza premiano chi sta davanti: sono le
        // conclusioni che nascono dentro l'area avversaria.
        else -> when (position) {
            Position.ATT -> 10.0
            Position.SP -> 8.5
            Position.AD, Position.AS -> 6.5
            Position.TRQ -> 6.0
            Position.CC -> 3.0
            Position.MED -> 1.4
            Position.DC -> 1.2
            Position.TD, Position.TS -> 0.8
            Position.POR -> 0.02
        }
    }

    /**
     * Quanto e' probabile che l'assist arrivi da questo ruolo.
     *
     * Il trequartista serve, il centrale quasi mai: e' la stessa asimmetria dei tiri
     * girata dall'altra parte. Senza, l'assist finirebbe a chi ha toccato l'ultimo pallone
     * — che spesso e' il difensore che ha rinviato.
     */
    fun pesoAssist(position: Position): Double = when (position) {
        Position.TRQ -> 8.0
        Position.CC, Position.AD, Position.AS -> 6.0
        Position.ATT, Position.SP -> 4.0
        Position.TD, Position.TS, Position.MED -> 2.5
        Position.DC -> 0.8
        Position.POR -> 0.15
    }

    /** Il valore di partenza di una conclusione, prima della qualita' di chi tira. */
    fun xgBase(tipo: TipoConclusione, engine: EngineConfig): Double = when (tipo) {
        TipoConclusione.DA_FUORI -> engine.xgDaFuori
        TipoConclusione.IN_AREA -> engine.xgInArea
        TipoConclusione.DI_TESTA -> engine.xgDiTesta
        TipoConclusione.LIMPIDA -> engine.xgLimpida
        TipoConclusione.RIPARTENZA -> engine.xgRipartenza
        TipoConclusione.PUNIZIONE -> engine.xgPunizione
    }

    /**
     * Quanto spesso capita ogni tipo.
     *
     * Un terzo da fuori, un terzo in area, un sesto di testa, e il resto fra occasioni
     * limpide, ripartenze e punizioni. Sono le proporzioni misurate del calcio vero, ed e'
     * il motivo per cui la media dei gol resta dove deve stare pur avendo allargato di
     * molto la platea di chi tira: i difensori tirano **da fuori e di testa**, che sono
     * le conclusioni che valgono meno.
     */
    fun pesoTipo(tipo: TipoConclusione, engine: EngineConfig): Double = when (tipo) {
        TipoConclusione.DA_FUORI -> engine.quotaDaFuori
        TipoConclusione.IN_AREA -> engine.quotaInArea
        TipoConclusione.DI_TESTA -> engine.quotaDiTesta
        TipoConclusione.LIMPIDA -> engine.quotaLimpida
        TipoConclusione.RIPARTENZA -> engine.quotaRipartenza
        TipoConclusione.PUNIZIONE -> engine.quotaPunizione
    }

    /**
     * L'attributo che conta per questo tipo di conclusione.
     *
     * Un colpo di testa non lo decide il tiro: lo decidono lo stacco e il tempismo. Usare
     * il tiro per tutto vorrebbe dire che il centrale che salta piu' in alto di tutti
     * segna di testa quanto uno che salta poco ma calcia bene.
     */
    fun diTesta(tipo: TipoConclusione): Boolean = tipo == TipoConclusione.DI_TESTA
}
