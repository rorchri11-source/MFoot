package dev.mfoot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.ui.icons.MFootIcons
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType

/**
 * I pezzi che ricorrono in ogni schermata.
 *
 * Stanno insieme perche' la coerenza di un'interfaccia non viene dall'avere le regole
 * scritte da qualche parte, ma dall'avere **un solo posto** dove il campo di testo e'
 * definito. Venti schermate che ridisegnano il proprio bordo sono venti bordi diversi.
 *
 * ## Cos'e' cambiato il 2026-08-23
 *
 * Prima qui c'erano otto pezzi: un'etichetta, un campo, due pulsanti, un chip, un avviso,
 * un filo, una riga. Bastavano a tenere insieme un'app fatta di elenchi di testo, e non
 * bastano piu' a farne una fatta di **schede**. Le aggiunte — [Scheda], [Riga],
 * [Segmentato], [Banda], [Vuoto], [Striscia], [Testata] — sono le forme che nel
 * riferimento ricorrono in ogni schermata, e averle qui e' cio' che evita che ogni
 * schermata se le ridisegni con angoli e margini leggermente diversi.
 */

// --------------------------------------------------------------------------- il testo

/** Etichetta piccola, maiuscola, larga: e' quella che fa sembrare la scheda curata. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = MFootColors.ink2) {
    Text(text.uppercase(), style = MFootType.label, color = color, modifier = modifier)
}

// ------------------------------------------------------------------------- le superfici

/**
 * La scheda: la forma piu' frequente dell'applicazione.
 *
 * Fondo piu' **scuro** del fondo pagina, angoli a 18, nessun bordo. Il distacco lo fa il
 * colore e lo spazio intorno, non una linea: le schede del riferimento non hanno contorno,
 * e aggiungerne uno le fa sembrare caselle di un modulo.
 */
@Composable
fun Scheda(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** La barretta verticale a sinistra: «questa e' la tua». */
    evidenziata: Boolean = false,
    contenuto: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MFootShapes.band)
            .background(MFootColors.core)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (evidenziata) {
            Box(
                Modifier
                    .width(4.dp)
                    .defaultMinSize(minHeight = 44.dp)
                    .background(MFootColors.blue),
            )
        }
        Box(Modifier.weight(1f)) { contenuto() }
    }
}

/**
 * Una riga d'elenco: stemma, nome, chi la possiede, e un numero a destra.
 *
 * E' la forma dell'elenco squadre, dei partecipanti, del listone, delle aste. Prima ognuna
 * di queste schermate disponeva i suoi quattro pezzi a modo suo, con il risultato che
 * passando dall'una all'altra sembrava di cambiare applicazione.
 */
@Composable
fun Riga(
    titolo: String,
    modifier: Modifier = Modifier,
    sottotitolo: String? = null,
    valore: String? = null,
    etichettaValore: String? = null,
    icona: ImageVector? = null,
    /** Disegnato dentro il tondo al posto dell'icona: lo stemma, la faccia, la maglia. */
    tondo: (@Composable () -> Unit)? = null,
    evidenziata: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Scheda(modifier, onClick, evidenziata) {
        Row(
            Modifier.padding(
                start = if (evidenziata) 10.dp else 14.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tondo != null || icona != null) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MFootColors.bg),
                    contentAlignment = Alignment.Center,
                ) {
                    if (tondo != null) tondo() else Icon(
                        icona!!,
                        contentDescription = null,
                        tint = MFootColors.ink2,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    titolo,
                    style = MFootType.rowTitle,
                    color = if (evidenziata) MFootColors.elite else MFootColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sottotitolo != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sottotitolo,
                        style = MFootType.secondary,
                        color = if (evidenziata) MFootColors.elite.copy(alpha = 0.75f)
                        else MFootColors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (valore != null) {
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(valore, style = MFootType.price, color = MFootColors.elite)
                    if (etichettaValore != null) Label(etichettaValore)
                }
            }
        }
    }
}

/**
 * La tessera quadrata colorata con l'icona, negli elenchi di impostazioni.
 *
 * Il colore raggruppa per famiglia: le voci blu riguardano chi gioca, le verdi come si
 * schiera, le arancioni come si calcola. Serve a far trovare una voce fra venti **senza
 * leggerle**, che e' l'unico modo in cui un elenco lungo resta usabile.
 */
