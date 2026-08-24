package dev.mfoot.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mfoot.android.app.AppState
import dev.mfoot.android.app.FormazioneAltrui
import dev.mfoot.android.ui.Hairline
import dev.mfoot.android.ui.Label
import dev.mfoot.android.ui.Notice
import dev.mfoot.android.ui.kit.CrestBadge
import dev.mfoot.android.ui.pitch.PITCH_ASPECT
import dev.mfoot.android.ui.pitch.Pitch
import dev.mfoot.android.ui.pitch.pitchSlots
import dev.mfoot.android.ui.theme.MFootColors
import dev.mfoot.android.ui.theme.MFootShapes
import dev.mfoot.android.ui.theme.MFootSpacing
import dev.mfoot.android.ui.theme.MFootType

/**
 * Come schiera un'altra squadra: il campo, la panchina, l'assetto.
 *
 * ## Perche' serviva
 *
 * Perche' era una delle cose date per scontate. Il modulo dell'avversario esiste nel
 * database, lo legge il tick per giocare la partita e lo si scopre comunque a partita
 * finita guardando le presenze — ma prima della partita non c'era **nessuna schermata** che
 * lo mostrasse. Preparare una gara significava tirare a indovinare, e la scelta fra
 * catenaccio e arrembante — che il motore fa contare davvero — si faceva al buio.
 *
 * ## Cosa non si vede
 *
 * Gli ordini condizionali e i cambi programmati, che sono la parte che uno prepara di
 * nascosto. Il modulo e' una dichiarazione pubblica, come una maglia; il piano per il
 * secondo tempo no.
 */
@Composable
fun CampoAltruiScreen(
    state: AppState.Dentro,
    clubId: Long,
    formazione: FormazioneAltrui,
    onCarica: (Long) -> Unit,
) {
    LaunchedEffect(clubId) { onCarica(clubId) }

    val club = state.lega.clubs.firstOrNull { it.id == clubId }
    if (club == null) {
        Box(Modifier.fillMaxSize().background(MFootColors.bg), contentAlignment = Alignment.Center) {
            Text("Questo club non esiste più.", style = MFootType.secondary, color = MFootColors.ink3)
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MFootColors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MFootColors.core)
                .padding(MFootSpacing.section, 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CrestBadge(club.crest, Modifier.size(40.dp), club.shortName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    club.name,
                    style = MFootType.rowTitle,
                    color = MFootColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formazione.formation.label} · ${state.lega.squadOf(clubId).size} in rosa",
                    style = MFootType.chip,
                    color = MFootColors.ink3,
                )
            }
        }
        Hairline()

        if (!formazione.letto || formazione.clubId != clubId) {
            Spacer(Modifier.height(40.dp))
            Text(
                "Leggo la formazione…",
                style = MFootType.secondary,
                color = MFootColors.ink3,
                modifier = Modifier.fillMaxWidth().padding(MFootSpacing.section),
            )
            return@Column
        }

        Spacer(Modifier.height(MFootSpacing.section))

        // La riga che dice cosa si sta guardando davvero. Senza, una previsione e una
        // scelta si leggono identiche, e si prepara la partita contro un modulo che
        // l'avversario non ha mai scelto.
        Box(Modifier.padding(horizontal = MFootSpacing.section)) {
            Notice(
                if (formazione.suPrevisione) {
                    "Non ha ancora schierato. Questo è l'undici che il server manderebbe " +
                        "in campo al posto suo: può cambiarlo fino al fischio d'inizio."
                } else {
                    "Formazione scelta dal proprietario."
                },
                if (formazione.suPrevisione) MFootColors.ink2 else MFootColors.elite,
            )
        }

        formazione.errore?.let {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = MFootSpacing.section)) {
                Notice(it, MFootColors.gamble)
            }
        }

        Spacer(Modifier.height(MFootSpacing.section))
        Box(Modifier.padding(horizontal = MFootSpacing.section)) {
            Pitch(
                slots = pitchSlots(formazione.formation, formazione.eleven),
                modifier = Modifier.fillMaxWidth().aspectRatio(PITCH_ASPECT),
                showEmptyLabels = false,
            )
        }

        Spacer(Modifier.height(MFootSpacing.section))
        Box(Modifier.padding(horizontal = MFootSpacing.section)) {
            Column(Modifier.fillMaxWidth()) {
                Label("In panchina · ${formazione.bench.size}")
                Spacer(Modifier.height(8.dp))
                if (formazione.bench.isEmpty()) {
                    Text("Nessuno.", style = MFootType.chip, color = MFootColors.ink3)
                } else {
                    formazione.bench.forEach { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(34.dp, 21.dp)
                                    .background(MFootColors.core, MFootShapes.field),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    p.primaryPosition.short,
                                    style = MFootType.label,
                                    color = MFootColors.ink3,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                p.shortName,
                                style = MFootType.chip,
                                color = MFootColors.ink2,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${p.overall}",
                                style = MFootType.value,
                                color = MFootColors.rating(p.overall),
                            )
                        }
                    }
                }
            }
        }

        formazione.tactics?.let { t ->
            Spacer(Modifier.height(MFootSpacing.section))
            Box(Modifier.padding(horizontal = MFootSpacing.section)) {
                Column(Modifier.fillMaxWidth()) {
                    Label("Come gioca")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Etichetta(t.stance.name.lowercase())
                        Etichetta(t.tempo.name.lowercase())
                        Etichetta(t.width.name.lowercase())
                        Etichetta(t.pressing.name.lowercase())
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Etichetta(testo: String) {
    Text(
        testo,
        style = MFootType.chip,
        color = MFootColors.ink2,
        modifier = Modifier
            .background(MFootColors.line, MFootShapes.pill)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
