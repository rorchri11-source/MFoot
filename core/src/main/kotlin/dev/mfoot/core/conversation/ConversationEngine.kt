package dev.mfoot.core.conversation

import dev.mfoot.core.config.RulesConfig
import dev.mfoot.core.model.MatchDay
import dev.mfoot.core.model.Player
import dev.mfoot.core.model.PlayerId
import dev.mfoot.core.model.Trait
import dev.mfoot.core.model.moraleVolatility

/**
 * Perche' il giocatore e' stato convocato a parlare.
 *
 * [prompt] e' la prima frase, quella che dice il manager. Non e' decorazione: e' cio' che
 * fa capire che il gioco sa cosa e' successo. "So che non ti va giu' di non giocare" detto
 * a chi ha davvero tre panchine sulle spalle vale quanto tutto il resto del sistema.
 */
enum class ConversationTopic(val label: String, val prompt: String) {
    NUOVO_ARRIVO("Nuovo arrivo", "Benvenuto. Voglio dirti come ti vedo qui."),
    PROMESSA_TRADITA("Promessa non mantenuta", "Lo so. Non ho mantenuto la parola."),
    RICHIESTA_CESSIONE("Vuole andarsene", "Ho saputo che vuoi lasciarci."),
    INFORTUNIO("Infortunio", "Mi dispiace. Prenditi il tempo che serve."),
    RIENTRO("Rientro", "Bentornato. Come ti senti?"),
    PANCHINA_PROLUNGATA("Panchina", "So che non ti va giu' di non giocare."),
    PRESTAZIONI_SCARSE("Rendimento", "Le ultime partite non sono state all'altezza."),
    MORALE_BASSO("Morale basso", "Non ti vedo sereno."),
    CONTRATTO_IN_SCADENZA("Contratto in scadenza", "Fra poco il tuo contratto finisce."),
    CAPITANO("Fascia da capitano", "Adesso la squadra ha bisogno di te piu' del solito."),
    GRANDE_PRESTAZIONE("Grande partita", "Quella li' e' stata la tua partita."),
    POCO_MINUTAGGIO("Poco spazio", "So che vorresti giocare di piu'."),
    RINNOVO("Rinnovo", "Vorrei che restassi con noi."),
}

/** Come si affronta il discorso. */
enum class ConversationTone(val label: String) {
    INCORAGGIA("Incoraggia"),
    SPIEGA("Spiega la scelta"),
    RIMPROVERA("Rimprovera"),
    PROMETTI("Fai una promessa"),
    SFIDA("Sfidalo"),
}

enum class PromiseType(val label: String) {
    TITOLARE_PER_PARTITE("Titolare per N partite"),
    RINNOVO_ENTRO("Rinnovo entro la scadenza"),
    CESSIONE_ENTRO("Cessione entro la scadenza"),
}

/**
 * Un debito che il manager si assume.
 *
 * E' la meccanica migliore del sistema: una promessa mantenuta vale piu' di qualsiasi
 * discorso, una tradita fa crollare il morale molto piu' di quanto lo avesse alzato.
 * Il World Tick la verifica da solo, anche a telefoni spenti.
 */
data class Promise(
    val playerId: PlayerId,
    val type: PromiseType,
    val madeOn: MatchDay,
    val deadline: MatchDay,
    /** Numero di partite da titolare, per [PromiseType.TITOLARE_PER_PARTITE]. */
    val target: Int = 0,
    val progress: Int = 0,
) {
    val isFulfilled: Boolean get() = progress >= target

    fun withProgress(delta: Int): Promise = copy(progress = progress + delta)

    fun describe(): String = when (type) {
        PromiseType.TITOLARE_PER_PARTITE ->
            "Titolare per $target partite (fatte: $progress) entro $deadline"
        PromiseType.RINNOVO_ENTRO -> "Rinnovo entro $deadline"
        PromiseType.CESSIONE_ENTRO -> "Cessione entro $deadline"
    }
}

enum class PromiseStatus { IN_CORSO, MANTENUTA, TRADITA }

data class ConversationOption(
    val tone: ConversationTone,
    val text: String,
    /** Se scelta, questa opzione crea un debito. */
    val createsPromise: PromiseType? = null,
    val promiseTarget: Int = 0,
    val promiseWindowMatchDays: Int = 0,
)

