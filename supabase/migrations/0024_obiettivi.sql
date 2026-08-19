-- =====================================================================================
-- MFoot - gli obiettivi di stagione, e i premi che pagano
--
-- Da incollare nell'SQL Editor di Supabase dopo 0023_chi_ha_offerto.sql. Rieseguibile.
--
-- =====================================================================================
-- COSA SONO
--
-- Una lega senza obiettivi ha una domanda sola per tutti — chi arriva primo — e per le
-- altre quindici squadre quella domanda smette di contare a meta' stagione. Con gli
-- obiettivi ognuno ha una stagione sua da giocare: chi punta al titolo, chi punta a non
-- retrocedere, chi punta a far arrivare il proprio giocatore a novanta.
--
-- Il premio si paga **solo se si raggiunge**. Niente premi parziali: un premio che paga
-- meta' se arrivi vicino non cambia nessuna decisione, si fa la stagione che si sarebbe
-- fatta comunque e si incassa quello che capita. Tutto o niente rende costoso il rischio,
-- che e' esattamente cio' che un obiettivo deve fare.
--
-- ## Chi decide cosa chiedere
--
-- Nessuna persona. La regola sta in `core` (`ObjectiveBoard`), e' scritta, e' uguale per
-- tutti e la leggono tutti. Obiettivi scelti a mano dall'amministratore sarebbero crediti
-- assegnati da un concorrente: non c'e' modo di renderli credibili nemmeno quando sono
-- onesti, che e' il caso quasi sempre.
--
-- Il database non li calcola: li **riceve gia' calcolati** e si limita a rifiutare cio' che
-- non torna. Ricalcolarli in SQL vorrebbe dire una seconda implementazione dello stesso
-- regolamento, e due regolamenti si separano al primo ritocco.
-- =====================================================================================

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

create index if not exists idx_objectives_club on club_objectives(club_id, season);
create index if not exists idx_objectives_league on club_objectives(league_id, status);

alter table club_objectives enable row level security;

drop policy if exists read_objectives on club_objectives;

-- Si leggono **tutti**, non solo i propri.
--
-- E' voluto: sapere che l'avversario ha in ballo un premio grosso se non retrocede spiega
-- perche' a marzo compra un difensore invece di vendere. Nascosti, gli obiettivi
-- resterebbero un fatto privato che muove il mercato di tutti senza che nessuno capisca
-- perche'.
create policy read_objectives on club_objectives for select using (is_member_of(league_id));

-- =====================================================================================
-- ASSEGNARE
--
-- In un colpo solo per tutta la lega: assegnarli a un club per volta vorrebbe dire una
-- lega in cui meta' squadre hanno obiettivi e meta' no, che e' peggio di non averne.
--
-- Rieseguibile: `on conflict do nothing` lascia stare quelli gia' assegnati invece di
-- riscriverli. Un obiettivo gia' in corso non deve poter cambiare a stagione iniziata --
-- e' l'unica cosa che lo rende un patto invece che un suggerimento.
-- =====================================================================================

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

-- =====================================================================================
-- CHIUDERE UN OBIETTIVO, E PAGARLO
--
-- Il verdetto lo calcola `core` — `ObjectiveEngine` — perche' e' regolamento. Qui si
-- registra, e si paga.
--
-- ## Perche' il pagamento sta dentro la stessa transazione del verdetto
--
-- Perche' altrimenti esisterebbe un istante in cui l'obiettivo risulta raggiunto e i
-- crediti non sono ancora arrivati. Basta un errore di rete li' in mezzo e il premio
-- sparisce senza che nessuno se ne accorga: lo stato dice «raggiunto», il conto dice di
-- no, e non c'e' modo di sapere quale dei due mente.
--
-- ## Perche' non si puo' pagare due volte
--
-- Perche' si scrive solo su una riga che e' ancora `IN_CORSO`. Un secondo tentativo non
-- trova niente da aggiornare e paga zero.
-- =====================================================================================

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

grant execute on function assign_objectives(bigint, integer, jsonb) to authenticated;
grant execute on function settle_objective(bigint, text) to authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
