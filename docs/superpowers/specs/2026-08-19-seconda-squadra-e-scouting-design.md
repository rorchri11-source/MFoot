# La seconda squadra, lo scouting, e un'interfaccia che si capisce

Data: 19 agosto 2026
Stato: approvata

## Il punto di partenza

La Primavera costruita il giorno prima e' un **magazzino**: un posto dove parcheggiare un
giovane perche' non occupi una casella della prima squadra. Non gioca, non ha uno staff,
non ha un campionato. Era la versione minima, ed era troppo minima.

Qui diventa una **seconda squadra vera**, che gioca un campionato suo, ha il suo allenatore
e i suoi giocatori — e che si popola scoutando, non comprando.

E siccome tutto questo aggiunge roba a un'interfaccia che ha gia' sedici voci piatte, il
riordino viene **prima**, non dopo.

---

## 1. L'interfaccia, prima di tutto il resto

### Il problema misurato

Cinque schede in basso piu' undici voci nel menu. Quattro di quelle undici — `Aste`,
`Svincolati`, `Listone`, `Scambi` — portano allo stesso composable con un `ListScope`
diverso. Aggiungere due squadre, lo staff e le missioni senza toccare niente porterebbe a
venti destinazioni piatte.

Il menu e' un elenco di **schermate**. Deve diventare un elenco corto di **posti**, dove
ogni posto risponde a una domanda sola.

### La struttura

| Posto | Schede dentro |
|---|---|
| **Casa** | — |
| **Squadra** | rosa · campo · staff · spogliatoio · infermeria |
| **Mercato** | aste · svincolati · listone · trattative · osservatori |
| **Calendario** | — |
| **Lega** | classifica · squadre · competizioni |

Nel menu restano solo le cose rare: profilo lega, partecipanti, regolamento, registro.

### L'interruttore

`Squadra` porta in cima un interruttore **prima squadra | Primavera**, e tutto quello che
sta sotto lo segue.

E' l'idea che rende gestibili due squadre senza raddoppiare la navigazione. Con un menu
doppio — rosa prima, rosa Primavera, campo prima, campo Primavera — le voci passerebbero da
sedici a ventiquattro, e ogni schermata nuova ne aggiungerebbe due invece di una.

`Campo` esce dalla barra in basso. Con due squadre «Campo» da solo non vuole piu' dire
niente — campo di chi? — e l'interruttore risponde alla domanda prima che venga posta.

### Cosa non cambia

Nessuna schermata viene riscritta. Cambiano `Route`, la barra, il menu e il `Router`: sono
modifiche meccaniche e a basso rischio, ma vanno fatte prima di aggiungere altro, o si
fanno due volte.

---

## 2. La seconda squadra e' un club vero

### La riga che fa tutto

```sql
alter table clubs add column parent_club_id bigint references clubs(id);
```

Null significa prima squadra; valorizzato significa "e' la Primavera di quel club".

Con quella colonna la seconda squadra **eredita gratis** tutto cio' che un club sa gia'
fare: formazione, staff, divisione, calendario, classifica, partite giocate dal tick,
pagelle, presenze. Non serve scrivere niente di nuovo per farla giocare.

L'alternativa — una tabella `youth_squads` con regole proprie — vorrebbe dire riscrivere
ognuna di quelle cose in una seconda versione che diverge alla prima modifica.

### Niente portafoglio

La Primavera **non ha crediti**. Stipendi e acquisti passano dal club padre.

Due bilanci sarebbero due volte il lavoro per chi gioca e una porta aperta al riciclaggio:
mi vendo un giocatore da me a me al prezzo che voglio, e sposto denaro fra due conti che
controllo entrambi.

### Dove gioca

Nella scala vera delle divisioni, partendo dall'ultima, **senza limiti di salita**. Se
arriva in Serie A incontra la prima squadra, e in quella partita si impostano tutte e due
le formazioni.

E' stato segnalato che questo apre la porta a una partita contro se stessi, ed e' stato
scelto lo stesso. Nessun vincolo aggiuntivo: e' una lega fra amici, e chi bara si vede.

### La `squad = 'primavera'` sparisce

La colonna di 0017 era il magazzino. Adesso la verita' e' il club di appartenenza, e due
modi di dire la stessa cosa si contraddicono al primo ritocco. La migrazione crea il club
Primavera per chi ha gia' dei giovani parcheggiati e ci sposta i contratti.

---

## 3. Promozione e retrocessione

**Promuovi**: il contratto passa alla prima squadra, se sotto `maxSquadSize`.

**Mandi giu'**: solo chi ha l'eta' (`youthMaxAge`, 21 di serie).

**Chi compie gli anni sale da solo.** Un ventiduenne non puo' restare in Primavera: il tick
lo promuove e lo comunica. Se la prima squadra e' piena lo dice e basta — chi esce lo
decide il manager.

E' la scadenza che rende la Primavera una scelta invece di un deposito: prima o poi ogni
ragazzo torna indietro, e bisogna avergli fatto posto.

---

## 4. Lo staff, all'asta

