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
*`create_youth_club` in `schema.sql`*

---

## Le competizioni

**Un campionato per divisione, e si compone a mano.**
Le divisioni dicono in che serie gioca un club; una competizione e' un calendario fra i
club che l'admin sceglie. Le due cose restano separate — una coppa e un torneo a gironi
*devono* poter mescolare le serie — ma nella scelta dei partecipanti i club adesso sono
**raggruppati per divisione**, con il conto di quanti ne sono dentro e un «tutte» per
gruppo. Scartata la creazione automatica di N campionati in un gesto: la composizione
resta una scelta.
Il motivo era che l'elenco era piatto. Il livello sta in `clubs.division_level` da sempre e
lo mostrano la Casa, la rosa e Squadre — non lo mostrava l'unica schermata in cui serve a
decidere, quindi si componevano campionati con dentro due serie e una classifica sola senza
accorgersene.
*Detta il 2026-08-29 · `ui/Competitions.kt` (`Partecipanti`), `CompetitionsState.divisioni`*

**Le coppe camminano da sole: finito un turno, il successivo nasce dopo tre giorni.**
Il numero di giorni e' `calendar.cupRoundGapDays`. Vale per l'eliminazione diretta, per la
fase finale dei gironi e per playoff e playout. Chi gioca riceve l'avviso con l'ora.
Prima non succedeva **niente**: la coppa giocava gli ottavi e restava ferma per sempre.
`FixtureGenerator.nextKnockoutRound` esisteva con i suoi test dal primo giorno e non lo
chiamava nessuno — ne' il server, ne' l'app, ne' una funzione del database.
*Detta il 2026-08-29 · `core/calendar/CompetitionProgress.kt`, `TickRunner.avanzaLeCompetizioni`*

**Chi resta senza avversario passa il turno, e al turno dopo gioca lui.**
Con un numero dispari il tabellone accoppia a due a due e uno resta fuori: e' un turno di
riposo, non un'eliminazione. Al turno successivo il riposato viene messo davanti, cosi' a
saltare e' un altro — altrimenti con un numero che resta dispari la stessa squadra
arriverebbe in finale senza giocare.
*Detta il 2026-08-29 · `CompetitionProgress.tabellone`*

**Nella coppa «doppia sfida» vuol dire andata e ritorno, e sono due turni distinti.**
L'interruttore c'era nella schermata e non faceva niente: il generatore leggeva
`twoLeggedKnockout`, che nessuno impostava mai. E le due gare finivano nello stesso turno,
cioe' nella stessa fascia oraria: la stessa squadra in casa e in trasferta allo stesso
istante. La finale resta in gara secca.
*Corretta il 2026-08-29 · `FixtureGenerator.buildKnockoutRound`, `CompetitionRepository.preview`*

**Un campionato si cancella anche a stagione cominciata.**
Se ne vanno partite, risultati, classifica e presenze. **Restano** i premi gia' incassati,
la crescita e il morale: sono cose successe, e disfarle richiederebbe di conoscere lo stato
del mondo prima di ogni partita, che nessuno conserva. La conferma dice quante partite si
portano via, e compare solo se ce n'e' almeno una giocata.
Prima era vietato dalla prima partita in poi — cioe' proprio da quando serve.
*Detta il 2026-08-29 · `delete_competition`, `CompetitionInfo.canDelete`, `ui/Competitions.kt`*

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
*Detta il 2026-08-24 · `core/market/Listing.kt`, `listings` e `buy_player` in `schema.sql`, `MarketRepository`*

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
*Detta il 2026-08-24 · `release_player` in `schema.sql`*

