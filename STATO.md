# MFoot — stato del progetto

**Aggiornato:** 2026-08-25
**Test:** 773 verdi, 0 falliti (745 in `core`, 28 in `tick`)
**Verificato:** `core:test`, `tick:test` e la build di rilascio firmata. **Da eseguire
[`supabase/schema.sql`](supabase/schema.sql) prima di installare l'APK**

---

## Il difetto più costoso, misurato il 2026-08-25

Il lavoro di GitHub Actions aveva `timeout-minutes: 10`. Sulle venti esecuzioni
consecutive prima della correzione, **tredici** finivano `cancelled` a dieci minuti e venti
secondi esatti dall'avvio: non era GitHub che annullava per concorrenza, era quel
cronometro che uccideva il processo.

E il processo ucciso non lasciava niente. Il tick elabora **una lega per transazione** e fa
`commit` solo alla fine di `runLeague`: staccare la spina a metà significa che Postgres
annulla tutto. Partite simulate, acquisti delle AI, stipendi, colloqui — tutto indietro, e
`last_processed_at` fermo.

| Esecuzione | Durata | Esito |
|---|---|---|
| 391 | 8m 04s | riuscita |
| 390 | 8m 56s | riuscita |
| 389 | 8m 55s | riuscita |
| 388 | **10m 23s** | annullata dal timeout |
| 387 | **10m 20s** | annullata dal timeout |
| 386 | **10m 26s** | annullata dal timeout |

Di quei quasi nove minuti: 43 secondi di build, **6m 45s di elaborazione**, 26 secondi
sprecati a riscrivere una cache identica a quella che c'era già.

È la spiegazione di «le AI comprano una volta al giorno»: non erano lente, due volte su tre
il loro acquisto veniva cancellato da un numero in un file YAML.

### Correzione del 2026-08-26: la diagnosi qui sotto era sbagliata

Quello che segue — «i minuti mancanti erano Telegram» — **non regge**, e va letto sapendolo.

Il proprietario ha segnalato che *«il gioco non dà nessuna, NESSUNA notifica mai»*. Se è
così, i due segreti `MFOOT_TELEGRAM_TOKEN` e `MFOOT_TELEGRAM_CHAT` non sono impostati,
`notificationsEnabled` è falso, e `consegnaLeNotifiche` **esce alla prima riga**: non ha
mai mandato una richiesta HTTP, quindi non può aver consumato niente.

**Dove andavano davvero i minuti**: il tick lavorava su **quindici leghe**, cosa che ho
scoperto solo il giorno dopo leggendo il registro. Il conto che non tornava — «millecento
viaggi verso il database fanno meno di un minuto, ne mancano cinque» — torna
perfettamente moltiplicato per quindici: sedicimila viaggi, quattordici minuti.

Il miglioramento misurato (da 10:09 a ~6 minuti) è quindi merito delle **cache del
mercato** e degli N+1 tolti, non del messaggio Telegram unico — che resta una correzione
giusta, ma per il giorno in cui le notifiche verranno accese.

La lezione è la stessa di prima, applicata a me: avevo un numero che non tornava e ho
riempito il buco con l'ipotesi più comoda invece di cercare il dato mancante. Il dato
mancante era in cima al registro, e diceva quindici nomi di lega.

### Dove finivano davvero i sei minuti e tre quarti

Il conto delle andate e ritorno verso il database non torna. Partite, mercato delle AI,
colloqui, promesse: sommati stanno intorno a **millecento** viaggi, che a cinquanta
millisecondi l'uno fanno meno di un minuto. Mancavano cinque minuti, e li mangiava una
cosa che con il database non c'entra niente:

```kotlin
val immediate = caricaDaConsegnare(league.id, "immediata", limite = 20)
for (riga in immediate) {
    if (!notifier.send(...)) break     // una richiesta HTTPS per notifica
```

**Venti richieste separate a Telegram, in fila, con quindici secondi di timeout
ciascuna.** Telegram limita a circa venti messaggi al minuto per chat: oltre quella
soglia risponde `429` e le richieste si trascinano. Caso peggiore: cinque minuti passati
ad aspettare, su un giro che ne aveva dieci prima di essere ucciso. E da quando le AI si
muovono davvero, venti notifiche in un giro non sono un caso limite — sono il normale.

Adesso è **un messaggio solo** con dentro tutte le notizie del giro. Resta immediato — è
lo stesso giro — e il gruppo smette di ricevere venti messaggi di fila, che era l'altro
motivo per cui non andava bene.

Le correzioni, in ordine di peso:

0. **Le notifiche immediate partono insieme**, non una per una. È la voce che da sola
   spiega i minuti mancanti.

1. **`timeout-minutes: 20`**, e un budget di quindici minuti dentro il tick
   ([`TickBudget`](tick/src/main/kotlin/dev/mfoot/tick/TickBudget.kt)): il giro si ferma da
   solo, chiude la transazione e salva, invece di essere ammazzato a metà. Quello che non
   ha fatto lo fa il giro dopo — il tick è costruito per recuperare gli intervalli persi.
2. **Meno viaggi verso il database**. `applyMatchAftermath` chiedeva un giocatore per volta
   dentro un ciclo, più le stelle dell'allenatore per ognuno: una cinquantina di andate e
   ritorni per partita. Adesso è una query sola, e i due valori che non cambiano dentro un
   giro si ricordano.
3. **Sette indici nuovi**, e un cronometro che misura ogni fase — perché il registro
   diceva solo «terminato in 405000 ms», e senza sapere di cosa l'unica strada è
   indovinare quale pezzo sia lento.
4. **Il listino si legge una volta per giro, non ottanta.** `compraDalListino` chiamava
   `loadListings` a ogni mossa: due query di cui una riporta un migliaio di righe. Andava
   bene con una mossa per risveglio; con otto mosse e dieci club diventavano ottanta
   letture dello stesso elenco. Era una regressione introdotta insieme alla correzione
   delle AI ferme — il genere di costo che non si vede scrivendo il codice, perché la
   funzione era già lì e sembrava gratis. Adesso listino, aste aperte e acquisti
   contestabili restano in memoria finché qualcuno non scrive.

### La misura dopo le correzioni, il 2026-08-26

Quattordici giri consecutivi con le correzioni applicate (messaggio Telegram unico, cache
del mercato, N+1 tolti). Durata di ognuno:

```
7:08  6:52  4:56  7:09  7:57  4:07  6:28  5:14  6:20  6:36  5:15  7:04  5:05  4:52
```

Media **6 minuti**, contro gli **11:29** del giro 393. E **zero annullati** su quattordici,
contro tredici su venti prima del timeout a venti minuti.

La diagnosi era giusta: il grosso dei minuti mancanti era Telegram, non il database.

**Quello che resta.** Tolti il minuto di costruzione del jar e i sedici secondi di
preparazione, il corpo del tick sta ancora fra i quattro e i cinque minuti. Per arrivare
sotto i due servono le altre due parti del progetto.

**E una cosa che i numeri non dicevano.** La cadenza vera, misurata sugli stessi
quattordici giri: 48, 56, 39, 34, 40, 26, 31, 31, 69, 43, 51, 92, 108 minuti. Media
**52 minuti** — peggio di prima. Non e' il tick: e' il pianificatore di GitHub, ed e' la
ragione per cui l'orologio passa dentro Supabase.

### Il vincolo che nessuno aveva guardato: l'egress

Il piano gratuito di Supabase da' **5 GB di traffico in uscita** al mese. Al 2026-08-26 ne
erano stati consumati **1,22 GB**, con il tick che gira una trentina di volte al giorno.

Portare la cadenza a cinque minuti significa **288 giri al giorno**, cioe' quasi dieci
volte tanto: la stessa lettura del mondo ripetuta dieci volte piu' spesso sfonderebbe il
tetto in una settimana.

Non e' un argomento contro i cinque minuti — e' il requisito che li rende possibili: **la
maggior parte dei giri non ha niente da fare**, e deve accorgersene leggendo quasi niente e
uscendo in due secondi. Un giro che non ha partite da giocare, aste da chiudere e AI da
svegliare non ha motivo di caricare milleduecento giocatori.

