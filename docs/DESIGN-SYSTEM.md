# Sistema visivo di MFoot

Approvato il 2026-08-16. Riferimento vivo: [`docs/mockups/system.html`](mockups/system.html).

I valori qui sotto sono già nella forma che serve a Compose. Non vanno reinventati a ogni
schermata, e nessun colore o dimensione va scritto a mano dentro un composable.

---

## Il principio: due registri, una lingua

| | **Registro alto** | **Registro calmo** |
|---|---|---|
| Dove | scheda giocatore, asta, gol in diretta, creazione player custom | liste, rosa, classifica, calendario, mercato |
| Come si usa | **una alla volta, con attenzione**, prima di una decisione | **si scorre per venti minuti** cercando qualcosa |
| Trattamento | numeri grandi, verde acceso, accenti, contrasto | densità alta, colore spento, zero effetti |

Stessa palette, stesso carattere, stessi angoli. **Cambia solo il volume.**

Sbagliare registro è il modo più veloce per rendere l'app faticosa: una lista con lo stile
della scheda diventa illeggibile dopo tre schermate.

---

## Colore

```kotlin
object MFootColors {
    val bg        = Color(0xFF07080A)   // fondo dell'app
    val core      = Color(0xFF14171C)   // corpo delle superfici
    val coreTop   = Color(0xFF181C22)   // alto del gradiente di superficie
    val line      = Color(0x12FFFFFF)   // hairline, 7% bianco
    val lineStrong= Color(0x1FFFFFFF)   // hairline in evidenza, 12%

    val ink       = Color(0xFFF2F4F7)   // testo primario
    val ink2      = Color(0xFF9BA3AE)   // testo secondario
    val ink3      = Color(0xFF5F6874)   // etichette, testo terziario
}
```

### La scala dei valori — tre gradini, non una sfumatura

È la decisione di colore più importante dell'app. Il primo tentativo usava cinque
sfumature di verde: un 96 e un 76 sembravano uguali e il colore non serviva a niente.

```kotlin
fun ratingColor(value: Int): Color = when {
    value >= 85 -> Color(0xFF2BE07E)   // eccellenza  — verde acceso
    value >= 70 -> Color(0xFFCFD6DE)   // solido      — bianco freddo
    value >= 55 -> Color(0xFF78828F)   // modesto     — grigio
    else        -> Color(0xFF4E5661)   // carente     — grigio spento
}
```

**Deve essere possibile scansionare una scheda senza leggere un solo numero.**

### L'ambra è riservata alla crescita

```kotlin
val gamble = Color(0xFFFFC53D)
```

Un solo significato in tutta l'app: **margine ancora da conquistare**. Non usarla per
avvisi, errori o evidenziazioni generiche, o smette di voler dire qualcosa.

---

## Tipografia

Il mockup usa Segoe UI, che è un ripiego. **Il carattere definitivo va scelto** — serve una
grottesca con cifre tabulari vere e un peso 600 leggibile a 10px.

Tutti i numeri usano cifre tabulari: senza, le colonne di attributi ballano mentre si
scorre.

| Ruolo | Dimensione | Peso | Spaziatura |
|---|---|---|---|
| Overall grande | 31sp | 600 | −0.03em |
| Nome giocatore | 23sp | 600 | −0.02em |
| Prezzo | 19sp | 600 | −0.02em |
| Overall in lista | 15sp | 600 | −0.02em |
| Nome di battesimo | 15sp | 400 | 0 |
| Riga di lista | 13.5sp | 500 | −0.01em |
| Corpo, valori | 12.5sp | 600 | 0 |
| Secondario | 11.5sp | 400 | 0 |
| Chip, tratti | 11sp | 400 | 0 |
| **Etichette** | 10sp | 400 | **+0.15em, maiuscolo** |

L'ultima riga è quella che fa sembrare l'interfaccia curata: le etichette piccole,
maiuscole e larghe creano il contrasto con i numeri grandi.

---

## Forme e spazio

```kotlin
object MFootShapes {
    val shell  = RoundedCornerShape(26.dp)   // guscio esterno
    val core   = RoundedCornerShape(20.dp)   // nucleo: 26 − 6 di padding
    val band   = RoundedCornerShape(16.dp)   // riquadri interni
    val field  = RoundedCornerShape(12.dp)   // campi, badge ruolo
    val pill   = RoundedCornerShape(50)      // chip, pulsanti
}
```

**Il doppio bordo.** Le superfici importanti non stanno piatte sul fondo: un guscio
esterno con 6dp di padding contiene un nucleo con raggio minore. I due raggi devono essere
concentrici — `26 − 6 = 20` — o la curva stona.

| Spazio | Uso |
|---|---|
| 20dp | margine orizzontale dentro le card |
| 18dp | fra sezioni |
| 12dp | fra elementi correlati |
| 9dp / 18dp | griglia attributi (verticale / orizzontale) |
| 11dp | padding verticale di una riga di lista |

Niente `py-24` da sito vetrina: questa è un'app densa che si usa col pollice.

---

## Movimento

```kotlin
val mfootEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
val durationFast = 400
val durationNormal = 500
```

Mai `LinearEasing`. Mai cambi di stato istantanei. Il tocco su un pulsante lo rimpicciolisce
al 97%, non ne cambia il colore.

---

## Componenti fissati

### Fascia di crescita

L'elemento firma. Ha **due stati distinti**, e la differenza è concettuale prima che
grafica.

**In crescita** — barra bianca per il tratto già percorso, ambra per il margine residuo,
pallino sulla posizione attuale. Etichetta a parole (*"Può crescere molto"*), mai numeri
grezzi tipo `+10/+24` che nessuno decifra. Sotto: *"Oggi 62 · Potrebbe arrivare fra 72 e 86"*.

**Completo** — sfondo e barra verdi, piena. **La maturità è un traguardo, non una mancanza:**
una scheggia di barra comunicherebbe il contrario. Etichetta *"◆ Giocatore completo"*.

Mostrare la fascia solo quando significa qualcosa: vedi `PotentialEstimator.hasUpside()`.

### Attributi

Due colonne. Quelli del ruolo in bianco pieno, gli altri **spenti al 42% ma visibili** — così
la scheda ha sempre la stessa altezza e si vede comunque che un difensore ha 41 di tiro.
Barra da 4dp sotto ogni valore.

### Riga di lista

`[badge ruolo 34dp] [nome + età·nazione] [segnale crescita] [overall] [crediti]`

Il **segnale di crescita** accanto all'overall è ciò che rende la lista utile: si trova un
prospetto scorrendo, senza aprire una scheda per volta. Tre forme: `+24` in ambra,
`al max` spento, `in calo` spento.

---

## Regole che non si violano

1. **Nessun colore o dp scritto a mano** dentro un composable: tutto da qui.
2. **Un solo significato per l'ambra**: margine di crescita.
3. **Cifre tabulari ovunque** ci siano numeri.
4. **Registro giusto**: prima di disegnare una schermata, decidere se si guarda o si scorre.
5. **Il potenziale vero non arriva mai al client.** Il client riceve la stima, mai
   `potentialMin`/`potentialMax`.
