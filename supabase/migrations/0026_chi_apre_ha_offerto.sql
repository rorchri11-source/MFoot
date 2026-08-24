-- =====================================================================================
-- CHI APRE UN'ASTA PER COMPRARE HA GIA' OFFERTO IL PREZZO BASE
--
-- Segnalato dal proprietario il 2026-08-24: «quando una qualsiasi squadra avvia un'asta
-- viene segnata senza offerta di nessuno, neanche quello che l'ha iniziata viene
-- contato».
--
-- Era esatto, e non era un difetto di sola presentazione. `start_auction` inseriva la
-- riga dell'asta e nient'altro, quindi l'asta nasceva **senza nessuno in testa**. Da li'
-- seguivano tre cose tutte sbagliate:
--
--   1. Se nessun altro la guardava, l'asta scadeva DESERTA e chi l'aveva aperta restava
--      a mani vuote — avendo per giunta consumato uno dei suoi slot di aste parallele
--      per tutta la durata. Ed e' il caso piu' frequente, perche' un'asta la si apre su
--      chi si vuole.
--   2. L'app scriveva «nessuno ha ancora offerto» anche sulla propria, che e' falso.
--   3. I crediti di chi apriva non risultavano impegnati, quindi lo stesso club poteva
--      aprire tre aste che insieme valevano piu' di quanto aveva in cassa.
--
-- La regola vive in `core` — `AuctionRules.open`, con le sue prove — e questa funzione
-- fa la stessa identica cosa lato server.
--
-- TRANNE QUANDO SI VENDE
--
-- Chi mette all'asta **un proprio** giocatore e' il venditore: un'offerta sua sarebbe
-- comprare da se' stesso. Li' l'asta nasce vuota davvero, e se nessuno offre resta
-- invenduta — che e' l'esito giusto.
--
-- LE ASTE GIA' APERTE NON SI TOCCANO
--
-- Nessun riempimento retroattivo. Inserire adesso un'offerta su ogni asta gia' aperta
-- vorrebbe dire impegnare crediti che quei club hanno gia' promesso altrove, e su una
-- lega in corso significherebbe spingere qualcuno sotto zero senza che abbia fatto
-- niente. Quelle finiscono come sono; la regola vale da qui in avanti.
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
    v_base      integer;
    v_vendendo  boolean := false;
    v_available integer;
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
    v_base      := greatest(1, coalesce(p_starting_price, 1));

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

            -- E' mio: lo sto cedendo, non comprando.
            v_vendendo := true;

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

    -- Aprire per comprare adesso costa: il prezzo base va impegnato subito. Il controllo
    -- va fatto **prima** di inserire l'asta, o resterebbe aperta un'asta che il suo stesso
    -- proprietario non puo' pagare.
    if not v_vendendo then
        select credits - committed_credits into v_available from clubs where id = v_club;
        if v_available < v_base then
            raise exception
                'Per aprire a % servono % crediti disponibili, ne hai %.',
                v_base, v_base, v_available using errcode = '22023';
        end if;
    end if;

    insert into auctions (league_id, target_type, target_id, started_by, ends_at,
                          starting_price, current_price, status)
    values (p_league_id, p_target_type, p_target_id, v_club,
            now() + make_interval(mins => v_minutes),
            v_base, v_base, 'APERTA')
    returning id into v_auction;

    -- L'offerta di apertura, e i crediti che impegna.
    if not v_vendendo then
        insert into bids (auction_id, club_id, max_amount)
        values (v_auction, v_club, v_base);

        update clubs set committed_credits = committed_credits + v_base where id = v_club;
    end if;

    return v_auction;
end;
$$;

grant execute on function start_auction(bigint, text, bigint, integer) to authenticated;
