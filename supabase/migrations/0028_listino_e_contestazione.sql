-- =====================================================================================
-- IL LISTINO E LA CONTESTAZIONE
--
-- Deciso dal proprietario il 2026-08-24: si compra a prezzo fisso e **il giocatore e' tuo
-- nello stesso istante**. Per dodici ore l'acquisto resta contestabile, e solo se qualcuno
-- contesta nasce un'asta.
--
-- PERCHE' L'ASTA COME RITO OBBLIGATORIO NON FUNZIONAVA
--
-- Non per come era fatta — l'offerta massima automatica e l'anti-snipe sono buone regole —
-- ma per quanto durava. Il tick impiega otto minuti a giro e viene cancellato nel 59%
-- delle esecuzioni: la cadenza vera e' fra venti e quaranta minuti. Un'asta da un'ora con
-- tre rilanci diventa mezza giornata, e una rosa da diciotto uomini tre settimane reali.
--
-- Come eccezione l'asta funziona: protegge dall'affare troppo buono senza tassare i
-- novanta acquisti banali che non interessano a nessuno.
--
-- LA REGOLA VIVE IN `core`
--
-- `core/market/Listing.kt`, con i suoi test. Qui il server rifa' lo stesso conto perche'
-- non puo' fidarsi del client sui soldi — esattamente come `place_bid` rifa' le regole
-- d'asta e `create_club` rifa' il conto del budget.
-- =====================================================================================

-- ------------------------------------------------------------------------- il listino

create table if not exists listings (
    id             bigserial primary key,
    league_id      bigint  not null references leagues(id) on delete cascade,
    -- L'id del bersaglio: un giocatore, oppure un membro dello staff (vedi target_type).
    -- Nessuna chiave esterna, proprio perche' punta a due tabelle diverse.
    player_id      bigint  not null,
    /*
     * Cosa si sta vendendo.
     *
     * Nasce **con la tabella** e non da una migrazione successiva, e non e' un dettaglio:
     * `players` e `staff` hanno due sequenze separate, quindi il giocatore 7 e
     * l'allenatore 7 esistono tutti e due. Senza questa colonna fin dal principio, un
     * listino che contiene entrambi si legge come un elenco di giocatori con dentro delle
     * righe che parlano di qualcun altro — e nessuno se ne accorge finche' non compra.
     */
    target_type    text    not null default 'player'
                           check (target_type in ('player', 'staff')),
    -- Null significa svincolato: non lo vende nessuno e l'incasso non va a nessun club.
    seller_club_id bigint  references clubs(id) on delete cascade,
    price          integer not null check (price >= 1),
    listed_at      timestamptz not null default now(),
    status         text    not null default 'APERTO'
                           check (status in ('APERTO', 'VENDUTO', 'RITIRATO'))
);

-- Un bersaglio per volta sul listino. Parziale, perche' dopo una vendita la riga resta
-- come storia e non deve impedire di rimetterlo in vendita domani.
create unique index if not exists idx_listings_uno_per_bersaglio
    on listings(target_type, player_id) where status = 'APERTO';

create index if not exists idx_listings_aperti on listings(league_id, status);

-- --------------------------------------------------------------------- gli acquisti

create table if not exists purchases (
    id                bigserial primary key,
    league_id         bigint  not null references leagues(id) on delete cascade,
    player_id         bigint  not null references players(id) on delete cascade,
    buyer_club_id     bigint  not null references clubs(id) on delete cascade,
    seller_club_id    bigint  references clubs(id) on delete set null,
    price             integer not null check (price >= 1),
    bought_at         timestamptz not null default now(),
    -- L'ora esatta in cui il giocatore diventa definitivo. Nota **dal primo istante**:
    -- e' cio' che rende accettabile comprare senza aspettare.
    contestable_until timestamptz not null,
    status            text    not null default 'IN_FINESTRA'
                              check (status in ('IN_FINESTRA', 'CONFERMATO', 'CONTESTATO', 'REVOCATO')),
    auction_id        bigint  references auctions(id) on delete set null
);

