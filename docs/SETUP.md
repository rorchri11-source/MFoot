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

Con `Type: JDBC` Supabase produce una stringa già pronta, che contiene anche utente e
password come parametri. Non c'è niente da ricomporre a mano — ed è il passaggio in cui è
più facile sbagliare.

La stringa ha questa forma:

```
jdbc:postgresql://aws-0-eu-central-1.pooler.supabase.com:5432/postgres?user=postgres.abcdefgh&password=[YOUR-PASSWORD]
```

**Sostituisci `[YOUR-PASSWORD]` con la password vera del database.** Se non la ricordi:
Project Settings → Database → *Reset database password*.

Se la stringa è completa così, ti serve **un solo segreto** e non tre.

---

## 3. Impostare i segreti su GitHub

**Settings → Secrets and variables → Actions → New repository secret.**

| Segreto | Quando serve |
|---|---|
| `MFOOT_DB_URL` | **Sempre.** La stringa JDBC del punto precedente, con la password già sostituita. |
| `MFOOT_DB_USER` | Solo se la stringa **non** contiene `user=` — cioè se hai scelto `Type: URI` invece di `JDBC`. Con il pooler è `postgres.<codice-progetto>`, non il semplice `postgres`. |
| `MFOOT_DB_PASSWORD` | Come sopra. |
| `MFOOT_TELEGRAM_TOKEN` | Facoltativo, per le notifiche. |
| `MFOOT_TELEGRAM_CHAT` | Facoltativo, per le notifiche. |

Il tick controlla da solo la coerenza: se l'URL non porta le credenziali e mancano anche i
segreti separati, si ferma dicendo esattamente cosa manca. E se ti sei dimenticato di
sostituire `[YOUR-PASSWORD]`, te lo dice invece di fallire con un incomprensibile errore
di autenticazione.

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

## Note

- Il cron di GitHub **non è puntuale** e ogni tanto salta un giro. Non è un problema: il
  tick recupera da solo gli intervalli persi.
- GitHub **disattiva i workflow programmati** dopo circa 60 giorni senza attività sul
  repository. Si riattivano con un click dalla tab Actions.
