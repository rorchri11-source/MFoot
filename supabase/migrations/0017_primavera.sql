-- =====================================================================================
-- MFoot - la Primavera, raggiungibile
--
-- Da incollare nell'SQL Editor di Supabase dopo 0016_scouting.sql. Rieseguibile.
--
-- Nessuna tabella e nessuna colonna nuova: `contracts.squad` esiste dal primo schema.
-- Si puo' applicare prima o dopo l'APK.
-- =====================================================================================

-- L'ultima giornata in cui questo giocatore si e' allenato.
--
-- ## Perche' serve una colonna e non basta un orologio
--
-- Il tick passa ogni cinque minuti. Senza una traccia di "l'ho gia' fatto oggi", un
-- ragazzo in Primavera si allenerebbe dodici volte all'ora — piu' in un pomeriggio che in
-- una stagione intera di partite.
--
-- E' esattamente il difetto per cui le promesse si mantenevano da sole in un quarto d'ora:
-- l'unita' di tempo del gioco e' la **giornata**, non il minuto, e tutto cio' che cresce
-- deve crescere con quella.
alter table contracts add column if not exists trained_on integer;

-- =====================================================================================
-- IL PEZZO CHE MANCAVA
--
-- La colonna `contracts.squad` c'e' dal primo giorno, con i suoi due valori. Ci sono i
-- parametri nel regolamento — eta' massima, quanto vale in crescita una partita di
-- Primavera — e c'e' `Club.youth` nel modello. Non c'era **nessun modo di metterci
-- qualcuno**: nessuna funzione, nessun pulsante.
--
-- E `StaminaEngine` porta scritto in testa che «serve la Primavera per turnare». Un pezzo
-- portante del progetto che non si poteva raggiungere.
-- =====================================================================================

create or replace function set_squad(
    p_player_id bigint,
    p_squad     text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_club      bigint;
    v_league    bigint;
    v_config    jsonb;
    v_max_age   integer;
    v_min_squad integer;
    v_max_squad integer;
    v_eta       integer;
    v_prima     integer;
    v_custom    boolean;
begin
    if p_squad not in ('prima', 'primavera') then
        return jsonb_build_object('ok', false, 'reason', 'Rosa sconosciuta.');
    end if;

    select c.club_id, cl.league_id into v_club, v_league
    from contracts c
    join clubs cl on cl.id = c.club_id
    where c.player_id = p_player_id and cl.owner_user_id = auth.uid();

    if v_club is null then
        return jsonb_build_object('ok', false, 'reason', 'Non e'' un tuo giocatore.');
    end if;

    select config into v_config from leagues where id = v_league;
    v_max_age   := coalesce((v_config -> 'rules' ->> 'youthMaxAge')::int, 21);
    v_min_squad := coalesce((v_config -> 'setup' ->> 'minSquadSize')::int, 0);
    v_max_squad := coalesce((v_config -> 'setup' ->> 'maxSquadSize')::int, 99);

    if coalesce((v_config -> 'rules' ->> 'youthTeamEnabled')::boolean, true) = false then
        return jsonb_build_object('ok', false, 'reason',
            'In questa lega la Primavera e'' disattivata.');
    end if;

    select age, is_custom into v_eta, v_custom from players where id = p_player_id;

    select count(*) into v_prima
    from contracts where club_id = v_club and squad = 'prima';

    if p_squad = 'primavera' then
        if v_eta > v_max_age then
            return jsonb_build_object('ok', false, 'reason',
                format('Ha %s anni: in Primavera si sta fino a %s.', v_eta, v_max_age));
        end if;

        -- Il giocatore creato dal proprietario resta in prima squadra.
        --
        -- Il regolamento gli impone di scendere in campo: e' il vincolo che rompe il
        -- circolo vizioso per cui, partendo da 65 in un mondo da 90, non giocherebbe mai
        -- e quindi non crescerebbe mai. Spostarlo in Primavera aggirerebbe l'obbligo dal
        -- lato sbagliato — non facendolo giocare affatto.
        if v_custom then
            return jsonb_build_object('ok', false, 'reason',
                'Il tuo giocatore resta in prima squadra.');
        end if;

        if v_prima - 1 < v_min_squad then
            return jsonb_build_object('ok', false, 'reason',
                format('La prima squadra resterebbe sotto il minimo (%s).', v_min_squad));
        end if;
    else
        if v_prima + 1 > v_max_squad then
            return jsonb_build_object('ok', false, 'reason',
                format('La prima squadra e'' gia'' al massimo (%s).', v_max_squad));
        end if;
    end if;

    update contracts set squad = p_squad where player_id = p_player_id;

    return jsonb_build_object('ok', true, 'squad', p_squad);
end;
$$;

grant execute on function set_squad(bigint, text) to authenticated;

-- =====================================================================================
-- PERCHE' NON C'E' UN LIMITE AL NUMERO DI GIOVANI
--
-- Verrebbe naturale metterne uno — "al massimo dieci in Primavera" — e sarebbe una regola
-- senza un problema da risolvere. Un giovane in Primavera costa comunque lo stipendio,
-- occupa un contratto e non gioca: chi ne accumula venti sta pagando venti stipendi per
-- non schierare nessuno, e la punizione arriva da sola dal bilancio.
--
-- Le regole che servono sono le due sopra: non si scende sotto il minimo di prima squadra,
-- e non si supera il massimo tornando su.
-- =====================================================================================

-- =====================================================================================
-- FINE
-- =====================================================================================
