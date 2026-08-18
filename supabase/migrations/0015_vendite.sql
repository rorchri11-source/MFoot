-- =====================================================================================
-- MFoot - vendere i propri giocatori all'asta
--
-- Da incollare nell'SQL Editor di Supabase dopo 0014_trattative.sql. Rieseguibile.
--
-- Nessuna colonna nuova: si puo' applicare prima o dopo l'APK, indifferentemente.
-- =====================================================================================

-- =====================================================================================
-- PERCHE' IL MERCATO SI FERMAVA
--
-- Le aste esistevano **solo per gli svincolati**: `start_auction` rifiutava chiunque avesse
-- un contratto, con una ragione buona — senza il controllo si sarebbe potuta "battere
-- all'asta" la rosa altrui.
--
-- L'effetto pero' era che il giorno in cui l'ultimo svincolato trovava squadra, il listino
-- restava vuoto per il resto della stagione. Nessuno vendeva, quindi nessuno comprava,
-- quindi non succedeva piu' niente.
--
-- La correzione e' minima: si puo' mettere all'asta un giocatore sotto contratto **se il
-- contratto e' tuo**. Il divieto che serviva resta intero — la rosa altrui non si tocca — e
-- il mercato ha di nuovo una fonte di offerta.
--
-- ## Perche' nessuna colonna per il venditore
--
-- Perche' c'e' gia': e' `started_by`. Alla chiusura il tick guarda se il giocatore ha un
-- contratto e di chi e':
--
--   * nessun contratto        -> era uno svincolato, come prima
--   * contratto di started_by -> vendita: incassa e il contratto passa al vincitore
--   * contratto di un altro   -> annullata, nel frattempo e' stato scambiato
--
-- Il terzo caso non e' pignoleria: fra l'apertura e la chiusura passa un'ora, e in mezzo lo
-- stesso giocatore puo' essere finito in uno scambio. Completare la vendita lo farebbe
-- esistere in due rose.
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
    v_auction   bigint;
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

    select config into v_config from leagues where id = p_league_id;
    v_minutes   := coalesce((v_config -> 'market' ->> 'auctionDurationMinutes')::integer, 60);
    v_max_open  := coalesce((v_config -> 'market' ->> 'maxParallelAuctionsPerClub')::integer, 3);
    v_min_squad := coalesce((v_config -> 'setup' ->> 'minSquadSize')::integer, 0);

    if p_target_type = 'player' then
        if not exists (select 1 from players where id = p_target_id and league_id = p_league_id) then
            raise exception 'Giocatore inesistente in questa lega.' using errcode = '22023';
        end if;

        select club_id into v_owner from contracts where player_id = p_target_id;

        if v_owner is not null then
            -- La rosa altrui non si tocca. E' il divieto che c'era prima, ristretto a cio'
            -- che doveva davvero impedire.
            if v_owner <> v_club then
                raise exception 'Questo giocatore e'' di un altro club: trattaci.'
                    using errcode = '22023';
            end if;

            -- Non si vende scendendo sotto il minimo di rosa: una squadra sotto il minimo
            -- non scende in campo, e vendere per fare cassa perdendo a tavolino non e' una
            -- scelta, e' un incidente.
            select count(*) into v_rosa from contracts where club_id = v_club;
            if v_rosa - 1 < v_min_squad then
                raise exception 'Con questa cessione resteresti sotto il minimo di rosa (%).',
                    v_min_squad using errcode = '22023';
            end if;

            -- Un giocatore in prestito non e' tuo da vendere: alla scadenza deve tornare a
            -- chi te lo ha prestato.
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

    -- Due aste aperte sullo stesso giocatore vorrebbero dire due club che se lo
    -- aggiudicano entrambi, e un contratto che ne sovrascrive un altro in silenzio.
    if exists (
        select 1 from auctions
        where league_id = p_league_id and status = 'APERTA'
          and target_type = p_target_type and target_id = p_target_id
    ) then
        raise exception 'C''e'' gia'' un''asta aperta su questo obiettivo.' using errcode = '23505';
    end if;

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

grant execute on function start_auction(bigint, text, bigint, integer) to authenticated;

-- =====================================================================================
-- IL PREZZO DI PARTENZA LO SCEGLIE IL VENDITORE
--
-- Nessun minimo imposto. Verrebbe voglia di metterne uno — "almeno meta' del valore" — per
-- impedire di regalare un fuoriclasse a un amico, ma il valore di mercato lo calcola `core`
-- e il database non lo conosce: replicarlo qui vorrebbe dire una seconda formula che si
-- separa dalla prima al primo ritocco.
--
-- E in un'asta il prezzo basso e' un rischio di chi vende, non un danno per gli altri:
-- l'asta dura il suo tempo e chiunque puo' rilanciare. Un mercato in cui si puo' fare un
-- affare a spese di un distratto e' un mercato, non un difetto.
-- =====================================================================================

-- =====================================================================================
-- FINE
-- =====================================================================================
