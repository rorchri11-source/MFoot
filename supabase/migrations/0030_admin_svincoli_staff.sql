-- =====================================================================================
-- TRE COSE DECISE IL 2026-08-24 CHE MANCAVANO
--
--   1. Lo svincolo e' **pubblico**: lo sa tutta la lega.
--   2. Lo **staff** si compra dal listino come i giocatori.
--   3. L'**admin** puo' aggiungere e togliere giocatori a qualsiasi club.
-- =====================================================================================

-- =====================================================================================
-- 1. LO SVINCOLO SI ANNUNCIA
--
-- Era l'unica contromisura tenuta fra le due proposte quando il proprietario ha scelto
-- lo svincolo gratuito, e serve perche' senza stipendio da pagare liberarsi di un errore
-- di mercato non costa piu' niente. Chi svincola un 84 per far cassa lo fa davanti a
-- tutti.
--
-- Una riga per club: `notifications.club_id` e' per club, e il tick le consegna gia'
-- (quelle da riepilogo finiscono nel messaggio giornaliero, che e' il registro giusto —
-- uno svincolo non e' una decisione con scadenza, e non merita un ping immediato).
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
    v_nome   text;
    v_squadra text;
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

    select is_custom, first_name || ' ' || last_name into v_custom, v_nome
    from players where id = p_player_id;

    if v_custom then
        return jsonb_build_object('ok', false, 'reason', 'Il tuo giocatore non si svincola.');
    end if;

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

    select short_name into v_squadra from clubs where id = v_club;

    delete from contracts where player_id = p_player_id;
    update listings set status = 'RITIRATO'
    where player_id = p_player_id and status = 'APERTO';

    -- L'annuncio, a ogni club della lega tranne chi lo ha svincolato: lui lo sa gia'.
    insert into notifications (league_id, club_id, kind, urgency, body)
    select v_league, c.id, 'mercato', 'riepilogo',
           format('%s ha svincolato %s: adesso puo'' prenderlo chiunque.', v_squadra, v_nome)
    from clubs c
    where c.league_id = v_league and c.id <> v_club and c.parent_club_id is null;

    return jsonb_build_object('ok', true);
end;
$$;

-- =====================================================================================
-- 2. LO STAFF SUL LISTINO
--
-- `start_auction` accetta `target_type = 'staff'` dalla migrazione `0019` e nessuna
-- schermata lo usa mai. Con il listino, allenatori, preparatori e osservatori diventano
-- una riga in piu' invece che un sistema nuovo.
--
-- La colonna e' nullable e con un default: le righe gia' scritte restano valide e
-- continuano a voler dire «un giocatore».
-- =====================================================================================

-- La colonna che distingue giocatori e staff nasce **con la tabella**, in `0028`, e non
-- e' una questione di ordine: `players` e `staff` hanno sequenze separate, quindi il
-- giocatore 7 e l'allenatore 7 esistono tutti e due. Aggiungerla dopo avrebbe lasciato
-- esistere un listino ambiguo per tutto il tempo fra le due migrazioni, e un acquisto
-- sbagliato dentro quella finestra non si sarebbe piu' potuto distinguere da uno giusto.
--
-- Qui restano solo le due funzioni che servono allo staff.

create or replace function list_staff(p_staff_id bigint, p_price integer)
returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user    uuid := auth.uid();
    v_club    bigint;
    v_league  bigint;
    v_listing bigint;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;
    if p_price < 1 then
        raise exception 'Il prezzo minimo e'' 1 credito.' using errcode = '22023';
    end if;

    select s.club_id, s.league_id into v_club, v_league
    from staff s
    join clubs c on c.id = s.club_id
    where s.id = p_staff_id and c.owner_user_id = v_user;

    if v_club is null then
        raise exception 'Puoi mettere in vendita solo il tuo staff.' using errcode = '42501';
    end if;

    insert into listings (league_id, player_id, seller_club_id, price, target_type)
    values (v_league, p_staff_id, v_club, p_price, 'staff')
    on conflict (target_type, player_id) where status = 'APERTO'
    do update set price = excluded.price, listed_at = now()
    returning id into v_listing;

    return v_listing;
end;
$$;

create or replace function buy_staff(p_staff_id bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user      uuid := auth.uid();
    v_listing   listings%rowtype;
    v_buyer     clubs%rowtype;
    v_available integer;
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    select * into v_listing from listings
    where player_id = p_staff_id and target_type = 'staff' and status = 'APERTO'
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

    v_available := v_buyer.credits - v_buyer.committed_credits;
    if v_available < v_listing.price then
        return jsonb_build_object(
            'ok', false,
            'reason', format('Ti servono %s crediti, ne hai %s.', v_listing.price, v_available));
    end if;

    update clubs set credits = credits - v_listing.price where id = v_buyer.id;
    if v_listing.seller_club_id is not null then
        update clubs set credits = credits + v_listing.price where id = v_listing.seller_club_id;
    end if;

    update staff set club_id = v_buyer.id where id = p_staff_id;
    update listings set status = 'VENDUTO' where id = v_listing.id;

    -- Lo staff **non** entra nella finestra di contestazione.
    --
    -- Non e' una dimenticanza: la finestra esiste perche' un giocatore svenduto sposta
    -- gli equilibri di un campionato, e per dare a chi lo voleva la possibilita' di
    -- reagire. Un preparatore atletico in piu' non ribalta una stagione, e dodici ore di
    -- attesa su ogni assunzione renderebbero lo staff piu' faticoso dei giocatori — che e'
    -- il contrario di quello che serve, visto che finora non lo comprava nessuno.
    return jsonb_build_object('ok', true, 'price', v_listing.price);
end;
$$;

-- =====================================================================================
-- 3. LO STRUMENTO DELL'AMMINISTRATORE
--
-- Deciso il 2026-08-24: l'admin puo' aggiungere e togliere giocatori a qualsiasi club e
-- muoverne i crediti. Serve a riparare le leghe rotte.
--
-- SENZA REGISTRO PUBBLICO, E PERCHE' QUESTO PESA
--
-- Il registro visibile a tutti e' stato proposto e scartato nella stessa sessione: si
-- regge sulla fiducia del gruppo, che in una lega di amici e' una base legittima.
--
-- Resta vero il motivo per cui era stato proposto, e chi mette le mani qui deve saperlo:
-- **l'admin e' uno dei concorrenti**. E' la ragione per cui gli obiettivi di stagione li
-- decide una regola in `core` e non lui. Questo e' l'unico punto del gioco dove quella
-- separazione non c'e', e per questo le funzioni sono **tre e strette** — spostare un
-- giocatore, toglierlo, correggere i crediti — invece di un'unica funzione capace di
-- tutto. Niente scorciatoie che assegnano premi, cambiano risultati o toccano le aste.
-- =====================================================================================

create or replace function admin_assign_player(
    p_player_id bigint,
    p_club_id   bigint
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user    uuid := auth.uid();
    v_league  bigint;
    v_admin   boolean;
    v_day     integer;
    v_config  jsonb;
    v_duration integer;
begin
    select league_id into v_league from clubs where id = p_club_id;
    if v_league is null then
        return jsonb_build_object('ok', false, 'reason', 'Club inesistente.');
    end if;

    select is_admin into v_admin from league_members
    where league_id = v_league and user_id = v_user;

    if not coalesce(v_admin, false) then
        raise exception 'Solo l''amministratore della lega.' using errcode = '42501';
    end if;

    if not exists (select 1 from players where id = p_player_id and league_id = v_league) then
        return jsonb_build_object('ok', false, 'reason', 'Giocatore di un''altra lega.');
    end if;

    -- Un giocatore dentro una finestra di contestazione non si sposta a mano: sotto c'e'
    -- un'asta aperta con crediti impegnati, e spostarlo lascerebbe chi ha contestato a
    -- inseguire un uomo che non c'e' piu'.
    if exists (
        select 1 from purchases
        where player_id = p_player_id
          and status in ('IN_FINESTRA', 'CONTESTATO')
          and now() < contestable_until
    ) then
        return jsonb_build_object('ok', false, 'reason', 'C''e'' una contestazione in corso.');
    end if;

    select config, current_match_day into v_config, v_day from leagues where id = v_league;
    v_duration := coalesce((v_config -> 'market' ->> 'defaultContractMatchDays')::integer, 19);

    insert into contracts (league_id, player_id, club_id, signed_on, expires_on,
                           wage_per_match_day, price_paid)
    values (v_league, p_player_id, p_club_id, v_day, v_day + v_duration, 0, 0)
    on conflict (player_id) do update
      set club_id    = excluded.club_id,
          signed_on  = excluded.signed_on,
          expires_on = excluded.expires_on,
          squad      = 'prima';

    update listings set status = 'RITIRATO'
    where player_id = p_player_id and target_type = 'player' and status = 'APERTO';

    return jsonb_build_object('ok', true);
end;
$$;

create or replace function admin_release_player(p_player_id bigint)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user   uuid := auth.uid();
    v_league bigint;
    v_admin  boolean;
begin
    select league_id into v_league from players where id = p_player_id;
    if v_league is null then
        return jsonb_build_object('ok', false, 'reason', 'Giocatore inesistente.');
    end if;

    select is_admin into v_admin from league_members
    where league_id = v_league and user_id = v_user;

    if not coalesce(v_admin, false) then
        raise exception 'Solo l''amministratore della lega.' using errcode = '42501';
    end if;

    if exists (
        select 1 from purchases
        where player_id = p_player_id
          and status in ('IN_FINESTRA', 'CONTESTATO')
          and now() < contestable_until
    ) then
        return jsonb_build_object('ok', false, 'reason', 'C''e'' una contestazione in corso.');
    end if;

    delete from contracts where player_id = p_player_id;
    update listings set status = 'RITIRATO'
    where player_id = p_player_id and target_type = 'player' and status = 'APERTO';

    return jsonb_build_object('ok', true);
end;
$$;

/*
 * Corregge i crediti di un club.
 *
 * `p_delta` e' una **differenza**, non un totale: e' l'unica forma che non puo' cancellare
 * per sbaglio quello che il club ha guadagnato giocando. Scrivere «metti 300» a un club
 * che ne ha 412 e' una perdita silenziosa; scrivere «+300» non lo e' mai.
 */
create or replace function admin_adjust_credits(
    p_club_id bigint,
    p_delta   integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user   uuid := auth.uid();
    v_league bigint;
    v_admin  boolean;
    v_dopo   integer;
begin
    select league_id into v_league from clubs where id = p_club_id;
    if v_league is null then
        return jsonb_build_object('ok', false, 'reason', 'Club inesistente.');
    end if;

    select is_admin into v_admin from league_members
    where league_id = v_league and user_id = v_user;

    if not coalesce(v_admin, false) then
        raise exception 'Solo l''amministratore della lega.' using errcode = '42501';
    end if;

    update clubs set credits = greatest(0, credits + p_delta)
    where id = p_club_id
    returning credits into v_dopo;

    return jsonb_build_object('ok', true, 'credits', v_dopo);
end;
$$;

grant execute on function list_staff(bigint, integer)          to authenticated, anon;
grant execute on function buy_staff(bigint)                    to authenticated, anon;
grant execute on function admin_assign_player(bigint, bigint)  to authenticated, anon;
grant execute on function admin_release_player(bigint)         to authenticated, anon;
grant execute on function admin_adjust_credits(bigint, integer) to authenticated, anon;
