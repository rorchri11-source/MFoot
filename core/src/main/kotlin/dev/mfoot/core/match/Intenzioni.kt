package dev.mfoot.core.match

import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Band
import dev.mfoot.core.model.Player
import dev.mfoot.core.rng.DeterministicRandom
import dev.mfoot.core.rng.MathX

/**
 * Cosa prova a fare chi ha la palla.
 *
 * ## Perche' non e' un'estrazione uniforme
 *
 * Perche' e' qui che due giocatori con lo stesso overall smettono di essere lo stesso
 * giocatore. Un'ala con 88 di velocita' e 60 di dribbling cerca la corsa alle spalle del
 * terzino; la stessa ala con 60 e 88 punta l'uomo. Prima finivano tutti e due nella stessa
 * media di zona e producevano lo stesso identico esito atteso.
 *
 * La scelta pesa tre cose:
 *
 * 1. **dove sta** — in difesa si costruisce, in attacco si punta l'uomo e si crossa;
 * 2. **chi e'** — l'attitudine, cioe' l'attributo che dice *questo e' il mio gioco*;
 * 3. **come gioca la squadra** — il ritmo alto cerca la profondita', il gioco largo cerca
 *    il cross, quello stretto la giocata nello spazio corto.
 *
 * ## Perche' le tabelle stanno nel codice
 *
 * Stessa ragione di [Conclusioni] e [Duelli]: che in area si crossi piu' che in difesa non
 * e' una manopola dell'admin, e' la descrizione del calcio. Le manopole della lega restano
 * in [dev.mfoot.core.config.EngineConfig].
 */
object Intenzioni {

    /**
     * Quanto un ruolo di quell'altezza prova ciascuna cosa, prima di sapere chi e'.
     *
     * In difesa il pallone si muove: sei volte su dieci e' un passaggio, e la palla lunga
     * e' l'alternativa vera. In attacco la costruzione lascia spazio al gesto individuale.
     */
    private fun pesoBase(duello: Duello, band: Band): Double = when (band) {
        Band.DIF -> when (duello) {
            Duello.PASSAGGIO -> 62.0
            Duello.AEREO -> 16.0
            Duello.CONTRASTO -> 12.0
            Duello.DRIBBLING -> 6.0
            Duello.CORSA -> 4.0
        }

        Band.MID -> when (duello) {
            Duello.PASSAGGIO -> 52.0
            Duello.CORSA -> 16.0
            Duello.DRIBBLING -> 14.0
            Duello.CONTRASTO -> 12.0
            Duello.AEREO -> 6.0
        }

        Band.ATT -> when (duello) {
            Duello.PASSAGGIO -> 28.0
            Duello.DRIBBLING -> 24.0
            Duello.CORSA -> 20.0
            Duello.AEREO -> 16.0
            Duello.CONTRASTO -> 12.0
        }
    }

    /**
     * L'attributo che dice *questo e' il mio gioco*.
     *
     * Sul duello aereo non e' il fisico ma il **passaggio**: chi ha la palla non salta, la
     * mette in mezzo. Chi salta e' un altro, e lo sceglie il motore fra chi attacca l'area.
     */
    private fun attitudine(duello: Duello): Attr = when (duello) {
        Duello.CORSA -> Attr.VELOCITA
        Duello.DRIBBLING -> Attr.DRIBBLING
        Duello.CONTRASTO -> Attr.FISICO
        Duello.AEREO -> Attr.PASSAGGIO
        Duello.PASSAGGIO -> Attr.PASSAGGIO
    }

    /**
     * Quanto la squadra spinge verso quel tipo di giocata.
     *
     * Sono moltiplicatori piu' larghi di quelli tattici sui rating, e volutamente: qui non
     * cambiano **quanto sei forte** ma **che partita giochi**, e una differenza del 5% non
     * si vedrebbe mai in un tabellino.
     */
    private fun fattoreTattico(duello: Duello, tactics: Tactics): Double = when (duello) {
        Duello.CORSA -> when (tactics.tempo) {
            TacticalTempo.LENTO -> 0.75
            TacticalTempo.NORMALE -> 1.0
            TacticalTempo.ALTO -> 1.35
        }

        Duello.PASSAGGIO -> when (tactics.tempo) {
            TacticalTempo.LENTO -> 1.20
            TacticalTempo.NORMALE -> 1.0
            TacticalTempo.ALTO -> 0.85
        }

        Duello.AEREO -> when (tactics.width) {
            TacticalWidth.STRETTO -> 0.70
            TacticalWidth.NORMALE -> 1.0
            TacticalWidth.LARGO -> 1.40
        }

        Duello.DRIBBLING -> when (tactics.width) {
            TacticalWidth.STRETTO -> 1.15
            TacticalWidth.NORMALE -> 1.0
            TacticalWidth.LARGO -> 0.95
        }

        Duello.CONTRASTO -> 1.0
    }

    /**
     * Quanto e' probabile che questo giocatore, li', provi questa cosa.
     *
     * L'attitudine va da 0,55 a 1,75: chi ha 95 di dribbling ci prova tre volte piu' di
     * chi ne ha 40. E' il divario che rende riconoscibile un giocatore guardando la
     * cronaca, senza che nessun attributo diventi una scorciatoia per vincere — perche'
     * provarci di piu' non vuol dire riuscirci di piu': quello lo decide [Duelli].
     */
    fun peso(duello: Duello, player: Player, band: Band, tactics: Tactics): Double {
        val quanto = MathX.remap(
            player.attributes[attitudine(duello)].toDouble(), 40.0, 95.0, 0.55, 1.75,
        )
        return pesoBase(duello, band) * quanto * fattoreTattico(duello, tactics)
    }

    /** Cosa prova a fare, adesso. */
    fun scegli(
        player: Player,
        band: Band,
        tactics: Tactics,
        rng: DeterministicRandom,
    ): Duello = rng.pickWeighted(Duello.entries) { peso(it, player, band, tactics) }
}
