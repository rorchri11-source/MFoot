# I duelli in campo

*Progetto del 2026-08-29 — profondità e realismo dentro i novanta minuti.*

## Il problema, misurato

Il motore attuale risolve ogni azione con **un numero contro un numero**:

```kotlin
val delta = attacker.rating(zone) - defender.rating(zone.mirror())
val advanceChance = MathX.sigmoid(delta, engine.sigmoidK)
```

`ZoneRatings` collassa gli undici in una media per zona **prima** che accada qualsiasi
cosa. Da lì in poi i singoli tornano solo per attribuire l'evento a un nome — il nome è
decorazione applicata a un esito già deciso.

Tre conseguenze verificate leggendo il codice:

1. **Nessun attributo decide mai un episodio individuale.** `MatchEngine` legge
   direttamente solo `TIRO`, `TECNICA`, `FISICO`, `POSIZIONAMENTO`, `PASSAGGIO` e i due
   da portiere. `DRIBBLING`, `VELOCITÀ`, `DIFESA`, `INTERCETTAZIONE` entrano soltanto
   dentro `BandWeights`, cioè dentro la media di zona: contribuiscono a un numero
   collettivo e non vincono mai niente da soli.

2. **Due giocatori con lo stesso overall giocano la stessa partita.** Un'ala 88 di
   velocità / 60 di dribbling e una 60/88 producono lo stesso `rating(ATT_DX)`, quindi
   letteralmente lo stesso esito atteso in ogni azione.

3. **I dodici tratti dentro la partita pesano quasi niente.** L'unico uso durante il
   gioco è `injuryFactor`, `staminaFactor`, `bigMatchBonus`, e il peso del rigorista.
   `TESTA_CALDA`, `INCOSTANTE`, `LEADER`, `UOMO_SPOGLIATOIO` non muovono un solo
   episodio.

Riassunto dal proprietario: *«i giocatori sono numeri, non persone»*.

## La decisione

**Il motore si riscrive sui duelli.** Un'azione non è più un tiro di dado su due medie:
è una catena di **contese fra due giocatori con un nome**.

Scelta consapevolmente contro il consiglio di innestare i duelli dentro l'avanzamento
esistente. Il costo accettato è la taratura: le costanti che oggi producono i numeri
misurati perdono significato e vanno rifatte. La contropartita è che la profondità
arriva a tutte le azioni, non solo a quelle dell'ultimo terzo.

**La profondità si ferma dentro i novanta minuti.** Nessun intervento in diretta:
niente cambi manuali durante la partita, niente istruzioni al volo. Chi prepara bene
gioca bene, e chi dorme alle tre di notte non perde per questo. Restano gli ordini
condizionali, che sono già completi in `core` e servono esattamente a questo.
*Deciso il 2026-08-29.*

## Cosa muore e cosa resta

| | |
|---|---|
| **Muore** | Il rating di zona come **decisore**: `delta → sigmoid → advanceChance`. Con lui si riscrive tutto `Simulation.step`, che oggi è un dado e diventa scelta dell'intenzione, estrazione dell'avversario, contesa, esito. |
| **Resta** | Le nove zone come **geografia**: `advance()`, `mirror()`, `isAttacking`. |
| **Resta** | `ZoneStrength.contributions` come **anagrafe**: chi gravita dove. Oggi serve a estrarre chi tocca la palla, domani serve a estrarre anche l'avversario. È il pezzo che rende possibile tutto il resto senza inventare niente. |
| **Resta calcolato** | `ZoneStrength.ratings` e `teamOverall`, perché li usano classifiche, valutazione AI e test. Smettono soltanto di decidere le partite. |
| **Resta intatto** | `Conclusioni` e tutto il livello del tiro. |

L'ultima riga è la scelta di perimetro più importante di questo progetto. Il livello del
tiro — che conclusione è, chi la prende fra gli undici, quanto vale — è stato misurato il
2026-08-29 e dà 66% attacco / 20% centrocampo / **12% difesa** con venti marcatori
diversi. **Non si tocca.** I duelli riguardano *come si arriva* al tiro, non il tiro.
Toccare entrambi nella stessa riscrittura significherebbe non sapere più quale dei due
ha spostato un numero.

## I cinque contesti

Un contesto è una funzione pura: due giocatori (o un giocatore e una zona) dentro, una
probabilità fuori.

