# Il gameplay del 2026-08-24

**Questo file è il verbale di una sessione di progettazione, non lo stato del progetto.**
Lo stato sta in [`STATO.md`](../STATO.md); le regole di gioco definitive in
[`REGOLE.md`](REGOLE.md), dove le voci di qui vengono promosse mano a mano che diventano
codice.

Serve a una cosa sola: **niente di quello che è stato deciso il 24 agosto 2026 deve
ridiventare una domanda aperta.** Per ogni scelta c'è la decisione, il motivo, e —
soprattutto — **le alternative scartate**, perché è quello che fra un mese farebbe
ricominciare la discussione da capo.

I mockup interattivi discussi nella sessione stanno in
[`mockups/2026-08-24/`](mockups/2026-08-24/) e si guardano avviando il server `mockups`.
Non sono disegni: due dei tre hanno dentro le regole vere e si possono usare.

| Mockup | Cosa dimostra |
|---|---|
| [`schede-giocatore.html`](mockups/2026-08-24/schede-giocatore.html) | Tre direzioni per la scheda, con l'interruttore fra un ragazzo da scommessa e un veterano arrivato |
| [`mercato.html`](mockups/2026-08-24/mercato.html) | L'acquisto a prezzo fisso e la contestazione, su due telefoni: asta, anti-snipe e rimborso funzionano davvero |
| [`campo-incarichi.html`](mockups/2026-08-24/campo-incarichi.html) | I dieci moduli e i cinque incarichi, con quanto ogni scelta pesa nel motore |

---

## Da dove nasce

Sei richieste del proprietario, in una frase sola: gameplay migliore, AI più reattive,
**un'alternativa alle noiose aste**, gestione della rosa con acquisti e svincoli che
muovono i crediti, tattiche e moduli veri con capitano e rigorista, via la barra grande
del potenziale e scheda giocatore rifatta.

Tre cose sono emerse leggendo il codice prima di progettare, e hanno cambiato le domande:

**Metà di quello che serviva esisteva già e non si vedeva.** `lineups` ha `captain_id` e
`penalty_taker_id` dal primo schema, `Lineup` li porta in `core`, `AutoLineup` li assegna
e `MatchEngine` usa il rigorista designato. Mancava solo la schermata. Gli **ordini
condizionali** sono completi in `core` — «se sono sotto dal 60'», «se scende sotto 40 di
stamina» — con la colonna `orders` che li aspetta, e in `android/` non compaiono mai.

**L'angolo è scenografia.** `MatchEventType.ANGOLO` viene emesso quando un attacco si
spegne in area, e poi non produce niente: nessun battitore, nessuno stacco, nessun gol.

**L'asta non è noiosa per com'è fatta, è noiosa per quanto dura.** Il tick impiega otto
minuti a giro e il 59% delle esecuzioni viene cancellato: la cadenza vera è fra venti e
quaranta minuti. Un'asta da un'ora con tre rilanci diventa mezza giornata di attesa.
Qualsiasi meccanica appesa al tick eredita quel ritardo — ed è la ragione per cui la
scelta sul mercato è stata fatta *contro* il tick, non dentro.

---

## 1. Il mercato: si compra subito, e chi vuole contesta

### La decisione

**Si compra a prezzo fisso, e il giocatore è tuo nello stesso istante.** Niente attesa,
niente rilanci, niente tick. Per **dodici ore** l'acquisto resta *contestabile*: chiunque
può opporsi, e solo allora nasce un'asta. Passate le dodici ore il giocatore è tuo per
sempre.

*Detta il 2026-08-24 · vive in `core/market/` (regola nuova), `supabase/migrations/`,
`TickRunner`*

### Perché

Perché l'asta come **rito obbligatorio** costa un giorno reale per ogni gregario, e una
rosa da diciotto uomini diventa tre settimane di attesa. Come **eccezione** invece
funziona: protegge dall'affare troppo buono senza tassare i novanta acquisti banali che
non interessano a nessuno.

### Le regole di dettaglio, decise nella stessa sessione

**Chi ha comprato è già in testa all'asta di contestazione, al prezzo che ha pagato.**
È la regola del 2026-08-24 già scritta in `REGOLE.md` — chi apre un'asta per comprare ha
già offerto il prezzo base — applicata qui: chi ha comprato non deve rioffrire su un
giocatore che era già suo.

