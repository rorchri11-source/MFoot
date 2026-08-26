# Il tick sotto i due minuti, e il mondo che si muove ogni cinque

**Deciso il 2026-08-25.** Approvato dal proprietario nella stessa sessione.

---

## Il problema, come è arrivato

> «Come ottimizzare ulteriormente i tick per farli andare tra i 2/4 minuti a tick
> d'esecuzione»

Dietro il numero c'erano due obiettivi, chiesti esplicitamente: **il mondo deve reagire
prima** e **i giri non devono accavallarsi**. E quando ho chiesto quali ritardi danno
fastidio, la risposta è stata: tutti e quattro — partite, risposte delle AI, scadenze,
crescita.

## La misura che ha cambiato la domanda

Minuti fra un avvio e il successivo, ultimi quattordici giri:

```
34  22  51  50  47  53  44  44  31  29  32  73  2
```

**Il cron chiede ogni 10 minuti. GitHub ne consegna uno ogni ~39.** Sul piano gratuito i
lavori pianificati vengono ritardati o saltati, e nessuna riga di codice del progetto può
influenzarlo.

Da qui la conclusione che riorienta tutto il lavoro:

| | Oggi | Con un tick da 2 minuti |
|---|---|---|
| Durata del giro | ~11 min | 2 min |
| **Ogni quanto il mondo si muove** | **~39 min** | **~39 min** |

Rendere il tick cinque volte più veloce non fa reagire il mondo prima. Fa finire prima un
giro che parte comunque quando decide GitHub. **Il numero chiesto e l'obiettivo dietro il
numero sono due problemi diversi, e vanno risolti tutti e due.**

### Dove va il tempo, misurato sul giro 393

| Passo | Giro 391 | Giro 393 |
|---|---|---|
| Preparazione + Gradle | 11 s | 16 s |
| Costruzione del jar | 43 s | 59 s |
| **Esecuzione del tick** | **6 m 45 s** | **10 m 09 s** |
| Salvataggio cache | 26 s | 1 s |

Il 393 è **peggiorato** rispetto al 391, ed è coerente: contiene le AI che fanno otto mosse
per risveglio ma non ancora la cache del listino né il messaggio Telegram unico, che stanno
nel commit successivo e non sono ancora stati misurati.

Due cose che questi numeri dicono:

- il timeout portato a venti minuti ha salvato il giro: con i dieci di prima sarebbe stato
  buttato via intero;
- `cache-read-only` ha tolto i 26 secondi di salvataggio ma ne ha aggiunti 16 alla build,
  perché la cache non si aggiorna più. Il jar precompilato risolve tutte e due.

Resta un **pavimento di ~75 secondi** che non è codice di gioco, e un corpo da portare da
dieci minuti a meno di due.

---

## Il disegno

### 1. Il tempo lo tiene Supabase, non GitHub

Ogni cinque minuti, l'orologio interno del database chiama GitHub e gli chiede di far
partire un giro. Il cron di GitHub resta acceso come rete di sicurezza: se il database non
riesce a chiamare — token scaduto, estensione disattivata — il mondo continua a muoversi
lentamente invece di fermarsi.

**Perché il database e non un servizio a parte.** Perché il vincolo del progetto è «costo
zero, niente di proprio lasciato acceso», e Supabase è già acceso: è il database del gioco.
Un orologio dentro qualcosa che c'è già non aggiunge niente da tenere in vita.

**Perché cinque minuti.** Scelto dal proprietario fra due, cinque e dieci. Con un giro da
due minuti non si accavallano mai, e le partite partono al massimo cinque minuti dopo
l'orario. È la cadenza che il progetto dichiara da sempre nei commenti senza averla mai
avuta.

**Quello che serve fare a mano, una volta:** creare un token GitHub con permesso di far
partire i workflow e depositarlo nel Vault di Supabase. È l'unica credenziale nuova, e
resta dentro il database — non entra mai nell'APK.

**Il rischio, detto:** un token che scade ferma la sveglia. Per questo il cron di GitHub
non si spegne. Il degrado è «il mondo torna lento», non «il mondo si ferma».

### 2. Il tick non si costruisce più a ogni giro

Il programma viene costruito **una volta, quando cambia il codice**, e pubblicato come
allegato di una Release. I giri lo scaricano già pronto in due o tre secondi.

Al termine della costruzione parte anche un giro: non esiste una finestra in cui il server
gira codice vecchio dopo un push.

**Perché la Release e non il jar nel repository.** Il repository è pubblico e ogni modifica
al tick aggiungerebbe una decina di megabyte binari alla storia di git, che cresce e non si
può più rimpicciolire.

**Il ripiego:** se lo scaricamento fallisce, il giro costruisce da sorgente come oggi. Un
minuto in più è meglio di un giro saltato.

### 3. Il tick legge una volta e scrive in blocco

È la parte grossa, ed è quella scelta dal proprietario fra tre livelli di intervento.

Oggi il tick fa **migliaia di viaggi** verso il database: chiede un giocatore, lo aggiorna,
ne chiede un altro. Ogni viaggio costa la latenza fra un runner di GitHub e Supabase, e
sono quei millisecondi che diventano minuti.

Diventerà: **carica lo stato della lega in cinque o sei domande**, fa tutto il ragionamento
in memoria, e **riscrive alla fine in blocchi**.

