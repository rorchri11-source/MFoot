# MFoot — stato del progetto

**Aggiornato:** 2026-08-16
**Test:** 446 verdi, 0 falliti
**Verificato:** su emulatore Android e su Supabase, non solo nei test

---

## Com'è fatto

```
mfoot/
├── core/          ✅ il gioco: motore, mondo, mercato, AI. Zero dipendenze di piattaforma
├── tick/          🟡 il battito: gira su GitHub Actions ogni 5 minuti
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
| 15 | **Le AI si muovono** | Si svegliano a turno, valutano sulla stima e offrono passando per `place_bid` come tutti |

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
| `players_public` | Non contiene i potenziali veri |
| Sessione persistente | Chiusa e riaperta l'app: si rientra senza reinserire il codice |
| Conteggio giocatori | 1128 dopo la paginazione (prima ne arrivavano 1000) |
| Budget del custom | 15 punti su Passaggio → 65→80, costo 4×1 + 8×3 + 3×5 = 43, overall 65→69 |

---

## Da fare

### Il prossimo blocco, in ordine di valore

1. **Il replay della partita.** La timeline viene salvata intera a database; manca la
   schermata che la riproduce con l'orologio del telefono.
2. **Formazione e ordini condizionali.** La tabella `lineups` esiste e si scrive già alla
   fondazione del club, ma il tick usa sempre la formazione automatica: quella scelta a
   mano non viene ancora letta.
3. **Notifiche Telegram.** Il tick le accumula in `notifications`, nessuno le consegna.

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
| Avvio della stagione e calendario | ✅ alla data scelta dall'admin |
| Risveglio AI, con offerte vere sulle aste | ✅ |
| Verifica promesse | ❌ pianificata, non applicata |
| Riepilogo giornaliero | ❌ pianificato, non applicato |

### Migrazioni SQL da eseguire

Nell'SQL Editor di Supabase, in ordine. Sono tutte rieseguibili.

| File | Contenuto |
|---|---|
| `supabase/migrations/0001_schema.sql` | Tabelle, vista pubblica, `place_bid`, RLS |
| `supabase/migrations/0002_create_league.sql` | `create_league`, `join_league` |
| `supabase/migrations/0003_club.sql` | `create_club` e il conto del budget lato server |
| `supabase/migrations/0004_auctions.sql` | `start_auction`, prezzo pubblico sulle aste |

Le leghe create prima della migrazione `0003` non hanno i pesi dei ruoli in
configurazione e **non possono accettare nuovi club**: per provare la fondazione va
creata una lega nuova.

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
