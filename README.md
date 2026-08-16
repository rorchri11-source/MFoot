# MFoot

Gioco manageriale di calcio **multiplayer asincrono** per un gruppo privato di amici.

Un admin crea una lega e ne configura ogni regola, gli altri entrano con un codice. Ognuno
crea il proprio club — nome, maglia — e **un giocatore unico costruito da zero** che dovrà
far crescere. Si compete in campionati e coppe, si comprano giocatori all'asta, si tratta,
si gestiscono contratti, Primavera, staff e morale dello spogliatoio.

Il mondo è **interamente generato proceduralmente**: nessun dato reale, nessuna licenza.

---

## I due principi

> **Il mondo gira senza i client.** Se tutti spengono il telefono per tre giorni, al
> ritorno trovano le giornate giocate, le aste concluse, i contratti scaduti e le squadre
> AI che si sono mosse.

> **Zero numeri nel codice.** Ogni regola vive in `LeagueConfig`, decisa dall'admin. Il
> motore non sa cosa sia "100 crediti": lo chiede alla configurazione. Vale anche per
> l'AI, che ragiona sempre in percentuale del budget disponibile e mai in crediti
> assoluti, così si adatta da sola a qualsiasi economia.

---

## Struttura

```
mfoot/
├── core/     ✅  Kotlin/JVM puro — tutta la logica di gioco, zero dipendenze di piattaforma
├── tick/     🚧  Il programma che fa avanzare il mondo, eseguito da GitHub Actions
└── android/  ⬜  App Jetpack Compose
```

`core` non importa niente di Android, niente di rete, niente di database. Prende oggetti e
restituisce oggetti. È il motivo per cui si testa in venti secondi e per cui ci si possono
far girare diecimila stagioni di seguito per bilanciare il gioco.

```bash
./gradlew :core:test
```

## Come funziona una partita

Il campo è diviso in nove zone (tre fasce × tre altezze). La partita è una sequenza di
~120 azioni: si confronta il rating della zona in cui sta la palla con quello della zona
avversaria che la fronteggia, e una sigmoide trasforma la differenza in probabilità di
avanzare.

Gli highlight risultano irregolari — quindici minuti di nulla, poi tre occasioni in due
minuti — ma **non c'è nessun codice che lo decide**: emerge dal fatto che le catene di
possesso hanno lunghezza variabile.

Il server simula una volta e salva la timeline di eventi; i client la riproducono in
locale. Nessun polling, nessun costo durante i novanta minuti, e chi apre l'app al
sessantesimo salta direttamente al sessantesimo.

## Bilanciamento

Misurato su migliaia di partite simulate, non stimato a occhio:

| Metrica | Valore | Riferimento |
|---|---|---|
| Squadre pari — casa / pari / trasferta | 45,1% / 28,0% / 27,0% | calcio vero: 45 / 27 / 28 |
| Gol a partita | 2,77 | 2,5–3,0 |
| Squadra con +10 di overall | vince il 62% | non deve essere una certezza |
| Catenaccio vs arrembante | 50,5% vs 46,6% | nessun assetto domina |

```bash
./gradlew :core:test --tests "*BalanceReportTest*" -i
```

---

## Documentazione

- [`docs/SETUP.md`](docs/SETUP.md) — come metterlo in piedi da zero
- [`STATO.md`](STATO.md) — cosa è fatto, cosa manca, cosa è stato deciso e perché
- [`docs/superpowers/specs/`](docs/superpowers/specs/) — la specifica completa del design