data class ConversationOutcome(
    val player: Player,
    val moraleDelta: Int,
    val reply: String,
    val promise: Promise? = null,
    val transferRequestWithdrawn: Boolean = false,
)

/**
 * Le conversazioni fra manager e giocatore.
 *
 * ## Perche' i tratti sono il cuore del sistema
 *
 * Le stesse quattro opzioni danno esiti diversi a seconda di chi si ha davanti. Un
 * *Testa calda* rimproverato reagisce peggio di come stava prima; un *Uomo spogliatoio*
 * accetta la panchina se gliela spieghi; un *Ambizioso* si accontenta solo di promesse
 * concrete. E' quello che trasforma i tratti da decorazione in informazione utile, e
 * che rende sensato leggere la scheda di un giocatore prima di comprarlo.
 */
object ConversationEngine {

    /**
     * Le opzioni disponibili per questo argomento.
     *
     * Non tutte hanno senso ovunque: non si sfida un giocatore che chiede la cessione,
     * e non si "spiega la scelta" a chi sta rendendo male.
     */
    fun optionsFor(topic: ConversationTopic, contractMatchDaysLeft: Int = 0): List<ConversationOption> =
        when (topic) {
            ConversationTopic.NUOVO_ARRIVO -> listOf(
                option(ConversationTone.INCORAGGIA, "Ti abbiamo voluto qui. Fai il tuo gioco."),
                option(ConversationTone.SPIEGA, "Ti spiego il ruolo che ho in mente per te."),
                option(ConversationTone.SFIDA, "Il posto non e' regalato a nessuno, nemmeno a te."),
                promiseOption(
                    ConversationTone.PROMETTI,
                    "Comincerai titolare, hai la mia parola.",
                    PromiseType.TITOLARE_PER_PARTITE, target = 3, window = 6,
                ),
            )

            // Qui non ci sono opzioni buone: il danno e' gia' fatto, e la domanda e'
            // soltanto quanto se ne porta via. E' l'unico argomento in cui "spiega"
            // funziona meglio di "prometti", perche' una seconda promessa a chi ha appena
            // visto tradire la prima vale meno di niente.
            ConversationTopic.PROMESSA_TRADITA -> listOf(
                option(ConversationTone.SPIEGA, "Ti devo una spiegazione, non una scusa."),
                option(ConversationTone.INCORAGGIA, "Rimedieremo, te lo dico guardandoti in faccia."),
                promiseOption(
                    ConversationTone.PROMETTI,
                    "Questa volta la mantengo: cinque partite da titolare.",
                    PromiseType.TITOLARE_PER_PARTITE, target = 5, window = 10,
                ),
            )

            ConversationTopic.INFORTUNIO -> listOf(
                option(ConversationTone.INCORAGGIA, "Recupera con calma, il posto ti aspetta."),
                option(ConversationTone.SPIEGA, "Ecco come ci organizziamo mentre non ci sei."),
                promiseOption(
                    ConversationTone.PROMETTI,
                    "Appena stai bene torni titolare.",
                    PromiseType.TITOLARE_PER_PARTITE, target = 2, window = 8,
                ),
            )

            ConversationTopic.RIENTRO -> listOf(
                option(ConversationTone.INCORAGGIA, "Bello riaverti. Vai tranquillo."),
                option(ConversationTone.SPIEGA, "Andiamo per gradi, ti reinserisco piano."),
                option(ConversationTone.SFIDA, "Fammi vedere che sei tornato quello di prima."),
            )

            ConversationTopic.PANCHINA_PROLUNGATA -> listOf(
                option(ConversationTone.SPIEGA, "Non e' contro di te: ti spiego le mie scelte."),
                promiseOption(
                    ConversationTone.PROMETTI,
                    "Dalla prossima giochi, e non per una partita sola.",
                    PromiseType.TITOLARE_PER_PARTITE, target = 4, window = 8,
                ),
                option(ConversationTone.SFIDA, "Prenditelo, il posto. In allenamento."),
                option(ConversationTone.RIMPROVERA, "Se non giochi un motivo c'e', e lo sai."),
            )

            ConversationTopic.CONTRATTO_IN_SCADENZA -> listOf(
                option(ConversationTone.INCORAGGIA, "Per me il tuo posto qui non e' in discussione."),
                option(ConversationTone.SPIEGA, "Parliamo di numeri con calma, ma voglio che resti."),
                promiseOption(
                    ConversationTone.PROMETTI,
                    "Rinnoviamo, e giochi da titolare.",
                    PromiseType.RINNOVO_ENTRO, target = 1, window = 6,
                ),
                promiseOption(
                    ConversationTone.PROMETTI,
                    "Se preferisci andartene, a fine stagione ti lascio libero.",
                    PromiseType.CESSIONE_ENTRO, target = 1, window = 10,
                ),
            )

            ConversationTopic.CAPITANO -> listOf(
                option(ConversationTone.SFIDA, "Tirali su tu, e' per questo che porti la fascia."),
                option(ConversationTone.SPIEGA, "Ti dico cosa non sta funzionando, e cosa ti chiedo."),
                option(ConversationTone.INCORAGGIA, "Ho ancora fiducia in questo gruppo, e in te."),
                option(ConversationTone.RIMPROVERA, "Da un capitano mi aspetto di piu' di cosi'."),
            )

            // Un complimento e' l'unico caso in cui rimproverare non compare: non esiste il
            // modo di andare male, esiste solo di andare meno bene.
            ConversationTopic.GRANDE_PRESTAZIONE -> listOf(
                option(ConversationTone.INCORAGGIA, "Bravo davvero. Continua cosi'."),
                option(ConversationTone.SFIDA, "Adesso fallo ogni domenica."),
                option(ConversationTone.SPIEGA, "Ti dico cosa hai fatto meglio degli altri."),
            )

            ConversationTopic.MORALE_BASSO -> listOf(
                option(ConversationTone.INCORAGGIA, "Ho fiducia in te, tieni duro."),
                option(ConversationTone.SPIEGA, "Ti spiego come ti vedo in questa squadra."),
                option(ConversationTone.SFIDA, "Dimostrami che mi sbaglio."),
                promiseOption(
                    ConversationTone.PROMETTI,
                    "Le prossime tre partite giochi dall'inizio.",
                    PromiseType.TITOLARE_PER_PARTITE, target = 3, window = 6,
                ),
            )

            ConversationTopic.POCO_MINUTAGGIO -> listOf(
                option(ConversationTone.SPIEGA, "In questo momento davanti a te c'e' chi sta meglio."),
                option(ConversationTone.INCORAGGIA, "Il tuo momento arrivera', continua cosi'."),
                promiseOption(
                    ConversationTone.PROMETTI,
                    "Ti garantisco cinque partite da titolare.",
                    PromiseType.TITOLARE_PER_PARTITE, target = 5, window = 10,
                ),
                option(ConversationTone.SFIDA, "Il posto te lo devi prendere in allenamento."),
            )

            ConversationTopic.PRESTAZIONI_SCARSE -> listOf(
                option(ConversationTone.INCORAGGIA, "Capita a tutti, ne uscirai."),
                option(ConversationTone.RIMPROVERA, "Cosi' non va. Mi aspetto molto di piu'."),
                option(ConversationTone.SFIDA, "Fammi ricredere nella prossima partita."),
            )

            ConversationTopic.RICHIESTA_CESSIONE -> listOf(
                option(ConversationTone.SPIEGA, "Per me sei importante, ti spiego perche'."),
                promiseOption(
                    ConversationTone.PROMETTI,
                    "Resta fino a fine stagione e poi ti lascio andare.",
                    PromiseType.CESSIONE_ENTRO, target = 1, window = 12,
                ),
                promiseOption(
                    ConversationTone.PROMETTI,
                    "Rimani e giochi titolare, hai la mia parola.",
                    PromiseType.TITOLARE_PER_PARTITE, target = 6, window = 12,
                ),
                option(ConversationTone.RIMPROVERA, "Hai un contratto, e lo rispetterai."),
            )

            ConversationTopic.RINNOVO -> listOf(
                option(ConversationTone.INCORAGGIA, "Qui sei a casa tua."),
                option(ConversationTone.SPIEGA, "Ecco il progetto che ho in mente per te."),
                promiseOption(
                    ConversationTone.PROMETTI,
                    "Rinnovi e diventi un titolare fisso.",
                    PromiseType.TITOLARE_PER_PARTITE, target = 5, window = 10,
                ),
            )
        }

