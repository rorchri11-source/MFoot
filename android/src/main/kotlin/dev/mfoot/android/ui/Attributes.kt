package dev.mfoot.android.ui

import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Position

/**
 * Gli attributi da mostrare per un ruolo, quelli caratteristici prima.
 *
 * L'ordine non e' cosmetico: su uno schermo da telefono si leggono i primi tre o quattro
 * e si scorre via. Mettere il tiro in cima alla scheda di un difensore centrale
 * significa far perdere tempo a chi sta cercando di capire se sa marcare.
 */
fun Position.displayAttributes(): List<Attr> {
    val relevant = relevantAttributes
    val others = Attr.entries
        .filter { it.goalkeeperOnly == isGoalkeeper }
        .filterNot { it in relevant }
    return relevant + others
}
