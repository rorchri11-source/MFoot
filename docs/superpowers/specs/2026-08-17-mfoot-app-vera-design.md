# MFoot — da motore ad applicazione

**Data:** 2026-08-17
**Stato:** approvato

Questa spec è il contratto fra sette blocchi di lavoro che procedono in parallelo. Chi
implementa un blocco non deve indovinare le scelte di un altro: nomi, unità e firme dei
componenti sono fissati qui.

---

## Il problema

Esiste un motore di gioco tarato e testato, e una schermata. Non esiste un'applicazione.
Mancano: la struttura di navigazione, le impostazioni della lega, l'elenco delle squadre,
la formazione, un campo visivo, un editor maglia, le divisioni. E due difetti rendono la
lega ingiocabile: i soldi sono crediti astratti, e le AI non aprono abbastanza aste per
completare le rose, quindi nessuno raggiunge il minimo e non si gioca mai.

---

## A — I soldi

### L'unità è il migliaio

Ogni cifra di denaro nel sistema è un `Int` che vale **migliaia**. `700` = 700K,
`1500` = 1,5M, `100000` = 100M.

Perché non i decimali: un budget in `Double` porta arrotondamenti che non tornano fra
client e server, e un'asta in cui il prezzo mostrato non coincide con quello addebitato è
il difetto peggiore possibile in un gioco di soldi. Perché non gli euro interi: `Int`
basterebbe comunque, ma nessuna cifra del gioco ha senso sotto il migliaio, quindi
tenere tre zeri in più significa solo occasioni di sbagliare.

### `core/model/Money.kt`

```kotlin
@JvmInline
value class Money(val thousands: Int) : Comparable<Money> {
    operator fun plus(other: Money): Money
    operator fun minus(other: Money): Money
    operator fun times(factor: Double): Money
    fun coerceAtLeast(other: Money): Money

    /** "700K", "1,5M", "18,5M", "120M". Mai decimali oltre il primo. */
    fun format(): String

    /** Forma compatta per le liste strette: "1,5M" resta, "700K" diventa "700K". */
    fun formatShort(): String

    companion object {
        val ZERO: Money
        fun thousands(value: Int): Money
        fun millions(value: Double): Money

        /** Legge "1,5M", "1.5M", "1500", "700K", "700k". Null se non è un numero. */
        fun parse(text: String): Money?
    }
}
```

Regole di formattazione, da fissare con test:

| Valore interno | `format()` |
|---|---|
| 0 | `0` |
| 450 | `450K` |
| 1000 | `1M` |
| 1500 | `1,5M` |
| 18500 | `18,5M` |
| 120000 | `120M` |
| 1250000 | `1,25Mrd` |

Il separatore decimale è la virgola: il gioco è in italiano.

### Cosa NON cambia

I nomi delle colonne sul database restano `credits`, `committed_credits`, `price_paid`,
`max_amount`, `final_price`, `starting_price`. Rinominarli costerebbe una migrazione su
sei tabelle per cambiare una parola, e il tipo è già l'intero giusto. Cambia solo cosa
quell'intero significa, e la documentazione dello schema lo dice.

Le leghe create prima di questo cambio continuano a funzionare: i loro numeri sono
semplicemente piccoli (300 = 300K invece di 300 crediti). Nessuna migrazione di dati.

### Le valutazioni derivano dal budget

`Valuation` continua a calcolare il valore come frazione del budget iniziale. Diventa
configurabile la frazione che costa il migliore del mondo, oggi costante interna:

```kotlin
// EconomyConfig
val topPlayerBudgetShare: Double = 0.65
```

**Taratura richiesta, misurata e non scelta a intuito.** Con budget 100M i bersagli sono:

| Overall | Costo atteso | Frazione del budget |
|---|---|---|
| 90+ (fuoriclasse) | 55-70M | 55-70% |
| 80 (buon titolare) | 12-18M | 12-18% |
| 71 (gregario) | 1-2M | 1-2% |
| 60 (riserva) | 200-500K | 0,2-0,5% |
| 65 custom appena creato | ~400K | 0,4% |