@Composable
fun Tessera(icona: ImageVector, colore: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(MFootShapes.tile)
            .background(colore),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icona, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/**
 * Una banda blu a tutta larghezza: «1ª di Lega», il nome della divisione.
 *
 * Arriva ai bordi dello schermo di proposito. Una banda con dei margini diventa una scheda
 * larga, e smette di leggersi come una separazione fra due gruppi.
 */
@Composable
fun Banda(testo: String, modifier: Modifier = Modifier, colore: Color = MFootColors.blue) {
    Text(
        testo,
        style = MFootType.rowTitle,
        color = Color.White,
        modifier = modifier
            .fillMaxWidth()
            .background(colore)
            .padding(horizontal = MFootSpacing.section, vertical = 12.dp),
    )
}

/** Il cartellino che avverte: «Rosa incompleta». Rosso spento, non acceso. */
@Composable
fun Cartellino(
    testo: String,
    modifier: Modifier = Modifier,
    fondo: Color = MFootColors.alarm,
    inchiostro: Color = MFootColors.onAlarm,
) {
    Text(
        testo,
        style = MFootType.chip,
        color = inchiostro,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(fondo)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * La striscia dei numeri: posizione, punti, partite, totale.
 *
 * Colonne di peso uguale e non larghezze fisse: con quattro voci di lunghezza diversa le
 * larghezze fisse lasciano un buco a destra su ogni telefono che non sia quello su cui
 * sono state misurate.
 */
@Composable
fun Striscia(voci: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Scheda(modifier) {
        Row(
            Modifier.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            voci.forEach { (valore, etichetta) ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(valore, style = MFootType.playerName, color = MFootColors.ink)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        etichetta,
                        style = MFootType.chip,
                        color = MFootColors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * La testata illustrata a schermo pieno, con gli archi concentrici.
 *
 * Gli archi sono disegnati e non un'immagine: un PNG andrebbe rifatto per ogni densita' di
 * schermo e sgranerebbe sui telefoni larghi, mentre queste sono sei circonferenze e un
 * ritaglio.
 */
@Composable
fun Testata(
    titolo: String,
    modifier: Modifier = Modifier,
    /** La riga piccola sopra il titolo: di solito il nome della lega. */
    sopra: String? = null,
    onIndietro: (() -> Unit)? = null,
    /**
     * Vero quando questa testata e' il **primo** elemento dello schermo.
     *
     * Allora il blu passa sotto la barra di stato — e' cio' che le da' l'aria di
     * intestazione invece che di riquadro — e il contenuto si sposta sotto l'orologio da
     * solo. Falso quando sta dentro una pagina che ha gia' una barra sopra.
     */
    insetAlto: Boolean = false,
    azione: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MFootColors.blueDeep),
    ) {
        Archi(Modifier.matchParentSize())

        // Niente `Spacer(weight)` in verticale qui dentro.
        //
        // Un peso dice «prenditi tutto lo spazio libero», e lo spazio libero dentro la
        // colonna del guscio e' **lo schermo intero**: la testata si mangiava la pagina e
        // il contenuto finiva fuori. Con un margine fisso e un'altezza minima l'ingombro e'
        // deciso qui e non dipende da chi la ospita.
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (insetAlto) Modifier.statusBarsPadding() else Modifier)
                .heightIn(min = 152.dp)
                .padding(MFootSpacing.section),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onIndietro != null) {
                    Icon(
                        MFootIcons.indietro,
                        contentDescription = "Indietro",
                        tint = Color.White,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onIndietro)
                            .padding(9.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (azione != null) azione()
            }
            Spacer(Modifier.height(30.dp))
            if (sopra != null) {
                Text(
                    sopra,
                    style = MFootType.label,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                titolo,
                style = MFootType.display,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Gli archi concentrici in alto a destra: la firma visiva del riferimento.
 *
 * Il centro sta **fuori** dallo schermo, oltre lo spigolo: e' quello che li fa sembrare un
 * frammento di qualcosa di piu' grande invece di un bersaglio appoggiato in un angolo.
 */
@Composable
private fun Archi(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val centro = Offset(size.width * 1.02f, -size.height * 0.34f)
        val tinte = listOf(
            MFootColors.blueArc.copy(alpha = 0.85f),
            MFootColors.blue.copy(alpha = 0.55f),
            MFootColors.blueArc.copy(alpha = 0.42f),
            MFootColors.blue.copy(alpha = 0.32f),
            MFootColors.blueArc.copy(alpha = 0.20f),
            MFootColors.blue.copy(alpha = 0.14f),
        )
        tinte.forEachIndexed { indice, tinta ->
            drawCircle(
                color = tinta,
                radius = size.height * (0.42f + indice * 0.19f),
                center = centro,
                style = Stroke(width = size.height * 0.055f),
            )
        }
    }
}

// -------------------------------------------------------------------------- i comandi

/**
 * Il bottone che porta avanti. Uno solo per schermata, o non e' piu' primario.
 *
 * Lavanda pieno con testo blu scuro: nel riferimento il pulsante importante e' **chiaro**
 * su fondo scuro, non blu. Su blu notte un pulsante blu sparisce dentro il fondo.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icona: ImageVector? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MFootShapes.pill)
            .background(if (enabled) MFootColors.elite else MFootColors.core)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 15.dp, horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val inchiostro = if (enabled) MFootColors.onAccent else MFootColors.ink3
        if (icona != null) {
            Icon(icona, contentDescription = null, tint = inchiostro, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MFootType.rowTitle, color = inchiostro)
    }
}

/**
 * L'alternativa: presente, leggibile, ma senza contendere l'attenzione al primario.
 *
 * ## Perche' il contorno lavanda
 *
 * Perche' senza sembrava spento. Il fondo di un pulsante secondario e' lo stesso delle
 * schede, e una pillola scura con dentro del testo bianco, su una pagina fatta di pillole
 * scure con dentro del testo bianco, non dice piu' «premimi» — dice «riquadro». Il
 * contorno costa una riga e restituisce l'unica cosa che un pulsante deve avere.
 */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MFootType.rowTitle,
        color = MFootColors.elite,
        modifier = modifier
            .fillMaxWidth()
            .clip(MFootShapes.pill)
            .background(MFootColors.core)
            .border(1.5.dp, MFootColors.elite.copy(alpha = 0.45f), MFootShapes.pill)
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        textAlign = TextAlign.Center,
    )
}

/**
 * Un pulsante tondo: quelli che nel riferimento galleggiano sopra la maglia.
 *
 * Il tondo lavanda in basso a destra e' la stessa cosa con [grande] acceso.
 */
@Composable
fun Tondo(
    icona: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fondo: Color = MFootColors.elite,
    inchiostro: Color = MFootColors.onAccent,
    grande: Boolean = false,
    descrizione: String? = null,
) {
    Box(
        modifier
            .size(if (grande) 60.dp else 46.dp)
            .clip(RoundedCornerShape(50))
            .background(fondo)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icona,
            contentDescription = descrizione,
            tint = inchiostro,
            modifier = Modifier.size(if (grande) 26.dp else 22.dp),
        )
    }
}

/**
 * Il segmentato: due o tre scelte che si escludono, tutte visibili insieme.
 *
 * Diverso dai [Chip], che sono un elenco che scorre. Qui le voci sono poche e fisse, e
 * vederle tutte e' il punto: «nella competizione» o «nella lega» e' una domanda a cui si
 * risponde una volta, non un filtro che si scorre.
 */
@Composable
fun <T> Segmentato(
    voci: List<T>,
    scelta: T,
    etichetta: (T) -> String,
    modifier: Modifier = Modifier,
    onScegli: (T) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, MFootColors.blue, RoundedCornerShape(12.dp)),
    ) {
        voci.forEach { voce ->
            val acceso = voce == scelta
            Text(
                etichetta(voce),
                style = MFootType.rowTitle,
                color = if (acceso) Color.White else MFootColors.blue,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .background(if (acceso) MFootColors.blue else Color.Transparent)
                    .clickable { onScegli(voce) }
                    .padding(vertical = 13.dp, horizontal = 6.dp),
            )
        }
    }
}

/**
 * La barra delle sezioni di un posto: linguette con la sottolineatura.
 *
 * ## Perche' non dei chip
 *
 * I chip dicono «filtro»: si accendono e si spengono, se ne possono immaginare due accesi
 * insieme, e restano al loro posto quando cambia il contenuto. Le sezioni di un posto non
 * sono filtri — sono **dove sei**, e ne esiste sempre esattamente una. La sottolineatura
 * lo dice; una pillola accesa fra pillole spente no, e infatti nel riferimento le due cose
 * hanno due forme diverse.
 *
 * Scorre in orizzontale perche' sei linguette non ci stanno su un telefono stretto, e
 * tagliarne una vorrebbe dire una destinazione che su certi schermi non esiste.
 */
@Composable
fun <T> BarraSchede(
    voci: List<T>,
    scelta: T,
    etichetta: (T) -> String,
    modifier: Modifier = Modifier,
    onScegli: (T) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MFootColors.bg)
            .horizontalScroll(rememberScrollState()),
    ) {
        voci.forEach { voce ->
            val acceso = voce == scelta
            // `width(IntrinsicSize.Max)` e non `fillMaxWidth()` sulla sottolineatura.
            //
            // Dentro un `horizontalScroll` la larghezza massima che arriva ai figli e'
            // **infinita**, e `fillMaxWidth()` con un vincolo infinito non si applica: la
            // sottolineatura veniva misurata zero e non si vedeva. Con la larghezza
            // intrinseca la colonna prende quella della sua etichetta, e la riga sotto la
            // eredita.
            Column(
                Modifier
                    .clickable { onScegli(voce) }
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp)
                    .width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    etichetta(voce),
                    style = MFootType.rowTitle,
                    color = if (acceso) MFootColors.ink else MFootColors.ink3,
                    maxLines = 1,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (acceso) MFootColors.blue else Color.Transparent),
                )
            }
        }
    }
    Hairline()
}