create index if not exists idx_purchases_finestra
    on purchases(league_id, contestable_until)
    where status in ('IN_FINESTRA', 'CONTESTATO');

create index if not exists idx_purchases_player on purchases(player_id);

-- =====================================================================================
-- ROW LEVEL SECURITY
--
-- Listino e acquisti sono **pubblici dentro la lega**, e devono esserlo: contestare
-- richiede di sapere che qualcuno ha comprato. Un acquisto segreto per dodici ore
-- sarebbe una finestra che nessuno puo' usare.
-- =====================================================================================

alter table listings  enable row level security;
alter table purchases enable row level security;

drop policy if exists read_listings  on listings;
drop policy if exists read_purchases on purchases;

create policy read_listings  on listings  for select using (is_member_of(league_id));
create policy read_purchases on purchases for select using (is_member_of(league_id));

-- Nessuna policy di scrittura: si passa dalle funzioni qui sotto, che sono
-- `security definer` e rifanno i conti. Il listino e' l'unico posto dove un client
-- potrebbe scrivere un prezzo, ed e' esattamente il posto dove non deve poterlo fare
-- senza controlli.

-- =====================================================================================
-- METTERE IN VENDITA
--
-- Il prezzo lo scrive il proprietario, **libero**, da un credito a tutto il budget.
-- Deciso il 2026-08-24 sapendo il rischio: due amici d'accordo possono spostare un
-- fuoriclasse per niente. Il correttivo e' la contestazione — un prezzo fuori mercato e'
-- la definizione stessa dell'affare troppo buono, e chiunque ha dodici ore per portarlo
-- all'asta. Le due decisioni si tengono in piedi a vicenda.
-- =====================================================================================

create or replace function list_player(
    p_player_id bigint,
    p_price     integer
)
returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user      uuid := auth.uid();
    v_club      bigint;
    v_league    bigint;
    v_custom    boolean;
    v_listing   bigint;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;
    if p_price < 1 then
        raise exception 'Il prezzo minimo e'' 1 credito.' using errcode = '22023';
    end if;

    -- Deve essere suo, e il club deve essere suo.
    select c.club_id, c.league_id into v_club, v_league
    from contracts c
    join clubs cl on cl.id = c.club_id
    where c.player_id = p_player_id and cl.owner_user_id = v_user;

    if v_club is null then
        raise exception 'Puoi mettere in vendita solo i tuoi giocatori.' using errcode = '42501';
    end if;

    -- Il giocatore costruito dal proprietario non si vende e non si svincola. Puo'
    -- essere prestato, ed e' un'altra strada.
    select is_custom into v_custom from players where id = p_player_id;
    if v_custom then
        raise exception 'Il tuo giocatore non si vende.' using errcode = '42501';
    end if;

    -- Un giocatore all'asta non va anche a listino: sarebbe venduto due volte.
    if exists (
        select 1 from auctions
        where target_type = 'player' and target_id = p_player_id and status = 'APERTA'
    ) then
        raise exception 'E'' gia'' all''asta.' using errcode = '23505';
    end if;

    insert into listings (league_id, player_id, seller_club_id, price, target_type)
    values (v_league, p_player_id, v_club, p_price, 'player')
    on conflict (target_type, player_id) where status = 'APERTO'
    do update set price = excluded.price, listed_at = now()
    returning id into v_listing;

    return v_listing;
end;
$$;

create or replace function unlist_player(p_player_id bigint)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user uuid := auth.uid();
begin
    update listings l
    set status = 'RITIRATO'
    from clubs c
    where l.player_id = p_player_id
      and l.target_type = 'player'
      and l.status = 'APERTO'
      and l.seller_club_id = c.id
      and c.owner_user_id = v_user;
end;
$$;

