-- =====================================================================================
-- I CINQUE INCARICHI
--
-- Deciso dal proprietario il 2026-08-24: in formazione si assegnano capitano, rigorista,
-- battitore d'angoli, battitore di punizioni e uomo dei calci lunghi. Ognuno pesa nel
-- motore — un incarico che non muove un numero e' una casella da riempire per niente.
--
-- DUE COLONNE C'ERANO GIA' E NON LE LEGGEVA NESSUNO
--
-- `captain_id` e `penalty_taker_id` stanno in `lineups` dalla `create table` di
-- `0001_schema.sql`. Il motore usava il rigorista designato (`MatchEngine`), il capitano
-- non lo guardava nessuno, e l'app non aveva nessuna schermata per sceglierli: la
-- funzionalita' e' stata a meta' dal primo giorno senza che si potesse vedere.
--
-- Questa migrazione aggiunge le tre che mancavano.
--
-- GLI ORDINI CONDIZIONALI, INVECE, NON HANNO BISOGNO DI NIENTE
--
-- `lineups.orders` esiste da `0001` ed e' un `jsonb` che aspetta da sempre. Gli ordini
-- («se sono sotto dal 60', dentro la punta») sono completi in `core` fin dall'inizio, con
-- i loro test, e in `android/` non compaiono da nessuna parte: quello che mancava era la
-- schermata, non la colonna. Da qui in avanti la scrive l'app.
--
-- RIESEGUIBILE
--
-- `add column if not exists` su tutte e tre: eseguirla due volte non fa niente, ed e' la
-- regola di ogni migrazione di questo progetto.
-- =====================================================================================

alter table lineups add column if not exists corner_taker_id    bigint;
alter table lineups add column if not exists free_kick_taker_id bigint;
alter table lineups add column if not exists long_ball_taker_id bigint;

comment on column lineups.corner_taker_id is
    'Chi batte gli angoli. Null significa "scegli tu": SetPieces mette in campo il piu'' adatto.';
comment on column lineups.free_kick_taker_id is
    'Chi calcia le punizioni dal limite.';
comment on column lineups.long_ball_taker_id is
    'Chi batte rimesse e rinvii lunghi. E'' l''unico incarico che puo'' toccare al portiere.';

-- =====================================================================================
-- PERCHE' NESSUN VINCOLO DI CHIAVE ESTERNA
--
-- Verrebbe naturale scrivere `references players(id)`. Non si fa, ed e' deliberato:
-- `captain_id` e `penalty_taker_id` non ce l'hanno, e aggiungerlo solo alle tre nuove
-- creerebbe due comportamenti diversi nella stessa tabella — tre colonne che bloccano la
-- cancellazione di un giocatore e due che la lasciano passare.
--
-- La coerenza la garantisce gia' il gioco da un'altra parte, e meglio: un incarico vale
-- **solo se quell'uomo e' in campo** (`SetPieces.designated`). Un id che punta a un
-- giocatore ceduto, svincolato o in panchina non produce nessun errore: produce il
-- comportamento giusto, cioe' l'incarico passa al piu' adatto fra gli undici.
-- =====================================================================================
