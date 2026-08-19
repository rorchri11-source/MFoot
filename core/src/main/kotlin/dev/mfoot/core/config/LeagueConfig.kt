package dev.mfoot.core.config

import dev.mfoot.core.model.Position
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Tutte le regole di una lega, decise dall'admin al momento della creazione.
 *
 * ## Il principio
 *
 * **Nessun numero di gioco vive nel codice.** Il motore non sa cosa sia "100 crediti":
 * lo chiede qui. Vale anche per l'AI, che ragiona sempre in percentuale del budget
 * disponibile e mai in crediti assoluti, cosi' si adatta da sola a qualunque economia
 * l'admin abbia impostato — ricca, povera o assurda.
 *
 * Questo permette anche di ritoccare il bilanciamento **senza pubblicare un nuovo APK**:
 * i valori stanno sul server, l'app li legge.
 *
 * Le impostazioni sono raggruppate per area cosi' l'interfaccia puo' mostrarne una per
 * schermata, e i preset possono sostituirne interi blocchi.
 */
data class LeagueConfig(
    val setup: SetupConfig = SetupConfig(),
    val divisions: DivisionsConfig = DivisionsConfig(),
    val economy: EconomyConfig = EconomyConfig(),
    val market: MarketConfig = MarketConfig(),
    val calendar: CalendarConfig = CalendarConfig(),
    val rules: RulesConfig = RulesConfig(),
    val custom: CustomPlayerConfig = CustomPlayerConfig(),
    val world: WorldConfig = WorldConfig(),
    val notifications: NotificationConfig = NotificationConfig(),
    val ai: AiConfig = AiConfig(),
    val engine: EngineConfig = EngineConfig(),
    val objectives: ObjectivesConfig = ObjectivesConfig(),
)

// ------------------------------------------------------------------------ obiettivi

/**
 * Cosa la societa' chiede a ogni allenatore, e quanto paga.
 *
 * ## Perche' i premi sono percentuali e non cifre
 *
 * Come tutto il resto dell'economia. Un premio da ventimila crediti e' un incentivo serio
 * in una lega che ne distribuisce centomila all'inizio, ed e' una mancia nella stessa lega
 * col budget moltiplicato per dieci. Scritto in percentuale, il premio segue l'economia
 * scelta dall'admin senza che nessuno debba ricordarsi di aggiornarlo.
 *
 * ## Perche' si possono spegnere
 *
 * Perche' cambiano il gioco: con gli obiettivi accesi, una squadra che gioca per non
 * retrocedere ha un motivo economico per giocare cosi' e non per divertirsi. C'e' chi vuole
 * quella pressione e chi no, e imporla sarebbe decidere che tipo di lega e' quella degli
 * altri.
 */
data class ObjectivesConfig(
    val enabled: Boolean = true,
    /** Il premio dell'obiettivo di classifica, in percentuale del budget di partenza. */
    val leagueRewardPercent: Int = 25,
    /** Il premio dell'obiettivo di crescita. */
    val developmentRewardPercent: Int = 15,
    /**
     * Il premio dell'obiettivo lungo.
     *
     * Piu' alto degli altri due perche' costa piu' di una stagione, e perche' e' l'unico
     * che chiede di non svendere tutto a giugno per far quadrare il bilancio di adesso.
     */
    val longTermRewardPercent: Int = 40,
    /** Quante stagioni dura l'obiettivo lungo. */
    val longTermSeasons: Int = 2,
)

// ---------------------------------------------------------------------------- setup

enum class LeagueMode { SOLO_MULTIPLAYER, MULTIPLAYER_PIU_AI }

data class SetupConfig(
    val leagueName: String = "Lega MFoot",
    /** Parola o codice che serve agli altri per unirsi. */
    val accessCode: String = "MFOOT",
    val totalClubs: Int = 16,
    val mode: LeagueMode = LeagueMode.MULTIPLAYER_PIU_AI,
    /** Quanti dei [totalClubs] sono gestiti dall'AI. */
    val aiClubs: Int = 8,
    val minSquadSize: Int = 16,
    val maxSquadSize: Int = 28,
    /** Seed del mondo. Generato una volta e salvato: non va mai rigenerato sul client. */
    val worldSeed: Long = 20260816L,
) {
    val humanClubs: Int get() = totalClubs - aiClubs
}