**Il giocatore costruito dal proprietario non se ne va per nessuna strada.**
Non si vende a listino, non si mette all'asta, non si scambia — e non si puo' nemmeno
**chiedere** in uno scambio, o il divieto varrebbe la meta': basterebbe essere l'altro dei
due a scrivere la proposta. Resta possibile prestarlo.
La regola c'era dal principio in `core` e in `list_player`, e mancava in `start_auction` e
in `propose_trade`: due strade su quattro erano aperte, quindi il giocatore unico **si
vendeva davvero**. In piu' l'app mostrava «Metti in vendita» e «Metti all'asta» sulla sua
scheda, e il rifiuto arrivava dal server dopo aver premuto.
*Chiusa il 2026-08-29 · `start_auction`, `propose_trade`, `ui/PlayerDetail.kt`, `ui/screens/Scambi.kt`*

**I propri giocatori in vendita si vedono nel listino, marcati.**
Compaiono in mezzo agli altri con il cartellino del prezzo spento invece che lavanda, e
senza pulsante per comprarli. Prima venivano filtrati via: metterne uno in vendita lo faceva
**sparire** dall'unico posto in cui si guarda il mercato, e da fuori la vendita non
risultava avvenuta. Vedere il proprio prezzo accanto a quelli degli altri e' anche l'unico
modo di accorgersi di averlo messo fuori mercato.
*Detta il 2026-08-29 · `AppState.visible`, `ui/PlayerList.kt`*

**Il prezzo di vendita si scrive con la tastiera, e il consigliato sta scritto.**
C'erano solo un meno e un piu': per mettere 4.000 partendo da 12.000 servivano quaranta
tocchi. Il valore di partenza **era** gia' il consigliato ma non lo diceva, quindi chi lo
cambiava non poteva piu' tornarci. Adesso c'e' scritto «Consigliato: N» e lo si rimette con
un tocco; il consigliato lo calcola `ListingRules.suggestedPrice`, che esisteva e non
chiamava nessuno.
*Detta il 2026-08-29 · `ui/PlayerDetail.kt` (`FoglioPrezzo`)*

**L'admin può aggiungere e togliere giocatori a qualsiasi club, senza registro pubblico.**
Serve a riparare le leghe rotte. Il registro visibile a tutti è stato proposto e scartato:
si regge sulla fiducia del gruppo. Resta vero il motivo per cui era stato proposto —
l'admin è uno dei concorrenti, ed è la ragione per cui gli obiettivi li decide una regola
in `core` e non lui: questo strumento è l'unico punto del gioco dove quella separazione
non c'è, e va tenuto stretto.
*Detta il 2026-08-24 · `admin_assign_player`, `admin_release_player`, `admin_adjust_credits`*

**Lo staff si assume a prezzo fisso, sempre, senza asta.**
Allenatori, preparatori e osservatori hanno un prezzo che dipende dalle stelle — un cinque
stelle costa `economy.staffBudgetShare` del budget, gli altri scendono col quadrato — e chi
non lavora per nessuno si assume con un tocco. Senza finestra di contestazione: un
preparatore in più non ribalta una stagione, e dodici ore d'attesa su ogni assunzione
renderebbero lo staff più faticoso dei giocatori.
Chi vende un proprio membro dello staff lo mette a listino e incassa lui.
*Chiesta all'inizio del progetto — è la richiesta che ha fatto nascere questo file. Il
2026-08-24 era passata dall'asta al listino, ma il listino lo riempiva solo il server:
finché quello non girava restava «Metti all'asta» e basta. Il **2026-08-25** il
proprietario l'ha segnalato di nuovo — «per prendere lo staff si è ancora obbligati a farlo
tramite asta» — e la dipendenza dal server è stata tolta.*
*`core/market/Valuation.staffPrice`, `staff_price`, `buy_staff`, `ui/screens/Staff.kt`*

**Un osservatore sta via al massimo due ore.**
Le fa il peggiore; il migliore mezz'ora. Erano otto ore per un cinque stelle e
**quarantotto** per un una stella, scritte dentro una funzione SQL dove nessuno poteva
vederle: due giorni reali per una singola ricerca, in un gioco che gioca due partite al
giorno. Le stelle continuano a comprare tempo oltre che qualità, ma su una scala che sta
dentro una serata.
*Detta il 2026-08-25 · `rules.scoutMinutesBest/Worst`, `core/world/Scouting.kt`, `send_scout`*

