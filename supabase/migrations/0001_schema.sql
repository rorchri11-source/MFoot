-- =====================================================================================
-- MFoot - schema iniziale
--
-- Da incollare nell'SQL Editor di Supabase ed eseguire una volta sola.
--
-- Principi:
--   1. Lo stato autorevole lo scrive il tick. I client propongono, il tick dispone.
--   2. Le operazioni sui crediti girano in transazione con lock di riga. Senza, un club
--      puo' vincere cinque aste con i soldi per una e la lega e' rotta.
--   3. Le durate di gioco sono in GIORNATE (match_day), mai in date reali. Le date reali
--      servono solo a sapere quando far partire le cose.
-- =====================================================================================

-- =====================================================================================
-- LEGHE E MEMBRI
-- =====================================================================================

create table if not exists leagues (
    id              bigserial primary key,
    name            text        not null,
    -- Mai la password in chiaro: si confronta l'hash.
    access_code_hash text       not null,
    config          jsonb       not null,
    world_seed      bigint      not null,
    status          text        not null default 'setup'
                                check (status in ('setup', 'mercato', 'in_corso', 'conclusa')),
    current_match_day integer   not null default 0,
    created_at      timestamptz not null default now()
);

create table if not exists league_members (
    league_id   bigint      not null references leagues(id) on delete cascade,
    user_id     uuid        not null references auth.users(id) on delete cascade,
    nickname    text        not null,
    is_admin    boolean     not null default false,
    joined_at   timestamptz not null default now(),
    primary key (league_id, user_id)
);

create index if not exists idx_members_user on league_members(user_id);

-- =====================================================================================
-- CLUB
-- =====================================================================================

create table if not exists clubs (
    id                bigserial primary key,
    league_id         bigint  not null references leagues(id) on delete cascade,
    name              text    not null,
    short_name        text    not null,
    kit               jsonb   not null default '{}'::jsonb,
    is_ai             boolean not null default false,
    -- Null per i club AI.
    owner_user_id     uuid    references auth.users(id) on delete set null,
    owner_name        text,
    credits           integer not null default 0,
    -- Crediti gia' impegnati in aste e offerte pendenti. Ogni decisione di spesa deve
    -- guardare (credits - committed_credits), mai credits da solo.
    committed_credits integer not null default 0 check (committed_credits >= 0),
    custom_player_id  bigint,
    created_at        timestamptz not null default now(),
    unique (league_id, name)
);

create index if not exists idx_clubs_league on clubs(league_id);
create index if not exists idx_clubs_owner on clubs(owner_user_id);

-- =====================================================================================
-- MONDO GENERATO
-- =====================================================================================

create table if not exists players (
    id                  bigserial primary key,
    league_id           bigint  not null references leagues(id) on delete cascade,
    first_name          text    not null,
    last_name           text    not null,
    nationality         text    not null,
    age                 integer not null,
    primary_position    text    not null,
    secondary_positions text[]  not null default '{}',
    attributes          jsonb   not null,
    weak_foot           integer not null check (weak_foot between 1 and 5),
    skill_stars         integer not null check (skill_stars between 1 and 5),

    -- MAI esposti al client: il client riceve solo una forbice stimata, che si stringe
    -- con i minuti giocati o con lo scouting. E' il meccanismo che sostituisce
    -- l'emozione dei nomi noti con la scommessa. Vedi la vista players_public.
    potential_min       integer not null,
    potential_max       integer not null,

    traits              text[]  not null default '{}',
    stamina             integer not null default 100 check (stamina between 0 and 100),
    morale              integer not null default 60  check (morale between 0 and 100),
    form                integer not null default 0   check (form between -5 and 5),
    experience          double precision not null default 0,
    is_custom           boolean not null default false,
    injured_until       integer,
    overall             integer not null,
    minutes_observed    integer not null default 0
);

create index if not exists idx_players_league on players(league_id);
create index if not exists idx_players_position on players(league_id, primary_position);

create table if not exists staff (
    id          bigserial primary key,
    league_id   bigint  not null references leagues(id) on delete cascade,
    first_name  text    not null,
    last_name   text    not null,
    nationality text    not null,
    role        text    not null check (role in ('ALLENATORE', 'PREPARATORE', 'OSSERVATORE')),
    stars       integer not null check (stars between 1 and 5),
    club_id     bigint  references clubs(id) on delete set null
);

create index if not exists idx_staff_league on staff(league_id);

