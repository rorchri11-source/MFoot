package dev.mfoot.core.staff

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.model.StaffRole

/** In quale delle due squadre lavora. */
enum class Posto(val etichetta: String) {
    PRIMA_SQUADRA("Prima squadra"),
    PRIMAVERA("Primavera"),
}

/**
 * Un posto di lavoro nello staff.
 *
 * L'indice serve solo agli osservatori, che ne hanno cinque uguali: per gli altri ruoli
 * la coppia ruolo-posto e' gia' unica.
 */
data class Cella(val role: StaffRole, val posto: Posto, val indice: Int = 0)

/**
 * Chi lavora dove, e quanti se ne possono tenere.
 *
 * ## Perche' e' una regola di `core` e non una schermata
 *
 * Perche' la stessa domanda la fanno in tre: l'app per disegnare le celle e spegnere il
 * pulsante giusto, il server per rifiutare un'assegnazione che non sta in piedi, e il
 * negozio per dire «hai gia' raggiunto il tetto» **prima** dell'acquisto. Scritta tre
 * volte sarebbero tre regolamenti che si separano al primo ritocco — che e' il principio
 * di struttura del progetto.
 *
 * ## Il difetto che queste celle chiudono
 *
 * Oggi `assign_staff` fa questo, in SQL:
 *
 * ```sql
 * update staff set club_id = null
 * where club_id = p_club_id and role = v_staff.role and id <> p_staff_id;
 * ```
 *
 * Assegnarne uno nuovo **libera il vecchio**, che torna sul mercato per chiunque. Quello
 * che il proprietario ha chiesto — *«cella uno hai tre preparatori che hai comprato,
 * inserisci il terzo, seconda cella selezioni il primo»* — non e' impossibile per una
 * svista di interfaccia: e' impossibile per costruzione.
 */
object Celle {

    /**
     * Tutte le celle che esistono, nell'ordine in cui si disegnano.
     *
     * Nove: due allenatori, due preparatori, cinque osservatori.
     */
    fun tutte(config: LeagueConfig): List<Cella> = buildList {
        add(Cella(StaffRole.ALLENATORE, Posto.PRIMA_SQUADRA))
        add(Cella(StaffRole.ALLENATORE, Posto.PRIMAVERA))
        add(Cella(StaffRole.PREPARATORE, Posto.PRIMA_SQUADRA))
        add(Cella(StaffRole.PREPARATORE, Posto.PRIMAVERA))
        repeat(tetto(StaffRole.OSSERVATORE, config)) {
            add(Cella(StaffRole.OSSERVATORE, Posto.PRIMAVERA, it))
        }
    }

    /**
     * Quanti se ne possono **possedere** di questo ruolo.
     *
     * Piu' delle celle per allenatori e preparatori — due in campo, due di scorta fra cui
     * scegliere — e uguale alle celle per gli osservatori, che si schierano tutti.
     */
    fun tetto(role: StaffRole, config: LeagueConfig): Int = when (role) {
        StaffRole.ALLENATORE -> config.staff.maxAllenatori
        StaffRole.PREPARATORE -> config.staff.maxPreparatori
        StaffRole.OSSERVATORE -> config.staff.maxOsservatori
    }.coerceAtLeast(1)

    /**
     * Perche' questa cella non si puo' riempire, o `null` se si puo'.
     *
     * E' il testo che va **scritto sulla tessera**, non mostrato dopo il tocco: un pulsante
     * che si puo' premere e che da' sempre errore insegna a non fidarsi di nessun pulsante.
     */
    fun impedimento(cella: Cella, haPrimavera: Boolean): String? = when {
        cella.posto == Posto.PRIMAVERA && !haPrimavera -> "Serve la Primavera"
        else -> null
    }

    /**
     * Perche' non se ne puo' comprare un altro, o `null` se si puo'.
     *
     * Due divieti diversi, e vanno detti diversi: uno si risolve vendendo, l'altro
     * fondando una squadra.
     */
    fun impedimentoAcquisto(
        role: StaffRole,
        posseduti: Int,
        haPrimavera: Boolean,
        config: LeagueConfig,
    ): String? {
        // Gli osservatori lavorano in Primavera, quindi senza Primavera non hanno dove
        // stare. Deciso dal proprietario il 2026-08-30.
        if (role == StaffRole.OSSERVATORE && !haPrimavera) {
            return "Gli osservatori lavorano nella Primavera: prima va fondata."
        }
        val massimo = tetto(role, config)
        if (posseduti >= massimo) {
            return "Ne hai gia' $massimo, il massimo per questo ruolo."
        }
        return null
    }

    /**
     * Le celle di un ruolo, in ordine.
     *
     * La prima e' sempre la prima squadra — tranne per gli osservatori, che stanno tutti
     * in Primavera e non scelgono niente.
     */
    fun di(role: StaffRole, config: LeagueConfig): List<Cella> =
        tutte(config).filter { it.role == role }

    /**
     * Le celle sono un posto di lavoro, non una classifica.
     *
     * Vale la pena dirlo esplicito perche' e' la differenza fra questo modello e quello di
     * prima: nessuna cella e' «migliore», e mettere il cinque stelle in Primavera invece
     * che in prima squadra e' una scelta legittima — chi punta sui giovani lo fa.
     */
    fun quanteCelle(role: StaffRole, config: LeagueConfig): Int = di(role, config).size
}