// ------------------------------------------------------------------------- divisioni

/**
 * Serie A, B, C: quante, come si chiamano, e chi sale e chi scende.
 *
 * ## Perche' e' spento di partenza
 *
 * Con otto amici un girone unico e' la cosa giusta: tutti si incontrano, la classifica e'
 * una sola e si capisce a colpo d'occhio. Le divisioni servono quando i club sono tanti, e
 * accenderle per default vorrebbe dire spezzare in tre una lega da sei che non ne aveva
 * bisogno.
 *
 * ## Perche' i nomi sono una lista e non un prefisso
 *
 * Verrebbe naturale generare "Serie 1", "Serie 2" da un contatore. Ma una lega fra amici
 * chiama le sue divisioni come vuole — "Champions" e "Europa", o i nomi dei bar dove si
 * vedono — e un nome scelto vale piu' di un nome corretto.
 */
data class DivisionsConfig(
    /**
     * Divisioni attive. Uno significa girone unico, cioe' divisioni disattivate.
     *
     * Non e' un booleano piu' un numero: due modi di dire la stessa cosa si contraddicono
     * appena qualcuno mette `enabled = true` con `count = 1`.
     */
    val count: Int = 1,
    /**
     * I nomi, dalla massima in giu'. Se sono meno di [count], le altre prendono un numero.
     */
    val names: List<String> = listOf("Serie A", "Serie B", "Serie C", "Serie D"),
    /**
     * Quante squadre per divisione, dalla massima in giu'.
     *
     * ## Perche' serve, dato che il numero di club si sa gia'
     *
     * Perche' la regola di questa lega e' che **i giocatori veri partono tutti in prima
     * divisione**, e quindi la dimensione della prima non e' piu' una divisione aritmetica:
     * dipende da quanti amici si sono iscritti. Dodici amici e una Serie A da dieci sono
     * una contraddizione che qualcuno deve sciogliere, e quel qualcuno e' l'admin — che sa
     * se preferisce una Serie A da dodici o due amici in Serie B.
     *
     * Vuota significa «dividi in parti uguali», che e' il comportamento di sempre.
     */
    val sizes: List<Int> = emptyList(),
    /** Quante salgono direttamente dalla divisione di sotto. */
    val directPromotions: Int = 1,
    /** Quante si giocano ai playoff l'ultimo posto disponibile. Zero li disattiva. */
    val playoffSlots: Int = 2,
    /** Quante scendono direttamente dalla divisione di sopra. */
    val directRelegations: Int = 1,
    /** Quante si giocano la salvezza al playout. Zero li disattiva. */
    val playoutSlots: Int = 2,
    /**
     * Gli spareggi si giocano in andata e ritorno.
     *
     * Secco e' piu' emozionante e piu' ingiusto; il doppio confronto premia chi e' arrivato
     * davanti, perche' in caso di parita' passa chi ha il ritorno in casa.
     */
    val twoLeggedPlayoffs: Boolean = false,
) {
    val enabled: Boolean get() = count > 1

    /** Il nome della divisione di livello [level], che sia stato scelto o no. */
    fun nameOf(level: Int): String = names.getOrNull(level - 1) ?: "Divisione $level"
}

// -------------------------------------------------------------------------- economia

/**
 * Ogni quanto arrivano le entrate.
 *
 * `PER_SETTIMANA` e `PER_MESE` sono settimane e mesi **di calendario reale**, non giornate
 * di gioco. Tutto il resto del sistema conta in giornate — contratti, stipendi, crescita —
 * ma un'entrata "a settimana" e' una cosa che si pensa in giorni veri, e tradurla in
 * giornate produrrebbe un accredito che arriva quando capita invece che il lunedi'.
 */
enum class IncomeCadence { PER_GIORNATA, PER_SETTIMANA, PER_MESE, FINE_COMPETIZIONE, MAI }

