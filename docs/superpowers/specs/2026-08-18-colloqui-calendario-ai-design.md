# Colloqui veri, calendario vero, AI complete

Data: 18 agosto 2026
Stato: approvata

## Il difetto sotto agli altri

`lineups` ha **una riga per club**: la formazione attuale, sovrascritta a ogni salvataggio.
Non esiste da nessuna parte l'elenco di chi e' sceso in campo nella dodicesima giornata.

Questo non e' un dettaglio. `TickRunner.eraTitolare` verifica la promessa "titolare per tre
partite" leggendo la formazione impostata **adesso**: cambi undici dopo la partita e il conto
sbaglia. E rende impossibile qualunque frase che cominci con "nelle ultime tre partite" —
che e' esattamente cio' che serve per far nascere un colloquio da un fatto invece che da una
soglia.

`MatchEngine` calcola gia' tutto: `PlayerMatchStats` per ogni giocatore, con minuti, gol,
assist, cartellini e voto. Poi lo butta via.

Per questo il primo pezzo di lavoro e' registrare le presenze, e tutto il resto ci poggia
sopra.

---

## 1. Presenze

### Cosa

Tabella `appearances`: una riga per giocatore per partita.

```
fixture_id, league_id, club_id, player_id, match_day,
started boolean, minutes, goals, assists, yellow, red, injured, rating numeric(3,1)
```

Chiave primaria `(fixture_id, player_id)`: rigiocare una partita non duplica le righe.

### Perche' `started` non si ricava dai minuti

`PlayerMatchStats.started` oggi risponde `minutesPlayed > 0`, che e' vero anche per chi entra
all'ottantesimo. Il titolare lo sa solo la formazione con cui la partita e' cominciata, che
il tick ha in mano al momento del calcio d'inizio. Va scritto li', non dedotto dopo.

### Conseguenze

- Le promesse si verificano sulla partita giusta.
- `LeagueFacts` (sotto) puo' dire "tre panchine di fila".
- Il calendario mostra le pagelle dei giorni passati.

### Cosa non si fa

