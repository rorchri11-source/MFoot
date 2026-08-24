# Sistema visivo di MFoot

Rifatto il **2026-08-23** sul riferimento scelto dal proprietario (le schermate in
`Stile UI MFoot`). La versione precedente — fondo nero, accento verde, schede più chiare
del fondo con un contorno — è archiviata nella storia di git.

I valori qui sotto sono già nella forma che serve a Compose, e stanno in
[`Theme.kt`](../android/src/main/kotlin/dev/mfoot/android/ui/theme/Theme.kt). Non vanno
reinventati a ogni schermata, e nessun colore o dimensione va scritto a mano dentro un
composable.

> **Perché ha funzionato in pochi file.** Prima del cambio, in tutta la cartella `ui/`
> c'erano **quattro** colori scritti a mano fuori dal tema. Tutto il resto passava dai
> token, quindi riscrivere `Theme.kt` ha ridipinto trentacinque schermate insieme. È la
> ragione pratica della prima delle regole in fondo: quel giorno è valsa settimane.

---

## Il principio: due registri, una lingua

| | **Registro alto** | **Registro calmo** |
|---|---|---|
| Dove | scheda giocatore, asta, gol in diretta, creazione player custom | liste, rosa, classifica, calendario, mercato |
| Come si usa | **una alla volta, con attenzione**, prima di una decisione | **si scorre per venti minuti** cercando qualcosa |
| Trattamento | numeri grandi, lavanda acceso, accenti, contrasto | densità alta, colore spento, zero effetti |

Stessa palette, stesso carattere, stessi angoli. **Cambia solo il volume.**

Sbagliare registro è il modo più veloce per rendere l'app faticosa: una lista con lo stile
della scheda diventa illeggibile dopo tre schermate.

---

## Colore

```kotlin
object MFootColors {
    val bg      = Color(0xFF111D2B)   // fondo dell'app, blu notte
    val core    = Color(0xFF0A1622)   // corpo delle schede — PIU' SCURO del fondo
    val coreTop = Color(0xFF102030)   // alto del gradiente di superficie
    val raised  = Color(0xFF1C2836)   // l'unica superficie piu' chiara: il tondo dei vuoti
    val bar     = Color(0xFF0C1520)   // barra in basso

    val line       = Color(0x14FFFFFF)
    val lineStrong = Color(0x24FFFFFF)

    val ink  = Color(0xFFF2F5FA)   // testo primario
    val ink2 = Color(0xFF93A2B8)   // testo secondario
    val ink3 = Color(0xFF5E6E85)   // etichette, testo terziario
}
```

### Le schede sono più scure del fondo

È il tratto che più di ogni altro fa somigliare l'app al riferimento, e **va contro
l'istinto**: di solito una superficie si stacca salendo di luminosità. Qui scende, e il blu
notte del fondo fa da luce intorno. Invertirlo per abitudine disfa l'intera pelle.

L'unica eccezione è `raised`, il tondo grande degli stati vuoti: quello è un disegno, non
un contenitore, e col colore delle schede spariva.

### Due accenti, due mestieri

```kotlin
val elite     = Color(0xFFBCCDFF)   // lavanda: l'accento
val onAccent  = Color(0xFF12275C)   // il testo sopra il lavanda
val blue      = Color(0xFF3F6ADD)   // il blu istituzionale
val blueDeep  = Color(0xFF0A1E86)   // le testate illustrate
val blueArc   = Color(0xFF69C0FF)   // gli archi concentrici
```

Il **blu inquadra** — barra in alto, bande di sezione, icone del menu: dice *dove sei*.
Il **lavanda chiama** — pulsanti primari, voci accese, numeri che contano: dice *cosa
toccare*. Usare l'uno per l'altro è il modo più rapido per ottenere una schermata dove
tutto grida e niente si distingue.

Sul blu notte il lavanda ha più contrasto del blu stesso: è per quello che nel riferimento
i pulsanti importanti sono chiari e non blu.

### La scala dei valori — tre gradini, non una sfumatura

È la decisione di colore più importante dell'app. Il primo tentativo usava cinque
sfumature: un 96 e un 76 sembravano uguali e il colore non serviva a niente.

