# La disciplina di spesa dell'AI

**Data:** 18 agosto 2026
**Stato:** approvato, da implementare

## Il problema

Nella lega vera "Milioni", otto club AI con la rosa vuota tenevano aperte otto aste
**tutte su giocatori da 83 a 86**, a prezzi fra 4M e 13M, ognuna con due offerte. Le rose
contavano 0, 0, 0, 0, 1, 2, 1, 2 giocatori. I crediti impegnati per club andavano da 14M a
**42M** — su un budget di 100M e con diciassette caselle ancora da riempire.

Nessuna partita si poteva giocare, perche' nessun club arrivava al minimo di rosa.

La causa e' una riga sola, in `AiManager.ceilingFor`:

```kotlin
val maxShare = MathX.lerp(0.45, 0.18, personality.budgetDiscipline)
val hardCap = available * maxShare
```

Il tetto di spesa per un singolo giocatore e' fra il 18% e il 45% del disponibile, e quella
frazione **non sa quante caselle restano da riempire**. Con 100M e diciotto slot la media
per casella e' 5,5M: un tetto del 45% autorizza a spenderne 45 su un uomo solo.

Non e' un difetto di prezzo. La curva dei prezzi e' gia' stata corretta e misurata
(`PriceScaleTest`): un 71 costa 1,3M, non 15M. E' un difetto di **disciplina**: nessuno
mette da parte i soldi per gli uomini che mancano.

### Perche' non e' stato visto prima

I test dell'AI verificano **una decisione alla volta**: "questa AI fa un'offerta per questo
giocatore?", "il tetto rispetta la personalita'?". Sono tutti verdi, e lo erano anche
mentre la lega restava ferma.

Nessun test faceva la domanda che conta: *finito il mercato, i club hanno una squadra?*

## La soluzione

### Il tetto conosce le caselle che restano

```
casellePerRiempire = max(1, minSquadSize − giocatoriInRosa)
mediaPerCasella    = disponibile / casellePerRiempire
tetto              = min(desiderato, mediaPerCasella × sforo, disponibile − riserva)
```

Con 100M, diciotto caselle e `sforo = 3` il tetto sul primo acquisto e' **16,7M**: bastano
per tre o quattro pezzi pregiati, poi la media cala da sola e il club e' costretto a
riempire con giocatori onesti. E' la forma di rosa scelta: *un paio di stelle e tanti
onesti*.

### Lo sforo lo decide la personalita'

`budgetDiscipline` smette di scegliere una percentuale cieca e sceglie **quanto ci si
permette di sforare la media**:

```
sforo = lerp(4.0, 2.0, personality.budgetDiscipline)
```

Un'AI spericolata (`budgetDiscipline` 0,3) arriva a ~3,6 volte la media; una prudente
(1,0) si ferma a 2. Il carattere resta visibile, ma nessun carattere autorizza a rovinarsi.

### La riserva per le ultime caselle

```
riserva = (casellePerRiempire − 1) × config.market.minimumRaise
```

A inizio mercato e' irrilevante (diciassette caselle per 100K fanno 1,7M su 100M); alla
fine e' decisiva, perche' impedisce di arrivare a diciassette giocatori senza un euro per
il diciottesimo.

Il prezzo di riferimento e' **il rilancio minimo**, non un valore inventato ne' il prezzo
del giocatore piu' scarso del mondo. Il motivo e' che il rilancio minimo e' esattamente la
cifra sotto la quale **nessuna asta puo' essere vinta**: e' il pavimento vero del mercato,
e' gia' una manopola dell'admin, e si adatta da solo a una lega povera come a una ricca.

### Chi ha gia' la rosa a posto torna alle vecchie regole

Quando `giocatoriInRosa >= minSquadSize` non c'e' nessun obbligo da proteggere, e vale il
tetto per personalita' di oggi (18–45% del disponibile). E' il mercato a stagione in corso,
dove spendere molto su un rinforzo e' una scelta legittima e non un suicidio.

