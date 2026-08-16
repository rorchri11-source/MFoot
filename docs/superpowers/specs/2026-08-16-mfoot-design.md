# MFoot — Design / Specifica

**Data:** 2026-08-16
**Stato:** approvata dall'utente, pronta per il piano di implementazione

---

## 1. Cos'è MFoot

Un gioco manageriale di calcio **multiplayer asincrono** per un gruppo privato di **8-20 amici**.

Un admin crea una lega, ne configura ogni regola, e distribuisce un codice d'accesso. Ogni partecipante crea il proprio club (nome, maglia custom) e **un giocatore unico creato da zero** che dovrà far crescere. I club competono in campionati e coppe configurabili, comprano giocatori all'asta, trattano fra loro, gestiscono contratti, Primavera, staff e morale dello spogliatoio.

**Il mondo è interamente generato proceduralmente.** Nessun dato reale, nessuna licenza.

### Il principio fondante

> **Il mondo gira sul server, sempre. Il client legge lo stato e lo disegna, non calcola mai niente.**

Se tutti i partecipanti spengono il telefono per tre giorni, al ritorno trovano le giornate giocate, le aste concluse, i contratti scaduti e le squadre AI che si sono mosse.

### Il secondo principio

> **Zero numeri nel codice. Ogni regola vive in `LeagueConfig`, deciso dall'admin.**

Il motore non sa cosa sia "100 crediti": lo chiede alla configurazione. Questo vale anche per l'AI, che ragiona sempre in *percentuale del budget disponibile*, mai in crediti assoluti — così si adatta da sola a qualsiasi economia l'admin abbia impostato.

---

## 2. Stack tecnologico

| Componente | Tecnologia | Motivazione |
|---|---|---|
| **core** | Kotlin/JVM, libreria pura | Nessuna dipendenza da Android, Ktor o database. Testabile in isolamento, condivisa da server e app. |
| **server** | Ktor + PostgreSQL, JVM | Processo sempre acceso per il World Tick. WebSocket integrati per aste e partite live. |
| **android** | Jetpack Compose + SQLDelight | App nativa, APK. Cache locale per funzionare con connessione scarsa. FCM nativo per le push. |
| **hosting** | VPS sempre acceso (Hetzner / Oracle Always Free / PC di casa) | ~0-5 €/mese. **Evitare i piani gratuiti che spengono l'app quando è inattiva** (Render, Heroku): fermerebbero il World Tick. |
| **notifiche** | FCM | Requisito esplicito: aste e trattative devono notificare. |

### Perché `core` è Kotlin/JVM e non Kotlin Multiplatform

Server e app girano entrambi su JVM/Android, quindi una libreria Kotlin/JVM con `jvmTarget = 11` è consumabile da entrambi senza la complessità di KMP. Se un giorno servirà iOS, la migrazione a KMP è meccanica perché `core` non ha già nessuna dipendenza di piattaforma.

### Distribuzione dell'APK

Canale "test interno" della Play Console: i partecipanti ricevono gli aggiornamenti automaticamente come per qualsiasi app (25 $ una tantum). In alternativa Firebase App Distribution (gratis).

**Regola di design collegata:** tutti i valori di bilanciamento vivono in `LeagueConfig` **sul server**. Ritoccare la crescita, i prezzi o le durate non richiede un nuovo APK. Solo i cambi di schermata lo richiedono.

---

## 3. Architettura

```
mfoot/                          progetto Gradle multi-modulo
│
├── core/        Kotlin/JVM · libreria pura · nessuna dipendenza di piattaforma
│   ├── config/          LeagueConfig, preset, validatore
│   ├── model/           Player, Club, Contract, Staff, Formation…
│   ├── rng/             generatore deterministico
│   ├── world/           WorldGenerator, banche nomi, template attributi
│   ├── match/           MatchEngine, zone, eventi, tattiche, ordini condizionali
│   ├── growth/          GrowthEngine, StaminaEngine, MoraleEngine
│   ├── calendar/        CalendarSolver, competizioni, fixture
│   ├── market/          AuctionRules, valutazione, trattative
│   ├── conversation/    ConversationEngine
│   └── ai/              personalità, decisioni, scheduling
│
├── server/      Ktor · JVM
│   ├── WorldTick        coroutine, ogni minuto, per sempre
│   ├── api/             REST: stato, azioni, storico
│   ├── ws/              WebSocket: aste e partite in diretta
│   ├── notify/          FCM + riepiloghi giornalieri
│   └── db/              PostgreSQL: proprietà, contratti, risultati
│
└── android/     Jetpack Compose
    ├── ui/              schermate
    ├── data/            SQLDelight, cache locale, sincronizzazione
    └── fcm/             ricezione notifiche
```