```kotlin
fun rating(value: Int): Color = when {
    value >= 85 -> elite            // eccellenza — lavanda
    value >= 70 -> Color(0xFFE6ECF5) // solido    — bianco freddo
    value >= 55 -> Color(0xFF8494AC) // modesto   — grigio
    else        -> Color(0xFF55647A) // carente   — grigio spento
}
```

**Deve essere possibile scansionare una scheda senza leggere un solo numero.**

Il gradino alto era verde fino al 2026-08-23. Adesso è il lavanda dell'accento: nel
riferimento il verde non esiste, e un solo colore acceso in tutta l'app significa che
quando compare vuol dire sempre la stessa cosa — questo conta più della tinta.

### L'oro è riservato alla crescita

```kotlin
val gamble = Color(0xFFE9BC5A)
```

Un solo significato in tutta l'app: **margine ancora da conquistare**. Non usarlo per
avvisi, errori o evidenziazioni generiche, o smette di voler dire qualcosa.

### L'allarme, e le quattro tessere

```kotlin
val alarm   = Color(0xFF5D2725)   // fondo del cartellino che avverte
val onAlarm = Color(0xFFFFCAC8)   // il testo sopra

val tileBlue   = Color(0xFF3F6ADD)
val tileGreen  = Color(0xFF7DC63F)
val tileOrange = Color(0xFFF0954A)
val tileRed    = Color(0xFFF04A5E)
```

Le tessere quadrate degli elenchi di impostazioni sono **quattro colori e non dodici**: il
colore raggruppa per famiglia — chi gioca, quanto costa, come si calcola — e con un colore
per voce non raggrupperebbe più niente.

### Il viola spiega

```kotlin
val teach   = Color(0xFF4C0AC4)   // fondo del riquadro che spiega
val onTeach = Color(0xFFD4B8FF)   // il testo dentro
```

È l'unico colore dell'app che non vuol dire né «tocca qui» né «attento»: vuol dire
**«questa cosa è nuova, ecco cos'è»**. Serviva perché le spiegazioni lunghe erano paragrafi
grigi in fondo alla schermata, con lo stesso stile delle didascalie — chi non sapeva cosa
fossero le divisioni non aveva nessun motivo di leggere proprio quel grigio invece di un
altro.

Compare tre o quattro volte in tutta l'app e sempre per la stessa ragione, ed è così che si
impara alla seconda. Se comincia a comparire anche sugli errori, torna a essere un
rettangolo colorato.

---

## Tipografia

Il carattere è ancora quello di sistema. **Va scelto**: serve una grottesca con cifre
tabulari vere e un peso 600 leggibile a 10sp.

Tutti i numeri usano cifre tabulari: senza, le colonne di attributi ballano mentre si
scorre.

| Ruolo | Dimensione | Peso | Spaziatura |
|---|---|---|---|
| Titolo di testata | 29sp | 400 | −0.02em |
| Overall grande | 30sp | 600 | −0.03em |
| Nome giocatore | 22sp | 600 | −0.02em |
| Titolo della barra | 19sp | 500 | −0.01em |
| Prezzo | 19sp | 600 | −0.02em |
| Overall in lista | 16sp | 600 | −0.02em |
| Nome di battesimo | 15sp | 400 | 0 |
| Riga di lista | 15.5sp | 500 | −0.01em |
| Corpo, valori | 14sp | 600 | 0 |
| Secondario | 13sp | 400 | 0 |
| Chip | 12.5sp | 400 | 0 |
| Etichetta della barra | 11.5sp | 500 | 0 |
| **Etichette** | 10.5sp | 400 | **+0.14em, maiuscolo** |

**La scala è salita di due punti** rispetto al 16 agosto. Quella di prima — righe a 13.5,
chip a 11 — era da tabella densa, e su un telefono in mano faceva strizzare gli occhi.
Buona parte della somiglianza col riferimento viene da qui, non dal colore.

L'ultima riga resta quella che fa sembrare l'interfaccia curata: le etichette piccole,
maiuscole e larghe creano il contrasto con i numeri grandi.

---

## Forme e spazio

```kotlin
object MFootShapes {
    val shell = RoundedCornerShape(24.dp)   // guscio esterno
    val core  = RoundedCornerShape(18.dp)   // nucleo: 24 − 6 di padding
    val band  = RoundedCornerShape(18.dp)   // la scheda: la forma piu' frequente
    val field = RoundedCornerShape(14.dp)   // campi, riquadri piccoli
    val tile  = RoundedCornerShape(12.dp)   // la tessera quadrata con l'icona
    val pill  = RoundedCornerShape(50)      // chip, pulsanti, ricerca
}
```

