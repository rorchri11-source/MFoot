-- =====================================================================================
-- MFoot - gli scambi fra squadre
--
-- Da incollare nell'SQL Editor di Supabase dopo 0007_tick_state_read.sql. Rieseguibile.
-- =====================================================================================

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
    status       text    not null default 'PROPOSTA'
                 check (status in ('PROPOSTA','ACCETTATA','RIFIUTATA','RITIRATA','SCADUTA')),
    -- Perche' e' stata rifiutata: serve a dirlo a chi l'ha fatta.
    answer       text    not null default '',
    created_at   timestamptz not null default now(),
    answered_at  timestamptz,

    constraint trade_non_a_se_stessi check (from_club <> to_club)
);

create index if not exists idx_trades_league on trades(league_id, status);
create index if not exists idx_trades_to on trades(to_club, status);
create index if not exists idx_trades_from on trades(from_club, status);

alter table trades enable row level security;

-- Le proposte le vedono **i due club coinvolti e nessun altro**.
--
-- Non e' pignoleria: sapere che il Montesole ha offerto trenta milioni per il tuo
-- centravanti e' un'informazione di mercato che vale, e in una lega fra amici uno sguardo
-- alle trattative altrui rovinerebbe il gioco piu' di qualunque squilibrio di bilancio.
drop policy if exists read_own_trades on trades;
create policy read_own_trades on trades for select
    using (owns_club(from_club) or owns_club(to_club));

-- La scrittura passa solo dalle funzioni qui sotto: nessuna policy di insert o update.

-- =====================================================================================
-- PROPORRE
--
-- I controlli stanno qui e non solo sull'app per la solita ragione: l'app e' una cortesia,
-- il database e' la regola. Chi sa comporre una richiesta HTTP proporrebbe altrimenti uno
-- scambio a nome di un club che non e' suo, o chiederebbe giocatori che l'altro non ha.
-- =====================================================================================

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

-- =====================================================================================
-- RISPONDERE
--
-- Accettare e' l'unica operazione del gioco che sposta giocatori **e** denaro fra due club
-- in un colpo solo, quindi o riesce tutta o non riesce per niente. Una transazione a meta'
-- lascerebbe un club senza il giocatore e senza i soldi, e non c'e' modo di accorgersene
-- guardando il risultato.
--
-- I controlli si rifanno tutti al momento dell'accettazione, non ci si fida di quelli fatti
-- alla proposta: fra le due cose possono passare giorni, e in mezzo il giocatore chiesto
-- puo' essere stato venduto all'asta e il denaro speso.
-- =====================================================================================

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

-- =====================================================================================
-- RITIRARE
-- =====================================================================================

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

grant execute on function propose_trade(bigint, bigint, bigint[], bigint[], integer, text) to authenticated;
grant execute on function respond_trade(bigint, boolean, text) to authenticated;
grant execute on function withdraw_trade(bigint) to authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