### La regola d'oro

`core` non importa **niente** di Android, **niente** di Ktor, **niente** di database, e non fa chiamate di rete né I/O. Prende oggetti, restituisce oggetti. Conseguenze:

1. Si testa con `gradlew :core:test` in pochi secondi.
2. Si può bilanciare a forza bruta: un'app console che simula 10.000 stagioni e riporta le statistiche aggregate.
3. La scelta del backend resta reversibile: cambia solo `server`, `core` non si tocca.

---

## 4. Determinismo

**Requisito:** la stessa partita, simulata due volte con lo stesso seed e gli stessi input, deve produrre esattamente lo stesso risultato.

**Implementazione:** un PRNG proprietario (`DeterministicRandom`, xorshift64* su aritmetica `Long`) invece di `kotlin.random.Random`. Solo aritmetica intera nei percorsi decisionali, così il risultato non dipende dalla piattaforma né dalla versione della stdlib.

**Uso del seed:**
- **Mondo** — generato una volta dal server e **salvato**. Non rigenerato sul client: una divergenza renderebbe il gioco incoerente fra i partecipanti.
- **Partita** — il server simula e salva la **timeline di eventi**. I client rileggono la timeline e la riproducono. Nessun rischio di divergenza, e zero costo durante i 90 minuti.

---

## 5. LeagueConfig — la configurazione dell'admin

Tutte le regole del gioco. Nessun valore duplicato nel codice.

### Setup
`nomeLega` · `codiceAccesso` · `numeroSquadre` · `modalità` (Multiplayer | Multiplayer+AI) · `numeroSquadreAI` · `livelloAI` · `rosaMinima` (default 16) · `rosaMassima` · `seedMondo`

### Economia
`creditiIniziali` · `entrateRicorrenti` (importo + cadenza: per giornata | settimana | fine competizione) · `premiPiazzamento[]` · `premioVittoria` · `premioPareggio` · `stipendiAttivi` · `formulaStipendio` · `costoRinnovo` (frazione del prezzo pagato, default 0,5) · `saldoNegativoConsentito`

### Mercato e aste
`durataAsta` · `rilancioMinimo` · `antiSnipeAttivo` · `antiSnipeSecondi` · `offertaMassimaAutomatica` · `asteParalleleMax` · `finestreMercato` (sempre | fasce orarie | solo a calendario vuoto) · `prestitiAttivi` · `clausoleAttive` · `scambiAttivi` · `durataContrattoGiornate` · `scadenzaOffertaTrattativa`

### Calendario
`dataInizio` · `dataFine` · `partiteAlGiornoPerClub` · `giorniBuca[]` · `fasceOrarie[]` · `velocitàPartita` (1× | 3× | 6× | istantanea)

### Competizioni (l'admin ne crea quante vuole)
`tipo` (girone | eliminazione diretta | gironi + eliminazione) · `andataRitorno` · `puntiVittoria` · `puntiPareggio` · `criteriSpareggio[]` · `partecipanti` · `montepremi`

### Regole di gioco
`customTitolareObbligatorio` · `customMinutiMinimi` · `moltiplicatoreCrescita` · `etàPicco` · `etàDeclino` · `amichevoliAttive` · `amichevoliContanoPerCrescita` · `infortuniAttivi` · `severitàInfortuni` · `squalificheAttive` · `primaveraAttiva` · `primaveraEtàMassima`

### Mondo generato
`numeroGiocatori` · `distribuzioneOverall` (quanti fuoriclasse / top / buoni / normali) · `distribuzioneEtà` · `quotePerRuolo` · `nazionalità[]`

### Notifiche
`notificheImmediateAttive` · `riepilogoGiornalieroOrario` · `tettoEventiAIPerClubAlGiorno` (default 2-3)

### Preset e validazione

**Preset obbligatori** — sessanta impostazioni in faccia al primo utilizzo sono un muro. Servono template (*Classica*, *Sprint*, *Fantacalcio-style*, *Personalizzata*) che riempiono tutto, con una sezione "Avanzate" per chi vuole smanettare.