**Chi apre un'asta per comprare ha già offerto il prezzo base.**
L'ha aperta perché lo vuole: parte in testa. Chi invece mette all'asta **un proprio**
giocatore è il venditore e non offre — sarebbe comprare da sé stesso — e se nessuno si fa
avanti resta invenduto.
Prima l'asta nasceva senza nessuno in testa, e succedevano tre cose: scadeva deserta anche
per chi l'aveva aperta, l'app scriveva «nessuno ha ancora offerto» pure sulla propria, e i
crediti di chi apriva non risultavano impegnati — quindi si potevano aprire tre aste che
insieme valevano più della cassa.
*Detta il 2026-08-24 · `core/market/Auction.kt` (`AuctionRules.open`), `start_auction` in
`schema.sql`, `TickRunner.apriAsta`*

**Gli under 20 non passano dalle aste.**
Si trovano mandandoci un osservatore. Un fuoriclasse di diciotto anni non deve poter essere
comprato da chi ha solo più soldi.
*`start_auction` in `schema.sql`*

**Durante l'asta si vede chi ha offerto e quanto ha portato il prezzo. Il massimo
dichiarato resta segreto fino alla chiusura.**
Vederlo prima cancellerebbe la meccanica: si offre quel numero più uno e si vince sempre.
*Confermata il 2026-08-20 · `auction_bids_public` in `schema.sql`, `ui/Auctions.kt`*

**Si dichiara un massimo, non un rilancio.**
Il sistema difende la posizione da solo. È ciò che permette di andare a dormire durante
un'asta invece di controllare il telefono ogni ora.
*`place_bid`, `core/market/AuctionRules.kt`*

---

## Le partite

> Le sei voci che seguono sono state decise **e implementate** il 2026-08-29. Sono un blocco
> solo — cambiano insieme il ritmo di tutto il gioco — e vanno lette insieme.

**La partita dura novanta minuti veri.**
Non piu' quindici secondi di riproduzione accelerata: il minuto di gioco e' un minuto vero,
e non e' un contatore che l'app fa avanzare ma una **funzione dell'ora**. Due telefoni
aperti nello stesso istante vedono lo stesso minuto, e chi arriva al 63' vede il 63'. La
timeline resta simulata una volta sola dal server e riprodotta in locale: il tempo reale non
costa nessuna richiesta in piu'.
A partita finita torna la riproduzione accelerata di sempre, con pausa e salto alla fine:
quello che si rivede e' *come e' andata*, e novanta minuti per raccontarlo sarebbero novanta
minuti. In diretta invece non ci sono comandi — non si mette in pausa una partita.
*Detta il 2026-08-29 · `core/match/MatchClock.kt`, `MatchState`, `AppViewModel.segui`*

**Due tick per partita, con venti minuti di pausa.**
Il primo simula tutto fino al 45'; il secondo parte alla fine dell'intervallo, che dura
**venti minuti e non di piu'**. La ripresa si conta dal fischio d'inizio — `kickoff + 45 +
pausa` — e non da quando il server e' passato: contandola da «adesso» ogni ritardo del tick
si sommerebbe alla partita, e la partita finirebbe a un'ora che nessuno aveva letto.
Puntualita': non serviva niente di nuovo. `pg_cron` chiama `sveglia_il_tick()` ogni cinque
minuti e quello fa `workflow_dispatch` su GitHub. La pausa dura quindi fra venti e
venticinque minuti, e finche' il secondo tempo non e' scritto **il minuto resta al 45'**,
con scritto che si sta aspettando la ripresa: un cronometro che corre sopra un campo di cui
non si sa niente e' peggio di un'attesa dichiarata.
*Detta il 2026-08-29 · `TickRunner.giocaPrimoTempo`, `MatchClock.ripresaDi`, `CalendarConfig.halfTimeWindowMinutes`*

