-- =====================================================================================
-- MFoot - prestiti e amichevoli, nella stessa casella degli scambi
--
-- Da incollare nell'SQL Editor di Supabase dopo 0013_colloqui.sql. Rieseguibile.
--
-- ATTENZIONE ALL'ORDINE: questa migrazione va applicata **prima** di installare l'APK.
-- Aggiunge `competitions.kind`, che l'app legge in una SELECT condivisa: se l'app arriva
-- per prima, PostgREST rifiuta l'intera query per una colonna che non esiste e la
-- schermata delle competizioni smette di funzionare. E' l'errore gia' commesso una volta
-- con `clubs.division_level`.
-- =====================================================================================

-- =====================================================================================
-- UNA TABELLA SOLA PER TRE TRATTATIVE
--
-- `trades` funziona gia' da un capo all'altro: proposta, risposta, ritiro, controlli
-- rifatti al momento dell'accettazione, tutto dentro una transazione. Scrivere due sistemi
-- paralleli per prestiti e amichevoli vorrebbe dire tre macchine a stati che si separano
-- al primo ritocco, tre caselle da guardare e tre pezzi di AI che rispondono.
--
-- Due colonne bastano.
-- =====================================================================================

alter table trades add column if not exists kind text not null default 'SCAMBIO';

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'trades_kind_valido'
    ) then
        alter table trades add constraint trades_kind_valido
            check (kind in ('SCAMBIO', 'PRESTITO', 'AMICHEVOLE'));
    end if;
end $$;

-- Le condizioni che dipendono dal tipo.
--
-- Un jsonb e non sei colonne: durata e ingaggio a carico hanno senso solo per un prestito,
-- la data solo per un'amichevole. Sei colonne quasi sempre nulle sarebbero sei modi di
-- sbagliarsi e sei controlli da scrivere per dire "questa qui non si usa".
alter table trades add column if not exists terms jsonb not null default '{}'::jsonb;

-- Le righe che c'erano gia' sono scambi, ed e' cio' che il default dice.

-- =====================================================================================
-- LE COMPETIZIONI CHE NON FANNO CLASSIFICA
-- =====================================================================================

alter table competitions add column if not exists kind text not null default 'UFFICIALE';

-- =====================================================================================
-- PROPORRE UN PRESTITO
--
-- Il giocatore resta di chi lo presta e torna alla scadenza. `loans` esiste dal primo
-- schema e il tick sa gia' far rientrare un prestito scaduto: mancava solo il modo di
-- proporne uno.
-- =====================================================================================

create or replace function propose_loan(
    p_from_club bigint,
    p_to_club   bigint,
    p_player_id bigint,
    -- Giornate di durata.
    p_match_days integer,
    -- Quanto paga per giornata chi lo prende. Zero e' lecito.
    p_fee        integer,
    p_wage_paid_by_borrower boolean,
    p_can_play_against_owner boolean,
    p_message    text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_from clubs%rowtype;
    v_to   clubs%rowtype;
    v_id   bigint;
begin
    select * into v_from from clubs where id = p_from_club;
    if not found or v_from.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' il tuo club.');
    end if;

    select * into v_to from clubs where id = p_to_club;
    if not found or v_to.league_id <> v_from.league_id then
        return jsonb_build_object('ok', false, 'reason', 'L''altro club non e'' in questa lega.');
    end if;

    if not exists (
        select 1 from contracts where player_id = p_player_id and club_id = p_from_club
    ) then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    if exists (select 1 from loans where player_id = p_player_id and active) then
        return jsonb_build_object('ok', false, 'reason', 'E'' gia'' in prestito.');
    end if;

    if coalesce(p_match_days, 0) < 1 then
        return jsonb_build_object('ok', false, 'reason', 'Un prestito dura almeno una giornata.');
    end if;

    insert into trades (league_id, from_club, to_club, offered, wanted, cash, message,
                        kind, terms)
    values (
        v_from.league_id, p_from_club, p_to_club,
        array[p_player_id]::bigint[], '{}', 0, coalesce(trim(p_message), ''),
        'PRESTITO',
        jsonb_build_object(
            'matchDays', p_match_days,
            'fee', greatest(0, coalesce(p_fee, 0)),
            'wagePaidByBorrower', coalesce(p_wage_paid_by_borrower, true),
            'canPlayAgainstOwner', coalesce(p_can_play_against_owner, false)
        )
    )
    returning id into v_id;

    return jsonb_build_object('ok', true, 'trade_id', v_id);