    /**
     * Esito della conversazione.
     *
     * Il tono di base vale poco: quello che conta e' come lo prende **quel** giocatore.
     */
    fun resolve(
        player: Player,
        topic: ConversationTopic,
        option: ConversationOption,
        today: MatchDay,
        rules: RulesConfig,
        /**
         * Vero quando sei tu ad averlo convocato e lui non aveva niente da dire.
         *
         * E' il parametro che tiene in piedi tutto il sistema. Senza, la convocazione
         * libera renderebbe quanto un colloquio nato da un fatto, e il modo ottimale di
         * giocare sarebbe chiamare tutta la rosa uno per uno finche' il morale non arriva
         * a cento. Con, resta possibile parlare a chi si vuole e non conviene farlo a
         * vuoto: che e' precisamente la differenza fra una meccanica e un pulsante.
         */
        spontanea: Boolean = false,
    ): ConversationOutcome {
        if (!rules.conversationsEnabled) {
            return ConversationOutcome(player, 0, "Non c'e' molto da dire.")
        }

        val base = baseEffect(option.tone)
        val traitModifier = traitReaction(option.tone, player.traits) +
            if (spontanea) fastidioDaConvocazione(player.traits) else 0.0
        val moraleContext = if (player.morale < 25) 0.8 else 1.0
        val peso = if (spontanea) PESO_CONVOCAZIONE else 1.0

        val delta = StrictMath.round(
            (base + traitModifier) * player.traits.moraleVolatility() * moraleContext * peso,
        ).toInt()

        val promise = option.createsPromise?.let { type ->
            Promise(
                playerId = player.id,
                type = type,
                madeOn = today,
                deadline = today + option.promiseWindowMatchDays,
                target = option.promiseTarget,
            )
        }

        return ConversationOutcome(
            player = player.withMorale(player.morale + delta),
            moraleDelta = delta,
            reply = replyFor(option.tone, delta, player),
            promise = promise,
            // Solo una promessa concreta convince chi vuole andarsene a restare.
            transferRequestWithdrawn = topic == ConversationTopic.RICHIESTA_CESSIONE &&
                promise != null && delta > 0,
        )
    }