**Validatore obbligatorio** — `ConfigValidator` deve rifiutare configurazioni matematicamente irrisolvibili *prima* che la lega parta. Esempio: 50 crediti iniziali con rosa minima 16 e quella distribuzione di Overall è impossibile; il validatore deve dirlo e proporre il valore realistico.

---

## 6. Il mondo generato

Al giorno 1 **tutti i giocatori sono svincolati**. Non esistono campionati esterni, non esiste un mondo oltre la lega. Il mondo è: un pool di giocatori generati + i club umani + i club AI.

### Scheda giocatore

```
anagrafica     id, nome, cognome, nazionalità, età, ruoli[]
attributi      tiro, dribbling, tecnica, passaggio, fisico, velocità,
               difesa, intercettazione, posizionamento,
               parata, uscita, riflessi           ← solo portieri
               piedeDebole 1-5, tecnicaStelle 1-5
overall        derivato dagli attributi, pesato sul ruolo
potMin/potMax  NASCOSTI — il client vede solo una forbice
tratti[]       Rigorista · TestaCalda · UomoSpogliatoio · Fragile · GrandiPartite …
stamina        0-100
morale         0-100
forma          −5 … +5
contratto      scadenzaInGiornate, stipendio, clausola?
```

### Passi della generazione

1. **Curva di Overall**, non distribuzione piatta. La coda alta deve essere sottile, altrimenti le aste perdono significato.
2. **Quote per ruolo** realistiche: più centrocampisti che punte, circa 1 portiere ogni 9 giocatori di movimento.
3. **Curva d'età** con picco a 24-27, coda di talenti 17-20 e di veterani 33-37.
4. **Potenziale** in funzione dell'età e del caso: più giovane = più margine e più incertezza.
5. **Attributi da ruolo + Overall** con rumore: una punta da 80 esce con tiro ~85, difesa ~35, fisico ~78, ±8.
6. **Nomi** da banche per nazionalità (~500 nomi + ~500 cognomi ciascuna): milioni di combinazioni plausibili in pochi KB.
7. **Tratti** assegnati con probabilità configurabile.

### Potenziale nascosto e scouting

`potMax` è un numero reale che il giocatore umano **non conosce mai**. Il client mostra una forbice (*"68 ora · potenziale 72-89"*) che si stringe man mano che il ragazzo accumula minuti, oppure immediatamente spendendo crediti in osservatori.

È il meccanismo che sostituisce l'emozione dei nomi noti: all'asta si scommette, non si compra una certezza.

---

## 7. Il motore di simulazione

```
MatchEngine.simulate(
    casa: TeamSetup,      // rosa + modulo + tattica + ordini condizionali
    ospite: TeamSetup,
    config: LeagueConfig,
    seed: Long
) → MatchResult { gol, timeline: List<MatchEvent>, statistiche per giocatore }
```

### 7.1 Dal modulo ai rating di zona

Il campo è diviso in **9 zone** (3 fasce × 3 altezze):

```
ATT_SX  ATT_C  ATT_DX
MID_SX  MID_C  MID_DX
DIF_SX  DIF_C  DIF_DX
```

Ogni giocatore schierato contribuisce a 1-3 zone con pesi dipendenti dal ruolo (un terzino sinistro: 70 % a `DIF_SX`, 30 % a `MID_SX`). Gli attributi rilevanti cambiano per fascia di campo:

| Zone | Attributi che pesano |
|---|---|
| Difensive | difesa, intercettazione, fisico, posizionamento |
| Centrocampo | tecnica, passaggio, fisico, intercettazione |
| Offensive | dribbling, tiro, velocità, tecnica |

Poi si sommano i modificatori: tattica, fattore campo, morale, coesione, stelle dell'allenatore, forma, stamina.

**Il rating di zona è tutto ciò che il resto del motore deve sapere.** I singoli giocatori servono solo per attribuire gli eventi.

Questo modello rende **visibile** la debolezza di una fascia: se l'avversario ha un terzino sinistro scarso, le azioni passano da lì e il giocatore lo vede.

### 7.2 La catena di possesso

Stato: *chi ha la palla, in quale zona, a che minuto*. Ogni azione copre 1-2 minuti di gioco.

```
Δ = ratingAttacco[zona] − ratingDifesa[zona specchiata]
p = 1 / (1 + e^(−Δ / K))          sigmoide, K ≈ 10
```