/**
 * Il riquadro viola che spiega una cosa nuova.
 *
 * Una lampadina, un titolo, e il testo. Prima queste spiegazioni erano paragrafi grigi in
 * fondo alla schermata, con lo stesso stile delle didascalie: chi non sapeva cosa fossero
 * le divisioni non aveva nessun motivo di leggere proprio quel grigio invece di un altro.
 *
 * **Non e' un avviso.** Per quello c'e' [Notice], che porta il colore del suo significato.
 * Se il viola comincia a comparire anche sugli errori, smette di voler dire «qui si
 * impara qualcosa» e torna a essere un rettangolo colorato.
 */
@Composable
fun Spiegazione(titolo: String, testo: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MFootShapes.band)
            .background(MFootColors.teach)
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                MFootIcons.lampadina,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(titolo, style = MFootType.rowTitle, color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(testo, style = MFootType.secondary, color = MFootColors.onTeach)
    }
}

/**
 * Una barra di avanzamento con i due estremi scritti.
 *
 * «1ª ————— 25ª» dice due cose che il solo numero non dice: a che punto si e', e **quanto
 * manca**. Nel riferimento e' quello che rende leggibile una competizione senza aprirla.
 */
@Composable
fun Avanzamento(
    fatto: Int,
    totale: Int,
    modifier: Modifier = Modifier,
    inizio: String = "$fatto",
    fine: String = "$totale",
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(inizio, style = MFootType.value, color = MFootColors.ink2)
            Spacer(Modifier.weight(1f))
            Text(fine, style = MFootType.value, color = MFootColors.ink2)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(MFootShapes.pill)
                .background(MFootColors.bg),
        ) {
            // `coerceIn` e non una divisione secca: con `totale` a zero — una competizione
            // creata e non ancora sorteggiata — sarebbe una divisione per zero, e la
            // schermata si spegnerebbe invece di mostrare una barra vuota.
            val quota = if (totale > 0) (fatto.toFloat() / totale).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .fillMaxWidth(quota)
                    .height(5.dp)
                    .clip(MFootShapes.pill)
                    .background(MFootColors.blue),
            )
        }
    }
}