```kotlin
enum class Duello { CORSA, DRIBBLING, CONTRASTO, AEREO, PASSAGGIO }

/** Da che parte del duello si sta: chi ha la palla o chi la vuole. */
enum class Lato { ATTACCO, DIFESA }

object Duelli {
    /** Quanto vale questo giocatore in questo duello, da quel lato. Scala 1-99. */
    fun valore(duello: Duello, lato: Lato, player: Player): Double

    /** La probabilità che chi ha la palla la spunti. */
    fun esito(duello: Duello, attacco: Double, difesa: Double, engine: EngineConfig): Double
}
```

| Contesto | Chi ha la palla | Chi si oppone | Decisività |
|---|---|---|---|
| **Corsa** — lo scatto sul filtrante, il pallone sporco | `VELOCITÀ` .60, `POSIZIONAMENTO` .40 | `VELOCITÀ` .60, `POSIZIONAMENTO` .40 | **alta** |
| **Dribbling** — saltare l'uomo | `DRIBBLING` .45, `TECNICA` .30, `VELOCITÀ` .25 | `DIFESA` .40, `POSIZIONAMENTO` .35, `VELOCITÀ` .25 | media |
| **Contrasto** — l'entrata | `FISICO` .55, `TECNICA` .45 | `DIFESA` .40, `INTERCETTAZIONE` .35, `FISICO` .25 | **bassa** |
| **Aereo** — cross, palla lunga, corner | `FISICO` .55, `POSIZIONAMENTO` .45 | `FISICO` .55, `POSIZIONAMENTO` .45 | media |
| **Passaggio** | `PASSAGGIO` .60, `TECNICA` .40 | `INTERCETTAZIONE` della zona d'arrivo | media-alta |

La decisività non è un aggettivo: è la `k` della sigmoide, **una per contesto**, in
`EngineConfig` (`kCorsa`, `kDribbling`, `kContrasto`, `kAereo`, `kPassaggio`). `k` bassa
significa curva ripida, cioè chi è più forte vince quasi sempre.

Detta dal proprietario, ed è la ragione per cui sono cinque manopole e non una:

> *Nella corsa la velocità è quasi decisiva — chi è più veloce ci arriva prima, punto.
> Nel contrasto e nel dribbling conta di più il caso, perché c'entrano la posizione, il
> rimbalzo e l'arbitro.*

**Le tabelle dei pesi stanno nel codice, non in configurazione.** Stessa ragione già
scritta in `Conclusioni`: non sono una manopola della lega, sono cosa vuol dire fare
quella cosa. Che un dribbling dipenda dal dribbling non è una scelta dell'admin. Le `k`
invece sono manopole vere e stanno in `EngineConfig`, come tutto il resto.

**Nessun attributo nuovo.** Tutti e cinque i contesti usano i dodici che esistono.
Aggiungerne uno rigenererebbe il mondo — vincolo già scritto in `REGOLE.md` a proposito
degli incarichi.

Con queste cinque tabelle **tutti e dodici gli attributi decidono episodi.** Nessuno
resta decorativo.

## Cosa prova chi ha la palla

Il contesto non si estrae a caso uniforme: dipende da **chi è** e da **dove sta**, come
`Conclusioni.peso` fa già per i tiri.

```kotlin
fun intenzione(slot: LineupSlot, zona: Zone, tattica: Tactics, rng: DeterministicRandom): Duello
```

Un'ala in `ATT_DX` con dribbling alto prova l'uomo; la stessa ala con velocità alta e
dribbling basso cerca la corsa alle spalle del terzino. Un mediano in `MID_C` passa. Una
punta che riceve in `ATT_C` spalle alla porta va di fisico. La tattica sposta i pesi: il
ritmo alto produce più corse e meno costruzione, il gioco largo più cross.

L'avversario si estrae da `contributions[zona.mirror()]` pesato sulla presenza — chi c'è
di più in quella zona ha più probabilità di essere lui. Un terzino scarso viene affrontato
spesso, ed è esattamente la lettura che il modello a nove zone prometteva di rendere
visibile.

## Il carattere dentro la partita

Tre tratti che oggi non muovono niente nei novanta minuti ne muovono uno:

- **`TESTA_CALDA`** — moltiplicatore sul fallo quando perde un contrasto, e sul cartellino
  quando lo commette. Si fa espellere nella partita che conta, che è quello che il tratto
  promette e che oggi non fa.