data class EconomyConfig(
    /**
     * Il budget iniziale di ogni club, in migliaia. 100_000 = 100M.
     *
     * Da qui deriva **tutto il listino**: nessun prezzo del gioco e' scritto in cifre
     * assolute, sono tutti frazioni di questo numero. Cambiarlo riscala il mercato intero
     * senza toccare altro, ed e' il motivo per cui l'AI ragiona sempre in percentuale del
     * disponibile invece che in milioni.
     */
    val startingCredits: Int = 100_000,
    val recurringIncome: Int = 2_000,
    val incomeCadence: IncomeCadence = IncomeCadence.PER_GIORNATA,
    /** Premi per posizione finale, dal primo in giu'. In migliaia. */
    val placementPrizes: List<Int> = listOf(20_000, 12_000, 8_000, 5_000, 3_000, 2_000, 1_000, 500),
    val winPrize: Int = 500,
    val drawPrize: Int = 150,
    val wagesEnabled: Boolean = true,
    /** Stipendio per giornata = overall^2 * questo fattore. Cresce piu' che linearmente. */
    val wageFactor: Double = 0.0009,

    /**
     * Quanto costa il miglior giocatore del mondo, in frazione del budget iniziale.
     *
     * E' la manopola che riscala **tutto il listino** insieme: cambia il budget e i prezzi
     * lo seguono da soli, perche' nessun valore e' scritto in crediti assoluti. Alzarla
     * rende i fuoriclasse un lusso da mezza rosa; abbassarla li mette alla portata di
     * tutti e toglie la scelta.
     */
    val topPlayerBudgetShare: Double = 0.65,
    /** Il rinnovo costa questa frazione di quanto era stato pagato. */
    val renewalCostFraction: Double = 0.5,
    val negativeBalanceAllowed: Boolean = false,
)

// --------------------------------------------------------------------------- mercato

enum class MarketWindowMode {
    /** Il mercato e' sempre aperto. */
    SEMPRE_APERTO,

    /** Solo nelle fasce orarie indicate. */
    FASCE_ORARIE,

    /** Solo quando non ci sono partite in programma: e' il comportamento predefinito. */
    SOLO_CALENDARIO_VUOTO,
}

enum class InitialAuctionMode {
    /** Tutti collegati insieme, si chiamano i giocatori a turno. Il momento sociale piu' forte. */
    SERATA_ASTA,

    /** Aste parallele a scadenza con offerta massima: nessuno deve essere presente. */
    ASINCRONA,
}

data class MarketConfig(
    val initialAuctionMode: InitialAuctionMode = InitialAuctionMode.SERATA_ASTA,
    val auctionDurationMinutes: Int = 60,
    /** Rilancio minimo, in migliaia: 100 = 100K. */
    val minimumRaise: Int = 100,
    val antiSnipeEnabled: Boolean = true,
    val antiSnipeSeconds: Int = 60,
    /**
     * Offerta massima automatica, stile eBay.
     *
     * E' il meccanismo che elimina la pressione di controllare il telefono ogni ora:
     * dichiari il tuo massimo e vai a dormire, il sistema difende la tua posizione.
     * Disattivarlo rende il mercato ostile a chi lavora o studia.
     */
    val proxyBiddingEnabled: Boolean = true,
    val maxParallelAuctionsPerClub: Int = 3,

    /**
     * Aste in parallelo e durata **durante l'allestimento**, quando le rose sono vuote.
     *
     * Il tetto di regime esiste per proteggere l'umano dai rilanci a raffica a stagione in
     * corso. Applicarlo anche all'inizio significa dieci club AI con tre aste a testa che
     * devono riempire centottanta caselle: nove giorni prima di poter giocare la prima
     * partita. Con sei aste da un quarto d'ora bastano tre quarti d'ora.
     *
     * La difesa contro lo sciame non passa da qui: e' l'affollamento sullo stesso
     * obiettivo a spegnere l'interesse, e resta intero. Sei aste sono su sei ruoli
     * diversi, non sei offerte sullo stesso giocatore.
     */
    val initialParallelAuctionsPerClub: Int = 6,
    val initialAuctionDurationMinutes: Int = 15,

    /**
     * Quante aste possono essere aperte **in tutta la lega** nello stesso momento.
     *
     * Il tetto per club non basta: otto club per sei aste fanno quarantotto, e a quel
     * punto il listino e una parete in cui non si trova niente. Questo e il numero che
     * decide quante cose si possono seguire davvero.
     *
     * Non rallenta il mercato quanto sembra: le aste dell allestimento durano un quarto
     * d ora, quindi venti aperte insieme sono ottanta aggiudicazioni all ora.
     */
    val maxOpenAuctionsPerLeague: Int = 20,
    val windowMode: MarketWindowMode = MarketWindowMode.SOLO_CALENDARIO_VUOTO,
    val windowSlots: List<ClosedRange<LocalTime>> = emptyList(),
    val loansEnabled: Boolean = true,
    val releaseClausesEnabled: Boolean = true,
    val swapsEnabled: Boolean = true,
    /** Durata base di un contratto, in giornate di gioco. */
    val defaultContractMatchDays: Int = 19,
    /** Dopo quanti minuti un'offerta di trattativa non risposta decade. */
    val negotiationExpiryMinutes: Int = 1440,
    val minLoanMatchDays: Int = 2,
    val maxLoanMatchDays: Int = 19,
)