Quello che entra in memoria all'inizio del giro:

| Cosa | Perché serve dappertutto |
|---|---|
| Club e crediti | Ogni decisione di spesa |
| Giocatori | Formazioni, mercato, crescita, colloqui |
| Contratti | Chi è di chi, e la rosa di ogni club |
| Formazioni salvate | Ogni partita |
| Listino, aste, acquisti aperti | Ogni mossa delle AI |
| Stati delle AI e staff | Risvegli e moltiplicatori |

Quello che **non** cambia: le regole di gioco restano in `core`, con i loro 745 test.
Cambia soltanto come il tick prende e restituisce i dati.

#### Il punto delicato: le funzioni SQL condivise

Il tick chiama `place_bid` — una funzione del database — per le offerte delle AI, di
proposito: stesso lock e stessi controlli sui fondi che usa l'app. Un'AI che scrivesse
diretta nella tabella potrebbe spendere crediti che non ha.

Quella chiamata **resta**, e le scritture in sospeso vengono riversate prima di farla. È
l'unico punto in cui il modello «scrivi alla fine» si interrompe, e si interrompe perché
sui soldi la sicurezza vale più della velocità.

#### L'ordine di conversione

Prima la fase che pesa di più, misurata — non quella che sembra pesare di più. Il cronometro
per fase è già in produzione e scrive il riepilogo in `tick_state.last_run_notes`:

```
tempi: partite 41200ms, mercato AI 12800ms, notifiche 900ms, scouting 400ms
```

Convertire una fase per volta tiene il tick funzionante a ogni passo, e ogni passo si può
misurare da solo.

### 4. Come si dimostra che non si è rotto niente

Il tick ha già una modalità di prova (`MFOOT_DRY_RUN`) che calcola tutto e **annulla invece
di salvare**. Si fa girare la versione vecchia e la nuova sulla stessa identica situazione
del database vero e si confronta cosa decidono. Se decidono cose diverse, il difetto si vede
prima di toccare la lega.

Non ci sono prove automatiche contro un database vero, e non se ne aggiungono in questo
lavoro: richiederebbero Docker, che non è installato sulla macchina di sviluppo. La rete di
sicurezza è la modalità di prova più i 745 test delle regole, che continuano a coprire ogni
decisione di gioco.

---

## Cosa questo lavoro non fa

- **Non tocca le regole di gioco.** Nessun prezzo, nessuna soglia, nessun comportamento
  delle AI.
- **Non sposta niente in SQL.** Il regolamento resta uno solo, in `core`. La duplicazione
  esistente — `mfoot_market_value`, `staff_price` — è motivata dal fatto che il server non
  può fidarsi del telefono sui soldi, e non si estende.
- **Non lascia niente acceso.** Era stata valutata e scartata l'ipotesi di un tick che resta
  in esecuzione per un'ora ticchettando ogni minuto: funzionerebbe e sarebbe gratis, perché
  il repository è pubblico e i minuti di Actions sono illimitati, ma GitHub scoraggia
  esplicitamente l'uso delle Actions come processo sempre acceso. Vale la pena saperlo: se
  un giorno cinque minuti non bastassero, quella strada esiste e ha un costo di reputazione,
  non di denaro.

## Il risultato che si sta cercando

| | Prima | Dopo |
|---|---|---|
| Durata di un giro | 8-11 min | **< 2 min** |
| Ogni quanto si muove il mondo | ~39 min | **5 min** |
| Giri annullati dal cronometro | 13 su 20 | 0 |
| Viaggi verso il database per giro | migliaia | decine |

---

## Aggiornamento del 2026-08-26: due misure che cambiano il piano

### Le correzioni hanno funzionato, e non bastano

Quattordici giri consecutivi con il messaggio Telegram unico e la cache del mercato:

```
7:08  6:52  4:56  7:09  7:57  4:07  6:28  5:14  6:20  6:36  5:15  7:04  5:05  4:52
```

Media **6 minuti** contro gli 11:29 del giro 393, e **zero annullati** su quattordici. La
diagnosi era giusta. Tolto il minuto di costruzione, il corpo sta ancora fra i quattro e i
cinque minuti: servono le altre due parti del progetto.

### Il vincolo che mancava: l'egress

Supabase, piano gratuito: **5 GB di traffico in uscita al mese**. Consumati 1,22 GB con una
trentina di giri al giorno.

A cinque minuti sarebbero **288 giri al giorno**, quasi dieci volte tanto. Il tetto
salterebbe in una settimana.

**Questo aggiunge un requisito al punto 3 del progetto**, e lo rende piu' preciso invece di
contraddirlo:

> **La maggior parte dei giri non ha niente da fare, e deve accorgersene leggendo quasi
> niente.**

Un giro senza partite in scadenza, senza aste da chiudere, senza AI sveglie e senza
finestre di contestazione scadute deve rispondere in due secondi con una manciata di
`select count(*)`, e uscire. Solo quando c'e' qualcosa da fare si carica lo stato della
lega.

E' lo stesso lavoro di prima — «carica una volta, scrivi in blocco» — con davanti una
guardia. Serve a due cose insieme: tiene l'egress dentro il piano gratuito, e porta la
**media** dei giri sotto i due minuti anche se quelli pieni ne durano tre.