Vale anche per la velocita': un giro che esce subito quando non c'e' niente da fare e' il
modo piu' diretto di portare la media sotto i due minuti.

### Dove finiva il tempo davvero, letto il 2026-08-26

Il cronometro per fase, alla sua prima lettura utile:

```
colloqui AI 119728ms · mercato AI 62045ms · partite 10926ms · scambi 10278ms
colloqui 9766ms · listino 3153ms · osservatori 2452ms · scouting 2232ms
promesse 1442ms · contestazioni 1101ms · primavera 1008ms · promozioni 1007ms
```

**Metà del giro in «colloqui AI»**, e non era dove nessuno avrebbe guardato. Il registro
spiegava perché: ogni lega scriveva *«40 colloqui aperti nello spogliatoio / 40 colloqui
gestiti dai club del computer»*, **a ogni giro**. Il tick ne apriva quaranta, l'AI li
chiudeva nello stesso giro, e al giro dopo si riapriva tutto.

Una riga mancante in `LeagueFacts.trigger`: `lastConversationOn` esiste da sempre, il tick
lo calcola con una query apposta e lo passa — e dentro la regola non lo leggeva nessuno.
L'attesa fra due colloqui valeva solo per la convocazione a mano.

Non era solo lavoro sprecato: era il morale di ogni giocatore del computer spostato ogni
cinque minuti da una conversazione che non era mai successa. Un difetto di gioco travestito
da lentezza. Vedi [`REGOLE.md`](docs/REGOLE.md).

**È anche la lezione sul metodo.** Avevo previsto che il tempo stesse nelle partite o nel
mercato. Stava altrove, e l'ho scoperto solo perché il giro prima avevo messo un cronometro
invece di continuare a ottimizzare a intuito.

### Quindici leghe, quattordici delle quali non le gioca nessuno

Il registro dello stesso giro elenca: *Lega di prova*, *Lega Vera*, *Punto Legha* (cinque
volte), *Milioni*, *Test*, *VERIFICA-20-AGOSTO*, *Gli*, *Carabina Series*, *Prova Scambi*…

Il tick le elabora **tutte**, perché `loadActiveLeagues` prende ogni lega in stato
`mercato` o `in_corso`. Quindici leghe moltiplicano ogni fase per quindici.

Non è una cosa da correggere nel codice: è l'admin che decide quali leghe sono vive.
Portare una lega a `conclusa` la toglie dal giro, ed è reversibile.

### Il fallimento del giro 425: `deadlock detected`

Il tick scriveva su una lega mentre un altro processo toccava le stesse tabelle
nell'ordine opposto. Postgres ne annulla uno: è come deve funzionare, non è un errore del
gioco. La lega tornava indietro per intero e sarebbe stata ripresa al giro dopo — ma il
giro dopo, sul piano gratuito, può voler dire quaranta minuti.

Adesso si riprova **subito, una volta sola**. Se l'intoppo si ripete allora è un problema
vero e il giro resta rosso.

### Com'è finita, il 2026-08-26 alle 20:30

L'orologio è passato dentro Supabase. La prova, presa dal registro di GitHub:

```
#429  partito 20:25:01
#430  partito 20:30:01
```

Cinque minuti esatti, al secondo, entrambi a `:01` dopo il multiplo di cinque. Per
confronto, il `#427` — l'ultimo partito dal cron di GitHub — è delle `20:22:17`.

| | Prima | Dopo |
|---|---|---|
| Durata di un giro | 8–11 min | **10–19 s** (mondo vuoto) |
| Ogni quanto si muove il mondo | ~40 min | **5 min** |
| Giri uccisi dal cronometro | 13 su 20 | 0 |
| Chi decide quando | GitHub | Il database |

**Il refactor «carica una volta, scrivi in blocco» non è stato fatto**, benché approvato.
Non serviva: il problema non era come il tick parlava al database, erano quindici leghe
invece di una, i colloqui che si riaprivano in eterno, un messaggio Telegram per notifica e
un timeout che uccideva due giri su tre. Quattromilanovecento righe da toccare per un
guadagno che a questi numeri non esiste più.

Se un giorno i giri pieni risultassero lenti, i tempi per fase sono in
`tick_state.last_run_notes` e si riparte da un numero invece che da un'ipotesi.

**Quello che resta da guardare** è l'egress: 5 GB al mese, e la cadenza a cinque minuti è
sostenibile solo perché i giri a vuoto costano pochi kilobyte. Se cresce oltre i ~150 MB al
giorno, la manopola è una riga:

```sql
select cron.schedule('mfoot-orologio', '*/10 * * * *', 'select sveglia_il_tick()');
```

### Come si leggono i tempi, senza scaricare i registri di GitHub

Il riepilogo per fase finisce in **`tick_state.last_run_notes`**, che si apre dal Table
Editor di Supabase in due tocchi:

```
tempi: partite 41200ms, mercato AI 12800ms, notifiche 900ms, scouting 400ms
```

Il registro di un'esecuzione di GitHub Actions si scarica solo con un token, e chi
gestisce la lega guarda il database. È la differenza fra sapere che un giro dura sette
minuti e sapere **di cosa** sono fatti: senza il secondo dato, ottimizzare significa
indovinare.

---

## Com'è fatto

```
mfoot/
├── core/          ✅ il gioco: motore, mondo, mercato, AI. Zero dipendenze di piattaforma
├── tick/          🟡 il battito: gira su GitHub Actions ogni 10 minuti
├── android/       🟡 l'app: entra in lega, fonda il club, compra all'asta
└── supabase/      ✅ schema, RLS, funzioni transazionali
```

**La regola d'oro:** `core` non importa niente di Android, niente di database, e non fa
I/O. Prende oggetti e restituisce oggetti. È il motivo per cui si testa in mezzo minuto e
per cui ci si può far girare diecimila stagioni di seguito per bilanciare il gioco.

**Il corollario:** tutto ciò che è una *regola* vive in `core` e viene usato identico
dall'app e dal server. La configurazione della lega viaggia con
[`ConfigJson`](core/src/main/kotlin/dev/mfoot/core/config/ConfigJson.kt), che scrive e
rilegge nello stesso file, con un test di andata e ritorno. Se scrittura e lettura
divergessero, le regole scelte dall'admin tornerebbero ai valori di serie **in silenzio**.

### Come si esegue

```bash
gradlew :core:test
```

```bash
gradlew :android:assembleDebug
```

---

## Fatto