-- =====================================================================================
-- CONTRATTI, PRESTITI, ROSE
-- =====================================================================================

create table if not exists contracts (
    id                bigserial primary key,
    league_id         bigint  not null references leagues(id) on delete cascade,
    player_id         bigint  not null references players(id) on delete cascade,
    club_id           bigint  not null references clubs(id) on delete cascade,
    signed_on         integer not null,
    expires_on        integer not null,
    wage_per_match_day integer not null default 0,
    price_paid        integer not null default 0,
    release_clause    integer,
    -- Prima squadra o Primavera.
    squad             text    not null default 'prima'
                              check (squad in ('prima', 'primavera')),
    -- Un giocatore ha un solo contratto attivo per volta.
    unique (player_id)
);

create index if not exists idx_contracts_club on contracts(club_id);
create index if not exists idx_contracts_expiry on contracts(league_id, expires_on);

create table if not exists loans (
    id                    bigserial primary key,
    league_id             bigint  not null references leagues(id) on delete cascade,
    player_id             bigint  not null references players(id) on delete cascade,
    owner_club_id         bigint  not null references clubs(id) on delete cascade,
    borrower_club_id      bigint  not null references clubs(id) on delete cascade,
    starts_on             integer not null,
    ends_on               integer not null,
    fee_per_match_day     integer not null default 0,
    wage_paid_by_borrower boolean not null default true,
    can_play_against_owner boolean not null default false,
    recallable            boolean not null default false,
    active                boolean not null default true,
    check (owner_club_id <> borrower_club_id),
    check (ends_on > starts_on)
);

create index if not exists idx_loans_expiry on loans(league_id, ends_on) where active;

-- =====================================================================================
-- COMPETIZIONI E PARTITE
-- =====================================================================================

create table if not exists competitions (
    id           bigserial primary key,
    league_id    bigint not null references leagues(id) on delete cascade,
    name         text   not null,
    type         text   not null check (type in
                        ('GIRONE', 'ELIMINAZIONE_DIRETTA', 'GIRONI_PIU_ELIMINAZIONE')),
    config       jsonb  not null,
    participants bigint[] not null default '{}'
);

create table if not exists fixtures (
    id             bigserial primary key,
    league_id      bigint  not null references leagues(id) on delete cascade,
    competition_id bigint  not null references competitions(id) on delete cascade,
    round          integer not null,
    round_label    text    not null,
    home_club_id   bigint  not null references clubs(id) on delete cascade,
    away_club_id   bigint  not null references clubs(id) on delete cascade,
    match_day      integer not null,
    kickoff        timestamptz,
    tie_id         text,
    is_second_leg  boolean not null default false,
    played         boolean not null default false,
    check (home_club_id <> away_club_id)
);

-- L'indice che usa il tick a ogni giro: le partite ancora da giocare, per orario.
create index if not exists idx_fixtures_pending on fixtures(league_id, kickoff)
    where not played;

create table if not exists match_results (
    fixture_id  bigint primary key references fixtures(id) on delete cascade,
    league_id   bigint  not null references leagues(id) on delete cascade,
    home_goals  integer not null,
    away_goals  integer not null,
    seed        bigint  not null,
    -- La timeline completa degli eventi. Il client la legge UNA volta e la riproduce in
    -- locale con un timer: nessun polling, costo zero durante i novanta minuti, e chi
    -- apre l'app al sessantesimo salta direttamente al sessantesimo.
    timeline    jsonb   not null,
    player_stats jsonb  not null,
    home_possession real not null default 0.5,
    simulated_at timestamptz not null default now()
);

-- =====================================================================================
-- MERCATO
-- =====================================================================================

create table if not exists auctions (
    id             bigserial primary key,
    league_id      bigint  not null references leagues(id) on delete cascade,
    target_type    text    not null check (target_type in ('player', 'staff')),
    target_id      bigint  not null,
    started_by     bigint  not null references clubs(id) on delete cascade,
    started_at     timestamptz not null default now(),
    ends_at        timestamptz not null,
    starting_price integer not null default 1,
    status         text    not null default 'APERTA'
                           check (status in ('APERTA', 'AGGIUDICATA', 'DESERTA', 'ANNULLATA')),
    extensions     integer not null default 0,
    winner_club_id bigint  references clubs(id) on delete set null,
    final_price    integer
);

create index if not exists idx_auctions_open on auctions(league_id, ends_at)
    where status = 'APERTA';

