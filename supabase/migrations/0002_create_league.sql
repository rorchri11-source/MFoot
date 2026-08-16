-- =====================================================================================
-- MFoot - creazione di una lega in un colpo solo
--
-- Da incollare nell'SQL Editor di Supabase dopo 0001_schema.sql. Rieseguibile.
--
-- PRIMA DI USARLO: su Supabase va attivato l'accesso anonimo in
-- Authentication -> Sign In / Providers -> Anonymous sign-ins.
-- Senza, `auth.uid()` e' sempre null e la funzione rifiuta la chiamata.
-- =====================================================================================

-- Serve per l'hash del codice d'accesso. Su Supabase e' gia' presente nello schema
-- `extensions`, ed e' il motivo per cui le funzioni qui sotto lo includono nel
-- search_path: senza, `crypt()` non verrebbe trovata.
create extension if not exists pgcrypto with schema extensions;

-- =====================================================================================
-- PERCHE' UNA FUNZIONE E NON DEGLI INSERT
--
-- Creare una lega significa scrivere in sei tabelle diverse: leghe, membri, giocatori,
-- staff, club AI, stato del tick. Farlo con sei chiamate dal telefono vuol dire che una
-- connessione persa a meta' lascia una lega monca, con i giocatori ma senza club, che
-- nessuno sapra' come sistemare.
--
-- Con una funzione sola: o passa tutto o non passa niente. E le Row Level Security
-- restano chiuse in scrittura, perche' e' la funzione (SECURITY DEFINER) ad avere il
-- permesso, non il client.
-- =====================================================================================

create or replace function create_league(
    p_name        text,
    p_access_code text,
    p_config      jsonb,
    p_seed        bigint,
    p_nickname    text,
    -- Il mondo generato sul telefono: core e' la stessa libreria che gira sul tick,
    -- quindi il dispositivo produce milletrecento giocatori in millisecondi e non c'e'
    -- ragione di far aspettare l'utente che ci pensi il server.
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
    v_league   bigint;
    v_club     jsonb;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido per creare una lega.'
            using errcode = '28000';
    end if;
    if coalesce(trim(p_name), '') = '' then
        raise exception 'La lega deve avere un nome.' using errcode = '22023';
    end if;
    if coalesce(trim(p_access_code), '') = '' then
        raise exception 'Serve un codice d''accesso.' using errcode = '22023';
    end if;
    if jsonb_array_length(coalesce(p_players, '[]'::jsonb)) = 0 then
        raise exception 'Il mondo e'' vuoto: nessun giocatore da caricare.' using errcode = '22023';
    end if;

    insert into leagues (name, access_code_hash, config, world_seed, status, current_match_day)
    values (trim(p_name), crypt(trim(p_access_code), gen_salt('bf')), p_config, p_seed, 'mercato', 0)
    returning id into v_league;

    insert into league_members (league_id, user_id, nickname, is_admin)
    values (v_league, v_user, coalesce(nullif(trim(p_nickname), ''), 'admin'), true);

    -- I giocatori. L'ordine dell'array determina l'id progressivo, cosi' il seed e la
    -- numerazione restano allineati e il tick puo' rigenerare e confrontare.
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

    -- I club gestiti dall'AI, con il carattere gia' generato dal telefono.
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

    -- Il tick parte da adesso: non deve recuperare il tempo prima della creazione.
    insert into tick_state (league_id, last_processed_at)
    values (v_league, now())
    on conflict (league_id) do update set last_processed_at = excluded.last_processed_at;

    return v_league;
end;
$$;

-- =====================================================================================
-- ENTRARE IN UNA LEGA CON IL CODICE
-- =====================================================================================

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
    v_league bigint;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    select id into v_league
    from leagues
    where access_code_hash = crypt(trim(p_access_code), access_code_hash)
    limit 1;

    if v_league is null then
        raise exception 'Codice non valido.' using errcode = '22023';
    end if;

    insert into league_members (league_id, user_id, nickname, is_admin)
    values (v_league, v_user, coalesce(nullif(trim(p_nickname), ''), 'giocatore'), false)
    on conflict (league_id, user_id) do update set nickname = excluded.nickname;

    return v_league;
end;
$$;

-- =====================================================================================
-- PERMESSI
--
-- Le funzioni sono SECURITY DEFINER, quindi girano con i permessi del proprietario e
-- possono scrivere anche se le policy chiudono la scrittura al client. E' proprio questo
-- il punto: si passa di qui o non si scrive.
-- =====================================================================================

grant execute on function create_league(text, text, jsonb, bigint, text, jsonb, jsonb, jsonb) to authenticated;
grant execute on function join_league(text, text) to authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