| # | Fase | Contenuto |
|---|---|---|
| 1 | **Fondamenta** | `DeterministicRandom` (xorshift64\*), `MathX` su `StrictMath`, modelli, `LeagueConfig` con ~110 parametri, `ConfigValidator`, tre preset |
| 2 | **Mondo procedurale** | `DevelopmentCurve`, `AttributeGenerator`, `NameBank`, `WorldGenerator`, `PotentialEstimator` |
| 3 | **Motore partita** | `Formation`, `Lineup`, `Tactics`, `ConditionalOrder`, `ZoneRatings`, `MatchEngine` con finestra di intervallo |
| 4 | **Bilanciamento** | `BalanceHarness` + tarature misurate su migliaia di partite |
| 5 | **Sistemi giocatore** | `GrowthEngine`, `StaminaEngine`, `MoraleEngine`, `ConversationEngine` con promesse |
| 6 | **Calendario** | `FixtureGenerator` (Berger, eliminazione, gironi), `CalendarSolver`, `Standings` |
| 7 | **Mercato** | `AuctionRules` (offerta massima, anti-snipe, blocco fondi), `NegotiationRules`, `ContractRules`, `Valuation` |
| 8 | **AI** | `AiPersonality`, `AiScheduler` (risvegli scaglionati), `AiManager` (anti-sciame) |
| 9 | **Sessione** | Accesso anonimo salvato su disco, rinnovo automatico, nessuna schermata di login |
| 10 | **Ingresso** | Crea una lega con un preset, oppure entra con un codice |
| 11 | **Lettura** | `LeagueRepository`: lega, club, giocatori e contratti letti dal database in streaming |
| 12 | **Club e custom** | `CustomPlayerBuilder` in `core`, `create_club` che rifà il conto lato server, schermata di fondazione |
| 13 | **Il mondo gira** | Il tick avvia la stagione, genera il calendario e gioca le partite; `AutoLineup` schiera da solo |
| 14 | **Le aste** | `start_auction`, prezzo pubblico e massimi segreti, schermata mercato e foglio dell'offerta |
| 15 | **Le AI si muovono** | Si svegliano a turno, aprono aste e offrono passando per `place_bid` come tutti |
| 16 | **Competizioni** | L'admin crea campionato, coppa o gironi: partecipanti, date e orari suoi, con anteprima del calendario |
| 17 | **Classifica** | Tabellone con punti, differenza reti e criteri di spareggio dell'admin, piu' il calendario turno per turno |
| 18 | **Divisioni** | Serie multiple con promozioni, retrocessioni e spareggi. `SeasonEnd` in `core`, dimensioni garantite dal database |
| 19 | **Trattative** | Scambi, prestiti e amichevoli in una casella sola: `trades` con `kind` e `terms` |
| 20 | **Presenze** | `appearances`: chi ha giocato, partita per partita, panchina compresa |
| 21 | **Colloqui veri** | `LeagueFacts` apre un discorso solo quando e' successo qualcosa, e scrive il fatto accanto. Tredici argomenti |
| 22 | **Calendario** | Griglia mensile con una cella per giorno e i colori degli eventi |
| 23 | **AI complete** | Propongono scambi, chiedono amichevoli, tengono in ordine la rosa, gestiscono il proprio spogliatoio |
| 24 | **Mercato che non si ferma** | `AiTurn` in `core` decide l'ordine delle mosse, si vendono i propri all'asta, e `MarketRhythmTest` conta quante aste sono aperte a ogni giro |
| 25 | **Notifiche** | Il tick consegna su Telegram: le immediate da sole, il resto in un riepilogo al giorno |
| 26 | **Scouting** | La forbice si stringe coi minuti visti e con gli osservatori. Il conto lo fa il server, il potenziale vero non esce mai |
| 27 | **Primavera** | Si sposta un giovane dalla scheda, e chi sta li' si allena una volta per giornata |
| 28 | **La partita** | La timeline salvata si rivede minuto per minuto, con le pagelle dalle presenze |
| 29 | **Cinque posti** | Il menu passa da sedici voci piatte a cinque posti con le schede, e un interruttore fra le due squadre |
| 30 | **Seconda squadra** | `clubs.parent_club_id`: la Primavera e un club vero che gioca un campionato suo |
| 31 | **Staff e scouting** | Lo staff si vince all asta, gli osservatori vanno in missione, gli under 20 escono dalle aste |
| 32 | **Aste che si chiudono** | Quelle scadute fuori finestra restavano aperte per sempre. Tetto di lega, e a fine asta si vede chi ha offerto quanto |
| 33 | **Controproposte** | Si scrive un messaggio quando si propone, e si puo rilanciare invece di rifiutare. Anche le AI |
| 34 | **Una lega sola** | Il codice d'accesso e' univoco e si rilegge. «Le mie leghe» dice in quale si sta guardando |
| 35 | **Aste leggibili** | Filtri col loro numero, e la cronologia di chi ha offerto mentre l'asta e' aperta |
| 36 | **Orari veri** | L'ora la scrive chi gioca, e cio' che e' gia' passato non si puo' scegliere. `KickoffRules` in `core` |
| 37 | **Le cose scritte** | Stamina in rosa, divisione, formazione degli avversari, a quale competizione si sta giocando |
| 38 | **Obiettivi e premi** | Tre per club, decisi da una regola in `core`. Il premio si paga solo per intero |
| 39 | **Si entra sapendo dove** | `peek_league` mostra il nome prima di entrare; chi e' in piu' leghe se lo vede scritto |
| 40 | **I giocatori in prima serie** | `DivisionAssignment`: gli umani partono dalla massima, le AI riempiono, l'admin sceglie le dimensioni |
| 41 | **Traguardi e offerte** | Obiettivi a multipli di cinque che pagano ogni scalino; l'asta dice «ha offerto» e quante squadre sono dentro |
| 42 | **`docs/REGOLE.md`** | Le decisioni del proprietario in un posto solo, e un `CLAUDE.md` che le fa leggere a ogni sessione |
| 43 | **L'app si aggiorna da sola** | Giro leggero ogni 30s, giro pieno quando serve. Non sbianca lo schermo e non tocca il lavoro in corso |
| 44 | **Interfaccia rifatta** | Pelle nuova sul riferimento scelto dal proprietario: blu notte, barra blu, pulsanti lavanda, schede più scure del fondo, icone disegnate. Vedi [`docs/DESIGN-SYSTEM.md`](docs/DESIGN-SYSTEM.md) |
| 45 | **La scheda è una figurina** | Via la barra del potenziale: il margine è un gradino sotto l'overall, sei attributi in tre colonne, e rientrano conoscenza e contratto |
| 46 | **Cinque incarichi e dieci moduli** | Capitano, rigorista, angoli, punizioni, calci lunghi — ognuno pesa nel motore. Gli angoli producono occasioni vere, il capitano frena il crollo quando si va sotto |
| 47 | **Gli ordini condizionali si vedono** | Erano completi in `core` dal primo giorno e non c'era nessuna schermata. Ora si scrivono, si salvano e il tick li applica |
| 48 | **Il mercato senza aste** | Listino a prezzo fisso, acquisto immediato, e una finestra di dodici ore in cui l'acquisto si contesta: **solo la contestazione apre un'asta** |
| 49 | **Le AI si muovono davvero** | Comprano a listino, contestano gli affari troppo buoni, offrono crediti per i tuoi giocatori e propongono in prestito i loro giovani |
| 50 | **La finestra dell'intervallo** | Il tick ferma la partita al 45', apre i minuti dei cambi e poi gioca il secondo tempo con la formazione aggiornata |
| 51 | **Lo strumento dell'admin** | Assegna, toglie e corregge i crediti di qualsiasi club. Senza registro pubblico: scelta del proprietario, e per questo le operazioni possibili sono tre e strette |
| 52 | **Le coppe camminano** | `CompetitionProgress` in `core`: turno finito, vincitori, turno nuovo tre giorni dopo. Vale per il tabellone, per la fase finale dei gironi e per gli spareggi |
| 53 | **Il giocatore unico resta tuo** | Chiusi i due buchi da cui se ne andava davvero: `start_auction` e `propose_trade`. E i pulsanti che lo offrivano non ci sono più |
| 54 | **Il listino si vede e si scrive** | I propri in vendita compaiono fra gli altri, il «ritira» chiude la scheda, e il prezzo si digita col consigliato indicato |
| 55 | **Le AI comprano davvero** | Il gradimento non passa più dalla curva dei prezzi, e il prezzo è un criterio separato dal gusto: un'AI compra al listino e non lascia passare un affare |
| 56 | **Amichevoli accettabili** | Accettare non è più la stessa regola con cui si chiede. E il conto delle giornate, che era sbagliato nei due sensi |
| 57 | **Divisioni nell'elenco, e cancellare** | I partecipanti raggruppati per serie, e un campionato si cancella anche a stagione cominciata, con la conferma che dice cosa si perde |
| 58 | **La partita in tempo reale** | Novanta minuti veri: il minuto lo decide l'orologio, non un contatore. `MatchClock` in `core`, e il primo tempo si guarda mentre si gioca |
| 59 | **Due tick e venti minuti di pausa** | La ripresa si conta dal fischio d'inizio. Finché il secondo tempo non è scritto il minuto resta al 45', e lo schermo lo dice |
| 60 | **Due ore fra due partite** | Una regola sola in `core`, applicata dal calendario, dalle amichevoli, dall'AI e dal database. Toglie di mezzo le tre ore fisse dentro l'SQL |
| 61 | **Il recupero a ore vere** | Sette punti l'ora invece di 34 per giornata: la giornata valeva sei o dodici ore a seconda di quante fasce aveva la lega |
| 62 | **Il campo che si guarda** | La palla fra le nove zone, l'alone sulle occasioni, l'onda del gol, la barra dell'inerzia. Decorativo: `MatchEvent.zone` c'era dal primo giorno |
| 63 | **Le azioni le decidono i duelli** | Cinque contese fra due giocatori con un nome, ognuna con la sua pendenza. Tutti e dodici gli attributi decidono episodi: prima nessuno ne decideva uno |
| 64 | **La palla usa tutte e nove le zone** | Sei restavano vuote per tutta la partita: la corsia centrale era assorbente. Terzini e ali non toccavano il pallone, e la larghezza tattica non faceva niente |
| 65 | **Incostante, leader, testa calda** | Tre tratti che promettevano e non muovevano un numero. La giornata, la spinta di chi trascina, i falli di chi va in ritardo |
| 66 | **Il tabellino di un difensore** | Duelli, dribbling riusciti e subiti, precisione dei passaggi. Prima diceva solo quanti cartellini aveva preso |

