-- =====================================================================================
-- MFoot - le trattative diventano un botta e risposta
--
-- Da incollare nell'SQL Editor di Supabase dopo 0020_aste_trasparenti.sql. Rieseguibile.
-- =====================================================================================

-- =====================================================================================
-- IL PROBLEMA
--
-- Una proposta si poteva accettare o rifiutare, e basta. Il novanta per cento finiva in un
-- no secco che non insegnava niente: chi lo riceveva non sapeva se aveva sbagliato di poco
-- o di tanto, e riprovava alla cieca o smetteva di provarci.
--
-- E c'era un'asimmetria che faceva ridere amaro: **le AI scrivevano un messaggio** con la
-- loro proposta, gli umani no — il campo `message` esisteva ed era mostrato, ma non c'era
-- nessuna casella dove scriverlo, e ogni proposta umana partiva vuota.
-- =====================================================================================

-- A quale proposta risponde questa.
--
-- Una controproposta e' **una proposta nuova nella direzione opposta**, non una modifica di
-- quella vecchia: cosi' resta la storia di chi ha chiesto cosa, e la casella "ricevute" di
-- ognuno continua a voler dire "qualcuno aspetta te".
alter table trades add column if not exists replies_to bigint references trades(id) on delete set null;

create index if not exists idx_trades_catena on trades(replies_to);

-- Una proposta a cui e' stata opposta una controproposta non e' ne' accettata ne'
-- rifiutata: e' andata avanti. Distinguerlo serve a raccontarlo.
do $$
begin
    alter table trades drop constraint if exists trades_status_check;
exception when others then null;
end $$;

alter table trades drop constraint if exists trades_status_check1;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'trades_stato_valido') then
        alter table trades add constraint trades_stato_valido
            check (status in ('PROPOSTA','ACCETTATA','RIFIUTATA','RITIRATA','SCADUTA','CONTROPROPOSTA'));
    end if;
end $$;

-- =====================================================================================
-- CONTROPROPORRE
--
-- Chi ha ricevuto rilancia: gli stessi giocatori, una cifra diversa.
--
-- ## Perche' solo il denaro
--
-- Perche' cambiare anche i giocatori non e' rispondere, e' proporre un'altra cosa — e per
-- quello c'e' gia' `propose_trade`. «Non il terzino, dammi il portiere» e' una trattativa
-- nuova; «lo stesso affare ma con dieci milioni sopra» e' una risposta.
-- =====================================================================================

create or replace function counter_trade(
    p_trade_id bigint,
    p_cash     integer,
    p_message  text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_trade trades%rowtype;
    v_to    clubs%rowtype;
    v_id    bigint;
begin
    select * into v_trade from trades where id = p_trade_id for update;
    if not found then
        return jsonb_build_object('ok', false, 'reason', 'Proposta inesistente.');
    end if;
    if v_trade.status <> 'PROPOSTA' then
        return jsonb_build_object('ok', false, 'reason', 'A questa proposta si e'' gia'' risposto.');
    end if;

    select * into v_to from clubs where id = v_trade.to_club;
    if v_to.owner_user_id is distinct from auth.uid() then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' una proposta per te.');
    end if;

    -- Il denaro promesso deve esserci adesso, come per una proposta qualsiasi. Non viene
    -- impegnato: una controproposta non e' un vincolo, e bloccare i crediti su ognuna
    -- vorrebbe dire non poter piu' partecipare a un'asta per aver risposto a un messaggio.
    if coalesce(p_cash, 0) > 0
       and p_cash > (v_to.credits - v_to.committed_credits) then
        return jsonb_build_object('ok', false, 'reason', 'Non hai quel denaro libero.');
    end if;

    update trades
    set status = 'CONTROPROPOSTA', answered_at = now(),
        answer = coalesce(trim(p_message), '')
    where id = p_trade_id;

    -- I due lati si scambiano: adesso a proporre e' chi aveva ricevuto. Con i lati
    -- invariati la controproposta finirebbe nella casella sbagliata, ad aspettare una
    -- risposta da chi l'ha scritta.
    insert into trades (league_id, from_club, to_club, offered, wanted, cash, message,
                        kind, terms, replies_to)
    values (
        v_trade.league_id,
        v_trade.to_club,
        v_trade.from_club,
        v_trade.wanted,
        v_trade.offered,
        coalesce(p_cash, 0),
        coalesce(trim(p_message), ''),
        v_trade.kind,
        v_trade.terms,
        p_trade_id
    )
    returning id into v_id;

    return jsonb_build_object('ok', true, 'trade_id', v_id);
end;
$$;

grant execute on function counter_trade(bigint, integer, text) to authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