**Contestare è già un'offerta.** Per contestare si dichiara subito il proprio massimo, che
deve superare il prezzo pagato, e i crediti si impegnano nello stesso momento. *Non esiste
contestare per dispetto*: se vinci, paghi. È l'unica versione che non trasforma la
contestazione in un modo gratuito di rovinare la giornata a un avversario.

**Una sola asta per acquisto.** Il secondo che contesta entra in quella aperta dal primo,
non ne apre un'altra.

**L'asta scade insieme alla finestra.** Non un'ora dopo l'ultimo rilancio: alla scadenza
delle dodici ore. Così chi compra conosce **fin dal primo istante l'ora esatta** in cui il
giocatore è suo definitivamente. L'anti-snipe resta e prolunga solo quella scadenza.

**Se chi ha comprato perde l'asta, riprende i crediti interi.** Ha perso il giocatore, non
i soldi.

**Nelle dodici ore il giocatore gioca.** È già tuo a tutti gli effetti: schierabile,
sostituibile, titolare. Se poi lo perdi in asta, le partite già giocate restano dove sono.
*Alternativa scartata:* bloccarlo fino a fine finestra — più giusto sulla carta, ma
significa una casella grigia in rosa per mezza giornata e una regola in più da spiegare.

**Sul listino ci vanno gli svincolati e chi il proprietario mette in vendita.** Non tutti.
*Alternative scartate:* la clausola su tutti a prezzo gonfiato (ti svegli e il tuo
centravanti è di un altro: mercato vivo ma brutale, e in una lega fra amici asimmetrica —
chi è online alle 15 razzia chi lavora); e la clausola scritta a mano dal proprietario su
ogni giocatore (libertà massima, ma richiede di tenere aggiornate venti clausole a testa,
cioè non lo farà nessuno e le rose diventeranno svuotabili per dimenticanza).

**Il prezzo di vendita lo scrive il proprietario, libero.** Da un credito a tutto il
budget. *Alternative scartate:* la forchetta legata a `Valuation` (fra metà e il doppio
del valore) e il prezzo secco calcolato dalla formula.

Il rischio della libertà è il favore fra amici — due che si mettono d'accordo spostano un
88 per un credito — e **la contestazione è già il suo correttivo**: un prezzo fuori
mercato è la definizione stessa dell'affare troppo buono, e chiunque nella lega ha dodici
ore per contestarlo e portarlo all'asta. Le due decisioni si tengono in piedi a vicenda:
il prezzo libero senza finestra di contestazione sarebbe indifendibile.

**Le AI contestano solo quello che volevano davvero.** Contesta l'AI che aveva quel
giocatore fra i propri bersagli, ha i crediti, e vede un prezzo sotto la propria
valutazione: la sua fissazione, il ruolo che le manca. Un affare troppo buono viene punito
e tutto il resto passa liscio. *Alternativa scartata:* AI che valutano ogni acquisto
altrui — mercato più combattuto, e si torna a fare aste ogni giorno, cioè la cosa da cui
questa intera sezione serve a scappare.

---

## 2. La rosa: si aggiunge e si svincola, e i crediti si muovono

### La decisione

Dalla propria rosa si **compra** (i crediti scendono) e si **svincola**. Lo svincolo è
**gratuito**, ma il giocatore torna svincolato e chiunque può prenderselo a listino il
minuto dopo — compreso il rivale diretto.

*Detta il 2026-08-24*

### Perché gratis

Scelta esplicita del proprietario. *Alternativa scartata:* pagare lo stipendio residuo,
che avrebbe reso costoso liberarsi di un errore di mercato.

### La conseguenza, e l'unica contromisura

Va scritto perché non venga scoperto giocando: **gli stipendi sono l'unico freno naturale
all'accumulo** (`ContractRules.wageFor` cresce più che linearmente con l'overall). Poter
azzerare un ingaggio a costo zero toglie il freno.

**Una sola contromisura, decisa il 2026-08-24: lo svincolo è pubblico.** Tutti ricevono
l'annuncio, subito. Chi svincola un 84 per far cassa lo fa davanti a tutta la lega.

*Scartata nella stessa sessione:* il divieto di ricomprare chi si è svincolato per un
certo numero di giornate. Resta quindi possibile svincolare e riprendersi lo stesso
giocatore a listino — cioè **riscrivere un contratto azzerandone la durata**. Va scritto
perché è una conseguenza voluta e non una svista: se un giorno il monte stipendi smettesse
di contare, si guarda prima qui.

### L'admin sulle altre squadre

