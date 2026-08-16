# Setup di MFoot

Da fare una volta sola. Serve un account Supabase (gratis, senza carta) e un repository
GitHub **pubblico** — quest'ultimo è la condizione per avere minuti GitHub Actions
illimitati, che è ciò che rende il tick gratuito.

---

## 1. Creare le tabelle

Su Supabase → **SQL Editor** → **New query** → incolla tutto il contenuto di
[`supabase/migrations/0001_schema.sql`](../supabase/migrations/0001_schema.sql) → **Run**.

Deve finire senza errori. Se ne trovi, di solito è perché lo script è stato eseguito a
metà: cancella le tabelle create e rifallo da capo.

---

## 2. Trovare le credenziali del database

Nella dashboard del progetto, in alto, c'è il pulsante **Connect**.

### ⚠️ Scegli "Session pooler", non "Direct connection"

I progetti Supabase nuovi espongono la connessione diretta **solo su IPv6**, mentre i
runner di GitHub Actions sono IPv4. Usando la diretta il tick fallirebbe con un errore di
rete poco comprensibile.

Evita anche il **Transaction pooler** (porta 6543): non gestisce bene i prepared statement
di JDBC, che il tick usa.

### Imposta i due menù così

| Menù | Valore |
|---|---|
| **Connection Method** | `Session pooler` |
| **Type** | `JDBC` |

Sotto le due tendine compare il riquadro **Connection parameters**, con `host`, `port`,
`database` e `user` già pronti da copiare. Sono quelli che servono.

### ⚠️ Non mettere la password dentro l'URL

Supabase stesso avvisa che *"if your database password contains special characters,
percent-encode them in the connection string"*. Se la password contiene `@ : / ? & #` o
simili, infilarla nell'URL richiede di codificarla — ed è l'ennesima occasione per
sbagliare, con un errore di autenticazione che non dice cosa è successo.

Meglio tenerla in un segreto a parte: così si incolla grezza e non serve nessuna codifica.

### I tre valori

| Segreto | Come si compone |
|---|---|
| `MFOOT_DB_URL` | `jdbc:postgresql://` + `host` + `:` + `port` + `/` + `database`. Viene una riga che finisce con `.pooler.supabase.com:5432/postgres` |
| `MFOOT_DB_USER` | Il campo **user** del riquadro. Comincia con `postgres.` seguito dal codice del progetto: **non** è il semplice `postgres`. |
| `MFOOT_DB_PASSWORD` | La password del database, così com'è. Se non la ricordi, il pulsante *Reset database password* è lì accanto. |

---

## 3. Impostare i segreti su GitHub

**Settings → Secrets and variables → Actions → New repository secret.**

| Segreto | Quando serve |
|---|---|
| `MFOOT_DB_URL` | Sempre |
| `MFOOT_DB_USER` | Sempre, salvo che tu abbia scelto di mettere le credenziali dentro l'URL |
| `MFOOT_DB_PASSWORD` | Come sopra |
| `MFOOT_TELEGRAM_TOKEN` | Facoltativo, per le notifiche |
| `MFOOT_TELEGRAM_CHAT` | Facoltativo, per le notifiche |

Il tick accetta entrambe le forme e controlla da solo la coerenza: se l'URL non porta le
credenziali e mancano anche i segreti separati, si ferma dicendo cosa manca. E se hai
lasciato il segnaposto `[YOUR-PASSWORD]` nell'URL, te lo dice invece di fallire con un
errore di autenticazione incomprensibile.

> I *secrets* restano privati anche su un repository pubblico, e GitHub li oscura nei log.
> È il motivo per cui il tick legge tutto da variabili d'ambiente e non ha nessun valore
> scritto nel codice.

---

## 4. Provare che il tubo funzioni

**Actions → World Tick → Run workflow**, spunta *"Calcola il piano senza scrivere niente"*
e lancia.

Con il database ancora vuoto la risposta corretta è:

```
MFoot World Tick - avvio ...
MODALITA' DI PROVA: nessuna scrittura sul database.
Nessuna lega attiva.
Terminato in ... ms
```

Se leggi questo, GitHub parla con Supabase e l'infrastruttura è in piedi.

### Se qualcosa non va

