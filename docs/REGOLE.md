# Le regole di MFoot

**Questo file contiene le decisioni del proprietario della lega, non lo stato del
progetto.** Lo stato sta in [`STATO.md`](../STATO.md); la struttura nel
[`README`](../README.md); cosa è stato corretto e quando, in `git log`.

Qui c'è solo ciò che il codice **non può dedurre da solo**: le scelte di gioco fatte da
una persona, che se non stanno scritte da qualche parte si perdono fra una sessione e
l'altra. È già successo — lo staff all'asta era stato chiesto all'inizio e implementato
molte settimane dopo — e questo file esiste perché non succeda di nuovo.

**Ogni voce ha la data in cui è stata decisa e il posto dove vive nel codice.** Se una
riga di codice contraddice una voce di qui, è il codice a sbagliare, non la voce.

---

## Il mondo e le squadre

**I club dei giocatori veri partono tutti in prima divisione.**
Le seconde squadre no: partono dall'ultima. Le AI riempiono i posti che restano, dalla più
forte alla più debole. Se gli umani sono più dei posti, entrano lo stesso e la prima
divisione si allarga — la regola vince sulla dimensione — e l'app lo dice prima di
assegnare.
Vale per **l'inizio**: chi poi retrocede giocando retrocede davvero.
*Detta il 2026-08-19 · `core/calendar/DivisionAssignment.kt`*

**Le dimensioni delle divisioni le sceglie l'admin.**
Perché con dodici amici e una prima divisione da dieci qualcuno deve decidere, e il
programma non deve farlo di nascosto.
*Detta il 2026-08-20 · `DivisionsConfig.sizes`*

**Ogni club può fondare la sua Primavera.**
È un club vero, gioca un campionato suo, e non ha portafoglio: stipendi e acquisti passano
dalla prima squadra. Due bilanci sarebbero due volte il lavoro e una porta aperta al
riciclaggio.
*`supabase/migrations/0018_seconda_squadra.sql`*

---

## Il mercato

> Le voci che seguono sono state decise il **2026-08-24** e implementate nella stessa
> sessione. Il ragionamento completo, con le alternative scartate — che è la parte che
> questo file non può contenere senza diventare illeggibile — sta in
> [`DESIGN-GAMEPLAY.md`](DESIGN-GAMEPLAY.md).

**Si compra a prezzo fisso, e il giocatore è tuo nello stesso istante.**
Niente asta, niente attesa, niente tick. L'asta come rito obbligatorio costava un giorno
reale per ogni gregario: una rosa da diciotto uomini erano tre settimane. Peggiorato dal
fatto che il tick passa ogni venti-quaranta minuti, non ogni dieci.
*Detta il 2026-08-24 · `core/market/Listing.kt`, `0028_listino_e_contestazione.sql`, `MarketRepository`*

**L'asta esiste solo se qualcuno contesta, entro dodici ore.**
Per dodici ore dall'acquisto chiunque può opporsi, e solo allora nasce un'asta; passate
quelle, il giocatore è definitivamente di chi l'ha comprato. Chi ha comprato **è già in
testa** al prezzo che ha pagato, e se perde riprende i crediti interi. Una sola asta per
acquisto: il secondo che contesta entra in quella. L'asta **scade insieme alla finestra**,
così chi compra conosce dal primo istante l'ora in cui il giocatore è suo per sempre.
*Detta il 2026-08-24 · `ContestRules`, `contest_purchase`, `TickRunner.closeContestation`*

**Contestare è già un'offerta.**
Si dichiara subito il proprio massimo, che deve superare il prezzo pagato, e i crediti si
impegnano in quel momento. Non esiste contestare per dispetto: se vinci, paghi.
*Detta il 2026-08-24 · `ContestRules.rejection`, `contest_purchase`*

**Nelle dodici ore il giocatore gioca.**
È già in rosa a tutti gli effetti. Se poi lo si perde in asta, le partite giocate restano
dove sono.
*Detta il 2026-08-24 · `buy_player` sposta il contratto subito*

**Sul listino ci vanno gli svincolati e chi il proprietario mette in vendita, al prezzo
che vuole lui.**
Nessuna clausola su tutti: non si deve poter svuotare la rosa di chi dorme. Il prezzo è
libero da un credito a tutto il budget, e il correttivo al favore fra amici è la
contestazione — un prezzo fuori mercato è la definizione stessa dell'affare troppo buono,
e chiunque ha dodici ore per portarlo all'asta.
*Detta il 2026-08-24 · `list_player`, `ListingRules`*