La curva attuale è cubica su `(overall-40)/53`. Con questi bersagli l'esponente va
alzato: un test deve stampare la tabella sopra e fallire se un valore esce dalla fascia.

### Il difetto del 71 pagato 50

Separato dalla scala. Il tetto che `AiManager.ceilingFor` produce è troppo generoso sui
giocatori mediocri: paga il 15% del budget per un giocatore che ne vale l'1%. Va tarato
sullo stesso test della tabella: **nessuna AI deve offrire più del doppio del valore di
mercato stimato**, e per i giocatori sotto 75 non più di 1,3 volte.

---

## B — Il mercato che riempie le rose

### Due mercati, non uno

Il tetto di tre aste per club esiste per una ragione buona: a stagione in corso protegge
l'umano dalle notifiche e dai duelli a raffica. Durante l'allestimento serve l'opposto,
perché dieci club AI con tre aste a testa non riempiranno mai centottanta caselle.

La distinzione è lo stato della lega, che già esiste: `mercato` contro `in_corso`.

```kotlin
// MarketConfig
val initialParallelAuctionsPerClub: Int = 6
val initialAuctionDurationMinutes: Int = 15
```

| | Asta iniziale (`mercato`) | Regime (`in_corso`) |
|---|---|---|
| Aste parallele per club | `initialParallelAuctionsPerClub` | `maxParallelAuctionsPerClub` |
| Durata | `initialAuctionDurationMinutes` | `auctionDurationMinutes` |
| Risvegli AI | ogni 1-4 minuti, scaglionati | `checksPerDay` |
| Tetto azioni giornaliere AI | non si applica | si applica |
| Obiettivo AI | raggiungere `minSquadSize` | migliorare la rosa |

Il tetto di azioni giornaliere già oggi non si applica durante l'allestimento: quella
parte è fatta. Manca alzare il limite di aste parallele e accorciarne la durata.

Conto: 10 AI × 6 aste da 15 minuti = 60 aggiudicazioni ogni quarto d'ora, 180 posti in
tre quarti d'ora invece di nove giorni.

### L'anti-sciame resta intero

Non si tocca la penalità di affollamento: è quella a impedire che venti AI si buttino
sullo stesso giocatore, ed è indipendente dal numero di aste aperte. Un'AI apre sei aste
su sei ruoli diversi, non sei offerte sullo stesso obiettivo.

---

## C — Struttura dell'applicazione

### Navigazione

Un drawer laterale per le sezioni, una tab bar in basso per i quattro-cinque posti dove
si torna sempre.

```
Tab bar        Dashboard · Squadre · Calendario · Classifica · Campo

Drawer
  intestazione    nickname, club, ruolo (amministratore o no)
  lega            nome, e il "+" per crearne o entrarne in un'altra

  SETUP           Profilo lega
                  Partecipanti
                  Regolamento e opzioni
                  Competizioni
                  Divisioni
                  Mercati

  GIOCA           Aste
                  Svincolati
                  Listone
                  Infermeria
                  Registro admin
```

Le voci di SETUP sono visibili solo all'amministratore. La difesa resta lato database:
nascondere una voce è cortesia verso chi non è admin, non sicurezza.

### `AppState` diventa una destinazione, non uno stato monolitico

Oggi `AppState.Dentro` contiene lista, aste, offerta e selezione tutti insieme. Con
quindici schermate quel tipo diventa illeggibile. Nuova forma:

```kotlin
sealed interface AppState {
    data object Avvio : AppState
    data class Porta(...) : AppState
    data class Caricamento(val fase: String) : AppState
    data class Guasto(val motivo: String) : AppState

    /** Dentro la lega. La destinazione dice quale schermata. */
    data class Dentro(
        val lega: LeagueSnapshot,
        val rows: List<PlayerRow>,
        val route: Route,
        val drawerOpen: Boolean = false,
        val avviso: String? = null,
        val errore: String? = null,
    ) : AppState
}

sealed interface Route {
    data object Dashboard : Route
    data object Squadre : Route
    data object Calendario : Route
    data object Classifica : Route
    data object Campo : Route
    data object ProfiloLega : Route
    data object Partecipanti : Route
    data class Regolamento(val sezione: SettingsSection) : Route
    data object Competizioni : Route
    data object Divisioni : Route
    data object Mercati : Route
    data object Aste : Route
    data object Svincolati : Route
    data object Listone : Route
    data object Infermeria : Route
    data object RegistroAdmin : Route
    data class Giocatore(val row: PlayerRow) : Route
    data class Offerta(val auction: AuctionRow) : Route
    data object Fondazione : Route
}
```

