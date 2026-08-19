-- =====================================================================================
-- MFoot - si entra sapendo dove
--
-- Da incollare nell'SQL Editor di Supabase dopo 0024_obiettivi.sql. Rieseguibile.
--
-- =====================================================================================
-- IL DIFETTO CHE CHIUDE
--
-- Due amici hanno giocato in due leghe diverse convinti di essere nella stessa. Non per
-- un codice sbagliato del programma: i codici erano diversi e `join_league` ha fatto
-- esattamente il suo mestiere, mettendo ognuno nella lega del codice che aveva digitato.
--
-- Il difetto e' che **nessuno dei due poteva accorgersene**. Digiti un codice e sei
-- dentro: l'app non dice mai in quale lega ti ha portato, ne' quante persone ci sono,
-- ne' se e' quella di cui ti hanno parlato. E riaprendo l'app ci si rientra dritto, senza
-- una parola.
--
-- Questa funzione serve a far leggere il nome **prima** di entrare. Il pulsante smette di
-- dire «Entra» e comincia a dire «Entra in Lega dei Bar», e un codice sbagliato si vede
-- nel momento in cui lo si digita invece che tre giorni dopo.
-- =====================================================================================

-- =====================================================================================
-- PERCHE' RISPONDE A CHI NON E' ANCORA MEMBRO
--
-- Perche' deve: chi sta per entrare non e' membro per definizione, e le Row Level
-- Security su `leagues` gli nascondono tutto — giustamente, perche' senza codice non deve
-- vedere niente.
--
-- Il codice **e'** la chiave. Chi ce l'ha ha gia' il diritto di entrare, quindi mostrargli
-- il nome della lega in cui sta per entrare non gli concede niente che non avesse gia'.
-- Chi non ce l'ha riceve `found: false` e nient'altro: la funzione non elenca le leghe,
-- non ne conta, non dice se il codice era vicino. Si puo' solo chiedere «esiste questa
-- lega?» a una domanda per volta, che e' esattamente quanto serve e nulla di piu'.
--
-- Restano fuori di proposito: l'id della lega, i nomi dei membri, la configurazione.
-- Il nome, quante persone ci sono e a che punto e' la stagione bastano a riconoscere la
-- lega dell'amico da quella sbagliata, ed e' tutto quello per cui questa funzione esiste.
-- =====================================================================================

create or replace function peek_league(p_access_code text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_codice text := trim(p_access_code);
    v_lega   leagues%rowtype;
    v_membri integer;
    v_club   integer;
begin
    if coalesce(v_codice, '') = '' then
        return jsonb_build_object('found', false);
    end if;

    select * into v_lega
    from leagues
    where access_code_hash = crypt(v_codice, access_code_hash)
    limit 1;

    if not found then
        return jsonb_build_object('found', false);
    end if;

    select count(*) into v_membri from league_members where league_id = v_lega.id;
    select count(*) into v_club
    from clubs
    where league_id = v_lega.id and parent_club_id is null;

    return jsonb_build_object(
        'found', true,
        'name', v_lega.name,
        'members', v_membri,
        'clubs', v_club,
        'status', v_lega.status,
        'match_day', v_lega.current_match_day,
        -- Serve a distinguere due leghe che si chiamano uguale, ed e' il caso normale per
        -- chi ne ha create tre di prova chiamandole tutte «Lega».
        'created_at', v_lega.created_at
    );
end;
$$;

-- Anche a `anon`: la porta d'ingresso si apre prima che l'accesso anonimo sia stato
-- stabilito, e un'anteprima che fallisce li' rimanderebbe al pulsante «Entra» cieco che
-- questa funzione esiste per togliere.
grant execute on function peek_league(text) to authenticated, anon;

-- =====================================================================================
-- FINE
-- =====================================================================================