| Errore | Causa quasi certa |
|---|---|
| `Variabile d'ambiente MFOOT_DB_URL mancante` | Il secret non è stato creato, o ha un nome diverso |
| `Connection refused` / timeout | Stai usando la *Direct connection* invece del *Session pooler* |
| `password authentication failed` | Utente sbagliato: con il pooler serve `postgres.<codice-progetto>`, non `postgres` |
| `relation "leagues" does not exist` | Lo schema del punto 1 non è stato eseguito |

---

---

## 5. Collegare l'app Android al database

L'app funziona anche senza: genera un mondo tutto suo a ogni avvio, il che va benissimo
per provare le schermate. Ma per salvare una lega vera servono due valori.

Apri `local.properties` nella cartella del progetto — **git lo ignora**, quindi quello che
ci scrivi non finisce mai nel repository pubblico — e aggiungi due righe:

```properties
supabase.url=https://abcdefghijkl.supabase.co
supabase.key=sb_publishable_xxxxxxxxxxxxxxxxxxxxxxxx
```

| Valore | Dove si trova |
|---|---|
| `supabase.url` | Project Settings → **Data API** → *Project URL* |
| `supabase.key` | Project Settings → **API Keys** → *Publishable key* |

⚠️ **Sostituisci davvero i valori.** Copiare la riga d'esempio così com'è è l'errore più
frequente: l'app se ne accorge e mostra `url da compilare` invece di un generico errore,
ma è comunque mezz'ora persa.

### Perché la chiave sta qui e non nel codice

La chiave **publishable** non è un segreto come una password: Supabase stessa scrive che
*"publishable keys can be safely shared publicly"*, e la difesa vera dei dati sono le Row
Level Security che lo schema ha già impostato.

Ma il repository è **pubblico**. Lasciarla dentro significherebbe che chiunque può
bersagliare il progetto e consumarne i limiti del piano gratuito. Tenerla fuori costa una
riga.

### Verifica

Ricompila e riavvia l'app: in alto a destra, accanto al conteggio dei giocatori, c'è un
pallino.

| Pallino | Significato |
|---|---|
| 🟡 `non collegato` | Le due righe non ci sono, o sono vuote |
| 🟢 `collegato` | URL e chiave funzionano, le tabelle rispondono |
| 🟡 `errore` | Chiave rifiutata, oppure lo schema SQL non è stato eseguito |

---

## 6. Creare una lega vera

Due passaggi, entrambi da fare una volta sola.

### 6a. Attivare l'accesso anonimo

Su Supabase: **Authentication → Sign In / Providers → Anonymous sign-ins → attiva**.

Senza, l'app non può identificare chi crea la lega e le funzioni la rifiutano. È la scelta
che permette ai tuoi amici di entrare scrivendo solo un nickname, senza email né password.

### 6b. Eseguire la seconda migrazione

SQL Editor → incolla
[`supabase/migrations/0002_create_league.sql`](../supabase/migrations/0002_create_league.sql)
→ Run. Anche questo è rieseguibile.

Aggiunge due funzioni: `create_league`, che crea la lega e carica l'intero mondo in
**un'unica transazione**, e `join_league`, per entrare con il codice.

> **Perché una funzione e non degli insert.** Creare una lega significa scrivere in sei
> tabelle. Farlo con sei chiamate dal telefono vuol dire che una connessione persa a metà
> lascia una lega monca — con i giocatori ma senza club — che nessuno saprà come
> sistemare. Con una funzione sola: o passa tutto o non passa niente. E le Row Level
> Security restano chiuse in scrittura, perché è la funzione ad avere il permesso, non il
> client.

### Verifica

Nell'app, il pulsante **"Crea la lega e carica il mondo"**. Deve rispondere con il numero
della lega e **quanti giocatori sono arrivati davvero sul database** — non un generico
"fatto", perché un 200 dice solo che la chiamata è passata, non che il carico sia
completo.

---

## Note

- Il cron di GitHub **non è puntuale** e ogni tanto salta un giro. Non è un problema: il
  tick recupera da solo gli intervalli persi.
- GitHub **disattiva i workflow programmati** dopo circa 60 giorni senza attività sul
  repository. Si riattivano con un click dalla tab Actions.
