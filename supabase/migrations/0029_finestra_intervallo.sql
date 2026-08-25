-- =====================================================================================
-- LA FINESTRA DELL'INTERVALLO
--
-- `MatchEngine` sa simulare primo e secondo tempo separatamente dal primo giorno —
-- `simulateFirstHalf` restituisce un `HalfTimeState`, `simulateSecondHalf` lo riprende — e
-- la configurazione ha `halfTimeWindowMinutes` da sempre. Quello che mancava era il posto
-- dove **mettere in pausa una partita**: senza, il tick simulava i novanta minuti in un
-- colpo solo e la finestra non si apriva mai.
--
-- PERCHE' NON SI SALVA LO STATO DELL'INTERVALLO
--
-- `HalfTimeState` contiene la timeline completa, le statistiche di ogni giocatore e i due
-- schieramenti: serializzarlo vorrebbe dire un secondo formato da tenere allineato al
-- motore per sempre.
--
-- Non serve. Il motore e' **deterministico**: stesso seed e stessi ingressi danno lo stesso
-- identico primo tempo. Alla ripresa si ri-simulano i quarantacinque minuti — costa
-- microsecondi — e si riparte da li'. L'unica cosa che va conservata e' **com'erano
-- schierate le squadre al fischio d'inizio**, perche' nel frattempo il manager le ha
-- cambiate: e' esattamente il punto della finestra.
--
-- Per lo stesso motivo `first_half` porta anche stamina, morale e forma di chi era in
-- campo: sono gli unici valori dei giocatori che cambiano da soli fra un giro e l'altro, e
-- senza di loro il primo tempo ri-simulato divergerebbe da quello che gli spettatori hanno
-- gia' visto.
-- =====================================================================================

alter table fixtures add column if not exists resume_at  timestamptz;
alter table fixtures add column if not exists first_half jsonb;

comment on column fixtures.resume_at is
    'Quando riprende il secondo tempo. Null se la partita non e'' ferma all''intervallo.';
comment on column fixtures.first_half is
    'Gli schieramenti al fischio d''inizio, per ri-simulare il primo tempo identico.';

-- L'indice che il tick interroga a ogni giro: le partite ferme all'intervallo.
create index if not exists idx_fixtures_paused on fixtures(league_id, resume_at)
    where resume_at is not null and not played;
