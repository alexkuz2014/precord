package com.precord.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.precord.app.core.ClipFormat
import com.precord.app.core.Fmt
import com.precord.app.core.PrecordSettings
import com.precord.app.service.VolumeComboService

private val RATES = listOf(16_000, 44_100, 48_000)

fun isVolumeServiceEnabled(context: Context): Boolean {
    val me = ComponentName(context, VolumeComboService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { it.equals(me, ignoreCase = true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: PrecordSettings, onChange: ((PrecordSettings) -> PrecordSettings) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RectangleShape,
        containerColor = PC.Graphite,
        contentColor = PC.Ink,
        scrimColor = PC.Black.copy(alpha = 0.7f),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SETTINGS", style = label(PC.Ink, 13.sp))
                Text("CLOSE", style = label(PC.Dim, 12.sp), modifier = Modifier.clickable { onDismiss() })
            }

            SectionTitle("Sample rate")
            Segmented(RATES.map { Fmt.rate(it) }, RATES.indexOf(settings.sampleRate).coerceAtLeast(0)) { i -> onChange { it.copy(sampleRate = RATES[i]) } }
            Text(
                when (settings.sampleRate) {
                    16_000 -> "Voice / tiny files. 32 KB/s of RAM."
                    44_100 -> "CD rate. 88 KB/s."
                    else -> "Studio / video rate. 96 KB/s."
                },
                style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 6.dp),
            )

            SectionTitle("Save format")
            val formats = ClipFormat.entries
            Segmented(
                listOf("WAV", "M4A (AAC)", "OPUS"), formats.indexOf(settings.format),
                enabled = listOf(true, true, settings.sampleRate != 44_100),
            ) { i -> onChange { it.copy(format = formats[i]) } }
            Text("WAV is lossless and written straight from Rust. M4A/Opus use the phone's encoder (Opus: 16/48 kHz).", style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 6.dp))

            SectionTitle("Processing")
            ToggleRow("Normalize on save", "Peak-normalise to -1 dBFS (up to +30 dB) so quiet, distant voices come up.", settings.normalize) { v -> onChange { it.copy(normalize = v) } }
            Hairline()
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Input gain", style = body(size = 15.sp, weight = androidx.compose.ui.text.font.FontWeight.Medium))
                Text(String.format(java.util.Locale.US, "%+d dB", settings.gainDb), style = mono(14.sp))
            }
            TickSlider(settings.gainDb, -12, 24) { v -> onChange { it.copy(gainDb = v) } }
            Hairline()
            ToggleRow("Noise gate", "Mutes hiss between sounds. Applied live, before the buffer.", settings.gateEnabled) { v -> onChange { it.copy(gateEnabled = v) } }
            if (settings.gateEnabled) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label("Threshold")
                    Text("${settings.gateDb} dBFS", style = mono(14.sp))
                }
                TickSlider(settings.gateDb, -70, -20) { v -> onChange { it.copy(gateDb = v) } }
            }

            SectionTitle("Triggers")
            ToggleRow("Shake to save", "Three hard shakes saves a replay, even screen-off. Uses the accelerometer + a partial wake lock.", settings.shakeToSave) { v -> onChange { it.copy(shakeToSave = v) } }
            Hairline()
            ToggleRow("Volume Up + Down combo", "Press both volume keys together to save with the screen off. Needs the Precord accessibility service (key events only).", settings.volumeCombo) { v -> onChange { it.copy(volumeCombo = v) } }
            if (settings.volumeCombo) {
                val on = isVolumeServiceEnabled(ctx)
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyButton(if (on) "Service enabled" else "Enable service", Modifier.weight(1f), filled = !on) {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            Hairline()
            Text("Also available: the Quick Settings tile (add 'Precord' from the tile editor) and the SAVE REPLAY button in the notification.", style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 10.dp))

            SectionTitle("Storage")
            ToggleRow("Auto-export to Recordings/Precord", "Every saved clip is also copied to the public Recordings folder.", settings.autoExport) { v -> onChange { it.copy(autoExport = v) } }

            SectionTitle("Memory")
            Text(Fmt.ramLine(settings), style = mono(13.sp))
        }
    }
}
