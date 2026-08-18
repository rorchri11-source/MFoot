-- =====================================================================================
-- MFoot - le promesse fatte ai giocatori
--
-- Da incollare nell'SQL Editor di Supabase dopo 0010_conversations.sql. Rieseguibile.
-- =====================================================================================

-- Un debito che il manager si assume parlando.
--
-- ## Perche' serve una tabella e non basta il morale
--
-- Il colloquio alza il morale subito, e senza una traccia finisce li'. La promessa e' la
-- meccanica che rende il colloquio una scelta invece di un pulsante gratuito: "titolare per
-- tre partite" vale molto se lo mantieni, e **piu' del doppio in negativo** se non lo fai.
-- Perche' quella conseguenza arrivi, qualcuno deve ricordarsi cosa e' stato promesso e
-- controllarlo giornata per giornata — anche a telefoni spenti. Quel qualcuno e' il tick, e
-- questa e' la sua memoria.
create table if not exists promises (
    id          bigserial primary key,
    league_id   bigint not null references leagues(id) on delete cascade,
    club_id     bigint not null references clubs(id) on delete cascade,
    player_id   bigint not null references players(id) on delete cascade,
    type        text   not null check (type in
                    ('TITOLARE_PER_PARTITE','RINNOVO_ENTRO','CESSIONE_ENTRO')),
    made_on     integer not null,
    deadline    integer not null,
    target      integer not null default 0,
    progress    integer not null default 0,
    status      text   not null default 'IN_CORSO'
                check (status in ('IN_CORSO','MANTENUTA','TRADITA')),
    closed_at   timestamptz,
    created_at  timestamptz not null default now()
);

create index if not exists idx_promises_open on promises(league_id, status);

-- Una promessa aperta per giocatore, non di piu'.
--
-- Senza, si potrebbe promettere il posto da titolare cinque volte di fila e incassare
-- cinque volte l'aumento di morale per lo stesso impegno.
create unique index if not exists idx_promises_una_per_giocatore
    on promises(player_id) where status = 'IN_CORSO';

alter table promises enable row level security;

drop policy if exists read_own_promises on promises;
create policy read_own_promises on promises for select
    using (owns_club(club_id));

-- =====================================================================================
-- FARE UNA PROMESSA
-- =====================================================================================

create or replace function make_promise(
    p_player_id bigint,
    p_type      text,
    p_made_on   integer,
    p_deadline  integer,
    p_target    integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_club   bigint;
    v_league bigint;
begin
    select c.club_id, cl.league_id into v_club, v_league
    from contracts c
    join clubs cl on cl.id = c.club_id
    where c.player_id = p_player_id and cl.owner_user_id = auth.uid();

    if v_club is null then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    if exists (select 1 from promises where player_id = p_player_id and status = 'IN_CORSO') then
        return jsonb_build_object('ok', false, 'reason',
            'Gli hai gia'' fatto una promessa che non hai ancora mantenuto.');
    end if;

    insert into promises (league_id, club_id, player_id, type, made_on, deadline, target)
    values (v_league, v_club, p_player_id, p_type, p_made_on, p_deadline, greatest(0, p_target));

    return jsonb_build_object('ok', true);
end;
$$;

grant execute on function make_promise(bigint, text, integer, integer, integer) to authenticated;

-- Il tick chiude le promesse scrivendo direttamente: si collega come servizio e non passa
-- dalle Row Level Security. Non serve nessuna funzione per quello, e non deve esistere:
-- un client che potesse dichiarare mantenuta una promessa la manterrebbe sempre.

-- =====================================================================================
-- FINE
-- =====================================================================================
