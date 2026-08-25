-- =====================================================================================
-- GLI SVINCOLATI SI COMPRANO SUBITO, SENZA ASPETTARE NIENTE
--
-- IL DIFETTO, DETTO COM'E'
--
-- Il mercato a prezzo fisso e' stato consegnato il 2026-08-25 e dal telefono **non
-- esisteva**: aprendo un giocatore c'era solo «Metti all'asta», come prima. Il motivo e'
-- che il pulsante «Compra» compare soltanto se quel giocatore ha una riga in `listings`, e
-- `listings` la riempiva solo il tick — che gira quando GitHub decide, e nelle ore in cui
-- serviva non ha completato un giro.
--
-- Una funzionalita' che esiste solo se prima gira un processo esterno, per l'utente **non
-- esiste**. Non e' un dettaglio di tempistica: e' un errore di progettazione, e la
-- correzione non e' far girare il tick piu' spesso ma togliere di mezzo la dipendenza.
--
-- LA CORREZIONE
--
-- Uno svincolato e' comprabile **sempre**, con o senza riga di listino. Se la riga non
-- c'e', il prezzo lo calcola il database con il valore di mercato: la stessa curva di
-- `core/market/Valuation.kt`.
--
-- PERCHE' QUI LA FORMULA SI DUPLICA, CONTRO LA REGOLA D'ORO
--
-- La regola del progetto e' che una regola di gioco vive in `core` e non si riscrive in
-- SQL. Qui si fa un'eccezione, per la stessa ragione per cui `place_bid` riscrive le
-- regole d'asta e `create_club` rifa' il conto del budget: **il server non puo' fidarsi
-- del client sui soldi**. Se il prezzo arrivasse dal telefono, chiunque comprerebbe un 90
-- per un credito.
--
-- L'autorita' resta Kotlin. Se un giorno i due conti divergono, e' questo file a
-- sbagliare — e il posto dove guardare e' `Valuation.marketValue`, con `PriceScaleTest`
-- che stampa il listino e fallisce se un prezzo esce dalla sua fascia.
-- =====================================================================================

/*
 * L'interpolazione con i bordi bloccati: `MathX.remap`.
 */
create or replace function mfoot_remap(
    v double precision, in_min double precision, in_max double precision,
    out_min double precision, out_max double precision
)
returns double precision
language sql
immutable
as $$
    select case
        when in_max = in_min then out_min
        else out_min + least(greatest((v - in_min) / (in_max - in_min), 0), 1) * (out_max - out_min)
    end;
$$;

/*
 * Il valore di mercato di un giocatore, in crediti.
 *
 * Ricalca `Valuation.marketValue`: qualita' (curva con esponente 7,5), scala del listino
 * (frazione del budget della lega), eta' e margine di crescita ancora credibile.
 *
 * Il potenziale vero entra qui dentro e **non esce**: la funzione restituisce un prezzo,
 * non i numeri da cui nasce. E' lo stesso motivo per cui `players_public` non contiene
 * `potential_min` e `potential_max`.
 */
create or replace function mfoot_market_value(p_player_id bigint)
returns integer
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_p       players%rowtype;
    v_config  jsonb;
    v_scale   double precision;
    v_quality double precision;
    v_age     double precision;
    v_upside  double precision;
    v_margine double precision;
    v_credib  double precision;
    v_peak_a  integer;
    v_peak_b  integer;
    v_plateau integer;
    v_decline integer;