Niente storico degli eventi partita per partita (chi ha segnato al 34'): `match_results`
contiene gia' il tabellino, e duplicarlo non serve a nessuna delle cose di cui sopra.

---

## 2. Colloqui

### Il difetto attuale

`Spogliatoio.argomentoDi` ricalcola l'argomento da una soglia sul morale a ogni apertura
della schermata. Parli, il morale sale, la soglia cambia, compare l'argomento successivo, e
"Incoraggia" rende +5 ogni volta perche' niente ricorda che l'hai gia' detto.

Non e' un difetto del motore: e' che **una conversazione non esiste come oggetto**. Da qui
vengono anche gli argomenti incoerenti — un giocatore appena comprato che chiede spazio
prima di aver messo piede in campo, perche' il gioco non guarda cosa gli e' successo, guarda
un numero.

### Cosa

Tabella `conversations`: `league_id, club_id, player_id, topic, cause, opened_on, status,
tone, morale_delta, closed_at`. Le apre **il tick**, che e' l'unico a vedere i fatti.

`cause` e' testo pronto da mostrare: *"Tre panchine di fila: 12a, 13a, 14a"*. Il colloquio
si apre, lo affronti una volta, si chiude. Non torna.

### Da quali fatti nasce un colloquio

| Argomento | Fatto che lo apre |
|---|---|
| `NUOVO_ARRIVO` | primo giorno in squadra |
| `PANCHINA_PROLUNGATA` | 3 partite consecutive senza scendere in campo |
| `PRESTAZIONI_SCARSE` | 2 voti sotto 5.5 nelle ultime 3 |
| `GRANDE_PRESTAZIONE` | un voto sopra 8, o una doppietta |
| `MORALE_BASSO` | morale sotto 35 **e** almeno una giornata dall'ultimo colloquio |
| `RICHIESTA_CESSIONE` | morale sotto 20 per due giornate di fila |
| `CONTRATTO_IN_SCADENZA` | mancano 6 giornate o meno |
| `INFORTUNIO` | infortunato nell'ultima partita |
| `PROMESSA_TRADITA` | il tick ha appena chiuso una promessa come tradita |
| `RIENTRO` | prima partita dopo l'infortunio |
| `CAPITANO` | e' il capitano e la squadra ha perso due partite di fila |

### La convocazione libera

Puoi convocare chiunque, in qualunque momento. Ma:

- una convocazione per giocatore ogni **3 giornate**;
- l'effetto sul morale vale **un terzo** di quello di un colloquio nato da un fatto;
- un `AMBIZIOSO` o un `TESTA_CALDA` convocato senza motivo reagisce **male** (-2 sul
  modificatore di tratto).

Questo e' il pezzo che chiude il rubinetto del +5 a ripetizione senza togliere la liberta'
di parlare a chi si vuole.

### Modifiche a `core`

`ConversationEngine` cresce ma non cambia forma:

- `ConversationTopic` passa da 5 a 11 voci, ognuna con `prompt` e le sue opzioni;
- `ConversationOption` invariata;
- `resolve` prende un parametro nuovo `spontanea: Boolean` (falso = nato da un fatto);
- `traitReaction` guadagna il caso "convocato per niente".

`LeagueFacts` (nuovo, in `core/conversation/`): funzioni pure che dicono, date le presenze e
la rosa, quali colloqui vanno aperti. Sta in `core` e non nel tick perche' e' regolamento, e
il regolamento si testa.

### Modifiche all'app

`Spogliatoio` legge `conversations` invece di ricalcolare la soglia. Due sezioni: **"Vogliono
parlarti"** (le aperte, con la causa scritta sotto al nome) e **"Convoca"** (il resto della
rosa, con quanto manca al prossimo giro se e' in attesa).

---

## 3. Orari

### I due difetti, che sommano nella stessa direzione

In scrittura, `CompetitionRepository` fa `f.kickoff.toInstant(ZoneOffset.UTC)`: le 21:00 che
l'admin ha scelto guardando il proprio orologio vengono marcate come UTC, cioe' spostate.

In lettura, `TableRepository.parseKickoff` prende `...+02:00`, taglia da `+` in poi e
appiccica una `Z`, buttando via il fuso una seconda volta. E' lo stesso difetto gia' corretto
in `LeagueDeskRepository.istante` e non propagato qui.

### Cosa

`CalendarConfig` guadagna `timeZone: ZoneId`, default `Europe/Rome`, serializzato in
`ConfigJson`. **Nessuna migrazione**: `leagues.config` e' gia' jsonb.

- Le fasce orarie dell'admin si interpretano in quel fuso e diventano istanti veri.
- La lettura usa lo stesso parser tollerante di `LeagueDeskRepository`, spostato in un
  `Istanti.kt` condiviso perche' averne due copie e' precisamente il motivo per cui una era
  rimasta rotta.
- La visualizzazione avviene in ora di lega, con l'etichetta del fuso accanto quando il
  telefono sta altrove.

---

## 4. Calendario a griglia

### Cosa

Griglia mensile: sette colonne, una cella per giorno, frecce per cambiare mese. Ogni cella
porta fino a tre puntini colorati; toccandola si apre sotto l'elenco degli eventi di quel
giorno con l'ora esatta.

| Colore | Evento |
|---|---|
| verde | la tua partita |
| grigio | partite delle altre squadre |
| azzurro | amichevole |
| arancio | asta in chiusura |
| rosso | contratto in scadenza, promessa in scadenza |
| viola | apertura o chiusura di una finestra di mercato |

I giorni passati con partita giocata mostrano il risultato al posto dell'ora.

### Dove sta il calcolo

`LeagueCalendar` in `core/calendar/`: prende partite, aste, contratti, promesse e finestre di
mercato, e restituisce `Map<LocalDate, List<CalendarEvent>>`. Funzione pura, quindi si testa.

Le scadenze dei contratti sono in giornate, non in date. La mappa giornata-data si costruisce
dalle partite gia' programmate: la giornata 14 cade il giorno della prima partita della
giornata 14. Le giornate oltre l'ultima programmata non compaiono in calendario, il che e'
corretto — quella data non esiste ancora.

---

## 5. AI

### Velocita'

`AiScheduler.nextWakeInWindow` produce **un solo risveglio al giorno**: dopo aver agito, il
candidato successivo cade prima di adesso e viene spinto a domani. Con `maxMarketActionsPerDay
= 2`, arrivare a diciotto giocatori richiede settimane reali.

Due ritmi:

- **Sprint**, finche' la rosa e' sotto `minSquadSize`: la finestra oraria si ignora, il
  risveglio e' ogni 15-40 minuti, il tetto giornaliero sale a 12 azioni.
- **Ritmo umano**, a rosa completa: come adesso, ma con i risvegli distribuiti dentro la
  finestra usando `personality.checksPerDay`, che oggi esiste e non viene letto da nessuno.

La discriminante e' la dimensione della rosa e non lo stato della lega: e' la stessa regola
che governa gia' il mercato in `AiManager`, e tenerne una sola evita che le due si separino.

### Parita' di azioni

Oggi un'AI sa fare tre cose: aprire un'asta, rilanciare, rispondere a uno scambio. Deve
saperne fare quante ne fa una persona.

1. **Proporre scambi e prestiti**, a te e alle altre AI. `AiManager.proponi` costruisce
   l'offerta partendo dalle stesse valutazioni che usa per le aste, e sceglie il bersaglio
   dai buchi di rosa.
2. **Chiedere amichevoli**, quando ha un buco in calendario e la rosa e' stanca o corta di
   ritmo.
3. **Gestire la propria rosa**: rinnovare chi serve, svincolare chi non gioca da tanto e
   costa, parlare con i propri giocatori quando `LeagueFacts` apre un colloquio. Questa non
   la vedi mai, ed e' quella che impedisce alle rose delle AI di diventare assurde.

### Tetti che restano

Il tetto giornaliero di azioni e i ritardi umani prima di rilanciare non si toccano fuori
dallo sprint. Il problema che risolvevano — venticinque notifiche in tre secondi — non e'
sparito.

---

## 6. Trattative

### Una casella, non tre schermate

`trades` funziona gia' da un capo all'altro. Invece di scrivere due sistemi paralleli per
prestiti e amichevoli, la tabella guadagna due colonne:

- `kind text not null default 'SCAMBIO'` con `check (kind in ('SCAMBIO','PRESTITO','AMICHEVOLE'))`
- `terms jsonb not null default '{}'` per le condizioni che dipendono dal tipo

Le righe esistenti diventano `SCAMBIO` da sole. La macchina a stati e' identica, la casella e'
una, il codice che risponde e' uno.

`terms` invece di sei colonne perche' i campi sono significativi solo per un tipo: un prestito
ha durata e ingaggio a carico, un'amichevole ha una data. Sei colonne quasi sempre nulle
sarebbero sei modi di sbagliarsi.

### Amichevoli

`fixtures.competition_id` e' `not null`, e va bene cosi'. Ogni lega ha una competizione
nascosta "Amichevoli", creata alla prima proposta accettata. La partita passa dal motore che
esiste gia', `Standings` non la vede perche' calcola per competizione, e `MatchImportance.
AMICHEVOLE` — che esiste ed e' testato — fa il resto: niente crescita, se il regolamento della
lega dice cosi'.

`competitions` guadagna `kind text not null default 'UFFICIALE'` per distinguerla e non
mostrarla nell'elenco delle competizioni.

### Prestiti

La tabella `loans` esiste dal primo schema e il tick sa gia' far rientrare un prestito
scaduto. Manca solo il modo di proporne uno: `propose_loan` inserisce una riga in `trades` con
`kind = 'PRESTITO'`, e l'accettazione scrive in `loans`.

---

## Ordine di lavoro

Presenze, colloqui, orari e calendario, AI, trattative. Ogni pezzo poggia sul precedente: i
colloqui hanno bisogno delle presenze, il calendario ha bisogno degli orari giusti, le AI
hanno bisogno delle trattative per poterle proporre — ma quest'ultima dipendenza si spezza
consegnando prima il lato umano.

## Migrazioni

Tre file, in ordine:

1. `0012_partite_giocate.sql` — `appearances`
2. `0013_colloqui.sql` — `conversations` e le funzioni per aprirla e chiuderla
3. `0014_trattative.sql` — `trades.kind`, `trades.terms`, `competitions.kind`,
   `propose_loan`, `propose_friendly`

**Le migrazioni vanno applicate prima dell'APK.** `0014` aggiunge una colonna a
`competitions`, che l'app legge in una SELECT condivisa: se l'app arriva per prima, PostgREST
rifiuta l'intera query per una colonna che non esiste e la schermata competizioni smette di
funzionare. E' l'errore gia' commesso con `clubs.division_level`.

## Rischi

**`appearances` cresce.** Venti club, diciotto giocatori, trenta giornate: circa undicimila
righe a stagione per lega. Trascurabile per il piano gratuito, ma l'indice va messo su
`(league_id, player_id, match_day desc)` perche' tutte le domande hanno quella forma.

**Lo sprint delle AI puo' svuotare il mercato prima che un umano si colleghi.** Il tetto e'
che lo sprint vale solo sotto `minSquadSize`: un'AI in sprint compra fino ad avere una rosa
legale e si ferma. I giocatori forti restano contesi perche' `crowdingFactor` e la disciplina
di spesa continuano a valere.

**Un'AI che propone scambi puo' diventare fastidiosa.** Il tetto giornaliero di azioni la
limita, e un rifiuto attiva il `refusalCooldown` che esiste gia'.
