package dev.mfoot.core.world

import dev.mfoot.core.config.LeagueConfig
import dev.mfoot.core.config.OverallTiers
import dev.mfoot.core.config.WorldConfig
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Position
import dev.mfoot.core.model.Staff
import dev.mfoot.core.model.StaffId
import dev.mfoot.core.model.StaffRole
import dev.mfoot.core.model.Trait
import dev.mfoot.core.rng.DeterministicRandom

/** Il mondo generato per una lega: il pool da cui si pesca all'asta. */
data class GeneratedWorld(
    val seed: Long,
    val players: List<Player>,
    val staff: List<Staff>,
) {
    fun playersByPosition(): Map<Position, List<Player>> =
        players.groupBy { it.primaryPosition }

    fun staffByRole(): Map<StaffRole, List<Staff>> =
        staff.groupBy { it.role }

    val goalkeepers: List<Player> get() = players.filter { it.isGoalkeeper }
}

/**
 * Genera il mondo di una lega: giocatori e staff, tutti svincolati al giorno 1.
 *
 * ## Deterministico, ma generato una volta sola
 *
 * Lo stesso seed produce lo stesso mondo, il che rende i test riproducibili e permette
 * di rigiocare una lega identica. Nonostante questo il mondo va **generato dal server e
 * salvato**, mai rigenerato sul client: basterebbe una differenza in una libreria per
 * far vedere a due persone due mondi diversi, e a quel punto nessuno si fida piu' di
 * niente.
 *
 * ## L'ordine conta
 *
 * Prima il potenziale, poi l'eta', poi l'overall attuale come conseguenza dei due.
 * Vedi [DevelopmentCurve] per il perche'.
 */
object WorldGenerator {

    /** Flussi separati, cosi' toccare la generazione dei giocatori non sposta lo staff. */
    private const val STREAM_PLAYERS = 101L
    private const val STREAM_STAFF = 202L

    private const val MAX_NAME_RETRIES = 8

    fun generate(config: LeagueConfig): GeneratedWorld {
        val seed = config.setup.worldSeed
        val root = DeterministicRandom(seed)
        return GeneratedWorld(
            seed = seed,
            players = generatePlayers(config, root.fork(STREAM_PLAYERS)),
            staff = generateStaff(config, root.fork(STREAM_STAFF)),
        )
    }

    // ---------------------------------------------------------------------- giocatori

    private fun generatePlayers(config: LeagueConfig, rng: DeterministicRandom): List<Player> {
        val world = config.world
        val potentials = rng.shuffled(buildPotentialPool(world.tiers, rng))
        val positions = rng.shuffled(buildPositionPool(world, potentials.size))
        val usedNames = mutableSetOf<String>()

        return potentials.indices.map { index ->
            buildPlayer(
                id = PlayerId(index + 1L),
                potential = potentials[index],
                position = positions[index],
                config = config,
                rng = rng,
                usedNames = usedNames,
            )
        }
    }

    /**
     * Un potenziale per ogni giocatore da generare, distribuito sulle fasce.
     *
     * La coda alta deve restare sottile: se i fuoriclasse fossero tanti, prenderne uno
     * smetterebbe di essere una scelta e l'asta perderebbe tensione.
     */
    internal fun buildPotentialPool(tiers: OverallTiers, rng: DeterministicRandom): List<Int> =
        buildList {
            repeat(tiers.fuoriclasse) { add(rng.nextIntInclusive(87, 93)) }
            repeat(tiers.top) { add(rng.nextIntInclusive(81, 86)) }
            repeat(tiers.buoni) { add(rng.nextIntInclusive(74, 80)) }
            repeat(tiers.normali) { add(rng.nextIntInclusive(66, 73)) }
            repeat(tiers.gregari) { add(rng.nextIntInclusive(55, 65)) }
        }

    /**
     * Un ruolo per ogni giocatore, rispettando le quote configurate.
     *
     * Le quote vengono normalizzate, quindi l'admin puo' scriverle come preferisce senza
     * doversi preoccupare che sommino a 1. L'eventuale resto per arrotondamento viene
     * riempito con i ruoli piu' richiesti.
     */
    private fun buildPositionPool(world: WorldConfig, total: Int): List<Position> {
        val quotas = world.positionQuotas.filterValues { it > 0.0 }
        require(quotas.isNotEmpty()) { "nessuna quota di ruolo configurata" }

        val sum = quotas.values.sum()
        val pool = mutableListOf<Position>()
        quotas.forEach { (position, quota) ->
            repeat(StrictMath.round(total * quota / sum).toInt()) { pool += position }
        }

        // L'arrotondamento puo' lasciare buchi o eccedenze: si sistema qui.
        val mostCommon = quotas.maxByOrNull { it.value }!!.key
        while (pool.size < total) pool += mostCommon
        while (pool.size > total) pool.removeAt(pool.lastIndex)
        return pool
    }

