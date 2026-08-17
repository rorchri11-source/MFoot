-- =====================================================================================
-- MFoot - l'admin cambia le regole della lega
--
-- Da incollare nell'SQL Editor di Supabase dopo 0005_competitions.sql. Rieseguibile.
-- =====================================================================================

-- =====================================================================================
-- PERCHE' UNA FUNZIONE E NON UN UPDATE
--
-- Le Row Level Security su `leagues` permettono la lettura ai membri e la scrittura a
-- nessuno. E' voluto: la configurazione decide budget, premi, durata dei contratti e
-- tasso di infortunio, cioe' l'equilibrio dell'intera stagione. Se il client potesse
-- scriverla, chiunque sappia comporre una richiesta HTTP si darebbe un budget da un
-- miliardo, e nessuno degli altri lo saprebbe mai.
--
-- Cosi' invece si passa da qui, e qui si controlla che chi scrive sia l'amministratore.
-- =====================================================================================

create or replace function update_league_config(
    p_league_id bigint,
    p_config    jsonb
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception 'Serve un accesso valido.' using errcode = '28000';
    end if;

    if not exists (
        select 1 from league_members
        where league_id = p_league_id and user_id = v_user and is_admin
    ) then
        raise exception 'Solo l''amministratore della lega puo'' cambiare le regole.'
            using errcode = '42501';
    end if;

    if p_config is null or jsonb_typeof(p_config) <> 'object' then
        raise exception 'Configurazione non valida.' using errcode = '22023';
    end if;

    -- I pesi dei ruoli non si sovrascrivono da qui.
    --
    -- Non sono un'impostazione: vengono da Position.kt e servono al database per
    -- ricalcolare l'overall del giocatore custom. Se una versione piu' vecchia dell'app
    -- salvasse una configurazione senza quel blocco, la creazione dei club smetterebbe di
    -- funzionare per tutta la lega, con un messaggio che non spiega niente. Si tiene
    -- quello che c'e' e si accetta quello che arriva solo se e' presente.
    update leagues
    set config = case
        when p_config ? 'roleWeights' then p_config
        else p_config || jsonb_build_object('roleWeights', config -> 'roleWeights')
    end
    where id = p_league_id;
end;
$$;

grant execute on function update_league_config(bigint, jsonb) to authenticated;

-- =====================================================================================
-- FINE
-- =====================================================================================
