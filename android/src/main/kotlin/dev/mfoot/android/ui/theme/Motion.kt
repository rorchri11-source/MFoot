package dev.mfoot.android.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * Il movimento di MFoot.
 *
 * ## Perche' un file solo
 *
 * Perche' un'animazione scritta due volte e' due animazioni diverse: la seconda ha
 * quaranta millesimi in piu' e una curva leggermente distinta, e da quel momento l'app
 * si muove in due modi. Qui ci sono i pezzi, e ogni schermata li chiama.
 *
 * ## La regola che li tiene insieme
 *
 * Il movimento **dice qualcosa**, o non c'e'. Un elenco che entra a cascata dice «questo
 * si e' appena caricato»; un pulsante che cede dice «ti ho sentito»; un alone che pulsa
 * dice «questa asta sta per chiudere». Un'animazione che non dice niente e' un ritardo
 * con delle belle maniere, e su una schermata che si scorre per venti minuti diventa un
 * fastidio.
 *
 * Le durate stanno in [MFootMotion]: veloce per cio' che risponde al dito, normale per
 * cio' che entra in scena. Mai `LinearEasing`, tranne dove il moto e' davvero costante —
 * la rotazione degli archi.
 */

/**
 * Il tocco che risponde: la superficie scende al 96% mentre il dito e' giu'.
 *
 * `docs/DESIGN-SYSTEM.md` lo prometteva dal 16 agosto — «il tocco su un pulsante lo
 * rimpicciolisce al 97%, non ne cambia il colore» — e non era mai stato scritto.
 *
 * Vuole la stessa [MutableInteractionSource] passata a `clickable`, altrimenti guarda
 * una pressione che non e' quella del pulsante che sta scalando.
 */
@Composable
fun Modifier.premuta(sorgente: MutableInteractionSource): Modifier {
    val premuto by sorgente.collectIsPressedAsState()
    val scala by animateFloatAsState(
        targetValue = if (premuto) 0.96f else 1f,
        animationSpec = tween(140, easing = MFootMotion.easing),
        label = "premuta",
    )
    return graphicsLayer { scaleX = scala; scaleY = scala }
}

/**
 * L'elenco entra a cascata: ogni riga sale e compare, in ritardo sulla precedente.
 *
 * ## Perche' [attiva] e non «sempre»
 *
 * Perche' dentro una `LazyColumn` le righe che escono dallo schermo vengono **buttate
 * via** e ricostruite quando rientrano: con l'animazione sempre accesa, ogni scorrimento
 * all'indietro le farebbe rientrare una alla volta tremolando. Il movimento serve a dire
 * «questo si e' appena caricato», e quella frase ha senso una volta sola.
 *
 * Si accende con [ricordaIntro], che resta vero per il tempo della prima comparsa e poi
 * spegne tutto.
 *
 * Il ritardo si ferma alla settima riga: oltre, l'ultima arriverebbe quando si e' gia'
 * scorso via.
 */
@Composable
fun Modifier.comparsa(indice: Int, attiva: Boolean = true): Modifier {
    if (!attiva) return this

    var entrato by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(indice.coerceAtMost(6) * 45L)
        entrato = true
    }
    val p by animateFloatAsState(
        targetValue = if (entrato) 1f else 0f,
        animationSpec = tween(MFootMotion.fast, easing = MFootMotion.easing),
        label = "comparsa",
    )
    return graphicsLayer {
        alpha = p
        translationY = (1f - p) * 22.dpToPx(density)
    }
}

/**
 * Vero finche' dura la prima comparsa di un elenco, falso da li' in poi.
 *
 * [chiave] e' cio' che, cambiando, rende l'elenco «nuovo»: di solito l'id della lega o
 * la scheda aperta. Cambiandola, la cascata riparte; scorrendo, no.
 */
@Composable
fun ricordaIntro(chiave: Any?): Boolean {
    var dentro by remember(chiave) { mutableStateOf(true) }
    LaunchedEffect(chiave) {
        delay(700)
        dentro = false
    }
    return dentro
}