**Il primo tempo si guarda mentre si gioca.**
Per quarantacinque minuti reali `match_results` non esiste ancora — e non deve: e' la riga
che significa «giocata», e scriverla a meta' vorrebbe dire una partita che entra in
classifica all'intervallo. Quindi il primo tempo viaggia dentro `fixtures.first_half`,
accanto agli schieramenti che gia' ci stavano. Il server non lo rilegge mai: serve solo a
chi guarda.
*Detta il 2026-08-29 · `HalfTimeJson.write`, `MatchRepository.load`*

**Fra due partite dello stesso club passano almeno due ore.**
Configurabile (`calendar.minHoursBetweenMatches`), di serie due — appena sopra i 110 minuti
che una partita occupa davvero (45 + 20 + 45). «Se la fai alle 10 non puoi alle 11, alle 12
minimo.» Vale in quattro posti con **una regola sola** in `core`: il risolutore del
calendario, la proposta di amichevole, la risposta dell'AI e il database.
Toglie di mezzo anche un numero scritto a mano: `propose_friendly` aveva tre ore fisse
dentro l'SQL, mentre il calendario non guardava l'orario affatto — due risposte diverse alla
stessa domanda. E il modulo della competizione adesso avvisa **prima** se due fasce orarie
sono troppo vicine, invece di produrre «non c'e' stato spazio per otto turni».
*Detta il 2026-08-29 · `KickoffRules.troppoVicine`, `CalendarSolver.fits`, `propose_friendly`, `respond_deal`*

**Il mondo resta sveglio fino alle 23.**
`ora_riposo` e' passata da 21 a 22 in `sveglia_il_tick()`: l'ultimo giro parte alle 22:55.
Serve al tempo reale — una partita delle 21 finisce alle 22:50, e con la finestra di prima
il suo secondo tempo sarebbe caduto a mondo dormiente, giocato alle 9 del mattino dopo.
**L'ultimo fischio d'inizio sensato resta quindi le 21.** Scartato lo spostare indietro
l'ultimo orario utile alle 20: le nove di sera sono l'ora in cui una lega di amici gioca.
*Detta il 2026-08-29 · `sveglia_il_tick()` in `schema.sql`*

**La stamina si recupera per ore vere, e piu' in fretta.**
Sette punti l'ora (`engine.staminaRecoveryPerHour`) invece di 34 per giornata di gioco: due
ore fra una partita e l'altra ne rendono quattordici, una notte riporta al massimo chiunque.
Sono circa due volte e mezzo il ritmo di prima — «tempi recuperi accelerati, ora troppo
lenti».
Il cambio di unita' conta quanto il numero: una «giornata» e' una fascia oraria del
calendario, quindi valeva dodici ore in una lega con due fasce e sei in una con quattro. Lo
stesso riposo pagava il doppio o la meta' a seconda della configurazione, e non c'era modo
di accorgersene. Il recupero si accredita a ogni giro del server sulle ore passate davvero,
con un tetto di dodici per giro.
*Detta il 2026-08-29 · `StaminaEngine`, `TickRunner.recoverStamina`, `tick_state.last_stamina_at`*

**Il campo si guarda, con la palla che segue l'azione.**
Decorativo e basta: nessun gameplay, nessun tocco, non cambia il risultato. La palla si
muove fra le nove zone che il motore usa gia' per simulare, l'azione pericolosa accende un
alone, il gol fa un'onda, e una barra dice da che parte sta andando la partita negli ultimi
dieci minuti. I dati c'erano tutti — `MatchEvent.zone` viene scritto dal primo giorno e non
lo leggeva nessuno.
*Detta il 2026-08-29 · `ui/pitch/CampoLive.kt`*