**Svincolare è gratuito, e pubblico.**
Non si paga nessuna buonuscita: il giocatore torna svincolato e chiunque può prenderselo a
listino il minuto dopo, compreso il rivale diretto. Ogni svincolo è annunciato a tutta la
lega. *Scartato nella stessa sessione* il divieto di ricomprare chi si è svincolato: resta
quindi possibile riscrivere un contratto svincolando e ricomprando.
*Detta il 2026-08-24 · `release_player` in `0030_admin_svincoli_staff.sql`*

**L'admin può aggiungere e togliere giocatori a qualsiasi club, senza registro pubblico.**
Serve a riparare le leghe rotte. Il registro visibile a tutti è stato proposto e scartato:
si regge sulla fiducia del gruppo. Resta vero il motivo per cui era stato proposto —
l'admin è uno dei concorrenti, ed è la ragione per cui gli obiettivi li decide una regola
in `core` e non lui: questo strumento è l'unico punto del gioco dove quella separazione
non c'è, e va tenuto stretto.
*Detta il 2026-08-24 · `admin_assign_player`, `admin_release_player`, `admin_adjust_credits`*

**Lo staff si compra come i giocatori.**
Allenatori, preparatori e osservatori non si assegnano solo alla generazione del mondo:
stanno sul listino a prezzo fisso e si contestano nelle stesse dodici ore. `start_auction`
accetta `target_type = 'staff'` da `0019` e nessuna schermata lo usa ancora.
*Chiesta all'inizio del progetto — è la richiesta che ha fatto nascere questo file — e
implementata all'asta con `0019_staff_e_scouting.sql`. Il 2026-08-24 la regola è passata
dall'asta al listino insieme a quella dei giocatori: «si vince all'asta» vale ora solo
quando qualcuno contesta.*

**Chi apre un'asta per comprare ha già offerto il prezzo base.**
L'ha aperta perché lo vuole: parte in testa. Chi invece mette all'asta **un proprio**
giocatore è il venditore e non offre — sarebbe comprare da sé stesso — e se nessuno si fa
avanti resta invenduto.
Prima l'asta nasceva senza nessuno in testa, e succedevano tre cose: scadeva deserta anche
per chi l'aveva aperta, l'app scriveva «nessuno ha ancora offerto» pure sulla propria, e i
crediti di chi apriva non risultavano impegnati — quindi si potevano aprire tre aste che
insieme valevano più della cassa.
*Detta il 2026-08-24 · `core/market/Auction.kt` (`AuctionRules.open`),
`supabase/migrations/0026_chi_apre_ha_offerto.sql`, `TickRunner.apriAsta`*

**Gli under 20 non passano dalle aste.**
Si trovano mandandoci un osservatore. Un fuoriclasse di diciotto anni non deve poter essere
comprato da chi ha solo più soldi.
*`0019_staff_e_scouting.sql`*

**Durante l'asta si vede chi ha offerto e quanto ha portato il prezzo. Il massimo
dichiarato resta segreto fino alla chiusura.**
Vederlo prima cancellerebbe la meccanica: si offre quel numero più uno e si vince sempre.
*Confermata il 2026-08-20 · `0023_chi_ha_offerto.sql`, `ui/Auctions.kt`*

**Si dichiara un massimo, non un rilancio.**
Il sistema difende la posizione da solo. È ciò che permette di andare a dormire durante
un'asta invece di controllare il telefono ogni ora.
*`place_bid`, `core/market/AuctionRules.kt`*

---

## Le partite

**Gli ordini condizionali si vedono e si scrivono.**
«Se sono sotto dal 60', dentro la punta», «se scende sotto 40 di stamina, cambialo». Sono
completi in `core` dal principio, il database ha la colonna `orders` che li aspetta, e
nell'app non compaiono da nessuna parte. Sono il modo in cui chi alle 21 lavora ha
comunque voce in capitolo.
*Detta il 2026-08-24 · `core/match/ConditionalOrder.kt`, `core/match/OrderJson.kt`, `ui/screens/Campo.kt`*

**Al 45' si può intervenire.**
La finestra dell'intervallo: `MatchEngine` simula già primo e secondo tempo separati e la
configurazione la prevede, ma la partita si guarda solo finita. È l'unico momento in cui
una partita asincrona diventa una partita che si guarda.
*Confermata il 2026-08-24 · `WorldTick.halfTimesDue`, `TickRunner.giocaPrimoTempo`, `0029_finestra_intervallo.sql`*

**Gli orari li sceglie chi gioca.**
Non fasce predefinite: l'ora si scrive. Vale per le competizioni e per le amichevoli.
*Detta il 2026-08-19 · `core/calendar/KickoffRules.kt`*

