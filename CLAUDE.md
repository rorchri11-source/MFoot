# MFoot

Gioco manageriale di calcio multiplayer asincrono per un gruppo privato di amici.
Kotlin: `core` (motore, zero dipendenze di piattaforma), `tick` (il server, su GitHub
Actions), `android` (l'app Compose), `supabase` (schema, RLS, funzioni).

## Prima di toccare qualsiasi cosa

**Leggi [`docs/REGOLE.md`](docs/REGOLE.md).**

Contiene le decisioni di gioco prese dal proprietario della lega: quello che il codice non
può dedurre da solo e che, non stando scritto da nessuna parte, si è già perso fra una
sessione e l'altra. Se una riga di codice contraddice una voce di quel file, è il codice a
sbagliare.

Quando il proprietario decide qualcosa di nuovo, **la voce si aggiunge lì nella stessa
sessione**, con la data. Anche se la cosa non viene implementata subito: una regola
dimenticata è peggio di una regola in attesa.

## Gli altri documenti

- [`STATO.md`](STATO.md) — cosa è fatto, cosa manca, i difetti trovati e perché
- [`README.md`](README.md) — struttura del progetto e i due principi
- [`docs/SETUP.md`](docs/SETUP.md) — credenziali Supabase e primo avvio

## Come si verifica

```bash
gradlew :core:test
```

```bash
gradlew :android:assembleDebug
```

`core:test` deve essere **verde prima e dopo** ogni modifica. Sono già stati consegnati
difetti veri con tre test rossi in repository che nessuno rieseguiva: le partite venivano
rigiocate a ogni giro del server e la prova lo diceva.

## Due regole di struttura, che non si negoziano

**Ogni *regola* di gioco vive in `core`**, con i suoi test, e viene usata identica
dall'app e dal server. Riscriverla in SQL o dentro una schermata significa due regolamenti
che si separano al primo ritocco.

**Nessun numero di gioco scritto nel codice.** Ogni parametro sta in `LeagueConfig` ed è
deciso dall'admin. Vale anche per i premi: si esprimono in percentuale del budget, così
seguono l'economia della lega da soli.

## Il database è un file solo

[`supabase/schema.sql`](supabase/schema.sql): tabelle, indici, permessi, funzioni. Si
incolla nell'SQL Editor di Supabase e si esegue, ed è rieseguibile senza danni.

Erano trentuno migrazioni numerate fino al 2026-08-25. Unificate su decisione del
proprietario, perché contenevano quattro versioni di `start_auction` e tre di `place_bid`:
per sapere cosa faceva una funzione bisognava leggerle tutte in ordine.

**Va eseguito prima di installare un APK che si aspetta qualcosa di nuovo.** Il motivo è la
trappola qui sotto.

## Due trappole già pagate

**PostgREST rifiuta l'intera query per una colonna che non esiste.** Non la riga, non il
campo: tutta la SELECT. Quindi una colonna nuova infilata in una lettura condivisa rende
l'app inservibile ovunque lo schema sia indietro — non una schermata, tutto. Le colonne
nuove si chiedono **in una lettura a parte**, che al peggio fallisce da sola. È già
successo con `clubs.division_level` e con `clubs.parent_club_id`.

**PostgREST tronca ogni risposta a mille righe e restituisce comunque un 200.** Chi legge
una tabella che può superarle deve chiederla a pagine con l'intestazione `Range`, o perde
dati in silenzio. È già successo con `players` (giovani che sparivano) e con `contracts`
(giocatori che risultavano svincolati pur avendo un club).