**Non segnano solo gli attaccanti.**
Chi conclude non è più chi ha la palla: si sceglie prima **che conclusione è** — tiro da
fuori, in area, di testa, occasione limpida, ripartenza, punizione — e poi chi la prende,
fra tutti e undici, con un peso per ruolo. Un centrale pesa 5,5 sui colpi di testa contro
l'1 di un terzino; un centrocampista pesa 8 sui tiri da fuori contro il 4 di una punta.
E chi attacca i corner non è più sempre lo stesso: era `il miglior stacco della squadra`,
quindi quel giocatore segnava da solo tutti i gol di testa della stagione.
Segnalato così: *«gol solo da quelli forti, dall'attacco e basta»*. Era vero per
costruzione — alle zone d'attacco contribuiscono solo punte, esterni e trequartista, e un
difensore non poteva concludere **mai**, nemmeno su calcio d'angolo.
Misurato dopo: attacco 66%, centrocampo 20%, **difesa 12%**, con venti marcatori diversi su
ventidue in campo.
*Detta il 2026-08-29 · `core/match/Conclusioni.kt`, `MatchEngine.scegliTiratore`*

**La bravura di chi tira conta, ma decide l'occasione.**
Il moltiplicatore del finalizzatore era da 0,55 a 1,95: un fuoriclasse segnava **tre volte
e mezzo** più di un onesto sulla stessa identica occasione, e sommato al fatto che tiravano
solo gli attaccanti spiegava da solo la frase sopra. Ora è da 0,97 a 1,40. Il divario
resta e si vede sulla stagione, ma non cancella più la partita singola — che è come
funziona il calcio.
*Detta il 2026-08-29 · `EngineConfig.finishingMin/Max`*

**Esistono il fuorigioco e i tiri murati.**
Non c'erano affatto, nemmeno come evento. Nel calcio vero sono quattro fuorigioco a partita
e un quarto delle conclusioni murate: senza, ogni tiro era gol, parata o fuori, e il
portiere risultava impegnato il doppio del vero.
*Detta il 2026-08-29 · `MatchEventType.FUORIGIOCO`, `TIRO_MURATO`*

**Le azioni le decidono i duelli fra due giocatori, non la media di due reparti.**
*Decisa e implementata il 2026-08-29.*
Oggi un'azione è un numero contro un numero: `rating(zona) − rating(zona specchiata)` dentro
una sigmoide. `ZoneRatings` schiaccia gli undici in una media **prima** che succeda
qualcosa, e i nomi arrivano dopo, su un esito già deciso. Conseguenza verificata: nessuno
dei dodici attributi decide mai un episodio — `DRIBBLING`, `VELOCITÀ`, `DIFESA` e
`INTERCETTAZIONE` entrano soltanto dentro la media di zona — e due giocatori con lo stesso
overall giocano la stessa identica partita. Detta così: *«i giocatori sono numeri, non
persone»*.
Si riscrive su **cinque contese**, ognuna con la sua decisività: corsa (velocità quasi
decisiva), dribbling, contrasto (dove contano posizione, rimbalzo e arbitro), duello aereo,
passaggio. *«Nella corsa chi è più veloce ci arriva prima, punto; nel contrasto e nel
dribbling conta di più il caso»* — quindi cinque manopole da tarare, non una.
Le zone restano come **geografia** e come anagrafe di chi c'è: servono a estrarre
l'avversario. Il livello del tiro — `Conclusioni`, misurato oggi a 66/20/12 con venti
marcatori — **non si tocca**: i duelli riguardano come si arriva al tiro.
Scelta contro il consiglio di innestare i duelli dentro l'avanzamento esistente, sapendo
che il costo è rifare la taratura. Difesa: la banda misurata è diventata un test che gira
su **tutti e due** i motori, e il motore nuovo è nato dietro un interruttore spento.
Misurato a taratura finita, 75 contro 75: **46,0% casa · 28,4% pari · 25,6% ospite · 2,89
gol · 27,7 tiri · 10,4% di conversione**, con attacco 64,6% / centrocampo 21,4% / difesa
13,9% e venti marcatori diversi. E le tre misure che prima non esistevano: 266 duelli, 16,4
dribbling riusciti su 37 tentati, 77,2% di precisione nei passaggi.
*Detta il 2026-08-29 · `core/match/Duelli.kt`, `Intenzioni.kt`, `MatchEngine.risolviDuello`
· progetto: [`superpowers/specs/2026-08-29-duelli-in-campo-design.md`](superpowers/specs/2026-08-29-duelli-in-campo-design.md)*

