-- =====================================================================================
-- MFoot - la Primavera diventa una squadra vera
--
-- Da incollare nell'SQL Editor di Supabase dopo 0017_primavera.sql. Rieseguibile.
-- =====================================================================================

-- Di chi e' la seconda squadra.
--
-- ## Perche' una colonna su `clubs` e non una tabella nuova
--
-- Perche' con questa riga la Primavera **eredita gratis** tutto quello che un club sa gia'
-- fare: formazione, staff, divisione, calendario, classifica, partite giocate dal tick,
-- presenze, pagelle. Non c'e' niente da riscrivere per farla giocare.
--
-- Una tabella `youth_squads` con regole proprie avrebbe voluto dire una seconda versione
-- di ognuna di quelle cose, e le due sarebbero diverse alla prima modifica: la formazione
-- della prima squadra con gli ordini condizionali, quella della Primavera senza.
alter table clubs add column if not exists parent_club_id bigint references clubs(id) on delete cascade;

create index if not exists idx_clubs_parent on clubs(parent_club_id);

-- Un club e' padre o figlio, mai tutti e due.
--
-- Senza, una catena di Primavere di Primavere sarebbe rappresentabile, e prima o poi
-- qualcuno la produrrebbe.
do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'clubs_niente_nipoti') then
        alter table clubs add constraint clubs_niente_nipoti
            check (parent_club_id is null or id <> parent_club_id);
    end if;
end $$;

-- =====================================================================================
-- FONDARE LA PRIMAVERA
--
-- Un club per volta, su richiesta. Non alla creazione della lega: chi entra oggi non ha
-- ancora nemmeno la prima squadra, e generargli una seconda vuota sarebbe una riga in piu'
-- in ogni classifica per un club che non esiste ancora.
--
-- Parte dall'ultima divisione, come si e' scelto.
-- =====================================================================================

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

grant execute on function create_youth_club(bigint) to authenticated;

-- =====================================================================================
-- LA PRIMAVERA NON HA PORTAFOGLIO
--
-- Nasce con zero crediti e non ne riceve mai. Stipendi e acquisti passano dal club padre.
--
-- Due bilanci sarebbero due volte il lavoro per chi gioca, e una porta aperta al
-- riciclaggio: mi vendo un giocatore da me a me al prezzo che voglio e sposto denaro fra
-- due conti che controllo entrambi. Con un portafoglio solo quella mossa non esiste.
-- =====================================================================================

-- =====================================================================================
-- PROMUOVERE E MANDARE GIU'
--
-- Sostituisce `set_squad` di 0017, che spostava un giocatore fra due rose dello stesso
-- club. Adesso i club sono due davvero, e la verita' e' il contratto.
-- =====================================================================================

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

grant execute on function move_between_squads(bigint, boolean) to authenticated;

-- La funzione di 0017 non serve piu': spostava fra due rose dello stesso club, e le rose
-- adesso sono due club. Resta definita perche' toglierla romperebbe una versione dell'app
-- ancora installata da qualche parte, ma non la puo' piu' chiamare nessuno.
revoke execute on function set_squad(bigint, text) from authenticated;

-- =====================================================================================
-- CHI ERA GIA' IN PRIMAVERA
--
-- Chi aveva usato la Primavera-magazzino di 0017 si ritrova i suoi giovani in un club
-- vero, creato qui. Chi non l'aveva usata non si accorge di niente.
-- =====================================================================================

do $$
declare
    r record;
    v_new bigint;
begin
    for r in
        select distinct c.club_id, cl.league_id, cl.name, cl.short_name,
               cl.owner_user_id, cl.owner_name, cl.kit
        from contracts c
        join clubs cl on cl.id = c.club_id
        where c.squad = 'primavera' and cl.parent_club_id is null
    loop
        select id into v_new from clubs where parent_club_id = r.club_id;

        if v_new is null then
            insert into clubs (league_id, name, short_name, owner_user_id, owner_name,
                               is_ai, credits, committed_credits, kit, division_level,
                               parent_club_id)
            values (r.league_id, r.name || ' Primavera', left(r.short_name, 3) || 'P',
                    r.owner_user_id, r.owner_name, false, 0, 0, r.kit, 1, r.club_id)
            returning id into v_new;
        end if;

        update contracts set club_id = v_new, squad = 'prima'
        where club_id = r.club_id and squad = 'primavera';
    end loop;
end $$;

-- =====================================================================================
-- FINE
-- =====================================================================================
