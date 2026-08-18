-- =====================================================================================
-- MFoot - la forbice che si stringe
--
-- Da incollare nell'SQL Editor di Supabase dopo 0015_vendite.sql. Rieseguibile.
--
-- Nessuna colonna aggiunta a tabelle esistenti: si puo' applicare prima o dopo l'APK.
-- =====================================================================================

-- Quanto **questo club** sa del potenziale di **questo giocatore**.
--
-- ## Il difetto che chiude
--
-- Il potenziale e' nascosto di proposito: e' la meccanica che sostituisce l'emozione dei
-- nomi noti con la scommessa. Il progetto dice che la forbice si stringe con i minuti che
-- lo hai visto giocare e con il lavoro degli osservatori.
--
-- Non si stringeva mai. L'app usava `PotentialEstimator.publicEstimate`, cioe' la stima a
-- **conoscenza zero**, e i minuti visti arrivavano dal database con accanto un commento che
-- diceva "non servono qui". Gli osservatori che si potevano assumere non facevano niente.
-- La scommessa non si risolveva: si guardava l'overall salire e basta.
--
-- ## Perche' il conto lo fa il server
--
-- Perche' stringere la forbice significa avvicinarla al **valore vero**, e il valore vero
-- non deve mai lasciare il server: `players_public` non contiene `potential_min` e
-- `potential_max` proprio per questo. Un client che calcolasse la stima ristretta dovrebbe
-- prima ricevere la verita', e allora tanto varrebbe mostrarla.
--
-- Qui arriva **gia' calcolata**, e non c'e' modo di dedurre il segreto per differenza:
-- quello che si riceve e' un intervallo, non uno scarto da un valore noto.
--
-- ## Perche' non e' una vista
--
-- Perche' il calcolo sta in `PotentialEstimator`, in `core`, con i suoi test, e riscriverlo
-- in PL/pgSQL vorrebbe dire due formule che si separano al primo ritocco — con l'app che
-- mostra una forbice e il gioco che ne usa un'altra. Lo esegue il tick, che di `core` ha
-- gia' una copia identica.
create table if not exists scouting (
    club_id    bigint not null references clubs(id) on delete cascade,
    player_id  bigint not null references players(id) on delete cascade,
    league_id  bigint not null references leagues(id) on delete cascade,

    est_min    integer not null,
    est_max    integer not null,

    -- Da 0 a 100: quanto si sa. Serve a mostrarlo — "lo conosci poco" e' un'informazione
    -- diversa da una forbice larga, perche' una forbice puo' essere larga anche quando la
    -- si conosce benissimo e il giocatore e' semplicemente imprevedibile.
    knowledge  integer not null default 0,

    updated_at timestamptz not null default now(),

    primary key (club_id, player_id)
);

create index if not exists idx_scouting_club on scouting(club_id);

alter table scouting enable row level security;

-- Ognuno vede solo cio' che sa **lui**. Due club che guardano lo stesso ragazzo hanno due
-- stime diverse, ed e' il punto: chi lo ha visto giocare venti volte ne sa di piu'.
drop policy if exists read_own_scouting on scouting;
create policy read_own_scouting on scouting for select
    using (owns_club(club_id));

-- Nessuna policy di scrittura: la calcola il tick, che si collega come servizio. Un client
-- che potesse scrivere qui si dichiarerebbe onnisciente su tutta la lega.

-- =====================================================================================
-- FINE
-- =====================================================================================