- In zona offensiva → alta probabilità di tiro
- Altrimenti → con probabilità `p` avanzi di zona, con `1−p` perdi palla
- In parallelo, piccole probabilità di fallo, angolo, cartellino, infortunio

**L'irregolarità richiesta emerge da sola**: le catene hanno lunghezza variabile, quindi capitano quindici minuti di nulla seguiti da tre occasioni in due minuti. Non va scritta a mano.

`K` è la manopola che regola quanto conta la sorpresa. Va tarata con il simulatore a forza bruta.

### 7.3 Risoluzione del tiro

```
xG = xG_base[zona] × f(tiro, tecnica) × malusPiedeDebole × pressione
pParata = sigmoide( (GK.parata + GK.riflessi)/2 − qualitàTiro )
```

`ATT_C` vale molto più di `ATT_SX`/`ATT_DX`: segnare da posizione defilata è raro, e questo da solo rende preziosi gli attaccanti centrali veri.

**L'assist va a chi ha toccato la palla nell'azione precedente**, così le statistiche dei centrocampisti emergono senza modellarle a parte.

### 7.4 Chi tocca la palla

Dentro una zona il giocatore viene estratto **pesato sulla sua rilevanza**: in `ATT_C` la punta esce spesso, il mediano quasi mai. Da qui emergono tutte le statistiche individuali — gol, assist, parate — che sono esattamente l'input del sistema di crescita.

### 7.5 Interattività

Deciso: **ordini condizionali pre-partita + finestra all'intervallo.**

- **Ordini condizionali**: regole impostate prima del fischio (*"se sono sotto dal 70', dentro l'attaccante per un difensore"*, *"se vinco di 2, passa a difensivo"*, *"se un giocatore prende un giallo, sostituiscilo"*). Il motore le valuta fra un'azione e l'altra.
- **Finestra all'intervallo**: il server simula il primo tempo, apre una finestra di N minuti reali per cambi e modifiche, poi simula il secondo tempo.

Conseguenza architetturale: **due simulazioni per partita**, non un tick continuo. Chi non c'è all'intervallo non è tagliato fuori, perché i suoi ordini condizionali girano comunque.

### 7.6 Esperienza live

I 90 minuti scorrono per intero, con highlight irregolari. Due livelli:

**Livello ambiente** (sempre attivo): zona della palla, possesso, indicatore di pressione/momentum. Non chiede attenzione.

**Livello highlight**: ogni evento porta un punteggio di **pericolosità 0-100**.

| Pericolosità | Reazione della UI |
|---|---|
| 0-20 · ambiente | Solo la palla che cambia zona |
| 21-50 · notevole | Riga nel feed: fallo, angolo, tiro da fuori |
| 51-80 · occasione | Card highlight: parata, palo, ammonizione |
| 81-100 · decisivo | Interruzione piena: gol, rigore, rosso, infortunio. Animazione + push |

**Momentum**: dopo un gol subito una squadra può ribaltarsi o crollare. È il meccanismo che genera le rimonte, cioè le partite di cui si parla il giorno dopo.

**Velocità configurabile**: 1× (90 minuti reali) fino a 6× (15 minuti) o istantanea. Con 2 partite al giorno, il default dev'essere compresso.

### 7.7 Come si verifica che funzioni

Test automatici, resi facili dal fatto che `core` è puro:

- Squadre identiche → casa ~45 % · pari ~27 % · trasferta ~28 %
- Gol per partita: media 2,5-3,0
- Squadra con +10 di Overall → vince ~65 % delle volte, **non il 95 %**
- Stesso seed → stesso identico risultato, 1.000 volte su 1.000

> **Se la squadra più forte vince sempre, il gioco è morto.** La terza riga è il test più importante del progetto.

---

## 8. Il player custom

Ogni partecipante crea un giocatore da zero: nome, cognome, ruolo, aspetto. Overall base **65**, con un budget di **100 punti abilità** da distribuire (piede debole e tecnica costano 10 punti per stella).

### Il problema, e la soluzione

Un giocatore da 65 in un mondo dove i migliori stanno a 88+ non entrerebbe mai in campo. Ma la crescita dipende da minuti, gol, assist e voti: se non gioca non cresce, e se non cresce non giocherà mai.

**Soluzione, tre meccanismi complementari:**