Il tasto indietro percorre una pila di `Route` tenuta nel ViewModel.

### Le schermate nuove

**Dashboard** — il club: maglia grande, nome, disponibili, giocatori in rosa, aste aperte,
prossima partita, e le due o tre cose che richiedono una decisione adesso.

**Squadre** — tutte le squadre della lega: stemma-colore, nome, proprietario o AI,
giocatori in rosa, disponibili. Il proprio club evidenziato in cima. Toccandone una si
apre la sua rosa.

**Partecipanti** — le persone: nickname, club, se è admin. L'admin può promuovere.

**Profilo lega** — nome, codice d'accesso (rigenerabile), stato, giornata corrente,
divisioni, numero di club.

**Infermeria** — gli infortunati della propria rosa con le giornate di rientro.

**Listone** — tutti i giocatori del mondo, con ricerca e filtri. È l'attuale lista.

**Registro admin** — cosa ha fatto il tick e cosa ha fatto l'admin, in ordine di tempo.
Legge `notifications` e `tick_state.last_run_notes`.

---

## D — Il campo

### Un componente, due usi

```kotlin
/** Una casella sul campo: dove sta e chi ci gioca. */
data class PitchSlot(
    val index: Int,
    val position: Position,
    val player: Player?,
    /** 0..1 da sinistra a destra, 0..1 dalla propria porta all'area avversaria. */
    val x: Float,
    val y: Float,
)

@Composable
fun Pitch(
    slots: List<PitchSlot>,
    modifier: Modifier = Modifier,
    highlight: Set<Int> = emptySet(),
    onSlotClick: (Int) -> Unit = {},
)
```

Il campo si disegna: erba scura, righe, area di rigore, cerchio di centrocampo, porta.
Nessuna immagine: tutto `Canvas`, così scala su qualsiasi schermo senza asset.

Le coordinate dei ruoli in un modulo derivano dal modulo stesso: una tabella
`Formation → List<Pair<Float, Float>>` in `core/match/PitchLayout.kt`, così client e
server concordano su dove sta un terzino sinistro.

**Formazione** — `Route.Campo`. Slot vuoto toccato → foglio con i giocatori della rosa
che sanno fare quel ruolo, ordinati per overall in quel ruolo, con la stamina visibile.
Slot pieno toccato → sostituisci o togli. Sotto il campo: panchina, capitano,
rigorista, e gli ordini condizionali.

Si scrive su `lineups`, tabella che esiste già e che il tick deve iniziare a leggere
invece di usare sempre `AutoLineup`. Se la formazione salvata è incompleta o schiera
giocatori non più in rosa, il tick completa i buchi con `AutoLineup` e lo dice nel
registro: mai rifiutare di giocare per una formazione vecchia.

**Giocatore custom** — nella fondazione, il campo mostra gli undici ruoli del 4-3-3 come
caselle toccabili. Tocchi dove vuoi giocare. Sostituisce la fila di sigle.

---

## E — La maglia

```kotlin
data class Kit(
    val pattern: KitPattern = KitPattern.TINTA_UNITA,
    val primary: Long,
    val secondary: Long,
    val detail: Long,
    val number: Int? = null,
)

enum class KitPattern {
    TINTA_UNITA, STRISCE_VERTICALI, STRISCE_ORIZZONTALI,
    BANDA_VERTICALE, BANDA_DIAGONALE, SPALLE, SCUDO, META_E_META,
}

@Composable
fun Shirt(kit: Kit, modifier: Modifier = Modifier, showNumber: Boolean = false)
```

