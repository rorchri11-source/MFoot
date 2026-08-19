-- =====================================================================================
-- MFoot - durante l'asta si vede chi c'e' dentro
--
-- Da incollare nell'SQL Editor di Supabase dopo 0022_una_lega_sola.sql. Rieseguibile.
--
-- =====================================================================================
-- IL PUNTO
--
-- Fino a `0020` di un'asta aperta si vedevano tre cose: il prezzo, il capofila, quante
-- offerte erano state fatte. Chi fossero gli altri, no. Un'asta a cui partecipano quattro
-- club e una a cui partecipa una persona sola sono la stessa riga sullo schermo, e
-- l'unica cosa che si puo' fare e' rilanciare al buio.
--
-- Il massimo dichiarato deve restare segreto — e' quello che permette di andare a dormire
-- invece di controllare il telefono ogni ora. Ma il **prezzo** non e' mai stato segreto:
-- e' scritto in cima all'asta e lo vedono tutti. L'unica cosa che mancava era il nome
-- accanto al momento in cui e' salito.
--
-- Quindi: chi ha offerto, quando, e a quanto e' arrivato il prezzo dopo la sua offerta.
-- Tre informazioni gia' pubbliche, rimesse insieme. E' come funzionano le aste online da
-- sempre: la cronologia dei rilanci si legge, il limite automatico no.
-- =====================================================================================

alter table bids add column if not exists public_price integer;

comment on column bids.public_price is
    'Il prezzo pubblico raggiunto DOPO questa offerta. Non e'' il massimo dichiarato: quello resta segreto finche'' l''asta non chiude.';

-- Le righe precedenti a questa migrazione restano a null: il prezzo pubblico di allora non
-- e' ricostruibile, e inventarlo vorrebbe dire scrivere una cronologia falsa. L'app le
-- mostra col nome e senza cifra, che e' la verita' su quello che si sa.

-- =====================================================================================
-- L'OFFERTA, CHE ORA FIRMA ANCHE IL PREZZO
--
-- Identica a quella di 0004 tranne per due righe: l'id dell'offerta appena inserita si
-- tiene da parte, e alla fine ci si scrive dentro il prezzo pubblico calcolato. Dentro la
-- stessa transazione che tiene il lock, quindi non esiste un istante in cui la cronologia
-- e' meta' scritta.
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

grant execute on function place_bid(bigint, bigint, integer) to authenticated;

-- =====================================================================================
-- LA CRONOLOGIA, SENZA I SEGRETI
--
-- Una vista e non una policy: le Row Level Security scelgono quali **righe** si vedono, e
-- qui il problema e' quale **colonna**. `max_amount` non deve uscire da un'asta aperta in
-- nessun modo, e l'unico modo di garantirlo e' non metterlo nell'elenco.
--
-- Il filtro sulla lega sta scritto dentro: una vista gira con i permessi di chi la possiede
-- e quindi non eredita le policy della tabella sotto. Dimenticarselo qui vorrebbe dire
-- pubblicare le aste di tutte le leghe a chiunque.
-- =====================================================================================

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

grant select on auction_bids_public to authenticated;

comment on view auction_bids_public is
    'Chi ha offerto e a che prezzo pubblico e'' arrivato. Mai il massimo dichiarato: quello si legge da `bids` solo per il proprio club, o su un''asta chiusa.';

-- =====================================================================================
-- MENTRE SI E' QUI: `players_public` non filtrava la lega
--
-- La vista serve a nascondere `potential_min` e `potential_max`, e quello lo fa. Ma una
-- vista gira con i permessi di chi la possiede, quindi **non applica** la policy
-- `read_players`: un membro di una lega qualsiasi poteva leggere i giocatori di tutte le
-- altre. Non e' mai stato un problema pratico — l'app chiede sempre `league_id=eq.…` — ma
-- e' una porta aperta che nessuno aveva notato.
--
-- `security_invoker` fa girare la vista con i permessi di chi la interroga, e da li' la
-- policy torna ad applicarsi. Esiste da PostgreSQL 15; se questo database fosse piu'
-- vecchio, la riga fallisce da sola e non porta giu' il resto della migrazione.
-- =====================================================================================

do $$
begin
    execute 'alter view players_public set (security_invoker = true)';
exception
    when others then
        raise notice 'players_public: security_invoker non applicato (%). Il resto e'' a posto.', sqlerrm;
end $$;

-- =====================================================================================
-- FINE
-- =====================================================================================