create table if not exists bids (
    id         bigserial primary key,
    auction_id bigint  not null references auctions(id) on delete cascade,
    club_id    bigint  not null references clubs(id) on delete cascade,
    -- L'offerta MASSIMA automatica, non il rilancio. Non e' mai visibile agli altri:
    -- il prezzo sale solo quanto serve per superare il secondo miglior massimo.
    max_amount integer not null check (max_amount > 0),
    placed_at  timestamptz not null default now()
);

create index if not exists idx_bids_auction on bids(auction_id, max_amount desc, placed_at);

create table if not exists negotiations (
    id         bigserial primary key,
    league_id  bigint  not null references leagues(id) on delete cascade,
    player_id  bigint  not null references players(id) on delete cascade,
    buyer_club_id  bigint not null references clubs(id) on delete cascade,
    seller_club_id bigint not null references clubs(id) on delete cascade,
    terms      jsonb   not null,
    awaiting_club_id bigint not null references clubs(id) on delete cascade,
    expires_at timestamptz not null,
    status     text    not null default 'IN_ATTESA'
                       check (status in ('IN_ATTESA', 'ACCETTATA', 'RIFIUTATA',
                                         'CONTROPROPOSTA', 'SCADUTA', 'RITIRATA')),
    history    jsonb   not null default '[]'::jsonb,
    check (buyer_club_id <> seller_club_id)
);

create index if not exists idx_negotiations_open on negotiations(league_id, expires_at)
    where status in ('IN_ATTESA', 'CONTROPROPOSTA');

-- =====================================================================================
-- FORMAZIONI E ORDINI
-- =====================================================================================

create table if not exists lineups (
    club_id    bigint primary key references clubs(id) on delete cascade,
    league_id  bigint not null references leagues(id) on delete cascade,
    formation  text   not null default 'F_4_3_3',
    -- [{player_id, position}, ...]
    slots      jsonb  not null default '[]'::jsonb,
    bench      bigint[] not null default '{}',
    tactics    jsonb  not null default '{}'::jsonb,
    -- Gli ordini condizionali: e' cosi' che il manager ha voce in capitolo senza dover
    -- essere davanti al telefono alle 21.
    orders     jsonb  not null default '[]'::jsonb,
    captain_id     bigint,
    penalty_taker_id bigint,
    updated_at timestamptz not null default now()
);

-- =====================================================================================
-- AI
-- =====================================================================================

create table if not exists ai_states (
    club_id       bigint primary key references clubs(id) on delete cascade,
    league_id     bigint not null references leagues(id) on delete cascade,
    personality   jsonb  not null,
    -- Il campo piu' importante dell'anti-sciame: il tick non scorre tutte le AI, sveglia
    -- solo quelle il cui orario e' arrivato. Un'AI che dorme non sa che l'asta esiste.
    next_wake_at  timestamptz not null,
    actions_today integer not null default 0,
    action_day    date,
    refusal_cooldowns jsonb not null default '{}'::jsonb,
    abandoned_targets bigint[] not null default '{}'
);

create index if not exists idx_ai_wake on ai_states(league_id, next_wake_at);

-- =====================================================================================
-- STATO DEL TICK
-- =====================================================================================

create table if not exists tick_state (
    league_id          bigint primary key references leagues(id) on delete cascade,
    -- Se il tick viene ritardato o saltato, questo resta indietro e al giro successivo
    -- si recupera tutto l'intervallo perso, in ordine e una volta sola.
    last_processed_at  timestamptz,
    last_digest_at     timestamptz,
    -- Giornate gia' liquidate: impedisce di pagare due volte gli stipendi se una
    -- transazione fallisce a meta' e il tick rigira sulla stessa finestra.
    settled_match_days integer[] not null default '{}',
    last_run_at        timestamptz,
    last_run_notes     text
);

-- =====================================================================================
-- NOTIFICHE
-- =====================================================================================

create table if not exists notifications (
    id         bigserial primary key,
    league_id  bigint not null references leagues(id) on delete cascade,
    club_id    bigint references clubs(id) on delete cascade,
    kind       text   not null,
    -- 'immediata' richiede una decisione con scadenza; 'riepilogo' finisce nel messaggio
    -- giornaliero. Un ping per ogni evento porta alla disinstallazione in tre giorni.
    urgency    text   not null default 'riepilogo'
                      check (urgency in ('immediata', 'riepilogo')),
    body       text   not null,
    created_at timestamptz not null default now(),
    delivered  boolean not null default false
);

