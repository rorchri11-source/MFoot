package dev.mfoot.android.app

import dev.mfoot.android.data.NotificationRow
import java.time.Instant

/**
 * Cosa è successo mentre non guardavi.
 *
 * ## Perché «nuovaDopo» e non un elenco di righe lette
 *
 * Perché segnare ogni riga come letta vorrebbe dire tenerne conto una per una — nel
 * database, con una tabella in più e una scrittura a ogni apertura di schermata — per
 * rispondere a una domanda che una data risponde da sola: **cosa è arrivato dopo l'ultima
 * volta che ho guardato**.
 *
 * Il costo di questa scelta è che aprire il registro azzera il pallino su tutto, anche su
 * quello che si è scorso senza leggere. È il comportamento di qualunque registro, ed è
 * quello che ci si aspetta.
 */
data class NovitaState(
    val righe: List<NotificationRow> = emptyList(),
    /** La lettura dal server è arrivata: distingue «vuoto» da «non ancora letto». */
    val letto: Boolean = false,
    /**
     * Tutto quello che è arrivato dopo questo istante è **nuovo**.
     *
     * Null la primissima volta: allora è nuovo tutto, ed è giusto — chi apre il registro
     * per la prima volta non ha mai visto niente.
     */
    val nuovaDopo: Instant? = null,
    /**
     * Che tipo si sta guardando, o null per tutti.
     *
     * ## Perché un filtro e non un ordinamento
     *
     * Perché il registro tiene duecento righe e la domanda che ci si fa non è «cosa è
     * successo» ma **«cosa è successo di quella cosa lì»**: com'è finita l'asta, chi mi ha
     * proposto uno scambio, quante partite ho giocato. Ordinare non toglie di mezzo le
     * centonovanta righe che non c'entrano; filtrare sì.
     */
    val filtro: String? = null,
    val errore: String? = null,
) {

    /** I tipi presenti davvero, in ordine di quanti ce ne sono. */
    val tipiPresenti: List<Pair<String, Int>>
        get() = righe.groupingBy { it.kind }.eachCount()
            .toList()
            .sortedByDescending { it.second }

    /** Le righe da mostrare, applicato il filtro. */
    val visibili: List<NotificationRow>
        get() = if (filtro == null) righe else righe.filter { it.kind == filtro }


    /**
     * Quante non se ne sono ancora viste. È il numero sul pallino del menu.
     *
     * Si conta su **tutte** le righe e non solo sulle proprie: una giornata giocata
     * riguarda anche chi non ci ha giocato, perché cambia la classifica.
     */
    val nonLette: Int
        get() {
            val soglia = nuovaDopo ?: return righe.size
            return righe.count { it.createdAt?.isAfter(soglia) == true }
        }
}