    // ------------------------------------------------------------------- promesse

    /** Aggiorna una promessa dopo una partita. */
    fun recordMatch(promise: Promise, wasStarter: Boolean): Promise =
        if (promise.type == PromiseType.TITOLARE_PER_PARTITE && wasStarter) {
            promise.withProgress(1)
        } else {
            promise
        }

    fun status(promise: Promise, today: MatchDay): PromiseStatus = when {
        promise.isFulfilled -> PromiseStatus.MANTENUTA
        today >= promise.deadline -> PromiseStatus.TRADITA
        else -> PromiseStatus.IN_CORSO
    }

    /**
     * Effetto sul morale alla chiusura di una promessa.
     *
     * Tradire costa **piu' del doppio** di quanto mantenere faccia guadagnare: e' quello
     * che rende la promessa una scelta vera e non un pulsante gratuito per alzare il
     * morale.
     */
    fun closePromise(player: Player, status: PromiseStatus): ConversationOutcome = when (status) {
        PromiseStatus.MANTENUTA -> {
            val delta = StrictMath.round(14 * player.traits.moraleVolatility()).toInt()
            ConversationOutcome(
                player.withMorale(player.morale + delta), delta,
                "Hai mantenuto la parola. ${player.shortName} non se lo dimentichera'.",
            )
        }
        PromiseStatus.TRADITA -> {
            val delta = -StrictMath.round(30 * player.traits.moraleVolatility()).toInt()
            ConversationOutcome(
                player.withMorale(player.morale + delta), delta,
                "${player.shortName} si sente preso in giro.",
            )
        }
        PromiseStatus.IN_CORSO -> ConversationOutcome(player, 0, "")
    }

    // ------------------------------------------------------------------- interni

