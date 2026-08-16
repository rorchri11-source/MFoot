package dev.mfoot.core.model

/**
 * Tratti caratteriali di un giocatore.
 *
 * In un mondo generato proceduralmente sono i tratti a dare identita': senza nomi reali
 * a cui affezionarsi, quello che rende un giocatore memorabile e' come si comporta.
 *
 * Ogni tratto ha effetti **meccanici**, non descrittivi. Un tratto che non muove nessun
 * numero e' decorazione, e la decorazione non va nel motore.
 *
 * Sono anche la spina dorsale delle conversazioni: le stesse quattro opzioni di dialogo
 * danno esiti diversi a seconda di chi si ha davanti.
 */
enum class Trait(
    val label: String,
    val description: String,
    /** Moltiplicatore sul consumo di stamina: < 1 significa che si stanca meno. */
    val staminaFactor: Double = 1.0,
    /** Moltiplicatore sul rischio di infortunio. */
    val injuryFactor: Double = 1.0,
    /** Moltiplicatore sull'esperienza guadagnata. */
    val growthFactor: Double = 1.0,
    /** Quanto oscilla il morale: > 1 significa reazioni piu' estreme in entrambi i sensi. */
    val moraleVolatility: Double = 1.0,
    /** Quanto oscilla la forma partita per partita. */
    val formVolatility: Double = 1.0,
    /** Bonus ai rating di zona nelle partite dichiarate importanti. */
    val bigMatchBonus: Double = 0.0,
    /** Bonus di morale che questo giocatore regala al resto dello spogliatoio. */
    val squadMoraleBonus: Double = 0.0,
    /** Peso extra nella scelta del rigorista. */
    val penaltyTakerWeight: Double = 1.0,
) {
    RIGORISTA(
        "Rigorista nato",
        "Va sul dischetto senza tremare.",
        penaltyTakerWeight = 3.0,
    ),

    TESTA_CALDA(
        "Testa calda",
        "Reagisce male ai rimproveri e colleziona cartellini.",
        moraleVolatility = 1.6,
        injuryFactor = 1.1,
    ),

    UOMO_SPOGLIATOIO(
        "Uomo spogliatoio",
        "Accetta le scelte se gliele spieghi, e tiene su il gruppo.",
        moraleVolatility = 0.6,
        squadMoraleBonus = 2.0,
    ),

    FRAGILE(
        "Fragile",
        "Si fa male spesso e recupera piano.",
        injuryFactor = 1.8,
        staminaFactor = 1.2,
    ),

    GRANDI_PARTITE(
        "Uomo delle grandi partite",
        "Nelle sfide che contano tira fuori qualcosa in piu'.",
        bigMatchBonus = 4.0,
    ),

    LEADER(
        "Leader",
        "Trascina la squadra e regge la pressione.",
        moraleVolatility = 0.7,
        squadMoraleBonus = 3.0,
        bigMatchBonus = 1.5,
    ),

    INCOSTANTE(
        "Incostante",
        "Un giorno domina, quello dopo sparisce.",
        formVolatility = 2.0,
    ),

    INSTANCABILE(
        "Instancabile",
        "Regge due partite al giorno senza crollare.",
        staminaFactor = 0.65,
    ),

    TALENTO_PRECOCE(
        "Talento precoce",
        "Esplode presto: cresce in fretta da giovanissimo.",
        growthFactor = 1.35,
    ),

    MATURAZIONE_TARDIVA(
        "Maturazione tardiva",
        "Ci mette tempo, ma non smette di crescere quando gli altri si fermano.",
        growthFactor = 0.8,
    ),

    AMBIZIOSO(
        "Ambizioso",
        "Vuole giocare e vincere: se resta in panchina si innervosisce presto.",
        moraleVolatility = 1.4,
        growthFactor = 1.1,
    ),

    FEDELE(
        "Fedele",
        "Difficile che chieda di andarsene, anche nei momenti storti.",
        moraleVolatility = 0.75,
    );

    companion object {
        /** Tratti che hanno senso solo per i giovani: non si assegnano ai veterani. */
        val youthOnly: Set<Trait> = setOf(TALENTO_PRECOCE, MATURAZIONE_TARDIVA)
    }
}

/** Effetti combinati di un insieme di tratti. I moltiplicatori si compongono, i bonus si sommano. */
fun Set<Trait>.staminaFactor(): Double = fold(1.0) { acc, t -> acc * t.staminaFactor }
fun Set<Trait>.injuryFactor(): Double = fold(1.0) { acc, t -> acc * t.injuryFactor }
fun Set<Trait>.growthFactor(): Double = fold(1.0) { acc, t -> acc * t.growthFactor }
fun Set<Trait>.moraleVolatility(): Double = fold(1.0) { acc, t -> acc * t.moraleVolatility }
fun Set<Trait>.formVolatility(): Double = fold(1.0) { acc, t -> acc * t.formVolatility }
fun Set<Trait>.bigMatchBonus(): Double = sumOf { it.bigMatchBonus }
fun Set<Trait>.squadMoraleBonus(): Double = sumOf { it.squadMoraleBonus }