    /**
     * Un giocatore.
     *
     * [forcedAge] e [forcedNationality] servono a [Talenti]: un osservatore mandato in
     * Brasile a cercare un terzino deve poterne trovare **uno brasiliano e terzino**,
     * anche quando il mondo non ne ha piu' nessuno libero. Lasciandoli a null si torna
     * alla generazione di sempre.
     */
    internal fun buildPlayer(
        id: PlayerId,
        potential: Int,
        position: Position,
        config: LeagueConfig,
        rng: DeterministicRandom,
        usedNames: MutableSet<String>,
        forcedAge: Int? = null,
        forcedNationality: String? = null,
    ): Player {
        val world = config.world

        // Media e ampiezza stanno in configurazione dal 2026-08-30. Erano scritte qui, e
        // con 25,4 di media producevano l'8% di under 20: su 110 combinazioni nazione per
        // ruolo, quarantuno restavano vuote **il primo giorno**, cioe' un terzo delle
        // ricerche di un osservatore non poteva riuscire mai.
        val age = forcedAge ?: rng.nextGaussian(
            mean = world.ageMean,
            stdDev = world.ageStdDev,
            min = world.minAge.toDouble(),
            max = world.maxAge.toDouble(),
        ).let { StrictMath.round(it).toInt() }

        // Qualcuno e' in anticipo sulla tabella di marcia, qualcuno in ritardo.
        val progressNoise = rng.nextGaussian() * 0.030
        val currentOverall = DevelopmentCurve.currentOverall(potential, age, progressNoise)

        val spread = DevelopmentCurve.potentialSpread(
            age = age,
            minSpread = world.minPotentialSpread,
            maxSpread = world.maxPotentialSpread,
        )
        // Il potenziale non puo' stare sotto l'overall gia' raggiunto.
        val potentialMin = (potential - spread / 2).coerceIn(currentOverall, 99)
        val potentialMax = (potential + spread - spread / 2).coerceIn(potentialMin, 99)

        val nationality = forcedNationality ?: rng.pick(world.nationalities)
        val (firstName, lastName) = uniqueName(nationality, rng, usedNames)
        val attributes = AttributeGenerator.generate(position, currentOverall, rng)
        val (weakFoot, skillStars) = AttributeGenerator.generateStars(currentOverall, rng)

        return Player(
            id = id,
            firstName = firstName,
            lastName = lastName,
            nationality = nationality,
            age = age,
            primaryPosition = position,
            secondaryPositions = secondaryPositions(position, rng),
            attributes = attributes,
            weakFoot = weakFoot,
            skillStars = skillStars,
            potentialMin = potentialMin,
            potentialMax = potentialMax,
            traits = generateTraits(age, world, rng),
            form = rng.nextIntInclusive(-1, 1),
        )
    }

    private fun uniqueName(
        nationality: String,
        rng: DeterministicRandom,
        used: MutableSet<String>,
    ): Pair<String, String> {
        repeat(MAX_NAME_RETRIES) {
            val name = NameBank.generate(nationality, rng)
            val key = "${name.first} ${name.second}"
            if (used.add(key)) return name
        }
        // Dopo qualche tentativo si accetta l'omonimia: succede anche nel calcio vero.
        return NameBank.generate(nationality, rng)
    }

    /** Ruoli in cui il giocatore se la cava quasi altrettanto bene. */
    private val naturalSecondary: Map<Position, List<Position>> = mapOf(
        Position.TD to listOf(Position.DC, Position.AD),
        Position.TS to listOf(Position.DC, Position.AS),
        Position.DC to listOf(Position.MED),
        Position.MED to listOf(Position.DC, Position.CC),
        Position.CC to listOf(Position.MED, Position.TRQ),
        Position.TRQ to listOf(Position.CC, Position.SP),
        Position.AD to listOf(Position.AS, Position.SP),
        Position.AS to listOf(Position.AD, Position.SP),
        Position.SP to listOf(Position.ATT, Position.TRQ),
        Position.ATT to listOf(Position.SP),
        Position.POR to emptyList(),
    )

