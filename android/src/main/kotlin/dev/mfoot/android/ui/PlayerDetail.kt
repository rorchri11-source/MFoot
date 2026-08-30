package dev.mfoot.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mfoot.core.model.Money
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType
import dev.mfoot.android.app.PlayerRow
import dev.mfoot.core.model.Attr

/**
 * La scheda giocatore — **registro alto**.
 *
 * Questa schermata si guarda una alla volta, con attenzione, prima di decidere se
 * spendere sessanta crediti. Puo' permettersi il teatro che la lista non puo'.
 */
@Composable
fun PlayerDetailScreen(
    row: PlayerRow,
    /** Presenze, gol e media voto. Vuota finche non ha giocato niente. */
    carriera: dev.mfoot.android.data.Carriera = dev.mfoot.android.data.Carriera.NESSUNA,
    /** La giornata di lega corrente: serve a dire *quando torna* un infortunato. */
    giornata: Int = 0,
    /** Gli incarichi che questo giocatore ha nella formazione: fascia, rigori, angoli. */
    incarichi: List<dev.mfoot.core.match.MatchDuty> = emptyList(),
    canAuction: Boolean = false,
    /** Vero quando il giocatore e mio: cambia solo la parola sul pulsante, ma cambiarla
     * conta — "metti all asta" e "vendi" sono due gesti diversi. */
    isSelling: Boolean = false,
    /** Il testo del pulsante Primavera, o null se non si puo spostare questo giocatore. */
    youthAction: String? = null,
    /** Quanto puo' spendere il proprio club, gia' al netto dei crediti impegnati. */
    creditiDisponibili: Int = 0,
    /** Il rilancio minimo della lega: serve a contestare, non a comprare. */
    rilancioMinimo: Int = 1,
    /**
     * Quanto costa questo svincolato, se lo e' e se il server ha risposto.
     *
     * Distinto dal prezzo di listino: uno svincolato **non ha bisogno** che qualcuno lo
     * metta in vendita per essere comprabile. Legare il pulsante alla riga di listino e'
     * stato l'errore che ha reso invisibile tutto il mercato nuovo.
     */
    prezzoSvincolato: Int? = null,
    /**
     * Il prezzo che l'app propone quando si mette in vendita.
     *
     * Lo calcola `ListingRules.suggestedPrice` in `core` — cioe' il valore di mercato con
     * la curva vera — e arriva gia' pronto perche' qui dentro la configurazione della lega
     * non c'e'. E' un **suggerimento**: serve a non far partire da zero chi non sa quanto
     * valga il suo terzino, e a rendere evidente quando qualcuno vende a un decimo del
     * valore, che e' il caso in cui gli altri contestano.
     */
    prezzoConsigliato: Int = 0,
    onYouth: () -> Unit = {},
    onAuction: () -> Unit = {},
    onCompra: () -> Unit = {},
    onVendi: (Int) -> Unit = {},
    onRitira: () -> Unit = {},
    onSvincola: () -> Unit = {},
    onContesta: (Int) -> Unit = {},
    /**
     * I club fra cui l'amministratore puo' spostare questo giocatore.
     *
     * Vuota per tutti gli altri, ed e' il modo giusto di dirlo: chi non e' admin non
     * riceve dei comandi spenti, non riceve proprio la sezione. Un pulsante che si puo'
     * premere e che da' sempre errore insegna a non fidarsi di nessun pulsante.
     */
    adminClubs: List<Pair<Long, String>> = emptyList(),
    onAdminAssegna: (Long) -> Unit = {},
    onAdminSvincola: () -> Unit = {},
    onClose: () -> Unit,
) {
    val player = row.player

    // I tre fogli che si aprono sopra la scheda. Stato locale e non nel ViewModel: sono
    // domande che nascono e muoiono dentro questa schermata, e portarle nello stato
    // globale vorrebbe dire ricordarsi di azzerarle da ogni strada che porta via di qui.
    var chiedeIlPrezzo by remember(row.player.id) { mutableStateOf(false) }
    var chiedeSvincolo by remember(row.player.id) { mutableStateOf(false) }
    var contesta by remember(row.player.id) { mutableStateOf(false) }
    var scegliClub by remember(row.player.id) { mutableStateOf(false) }

    // La scheda riempie lo schermo e il piede resta ancorato in basso: lasciarla
    // galleggiare su un fondo vuoto la faceva sembrare incompiuta.
    Box(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .padding(MFootSpacing.related),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MFootColors.raised, MFootShapes.shell)
                .padding(MFootSpacing.shellPadding),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(MFootShapes.core)
                    .background(
                        Brush.verticalGradient(listOf(MFootColors.coreTop, MFootColors.core)),
                    ),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Figurina(row, giornata, incarichi)
                    Condizione(row, giornata)
                    Carriera(carriera)
                    Attributes(row)
                    Stars(row)
                    Traits(row)
                    if (adminClubs.isNotEmpty()) {
                        SezioneAdmin(
                            row = row,
                            onAssegna = { scegliClub = true },
                            onSvincola = onAdminSvincola,
                        )
                    }
                }
                Footer(
                    row = row,
                    prezzoSvincolato = prezzoSvincolato,
                    canAuction = canAuction,
                    isSelling = isSelling,
                    youthAction = youthAction,
                    onYouth = onYouth,
                    onAuction = onAuction,
                    onClose = onClose,
                    onCompra = onCompra,
                    onVendi = { chiedeIlPrezzo = true },
                    onRitira = onRitira,
                    onSvincola = { chiedeSvincolo = true },
                    onContesta = { contesta = true },
                )
            }
        }

        if (chiedeIlPrezzo) {
            val consigliato = prezzoConsigliato.takeIf { it > 0 } ?: row.value
            FoglioPrezzo(
                titolo = "A quanto lo vendi?",
                spiegazione = "Il prezzo lo decidi tu. Chi lo compra se lo porta via subito, " +
                    "ma per dodici ore chiunque può contestare l'acquisto e aprire un'asta.",
                iniziale = consigliato,
                passo = 1.coerceAtLeast(consigliato / 20),
                minimo = 1,
                massimo = Int.MAX_VALUE / 2,
                consigliato = consigliato,
                conferma = { "Metti in vendita a $it" },
                onConferma = { chiedeIlPrezzo = false; onVendi(it) },
                onClose = { chiedeIlPrezzo = false },
            )
        }

        if (contesta) {
            val prezzo = row.acquisto?.price ?: 0
            val minimo = prezzo + rilancioMinimo
            FoglioPrezzo(
                titolo = "Contesta l'acquisto",
                spiegazione = "Offri una cifra per contestare l'acquisto di ${row.player.shortName}. " +
                    "I crediti si impegnano subito e parte l'asta: se nessuno rilancia entro la scadenza, " +
                    "il giocatore passa alla tua squadra.",
                iniziale = minimo,
                passo = rilancioMinimo,
                minimo = minimo,
                massimo = creditiDisponibili.coerceAtLeast(minimo),
                conferma = { "Contesta a $it" },
                onConferma = { contesta = false; onContesta(it) },
                onClose = { contesta = false },
            )
        }

        if (scegliClub) {
            SceltaClub(
                club = adminClubs,
                onPick = { id -> scegliClub = false; onAdminAssegna(id) },
                onClose = { scegliClub = false },
            )
        }

        if (chiedeSvincolo) {
            Conferma(
                titolo = "Svincolare ${player.shortName}?",
                spiegazione = "Non costa niente, ma torna svincolato e può prenderlo " +
                    "chiunque — anche chi ti sta davanti in classifica. Lo saprà tutta la lega.",
                azione = "Svincola",
                onConferma = { chiedeSvincolo = false; onSvincola() },
                onClose = { chiedeSvincolo = false },
            )
        }
    }
}