Sagoma di maglia disegnata a `Path`: colletto, maniche, spalle. Il motivo si dipinge
dentro il ritaglio della sagoma, così le strisce seguono la forma invece di uscire dai
bordi.

L'editor: motivo fra otto, tre colori da una tavolozza di dodici più un selettore libero,
anteprima grande che si aggiorna a ogni tocco. Sul database `clubs.kit` è già `jsonb`.

---

## F — Regolamento e opzioni

Sei sezioni, una schermata per sezione, raggiungibili da un elenco.

```kotlin
enum class SettingsSection(val label: String) {
    SQUADRE("Squadre e rose"),
    ECONOMIA("Economia"),
    MERCATO("Mercato"),
    PARTITA("Partita"),
    CRESCITA("Crescita e giocatori"),
    CUSTOM("Il tuo giocatore"),
}
```

| Sezione | Campi | Dove sta oggi |
|---|---|---|
| Squadre | numero club · quanti AI · minimo in rosa · massimo in rosa | `SetupConfig` |
| Economia | budget · entrata ricorrente · cadenza (giornata/settimana/mese/fine/mai) · premio vittoria · premio pareggio · premi finali 1°,2°,3°… · stipendi sì/no · peso stipendi · costo rinnovo · saldo negativo | `EconomyConfig` |
| Mercato | durata asta · rilancio minimo · anti-snipe · aste parallele · aste iniziali parallele · durata asta iniziale · durata contratti iniziali · prestiti · clausole · scambi | `MarketConfig` |
| Partita | tasso infortuni · gravità · cartellini gialli sì/no · probabilità giallo · probabilità rosso · gialli per squalifica · velocità partita · finestra intervallo | `RulesConfig` + `EngineConfig` |
| Crescita | velocità · età di picco · età di declino · Primavera sì/no · età massima Primavera · morale · conversazioni · soglia morale basso | `RulesConfig` |
| Il tuo giocatore | budget punti · costo stella · overall di partenza · età minima e massima · bonus potenziale | `CustomPlayerConfig` |

Aggiunte a `EconomyConfig`:

```kotlin
val topPlayerBudgetShare: Double = 0.65
enum class IncomeCadence { PER_GIORNATA, PER_SETTIMANA, PER_MESE, FINE_COMPETIZIONE, MAI }
```

`PER_SETTIMANA` e `PER_MESE` sono settimane e mesi **di calendario reale**, non giornate:
è quello che l'utente ha chiesto, e il resto del sistema continua a contare in giornate.

Aggiunte a `RulesConfig`:

```kotlin
/** Moltiplicatore sul tasso base di infortunio del motore. 0 = mai. */
val injuryRateMultiplier: Double = 1.0
val yellowCardsEnabled: Boolean = true
```

Ogni schermata scrive su `leagues.config`. Serve una funzione SQL
`update_league_config(p_league_id, p_config)` riservata all'admin: la scrittura diretta
resta chiusa dalle Row Level Security.

**Vincolo:** il campo del denaro accetta `1,5M`, `1500`, `700K`. Mostra sempre la forma
normalizzata quando perde il fuoco.

---

## G — Divisioni con spareggi

### Il modello

```kotlin
data class Division(
    val id: Long,
    val name: String,
    /** 1 = la più alta. Determina chi sale verso chi. */
    val level: Int,
    val clubs: List<ClubId>,
    val promotedDirectly: Int = 0,
    val promotionPlayoffSlots: Int = 0,
    val relegatedDirectly: Int = 0,
    val relegationPlayoutSlots: Int = 0,
)
```

L'admin imposta **quante** squadre, non quali posizioni. Le posizioni si ricavano dalla
dimensione della divisione: `relegatedDirectly = 3` in una divisione da 20 significa
18ª, 19ª, 20ª; in una da 6 significa 4ª, 5ª, 6ª.

### `core/calendar/SeasonEnd.kt`