Il proprietario ha chiesto uno **strumento da amministratore** che aggiunga o tolga
giocatori a qualsiasi club e ne muova il budget. Serve a riparare le leghe rotte.

**Senza registro pubblico.** Proposta e scartata il 2026-08-24 la traccia visibile a tutti
degli interventi. Lo strumento resta quindi silenzioso, e regge sulla fiducia del gruppo —
che in una lega di amici è una base legittima.

Resta vero il motivo per cui il registro era stato proposto, e va ricordato a chi
implementa: l'admin **è uno dei concorrenti**, ed è la ragione per cui gli obiettivi di
stagione li decide una regola in `core` e non lui. Questo strumento è l'unico punto del
gioco dove quella separazione non c'è: va tenuto stretto — poche operazioni, dichiarate,
nessuna scorciatoia che assegni crediti.

---

## 3. Tattiche, moduli e i cinque incarichi

### La decisione

Cinque incarichi da assegnare in formazione: **capitano, rigorista, battitore d'angoli,
battitore di punizioni, uomo dei calci lunghi.** Ognuno deve **pesare nel motore**: un
incarico che non cambia un numero è una casella da riempire per niente.

*Detta il 2026-08-24 · `core/match/Formation.kt` (`Lineup`), `MatchEngine`,
`ui/screens/Campo.kt`*

*Alternative scartate:* i tre essenziali con angoli e punizioni affidati allo stesso uomo
(meno da spiegare, ma perde la differenza fra chi crossa e chi calcia); e i sette con
vice-capitano e rigorista di riserva (nessuna casella scoperta, ma sono sette scelte prima
di ogni partita).

### Il capitano tiene in piedi la squadra

Quando si va sotto nel punteggio o si perdono partite di fila, **il capitano frena il
crollo** del morale e della prestazione. Conta chi è: carisma ed esperienza valgono di più
della sola qualità tecnica.

**E chiude un cerchio che era aperto.** `ConversationEngine` ha dal principio l'argomento
*«Fascia da capitano»*: si può promettere la fascia a un giocatore, e la fascia non
esiste. Da qui in avanti quella promessa è verificabile come tutte le altre.

*Alternative scartate:* il capitano che alza i rating dei compagni intorno a sé (si vede
nei numeri, ma è un effetto che il calcio vero fatica a mostrare); e la versione con
penalità se lo togli o lo lasci in panchina — interessante, rimandata: prima la fascia
deve fare qualcosa, poi potrà costare toglierla.

### Dieci moduli

Ai sei di adesso se ne aggiungono quattro: **4-3-1-2** (il rombo), **3-4-3** (il tridente
con la difesa a tre), **4-1-4-1** (il regista basso), **5-4-1** (il fortino).

Non costano niente al motore, ed è il motivo per cui si possono aggiungere in un
pomeriggio: un modulo è **solo la lista degli undici ruoli da coprire**, e la forza di ogni
zona nasce da chi ci finisce dentro (`ZoneRatings`). Giocare a tre dietro indebolisce le
fasce da solo, senza bisogno di un bonus scritto a mano.

*Alternativa scartata:* il campo libero, con gli undici trascinabili ovunque e le zone
calcolate dalle posizioni. È la libertà massima e costa la riscrittura della schermata del
campo — più un problema che il modulo risolve da solo: chi non sa dove mettere gli uomini
davanti a un campo vuoto resta fermo.

### Gli angoli diventano occasioni vere

Oggi l'angolo viene emesso e la palla riparte. Deve invece produrre un tentativo, deciso
da **chi batte** (passaggio, tecnica) e da **chi salta in area** (fisico, posizionamento —
non esiste un attributo di stacco, e non va aggiunto: rigenererebbe il mondo).

---

## 4. Le AI: quattro cose che devono fare per prime

Il proprietario ha chiesto tutte e quattro.

**Comprano a listino appena serve.** È quello che scioglie il riempimento lento delle rose:
un'AI con la rosa corta non aspetta più un'asta, compra. Il difetto misurato — club fermi
fra uno e nove giocatori dopo venti giri — sparisce alla radice.

**Ti offrono crediti per i tuoi giocatori.** «Ti do 45 crediti per Baresi.» Oggi non
possono: `AiInitiative.proposeTrade` sa proporre solo giocatore contro giocatore, e il
conguaglio in crediti (`TradeOffer.cash`, che ha già il segno) non viene mai usato da solo.