1. **Obbligo di titolarità** (configurabile) — il custom deve stare negli 11 e giocare almeno N minuti. Non è una punizione: è il vincolo che rende il gioco personale. Ogni squadra ha un punto debole strutturale, e quel punto debole è il proprietario. La bravura sta nel costruirci intorno.
2. **Potenziale alto + crescita 3-4× più rapida** dei generati. L'arco completo dura ~2 mesi reali con stagioni da 10-19 giorni.
3. **Bonus leader** — piccolo bonus di morale/coesione alla squadra, così non è del tutto passivo nella prima stagione.

### Regole speciali

- **Non vendibile, non svincolabile.**
- **Prestabile sì** — e diventa una mossa sensata: lo mandi a giocare titolare in un club più debole per farlo crescere, esattamente come i giovani veri.

---

## 9. Sistemi del giocatore

### 9.1 Crescita

```
xp = f(minuti, voto, gol, assist, parate, importanza partita)
     × moltiplicatoreEtà   22-26: ×2,0 | 27-28: ×1,0 | 29-31: ×0,3 | 32+: negativo
     × stelleAllenatore    1★ ×0,6 … 5★ ×1,8
     × moltiplicatoreConfig
     × rendimentiDecrescenti   (quanto più vicino a potMax, tanto più lento)
```

L'esperienza si accumula in un pool nascosto; superata una soglia sale **un attributo specifico** fra quelli del ruolo. Il giocatore non vede "+0,3 Overall" ma *"Ferrero: Tiro 74 → 75"*, che è molto più soddisfacente.

**Attenzione al ritmo:** con 38 giornate in 19 giorni reali, la crescita per partita dev'essere piccola e frazionaria, o un custom da 65 arriva a 90 in due settimane e il gioco finisce. Deve *sentirsi* ogni giorno ma richiedere una stagione intera per +10.

### 9.2 Stamina e staff di recupero

Cala con i minuti giocati, pesata su `fisico` e sull'intensità tattica. Recupera ogni giorno: `base × stelleStaff`, con i giovani che recuperano più in fretta. Sotto soglia: malus agli attributi in partita e rischio infortunio.

Lo staff di recupero è valutato **1-5 stelle** e si prende all'asta come gli allenatori.

> **È il sistema che tiene in piedi il ritmo.** Con due partite al giorno non si possono schierare gli 11 migliori due volte: bisogna turnare. Quindi serve una rosa profonda, serve la Primavera, serve lo staff. Il "minimo 16 giocatori" diventa una necessità sentita invece che una regola imposta.

### 9.3 Primavera

Rosa separata sotto un'età massima configurabile. I giovani crescono giocando lì invece di restare in tribuna. Promozione e retrocessione fra le due rose in qualsiasi momento (o a finestre, secondo config). L'admin decide se la Primavera ha un proprio campionato.

Decisione ricorrente e vera: *lo promuovo ora o gli faccio fare un'altra stagione da titolare?*

### 9.4 Morale

**Sale con:** minuti da titolare, gol, assist, voti alti, vittorie, rinnovo.
**Scende con:** panchina prolungata, sostituzione precoce, sconfitte, offerta rifiutata, un compagno più scarso preferito a lui.

**Effetti:** modificatore alle prestazioni; sotto soglia, richiesta di cessione; **un giocatore con morale basso può rifiutare il rinnovo**.

### 9.5 Conversazioni

Quando il morale è basso (o quando lo si desidera) si convoca il giocatore. Dialogo a 3-4 opzioni, **l'esito dipende dai tratti**: un *TestaCalda* reagisce male a un rimprovero, un *UomoSpogliatoio* accetta la panchina se glielo si spiega. I tratti smettono di essere decorazione.

**Le promesse** sono la meccanica migliore del sistema: *"giocherai titolare le prossime tre partite"*. Il World Tick se lo segna e verifica. Mantenuta → morale alle stelle. Tradita → crollo e richiesta di cessione. Un debito vero, controllato dal server.

---

## 10. Calendario

Non è una lista fissa di partite: è un **risolutore di vincoli**.

```
Input (admin):   dataInizio, dataFine, partiteAlGiornoPerClub,
                 giorniBuca[], fasceOrarie[]
Output:          tutte le fixture, distribuite sui giorni reali,
                 rispettando i vincoli e coordinando le competizioni parallele
```

Gli accoppiamenti usano il metodo del cerchio (tabelle di Berger). La parte specifica è la distribuzione sui giorni con i vincoli dell'admin, e il coordinamento fra un club che gioca contemporaneamente campionato e coppa.

