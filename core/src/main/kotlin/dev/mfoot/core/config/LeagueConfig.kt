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
    /**
     * Quanto costa un membro dello staff da cinque stelle, in frazione del budget.
     *
     * La stessa manopola di [topPlayerBudgetShare], per l'altro mercato. Il quattro per
     * cento contro il sessantacinque non e' una svalutazione dello staff: e' il rapporto
     * fra le due spese. Una rosa sono venti acquisti, lo staff sono tre — e un allenatore
     * che costasse come un titolare renderebbe la scelta ovvia in un verso solo.
     *
     * Il conto per le stelle sotto lo fa [dev.mfoot.core.market.Valuation.staffPrice], con
     * una curva quadratica: 1★ costa un venticinquesimo di 5★, che e' quello che merita
     * chi fa crescere i giocatori tre volte meno.
     */
    val staffBudgetShare: Double = 0.04,
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

    // ------------------------------------------------- il listino e la contestazione

    /**
     * Si compra a prezzo fisso, e il giocatore e' tuo nello stesso istante.
     *
     * Deciso dal proprietario il 2026-08-24. L'asta come rito obbligatorio costava un
     * giorno reale per ogni gregario — peggiorato dal fatto che il tick passa ogni venti
     * o quaranta minuti, non ogni dieci — e una rosa da diciotto uomini diventava tre
     * settimane di attesa.
     *
     * Spegnerlo riporta la lega al mercato di sole aste.
     */
    val instantBuyEnabled: Boolean = true,

    /**
     * Per quante ore un acquisto puo' essere contestato.
     *
     * E' l'unica cosa che fa nascere un'asta. Passate queste ore il giocatore e' di chi
     * l'ha comprato per sempre, e chi compra conosce **fin dal primo istante** l'ora in
     * cui sara' definitivo: l'asta di contestazione scade insieme alla finestra, non
     * un'ora dopo l'ultimo rilancio.
     *
     * A zero il mercato diventa senza reti: si compra e non si discute.
     */
    val contestWindowHours: Int = 12,

    /**
     * Il prezzo di vendita lo scrive il proprietario, libero.
     *
     * Deciso il 2026-08-24, sapendo il rischio: due amici d'accordo possono spostare un
     * fuoriclasse per un credito. **Il correttivo e' la contestazione** — un prezzo fuori
     * mercato e' la definizione stessa dell'affare troppo buono, e chiunque ha dodici ore
     * per portarlo all'asta. Le due decisioni si tengono in piedi a vicenda.
     *
     * Questo resta come limite di sanita', non come regola di gioco: un prezzo sotto
     * l'unita' non e' un regalo, e' un errore di battitura.
     */
    val minListingPrice: Int = 1,
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

    /**
     * Durata in minuti reali dell'intervallo.
     *
     * Venti, decisi dal proprietario il 2026-08-29 — «non piu'». Erano tre, e con la
     * partita che si liquidava in quindici secondi bastavano: adesso il primo tempo dura
     * quarantacinque minuti veri e l'intervallo e' il momento in cui si guarda com'e'
     * andata e si cambia qualcosa. Tre minuti non sarebbero il tempo di aprire la
     * formazione.
     *
     * E' anche l'attesa del server: il secondo tempo si gioca alla fine di questa finestra.
     */
    val halfTimeWindowMinutes: Int = 20,

    /**
     * Quante ore devono passare fra due partite dello stesso club.
     *
     * ## Perche' due, e perche' e' un numero e non un divieto
     *
     * Perche' una partita **occupa 110 minuti reali**: quarantacinque, venti di intervallo,
     * quarantacinque. Due ore sono quei 110 minuti piu' dieci di respiro. Chiesta dal
     * proprietario cosi': «se la fai alle 10 non puoi alle 11, ma alle 12 minimo».
     *
     * Resta configurabile perche' dipende dall'intervallo: una lega che lo accorcia puo'
     * stringere anche questa, e una che vuole una partita a sera la porta a ventiquattro.
     *
     * ## Il rapporto con [matchesPerDayPerClub]
     *
     * Sono lo stesso vincolo visto da due lati, e prima si contraddicevano: il tetto
     * giornaliero arrivava a quattro partite in un giorno, che con 110 minuti l'una sono
     * sette ore e venti di calcio. Adesso il tetto vero lo fa la distanza, e
     * [matchesPerDayPerClub] resta come limite superiore per chi vuole essere piu' severo.
     */
    val minHoursBetweenMatches: Int = 2,

    /**
     * Quanti giorni passano fra un turno di coppa e il successivo.
     *
     * ## Perche' un turno di coppa non si programma insieme agli altri
     *
     * Perche' non si sa chi lo giochera' finche' il turno prima non e' finito. Il
     * calendario di un campionato nasce intero il giorno della creazione; un tabellone
     * nasce un turno per volta, e ogni volta serve una data.
     *
     * Tre giorni: il tempo di leggere com'e' andata, rimettere in piedi la rosa e
     * schierare. Con uno si giocherebbe il giorno dopo con le gambe della partita prima;
     * con sette una coppa da quattro turni durerebbe un mese.
     */
    val cupRoundGapDays: Int = 3,

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

    /**
     * Quanto sta via un osservatore da cinque stelle, in minuti.
     *
     * ## Perche' due numeri e non una formula scritta nel codice
     *
     * Perche' la formula c'era, stava dentro una funzione SQL — `8 + (5 - stelle) * 10`
     * ore — e nessuno poteva toccarla senza aprire il database. Otto ore per il migliore,
     * **quarantotto** per il peggiore: due giorni reali per una singola ricerca, in un
     * gioco dove si gioca due partite al giorno.
     *
     * Il proprietario l'ha misurato e ha deciso il 2026-08-25: massimo due ore, e le fa
     * il peggiore. Le stelle continuano a comprare tempo oltre che qualita' — trenta
     * minuti contro centoventi sono comunque quattro volte tanto — ma su una scala che
     * sta dentro una serata invece che dentro un fine settimana.
     *
     * Il conto lo fa [dev.mfoot.core.world.Scouting.missionMinutes].
     */
    val scoutMinutesBest: Int = 30,
    /** Quanto sta via un osservatore da una stella. Il tetto, deciso dal proprietario. */
    val scoutMinutesWorst: Int = 120,

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

    /**
     * Il gradimento oltre il quale un'AI vuole un giocatore quanto lo puo' volere.
     *
     * Serve a leggere il gradimento su una scala vera. `AiManager.evaluate` restituisce
     * numeri fra 0,1 e 0,6 su una rosa normale — la curva del valore e' cubica, quindi un
     * settantacinque vale 0,42 e non 0,75 — e trattarli come se andassero da zero a uno
     * significava dire «questo giocatore mi interessa al venti per cento», cioe' **lo
     * pago un quinto**. Con questa soglia 0,6 e' pieno interesse, e il resto si distribuisce
     * fra li' e la soglia di indifferenza.
     */
    val fullInterestAppeal: Double = 0.6,

    /**
     * Sotto questa frazione del valore stimato, un'AI compra anche cio' che non cercava.
     *
     * E' il criterio del **prezzo**, tenuto separato da quello del gusto. Senza, un club a
     * rosa completa rispondeva no a qualunque cosa non gli servisse, anche a un ottantenne
     * a un decimo del valore: il gradimento decideva da solo, e il gradimento non sa quanto
     * costa. Un affare cosi' lo prende chiunque faccia mercato sul serio, e poi lo rivende.
     */
    val bargainShare: Double = 0.6,
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

    // ------------------------------------------------------------------- i duelli

    /**
     * Se le azioni le decidono i duelli fra due giocatori invece della media dei reparti.
     *
     * Nasce **spento**. Il motore nuovo sostituisce il decisore dell'azione, quindi la
     * taratura misurata su migliaia di partite — 2,64 gol, 45/27/28, 23,8 tiri, 11,1% di
     * conversione — va rifatta da capo. Finche' [MatchBalanceTest] non e' verde con i
     * duelli accesi, il gioco continua a girare sul motore vecchio: non esiste un giorno
     * in cui e' rotto, e se la taratura non converge si torna indietro togliendo una riga.
     */
    val duelliAttivi: Boolean = false,

    /**
     * Quanto conta essere piu' forte, in ciascuna delle cinque contese.
     *
     * Sono differenze di **attributo** (scala 1-99), non di rating di zona: `k = 9`
     * significa che venti punti di velocita' in piu' fanno vincere lo scatto nove volte
     * su dieci, `k = 26` che gli stessi venti punti nel contrasto valgono poco piu' di due
     * volte su tre.
     *
     * Detta dal proprietario il 2026-08-29: *«nella corsa la velocita' e' quasi decisiva;
     * nel contrasto e nel dribbling conta di piu' il caso, perche' c'entrano la posizione,
     * il rimbalzo e l'arbitro»*. Da qui la scelta di cinque manopole invece di una — e il
     * fatto che ognuna si legga in una statistica diversa: quella del dribbling nei
     * dribbling riusciti a partita, non nei gol.
     */
    val kCorsa: Double = 10.0,
    val kDribbling: Double = 30.0,
    val kContrasto: Double = 55.0,
    val kAereo: Double = 32.0,
    val kPassaggio: Double = 45.0,

    /**
     * Quanto spesso la spunta chi ha la palla, **a parita' di valore**.
     *
     * Distinta dalla pendenza di proposito. Un passaggio riesce quattro volte su cinque e
     * un dribbling meno di una su due, ed e' vero anche fra due giocatori identici: e' il
     * gesto a essere piu' o meno difficile, non il divario. Con una sola manopola le due
     * cose si confonderebbero e non si potrebbe avere un passaggio facile *e* molto
     * sensibile alla qualita' di chi lo da'.
     */
    val equilibrioCorsa: Double = 0.50,
    val equilibrioDribbling: Double = 0.46,
    val equilibrioContrasto: Double = 0.52,
    val equilibrioAereo: Double = 0.50,
    val equilibrioPassaggio: Double = 0.80,

    /**
     * Quante azioni si giocano quando le decidono i duelli.
     *
     * Serve separata da [actionsPerMatch] perche' con i duelli una catena di possesso e'
     * fatta di piu' episodi: un passaggio riuscito non e' un avanzamento, e per arrivare
     * in porta ne servono diversi. Con lo stesso numero di azioni si giocherebbe una
     * partita lunga un terzo.
     */
    val actionsPerMatchDuelli: Int = 280,

    /**
     * Probabilita' di concludere in zona offensiva, col motore a duelli.
     *
     * Piu' bassa di [shotChanceInAttackingZone] perche' le azioni sono piu' numerose e in
     * zona d'attacco ci si resta piu' a lungo: senza, si tirerebbe sessanta volte a
     * partita.
     */
    val shotChanceDuelli: Double = 0.162,

    /**
     * Quanto si smorza la leva dell'assetto sulle conclusioni, col motore a duelli.
     *
     * [TacticalStance.shotChanceFactor] va da 0,80 a 1,34 ed era tarato su un motore in
     * cui **arrivare** in zona d'attacco era raro: la leva agiva su poche azioni. Coi
     * duelli in zona d'attacco ci si resta per piu' episodi di fila, lo stesso fattore
     * agisce su molte piu' conclusioni, e l'arrembante vinceva quindici punti percentuali
     * piu' del catenaccio — cioe' uno dei due assetti diventava semplicemente quello
     * sbagliato da scegliere.
     *
     * Smorzato a meta', 1,34 diventa 1,17 e 0,80 diventa 0,90: l'assetto continua a
     * decidere che partita si gioca, senza decidere chi la vince.
     */
    val smorzamentoAssetto: Double = 0.5,

    /**
     * Quanto oscilla la **giornata** di un giocatore, in punti di attributo.
     *
     * Un tiro di dado a inizio partita, uguale per tutti i novanta minuti, moltiplicato
     * per la volatilita' di forma di chi lo tira. Serve a rendere vera una promessa che il
     * gioco faceva e non manteneva: *«un giorno domina, quello dopo sparisce»* era la
     * descrizione di `INCOSTANTE`, ma dentro la partita quel tratto non muoveva niente e
     * il giocatore faceva la stessa identica partita ogni volta.
     *
     * Con 2,2 un giocatore normale oscilla di due o tre punti; un incostante, che ha
     * volatilita' doppia, arriva a nove nelle giornate estreme. Si vede, e non decide.
     */
    val giornataStdDev: Double = 2.2,

    /**
     * Quanto pesa chi trascina, quando si e' sotto nel finale.
     *
     * Moltiplica la somma dei `rimontaBonus` degli undici in campo. La differenza con la
     * `resistenza` del capitano, che esiste gia', e' che quella guarda **solo la fascia**:
     * un leader senza fascia non trascinava nessuno.
     */
    val spintaLeader: Double = 0.55,
    val spintaLeaderMassima: Double = 4.0,

    /** Da che minuto una squadra sotto comincia a spingere. */
    val minutoRimonta: Int = 75,

    /**
     * Quanto il gioco resta sulla corsia dov'e', invece di spostarsi.
     *
     * ## Il difetto che questi due numeri chiudono
     *
     * Sei zone su nove non venivano **mai** usate. `Zone.advance()` conserva la corsia,
     * `Zone.mirror()` manda il centro nel centro, e ogni ripartenza e' centrale: la palla
     * nasceva in `MID_C` e non ne usciva piu'. Il modello a nove zone era in realta' un
     * modello a tre.
     *
     * Si vedeva misurando i falli: in cento partite li commettevano solo attaccanti,
     * centrali e mediani — nessun terzino, nessuna ala, mai. Non erano scarsi, non
     * toccavano il pallone. E la larghezza tattica moltiplicava fattori di corsie in cui
     * non passava nessuno, quindi «stretto» e «largo» erano la stessa impostazione.
     *
     * Il cambio di fronte da una fascia all'altra resta raro — nel calcio si passa quasi
     * sempre dal centro — ma non impossibile.
     */
    val pesoStessaCorsia: Double = 3.2,
    val pesoCorsiaOpposta: Double = 0.30,

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
    val shotChanceInAttackingZone: Double = 0.54,

    /** xG base per zona di conclusione: il centro vale molto piu' delle fasce. */
    val baseXgCentral: Double = 0.112,
    val baseXgWide: Double = 0.046,

    /**
     * Quanto vale ogni tipo di conclusione, e quanto spesso capita.
     *
     * ## Perche' esistono
     *
     * Perche' prima una conclusione era una sola cosa: la prendeva chi aveva la palla in
     * zona d'attacco, e valeva `baseXgCentral` o `baseXgWide` a seconda della fascia. Il
     * risultato era che segnavano solo gli attaccanti — un difensore non poteva fare gol
     * nemmeno su calcio d'angolo — ed e' la cosa che il proprietario ha segnalato il
     * 2026-08-29: *«gol solo da quelli forti, dall'attacco e basta»*.
     *
     * Con sei tipi, ognuno con il suo valore e la sua platea di tiratori
     * ([Conclusioni.peso]), un centrale attacca i corner e un centrocampista tira da
     * fuori. La media dei gol resta dov'era perche' le conclusioni che si sono aggiunte —
     * teste e tiri da lontano — sono quelle che valgono meno.
     *
     * ## Da dove vengono i numeri
     *
     * Misurati sul calcio vero, e sono gli stessi del Match Simulator che il proprietario
     * ha indicato come metro: un tiro da fuori su venticinque diventa gol, una conclusione
     * in area una su dieci, un'occasione limpida quasi una su tre.
     */
    val xgDaFuori: Double = 0.036,
    val xgInArea: Double = 0.098,
    val xgDiTesta: Double = 0.076,
    val xgLimpida: Double = 0.310,
    val xgRipartenza: Double = 0.196,
    val xgPunizione: Double = 0.054,

    /**
     * Quanto la bravura di chi tira sposta il valore di una conclusione.
     *
     * ## Il numero che concentrava tutti i gol sui fuoriclasse
     *
     * Era da 0,55 a 1,95: un grande finalizzatore segnava **tre volte e mezzo** piu' di uno
     * scarso sulla stessa identica occasione. Sommato al fatto che tiravano solo gli
     * attaccanti, il risultato e' quello che il proprietario ha segnalato il 2026-08-29:
     * *«gol solo da quelli forti»*.
     *
     * Nel calcio vero e' il contrario: **decide l'occasione, non chi la prende**. Un
     * fuoriclasse converte un'occasione limpida forse il 40% in piu' di un onesto, non tre
     * volte tanto — e infatti in una stagione segnano tutti, compresi i difensori sui
     * corner. Il divario resta e si vede sul lungo periodo, ma non cancella piu' la partita
     * singola.
     */
    val finishingMin: Double = 0.97,
    val finishingMax: Double = 1.40,

    /**
     * Quanto spesso un'azione offensiva muore in fuorigioco.
     *
     * Quattro a partita nel calcio vero. Non esisteva: MFoot non aveva nemmeno l'evento, e
     * una statistica che ogni tabellino mostra non si poteva calcolare.
     */
    val offsideChance: Double = 0.035,

    /**
     * Quante conclusioni finiscono murate da un difensore.
     *
     * Un quarto. Contano come tiri e non arrivano al portiere: senza, ogni conclusione era
     * gol, parata o fuori, e il portiere risultava impegnato circa il doppio del vero.
     */
    val blockedShotChance: Double = 0.25,

    /** Quanto spesso capita ogni tipo di conclusione, in parti su cento. */
    val quotaDaFuori: Double = 32.0,
    val quotaInArea: Double = 33.0,
    val quotaDiTesta: Double = 16.0,
    val quotaLimpida: Double = 8.0,
    val quotaRipartenza: Double = 8.0,
    val quotaPunizione: Double = 3.0,

    /** Malus all'xG quando si conclude di piede debole. */
    val weakFootPenaltyPerStar: Double = 0.055,

    val foulChance: Double = 0.09,
    val yellowCardChanceOnFoul: Double = 0.18,
    val redCardChanceOnFoul: Double = 0.006,
    val cornerChanceOnLostAttack: Double = 0.14,
    val injuryChancePerAction: Double = 0.0016,

    /**
     * Quanto spesso un angolo diventa una conclusione vera.
     *
     * I due estremi sono le probabilita' col peggior battitore possibile e col migliore:
     * e' **la ragione per cui l'incarico dei calci d'angolo esiste**. Fino al 2026-08-24
     * l'angolo veniva emesso e la palla ripartiva, quindi valeva esattamente zero per
     * chiunque e chi aveva in rosa uno specialista non ne ricavava niente.
     */
    val cornerConversionMin: Double = 0.10,
    val cornerConversionMax: Double = 0.34,

    /** Quanto spesso un fallo in zona offensiva produce una punizione battuta in porta. */
    val freeKickShotChance: Double = 0.28,

    /** xG di una punizione, dal peggior battitore al migliore. */
    val freeKickXgMin: Double = 0.028,
    val freeKickXgMax: Double = 0.125,

    /**
     * Quanto il capitano tiene in piedi la squadra quando si va sotto.
     *
     * Si somma all'inerzia psicologica ([momentumStrength]) e **solo in svantaggio**: e'
     * la regola dettata il 2026-08-24 — la fascia serve quando c'e' da tenere botta, non
     * quando si vince 3-0. A zero il capitano torna a essere un titolo senza effetti.
     *
     * **Misurato, non scelto.** A 2,6 valeva quasi quanto un gol subito ([momentumStrength]
     * e' 3,2) e spostava il bilanciamento di tutta la lega: fra squadre pari le vittorie in
     * casa scendevano dal 45,1% al 42,5%, perche' chi va sotto piu' spesso e' l'ospite e la
     * spinta arrivava quasi sempre a lui. A 1,3 il capitano si sente nelle rimonte e i
     * numeri tornano dove `BalanceReportTest` li aveva misurati.
     */
    val captainResilience: Double = 1.3,

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
    /**
     * Punti di stamina recuperati per **ora reale**, da un giocatore medio senza staff.
     *
     * ## Perche' per ora e non per giornata
     *
     * Era 34 per giornata di gioco, e una giornata e' una fascia oraria del calendario:
     * quanto tempo vero valga dipendeva da quante fasce l'admin aveva messo in un giorno.
     * Con due fasce erano dodici ore, con quattro sei — lo stesso identico riposo pagava il
     * doppio in una lega e la meta' nell'altra, e nessuno poteva accorgersene.
     *
     * Dal 2026-08-29 la partita dura novanta minuti veri e fra due partite passano almeno
     * due ore: il riposo e' diventato una quantita' di **tempo**, e va misurato in tempo.
     *
     * ## Il valore
     *
     * Sette punti l'ora: due ore fra una partita e l'altra ne rendono quattordici, una
     * notte riporta al massimo chiunque. Sono circa due volte e mezzo il ritmo di prima —
     * «tempi recuperi accelerati, ora troppo lenti», chiesto dal proprietario il
     * 2026-08-29 — e restano lontani dal rendere la rosa profonda inutile: dopo una partita
     * vera servono comunque diverse ore per tornare schierabili.
     */
    val staminaRecoveryPerHour: Double = 7.0,
    /** Sotto questa soglia la stanchezza inizia a pesare sui rating. */
    val staminaComfortThreshold: Int = 65,
    /** Malus massimo ai rating quando la stamina e' a zero. */
    val maxStaminaPenalty: Double = 14.0,

    /** Peso di morale e forma sui rating di zona. */
    val moraleWeight: Double = 0.06,
    val formWeight: Double = 0.9,
)