create index if not exists idx_notifications_pending on notifications(league_id, created_at)
    where not delivered;

-- =====================================================================================
-- LA VISTA CHE IL CLIENT PUO' LEGGERE
--
-- I potenziali veri non escono mai da qui. Vale anche per l'AI, che gira nel tick ma
-- usa la stessa stima incerta: un'AI che leggesse i valori veri comprerebbe sempre i
-- giovani giusti e sembrerebbe truccata.
-- =====================================================================================

create or replace view players_public as
select
    id, league_id, first_name, last_name, nationality, age,
    primary_position, secondary_positions, attributes,
    weak_foot, skill_stars, traits,
    stamina, morale, form, is_custom, injured_until, overall,
    minutes_observed
from players;

-- =====================================================================================
-- OFFERTA D'ASTA ATOMICA
--
-- E' la funzione piu' delicata di tutto il sistema. Due offerte simultanee dello stesso
-- club non devono poter passare entrambe il controllo fondi: il lock di riga sul club
-- serializza il controllo.
--
-- Il client chiama questa e riceve risposta SUBITO, senza aspettare il tick: e' il
-- motivo per cui una griglia da 5 minuti non si sente sulle aste.
-- =====================================================================================

create or replace function place_bid(
    p_auction_id bigint,
    p_club_id    bigint,
    p_max_amount integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_auction    auctions%rowtype;
    v_club       clubs%rowtype;
    v_previous   integer;
    v_additional integer;
    v_available  integer;
    v_top_max    integer;
    v_second_max integer;
    v_min_raise  integer;
    v_price      integer;
    v_leader     bigint;
    v_config     jsonb;
begin
    -- Il lock arriva prima di ogni controllo: e' quello che rende il controllo fondi
    -- affidabile anche con due richieste nello stesso millisecondo.
    select * into v_club from clubs where id = p_club_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Club inesistente.');
    end if;

    select * into v_auction from auctions where id = p_auction_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Asta inesistente.');
    end if;
    if v_auction.status <> 'APERTA' then
        return jsonb_build_object('ok', false, 'reason', 'L''asta non e'' piu'' aperta.');
    end if;
    if now() >= v_auction.ends_at then
        return jsonb_build_object('ok', false, 'reason', 'L''asta e'' gia'' scaduta.');
    end if;
    if v_club.league_id <> v_auction.league_id then
        return jsonb_build_object('ok', false, 'reason', 'Asta di un''altra lega.');
    end if;

    select config into v_config from leagues where id = v_auction.league_id;
    v_min_raise := coalesce((v_config -> 'market' ->> 'minimumRaise')::integer, 1);

    select coalesce(max(max_amount), 0) into v_previous
    from bids where auction_id = p_auction_id and club_id = p_club_id;

    if p_max_amount <= v_previous then
        return jsonb_build_object('ok', false, 'reason', 'Puoi solo alzare la tua offerta massima.');
    end if;

    -- Si impegna solo la differenza rispetto a quanto gia' bloccato su questa asta.
    v_additional := p_max_amount - v_previous;
    v_available  := v_club.credits - v_club.committed_credits;

    if v_additional > v_available then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Crediti insufficienti: servono altri %s, disponibili %s.',
                             v_additional, v_available));
    end if;

    -- Prezzo corrente prima dell'offerta, per calcolare il minimo accettabile.
    select coalesce(max(max_amount), 0) into v_top_max from bids where auction_id = p_auction_id;
    select coalesce(max(max_amount), 0) into v_second_max
    from bids where auction_id = p_auction_id
      and max_amount < v_top_max;

    if v_top_max = 0 then
        v_price := v_auction.starting_price;
    elsif v_second_max = 0 then
        v_price := v_auction.starting_price;
    else
        v_price := least(v_second_max + v_min_raise, v_top_max);
    end if;

    -- Il minimo da superare e' il PREZZO corrente, non il massimo del capofila: quello
    -- e' segreto, e chiedere di batterlo renderebbe impossibile offrire.
    if v_previous = 0 and v_top_max > 0 and p_max_amount < v_price + v_min_raise then
        return jsonb_build_object(
            'ok', false,
            'reason', format('L''offerta minima e'' %s crediti.', v_price + v_min_raise));
    end if;

    insert into bids (auction_id, club_id, max_amount) values (p_auction_id, p_club_id, p_max_amount);

    update clubs set committed_credits = committed_credits + v_additional where id = p_club_id;

    -- Anti-snipe: un rilancio negli ultimi secondi prolunga l'asta, cosi' vince chi
    -- valuta di piu' e non chi ha il dito piu' veloce.
    if coalesce((v_config -> 'market' ->> 'antiSnipeEnabled')::boolean, true)
       and v_auction.ends_at - now() <=
           make_interval(secs => coalesce((v_config -> 'market' ->> 'antiSnipeSeconds')::integer, 60))
    then
        update auctions
        set ends_at = ends_at + make_interval(secs =>
                coalesce((v_config -> 'market' ->> 'antiSnipeSeconds')::integer, 60)),
            extensions = extensions + 1
        where id = p_auction_id;
    end if;

    -- Ricalcolo di capofila e prezzo dopo l'inserimento.
    select club_id into v_leader
    from bids where auction_id = p_auction_id
    order by max_amount desc, placed_at asc limit 1;

    select coalesce(max(max_amount), 0) into v_top_max from bids where auction_id = p_auction_id;
    select coalesce(max(max_amount), 0) into v_second_max
    from bids where auction_id = p_auction_id and max_amount < v_top_max;

    v_price := case when v_second_max = 0 then v_auction.starting_price
                    else least(v_second_max + v_min_raise, v_top_max) end;

    return jsonb_build_object(
        'ok', true,
        'leader_club_id', v_leader,
        'current_price', v_price,
        'you_lead', v_leader = p_club_id
    );
