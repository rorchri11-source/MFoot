package dev.mfoot.core.match

import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Attributes
import dev.mfoot.core.model.Band
import dev.mfoot.core.model.BandWeights
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.rng.DeterministicRandom
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cosa prova a fare chi ha la palla.
 *
 * Il test che porta il peso e' `due giocatori con lo stesso overall giocano partite
 * diverse`: e' la frase del proprietario — *«i giocatori sono numeri, non persone»* —
 * scritta come misura.
 */
class IntenzioniTest {

    private fun giocatore(
        position: Position = Position.AD,
        vararg attributi: Pair<Attr, Int>,
    ): Player = Player(
        id = PlayerId(1),
        firstName = "Prova",
        lastName = "Prova",
        nationality = "IT",
        age = 25,
        primaryPosition = position,
        attributes = Attributes.of(60, *attributi),
        potentialMin = 70,
        potentialMax = 80,
    )

    /** Quante volte su mille prova quella cosa, li'. */
    private fun distribuzione(
        player: Player,
        band: Band,
        tactics: Tactics = Tactics.DEFAULT,
        tiri: Int = 4000,
    ): Map<Duello, Int> {
        val rng = DeterministicRandom(4242)
        val conteggio = Duello.entries.associateWith { 0 }.toMutableMap()
        repeat(tiri) {
            val scelta = Intenzioni.scegli(player, band, tactics, rng)
            conteggio[scelta] = conteggio.getValue(scelta) + 1
        }
        return conteggio
    }

    /**
     * **Il test per cui questo file esiste.**
     *
     * Due ali con lo stesso overall — una che va in profondita', una che punta l'uomo —
     * devono giocare due partite diverse. Prima finivano tutte e due nella stessa media di
     * zona e producevano lo stesso identico esito atteso in ogni azione.
     */
    @Test
    fun `due giocatori con lo stesso overall giocano partite diverse`() {
        val scattista = giocatore(Position.AD, Attr.VELOCITA to 92, Attr.DRIBBLING to 58)
        val dribblatore = giocatore(Position.AD, Attr.VELOCITA to 58, Attr.DRIBBLING to 92)

        // Il campione e' valido solo se il vecchio motore non li distingueva. Quello che il
        // vecchio motore leggeva era esattamente questo numero: BandWeights sulla fascia
        // d'attacco, poi mediato con gli altri dieci dentro il rating di zona.
        val vecchioMotore = StrictMath.abs(
            BandWeights.rate(scattista.attributes, Band.ATT) -
                BandWeights.rate(dribblatore.attributes, Band.ATT),
        )
        assertTrue(
            vecchioMotore < 2.0,
            "il campione non e' valido: gia' il vecchio motore li distingueva di $vecchioMotore",
        )

        val a = distribuzione(scattista, Band.ATT)
        val b = distribuzione(dribblatore, Band.ATT)

        assertTrue(
            a.getValue(Duello.CORSA) > b.getValue(Duello.CORSA) * 1.5,
            "lo scattista non cerca la profondita' piu' dell'altro: " +
                "${a[Duello.CORSA]} contro ${b[Duello.CORSA]}",
        )
        assertTrue(
            b.getValue(Duello.DRIBBLING) > a.getValue(Duello.DRIBBLING) * 1.5,
            "il dribblatore non punta l'uomo piu' dell'altro: " +
                "${b[Duello.DRIBBLING]} contro ${a[Duello.DRIBBLING]}",
        )
    }

    @Test
    fun `in difesa si costruisce, in attacco si prova la giocata`() {
        val player = giocatore(Position.CC)
        val dietro = distribuzione(player, Band.DIF)
        val davanti = distribuzione(player, Band.ATT)

        assertTrue(
            dietro.getValue(Duello.PASSAGGIO) > davanti.getValue(Duello.PASSAGGIO),
            "in difesa non si passa piu' che in attacco",
        )
        assertTrue(
            davanti.getValue(Duello.DRIBBLING) > dietro.getValue(Duello.DRIBBLING),
            "in attacco non si punta l'uomo piu' che in difesa",
        )
    }

    @Test
    fun `il ritmo alto cerca la profondita'`() {
        val player = giocatore(Position.CC)
        val lento = distribuzione(player, Band.MID, Tactics(tempo = TacticalTempo.LENTO))
        val alto = distribuzione(player, Band.MID, Tactics(tempo = TacticalTempo.ALTO))

        assertTrue(
            alto.getValue(Duello.CORSA) > lento.getValue(Duello.CORSA),
            "il ritmo non cambia il tipo di giocata",
        )
        assertTrue(
            lento.getValue(Duello.PASSAGGIO) > alto.getValue(Duello.PASSAGGIO),
            "giocare lento non produce piu' costruzione",
        )
    }

    @Test
    fun `il gioco largo produce piu' cross`() {
        val player = giocatore(Position.AD)
        val stretto = distribuzione(player, Band.ATT, Tactics(width = TacticalWidth.STRETTO))
        val largo = distribuzione(player, Band.ATT, Tactics(width = TacticalWidth.LARGO))

        assertTrue(
            largo.getValue(Duello.AEREO) > stretto.getValue(Duello.AEREO) * 1.4,
            "la larghezza non cambia quanto si crossa: " +
                "${largo[Duello.AEREO]} contro ${stretto[Duello.AEREO]}",
        )
    }

    /**
     * Provarci non e' riuscirci. Se l'attitudine schiacciasse tutto il resto, un attributo
     * alto diventerebbe una scorciatoia: si farebbe solo quella cosa, sempre.
     */
    @Test
    fun `nessuna attitudine cancella le altre giocate`() {
        val monomaniaco = giocatore(Position.AD, Attr.DRIBBLING to 99)
        val d = distribuzione(monomaniaco, Band.ATT)

        assertTrue(
            d.values.all { it > 0 },
            "con 99 di dribbling il giocatore ha smesso di fare tutto il resto: $d",
        )
        assertTrue(
            d.getValue(Duello.DRIBBLING) < 4000 * 0.6,
            "il dribbling si e' mangiato la partita: ${d[Duello.DRIBBLING]} su 4000",
        )
    }

    @Test
    fun `il peso cresce con l'attributo che conta`() {
        val scarso = giocatore(Position.AD, Attr.DRIBBLING to 35)
        val bravo = giocatore(Position.AD, Attr.DRIBBLING to 95)

        val pesoScarso = Intenzioni.peso(Duello.DRIBBLING, scarso, Band.ATT, Tactics.DEFAULT)
        val pesoBravo = Intenzioni.peso(Duello.DRIBBLING, bravo, Band.ATT, Tactics.DEFAULT)

        assertTrue(
            pesoBravo > pesoScarso * 2.5,
            "chi sa dribblare non ci prova abbastanza piu' di chi non sa: " +
                "$pesoBravo contro $pesoScarso",
        )
    }
}