-- =====================================================================================
-- COMPRARE
--
-- Il giocatore cambia squadra **adesso**, dentro questa transazione. La finestra di
-- contestazione non lo trattiene: nelle dodici ore gioca, e se poi lo si perde in asta le
-- partite gia' giocate restano dove sono (deciso il 2026-08-24).
-- =====================================================================================

create or replace function buy_player(p_player_id bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user      uuid := auth.uid();
    v_listing   listings%rowtype;
    v_buyer     clubs%rowtype;
    v_config    jsonb;
    v_max_squad integer;
    v_window    integer;
    v_rosa      integer;
    v_available integer;
    v_eta       integer;
    v_day       integer;
    v_duration  integer;
    v_purchase  bigint;
    v_until     timestamptz;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    -- Il lock prima dei controlli: due che comprano lo stesso giocatore nello stesso
    -- istante devono trovarne uno solo disponibile.
    select * into v_listing from listings
    where player_id = p_player_id and target_type = 'player' and status = 'APERTO'
    for update;

    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' piu'' in vendita.');
    end if;

    select * into v_buyer from clubs
    where league_id = v_listing.league_id
      and owner_user_id = v_user
      and parent_club_id is null
    for update;

    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Devi avere un club in questa lega.');
    end if;

    if v_listing.seller_club_id = v_buyer.id then
        return jsonb_build_object('ok', false, 'reason', 'E'' gia'' tuo.');
    end if;

    select config, current_match_day into v_config, v_day
    from leagues where id = v_listing.league_id;

    v_max_squad := coalesce((v_config -> 'setup'  ->> 'maxSquadSize')::integer, 28);
    v_window    := coalesce((v_config -> 'market' ->> 'contestWindowHours')::integer, 12);
    v_duration  := coalesce((v_config -> 'market' ->> 'defaultContractMatchDays')::integer, 19);

    if coalesce((v_config -> 'market' ->> 'instantBuyEnabled')::boolean, true) = false then
        return jsonb_build_object('ok', false, 'reason', 'In questa lega si compra solo all''asta.');
    end if;

    -- Gli under 20 svincolati non si comprano: si trovano mandandoci un osservatore.
    -- E' la regola di `0019`, e vale a maggior ragione qui — a prezzo fisso un
    -- fuoriclasse di diciotto anni sarebbe di chi ha piu' soldi e basta. Chi invece
    -- **possiede** un giovane puo' venderlo: e' roba sua.
    if v_listing.seller_club_id is null then
        select age into v_eta from players where id = p_player_id;
        if v_eta < 20 then
            return jsonb_build_object(
                'ok', false,
                'reason', 'Gli under 20 senza contratto si trovano con gli osservatori.');
        end if;
    end if;

    select count(*) into v_rosa from contracts where club_id = v_buyer.id;
    if v_rosa >= v_max_squad then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Hai gia'' %s giocatori: prima devi liberare un posto.', v_max_squad));
    end if;

    v_available := v_buyer.credits - v_buyer.committed_credits;
    if v_available < v_listing.price then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Ti servono %s crediti, ne hai %s.', v_listing.price, v_available));
    end if;

    -- I soldi si muovono: escono da chi compra, entrano a chi vende (se c'e' un venditore).
    update clubs set credits = credits - v_listing.price where id = v_buyer.id;
    if v_listing.seller_club_id is not null then
        update clubs set credits = credits + v_listing.price where id = v_listing.seller_club_id;
    end if;

    -- Il contratto passa di mano. Lo stipendio resta a zero come fa il tick quando
    -- aggiudica un'asta: il costo per giornata lo ricalcola chi paga gli stipendi.
    insert into contracts (league_id, player_id, club_id, signed_on, expires_on,
                           wage_per_match_day, price_paid)
    values (v_listing.league_id, p_player_id, v_buyer.id, v_day, v_day + v_duration,
            0, v_listing.price)
    on conflict (player_id) do update
      set club_id    = excluded.club_id,
          signed_on  = excluded.signed_on,
          expires_on = excluded.expires_on,
          price_paid = excluded.price_paid,
          squad      = 'prima';

    update listings set status = 'VENDUTO' where id = v_listing.id;

    v_until := now() + make_interval(hours => v_window);

    insert into purchases (league_id, player_id, buyer_club_id, seller_club_id,
                           price, contestable_until)
    values (v_listing.league_id, p_player_id, v_buyer.id, v_listing.seller_club_id,
            v_listing.price, v_until)
    returning id into v_purchase;

    return jsonb_build_object(
        'ok', true,
        'purchase_id', v_purchase,
        'price', v_listing.price,
        'contestable_until', v_until);