### Il motore a duelli, 2026-08-29

Il pezzo che vale la pena ricordare non è come funziona — quello sta nel progetto — ma
**cosa si è scoperto misurando**, perché nessuna delle tre cose si vedeva leggendo il
codice.

**Ogni duello vinto in area diventava un tiro.** Col motore vecchio arrivare in zona
d'attacco era raro, quindi «sei arrivato, concludi» era giusto. Coi duelli in area ci si
resta per più episodi di fila e uscivano quarantatré tiri a partita invece di ventiquattro.
Adesso lì la palla gira, e a concludere pensa il tiro di dado in cima all'azione.

**Le pendenze compoundavano.** Con 280 episodi invece di 118 decisioni, un vantaggio per
duello si moltiplica: cinque punti di overall valevano il 95% delle vittorie. Alzate tutte
tranne quella della corsa, che resta ripida perché è una regola detta dal proprietario — e
il test che la codifica è rimasto com'era, mentre la manopola tornava dove la frase è vera.

**Sei zone su nove non le usava nessuno.** Il difetto più vecchio e il più invisibile: si è
visto solo contando chi commetteva i falli. Vedi la voce 64.

Una quarta cosa, sui test: la prova degli angoli era **sotto-campionata**. A quattrocento
partite l'effetto — quattro centesimi di gol — spariva nel rumore, e la prova passava o
falliva a seconda di quale seme capitava. Passava da mesi senza misurare niente. Adesso
duemilacinquecento.

### La partita in tempo reale, 2026-08-29

Decisa e fatta nella stessa sessione. Vale la pena scrivere le tre cose che non erano ovvie.

**Il minuto e' una funzione dell'ora, non un contatore.** E' l'unica forma che regge in
multiplayer: due telefoni aperti nello stesso istante devono vedere lo stesso minuto, o
«hai visto che gol al 78'?» non vuol dire niente. Contandolo in locale, chi apre l'app piu'
tardi vedrebbe una partita piu' indietro. `MatchClock` sta in `core` con dieci prove.

**Il cronometro non puo' correre sopra un campo di cui non si sa niente.** Fra la fine
dell'intervallo e il momento in cui il tick gioca il secondo tempo passano fino a cinque
minuti. In quei minuti l'orologio direbbe «61'» di una partita conosciuta fino al 45': il
minuto resta fermo e lo schermo dice che si sta aspettando la ripresa. E' la differenza fra
un'attesa e un guasto.

**Non serviva un cron nuovo.** La misura dei 20-40 minuti che sta piu' su in questo
documento e' **precedente** alla sveglia via Supabase, ed e' rimasta a dare l'impressione di
un problema aperto: `pg_cron` chiama `sveglia_il_tick()` ogni cinque minuti e quello fa
`workflow_dispatch` su GitHub. La ripresa parte entro cinque minuti dal dovuto, a costo
zero.

**E una contraddizione che il tempo reale ha reso visibile.** `propose_friendly` teneva tre
ore fisse dentro l'SQL per non sovrapporre due partite; il risolutore del calendario non
guardava l'orario affatto e accettava due partite alle 20:30 e alle 21:00. Due risposte
diverse alla stessa domanda, in due posti che non si parlavano. Adesso e' una regola sola in
`core`, applicata in quattro punti.

### I sette difetti del 2026-08-29, e cosa avevano in comune

Segnalati tutti insieme dal proprietario dopo aver guardato l'app. Sei su sette non erano
regole sbagliate: erano **regole giuste che nessuno chiamava**, o chiamate con il numero
sbagliato dentro.

- **La coppa si fermava agli ottavi.** `FixtureGenerator.nextKnockoutRound` era corretto,
  provato dal primo giorno, e non aveva **nessun chiamante in tutto il repository**. Stessa
  cosa per `Standings.qualifiers` e per i playoff, che nascevano come semifinali e non
  arrivavano mai alla finale.
- **Il giocatore unico si vendeva.** La regola c'era in `core` e in `list_player`, e
  mancava in `start_auction` e in `propose_trade`. Due strade su quattro aperte: una regola
  applicata in meta' dei posti non e' una regola.
- **«Ritira» non faceva niente.** Il server ritirava davvero. La scheda restava aperta con
  la stessa scritta perche' `ritiraDalListino` era **l'unica** azione di mercato che non
  faceva `selected = null`; ogni altra lo faceva.
- **I propri in vendita sparivano.** Un filtro nato per non mostrare «Compra» sui propri
  toglieva anche il vederli.
- **Nessuno comprava niente.** Il gradimento di un'AI passava per `Valuation.overallScore`,
  che ha esponente 7,5 perche' descrive **quanto costa** un giocatore, non quanto lo si
  vuole: un settantasette valeva 0,068 su 1. Quel numero moltiplicava il tetto di spesa,
  che finiva cinque volte sotto il prezzo consigliato dall'app a chi vende.
- **Ogni amichevole veniva rifiutata.** `answerFriendly` era `wantsFriendly`, e una delle
  sue condizioni — due giornate libere davanti — e' falsa **sempre** in una lega con un
  campionato in corso.
- **Le divisioni non comparivano dove si sceglie.** Unico difetto di vera assenza: il dato
  c'era, la schermata non lo chiedeva.

Vale la pena tenere il metodo: nessuno di questi sarebbe uscito da un test, perche' i test
provano il codice che qualcuno chiama. Sono usciti perche' **qualcuno ha guardato l'app**.

### La lega nasceva con i numeri sbagliati, 2026-08-29

Segnalato dal proprietario: «ho impostato 10 squadre AI ma me ne ha fatte 8, ho impostato
60M e sono partiti a 100M». **Otto e cento milioni sono esattamente i valori di serie del
preset `sprint`** — misurato, non dedotto — quindi le scelte non arrivavano alla creazione.

Due difetti diversi, tutti e due in Compose, tutti e due riprodotti sull'emulatore.

**Le scelte non sopravvivevano alla ricreazione della schermata.** Nel modulo di creazione
il nome, il codice, il nickname e il preset erano `rememberSaveable`; le scelte erano un
`remember` normale. Bastava una rotazione — o un ritorno nell'app dopo che Android aveva
liberato memoria — perche' i numeri tornassero a quelli del preset **lasciando pieni i campi
di testo**: niente sembrava andato storto. Riprodotto ruotando lo schermo con le
impostazioni a vista: `10` tornava `8`, e il budget tornava `100M`.

**E i campi di testo si riscrivevano sotto le dita.** `MoneyField`, `NameField` e
`DecimalField` scrivevano `remember(value) { mutableStateOf(...) }`, cioe' usavano come
chiave del ricordo **il valore che loro stessi cambiano**: si digita un carattere, il testo
viene interpretato, `onChange` aggiorna il valore, la chiave cambia, il testo viene
riscritto normalizzato. Il cursore salta, e se il testo a meta' non e' piu' interpretabile
`onChange` smette di essere chiamato — quindi il valore resta quello di partenza e il campo,
perdendo il fuoco, si ridisegna com'era. In `NameField` lo stesso difetto impediva di
scrivere uno **spazio**, perche' `onChange` manda il nome ripulito.

