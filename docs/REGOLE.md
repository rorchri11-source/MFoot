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

**Lo staff si vince all'asta.**
Allenatori, preparatori e osservatori non si assegnano solo alla generazione del mondo.
*Chiesta all'inizio del progetto, implementata con `0019_staff_e_scouting.sql`. È la
richiesta che ha fatto nascere questo file.*

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

**Gli orari li sceglie chi gioca.**
Non fasce predefinite: l'ora si scrive. Vale per le competizioni e per le amichevoli.
*Detta il 2026-08-19 · `core/calendar/KickoffRules.kt`*

**Un'ora già passata si blocca.**
Non un errore dopo aver premuto: il pulsante è spento. Con un margine di quindici minuti,
perché una partita fra trenta secondi è nel futuro ed è inutilizzabile lo stesso.
*Detta il 2026-08-19 · `KickoffRules.MARGINE_MINUTI`*

**Gli orari sono ore di lega, non del telefono.**
Un appuntamento fissato alle nove deve restare alle nove anche per chi lo guarda da un
altro paese.
*`CalendarConfig.timeZone`*

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
