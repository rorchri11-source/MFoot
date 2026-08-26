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
    val errore: String? = null,
) {

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
