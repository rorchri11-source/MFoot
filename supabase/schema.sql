-- =====================================================================================
--  M F O O T  —  L O   S C H E M A   I N T E R O
-- =====================================================================================
--
--  Questo file e' il database. Tutto: tabelle, indici, permessi, funzioni.
--  Si esegue su un progetto Supabase vuoto e la lega e' pronta.
--
--  ## Perche' un file solo, dal 2026-08-25
--
--  Perche' prima erano trentuno, numerati da `0001` a `0031`, e la storia che
--  raccontavano non serviva a nessuno: quattro versioni di `start_auction`, tre di
--  `place_bid`, due di `buy_player`. Per sapere cosa faceva davvero una funzione bisognava
--  leggere tutti i file in ordine e tenere a mente quale vinceva.
--
--  Le migrazioni incrementali servono quando un database in produzione non si puo'
--  ricreare. Qui si puo': e' una lega privata fra amici, e il mondo si rigenera in un
--  minuto. Il costo di tenerle era reale — la trappola di PostgREST sulle colonne nuove e'
--  costata due volte l'inservibilita' dell'app — e il beneficio era zero.
--
--  Il proprietario lo ha deciso il 2026-08-25: «sono disposto a rimettere da capo ogni
--  migrazione basta che non siano 30 separate inutilmente».
--
--  ## Come si rimette in piedi
--
--  Sull'SQL Editor di Supabase, in un progetto nuovo o svuotato:
--
--      1. incolla questo file per intero
--      2. eseguilo
--      3. crea la lega dall'app
--
--  E' scritto per essere **rieseguibile**: `if not exists`, `create or replace`, `drop
--  policy if exists`. Rilanciarlo su un database gia' a posto non rompe niente e non
--  cancella niente. Non ricrea pero' le colonne mancanti su tabelle gia' esistenti: se
--  arrivi da uno schema vecchio, la strada e' svuotare e ripartire.
--
--  ## L'ordine, e perche' e' questo
--
--      1. le tabelle
--      2. le due funzioni dei permessi   — leggono le tabelle, quindi vengono dopo
--      3. gli indici
--      4. le policy di sicurezza
--      5. le viste
--      6. le funzioni che l'app chiama
--      7. i permessi di esecuzione
--      8. l'orologio che sveglia il server ogni cinque minuti
--
-- =====================================================================================


-- =====================================================================================
--  1. LE TABELLE
--
--  Le colonne che nelle vecchie migrazioni arrivavano da un `alter table add column`
--  stanno qui dentro, al loro posto. Erano quindici sparse su nove file, e ognuna era una
--  mina per PostgREST: chiedere in una SELECT condivisa una colonna che il database non ha
--  ancora fa fallire **l'intera query**, quindi non si legge piu' la lega — non una
--  schermata, tutto.
-- =====================================================================================

create table if not exists leagues (
    id              bigserial primary key,
    name            text        not null,
    -- Mai la password in chiaro: si confronta l'hash.
    access_code_hash text       not null,
    -- Il codice in chiaro, che solo l'admin puo' rileggere per dettarlo a un amico.
    access_code     text,
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
    -- In quale divisione gioca: 1 e' la piu' alta.
    division_level    integer not null default 1,
    -- Se valorizzato, questo club e' la Primavera di quello indicato.
    parent_club_id    bigint  references clubs(id) on delete cascade,
    created_at        timestamptz not null default now(),
    unique (league_id, name)
);

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
    -- L'ultima giornata in cui questo ragazzo si e' allenato in Primavera: e' cio' che
    -- impedisce dodici allenamenti in un'ora.
    trained_on        integer,
    -- Un giocatore ha un solo contratto attivo per volta.
    unique (player_id)
);

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

create table if not exists competitions (
    id           bigserial primary key,
    league_id    bigint not null references leagues(id) on delete cascade,
    name         text   not null,
    type         text   not null check (type in
                        ('GIRONE', 'ELIMINAZIONE_DIRETTA', 'GIRONI_PIU_ELIMINAZIONE')),
    config       jsonb  not null,
    participants bigint[] not null default '{}',
    -- 'UFFICIALE' oppure 'AMICHEVOLE': le amichevoli non contano per la classifica e,
    -- se l'admin lo vuole, nemmeno per la crescita.
    kind         text   not null default 'UFFICIALE'
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
    -- Quando riprende una partita ferma all'intervallo. Null se non e' ferma.
    resume_at      timestamptz,
    -- Come stavano le due squadre al fischio d'inizio: serve a ricostruire il primo tempo
    -- identico dopo che il manager ha cambiato qualcosa nella finestra.
    first_half     jsonb,
    check (home_club_id <> away_club_id)
);

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
    final_price    integer,
    -- Il prezzo **pubblico**: quanto si sta pagando adesso. Diverso dai massimi, che
    -- restano segreti fino alla chiusura.
    current_price  integer not null default 0,
    leader_club_id bigint  references clubs(id) on delete set null,
    bid_count      integer not null default 0
);

create table if not exists bids (
    id         bigserial primary key,
    auction_id bigint  not null references auctions(id) on delete cascade,
    club_id    bigint  not null references clubs(id) on delete cascade,
    -- L'offerta MASSIMA automatica, non il rilancio. Non e' mai visibile agli altri:
    -- il prezzo sale solo quanto serve per superare il secondo miglior massimo.
    max_amount integer not null check (max_amount > 0),
    -- Il prezzo che questa offerta ha reso pubblico: quello che gli altri hanno visto.
    -- E' l'unico numero di questa riga che si puo' mostrare mentre l'asta e' aperta.
    public_price integer,
    placed_at  timestamptz not null default now()
);

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
    -- I cinque incarichi. Null vuol dire "scegli tu": il motore prende il piu' adatto
    -- fra gli undici in campo.
    captain_id         bigint,
    penalty_taker_id   bigint,
    corner_taker_id    bigint,
    free_kick_taker_id bigint,
    long_ball_taker_id bigint,
    updated_at timestamptz not null default now()
);

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

create table if not exists trades (
    id           bigserial primary key,
    league_id    bigint not null references leagues(id) on delete cascade,
    from_club    bigint not null references clubs(id) on delete cascade,
    to_club      bigint not null references clubs(id) on delete cascade,
    -- Chi propone cede questi, e chiede quelli.
    offered      bigint[] not null default '{}',
    wanted       bigint[] not null default '{}',
    -- Positivo: chi propone aggiunge denaro. Negativo: ne chiede.
    --
    -- Un numero con un segno invece di due campi separati. Con "offro" e "chiedo" distinti
    -- si potrebbero riempire tutti e due, e una proposta che offre venti milioni e ne chiede
    -- trenta non vuol dire niente.
    cash         integer not null default 0,
    message      text    not null default '',
    -- Cosa si sta proponendo: uno scambio, un prestito, un'amichevole.
    kind         text    not null default 'SCAMBIO',
    -- I dettagli che valgono solo per certi tipi: durata del prestito, data dell'amichevole.
    terms        jsonb   not null default '{}'::jsonb,
    -- La proposta a cui questa risponde, se e' una controproposta. E' la catena che
    -- permette di leggere una trattativa dall'inizio.
    replies_to   bigint  references trades(id) on delete set null,
    status       text    not null default 'PROPOSTA'
                 check (status in ('PROPOSTA','ACCETTATA','RIFIUTATA','RITIRATA','SCADUTA')),
    -- Perche' e' stata rifiutata: serve a dirlo a chi l'ha fatta.
    answer       text    not null default '',
    created_at   timestamptz not null default now(),
    answered_at  timestamptz,

    constraint trade_non_a_se_stessi check (from_club <> to_club)
);

create table if not exists promises (
    id          bigserial primary key,
    league_id   bigint not null references leagues(id) on delete cascade,
    club_id     bigint not null references clubs(id) on delete cascade,
    player_id   bigint not null references players(id) on delete cascade,
    type        text   not null check (type in
                    ('TITOLARE_PER_PARTITE','RINNOVO_ENTRO','CESSIONE_ENTRO')),
    made_on     integer not null,
    deadline    integer not null,
    target      integer not null default 0,
    progress    integer not null default 0,
    status      text   not null default 'IN_CORSO'
                check (status in ('IN_CORSO','MANTENUTA','TRADITA')),
    closed_at   timestamptz,
    created_at  timestamptz not null default now()
);

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

create table if not exists conversations (
    id           bigserial primary key,
    league_id    bigint not null references leagues(id) on delete cascade,
    club_id      bigint not null references clubs(id) on delete cascade,
    player_id    bigint not null references players(id) on delete cascade,

    topic        text   not null,

    -- Il fatto che lo ha aperto, gia' in italiano: "3 partite senza scendere in campo:
    -- 17a, 18a, 19a". Si salva scritto invece di ricostruirlo perche' il fatto e' vero
    -- **nel momento in cui e' successo**: fra due giornate quelle panchine sono ancora
    -- accadute, ma la query che le trovava non le troverebbe piu'.
    cause        text   not null default '',

    opened_on    integer not null,

    -- Vero quando sei stato tu a convocarlo e lui non aveva niente da dire. Vale un terzo
    -- sul morale, ed e' il motivo per cui la convocazione libera non e' un rubinetto.
    spontaneous  boolean not null default false,

    status       text   not null default 'APERTA'
                 check (status in ('APERTA', 'CHIUSA')),
    tone         text   not null default '',
    morale_delta integer not null default 0,

    created_at   timestamptz not null default now(),
    closed_at    timestamptz
);

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

create table if not exists scouting_missions (
    id          bigserial primary key,
    league_id   bigint not null references leagues(id) on delete cascade,
    club_id     bigint not null references clubs(id) on delete cascade,
    staff_id    bigint not null references staff(id) on delete cascade,

    country     text   not null,
    position    text   not null,

    started_at  timestamptz not null default now(),
    ready_at    timestamptz not null,

    status      text   not null default 'IN_CORSO'
                check (status in ('IN_CORSO', 'CONCLUSA', 'A_VUOTO')),
    found_player_id bigint references players(id) on delete set null,
    closed_at   timestamptz
);

create table if not exists club_objectives (
    id          bigserial primary key,
    league_id   bigint  not null references leagues(id) on delete cascade,
    club_id     bigint  not null references clubs(id)   on delete cascade,
    -- Da quale stagione decorre. La stagione e' un numero che cresce: la prima e' 1.
    season      integer not null,
    kind        text    not null,
    target      integer not null default 0,
    reward      integer not null default 0 check (reward >= 0),
    seasons     integer not null default 1 check (seasons >= 1),
    status      text    not null default 'IN_CORSO'
                        check (status in ('IN_CORSO', 'RAGGIUNTO', 'FALLITO')),
    -- Quanto e' stato effettivamente pagato. Zero su un obiettivo fallito, ed e' il punto.
    paid        integer not null default 0,
    assigned_at timestamptz not null default now(),
    resolved_at timestamptz,

    -- Lo stesso obiettivo non si assegna due volte allo stesso club nella stessa stagione:
    -- senza, un doppio tocco sul pulsante raddoppierebbe il montepremi.
    unique (club_id, season, kind)
);

create table if not exists listings (
    id             bigserial primary key,
    league_id      bigint  not null references leagues(id) on delete cascade,
    -- L'id del bersaglio: un giocatore, oppure un membro dello staff (vedi target_type).
    -- Nessuna chiave esterna, proprio perche' punta a due tabelle diverse.
    player_id      bigint  not null,
    /*
     * Cosa si sta vendendo.
     *
     * `players` e `staff` hanno due sequenze separate, quindi il giocatore 7 e
     * l'allenatore 7 esistono tutti e due. Senza questa colonna, un listino che contiene
     * entrambi si legge come un elenco di giocatori con dentro delle righe che parlano di
     * qualcun altro — e nessuno se ne accorge finche' non compra.
     */
    target_type    text    not null default 'player'
                           check (target_type in ('player', 'staff')),
    -- Null significa svincolato: non lo vende nessuno e l'incasso non va a nessun club.
    seller_club_id bigint  references clubs(id) on delete cascade,
    price          integer not null check (price >= 1),
    listed_at      timestamptz not null default now(),
    status         text    not null default 'APERTO'
                           check (status in ('APERTO', 'VENDUTO', 'RITIRATO'))
);

create table if not exists purchases (
    id                bigserial primary key,
    league_id         bigint  not null references leagues(id) on delete cascade,
    player_id         bigint  not null references players(id) on delete cascade,
    buyer_club_id     bigint  not null references clubs(id) on delete cascade,
    seller_club_id    bigint  references clubs(id) on delete set null,
    price             integer not null check (price >= 1),
    bought_at         timestamptz not null default now(),
    -- L'ora esatta in cui il giocatore diventa definitivo. Nota **dal primo istante**:
    -- e' cio' che rende accettabile comprare senza aspettare.
    contestable_until timestamptz not null,
    status            text    not null default 'IN_FINESTRA'
                              check (status in ('IN_FINESTRA', 'CONFERMATO', 'CONTESTATO', 'REVOCATO')),
    auction_id        bigint  references auctions(id) on delete set null
);


-- =====================================================================================
--  2. I PERMESSI, IN DUE FUNZIONI
--
--  Ogni policy si riduce a una di queste due domande: sei di questa lega, e questo club e'
--  tuo. Scritte una volta sola perche' una regola di accesso ripetuta in venti punti e'
--  una regola che prima o poi diverge in uno di quei venti.
--
--  Sono `security definer` e leggono `league_members` scavalcando la sua stessa policy: se
--  non lo facessero, controllare l'appartenenza richiederebbe di essere gia' dentro.
-- =====================================================================================

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
        select 1 from clubs
        where id = p_club_id and owner_user_id = auth.uid()
    );
$$;


-- =====================================================================================
--  3. GLI INDICI
--
--  Uno per ogni domanda che il gioco fa spesso. Il criterio non e' "indicizziamo tutto":
--  ogni indice rallenta le scritture, e qui si scrive parecchio.
--
--  ## Quelli aggiunti il 2026-08-25, e perche'
--
--  Il giro del server durava fra i sette e i nove minuti e ne aveva dieci prima di essere
--  ucciso: tredici esecuzioni su venti finivano annullate a meta' transazione, buttando
--  via tutto quello che avevano fatto. Il rimedio principale sta nel codice del tick — meno
--  viaggi verso il database, un budget di tempo che lo fa fermare prima di essere ammazzato
--  — ma alcune domande erano anche senza indice, e sono queste.
-- =====================================================================================

create index if not exists idx_members_user on league_members(user_id);
create index if not exists idx_clubs_league on clubs(league_id);
create index if not exists idx_clubs_owner on clubs(owner_user_id);
create index if not exists idx_clubs_division on clubs(league_id, division_level);
create index if not exists idx_clubs_parent on clubs(parent_club_id);

create index if not exists idx_players_league on players(league_id);
create index if not exists idx_players_position on players(league_id, primary_position);

-- Gli svincolati sopra una certa eta': la domanda che il listino fa a ogni giro, su
-- milletrecento giocatori.
create index if not exists idx_players_eta on players(league_id, age);

create index if not exists idx_staff_league on staff(league_id);

-- «Quante stelle ha l'allenatore di questo club», chiesta una volta per giocatore sceso
-- in campo prima che il tick imparasse a ricordarsela.
create index if not exists idx_staff_club_ruolo on staff(club_id, role);

create index if not exists idx_contracts_club on contracts(club_id);
create index if not exists idx_contracts_expiry on contracts(league_id, expires_on);

-- La rosa di un club, che e' la lettura piu' frequente di tutto il tick.
create index if not exists idx_contracts_club_squad on contracts(club_id, squad);

create index if not exists idx_loans_expiry on loans(league_id, ends_on) where active;

create index if not exists idx_fixtures_pending on fixtures(league_id, kickoff) where not played;
create index if not exists idx_fixtures_paused on fixtures(league_id, resume_at)
    where resume_at is not null and not played;
create index if not exists idx_fixtures_competition on fixtures(competition_id, match_day);

create index if not exists idx_results_league on match_results(league_id);

create index if not exists idx_auctions_open on auctions(league_id, ends_at) where status = 'APERTA';

-- «Questo giocatore e' gia' all'asta?», chiesta prima di ogni acquisto a prezzo fisso e
-- una volta per svincolato quando si allinea il listino.
create index if not exists idx_auctions_bersaglio on auctions(target_type, target_id, status);

create index if not exists idx_bids_auction on bids(auction_id, max_amount desc, placed_at);

create index if not exists idx_negotiations_open on negotiations(league_id, expires_at)
    where status in ('IN_ATTESA', 'CONTROPROPOSTA');

create index if not exists idx_ai_wake on ai_states(league_id, next_wake_at);

create index if not exists idx_notifications_pending on notifications(league_id, created_at)
    where not delivered;

create index if not exists idx_trades_league on trades(league_id, status);
create index if not exists idx_trades_from on trades(from_club, status);
create index if not exists idx_trades_to on trades(to_club, status);
create index if not exists idx_trades_catena on trades(replies_to);

create index if not exists idx_promises_open on promises(league_id, status);
create unique index if not exists idx_promises_una_per_giocatore
    on promises(player_id) where status = 'IN_CORSO';

create index if not exists idx_appearances_club on appearances(club_id, match_day desc);
create index if not exists idx_appearances_player on appearances(league_id, player_id, match_day desc);

-- «Quanti minuti ha visto giocare questo club, per giocatore»: e' una somma raggruppata,
-- e senza questo indice era una scansione della tabella piu' grande del database.
create index if not exists idx_appearances_minuti on appearances(club_id, player_id);

create index if not exists idx_conversations_club on conversations(club_id, status);
create index if not exists idx_conversations_ultimo on conversations(player_id, opened_on desc);
create unique index if not exists idx_conversations_uno_aperto
    on conversations(player_id) where status = 'APERTA';

create index if not exists idx_scouting_club on scouting(club_id);

create index if not exists idx_missions_open on scouting_missions(league_id, status);
-- Un osservatore, una missione per volta. E' il vincolo che rende gli osservatori una
-- risorsa e non un pulsante.
create unique index if not exists idx_missions_un_osservatore
    on scouting_missions(staff_id) where status = 'IN_CORSO';

create index if not exists idx_objectives_club on club_objectives(club_id, season);
create index if not exists idx_objectives_league on club_objectives(league_id, status);

create index if not exists idx_listings_aperti on listings(league_id, status);
-- Il listino di un tipo solo: giocatori oppure staff, mai mescolati.
create index if not exists idx_listings_tipo on listings(league_id, target_type, status);
create unique index if not exists idx_listings_uno_per_bersaglio
    on listings(target_type, player_id) where status = 'APERTO';

create index if not exists idx_purchases_player on purchases(player_id);
create index if not exists idx_purchases_finestra on purchases(league_id, contestable_until)
    where status in ('IN_FINESTRA', 'CONTESTATO');


-- =====================================================================================
--  4. LA SICUREZZA
--
--  Row Level Security su tutto. La regola generale e' semplice: **si vede quello che
--  succede nella propria lega**, e si scrive solo attraverso le funzioni piu' sotto, che
--  sono `security definer` e controllano una cosa per volta.
--
--  Le due eccezioni sono informazioni che rovinerebbero il gioco se si vedessero:
--
--  - `players.potential_min` e `potential_max` non escono mai. Il client legge la vista
--    `players_public`, che non li contiene. E' il meccanismo che sostituisce l'emozione
--    dei nomi noti con la scommessa, e basta una riga sbagliata qui per spegnerlo.
--  - le offerte massime delle aste aperte non si vedono. Si vede il prezzo pubblico e chi
--    e' in testa, non fino a quanto e' disposto a spingersi.
-- =====================================================================================

alter table leagues           enable row level security;
alter table league_members    enable row level security;
alter table clubs             enable row level security;
alter table players           enable row level security;
alter table staff             enable row level security;
alter table contracts         enable row level security;
alter table loans             enable row level security;
alter table competitions      enable row level security;
alter table fixtures          enable row level security;
alter table match_results     enable row level security;
alter table auctions          enable row level security;
alter table bids              enable row level security;
alter table negotiations      enable row level security;
alter table lineups           enable row level security;
alter table ai_states         enable row level security;
alter table tick_state        enable row level security;
alter table notifications     enable row level security;
alter table trades            enable row level security;
alter table promises          enable row level security;
alter table appearances       enable row level security;
alter table conversations     enable row level security;
alter table scouting          enable row level security;
alter table scouting_missions enable row level security;
alter table club_objectives   enable row level security;
alter table listings          enable row level security;
alter table purchases         enable row level security;

drop policy if exists read_leagues           on leagues;
drop policy if exists read_members           on league_members;
drop policy if exists read_clubs             on clubs;
drop policy if exists read_players           on players;
drop policy if exists read_staff             on staff;
drop policy if exists read_contracts         on contracts;
drop policy if exists read_loans             on loans;
drop policy if exists read_competitions      on competitions;
drop policy if exists read_fixtures          on fixtures;
drop policy if exists read_results           on match_results;
drop policy if exists read_auctions          on auctions;
drop policy if exists read_own_bids          on bids;
drop policy if exists read_negotiations      on negotiations;
drop policy if exists read_lineups           on lineups;
drop policy if exists write_own_lineup       on lineups;
drop policy if exists read_tick_state        on tick_state;
drop policy if exists read_notifications     on notifications;
drop policy if exists read_own_trades        on trades;
drop policy if exists read_own_promises      on promises;
drop policy if exists read_appearances       on appearances;
drop policy if exists read_own_conversations on conversations;
drop policy if exists read_own_scouting      on scouting;
drop policy if exists read_own_missions      on scouting_missions;
drop policy if exists read_objectives        on club_objectives;
drop policy if exists read_listings          on listings;
drop policy if exists read_purchases         on purchases;

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

create policy read_own_bids on bids for select
    using (
        owns_club(club_id)
        or exists (
            select 1 from auctions a
            where a.id = bids.auction_id
              and a.status <> 'APERTA'
              and is_member_of(a.league_id)
        )
    );

create policy write_own_lineup on lineups for all
    using (owns_club(club_id)) with check (owns_club(club_id));

create policy read_tick_state on tick_state for select
    using (is_member_of(league_id));

create policy read_own_trades on trades for select
    using (owns_club(from_club) or owns_club(to_club));

create policy read_own_promises on promises for select
    using (owns_club(club_id));

create policy read_appearances on appearances for select
    using (is_member_of(league_id));

create policy read_own_conversations on conversations for select
    using (owns_club(club_id));

create policy read_own_scouting on scouting for select
    using (owns_club(club_id));

create policy read_own_missions on scouting_missions for select
    using (owns_club(club_id));

create policy read_objectives on club_objectives for select using (is_member_of(league_id));

create policy read_listings  on listings  for select using (is_member_of(league_id));

create policy read_purchases on purchases for select using (is_member_of(league_id));



-- =====================================================================================
--  5. LE VISTE
--
--  Due, e servono tutte e due a **non far vedere qualcosa**.
-- =====================================================================================

/*
 * I giocatori come li vede il client: senza potenziale.
 *
 * `potential_min` e `potential_max` non compaiono, ed e' l'unica ragione per cui questa
 * vista esiste. Il client vede una forbice **stimata**, che si stringe con i minuti
 * giocati e con il lavoro degli osservatori: e' cio' che sostituisce l'emozione dei nomi
 * noti con la scommessa.
 *
 * Chi aggiunge una colonna a `players` e la vuole nell'app deve aggiungerla anche qui.
 * Chi ce la aggiunge per sbaglio insieme al potenziale ha spento il gioco.
 */
create or replace view players_public as
select
    id, league_id, first_name, last_name, nationality, age,
    primary_position, secondary_positions, attributes,
    weak_foot, skill_stars, traits,
    stamina, morale, form, is_custom, injured_until, overall,
    minutes_observed
from players;

/*
 * E la vista deve girare con i permessi di **chi la interroga**, non del proprietario.
 *
 * ## Perche' questa riga vale piu' di quanto sembri
 *
 * Una vista, di regola, gira con i permessi di chi l'ha creata: quindi **non applica** la
 * policy `read_players`. Senza questa riga un membro di una lega qualsiasi puo' leggere i
 * giocatori di **tutte le altre**. Non e' mai stato un problema pratico — l'app chiede
 * sempre `league_id=eq.…` — ma e' una porta aperta.
 *
 * Era gia' stata chiusa nella vecchia migrazione `0023`. **Unificando le trentuno
 * migrazioni in questo file l'ho persa**: la mia estrazione prendeva l'ultima
 * `create view` di ognuna e questa correzione arrivava da un `alter view` separato, che
 * non e' finito in nessun filtro. Il linter di Supabase l'ha ritrovata come `ERROR`
 * il 2026-08-25, ed e' esattamente il tipo di difetto che rende pericolosa una
 * unificazione fatta a macchina: quello che nessuno controlla perche' "c'era gia'".
 *
 * `security_invoker` esiste da PostgreSQL 15. Se il database fosse piu' vecchio la riga
 * fallisce da sola e non porta giu' il resto del file.
 */
do $$
begin
    execute 'alter view players_public set (security_invoker = true)';
exception when others then
    raise notice 'players_public: security_invoker non applicato (%).', sqlerrm;
end;
$$;

/*
 * Chi ha offerto su un'asta, e quanto ha reso pubblico.
 *
 * `max_amount` non c'e': quello resta segreto finche' l'asta e' aperta, o il rilancio
 * automatico non avrebbe piu' senso. Si vede il prezzo che ogni offerta ha portato in
 * superficie, che e' esattamente quello che si sarebbe visto guardando l'asta dal vivo.
 *
 * `security_barrier` impedisce che una condizione scritta dal client venga valutata
 * **prima** del filtro sull'appartenenza alla lega: senza, si potrebbe far trapelare per
 * differenza il contenuto di righe che non si dovrebbero vedere.
 *
 * ## Perche' qui `security_invoker` NON si mette, al contrario di `players_public`
 *
 * Il linter di Supabase segnala anche questa come `ERROR`, e qui la segnalazione va
 * respinta invece che accolta. La policy `read_own_bids` permette di leggere **solo le
 * proprie** offerte finche' l'asta e' aperta: farla applicare a questa vista la
 * svuoterebbe, e la trasparenza dell'asta — chi ha offerto, e a che prezzo pubblico e'
 * arrivato — sparirebbe proprio mentre serve.
 *
 * Scavalcare quella policy e' l'unico scopo per cui questa vista esiste, e lo fa
 * mostrando **soltanto** `public_price`. Il massimo dichiarato non compare in nessuna
 * colonna: quello resta segreto fino alla chiusura, o il rilancio automatico non avrebbe
 * piu' senso. Il filtro sull'appartenenza alla lega e' scritto a mano nel `where`, ed e'
 * la ragione per cui `security_barrier` c'e'.
 */
drop view if exists auction_bids_public;
create view auction_bids_public
with (security_barrier = true)
as
select
    b.id,
    b.auction_id,
    a.league_id,
    b.club_id,
    c.name       as club_name,
    c.short_name as club_short,
    b.public_price,
    b.placed_at
from bids b
join auctions a on a.id = b.auction_id
join clubs    c on c.id = b.club_id
where is_member_of(a.league_id);


-- =====================================================================================
--  6. LE FUNZIONI
--
--  Tutto quello che l'app puo' **fare**. Sono `security definer` — girano con i permessi
--  di chi le ha create — e ognuna controlla da sola chi la sta chiamando: e' il motivo per
--  cui le tabelle sono in sola lettura e non esiste una scrittura diretta da fuori.
--
--  Qui c'e' una versione sola di ognuna. Nelle vecchie migrazioni ce n'erano quattro di
--  `start_auction` e tre di `place_bid`, e capire quale valesse voleva dire leggere
--  trentuno file in ordine.
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
    v_bid        bigint;
begin
    select * into v_club from clubs where id = p_club_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Club inesistente.');
    end if;

    if v_club.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' il tuo club.');
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

    v_additional := p_max_amount - v_previous;
    v_available  := v_club.credits - v_club.committed_credits;

    if v_additional > v_available then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Crediti insufficienti: servono altri %s, disponibili %s.',
                             v_additional, v_available));
    end if;

    select coalesce(max(max_amount), 0) into v_top_max from bids where auction_id = p_auction_id;
    select coalesce(max(max_amount), 0) into v_second_max
    from bids where auction_id = p_auction_id and max_amount < v_top_max;

    if v_top_max = 0 or v_second_max = 0 then
        v_price := v_auction.starting_price;
    else
        v_price := least(v_second_max + v_min_raise, v_top_max);
    end if;

    if v_previous = 0 and v_top_max > 0 and p_max_amount < v_price + v_min_raise then
        return jsonb_build_object(
            'ok', false,
            'reason', format('L''offerta minima e'' %s crediti.', v_price + v_min_raise));
    end if;

    insert into bids (auction_id, club_id, max_amount)
    values (p_auction_id, p_club_id, p_max_amount)
    returning id into v_bid;

    update clubs set committed_credits = committed_credits + v_additional where id = p_club_id;

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

    select club_id into v_leader
    from bids where auction_id = p_auction_id
    order by max_amount desc, placed_at asc limit 1;

    select coalesce(max(max_amount), 0) into v_top_max from bids where auction_id = p_auction_id;
    select coalesce(max(max_amount), 0) into v_second_max
    from bids where auction_id = p_auction_id and max_amount < v_top_max;

    v_price := case when v_second_max = 0 then v_auction.starting_price
                    else least(v_second_max + v_min_raise, v_top_max) end;

    update auctions
    set current_price  = v_price,
        leader_club_id = v_leader,
        bid_count      = (select count(*) from bids where auction_id = p_auction_id)
    where id = p_auction_id;

    -- La firma sulla cronologia: questo club, a quest'ora, ha portato il prezzo qui.
    update bids set public_price = v_price where id = v_bid;

    return jsonb_build_object(
        'ok', true,
        'leader_club_id', v_leader,
        'current_price', v_price,
        'you_lead', v_leader = p_club_id
    );
end;
$$;

create or replace function create_league(
    p_name        text,
    p_access_code text,
    p_config      jsonb,
    p_seed        bigint,
    p_nickname    text,
    p_players     jsonb,
    p_staff       jsonb,
    p_ai_clubs    jsonb
)
returns bigint
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_user     uuid := auth.uid();
    v_codice   text := trim(p_access_code);
    v_league   bigint;
    v_gia      text;
    v_club     jsonb;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido per creare una lega.'
            using errcode = '28000';
    end if;
    if coalesce(trim(p_name), '') = '' then
        raise exception 'La lega deve avere un nome.' using errcode = '22023';
    end if;
    if coalesce(v_codice, '') = '' then
        raise exception 'Serve un codice d''accesso.' using errcode = '22023';
    end if;
    if jsonb_array_length(coalesce(p_players, '[]'::jsonb)) = 0 then
        raise exception 'Il mondo e'' vuoto: nessun giocatore da caricare.' using errcode = '22023';
    end if;

    -- Il controllo che mancava. Senza, due leghe con lo stesso codice sono legali, e da
    -- quel momento nessuno dei due amici puo' piu' sapere in quale delle due sta entrando.
    select name into v_gia
    from leagues
    where access_code_hash = crypt(v_codice, access_code_hash)
    limit 1;

    if v_gia is not null then
        raise exception 'Il codice "%" e'' gia'' della lega "%". Scegline un altro: due leghe con lo stesso codice manderebbero i tuoi amici in quella sbagliata.',
            v_codice, v_gia using errcode = '23505';
    end if;

    insert into leagues (name, access_code_hash, access_code, config, world_seed,
                         status, current_match_day)
    values (trim(p_name), crypt(v_codice, gen_salt('bf')), v_codice, p_config, p_seed,
            'mercato', 0)
    returning id into v_league;

    insert into league_members (league_id, user_id, nickname, is_admin)
    values (v_league, v_user, coalesce(nullif(trim(p_nickname), ''), 'admin'), true);

    insert into players (
        league_id, first_name, last_name, nationality, age,
        primary_position, secondary_positions, attributes,
        weak_foot, skill_stars, potential_min, potential_max,
        traits, overall
    )
    select
        v_league,
        p ->> 'fn',
        p ->> 'ln',
        p ->> 'nat',
        (p ->> 'age')::int,
        p ->> 'pos',
        coalesce(array(select jsonb_array_elements_text(p -> 'sec')), '{}'),
        p -> 'attr',
        (p ->> 'wf')::int,
        (p ->> 'sk')::int,
        (p ->> 'pmin')::int,
        (p ->> 'pmax')::int,
        coalesce(array(select jsonb_array_elements_text(p -> 'traits')), '{}'),
        (p ->> 'ovr')::int
    from jsonb_array_elements(p_players) as p;

    insert into staff (league_id, first_name, last_name, nationality, role, stars)
    select
        v_league, s ->> 'fn', s ->> 'ln', s ->> 'nat', s ->> 'role', (s ->> 'stars')::int
    from jsonb_array_elements(coalesce(p_staff, '[]'::jsonb)) as s;

    for v_club in select * from jsonb_array_elements(coalesce(p_ai_clubs, '[]'::jsonb))
    loop
        with nuovo as (
            insert into clubs (league_id, name, short_name, is_ai, credits)
            values (
                v_league,
                v_club ->> 'name',
                v_club ->> 'short',
                true,
                (p_config -> 'economy' ->> 'startingCredits')::int
            )
            returning id
        )
        insert into ai_states (club_id, league_id, personality, next_wake_at)
        select nuovo.id, v_league, v_club -> 'personality', now() + (random() * interval '6 hours')
        from nuovo;
    end loop;

    insert into tick_state (league_id, last_processed_at)
    values (v_league, now())
    on conflict (league_id) do update set last_processed_at = excluded.last_processed_at;

    return v_league;
end;
$$;

create or replace function join_league(
    p_access_code text,
    p_nickname    text
)
returns bigint
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_user   uuid := auth.uid();
    v_codice text := trim(p_access_code);
    v_quante integer;
    v_league bigint;
    v_nomi   text;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    select count(*) into v_quante
    from leagues
    where access_code_hash = crypt(v_codice, access_code_hash);

    if v_quante = 0 then
        raise exception 'Codice non valido.' using errcode = '22023';
    end if;

    if v_quante > 1 then
        select string_agg(name, ', ' order by created_at) into v_nomi
        from leagues
        where access_code_hash = crypt(v_codice, access_code_hash);

        raise exception 'Questo codice apre % leghe diverse (%). Chiedi all''amministratore di cambiarne il codice: cosi'' non c''e'' modo di sapere in quale entrare.',
            v_quante, v_nomi using errcode = '22023';
    end if;

    select id into v_league
    from leagues
    where access_code_hash = crypt(v_codice, access_code_hash);

    insert into league_members (league_id, user_id, nickname, is_admin)
    values (v_league, v_user, coalesce(nullif(trim(p_nickname), ''), 'giocatore'), false)
    on conflict (league_id, user_id) do update set nickname = excluded.nickname;

    return v_league;
end;
$$;

create or replace function mfoot_attr_cost(
    p_from  integer,
    p_to    integer,
    p_tiers jsonb
)
returns integer
language plpgsql
immutable
-- Nessuna tabella, nessuno schema: il percorso di ricerca si blocca perche. una funzione
-- senza `search_path` fissato puo. essere dirottata da chi ne crea una omonima in uno
-- schema davanti. Segnalato dal linter di Supabase il 2026-08-25.
set search_path = pg_catalog
as $$
declare
    v_cost  integer := 0;
    v_level integer;
    v_tier  jsonb;
    v_found boolean;
begin
    if p_to <= p_from then
        return 0;
    end if;

    -- Un punto per volta: gli scaglioni sono pochi e il salto e' di qualche decina di
    -- punti, quindi il ciclo costa niente e resta leggibile.
    for v_level in p_from .. (p_to - 1) loop
        v_found := false;
        for v_tier in select * from jsonb_array_elements(p_tiers) loop
            if not v_found and v_level < (v_tier ->> 'upTo')::integer then
                v_cost := v_cost + (v_tier ->> 'cost')::integer;
                v_found := true;
            end if;
        end loop;
        -- Sopra l'ultimo scaglione si paga comunque il prezzo piu' alto, mai zero.
        if not v_found then
            v_cost := v_cost + coalesce(
                (select max((t ->> 'cost')::integer) from jsonb_array_elements(p_tiers) t), 1);
        end if;
    end loop;

    return v_cost;
end;
$$;

create or replace function create_club(
    p_league_id bigint,
    p_name      text,
    p_short     text,
    p_kit       jsonb,
    -- { fn, ln, nat, age, pos, sec: [], inc: {ATTR: punti}, wf, sk }
    p_player    jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_user        uuid := auth.uid();
    v_config      jsonb;
    v_custom      jsonb;
    v_weights     jsonb;
    v_role        jsonb;
    v_position    text;
    v_base        integer;
    v_off_role    integer;
    v_wrong_side  integer;
    v_budget      integer;
    v_star_cost   integer;
    v_start_stars integer;
    v_tiers       jsonb;
    v_is_keeper   boolean;
    v_attr        text;
    v_attr_base   integer;
    v_attr_final  integer;
    v_inc         integer;
    v_spent       integer := 0;
    v_weak_foot   integer;
    v_skill       integer;
    v_age         integer;
    v_attributes  jsonb := '{}'::jsonb;
    v_num         double precision := 0;
    v_den         double precision := 0;
    v_overall     integer;
    v_potential   integer;
    v_club        bigint;
    v_player      bigint;
    v_match_day   integer;
    v_contract_len integer;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    if not exists (
        select 1 from league_members
        where league_id = p_league_id and user_id = v_user
    ) then
        raise exception 'Non fai parte di questa lega.' using errcode = '42501';
    end if;

    -- Un club per persona. Senza questo, un rubinetto che gocciola: si creano club a
    -- ripetizione e la lega si riempie di squadre fantasma con i crediti iniziali.
    if exists (
        select 1 from clubs where league_id = p_league_id and owner_user_id = v_user
    ) then
        raise exception 'Hai gia'' un club in questa lega.' using errcode = '23505';
    end if;

    if coalesce(trim(p_name), '') = '' then
        raise exception 'Il club deve avere un nome.' using errcode = '22023';
    end if;

    select config, current_match_day into v_config, v_match_day
    from leagues where id = p_league_id;

    if v_config is null then
        raise exception 'Lega inesistente.' using errcode = '22023';
    end if;

    v_custom      := coalesce(v_config -> 'custom', '{}'::jsonb);
    v_weights     := coalesce(v_config -> 'roleWeights', '{}'::jsonb);
    v_base        := coalesce((v_custom ->> 'baseOverall')::integer, 65);
    v_off_role    := coalesce((v_custom ->> 'offRoleBase')::integer, 45);
    v_wrong_side  := coalesce((v_custom ->> 'wrongSideBase')::integer, 15);
    v_budget      := coalesce((v_custom ->> 'skillBudget')::integer, 100);
    v_star_cost   := coalesce((v_custom ->> 'starCost')::integer, 10);
    v_start_stars := coalesce((v_custom ->> 'startingStars')::integer, 1);
    v_tiers       := coalesce(v_custom -> 'costTiers', '[{"upTo":99,"cost":1}]'::jsonb);

    v_position := p_player ->> 'pos';
    v_role     := v_weights -> v_position;
    if v_role is null then
        raise exception 'Ruolo sconosciuto: %', v_position using errcode = '22023';
    end if;
    v_is_keeper := (v_position = 'POR');

    v_age       := coalesce((p_player ->> 'age')::integer, 0);
    v_weak_foot := coalesce((p_player ->> 'wf')::integer, v_start_stars);
    v_skill     := coalesce((p_player ->> 'sk')::integer, v_start_stars);

    if v_age < coalesce((v_custom ->> 'minAge')::integer, 16)
       or v_age > coalesce((v_custom ->> 'maxAge')::integer, 23) then
        raise exception 'Eta'' fuori dai limiti della lega: %', v_age using errcode = '22023';
    end if;
    if v_weak_foot not between 1 and 5 or v_skill not between 1 and 5 then
        raise exception 'Le stelle vanno da 1 a 5.' using errcode = '22023';
    end if;

    v_spent := ((v_weak_foot - v_start_stars) + (v_skill - v_start_stars)) * v_star_cost;

    -- Ogni attributo: base secondo il ruolo, piu' i punti dichiarati, al prezzo dovuto.
    for v_attr in
        select unnest(array[
            'TIRO','DRIBBLING','TECNICA','PASSAGGIO','FISICO','VELOCITA',
            'DIFESA','INTERCETTAZIONE','POSIZIONAMENTO','PARATA','USCITA','RIFLESSI'])
    loop
        if jsonb_exists(v_role, v_attr) then
            v_attr_base := v_base;
        elsif (v_attr in ('PARATA','USCITA','RIFLESSI')) <> v_is_keeper then
            v_attr_base := v_wrong_side;
        else
            v_attr_base := v_off_role;
        end if;

        v_inc := coalesce((p_player -> 'inc' ->> v_attr)::integer, 0);
        if v_inc < 0 then
            raise exception 'Non si possono togliere punti sotto la base.' using errcode = '22023';
        end if;

        v_attr_final := least(v_attr_base + v_inc, 99);
        v_spent := v_spent + mfoot_attr_cost(v_attr_base, v_attr_final, v_tiers);
        v_attributes := v_attributes || jsonb_build_object(v_attr, v_attr_final);

        if jsonb_exists(v_role, v_attr) then
            v_num := v_num + v_attr_final * (v_role ->> v_attr)::double precision;
            v_den := v_den + (v_role ->> v_attr)::double precision;
        end if;
    end loop;

    if v_spent > v_budget then
        raise exception 'Budget superato: % punti su %.', v_spent, v_budget
            using errcode = '22023';
    end if;

    v_overall := greatest(1, least(99, round(v_num / nullif(v_den, 0))::integer));
    v_potential := least(
        v_overall + coalesce((v_custom ->> 'potentialBonus')::integer, 18),
        coalesce((v_custom ->> 'potentialCeiling')::integer, 93));

    insert into clubs (league_id, name, short_name, kit, is_ai, owner_user_id, owner_name, credits)
    values (
        p_league_id,
        trim(p_name),
        upper(coalesce(nullif(trim(p_short), ''), substr(trim(p_name), 1, 3))),
        coalesce(p_kit, '{}'::jsonb),
        false,
        v_user,
        (select nickname from league_members
          where league_id = p_league_id and user_id = v_user),
        coalesce((v_config -> 'economy' ->> 'startingCredits')::integer, 300)
    )
    returning id into v_club;

    insert into players (
        league_id, first_name, last_name, nationality, age,
        primary_position, secondary_positions, attributes,
        weak_foot, skill_stars, potential_min, potential_max,
        traits, overall, is_custom
    )
    values (
        p_league_id,
        coalesce(nullif(trim(p_player ->> 'fn'), ''), 'Senza'),
        coalesce(nullif(trim(p_player ->> 'ln'), ''), 'Nome'),
        coalesce(nullif(trim(p_player ->> 'nat'), ''), 'Italia'),
        v_age,
        v_position,
        coalesce(array(select jsonb_array_elements_text(p_player -> 'sec')), '{}'),
        v_attributes,
        v_weak_foot,
        v_skill,
        v_potential,
        v_potential,
        '{}',
        v_overall,
        true
    )
    returning id into v_player;

    update clubs set custom_player_id = v_player where id = v_club;

    -- Il contratto del custom non e' una formalita': senza, il giocatore risulterebbe
    -- svincolato e comparirebbe sul mercato di tutti gli altri.
    v_contract_len := coalesce((v_config -> 'market' ->> 'defaultContractMatchDays')::integer, 19);
    insert into contracts (league_id, player_id, club_id, signed_on, expires_on, price_paid)
    values (p_league_id, v_player, v_club, coalesce(v_match_day, 0),
            coalesce(v_match_day, 0) + v_contract_len, 0);

    insert into lineups (club_id, league_id) values (v_club, p_league_id)
    on conflict (club_id) do nothing;

    return jsonb_build_object(
        'club_id', v_club,
        'player_id', v_player,
        'overall', v_overall,
        'spent', v_spent
    );
end;
$$;

create or replace function start_auction(
    p_league_id      bigint,
    p_target_type    text,
    p_target_id      bigint,
    p_starting_price integer default 1
)
returns bigint
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_user      uuid := auth.uid();
    v_club      bigint;
    v_config    jsonb;
    v_minutes   integer;
    v_max_open  integer;
    v_min_squad integer;
    v_open      integer;
    v_owner     bigint;
    v_rosa      integer;
    v_eta       integer;
    v_auction   bigint;
    v_base      integer;
    v_vendendo  boolean := false;
    v_available integer;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    -- La prima squadra, non la Primavera: e' quella che ha il portafoglio.
    select id into v_club from clubs
    where league_id = p_league_id and owner_user_id = v_user and parent_club_id is null;

    if v_club is null then
        raise exception 'Devi avere un club in questa lega per aprire un''asta.'
            using errcode = '42501';
    end if;

    if p_target_type not in ('player', 'staff') then
        raise exception 'Tipo di asta sconosciuto: %', p_target_type using errcode = '22023';
    end if;

    select config into v_config from leagues where id = p_league_id;
    v_minutes   := coalesce((v_config -> 'market' ->> 'auctionDurationMinutes')::integer, 60);
    v_max_open  := coalesce((v_config -> 'market' ->> 'maxParallelAuctionsPerClub')::integer, 3);
    v_min_squad := coalesce((v_config -> 'setup' ->> 'minSquadSize')::integer, 0);
    v_base      := greatest(1, coalesce(p_starting_price, 1));

    if p_target_type = 'player' then
        select age into v_eta from players where id = p_target_id and league_id = p_league_id;
        if v_eta is null then
            raise exception 'Giocatore inesistente in questa lega.' using errcode = '22023';
        end if;

        select club_id into v_owner from contracts where player_id = p_target_id;

        -- Uno svincolato sotto i vent'anni non si compra: si trova.
        if v_owner is null and v_eta < 20 then
            raise exception 'Sotto i vent''anni non si passa dalle aste: mandaci un osservatore.'
                using errcode = '22023';
        end if;

        if v_owner is not null then
            -- La rosa altrui non si tocca. Vale anche per la propria Primavera: quello che
            -- si fa con i propri giovani e' promuoverli, non batterli all'asta contro se
            -- stessi.
            if v_owner <> v_club then
                raise exception 'Questo giocatore e'' di un altro club: trattaci.'
                    using errcode = '22023';
            end if;

            -- E' mio: lo sto cedendo, non comprando.
            v_vendendo := true;

            select count(*) into v_rosa from contracts where club_id = v_club;
            if v_rosa - 1 < v_min_squad then
                raise exception 'Con questa cessione resteresti sotto il minimo di rosa (%).',
                    v_min_squad using errcode = '22023';
            end if;

            if exists (select 1 from loans where player_id = p_target_id and active) then
                raise exception 'E'' in prestito: non lo puoi mettere all''asta.'
                    using errcode = '22023';
            end if;
        end if;
    else
        if not exists (
            select 1 from staff where id = p_target_id and league_id = p_league_id and club_id is null
        ) then
            raise exception 'Membro dello staff non disponibile.' using errcode = '22023';
        end if;
    end if;

    if exists (
        select 1 from auctions
        where league_id = p_league_id and status = 'APERTA'
          and target_type = p_target_type and target_id = p_target_id
    ) then
        raise exception 'C''e'' gia'' un''asta aperta su questo obiettivo.' using errcode = '23505';
    end if;

    select count(*) into v_open from auctions
    where league_id = p_league_id and status = 'APERTA' and started_by = v_club;

    if v_open >= v_max_open then
        raise exception 'Hai gia'' % aste aperte, il massimo e'' %.', v_open, v_max_open
            using errcode = '22023';
    end if;

    -- Aprire per comprare adesso costa: il prezzo base va impegnato subito. Il controllo
    -- va fatto **prima** di inserire l'asta, o resterebbe aperta un'asta che il suo stesso
    -- proprietario non puo' pagare.
    if not v_vendendo then
        select credits - committed_credits into v_available from clubs where id = v_club;
        if v_available < v_base then
            raise exception
                'Per aprire a % servono % crediti disponibili, ne hai %.',
                v_base, v_base, v_available using errcode = '22023';
        end if;
    end if;

    insert into auctions (league_id, target_type, target_id, started_by, ends_at,
                          starting_price, current_price, status)
    values (p_league_id, p_target_type, p_target_id, v_club,
            now() + make_interval(mins => v_minutes),
            v_base, v_base, 'APERTA')
    returning id into v_auction;

    -- L'offerta di apertura, e i crediti che impegna.
    if not v_vendendo then
        insert into bids (auction_id, club_id, max_amount)
        values (v_auction, v_club, v_base);

        update clubs set committed_credits = committed_credits + v_base where id = v_club;
    end if;

    return v_auction;
end;
$$;

create or replace function create_competition(
    p_league_id    bigint,
    p_name         text,
    p_type         text,
    p_config       jsonb,
    p_participants bigint[],
    -- [{round, label, home, away, matchDay, kickoff, tieId, secondLeg}, ...]
    p_fixtures     jsonb
)
returns bigint
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_user        uuid := auth.uid();
    v_competition bigint;
    v_club        bigint;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    -- Solo l'admin della lega. Non e' un dettaglio: creare una competizione decide chi
    -- gioca contro chi per settimane, e chiunque potesse farlo potrebbe anche escludere
    -- gli altri dal proprio campionato.
    if not exists (
        select 1 from league_members
        where league_id = p_league_id and user_id = v_user and is_admin
    ) then
        raise exception 'Solo l''amministratore della lega puo'' creare competizioni.'
            using errcode = '42501';
    end if;

    if p_type not in ('GIRONE', 'ELIMINAZIONE_DIRETTA', 'GIRONI_PIU_ELIMINAZIONE') then
        raise exception 'Tipo di competizione sconosciuto: %', p_type using errcode = '22023';
    end if;

    if coalesce(array_length(p_participants, 1), 0) < 2 then
        raise exception 'Servono almeno due partecipanti.' using errcode = '22023';
    end if;

    -- Ogni partecipante deve essere un club di questa lega. Senza il controllo si
    -- potrebbe iscrivere il club di un'altra lega e ritrovarsi partite impossibili da
    -- mostrare, con una squadra che nessuno dei due gruppi conosce.
    foreach v_club in array p_participants loop
        if not exists (select 1 from clubs where id = v_club and league_id = p_league_id) then
            raise exception 'Il club % non fa parte di questa lega.', v_club using errcode = '22023';
        end if;
    end loop;

    if jsonb_array_length(coalesce(p_fixtures, '[]'::jsonb)) = 0 then
        raise exception 'Il calendario e'' vuoto: nessuna partita da programmare.'
            using errcode = '22023';
    end if;

    insert into competitions (league_id, name, type, config, participants)
    values (p_league_id, trim(p_name), p_type, coalesce(p_config, '{}'::jsonb), p_participants)
    returning id into v_competition;

    insert into fixtures (league_id, competition_id, round, round_label,
                          home_club_id, away_club_id, match_day, kickoff,
                          tie_id, is_second_leg)
    select
        p_league_id,
        v_competition,
        (f ->> 'round')::integer,
        f ->> 'label',
        (f ->> 'home')::bigint,
        (f ->> 'away')::bigint,
        (f ->> 'matchDay')::integer,
        (f ->> 'kickoff')::timestamptz,
        f ->> 'tieId',
        coalesce((f ->> 'secondLeg')::boolean, false)
    from jsonb_array_elements(p_fixtures) as f;

    -- La lega entra in corso alla prima competizione creata. Il mercato resta aperto:
    -- e' l'admin a deciderne le finestre, non l'esistenza di un calendario.
    update leagues set status = 'in_corso'
    where id = p_league_id and status = 'setup';

    return v_competition;
end;
$$;

create or replace function delete_competition(p_competition_id bigint)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user   uuid := auth.uid();
    v_league bigint;
begin
    select league_id into v_league from competitions where id = p_competition_id;
    if v_league is null then
        raise exception 'Competizione inesistente.' using errcode = '22023';
    end if;

    if not exists (
        select 1 from league_members
        where league_id = v_league and user_id = v_user and is_admin
    ) then
        raise exception 'Solo l''amministratore della lega puo'' cancellare competizioni.'
            using errcode = '42501';
    end if;

    if exists (select 1 from fixtures where competition_id = p_competition_id and played) then
        raise exception 'Questa competizione ha gia'' partite giocate: non si puo'' cancellare.'
            using errcode = '22023';
    end if;

    delete from competitions where id = p_competition_id;
end;
$$;

create or replace function update_league_config(
    p_league_id bigint,
    p_config    jsonb
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    if not exists (
        select 1 from league_members
        where league_id = p_league_id and user_id = v_user and is_admin
    ) then
        raise exception 'Solo l''amministratore della lega puo'' cambiare le regole.'
            using errcode = '42501';
    end if;

    if p_config is null or jsonb_typeof(p_config) <> 'object' then
        raise exception 'Configurazione non valida.' using errcode = '22023';
    end if;

    -- I pesi dei ruoli non si sovrascrivono da qui.
    --
    -- Non sono un'impostazione: vengono da Position.kt e servono al database per
    -- ricalcolare l'overall del giocatore custom. Se una versione piu' vecchia dell'app
    -- salvasse una configurazione senza quel blocco, la creazione dei club smetterebbe di
    -- funzionare per tutta la lega, con un messaggio che non spiega niente. Si tiene
    -- quello che c'e' e si accetta quello che arriva solo se e' presente.
    update leagues
    set config = case
        when p_config ? 'roleWeights' then p_config
        else p_config || jsonb_build_object('roleWeights', config -> 'roleWeights')
    end
    where id = p_league_id;
end;
$$;

create or replace function propose_trade(
    p_from_club bigint,
    p_to_club   bigint,
    p_offered   bigint[],
    p_wanted    bigint[],
    p_cash      integer,
    p_message   text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_from    clubs%rowtype;
    v_to      clubs%rowtype;
    v_id      bigint;
    v_mancano int;
begin
    select * into v_from from clubs where id = p_from_club;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Club inesistente.');
    end if;
    if v_from.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' il tuo club.');
    end if;

    select * into v_to from clubs where id = p_to_club;
    if not found or v_to.league_id <> v_from.league_id then
        return jsonb_build_object('ok', false, 'reason', 'L''altro club non e'' in questa lega.');
    end if;

    if coalesce(array_length(p_offered, 1), 0) = 0
       and coalesce(array_length(p_wanted, 1), 0) = 0
       and coalesce(p_cash, 0) = 0 then
        return jsonb_build_object('ok', false, 'reason', 'La proposta e'' vuota.');
    end if;

    -- I giocatori offerti devono essere davvero miei, e quelli chiesti davvero suoi.
    -- Senza questo controllo si potrebbe offrire il fuoriclasse di un terzo club.
    select count(*) into v_mancano
    from unnest(coalesce(p_offered, '{}')) as pid
    where not exists (
        select 1 from contracts c where c.player_id = pid and c.club_id = p_from_club
    );
    if v_mancano > 0 then
        return jsonb_build_object('ok', false, 'reason', 'Stai offrendo giocatori che non hai.');
    end if;

    select count(*) into v_mancano
    from unnest(coalesce(p_wanted, '{}')) as pid
    where not exists (
        select 1 from contracts c where c.player_id = pid and c.club_id = p_to_club
    );
    if v_mancano > 0 then
        return jsonb_build_object('ok', false, 'reason', 'Chiedi giocatori che non sono suoi.');
    end if;

    -- Il denaro promesso deve esserci adesso. Non viene impegnato: una proposta non e' un
    -- vincolo, e bloccare i crediti su ogni proposta significherebbe non poter piu'
    -- partecipare alle aste per il solo fatto di aver chiesto uno scambio.
    if coalesce(p_cash, 0) > 0
       and p_cash > (v_from.credits - v_from.committed_credits) then
        return jsonb_build_object('ok', false, 'reason', 'Non hai quel denaro libero.');
    end if;

    insert into trades (league_id, from_club, to_club, offered, wanted, cash, message)
    values (
        v_from.league_id, p_from_club, p_to_club,
        coalesce(p_offered, '{}'), coalesce(p_wanted, '{}'),
        coalesce(p_cash, 0), coalesce(trim(p_message), '')
    )
    returning id into v_id;

    return jsonb_build_object('ok', true, 'trade_id', v_id);
end;
$$;

create or replace function respond_trade(
    p_trade_id bigint,
    p_accept   boolean,
    p_answer   text default ''
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_trade   trades%rowtype;
    v_from    clubs%rowtype;
    v_to      clubs%rowtype;
    v_config  jsonb;
    v_min     int;
    v_mancano int;
begin
    select * into v_trade from trades where id = p_trade_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Proposta inesistente.');
    end if;
    if v_trade.status <> 'PROPOSTA' then
        return jsonb_build_object('ok', false, 'reason', 'A questa proposta si e'' gia'' risposto.');
    end if;

    select * into v_to from clubs where id = v_trade.to_club for update;
    if v_to.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' una proposta per te.');
    end if;

    if not p_accept then
        update trades
        set status = 'RIFIUTATA', answer = coalesce(trim(p_answer), ''), answered_at = now()
        where id = p_trade_id;
        return jsonb_build_object('ok', true, 'status', 'RIFIUTATA');
    end if;

    select * into v_from from clubs where id = v_trade.from_club for update;

    -- I giocatori ci sono ancora?
    select count(*) into v_mancano
    from unnest(v_trade.offered) as pid
    where not exists (
        select 1 from contracts c where c.player_id = pid and c.club_id = v_trade.from_club
    );
    if v_mancano > 0 then
        update trades set status = 'SCADUTA',
            answer = 'Nel frattempo ha ceduto i giocatori che offriva.', answered_at = now()
        where id = p_trade_id;
        return jsonb_build_object('ok', false, 'reason',
            'Nel frattempo ha ceduto i giocatori che offriva.');
    end if;

    select count(*) into v_mancano
    from unnest(v_trade.wanted) as pid
    where not exists (
        select 1 from contracts c where c.player_id = pid and c.club_id = v_trade.to_club
    );
    if v_mancano > 0 then
        update trades set status = 'SCADUTA',
            answer = 'Nel frattempo hai ceduto i giocatori che ti chiedeva.', answered_at = now()
        where id = p_trade_id;
        return jsonb_build_object('ok', false, 'reason',
            'Nel frattempo hai ceduto i giocatori che ti chiedeva.');
    end if;

    -- Il denaro c'e' ancora? `cash` positivo lo paga chi propone, negativo chi accetta.
    if v_trade.cash > 0 and v_trade.cash > (v_from.credits - v_from.committed_credits) then
        return jsonb_build_object('ok', false, 'reason',
            'Non ha piu'' il denaro che aveva promesso.');
    end if;
    if v_trade.cash < 0 and (-v_trade.cash) > (v_to.credits - v_to.committed_credits) then
        return jsonb_build_object('ok', false, 'reason', 'Non hai il denaro che ti chiede.');
    end if;

    -- Nessuno dei due deve restare sotto il minimo di rosa: una squadra sotto il minimo
    -- non scende in campo, e uno scambio che la porta li' fa vincere l'affare e perdere il
    -- campionato.
    select config into v_config from leagues where id = v_trade.league_id;
    v_min := coalesce((v_config -> 'setup' ->> 'minSquadSize')::int, 0);

    if (select count(*) from contracts where club_id = v_trade.from_club)
        - coalesce(array_length(v_trade.offered, 1), 0)
        + coalesce(array_length(v_trade.wanted, 1), 0) < v_min then
        return jsonb_build_object('ok', false, 'reason',
            'Lo scambio lo lascerebbe sotto il minimo di rosa.');
    end if;
    if (select count(*) from contracts where club_id = v_trade.to_club)
        - coalesce(array_length(v_trade.wanted, 1), 0)
        + coalesce(array_length(v_trade.offered, 1), 0) < v_min then
        return jsonb_build_object('ok', false, 'reason',
            'Lo scambio ti lascerebbe sotto il minimo di rosa.');
    end if;

    -- Da qui in poi si scrive. Tutto dentro la stessa transazione.
    update contracts set club_id = v_trade.to_club
    where player_id = any(v_trade.offered) and club_id = v_trade.from_club;

    update contracts set club_id = v_trade.from_club
    where player_id = any(v_trade.wanted) and club_id = v_trade.to_club;

    if v_trade.cash <> 0 then
        update clubs set credits = credits - v_trade.cash where id = v_trade.from_club;
        update clubs set credits = credits + v_trade.cash where id = v_trade.to_club;
    end if;

    update trades
    set status = 'ACCETTATA', answer = coalesce(trim(p_answer), ''), answered_at = now()
    where id = p_trade_id;

    return jsonb_build_object('ok', true, 'status', 'ACCETTATA');
end;
$$;

create or replace function withdraw_trade(p_trade_id bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_trade trades%rowtype;
    v_from  clubs%rowtype;
begin
    select * into v_trade from trades where id = p_trade_id for update;
    if not found or v_trade.status <> 'PROPOSTA' then
        return jsonb_build_object('ok', false, 'reason', 'Non c''e'' piu'' niente da ritirare.');
    end if;

    select * into v_from from clubs where id = v_trade.from_club;
    if v_from.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' una tua proposta.');
    end if;

    update trades set status = 'RITIRATA', answered_at = now() where id = p_trade_id;
    return jsonb_build_object('ok', true, 'status', 'RITIRATA');
end;
$$;

create or replace function assign_divisions(
    p_league_id bigint,
    -- [{club_id, level}, ...]
    p_assignments jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user     uuid := auth.uid();
    v_count    int;
    v_expected int;
    v_max      int;
begin
    if not exists (
        select 1 from league_members
        where league_id = p_league_id and user_id = v_user and is_admin
    ) then
        raise exception 'Solo l''amministratore puo'' cambiare le divisioni.'
            using errcode = '42501';
    end if;

    if jsonb_typeof(p_assignments) <> 'array' then
        return jsonb_build_object('ok', false, 'reason', 'Assegnazioni non valide.');
    end if;

    -- Ogni club della lega, una volta sola.
    select count(*) into v_expected from clubs where league_id = p_league_id;
    select count(distinct (a ->> 'club_id')::bigint) into v_count
    from jsonb_array_elements(p_assignments) a;

    if v_count <> v_expected then
        return jsonb_build_object('ok', false, 'reason',
            format('Ci sono %s club e %s assegnazioni: devono coincidere.', v_expected, v_count));
    end if;

    select max((a ->> 'level')::int) into v_max from jsonb_array_elements(p_assignments) a;
    if v_max is null or v_max < 1 then
        return jsonb_build_object('ok', false, 'reason', 'Livelli non validi.');
    end if;

    -- Nessuna divisione puo' restare vuota: un buco nella scala vorrebbe dire promozioni
    -- verso il nulla, e il campionato non tornerebbe piu'.
    if exists (
        select l from generate_series(1, v_max) l
        where not exists (
            select 1 from jsonb_array_elements(p_assignments) a
            where (a ->> 'level')::int = l
        )
    ) then
        return jsonb_build_object('ok', false, 'reason',
            'Una delle divisioni resterebbe senza squadre.');
    end if;

    update clubs c
    set division_level = (a ->> 'level')::int
    from jsonb_array_elements(p_assignments) a
    where c.id = (a ->> 'club_id')::bigint
      and c.league_id = p_league_id;

    return jsonb_build_object('ok', true, 'clubs', v_expected, 'divisions', v_max);
end;
$$;

create or replace function set_player_morale(
    p_player_id bigint,
    p_morale    integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_club bigint;
begin
    -- Il giocatore deve stare in una squadra di chi chiama. Il contratto e' l'unica prova
    -- di proprieta' che esista: `players` non sa a chi appartiene nessuno.
    select c.club_id into v_club
    from contracts c
    join clubs cl on cl.id = c.club_id
    where c.player_id = p_player_id and cl.owner_user_id = auth.uid();

    if v_club is null then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    update players
    set morale = greatest(0, least(100, p_morale))
    where id = p_player_id;

    return jsonb_build_object('ok', true);
end;
$$;

create or replace function make_promise(
    p_player_id bigint,
    p_type      text,
    p_made_on   integer,
    p_deadline  integer,
    p_target    integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_club   bigint;
    v_league bigint;
begin
    select c.club_id, cl.league_id into v_club, v_league
    from contracts c
    join clubs cl on cl.id = c.club_id
    where c.player_id = p_player_id and cl.owner_user_id = auth.uid();

    if v_club is null then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    if exists (select 1 from promises where player_id = p_player_id and status = 'IN_CORSO') then
        return jsonb_build_object('ok', false, 'reason',
            'Gli hai gia'' fatto una promessa che non hai ancora mantenuto.');
    end if;

    insert into promises (league_id, club_id, player_id, type, made_on, deadline, target)
    values (v_league, v_club, p_player_id, p_type, p_made_on, p_deadline, greatest(0, p_target));

    return jsonb_build_object('ok', true);
end;
$$;

create or replace function open_conversation(
    p_player_id bigint,
    p_topic     text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_club    bigint;
    v_league  bigint;
    v_oggi    integer;
    v_ultimo  integer;
    v_attesa  constant integer := 3;
    v_id      bigint;
begin
    select c.club_id, cl.league_id, l.current_match_day
      into v_club, v_league, v_oggi
    from contracts c
    join clubs cl on cl.id = c.club_id
    join leagues l on l.id = cl.league_id
    where c.player_id = p_player_id and cl.owner_user_id = auth.uid();

    if v_club is null then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    if exists (select 1 from conversations
               where player_id = p_player_id and status = 'APERTA') then
        return jsonb_build_object('ok', false, 'reason',
            'Hai gia'' un discorso aperto con lui.');
    end if;

    select max(opened_on) into v_ultimo
    from conversations where player_id = p_player_id;

    if v_ultimo is not null and v_oggi - v_ultimo < v_attesa then
        return jsonb_build_object('ok', false, 'reason',
            format('Gli hai gia'' parlato: aspetta %s giornate.', v_attesa - (v_oggi - v_ultimo)));
    end if;

    insert into conversations (league_id, club_id, player_id, topic, cause,
                               opened_on, spontaneous)
    values (v_league, v_club, p_player_id, p_topic,
            'Lo hai convocato tu.', v_oggi, true)
    returning id into v_id;

    return jsonb_build_object('ok', true, 'id', v_id);
end;
$$;

create or replace function answer_conversation(
    p_conversation_id bigint,
    p_tone            text,
    p_morale_delta    integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_player  bigint;
    v_delta   integer;
    v_morale  integer;
begin
    -- Il limite non e' pignoleria: `p_morale_delta` lo calcola il telefono, e un telefono
    -- puo' dire qualunque numero. Trenta e' oltre il massimo che il motore produce
    -- davvero, quindi non taglia nessuna partita onesta e taglia tutte le altre.
    v_delta := greatest(-30, least(30, coalesce(p_morale_delta, 0)));

    update conversations
    set status = 'CHIUSA',
        tone = coalesce(p_tone, ''),
        morale_delta = v_delta,
        closed_at = now()
    where id = p_conversation_id
      and status = 'APERTA'
      and owns_club(club_id)
    returning player_id into v_player;

    if v_player is null then
        return jsonb_build_object('ok', false, 'reason',
            'Questo discorso non e'' aperto, o non e'' tuo.');
    end if;

    update players
    set morale = greatest(0, least(100, morale + v_delta))
    where id = v_player
    returning morale into v_morale;

    return jsonb_build_object('ok', true, 'morale', v_morale, 'delta', v_delta);
end;
$$;

create or replace function propose_loan(
    p_from_club bigint,
    p_to_club   bigint,
    p_player_id bigint,
    -- Giornate di durata.
    p_match_days integer,
    -- Quanto paga per giornata chi lo prende. Zero e' lecito.
    p_fee        integer,
    p_wage_paid_by_borrower boolean,
    p_can_play_against_owner boolean,
    p_message    text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_from clubs%rowtype;
    v_to   clubs%rowtype;
    v_id   bigint;
begin
    select * into v_from from clubs where id = p_from_club;
    if not found or v_from.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' il tuo club.');
    end if;

    select * into v_to from clubs where id = p_to_club;
    if not found or v_to.league_id <> v_from.league_id then
        return jsonb_build_object('ok', false, 'reason', 'L''altro club non e'' in questa lega.');
    end if;

    if not exists (
        select 1 from contracts where player_id = p_player_id and club_id = p_from_club
    ) then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    if exists (select 1 from loans where player_id = p_player_id and active) then
        return jsonb_build_object('ok', false, 'reason', 'E'' gia'' in prestito.');
    end if;

    if coalesce(p_match_days, 0) < 1 then
        return jsonb_build_object('ok', false, 'reason', 'Un prestito dura almeno una giornata.');
    end if;

    insert into trades (league_id, from_club, to_club, offered, wanted, cash, message,
                        kind, terms)
    values (
        v_from.league_id, p_from_club, p_to_club,
        array[p_player_id]::bigint[], '{}', 0, coalesce(trim(p_message), ''),
        'PRESTITO',
        jsonb_build_object(
            'matchDays', p_match_days,
            'fee', greatest(0, coalesce(p_fee, 0)),
            'wagePaidByBorrower', coalesce(p_wage_paid_by_borrower, true),
            'canPlayAgainstOwner', coalesce(p_can_play_against_owner, false)
        )
    )
    returning id into v_id;

    return jsonb_build_object('ok', true, 'trade_id', v_id);
end;
$$;

create or replace function propose_friendly(
    p_from_club bigint,
    p_to_club   bigint,
    p_kickoff   timestamptz,
    p_message   text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_from clubs%rowtype;
    v_to   clubs%rowtype;
    v_id   bigint;
begin
    select * into v_from from clubs where id = p_from_club;
    if not found or v_from.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' il tuo club.');
    end if;

    select * into v_to from clubs where id = p_to_club;
    if not found or v_to.league_id <> v_from.league_id then
        return jsonb_build_object('ok', false, 'reason', 'L''altro club non e'' in questa lega.');
    end if;

    if p_kickoff is null or p_kickoff <= now() then
        return jsonb_build_object('ok', false, 'reason', 'L''orario e'' gia'' passato.');
    end if;

    -- Nessuno dei due deve avere gia' un impegno in quell'ora. Un'amichevole che si
    -- sovrappone a una partita di campionato manderebbe in campo la stessa rosa due volte,
    -- e la stanchezza pagherebbe il conto nella partita che conta.
    if exists (
        select 1 from fixtures f
        where f.league_id = v_from.league_id and not f.played
          and f.kickoff between p_kickoff - interval '3 hours' and p_kickoff + interval '3 hours'
          and (f.home_club_id in (p_from_club, p_to_club)
            or f.away_club_id in (p_from_club, p_to_club))
    ) then
        return jsonb_build_object('ok', false, 'reason',
            'Una delle due squadre gioca gia'' a quell''ora.');
    end if;

    insert into trades (league_id, from_club, to_club, offered, wanted, cash, message,
                        kind, terms)
    values (
        v_from.league_id, p_from_club, p_to_club, '{}', '{}', 0,
        coalesce(trim(p_message), ''),
        'AMICHEVOLE',
        jsonb_build_object('kickoff', p_kickoff)
    )
    returning id into v_id;

    return jsonb_build_object('ok', true, 'trade_id', v_id);
end;
$$;

create or replace function respond_deal(
    p_trade_id bigint,
    p_accept   boolean,
    p_answer   text default ''
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_trade   trades%rowtype;
    v_to      clubs%rowtype;
    v_player  bigint;
    v_oggi    integer;
    v_comp    bigint;
    v_kickoff timestamptz;
begin
    select * into v_trade from trades where id = p_trade_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Proposta inesistente.');
    end if;
    if v_trade.status <> 'PROPOSTA' then
        return jsonb_build_object('ok', false, 'reason', 'A questa proposta si e'' gia'' risposto.');
    end if;
    if v_trade.kind = 'SCAMBIO' then
        return jsonb_build_object('ok', false, 'reason', 'Uno scambio si accetta con respond_trade.');
    end if;

    select * into v_to from clubs where id = v_trade.to_club for update;
    if v_to.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' una proposta per te.');
    end if;

    if not p_accept then
        update trades set status = 'RIFIUTATA',
            answer = coalesce(trim(p_answer), ''), answered_at = now()
        where id = p_trade_id;
        return jsonb_build_object('ok', true, 'status', 'RIFIUTATA');
    end if;

    select current_match_day into v_oggi from leagues where id = v_trade.league_id;

    if v_trade.kind = 'PRESTITO' then
        v_player := v_trade.offered[1];

        if not exists (
            select 1 from contracts where player_id = v_player and club_id = v_trade.from_club
        ) then
            update trades set status = 'SCADUTA',
                answer = 'Nel frattempo ha ceduto il giocatore.', answered_at = now()
            where id = p_trade_id;
            return jsonb_build_object('ok', false, 'reason', 'Nel frattempo ha ceduto il giocatore.');
        end if;

        if exists (select 1 from loans where player_id = v_player and active) then
            return jsonb_build_object('ok', false, 'reason', 'E'' gia'' in prestito da qualche altra parte.');
        end if;

        insert into loans (league_id, player_id, owner_club_id, borrower_club_id,
                           starts_on, ends_on, fee_per_match_day,
                           wage_paid_by_borrower, can_play_against_owner, active)
        values (
            v_trade.league_id, v_player, v_trade.from_club, v_trade.to_club,
            v_oggi, v_oggi + greatest(1, (v_trade.terms ->> 'matchDays')::int),
            coalesce((v_trade.terms ->> 'fee')::int, 0),
            coalesce((v_trade.terms ->> 'wagePaidByBorrower')::boolean, true),
            coalesce((v_trade.terms ->> 'canPlayAgainstOwner')::boolean, false),
            true
        );

        -- Il contratto si sposta come per uno scambio: e' cio' che fa scendere in campo il
        -- giocatore con la maglia giusta. Quello che distingue un prestito da una cessione
        -- e' la riga in `loans`, che dice a chi torna e quando.
        update contracts set club_id = v_trade.to_club where player_id = v_player;

    elsif v_trade.kind = 'AMICHEVOLE' then
        v_kickoff := (v_trade.terms ->> 'kickoff')::timestamptz;

        if v_kickoff is null or v_kickoff <= now() then
            update trades set status = 'SCADUTA',
                answer = 'L''orario e'' passato.', answered_at = now()
            where id = p_trade_id;
            return jsonb_build_object('ok', false, 'reason', 'L''orario e'' passato.');
        end if;

        select id into v_comp from competitions
        where league_id = v_trade.league_id and kind = 'AMICHEVOLE' limit 1;

        if v_comp is null then
            insert into competitions (league_id, name, type, config, participants, kind)
            values (v_trade.league_id, 'Amichevoli', 'GIRONE', '{}'::jsonb, '{}', 'AMICHEVOLE')
            returning id into v_comp;
        end if;

        -- `match_day` a zero: un'amichevole non appartiene a nessuna giornata, e darle
        -- quella corrente la farebbe contare nelle promesse "titolare per N partite".
        insert into fixtures (league_id, competition_id, round, round_label,
                              home_club_id, away_club_id, match_day, kickoff)
        values (v_trade.league_id, v_comp, 0, 'Amichevole',
                v_trade.from_club, v_trade.to_club, 0, v_kickoff);
    end if;

    update trades set status = 'ACCETTATA',
        answer = coalesce(trim(p_answer), ''), answered_at = now()
    where id = p_trade_id;

    return jsonb_build_object('ok', true, 'status', 'ACCETTATA');
end;
$$;

create or replace function set_squad(
    p_player_id bigint,
    p_squad     text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_club      bigint;
    v_league    bigint;
    v_config    jsonb;
    v_max_age   integer;
    v_min_squad integer;
    v_max_squad integer;
    v_eta       integer;
    v_prima     integer;
    v_custom    boolean;
begin
    if p_squad not in ('prima', 'primavera') then
        return jsonb_build_object('ok', false, 'reason', 'Rosa sconosciuta.');
    end if;

    select c.club_id, cl.league_id into v_club, v_league
    from contracts c
    join clubs cl on cl.id = c.club_id
    where c.player_id = p_player_id and cl.owner_user_id = auth.uid();

    if v_club is null then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    select config into v_config from leagues where id = v_league;
    v_max_age   := coalesce((v_config -> 'rules' ->> 'youthMaxAge')::int, 21);
    v_min_squad := coalesce((v_config -> 'setup' ->> 'minSquadSize')::int, 0);
    v_max_squad := coalesce((v_config -> 'setup' ->> 'maxSquadSize')::int, 99);

    if coalesce((v_config -> 'rules' ->> 'youthTeamEnabled')::boolean, true) = false then
        return jsonb_build_object('ok', false, 'reason',
            'In questa lega la Primavera e'' disattivata.');
    end if;

    select age, is_custom into v_eta, v_custom from players where id = p_player_id;

    select count(*) into v_prima
    from contracts where club_id = v_club and squad = 'prima';

    if p_squad = 'primavera' then
        if v_eta > v_max_age then
            return jsonb_build_object('ok', false, 'reason',
                format('Ha %s anni: in Primavera si sta fino a %s.', v_eta, v_max_age));
        end if;

        -- Il giocatore creato dal proprietario resta in prima squadra.
        --
        -- Il regolamento gli impone di scendere in campo: e' il vincolo che rompe il
        -- circolo vizioso per cui, partendo da 65 in un mondo da 90, non giocherebbe mai
        -- e quindi non crescerebbe mai. Spostarlo in Primavera aggirerebbe l'obbligo dal
        -- lato sbagliato — non facendolo giocare affatto.
        if v_custom then
            return jsonb_build_object('ok', false, 'reason',
                'Il tuo giocatore resta in prima squadra.');
        end if;

        if v_prima - 1 < v_min_squad then
            return jsonb_build_object('ok', false, 'reason',
                format('La prima squadra resterebbe sotto il minimo (%s).', v_min_squad));
        end if;
    else
        if v_prima + 1 > v_max_squad then
            return jsonb_build_object('ok', false, 'reason',
                format('La prima squadra e'' gia'' al massimo (%s).', v_max_squad));
        end if;
    end if;

    update contracts set squad = p_squad where player_id = p_player_id;

    return jsonb_build_object('ok', true, 'squad', p_squad);
end;
$$;

create or replace function create_youth_club(p_parent bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_parent  clubs%rowtype;
    v_config  jsonb;
    v_ultima  integer;
    v_id      bigint;
begin
    select * into v_parent from clubs where id = p_parent;
    if not found or v_parent.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' il tuo club.');
    end if;

    if v_parent.parent_club_id is not null then
        return jsonb_build_object('ok', false, 'reason',
            'Questa e'' gia'' una seconda squadra.');
    end if;

    if exists (select 1 from clubs where parent_club_id = p_parent) then
        return jsonb_build_object('ok', false, 'reason', 'Hai gia'' la tua Primavera.');
    end if;

    select config into v_config from leagues where id = v_parent.league_id;
    if coalesce((v_config -> 'rules' ->> 'youthTeamEnabled')::boolean, true) = false then
        return jsonb_build_object('ok', false, 'reason',
            'In questa lega la Primavera e'' disattivata.');
    end if;

    -- L'ultima divisione della lega. Con un girone unico e' la stessa della prima squadra,
    -- ed e' l'esito giusto: non si puo' partire da sotto se sotto non c'e' niente.
    v_ultima := greatest(1, coalesce((v_config -> 'divisions' ->> 'count')::int, 1));

    -- Stessa maglia e stesso stemma del club padre: e' la sua seconda squadra, non
    -- un'altra societa'. Il nome porta il suffisso, che e' l'unico modo di distinguerle in
    -- una classifica di venti righe.
    insert into clubs (league_id, name, short_name, owner_user_id, owner_name, is_ai,
                       credits, committed_credits, kit, division_level, parent_club_id)
    values (
        v_parent.league_id,
        v_parent.name || ' Primavera',
        left(v_parent.short_name, 3) || 'P',
        v_parent.owner_user_id,
        v_parent.owner_name,
        false,
        0,
        0,
        v_parent.kit,
        v_ultima,
        p_parent
    )
    returning id into v_id;

    return jsonb_build_object('ok', true, 'club_id', v_id);
end;
$$;

create or replace function move_between_squads(
    p_player_id bigint,
    -- true = in prima squadra, false = in Primavera
    p_promote   boolean
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_club      bigint;
    v_prima     bigint;
    v_primavera bigint;
    v_league    bigint;
    v_config    jsonb;
    v_max_age   integer;
    v_min_squad integer;
    v_max_squad integer;
    v_eta       integer;
    v_custom    boolean;
    v_quanti    integer;
begin
    select c.club_id, cl.league_id into v_club, v_league
    from contracts c
    join clubs cl on cl.id = c.club_id
    where c.player_id = p_player_id and cl.owner_user_id = auth.uid();

    if v_club is null then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    -- Qual e' la prima e quale la Primavera, partendo da dove sta adesso.
    select case when parent_club_id is null then id else parent_club_id end
      into v_prima
    from clubs where id = v_club;

    select id into v_primavera from clubs where parent_club_id = v_prima;

    if v_primavera is null then
        return jsonb_build_object('ok', false, 'reason', 'Non hai una Primavera.');
    end if;

    select config into v_config from leagues where id = v_league;
    v_max_age   := coalesce((v_config -> 'rules' ->> 'youthMaxAge')::int, 21);
    v_min_squad := coalesce((v_config -> 'setup' ->> 'minSquadSize')::int, 0);
    v_max_squad := coalesce((v_config -> 'setup' ->> 'maxSquadSize')::int, 99);

    select age, is_custom into v_eta, v_custom from players where id = p_player_id;

    if p_promote then
        if v_club = v_prima then
            return jsonb_build_object('ok', false, 'reason', 'E'' gia'' in prima squadra.');
        end if;

        select count(*) into v_quanti from contracts where club_id = v_prima;
        if v_quanti + 1 > v_max_squad then
            return jsonb_build_object('ok', false, 'reason',
                format('La prima squadra e'' al massimo (%s).', v_max_squad));
        end if;

        update contracts set club_id = v_prima where player_id = p_player_id;
        return jsonb_build_object('ok', true, 'club_id', v_prima);
    end if;

    if v_club = v_primavera then
        return jsonb_build_object('ok', false, 'reason', 'E'' gia'' in Primavera.');
    end if;

    if v_eta > v_max_age then
        return jsonb_build_object('ok', false, 'reason',
            format('Ha %s anni: in Primavera si sta fino a %s.', v_eta, v_max_age));
    end if;

    -- Il giocatore creato dal proprietario resta in prima squadra: il regolamento gli
    -- impone di scendere in campo, ed e' il vincolo che rompe il circolo vizioso per cui,
    -- partendo da 65 in un mondo da 90, non giocherebbe mai e quindi non crescerebbe mai.
    if v_custom then
        return jsonb_build_object('ok', false, 'reason',
            'Il tuo giocatore resta in prima squadra.');
    end if;

    select count(*) into v_quanti from contracts where club_id = v_prima;
    if v_quanti - 1 < v_min_squad then
        return jsonb_build_object('ok', false, 'reason',
            format('La prima squadra resterebbe sotto il minimo (%s).', v_min_squad));
    end if;

    update contracts set club_id = v_primavera where player_id = p_player_id;
    return jsonb_build_object('ok', true, 'club_id', v_primavera);
end;
$$;

create or replace function assign_staff(
    p_staff_id bigint,
    p_club_id  bigint
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_staff staff%rowtype;
    v_club  clubs%rowtype;
    v_prima bigint;
    v_quanti integer;
begin
    select * into v_staff from staff where id = p_staff_id;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Non esiste.');
    end if;

    select * into v_club from clubs where id = p_club_id;
    if not found or v_club.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo club.');
    end if;

    -- Deve gia' essere tuo: si sposta fra le proprie squadre, non si ruba.
    v_prima := coalesce(v_club.parent_club_id, v_club.id);
    if v_staff.club_id is null or
       coalesce((select parent_club_id from clubs where id = v_staff.club_id), v_staff.club_id)
           <> v_prima then
        return jsonb_build_object('ok', false, 'reason', 'Non lavora per te.');
    end if;

    -- Fino a cinque osservatori per club. Gli altri ruoli uno per squadra: due allenatori
    -- non allenano il doppio, e non c'e' nessuna regola sensata per decidere quale dei due
    -- moltiplicatori applicare.
    if v_staff.role = 'OSSERVATORE' then
        select count(*) into v_quanti from staff
        where club_id = p_club_id and role = 'OSSERVATORE' and id <> p_staff_id;
        if v_quanti >= 5 then
            return jsonb_build_object('ok', false, 'reason',
                'Hai gia'' cinque osservatori su questa squadra.');
        end if;
    else
        update staff set club_id = null
        where club_id = p_club_id and role = v_staff.role and id <> p_staff_id;
    end if;

    update staff set club_id = p_club_id where id = p_staff_id;
    return jsonb_build_object('ok', true);
end;
$$;

create or replace function send_scout(
    p_staff_id bigint,
    p_country  text,
    p_position text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_staff    staff%rowtype;
    v_club     clubs%rowtype;
    v_config   jsonb;
    v_stelle   integer;
    v_migliore integer;
    v_peggiore integer;
    v_minuti   integer;
    v_id       bigint;
begin
    select * into v_staff from staff where id = p_staff_id;
    if not found or v_staff.role <> 'OSSERVATORE' then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un osservatore.');
    end if;
    if v_staff.club_id is null then
        return jsonb_build_object('ok', false, 'reason', 'Non lavora per nessuno.');
    end if;

    select * into v_club from clubs where id = v_staff.club_id;
    if v_club.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' il tuo osservatore.');
    end if;

    if exists (select 1 from scouting_missions
               where staff_id = p_staff_id and status = 'IN_CORSO') then
        return jsonb_build_object('ok', false, 'reason', 'E'' gia'' in viaggio.');
    end if;

    /*
     * QUANTO STA VIA, E PERCHE' NON E' PIU' MEZZA SETTIMANA
     *
     * Qui c'era `8 + (5 - stelle) * 10` **ore**: otto per un cinque stelle, quarantotto
     * per un una stella. Due giorni reali per una singola ricerca, in un gioco che gioca
     * due partite al giorno. Chi comprava il primo osservatore che poteva permettersi —
     * cioe' quello scarso, cioe' tutti all'inizio — lo vedeva sparire per il fine
     * settimana.
     *
     * Deciso dal proprietario il 2026-08-25: **due ore al massimo, e le fa il peggiore**.
     *
     * E i due numeri non stanno piu' qui: stanno in `rules.scoutMinutesWorst` e
     * `rules.scoutMinutesBest`, cioe' nella configurazione della lega, come ogni altro
     * numero di gioco. L'autorita' e' `core/world/Scouting.kt`, che fa lo stesso conto con
     * i suoi test; questa e' la copia che serve al server per non fidarsi del telefono
     * sulla data di rientro.
     */
    select config into v_config from leagues where id = v_club.league_id;

    v_stelle   := greatest(1, least(5, v_staff.stars));
    v_peggiore := greatest(coalesce((v_config -> 'rules' ->> 'scoutMinutesWorst')::integer, 120), 1);
    v_migliore := least(
        greatest(coalesce((v_config -> 'rules' ->> 'scoutMinutesBest')::integer, 30), 1),
        v_peggiore);

    v_minuti := greatest(
        round(v_peggiore - ((v_peggiore - v_migliore) / 4.0) * (v_stelle - 1))::integer, 1);

    insert into scouting_missions (league_id, club_id, staff_id, country, position, ready_at)
    values (v_club.league_id, v_staff.club_id, p_staff_id, p_country, p_position,
            now() + make_interval(mins => v_minuti))
    returning id into v_id;

    return jsonb_build_object('ok', true, 'id', v_id, 'minutes', v_minuti);
end;
$$;

create or replace function counter_trade(
    p_trade_id bigint,
    p_cash     integer,
    p_message  text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_trade trades%rowtype;
    v_to    clubs%rowtype;
    v_id    bigint;
begin
    select * into v_trade from trades where id = p_trade_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Proposta inesistente.');
    end if;
    if v_trade.status <> 'PROPOSTA' then
        return jsonb_build_object('ok', false, 'reason', 'A questa proposta si e'' gia'' risposto.');
    end if;

    select * into v_to from clubs where id = v_trade.to_club;
    if v_to.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' una proposta per te.');
    end if;

    -- Il denaro promesso deve esserci adesso, come per una proposta qualsiasi. Non viene
    -- impegnato: una controproposta non e' un vincolo, e bloccare i crediti su ognuna
    -- vorrebbe dire non poter piu' partecipare a un'asta per aver risposto a un messaggio.
    if coalesce(p_cash, 0) > 0
       and p_cash > (v_to.credits - v_to.committed_credits) then
        return jsonb_build_object('ok', false, 'reason', 'Non hai quel denaro libero.');
    end if;

    update trades
    set status = 'CONTROPROPOSTA', answered_at = now(),
        answer = coalesce(trim(p_message), '')
    where id = p_trade_id;

    -- I due lati si scambiano: adesso a proporre e' chi aveva ricevuto. Con i lati
    -- invariati la controproposta finirebbe nella casella sbagliata, ad aspettare una
    -- risposta da chi l'ha scritta.
    insert into trades (league_id, from_club, to_club, offered, wanted, cash, message,
                        kind, terms, replies_to)
    values (
        v_trade.league_id,
        v_trade.to_club,
        v_trade.from_club,
        v_trade.wanted,
        v_trade.offered,
        coalesce(p_cash, 0),
        coalesce(trim(p_message), ''),
        v_trade.kind,
        v_trade.terms,
        p_trade_id
    )
    returning id into v_id;

    return jsonb_build_object('ok', true, 'trade_id', v_id);
end;
$$;

create or replace function set_access_code(p_league_id bigint, p_code text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_codice text := trim(p_code);
    v_gia    text;
begin
    if not exists (
        select 1 from league_members
        where league_id = p_league_id and user_id = auth.uid() and is_admin
    ) then
        return jsonb_build_object('ok', false, 'reason', 'Solo l''amministratore.');
    end if;

    if coalesce(v_codice, '') = '' then
        return jsonb_build_object('ok', false, 'reason', 'Il codice non puo'' essere vuoto.');
    end if;

    select name into v_gia
    from leagues
    where id <> p_league_id and access_code_hash = crypt(v_codice, access_code_hash)
    limit 1;

    if v_gia is not null then
        return jsonb_build_object('ok', false, 'reason',
            format('Il codice e'' gia'' della lega "%s".', v_gia));
    end if;

    update leagues
    set access_code_hash = crypt(v_codice, gen_salt('bf')),
        access_code      = v_codice
    where id = p_league_id;

    return jsonb_build_object('ok', true, 'code', v_codice);
end;
$$;

create or replace function assign_objectives(
    p_league_id bigint,
    p_season    integer,
    -- [{club_id, kind, target, reward, seasons}, ...]
    p_items     jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_quanti integer;
begin
    if not exists (
        select 1 from league_members
        where league_id = p_league_id and user_id = auth.uid() and is_admin
    ) then
        return jsonb_build_object('ok', false, 'reason', 'Solo l''amministratore.');
    end if;

    if p_season is null or p_season < 1 then
        return jsonb_build_object('ok', false, 'reason', 'Stagione non valida.');
    end if;

    -- I club devono essere di questa lega. Senza il controllo, un payload costruito a mano
    -- potrebbe assegnare un premio a un club di un'altra lega.
    if exists (
        select 1
        from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) as i
        left join clubs c on c.id = (i ->> 'club_id')::bigint
        where c.id is null or c.league_id <> p_league_id
    ) then
        return jsonb_build_object('ok', false, 'reason', 'Un club non appartiene a questa lega.');
    end if;

    insert into club_objectives (league_id, club_id, season, kind, target, reward, seasons)
    select
        p_league_id,
        (i ->> 'club_id')::bigint,
        p_season,
        i ->> 'kind',
        coalesce((i ->> 'target')::int, 0),
        greatest(0, coalesce((i ->> 'reward')::int, 0)),
        greatest(1, coalesce((i ->> 'seasons')::int, 1))
    from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) as i
    on conflict (club_id, season, kind) do nothing;

    get diagnostics v_quanti = row_count;

    return jsonb_build_object('ok', true, 'assegnati', v_quanti);
end;
$$;

create or replace function settle_objective(
    p_objective_id bigint,
    p_status       text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_row    club_objectives%rowtype;
    v_pagato integer := 0;
    v_prima  bigint;
begin
    if p_status not in ('RAGGIUNTO', 'FALLITO') then
        return jsonb_build_object('ok', false, 'reason', 'Verdetto sconosciuto.');
    end if;

    select * into v_row from club_objectives where id = p_objective_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Obiettivo inesistente.');
    end if;

    if not exists (
        select 1 from league_members
        where league_id = v_row.league_id and user_id = auth.uid() and is_admin
    ) then
        return jsonb_build_object('ok', false, 'reason', 'Solo l''amministratore.');
    end if;

    if v_row.status <> 'IN_CORSO' then
        return jsonb_build_object('ok', false, 'reason', 'Gia'' chiuso.',
                                  'status', v_row.status, 'paid', v_row.paid);
    end if;

    if p_status = 'RAGGIUNTO' then
        v_pagato := v_row.reward;

        -- Il premio va alla **prima squadra**: la Primavera non ha portafoglio, e
        -- accreditarglielo vorrebbe dire crediti che non si possono spendere.
        select case when parent_club_id is null then id else parent_club_id end
          into v_prima
        from clubs where id = v_row.club_id;

        update clubs set credits = credits + v_pagato where id = v_prima;
    end if;

    update club_objectives
    set status = p_status, paid = v_pagato, resolved_at = now()
    where id = p_objective_id;

    return jsonb_build_object('ok', true, 'paid', v_pagato);
end;
$$;

create or replace function peek_league(p_access_code text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_codice text := trim(p_access_code);
    v_lega   leagues%rowtype;
    v_membri integer;
    v_club   integer;
begin
    if coalesce(v_codice, '') = '' then
        return jsonb_build_object('found', false);
    end if;

    select * into v_lega
    from leagues
    where access_code_hash = crypt(v_codice, access_code_hash)
    limit 1;

    if not found then
        return jsonb_build_object('found', false);
    end if;

    select count(*) into v_membri from league_members where league_id = v_lega.id;
    select count(*) into v_club
    from clubs
    where league_id = v_lega.id and parent_club_id is null;

    return jsonb_build_object(
        'found', true,
        'name', v_lega.name,
        'members', v_membri,
        'clubs', v_club,
        'status', v_lega.status,
        'match_day', v_lega.current_match_day,
        -- Serve a distinguere due leghe che si chiamano uguale, ed e' il caso normale per
        -- chi ne ha create tre di prova chiamandole tutte «Lega».
        'created_at', v_lega.created_at
    );
end;
$$;

create or replace function list_player(
    p_player_id bigint,
    p_price     integer
)
returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user      uuid := auth.uid();
    v_club      bigint;
    v_league    bigint;
    v_custom    boolean;
    v_listing   bigint;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;
    if p_price < 1 then
        raise exception 'Il prezzo minimo e'' 1 credito.' using errcode = '22023';
    end if;

    -- Deve essere suo, e il club deve essere suo.
    select c.club_id, c.league_id into v_club, v_league
    from contracts c
    join clubs cl on cl.id = c.club_id
    where c.player_id = p_player_id and cl.owner_user_id = v_user;

    if v_club is null then
        raise exception 'Puoi mettere in vendita solo i tuoi giocatori.' using errcode = '42501';
    end if;

    -- Il giocatore costruito dal proprietario non si vende e non si svincola. Puo'
    -- essere prestato, ed e' un'altra strada.
    select is_custom into v_custom from players where id = p_player_id;
    if v_custom then
        raise exception 'Il tuo giocatore non si vende.' using errcode = '42501';
    end if;

    -- Un giocatore all'asta non va anche a listino: sarebbe venduto due volte.
    if exists (
        select 1 from auctions
        where target_type = 'player' and target_id = p_player_id and status = 'APERTA'
    ) then
        raise exception 'E'' gia'' all''asta.' using errcode = '23505';
    end if;

    insert into listings (league_id, player_id, seller_club_id, price, target_type)
    values (v_league, p_player_id, v_club, p_price, 'player')
    on conflict (target_type, player_id) where status = 'APERTO'
    do update set price = excluded.price, listed_at = now()
    returning id into v_listing;

    return v_listing;
end;
$$;

create or replace function unlist_player(p_player_id bigint)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user uuid := auth.uid();
begin
    update listings l
    set status = 'RITIRATO'
    from clubs c
    where l.player_id = p_player_id
      and l.target_type = 'player'
      and l.status = 'APERTO'
      and l.seller_club_id = c.id
      and c.owner_user_id = v_user;
end;
$$;

create or replace function buy_player(p_player_id bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user      uuid := auth.uid();
    v_listing   listings%rowtype;
    v_buyer     clubs%rowtype;
    v_config    jsonb;
    v_league    bigint;
    v_seller    bigint;
    v_price     integer;
    v_max_squad integer;
    v_window    integer;
    v_rosa      integer;
    v_available integer;
    v_eta       integer;
    v_day       integer;
    v_duration  integer;
    v_purchase  bigint;
    v_until     timestamptz;
    v_da_listino boolean := false;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    select league_id, age into v_league, v_eta from players where id = p_player_id;
    if v_league is null then
        return jsonb_build_object('ok', false, 'reason', 'Giocatore inesistente.');
    end if;

    select * into v_listing from listings
    where player_id = p_player_id and target_type = 'player' and status = 'APERTO'
    for update;

    if found then
        v_da_listino := true;
        v_seller := v_listing.seller_club_id;
        v_price  := v_listing.price;
    else
        -- Nessuna riga: si compra solo se non e' di nessuno.
        if exists (select 1 from contracts where player_id = p_player_id) then
            return jsonb_build_object('ok', false, 'reason', 'Non e'' in vendita.');
        end if;
        v_seller := null;
        v_price  := mfoot_market_value(p_player_id);
    end if;

    select * into v_buyer from clubs
    where league_id = v_league and owner_user_id = v_user and parent_club_id is null
    for update;

    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Devi avere un club in questa lega.');
    end if;
    if v_seller = v_buyer.id then
        return jsonb_build_object('ok', false, 'reason', 'E'' gia'' tuo.');
    end if;

    select config, current_match_day into v_config, v_day from leagues where id = v_league;

    v_max_squad := coalesce((v_config -> 'setup'  ->> 'maxSquadSize')::integer, 28);
    v_window    := coalesce((v_config -> 'market' ->> 'contestWindowHours')::integer, 12);
    v_duration  := coalesce((v_config -> 'market' ->> 'defaultContractMatchDays')::integer, 19);

    if coalesce((v_config -> 'market' ->> 'instantBuyEnabled')::boolean, true) = false then
        return jsonb_build_object('ok', false, 'reason', 'In questa lega si compra solo all''asta.');
    end if;

    -- Un giocatore all'asta non si compra a prezzo fisso: sarebbe venduto due volte.
    if exists (
        select 1 from auctions
        where target_type = 'player' and target_id = p_player_id and status = 'APERTA'
    ) then
        return jsonb_build_object('ok', false, 'reason', 'E'' all''asta: offri li''.');
    end if;

    -- Gli under 20 senza contratto si trovano con gli osservatori, non a listino.
    if v_seller is null and v_eta < 20 then
        return jsonb_build_object(
            'ok', false,
            'reason', 'Gli under 20 senza contratto si trovano con gli osservatori.');
    end if;

    select count(*) into v_rosa from contracts where club_id = v_buyer.id;
    if v_rosa >= v_max_squad then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Hai gia'' %s giocatori: prima devi liberare un posto.', v_max_squad));
    end if;

    v_available := v_buyer.credits - v_buyer.committed_credits;
    if v_available < v_price then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Ti servono %s crediti, ne hai %s.', v_price, v_available));
    end if;

    update clubs set credits = credits - v_price where id = v_buyer.id;
    if v_seller is not null then
        update clubs set credits = credits + v_price where id = v_seller;
    end if;

    insert into contracts (league_id, player_id, club_id, signed_on, expires_on,
                           wage_per_match_day, price_paid)
    values (v_league, p_player_id, v_buyer.id, v_day, v_day + v_duration, 0, v_price)
    on conflict (player_id) do update
      set club_id    = excluded.club_id,
          signed_on  = excluded.signed_on,
          expires_on = excluded.expires_on,
          price_paid = excluded.price_paid,
          squad      = 'prima';

    if v_da_listino then
        update listings set status = 'VENDUTO' where id = v_listing.id;
    end if;

    v_until := now() + make_interval(hours => v_window);

    insert into purchases (league_id, player_id, buyer_club_id, seller_club_id,
                           price, contestable_until)
    values (v_league, p_player_id, v_buyer.id, v_seller, v_price, v_until)
    returning id into v_purchase;

    return jsonb_build_object(
        'ok', true,
        'purchase_id', v_purchase,
        'price', v_price,
        'contestable_until', v_until);
end;
$$;

create or replace function release_player(p_player_id bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user   uuid := auth.uid();
    v_club   bigint;
    v_league bigint;
    v_custom boolean;
    v_nome   text;
    v_squadra text;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    select c.club_id, c.league_id into v_club, v_league
    from contracts c
    join clubs cl on cl.id = c.club_id
    where c.player_id = p_player_id and cl.owner_user_id = v_user;

    if v_club is null then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    select is_custom, first_name || ' ' || last_name into v_custom, v_nome
    from players where id = p_player_id;

    if v_custom then
        return jsonb_build_object('ok', false, 'reason', 'Il tuo giocatore non si svincola.');
    end if;

    if exists (
        select 1 from purchases
        where player_id = p_player_id
          and status in ('IN_FINESTRA', 'CONTESTATO')
          and now() < contestable_until
    ) then
        return jsonb_build_object(
            'ok', false,
            'reason', 'L''acquisto e'' ancora contestabile: aspetta che si chiuda.');
    end if;

    select short_name into v_squadra from clubs where id = v_club;

    delete from contracts where player_id = p_player_id;
    update listings set status = 'RITIRATO'
    where player_id = p_player_id and status = 'APERTO';

    -- L'annuncio, a ogni club della lega tranne chi lo ha svincolato: lui lo sa gia'.
    insert into notifications (league_id, club_id, kind, urgency, body)
    select v_league, c.id, 'mercato', 'riepilogo',
           format('%s ha svincolato %s: adesso puo'' prenderlo chiunque.', v_squadra, v_nome)
    from clubs c
    where c.league_id = v_league and c.id <> v_club and c.parent_club_id is null;

    return jsonb_build_object('ok', true);
end;
$$;

create or replace function contest_purchase(
    p_purchase_id bigint,
    p_max_amount  integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user      uuid := auth.uid();
    v_purchase  purchases%rowtype;
    v_club      clubs%rowtype;
    v_config    jsonb;
    v_min_raise integer;
    v_minimo    integer;
    v_max_squad integer;
    v_rosa      integer;
    v_available integer;
    v_auction   bigint;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    select * into v_purchase from purchases where id = p_purchase_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Acquisto inesistente.');
    end if;

    select * into v_club from clubs
    where league_id = v_purchase.league_id
      and owner_user_id = v_user
      and parent_club_id is null
    for update;

    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Devi avere un club in questa lega.');
    end if;

    if v_club.id = v_purchase.buyer_club_id then
        return jsonb_build_object('ok', false, 'reason', 'L''hai comprato tu: sei gia'' in testa.');
    end if;
    if v_club.id = v_purchase.seller_club_id then
        return jsonb_build_object('ok', false, 'reason', 'L''hai venduto tu.');
    end if;
    if now() >= v_purchase.contestable_until then
        return jsonb_build_object('ok', false, 'reason', 'Il tempo per contestare e'' finito.');
    end if;
    if v_purchase.status not in ('IN_FINESTRA', 'CONTESTATO') then
        return jsonb_build_object('ok', false, 'reason', 'Questo acquisto e'' gia'' chiuso.');
    end if;

    select config into v_config from leagues where id = v_purchase.league_id;
    v_min_raise := coalesce((v_config -> 'market' ->> 'minimumRaise')::integer, 1);
    v_max_squad := coalesce((v_config -> 'setup'  ->> 'maxSquadSize')::integer, 28);

    if coalesce((v_config -> 'market' ->> 'contestWindowHours')::integer, 12) <= 0 then
        return jsonb_build_object('ok', false, 'reason', 'In questa lega gli acquisti non si contestano.');
    end if;

    v_minimo := v_purchase.price + v_min_raise;
    if p_max_amount < v_minimo then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Per contestare devi offrire almeno %s.', v_minimo));
    end if;

    select count(*) into v_rosa from contracts where club_id = v_club.id;
    if v_rosa >= v_max_squad then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Hai gia'' %s giocatori in rosa.', v_max_squad));
    end if;

    v_available := v_club.credits - v_club.committed_credits;
    if v_available < p_max_amount then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Crediti insufficienti: ne hai %s.', v_available));
    end if;

    -- La prima contestazione crea l'asta; le successive entrano in quella.
    -- **Una sola asta per acquisto**, deciso il 2026-08-24.
    if v_purchase.auction_id is null then
        insert into auctions (league_id, target_type, target_id, started_by,
                              ends_at, starting_price)
        values (v_purchase.league_id, 'player', v_purchase.player_id, v_club.id,
                v_purchase.contestable_until, v_purchase.price)
        returning id into v_auction;

        -- Chi ha comprato entra con la sua offerta, senza rioffrire, e i suoi crediti
        -- risultano impegnati come quelli di chiunque altro.
        insert into bids (auction_id, club_id, max_amount, placed_at)
        values (v_auction, v_purchase.buyer_club_id, v_purchase.price, v_purchase.bought_at);

        update clubs set committed_credits = committed_credits + v_purchase.price
        where id = v_purchase.buyer_club_id;

        update purchases set status = 'CONTESTATO', auction_id = v_auction
        where id = p_purchase_id;
    else
        v_auction := v_purchase.auction_id;
    end if;

    -- L'offerta di chi contesta passa da `place_bid` come tutte le altre: anti-snipe,
    -- blocco fondi e prezzo corrente sono gia' li' dentro, e riscriverli qui vorrebbe
    -- dire due regole d'asta che si separano al primo ritocco.
    return place_bid(v_auction, v_club.id, p_max_amount) || jsonb_build_object('auction_id', v_auction);
end;
$$;

create or replace function list_staff(p_staff_id bigint, p_price integer)
returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user    uuid := auth.uid();
    v_club    bigint;
    v_league  bigint;
    v_listing bigint;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;
    if p_price < 1 then
        raise exception 'Il prezzo minimo e'' 1 credito.' using errcode = '22023';
    end if;

    select s.club_id, s.league_id into v_club, v_league
    from staff s
    join clubs c on c.id = s.club_id
    where s.id = p_staff_id and c.owner_user_id = v_user;

    if v_club is null then
        raise exception 'Puoi mettere in vendita solo il tuo staff.' using errcode = '42501';
    end if;

    insert into listings (league_id, player_id, seller_club_id, price, target_type)
    values (v_league, p_staff_id, v_club, p_price, 'staff')
    on conflict (target_type, player_id) where status = 'APERTO'
    do update set price = excluded.price, listed_at = now()
    returning id into v_listing;

    return v_listing;
end;
$$;

/*
 * Quanto costa un membro dello staff, in crediti.
 *
 * Ricalca `Valuation.staffPrice` di `core`: il tetto e' `economy.staffBudgetShare` — cioe'
 * quanto costa un cinque stelle in frazione del budget — e le stelle sotto scendono con il
 * quadrato, perche' un allenatore da cinque fa crescere i giocatori tre volte piu' di uno
 * da una e la differenza fra il quarto e il quinto e' molto piu' grande di quella fra il
 * primo e il secondo.
 *
 * ## Perche' la formula si duplica, contro la regola d'oro
 *
 * Per la stessa ragione per cui esiste `mfoot_market_value`: **il server non puo' fidarsi
 * del client sui soldi**. Se il prezzo arrivasse dal telefono, chiunque assumerebbe un
 * cinque stelle per un credito.
 *
 * L'autorita' resta Kotlin. Se un giorno i due conti divergono, e' questo file a
 * sbagliare — e il posto dove guardare e' `Valuation.staffPrice`, con `StaffPriceTest` che
 * stampa il listino e fallisce se il migliore esce dalla sua fascia.
 */
create or replace function staff_price(p_staff_id bigint)
returns integer
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_stelle integer;
    v_lega   bigint;
    v_config jsonb;
    v_tetto  double precision;
begin
    select stars, league_id into v_stelle, v_lega from staff where id = p_staff_id;
    if v_lega is null then return null; end if;

    select config into v_config from leagues where id = v_lega;

    v_tetto := coalesce((v_config -> 'economy' ->> 'startingCredits')::double precision, 100000)
             * coalesce((v_config -> 'economy' ->> 'staffBudgetShare')::double precision, 0.04);

    v_stelle := greatest(1, least(5, coalesce(v_stelle, 1)));
    return greatest(round(v_tetto * (v_stelle * v_stelle) / 25.0)::integer, 1);
end;
$$;

-- =====================================================================================
--  ASSUMERE, ANCHE SENZA RIGA DI LISTINO
--
--  IL DIFETTO, DETTO COM'E'
--
--  Fino al 2026-08-25 questa funzione pretendeva una riga in `listings`, e quella riga la
--  scriveva solo il tick. Finche' il tick non aveva girato — cioe' quasi sempre, visto che
--  due giri su tre venivano uccisi dal timeout — sulla schermata dello staff restava
--  «All'asta» e basta. Segnalazione del proprietario, alla lettera: «per prendere lo staff
--  si e' ancora obbligati a farlo tramite asta».
--
--  E' lo stesso errore di progettazione gia' pagato sui giocatori: una funzionalita' che
--  esiste solo dopo che un processo esterno ha girato, per chi gioca **non esiste**.
--
--  LA CORREZIONE
--
--  Chi non lavora per nessuno si assume **sempre**, al prezzo di `staff_price`. La riga di
--  listino serve ancora, ma solo per il caso in cui un club venda un proprio membro dello
--  staff a un prezzo suo: li' il prezzo lo fa il venditore e l'incasso va a lui.
-- =====================================================================================

create or replace function buy_staff(p_staff_id bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user      uuid := auth.uid();
    v_listing   listings%rowtype;
    v_staff     staff%rowtype;
    v_buyer     clubs%rowtype;
    v_lega      bigint;
    v_seller    bigint;
    v_price     integer;
    v_available integer;
    v_da_listino boolean := false;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    select * into v_staff from staff where id = p_staff_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Non esiste.');
    end if;
    v_lega := v_staff.league_id;

    select * into v_listing from listings
    where player_id = p_staff_id and target_type = 'staff' and status = 'APERTO'
    for update;

    if found then
        v_da_listino := true;
        v_seller := v_listing.seller_club_id;
        v_price  := v_listing.price;
    elsif v_staff.club_id is not null then
        -- Lavora per qualcuno e quel qualcuno non lo ha messo in vendita.
        return jsonb_build_object('ok', false, 'reason', 'Non e'' in vendita.');
    else
        v_seller := null;
        v_price  := staff_price(p_staff_id);
    end if;

    -- Uno staff all'asta non si assume a prezzo fisso: sarebbe venduto due volte.
    if exists (
        select 1 from auctions
        where target_type = 'staff' and target_id = p_staff_id and status = 'APERTA'
    ) then
        return jsonb_build_object('ok', false, 'reason', 'E'' all''asta: offri li''.');
    end if;

    select * into v_buyer from clubs
    where league_id = v_lega
      and owner_user_id = v_user
      and parent_club_id is null
    for update;

    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Devi avere un club in questa lega.');
    end if;
    if v_seller = v_buyer.id or v_staff.club_id = v_buyer.id then
        return jsonb_build_object('ok', false, 'reason', 'E'' gia'' tuo.');
    end if;

    v_available := v_buyer.credits - v_buyer.committed_credits;
    if v_available < v_price then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Ti servono %s crediti, ne hai %s.', v_price, v_available));
    end if;

    update clubs set credits = credits - v_price where id = v_buyer.id;
    if v_seller is not null then
        update clubs set credits = credits + v_price where id = v_seller;
    end if;

    update staff set club_id = v_buyer.id where id = p_staff_id;
    if v_da_listino then
        update listings set status = 'VENDUTO' where id = v_listing.id;
    end if;

    -- Lo staff **non** entra nella finestra di contestazione.
    --
    -- Non e' una dimenticanza: la finestra esiste perche' un giocatore svenduto sposta
    -- gli equilibri di un campionato, e per dare a chi lo voleva la possibilita' di
    -- reagire. Un preparatore atletico in piu' non ribalta una stagione, e dodici ore di
    -- attesa su ogni assunzione renderebbero lo staff piu' faticoso dei giocatori — che e'
    -- il contrario di quello che serve, visto che finora non lo comprava nessuno.
    return jsonb_build_object('ok', true, 'price', v_price);
end;
$$;

create or replace function admin_assign_player(
    p_player_id bigint,
    p_club_id   bigint
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user    uuid := auth.uid();
    v_league  bigint;
    v_admin   boolean;
    v_day     integer;
    v_config  jsonb;
    v_duration integer;
begin
    select league_id into v_league from clubs where id = p_club_id;
    if v_league is null then
        return jsonb_build_object('ok', false, 'reason', 'Club inesistente.');
    end if;

    select is_admin into v_admin from league_members
    where league_id = v_league and user_id = v_user;

    if not coalesce(v_admin, false) then
        raise exception 'Solo l''amministratore della lega.' using errcode = '42501';
    end if;

    if not exists (select 1 from players where id = p_player_id and league_id = v_league) then
        return jsonb_build_object('ok', false, 'reason', 'Giocatore di un''altra lega.');
    end if;

    -- Un giocatore dentro una finestra di contestazione non si sposta a mano: sotto c'e'
    -- un'asta aperta con crediti impegnati, e spostarlo lascerebbe chi ha contestato a
    -- inseguire un uomo che non c'e' piu'.
    if exists (
        select 1 from purchases
        where player_id = p_player_id
          and status in ('IN_FINESTRA', 'CONTESTATO')
          and now() < contestable_until
    ) then
        return jsonb_build_object('ok', false, 'reason', 'C''e'' una contestazione in corso.');
    end if;

    select config, current_match_day into v_config, v_day from leagues where id = v_league;
    v_duration := coalesce((v_config -> 'market' ->> 'defaultContractMatchDays')::integer, 19);

    insert into contracts (league_id, player_id, club_id, signed_on, expires_on,
                           wage_per_match_day, price_paid)
    values (v_league, p_player_id, p_club_id, v_day, v_day + v_duration, 0, 0)
    on conflict (player_id) do update
      set club_id    = excluded.club_id,
          signed_on  = excluded.signed_on,
          expires_on = excluded.expires_on,
          squad      = 'prima';

    update listings set status = 'RITIRATO'
    where player_id = p_player_id and target_type = 'player' and status = 'APERTO';

    return jsonb_build_object('ok', true);
end;
$$;

create or replace function admin_release_player(p_player_id bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user   uuid := auth.uid();
    v_league bigint;
    v_admin  boolean;
begin
    select league_id into v_league from players where id = p_player_id;
    if v_league is null then
        return jsonb_build_object('ok', false, 'reason', 'Giocatore inesistente.');
    end if;

    select is_admin into v_admin from league_members
    where league_id = v_league and user_id = v_user;

    if not coalesce(v_admin, false) then
        raise exception 'Solo l''amministratore della lega.' using errcode = '42501';
    end if;

    if exists (
        select 1 from purchases
        where player_id = p_player_id
          and status in ('IN_FINESTRA', 'CONTESTATO')
          and now() < contestable_until
    ) then
        return jsonb_build_object('ok', false, 'reason', 'C''e'' una contestazione in corso.');
    end if;

    delete from contracts where player_id = p_player_id;
    update listings set status = 'RITIRATO'
    where player_id = p_player_id and target_type = 'player' and status = 'APERTO';

    return jsonb_build_object('ok', true);
end;
$$;

create or replace function admin_adjust_credits(
    p_club_id bigint,
    p_delta   integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user   uuid := auth.uid();
    v_league bigint;
    v_admin  boolean;
    v_dopo   integer;
begin
    select league_id into v_league from clubs where id = p_club_id;
    if v_league is null then
        return jsonb_build_object('ok', false, 'reason', 'Club inesistente.');
    end if;

    select is_admin into v_admin from league_members
    where league_id = v_league and user_id = v_user;

    if not coalesce(v_admin, false) then
        raise exception 'Solo l''amministratore della lega.' using errcode = '42501';
    end if;

    update clubs set credits = greatest(0, credits + p_delta)
    where id = p_club_id
    returning credits into v_dopo;

    return jsonb_build_object('ok', true, 'credits', v_dopo);
end;
$$;

create or replace function mfoot_remap(
    v double precision, in_min double precision, in_max double precision,
    out_min double precision, out_max double precision
)
returns double precision
language sql
immutable
-- Solo aritmetica: vedi la nota su mfoot_attr_cost.
set search_path = pg_catalog
as $$
    select case
        when in_max = in_min then out_min
        else out_min + least(greatest((v - in_min) / (in_max - in_min), 0), 1) * (out_max - out_min)
    end;
$$;

create or replace function mfoot_market_value(p_player_id bigint)
returns integer
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_p       players%rowtype;
    v_config  jsonb;
    v_scale   double precision;
    v_quality double precision;
    v_age     double precision;
    v_upside  double precision;
    v_margine double precision;
    v_credib  double precision;
    v_peak_a  integer;
    v_peak_b  integer;
    v_plateau integer;
    v_decline integer;
begin
    select * into v_p from players where id = p_player_id;
    if not found then return 1; end if;

    select config into v_config from leagues where id = v_p.league_id;

    v_scale := coalesce((v_config -> 'economy' ->> 'startingCredits')::double precision, 100000)
             * coalesce((v_config -> 'economy' ->> 'topPlayerBudgetShare')::double precision, 0.65);

    -- Qualita': ((overall - 40) / (93 - 40)) ^ 7,5, con i bordi bloccati.
    v_quality := power(
        least(greatest((v_p.overall::double precision - 40) / (93 - 40), 0), 1),
        7.5
    );

    v_peak_a  := coalesce((v_config -> 'rules' ->> 'peakAgeStart')::integer, 22);
    v_peak_b  := coalesce((v_config -> 'rules' ->> 'peakAgeEnd')::integer, 26);
    v_plateau := coalesce((v_config -> 'rules' ->> 'plateauAgeEnd')::integer, 28);
    v_decline := coalesce((v_config -> 'rules' ->> 'declineAge')::integer, 32);

    v_age := case
        when v_p.age < v_peak_a  then mfoot_remap(v_p.age, 16, v_peak_a, 0.82, 1.15)
        when v_p.age <= v_peak_b then 1.15
        when v_p.age <= v_plateau then mfoot_remap(v_p.age, v_peak_b, v_plateau, 1.15, 1.0)
        when v_p.age < v_decline then mfoot_remap(v_p.age, v_plateau, v_decline, 1.0, 0.62)
        else mfoot_remap(v_p.age, v_decline, 38, 0.62, 0.22)
    end;

    -- Il margine di crescita si paga solo finche' l'eta' lo rende credibile: un
    -- trentaduenne con potenziale 90 non vale nulla di piu', non ci arrivera' mai.
    v_margine := greatest(
        ((v_p.potential_min + v_p.potential_max) / 2.0) - v_p.overall::double precision, 0
    );
    v_credib := case
        when v_p.age <= v_peak_b then 1.0
        when v_p.age <= v_plateau then 0.45
        else 0.0
    end;
    v_upside := 1.0 + (v_margine / 25.0) * v_credib;

    return greatest(round(v_quality * v_scale * v_age * v_upside)::integer, 1);
end;
$$;

create or replace function free_agent_price(p_player_id bigint)
returns integer
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_league bigint;
begin
    select league_id into v_league from players where id = p_player_id;
    if v_league is null then return null; end if;
    if not is_member_of(v_league) then return null; end if;
    if exists (select 1 from contracts where player_id = p_player_id) then return null; end if;

    return mfoot_market_value(p_player_id);
end;
$$;


-- =====================================================================================
--  7. CHI PUO' CHIAMARE COSA
--
--  `anon` compare accanto ad `authenticated` su alcune: sono quelle che l'app chiama
--  anche prima di sapere chi sei, oppure con la chiave pubblica. Il controllo vero non
--  e' qui — e' dentro la funzione, che comincia sempre chiedendo `auth.uid()`.
--
--  Due sono revocate di proposito: `set_player_morale` e `set_squad` esistono per il
--  tick e non devono essere raggiungibili da un telefono.
-- =====================================================================================

grant execute on function admin_adjust_credits(bigint, integer) to authenticated, anon;
grant execute on function admin_assign_player(bigint, bigint) to authenticated, anon;
grant execute on function admin_release_player(bigint) to authenticated, anon;
grant execute on function answer_conversation(bigint, text, integer) to authenticated;
grant execute on function assign_divisions(bigint, jsonb) to authenticated;
grant execute on function assign_objectives(bigint, integer, jsonb) to authenticated;
grant execute on function assign_staff(bigint, bigint) to authenticated;
grant execute on function buy_player(bigint) to authenticated, anon;
grant execute on function buy_staff(bigint) to authenticated, anon;
grant execute on function contest_purchase(bigint, integer) to authenticated, anon;
grant execute on function counter_trade(bigint, integer, text) to authenticated;
grant execute on function create_club(bigint, text, text, jsonb, jsonb) to authenticated;
grant execute on function create_competition(bigint, text, text, jsonb, bigint[], jsonb) to authenticated;
grant execute on function create_league(text, text, jsonb, bigint, text, jsonb, jsonb, jsonb) to authenticated;
grant execute on function create_youth_club(bigint) to authenticated;
grant execute on function delete_competition(bigint) to authenticated;
grant execute on function free_agent_price(bigint) to authenticated, anon;
grant execute on function join_league(text, text) to authenticated;
grant execute on function list_player(bigint, integer) to authenticated, anon;
grant execute on function list_staff(bigint, integer) to authenticated, anon;
grant execute on function make_promise(bigint, text, integer, integer, integer) to authenticated;
grant execute on function mfoot_attr_cost(integer, integer, jsonb) to authenticated;
grant execute on function mfoot_market_value(bigint) to authenticated, anon;
grant execute on function mfoot_remap(double precision, double precision, double precision, double precision, double precision) to authenticated, anon;
grant execute on function move_between_squads(bigint, boolean) to authenticated;
grant execute on function open_conversation(bigint, text) to authenticated;
grant execute on function peek_league(text) to authenticated, anon;
grant execute on function place_bid(bigint, bigint, integer) to authenticated;
grant execute on function propose_friendly(bigint, bigint, timestamptz, text) to authenticated;
grant execute on function propose_loan(bigint, bigint, bigint, integer, integer, boolean, boolean, text) to authenticated;
grant execute on function propose_trade(bigint, bigint, bigint[], bigint[], integer, text) to authenticated;
grant execute on function release_player(bigint) to authenticated, anon;
grant execute on function respond_deal(bigint, boolean, text) to authenticated;
grant execute on function respond_trade(bigint, boolean, text) to authenticated;
grant execute on function send_scout(bigint, text, text) to authenticated;
grant execute on function set_access_code(bigint, text) to authenticated;
grant execute on function set_player_morale(bigint, integer) to authenticated;
grant execute on function set_squad(bigint, text) to authenticated;
grant execute on function settle_objective(bigint, text) to authenticated;
grant execute on function staff_price(bigint) to authenticated, anon;
grant execute on function start_auction(bigint, text, bigint, integer) to authenticated;
grant execute on function unlist_player(bigint) to authenticated, anon;
grant execute on function update_league_config(bigint, jsonb) to authenticated;
grant execute on function withdraw_trade(bigint) to authenticated;
grant select on auction_bids_public to authenticated;
/*
 * QUI C'ERANO DUE REVOKE CHE NON REVOCAVANO NIENTE
 *
 *     revoke execute on function set_player_morale(bigint, integer) from authenticated;
 *     revoke execute on function set_squad(bigint, text) from authenticated;
 *
 * Erano state scritte credendo che quelle due funzioni servissero solo al tick. Non e'
 * vero: le chiama l'app, da `PlayerRepository`, per il morale dopo un colloquio e per
 * spostare un giovane fra prima squadra e Primavera.
 *
 * Non rompevano niente per un motivo che vale la pena sapere: **in PostgreSQL `execute` su
 * una funzione e' concesso a `public` per impostazione predefinita**, quindi togliere il
 * permesso a un ruolo solo non toglie niente a nessuno. Erano due righe che dichiaravano
 * una protezione inesistente — e se un giorno avessero funzionato davvero, avrebbero rotto
 * il colloquio e la Primavera.
 *
 * La protezione vera e' dentro le funzioni, dove deve stare: tutte e due cercano il
 * contratto del giocatore in un club di `auth.uid()` e rifiutano se non lo trovano. E' la
 * stessa regola di tutte le altre, ed e' il motivo per cui il linter di Supabase segnala
 * novanta funzioni «eseguibili da chiunque» senza che nessuna di quelle segnalazioni sia
 * un difetto: le tabelle sono in sola lettura, si scrive solo passando di qui, e ognuna
 * controlla da sola chi sta chiamando.
 */



-- =====================================================================================
--  8. L'OROLOGIO
--
--  IL PROBLEMA, MISURATO
--
--  Il file del World Tick chiede a GitHub un giro ogni dieci minuti. Misurato il
--  2026-08-25 sugli ultimi quattordici giri, i minuti fra un avvio e il successivo erano:
--
--      34  22  51  50  47  53  44  44  31  29  32  73  2
--
--  Uno ogni **trentanove minuti**. Sul piano gratuito GitHub ritarda o salta i lavori
--  pianificati, e nessuna riga di codice del progetto puo' influenzarlo. E' la ragione per
--  cui il mondo sembrava fermo anche quando il tick funzionava: non era lento, era in
--  ritardo.
--
--  LA CORREZIONE
--
--  L'orologio sta qui dentro. `pg_cron` e' puntuale, e ogni cinque minuti chiede a GitHub
--  di far partire un giro. Il cron di GitHub resta acceso come **rete di sicurezza**: se
--  questa chiamata smette di funzionare — token scaduto, estensione disattivata — il mondo
--  torna lento invece di fermarsi.
--
--  PERCHE' QUI E NON IN UN SERVIZIO A PARTE
--
--  Il vincolo del progetto e' «costo zero, niente di proprio lasciato acceso». Supabase e'
--  gia' acceso: e' il database del gioco. Un orologio dentro qualcosa che c'e' gia' non
--  aggiunge niente da tenere in vita.
--
--  COSA SERVE FARE A MANO, UNA VOLTA SOLA
--
--  1. Su GitHub: Settings > Developer settings > Personal access tokens > Fine-grained.
--     Un token sul solo repository di MFoot, con il permesso **Actions: Read and write**.
--  2. Su Supabase, nell'SQL Editor:
--
--         select vault.create_secret('IL_TOKEN', 'github_tick_token');
--
--  Il token resta dentro il Vault del database. Non entra mai nell'APK, non finisce nel
--  repository, e non lo vede nessun giocatore: le funzioni che lo leggono sono
--  `security definer` e nessuna lo restituisce.
--
--  Finche' il segreto non c'e', `sveglia_il_tick()` non fallisce: dice che manca e non fa
--  niente. Il gioco continua a girare con la cadenza lenta di GitHub.
-- =====================================================================================

/*
 * Le due estensioni, senza far fallire tutto se non ci sono.
 *
 * Su un progetto Supabase dove non sono state abilitate dal pannello, `create extension`
 * puo' rifiutare. Senza questa protezione l'intero `schema.sql` si fermerebbe qui, e un
 * database che non ha l'orologio e' molto meglio di un database che non ha le tabelle.
 */
do $$
begin
    create extension if not exists pg_net with schema extensions;
exception when others then
    raise notice 'pg_net non disponibile (%): la sveglia resta spenta.', sqlerrm;
end;
$$;

do $$
begin
    create extension if not exists pg_cron;
exception when others then
    raise notice 'pg_cron non disponibile (%): l''orologio resta spento.', sqlerrm;
end;
$$;

/*
 * Chiede a GitHub di far partire un giro del World Tick.
 *
 * Restituisce una riga di testo che dice cosa e' successo: serve a chiamarla a mano
 * dall'SQL Editor per provarla, senza dover andare a guardare la tab Actions.
 *
 * La chiamata e' **asincrona**: `net.http_post` mette la richiesta in coda e torna subito.
 * Non aspetta la risposta di GitHub, e non deve — questa funzione la chiama un cron, e un
 * cron che si blocca su una rete lenta e' un cron che salta il giro dopo.
 */
create or replace function sveglia_il_tick()
returns text
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_token text;
    v_id    bigint;
begin
    select decrypted_secret into v_token
    from vault.decrypted_secrets
    where name = 'github_tick_token';

    if v_token is null or v_token = '' then
        return 'Nessun token: metti il segreto github_tick_token nel Vault. ' ||
               'Il mondo continua a girare con il cron di GitHub, che e'' lento.';
    end if;

    select net.http_post(
        url := 'https://api.github.com/repos/rorchri11-source/MFoot/actions/workflows/world-tick.yml/dispatches',
        headers := jsonb_build_object(
            'Authorization', 'Bearer ' || v_token,
            'Accept', 'application/vnd.github+json',
            'X-GitHub-Api-Version', '2022-11-28',
            'User-Agent', 'mfoot-orologio',
            'Content-Type', 'application/json'
        ),
        body := jsonb_build_object('ref', 'main')
    ) into v_id;

    return 'Sveglia mandata (richiesta ' || v_id || ').';
end;
$$;

/*
 * Ogni cinque minuti.
 *
 * Scelto dal proprietario il 2026-08-25 fra due, cinque e dieci: con un giro da due minuti
 * non si accavallano mai, e le partite partono al massimo cinque minuti dopo l'orario.
 *
 * `cron.schedule` con lo stesso nome **sostituisce** il lavoro precedente invece di
 * aggiungerne un secondo, quindi rieseguire questo file non produce due sveglie.
 */
do $$
begin
    perform cron.schedule('mfoot-orologio', '*/5 * * * *', 'select sveglia_il_tick()');
    raise notice 'Orologio impostato: una sveglia ogni cinque minuti.';
exception when others then
    raise notice 'Orologio non impostato (%): resta il cron di GitHub.', sqlerrm;
end;
$$;

grant execute on function sveglia_il_tick() to postgres;
