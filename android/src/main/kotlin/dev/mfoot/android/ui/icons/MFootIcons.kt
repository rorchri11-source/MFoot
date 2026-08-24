package dev.mfoot.android.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Le icone dell'applicazione, disegnate qui.
 *
 * ## Perche' non `material-icons-extended`
 *
 * Perche' la build di rilascio ha `isMinifyEnabled = false` (vedi `android/build.gradle.kts`),
 * e senza R8 quella libreria non viene sfoltita: nell'APK finirebbero tutte le icone di
 * Material, migliaia, per usarne trenta. Su un'app che si distribuisce agli amici come
 * file da scaricare, e' un costo che si paga a ogni installazione.
 *
 * ## Perche' non i glifi di testo
 *
 * Perche' era quello che c'era prima — `⌂`, `⛨`, `⇄` — e ogni telefono li disegnava con
 * un carattere diverso: su alcuni la barra in basso aveva cinque icone di cinque pesi
 * diversi, su altri un paio erano il rettangolo del glifo mancante.
 *
 * ## Come sono fatte
 *
 * Contorno da 1.9, estremi tondi, griglia 24. Sono i valori del riferimento: un tratto
 * piu' sottile le fa sparire sul blu notte, uno piu' spesso le fa sembrare grasse accanto
 * al testo. Le poche piene — i pallini, l'indicatore acceso — usano [solid].
 */
object MFootIcons {

    // ------------------------------------------------------------------ i cinque posti

    val casa = stroked("casa") {
        moveTo(3.2f, 10.4f); lineTo(12f, 3.2f); lineTo(20.8f, 10.4f)
        lineTo(20.8f, 20.4f); lineTo(3.2f, 20.4f); close()
        moveTo(9.4f, 20.4f); lineTo(9.4f, 13.8f); lineTo(14.6f, 13.8f); lineTo(14.6f, 20.4f)
    }

    val maglia = stroked("maglia") {
        moveTo(9.2f, 3.2f)
        curveTo(9.2f, 5.6f, 14.8f, 5.6f, 14.8f, 3.2f)
        lineTo(20f, 5.6f); lineTo(21.6f, 10.2f); lineTo(18.4f, 11.6f)
        lineTo(18.4f, 20.6f); lineTo(5.6f, 20.6f); lineTo(5.6f, 11.6f)
        lineTo(2.4f, 10.2f); lineTo(4f, 5.6f); close()
    }

    val calendario = stroked("calendario") {
        moveTo(3.4f, 6.4f); lineTo(20.6f, 6.4f); lineTo(20.6f, 20.6f); lineTo(3.4f, 20.6f); close()
        moveTo(3.4f, 10.6f); lineTo(20.6f, 10.6f)
        moveTo(8f, 3.4f); lineTo(8f, 7.4f)
        moveTo(16f, 3.4f); lineTo(16f, 7.4f)
    }