**La palla usa tutte e nove le zone, non solo la colonna centrale.**
`Zone.advance()` conservava la corsia, `Zone.mirror()` manda il centro nel centro, e ogni
ripartenza è centrale: la palla nasceva in `MID_C` e non ne usciva mai più. Il modello a
nove zone era, in esercizio, un modello a tre — **sei zone su nove restavano vuote per
tutta la partita**.
Si vedeva misurando i falli: in cento partite li commettevano solo attaccanti, centrali e
mediani. Nessun terzino, nessuna ala, mai — non perché scarsi, ma perché non toccavano il
pallone. E la larghezza tattica moltiplicava i fattori di corsie in cui non passava
nessuno, quindi «stretto» e «largo» erano la stessa impostazione.
*Corretta il 2026-08-29 · `MatchEngine.avanza`, `prossimaCorsia`, `engine.pesoStessaCorsia`*

**Tre tratti che promettevano e non mantenevano.**
- **Incostante** — *«un giorno domina, quello dopo sparisce»*: adesso esiste la **giornata**,
  un tiro di dado a inizio partita valido per tutti i novanta minuti. Un giocatore normale
  oscilla di due o tre punti, un incostante fino a nove.
- **Leader** — *«trascina la squadra»*: valeva solo con la fascia, perché l'unica spinta
  esistente passava dal capitano. Adesso contano tutti i trascinatori in campo, quando si è
  sotto dal 75'.
- **Testa calda** — *«colleziona cartellini»*: ne prendeva quanti chiunque altro. Adesso
  commette più falli e li paga di più.
*Corretti il 2026-08-29 · `core/match/Carattere.kt`, `Trait.foulFactor/cardFactor/rimontaBonus`*

**Il tabellino dice cosa ha fatto un difensore.**
Duelli vinti e persi, dribbling riusciti e subiti, precisione dei passaggi. Prima, di un
centrale il foglio diceva soltanto quanti cartellini aveva preso: un grande centrale e uno
scarso producevano lo stesso identico tabellino.
Viaggiano dentro `match_results.player_stats`, che è già `jsonb`: sei chiavi in più in un
JSON non sono sei colonne in più. **Non** si tocca `appearances`, che l'app legge con una
`select` a lista esplicita.
*Detta il 2026-08-29 · `PlayerMatchStats`, `MatchJson.playerStats`, `ui/screens/Partita.kt`*

**La profondità si ferma dentro i novanta minuti.**
*Decisa il 2026-08-29.*
Nessun intervento in diretta: niente cambi manuali mentre si gioca, niente istruzioni al
volo. Con partite di novanta minuti veri, chi può stare attaccato al telefono batterebbe
chi lavora — e le migliaia di partite fra squadre del computer resterebbero comunque
automatiche, cioè povere. Chi prepara bene gioca bene.
Restano gli ordini condizionali, che sono già completi in `core` e servono esattamente a
dare voce a chi alle 21 non c'è.



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
*Confermata il 2026-08-24 · `WorldTick.halfTimesDue`, `TickRunner.giocaPrimoTempo`, `fixtures.resume_at`*

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
`lineups.corner_taker_id` e sorelle, `ui/screens/Campo.kt`*

