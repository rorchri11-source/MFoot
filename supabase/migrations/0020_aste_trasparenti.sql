-- =====================================================================================
-- MFoot - a fine asta si vede chi ha offerto quanto
--
-- Da incollare nell'SQL Editor di Supabase dopo 0019_staff_e_scouting.sql. Rieseguibile.
--
-- Nessuna colonna nuova: si puo' applicare prima o dopo l'APK.
-- =====================================================================================

-- =====================================================================================
-- PERCHE' DOPO E NON DURANTE
--
-- Le offerte sono **massimi segreti**: si dichiara fin dove si e' disposti a spingersi e il
-- sistema difende la posizione da solo, alzando il prezzo quanto basta. E' quello che
-- permette di andare a dormire durante un'asta invece di controllare il telefono ogni ora.
--
-- Vederli mentre l'asta e' aperta cancellerebbe la meccanica: sapendo che il capofila si
-- ferma a diciotto, si offre diciotto e cento e si vince sempre. Non e' un'asta, e' una
-- coda.
--
-- A **fine** asta invece non c'e' piu' niente da proteggere, e mostrare chi si e' spinto
-- fino a dove e' esattamente quello che serve: si scopre chi voleva davvero quel giocatore
-- e per quanto ci si e' andati vicini. E' la parte piu' divertente di un'asta, e finora
-- spariva.
-- =====================================================================================

drop policy if exists read_own_bids on bids;

-- Le proprie, sempre. Quelle degli altri, solo su un'asta che non e' piu' aperta.
create policy read_own_bids on bids for select
    using (
        owns_club(club_id)
        or exists (
            select 1 from auctions a
            where a.id = bids.auction_id
              and a.status <> 'APERTA'
              and is_member_of(a.league_id)
        )
    );

-- =====================================================================================
-- FINE
-- =====================================================================================
