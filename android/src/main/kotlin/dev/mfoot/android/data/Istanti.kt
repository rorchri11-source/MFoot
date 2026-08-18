package dev.mfoot.android.data

import java.time.Instant
import java.time.OffsetDateTime

/**
 * Le date come le scrive Postgres.
 *
 * ## Perche' esiste questo file
 *
 * Perche' lo stesso difetto viveva in due copie. Entrambe facevano cosi':
 *
 * ```
 * if (!testo.endsWith("Z")) testo + "Z"
 * ```
 *
 * Postgres restituisce `2026-08-18T15:04:05.123456+00:00`: il fuso e' scritto come
 * scostamento, non come `Z`. Accodare una `Z` produce `...+00:00Z`, che non e' una data
 * valida in nessun formato. La variante piu' astuta — tagliare da `+` in poi e mettere la
 * `Z` — e' peggio: butta via lo scostamento e sposta l'orario di due ore senza fallire.
 *
 * Il primo difetto faceva dire **"mai"** al registro accanto a un giro appena avvenuto. Il
 * secondo faceva vedere le partite due ore piu' tardi. L'ho corretto una volta, in un file
 * solo, e l'altra copia e' rimasta rotta per una settimana: e' esattamente il motivo per
 * cui adesso ce n'e' una sola.
 *
 * ## Perche' fallisce in silenzio
 *
 * Un formato inatteso restituisce null e non un'eccezione: una data strana non deve
 * svuotare una schermata. E' anche il motivo per cui il difetto non si notava, e per
 * questo chi chiama deve mostrare qualcosa di sensato quando arriva null — "mai",
 * "giornata 14" — invece di una riga vuota.
 */
object Istanti {

    /**
     * Le tre forme che si incontrano davvero: con scostamento, gia' in UTC, e con lo
     * spazio al posto della T che alcuni strumenti producono.
     */
    fun parse(testo: String): Instant? {
        val pulito = testo.trim()
        if (pulito.isEmpty()) return null

        return runCatching { OffsetDateTime.parse(pulito).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(pulito) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(pulito.replace(' ', 'T')).toInstant() }.getOrNull()
    }
}