- **`INCOSTANTE`** — una **giornata**: un tiro di dado a inizio partita che sposta i suoi
  rating di duello per tutti i novanta minuti, con ampiezza proporzionale a
  `formVolatility`. Il giocatore che «un giorno domina e quello dopo sparisce» oggi ha
  esattamente la stessa partita ogni volta. La giornata vale per tutti — ma per lui vale
  il doppio.
- **`LEADER`** — quando la squadra è sotto negli ultimi quindici minuti, un bonus ai duelli
  dei compagni in campo. Rende verificabile la fascia, come già fa `resistenza` per il
  capitano.

Gli altri nove restano dove sono: `RIGORISTA`, `GRANDI_PARTITE`, `FRAGILE` e
`INSTANCABILE` agiscono già; `UOMO_SPOGLIATOIO`, `AMBIZIOSO`, `FEDELE`,
`TALENTO_PRECOCE` e `MATURAZIONE_TARDIVA` appartengono allo spogliatoio e alla crescita,
e forzarli dentro la partita sarebbe decorazione.

## Il racconto

Ogni duello ha due nomi, quindi la cronaca può finalmente dirli. Sei tipi di evento nuovi
in `MatchEventType`, tutti a pericolosità bassa perché sono rumore di fondo — è il tessuto
della partita, non l'highlight:

| Evento | `baseDanger` | Cronaca |
|---|---|---|
| `DRIBBLING_RIUSCITO` | 16 | «Rossi salta Bianchi sulla fascia» |
| `DRIBBLING_FALLITO` | 14 | «Bianchi lo chiude in scivolata» |
| `SCATTO` | 15 | «Rossi brucia Bianchi in velocità» |
| `ANTICIPO` | 16 | «Verdi anticipa di testa» |
| `PASSAGGIO_FILTRANTE` | 20 | «Neri apre per Rossi dentro l'area» |
| `CROSS` | 24 | «Cross di Rossi, testa di Verdi» |

`AVANZAMENTO` resta per i casi senza contesa. `CONTRASTO` esiste già.

E sei statistiche nuove in `PlayerMatchStats`, che è già l'input diretto del sistema di
crescita:

```kotlin
val duelsWon: Int, val duelsLost: Int,
val dribblesCompleted: Int, val dribblesSuffered: Int,
val passesCompleted: Int, val passesAttempted: Int,
```

`rating()` ne tiene conto con pesi piccoli: un difensore che vince dodici duelli deve
prendere più di 6, e oggi non può perché l'unica cosa che lo riguarda è `tackles * 0.07`.

## Come si difende la taratura

È la parte che rende sopravvivibile una riscrittura, e va letta come parte del progetto,
non come contorno.

**La banda misurata è il collaudo.** Il motore attuale, misurato il 2026-08-29 su
migliaia di partite:

| | |
|---|---|
| Gol a partita | 2,64 |
| Casa / pari / ospite | 45,2% / 27,0% / 27,9% |
| Tiri a partita | 23,8 |
| Conversione | 11,1% |
| Marcatori: att / cen / dif | 66% / 20% / 12% |
| Marcatori diversi su 22 | 20 |

`MatchBalanceTest` verifica già una parte di questi. Va esteso a **tutti**, con bande
esplicite, e va aggiunto a `BalanceHarness.Report` quello che oggi non misura: gol per
ruolo, marcatori diversi, duelli / dribbling / contrasti a partita, precisione dei
passaggi. Confrontabili col calcio vero, non solo col motore di ieri.

**Il vecchio motore resta in piedi finché il nuovo non passa.** Il livello dei duelli
nasce dietro un interruttore in `EngineConfig` (`duelliAttivi`, di serie `false`) e si
accende quando i test sono verdi. Non esiste un giorno in cui il gioco è rotto, e se la
taratura non converge si torna indietro togliendo una riga.

**Le costanti che perdono significato** — `sigmoidK`, `actionsPerMatch`,
`shotChanceInAttackingZone` — restano nel file finché l'interruttore non si accende, poi
si rimuovono in un commit separato dal resto.

## Cosa non si fa

- **Nessun intervento in diretta.** Deciso sopra.
- **Non si tocca `Conclusioni`.** Deciso sopra.
- **Nessun attributo nuovo.** Rigenererebbe il mondo.
- **Nessun modello di posizione continua sul campo.** Le nove zone bastano: sono già la
  base del campo animato e della lettura tattica. Un modello a coordinate sarebbe un
  secondo progetto.