La condizione e' la stessa gia' usata in `TickRunner` per distinguere il mercato veloce da
quello calmo — la rosa, non lo stato della lega.

## Il test che serviva

Un test di aggregato in `core`, che simula un **mercato iniziale intero**:

- otto club AI con personalita' generate dal seed vero;
- il mondo generato vero, non giocatori costruiti a tavolino;
- un ciclo di risvegli: ogni club valuta, apre o rilancia, le aste si chiudono e assegnano;
- il ciclo gira per un numero di giri pari a quelli che il tick farebbe in poche ore.

Alla fine pretende:

1. **ogni club raggiunge `minSquadSize`** — e' l'asserzione che sarebbe caduta;
2. nessun club scende sotto zero crediti;
3. ogni club ha almeno un portiere;
4. la forma della rosa e' quella scelta: almeno una stella (sopra la media della lega di un
   margine) e la maggioranza dei giocatori sotto il doppio della media per casella.

Il quarto punto e' quello che **misura lo sforo** invece di sceglierlo: se con 3 le rose
escono piatte o squilibrate, si vede qui.

Il test non usa il database: la simulazione vive in `core`, con le stesse funzioni pure che
il tick chiama. Non e' una riproduzione del tick — e' il modello di mercato che il tick
esegue.

## Cosa **non** cambia

- **La curva dei prezzi.** E' gia' misurata e va bene.
- **La penalita' di affollamento.** Resta com'e'.
- **Il numero di aste in parallelo e la durata** durante l'allestimento. Gia' corretti.
- **La stima del potenziale.** L'AI continua a vedere quello che vedrebbe un umano.

## Fuori perimetro, dichiarato

- **La varieta' degli obiettivi.** Che tutte e otto le aste finiscano su giocatori 83–86
  resta un fatto sospetto: la penalita' di affollamento evidentemente non basta a spingere
  le AI su uomini diversi. La disciplina di spesa lo attenuera' da sola — un club che puo'
  spendere 16M e non 45M guarda piu' in basso — ma se dopo la correzione le aste restano
  tutte sui fuoriclasse, e' un lavoro a se'.
- **L'esecuzione delle divisioni.** Promozioni e retrocessioni hanno regole scritte e
  testate ma nessuno che le applichi. Viene dopo: senza partite giocate non ha senso
  decidere chi retrocede.
- **Le schermate segnaposto** (Profilo lega, Partecipanti, Mercati, Registro attivita').
- **La lega "Milioni".** Resta com'e'. Le aste gia' aperte non si annullano: la prossima
  lega sara' quella buona.

## Rischi

**Lo sforo potrebbe essere il numero sbagliato.** E' il motivo per cui il test lo misura
invece di darlo per buono. Se con 3 le rose non si riempiono, il test lo dice prima che lo
dica una lega ferma.

**Il tetto potrebbe rendere le AI troppo prevedibili.** Tutte con lo stesso vincolo
comprano in modo simile. La personalita' resta la leva che le differenzia, e il quarto
punto del test verifica che le rose non escano identiche.

**La simulazione potrebbe divergere dal tick.** Se il modello di mercato del test e quello
che il tick esegue si separassero, il test passerebbe mentre la lega si ferma — cioe'
esattamente il difetto di oggi, in una forma nuova. Le funzioni pure devono restare le
stesse: la simulazione chiama `AiManager`, non lo riscrive.

## File toccati

| File | Cosa |
|---|---|
| `core/.../ai/AiManager.kt` | `ceilingFor` prende le caselle che restano; nuova formula |
| `core/src/test/.../ai/MarketSimulationTest.kt` | il test di aggregato (nuovo) |
| `core/src/test/.../ai/AiTest.kt` | adeguare i test del tetto alla nuova firma |

Nessuna migrazione SQL: il difetto e la correzione vivono interamente in `core`, e la
configurazione usa manopole che esistono gia' (`minSquadSize`, `minimumRaise`).
