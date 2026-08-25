# Guida di Ripristino, Rollback e Diagnostica — MFoot

Questa guida contiene tutte le istruzioni pratiche per **tornare indietro** (rollback) a qualsiasi versione precedente, annullare modifiche specifiche, diagnosticare problemi operativi (GitHub Actions, Supabase, Android) e correggere rapidamente eventuali errori.

---

## 1. Come tornare indietro con Git

Tutti i commit sono pubblicati sul repository remoto `origin/main`. Se devi annullare una modifica o ripristinare uno stato precedente, segui queste indicazioni.

### Registro dei punti di ripristino (Ultimi Commit)

| Hash Breve | Descrizione | Cosa contiene |
|---|---|---|
| `6b0a782` | **Stato attuale** (Doc tick) | Aggiornamento documentazione `STATO.md` |
| `98e630c` | **Tick a 10m + Errori visibili** | Cron a 10 min, exit code 2 se una lega fallisce |
| `7e76d9a` | **Package Android** | Nome package `com.christianrocco.mfoot` |
| `a5e094e` | **Auto-refresh UI** | Rilettura leggera ogni 30s in Compose senza sfarfallio |
| `0382d25` | **Obiettivi & Aste** | Traguardi ogni 5 overall, storico offerte pubbliche |
| `d2c03c5` | **Anteprima & Serie A** | `peek_league` (nome prima di entrare), umani in Serie A |
| `a8e3a25` | **Primavera & ID Lega** | Fix caricamento seconda squadra e nome lega visibile |
| `d9cec3c` | **Punto di partenza iniziale** | Versione precedente al pacchetto di 15 commit |

---

### Procedure di Rollback Git

#### Opzione A: Annullare l'ultimo commit in modo pulito e sicuro (`git revert`)
*Consigliato per repository pubblici, crea un nuovo commit che disfa le modifiche senza riscrivere la storia.*

```bash
# Annulla l'ultimo commit:
git revert HEAD

# Spingi la modifica su GitHub:
git push origin main
```

Per annullare uno specifico commit del passato (es. `98e630c`):
```bash
git revert 98e630c
git push origin main
```

#### Opzione B: Tornare a una versione precedente esatta (Hard Reset)
*Attenzione: riscrive la storia locale. Da usare solo se si vuole cancellare completamente il lavoro successivo a un certo commit.*

```bash
# Esempio: torna esattamente a prima delle modifiche del tick (commit 7e76d9a)
git reset --hard 7e76d9a

# Per allineare anche GitHub (richiede force push):
git push origin main --force
```

#### Opzione C: Recuperare solo un singolo file da una versione precedente
Se hai modificato o rotto un file specifico (es. `.github/workflows/world-tick.yml`) e vuoi ripristinarlo come era prima:
```bash
git checkout 7e76d9a -- .github/workflows/world-tick.yml
git commit -m "Ripristinato world-tick.yml alla versione precedente"
git push origin main
```

---

## 2. Come verificare e diagnosticare il World Tick (Server)

Il tick server gira su **GitHub Actions** ogni 10 minuti tramite `.github/workflows/world-tick.yml`.

### A. Monitorare lo stato
1. Apri il repository su GitHub → scheda **Actions** → seleziona **World Tick**.
2. **Se è Verde 🟢**: il giro è riuscito e tutte le leghe attive sono state elaborate senza errori.
3. **Se è Rosso 🔴**: una o più leghe hanno riscontrato un'eccezione (es. vincolo DB, dato non trovato). Apri il log del job e cerca la parola `FALLITA` o lo stacktrace dell'eccezione.
4. **Se è Annullato ⚪ (Cancelled)**: significa che due job si sono accavallati. Con la cadenza a 10 minuti (`*/10 * * * *`) questo non dovrebbe più accadere normalmente.

### B. Eseguire un Tick manuale di prova (Senza scrivere su DB)
Per testare se il server e il DB comunicano senza alterare i dati di gioco:
1. Vai su **Actions** → **World Tick** → pulsante **Run workflow**.
2. Seleziona la casella *"Calcola il piano senza scrivere niente (dry run)"*.
3. Clicca su **Run workflow** e controlla l'output.

### C. Se il Tick dà errore di connessione al Database
Verifica in **Settings → Secrets and variables → Actions** che siano definiti:
- `MFOOT_DB_URL`: `jdbc:postgresql://<host>:5432/postgres` (usando il *Session Pooler* di Supabase).
- `MFOOT_DB_USER`: `postgres.<codice-progetto>`
- `MFOOT_DB_PASSWORD`: password del database Supabase.

---

## 3. Come gestire lo schema SQL

Il database è **un file solo**: `supabase/schema.sql`. Contiene tabelle, indici, permessi e
funzioni. Erano trentuno migrazioni numerate fino al 2026-08-25.

### Regole di Sicurezza:
1. **Idempotenza**: è scritto con `create table if not exists`, `create or replace function`
   e `drop policy if exists`, quindi si può rieseguire in sicurezza nell'SQL Editor di
   Supabase. Rilanciarlo aggiorna le funzioni e non tocca i dati.
2. **Quello che non fa**: `create table if not exists` su una tabella che esiste già non
   aggiunge colonne. Chi arriva da uno schema più vecchio deve svuotare e ripartire.
3. **Come annullare una funzione specifica**:
   ```sql
   DROP FUNCTION IF EXISTS peek_league(text);
   ```
4. **Se una tabella o colonna è bloccata**:
   Puoi ispezionare lo stato dei dati e dei vincoli direttamente dal **Table Editor** di Supabase.

---

## 4. Come diagnosticare e verificare l'App Android

### A. Test e Compilazione Locale
Prima di distribuire l'app, esegui sempre i test e la compilazione:
```bash
# Esegue tutti i test di logica di gioco (core) e del server (tick):
./gradlew :core:test :tick:test

# Compila l'APK di debug:
./gradlew :android:assembleDebug
```
L'APK generato si troverà in:
`android/build/outputs/apk/debug/android-debug.apk`

### B. Pallino di stato in alto a destra nell'App
- 🟢 **Verde (`collegato`)**: `supabase.url` e `supabase.key` in `local.properties` sono validi e il database risponde.
- 🟡 **Giallo (`non collegato` o `errore`)**: Controlla il file `local.properties` nella radice del progetto:
  ```properties
  supabase.url=https://<tuo-progetto>.supabase.co
  supabase.key=<tua-chiave-anon-public>
  ```

### C. Debug in tempo reale con Logcat
Se l'app si chiude in modo anomalo o non mostra un dato atteso:
```bash
adb logcat -s "MFoot" "AndroidRuntime"
```

---

## 5. Riepilogo Comandi Rapidi di Emergenza

| Situazione | Comando da eseguire |
|---|---|
| **Voglio annullare l'ultimo commit** | `git revert HEAD && git push origin main` |
| **Voglio verificare se il codice è integro** | `./gradlew :core:test :tick:test` |
| **Voglio ricompilare l'app Android** | `./gradlew :android:assembleDebug` |
| **Voglio ripristinare il tick a 5 minuti** | Modifica `world-tick.yml` -> `cron: '*/5 * * * *'` |
| **Voglio vedere lo storico completo** | `git log --oneline -n 20` |
