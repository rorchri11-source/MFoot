# Un mercato che non si ferma

Data: 18 agosto 2026
Stato: approvata

## Il difetto, e perche' nessun test lo ha preso

Il mercato faceva poche aste durante l'allestimento e nessuna dopo. Due cause
indipendenti, e una terza che spiega perche' erano rimaste nascoste.

### Il corto circuito

In `TickRunner.wakeAi`:

```kotlin
val acted = tryBid(...) || tryOpenAuction(...)
```

Un `||` in corto circuito. Se esiste **anche una sola** asta su cui valga la pena offrire,
l'AI offre e non apre niente. Ha sei slot d'asta liberi e nove caselle vuote in rosa, ma
quel risveglio e' finito.

Il commento nel codice conta «10 club per 6 aste da 15 minuti fanno 60 aggiudicazioni ogni
quarto d'ora». Il conto presuppone che le sei aste si aprano. Non si aprivano: appena ne
esisteva una, tutti offrivano su quella. Da qui **poche aste con molti rilanci** invece di
molte aste parallele.

### Il mercato che muore

`tryOpenAuction` comincia con `if (squad.size >= minSquadSize) return false`: dal momento in
cui un club AI arriva alla rosa minima non apre piu' un'asta per il resto della stagione.

E `start_auction` rifiuta chi ha gia' un contratto — *"un giocatore sotto contratto non si
mette all'asta"*. Le aste esistono solo per gli svincolati.

Messe insieme: finito l'allestimento, l'unico mercato possibile e' un umano che apre un'asta
sugli scarti rimasti.

### Perche' i test erano verdi

`MarketSimulationTest` simula le **decisioni** di `AiManager`: dato un club e un giocatore,
quanto lo vuole e fino a quanto si spinge. Il corto circuito non vive li': vive nel **ciclo
del tick**, che non e' simulato da niente perche' e' scritto dentro una funzione che ha
bisogno di una connessione al database.

E' lo stesso genere di buco di prima: ogni decisione era giusta e l'insieme non funzionava.

---

## 1. L'ordine delle mosse esce dal tick

`TickRunner.wakeAi` decide cosa fare con una catena di `||` scritta a mano. Finche' resta li'
non si puo' provare, e infatti non era provata.

Nasce `AiTurn` in `core/ai/`: funzioni pure che rispondono a due domande.

```kotlin
enum class AiMove { APRI_ASTA, OFFRI, GESTISCI_ROSA, PROPONI_SCAMBIO, CHIEDI_AMICHEVOLE }

object AiTurn {
    fun order(squadSize: Int, openAuctionsByMe: Int, config: LeagueConfig): List<AiMove>
    fun auctionsToOpen(squadSize: Int, openAuctionsByMe: Int, config: LeagueConfig): Int
}
```

### La regola

- **Rosa sotto il minimo**: `APRI_ASTA` prima di `OFFRI`. Aprire crea offerta, rilanciare si
  limita a contendersi quella che c'e', e quando tutti sono corti quello che manca e'
  l'offerta.
- **Rosa a posto**: `OFFRI` prima di `APRI_ASTA`. Rilanciare su un'asta che esiste costa meno
  che crearne una, e a stagione in corso il mercato deve essere piu' lento.
- `auctionsToOpen` restituisce **tutti** gli slot liberi durante l'allestimento e **uno** a
  regime.

### Perche' aprire molte aste insieme non e' uno sciame

Lo sciame che il progetto teme e' venti club che si buttano sullo stesso giocatore, e la
difesa e' `crowdingFactor` sui rilanci: resta intera e non viene toccata. Sei aste aperte da
un club sono sei ruoli scoperti, non sei offerte sullo stesso obiettivo.

---

## 2. Vendere all'asta

### Cosa

`start_auction` smette di rifiutare un giocatore sotto contratto **se il contratto e' di chi
chiama**. Alla chiusura il prezzo va al venditore e il contratto si sposta.