**Va costruito presto: crescita, contratti, stipendi e finestre d'asta dipendono tutti da lui.**

### Unità di tempo

> **Tutto si misura in giornate di gioco, mai in giorni reali.**

Contratto = "6 giornate". Crescita = "ogni giornata". Stipendi = "ogni giornata". L'admin decide separatamente quanto dura una giornata nel mondo reale, e nessun sistema si rompe se cambia idea.

Scrivere `plusDays(14)` nel codice dei contratti è un errore che si paga dopo mesi.

### Riferimento di scala

20 squadre, girone singolo = 19 giornate; a 2 partite al giorno per club ≈ 10 giorni reali. Andata e ritorno = 38 giornate ≈ 19 giorni.

### Amichevoli

Attivabili dall'admin, servono al lato sociale (sfottò, prove di formazione).

⚠️ **Trappola da chiudere:** se un'amichevole facesse crescere i giocatori, si potrebbe fare *growth farming* chiedendone quindici al giorno. Le amichevoli danno crescita **zero o dimezzata**, oppure esiste un tetto giornaliero di partite che contano.

---

## 11. Mercato

### 11.1 Avvio della lega

Tutti svincolati al giorno 1. L'admin sceglie:

- **Serata d'asta** (fantacalcio classico) — tutti presenti, a turno si chiama un giocatore e parte un'asta rapida. È il momento sociale più forte; costa due o tre ore in cui ci devono essere tutti.
- **Asta asincrona** — decine di aste parallele a scadenza con offerta massima automatica. Nessuno deve essere presente.

Consiglio: serata d'asta per la prima stagione, asincrona per le successive.

### 11.2 Asta durante la stagione

Un club chiama uno svincolato → l'asta si apre → **tutti ricevono notifica** → dura quanto configurato → chi resta se lo prende.

| Meccanismo | Perché serve |
|---|---|
| **Offerta massima automatica** | Dichiari "fino a 30" e il sistema rilancia per te. Elimina del tutto la pressione di controllare ogni ora. |
| **Anti-snipe** | Offerta negli ultimi N secondi → timer esteso. |
| **Blocco fondi** ⚠️ | Offrire 20 rende quei 20 **impegnati**. Impedisce di vincere cinque aste con i soldi per una. **Senza questo la lega si rompe.** |
| **Finestre di mercato** | Le aste girano solo quando il calendario è libero. Il World Tick le apre da solo. |
| **Tetto aste parallele** | Evita che un club blocchi metà mercato. |

### 11.3 Trattative dirette

Ogni offerta è un pacchetto: **crediti + giocatori in scambio + clausole + durata contratto**. Il proprietario riceve notifica e può accettare, rifiutare o controproporre. **Ogni offerta ha una scadenza**, altrimenti restano appese per sempre.

**Clausola rescissoria** — modellata come *voce negoziabile del contratto*, non come proprietà del giocatore. Chi la subisce rischia di perderlo, chi la ottiene ha un'opzione d'acquisto. Diventa moneta di scambio: *accetto 17 invece di 18, ma tu gli metti una clausola a 25*. Funziona soprattutto sui giovani che crescono.

### 11.4 Prestiti

Durata in **giornate**, canone in crediti per giornata o forfait. Opzioni decise dall'admin: chi paga lo stipendio, se è interrompibile, se il giocatore può giocare contro il club proprietario. **Alla scadenza il World Tick lo restituisce da solo**, anche con tutti offline.

### 11.5 Contratti

Durata in giornate, stipendio per giornata (attivabile). Alla scadenza: rinnovo a `costoRinnovo × prezzo pagato`, oppure svincolo. Nessuna azione entro il limite → svincolo automatico dal World Tick.

**Aggancio col morale:** un giocatore con morale basso può rifiutare il rinnovo.

### 11.6 Integrità economica

Ogni operazione che tocca i crediti gira in **transazione con lock di riga**:

```
creditiDisponibili = creditiTotali
                   − creditiImpegnatiInAste
                   − creditiImpegnatiInOfferte
```

I crediti non devono **mai** andare negativi (salvo config esplicita) né essere spesi due volte. È il tipo di bug che rompe una lega e fa litigare le persone.

---

## 12. L'AI

**Requisito dell'utente:** competente e competitiva — può benissimo intromettersi nei duelli fra umani — ma **mai uno sciame**.

### Il problema da evitare

