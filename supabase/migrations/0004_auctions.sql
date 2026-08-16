-- =====================================================================================
-- MFoot - le aste viste da fuori
--
-- Da incollare nell'SQL Editor di Supabase dopo 0003_club.sql. Rieseguibile.
-- =====================================================================================

-- =====================================================================================
-- IL PREZZO E' PUBBLICO, LE OFFERTE MASSIME NO
--
-- Questa e' la distinzione su cui si regge tutto il mercato. Il prezzo corrente lo devono
-- vedere tutti, o non si puo' decidere se rilanciare. L'offerta massima di un avversario
-- non la deve vedere nessuno, o l'asta diventa un esercizio di lettura invece che una
-- scommessa.
--
-- Le Row Level Security nascondono le offerte altrui (`read_own_bids`), quindi il client
-- non puo' calcolarsi il prezzo da solo: glielo deve dire il server. Da qui queste due
-- colonne, che `place_bid` tiene aggiornate dentro la stessa transazione del lock.
-- =====================================================================================

alter table auctions add column if not exists current_price  integer not null default 0;
alter table auctions add column if not exists leader_club_id bigint references clubs(id) on delete set null;
alter table auctions add column if not exists bid_count      integer not null default 0;

-- Allinea le aste gia' aperte prima di questa migrazione.
update auctions a
set current_price = greatest(a.starting_price, coalesce((
        select min(b.max_amount) from bids b where b.auction_id = a.id
    ), a.starting_price)),
    bid_count = coalesce((select count(*) from bids b where b.auction_id = a.id), 0),
    leader_club_id = (
        select b.club_id from bids b where b.auction_id = a.id
        order by b.max_amount desc, b.placed_at asc limit 1
    )
where a.status = 'APERTA' and a.current_price = 0;

-- =====================================================================================
-- APRIRE UN'ASTA
--
-- Non e' un insert diretto: le regole da controllare sono quattro e devono valere
-- **tutte**, altrimenti il mercato si rompe in modi che nessuno sa poi ricostruire.
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
    v_user     uuid := auth.uid();
    v_club     bigint;
    v_config   jsonb;
    v_minutes  integer;
    v_max_open integer;
    v_open     integer;
    v_auction  bigint;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    select id into v_club from clubs
    where league_id = p_league_id and owner_user_id = v_user;

    if v_club is null then
        raise exception 'Devi avere un club in questa lega per aprire un''asta.'
            using errcode = '42501';
    end if;

    if p_target_type not in ('player', 'staff') then
        raise exception 'Tipo di asta sconosciuto: %', p_target_type using errcode = '22023';
    end if;

    -- Un giocatore sotto contratto non si mette all'asta: si tratta col suo club. Senza
    -- questo controllo si potrebbe "battere all'asta" la rosa altrui.
    if p_target_type = 'player' then
        if not exists (select 1 from players where id = p_target_id and league_id = p_league_id) then
            raise exception 'Giocatore inesistente in questa lega.' using errcode = '22023';
        end if;
        if exists (select 1 from contracts where player_id = p_target_id) then
            raise exception 'Questo giocatore ha gia'' un contratto.' using errcode = '22023';
        end if;
    else
        if not exists (
            select 1 from staff where id = p_target_id and league_id = p_league_id and club_id is null
        ) then
            raise exception 'Membro dello staff non disponibile.' using errcode = '22023';
        end if;
    end if;

    -- Due aste aperte sullo stesso giocatore vorrebbero dire due club che se lo
    -- aggiudicano entrambi, e un contratto che ne sovrascrive un altro in silenzio.
    if exists (
        select 1 from auctions
        where league_id = p_league_id and status = 'APERTA'
          and target_type = p_target_type and target_id = p_target_id
    ) then
        raise exception 'C''e'' gia'' un''asta aperta su questo obiettivo.' using errcode = '23505';
    end if;

    select config into v_config from leagues where id = p_league_id;
    v_minutes  := coalesce((v_config -> 'market' ->> 'auctionDurationMinutes')::integer, 60);
    v_max_open := coalesce((v_config -> 'market' ->> 'maxParallelAuctionsPerClub')::integer, 3);

    -- Il tetto di aste parallele non e' una scortesia: senza, un club puo' aprirne venti
    -- e bloccare l'intero listino mentre decide con calma.
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

-- =====================================================================================
-- OFFERTA: come prima, ma ora pubblica il prezzo
--
-- L'unica differenza rispetto a 0001 e' che alla fine scrive prezzo corrente, capofila e
-- numero di offerte sull'asta, dentro la stessa transazione che tiene il lock. Il client
-- non puo' calcolarli da solo perche' non vede le offerte altrui, ed e' giusto cosi'.
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

    -- Si offre solo per il proprio club. Senza, chiunque potrebbe svuotare i crediti
    -- altrui offrendo a loro nome.
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

    select club_id into v_leader
    from bids where auction_id = p_auction_id
    order by max_amount desc, placed_at asc limit 1;

    select coalesce(max(max_amount), 0) into v_top_max from bids where auction_id = p_auction_id;
    select coalesce(max(max_amount), 0) into v_second_max
    from bids where auction_id = p_auction_id and max_amount < v_top_max;

    v_price := case when v_second_max = 0 then v_auction.starting_price
                    else least(v_second_max + v_min_raise, v_top_max) end;

    -- Il prezzo diventa pubblico. Le offerte massime restano dove sono.
    update auctions
    set current_price  = v_price,
        leader_club_id = v_leader,
        bid_count      = (select count(*) from bids where auction_id = p_auction_id)
    where id = p_auction_id;

    return jsonb_build_object(
        'ok', true,
        'leader_club_id', v_leader,
        'current_price', v_price,
        'you_lead', v_leader = p_club_id
    );
end;
$$;

grant execute on function start_auction(bigint, text, bigint, integer) to authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