end;
$$;

grant execute on function propose_loan(bigint, bigint, bigint, integer, integer,
                                       boolean, boolean, text) to authenticated;

-- =====================================================================================
-- PROPORRE UN'AMICHEVOLE
--
-- La partita non ufficiale passa dallo stesso motore delle altre. Serve una competizione a
-- cui appenderla, perche' `fixtures.competition_id` e' `not null` — e va bene cosi': e'
-- quel vincolo che impedisce a una partita di esistere fuori da ogni contesto.
--
-- Ogni lega ha quindi una competizione "Amichevoli" con `kind = 'AMICHEVOLE'`, creata alla
-- prima accettazione. `Standings` calcola per competizione, quindi non tocca nessuna
-- classifica, e `MatchImportance.AMICHEVOLE` fa il resto lato motore.
-- =====================================================================================

create or replace function propose_friendly(
    p_from_club bigint,
    p_to_club   bigint,
    p_kickoff   timestamptz,
    p_message   text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_from clubs%rowtype;
    v_to   clubs%rowtype;
    v_id   bigint;
begin
    select * into v_from from clubs where id = p_from_club;
    if not found or v_from.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' il tuo club.');
    end if;

    select * into v_to from clubs where id = p_to_club;
    if not found or v_to.league_id <> v_from.league_id then
        return jsonb_build_object('ok', false, 'reason', 'L''altro club non e'' in questa lega.');
    end if;

    if p_kickoff is null or p_kickoff <= now() then
        return jsonb_build_object('ok', false, 'reason', 'L''orario e'' gia'' passato.');
    end if;

    -- Nessuno dei due deve avere gia' un impegno in quell'ora. Un'amichevole che si
    -- sovrappone a una partita di campionato manderebbe in campo la stessa rosa due volte,
    -- e la stanchezza pagherebbe il conto nella partita che conta.
    if exists (
        select 1 from fixtures f
        where f.league_id = v_from.league_id and not f.played
          and f.kickoff between p_kickoff - interval '3 hours' and p_kickoff + interval '3 hours'
          and (f.home_club_id in (p_from_club, p_to_club)
            or f.away_club_id in (p_from_club, p_to_club))
    ) then
        return jsonb_build_object('ok', false, 'reason',
            'Una delle due squadre gioca gia'' a quell''ora.');
    end if;

    insert into trades (league_id, from_club, to_club, offered, wanted, cash, message,
                        kind, terms)
    values (
        v_from.league_id, p_from_club, p_to_club, '{}', '{}', 0,
        coalesce(trim(p_message), ''),
        'AMICHEVOLE',
        jsonb_build_object('kickoff', p_kickoff)
    )
    returning id into v_id;

    return jsonb_build_object('ok', true, 'trade_id', v_id);
end;
$$;

grant execute on function propose_friendly(bigint, bigint, timestamptz, text) to authenticated;

-- =====================================================================================
-- ACCETTARE UN PRESTITO O UN'AMICHEVOLE
--
-- `respond_trade` sa accettare gli scambi e non va toccata: funziona, ed e' la funzione
-- piu' delicata del sistema. Questa affianca, e si occupa dei due tipi nuovi.
--
-- Come l'altra, rifa' **tutti** i controlli al momento dell'accettazione: fra la proposta e
-- la risposta possono passare giorni, e in mezzo il giocatore prestato puo' essere stato
-- venduto.
-- =====================================================================================