- **Nessuna modifica al database, per davvero.** Le sei statistiche nuove vanno in
  `match_results.player_stats`, che è già `jsonb` e già scritto dal tick: un campo in più
  nel JSON non è una colonna in più. **Non** si toccano le colonne di `appearances`, che
  l'app legge con una `select` a lista esplicita — è precisamente la lettura condivisa che
  la trappola di PostgREST fa esplodere per intero, già pagata due volte con
  `clubs.division_level` e `clubs.parent_club_id`. `appearances` resta l'aggregato di
  stagione: gol, assist, cartellini, voto.
  Conseguenza sul passo 9: il tabellino a fine partita legge le statistiche di duello da
  `player_stats`, non da `appearances`. Chi apre una partita vecchia trova il campo
  assente e vede zero, che è la verità.

## Rischi

**La taratura è il grosso del lavoro, non un rifinitura.** Cinque `k` più i pesi delle
intenzioni sono un ordine di grandezza più di manopole di prima. La difesa è che ognuna
si misura separatamente: la `k` del dribbling si legge nei dribbling riusciti a partita,
non nei gol.

**Una partita in corso all'aggiornamento.** `HalfTimeState` contiene lo stato del primo
tempo; se il secondo tempo viene giocato da un motore diverso, la partita cambia carattere
al 45'. Non è un errore ma si vede. Mitigazione: `HalfTimeState` porta con sé se i duelli
erano attivi quando è stato scritto, e il secondo tempo usa quel motore.

**Il tick non ha test.** Vale da prima di questo progetto ed è già annotato in `STATO.md`,
ma qui pesa di più: il tick è l'unico posto dove le partite vengono davvero giocate.

## Ordine dei lavori

1. `Duelli.kt` in `core` con le cinque tabelle e le cinque sigmoidi, e i suoi test.
2. Le `k` in `EngineConfig` con l'interruttore spento.
3. `BalanceHarness` esteso alle misure nuove; `MatchBalanceTest` con le bande.
4. Il livello duelli dentro `Simulation.step`, dietro l'interruttore.
5. Taratura misurata, iterazione fino alla banda.
6. Eventi, cronaca, statistiche individuali.
7. I tre tratti.
8. Interruttore acceso, costanti morte rimosse.
9. L'app: statistiche per giocatore nella scheda partita.

I passi da 1 a 3 non cambiano niente di visibile: costruiscono lo strumento con cui si
misura il passo 5. Sono la parte che rende questo progetto diverso da un rifacimento a
occhio.

---

## Com'è andata

*Aggiunto il 2026-08-29, a lavoro finito. Il progetto qui sopra è rimasto com'era: questa
sezione dice dove la realtà si è discostata, che è l'unica parte che serve rileggere.*

**I nove passi sono stati fatti tutti**, nell'ordine previsto. L'interruttore è acceso e la
banda misurata è verde su tutti e due i motori.

**Tre cose sono uscite solo misurando**, e nessuna si vedeva leggendo il codice:

1. *Ogni duello vinto in area diventava un tiro.* Col motore vecchio arrivare in zona
   d'attacco era raro, quindi «sei arrivato, concludi» era giusto; coi duelli in area ci si
   resta. Quarantatré tiri a partita invece di ventiquattro.
2. *Le pendenze compoundavano.* Con 280 episodi invece di 118 decisioni, cinque punti di
   overall valevano il 95% delle vittorie. Tutte alzate tranne quella della corsa, che è una
   regola detta e non una manopola.
3. *Sei zone su nove non le usava nessuno* — e questo era un difetto vecchio, non dei duelli.
   La corsia centrale era assorbente: terzini e ali non toccavano mai il pallone, e la
   larghezza tattica non faceva niente. Si è visto contando chi commetteva i falli.

**Una prova era rotta e una si è rotta.** Il test degli angoli era sotto-campionato — a
quattrocento partite l'effetto spariva nel rumore, quindi passava senza misurare niente — e
`DuelliReportTest` ha cominciato a confrontare il motore con se stesso nel momento in cui
l'interruttore è stato acceso di serie, stampando due colonne identiche senza che niente lo
segnalasse.

**Una cosa in più rispetto al progetto:** `Carattere.kt`. I tre tratti erano previsti dentro
la simulazione; sono diventati funzioni pure perché altrimenti non erano provabili se non
facendo girare partite intere.

**Una cosa che il progetto prometteva ed è stata mantenuta:** zero modifiche al database. Le
statistiche nuove viaggiano in `match_results.player_stats`, che è `jsonb` dal primo giorno.
