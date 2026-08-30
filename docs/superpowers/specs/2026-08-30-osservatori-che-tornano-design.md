# Gli osservatori che tornano con qualcosa

*Progetto del 2026-08-30 — area B di cinque. Si appoggia sulle celle dell'area A.*

## I due problemi, misurati

### «Non ti porta niente» è strutturale, non sfortuna

Il mondo generato dal preset sprint ha **1128 giocatori e 98 under 20** — l'8%. Le
combinazioni nazione × ruolo sono 110, quindi la media è **0,89 giovani per combinazione**.

| | |
|---|---|
| Combinazioni **vuote al primo giorno** | **41 su 110** |
| Combinazioni con esattamente **uno** | 46 |
| Con due o più | 23 |

Trentasette ricerche su cento non possono riuscire mai, prima che qualcuno abbia firmato
qualcuno. Altre quarantadue muoiono appena quell'unico ragazzo viene preso. E la ricerca è
un `and` di tre filtri esatti:

```sql
where age < 20 and nationality = ? and primary_position = ? and non ha contratto
```

La causa a monte: le età nascono da una gaussiana con `mean = 25.4, stdDev = 4.6` scritta
dentro `WorldGenerator`. Un altro numero di gioco nel codice, contro il principio del
progetto.

### «Anche se è un 5 ti porta un 32» non è un difetto

L'osservatore ordina i candidati per `potentialMax`, e i potenziali più alti appartengono
ai sedicenni. Il miglior talento del mondo misurato è **M. Iglesias, 16 anni, overall 43,
potenziale 70-88**. L'under medio vale 43 di overall, il ventenne medio 63.

Il cinque stelle sta facendo esattamente il suo mestiere — trovare il talento più forte che
esiste — e chi guarda vede «43». **Il difetto è che nessuno lo dice.**

## Le decisioni del proprietario

Prese il 2026-08-30:

1. **Si genera su misura.** Quando quello che hai chiesto non esiste, il mondo crea un
   under 20 di quella nazione e di quel ruolo. Si ottiene sempre esattamente quello che si
   è chiesto. Scartato l'allargamento della ricerca a paesi o ruoli vicini.
2. **Il criterio di scelta resta com'è**: si pesca sul potenziale, e le stelle decidono
   quanto in alto.

Il pop-up mostrerà comunque la forbice di potenziale accanto all'overall. Non contraddice
la decisione 2 — non cambia *cosa* torna, cambia *cosa si vede* — ed è necessario: un
«accetta o rifiuta» senza sapere cosa si accetta è una scelta alla cieca.

## Cosa cambia

### Il ritorno non è più automatico

Oggi la missione scade, il tick assegna il giocatore alla Primavera e manda una notifica.
Adesso la missione torna in stato **`DA_VALUTARE`** con i giocatori trovati **non
assegnati**, e la cella dell'osservatore dice *tornato · guarda*.

Toccandola si apre il pop-up con, per ciascun trovato: nome, età, ruolo, nazione, **overall
di adesso e forbice di potenziale**. Tre risposte:

- **Accetta** — entra in Primavera. Se hai chiesto più ruoli, si accetta quello che si vuole.
- **Rifiuta** — resta libero per chiunque, la missione si chiude.
- **Ri-scouta** — rifiuta e riparte con lo stesso incarico, senza ricompilare il modulo.

Serve un valore nuovo nello stato, quindi il vincolo va riscritto:

```sql
alter table scouting_missions drop constraint if exists scouting_missions_status_check;
alter table scouting_missions add constraint scouting_missions_status_check
    check (status in ('IN_CORSO','DA_VALUTARE','CONCLUSA','A_VUOTO','RIFIUTATA'));
```

### Più ruoli, e più di un giocatore

Il modulo accetta più ruoli. **`scouting_missions.position` diventa una lista separata da
virgole** — `"TS,TD,DC"` — invece di una colonna nuova: l'app la legge già in una `select`
a lista esplicita, e aggiungere `positions text[]` lì dentro spegnerebbe la lettura delle
missioni su ogni database indietro. Una colonna che cambia significato senza cambiare tipo
non rompe niente.