/**
 * Il foglio che chiede un numero: il prezzo di vendita, o il massimo per contestare.
 *
 * Uno solo per tutti e due i casi perche' la domanda e' la stessa — quanti crediti — e la
 * differenza sta nelle parole, non nel meccanismo.
 *
 * ## Il numero si scrive
 *
 * Erano solo un meno e un piu'. Il passo si adattava alla cifra — un ventesimo del valore
 * — ma restava l'unico modo di arrivare a un numero: per mettere 4.000 partendo da 12.000
 * servivano quaranta tocchi, e per metterne uno tondo non c'era proprio modo. Il campo si
 * scrive con la tastiera, e i due tondi restano per gli aggiustamenti, che e' la cosa in
 * cui sono bravi.
 *
 * ## E il consigliato e' scritto, non implicito
 *
 * Il valore di partenza **era** gia' il prezzo consigliato, ma non lo diceva: chi lo
 * vedeva non poteva sapere se fosse una stima del gioco o un numero a caso, e chi lo
 * cambiava non poteva piu' tornarci. Adesso c'e' scritto quanto vale e lo si rimette con
 * un tocco.
 */
@Composable
private fun FoglioPrezzo(
    titolo: String,
    spiegazione: String,
    iniziale: Int,
    passo: Int,
    minimo: Int,
    massimo: Int,
    conferma: (Int) -> String,
    onConferma: (Int) -> Unit,
    onClose: () -> Unit,
    consigliato: Int? = null,
) {
    // Il testo e il numero sono due cose diverse, di proposito. Tenere solo il numero
    // vorrebbe dire che cancellando l'ultima cifra ricompare uno zero sotto le dita, e che
    // «0500» si riscrive da solo mentre lo si sta battendo.
    var testo by remember { mutableStateOf(iniziale.coerceIn(minimo, massimo).toString()) }
    val valore = (testo.toIntOrNull() ?: 0).coerceIn(minimo, massimo)
    val valido = testo.toIntOrNull()?.let { it in minimo..massimo } == true

    fun imposta(n: Int) { testo = n.coerceIn(minimo, massimo).toString() }

    Sipario(onClose) {
        Text(titolo, style = MFootType.playerName, color = MFootColors.ink)
        Spacer(Modifier.height(6.dp))
        Text(spiegazione, style = MFootType.chip, color = MFootColors.ink2)
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .background(MFootColors.bg, MFootShapes.pill)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tondo("−") { imposta(valore - passo) }
            BasicTextField(
                value = testo,
                // Solo cifre: un campo numerico che accetta lettere e' un campo che
                // prima o poi manda al server una parola.
                onValueChange = { nuovo -> testo = nuovo.filter { it.isDigit() }.take(9) },
                singleLine = true,
                textStyle = MFootType.overallLarge.copy(
                    color = if (valido) MFootColors.ink else MFootColors.gamble,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(MFootColors.elite),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.weight(1f),
            )
            Tondo("+") { imposta(valore + passo) }
        }

        if (consigliato != null && consigliato > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (valore == consigliato) "È il prezzo consigliato" else "Consigliato: $consigliato",
                style = MFootType.chip,
                color = if (valore == consigliato) MFootColors.ink3 else MFootColors.elite,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (valore == consigliato) Modifier
                        else Modifier.clickable { imposta(consigliato) },
                    )
                    .padding(vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            if (valido) conferma(valore) else "Scrivi un numero fra $minimo e $massimo",
            style = MFootType.value,
            color = if (valido) MFootColors.onAccent else MFootColors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (valido) MFootColors.elite else MFootColors.core, MFootShapes.pill)
                // Spento invece che "premi e vedi": un campo vuoto non e' uno zero, e
                // mandare zero al server per farsi rispondere di no e' il modo peggiore
                // di scoprire che serviva un numero.
                .then(if (valido) Modifier.clickable { onConferma(valore) } else Modifier)
                .padding(vertical = 13.dp),
        )
    }
}

