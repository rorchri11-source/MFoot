package dev.mfoot.android.ui.pitch

import dev.mfoot.core.match.Formation
import dev.mfoot.core.match.PitchLayout
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position

/**
 * Costruttori di caselle.
 *
 * Stanno in un file a parte, e non dentro `Pitch.kt`, perche' sono l'unico punto in cui
 * questi tre componenti toccano `dev.mfoot.core.match.PitchLayout`. Il campo in se' non
 * sa niente di moduli: prende delle coordinate e le disegna. Tenere la dipendenza isolata
 * significa che se `PitchLayout` cambia forma si corregge qui, e il campo, l'editor e le
 * anteprime continuano a compilare.
 */

/**
 * Le undici caselle di un modulo.
 *
 * [players] va nello stesso ordine di `formation.positions`: il portiere primo, poi la
 * difesa, e cosi' via. Un `null` e' una casella da riempire, non un errore.
 */
fun pitchSlots(formation: Formation, players: List<Player?>): List<PitchSlot> =
    pitchSlots(
        positions = formation.positions,
        coordinates = PitchLayout.of(formation),
        players = players,
    )

/**
 * Le caselle da ruoli e coordinate date a mano.
 *
 * Serve a chi ha coordinate che non vengono da un modulo — per esempio la fondazione, che
 * mostra gli undici ruoli come bersagli su cui scegliere dove giocare — e a chiunque
 * debba costruire un campo senza passare da [Formation].
 */
fun pitchSlots(
    positions: List<Position>,
    coordinates: List<Pair<Float, Float>>,
    players: List<Player?> = emptyList(),
): List<PitchSlot> {
    require(coordinates.size >= positions.size) {
        "servono almeno ${positions.size} coordinate, ne sono arrivate ${coordinates.size}"
    }
    return positions.mapIndexed { index, position ->
        val (x, y) = coordinates[index]
        PitchSlot(
            index = index,
            position = position,
            player = players.getOrNull(index),
            x = x,
            y = y,
        )
    }
}