**Ti chiedono cose a parole.** Un messaggio nella scrivania: «il mio attaccante non gioca
mai, lo prendi in prestito?», «mi serve un portiere, hai qualcosa?». Le AI diventano
interlocutori invece di comparse.

**Reagiscono a quello che fai.** Se compri il loro obiettivo, contestano. Se le batti 5-0,
la settimana dopo si rinforzano. Se metti in vendita, chi ha quel buco si fa avanti entro
l'ora.

*Detta il 2026-08-24 · `core/ai/AiInitiative.kt`, `AiTurn`, `AiScheduler`*

**Il vincolo che non si tocca:** l'anti-sciame. Le AI restano scaglionate su ore diverse e
con i loro tetti di azioni giornaliere. «Più reattive» non deve mai voler dire venticinque
notifiche in due secondi — è il difetto che `AiScheduler` esiste per impedire.

---

## 5. La scheda giocatore: la figurina

### La decisione

**Direzione A — la figurina.** Scelta dal proprietario il 2026-08-24 fra tre mockup
interattivi ([`mockups/2026-08-24/schede-giocatore.html`](mockups/2026-08-24/schede-giocatore.html)).

*Alternative scartate:* **il cruscotto** (tessere di misura uguale, la più informativa e
la più facile da estendere) e **il dossier** (un giudizio scritto a parole al posto dei
numeri, la più chiara per chi impara a giocare).

### Cosa cambia

**La barra grande del potenziale sparisce.** Occupava la fascia più preziosa della scheda —
subito sotto il nome — per dire una cosa sola, e per metà dei giocatori quella cosa era
«niente da aggiungere»: su un maturo si riempiva tutta senza informare, su un giovane
mostrava un vuoto che sembra un difetto invece di una promessa.

**Al suo posto, un gradino sotto l'overall.** «71», e sotto «+13» in oro. Quarantacinque
pixel invece di centoventi, letto nello stesso colpo d'occhio del numero grande. Chi è
arrivato legge «AL MAX» in lavanda: un traguardo, non una mancanza.

**Sei attributi in tre colonne**, quelli che il ruolo pesa davvero, invece di dodici in due.

**La testata ad archi** è la stessa delle altre schermate: la scheda entra nell'app invece
di sembrare un'altra app.

### Le due cose da riportare dentro, che il mockup non ha

La figurina è la meno densa delle tre, e due informazioni non possono perdersi:

1. **Quanto conosci quel giocatore.** Una forbice larga vuol dire due cose opposte —
   giocatore imprevedibile, oppure non l'hai mai visto giocare — e senza dirlo la
   scommessa resta muta. Va sotto il gradino di crescita, in una riga piccola.
2. **Il contratto.** Sei giornate alla scadenza è un'informazione che cambia una
   decisione d'acquisto, e nella figurina non c'era.

### Dove va lo spazio liberato

Sulla scheda dell'uomo a cui li assegni: **capitano, rigorista, battitore d'angoli.** È
il posto giusto — si decide chi calcia i rigori guardando il tiro, non una lista.

---

## L'ordine dei lavori

Deciso il 2026-08-24. Va dal rischio più basso al più alto, e ogni passo lascia l'app in
uno stato consegnabile.

**1. La scheda giocatore.** Solo interfaccia, nessuna migrazione, nessuna regola toccata:
si vede subito ed è impossibile che rompa una partita. Rientrano dentro la conoscenza del
giocatore e il contratto.

**2. Gli incarichi, i dieci moduli e gli ordini condizionali.** `captain_id` e
`penalty_taker_id` esistono già nel database e in `Lineup`; servono tre colonne nuove per
angoli, punizioni e calci lunghi, e la colonna `orders` aspetta da sempre. Qui il lavoro
vero è nel motore: l'angolo deve produrre un tentativo, e il capitano deve pesare sul
morale.

**3. Il mercato: listino, acquisto immediato, contestazione.** Il pezzo grosso — `core`,
SQL, tick e app insieme, e sposta soldi. Le regole stanno in `core` con i loro test, e le
due implementazioni (SQL e tick) le seguono, come è già stato fatto per l'apertura d'asta.
Lo staff entra nel listino con le stesse regole.

**4. Le AI.** Per ultime, quando il listino esiste già: comprare a listino è la mossa che
scioglie il riempimento lento delle rose, e senza listino non si può scrivere.

**5. La finestra dell'intervallo.** Voluta esplicitamente: è l'unico momento in cui una
partita asincrona diventa una partita che si guarda.

