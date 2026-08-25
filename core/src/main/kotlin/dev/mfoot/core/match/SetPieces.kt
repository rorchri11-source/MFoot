package dev.mfoot.core.match

import dev.mfoot.core.model.Attr
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.squadMoraleBonus

/**
 * I cinque incarichi che si assegnano in formazione.
 *
 * ## Perche' esistono
 *
 * Perche' fino al 2026-08-24 la formazione era una domanda sola — chi gioca — e la
 * risposta la dava quasi da sola la rosa: si schierano i migliori. Gli incarichi
 * aggiungono le decisioni che il calcio ha davvero e che una rosa non decide per te:
 * chi porta la fascia, chi va sul dischetto, chi mette il pallone in mezzo.
 *
 * ## La regola che li tiene onesti
 *
 * **Un incarico deve muovere un numero.** Se non lo fa e' una casella da riempire per
 * niente, e le caselle da riempire per niente sono precisamente ciò che rende faticosa
 * una schermata. Ognuno di questi cinque entra in un calcolo del motore: chi non ci
 * entrasse non starebbe qui.
 */
enum class MatchDuty(
    val label: String,
    /** Cosa si guarda per sceglierlo, detto a chi sta scegliendo. */
    val hint: String,
) {
    CAPITANO("Capitano", "esperienza e carisma"),
    RIGORISTA("Rigorista", "tiro e freddezza"),
    ANGOLI("Battitore d'angoli", "passaggio e tecnica"),
    PUNIZIONI("Battitore di punizioni", "tiro e tecnica"),
    LANCI_LUNGHI("Uomo dei calci lunghi", "fisico e passaggio"),
}

/**
 * Chi calcia cosa, e chi porta la fascia.
 *
 * ## Il designato, e chi lo sostituisce
 *
 * Ogni incarico ha due risposte, e servono tutte e due. [designated] e' chi il manager ha
 * scelto — e vale **solo se e' in campo**: un rigorista in panchina non calcia i rigori, e
 * uno espulso nemmeno. [taker] e' chi calcia davvero: il designato se c'e', altrimenti il
 * piu' adatto fra gli undici.
 *
 * Senza il secondo, ogni sostituzione e ogni cartellino rosso lascerebbero una casella
 * vuota che il motore dovrebbe risolvere per conto suo, ognuno a modo suo.
 */
object SetPieces {

    /**
     * Quanto uno e' adatto a un incarico, da 0 a 100 circa.
     *
     * I pesi non sono arrotondati a caso: sono la stessa forma delle tabelle di
     * [dev.mfoot.core.model.Position], cioe' una media pesata di attributi che gia'
     * esistono. **Nessun attributo nuovo**: aggiungerne uno — «stacco di testa», «calci
     * piazzati» — vorrebbe dire rigenerare il mondo, e i giocatori gia' generati
     * resterebbero senza.
     */
    fun aptitude(player: Player, duty: MatchDuty): Double {
        val a = player.attributes
        return when (duty) {
            // L'esperienza pesa piu' della qualita': un 78 di trent'anni guida meglio di
            // un 84 di diciannove, ed e' quello che rende la fascia una scelta invece di
            // una conseguenza dell'overall.
            MatchDuty.CAPITANO ->
                player.overall * 0.35 +
                    minOf(player.age, ETA_MASSIMA_UTILE) * 1.4 +
                    player.traits.squadMoraleBonus() * 2.5

            // Il tratto «Rigorista nato» vale gia' tre volte nella scelta automatica del
            // motore: qui entra come bonus piatto, cosi' la stessa preferenza si vede
            // anche nell'elenco che il manager sfoglia.
            MatchDuty.RIGORISTA ->
                a[Attr.TIRO] * 0.78 + a[Attr.TECNICA] * 0.22 + bonusRigorista(player)

            MatchDuty.ANGOLI ->
                a[Attr.PASSAGGIO] * 0.62 + a[Attr.TECNICA] * 0.38

            MatchDuty.PUNIZIONI ->
                a[Attr.TIRO] * 0.55 + a[Attr.TECNICA] * 0.45

            MatchDuty.LANCI_LUNGHI ->
                a[Attr.FISICO] * 0.45 + a[Attr.PASSAGGIO] * 0.55
        }
    }

