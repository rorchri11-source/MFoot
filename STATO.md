# MFoot — stato del progetto

**Aggiornato:** 2026-08-25
**Test:** 716 verdi, 0 falliti
**Verificato:** `core:test` completo e `android:assembleDebug`. **Il blocco del 2026-08-25
— mercato, incarichi, AI, intervallo — non è ancora girato su un database vero**: le
migrazioni `0027`-`0030` vanno applicate prima di installare l'APK

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

Misurati su migliaia di partite simulate, non stimati:

| Metrica | Valore | Riferimento |
|---|---|---|
| Squadre pari — casa / pari / trasferta | 45,1% / 28,0% / 27,0% | calcio vero: 45 / 27 / 28 |
| Gol a partita | 2,77 | 2,5-3,0 |
| Squadra con +10 di overall | vince il 62% | non deve essere una certezza |
| Catenaccio vs Arrembante | 50,5% vs 46,6% | nessun assetto domina |
| Allenatore 5⭐ vs 1⭐ | 55,5% | conta, ma meno della rosa |

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

### Visto sull'emulatore il 2026-08-25, e non chiuso

**«Metti all'asta» non compare più nel piede della scheda giocatore.** Alla primissima
apertura dopo l'installazione c'era; riaprendo la stessa scheda più tardi non c'è più, su
uno svincolato, con zero aste aperte e il club regolarmente presente — cioè con
`canAuction` che dovrebbe essere vero.

Non è stato riprodotto in modo pulito e **non è chiaro se dipenda dalle modifiche del
2026-08-25**: il calcolo di `canAuction` in `MainActivity` non è stato toccato. L'unica
differenza osservata fra il caso che funziona e quello che non funziona è che nel secondo
era già passato almeno un giro leggero (`aggiornaLeggero`), che da oggi rilegge anche
listino, acquisti e intervalli. È il primo posto dove guardare.

Sta scritto qui invece che nella lista delle cose fatte perché **è stato visto e non
capito**, e un difetto visto e taciuto è il modo più rapido di ritrovarselo fra un mese
senza sapere da dove è arrivato.

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

### Migrazioni SQL da eseguire

Nell'SQL Editor di Supabase, in ordine. Sono tutte rieseguibili.

| File | Contenuto |
|---|---|
| `supabase/migrations/0001_schema.sql` | Tabelle, vista pubblica, `place_bid`, RLS |
| `supabase/migrations/0002_create_league.sql` | `create_league`, `join_league` |
| `supabase/migrations/0003_club.sql` | `create_club` e il conto del budget lato server |
| `supabase/migrations/0004_auctions.sql` | `start_auction`, prezzo pubblico sulle aste |
| `supabase/migrations/0005_competitions.sql` | `create_competition`, `delete_competition` |
| `supabase/migrations/0006_config.sql` | `update_league_config` |
| `supabase/migrations/0007_tick_state_read.sql` | La policy che rendeva `tick_state` leggibile |
| `supabase/migrations/0008_trades.sql` | Gli scambi |
| `supabase/migrations/0009_divisions.sql` | Le divisioni |
| `supabase/migrations/0010_conversations.sql` | Il morale dai colloqui |
| `supabase/migrations/0011_promises.sql` | Le promesse |
| `supabase/migrations/0012_partite_giocate.sql` | `appearances`: chi ha giocato, partita per partita |
| `supabase/migrations/0013_colloqui.sql` | `conversations` e le funzioni per aprirla e chiuderla |
| `supabase/migrations/0014_trattative.sql` | Prestiti e amichevoli, `competitions.kind` |
| `supabase/migrations/0015_vendite.sql` | Vendere i propri giocatori all'asta |
| `supabase/migrations/0016_scouting.sql` | Le stime che si stringono |
| `supabase/migrations/0017_primavera.sql` | Spostare un giovane, e la traccia dell'allenamento |
| `supabase/migrations/0018_seconda_squadra.sql` | La Primavera diventa un club vero |
| `supabase/migrations/0019_staff_e_scouting.sql` | Staff assegnabile, missioni, under 20 fuori dalle aste |
| `supabase/migrations/0020_aste_trasparenti.sql` | A fine asta si vede chi ha offerto quanto |
| `supabase/migrations/0021_controproposte.sql` | Le trattative diventano un botta e risposta |
| `supabase/migrations/0022_una_lega_sola.sql` | Codice d'accesso univoco, rileggibile e cambiabile |
| `supabase/migrations/0023_chi_ha_offerto.sql` | La cronologia pubblica delle aste aperte |
| `supabase/migrations/0024_obiettivi.sql` | Gli obiettivi di stagione e i premi |
| `supabase/migrations/0025_entrare_sapendo_dove.sql` | `peek_league`: che lega apre un codice, prima di entrarci |
| `supabase/migrations/0026_chi_apre_ha_offerto.sql` | Chi apre un'asta per comprare parte in testa |
| `supabase/migrations/0027_incarichi_e_ordini.sql` | Le tre colonne degli incarichi da palla ferma |
| `supabase/migrations/0028_listino_e_contestazione.sql` | `listings`, `purchases`, e le funzioni del mercato immediato |
| `supabase/migrations/0029_finestra_intervallo.sql` | `resume_at` e `first_half`: la partita si ferma al 45' |
| `supabase/migrations/0030_admin_svincoli_staff.sql` | Svincolo annunciato, staff sul listino, gli strumenti dell'admin |

**`0028` e `0030` vanno applicate insieme, e prima dell'APK.** Non per abitudine: l'app
chiede `listings.target_type` — senza, PostgREST rifiuta l'intera query e il listino resta
vuoto per sempre. La colonna nasce dentro `0028` proprio perché `players` e `staff` hanno
sequenze di id separate, e un listino che non distingue i due vende un allenatore a chi
crede di prendere un centrocampista.

**`0014` va applicata prima di installare l'APK.** Aggiunge una colonna a `competitions`,
e una colonna nuova dentro una SELECT condivisa non è un'aggiunta: PostgREST rifiuta
l'intera query per una colonna che non esiste. È già successo con `clubs.division_level`,
e aveva rotto tutta l'app.

Le leghe create prima della migrazione `0003` non hanno i pesi dei ruoli in
configurazione e **non possono accettare nuovi club**: per provare la fondazione va
creata una lega nuova.

**Se due leghe hanno lo stesso codice**, `0022` non le separa da sola — non c'è modo di
sapere in quale volevano entrare. Da lì in poi `join_league` rifiuta il codice ambiguo
invece di sceglierne una a caso, e l'admin ne cambia uno da «Le mie leghe». Il codice
delle leghe create prima non compare finché non lo si cambia una volta: dell'originale
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


