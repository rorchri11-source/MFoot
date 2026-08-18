-- =====================================================================================
-- MFoot - i colloqui con i giocatori
--
-- Da incollare nell'SQL Editor di Supabase dopo 0009_divisions.sql. Rieseguibile.
-- =====================================================================================

-- =====================================================================================
-- PERCHE' UNA FUNZIONE PER UN SOLO NUMERO
--
-- `players` non e' scrivibile da nessun client, e deve restare cosi': overall, potenziale
-- e attributi decidono chi vince le partite, e un pomeriggio con un proxy HTTP basterebbe
-- a costruirsi una rosa da novantacinque.
--
-- Il morale pero' cambia parlando, e parlare e' un'azione di gioco che parte dal telefono.
-- Serviva un permesso stretto quanto basta: **solo il morale, solo dei propri giocatori,
-- solo dentro l'intervallo consentito**. Una policy di update su `players` limitata a una
-- colonna non si esprime in Postgres; una funzione si'.
-- =====================================================================================

create or replace function set_player_morale(
    p_player_id bigint,
    p_morale    integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_club bigint;
begin
    -- Il giocatore deve stare in una squadra di chi chiama. Il contratto e' l'unica prova
    -- di proprieta' che esista: `players` non sa a chi appartiene nessuno.
    select c.club_id into v_club
    from contracts c
    join clubs cl on cl.id = c.club_id
    where c.player_id = p_player_id and cl.owner_user_id = auth.uid();

    if v_club is null then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    update players
    set morale = greatest(0, least(100, p_morale))
    where id = p_player_id;

    return jsonb_build_object('ok', true);
end;
$$;

grant execute on function set_player_morale(bigint, integer) to authenticated;

-- =====================================================================================
-- LE PROMESSE NON SONO ANCORA QUI
--
-- `ConversationEngine` sa gia' crearle e verificarle — promettere il posto da titolare per
-- N partite, il rinnovo entro la scadenza — e ha i suoi test. Manca la tabella e il pezzo
-- di tick che le controlla giornata per giornata.
--
-- Finche' non c'e', l'app fa la cosa onesta: l'opzione che crea una promessa funziona e
-- da' il suo effetto immediato, ma dice chiaramente che il debito non viene ancora
-- riscosso. Salvare una promessa che nessuno verifica sarebbe peggio che non poterla fare:
-- il gioco prometterebbe una conseguenza che non arriva mai.
-- =====================================================================================

-- =====================================================================================
-- FINE
-- =====================================================================================
