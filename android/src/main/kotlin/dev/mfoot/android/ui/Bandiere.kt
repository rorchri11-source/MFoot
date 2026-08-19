package dev.mfoot.android.ui

/**
 * La bandiera di un paese, come emoji.
 *
 * ## Perche' una mappa e non un calcolo
 *
 * Le bandiere emoji si costruiscono dalle due lettere del codice ISO: `IT` diventa la
 * coppia di indicatori regionali 🇮🇹. Il calcolo e' una riga, ma richiede il codice — e
 * quello che il gioco ha e' il **nome in italiano**, perche' e' quello che si mostra nella
 * scheda. Servirebbe comunque una mappa da "Paesi Bassi" a `NL`, quindi tanto vale che
 * porti direttamente la bandiera.
 *
 * L'elenco e' quello di `WorldConfig.nationalities`: dieci paesi, non trecento. Se un
 * domani se ne aggiunge uno e qui manca, si vede subito — compare un pallino grigio invece
 * di una bandiera — invece di rompersi in silenzio.
 */
private val BANDIERE = mapOf(
    "Italia" to "🇮🇹",
    "Francia" to "🇫🇷",
    "Germania" to "🇩🇪",
    "Spagna" to "🇪🇸",
    "Inghilterra" to "🏴󠁧󠁢󠁥󠁮󠁧󠁿",
    "Turchia" to "🇹🇷",
    "Brasile" to "🇧🇷",
    "Argentina" to "🇦🇷",
    "Portogallo" to "🇵🇹",
    "Paesi Bassi" to "🇳🇱",
)

/** Un cerchietto neutro per i paesi che non sono in elenco: si vede che manca, e non rompe. */
private const val SCONOSCIUTA = "◦"

fun bandiera(nazionalita: String): String = BANDIERE[nazionalita] ?: SCONOSCIUTA
