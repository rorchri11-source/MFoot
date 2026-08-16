-- =====================================================================================
-- MFoot - il club e il giocatore che sei tu
--
-- Da incollare nell'SQL Editor di Supabase dopo 0002_create_league.sql. Rieseguibile.
-- =====================================================================================

-- =====================================================================================
-- PERCHE' IL CONTO LO RIFA' IL DATABASE
--
-- Il giocatore custom si costruisce con un budget di punti. Se quel budget lo
-- controllasse solo l'app, chiunque sappia comporre una richiesta HTTP potrebbe
-- presentarsi con un 93 il primo giorno — e in una lega fra amici basta una persona
-- perche' la stagione non abbia piu' senso per le altre diciannove.
--
-- Quindi qui si rifa' tutto il conto: base di partenza, costo a scaglioni, stelle,
-- overall finale. I numeri per farlo non sono riscritti in SQL: arrivano dalla
-- configurazione della lega (`roleWeights`), che li prende da Position.kt. Una sola
-- verita', in un posto solo.
-- =====================================================================================

create or replace function mfoot_attr_cost(
    p_from  integer,
    p_to    integer,
    p_tiers jsonb
)
returns integer
language plpgsql
immutable
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

-- =====================================================================================
-- CREAZIONE DEL CLUB
--
-- Club, giocatore custom, contratto e formazione vuota nascono in una transazione sola.
-- Un club senza il suo giocatore, o un giocatore senza contratto, sarebbe uno stato che
-- nessuna schermata sa mostrare e che nessuno saprebbe come sistemare.
-- =====================================================================================

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

grant execute on function create_club(bigint, text, text, jsonb, jsonb) to authenticated;
grant execute on function mfoot_attr_cost(integer, integer, jsonb) to authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