create or replace function respond_deal(
    p_trade_id bigint,
    p_accept   boolean,
    p_answer   text default ''
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_trade   trades%rowtype;
    v_to      clubs%rowtype;
    v_player  bigint;
    v_oggi    integer;
    v_comp    bigint;
    v_kickoff timestamptz;
begin
    select * into v_trade from trades where id = p_trade_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Proposta inesistente.');
    end if;
    if v_trade.status <> 'PROPOSTA' then
        return jsonb_build_object('ok', false, 'reason', 'A questa proposta si e'' gia'' risposto.');
    end if;
    if v_trade.kind = 'SCAMBIO' then
        return jsonb_build_object('ok', false, 'reason', 'Uno scambio si accetta con respond_trade.');
    end if;

    select * into v_to from clubs where id = v_trade.to_club for update;
    if v_to.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' una proposta per te.');
    end if;

    if not p_accept then
        update trades set status = 'RIFIUTATA',
            answer = coalesce(trim(p_answer), ''), answered_at = now()
        where id = p_trade_id;
        return jsonb_build_object('ok', true, 'status', 'RIFIUTATA');
    end if;

    select current_match_day into v_oggi from leagues where id = v_trade.league_id;

    if v_trade.kind = 'PRESTITO' then
        v_player := v_trade.offered[1];

        if not exists (
            select 1 from contracts where player_id = v_player and club_id = v_trade.from_club
        ) then
            update trades set status = 'SCADUTA',
                answer = 'Nel frattempo ha ceduto il giocatore.', answered_at = now()
            where id = p_trade_id;
            return jsonb_build_object('ok', false, 'reason', 'Nel frattempo ha ceduto il giocatore.');
        end if;

        if exists (select 1 from loans where player_id = v_player and active) then
            return jsonb_build_object('ok', false, 'reason', 'E'' gia'' in prestito da qualche altra parte.');
        end if;

        insert into loans (league_id, player_id, owner_club_id, borrower_club_id,
                           starts_on, ends_on, fee_per_match_day,
                           wage_paid_by_borrower, can_play_against_owner, active)
        values (
            v_trade.league_id, v_player, v_trade.from_club, v_trade.to_club,
            v_oggi, v_oggi + greatest(1, (v_trade.terms ->> 'matchDays')::int),
            coalesce((v_trade.terms ->> 'fee')::int, 0),
            coalesce((v_trade.terms ->> 'wagePaidByBorrower')::boolean, true),
            coalesce((v_trade.terms ->> 'canPlayAgainstOwner')::boolean, false),
            true
        );

        -- Il contratto si sposta come per uno scambio: e' cio' che fa scendere in campo il
        -- giocatore con la maglia giusta. Quello che distingue un prestito da una cessione
        -- e' la riga in `loans`, che dice a chi torna e quando.
        update contracts set club_id = v_trade.to_club where player_id = v_player;

    elsif v_trade.kind = 'AMICHEVOLE' then
        v_kickoff := (v_trade.terms ->> 'kickoff')::timestamptz;

        if v_kickoff is null or v_kickoff <= now() then
            update trades set status = 'SCADUTA',
                answer = 'L''orario e'' passato.', answered_at = now()
            where id = p_trade_id;
            return jsonb_build_object('ok', false, 'reason', 'L''orario e'' passato.');
        end if;

        select id into v_comp from competitions
        where league_id = v_trade.league_id and kind = 'AMICHEVOLE' limit 1;

        if v_comp is null then
            insert into competitions (league_id, name, type, config, participants, kind)
            values (v_trade.league_id, 'Amichevoli', 'GIRONE', '{}'::jsonb, '{}', 'AMICHEVOLE')
            returning id into v_comp;
        end if;

        -- `match_day` a zero: un'amichevole non appartiene a nessuna giornata, e darle
        -- quella corrente la farebbe contare nelle promesse "titolare per N partite".
        insert into fixtures (league_id, competition_id, round, round_label,
                              home_club_id, away_club_id, match_day, kickoff)
        values (v_trade.league_id, v_comp, 0, 'Amichevole',
                v_trade.from_club, v_trade.to_club, 0, v_kickoff);
    end if;

    update trades set status = 'ACCETTATA',
        answer = coalesce(trim(p_answer), ''), answered_at = now()
    where id = p_trade_id;

    return jsonb_build_object('ok', true, 'status', 'ACCETTATA');
end;
$$;

grant execute on function respond_deal(bigint, boolean, text) to authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