// ------------------------------------------------------------------------- calendario

enum class MatchSpeed(val label: String, val secondsPerGameMinute: Double) {
    REALISTICA("1x - 90 minuti", 60.0),
    VELOCE("3x - 30 minuti", 20.0),
    RAPIDA("6x - 15 minuti", 10.0),
    ISTANTANEA("Istantanea", 0.0),
}

data class CalendarConfig(
    val startDate: LocalDate = LocalDate.of(2026, 9, 1),
    val endDate: LocalDate = LocalDate.of(2026, 9, 20),
    /** Quante partite puo' giocare lo stesso club in un giorno reale. */
    val matchesPerDayPerClub: Int = 2,
    /** Giorni della settimana senza partite. */
    val restWeekdays: Set<DayOfWeek> = emptySet(),
    /** Date specifiche da saltare. */
    val restDates: Set<LocalDate> = emptySet(),
    /** Orari di inizio disponibili nell'arco della giornata. */
    val kickoffSlots: List<LocalTime> = listOf(LocalTime.of(18, 30), LocalTime.of(21, 0)),
    val matchSpeed: MatchSpeed = MatchSpeed.RAPIDA,
    /** Durata in minuti reali della finestra di intervallo per cambi e correzioni. */
    val halfTimeWindowMinutes: Int = 3,

    /**
     * Il fuso in cui vive la lega.
     *
     * ## Perche' serve, invece di usare l'ora del telefono
     *
     * Perche' [kickoffSlots] sono orari senza data e senza fuso: `21:00` da solo non e' un
     * momento, e' un numero. Qualcuno deve dire *le nove di dove*, e la risposta giusta e'
     * "di casa della lega": una partita e' un appuntamento fra persone, e un appuntamento
     * fissato alle nove deve restare alle nove anche per chi lo guarda da un altro paese.
     *
     * Senza questo campo il codice faceva la cosa piu' comoda e sbagliata: prendeva le
     * 21:00 scelte dall'admin e le marcava come UTC. In Italia d'estate la partita
     * finiva alle 23:00, e nessuno capiva perche'.
     */
    val timeZone: ZoneId = ZoneId.of("Europe/Rome"),
) {
    /** Il momento vero in cui comincia una partita programmata a quest'ora locale. */
    fun instantOf(local: LocalDateTime): Instant = local.atZone(timeZone).toInstant()

    /** L'ora di lega di un momento: quella che si mostra e quella che si e' scelta. */
    fun localOf(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, timeZone)
}

// ----------------------------------------------------------------------- regole gioco

enum class InjurySeverity { LIEVE, NORMALE, REALISTICA }

