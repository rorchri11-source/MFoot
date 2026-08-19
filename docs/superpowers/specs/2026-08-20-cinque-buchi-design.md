# Cinque buchi, e un posto dove tenere le regole

**Data:** 2026-08-20
**Stato:** approvato dal proprietario, sezione per sezione

---

## Da dove nasce

Il proprietario ha ripetuto sei lamentele identiche a quelle del giorno prima. Cinque
erano già state affrontate; la ripetizione non era una svista, era il segnale che la
diagnosi era sbagliata in almeno un punto e che alcune richieste erano state lette come
osservazioni invece che come regole.

Tre domande hanno cambiato il quadro:

1. *«Chiudendo e riaprendo l'app vedevi la sua squadra?»* → **no**. Questo elimina la
   teoria della fotografia congelata (l'app legge il mondo una volta sola all'avvio, e
   non lo aggiorna mai da sola — vero, ma non era questo).
2. *«Quante leghe avevi, e con che codici?»* → **più di una, codici diversi**. Questo
   elimina anche la teoria dei codici duplicati, che era stata corretta il giorno prima.
3. *«Tocchi un giocatore e non si apre niente?»* → **«non ho visto»**. La scheda esiste,
   funziona, e nessuno sa che esiste.

Quel che resta, e che è il difetto vero del punto 1: **la lega in cui sei non si vede e
non si cambia.** Chi entra col codice non sa dove sta entrando; chi riapre l'app ci
rientra dritto senza che niente glielo dica. Due amici possono giocare in due leghe
diverse convinti di essere nella stessa, e nessuna schermata li smentisce.

---

## Cosa non è in questo documento

Le cose già fatte il 19 agosto e che restano valide: la Primavera che spariva dopo
un'offerta, le aste che non venivano contate, gli orari scelti da chi gioca, la stamina
scritta in rosa, le divisioni raggruppate, la formazione degli avversari, gli obiettivi
di stagione. Stanno in `STATO.md` e nei commit.

Qui c'è solo quello che manca ancora, più il meccanismo che deve impedire alla prossima
regola di andare persa.

---

## Sezione 1 — Le due regole che cambiano il comportamento

### 1a. Entrare in una lega sapendo in quale

**Anteprima prima di entrare.** Digitato il codice, l'app chiede al server soltanto *che
lega è questo codice* e mostra nome, iscritti, club e stato prima di far premere
qualcosa. Il pulsante porta il nome della lega: «Entra in Lega dei Bar», non «Entra».
Codice che non combacia: lo dice lì, senza tentare l'ingresso.

Serve `peek_league(codice)` sul database: `security definer`, risponde anche a chi non è
ancora membro. Espone nome e due conteggi — chi ha il codice ha già il diritto di
entrare, quindi non si sta pubblicando niente che non fosse già suo.

**Avviso all'avvio con più leghe.** L'app continua ad aprire quella salvata senza
chiedere niente. Ma se risulti iscritto a più di una, in cima compare una riga toccabile:
«Sei in N leghe. Stai guardando X.» Una richiesta minuscola, e solo quando serve davvero.

**«Esci dalla lega» diventa «Cambia lega»** e sale accanto a «Le mie leghe». Il gesto è
sempre stato quello — la lega resta, ci si può rientrare — ma scritto in rosso in fondo
sembrava una cancellazione, quindi nessuno lo toccava.

### 1b. Chi parte in quale serie

**Regola del proprietario:** i club umani partono **tutti in prima divisione**. Le
seconde squadre non entrano in questo conto e partono dall'ultima, come già fanno. Le AI
riempiono i posti che restano, dalla più forte alla più debole: prima completano la prima
divisione, poi la seconda, e così via.

**Le dimensioni le sceglie l'admin**, una per divisione. Oggi la configurazione sa solo
*quante* divisioni ci sono, non quanto sono grandi: va aggiunto `divisions.sizes`. Lista
vuota o incompleta significa «dividi in parti uguali», che è il comportamento di adesso.

**Avviso prima di assegnare**, non dopo: «Hai 12 club umani e la Serie A ne prevede 10.
Allargala a 12, o due partiranno in Serie B.» L'app dice il problema e lascia decidere.

**La regola vale per l'inizio.** Se poi un umano retrocede giocando, retrocede davvero:
un campionato in cui non si può scendere non è un campionato. Questa frase sta qui per
iscritto perché è l'ambiguità in cui si sarebbe inciampati fra sei mesi.

La regola vive in `core`, accanto a quella che decide promozioni e retrocessioni, con i
suoi test: è regolamento, non un dettaglio della schermata che la invoca.

---

## Sezione 2 — Le tre cose che si vedono

### 2a. Obiettivi: traguardi, non incrementi

L'obiettivo di crescita dice «porta il tuo giocatore a 71», perché somma cinque a dov'è
adesso. Si legge come un compito di aritmetica.

