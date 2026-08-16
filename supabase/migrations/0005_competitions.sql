-- =====================================================================================
-- MFoot - le competizioni le crea l'admin
--
-- Da incollare nell'SQL Editor di Supabase dopo 0004_auctions.sql. Rieseguibile.
-- =====================================================================================

-- =====================================================================================
-- PERCHE' NON PARTE NIENTE DA SOLO
--
-- Una prima versione faceva partire il campionato automaticamente alla data indicata
-- nella configurazione. Sembrava comodo e invece toglieva all'admin la cosa piu'
-- importante che deve poter fare: decidere **quali** competizioni esistono, chi ci
-- partecipa, di che tipo sono e quando si giocano.
--
-- Una lega puo' avere un campionato e una coppa insieme, o solo una coppa, o un torneo a
-- gironi fra otto dei venti club. Nessuna di queste cose e' deducibile da una data.
--
-- Il calendario lo calcola l'app con `core` — le stesse tabelle di Berger e lo stesso
-- risolutore di vincoli che girano nei test — cosi' l'admin lo **vede prima** di
-- confermarlo. Qui si scrive, in una transazione sola.
-- =====================================================================================

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

-- =====================================================================================
-- CANCELLARE UNA COMPETIZIONE
--
-- Serve piu' di quanto sembri: si sbaglia una data, si sceglie il tipo sbagliato, ci si
-- accorge di aver dimenticato un partecipante. Senza un modo di disfare, l'unica via
-- sarebbe ricreare la lega da capo.
--
-- Si puo' cancellare solo finche' non si e' giocato niente: dopo, i risultati sono
-- storia e cancellarli falserebbe la crescita dei giocatori che quelle partite le hanno
-- gia' giocate.
-- =====================================================================================

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

grant execute on function create_competition(bigint, text, text, jsonb, bigint[], jsonb) to authenticated;
grant execute on function delete_competition(bigint) to authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