### Perche' nessuna colonna nuova

Il venditore e' gia' scritto: e' `started_by`. Alla chiusura si guarda se il giocatore ha un
contratto:

| Situazione | Cosa succede |
|---|---|
| Nessun contratto | Svincolato: come oggi, il prezzo va perso nel nulla |
| Contratto di `started_by` | Vendita: `started_by` incassa, il contratto passa al vincitore |
| Contratto di un altro club | **Annullata**: nel frattempo e' stato scambiato |

Il terzo caso non e' pignoleria. Fra l'apertura e la chiusura passa un'ora, e in mezzo lo
stesso giocatore puo' essere finito in uno scambio: completare la vendita lo farebbe
esistere in due rose, che e' lo stato assurdo da cui bisogna stare lontani.

### Le due protezioni

**Non si vende sotto il minimo di rosa.** Controllato all'apertura *e* alla chiusura, perche'
fra le due passa un'ora e in mezzo si puo' aver ceduto altro. Alla chiusura, se il venditore
scenderebbe sotto il minimo, l'asta si annulla.

**Il prezzo di partenza lo sceglie il venditore.** Nessun minimo imposto: se lo mette basso
e' un suo problema, l'asta dura il suo tempo e chiunque puo' rilanciare. Un mercato in cui si
puo' fare un affare a spese di un distratto e' un mercato, non un difetto.

### Le AI vendono

`AiInitiative.playerToSell`: chi sta sotto la mediana in un ruolo dove abbondano, oppure chi
ha il contratto in scadenza e non vale il rinnovo. Prezzo di partenza a una frazione del
valore, perche' e' l'asta a fare il prezzo.

---

## 3. Le AI comprano anche a rosa piena

Sparisce il `return false` a rosa completa. Sopra il minimo l'AI apre un'asta solo se il
candidato **migliora davvero** un ruolo — deve essere piu' forte del migliore che ha li' —
sotto `maxSquadSize` e con la disciplina di spesa che vale gia' oggi.

Sotto il minimo resta l'obbligo di rosa, con i numeri dell'allestimento.

---

## 4. La prova che avrebbe fallito

`MarketRhythmTest` in `core`: un mercato finto con N club, M svincolati e un ciclo che a ogni
giro chiede a `AiTurn` cosa fare, apre le aste che dice di aprire, le chiude quando scadono, e
**conta le aste aperte a ogni giro**.

Cosa verifica:

1. Al terzo giro ci sono almeno venti aste aperte in tutta la lega. Con il corto circuito ce
   n'era una.
2. Le rose arrivano al minimo entro il numero di giri che corrisponde a tre ondate.
3. A rose piene le aste continuano a esistere, ma poche: il mercato rallenta senza spegnersi.

E' una simulazione e non un test di `TickRunner`, perche' `TickRunner` ha bisogno di un
database. Ma il pezzo che sbagliava — **l'ordine delle mosse** — adesso vive in `core` ed e'
lo stesso identico codice che il tick esegue.

---

## Migrazione

`0015_vendite.sql`, un file solo: `start_auction` accetta un giocatore sotto contratto se il
contratto e' di chi chiama e se la cessione non porta il club sotto il minimo di rosa.

Nessuna colonna nuova, quindi **nessun rischio di rompere una SELECT condivisa**: si puo'
applicare prima o dopo l'APK indifferentemente.

## Rischi

**Quarantotto aste aperte insieme sono tante da guardare.** Lo sono, e succede solo durante
l'allestimento — che dura tre quarti d'ora e in cui e' esattamente quello che si vuole
vedere. A regime il tetto resta quello di prima.

**Un'AI potrebbe vendere e ricomprare lo stesso giocatore.** Il tetto giornaliero di azioni e
il fatto che vende solo chi sta sotto la mediana lo rendono improbabile, ma va guardato nella
lega vera: se succede, la difesa e' non rimettere all'asta chi si e' venduto da poco.