end;
$$;

-- =====================================================================================
-- ROW LEVEL SECURITY
--
-- Regola generale: si legge solo la propria lega, si scrive solo il proprio club, e lo
-- stato autorevole (crediti, risultati, contratti) lo scrive esclusivamente il tick con
-- la service_role key, che RLS non applica.
-- =====================================================================================

alter table leagues        enable row level security;
alter table league_members enable row level security;
alter table clubs          enable row level security;
alter table players        enable row level security;
alter table staff          enable row level security;
alter table contracts      enable row level security;
alter table loans          enable row level security;
alter table competitions   enable row level security;
alter table fixtures       enable row level security;
alter table match_results  enable row level security;
alter table auctions       enable row level security;
alter table bids           enable row level security;
alter table negotiations   enable row level security;
alter table lineups        enable row level security;
alter table ai_states      enable row level security;
alter table tick_state     enable row level security;
alter table notifications  enable row level security;

create or replace function is_member_of(p_league_id bigint)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from league_members
        where league_id = p_league_id and user_id = auth.uid()
    );
$$;

create or replace function owns_club(p_club_id bigint)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from clubs where id = p_club_id and owner_user_id = auth.uid()
    );
$$;

-- Lettura: tutto quello che riguarda una lega di cui si fa parte.
create policy read_leagues      on leagues       for select using (is_member_of(id));
create policy read_members      on league_members for select using (is_member_of(league_id));
create policy read_clubs        on clubs         for select using (is_member_of(league_id));
create policy read_players      on players       for select using (is_member_of(league_id));
create policy read_staff        on staff         for select using (is_member_of(league_id));
create policy read_contracts    on contracts     for select using (is_member_of(league_id));
create policy read_loans        on loans         for select using (is_member_of(league_id));
create policy read_competitions on competitions  for select using (is_member_of(league_id));
create policy read_fixtures     on fixtures      for select using (is_member_of(league_id));
create policy read_results      on match_results for select using (is_member_of(league_id));
create policy read_auctions     on auctions      for select using (is_member_of(league_id));
create policy read_negotiations on negotiations  for select using (is_member_of(league_id));
create policy read_lineups      on lineups       for select using (is_member_of(league_id));
create policy read_notifications on notifications for select using (is_member_of(league_id));

-- Le offerte si leggono, ma il massimo dichiarato dagli avversari non deve trapelare:
-- il client vede solo le proprie. Il prezzo corrente lo pubblica l'asta.
create policy read_own_bids on bids for select
    using (owns_club(club_id));

-- Scrittura diretta: solo la propria formazione. Tutto il resto passa dal tick o dalle
-- funzioni con security definer.
create policy write_own_lineup on lineups for all
    using (owns_club(club_id)) with check (owns_club(club_id));

-- Le offerte NON si inseriscono a mano: solo tramite place_bid(), che controlla i fondi
-- con il lock. Nessuna policy di insert su bids e' voluta.

-- =====================================================================================
-- FINE
-- =====================================================================================