```
SBAGLIATO                              GIUSTO
20:14:00  25 AI valutano Ferrero       20:14  "Verdemar" apre l'asta      →  8
20:14:01  25 rilanci                   20:41  "Nordkap" rilancia          →  11
20:14:02  25 notifiche                 21:02  Tu imposti massimo 20       →  12
20:14:03  prezzo a 87                  22:30  Nordkap 14, il tuo auto 15
                                       23:15  Nordkap tocca il tetto, molla
                                       07:00  Chiusa. Tuo per 17.
                                              → 2 notifiche in tutto
```

### Personalità

Ogni club AI riceve alla creazione della lega:

```
aggressivitàMercato       0…1
preferenzaGiovaniPronti   0…1
disciplinaBudget          0…1     quanto rischia di svenarsi
pazienza                  0…1     quanto aspetta prima di rilanciare
ossessioni[]              "vuole sempre un portiere forte" · "ama i talenti"
finestraAttività          "vive la sera, 20:00-23:00"
frequenzaControllo        1-2 volte al giorno
latenzaRilancio           18 minuti … 3 ore
```

I club AI diventano riconoscibili — *"quello si compra sempre i giovani"* — senza essere furbi.

### Valutazione

```
valore = f(overall, età, potenziale STIMATO, ruoli mancanti)
       × personalità
       × situazione (ho 14 giocatori? mi manca il portiere?)
```

Espresso **sempre in percentuale del budget disponibile**, mai in crediti assoluti.

### Le sei regole anti-sciame

1. **Risvegli scaglionati.** Ogni AI ha un campo `prossimoRisveglio`. Il World Tick **non scorre tutte le AI**: sveglia solo quelle il cui orario è arrivato. Un'AI che dorme **non sa nemmeno che l'asta esiste**. È la riga di codice che trasforma uno sciame in venticinque individui.
2. **Si scansano da sole.** L'appetibilità di un giocatore **cala in base a quante AI ci sono già sopra**. Risultato naturale: 1-3 AI per asta, mai di più. Gusti diversi le spargono su obiettivi diversi.
3. **Tetto di azioni giornaliere.** 1-3 mosse di mercato al giorno per AI.
4. **Tetto di notifiche.** ⚠️ Garanzia imposta dal server: **massimo N eventi generati da AI per club umano al giorno**. È una regola del server, non una speranza sul comportamento dell'AI.
5. **Non insistono.** Dopo un rifiuto non ritentano per N giornate. Nessuna offerta per un giocatore appena acquistato.
6. **Non barano mai.** ⚠️ L'AI stima `potMax` **con la stessa incertezza degli umani**. Non vede i valori nascosti. Un'AI che conoscesse i potenziali veri comprerebbe sempre i giovani giusti e sembrerebbe truccata.

### Rilanci

Ritardo umano (30 secondi - 5 minuti), incrementi piccoli, tetto calcolato una volta all'inizio e **mai superato**.

### Gestione squadra

Schiera il miglior giocatore per ruolo **rispettando la stamina** (turna). Imposta ordini condizionali semplici. Rinnova i migliori, svincola gli scarsi. Promuove dalla Primavera quando un giovane supera un titolare. Partecipa alle aste per allenatori e staff.

### Verifica

**Una lega di sole AI per dieci stagioni**, come test automatico: le rose restano sensate? Un club accumula tutti i migliori? L'economia esplode o si blocca? Gira in secondi perché `core` è puro.

---

## 13. Notifiche

Un ping per ogni evento in una lega da 25 club porta alla disinstallazione in tre giorni.

| Tipo | Quando |
|---|---|
| **Immediate** | Solo decisioni con limite di tempo: superato oltre il tuo massimo · offerta per un tuo giocatore · un giocatore chiede di parlarti · contratto in scadenza domani |
| **Riepilogo giornaliero** | Tutto il resto in un solo messaggio: *"3 aste chiuse · 2 giocatori cresciuti · il Nordkap ha preso un attaccante · domani giochi alle 21"* |

Configurabile dall'admin.

---

## 14. Il World Tick

Il cuore del server. Una coroutine che gira **ogni minuto, per sempre**:

