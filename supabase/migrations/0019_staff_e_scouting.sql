-- =====================================================================================
-- MFoot - lo staff si assume, gli osservatori vanno in missione
--
-- Da incollare nell'SQL Editor di Supabase dopo 0018_seconda_squadra.sql. Rieseguibile.
-- =====================================================================================

-- =====================================================================================
-- GLI UNDER 20 ESCONO DALLE ASTE
--
-- Sono circa il dieci per cento del mondo: l'eta' e' una gaussiana su 25,4 con deviazione
-- 4,6, quindi in una lega da milletrecento giocatori sono un centinaio e trenta.
-- Abbastanza da cambiare il gioco, non tanti da svuotare il listino.
--
-- Si trovano **solo scoutando**. Restano vendibili una volta tesserati: chi scopre un
-- fenomeno lo puo' rivendere, e quello e' mercato.
-- =====================================================================================

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

    insert into auctions (league_id, target_type, target_id, started_by, ends_at,
                          starting_price, current_price, status)
    values (p_league_id, p_target_type, p_target_id, v_club,
            now() + make_interval(mins => v_minutes),
            greatest(1, coalesce(p_starting_price, 1)),
            greatest(1, coalesce(p_starting_price, 1)),
            'APERTA')
    returning id into v_auction;

    return v_auction;
end;
$$;

grant execute on function start_auction(bigint, text, bigint, integer) to authenticated;

-- =====================================================================================
-- ASSEGNARE UN MEMBRO DELLO STAFF A UNA DELLE DUE SQUADRE
--
-- Lo staff si vince all'asta come i giocatori, e la funzione che lo assegna esiste gia' nel
-- tick. Quello che manca e' poterlo **spostare** fra prima squadra e Primavera: un
-- allenatore da cinque stelle sulla Primavera fa crescere i ragazzi il triplo, e quella e'
-- la scelta interessante — non si puo' mettere su tutte e due.
-- =====================================================================================

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

grant execute on function assign_staff(bigint, bigint) to authenticated;

-- =====================================================================================
-- LE MISSIONI DI SCOUTING
--
-- «Vai in Brasile, trovami un attaccante.»
--
-- ## Perche' ci vuole tempo vero
--
-- Perche' e' l'unico costo della scoperta. Il giocatore trovato arriva gratis, quindi se
-- la ricerca fosse istantanea cinque osservatori produrrebbero cinque talenti al minuto.
-- Il prezzo sono le ore in cui quell'osservatore e' occupato e non puo' cercare altro:
-- cinque osservatori sono cinque ricerche in parallelo, non cinquanta.
--
-- ## Perche' il risultato lo calcola il tick
--
-- Perche' "trova un giovane con buon potenziale" e' una domanda sul **potenziale vero**,
-- che non deve lasciare il server. Il database sa quando la missione scade; chi la risolve
-- e' il tick, che di `core` ha una copia identica e i valori veri li ha gia' in mano.
-- =====================================================================================

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

create index if not exists idx_missions_open on scouting_missions(league_id, status);

-- Un osservatore, una missione per volta. E' il vincolo che rende gli osservatori una
-- risorsa: senza, lo stesso uomo cercherebbe in dieci paesi contemporaneamente.
create unique index if not exists idx_missions_un_osservatore
    on scouting_missions(staff_id) where status = 'IN_CORSO';

alter table scouting_missions enable row level security;

drop policy if exists read_own_missions on scouting_missions;
create policy read_own_missions on scouting_missions for select
    using (owns_club(club_id));

-- =====================================================================================
-- MANDARE UN OSSERVATORE
-- =====================================================================================

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
    v_staff  staff%rowtype;
    v_club   clubs%rowtype;
    v_ore    integer;
    v_id     bigint;
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

    -- Otto ore a cinque stelle, quarantotto a una. Le stelle comprano **tempo** oltre che
    -- qualita': un osservatore scarso trova poco e ci mette sei volte tanto, ed e' quello
    -- che rende un cinque stelle degno di una guerra all'asta.
    v_ore := 8 + (5 - greatest(1, least(5, v_staff.stars))) * 10;

    insert into scouting_missions (league_id, club_id, staff_id, country, position, ready_at)
    values (v_club.league_id, v_staff.club_id, p_staff_id, p_country, p_position,
            now() + make_interval(hours => v_ore))
    returning id into v_id;

    return jsonb_build_object('ok', true, 'id', v_id, 'hours', v_ore);
end;
$$;

grant execute on function send_scout(bigint, text, text) to authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