Il riferimento è molto più arrotondato di quanto fosse MFoot. Angoli timidi su fondo scuro
fanno sembrare l'interfaccia una tabella.

**Il doppio bordo** sopravvive solo dove serve davvero — la scheda giocatore: un guscio
esterno con 6dp di padding contiene un nucleo con raggio minore, e i due raggi devono
restare concentrici (`24 − 6 = 18`) o la curva stona.

**Le schede non hanno contorno.** Il distacco lo fanno il colore e lo spazio intorno.
Aggiungere una linea le fa sembrare caselle di un modulo — ed è esattamente il difetto che
il cambio del 23 agosto ha rimosso da venti file.

| Spazio | Uso |
|---|---|
| 18dp | margine orizzontale dentro le superfici |
| 16dp | margine della pagina, fra sezioni |
| 12dp | fra elementi correlati, fra una scheda e la successiva |
| 10dp / 18dp | griglia attributi (verticale / orizzontale) |
| 12dp | padding verticale di una riga di lista |

Niente `py-24` da sito vetrina: questa è un'app densa che si usa col pollice.

---

## Movimento

```kotlin
val mfootEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
val durationFast = 400
val durationNormal = 500
```

Mai `LinearEasing`. Mai cambi di stato istantanei.

---

## Il guscio