```
· Partite in programma in questo minuto?   → simula, salva timeline, notifica
· Aste scadute?                            → assegna, addebita, notifica
· Trattative scadute?                      → chiudi
· Contratti in scadenza?                   → rinnova o svincola
· Prestiti in scadenza?                    → restituisci
· Ora di distribuire i crediti?            → distribuisci
· AI da svegliare (prossimoRisveglio)?     → falle agire
· Stamina, morale, crescita, forma         → aggiorna
· Promesse da verificare?                  → verifica
· Ora del riepilogo giornaliero?           → invia
```

### Recupero e idempotenza

Il server salva `ultimoTickElaborato`. Al riavvio dopo un'interruzione **recupera tutto quello che sarebbe dovuto succedere**, in ordine, **una volta sola**.

Un'asta non deve poter essere assegnata due volte perché il server è ripartito. Ogni operazione del tick è idempotente e protetta da chiave univoca sul minuto elaborato.

---

## 15. Fasi di costruzione

L'ordine è scelto perché ogni fase è verificabile da sola e sblocca la successiva.

| # | Fase | Contenuto | Verificabile con |
|---|---|---|---|
| **1** | **Fondamenta di `core`** | `DeterministicRandom`, modelli, `LeagueConfig`, preset, `ConfigValidator` | test unitari |
| **2** | **WorldGenerator** | banche nomi, template attributi, curve, potenziale, tratti | test su distribuzioni |
| **3** | **MatchEngine** | zone, catena di possesso, tiri, eventi, ordini condizionali | i 4 test di §7.7 |
| **4** | **Bilanciamento** | app console che simula 10.000 partite/stagioni | taratura di `K` e delle curve |
| **5** | **Growth · Stamina · Morale** | crescita per attributo, recupero, morale, conversazioni | test unitari |
| **6** | **CalendarSolver** | Berger + vincoli admin + competizioni parallele | test sui vincoli |
| **7** | **Market** | valutazione, aste, blocco fondi, trattative, prestiti, contratti | test unitari |
| **8** | **AI** | personalità, scheduling scaglionato, decisioni, anti-sciame | lega di sole AI × 10 stagioni |
| **9** | **Server** | Ktor, PostgreSQL, World Tick, REST, WebSocket | test d'integrazione |
| **10** | **Android** | Compose, cache locale, FCM, schermate | manuale + emulatore |

**Le fasi 1-8 sono interamente in `core`**: non richiedono server, database, né Android, e si testano con `gradlew :core:test`.

---

## 16. Rischi noti

| Rischio | Mitigazione |
|---|---|
| **Bilanciamento sbagliato** — il gioco gira ma non diverte | Fase 4 esiste apposta. Bilanciare a occhio un manageriale è impossibile; il simulatore a forza bruta lo rende un problema misurabile. |
| **Crescita troppo veloce** col ritmo intenso | Crescita frazionaria + rendimenti decrescenti, tarati in fase 4. |
| **Sciame AI** | Le sei regole di §12, la n°4 imposta lato server. |
| **Doppia spesa dei crediti** | Transazioni con lock di riga, blocco fondi. |
| **Server giù** | `ultimoTickElaborato` + recupero idempotente. |
| **Ciclo UI lento** su Android | Accettato consapevolmente: è il costo della scelta Kotlin nativo. Le schermate si raffineranno a iterazioni. |
| **Scope** | 10 fasi, ognuna verificabile. Nessuna fase dipende da quella dopo. |

---

## 17. Decisioni prese, con il motivo

| Decisione | Motivo |
|---|---|
| Mondo 100 % procedurale | Zero licenze, zero manutenzione dati, bilanciamento totale, settore giovanile naturale |
| Kotlin nativo (non Blazor/C#) | Scelta dell'utente: migliore app Android, FCM nativo, funzionamento offline |
| `core` Kotlin/JVM, non KMP | Server e app sono entrambi JVM. Migrazione a KMP meccanica se servirà iOS |
| Server sempre acceso | Il mondo deve girare a telefoni spenti — requisito esplicito |
| Timeline pre-calcolata | Costo zero durante il live, nessuna divergenza, replay gratuito |
| Ordini condizionali + intervallo | Agency vera senza penalizzare chi non c'è alle 21 |
| Tutto in giornate, non giorni | Il ritmo reale è configurabile e nessun sistema si rompe |
| Ogni numero in `LeagueConfig` | L'admin controlla tutto, come nelle leghe fantacalcio |
| Potenziale nascosto | Sostituisce l'emozione dei nomi noti con la scommessa |
| Stamina come vincolo centrale | Rende necessarie rosa profonda e Primavera senza imporle |