    /**
     * Quanto vale un colloquio che non nasceva da niente.
     *
     * Un terzo. Non zero, perche' un manager che passa a scambiare due parole qualcosa
     * lascia; non uno, perche' altrimenti la strada piu' efficiente per vincere il
     * campionato sarebbe convocare venti giocatori a turno invece di allenarli.
     */
    const val PESO_CONVOCAZIONE = 0.35

    /**
     * Chi si secca a essere chiamato senza motivo.
     *
     * Un `AMBIZIOSO` convocato per niente sente di aver perso tempo; un `TESTA_CALDA`
     * sospetta che gli si voglia dire qualcosa e non lo si dica. Un `FEDELE`, al
     * contrario, e' contento che ci si ricordi di lui.
     */
    private fun fastidioDaConvocazione(traits: Set<Trait>): Double {
        var modifier = 0.0
        for (trait in traits) {
            modifier += when (trait) {
                Trait.AMBIZIOSO -> -3.0
                Trait.TESTA_CALDA -> -2.0
                Trait.LEADER -> -1.0
                Trait.FEDELE -> 2.0
                Trait.UOMO_SPOGLIATOIO -> 1.0
                else -> 0.0
            }
        }
        return modifier
    }

    private fun baseEffect(tone: ConversationTone): Double = when (tone) {
        ConversationTone.INCORAGGIA -> 5.0
        ConversationTone.SPIEGA -> 4.0
        ConversationTone.RIMPROVERA -> -3.0
        ConversationTone.PROMETTI -> 11.0
        ConversationTone.SFIDA -> 1.0
    }

    /**
     * Come **quel** giocatore prende quel tono.
     *
     * E' la tabella che da' senso a tutto il sistema: senza, ogni conversazione avrebbe
     * lo stesso esito e tanto varrebbe un pulsante "alza morale".
     */
    private fun traitReaction(tone: ConversationTone, traits: Set<Trait>): Double {
        var modifier = 0.0
        for (trait in traits) {
            modifier += when (trait to tone) {
                Trait.TESTA_CALDA to ConversationTone.RIMPROVERA -> -9.0
                Trait.TESTA_CALDA to ConversationTone.SFIDA -> 6.0
                Trait.TESTA_CALDA to ConversationTone.INCORAGGIA -> 2.0

                Trait.UOMO_SPOGLIATOIO to ConversationTone.SPIEGA -> 8.0
                Trait.UOMO_SPOGLIATOIO to ConversationTone.RIMPROVERA -> 3.0

                Trait.LEADER to ConversationTone.SFIDA -> 5.0
                Trait.LEADER to ConversationTone.SPIEGA -> 4.0
                Trait.LEADER to ConversationTone.INCORAGGIA -> -1.0

                Trait.AMBIZIOSO to ConversationTone.PROMETTI -> 6.0
                Trait.AMBIZIOSO to ConversationTone.INCORAGGIA -> -4.0
                Trait.AMBIZIOSO to ConversationTone.SPIEGA -> -2.0

                Trait.FEDELE to ConversationTone.INCORAGGIA -> 4.0
                Trait.FEDELE to ConversationTone.SPIEGA -> 3.0
                Trait.FEDELE to ConversationTone.RIMPROVERA -> 2.0

                Trait.INCOSTANTE to ConversationTone.SFIDA -> 4.0
                Trait.INCOSTANTE to ConversationTone.RIMPROVERA -> -3.0

                Trait.FRAGILE to ConversationTone.RIMPROVERA -> -4.0
                Trait.FRAGILE to ConversationTone.INCORAGGIA -> 3.0

                else -> 0.0
            }
        }
        return modifier
    }

    private fun replyFor(tone: ConversationTone, delta: Int, player: Player): String = when {
        delta >= 10 -> "${player.shortName} esce dalla stanza convinto."
        delta > 0 -> "${player.shortName} annuisce."
        delta == 0 -> "${player.shortName} ascolta senza dire niente."
        delta > -8 -> "${player.shortName} non sembra averla presa bene."
        else -> "${player.shortName} se ne va sbattendo la porta."
    }

    private fun option(tone: ConversationTone, text: String) = ConversationOption(tone, text)

    private fun promiseOption(
        tone: ConversationTone,
        text: String,
        type: PromiseType,
        target: Int,
        window: Int,
    ) = ConversationOption(tone, text, type, target, window)
}
