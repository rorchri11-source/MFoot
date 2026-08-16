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

### Da dove si ricavano i tre valori

La stringa che vedi ha questa forma:

```
postgresql://postgres.abcdefghijkl:[YOUR-PASSWORD]@aws-0-eu-central-1.pooler.supabase.com:5432/postgres
             └────────┬─────────┘  └──────┬─────┘ └──────────────┬────────────────────────────────┘
                   UTENTE              PASSWORD                   HOST : PORTA / DATABASE
```

| Segreto | Come si ottiene |
|---|---|
| `MFOOT_DB_USER` | La parte fra `://` e i due punti, es. `postgres.abcdefghijkl`. **Non** è solo `postgres`: con il pooler l'utente include il codice del progetto. |
| `MFOOT_DB_PASSWORD` | La password scelta creando il progetto. Se non la ricordi: Project Settings → Database → *Reset database password*. |
| `MFOOT_DB_URL` | Tutto quello che sta **dopo la chiocciola**, con davanti `jdbc:postgresql://` |

Nell'esempio sopra `MFOOT_DB_URL` sarebbe:

```
jdbc:postgresql://aws-0-eu-central-1.pooler.supabase.com:5432/postgres
```

---

## 3. Impostare i segreti su GitHub

**Settings → Secrets and variables → Actions → New repository secret.**

Obbligatori: `MFOOT_DB_URL`, `MFOOT_DB_USER`, `MFOOT_DB_PASSWORD`.

Opzionali, per le notifiche Telegram: `MFOOT_TELEGRAM_TOKEN`, `MFOOT_TELEGRAM_CHAT`.

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