@Composable
private fun Conferma(
    titolo: String,
    spiegazione: String,
    azione: String,
    onConferma: () -> Unit,
    onClose: () -> Unit,
) {
    Sipario(onClose) {
        Text(titolo, style = MFootType.playerName, color = MFootColors.ink)
        Spacer(Modifier.height(6.dp))
        Text(spiegazione, style = MFootType.chip, color = MFootColors.ink2)
        Spacer(Modifier.height(18.dp))
        Text(
            azione,
            style = MFootType.value,
            color = MFootColors.onAlarm,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(MFootColors.alarm, MFootShapes.pill)
                .clickable(onClick = onConferma)
                .padding(vertical = 13.dp),
        )
    }
}

/**
 * Gli interventi dell'amministratore, in fondo alla scheda.
 *
 * ## Perche' in fondo, e perche' con un colore diverso
 *
 * Perche' non sono mosse di gioco: sono riparazioni. Metterle accanto a «Compra» le
 * renderebbe una scorciatoia — e chi amministra e' anche uno dei concorrenti, quindi il
 * confine fra le due cose deve vedersi anche guardando lo schermo di sfuggita.
 */
@Composable
private fun SezioneAdmin(row: PlayerRow, onAssegna: () -> Unit, onSvincola: () -> Unit) {
    Column(Modifier.padding(horizontal = MFootSpacing.gutter)) {
        SectionLabel("AMMINISTRAZIONE")
        Spacer(Modifier.height(MFootSpacing.related))
        Text(
            "Strumenti da amministratore: servono a riparare, non a giocare. " +
                "Nessuno riceve un avviso quando li usi.",
            style = MFootType.chip,
            color = MFootColors.ink3,
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                "Assegna a un club",
                style = MFootType.value,
                color = MFootColors.ink,
                modifier = Modifier
                    .weight(1f)
                    .background(MFootColors.bg, MFootShapes.pill)
                    .clickable(onClick = onAssegna)
                    .padding(vertical = 11.dp),
                textAlign = TextAlign.Center,
            )
            if (row.club != null) {
                Text(
                    "Togli dal club",
                    style = MFootType.value,
                    color = MFootColors.onAlarm,
                    modifier = Modifier
                        .weight(1f)
                        .background(MFootColors.alarm, MFootShapes.pill)
                        .clickable(onClick = onSvincola)
                        .padding(vertical = 11.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    Spacer(Modifier.height(MFootSpacing.section))
}

@Composable
private fun SceltaClub(
    club: List<Pair<Long, String>>,
    onPick: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Sipario(onClose) {
        Text("A quale club?", style = MFootType.playerName, color = MFootColors.ink)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            club.forEach { (id, nome) ->
                Text(
                    nome,
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(id) }
                        .padding(vertical = 13.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(MFootColors.line))
            }
        }
    }
}

/** Il fondo scuro con il foglio in basso, comune ai tre. */
@Composable
private fun Sipario(onClose: () -> Unit, contenuto: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.66f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MFootColors.coreTop, MFootShapes.shell)
                // Un tocco dentro al foglio non deve chiuderlo: il click del fondo
                // arriverebbe comunque, ed e' il modo piu' rapido per perdere un numero
                // appena composto.
                .clickable(enabled = false) {}
                .padding(MFootSpacing.gutter, 20.dp, MFootSpacing.gutter, 26.dp),
        ) {
            contenuto()
            Spacer(Modifier.height(10.dp))
            Text(
                "Lascia perdere",
                style = MFootType.value,
                color = MFootColors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClose)
                    .padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Tondo(segno: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .background(MFootColors.raised, MFootShapes.pill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(segno, style = MFootType.playerName, color = MFootColors.ink)
    }
}

/**
 * La testata: **la figurina**.
 *
 * ## Cosa ha preso il posto della barra
 *
 * Fino al 2026-08-24 qui sotto c'era una barra alta centoventi pixel che diceva una cosa
 * sola — quanto puo' ancora crescere — e per meta' dei giocatori quella cosa era «niente».
 * Su un maturo si riempiva tutta e non informava; su un giovane mostrava un vuoto che
 * sembra un difetto invece di una promessa.
 *
 * Adesso il margine e' **un gradino sotto l'overall**: «71», e sotto «+13». Quarantacinque
 * pixel invece di centoventi, e si legge nello stesso colpo d'occhio del numero grande,
 * che e' proprio il punto — sono la stessa informazione, non due.
 *
 * ## Le due cose che non potevano perdersi
 *
 * **Quanto lo conosci**, perche' una forbice larga vuol dire due cose opposte (giocatore
 * imprevedibile, oppure mai visto giocare) e senza dirlo la scommessa resta muta. E il
 * **contratto**, che cambia una decisione d'acquisto: sei giornate alla scadenza non sono
 * trentadue.
 */
@Composable
private fun Figurina(
    row: PlayerRow,
    giornata: Int,
    incarichi: List<dev.mfoot.core.match.MatchDuty>,
) {
    val player = row.player

    Column(Modifier.padding(horizontal = MFootSpacing.related)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MFootShapes.band)
                .background(
                    Brush.linearGradient(
                        listOf(MFootColors.blueDeep, Color(0xFF16307E), MFootColors.core),
                    ),
                ),
        ) {
            // Gli archi concentrici del riferimento, gli stessi delle testate: e' cio' che
            // fa entrare la scheda nell'app invece di farla sembrare un'altra app.
            Archi(Modifier.align(Alignment.BottomEnd))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    Modifier.width(78.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        player.overall.toString(),
                        style = MFootType.overallHero,
                        color = Color.White,
                    )
                    Text("OVR", style = MFootType.label, color = Color.White.copy(alpha = 0.62f))
                    Spacer(Modifier.height(8.dp))
                    Targhetta(
                        player.primaryPosition.short,
                        Color.White.copy(alpha = 0.13f),
                        Color.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    Gradino(row)
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        player.firstName,
                        style = MFootType.givenName,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                    Text(player.lastName, style = MFootType.playerName, color = Color.White)

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChipChiaro("${bandiera(player.nationality)} ${player.age} anni")
                        player.secondaryPositions.firstOrNull()?.let { ChipChiaro("anche ${it.short}") }
                    }

                    // Gli incarichi che ha in questa formazione. Stanno qui perche' la
                    // scheda e' dove si decide: si guarda il tiro di uno e si capisce che
                    // i rigori dovrebbe calciarli lui.
                    if (incarichi.isNotEmpty()) {
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            incarichi.take(2).forEach { duty ->
                                Text(
                                    duty.label,
                                    style = MFootType.chip,
                                    color = MFootColors.onAccent,
                                    modifier = Modifier
                                        .background(MFootColors.elite, MFootShapes.pill)
                                        .padding(horizontal = 9.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    row.contratto?.let { contratto ->
                        val restano = contratto.matchDaysLeft(giornata)
                        // Sotto le otto giornate cambia il colore, non solo il numero: e'
                        // la soglia oltre la quale il contratto smette di essere un
                        // dettaglio anagrafico e diventa la cosa che decide l'acquisto.
                        val inScadenza = restano <= SCADENZA_VICINA
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (inScadenza) "Contratto in scadenza: $restano giornate"
                            else "Contratto: $restano giornate",
                            style = MFootType.chip,
                            color = if (inScadenza) MFootColors.gamble else Color.White.copy(alpha = 0.62f),
                        )
                    }
                }
            }
        }

        // Quanto puo' arrivare e quanto ne sai, in una riga: e' la stessa frase che stava
        // sotto la barra, e non ha mai avuto bisogno della barra per essere letta.
        Spacer(Modifier.height(9.dp))
        Text(
            growthDetail(row),
            style = MFootType.chip,
            color = MFootColors.ink2,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    Spacer(Modifier.height(MFootSpacing.section))
}

/** Giornate sotto le quali un contratto si segnala da solo. */
private const val SCADENZA_VICINA = 8

/**
 * Il gradino della crescita: il margine, sotto l'overall invece che accanto.
 *
 * Chi e' arrivato non legge una barra piena ne' una vuota, legge **AL MAX**: la maturita'
 * e' un traguardo, e una barra — comunque la si riempia — dice sempre il contrario.
 */
@Composable
private fun Gradino(row: PlayerRow) {
    val margine = row.estimate.last - row.player.overall

    when {
        row.hasUpside && margine > 0 ->
            Targhetta("+$margine", MFootColors.gamble.copy(alpha = 0.20f), MFootColors.gamble)

        row.player.age >= 30 ->
            Targhetta("IN CALO", Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.70f))

        else ->
            Targhetta("AL MAX", MFootColors.elite.copy(alpha = 0.18f), MFootColors.elite)
    }
}

/** Il rettangolino sotto l'overall: ruolo e margine hanno la stessa forma di proposito. */
@Composable
private fun Targhetta(testo: String, fondo: Color, inchiostro: Color) {
    Text(
        testo,
        style = MFootType.value,
        color = inchiostro,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(fondo, RoundedCornerShape(9.dp))
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun ChipChiaro(text: String) {
    Text(
        text = text,
        style = MFootType.chip,
        color = Color.White.copy(alpha = 0.86f),
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.12f), MFootShapes.pill)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** I quattro archi concentrici, disegnati e non ritagliati da un'immagine. */
@Composable
private fun Archi(modifier: Modifier = Modifier) {
    Canvas(modifier.size(190.dp)) {
        val centro = Offset(size.width * 0.86f, size.height * 0.94f)
        repeat(4) { index ->
            drawCircle(
                color = MFootColors.blueArc.copy(alpha = 0.16f),
                radius = size.minDimension * (0.28f + index * 0.17f),
                center = centro,
                style = Stroke(width = 1.4.dp.toPx()),
            )
        }
    }
}

/**
 * Quanto puo' arrivare, e **quanto ne sai**.
 *
 * ## Perche' la seconda meta' conta quanto la prima
 *
 * Una forbice larga puo' voler dire due cose opposte: che il giocatore e' imprevedibile,
 * o che non lo hai mai visto giocare. Senza dirlo, chi guarda non sa se aspettare che si
 * stringa o se quello e' tutto cio' che si potra' mai sapere — e la scommessa, che e' la
 * meccanica centrale del gioco, resta muta.
 *
 * La conoscenza cresce con i minuti che lo hai visto in campo e con gli osservatori che
 * paghi. E' il motivo per cui vale la pena assumerli, e il motivo per cui un giovane
 * comprato oggi e' un rischio piu' grande di uno cresciuto in casa.
 */
private fun growthDetail(row: PlayerRow): String {
    val base = if (row.hasUpside) {
        "Potrebbe arrivare fra ${row.estimate.first} e ${row.estimate.last}"
    } else {
        "Tetto stimato ${row.estimate.last}"
    }

    val quanto = when {
        row.knowledge >= 70 -> "lo conosci bene"
        row.knowledge >= 40 -> "lo conosci abbastanza"
        row.knowledge >= 15 -> "lo conosci poco"
        // Zero copre due casi che si assomigliano: non lo hai mai visto, oppure la lega
        // non ha ancora lo scouting. In tutti e due la frase e' vera.
        else -> "non lo conosci"
    }
    return "$base · $quanto"
}

/**
 * Gli attributi fuori ruolo restano **visibili ma spenti**: cosi' tutte le schede hanno
 * la stessa altezza e si vede comunque che un difensore ha 41 di tiro.
 */
/**
 * Gli attributi che il ruolo pesa davvero, **sei in tre colonne**.
 *
 * ## Perche' sei e non dodici
 *
 * Perche' dodici attributi in due colonne sono sei righe di lettura per rispondere a una
 * domanda — quanto e' forte in cio' che fara' in campo — a cui i primi sei rispondono da
 * soli: sono quelli con cui `Position.ovrWeights` calcola l'overall, cioe' esattamente
 * quelli che contano per quel ruolo.
 *
 * Gli altri restano raggiungibili sotto, **visibili ma spenti**: si vede comunque che un
 * difensore ha 41 di tiro, e nessuna scheda cambia altezza a seconda del ruolo.
 */
@Composable
private fun Attributes(row: PlayerRow) {
    val player = row.player
    val chiave = player.primaryPosition.relevantAttributes.take(ATTRIBUTI_IN_VISTA)
    val altri = player.primaryPosition.displayAttributes().filterNot { it in chiave }

    Column(Modifier.padding(horizontal = MFootSpacing.gutter)) {
        // Il respiro sopra il titolo. Senza, "Pronto a giocare" della sezione precedente
        // finisce appiccicato a "ATTRIBUTI" e le due sezioni si leggono come una sola —
        // visto sull'emulatore, non dedotto dal codice.
        Spacer(Modifier.height(MFootSpacing.section))
        SectionLabel("ATTRIBUTI")
        Spacer(Modifier.height(MFootSpacing.related))

        chiave.chunked(3).forEach { terna ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MFootSpacing.gridHorizontal),
            ) {
                terna.forEach { attr ->
                    Box(Modifier.weight(1f)) {
                        AttributeCell(attr, player.attributes[attr], key = true)
                    }
                }
                repeat(3 - terna.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(MFootSpacing.gridVertical))
        }

        if (altri.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            altri.chunked(3).forEach { terna ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MFootSpacing.gridHorizontal),
                ) {
                    terna.forEach { attr ->
                        Box(Modifier.weight(1f)) {
                            AttributeCell(attr, player.attributes[attr], key = false)
                        }
                    }
                    repeat(3 - terna.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(MFootSpacing.gridVertical))
            }
        }
    }

    Spacer(Modifier.height(MFootSpacing.related))
}

/** Quanti attributi stanno nella fascia in evidenza. */
private const val ATTRIBUTI_IN_VISTA = 6

/**
 * Le etichette accorciate per la griglia a tre colonne.
 *
 * ## Perche' non basta `Attr.label`
 *
 * Perche' in tre colonne una cella e' larga un terzo di schermo, e «Intercettazione»
 * accanto al suo numero non ci sta: sull'emulatore il numero **si spezzava a meta'** e si
 * leggeva «Intercettazione 7 / 3». Con due colonne non succedeva, ed e' esattamente il
 * tipo di difetto che non si vede leggendo il codice.
 *
 * L'accorciamento sta qui e non in `core`: `Attr.label` e' il nome del dato, e serve
 * intero dove c'e' spazio.
 */
private fun etichettaCorta(attr: Attr): String = when (attr) {
    Attr.INTERCETTAZIONE -> "Intercett."
    Attr.POSIZIONAMENTO -> "Posizione"
    Attr.VELOCITA -> "Velocità"
    else -> attr.label
}

@Composable
private fun AttributeCell(attr: Attr, value: Int, key: Boolean) {
    Column(Modifier.alpha(if (key) 1f else 0.42f)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                etichettaCorta(attr),
                style = MFootType.secondary,
                color = if (key) MFootColors.ink else MFootColors.ink2,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                value.toString(),
                style = MFootType.value,
                color = MFootColors.rating(value),
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(MFootColors.bg, RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value / 99f)
                    .height(4.dp)
                    .background(MFootColors.rating(value), RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MFootType.label, color = MFootColors.ink3)
        Spacer(Modifier.width(9.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MFootColors.line),
        )
    }
}

/**
 * Come sta **adesso**: stamina, morale, forma, infortuni.
 *
 * ## Perche' e' la sezione che mancava di piu'
 *
 * La stamina e' il vincolo centrale del gioco — e' il motivo per cui serve una rosa
 * profonda, il motivo per cui esiste la Primavera, il motivo per cui non si schierano
 * sempre gli undici migliori. Il numero c'era nel database dal primo giorno, arrivava sul
 * telefono dentro `players_public`, ed **era scritto in nessun posto**: ne' nella rosa, ne'
 * nella scheda, ne' nel campo.
 *
 * Il risultato e' un gioco che chiede di ruotare la rosa senza dire mai quando. Un
 * regolamento che non si puo' leggere non e' un regolamento: e' una sorpresa.
 *
 * ## Le quattro cose sono diverse fra loro, e vanno lette insieme
 *
 * La **stamina** si consuma giocando e torna col riposo: dice se puo' scendere in campo
 * oggi. Il **morale** viene dallo spogliatoio e dalle promesse mantenute: dice quanto ci
 * mette. La **forma** e' il momento, va da -5 a +5 e cambia da sola. L'**infortunio** e'
 * l'unico che non e' una sfumatura: o c'e' o non c'e'.
 */
@Composable
private fun Condizione(row: PlayerRow, giornata: Int) {
    val p = row.player
    val fuoriFino = p.injuredUntil?.value
    val infortunato = fuoriFino != null && fuoriFino >= giornata

    Column(Modifier.padding(horizontal = MFootSpacing.gutter)) {
        Spacer(Modifier.height(MFootSpacing.section))
        SectionLabel("COME STA")
        Spacer(Modifier.height(MFootSpacing.related))

        Barra("Stamina", p.stamina, 100, coloreStamina(p.stamina))
        Spacer(Modifier.height(9.dp))
        Barra("Morale", p.morale, 100, coloreMorale(p.morale))
        Spacer(Modifier.height(9.dp))
        // La forma va da -5 a +5: si mostra spostata, altrimenti una barra vuota
        // sembrerebbe un giocatore senza forma invece che uno in forma pessima.
        Barra(
            etichetta = "Forma",
            valore = p.form + 5,
            massimo = 10,
            colore = if (p.form >= 0) MFootColors.elite else MFootColors.gamble,
            testo = if (p.form > 0) "+${p.form}" else "${p.form}",
        )

        Spacer(Modifier.height(12.dp))
        Text(
            when {
                infortunato -> "Infortunato: torna alla giornata $fuoriFino."
                p.stamina < 40 -> "Stanco: schierarlo così vuol dire un rendimento sotto il suo valore."
                p.stamina < 70 -> "Non fresco. Con una giornata di riposo torna al massimo."
                else -> "Pronto a giocare."
            },
            style = MFootType.chip,
            color = if (infortunato || p.stamina < 40) MFootColors.gamble else MFootColors.ink3,
        )
    }
}

/** Una barra con l'etichetta a sinistra e il numero a destra. */
@Composable
private fun Barra(
    etichetta: String,
    valore: Int,
    massimo: Int,
    colore: Color,
    testo: String = "$valore",
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                etichetta,
                style = MFootType.chip,
                color = MFootColors.ink3,
                modifier = Modifier.weight(1f),
            )
            Text(testo, style = MFootType.value, color = colore)
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(MFootColors.line, MFootShapes.pill),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((valore.toFloat() / massimo).coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(colore, MFootShapes.pill),
            )
        }
    }
}

