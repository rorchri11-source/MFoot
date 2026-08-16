package dev.mfoot.android.world

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mfoot.android.data.ConnectionStatus
import dev.mfoot.android.data.Supabase
import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.Reparto
import dev.mfoot.core.world.GeneratedWorld
import dev.mfoot.core.world.PotentialEstimator
import dev.mfoot.core.world.WorldGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Un giocatore pronto da mostrare: il client non vede mai i valori nascosti. */
data class PlayerRow(
    val player: Player,
    /** La forbice **stimata**, non quella vera. */
    val estimate: IntRange,
    val hasUpside: Boolean,
    val value: Int,
) {
    /** L'etichetta compatta della lista: `+24`, `al max`, `in calo`. */
    val growthLabel: String
        get() = when {
            !hasUpside && player.age >= 30 -> "in calo"
            !hasUpside -> "al max"
            else -> "+${(estimate.last - player.overall).coerceAtLeast(0)}"
        }
}

enum class RoleFilter(val label: String) {
    TUTTI("Tutti"),
    POR("POR"),
    DIF("DIF"),
    CEN("CEN"),
    ATT("ATT"),
    GIOVANI("Under 21");

    fun matches(player: Player): Boolean = when (this) {
        TUTTI -> true
        POR -> player.primaryPosition.isGoalkeeper
        DIF -> player.primaryPosition.reparto == Reparto.DIFESA
        CEN -> player.primaryPosition.reparto == Reparto.CENTROCAMPO
        ATT -> player.primaryPosition.reparto == Reparto.ATTACCO
        GIOVANI -> player.age <= 21
    }
}

data class WorldUiState(
    val loading: Boolean = true,
    val rows: List<PlayerRow> = emptyList(),
    val query: String = "",
    val filter: RoleFilter = RoleFilter.TUTTI,
    val selected: PlayerRow? = null,
    val generationMillis: Long = 0,
    val connection: ConnectionStatus = ConnectionStatus.Checking,
) {
    val visible: List<PlayerRow>
        get() = rows
            .filter { filter.matches(it.player) }
            .filter {
                query.isBlank() ||
                    it.player.fullName.contains(query, ignoreCase = true) ||
                    it.player.primaryPosition.short.equals(query, ignoreCase = true)
            }
}

/**
 * Genera il mondo **sul telefono**.
 *
 * `core` e' la stessa identica libreria che gira sul tick, quindi il dispositivo puo'
 * produrre milletrecento giocatori in millisecondi. L'alternativa — farlo generare al
 * server — avrebbe significato restare a guardare "sto generando il mondo..." fino a
 * cinque minuti, che come prima impressione e' pessima.
 *
 * Il seed viene salvato con la lega, quindi il tick puo' sempre rigenerare lo stesso
 * mondo e verificare che nessuno l'abbia manomesso.
 */
class WorldViewModel : ViewModel() {

    private val _state = MutableStateFlow(WorldUiState())
    val state: StateFlow<WorldUiState> = _state

    /** L'osservatore: la stima di potenziale cambia da club a club. */
    private val observerId = 1L

    init {
        generate(ConfigPresets.sprint(20, 12, LocalDate.now()))
        checkConnection()
    }

    /**
     * Verifica il collegamento al database in parallelo alla generazione.
     *
     * Il mondo si genera comunque in locale, quindi l'app resta usabile anche senza
     * database: e' una scelta, non un ripiego, perche' permette di provare le schermate
     * e di giocare offline con un mondo di prova.
     */
    fun checkConnection() {
        viewModelScope.launch {
            _state.value = _state.value.copy(connection = ConnectionStatus.Checking)
            val status = Supabase.checkConnection()
            _state.value = _state.value.copy(connection = status)
        }
    }

    fun generate(config: LeagueConfig) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)

            val started = System.currentTimeMillis()
            val world: GeneratedWorld = withContext(Dispatchers.Default) {
                WorldGenerator.generate(config)
            }
            val rows = withContext(Dispatchers.Default) {
                world.players
                    .map { toRow(it, config) }
                    .sortedByDescending { it.player.overall }
            }

            _state.value = _state.value.copy(
                loading = false,
                rows = rows,
                generationMillis = System.currentTimeMillis() - started,
            )
        }
    }

    private fun toRow(player: Player, config: LeagueConfig): PlayerRow {
        val estimate = PotentialEstimator.estimate(
            player = player,
            observerId = observerId,
            minutesObserved = player.minutesObservedOrZero(),
        )
        return PlayerRow(
            player = player,
            estimate = estimate,
            hasUpside = PotentialEstimator.hasUpside(player),
            value = Valuation.estimatedValue(player, estimate, config),
        )
    }

    private fun Player.minutesObservedOrZero(): Int = 0

    fun onQuery(text: String) {
        _state.value = _state.value.copy(query = text)
    }

    fun onFilter(filter: RoleFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    fun select(row: PlayerRow?) {
        _state.value = _state.value.copy(selected = row)
    }
}

/** Gli attributi da mostrare per un ruolo, quelli caratteristici prima. */
fun Position.displayAttributes(): List<dev.mfoot.core.model.Attr> {
    val relevant = relevantAttributes
    val others = dev.mfoot.core.model.Attr.entries
        .filter { it.goalkeeperOnly == isGoalkeeper }
        .filterNot { it in relevant }
    return relevant + others
}
