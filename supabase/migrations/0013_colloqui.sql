-- =====================================================================================
-- MFoot - i colloqui, con un motivo per esistere
--
-- Da incollare nell'SQL Editor di Supabase dopo 0012_partite_giocate.sql. Rieseguibile.
-- =====================================================================================

-- Un discorso aperto con un giocatore.
--
-- ## Il difetto che questa tabella chiude
--
-- Prima un colloquio non esisteva: l'app ricavava l'argomento da una soglia sul morale,
-- ricalcolandola a ogni apertura della schermata. Parlavi, il morale saliva, la soglia
-- cambiava, compariva l'argomento successivo. Quattro colloqui di fila con lo stesso
-- giocatore, "Incoraggia" a +5 ogni volta, perche' niente da nessuna parte ricordava che
-- avevi gia' parlato.
--
-- Il ricordo e' questa riga.
create table if not exists conversations (
    id           bigserial primary key,
    league_id    bigint not null references leagues(id) on delete cascade,
    club_id      bigint not null references clubs(id) on delete cascade,
    player_id    bigint not null references players(id) on delete cascade,

    topic        text   not null,

    -- Il fatto che lo ha aperto, gia' in italiano: "3 partite senza scendere in campo:
    -- 17a, 18a, 19a". Si salva scritto invece di ricostruirlo perche' il fatto e' vero
    -- **nel momento in cui e' successo**: fra due giornate quelle panchine sono ancora
    -- accadute, ma la query che le trovava non le troverebbe piu'.
    cause        text   not null default '',

    opened_on    integer not null,

    -- Vero quando sei stato tu a convocarlo e lui non aveva niente da dire. Vale un terzo
    -- sul morale, ed e' il motivo per cui la convocazione libera non e' un rubinetto.
    spontaneous  boolean not null default false,

    status       text   not null default 'APERTA'
                 check (status in ('APERTA', 'CHIUSA')),
    tone         text   not null default '',
    morale_delta integer not null default 0,

    created_at   timestamptz not null default now(),
    closed_at    timestamptz
);

-- Un discorso aperto per giocatore, non di piu'.
--
-- Senza, il tick aprirebbe un colloquio "panchina" a ogni giro finche' la panchina dura,
-- e ne troveresti sei identici in elenco.
create unique index if not exists idx_conversations_uno_aperto
    on conversations(player_id) where status = 'APERTA';

create index if not exists idx_conversations_club
    on conversations(club_id, status);

-- L'ultimo colloquio di un giocatore, che e' la domanda dell'attesa fra convocazioni.
create index if not exists idx_conversations_ultimo
    on conversations(player_id, opened_on desc);

alter table conversations enable row level security;

drop policy if exists read_own_conversations on conversations;
create policy read_own_conversations on conversations for select
    using (owns_club(club_id));

-- =====================================================================================
-- CONVOCARE
--
-- Apre un colloquio che nessun fatto giustificava. E' permesso — un manager deve poter
-- parlare a chi vuole — ma con un'attesa fra una volta e l'altra, altrimenti tornerebbe
-- a essere il pulsante "alza morale" con un altro nome.
-- =====================================================================================

create or replace function open_conversation(
    p_player_id bigint,
    p_topic     text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_club    bigint;
    v_league  bigint;
    v_oggi    integer;
    v_ultimo  integer;
    v_attesa  constant integer := 3;
    v_id      bigint;
begin
    select c.club_id, cl.league_id, l.current_match_day
      into v_club, v_league, v_oggi
    from contracts c
    join clubs cl on cl.id = c.club_id
    join leagues l on l.id = cl.league_id
    where c.player_id = p_player_id and cl.owner_user_id = auth.uid();

    if v_club is null then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    if exists (select 1 from conversations
               where player_id = p_player_id and status = 'APERTA') then
        return jsonb_build_object('ok', false, 'reason',
            'Hai gia'' un discorso aperto con lui.');
    end if;

    select max(opened_on) into v_ultimo
    from conversations where player_id = p_player_id;

    if v_ultimo is not null and v_oggi - v_ultimo < v_attesa then
        return jsonb_build_object('ok', false, 'reason',
            format('Gli hai gia'' parlato: aspetta %s giornate.', v_attesa - (v_oggi - v_ultimo)));
    end if;

    insert into conversations (league_id, club_id, player_id, topic, cause,
                               opened_on, spontaneous)
    values (v_league, v_club, p_player_id, p_topic,
            'Lo hai convocato tu.', v_oggi, true)
    returning id into v_id;

    return jsonb_build_object('ok', true, 'id', v_id);
end;
$$;

grant execute on function open_conversation(bigint, text) to authenticated;

-- =====================================================================================
-- RISPONDERE
--
-- Chiude il colloquio e applica il morale **nella stessa transazione**.
--
-- Erano due operazioni separate, e potevano scollarsi in entrambi i modi: chiudere senza
-- pagare, o pagare due volte chiudendo una volta sola. Il vincolo `status = 'APERTA'`
-- nella update fa il resto: una seconda risposta allo stesso colloquio non trova niente
-- da aggiornare e se ne torna senza toccare il morale.
-- =====================================================================================

create or replace function answer_conversation(
    p_conversation_id bigint,
    p_tone            text,
    p_morale_delta    integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_player  bigint;
    v_delta   integer;
    v_morale  integer;
begin
    -- Il limite non e' pignoleria: `p_morale_delta` lo calcola il telefono, e un telefono
    -- puo' dire qualunque numero. Trenta e' oltre il massimo che il motore produce
    -- davvero, quindi non taglia nessuna partita onesta e taglia tutte le altre.
    v_delta := greatest(-30, least(30, coalesce(p_morale_delta, 0)));

    update conversations
    set status = 'CHIUSA',
        tone = coalesce(p_tone, ''),
        morale_delta = v_delta,
        closed_at = now()
    where id = p_conversation_id
      and status = 'APERTA'
      and owns_club(club_id)
    returning player_id into v_player;

    if v_player is null then
        return jsonb_build_object('ok', false, 'reason',
            'Questo discorso non e'' aperto, o non e'' tuo.');
    end if;

    update players
    set morale = greatest(0, least(100, morale + v_delta))
    where id = v_player
    returning morale into v_morale;

    return jsonb_build_object('ok', true, 'morale', v_morale, 'delta', v_delta);
end;
$$;

grant execute on function answer_conversation(bigint, text, integer) to authenticated;

-- =====================================================================================
-- `set_player_morale` NON SERVE PIU'
--
-- Accettava un morale **assoluto**, un numero qualsiasi, quante volte si voleva. Era il
-- prezzo da pagare per far funzionare i colloqui prima che esistessero come oggetto, ed
-- era esattamente il rubinetto che rendeva gratis alzare il morale di tutta la rosa:
-- bastava una richiesta HTTP ripetuta.
--
-- Adesso l'unica strada e' `answer_conversation`, che chiede un colloquio aperto, ne
-- consuma uno per volta e limita l'effetto. La funzione vecchia resta definita perche'
-- toglierla romperebbe una versione dell'app ancora installata su qualche telefono, ma
-- non la puo' piu' chiamare nessuno.
-- =====================================================================================

revoke execute on function set_player_morale(bigint, integer) from authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