```kotlin
data class SeasonOutcome(
    val promoted: List<ClubId>,
    val relegated: List<ClubId>,
    val promotionPlayoff: List<ClubId>,
    val relegationPlayout: List<ClubId>,
    val stayed: List<ClubId>,
)

object SeasonEnd {
    /** Chi sale, chi scende, chi va agli spareggi. Dalla classifica finale. */
    fun resolve(division: Division, table: List<StandingRow>): SeasonOutcome

    /** Errori da mostrare all'admin mentre imposta, non a fine stagione. */
    fun problems(divisions: List<Division>): List<String>

    /** Il tabellone degli spareggi fra i qualificati. */
    fun playoffBracket(clubs: List<ClubId>, seed: Long): Competition
}
```

`problems` deve segnalare:

- posti totali (promosse + playoff + retrocesse + playout) maggiori dei club nella divisione
- retrocesse dalla divisione `n` diverse dalle promosse dalla divisione `n+1`, che
  produrrebbe un campionato con 21 squadre in A e 15 in B l'anno dopo
- promozioni nella divisione di livello 1 o retrocessioni nell'ultima
- una divisione con meno di due club

### Sul database

```sql
create table divisions (
    id bigserial primary key,
    league_id bigint not null references leagues(id) on delete cascade,
    name text not null,
    level integer not null,
    promoted_directly integer not null default 0,
    promotion_playoff_slots integer not null default 0,
    relegated_directly integer not null default 0,
    relegation_playout_slots integer not null default 0,
    unique (league_id, level)
);

alter table clubs add column division_id bigint references divisions(id) on delete set null;
alter table competitions add column division_id bigint references divisions(id) on delete set null;
```

Funzioni riservate all'admin: `create_division`, `update_division`, `delete_division`,
`assign_club_to_division`.

### La fine di stagione nel tick

Non esiste oggi. Quando tutte le partite di una competizione di tipo campionato di una
divisione sono giocate:

1. calcola la classifica finale con `Standings`
2. `SeasonEnd.resolve` per sapere chi sale, chi scende, chi spareggia
3. paga i premi finali secondo `placementPrizes`
4. se ci sono spareggi, genera i tabelloni e **si ferma**: le squadre si muovono solo a
   spareggi conclusi
5. a spareggi conclusi, sposta `clubs.division_id` e segna la lega `conclusa`

Il punto 4 è la parte delicata: muovere le squadre prima degli spareggi le manderebbe
nella divisione sbagliata, e tornare indietro sarebbe impossibile.

---

## Come si divide il lavoro

Sette blocchi, quattro proprietari di file che non si sovrappongono.

| Chi | Possiede | Fa |
|---|---|---|
| **core** | `core/**` | Money, valutazioni tarate, campi di configurazione nuovi, `ConfigJson`, `Division`, `SeasonEnd`, `PitchLayout` |
| **server** | `tick/**`, `supabase/**` | asta iniziale, tetto aste, fine stagione, migrazione 0006, `update_league_config`, funzioni divisioni |
| **guscio** | `android/.../app/**`, `MainActivity`, `ui/shell/**`, `ui/screens/**` | drawer, tab bar, `Route`, Dashboard, Squadre, Partecipanti, Profilo lega, Infermeria, Listone, Registro |
| **pezzi** | `android/.../ui/pitch/**`, `ui/kit/**`, `ui/settings/**` | `Pitch`, `Shirt`, editor maglia, le sei schermate di Regolamento |

Il guscio chiama i pezzi tramite le firme fissate in questa spec. `core` va prima perché
gli altri tre compilano contro i suoi nomi.

---

## Cosa resta fuori, e volutamente

- **Notifiche Telegram.** Il tick le accumula in `notifications`, la consegna è un blocco
  a sé.
- **Replay della partita.** La timeline si salva già intera; la schermata che la riproduce
  è un blocco a sé, e ha senso farla dopo che le partite si giocano su formazioni scelte
  a mano.
- **Gestione divisioni multi-stagione.** Una stagione finisce, le squadre si spostano. Il
  ricominciare — azzerare le classifiche, generare i nuovi calendari — arriva dopo.