data class RulesConfig(
    /**
     * Il player custom deve essere schierato titolare.
     *
     * Senza questo vincolo il giocatore creato dal proprietario (che parte da 65 in un
     * mondo dove i migliori stanno a 88+) non entrerebbe mai in campo, quindi non
     * crescerebbe mai, quindi non entrerebbe mai in campo. L'obbligo rompe il circolo
     * vizioso e rende la squadra davvero *tua*: hai un punto debole strutturale e il
     * gioco sta nel costruirci intorno.
     */
    val customMustStart: Boolean = true,
    val customMinimumMinutes: Int = 60,
    /** Quante volte piu' in fretta cresce il player custom rispetto ai generati. */
    val customGrowthMultiplier: Double = 3.5,

    /** Moltiplicatore globale sulla crescita: la manopola del ritmo di progressione. */
    val growthMultiplier: Double = 1.0,
    val peakAgeStart: Int = 22,
    val peakAgeEnd: Int = 26,
    val plateauAgeEnd: Int = 28,
    val declineAge: Int = 32,

    val friendliesEnabled: Boolean = true,
    /**
     * Se false, le amichevoli non fanno crescere nessuno.
     *
     * Va lasciato false salvo ottime ragioni: se un'amichevole facesse crescere, si
     * potrebbero concordare quindici partite al giorno fra amici compiacenti e il
     * sistema di crescita salterebbe del tutto.
     */
    val friendliesCountForGrowth: Boolean = false,

    val injuriesEnabled: Boolean = true,

    /**
     * Moltiplicatore sul tasso di infortunio del motore. 0 = non si infortuna nessuno.
     *
     * Separato da [injuriesEnabled] perche' sono due domande diverse: se gli infortuni
     * esistono, e quanto sono frequenti. Chi vuole una lega senza sfortuna spegne il primo;
     * chi la vuole solo piu' clemente abbassa il secondo.
     */
    val injuryRateMultiplier: Double = 1.0,

    val yellowCardsEnabled: Boolean = true,
    val injurySeverity: InjurySeverity = InjurySeverity.NORMALE,
    val suspensionsEnabled: Boolean = true,
    val yellowCardsForSuspension: Int = 5,

    val youthTeamEnabled: Boolean = true,
    val youthMaxAge: Int = 21,
    /** Quanto vale, in crescita, una partita di Primavera rispetto a una di prima squadra. */
    val youthMatchGrowthFactor: Double = 0.75,

    val moraleEnabled: Boolean = true,
    val conversationsEnabled: Boolean = true,
    /** Sotto questa soglia il giocatore puo' chiedere la cessione o rifiutare il rinnovo. */
    val lowMoraleThreshold: Int = 30,
)

// ------------------------------------------------------------- il giocatore che sei tu

/**
 * Uno scaglione di costo.
 *
 * Alzare un attributo costa di piu' man mano che sale: e' quello che impedisce di
 * costruire un mostro monodimensionale spendendo tutto su un attributo solo.
 */
data class CostTier(val upTo: Int, val cost: Int)

/**
 * Le regole con cui il proprietario costruisce il suo giocatore.
 *
 * ## Perche' non deve venire troppo forte
 *
 * Il gioco vive di una tensione precisa: **hai un punto debole strutturale e devi
 * costruirci intorno**. Se il budget permettesse di uscire con un 82 gia' pronto, quella
 * tensione sparirebbe e resterebbe solo un giocatore in piu'.
 *
 * Gli scaglioni qui sotto sono **misurati**, non scelti a intuito: c'e' un test che
 * costruisce il giocatore piu' forte possibile in ogni ruolo, spendendo sempre sul punto
 * col miglior rapporto peso/prezzo. Con questi valori nessun ruolo supera 79, e per
 * arrivarci bisogna lasciare piede debole e tecnica a una stella. Chi vuole un giocatore
 * completo resta sotto il 72. In un mondo dove i migliori stanno a 91, entrambi sono
 * ancora **da far crescere**, che e' il punto.
 *
 * Gli scaglioni sono la manopola vera: abbassarli rende il custom subito competitivo,
 * alzarli lo rende un progetto a lungo termine. Come tutto il resto, li decide l'admin.
 */