**Un'ora già passata si blocca.**
Non un errore dopo aver premuto: il pulsante è spento. Con un margine di quindici minuti,
perché una partita fra trenta secondi è nel futuro ed è inutilizzabile lo stesso.
*Detta il 2026-08-19 · `KickoffRules.MARGINE_MINUTI`*

**Si assegnano cinque incarichi, e ognuno pesa nel motore.**
Capitano, rigorista, battitore d'angoli, battitore di punizioni, uomo dei calci lunghi. Un
incarico che non cambia un numero è una casella da riempire per niente. Gli angoli oggi
vengono emessi e la palla riparte: devono produrre un tentativo, deciso da chi batte
(passaggio, tecnica) e da chi salta in area (fisico, posizionamento — nessun attributo
nuovo, aggiungerlo rigenererebbe il mondo).
*Detta il 2026-08-24 · `core/match/SetPieces.kt`, `MatchEngine.resolveCorner`,
`0027_incarichi_e_ordini.sql`, `ui/screens/Campo.kt`*

**Il capitano tiene in piedi la squadra.**
Quando si va sotto o si perdono partite di fila, frena il crollo di morale e prestazione;
conta chi è, non solo quanto vale. Rende verificabile la promessa della fascia, che si può
già fare in un colloquio a una fascia che non esiste.
*Detta il 2026-08-24 · `core/match/SetPieces.kt`, `MatchEngine.resistenza`*

**I moduli sono dieci.**
Ai sei di adesso si aggiungono 4-3-1-2, 3-4-3, 4-1-4-1 e 5-4-1. Non costano niente al
motore: un modulo è solo la lista degli undici ruoli da coprire, e la forza delle zone
nasce da chi ci finisce dentro. Scartato il campo libero con gli undici trascinabili.
*Detta il 2026-08-24 · `core/match/Formation.kt`, `PitchLayout.kt`*

**Gli orari sono ore di lega, non del telefono.**
Un appuntamento fissato alle nove deve restare alle nove anche per chi lo guarda da un
altro paese.
*`CalendarConfig.timeZone`*

---

## Le squadre del computer

**Le AI devono fare quattro cose di loro iniziativa, tutte rivolte a chi gioca.**
Comprare a listino appena la rosa è corta — è quello che scioglie il riempimento lento
delle rose; **offrire crediti** per i tuoi giocatori, cosa che oggi non sanno fare (sanno
proporre solo giocatore contro giocatore); **chiedere a parole** — «il mio attaccante non
gioca mai, lo prendi in prestito?»; e **reagire a quello che fai** — contestare se compri
il loro obiettivo, rinforzarsi se le batti 5-0, farsi avanti quando metti in vendita.
*Detta il 2026-08-24 · `core/ai/AiMarket.kt`, `AiInitiative.playerToLoanOut`, `AiTurn`*

**Restano scaglionate.**
«Più reattive» non deve mai voler dire venticinque notifiche in due secondi: l'anti-sciame
di `AiScheduler` e i tetti di azioni giornaliere non si toccano. È il difetto che quel file
esiste per impedire.
*Confermata il 2026-08-24*

---

## Gli obiettivi

**Ogni squadra ha i suoi obiettivi di stagione, con un premio.**
Esempi chiesti: vinci la prima divisione, non retrocedere per due anni, porta il tuo
giocatore a 90.
*Chiesta il 2026-08-19 · `core/objectives/`*

**Il premio si paga solo per intero. Zero se non ci arrivi.**
Niente premi parziali: uno che paga metà se arrivi vicino non cambia nessuna decisione.
*Detta il 2026-08-19 · `ObjectiveEngine.reward`*

**I traguardi di crescita sono multipli di cinque, e ogni scalino paga.**
Da 66 si chiede 70, poi 75, poi 80. Chi arriva a 90 ha incassato cinque volte lungo la
strada, invece di aspettare tre stagioni per un premio solo.
*Detta il 2026-08-20 · `ObjectiveBoard.prossimoScalino`*

**Cosa chiedere a chi lo decide una regola scritta, non l'amministratore.**
L'admin è uno dei concorrenti: obiettivi scelti a mano sarebbero crediti assegnati da un
avversario.
*`ObjectiveBoard.forClub`*

---

## L'interfaccia

**L'aspetto di MFoot è quello del riferimento allegato il 2026-08-23.**
Venticinque schermate di un'altra app, consegnate come modello. Blu notte di fondo, barra
blu in cima che colora anche la barra di stato, schede **più scure** del fondo e senza
contorno, pulsanti lavanda pieni con testo blu scuro, angoli larghi, icone disegnate.
*Detta il 2026-08-23 · [`docs/DESIGN-SYSTEM.md`](DESIGN-SYSTEM.md), `ui/theme/Theme.kt`*