/**
 * Il selettore di un numero piccolo: 2 3 4 5 6, con un tondo su quello scelto.
 *
 * Sotto la decina si tocca invece di digitare — e' piu' rapido e non apre la tastiera —
 * e si vedono tutte le scelte possibili insieme, che con un campo di testo non succede.
 */
@Composable
fun Selettore(
    valori: List<Int>,
    scelto: Int,
    modifier: Modifier = Modifier,
    abilitato: Boolean = true,
    onScegli: (Int) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MFootShapes.pill)
            .background(MFootColors.bg)
            .padding(5.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        valori.forEach { valore ->
            val acceso = valore == scelto
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (acceso) MFootColors.elite else Color.Transparent)
                    .then(if (abilitato) Modifier.clickable { onScegli(valore) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    valore.toString(),
                    style = MFootType.rowTitle,
                    color = when {
                        acceso -> MFootColors.onAccent
                        abilitato -> MFootColors.ink
                        else -> MFootColors.ink3
                    },
                )
            }
        }
    }
}

/**
 * Un chip di un elenco che scorre.
 *
 * Resta accanto al [Segmentato] e non al suo posto: le schede di un posto sono cinque o
 * sei e devono poter scorrere, un segmentato con sei voci le riduce a monconi illeggibili.
 */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MFootType.chip,
        color = if (selected) MFootColors.onAccent else MFootColors.ink2,
        modifier = Modifier
            .clip(MFootShapes.pill)
            .background(if (selected) MFootColors.elite else MFootColors.core)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