Corretti: le scelte passano da `rememberSaveable` con un `Saver`, e i tre campi tengono il
testo finche' hanno il fuoco. Verificato sul dispositivo: `10` e `60M` sopravvivono sia alla
perdita del fuoco sia alla rotazione.

Vale la pena tenere il metodo. La prima ipotesi — «forse il preset viene riapplicato» — era
sbagliata, e a smontarla e' stato **guardare i numeri**: coincidevano al valore con i
predefiniti di `sprint`, e quella coincidenza diceva dove cercare.

### Due difetti trovati dentro le prove, mentre si correggeva

Tutte e due nascondevano il resto, ed erano li' da prima.

**`MarketRhythmTest` non simulava il listino.** Simulava solo le aste — e' stato scritto
prima del 2026-08-24, quando il listino non c'era. `AiTurn` proponeva
`COMPRA_A_LISTINO` come prima mossa e il `when` la buttava via con `else -> false`: la
prova misurava una lega in cui si compra solo all'asta e vince sempre il piu' spregiudicato,
e infatti il club con l'aggressivita' piu' bassa restava a tredici giocatori **con i
crediti piu' alti di tutti in mano**. Adesso il listino c'e', e le tre asserzioni sul numero
di aste girano con `conListino = false`, cioe' nella lega che descrivono davvero — quella in
cui `market.instantBuyEnabled` e' spento, che il gioco sa ancora fare.

**E calcolava il tetto sui crediti pieni.** Il commento diceva di modellare
`committed_credits`, e lo faceva solo per il confronto finale: `ceilingFor` riceveva un club
con la cassa intera e restituiva un tetto che quel club non poteva permettersi. Non si
vedeva perche' i tetti erano minuscoli — di nuovo la curva dei prezzi — quindi la
differenza non cambiava nessuna decisione.

### La riprogettazione del 2026-08-23

Il proprietario ha allegato venticinque schermate di un'altra app e ha chiesto che MFoot
somigliasse a quelle. Due cose valgono la pena di essere scritte.

**È costata tre file, non trentacinque.** In tutta la cartella `ui/` c'erano **quattro**
colori scritti a mano fuori dal tema: tutto il resto passava dai token, quindi riscrivere
`Theme.kt` ha ridipinto ogni schermata insieme. Restano `Shell.kt` per il guscio e
`Atoms.kt` per il vocabolario dei componenti, più le rifiniture dove la struttura era
diversa. È la prova pratica della regola «nessun colore scritto a mano»: quel giorno è
valsa settimane.

**I nomi dei token non sono cambiati coi colori.** `elite` era verde e adesso è lavanda;
`core` era più chiaro del fondo e adesso è più scuro. I nomi dicono il **ruolo**, e il
ruolo non è cambiato: rinominarli avrebbe voluto dire toccare settecento punti di richiamo
per ottenere esattamente lo stesso pixel.

Due decisioni di gioco prese dal proprietario in quella sessione: **via il verde
dappertutto**, scala di valutazione compresa (adesso lavanda / bianco / grigio / grigio
spento), e **navigazione copiata dal riferimento**, non solo l'aspetto.

**Il secondo giro, lo stesso giorno.** Alla prima consegna il proprietario ha chiesto
«ancora più uguale, più vivace e complessa». Ne sono usciti: le **linguette sottolineate**
al posto dei chip per le sezioni di un posto (un chip dice «filtro», una linguetta dice
«dove sei»); la **testata ad archi** che sostituisce barra e nastro fuori dai cinque posti;
il **riquadro viola** che spiega, al posto dei paragrafi grigi che non leggeva nessuno; la
**barra di avanzamento** delle competizioni; il **selettore numerico**; la cupola e i tre
tondi sulla maglia in Casa; gli **stemmi** nelle schede partita.

I mockup in `docs/mockups/` sono del sistema **precedente** e non sono stati rifatti: se
si riaprono, non sono più il riferimento.

### Numeri di bilanciamento raggiunti

Misurati su migliaia di partite simulate, non stimati. Col **motore a duelli**, acceso il
2026-08-29; fra parentesi il motore vecchio, che resta in piedi dietro `duelliAttivi` e
sul quale ogni prova gira comunque.

| Metrica | Valore | Riferimento |
|---|---|---|
| Squadre pari — casa / pari / trasferta | 46,0% / 28,4% / 25,6%  (46,0 / 26,0 / 28,0) | calcio vero: 45 / 27 / 28 |
| Gol a partita | 2,89  (2,68) | 2,5-3,0 |
| Tiri a partita | 27,7  (23,8) | 24-28 |
| Conversione | 10,4%  (11,3%) | 10-12% |
| Squadra con +10 di overall | vince il 62% | non deve essere una certezza |
| Catenaccio vs Arrembante | 42,5% vs 54,8% | nessun assetto domina (banda: 15 punti) |
| Allenatore 5⭐ vs 1⭐ | 55,5% | conta, ma meno della rosa |

E le misure che prima non esistevano — quelle per cui i numeri d'insieme non bastavano:

| Metrica | Valore | Riferimento |
|---|---|---|
| Chi segna: attacco / centrocampo / difesa | 64,6% / 21,4% / 13,9% | non solo gli attaccanti |
| Marcatori diversi su 36 | 20 | non sempre gli stessi cinque |
| Duelli a partita | 267 | non due tiri di dado |
| Dribbling riusciti / tentati | 16,4 su 36,6 — **44,8%** | calcio vero: circa uno su due |
| Precisione dei passaggi | 77,2% | calcio vero: circa 80% |

**L'arrembante è più forte del catenaccio di dodici punti, e resta dentro la banda.** Va
letto sapendo cosa il collaudo *non* misura: `BalanceHarness` gioca **una** partita con le
gambe fresche, mentre l'arrembante consuma il 46% di stamina in più (ritmo alto per
pressing alto). In una lega con due partite al giorno quel costo si paga la sera, e il
banco di prova non lo vede. Se dopo una stagione vera l'arrembante risultasse comunque
dominante, la manopola da girare è `smorzamentoAssetto` — o il consumo del pressing.

### Il giocatore custom, misurato

Il budget non deve permettere di uscire con un titolare già pronto: il custom è un
progetto, non un acquisto. Un test costruisce il giocatore più forte possibile in ogni
ruolo, spendendo sempre sul punto col miglior rapporto peso/prezzo.

| Costruzione | Overall |
|---|---|
| Tutto sugli attributi, stelle a 1 | fino a 79 |
| Giocatore completo (stelle comprate) | sotto 72 |
| I fuoriclasse del mondo generato | 87-93 |

Con i primi scaglioni di costo usciva un **81**, cioè già dentro la fascia dei top: la
curva è stata resa più ripida finché il numero non è tornato dove doveva.

Asimmetria nota e fissata in un test: il portiere rende di più a parità di budget, perché
il suo overall dipende da quattro attributi invece che da sei.

---

## Verificato sul campo, non solo nei test

| Cosa | Come |
|---|---|
| Caricamento del mondo | 1.128 giocatori, 120 fra staff, 8 club AI sul database |
| Row Level Security | Da non membro si vedono zero righe; dopo `join_league` compaiono tutte |
| `ai_states` invisibile | Anche ai membri: una personalità leggibile renderebbe l'asta un esercizio di lettura |
| `players_public` | Non contiene i potenziali veri. Da `0023` gira anche con `security_invoker`: prima, essendo una vista, non applicava la policy `read_players` e un membro qualsiasi poteva leggere i giocatori di **ogni** lega. Mai sfruttato — l'app chiede sempre `league_id=eq.…` — ma era aperto |
| Sessione persistente | Chiusa e riaperta l'app: si rientra senza reinserire il codice |
| Conteggio giocatori | 1128 dopo la paginazione (prima ne arrivavano 1000) |
| Budget del custom | 15 punti su Passaggio → 65→80, costo 4×1 + 8×3 + 3×5 = 43, overall 65→69 |

---

## Da fare

