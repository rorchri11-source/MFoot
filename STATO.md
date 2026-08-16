# MFoot — stato del progetto

**Aggiornato:** 2026-08-16
**Test:** 377 verdi, 0 falliti
**Righe:** 6.677 di sorgente, 4.170 di test

---

## Com'è fatto

```
mfoot/
├── core/          ✅ COMPLETO — Kotlin/JVM puro, zero dipendenze di piattaforma
├── server/        ❌ da fare — Ktor + PostgreSQL + World Tick
└── android/       ❌ da fare — Jetpack Compose + SQLDelight + FCM
```

**La regola d'oro:** `core` non importa niente di Android, niente di Ktor, niente di
database, e non fa I/O. Prende oggetti e restituisce oggetti. È il motivo per cui si
testa in venti secondi e per cui ci si può far girare diecimila stagioni di seguito
per bilanciare il gioco.

### Come si esegue

```bash
gradlew :core:test
```

Per vedere i numeri di bilanciamento del motore:

```bash
gradlew :core:test --tests "*BalanceReportTest*" -i
```

---

## Fatto

| # | Fase | Contenuto |
|---|---|---|
| 1 | **Fondamenta** | `DeterministicRandom` (xorshift64\*), `MathX` su `StrictMath`, modelli (Player, Club, Contract, Loan, Staff, Attributes, Position, Zone, Trait), `LeagueConfig` con ~90 parametri, `ConfigValidator`, tre preset |
| 2 | **Mondo procedurale** | `DevelopmentCurve`, `AttributeGenerator`, `NameBank` (10 nazionalità), `WorldGenerator`, `PotentialEstimator` |
| 3 | **Motore partita** | `Formation`, `Lineup`, `Tactics`, `ConditionalOrder`, `ZoneRatings`, `MatchEngine` con finestra di intervallo |
| 4 | **Bilanciamento** | `BalanceHarness` + tarature misurate su migliaia di partite |
| 5 | **Sistemi giocatore** | `GrowthEngine`, `StaminaEngine`, `MoraleEngine`, `ConversationEngine` con promesse |
| 6 | **Calendario** | `FixtureGenerator` (Berger, eliminazione, gironi), `CalendarSolver`, `Standings` |
| 7 | **Mercato** | `AuctionRules` (offerta massima, anti-snipe, blocco fondi), `NegotiationRules`, `ContractRules`, `Valuation` |
| 8 | **AI** | `AiPersonality`, `AiScheduler` (risvegli scaglionati), `AiManager` (anti-sciame) |

### Numeri di bilanciamento raggiunti

Misurati su migliaia di partite simulate, non stimati:

| Metrica | Valore | Riferimento |
|---|---|---|
| Squadre pari — casa / pari / trasferta | 45,1% / 28,0% / 27,0% | calcio vero: 45 / 27 / 28 |
| Gol a partita | 2,77 | 2,5-3,0 |
| Squadra con +10 di overall | vince il 62% | non deve essere una certezza |
| Catenaccio vs Arrembante | 50,5% vs 46,6% | nessun assetto domina |
| Allenatore 5⭐ vs 1⭐ | 55,5% | conta, ma meno della rosa |

---

## Da fare

### Fase 9 — Server (`server/`)

Il pezzo che rende il gioco giocabile. Tutto quello che serve esiste già in `core`:
qui si tratta di orchestrarlo e conservarlo.

- **World Tick** — coroutine che gira ogni minuto per sempre: simula le partite in
  orario, chiude le aste scadute, fa scadere contratti e prestiti, distribuisce i
  crediti, sveglia le AI dovute, aggiorna stamina/morale/crescita, verifica le promesse,
  manda il riepilogo giornaliero.
- **Recupero idempotente** — `ultimoTickElaborato` salvato, e al riavvio dopo
  un'interruzione recupera tutto quello che sarebbe dovuto succedere, **una volta sola**.
  Un'asta non deve poter essere assegnata due volte perché il server è ripartito.
- **PostgreSQL** — schema e persistenza. Le operazioni sui crediti devono girare in
  transazione con lock di riga.
- **REST API** — stato lega, rose, formazioni, mercato, storico.
- **WebSocket** — aste e partite in diretta.
- **Auth** — codice lega + nickname + password. Per venti amici basta questo.
- **Notifiche** — bot Discord/Telegram in fase 1 (venti righe, arriva dove già chattate),
  FCM in fase 2.

### Fase 10 — App Android (`android/`)

- Compose per tutte le schermate: creazione lega con preset, creazione club ed editor
  maglia, creazione player custom con budget abilità, rosa e Primavera, formazione con
  drag&drop, ordini condizionali, mercato e aste live, trattative, conversazioni,
  classifiche, replay partita con i due livelli (ambiente + highlight).
- SQLDelight per la cache locale: rosa e formazione consultabili anche senza linea.
- Ricezione FCM.

### Cose progettate ma non ancora scritte

Sono nella spec, non nel codice:

- **Asta iniziale in modalità "serata"** — le regole d'asta ci sono tutte, manca la
  sequenza a chiamata con i turni.
- **Scouting come spesa** — `PotentialEstimator` accetta già `scoutAccuracy`, manca il
  meccanismo che fa spendere crediti per alzarlo.
- **Avanzamento automatico dei tabelloni** — `nextKnockoutRound` esiste, va collegato
  ai risultati dal World Tick.
- **Deriva di forma dei giocatori liberi** — la versione economica del "mondo vivo".
- **Amichevoli fra club** — le regole di crescita le gestiscono già
  (`friendliesCountForGrowth`), manca il flusso di richiesta/accettazione.

---

## Decisioni prese, con il motivo

| Decisione | Perché |
|---|---|
| Mondo 100% procedurale | Zero licenze, zero manutenzione dati, bilanciamento totale, settore giovanile naturale |
| Kotlin nativo | Scelta dell'utente: migliore app Android, FCM nativo, funzionamento offline |
| `core` Kotlin/JVM, non KMP | Server e app sono entrambi JVM. Migrazione a KMP meccanica se servirà iOS |
| Server sempre acceso | Il mondo deve girare a telefoni spenti — requisito esplicito |
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