Il modello c'e' per intero — tre ruoli, stelle da 1 a 5, moltiplicatori tarati — e ne
vengono generati un centinaio per lega, tutti liberi. `start_auction` accetta gia'
`target_type = 'staff'`. **Manca solo la schermata.**

| Ruolo | Effetto |
|---|---|
| Allenatore | crescita ×0,60 → ×1,80 |
| Preparatore | recupero stamina ×0,70 → ×1,55 |
| Osservatore | va in missione |

Ogni club ha il suo staff, **Primavera compresa**. Un allenatore da cinque stelle sulla
Primavera fa crescere i ragazzi il triplo: e' li' che la scelta diventa interessante,
perche' quello bravo non si puo' mettere su tutte e due.

**Fino a cinque osservatori** per club.

---

## 5. Le missioni di scouting

### Cosa

Si sceglie un osservatore, un paese e un ruolo. Parte, e ci mette del **tempo reale**.

| Stelle | Durata | Cosa riporta |
|---|---|---|
| ★ | 48 h | il primo che capita |
| ★★★ | 24 h | qualcosa di discreto |
| ★★★★★ | 8 h | il meglio disponibile in quel paese |

Quello che le stelle pescano e' il **potenziale**, non l'overall. Un cinque stelle riporta
un diciassettenne da 52 che arrivera' a 88; un una stella uno da 58 che si ferma a 64.
La forbice che si vede sulla scheda e' gia' stretta, perche' l'ha guardato giocare lui.

### Il giocatore arriva gratis

Entra in Primavera senza costo d'acquisto. Il prezzo si paga altrove, e sono due prezzi
veri: **l'asta per prendere l'osservatore bravo**, e **le ore in cui quell'osservatore e'
occupato** e non puo' cercare altro. Cinque osservatori sono cinque ricerche in parallelo,
non cinquanta.

### Puo' tornare a mani vuote

Se in Brasile non ci sono piu' attaccanti under 20 liberi, la missione finisce senza
niente. Deve poter succedere, o la mappa del mondo non conta niente e "vai in Brasile" e'
una decorazione.

### Le AI scoutano

Senza, gli otto club del computer starebbero a guardare mentre gli umani si prendono ogni
talento del mondo, e in tre stagioni la lega sarebbe decisa. Usano gli osservatori che
hanno, con la stessa tabella e le stesse durate.

---

## 6. Gli under 20 escono dal mercato

`start_auction` rifiuta uno **svincolato** sotto i vent'anni. Sono circa il dieci per cento
del mondo — l'eta' e' una gaussiana su 25,4 con deviazione 4,6 — cioe' un centinaio e
trenta giocatori per lega: abbastanza da cambiare il gioco, non tanti da svuotare il
listino.

Restano **vendibili una volta tesserati**: chi scopre un fenomeno lo puo' rivendere, e
quello e' mercato.

Anche il tick smette di aprire aste su di loro.

---

## 7. Le due cose piccole

**La bandiera** accanto al nome, sulla scheda e nelle liste. Dieci nazionalita', dieci
emoji, una mappa e basta.

**Le statistiche di carriera**: presenze, da titolare, minuti, gol, assist, media voto.
`appearances` le contiene gia' tutte — e' una lettura e un riquadro.

---

## 8. Il difetto dei prestiti

Segnalato come non funzionante. Leggendo, il percorso c'e' tutto — chip nella schermata,
`DealRepository.proposeLoan`, `propose_loan`, `respond_deal` — e i tipi combaciano.

Va trovato **provandolo**, non leggendolo. E' il metodo che in questa sessione ha funzionato
tre volte su tre: il mercato bloccato, gli orari sbagliati e le promesse gratis li ha
trovati qualcuno che guardava, non una prova che falliva.

---

## Migrazioni

| File | Contenuto |
|---|---|
| `0018_seconda_squadra.sql` | `clubs.parent_club_id`, creazione della Primavera, promozioni |
| `0019_staff_e_scouting.sql` | assegnazione dello staff, `scouting_missions`, under 20 fuori dalle aste |

## Ordine di lavoro

Prestiti → riordino dell'interfaccia → seconda squadra → promozioni → staff → missioni →
under 20 → bandiera e statistiche.

Il riordino sta al secondo posto e non all'ultimo: infilare staff e missioni nella struttura
vecchia e poi riordinare vorrebbe dire farlo due volte.

## Rischi

**Il riordino tocca l'ingresso di ogni schermata.** Sono modifiche meccaniche, ma sono
tante: un `when` esaustivo sulle rotte e' la difesa — se una destinazione resta scollegata,
il compilatore lo dice.

**Due squadre sono il doppio delle formazioni da impostare.** `AutoLineup` esiste ed e' la
rete: chi non schiera la Primavera la vede schierata bene lo stesso. Va verificato che il
tick la usi anche per i club figli.

**Le missioni possono svuotare un paese.** Dieci nazionalita' e circa centotrenta under 20
fanno tredici giovani per paese: con venti club che scoutano, il Brasile finisce. E' un
esito accettabile e va detto in schermata — «non c'e' rimasto piu' nessuno» e' una risposta,
non un errore.
