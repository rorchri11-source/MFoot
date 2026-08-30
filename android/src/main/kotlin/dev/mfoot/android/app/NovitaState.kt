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

/**
 * Una voce del registro: una riga sola, oppure un mucchio di righe uguali.
 *
 * ## Perché il raggruppamento
 *
 * Perché una giornata di campionato scrive una riga per ogni partita: con dieci squadre
 * sono cinque righe che dicono la stessa cosa con nomi diversi, e la proposta di scambio
 * arrivata nel frattempo finisce sepolta in mezzo. Il registro serve a far emergere quello
 * che conta, e venti righe identiche fanno il contrario.
 */
sealed interface VoceRegistro {
    data class Singola(val riga: NotificationRow) : VoceRegistro
    data class Gruppo(
        val kind: String,
        val giorno: java.time.LocalDate,
        val righe: List<NotificationRow>,
    ) : VoceRegistro {
        val chiave: String get() = "$kind-$giorno"
    }
}

/**
 * Raggruppa le righe dello stesso tipo dello stesso giorno.
 *
 * ## Perché solo dalla terza in poi
 *
 * Perché due righe non sono un mucchio: nasconderle dietro un «2 partite» costringerebbe a
 * un tocco per leggere quello che si leggeva già. Da tre in su il guadagno c'è.
 *
 * ## Perché l'ordine non cambia
 *
 * Il gruppo prende il posto della **prima** delle sue righe, non va in fondo né in cima: un
 * registro è una cronologia, e spostare le cose per comodità di raggruppamento vorrebbe
 * dire che l'ordine non vuol più dire quando è successo.
 */
fun raggruppa(righe: List<NotificationRow>, minimo: Int = 3): List<VoceRegistro> {
    if (righe.size < minimo) return righe.map(VoceRegistro::Singola)

    val giornoDi = { r: NotificationRow ->
        r.createdAt?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
    }
    val conteggio = righe.groupingBy { it.kind to giornoDi(it) }.eachCount()

    val fuori = mutableListOf<VoceRegistro>()
    val gia = mutableSetOf<Pair<String, java.time.LocalDate?>>()

    for (riga in righe) {
        val chiave = riga.kind to giornoDi(riga)
        val giorno = chiave.second
        if (giorno == null || (conteggio[chiave] ?: 0) < minimo) {
            fuori += VoceRegistro.Singola(riga)
            continue
        }
        if (!gia.add(chiave)) continue

        fuori += VoceRegistro.Gruppo(
            kind = riga.kind,
            giorno = giorno,
            righe = righe.filter { it.kind == chiave.first && giornoDi(it) == giorno },
        )
    }
    return fuori
}