### La cosa che manca davvero

**Nessuno ci ha ancora giocato.** Una persona, una lega di prova, un emulatore. Zero
stagioni complete. Divisioni, promozioni e spareggi sono implementati e testati in `core`,
e non hanno mai girato fino in fondo su un database vero.

Tutto quello che questo documento dice sul bilanciamento è misurato **in simulazione**.
Nessuno ha ancora scoperto che alla dodicesima giornata succede una cosa stupida che
nessuno aveva previsto — e succederà.

Non è un riempitivo. Ogni difetto grosso corretto finora — il mercato bloccato, le promesse
che si mantenevano da sole, gli orari sbagliati di due ore in tre file diversi — è stato
trovato perché **qualcuno ha guardato**, non perché una prova ha fallito.

### Visto sull'emulatore il 2026-08-25, capito e corretto lo stesso giorno

**«Metti all'asta» spariva dal piede della scheda giocatore.** Alla primissima apertura
dopo l'installazione c'era; riaprendo la stessa scheda più tardi non c'era più, su uno
svincolato, con zero aste aperte sui giocatori e il club regolarmente presente.

Era scritto qui come «visto e non capito», con l'indizio giusto — *«nel caso che non
funziona era già passato un giro leggero»*. La causa:

```kotlin
current.auctions.none { it.auction.targetId == row.player.id.value }
```

`targetId` è un id di **giocatore oppure di staff**, e le due tabelle hanno sequenze
separate: il giocatore 7 e l'allenatore 7 esistono tutti e due. I club del computer
aprivano aste sullo staff, e un'asta sull'allenatore numero 7 spegneva il pulsante sul
giocatore numero 7. Alla prima apertura l'elenco delle aste era ancora vuoto, quindi il
pulsante c'era; dopo il primo giro leggero le aste arrivavano e il pulsante spariva.

Ogni altro punto dell'app confrontava già anche `targetType`. Questo era l'unico rimasto
indietro — ed è esattamente il difetto che il commento dentro la vecchia migrazione `0028`
descriveva in anticipo, sulla tabella `listings`, senza che nessuno lo cercasse anche
altrove.

Corrette tre cose insieme, perché erano la stessa:

- il confronto adesso guarda anche il tipo (`MainActivity`);
- **i club del computer non aprono più aste sullo staff**: lo assumono a prezzo fisso come
  chiunque altro, il che toglie di mezzo la sorgente delle collisioni;
- e adesso **lo pagano**. `assumiDalFondo` faceva `update staff set club_id = …` e
  nient'altro: i computer prendevano allenatori e preparatori gratis mentre un umano li
  pagava. Non era bilanciamento, era una riga mancante.

Vale la pena tenere il metodo, non solo il risultato: il difetto è stato trovato perché
**qualcuno ha guardato** e perché quello che ha visto è stato scritto anche senza essere
capito. Un difetto visto e taciuto è il modo più rapido di ritrovarselo fra un mese senza
sapere da dove è arrivato.

### Il prossimo blocco, in ordine di valore

1. **Giocare una stagione vera, con gli amici.** Prima di costruire altro. Vale doppio
   adesso: il blocco del 2026-08-25 ha aggiunto un mercato, una finestra dentro la partita
   e quattro mosse nuove alle AI, e **niente di tutto questo ha ancora girato su un
   database vero**. I test dicono che le regole sono giuste; non dicono che alla dodicesima
   giornata non succeda una cosa stupida che nessuno aveva previsto.
2. **Guardare la prima finestra di contestazione dal vivo.** È il pezzo con più parti in
   movimento — SQL, tick e app che si scambiano crediti impegnati — e l'unico dove un
   errore lascia crediti bloccati su un'asta che non esiste più.
3. **Provare gli obiettivi su una stagione vera.** La regola e i verdetti hanno
   ventisei test in `core`, ma il giro completo — assegnazione, stagione, chiusura,
   premio accreditato — non è mai girato su un database vero. È il punto 1 di questo
   elenco visto da un'altra angolazione.
4. **Il tick impiega otto minuti a giro, e il grosso non è la build.**
   Misurato il 2026-08-23 dal registro pubblico delle esecuzioni: un giro riuscito dura
   **8 min 24 s**, di cui 50 secondi di build e circa **sette minuti e mezzo di
   elaborazione**. Portare il cron a dieci minuti non è bastato: sulle ultime cento
   esecuzioni **59 restano `cancelled`** perché i giri continuano ad accavallarsi, e la
   cadenza vera è fra i venti e i quaranta minuti.

   **Rimisurato il 2026-08-25, ed è peggiorato.** Gli intervalli fra un giro riuscito e
   il successivo, letti dal registro pubblico: 22, 51, 50, 48 minuti — e dopo l'ultimo
   sono passati cinquantuno minuti senza che ne partisse un altro. Il cron dice dieci.
   Non è (solo) l'accavallamento: GitHub ritarda le esecuzioni programmate dei
   repository gratuiti, e il ritardo qui vale **cinque volte** il periodo chiesto.

   Ha una conseguenza pratica che vale la pena sapere prima di aspettare: **una cosa
   pubblicata adesso può metterci un'ora a succedere nel mondo**. Per provare subito c'è
   `workflow_dispatch` nella tab Actions, con l'opzione «calcola senza scrivere».

   Non è solo spreco di CI. **L'anti-snipe è tarato su sessanta secondi**: se il tick
   passa ogni mezz'ora, un'asta che scade alle 21:00 chiude alle 21:35, e il meccanismo
   che dovrebbe far vincere chi valuta di più invece di chi ha il dito veloce smette di
   funzionare.

   Il sospetto, da leggere prima di intervenire: `loadSquad` fa **una query per club** ed
   è chiamata da sette punti diversi, uno dei quali dentro un ciclo su tutte le squadre.
   Con sedici club per lega, più leghe, e ogni risveglio AI che rilegge la propria rosa,
   sono centinaia di andate e ritorno verso Supabase, ognuna con la sua latenza di rete.
   La correzione sarebbe leggere le rose **una volta per lega** a inizio giro. È dentro il
   codice che sposta soldi, contratti e aggiudicazioni: va fatta con attenzione.

### Cosa fa e cosa non fa il tick, oggi

