-- =====================================================================================
-- MFoot - il registro del tick si puo' leggere
--
-- Da incollare nell'SQL Editor di Supabase dopo 0006_config.sql. Rieseguibile.
-- =====================================================================================

-- =====================================================================================
-- IL DIFETTO CHE CHIUDE
--
-- `tick_state` aveva le Row Level Security attive e **nessuna regola**. Su Postgres questo
-- non produce un errore: produce zero righe. Il telefono chiedeva il registro, riceveva una
-- risposta vuota e valida, e concludeva l'unica cosa che poteva concludere — "il tick non ha
-- mai girato su questa lega" — mentre il server stava girando regolarmente ogni cinque
-- minuti e riempiendo le rose.
--
-- E' la forma peggiore di difetto: nessun errore da nessuna parte, e un messaggio
-- **sbagliato e allarmante** che manda a cercare un guasto dove non c'e'.
--
-- Delle diciassette tabelle con le RLS attive, questa e `ai_states` erano le uniche due
-- senza regole. Per `ai_states` e' voluto e deve restare cosi': contiene le personalita' e
-- gli obiettivi delle squadre gestite dal computer, e un giocatore che potesse leggerle
-- saprebbe in anticipo su chi stanno per puntare. Qui invece non c'e' niente da
-- nascondere: partite rinviate e formazioni corrette d'ufficio, cioe' esattamente le cose
-- che si vuole poter spiegare a chi chiede "perche' la mia squadra non ha giocato?".
-- =====================================================================================

drop policy if exists read_tick_state on tick_state;

create policy read_tick_state on tick_state for select
    using (is_member_of(league_id));

-- La scrittura resta di nessuno: il registro lo scrive il tick, che si collega come
-- servizio e non passa dalle Row Level Security. Un client che potesse riscriverlo
-- potrebbe raccontare una stagione che non e' avvenuta.

-- =====================================================================================
-- FINE
-- =====================================================================================
