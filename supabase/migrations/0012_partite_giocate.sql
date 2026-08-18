-- =====================================================================================
-- MFoot - chi ha giocato, partita per partita
--
-- Da incollare nell'SQL Editor di Supabase dopo 0011_promises.sql. Rieseguibile.
-- =====================================================================================

-- Una riga per giocatore per partita.
--
-- ## Perche' serve, visto che c'e' gia' `match_results.player_stats`
--
-- Il tabellino c'e' davvero: `player_stats` e' un jsonb con i numeri di tutti. Ma sta
-- dentro **una** partita, e ogni domanda che conta comincia con "nelle ultime tre":
-- tre panchine di fila, due voti sotto il cinque, la promessa di giocare titolare per
-- cinque giornate. Rispondere leggendo il jsonb vorrebbe dire scaricare tutte le partite
-- della stagione e aprirle una per una.
--
-- ## Il difetto che chiude
--
-- Fino a qui `lineups` era l'unica traccia di chi giocava, e ne tiene **una riga per
-- club**: la formazione attuale, sovrascritta a ogni salvataggio. Il tick verificava la
-- promessa "titolare per tre partite" guardando la formazione impostata *adesso*. Bastava
-- cambiare undici dopo la partita perche' il conto sbagliasse, in entrambe le direzioni.
create table if not exists appearances (
    fixture_id  bigint  not null references fixtures(id) on delete cascade,
    player_id   bigint  not null references players(id) on delete cascade,
    league_id   bigint  not null references leagues(id) on delete cascade,
    club_id     bigint  not null references clubs(id) on delete cascade,
    match_day   integer not null,

    -- Titolare vuol dire "era in campo al fischio d'inizio", e lo sa solo la formazione
    -- con cui la partita e' cominciata. Dedurlo dai minuti darebbe titolare anche a chi
    -- entra all'ottantesimo.
    started     boolean not null default false,
    minutes     integer not null default 0,
    goals       integer not null default 0,
    assists     integer not null default 0,
    yellow      integer not null default 0,
    red         integer not null default 0,
    injured     boolean not null default false,

    -- Zero significa "non e' sceso in campo": il voto di chi non gioca non esiste, e un
    -- 6 di comodo falserebbe ogni media.
    rating      numeric(3,1) not null default 0,

    primary key (fixture_id, player_id)
);

-- Le righe ci sono **anche per chi non ha giocato**, panchina ed esclusi compresi.
--
-- Senza, "tre partite senza scendere in campo" sarebbe indistinguibile da "tre partite in
-- cui questa tabella non sa niente di lui" — e sono due cose molto diverse quando un
-- giocatore e' arrivato ieri.

-- Tutte le domande hanno questa forma: questo giocatore, le ultime N giornate.
create index if not exists idx_appearances_player
    on appearances(league_id, player_id, match_day desc);

-- Il tabellino di una squadra in una giornata: la seconda forma di domanda.
create index if not exists idx_appearances_club
    on appearances(club_id, match_day desc);

alter table appearances enable row level security;

-- Le presenze sono pubbliche dentro la lega: sono il tabellino, e un tabellino segreto
-- non ha senso. Non contengono niente di nascosto — nessun potenziale, nessun attributo.
drop policy if exists read_appearances on appearances;
create policy read_appearances on appearances for select
    using (is_member_of(league_id));

-- La scrittura e' solo del tick, che si collega come servizio e non passa dalle policy.
-- Nessuna policy di insert: un client che potesse scriversi le presenze si dichiarerebbe
-- titolare per mantenere le promesse che ha fatto.

-- =====================================================================================
-- FINE
-- =====================================================================================
