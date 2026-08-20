# L'app si aggiorna da sola

**Data:** 2026-08-20
**Stato:** cadenza approvata dal proprietario (30 secondi, in silenzio)

---

## Il difetto

L'app legge il mondo quando parte e **non lo rilegge mai più da sola**. Nessun timer,
nessuna sottoscrizione, nessuna ricarica al ritorno da sfondo. Verificato nel codice, non
dedotto: `carica()` viene chiamata solo da `avvia()` e da una manciata di azioni
esplicite.

Ne segue una proprietà certa: **un club creato dopo che la tua app ha letto è invisibile
alla tua app**, e con lui le sue aste e le sue mosse, finché qualcosa non la costringe a
rileggere.

Peggiora per un motivo di piattaforma: su Android uscire col tasto home e rientrare dal
selettore **non fa ripartire niente**. L'app resta viva con la stessa fotografia. Solo una
chiusura vera rilegge. Per chi gioca, «ho chiuso e riaperto e non lo vedevo lo stesso» è
quindi perfettamente compatibile con la fotografia congelata — ed è la risposta che mi ha
fatto scartare la diagnosi giusta.

### Perché adesso è la spiegazione più probabile

Ricostruito con il proprietario, un pezzo per volta:

- **La lega era la stessa.** Cade la teoria delle due leghe diverse.
- **L'amico vedeva la sua squadra e le sue aste.** Quindi quel club esiste davvero nel
  database, in quella lega: il server pretende un club in lega per lasciare aprire
  un'asta, e lui ne apriva.
- **Sono entrambi membri.** Le Row Level Security mostrano ai membri tutti i club della
  loro lega: da non membro zero righe, da membro tutte.

Fra il proprietario e la squadra dell'amico non c'era né un permesso né una lega sbagliata.
Mancava la domanda.

Non è *provato* — servirebbe guardare quel database — ma non resta nessuna spiegazione
alternativa in piedi. E vale la pena costruirlo comunque: se la diagnosi è giusta risolve,
se è sbagliata toglie di mezzo una variabile.

---

## Il vincolo che decide la forma

La ricarica completa **sbianca lo schermo** (`AppState.Caricamento`, «Leggo la lega…» a
tutto schermo) e scarica milletrecento giocatori, circa quattrocento kilobyte. Farla ogni
trenta secondi renderebbe l'app inusabile.

Quindi l'aggiornamento automatico non può essere «rifai quello che fa l'avvio».

---

## Due giri

### Il giro leggero — ogni 30 secondi

Rilegge solo ciò che cambia mentre si gioca:

| Cosa | Perché | Costo |
|---|---|---|
| Riga della lega | stato e giornata di campionato | 1 riga |
| Club | crediti, crediti impegnati, **e i club nuovi** | ~20 righe |
| Contratti | chi possiede chi: è la riga che dice «ha comprato quel giocatore» | ~500 righe strette |
| Aste aperte | con le proprie offerte e quante squadre sono dentro | poche righe |

**Non** rilegge i giocatori: attributi e stamina cambiano quando il server gioca una
giornata, non perché qualcuno ha rilanciato.

I club si rileggono con la lettura **completa** — quella che porta anche divisione e club
padre — che costa due richieste minuscole in più. La versione ridotta è esattamente quella
che il 19 agosto faceva sparire la Primavera dopo ogni offerta. Due richieste da niente
ogni mezzo minuto sono il prezzo giusto per non rifare quel difetto.

### Il giro pieno — quando serve davvero

Tre casi:

1. **La giornata di campionato è cambiata.** Il server ha giocato: crescita, stamina,
   infortuni, presenze. Va riletto tutto.
2. **Si torna sull'app** dopo esserne usciti.
3. **Lo chiede il proprietario**, toccando la riga «aggiornato N fa».

Nei primi due casi il giro pieno è **silenzioso**: niente schermata di caricamento, i dati
si scambiano sotto a quello che si sta guardando.

---

## Le tre garanzie

### Non sbianca lo schermo

Un aggiornamento automatico non tocca la schermata, la posizione dello scorrimento, la
ricerca scritta, la scheda aperta, il filtro delle aste, l'interruttore fra prima squadra e
Primavera.

### Non butta via il lavoro in corso

È il rischio vero. Oggi una ricarica ricostruisce la formazione da capo: un aggiornamento
automatico cancellerebbe l'undici mentre lo si compone.

**Regola: un giro automatico non tocca mai niente che il proprietario stia modificando.**

| Cosa | Come si riconosce | Cosa fa il giro automatico |
|---|---|---|
| Formazione non salvata | `LineupEdit.dirty` | non la ricarica |
| Proposta di scambio in scrittura | `TradesState.bozza != null` | non la tocca |
| Regolamento in modifica | `SettingsEdit.bozza != null` | non lo tocca |
| Foglio dell'offerta aperto | `Dentro.bidding != null` | aggiorna i dati sotto, lascia il foglio |

Un aggiornamento **chiesto** aggiorna tutto, perché l'ha chiesto una persona.

### Non gira a vuoto

L'orologio si ferma quando l'app non è in primo piano: in sottofondo non serve e consuma
batteria. Un giro non parte se ce n'è già uno in corso.

---

## Le squadre nuove devono comparire davvero

Non basta che il club nuovo entri nell'elenco: vanno ricostruite anche le liste che
dipendono da lui — di chi è ogni giocatore, chi ha aperto quell'asta, chi è in testa.
Altrimenti il club arriva a metà: presente fra le squadre e sconosciuto ovunque altro.

È un conto in memoria, non una richiesta in più.

**E quando entra qualcuno, lo dice:** *«Il Bar di Marco è entrato nella lega»*. È
esattamente il momento che è mancato, e costa il confronto fra due elenchi di id.

---

## Come si vede che funziona

Una riga discreta in cima: *«aggiornato 12s fa»*, toccabile per forzare un giro pieno.

Senza, un aggiornamento silenzioso è indistinguibile da un aggiornamento rotto — e questo
progetto ha già pagato una volta il prezzo di un'informazione che il database aveva e
nessuna schermata scriveva.

---

## Piano di lavoro

| # | Cosa | Dove |
|---|---|---|
| 1 | Letture leggere: stato della lega, contratti | `data/LeagueRepository.kt` |
| 2 | `carica(silenzioso)`: ricarica che non sbianca e conserva il contesto | `app/AppViewModel.kt` |
| 3 | `aggiornaLeggero()`: il giro dei trenta secondi | `app/AppViewModel.kt` |
| 4 | L'orologio, legato al primo piano | `app/AppViewModel.kt`, `MainActivity.kt` |
| 5 | Le guardie sul lavoro in corso | `app/AppViewModel.kt` |
| 6 | «È entrato nella lega» e «aggiornato N fa» | `ui/shell/Shell.kt` |

### Come si verifica

`gradlew :core:test` verde (il giro non tocca `core`, ma resta la prova che niente si è
rotto), `gradlew :android:assembleDebug`, e la prova vera: due telefoni nella stessa lega,
uno fonda un club, l'altro **senza toccare niente** lo vede comparire entro mezzo minuto.