**Con chi si è appena parlato non si riparla per tre giornate. Nemmeno se ha ragione.**
L'attesa esisteva già per la convocazione a mano — un manager deve poter parlare a chi
vuole, ma non ogni cinque minuti, o sarebbe di nuovo il pulsante «alza morale» con un altro
nome. Non valeva per i colloqui che nascono da soli: il dato c'era, il server lo calcolava
e lo passava, e la regola non lo leggeva.
Si vedeva nel registro del server: *«40 colloqui aperti nello spogliatoio, 40 colloqui
gestiti dai club del computer»*, a ogni giro, in ogni lega. Il morale di ogni giocatore del
computer si spostava ogni cinque minuti per una conversazione che non era mai successa.
Vale **anche per la promessa tradita**, che è la cosa più urgente che possa capitare in uno
spogliatoio: urgente vuol dire *subito*, non *di nuovo fra cinque minuti*.
*Corretta il 2026-08-26 · `LeagueFacts.trigger`, `ATTESA_FRA_CONVOCAZIONI`*

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

**Un'amichevole si accetta per cortesia, non per carattere.**
Chiedere resta una scelta — i temerari ne cercano piu' dei prudenti — ma **accettare** no:
si rifiuta solo con le gambe davvero a terra (stamina media sotto 55) o con una partita
vera lo stesso giorno. E il rifiuto dice il motivo vero.
Erano la stessa identica regola, e quasi ogni amichevole veniva rifiutata. Tre condizioni
pensate per decidere se *proporre* una partita, girate, diventavano tre motivi per dire di
no a un amico: due giornate libere davanti — impossibile con un campionato in corso, quindi
falso **sempre**; la rosa sotto il minimo di diciotto, cioe' per giorni; e
`marketAggression > 0.45`, che escludeva un quarto dei club **per sempre**. In piu' il
conto delle giornate era sbagliato in tutti e due i sensi: chi rispondeva riceveva la
giornata assoluta meno quella corrente con un ripiego che invecchiava, e chi chiedeva
riceveva la giornata assoluta e basta.
*Detta il 2026-08-29 · `AiInitiative.friendlyRefusal`, `TickRunner.giornateAllaProssimaPartita`*

**Le AI comprano al prezzo che l'app consiglia a chi vende. E un affare lo prendono comunque.**
Segnalato come «nessuno li acquista, anche se non forti o molto poco costosi e di grande
qualita' prezzo». Non era il ritmo: era che il gradimento veniva letto sulla **curva dei
prezzi** — esponente 7,5, dove un settantasette vale 0,068 su 1 — e quel numero moltiplicava
anche il tetto di spesa. Il tetto stava cinque volte sotto il prezzo consigliato: nessuna
AI poteva comprare niente da nessuno nemmeno volendo.
Adesso il gradimento sta su scala lineare (`Valuation.qualityLevel`), e il prezzo e' un
criterio **separato** dal gusto: sotto `ai.bargainShare` del valore stimato un'AI compra
anche cio' che non cercava, perche' un affare lo prende chiunque faccia mercato sul serio.
Il tetto per casella resta dov'era e continua a impedire che un club si rovini.
*Corretta il 2026-08-29 · `AiManager.qualityAppeal`, `AiManager.voglia`, `AiMarket.playerToBuy`*

**Restano scaglionate.**
«Più reattive» non deve mai voler dire venticinque notifiche in due secondi: l'anti-sciame
di `AiScheduler` e i tetti di azioni giornaliere non si toccano. È il difetto che quel file
esiste per impedire.
*Confermata il 2026-08-24*

**Una squadra del computer con la rosa incompleta compra in fretta. Con la rosa completa,
piano.**
Misurato dal proprietario: dopo mezza giornata reale, cinque club su dieci avevano qualche
giocatore e nessuno ne aveva più di tre. Lo scaglionamento serve a proteggere chi gioca dal
rumore, ma comprare uno svincolato a prezzo di listino **non è un evento per nessuno** —
non c'è un venditore da avvisare, non c'è nessuno che viene superato. Sotto il minimo di
rosa quindi fa fino a otto mosse per risveglio; sopra torna a una, dove ogni mossa è una
notifica sul telefono di qualcuno.
*Detta il 2026-08-25 · `AiTurn.movesPerWake`*