begin
    select * into v_p from players where id = p_player_id;
    if not found then return 1; end if;

    select config into v_config from leagues where id = v_p.league_id;

    v_scale := coalesce((v_config -> 'economy' ->> 'startingCredits')::double precision, 100000)
             * coalesce((v_config -> 'economy' ->> 'topPlayerBudgetShare')::double precision, 0.65);

    -- Qualita': ((overall - 40) / (93 - 40)) ^ 7,5, con i bordi bloccati.
    v_quality := power(
        least(greatest((v_p.overall::double precision - 40) / (93 - 40), 0), 1),
        7.5
    );

    v_peak_a  := coalesce((v_config -> 'rules' ->> 'peakAgeStart')::integer, 22);
    v_peak_b  := coalesce((v_config -> 'rules' ->> 'peakAgeEnd')::integer, 26);
    v_plateau := coalesce((v_config -> 'rules' ->> 'plateauAgeEnd')::integer, 28);
    v_decline := coalesce((v_config -> 'rules' ->> 'declineAge')::integer, 32);

    v_age := case
        when v_p.age < v_peak_a  then mfoot_remap(v_p.age, 16, v_peak_a, 0.82, 1.15)
        when v_p.age <= v_peak_b then 1.15
        when v_p.age <= v_plateau then mfoot_remap(v_p.age, v_peak_b, v_plateau, 1.15, 1.0)
        when v_p.age < v_decline then mfoot_remap(v_p.age, v_plateau, v_decline, 1.0, 0.62)
        else mfoot_remap(v_p.age, v_decline, 38, 0.62, 0.22)
    end;

    -- Il margine di crescita si paga solo finche' l'eta' lo rende credibile: un
    -- trentaduenne con potenziale 90 non vale nulla di piu', non ci arrivera' mai.
    v_margine := greatest(
        ((v_p.potential_min + v_p.potential_max) / 2.0) - v_p.overall::double precision, 0
    );
    v_credib := case
        when v_p.age <= v_peak_b then 1.0
        when v_p.age <= v_plateau then 0.45
        else 0.0
    end;
    v_upside := 1.0 + (v_margine / 25.0) * v_credib;

    return greatest(round(v_quality * v_scale * v_age * v_upside)::integer, 1);
end;
$$;

