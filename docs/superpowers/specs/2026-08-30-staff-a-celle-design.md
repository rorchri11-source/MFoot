# Lo staff a celle, e il negozio

*Progetto del 2026-08-30 — area A di cinque. Le altre: B scouting, C notifiche, D partite,
E reattività delle AI.*

## Il problema, misurato leggendo il codice

**Non puoi possedere due membri dello stesso ruolo.** `assign_staff` fa questo:

```sql
update staff set club_id = null
where club_id = p_club_id and role = v_staff.role and id <> p_staff_id;
```

Assegnarne uno nuovo **libera** il vecchio, che torna sul mercato per chiunque. Quello che
il proprietario ha chiesto — *«cella uno hai tre preparatori che hai comprato, inserisci il
terzo, seconda cella selezioni il primo»* — oggi è impossibile per costruzione, non per una
svista di interfaccia.

**Il mercato delle stelle finisce subito perché ci sono due stelle.** Il mondo genera
`STAFF_PER_CLUB = 2.0` per ruolo per club, con pesi `20/28/30/16/6`. Con sedici squadre
sono 32 allenatori, di cui il 6% da cinque stelle: **due in tutta la lega**. Non è un
mercato che si esaurisce, è un mercato che non è mai esistito. Ed entrambi i numeri sono
scritti dentro `WorldGenerator`, non in `LeagueConfig` — contro la regola del progetto, e
quindi non correggibili senza pubblicare un APK.

**La schermata è una lista sola.** I tuoi tre in cima, poi `Liberi · 74` con fino a quaranta
righe di ruoli mescolati. Per sapere chi hai bisogna scorrere oltre il rumore.

Una cosa invece **funziona già** e non va rifatta: lo staff si compra a prezzo fisso con
`Valuation.staffPrice`, senza asta, dal 2026-08-24.

## Le decisioni del proprietario

Prese il 2026-08-30, in questa forma:

1. **Un tetto, non un'economia.** Niente stipendi, niente specialità. Possiedi al massimo
   quanti ne puoi schierare più una scorta; le celle dicono **chi lavora dove**, e servono
   a spostarli fra prima squadra e Primavera senza perderli — che oggi non si può fare.
2. **Negozio a prezzo fisso, su una schermata sua.** Lo staff smette di stare nella lista
   che si usa per i giocatori.
3. **C'è sempre qualcosa, il top quasi mai.** Da una a tre stelle non finiscono mai. Quattro
   e cinque compaiono di rado e spariscono appena qualcuno li prende.
4. **Cinque celle osservatori sempre visibili**, anche vuote. I posti si vedono anche quando
   non sono occupati.
5. **Gli osservatori solo con la Primavera.** Senza seconda squadra non si comprano; comprati,
   lavorano lì.

## Il modello: due domande diverse, due colonne

Oggi `staff.club_id` risponde a due domande insieme — *di chi è* e *dove lavora* — e per
questo non si può possedere qualcuno che non gioca.

| | `owner_club_id` | `club_id` |
|---|---|---|
| Nel negozio | `null` | `null` |
| Tuo, in una cella | prima squadra | prima squadra **o** Primavera |
| Tuo, in panchina | prima squadra | `null` |

`owner_club_id` è **sempre la prima squadra**, mai la Primavera: si possiede come società,
si schiera come squadra. Senza questa regola «quanti ne ho» andrebbe contato su due club e
il tetto si aggirerebbe fondando la Primavera.

**Conseguenza da non dimenticare:** ovunque il codice oggi legga `club_id is null` per dire
«libero», da adesso deve leggere `owner_club_id is null`. Il posto è
`start_auction` (riga ~1625) e la lettura del negozio.

## Le celle

Una regola sola in `core`, come ogni regola di gioco del progetto:

```kotlin
object Celle {
    enum class Posto { PRIMA_SQUADRA, PRIMAVERA }

    data class Cella(val role: StaffRole, val posto: Posto, val indice: Int)

    /** Le celle che esistono, in ordine di disegno. */
    fun tutte(config: LeagueConfig): List<Cella>

    /** Perché questa cella non si può riempire, o null se si può. */
    fun impedimento(cella: Cella, haPrimavera: Boolean): String?

    /** Quanti se ne possono possedere di questo ruolo. */
    fun tetto(role: StaffRole, config: LeagueConfig): Int

    /** Perché non puoi comprarne un altro, o null se puoi. */
    fun impedimentoAcquisto(
        role: StaffRole, posseduti: Int, haPrimavera: Boolean, config: LeagueConfig,
    ): String?
}
```

Nove celle: **due allenatori** (prima, Primavera), **due preparatori** (prima, Primavera),
**cinque osservatori** (tutti Primavera).

Gli osservatori sono asimmetrici di proposito. Non scelgono fra due squadre — stanno tutti
nella Primavera — quindi la loro cella non deve **assegnare**, deve **raccontare**: chi è,
quante stelle, e cosa sta facendo (*Brasile · 9 min*, *tornato · guarda*). È da lì che
l'area B aprirà il pop-up del giocatore trovato.

Il testo di `impedimento` è la ragione scritta sulla tessera — «Serve la Primavera» — non un
errore dopo il tocco. Regola già in `REGOLE.md`: *un pulsante che si può premere e che dà
sempre errore insegna a non fidarsi di nessun pulsante*.

## Il negozio

Schermata a parte, raggiunta dalla testata dello staff.

- **Filtro per ruolo.** La linguetta degli osservatori è **spenta con un lucchetto** quando
  non c'è la Primavera: il divieto sta sul filtro, prima del tocco.