**Due intestazioni, non una.** Sui **cinque posti** — quelli a cui si torna dieci volte al
giorno — la barra blu compatta con l'hamburger e il nastro: serve spazio per il contenuto.
Sulle schermate del **menu** la [`Testata`](#il-vocabolario-dei-componenti) illustrata con
gli archi, che porta il titolo grande e la freccia indietro, e sostituisce sia la barra sia
il nastro.

Non è solo estetica: quelle schermate si raggiungono da una porta sola e prima si
lasciavano **solo col gesto di sistema**, e il loro titolo finiva scritto due volte sullo
stesso schermo (sottotitolo della barra e testata della pagina).

**Il blu passa sotto la barra di stato e la colora**, sia nella barra alta sia nella
testata: è il pezzo che dà all'app la sua faccia, e fermarlo qualche pixel più in basso lo
ridurrebbe a una fascia colorata dentro una schermata scura. Perciò `MainActivity` **non**
mette `systemBarsPadding()` alla radice quando si è dentro la lega — se ne occupa il guscio.

**Nastro scuro** sotto la barra: a sinistra dove si gioca (divisione e giornata), a destra
da quanto è fresco il mondo. Non ripetere lì il sottotitolo già scritto due centimetri più
su.

**Barra in basso** con cinque posti: casa, maglia, calendario, medaglia, carrello — ordine
e icone del riferimento. Cinque e non sette: oltre, le etichette diventano indovinelli.

**Menu laterale** con la testata a gradiente, il nome della lega, e tre gruppi a pillola —
**Setup**, **Gioca**, **Gestione**. Sono quelli del riferimento, e combaciano perché sono
le tre domande di chi apre il menu di una lega.

---

## Il vocabolario dei componenti

Tutto in [`Atoms.kt`](../android/src/main/kotlin/dev/mfoot/android/ui/Atoms.kt).

| Pezzo | Cos'è |
|---|---|
| `Scheda` | la superficie scura arrotondata, con la barretta blu opzionale a sinistra |
| `Riga` | stemma tondo + titolo + sottotitolo + numero a destra con etichetta |
| `Tessera` | il quadrato colorato 40dp con l'icona bianca |
| `Banda` | la fascia blu a tutta larghezza: la giornata, la divisione |
| `Cartellino` | il tag che avverte, rosso spento |
| `Striscia` | la riga di numeri con l'etichetta sotto |
| `Testata` | la testata illustrata con gli archi concentrici |
| `Spiegazione` | il riquadro viola con la lampadina: cos'è questa cosa |
| `Avanzamento` | la barra «12 giocate ———— 25 in tutto» |
| `Selettore` | i numeri piccoli in fila, con il tondo su quello scelto |
| `BarraSchede` | le sezioni di un posto: linguette con la sottolineatura blu |
| `Segmentato` | due o tre scelte fisse, tutte visibili |
| `Chip` | un filtro che si accende, in una riga che scorre |
| `Vuoto` | tondo grande, icona, una frase, e il pulsante che risolve |
| `Ricerca` | la pillola con la lente |
| `PrimaryButton` | pillola lavanda piena, testo `onAccent` |
| `Tondo` | il pulsante circolare |

### Le icone sono disegnate a mano

In [`MFootIcons.kt`](../android/src/main/kotlin/dev/mfoot/android/ui/icons/MFootIcons.kt),
contorno 1.9, estremi tondi, griglia 24.

Non `material-icons-extended`: la build di rilascio ha `isMinifyEnabled = false`, quindi
senza R8 quella libreria non viene sfoltita e nell'APK finirebbero migliaia di icone per
usarne trenta. E non i glifi di testo — `⌂`, `⛨`, `⇄` — che erano quello che c'era prima:
ogni telefono li disegnava con un carattere diverso, e su parecchi la barra in basso aveva
cinque icone di cinque pesi diversi.

L'unica costruita con due tracciati è la **medaglia**: nastri pieni e anello spesso. Con
due fili sottili sopra un cerchio di contorno, a ventitré pixel, non era una medaglia ma un
paio di orecchie da coniglio. La massa distingue le due cose, e a quella dimensione non è
decorazione.

---

## Componenti fissati

### Fascia di crescita

L'elemento firma. Ha **due stati distinti**, e la differenza è concettuale prima che
grafica.

**In crescita** — barra chiara per il tratto già percorso, oro per il margine residuo,
pallino sulla posizione attuale. Etichetta a parole (*"Può crescere molto"*), mai numeri
grezzi tipo `+10/+24` che nessuno decifra.

**Completo** — barra lavanda, piena. **La maturità è un traguardo, non una mancanza:** una
scheggia di barra comunicherebbe il contrario.

Mostrare la fascia solo quando significa qualcosa: vedi `PotentialEstimator.hasUpside()`.

### Attributi

Due colonne. Quelli del ruolo in bianco pieno, gli altri **spenti al 42% ma visibili** — così
la scheda ha sempre la stessa altezza e si vede comunque che un difensore ha 41 di tiro.

### Linguette o chip: non sono la stessa cosa

Un **chip** dice «filtro»: si accende, si spegne, se ne possono immaginare due accesi
insieme, e resta al suo posto quando cambia il contenuto. Le **linguette** dicono «dove
sei», e ne esiste sempre esattamente una: la sottolineatura lo comunica, una pillola accesa
fra pillole spente no.

Le sezioni di un posto (Rosa · Campo · Staff …, Aste · Svincolati · Listone …) sono
linguette. I ruoli, gli stati delle aste, le competizioni della classifica sono chip.

> **Trappola di misura.** Dentro un `horizontalScroll` la larghezza massima che arriva ai
> figli è **infinita**, e `fillMaxWidth()` con un vincolo infinito non si applica: la
> sottolineatura veniva misurata zero e non si vedeva. Serve `width(IntrinsicSize.Max)`
> sulla colonna. Stessa famiglia di errore: uno `Spacer(weight)` **verticale** dentro la
> `Testata` la faceva espandere a tutto lo schermo, perché lo spazio libero nella colonna
> del guscio è lo schermo intero. Nelle testate e nelle linguette l'ingombro si decide con
> numeri, non con i pesi.

### Riga di lista

`[tondo col ruolo 46dp] [nome + età·club] [overall] [valore]`

Con il **gettone della crescita** in oro appoggiato sul tondo, quando c'è margine: è ciò
che rende la lista utile: si trova un prospetto scorrendo, senza aprire una scheda per
volta. Sopra la lista, una riga di etichette dice cosa sono i due numeri a destra — senza,
`84` e `12` sono due cifre e basta.

---

## Regole che non si violano

1. **Nessun colore o dp scritto a mano** dentro un composable: tutto da qui.
2. **Un solo significato per l'oro**: margine di crescita.
3. **Il blu inquadra, il lavanda chiama.** Mai scambiarli.
4. **Le schede sono più scure del fondo**, e non hanno contorno.
5. **Cifre tabulari ovunque** ci siano numeri.
6. **Registro giusto**: prima di disegnare una schermata, decidere se si guarda o si scorre.
7. **Il potenziale vero non arriva mai al client.** Il client riceve la stima, mai
   `potentialMin`/`potentialMax`.
