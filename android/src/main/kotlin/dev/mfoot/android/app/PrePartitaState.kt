package dev.mfoot.android.app

import dev.mfoot.core.match.Pronostico
import dev.mfoot.core.match.Tactics
import dev.mfoot.core.match.Formation
import dev.mfoot.core.model.Player

/**
 * Come si presentano due squadre a una partita che deve ancora giocarsi.
 *
 * ## Perche' esiste
 *
 * Perche' preparare una partita era compilare un modulo e sperare. La formazione
 * dell'avversario si poteva gia' guardare — una squadra alla volta, da un'altra schermata —
 * ma non c'era nessun posto in cui **le due stessero insieme**, che e' l'unico modo in cui
 * un confronto si guarda.
 *
 * *Chiesto dal proprietario il 2026-08-30: «voglio solo la visuale del campo mio e suo, due
 * campi separati, come schiera visivamente e la panchina; probabilita' vittoria sua/mia o
 * pareggio graficamente, come nelle partite vere».*
 */
data class SchieramentoDiUnClub(
    val clubId: Long = 0,
    val formation: Formation = Formation.F_4_3_3,
    /** Un elemento per casella, nell'ordine del modulo. */
    val eleven: List<Player?> = emptyList(),
    val bench: List<Player> = emptyList(),
    val tactics: Tactics? = null,
    /**
     * Nessuno ha schierato: quello che si vede e' cio' che scenderebbe in campo da solo.
     *
     * Va detto, sempre. Una previsione e una scelta si leggono identiche, e preparare la
     * partita contro un modulo che l'avversario non ha mai scelto e' peggio che non
     * guardarlo affatto.
     */
    val suPrevisione: Boolean = false,
)

data class PrePartitaState(
    val fixtureId: Long = 0,
    val casa: SchieramentoDiUnClub? = null,
    val ospite: SchieramentoDiUnClub? = null,
    val nomeCasa: String = "",
    val nomeOspite: String = "",
    /**
     * Le tre probabilita'.
     *
     * Nullo mentre si calcola: il conto e' trecento partite simulate, e su un telefono
     * costa qualche secondo. Meglio una schermata che si riempie a pezzi che una schermata
     * che non compare finche' non e' pronta tutta.
     */
    val pronostico: Pronostico.Esito? = null,
    val quando: String = "",
    val problema: String? = null,
    val caricamento: Boolean = true,
    val errore: String? = null,
)