**Il verde non esiste più, nemmeno nella scala di valutazione.**
Era l'accento dell'app e insieme il gradino alto della scala — due mestieri per un colore
solo. Chiesto esplicitamente che sparisse dappertutto: adesso i quattro gradini sono
lavanda, bianco freddo, grigio, grigio spento. La proprietà che conta resta quella di
sempre — **si deve poter leggere una scheda senza leggere un numero** — e i gradini sono
sempre tre e netti.
*Detta il 2026-08-23 · `MFootColors.rating`*

**La barra grande del potenziale sparisce, e la scheda giocatore è una figurina.**
La barra occupava la fascia più preziosa — subito sotto il nome — per dire una cosa sola, e
per metà dei giocatori quella cosa era «niente da aggiungere»: su un maturo si riempiva
tutta senza informare, su un giovane mostrava un vuoto che sembra un difetto invece di una
promessa. Al suo posto un **gradino sotto l'overall**: «71», e sotto «+13» in oro; chi è
arrivato legge «AL MAX» in lavanda, perché la maturità è un traguardo. Sei attributi in tre
colonne invece di dodici in due, e la testata ad archi delle altre schermate. Vanno tenute
dentro due cose che la figurina non aveva: **quanto conosci** quel giocatore e il
**contratto**. Lo spazio liberato serve agli incarichi.
*Scelta il 2026-08-24 fra tre mockup · [`mockups/2026-08-24/schede-giocatore.html`](mockups/2026-08-24/schede-giocatore.html)
· `ui/PlayerDetail.kt`*

**Del riferimento si copia anche la navigazione, non solo l'aspetto.**
Chiesto per intero. Barra in basso con i cinque posti nell'ordine e con le icone del
modello (casa, maglia, calendario, medaglia, e il carrello dove il modello ha «Video», che
MFoot non ha); menu laterale con la testata a gradiente e i tre gruppi **Setup**, **Gioca**,
**Gestione**.
Una sola eccezione, e vale la pena saperla: nel modello Setup è un gruppo da amministratore,
qui **«Profilo lega» e «Partecipanti» le vedono tutti**. Non configurano niente, raccontano
— che lega è questa, chi c'è dentro, chi si è iscritto e non ha ancora fondato — e sono
precisamente le due schermate che serve aprire quando un amico dice «io ti vedo e tu no».
*Detta il 2026-08-23 · `ui/shell/Shell.kt`*

**Niente dev'essere dato per scontato.**
Se un dato esiste nel database e decide qualcosa, deve stare scritto in una schermata.
Vale per: la stamina, la divisione in cui si gioca, la formazione degli avversari, a quale
competizione si sta partecipando, in quale lega si è.
*Detta il 2026-08-19, ripetuta il 2026-08-20*

**Un pulsante che si può premere e che dà sempre errore insegna a non fidarsi di nessun
pulsante.**
Quello che il server rifiuterebbe va spento prima, non spiegato dopo.
*Detta il 2026-08-19*

**Si entra in una lega sapendo quale.**
Il nome si legge prima di premere, non dopo. Chi è iscritto a più leghe lo vede scritto.
*Detta il 2026-08-20 · `peek_league`, `ui/Door.kt`*

**L'app si aggiorna da sola, ogni trenta secondi.**
Non «chiudi e riapri»: mentre la guardi. Un giro leggero rilegge lega, club, contratti e
aste; un giro pieno scatta quando il server ha giocato una giornata, quando si torna
sull'app, o quando lo si chiede.
Due vincoli che non si negoziano: **non sbianca lo schermo** e **non tocca niente che si
stia modificando** — formazione in composizione, proposta in scrittura, regolamento in
modifica. Chi lo chiede a mano aggiorna tutto, perché l'ha chiesto lui.
*Detta il 2026-08-20 · `AppViewModel.aggiornaLeggero`, `CADENZA_MS`*

**Quando entra una squadra nuova, l'app lo dice.**
*«Il Bar di Marco è entrata nella lega.»* È il momento che è mancato al proprietario e al
suo amico: uno fonda il club e dall'altra parte non succede niente, per sempre.
*Detta il 2026-08-20 · `AppViewModel.squadreNuove`*

---

## Come si tiene aggiornato

Quando il proprietario decide qualcosa di nuovo — una regola di gioco, non una preferenza
passeggera — **la voce si aggiunge qui nella stessa sessione in cui viene detta**, con la
data e il posto in cui vive.

Se una richiesta arriva e non viene implementata subito, la voce si scrive lo stesso, con
scritto che manca: una regola dimenticata è peggio di una regola in attesa.