**Anche le squadre del computer schierano, e la loro formazione si vede.**
«Non schierano o fanno nessuna tattica o scelta tecnica» era vero alla lettera: il server
gli costruiva un undici un istante prima del fischio d'inizio, con l'assetto predefinito
uguale per tutti e dieci, e lo buttava un istante dopo. Adesso ogni club del computer
**salva** modulo, undici, panchina, assetto e i cinque incarichi, e li riscrive a ogni
risveglio perché la rosa cambia. Aprire il campo di un avversario deve mostrare qualcosa.
*Detta il 2026-08-25 · `core/ai/AiTactics.kt`, `TickRunner.schieraLAi`*

**L'assetto lo decide la rosa, poi la stanchezza, poi il carattere — in quest'ordine.**
Chi ha l'attacco più forte della difesa gioca in avanti; chi ha la rosa a terra rallenta
comunque, qualunque carattere abbia; le fissazioni inclinano e basta. Se il carattere
pesasse più della rosa si otterrebbero club che attaccano senza attaccanti, che è il modo
in cui un'AI smette di sembrare una persona.
*Detta il 2026-08-25 · `AiTactics.choose`*

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

**L'interruttore Prima squadra / Primavera vale su tutta la sezione Squadra.**
Rosa, campo, staff, spogliatoio, infermeria: se è su Primavera, si vedono i ragazzi e non i
titolari. Spogliatoio e infermeria leggevano sempre la prima squadra qualunque cosa dicesse
l'interruttore — e un comando che smette di funzionare a metà strada è peggio di un comando
che non c'è.
*Detta il 2026-08-25 · `AppState.clubMostrato`*

**Niente didascalie che spiegano l'ovvio in cima agli elenchi.**
Tolte quelle che dicevano «991 si comprano subito · nessuna attesa, nessuna asta», «1115 da
prendere · 14 hanno già un club», «tocca un giocatore per la sua scheda», «comprati da poco
· si possono ancora contestare». Sopravvive solo il conto delle aste, perché «quante hanno
una tua offerta» non è deducibile guardando l'elenco.
Il criterio: un'intestazione resta se aggiunge un fatto, non se ripete quello che c'è
sotto. Una spiegazione utile il primo giorno diventa rumore il secondo, su una schermata
che si riapre venti volte al giorno.
*Detta il 2026-08-25 · `ui/PlayerList.kt`, `ui/screens/Rosa.kt`, `ui/Auctions.kt`*

**Nel listone ogni giocatore dice di chi è, su una riga sua e col nome per intero.**
«di Matletico Mangao», sotto l'età. Era in coda ai dati anagrafici e abbreviato, e
appiccicato dopo l'età si legge come un altro dato del giocatore invece che come il suo
proprietario.
*Detta il 2026-08-25 · `ui/PlayerList.kt`*

**E lo dice con un riquadro nei colori della squadra, con lo stemma.**
Non piu' una riga di testo grigia: una targhetta col fondo del colore della maglia, lo
stemma e il nome del club per intero. In grigio chiaro sotto l'eta' si leggeva ancora come
l'ultimo dei dati anagrafici, mentre e' la prima cosa che si cerca scorrendo. Colorata si
riconosce senza leggerla.
**Chi non è di nessuno non ha nessun riquadro:** uno svincolato non ha un proprietario
vuoto, non ha proprio un proprietario. L'inchiostro lo decide la luminanza del fondo, o
meta' delle maglie darebbe targhette illeggibili.
*Detta il 2026-08-29 · `ui/PlayerList.kt` (`TargaProprietario`)*

**Chi compra uno svincolato lo vede sparire dagli svincolati nello stesso istante.**
Non dopo la rilettura dal server: subito. Il server ha già risposto sì, quindi il contratto
esiste; aspettare qualche secondo su una rete lenta significa vederlo ancora fra quelli da
prendere, toccarlo di nuovo, e ricevere un rifiuto che sembra un guasto.
*Detta il 2026-08-25 · `AppViewModel.compra`*

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