end;
$$;

-- =====================================================================================
-- SVINCOLARE
--
-- Gratuito, deciso il 2026-08-24. Nessuna buonuscita: il giocatore torna svincolato e
-- chiunque puo' prenderselo a listino il minuto dopo, compreso il rivale diretto.
--
-- E' **pubblico**: la riga di listino che ne nasce lo dice a tutta la lega. Era l'unica
-- contromisura tenuta fra le due proposte, e serve perche' senza stipendio da pagare
-- liberarsi di un errore di mercato non costa piu' niente.
-- =====================================================================================

create or replace function release_player(p_player_id bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user   uuid := auth.uid();
    v_club   bigint;
    v_league bigint;
    v_custom boolean;
    v_price  integer;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    select c.club_id, c.league_id into v_club, v_league
    from contracts c
    join clubs cl on cl.id = c.club_id
    where c.player_id = p_player_id and cl.owner_user_id = v_user;

    if v_club is null then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    select is_custom into v_custom from players where id = p_player_id;
    if v_custom then
        return jsonb_build_object('ok', false, 'reason', 'Il tuo giocatore non si svincola.');
    end if;

    -- Un giocatore appena comprato e ancora contestabile non si svincola: sparirebbe da
    -- sotto un'asta aperta, e chi ha contestato resterebbe con i crediti impegnati su
    -- un uomo che non esiste piu' in nessuna rosa.
    if exists (
        select 1 from purchases
        where player_id = p_player_id
          and status in ('IN_FINESTRA', 'CONTESTATO')
          and now() < contestable_until
    ) then
        return jsonb_build_object(
            'ok', false,
            'reason', 'L''acquisto e'' ancora contestabile: aspetta che si chiuda.');
    end if;

    delete from contracts where player_id = p_player_id;
    update listings set status = 'RITIRATO'
    where player_id = p_player_id and target_type = 'player' and status = 'APERTO';

    return jsonb_build_object('ok', true);
end;
$$;

-- =====================================================================================
-- CONTESTARE
--
-- L'unica cosa che fa nascere un'asta.
--
-- CHI HA COMPRATO E' GIA' IN TESTA, AL PREZZO CHE HA PAGATO
--
-- E' la regola del 2026-08-24 — chi apre un'asta per comprare ha gia' offerto il prezzo
-- base — applicata qui: chi ha comprato non deve rioffrire su un giocatore che era gia'
-- suo. I suoi crediti risultano impegnati come quelli di tutti gli altri.
--
-- E SCADE INSIEME ALLA FINESTRA
--
-- `ends_at` e' `contestable_until`, non «un'ora da adesso»: chi compra conosce fin dal
-- primo istante l'ora in cui il giocatore e' suo per sempre. L'anti-snipe resta e
-- prolunga solo quella scadenza.
-- =====================================================================================

create or replace function contest_purchase(
    p_purchase_id bigint,
    p_max_amount  integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user      uuid := auth.uid();
    v_purchase  purchases%rowtype;
    v_club      clubs%rowtype;
    v_config    jsonb;
    v_min_raise integer;
    v_minimo    integer;
    v_max_squad integer;
    v_rosa      integer;
    v_available integer;
    v_auction   bigint;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    select * into v_purchase from purchases where id = p_purchase_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Acquisto inesistente.');
    end if;

    select * into v_club from clubs
    where league_id = v_purchase.league_id
      and owner_user_id = v_user
      and parent_club_id is null
    for update;

    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Devi avere un club in questa lega.');
    end if;

    if v_club.id = v_purchase.buyer_club_id then
        return jsonb_build_object('ok', false, 'reason', 'L''hai comprato tu: sei gia'' in testa.');
    end if;
    if v_club.id = v_purchase.seller_club_id then
        return jsonb_build_object('ok', false, 'reason', 'L''hai venduto tu.');
    end if;
    if now() >= v_purchase.contestable_until then
        return jsonb_build_object('ok', false, 'reason', 'Il tempo per contestare e'' finito.');
    end if;
    if v_purchase.status not in ('IN_FINESTRA', 'CONTESTATO') then
        return jsonb_build_object('ok', false, 'reason', 'Questo acquisto e'' gia'' chiuso.');
    end if;

    select config into v_config from leagues where id = v_purchase.league_id;
    v_min_raise := coalesce((v_config -> 'market' ->> 'minimumRaise')::integer, 1);
    v_max_squad := coalesce((v_config -> 'setup'  ->> 'maxSquadSize')::integer, 28);

    if coalesce((v_config -> 'market' ->> 'contestWindowHours')::integer, 12) <= 0 then
        return jsonb_build_object('ok', false, 'reason', 'In questa lega gli acquisti non si contestano.');
    end if;

    v_minimo := v_purchase.price + v_min_raise;
    if p_max_amount < v_minimo then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Per contestare devi offrire almeno %s.', v_minimo));
    end if;

    select count(*) into v_rosa from contracts where club_id = v_club.id;
    if v_rosa >= v_max_squad then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Hai gia'' %s giocatori in rosa.', v_max_squad));
    end if;

    v_available := v_club.credits - v_club.committed_credits;
    if v_available < p_max_amount then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Crediti insufficienti: ne hai %s.', v_available));
    end if;

    -- La prima contestazione crea l'asta; le successive entrano in quella.
    -- **Una sola asta per acquisto**, deciso il 2026-08-24.
    if v_purchase.auction_id is null then
        insert into auctions (league_id, target_type, target_id, started_by,
                              ends_at, starting_price)
        values (v_purchase.league_id, 'player', v_purchase.player_id, v_club.id,
                v_purchase.contestable_until, v_purchase.price)
        returning id into v_auction;

        -- Chi ha comprato entra con la sua offerta, senza rioffrire, e i suoi crediti
        -- risultano impegnati come quelli di chiunque altro.
        insert into bids (auction_id, club_id, max_amount, placed_at)
        values (v_auction, v_purchase.buyer_club_id, v_purchase.price, v_purchase.bought_at);

        update clubs set committed_credits = committed_credits + v_purchase.price
        where id = v_purchase.buyer_club_id;

        update purchases set status = 'CONTESTATO', auction_id = v_auction
        where id = p_purchase_id;
    else
        v_auction := v_purchase.auction_id;
    end if;

    -- L'offerta di chi contesta passa da `place_bid` come tutte le altre: anti-snipe,
    -- blocco fondi e prezzo corrente sono gia' li' dentro, e riscriverli qui vorrebbe
    -- dire due regole d'asta che si separano al primo ritocco.
    return place_bid(v_auction, v_club.id, p_max_amount) || jsonb_build_object('auction_id', v_auction);
end;
$$;

-- =====================================================================================
-- LE FUNZIONI SONO CHIAMABILI DA CHI HA UN ACCESSO
-- =====================================================================================

grant execute on function list_player(bigint, integer)      to authenticated, anon;
grant execute on function unlist_player(bigint)             to authenticated, anon;
grant execute on function buy_player(bigint)                to authenticated, anon;
grant execute on function release_player(bigint)            to authenticated, anon;
grant execute on function contest_purchase(bigint, integer) to authenticated, anon;