Diventa **il prossimo multiplo di cinque**: da 66 il traguardo è 70, da 71 è 75, da 88 è
90. Sono gli stessi cinque punti, ma sono un posto dove arrivare.

Il premio si paga **a ogni scalino**. Chi porta il suo giocatore a 90 ha incassato a 70,
75, 80, 85 e 90: cinque volte lungo la strada. «Fai salire il player speciale a 90»
diventa un percorso che paga mentre lo fai, invece di una scommessa unica che o si vince
o si sono buttate via tre stagioni. Non serve nessun numero nuovo: la percentuale del
premio è quella già configurata.

### 2b. L'asta dice chi ha offerto quanto

La riga si legge «Milan · prezzo a 42»: è un prezzo, non un'offerta. Diventa un elenco di
**nome, importo, quando**, dal più recente, col capofila marcato.

In cima, un riepilogo: «3 squadre dentro, 7 offerte». È la cosa che dice se è una gara o
se sei solo, e oggi non esiste. Lo stesso conteggio va nella riga dell'asta nell'elenco
del mercato, accanto al tempo che manca.

Il massimo dichiarato resta fuori, come deciso dal proprietario: si legge a fine asta,
dove già si vede.

### 2c. La scheda giocatore si deve poter trovare

La scheda esiste, ha tutto — overall, ogni attributo con la sua barra, stelle, crescita,
stamina, morale, forma — e si apre toccando un giocatore in qualsiasi lista. Non lo sa
nessuno perché niente lo dice.

Due interventi: una **freccia `›` a destra di ogni riga di giocatore** (rosa, listone,
svincolati), come già hanno le scorciatoie della Casa; e una riga sotto l'intestazione
della rosa che dice cosa c'è dentro. Nessuna schermata nuova: smettere di tenere nascosta
quella che c'è.

---

## Sezione 3 — Il file che smette di perdere le regole

### `docs/REGOLE.md`

Le **regole decise dal proprietario**, in italiano, ognuna con la data in cui è stata
detta e dove vive nel codice. Non un diario e non un riassunto dello stato: solo ciò che
il codice non può dedurre da sé e che, dimenticato, sparisce.

Non ci va la storia dei difetti (sta in `STATO.md`), non ci va la struttura del progetto
(sta nel `README`), non ci vanno le cose leggibili da `git log`.

### `CLAUDE.md` nella radice

Un file in `docs/` non serve se nessuno lo apre. Nella radice va un `CLAUDE.md` — che non
esiste ancora — che dice in poche righe cos'è MFoot e che le regole del proprietario
stanno in `docs/REGOLE.md`, da leggere prima di toccare qualsiasi cosa.

Quel file viene caricato **automaticamente all'inizio di ogni sessione**. È la differenza
fra un documento che esiste e uno che viene letto.

**Il limite, detto:** il file resta utile solo se viene aggiornato ogni volta che il
proprietario decide qualcosa di nuovo. Quella parte resta un impegno, non un
automatismo — ma da adesso c'è un posto dove metterlo.

---

## Piano di lavoro

| # | Cosa | Dove |
|---|---|---|
| 1 | `peek_league` | `supabase/migrations/0025_entrare_sapendo_dove.sql` |
| 2 | Anteprima nella porta d'ingresso | `ui/Door.kt`, `app/AppState.kt`, `app/AppViewModel.kt` |
| 3 | Avviso «sei in N leghe» | `app/AppViewModel.kt`, `ui/shell/Shell.kt` |
| 4 | «Cambia lega» al posto di «Esci» | `ui/shell/Shell.kt` |
| 5 | `DivisionAssignment.initial` + test | `core/calendar/DivisionAssignment.kt` |
| 6 | `divisions.sizes` in configurazione | `core/config/LeagueConfig.kt`, `ConfigJson.kt` |
| 7 | Assegnazione e avviso nell'app | `app/AppViewModel.kt`, `ui/settings/SettingsScreen.kt` |
| 8 | Traguardi a multipli di cinque | `core/objectives/ObjectiveBoard.kt` + test |
| 9 | Cronologia asta riscritta, con i conteggi | `data/AuctionRepository.kt`, `ui/Auctions.kt` |
| 10 | Freccia e riga di aiuto | `ui/screens/Rosa.kt`, `ui/PlayerList.kt` |
| 11 | `docs/REGOLE.md` e `CLAUDE.md` | radice e `docs/` |

### Come si verifica

`gradlew :core:test` deve restare verde e crescere: i punti 5, 6 e 8 sono regolamento e
non si accettano senza prove. Il resto si verifica compilando e installando: `gradlew
:android:assembleDebug`.

Le migrazioni SQL vanno applicate **prima** dell'APK, come sempre in questo progetto: una
colonna che non esiste fa rifiutare a PostgREST l'intera query in cui compare.