I giocatori trovati diventano più di uno — **uno per ruolo chiesto, e almeno uno sempre** —
e per quelli serve davvero una colonna:

```sql
alter table scouting_missions add column if not exists found_player_ids bigint[]
    not null default '{}';
```

`found_player_id` resta e continua a contenere il primo: le missioni vecchie non cambiano
significato. La colonna nuova si legge **a parte**, come la proprietà dello staff.

### Il tempo

`scoutMinutesWorst` passa da 120 a **15** e `scoutMinutesBest` da 30 a **5**. Richiesto:
*«diminuisci tempo massimo da 40 a 15 minuti»*.

**Attenzione, e va scritto:** questi due numeri sono serializzati in `ConfigJson`, quindi
una lega già creata **conserva i suoi**. Cambiare il valore di serie vale per le leghe
nuove; per quella in corso va cambiato dal regolamento della lega. È il contrario della
trappola solita — qui il dato salvato vince sul codice.

### Più giovani nel mondo

`ageMean` e `ageStdDev` escono da `WorldGenerator` ed entrano in `WorldConfig`, e la media
scende. L'obiettivo è misurabile: **nessuna combinazione nazione × ruolo vuota al primo
giorno**, o quasi.

Vale solo per i mondi nuovi — quelli esistenti stanno già nel database — quindi non
sistema la lega in corso. Quella la sistema la generazione su misura, che è la ragione per
cui le due cose stanno insieme in questo progetto.

## Il modello della generazione su misura

Una funzione in `core`, perché è una regola di gioco:

```kotlin
object Talenti {
    /** Un under 20 di quella nazione e di quel ruolo, creato adesso. */
    fun giovane(
        nationality: String,
        position: Position,
        config: LeagueConfig,
        rng: DeterministicRandom,
    ): Player
}
```

Nasce con la stessa curva di sviluppo di tutti gli altri: **non è un giocatore
privilegiato**, è un giocatore che sarebbe potuto esistere. L'età viene dalla fascia under,
il potenziale dalla distribuzione del mondo, e le stelle dell'osservatore continuano a
decidere quanto in alto si pesca — su un candidato solo, la finestra è sempre quello.

Il seme viene dalla missione, non dal flusso: due tick sulla stessa missione devono
produrre lo stesso ragazzo.

## Cosa non si fa

- **Non si allarga la ricerca** a paesi o ruoli vicini: scartato esplicitamente.
- **Non cambia chi pesca l'osservatore.** Il criterio resta il potenziale.
- **Non si toccano le celle** dell'area A: la cella dell'osservatore mostra lo stato, e
  questo progetto riempie quello stato.
- **Niente scadenza sul pop-up.** Un ragazzo trovato aspetta finché non si decide: una
  missione che scade da sola sarebbe una punizione per chi apre l'app la sera.

## Come si verifica

`Talenti.giovane` è una funzione pura: età nella fascia, ruolo e nazione richiesti, curva
di sviluppo coerente con gli altri, e determinismo a parità di seme.

La copertura del mondo si misura, non si stima: quante combinazioni nazione × ruolo restano
vuote alla generazione. Oggi 41 su 110; il test fissa una soglia.

`core:test` verde prima e dopo, e lo schema eseguito prima dell'APK.

## Ordine dei lavori

1. `Talenti` in `core`, con i suoi test.
2. `ageMean` e `ageStdDev` in `WorldConfig`, e la misura della copertura.
3. Lo schema: lo stato nuovo, `found_player_ids`, `send_scout` con più ruoli.
4. Il tick: niente assegnazione automatica, generazione su misura, uno per ruolo.
5. Le RPC di accetta, rifiuta, ri-scouta.
6. Il pop-up nella cella dell'osservatore, e il modulo con più ruoli.