data class CustomPlayerConfig(
    /** Overall di partenza nel ruolo scelto, prima di spendere un solo punto. */
    val baseOverall: Int = 65,

    /** I punti che il proprietario distribuisce. */
    val skillBudget: Int = 100,

    /** Quanto costa ogni stella di piede debole o tecnica oltre la prima. */
    val starCost: Int = 10,
    val startingStars: Int = 1,

    /**
     * Costo di un punto attributo, per fascia.
     *
     * Si legge dall'alto: il primo scaglione il cui `upTo` non e' ancora stato superato
     * decide il prezzo del prossimo punto.
     */
    val costTiers: List<CostTier> = listOf(
        CostTier(upTo = 69, cost = 1),
        CostTier(upTo = 77, cost = 3),
        CostTier(upTo = 83, cost = 5),
        CostTier(upTo = 99, cost = 8),
    ),

    /** Attributi che il ruolo non usa: bassi ma non ridicoli. */
    val offRoleBase: Int = 45,

    /**
     * Attributi del mestiere sbagliato: un portiere non sa tirare, un attaccante non
     * sa parare. Restano molto bassi, o schierare il proprio custom fuori ruolo
     * diventerebbe una furbizia invece che una disperazione.
     */
    val wrongSideBase: Int = 15,

    val minAge: Int = 16,
    val maxAge: Int = 23,
    val defaultAge: Int = 18,

    /**
     * Quanto potenziale in piu' rispetto a com'e' uscito.
     *
     * E' generoso di proposito. Il custom non si puo' vendere e deve giocare titolare:
     * senza un tetto alto, l'obbligo di schierarlo sarebbe solo una tassa. Con un tetto
     * alto diventa una scommessa su se stessi.
     */
    val potentialBonus: Int = 18,
    val potentialCeiling: Int = 93,
) {
    /** Il massimo che si puo' spendere in stelle, se si portano entrambe a cinque. */
    val maxStarSpend: Int get() = 2 * starCost * (5 - startingStars)
}

// ------------------------------------------------------------------------ mondo

/** Quanti giocatori per fascia di forza. La coda alta deve restare sottile. */
data class OverallTiers(
    val fuoriclasse: Int = 8,      // 87-93
    val top: Int = 40,             // 81-86
    val buoni: Int = 160,          // 74-80
    val normali: Int = 420,        // 66-73
    val gregari: Int = 500,        // 55-65
) {
    val total: Int get() = fuoriclasse + top + buoni + normali + gregari
}

data class WorldConfig(
    val tiers: OverallTiers = OverallTiers(),
    /** Quote indicative per ruolo. Vengono normalizzate, non serve che sommino a 1. */
    val positionQuotas: Map<Position, Double> = defaultPositionQuotas(),
    val nationalities: List<String> = listOf(
        "Italia", "Francia", "Germania", "Spagna", "Inghilterra", "Turchia",
        "Brasile", "Argentina", "Portogallo", "Paesi Bassi",
    ),
    val minAge: Int = 16,
    val maxAge: Int = 37,
    /** Probabilita' che un giocatore abbia almeno un tratto. */
    val traitChance: Double = 0.45,
    val maxTraitsPerPlayer: Int = 2,
    /** Ampiezza minima e massima della forbice di potenziale alla generazione. */
    val minPotentialSpread: Int = 3,
    val maxPotentialSpread: Int = 18,
) {
    val playerCount: Int get() = tiers.total
}

private fun defaultPositionQuotas(): Map<Position, Double> = mapOf(
    Position.POR to 0.11,
    Position.TD to 0.09,
    Position.DC to 0.17,
    Position.TS to 0.09,
    Position.MED to 0.09,
    Position.CC to 0.15,
    Position.TRQ to 0.07,
    Position.AD to 0.07,
    Position.AS to 0.07,
    Position.SP to 0.04,
    Position.ATT to 0.05,
)

// -------------------------------------------------------------------------- notifiche

data class NotificationConfig(
    val immediateEnabled: Boolean = true,
    val dailyDigestHour: LocalTime = LocalTime.of(9, 0),
    /**
     * Tetto duro di eventi generati dall'AI notificati a un club umano in un giorno.
     *
     * E' una garanzia imposta dal server, non una speranza sul comportamento dell'AI.
     * Con venticinque club che si muovono, senza questo tetto l'app verrebbe
     * disinstallata nel giro di tre giorni.
     */
    val maxAiEventsPerHumanClubPerDay: Int = 3,
)

// --------------------------------------------------------------------------------- AI