-- =====================================================================================
-- COMPRARE, ANCHE SENZA RIGA DI LISTINO
--
-- Tre strade in una funzione sola:
--
--   1. c'e' una riga di listino  -> si paga quel prezzo, e l'incasso va al venditore;
--   2. non c'e' ma il giocatore e' **svincolato** -> si paga il valore di mercato, e i
--      crediti escono dall'economia come in un'asta vinta su uno senza contratto;
--   3. non c'e' e il giocatore ha un contratto -> non e' in vendita, e non lo si tocca.
--
-- La finestra di dodici ore vale in tutti e due i casi in cui si compra: un affare troppo
-- buono resta contestabile anche quando il prezzo l'ha fatto il server.
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
    v_league    bigint;
    v_seller    bigint;
    v_price     integer;
    v_max_squad integer;
    v_window    integer;
    v_rosa      integer;
    v_available integer;
    v_eta       integer;
    v_day       integer;
    v_duration  integer;
    v_purchase  bigint;
    v_until     timestamptz;
    v_da_listino boolean := false;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    select league_id, age into v_league, v_eta from players where id = p_player_id;
    if v_league is null then
        return jsonb_build_object('ok', false, 'reason', 'Giocatore inesistente.');
    end if;

    select * into v_listing from listings
    where player_id = p_player_id and target_type = 'player' and status = 'APERTO'
    for update;

    if found then
        v_da_listino := true;
        v_seller := v_listing.seller_club_id;
        v_price  := v_listing.price;
    else
        -- Nessuna riga: si compra solo se non e' di nessuno.
        if exists (select 1 from contracts where player_id = p_player_id) then
            return jsonb_build_object('ok', false, 'reason', 'Non e'' in vendita.');
        end if;
        v_seller := null;
        v_price  := mfoot_market_value(p_player_id);
    end if;

    select * into v_buyer from clubs
    where league_id = v_league and owner_user_id = v_user and parent_club_id is null
    for update;

    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Devi avere un club in questa lega.');
    end if;
    if v_seller = v_buyer.id then
        return jsonb_build_object('ok', false, 'reason', 'E'' gia'' tuo.');
    end if;

    select config, current_match_day into v_config, v_day from leagues where id = v_league;

    v_max_squad := coalesce((v_config -> 'setup'  ->> 'maxSquadSize')::integer, 28);
    v_window    := coalesce((v_config -> 'market' ->> 'contestWindowHours')::integer, 12);
    v_duration  := coalesce((v_config -> 'market' ->> 'defaultContractMatchDays')::integer, 19);

    if coalesce((v_config -> 'market' ->> 'instantBuyEnabled')::boolean, true) = false then
        return jsonb_build_object('ok', false, 'reason', 'In questa lega si compra solo all''asta.');
    end if;

    -- Un giocatore all'asta non si compra a prezzo fisso: sarebbe venduto due volte.
    if exists (
        select 1 from auctions
        where target_type = 'player' and target_id = p_player_id and status = 'APERTA'
    ) then
        return jsonb_build_object('ok', false, 'reason', 'E'' all''asta: offri li''.');
    end if;

    -- Gli under 20 senza contratto si trovano con gli osservatori, non a listino.
    if v_seller is null and v_eta < 20 then
        return jsonb_build_object(
            'ok', false,
            'reason', 'Gli under 20 senza contratto si trovano con gli osservatori.');
    end if;

    select count(*) into v_rosa from contracts where club_id = v_buyer.id;
    if v_rosa >= v_max_squad then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Hai gia'' %s giocatori: prima devi liberare un posto.', v_max_squad));
    end if;

    v_available := v_buyer.credits - v_buyer.committed_credits;
    if v_available < v_price then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Ti servono %s crediti, ne hai %s.', v_price, v_available));
    end if;

    update clubs set credits = credits - v_price where id = v_buyer.id;
    if v_seller is not null then
        update clubs set credits = credits + v_price where id = v_seller;
    end if;

    insert into contracts (league_id, player_id, club_id, signed_on, expires_on,
                           wage_per_match_day, price_paid)
    values (v_league, p_player_id, v_buyer.id, v_day, v_day + v_duration, 0, v_price)
    on conflict (player_id) do update
      set club_id    = excluded.club_id,
          signed_on  = excluded.signed_on,
          expires_on = excluded.expires_on,
          price_paid = excluded.price_paid,
          squad      = 'prima';

    if v_da_listino then
        update listings set status = 'VENDUTO' where id = v_listing.id;
    end if;

    v_until := now() + make_interval(hours => v_window);

    insert into purchases (league_id, player_id, buyer_club_id, seller_club_id,
                           price, contestable_until)
    values (v_league, p_player_id, v_buyer.id, v_seller, v_price, v_until)
    returning id into v_purchase;

    return jsonb_build_object(
        'ok', true,
        'purchase_id', v_purchase,
        'price', v_price,
        'contestable_until', v_until);
end;
$$;

/*
 * Il prezzo che l'app scrive sul pulsante.
 *
 * Serve perche' «Compra» senza numero non e' un pulsante: e' una scommessa. Il valore che
 * il telefono calcola da solo usa la **stima** del potenziale — la forbice larga che ogni
 * club vede in modo diverso — mentre il prezzo vero nasce dal potenziale reale, che non
 * esce mai dal server. Chiedendolo qui, il numero sul pulsante e' quello che verra'
 * addebitato.
 */
create or replace function free_agent_price(p_player_id bigint)
returns integer
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_league bigint;
begin
    select league_id into v_league from players where id = p_player_id;
    if v_league is null then return null; end if;
    if not is_member_of(v_league) then return null; end if;
    if exists (select 1 from contracts where player_id = p_player_id) then return null; end if;

    return mfoot_market_value(p_player_id);
end;
$$;

grant execute on function mfoot_remap(double precision, double precision, double precision, double precision, double precision) to authenticated, anon;
grant execute on function mfoot_market_value(bigint) to authenticated, anon;
grant execute on function free_agent_price(bigint)   to authenticated, anon;