    private fun secondaryPositions(
        position: Position,
        rng: DeterministicRandom,
    ): List<Position> {
        val candidates = naturalSecondary[position].orEmpty()
        if (candidates.isEmpty()) return emptyList()
        // Circa un giocatore su tre e' adattabile: gli altri hanno un ruolo solo.
        return if (rng.chance(0.34)) listOf(rng.pick(candidates)) else emptyList()
    }

    /** Coppie di tratti che si contraddicono e non devono coesistere. */
    private val conflicting: Set<Set<Trait>> = setOf(
        setOf(Trait.TALENTO_PRECOCE, Trait.MATURAZIONE_TARDIVA),
        setOf(Trait.FRAGILE, Trait.INSTANCABILE),
        setOf(Trait.TESTA_CALDA, Trait.UOMO_SPOGLIATOIO),
        setOf(Trait.AMBIZIOSO, Trait.FEDELE),
    )

    private fun generateTraits(
        age: Int,
        world: WorldConfig,
        rng: DeterministicRandom,
    ): Set<Trait> {
        if (!rng.chance(world.traitChance)) return emptySet()

        // I tratti "da giovane promessa" non hanno senso su un ventottenne.
        val available = Trait.entries.filter { it !in Trait.youthOnly || age <= 23 }
        val wanted = rng.nextIntInclusive(1, world.maxTraitsPerPlayer.coerceAtLeast(1))

        val chosen = mutableSetOf<Trait>()
        repeat(wanted * 3) {
            if (chosen.size >= wanted) return@repeat
            val candidate = rng.pick(available)
            val clashes = chosen.any { setOf(it, candidate) in conflicting }
            if (!clashes) chosen += candidate
        }
        return chosen
    }

    // -------------------------------------------------------------------------- staff

    /**
     * Lo staff che il mondo ha al primo giorno.
     *
     * I cinque stelle sono rari di proposito: l'allenatore top quasi raddoppia la velocita'
     * di crescita di tutta la rosa, e se fossero facili da trovare la scelta di spenderci
     * mezzo budget non esisterebbe.
     *
     * I due numeri che lo decidono stanno adesso in [dev.mfoot.core.config.StaffConfig]:
     * quanti per club, e con che pesi. Erano scritti qui, e insieme facevano **due**
     * allenatori da cinque stelle in tutta una lega da sedici squadre — un mercato che non
     * e' mai esistito, e che non si poteva correggere senza pubblicare un APK.
     */
    private fun generateStaff(config: LeagueConfig, rng: DeterministicRandom): List<Staff> {
        val perRole = StrictMath.round(config.setup.totalClubs * config.staff.perClub).toInt()
            .coerceAtLeast(6)
        val used = mutableSetOf<String>()
        var nextId = 1L

        return StaffRole.entries.flatMap { role ->
            List(perRole) { staffMember(StaffId(nextId++), role, config, rng, used) }
        }
    }

    /**
     * Un membro dello staff solo.
     *
     * Estratta da [generateStaff] perche' serve anche **dopo** la creazione del mondo: il
     * negozio si rifornisce a ogni giornata, e chi entra in lega a stagione iniziata deve
     * trovare comunque un preparatore.
     *
     * [minStars] e [maxStars] restringono il tiro di dado senza cambiarne i pesi: il
     * rifornimento dei comuni chiede 1-3, quello dei rari 4-5, e la distribuzione dentro
     * quella fascia resta quella della lega.
     */
    fun staffMember(
        id: StaffId,
        role: StaffRole,
        config: LeagueConfig,
        rng: DeterministicRandom,
        used: MutableSet<String> = mutableSetOf(),
        minStars: Int = 1,
        maxStars: Int = 5,
    ): Staff {
        val nationality = rng.pick(config.world.nationalities)
        val (firstName, lastName) = uniqueName(nationality, rng, used)
        return Staff(
            id = id,
            firstName = firstName,
            lastName = lastName,
            nationality = nationality,
            role = role,
            stars = rollStars(config, rng, minStars, maxStars),
        )
    }

    private fun rollStars(
        config: LeagueConfig,
        rng: DeterministicRandom,
        minStars: Int = 1,
        maxStars: Int = 5,
    ): Int {
        val pesi = config.staff.pesiStelle
        val basso = minStars.coerceIn(1, 5)
        val alto = maxStars.coerceIn(basso, 5)
        val indici = (basso - 1)..(alto - 1)
        return rng.pickWeighted(indici.toList()) { pesi.getOrElse(it) { 1.0 } } + 1
    }
}