data class AiConfig(
    /** Moltiplicatore su valutazioni e budget: la difficolta' della lega. */
    val difficultyMultiplier: Double = 1.0,
    val maxMarketActionsPerDay: Int = 2,
    /** Intervallo del ritardo umano prima di rilanciare. */
    val minRebidDelayMinutes: Int = 18,
    val maxRebidDelayMinutes: Int = 180,
    /** Quante volte al giorno un'AI si sveglia a guardare il mercato. */
    val minWakeupsPerDay: Int = 1,
    val maxWakeupsPerDay: Int = 3,
    /**
     * Quanto cala l'appetibilita' di un giocatore per ogni AI gia' impegnata su di lui.
     *
     * E' la regola che impedisce lo sciame: la seconda AI ci pensa, la terza quasi mai,
     * la quarta mai. Il risultato naturale e' 1-3 AI per asta invece di venticinque.
     */
    val crowdingPenalty: Double = 0.45,
    /** Dopo un rifiuto, quante giornate prima di riprovare con lo stesso club. */
    val refusalCooldownMatchDays: Int = 4,
    /** Non fa offerte per un giocatore acquistato da meno di queste giornate. */
    val recentPurchaseGraceMatchDays: Int = 6,
    /** Quanto rilancia oltre l'offerta corrente, in frazione del proprio tetto. */
    val rebidStepFraction: Double = 0.08,
)

// ----------------------------------------------------------------- manopole del motore

/**
 * I parametri numerici del motore di simulazione.
 *
 * Sono separati dal resto perche' hanno un ciclo di vita diverso: le altre sezioni le
 * tocca l'admin, questa la si tara **misurando**, con la simulazione a forza bruta di
 * migliaia di partite. Nessuno di questi valori va scelto a intuito.
 */
data class EngineConfig(
    /**
     * Quanto conta la differenza di forza fra due zone.
     *
     * E' il parametro piu' importante del gioco. K piccolo: la squadra piu' forte vince
     * quasi sempre e il campionato e' gia' deciso al sorteggio. K grande: tutto diventa
     * casuale e costruire la rosa non serve a niente. Va tarato finche' una squadra con
     * +10 di overall vince circa il 65% delle volte, non il 95%.
     */
    val sigmoidK: Double = 34.0,

    /**
     * Bonus ai rating di zona per chi gioca in casa.
     *
     * Va letto **insieme a [sigmoidK]**: i due parametri non sono indipendenti. Alzando
     * K, ogni bonus per azione pesa meno, quindi il vantaggio del campo va rialzato in
     * proporzione. Tarato per ottenere circa 45% vittorie in casa e 28% fuori fra
     * squadre di pari forza.
     */
    val homeAdvantage: Double = 5.0,

    /** Numero medio di azioni in cui si scompone una partita. */
    val actionsPerMatch: Int = 118,

    /** Probabilita' base di concludere quando l'azione arriva in zona offensiva. */
    val shotChanceInAttackingZone: Double = 0.34,

    /** xG base per zona di conclusione: il centro vale molto piu' delle fasce. */
    val baseXgCentral: Double = 0.112,
    val baseXgWide: Double = 0.046,

    /** Malus all'xG quando si conclude di piede debole. */
    val weakFootPenaltyPerStar: Double = 0.055,

    val foulChance: Double = 0.09,
    val yellowCardChanceOnFoul: Double = 0.18,
    val redCardChanceOnFoul: Double = 0.006,
    val cornerChanceOnLostAttack: Double = 0.14,
    val injuryChancePerAction: Double = 0.0016,

    /**
     * Quanto pesa l'inerzia psicologica dopo un gol.
     *
     * E' il parametro che genera le rimonte, cioe' le partite di cui si parla il giorno
     * dopo. A zero le partite diventano piatte e prevedibili.
     */
    val momentumStrength: Double = 3.2,
    val momentumDecayPerAction: Double = 0.94,

    /** Stamina consumata per minuto giocato, prima dei modificatori di fisico e tratti. */
    val staminaDrainPerMinute: Double = 0.34,
    /** Stamina recuperata per giornata, prima del moltiplicatore del preparatore. */
    val staminaRecoveryPerMatchDay: Double = 34.0,
    /** Sotto questa soglia la stanchezza inizia a pesare sui rating. */
    val staminaComfortThreshold: Int = 65,
    /** Malus massimo ai rating quando la stamina e' a zero. */
    val maxStaminaPenalty: Double = 14.0,

    /** Peso di morale e forma sui rating di zona. */
    val moraleWeight: Double = 0.06,
    val formWeight: Double = 0.9,
)