### Il vincolo tecnico che attraversa tutto

**Ogni iniziativa AI in più allunga il tick**, che oggi dura otto minuti a giro e viene
cancellato nel 59% delle esecuzioni. `loadSquad` fa una query per club ed è chiamata da
sette punti, uno dei quali dentro un ciclo su tutte le squadre: le AI più reattive passano
tutte di lì.

Due cose lo tengono sotto controllo, e vanno tenute a mente al punto 4:

- **Il mercato a prezzo fisso toglie lavoro al tick** invece di aggiungerne: gli acquisti
  non ci passano più, solo le contestazioni.
- **Leggere le rose una volta per lega** a inizio giro resta la correzione da fare. Se il
  punto 4 comincia a pesare, si fa prima quella — è dentro il codice che sposta soldi,
  contratti e aggiudicazioni, quindi va fatta con attenzione e non di fretta.

---

## Cosa è successo implementandolo, il 2026-08-25

Tre cose che il progetto non poteva sapere prima di provare, e che vale la pena non
riscoprire da capo.

**Il capitano, tarato a occhio, spostava il bilanciamento di tutta la lega.** La resistenza
in svantaggio era a 2,6 — quasi quanto un gol subito, che ne vale 3,2 — e fra squadre pari
le vittorie in casa scendevano dal 45,1% al 42,5%. Il motivo è ovvio a posteriori: **chi va
sotto è più spesso l'ospite**, quindi la spinta arrivava quasi sempre a lui. A 1,3 il
capitano si sente nelle rimonte e i numeri tornano dove `BalanceReportTest` li aveva
misurati.

**Le soglie di gradimento delle AI erano irraggiungibili, non severe.** La prima versione
faceva contestare un acquisto solo con `appeal >= 0,45`. Misurando i valori veri in una
lega generata, il gradimento sta **fra 0,1 e 0,2**: nessuna AI avrebbe contestato mai
niente, e non sarebbe stato un difetto visibile — solo un mercato stranamente tranquillo.
La regola adesso passa dal **tetto di spesa**, che è già proporzionale al gradimento, al
valore stimato e alla disciplina del club, e si tara da solo in qualunque lega.

**Sotto il minimo di rosa, il gradimento smette di distinguere.** `AiManager.evaluate` alza
al pavimento (0,2) qualunque giocatore per un club a cui manca gente — giustamente. Ma
l'effetto è che un attaccante da 77 con l'attacco vuoto e un difensore da 85 con otto
difensori in rosa escono con lo **stesso identico numero**, e la scelta finiva sulla
convenienza: cioè sempre il più forte. Un club con otto difensori centrali e zero
attaccanti comprava il nono difensore. Ora, a parità di gradimento, decide **quanti ne ha
già in quel ruolo**.

**Il listino non conteneva gli svincolati.** Trovato provando le funzioni sul database
vero, non leggendo il codice: `listings` si riempiva **solo** quando qualcuno metteva in
vendita, quindi un giocatore senza contratto non era comprabile a prezzo fisso e restava
raggiungibile soltanto all'asta — cioè metà della regola non esisteva, e proprio la metà
più numerosa del mercato. Adesso li mette a listino il tick a ogni giro
(`aggiornaListinoSvincolati`), al valore di mercato calcolato da `Valuation`: il prezzo non
si riscrive in SQL, o si separerebbe dal listino vero al primo ritocco. Gli under 20 restano
fuori, come dice la regola di `0019`.

Fuori tema ma annotato: il bonus delle fissazioni (`obsessionBonusFor`) è **additivo e
applicato dopo i moltiplicatori**, quindi non viene ridotto da un ruolo già coperto. Su
gradimenti che valgono 0,1-0,2 vince su qualunque bisogno. Non è stato cambiato — è una
scelta di carattere che si vede giocando, e ritoccarla andrebbe misurato su una stagione
intera — ma chi un giorno si chiederà perché quel club compra sempre difensori, la risposta
è questa.

---

## Come si tiene aggiornato

Quando una voce di qui diventa codice, la sua regola si promuove in
[`REGOLE.md`](REGOLE.md) con la data in cui è stata **decisa** — il 2026-08-24 — non
quella in cui è stata scritta. Questo file resta come verbale: serve a sapere *cosa era
stato scartato e perché*, che è l'unica cosa che `REGOLE.md` non può contenere senza
diventare illeggibile.