// ---------------------------------------------------------------------------- i campi

/**
 * Un campo di testo.
 *
 * `BasicTextField` invece del campo di Material: quello porta con se' etichette
 * fluttuanti, bordi e colori suoi che combattono con il sistema visivo. Qui il bordo lo
 * disegniamo noi e resta uguale a tutto il resto.
 */
@Composable
fun MFootField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    uppercase: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) {
    Column(modifier) {
        if (label != null) {
            Label(label)
            Spacer(Modifier.height(6.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MFootShapes.field)
                .background(MFootColors.core)
                .padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = MFootType.rowTitle, color = MFootColors.ink3)
            }
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(if (uppercase) it.uppercase() else it) },
                singleLine = true,
                textStyle = MFootType.rowTitle.copy(color = MFootColors.ink),
                cursorBrush = SolidColor(MFootColors.elite),
                keyboardOptions = KeyboardOptions(
                    capitalization = if (uppercase) KeyboardCapitalization.Characters
                    else KeyboardCapitalization.Words,
                    imeAction = imeAction,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Il campo di ricerca: pillola, lente a sinistra, e i tondi dei filtri a destra. */
@Composable
fun Ricerca(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    azioni: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clip(MFootShapes.pill)
                .background(MFootColors.core)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MFootIcons.cerca,
                contentDescription = null,
                tint = MFootColors.ink2,
                modifier = Modifier.size(21.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MFootType.rowTitle, color = MFootColors.ink3)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MFootType.rowTitle.copy(color = MFootColors.ink),
                    cursorBrush = SolidColor(MFootColors.elite),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (azioni != null) {
            Spacer(Modifier.width(10.dp))
            azioni()
        }
    }
}

// ------------------------------------------------------------------------- i messaggi

/** Un messaggio in un riquadro: esito, errore, avviso. Il colore porta il significato. */
@Composable
fun Notice(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MFootType.chip,
        color = color,
        modifier = modifier
            .fillMaxWidth()
            .clip(MFootShapes.field)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/**
 * La schermata che non ha niente da mostrare.
 *
 * Tondo grande, icona, una frase, e — se c'e' qualcosa da fare — il pulsante che la fa,
 * in basso a destra. Prima era una riga di testo grigio al centro dello schermo, e la
 * differenza fra «non c'e' niente» e «non ha caricato» era invisibile.
 */
@Composable
fun Vuoto(
    testo: String,
    modifier: Modifier = Modifier,
    icona: ImageVector? = null,
    azione: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icona != null) {
                Box(
                    Modifier
                        .size(148.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MFootColors.raised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icona,
                        contentDescription = null,
                        tint = MFootColors.ink2,
                        modifier = Modifier.size(62.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
            Text(
                testo,
                style = MFootType.rowTitle,
                color = MFootColors.ink2,
                textAlign = TextAlign.Center,
            )
        }
        if (azione != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(MFootSpacing.section),
            ) { azione() }
        }
    }
}

/** Il filo che separa senza pesare. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MFootColors.line),
    )
}

/** Riga con etichetta a sinistra e valore a destra: la forma dei riepiloghi. */
@Composable
fun StatRow(label: String, value: String, valueColor: Color = MFootColors.ink) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = MFootSpacing.gridVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, Modifier.weight(1f))
        Text(value, style = MFootType.value, color = valueColor)
    }
}