/**
 * Una lama di luce che attraversa una volta sola: «questa e' la tua».
 *
 * In venti righe di nomi inventati, trovare la propria squadra senza leggerle tutte e' la
 * differenza fra consultare e cercare. La barretta blu lo dice gia' da ferma; questo lo
 * dice **all'apertura**, che e' il momento in cui si sta ancora cercando.
 */
@Composable
fun Modifier.lampo(attivo: Boolean, chiave: Any? = Unit): Modifier {
    if (!attivo) return this

    val avanzamento = remember(chiave) { Animatable(0f) }
    LaunchedEffect(chiave) {
        avanzamento.snapTo(0f)
        delay(260)
        avanzamento.animateTo(1f, tween(950, easing = MFootMotion.easing))
    }

    return drawWithContent {
        drawContent()
        val p = avanzamento.value
        if (p > 0f && p < 1f) {
            // La lama e' larga mezzo riquadro e parte da fuori a sinistra: cosi' entra ed
            // esce invece di comparire e sparire a meta' strada.
            val larghezza = size.width * 0.5f
            val x = -larghezza + p * (size.width + larghezza * 2f)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        MFootColors.elite.copy(alpha = 0.28f),
                        Color.Transparent,
                    ),
                    startX = x,
                    endX = x + larghezza,
                ),
                topLeft = Offset(x, 0f),
                size = Size(larghezza, size.height),
            )
        }
    }
}

/**
 * Il respiro: un alone che pulsa piano, per cio' che sta per scadere.
 *
 * Disegnato invece che messo come bordo: un bordo occupa spazio e farebbe **saltare il
 * contenuto** di tre pixel a ogni pulsazione. Qui il tratto sta sopra il disegno e non
 * tocca la disposizione.
 *
 * E' l'unica animazione infinita delle liste, e va accesa su poche righe alla volta.
 */
@Composable
fun Modifier.respiro(attivo: Boolean, colore: Color = MFootColors.gamble): Modifier {
    if (!attivo) return this

    val battito = rememberInfiniteTransition(label = "respiro")
    val a by battito.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = MFootMotion.easing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alone",
    )

    return drawWithContent {
        drawContent()
        val spessore = 3.dpToPx(density)
        drawRoundRect(
            color = colore.copy(alpha = 0.06f + 0.26f * a),
            topLeft = Offset(spessore / 2f, spessore / 2f),
            size = Size(size.width - spessore, size.height - spessore),
            cornerRadius = CornerRadius(18.dpToPx(density)),
            style = Stroke(width = spessore),
        )
    }
}

/**
 * La rotazione lentissima degli archi: un giro ogni ventisei secondi.
 *
 * `LinearEasing` qui e' giusto — e' l'unico posto: il moto e' costante per davvero, e una
 * curva lo farebbe accelerare e frenare senza motivo.
 *
 * Non serve fermarla quando l'app va dietro: Compose smette di produrre fotogrammi quando
 * la finestra non e' visibile, quindi in tasca non gira niente.
 */
@Composable
fun rotazioneLenta(): Float {
    val giro = rememberInfiniteTransition(label = "archi")
    val gradi by giro.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(26_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "gradi",
    )
    return gradi
}

/**
 * Un numero che sale da zero al suo valore.
 *
 * Curva in uscita: parte svelto e frena, che e' come si legge un totale — l'ordine di
 * grandezza subito, la cifra esatta dopo. Restituisce il valore da disegnare, cosi' chi
 * chiama resta libero di formattarlo come vuole.
 */
@Composable
fun contaFinoA(valore: Long, chiave: Any? = Unit): Long {
    var partito by remember(chiave) { mutableStateOf(false) }
    LaunchedEffect(chiave, valore) {
        partito = false
        delay(60)
        partito = true
    }
    val p by animateFloatAsState(
        targetValue = if (partito) 1f else 0f,
        animationSpec = tween(850, easing = MFootMotion.easing),
        label = "conteggio",
    )
    return (valore * p).toLong()
}

/** I dp in pixel dentro uno `DrawScope`, dove non c'e' il `Density` sotto mano. */
private fun Int.dpToPx(density: Float): Float = this * density
private fun Float.dpToPx(density: Float): Float = this * density