private fun coloreStamina(valore: Int): Color = when {
    valore >= 70 -> MFootColors.elite
    valore >= 40 -> MFootColors.gamble
    else -> MFootColors.gamble
}

private fun coloreMorale(valore: Int): Color =
    if (valore >= 50) MFootColors.elite else MFootColors.gamble

/**
 * Presenze, gol, media voto.
 *
 * ## Perche non c era
 *
 * Perche fino a ieri non esisteva `appearances`: la formazione salvata era una riga per
 * club, sovrascritta, e di chi avesse giocato la settimana scorsa non restava traccia. Gli
 * attributi dicono quanto vale; questo dice **cosa ha fatto**, che e la domanda che ci si
 * fa prima di rinnovargli il contratto.
 */
@Composable
private fun Carriera(carriera: dev.mfoot.android.data.Carriera) {
    if (carriera.vuota) return

    Column(Modifier.padding(horizontal = MFootSpacing.gutter)) {
        Spacer(Modifier.height(MFootSpacing.section))
        SectionLabel("IN CAMPO")
        Spacer(Modifier.height(MFootSpacing.related))

        // I numeri, non le sole etichette. Cinque delle sei caselle passavano una stringa
        // vuota: la sezione esisteva, aveva i titoli giusti, ed era vuota. Chi la guardava
        // vedeva "Presenze" con sotto il nulla e concludeva che le presenze non si
        // contassero -- mentre `appearances` le contava da sempre.
        Row(Modifier.fillMaxWidth()) {
            Voce("Presenze", "${carriera.presenze}", Modifier.weight(1f))
            Voce("Da titolare", "${carriera.daTitolare}", Modifier.weight(1f))
            Voce("Minuti", "${carriera.minuti}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Voce("Gol", "${carriera.gol}", Modifier.weight(1f))
            Voce("Assist", "${carriera.assist}", Modifier.weight(1f))
            Voce("Media voto", voto(carriera.mediaVoto), Modifier.weight(1f))
        }

        if (carriera.gialli > 0 || carriera.rossi > 0) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Voce("Gialli", "${carriera.gialli}", Modifier.weight(1f))
                Voce("Rossi", "${carriera.rossi}", Modifier.weight(1f))
                Voce("", "", Modifier.weight(1f))
            }
        }
    }
}