    /** Chi il manager ha designato, se e' ancora in campo. */
    fun designated(lineup: Lineup, duty: MatchDuty): Player? =
        idFor(lineup, duty)?.let { id -> lineup.slots.firstOrNull { it.player.id == id }?.player }

    /**
     * Chi calcia davvero: il designato, oppure il piu' adatto fra gli undici.
     *
     * Il portiere e' escluso da tutto tranne che dai calci lunghi — dove il rinvio e'
     * proprio il suo mestiere — e dalla fascia, che un portiere puo' portare benissimo.
     */
    fun taker(lineup: Lineup, duty: MatchDuty): Player? =
        designated(lineup, duty) ?: best(candidates(lineup, duty), duty)

    fun best(players: List<Player>, duty: MatchDuty): Player? =
        players.maxWithOrNull(
            compareBy<Player> { aptitude(it, duty) }
                // A parita' esatta decide l'id, non l'ordine in cui sono capitati nella
                // lista: due simulazioni dello stesso mondo devono dare lo stesso uomo.
                .thenBy { it.id.value },
        )

    /** Chi puo' ricevere questo incarico, fra chi e' in campo. */
    fun candidates(lineup: Lineup, duty: MatchDuty): List<Player> = when (duty) {
        MatchDuty.CAPITANO, MatchDuty.LANCI_LUNGHI -> lineup.slots.map { it.player }
        else -> lineup.outfield.map { it.player }
    }

    fun idFor(lineup: Lineup, duty: MatchDuty): PlayerId? = when (duty) {
        MatchDuty.CAPITANO -> lineup.captainId
        MatchDuty.RIGORISTA -> lineup.penaltyTakerId
        MatchDuty.ANGOLI -> lineup.cornerTakerId
        MatchDuty.PUNIZIONI -> lineup.freeKickTakerId
        MatchDuty.LANCI_LUNGHI -> lineup.longBallTakerId
    }

    /** La stessa formazione con un incarico riassegnato. */
    fun assign(lineup: Lineup, duty: MatchDuty, playerId: PlayerId?): Lineup = when (duty) {
        MatchDuty.CAPITANO -> lineup.copy(captainId = playerId)
        MatchDuty.RIGORISTA -> lineup.copy(penaltyTakerId = playerId)
        MatchDuty.ANGOLI -> lineup.copy(cornerTakerId = playerId)
        MatchDuty.PUNIZIONI -> lineup.copy(freeKickTakerId = playerId)
        MatchDuty.LANCI_LUNGHI -> lineup.copy(longBallTakerId = playerId)
    }

    /**
     * Quanto il capitano frena il crollo, da 0 a 1.
     *
     * Zero senza capitano in campo, e non e' un dettaglio: e' il costo di lasciarlo in
     * panchina, ed e' l'unico modo perche' la fascia sia una decisione e non un titolo.
     */
    fun leadership(lineup: Lineup): Double {
        val capitano = taker(lineup, MatchDuty.CAPITANO) ?: return 0.0
        return (aptitude(capitano, MatchDuty.CAPITANO) / SCALA_LEADERSHIP).coerceIn(0.0, 1.0)
    }

    /** Oltre questa eta' l'esperienza non aggiunge piu' niente alla guida del gruppo. */
    private const val ETA_MASSIMA_UTILE = 34

    /**
     * Il divisore che porta il punteggio di capitano nella scala 0-1.
     *
     * Un uomo da 80 di overall e 30 anni con il tratto «Leader» sfiora l'uno; un
     * ventenne qualsiasi sta poco sopra la meta'. La differenza fra i due deve contare
     * senza diventare decisiva: il capitano attenua un crollo, non lo cancella.
     */
    private const val SCALA_LEADERSHIP = 90.0

    private fun bonusRigorista(player: Player): Double =
        (player.traits.maxOfOrNull { it.penaltyTakerWeight } ?: 1.0).let { peso ->
            if (peso > 1.0) BONUS_RIGORISTA_NATO else 0.0
        }

    private const val BONUS_RIGORISTA_NATO = 6.0
}
