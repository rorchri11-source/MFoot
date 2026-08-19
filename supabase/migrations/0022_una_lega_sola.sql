-- =====================================================================================
-- MFoot - due amici, la stessa lega
--
-- Da incollare nell'SQL Editor di Supabase dopo 0021_controproposte.sql. Rieseguibile.
--
-- =====================================================================================
-- IL DIFETTO CHE QUESTA MIGRAZIONE CHIUDE
--
-- `join_league` cercava la lega cosi':
--
--     select id into v_league from leagues
--     where access_code_hash = crypt(trim(p_access_code), access_code_hash)
--     limit 1;
--
-- Il `limit 1` senza un ordine e senza un controllo di unicita' e' il difetto. Il codice
-- d'accesso non e' mai stato univoco: chi prova il gioco crea tre o quattro leghe di fila
-- e usa lo stesso codice tutte le volte, perche' e' un codice fra amici, non una password.
--
-- Da quel momento il codice non identifica piu' una lega: ne identifica un insieme, e il
-- database ne restituisce una qualsiasi. Due persone che digitano lo stesso identico
-- codice finiscono in due leghe diverse — e ognuna vede l'altra come «iscritta da qualche
-- parte» senza vederne mai le mosse, perche' le mosse succedono in un altro mondo. E'
-- esattamente la sensazione di «sembravano due partite diverse».
--
-- Qui il codice torna a essere un indirizzo:
--   1. `create_league` rifiuta un codice gia' in uso, invece di crearne un secondo uguale;
--   2. `join_league` rifiuta un codice ambiguo invece di sceglierne una a caso;
--   3. il codice si puo' rileggere, cosi' chi invita manda quello giusto.
-- =====================================================================================

-- =====================================================================================
-- IL CODICE, IN CHIARO, PER CHI E' GIA' DENTRO
--
-- Sembra un passo indietro e non lo e'. Il codice d'accesso non protegge dei dati: e'
-- l'indirizzo di casa di una lega fra amici, e chi e' dentro lo conosce gia' — lo ha
-- digitato per entrare. Tenerlo illeggibile non nascondeva niente a nessuno, e in cambio
-- rendeva impossibile la cosa piu' ovvia del mondo: «qual era il codice? Te lo rimando».
--
-- L'hash resta ed e' ancora lui a decidere chi entra. Questa colonna serve solo a
-- mostrarlo a chi e' gia' membro, e le Row Level Security su `leagues` lo garantiscono:
-- chi non e' della lega non ne legge nemmeno il nome.
-- =====================================================================================

alter table leagues add column if not exists access_code text;

comment on column leagues.access_code is
    'Il codice in chiaro, visibile solo ai membri. Chi entra passa sempre dall''hash.';

-- =====================================================================================
-- CREARE UNA LEGA: UN CODICE NUOVO, NON UNO GIA' IN GIRO
-- =====================================================================================

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

-- =====================================================================================
-- ENTRARE: O E' UNA SOLA, O NON SI ENTRA
--
-- Le leghe create prima di questa migrazione possono avere codici doppi. Non si possono
-- separare a posteriori — non c'e' modo di sapere in quale volevano entrare — ma si puo'
-- smettere di sceglierne una a caso e dirlo, cosi' l'admin cambia il codice di una delle
-- due e il problema finisce li'.
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

-- =====================================================================================
-- CAMBIARE IL CODICE
--
-- Serve a chi si ritrova due leghe con lo stesso codice: ne cambia uno e le due tornano
-- distinguibili. Solo l'amministratore, e solo verso un codice non gia' in uso.
-- =====================================================================================

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

grant execute on function create_league(text, text, jsonb, bigint, text, jsonb, jsonb, jsonb) to authenticated;
grant execute on function join_league(text, text) to authenticated;
grant execute on function set_access_code(bigint, text) to authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
