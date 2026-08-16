package dev.mfoot.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mfoot.android.data.ApiResult
import dev.mfoot.android.data.LeagueRepository
import dev.mfoot.android.data.LeagueSnapshot
import dev.mfoot.android.data.Session
import dev.mfoot.android.data.Supabase
import dev.mfoot.android.data.SupabaseApi
import dev.mfoot.android.data.WorldUpload
import dev.mfoot.core.config.ConfigPresets
import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.market.Valuation
import dev.mfoot.core.world.PotentialEstimator
import dev.mfoot.core.world.WorldGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.random.Random

/**
 * Il cervello dell'app.
 *
 * ## Il mondo si genera sul telefono, ma la verita' sta sul database
 *
 * `core` e' la stessa identica libreria che gira sul server, quindi il dispositivo
 * produce milletrecento giocatori in millisecondi. Ma li genera **una volta sola**, al
 * momento di creare la lega: subito dopo li carica, e da li' in avanti legge dal database
 * come tutti gli altri.
 *
 * E' la differenza fra un'app che mostra un mondo e un'app che partecipa a un mondo. Se
 * ogni avvio rigenerasse, due telefoni vedrebbero due leghe diverse con lo stesso nome.
 */
class AppViewModel : ViewModel() {

    private val _state = MutableStateFlow<AppState>(AppState.Avvio)
    val state: StateFlow<AppState> = _state

    init {
        avvia()
    }

    // ------------------------------------------------------------------------- ingresso

    /**
     * Recupera l'identita' salvata e, se c'e' gia' una lega, la apre.
     *
     * Nessuna schermata di accesso: chi ha gia' giocato riapre l'app e si ritrova dentro.
     */
    fun avvia() {
        viewModelScope.launch {
            if (!Supabase.isConfigured) {
                _state.value = AppState.Guasto(
                    "Credenziali Supabase assenti. Vanno messe in local.properties: " +
                        "vedi docs/SETUP.md.",
                )
                return@launch
            }

            _state.value = AppState.Avvio

            when (val session = SupabaseApi.ensureSession()) {
                is ApiResult.Error -> {
                    _state.value = AppState.Porta(errore = session.message)
                    return@launch
                }
                is ApiResult.Ok -> Unit
            }

            val leagueId = Session.leagueId
            if (leagueId == null) {
                _state.value = AppState.Porta()
            } else {
                carica(leagueId)
            }
        }
    }

    fun apriPorta(mode: DoorMode) {
        val corrente = _state.value as? AppState.Porta ?: AppState.Porta()
        _state.value = corrente.copy(mode = mode, errore = null)
    }

    /**
     * Crea la lega: genera il mondo, lo carica, poi lo rilegge da dove l'ha messo.
     *
     * La rilettura non e' pignoleria. Un 200 dice che la chiamata e' andata a buon fine,
     * non che il mondo sia arrivato tutto: leggere indietro quello che si e' scritto e'
     * l'unica conferma che valga qualcosa, e costa una richiesta.
     */
    fun creaLega(nome: String, codice: String, nickname: String, presetId: String = "sprint") {
        if (nome.isBlank() || codice.isBlank() || nickname.isBlank()) {
            aggiornaPorta(errore = "Servono nome della lega, codice e il tuo nickname.")
            return
        }

        viewModelScope.launch {
            aggiornaPorta(busy = "Genero il mondo…")

            val preset = ConfigPresets.byId(presetId) ?: ConfigPresets.all.first()
            val config = preset.build(16, 8, LocalDate.now()).let {
                it.copy(
                    setup = it.setup.copy(
                        leagueName = nome.trim(),
                        // Ogni lega ha il suo mondo. Con il seed predefinito, due gruppi
                        // di amici si ritroverebbero gli stessi identici giocatori con gli
                        // stessi identici nomi, e la sensazione di scoprire qualcosa
                        // sparirebbe alla seconda lega.
                        worldSeed = nuovoSeed(),
                    ),
                )
            }

            val world = withContext(Dispatchers.Default) { WorldGenerator.generate(config) }

            aggiornaPorta(busy = "Carico ${world.players.size} giocatori…")
            val payload = withContext(Dispatchers.Default) {
                WorldUpload.buildPayload(world, config, nome.trim(), codice.trim(), nickname.trim())
            }

            when (val creata = SupabaseApi.createLeague(payload)) {
                is ApiResult.Error -> aggiornaPorta(errore = creata.message)
                is ApiResult.Ok -> {
                    Session.leagueId = creata.value
                    Session.nickname = nickname.trim()
                    carica(creata.value, avviso = "Lega creata. Il codice per gli altri e' ${codice.trim()}.")
                }
            }
        }
    }

