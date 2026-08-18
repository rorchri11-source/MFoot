-- =====================================================================================
-- MFoot - le divisioni, applicate davvero
--
-- Da incollare nell'SQL Editor di Supabase dopo 0008_trades.sql. Rieseguibile.
-- =====================================================================================

-- In quale divisione gioca ogni club. 1 e' la massima.
--
-- Sta su `clubs` e non in una tabella a parte perche' e' un attributo del club come il
-- nome: una tabella di appartenenze renderebbe possibile lo stato assurdo "club che non
-- sta in nessuna divisione", e ci sarebbe da decidere cosa farne a ogni lettura.
alter table clubs add column if not exists division_level integer not null default 1;

create index if not exists idx_clubs_division on clubs(league_id, division_level);

-- =====================================================================================
-- ASSEGNARE
--
-- Chi va in quale divisione. Lo decide l'app e lo scrive qui, ma **le dimensioni le
-- controlla il database**: e' l'unico invariante che conta davvero, e l'unico errore che
-- non si corregge piu'. Se una stagione la Serie A finisse con un club in piu' e la Serie C
-- con uno in meno, l'anno dopo nessuno saprebbe rimetterle a posto.
-- =====================================================================================

create or replace function assign_divisions(
    p_league_id bigint,
    -- [{club_id, level}, ...]
    p_assignments jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user     uuid := auth.uid();
    v_count    int;
    v_expected int;
    v_max      int;
begin
    if not exists (
        select 1 from league_members
        where league_id = p_league_id and user_id = v_user and is_admin
    ) then
        raise exception 'Solo l''amministratore puo'' cambiare le divisioni.'
            using errcode = '42501';
    end if;

    if jsonb_typeof(p_assignments) <> 'array' then
        return jsonb_build_object('ok', false, 'reason', 'Assegnazioni non valide.');
    end if;

    -- Ogni club della lega, una volta sola.
    select count(*) into v_expected from clubs where league_id = p_league_id;
    select count(distinct (a ->> 'club_id')::bigint) into v_count
    from jsonb_array_elements(p_assignments) a;

    if v_count <> v_expected then
        return jsonb_build_object('ok', false, 'reason',
            format('Ci sono %s club e %s assegnazioni: devono coincidere.', v_expected, v_count));
    end if;

    select max((a ->> 'level')::int) into v_max from jsonb_array_elements(p_assignments) a;
    if v_max is null or v_max < 1 then
        return jsonb_build_object('ok', false, 'reason', 'Livelli non validi.');
    end if;

    -- Nessuna divisione puo' restare vuota: un buco nella scala vorrebbe dire promozioni
    -- verso il nulla, e il campionato non tornerebbe piu'.
    if exists (
        select l from generate_series(1, v_max) l
        where not exists (
            select 1 from jsonb_array_elements(p_assignments) a
            where (a ->> 'level')::int = l
        )
    ) then
        return jsonb_build_object('ok', false, 'reason',
            'Una delle divisioni resterebbe senza squadre.');
    end if;

    update clubs c
    set division_level = (a ->> 'level')::int
    from jsonb_array_elements(p_assignments) a
    where c.id = (a ->> 'club_id')::bigint
      and c.league_id = p_league_id;

    return jsonb_build_object('ok', true, 'clubs', v_expected, 'divisions', v_max);
end;
$$;

grant execute on function assign_divisions(bigint, jsonb) to authenticated;

-- =====================================================================================
-- PERCHE' LA CLASSIFICA NON SI CALCOLA QUI
--
-- Verrebbe naturale far decidere al database chi sale e chi scende: ha i risultati, e una
-- funzione SQL girerebbe senza che nessuno apra l'app.
--
-- Sarebbe pero' la **seconda** implementazione dello stesso regolamento. I criteri di
-- spareggio li sceglie l'admin e vivono in `core`, con i test; promozioni, playoff e
-- playout stanno in `SeasonEnd`, con altri ventuno test. Riscriverli in PL/pgSQL
-- significherebbe due regolamenti che si separano al primo ritocco, e la classifica
-- mostrata nell'app non corrisponderebbe piu' a chi retrocede davvero.
--
-- Quindi il conto lo fa `core` — lo stesso codice che disegna l'anteprima nella schermata
-- Divisioni, quindi cio' che si vede prima e' cio' che succede — e qui arriva gia' deciso.
-- Al database resta l'invariante che nessun calcolo puo' garantire da solo: che le
-- divisioni restino della dimensione giusta e che nessuna resti vuota.
-- =====================================================================================

-- =====================================================================================
-- FINE
-- =====================================================================================
