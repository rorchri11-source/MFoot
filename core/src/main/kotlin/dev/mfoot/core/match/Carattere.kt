package dev.mfoot.core.match

import dev.mfoot.core.config.EngineConfig
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.formVolatility
import dev.mfoot.core.model.rimontaBonus
import dev.mfoot.core.rng.DeterministicRandom

/**
 * Il carattere dentro i novanta minuti.
 *
 * ## Perche' esiste
 *
 * Perche' i dodici tratti promettevano cose che il motore non manteneva. `INCOSTANTE`
 * diceva *«un giorno domina, quello dopo sparisce»* e dentro la partita non muoveva un solo
 * numero: quel giocatore faceva la stessa identica partita ogni volta. `LEADER` diceva
 * *«trascina la squadra»* e trascinava solo se portava la fascia, perche' l'unica spinta che
 * esisteva passava dal capitano. `TESTA_CALDA` diceva *«colleziona cartellini»* e ne
 * prendeva quanti chiunque altro.
 *
 * Un tratto che non muove nessun numero e' decorazione, e la decorazione non va nel motore:
 * lo dice [dev.mfoot.core.model.Trait] dalla prima riga. Queste sono le due funzioni che
 * mancavano — la terza, i falli della testa calda, vive dove i falli si assegnano.
 *
 * ## Perche' funzioni pure e non righe dentro la simulazione
 *
 * Perche' cosi' si possono provare senza far girare una partita, e perche' un effetto di
 * carattere che sta dentro `Simulation` e' un effetto che nessuno rilegge mai piu'.
 */
object Carattere {

    /**
     * Quanto e' in palla **oggi**, in punti di attributo.
     *
     * Un tiro di dado solo, valido per tutti i novanta minuti, di ampiezza proporzionale
     * alla volatilita' di forma di chi lo tira: un giocatore normale oscilla di due o tre
     * punti, un incostante — che ha volatilita' doppia — arriva a nove nelle giornate
     * estreme. Si vede, e non decide.
     *
     * ## Perche' il dado nasce dal seme e non dal flusso
     *
     * Perche' il secondo tempo ricostruisce il primo **risimulandolo**. Una giornata
     * estratta dal flusso principale cadrebbe in un punto diverso della sequenza fra i due
     * tempi, e un giocatore in palla al 44' si ritroverebbe spento al 46'.
     */
    fun giornata(player: Player, seed: Long, engine: EngineConfig): Double =
        DeterministicRandom(seed * SALE + player.id.value).nextGaussian(
            mean = 0.0,
            stdDev = engine.giornataStdDev * player.traits.formVolatility(),
            min = -LIMITE,
            max = LIMITE,
        )

    /**
     * Quanto spinge una squadra che sta perdendo nel finale.
     *
     * Diversa dalla resistenza del capitano, che esisteva gia' e che guarda **solo la
     * fascia**: qui contano tutti quelli che trascinano, fascia o non fascia.
     *
     * Zero se si e' in parita' o avanti — non e' un bonus di bravura, e' quello che succede
     * quando una squadra capisce che sta per perdere.
     */
    fun spintaDiRimonta(
        lineup: Lineup,
        scarto: Int,
        minute: Int,
        engine: EngineConfig,
    ): Double {
        if (minute < engine.minutoRimonta || scarto >= 0) return 0.0
        val trascinatori = lineup.slots.sumOf { it.player.traits.rimontaBonus() }
        return (trascinatori * engine.spintaLeader).coerceAtMost(engine.spintaLeaderMassima)
    }

    /**
     * Mescola il seme della partita con l'identificativo del giocatore. Primo e grande:
     * partite con seme vicino non devono dare a tutti le stesse giornate.
     */
    private const val SALE = 1_000_003L

    /** Nessuna giornata puo' valere piu' di questo, in nessuna direzione. */
    private const val LIMITE = 12.0
}