- **Il tetto scritto sopra la lista**: «Ne hai 2 su 4 · resta spazio per 2».
- **Accanto alle stelle, cosa comprano**: `★★★★★ · crescita ×1,80`. Cinque stelle da sole
  non dicono niente; quel moltiplicatore è la ragione per cui uno vale ventitré volte
  l'altro — e il prezzo lo sa già `Valuation.staffPrice`, curva quadratica sulle stelle.
- **Il raro in cima, con scritto che va via.** È l'unica cosa che rende sensato riaprire il
  negozio domani.
- **In fondo, quando si rinnova lo scaffale.** Un rifornimento silenzioso non si scopre.

## Il rifornimento

Due regole diverse, ed è la traduzione della decisione 3.

**Da una a tre stelle — un pavimento.** Il tick tiene almeno `scaffaleMinimo` liberi per
ruolo e ricompleta a ogni giornata. Chi entra tardi in lega trova comunque un preparatore:
restare bloccati per essere arrivati dopo è il difetto che questa regola chiude.

**Quattro e cinque stelle — una probabilità.** Compaiono con `probabilitaRaro` per giornata
e per ruolo, e **non vengono mai ricompletati**: quando qualcuno li prende, non ci sono più.
Chi guarda spesso li trova, chi passa una volta a settimana quasi mai.

Il rifornimento è un lavoro del tick, accanto agli altri: genera righe `staff` nuove con
`WorldGenerator.staffMember`, che oggi non esiste come funzione singola e va estratta da
`generateStaff`.

## I numeri, e dove vivono

Nuovo blocco in `LeagueConfig`:

```kotlin
data class StaffConfig(
    val maxAllenatori: Int = 4,
    val maxPreparatori: Int = 4,
    val maxOsservatori: Int = 5,
    /** Quanti liberi da 1-3 stelle il negozio tiene sempre, per ruolo. */
    val scaffaleMinimo: Int = 6,
    /** Probabilità per giornata e per ruolo che compaia un 4-5 stelle. */
    val probabilitaRaro: Double = 0.35,
    /** Quanti membri per ruolo per club genera il mondo. Era in WorldGenerator. */
    val perClub: Double = 2.0,
    /** I pesi delle stelle, da una a cinque. Erano in WorldGenerator. */
    val pesiStelle: List<Double> = listOf(20.0, 28.0, 30.0, 16.0, 6.0),
)
```

`perClub` e `pesiStelle` **si portano via da `WorldGenerator`**. Sono numeri di gioco nel
codice, contro il principio del progetto, e sono precisamente quelli che decidono il «troppo
poco stuff»: finché stanno lì non si toccano senza pubblicare un APK.

Il tetto degli osservatori resta cinque, uguale alle celle: non serve scorta, si schierano
tutti.

## Lo schema, e la trappola

**Questa area richiede una modifica al database**, a differenza del lavoro sui duelli:

```sql
alter table staff add column if not exists owner_club_id bigint
    references clubs(id) on delete set null;
```

Più il travaso di quello che c'è:

```sql
update staff set owner_club_id =
    coalesce((select c.parent_club_id from clubs c where c.id = staff.club_id), staff.club_id)
where club_id is not null and owner_club_id is null;
```

E gli osservatori già in prima squadra passano alla Primavera dove esiste. Dove non esiste
**restano dove sono**: si perde il diritto di comprarne altri, non quello che si è già
pagato.

**La lettura della colonna nuova va isolata.** `StaffRepository.all` chiede oggi
`id,first_name,last_name,nationality,role,stars,club_id`: se ci si aggiunge
`owner_club_id` e il database è indietro, PostgREST rifiuta **l'intera query** e la
schermata dello staff smette di aprirsi — non perde le celle, sparisce. È la trappola pagata
tre volte: `clubs.division_level`, `clubs.parent_club_id`, e il 2026-08-29 con
`match_results.home_formation`.

Quindi: `all()` resta identica, e la proprietà arriva da una **seconda lettura**
`StaffRepository.proprieta(leagueId)` che chiede solo `id,owner_club_id`. Se fallisce, si
degrada al comportamento di oggi — celle vuote e nessun acquisto — invece di sparire.

`limit=400` nella lettura dello staff va alzato: con il rifornimento acceso il numero di
righe cresce, e PostgREST tronca in silenzio restituendo comunque 200.

## Cosa non si fa

- **Niente stipendi.** Deciso: il freno è il tetto, non l'economia.
- **Niente specialità per membro.** Un preparatore vale le sue stelle e basta.
- **Niente aste sullo staff.** Il prezzo fisso c'è già e funziona.
- **Nessuna terza cella** per allenatori e preparatori: due squadre, due celle.
- **Non si tocca lo scouting.** Le celle degli osservatori mostrano lo stato della missione,
  ma la missione — durata, ruoli, cosa torna, il pop-up — è tutta area B. Toccarle insieme
  vorrebbe dire non sapere quale delle due ha rotto cosa.

## Come si verifica

`Celle` è una funzione pura e si prova senza database: tetti, impedimenti, la Primavera che
manca, l'asimmetria degli osservatori.

Il rifornimento si prova sul comportamento, non sull'implementazione: dopo N giornate lo
scaffale dei comuni non è mai vuoto, e i rari compaiono con la frequenza attesa dentro una
banda larga.

`core:test` verde prima e dopo, e l'APK installato e avviato — con lo schema eseguito
**prima**.

## Ordine dei lavori

1. `StaffConfig` in `LeagueConfig`, con `perClub` e `pesiStelle` portati via da
   `WorldGenerator`.
2. `Celle` in `core`, con i suoi test.
3. Lo schema: `owner_club_id`, il travaso, `assign_staff` riscritta, `start_auction` che
   guarda la colonna giusta.
4. Il rifornimento nel tick.
5. `StaffRepository.proprieta` come lettura separata.
6. La schermata a celle.
7. Il negozio.