/** Un voto con la virgola, che e come si scrive in italiano. */
private fun voto(valore: Double): String =
    (StrictMath.round(valore * 10.0) / 10.0).toString().replace(Char(46), Char(44))

@Composable
private fun Voce(etichetta: String, valore: String, modifier: Modifier) {
    Column(modifier) {
        Text(valore, style = MFootType.value, color = MFootColors.ink)
        Spacer(Modifier.height(2.dp))
        Text(etichetta, style = MFootType.label, color = MFootColors.ink3)
    }
}

@Composable
private fun Stars(row: PlayerRow) {
    Row(
        Modifier
            .padding(horizontal = MFootSpacing.gutter)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MFootSpacing.gutter),
    ) {
        StarGroup("PIEDE DEBOLE", row.player.weakFoot, Modifier.weight(1f))
        StarGroup("TECNICA", row.player.skillStars, Modifier.weight(1f))
    }
    Spacer(Modifier.height(MFootSpacing.section))
}

@Composable
private fun StarGroup(label: String, filled: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MFootType.label, color = MFootColors.ink3)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(5) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (index < filled) MFootColors.elite else MFootColors.bg,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun Traits(row: PlayerRow) {
    val traits = row.player.traits
    Row(
        Modifier
            .padding(horizontal = MFootSpacing.gutter)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (traits.isEmpty()) {
            Text(
                "Nessun tratto noto",
                style = MFootType.chip,
                color = MFootColors.ink3,
                modifier = Modifier
                    .background(MFootColors.bg, MFootShapes.pill)
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        } else {
            traits.take(2).forEach { trait ->
                Text(
                    trait.label,
                    style = MFootType.chip,
                    // Era un verde scritto a mano, sopravvissuto al cambio di pelle del
                    // 2026-08-23 perche' stava fuori dal tema: uno dei quattro colori che
                    // riscrivere `Theme.kt` non aveva potuto raggiungere.
                    color = MFootColors.elite,
                    modifier = Modifier
                        .background(MFootColors.elite.copy(alpha = 0.09f), MFootShapes.pill)
                        .border(1.dp, MFootColors.elite.copy(alpha = 0.22f), MFootShapes.pill)
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(MFootSpacing.section))
}

@Composable
private fun Footer(
    row: PlayerRow,
    prezzoSvincolato: Int?,
    canAuction: Boolean,
    isSelling: Boolean,
    youthAction: String?,
    onYouth: () -> Unit,
    onAuction: () -> Unit,
    onClose: () -> Unit,
    onCompra: () -> Unit,
    onVendi: () -> Unit,
    onRitira: () -> Unit,
    onSvincola: () -> Unit,
    onContesta: () -> Unit,
) {
    val mio = row.club?.isMine == true
    val inVendita = row.inVendita
    val acquisto = row.acquisto?.takeIf { it.aperto() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MFootColors.line),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(MFootSpacing.gutter, 13.dp),
    ) {
        // L'azione grossa, quella per cui si e' aperta la scheda. Ce n'e' **una sola**:
        // due pulsanti pieni fianco a fianco costringono a leggerli entrambi ogni volta.
        when {
            // Contestare vale sugli acquisti altrui durante la finestra delle 12 ore:
            acquisto != null && !mio -> Azione(
                testo = "Contesta · restano ${acquisto.tempoRimasto()}",
                fondo = MFootColors.gamble,
                inchiostro = MFootColors.bg,
                onClick = onContesta,
            )

            inVendita != null && !mio -> Azione(
                testo = "Compra · ${inVendita.price}",
                fondo = MFootColors.elite,
                inchiostro = MFootColors.onAccent,
                onClick = onCompra,
            )

            // Uno svincolato si compra sempre, senza che nessuno lo abbia messo in
            // vendita: e' meta' della regola del 2026-08-24, ed e' la meta' che alla
            // prima consegna non esisteva.
            prezzoSvincolato != null && !mio -> Azione(
                testo = "Compra · $prezzoSvincolato",
                fondo = MFootColors.elite,
                inchiostro = MFootColors.onAccent,
                onClick = onCompra,
            )

            mio && inVendita != null -> Azione(
                testo = "In vendita a ${inVendita.price} · ritira",
                fondo = MFootColors.raised,
                inchiostro = MFootColors.ink,
                onClick = onRitira,
            )

            // IL GIOCATORE COSTRUITO DAL PROPRIETARIO NON SI VENDE
            //
            // La regola c'era gia' in `core` e in `list_player`, e qui il pulsante
            // compariva lo stesso: si toccava e tornava un errore dal server. E' proprio
            // il caso che `docs/REGOLE.md` chiama per nome — *un pulsante che si puo'
            // premere e che da' sempre errore insegna a non fidarsi di nessun pulsante*.
            //
            // Al suo posto non resta un buco ma la ragione, perche' senza sembrerebbe una
            // schermata a cui manca qualcosa.
            mio && row.player.isCustom -> Spiegazione(
                "È il tuo giocatore",
                "Non si vende e non si svincola: l'hai costruito tu. Puoi prestarlo, e " +
                    "quella strada passa dalle trattative.",
            )

            mio -> Azione(
                testo = "Metti in vendita",
                fondo = MFootColors.elite,
                inchiostro = MFootColors.onAccent,
                onClick = onVendi,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Money(row.value).format(), style = MFootType.price, color = MFootColors.ink)
                Spacer(Modifier.width(6.dp))
                Text("valore stimato", style = MFootType.chip, color = MFootColors.ink3)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Anche la Primavera si decide guardando la scheda: e' li' che si vede
                // l'eta' accanto alla forbice di crescita.
                youthAction?.let { testo -> Secondaria(testo, onYouth) }

                // Svincolare e' gratis e definitivo, quindi non e' un pulsante pieno:
                // sta fra le azioni di servizio, dove non lo si preme per sbaglio.
                if (mio && !row.player.isCustom) Secondaria("Svincola", onSvincola)

                if (canAuction) Secondaria(if (isSelling) "All'asta" else "Metti all'asta", onAuction)

                Text(
                    "Chiudi",
                    style = MFootType.value,
                    color = MFootColors.bg,
                    modifier = Modifier
                        .background(MFootColors.ink, MFootShapes.pill)
                        .clickable(onClick = onClose)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun Azione(testo: String, fondo: Color, inchiostro: Color, onClick: () -> Unit) {
    Text(
        testo,
        style = MFootType.value,
        color = inchiostro,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(fondo, MFootShapes.pill)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
    )
}

@Composable
private fun Secondaria(testo: String, onClick: () -> Unit) {
    Text(
        testo,
        style = MFootType.value,
        color = MFootColors.ink2,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    )
}