    /**
     * La medaglia: nastri **pieni** e anello spesso, non due fili e un cerchio.
     *
     * E' l'unica icona costruita con due tracciati, e il motivo si e' visto solo a
     * ventitre pixel: con i nastri disegnati come due linee sottili convergenti, sopra un
     * cerchio di contorno, il risultato non era una medaglia ma un paio di orecchie da
     * coniglio. La massa distingue le due cose, e la massa a questa dimensione non e'
     * decorazione.
     */
    val medaglia = ImageVector.Builder(
        name = "medaglia",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(7.2f, 2.6f); lineTo(10.4f, 2.6f); lineTo(12f, 7.6f)
            lineTo(13.6f, 2.6f); lineTo(16.8f, 2.6f); lineTo(14.8f, 11.4f)
            lineTo(9.2f, 11.4f); close()
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 3f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            circle(12f, 14.8f, 5f)
        }
    }.build()

    val carrello = stroked("carrello") {
        moveTo(2.6f, 4.4f); lineTo(5.2f, 4.4f); lineTo(7.6f, 15f); lineTo(18.2f, 15f)
        lineTo(20.6f, 7.6f); lineTo(5.9f, 7.6f)
        circle(9.4f, 19f, 1.6f)
        circle(17.2f, 19f, 1.6f)
    }

    // ------------------------------------------------------------------ la barra in alto

    val menu = stroked("menu") {
        moveTo(3.4f, 6.6f); lineTo(20.6f, 6.6f)
        moveTo(3.4f, 12f); lineTo(20.6f, 12f)
        moveTo(3.4f, 17.4f); lineTo(20.6f, 17.4f)
    }

    val indietro = stroked("indietro") {
        moveTo(20f, 12f); lineTo(4.2f, 12f)
        moveTo(10.6f, 5.4f); lineTo(4f, 12f); lineTo(10.6f, 18.6f)
    }

    val cerca = stroked("cerca") {
        circle(10.8f, 10.8f, 6.6f)
        moveTo(15.6f, 15.6f); lineTo(20.6f, 20.6f)
    }

    val campanella = stroked("campanella") {
        moveTo(5.6f, 17f); lineTo(18.4f, 17f)
        lineTo(17f, 14.6f); lineTo(17f, 10.6f)
        curveTo(17f, 6.8f, 14.8f, 4.6f, 12f, 4.6f)
        curveTo(9.2f, 4.6f, 7f, 6.8f, 7f, 10.6f)
        lineTo(7f, 14.6f); close()
        moveTo(10f, 19.6f); curveTo(10.6f, 21.2f, 13.4f, 21.2f, 14f, 19.6f)
    }

    val fumetto = stroked("fumetto") {
        moveTo(3.6f, 5.4f); lineTo(20.4f, 5.4f); lineTo(20.4f, 16.4f); lineTo(10.4f, 16.4f)
        lineTo(6.4f, 20.2f); lineTo(6.4f, 16.4f); lineTo(3.6f, 16.4f); close()
    }

    val info = stroked("info") {
        circle(12f, 12f, 8.8f)
        moveTo(12f, 11f); lineTo(12f, 16.4f)
        moveTo(12f, 7.6f); lineTo(12f, 8.2f)
    }

    val altro = solid("altro") {
        circle(12f, 5.2f, 1.7f)
        circle(12f, 12f, 1.7f)
        circle(12f, 18.8f, 1.7f)
    }

    val condividi = stroked("condividi") {
        circle(18f, 5.6f, 2.6f)
        circle(6f, 12f, 2.6f)
        circle(18f, 18.4f, 2.6f)
        moveTo(8.3f, 10.8f); lineTo(15.7f, 6.8f)
        moveTo(8.3f, 13.2f); lineTo(15.7f, 17.2f)
    }

    val aggiorna = stroked("aggiorna") {
        moveTo(20f, 12f)
        curveTo(20f, 16.4f, 16.4f, 20f, 12f, 20f)
        curveTo(7.6f, 20f, 4f, 16.4f, 4f, 12f)
        curveTo(4f, 7.6f, 7.6f, 4f, 12f, 4f)
        curveTo(15f, 4f, 17.6f, 5.7f, 19f, 8.2f)
        moveTo(19.4f, 3.4f); lineTo(19.4f, 8.6f); lineTo(14.2f, 8.6f)
    }

    // --------------------------------------------------------------------------- il menu

    val scudo = stroked("scudo") {
        moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 11.6f)
        curveTo(20f, 16.6f, 16.6f, 19.8f, 12f, 21.2f)
        curveTo(7.4f, 19.8f, 4f, 16.6f, 4f, 11.6f)
        lineTo(4f, 6f); close()
    }

    val persona = stroked("persona") {
        circle(12f, 8f, 3.8f)
        moveTo(4.6f, 20.6f)
        curveTo(4.6f, 15.8f, 8f, 13.4f, 12f, 13.4f)
        curveTo(16f, 13.4f, 19.4f, 15.8f, 19.4f, 20.6f)
    }

    val persone = stroked("persone") {
        circle(9f, 8.2f, 3.4f)
        moveTo(2.8f, 20.4f)
        curveTo(2.8f, 16.2f, 5.6f, 14.2f, 9f, 14.2f)
        curveTo(12.4f, 14.2f, 15.2f, 16.2f, 15.2f, 20.4f)
        moveTo(16.2f, 5.2f)
        curveTo(18.4f, 5.6f, 19.8f, 7.2f, 19.8f, 9f)
        curveTo(19.8f, 10.8f, 18.6f, 12.2f, 16.8f, 12.6f)
        moveTo(18f, 14.8f)
        curveTo(20f, 15.6f, 21.2f, 17.4f, 21.2f, 20.4f)
    }

    val documento = stroked("documento") {
        moveTo(5.4f, 3.4f); lineTo(14.4f, 3.4f); lineTo(19f, 8f); lineTo(19f, 20.6f)
        lineTo(5.4f, 20.6f); close()
        moveTo(14.2f, 3.6f); lineTo(14.2f, 8.2f); lineTo(18.8f, 8.2f)
        moveTo(8.4f, 12.4f); lineTo(15.6f, 12.4f)
        moveTo(8.4f, 16.2f); lineTo(15.6f, 16.2f)
    }

    val coppa = stroked("coppa") {
        moveTo(7f, 3.6f); lineTo(17f, 3.6f); lineTo(17f, 9f)
        curveTo(17f, 12.4f, 14.8f, 14.4f, 12f, 14.4f)
        curveTo(9.2f, 14.4f, 7f, 12.4f, 7f, 9f); close()
        moveTo(7f, 5.4f); lineTo(3.8f, 5.4f); lineTo(3.8f, 8f)
        curveTo(3.8f, 10f, 5.2f, 11.2f, 7f, 11.2f)
        moveTo(17f, 5.4f); lineTo(20.2f, 5.4f); lineTo(20.2f, 8f)
        curveTo(20.2f, 10f, 18.8f, 11.2f, 17f, 11.2f)
        moveTo(12f, 14.4f); lineTo(12f, 17.6f)
        moveTo(8.2f, 20.6f); lineTo(15.8f, 20.6f); lineTo(15.8f, 17.6f); lineTo(8.2f, 17.6f); close()
    }

    /**
     * Tre fasce sovrapposte che si allargano scendendo: la piramide dei campionati.
     *
     * Non e' una scelta grafica, e' la cosa che le divisioni sono — Serie A stretta in
     * cima, Serie C larga in fondo. Il primo tentativo era un tabellone a eliminazione, e
     * a ventitre pixel diventava una «T» su un piedistallo.
     */
    val divisioni = stroked("divisioni") {
        moveTo(7.2f, 3.6f); lineTo(16.8f, 3.6f); lineTo(16.8f, 7.4f); lineTo(7.2f, 7.4f); close()
        moveTo(4.8f, 10.1f); lineTo(19.2f, 10.1f); lineTo(19.2f, 13.9f); lineTo(4.8f, 13.9f); close()
        moveTo(2.6f, 16.6f); lineTo(21.4f, 16.6f); lineTo(21.4f, 20.4f); lineTo(2.6f, 20.4f); close()
    }

    val cartellino = stroked("cartellino") {
        moveTo(11.4f, 3.4f); lineTo(20.6f, 12.6f); lineTo(12.6f, 20.6f); lineTo(3.4f, 11.4f)
        lineTo(3.4f, 3.4f); close()
        circle(7.6f, 7.6f, 1.5f)
    }

    val calcolatrice = stroked("calcolatrice") {
        moveTo(5f, 3.4f); lineTo(19f, 3.4f); lineTo(19f, 20.6f); lineTo(5f, 20.6f); close()
        moveTo(8f, 6.6f); lineTo(16f, 6.6f); lineTo(16f, 9.4f); lineTo(8f, 9.4f); close()
        moveTo(8.4f, 12.8f); lineTo(8.6f, 12.8f)
        moveTo(11.9f, 12.8f); lineTo(12.1f, 12.8f)
        moveTo(15.4f, 12.8f); lineTo(15.6f, 12.8f)
        moveTo(8.4f, 16.6f); lineTo(8.6f, 16.6f)
        moveTo(11.9f, 16.6f); lineTo(12.1f, 16.6f)
        moveTo(15.4f, 16.6f); lineTo(15.6f, 16.6f)
    }

    val gettoni = stroked("gettoni") {
        moveTo(3.4f, 7.6f)
        curveTo(3.4f, 5.8f, 7f, 4.6f, 10.4f, 4.6f)
        curveTo(13.8f, 4.6f, 17.4f, 5.8f, 17.4f, 7.6f)
        curveTo(17.4f, 9.4f, 13.8f, 10.6f, 10.4f, 10.6f)
        curveTo(7f, 10.6f, 3.4f, 9.4f, 3.4f, 7.6f); close()
        moveTo(3.4f, 7.6f); lineTo(3.4f, 12.4f)
        curveTo(3.4f, 14.2f, 7f, 15.4f, 10.4f, 15.4f)
        curveTo(11.4f, 15.4f, 12.4f, 15.3f, 13.2f, 15.1f)
        moveTo(20.6f, 16.4f)
        curveTo(20.6f, 18.2f, 17.6f, 19.4f, 14.8f, 19.4f)
        curveTo(12f, 19.4f, 9f, 18.2f, 9f, 16.4f)
        curveTo(9f, 14.6f, 12f, 13.4f, 14.8f, 13.4f)
        curveTo(17.6f, 13.4f, 20.6f, 14.6f, 20.6f, 16.4f); close()
    }

    val frecceOpposte = stroked("frecce-opposte") {
        moveTo(8f, 20.4f); lineTo(8f, 4f)
        moveTo(4f, 8f); lineTo(8f, 3.8f); lineTo(12f, 8f)
        moveTo(16f, 3.6f); lineTo(16f, 20f)
        moveTo(12f, 16f); lineTo(16f, 20.2f); lineTo(20f, 16f)
    }

    val megafono = stroked("megafono") {
        moveTo(4f, 9.4f); lineTo(8.4f, 9.4f); lineTo(17.4f, 4.4f); lineTo(17.4f, 19.6f)
        lineTo(8.4f, 14.6f); lineTo(4f, 14.6f); close()
        moveTo(7.6f, 14.6f); lineTo(8.6f, 20.4f); lineTo(12.2f, 20.4f); lineTo(10.8f, 16.4f)
        moveTo(20.4f, 9.8f); lineTo(20.4f, 14.2f)
    }

    val campo = stroked("campo") {
        moveTo(3.4f, 4.6f); lineTo(20.6f, 4.6f); lineTo(20.6f, 19.4f); lineTo(3.4f, 19.4f); close()
        moveTo(12f, 4.6f); lineTo(12f, 19.4f)
        circle(12f, 12f, 2.8f)
        moveTo(3.4f, 8.6f); lineTo(6.6f, 8.6f); lineTo(6.6f, 15.4f); lineTo(3.4f, 15.4f)
        moveTo(20.6f, 8.6f); lineTo(17.4f, 8.6f); lineTo(17.4f, 15.4f); lineTo(20.6f, 15.4f)
    }

    val croce = stroked("croce") {
        moveTo(3.6f, 8.6f); lineTo(8.6f, 8.6f); lineTo(8.6f, 3.6f); lineTo(15.4f, 3.6f)
        lineTo(15.4f, 8.6f); lineTo(20.4f, 8.6f); lineTo(20.4f, 15.4f); lineTo(15.4f, 15.4f)
        lineTo(15.4f, 20.4f); lineTo(8.6f, 20.4f); lineTo(8.6f, 15.4f); lineTo(3.6f, 15.4f); close()
    }

    val chiave = stroked("chiave") {
        moveTo(14.6f, 3.6f); lineTo(20.4f, 9.4f); lineTo(17.6f, 12.2f); lineTo(11.8f, 6.4f); close()
        moveTo(12.4f, 9.4f); lineTo(4.4f, 17.4f); lineTo(6.6f, 19.6f); lineTo(14.6f, 11.6f)
    }

    val esci = stroked("esci") {
        moveTo(14f, 3.6f); lineTo(4.4f, 3.6f); lineTo(4.4f, 20.4f); lineTo(14f, 20.4f)
        moveTo(10.4f, 12f); lineTo(20.4f, 12f)
        moveTo(16.6f, 8f); lineTo(20.6f, 12f); lineTo(16.6f, 16f)
    }

    // ------------------------------------------------------------------------- le azioni

    val piu = stroked("piu") {
        moveTo(12f, 4.6f); lineTo(12f, 19.4f)
        moveTo(4.6f, 12f); lineTo(19.4f, 12f)
    }

    val meno = stroked("meno") {
        moveTo(4.6f, 12f); lineTo(19.4f, 12f)
    }

    val chiudi = stroked("chiudi") {
        moveTo(5.6f, 5.6f); lineTo(18.4f, 18.4f)
        moveTo(18.4f, 5.6f); lineTo(5.6f, 18.4f)
    }

    val spunta = stroked("spunta") {
        moveTo(4.4f, 12.6f); lineTo(9.6f, 18f); lineTo(19.6f, 6.4f)
    }

    val giu = stroked("giu") {
        moveTo(5.6f, 9f); lineTo(12f, 15.4f); lineTo(18.4f, 9f)
    }

    val avanti = stroked("avanti") {
        moveTo(9f, 5.6f); lineTo(15.4f, 12f); lineTo(9f, 18.4f)
    }

    val matita = stroked("matita") {
        moveTo(16.2f, 3.4f); lineTo(20.6f, 7.8f); lineTo(8.4f, 20f); lineTo(3.4f, 20.6f)
        lineTo(4f, 15.6f); close()
        moveTo(13.4f, 6.2f); lineTo(17.8f, 10.6f)
    }

    val filtro = stroked("filtro") {
        moveTo(3.6f, 6.6f); lineTo(20.4f, 6.6f)
        moveTo(6.6f, 12f); lineTo(17.4f, 12f)
        moveTo(9.6f, 17.4f); lineTo(14.4f, 17.4f)
    }

    /** Le tre manopole: nel riferimento e' l'icona delle impostazioni di visualizzazione. */
    val manopole = stroked("manopole") {
        moveTo(3.6f, 7.4f); lineTo(20.4f, 7.4f)
        moveTo(3.6f, 16.6f); lineTo(20.4f, 16.6f)
        circle(9f, 7.4f, 2.4f)
        circle(15.6f, 16.6f, 2.4f)
    }

    val scarica = stroked("scarica") {
        moveTo(12f, 3.6f); lineTo(12f, 15.4f)
        moveTo(7.2f, 10.8f); lineTo(12f, 15.6f); lineTo(16.8f, 10.8f)
        moveTo(4.4f, 20.4f); lineTo(19.6f, 20.4f)
    }

    val ingranaggio = stroked("ingranaggio") {
        circle(12f, 12f, 3.4f)
        moveTo(12f, 2.8f); lineTo(12f, 5.4f)
        moveTo(12f, 18.6f); lineTo(12f, 21.2f)
        moveTo(2.8f, 12f); lineTo(5.4f, 12f)
        moveTo(18.6f, 12f); lineTo(21.2f, 12f)
        moveTo(5.5f, 5.5f); lineTo(7.3f, 7.3f)
        moveTo(16.7f, 16.7f); lineTo(18.5f, 18.5f)
        moveTo(18.5f, 5.5f); lineTo(16.7f, 7.3f)
        moveTo(7.3f, 16.7f); lineTo(5.5f, 18.5f)
    }

    val fulmine = stroked("fulmine") {
        moveTo(13.4f, 2.6f); lineTo(5.4f, 13.4f); lineTo(11.4f, 13.4f); lineTo(10.6f, 21.4f)
        lineTo(18.6f, 10.6f); lineTo(12.6f, 10.6f); close()
    }

    val sveglia = stroked("sveglia") {
        circle(12f, 13.2f, 7.6f)
        moveTo(12f, 9f); lineTo(12f, 13.2f); lineTo(15f, 15.2f)
        moveTo(3.4f, 5.4f); lineTo(6.6f, 2.8f)
        moveTo(20.6f, 5.4f); lineTo(17.4f, 2.8f)
    }

    val archivio = stroked("archivio") {
        moveTo(3.4f, 4.4f); lineTo(20.6f, 4.4f); lineTo(20.6f, 8.4f); lineTo(3.4f, 8.4f); close()
        moveTo(5f, 8.4f); lineTo(5f, 19.6f); lineTo(19f, 19.6f); lineTo(19f, 8.4f)
        moveTo(9.6f, 12.4f); lineTo(14.4f, 12.4f)
    }

    val scambio = stroked("scambio") {
        moveTo(4f, 8.4f); lineTo(18f, 8.4f)
        moveTo(14.4f, 4.6f); lineTo(18.2f, 8.4f); lineTo(14.4f, 12.2f)
        moveTo(20f, 15.6f); lineTo(6f, 15.6f)
        moveTo(9.6f, 11.8f); lineTo(5.8f, 15.6f); lineTo(9.6f, 19.4f)
    }

    val corona = solid("corona") {
        moveTo(3.4f, 8f); lineTo(7.6f, 12.4f); lineTo(12f, 5.2f); lineTo(16.4f, 12.4f)
        lineTo(20.6f, 8f); lineTo(19f, 18.4f); lineTo(5f, 18.4f); close()
    }

    /**
     * Il pianeta con l'anello: **una lega**.
     *
     * Serviva un'icona sua. «Le mie leghe» usava la coppa, che e' gia' di «Competizioni»,
     * e due voci con lo stesso simbolo in un menu di quattordici righe si trovano una al
     * posto dell'altra. Il pianeta e' anche quello che il riferimento mette come stemma
     * della lega, quindi non e' un simbolo inventato qui.
     */
    val pianeta = stroked("pianeta") {
        circle(12f, 12f, 6.4f)
        moveTo(5.2f, 14.6f)
        curveTo(2.6f, 15.8f, 1.4f, 17.4f, 2f, 18.6f)
        curveTo(2.9f, 20.4f, 8.2f, 19.6f, 14f, 16.8f)
        curveTo(19.8f, 14f, 23.1f, 10.2f, 22.2f, 8.4f)
        curveTo(21.7f, 7.4f, 19.9f, 7.2f, 17.4f, 7.8f)
    }

    val lampadina = stroked("lampadina") {
        moveTo(9.4f, 18.4f); lineTo(14.6f, 18.4f)
        moveTo(10f, 21f); lineTo(14f, 21f)
        moveTo(9.6f, 18.4f)
        curveTo(9.6f, 15f, 6.2f, 14f, 6.2f, 10.4f)
        curveTo(6.2f, 6.9f, 8.8f, 4.4f, 12f, 4.4f)
        curveTo(15.2f, 4.4f, 17.8f, 6.9f, 17.8f, 10.4f)
        curveTo(17.8f, 14f, 14.4f, 15f, 14.4f, 18.4f)
    }

    val stella = solid("stella") {
        moveTo(12f, 2.8f); lineTo(14.9f, 9f); lineTo(21.4f, 9.9f); lineTo(16.7f, 14.6f)
        lineTo(17.9f, 21.2f); lineTo(12f, 18.1f); lineTo(6.1f, 21.2f); lineTo(7.3f, 14.6f)
        lineTo(2.6f, 9.9f); lineTo(9.1f, 9f); close()
    }

    // ---------------------------------------------------------------------- l'impalcatura

    /**
     * Un cerchio chiuso, in due archi.
     *
     * `PathBuilder` non ha un cerchio: senza questa scorciatoia ogni stemma, ogni ruota e
     * ogni quadrante andrebbero scritti come quattro curve di Bezier a mano, ed e'
     * esattamente il genere di codice in cui un numero sbagliato non si nota piu'.
     */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    /** Un'icona di contorno: il caso normale. */
    private fun stroked(name: String, body: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = body,
            )
        }.build()

    /** Un'icona piena: i pallini, la corona, la stella. */
    private fun solid(name: String, body: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White), pathBuilder = body)
        }.build()
}