    fun entraInLega(codice: String, nickname: String) {
        if (codice.isBlank() || nickname.isBlank()) {
            aggiornaPorta(errore = "Servono il codice della lega e il tuo nickname.")
            return
        }

        viewModelScope.launch {
            aggiornaPorta(busy = "Cerco la lega…")

            when (val entrata = SupabaseApi.joinLeague(codice.trim(), nickname.trim())) {
                is ApiResult.Error -> aggiornaPorta(errore = entrata.message)
                is ApiResult.Ok -> {
                    Session.leagueId = entrata.value
                    Session.nickname = nickname.trim()
                    carica(entrata.value, avviso = "Sei dentro.")
                }
            }
        }
    }

    /** Torna alla porta senza perdere l'identita': la lega resta, ci si puo' rientrare. */
    fun lasciaLega() {
        Session.leagueId = null
        _state.value = AppState.Porta()
    }

    // -------------------------------------------------------------------------- lettura

    fun ricarica() {
        Session.leagueId?.let { carica(it) } ?: run { _state.value = AppState.Porta() }
    }

    private fun carica(leagueId: Long, avviso: String? = null) {
        viewModelScope.launch {
            _state.value = AppState.Caricamento("Leggo la lega…")

            when (val snapshot = LeagueRepository.snapshot(leagueId)) {
                is ApiResult.Error -> {
                    // Non si cancella la lega salvata: quasi sempre e' un problema di rete
                    // e al prossimo tentativo funziona. Buttarla via costringerebbe a
                    // reinserire il codice ogni volta che il treno entra in galleria.
                    _state.value = AppState.Porta(
                        errore = "Non riesco a leggere la lega: ${snapshot.message}",
                    )
                }

                is ApiResult.Ok -> {
                    val rows = withContext(Dispatchers.Default) { righe(snapshot.value) }
                    _state.value = AppState.Dentro(
                        lega = snapshot.value,
                        rows = rows,
                        browse = BrowseState(
                            // Chi ha gia' una rosa vuole vedere la sua rosa; chi non ce
                            // l'ha ancora vuole vedere cosa c'e' da prendere.
                            scope = if (snapshot.value.myClub != null) ListScope.MIA_ROSA
                            else ListScope.SVINCOLATI,
                        ),
                        avviso = avviso,
                    )
                }
            }
        }
    }

    /**
     * Da giocatori del database a righe da mostrare.
     *
     * La stima di potenziale usa l'id del **proprio club** come osservatore: due club
     * vedono forbici leggermente diverse dello stesso giocatore, ed e' quello che rende
     * possibile l'affare. Chi non ha ancora un club guarda con l'occhio della lega.
     */
    private fun righe(snapshot: LeagueSnapshot): List<PlayerRow> {
        val observerId = snapshot.myClub?.id ?: snapshot.league.id
        val clubById = snapshot.clubs.associateBy { it.id }
        val config: LeagueConfig = snapshot.league.config

        return snapshot.players.map { player ->
            val estimate = PotentialEstimator.publicEstimate(player, observerId)
            PlayerRow(
                player = player,
                estimate = estimate,
                hasUpside = PotentialEstimator.hasUpside(player),
                value = Valuation.estimatedValue(player, estimate, config),
                club = snapshot.clubOfPlayer[player.id.value]?.let(clubById::get),
            )
        }
    }

    // ---------------------------------------------------------------------- navigazione

    fun onQuery(text: String) = aggiornaBrowse { it.copy(query = text) }

    fun onFilter(filter: RoleFilter) = aggiornaBrowse { it.copy(filter = filter) }

    fun onScope(scope: ListScope) = aggiornaBrowse { it.copy(scope = scope) }

    fun select(row: PlayerRow?) = aggiornaBrowse { it.copy(selected = row) }

    fun chiudiAvviso() {
        val dentro = _state.value as? AppState.Dentro ?: return
        _state.value = dentro.copy(avviso = null)
    }

    // ------------------------------------------------------------------------- aiutanti

    private fun aggiornaBrowse(block: (BrowseState) -> BrowseState) {
        val dentro = _state.value as? AppState.Dentro ?: return
        _state.value = dentro.copy(browse = block(dentro.browse))
    }

    private fun aggiornaPorta(busy: String? = null, errore: String? = null) {
        val corrente = _state.value as? AppState.Porta ?: AppState.Porta()
        _state.value = corrente.copy(busy = busy, errore = errore)
    }

    /**
     * Un seed che non si ripete.
     *
     * L'orologio da solo non basta: due leghe create nello stesso millisecondo da due
     * telefoni diversi avrebbero lo stesso mondo. Il seed vero e' quello salvato con la
     * lega, e da li' in poi tutto e' riproducibile.
     */
    private fun nuovoSeed(): Long =
        System.currentTimeMillis() * 1_000_003L + Random.nextLong(1_000_003L)
}