| Effetto | Stato |
|---|---|
| Chiusura aste, con fondi e contratto | ✅ |
| Scadenza contratti (il custom si rinnova d'ufficio) | ✅ |
| Rientro dai prestiti | ✅ |
| Scadenza trattative | ✅ |
| Entrate ricorrenti | ✅ |
| Stipendi | ✅ |
| Recupero stamina, col moltiplicatore del preparatore | ✅ |
| Simulazione partite, con crescita, morale e premi | ✅ |
| Avvio della stagione | ➖ non e' compito del tick: le competizioni le crea l'admin |
| Risveglio AI, con offerte vere sulle aste | ✅ |
| Presenze scritte dopo ogni partita | ✅ |
| Verifica promesse, contando le presenze | ✅ |
| Apertura dei colloqui dai fatti | ✅ |
| Risposta a scambi, prestiti e amichevoli | ✅ |
| AI che propongono, rinnovano e svincolano | ✅ |
| Riepilogo giornaliero, su Telegram | ✅ |
| Notifiche immediate, su Telegram | ✅ |
| Stime di scouting, dai minuti visti | ✅ |
| Allenamento della Primavera, una volta per giornata | ✅ |


### Il database: un file solo

Nell'SQL Editor di Supabase si incolla [`supabase/schema.sql`](supabase/schema.sql) e si
esegue. È tutto lì: tabelle, indici, permessi, funzioni.

Fino al 2026-08-25 erano **trentuno migrazioni numerate**, da `0001` a `0031`. Le ho
unificate su richiesta del proprietario — «basta che non siano 30 separate inutilmente» —
e il motivo per cui aveva ragione è che la storia che raccontavano non serviva a nessuno:
quattro versioni di `start_auction`, tre di `place_bid`, due di `buy_player`. Per sapere
cosa faceva davvero una funzione bisognava leggere trentuno file in ordine e ricordare
quale vincesse.

Il file è **rieseguibile**: `if not exists`, `create or replace`, `drop policy if exists`.
Rilanciarlo su un database già a posto aggiorna le funzioni e non cancella niente.

**Su un database che ha già le migrazioni `0001`-`0031` applicate, `schema.sql` basta e
non serve svuotare nulla:** le colonne sono le stesse una per una (verificato
meccanicamente, confrontando le colonne del vecchio schema con quelle del nuovo), quindi
l'unico effetto è sostituire le funzioni e aggiungere i sette indici nuovi.

Su un progetto Supabase vuoto, `schema.sql` costruisce tutto da zero.

**Quello che il file non fa** è aggiungere colonne a tabelle che esistono già con meno
colonne: `create table if not exists` su una tabella esistente non fa niente. Chi arriva
da uno schema più vecchio del `0031` deve svuotare e ripartire.

#### Cosa è cambiato nello schema il 2026-08-29

**Va rieseguito.** Cinque funzioni cambiate:

- `start_auction` e `propose_trade` rifiutano il giocatore custom;
- `delete_competition` non chiede piu' che non si sia giocato;
- `propose_friendly` e `respond_deal` leggono le ore di distanza dalla configurazione della
  lega invece delle tre scritte a mano, e la distanza si **ricontrolla alla risposta**: fra
  la proposta e l'accettazione l'admin puo' aver scritto una giornata di campionato.

E `sveglia_il_tick()` tiene il mondo sveglio fino alle 22:59 invece che alle 21:59.

Tre colonne nuove: `competitions.finished_at` e `competitions.winner_club_id`, che dicono al
server quando **smettere** di cercare il turno successivo di una coppa, e
`tick_state.last_stamina_at`, il punto da cui contare le ore di recupero.

Le colonne nuove hanno anche un `alter table ... add column if not exists` in coda alle
tabelle, ed e' una cosa che mancava al file: `create table if not exists` non tocca una
tabella che c'e' gia', quindi su un database in cui la lega sta girando una colonna aggiunta
dentro la `create table` **non sarebbe mai comparsa**. Il file sarebbe restato rieseguibile
e non avrebbe fatto niente, che e' il modo piu' silenzioso di rompere un aggiornamento.

Nessuna delle tre entra in una lettura condivisa dell'app, quindi la trappola di PostgREST
non scatta: il tick le legge via JDBC. L'unica lettura nuova dell'app — `first_half` e
`resume_at` sulla partita — chiede colonne che esistono dalla migrazione `0029`, ed e'
isolata nella query della singola partita: al peggio fallisce li' e la partita si vede solo
a fine gara, come prima.

#### Cosa è cambiato nello schema il 2026-08-25

| Novità | Perché |
|---|---|
| `staff_price(bigint)` | Il prezzo dello staff viveva solo dentro una schermata dell'app, quindi il server non poteva addebitarlo e l'unica strada restava l'asta |
| `buy_staff` senza riga di listino | Chi è libero si assume sempre: la riga la scriveva solo il tick, e il tick quasi non girava |
| `send_scout` con i minuti in configurazione | Erano 8-48 **ore** scritte in SQL. Adesso sono `rules.scoutMinutesBest/Worst`, e il massimo è due ore |
| Sette indici nuovi | `players(league_id, age)`, `staff(club_id, role)`, `contracts(club_id, squad)`, `auctions(target_type, target_id, status)`, `appearances(club_id, player_id)`, `listings(league_id, target_type, status)`, `fixtures(competition_id, match_day)` |

#### Cose che restano vere sui dati vecchi

Le leghe create prima della vecchia migrazione `0003` non hanno i pesi dei ruoli in
configurazione e **non possono accettare nuovi club**: per provare la fondazione va creata
una lega nuova.

**Se due leghe hanno lo stesso codice**, lo schema non le separa da solo — non c'è modo di
sapere in quale volevano entrare. `join_league` rifiuta il codice ambiguo invece di
sceglierne una a caso, e l'admin ne cambia uno da «Le mie leghe». Il codice delle leghe
create prima del `0022` non compare finché non lo si cambia una volta: dell'originale
esiste solo l'impronta cifrata.

---

## Decisioni prese, con il motivo

| Decisione | Perché |
|---|---|
| Mondo 100% procedurale | Zero licenze, zero manutenzione dati, bilanciamento totale, settore giovanile naturale |
| Kotlin nativo | Scelta dell'utente: migliore app Android, FCM nativo, funzionamento offline |
| `core` Kotlin/JVM, non KMP | Server e app sono entrambi JVM. Migrazione a KMP meccanica se servirà iOS |
| Backend a costo zero (Supabase + GitHub Actions) | Il mondo deve girare a telefoni spenti, ma senza pagare niente e senza lasciare acceso niente di proprio |
| Timeline pre-calcolata | Costo zero durante il live, nessuna divergenza, replay gratuito |
| Ordini condizionali + intervallo | Agency vera senza penalizzare chi non c'è alle 21 |
| Tutto in giornate, non giorni | Il ritmo reale è configurabile e nessun sistema si rompe |
| Ogni numero in `LeagueConfig` | L'admin controlla tutto, come nelle leghe fantacalcio |
| Potenziale nascosto | Sostituisce l'emozione dei nomi noti con la scommessa |
| Stamina come vincolo centrale | Rende necessarie rosa profonda e Primavera senza imporle |

---

## Bug veri trovati dai test

Non refusi: difetti di logica che sarebbero arrivati fino in produzione.

1. **La stamina non calava mai.** Il consumo per azione è ~0,26 punti su un valore
   intero: riarrotondando a ogni azione restava fermo per sempre. La rotazione della
   rosa — cioè metà del design del gioco — non sarebbe servita a niente.
2. **Chi usciva prima del 90' risultava con zero minuti.** Nessuna esperienza per un
   giocatore sostituito al 60'.
3. **La crescita applicava un solo punto per partita.** L'esperienza in eccesso non
   veniva mai spesa, quindi il moltiplicatore del player custom e quello
   dell'allenatore a 5 stelle erano decorativi.
4. **L'offerta minima d'asta pretendeva di superare il massimo del capofila**, che però
   è segreto. Nessuno avrebbe mai potuto fare un'offerta.
5. **Il cartellino rosso rompeva la formazione**, perché `Lineup` pretendeva sempre
   esattamente 11 giocatori.
6. **Le promesse si mantenevano da sole in un quarto d'ora.** Il tick incrementava un
   contatore a ogni giro invece di contare le partite, e i giri passano ogni cinque
   minuti. Il sistema che doveva rendere costoso promettere era il modo più rapido di
   alzare il morale gratis.
7. **Lo stesso difetto di fuso orario viveva in tre copie.** Tagliare lo scostamento
   `+02:00` e appiccicare una `Z` butta via due ore *senza fallire*: le aste chiudevano
   due ore dopo il conto alla rovescia, le partite comparivano due ore più tardi, e il
   registro diceva «mai» accanto a un giro appena avvenuto.
9. **Il mercato faceva la fila su una sola asta.** `tryBid(...) || tryOpenAuction(...)`:
   un `||` in corto circuito. Se esisteva anche una sola asta su cui offrire, l'AI
   offriva e non ne apriva nessuna � sei slot liberi, nove caselle vuote, risveglio
   finito. Misurato dopo: cinque aste aperte in tutta la lega al terzo giro, e club
   fermi fra uno e nove giocatori dopo venti. Nessun test lo prendeva perche' viveva
   dentro una funzione che ha bisogno di una connessione al database.
8. **Le AI si svegliavano una volta al giorno.** Dopo aver agito, il risveglio successivo
   cadeva nel passato e veniva spinto a domani. `checksPerDay` esisteva e non lo leggeva
   nessuno.
10. **La correzione delle aste rigiocava le partite.** Insegnare al tick che una cosa
    scaduta fuori finestra va fatta comunque è giusto per un'asta — se non si chiude
    adesso non si chiuderà mai più — e disastroso per una partita: il calcio d'inizio
    resta per sempre prima della finestra, quindi la stessa partita veniva ripianificata
    a ogni giro. Saltavano anche le due protezioni scritte apposta: al primo giro di una
    lega nuova si simulavano tutte le partite già in calendario, e col tick fermo da un
    mese se ne recuperava un mese malgrado il tetto annunciato nelle note. **Tre test lo
    dicevano e nessuno li aveva più eseguiti.**
11. **La Primavera spariva dopo un'offerta all'asta.** La rilettura dei soli club — quella
    che aggiorna i crediti impegnati — non portava `parent_club_id` né `division_level`,
    perché sono colonne che si chiedono a parte. Il montaggio viveva nel ViewModel, cioè
    in un posto solo, e ogni altra strada restituiva club a metà. Da lì: l'interruttore
    fra le due squadre svaniva, ricompariva «fonda la Primavera», e il server rispondeva
    — giustamente — che ce l'hai già.
12. **Le proprie offerte sparivano dalle aste aperte.** `bids?select=…` senza filtro
    contava sulle Row Level Security, ma da `0020` le offerte delle aste chiuse sono
    pubbliche: «tutte le offerte» ha smesso di voler dire «le mie». PostgREST tronca a
    mille righe e non lo dice, la storia sta in fondo alla tabella e arriva per prima.
13. **Due amici, lo stesso codice, due leghe diverse.** `join_league` faceva `limit 1` su
    un codice che non è mai stato univoco — e chi prova il gioco crea tre leghe di fila
    riusando lo stesso. Da quel momento il codice identifica un insieme, non una lega.
14. **La scheda giocatore mostrava sei etichette e cinque caselle vuote.** Presenze,
    minuti, gol e assist passavano una stringa vuota. Si concludeva che non si contassero,
    mentre `appearances` le contava da sempre.
15. **Gli umani finivano in Serie B dal primo giorno.** L'assegnazione iniziale ordinava
    tutti i club per forza e li distribuiva a serpentina, umani e AI mescolati. È una
    regola sensata per un campionato vero e sbagliata per una lega fra amici: chi si
    iscrive vuole giocare contro gli altri amici, non contro otto squadre del computer
    perché la sua rosa iniziale valeva tre punti di meno. La regola giusta — i giocatori
    veri partono tutti dalla massima serie — **era stata detta** e letta come
    un'osservazione.
16. **Si entrava in una lega senza sapere quale.** Non un difetto del programma: con codici
    diversi `join_league` faceva esattamente il suo mestiere. Il difetto è che l'app non
    diceva mai dove ti aveva portato, e riaprendola ci si rientrava dritto. Due amici hanno
    giocato in due leghe diverse convinti di essere nella stessa, e nessuno dei due poteva
    accorgersene.
17. **La scheda del giocatore non la trovava nessuno.** Esisteva dal principio, con overall,
    ogni attributo, stelle e crescita, e si apriva toccando una riga qualsiasi. Niente
    diceva che una riga si potesse toccare, quindi per il proprietario della lega quei dati
    semplicemente non esistevano.
18. **L'app non si aggiornava mai.** Letto il mondo all'avvio, non lo rileggeva più da sola:
    nessun timer, nessuna sottoscrizione, nessuna ricarica al ritorno da sfondo. Un club
    creato dopo la tua lettura era invisibile per sempre, e con lui le sue aste e le sue
    mosse. In un gioco multiplayer è il difetto che li contiene tutti: due amici nella
    stessa lega, ognuno con la sua fotografia di momenti diversi, convinti di giocare
    insieme. Peggiorato da una cosa di piattaforma: su Android uscire col tasto home e
    rientrare **non fa ripartire niente**, quindi «ho chiuso e riaperto» non ricaricava
    nulla — ed è la risposta che mi aveva fatto scartare la diagnosi giusta per un giorno
    intero.
19. **Le leghe rotte producevano esecuzioni verdi per sempre.** `runAllLeagues` catturava
    l'eccezione di una singola lega, faceva rollback e la metteva in `failures`, ma `main`
    restituiva sempre `0`. Su GitHub Actions l'esecuzione risultava verde e nessuno leggeva
    i log: una lega con un errore bloccante (come aste non chiudibili o migrazioni non ancora
    allineate) restava ferma per sempre. Ora `summary.failed` esce con codice 2 e tinge di
    rosso il giro.
20. **Il cron ogni 5 minuti si accavallava e si auto-annullava.** Un giro di tick durava
    circa 8 minuti (build + elaborazione) contro una frequenza cron di 5 minuti. Le corse
    si sovrapponevano e GitHub cancellava i lavori in coda (un terzo delle esecuzioni era
    `cancelled`). La frequenza è ora portata a 10 minuti (`*/10 * * * *`).
21. **Il menu laterale si riapriva da solo dopo ogni scelta.** `vai()` mette già
    `drawerOpen = false`, e il guscio chiamava `onToggleDrawer()` subito dopo: il toggle
    non confermava lo stato appena deciso, lo ribaltava. Toccare una voce chiudeva e
    riapriva il menu nello stesso istante, quindi sembrava che non facesse niente — e la
    schermata sotto era cambiata davvero. Trovato guardando uno screenshot, non da un test.
22. **Quattro chip del mercato non facevano niente.** «Svincolati · Aste · Tutto il mondo ·
    La mia rosa» stavano sopra la lista e chiamavano `onScope`, ma `Lista` nel Router impone
    l'ambito che la rotta porta con sé a ogni ricomposizione — deve farlo, o chi entra da
    «Svincolati» vedrebbe l'ultimo filtro lasciato attivo. I chip erano quindi inerti da
    sempre, e per giunta ripetevano tre destinazioni già presenti nella riga sopra. Tolti
    con tutto il cablaggio: `onScope` attraversava `MainActivity`, `Router` e il ViewModel
    per finire in un comando che non poteva funzionare.
23. **I chip delle schede scrivevano il nome dell'enum.** `Schede` aveva un'etichetta
    facoltativa che ripiegava su `Enum.name`, e nessun punto di richiamo ne passava una:
    sullo schermo si leggeva «SPOGLIATOIO», «OSSERVATORI». Adesso l'etichetta è
    obbligatoria e il compilatore chiede quella giusta.
24. **«Classifica» compariva tre volte sullo stesso schermo** indicando tre cose diverse: il
    posto, il segmentato, e la vista interna della competizione. Le due viste si chiamano
    ora «Punti» e «Partite» — dicono cosa si guarda invece di ripetere dove si è.
25. **Un'asta nasceva senza nessuno in testa, nemmeno chi l'aveva aperta.** Segnalato dal
    proprietario guardando il mercato. `start_auction` inseriva la riga dell'asta e
    nient'altro; il tick faceva lo stesso su due strade sue. Tre conseguenze, tutte
    sbagliate: un'asta che nessun altro guardava scadeva **deserta** e chi l'aveva aperta
    restava a mani vuote, avendo consumato uno slot per un'ora — ed è il caso più
    frequente, perché un'asta la si apre su chi si vuole; l'app scriveva «nessuno ha
    ancora offerto» anche sulla propria; e i crediti di chi apriva non risultavano
    impegnati, quindi lo stesso club poteva aprire tre aste che insieme valevano più della
    sua cassa. Peggiorato da un secondo scarto: l'AI teneva il conto dell'impegno **in
    memoria** mentre sul database non c'era niente, e al giro dopo il conto ripartiva da
    zero. La regola sta ora in `core` con due prove, e le due implementazioni — SQL e tick
    — la seguono. Chi **vende** un proprio giocatore continua a non offrire: sarebbe
    comprare da sé stesso.
26. **Le accentate erano scritte con l'apostrofo in tutto il testo visibile.** 176 fra
    `velocita'`, `puo'`, `perche'`, `sara'`, `giu'`. Corrette con una macchina a stati che
    distingue stringhe da commenti — i commenti il progetto li tiene in ASCII e restano
    così. Restano intatti di proposito `po'`, i minuti di gioco (`dal 45'`) e le virgolette
    semplici intorno ai nomi. Un test di `core` verificava una sottostringa di uno di quei
    messaggi: aggiornata l'asserzione, non l'intento.


