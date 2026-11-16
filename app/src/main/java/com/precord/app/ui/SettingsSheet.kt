package com.precord.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.precord.app.BuildConfig
import com.precord.app.R
import com.precord.app.core.ClipFormat
import com.precord.app.core.Fmt
import com.precord.app.core.PrecordSettings

private val RATES = listOf(16_000, 44_100, 48_000)
const val PRIVACY_URL = "https://github.com/mantisfett/precord/blob/main/docs/PRIVACY_POLICY.md"

/** The accessibility service only exists in the "full" flavor, so it is addressed by name. */
fun isVolumeServiceEnabled(context: Context): Boolean {
    val me = ComponentName(context.packageName, "com.precord.app.service.VolumeComboService").flattenToString()
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
        modifier = Modifier.widthIn(max = 720.dp),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings), style = label(PC.Ink, 13.sp))
                Text(stringResource(R.string.close), style = label(PC.Dim, 12.sp), modifier = Modifier.clickable { onDismiss() }.padding(12.dp))
            }

            SectionTitle(stringResource(R.string.sample_rate))
            Segmented(RATES.map { Fmt.rate(it) }, RATES.indexOf(settings.sampleRate).coerceAtLeast(0)) { i -> onChange { it.copy(sampleRate = RATES[i]) } }
            Text(
                stringResource(when (settings.sampleRate) { 16_000 -> R.string.rate_16_hint; 44_100 -> R.string.rate_44_hint; else -> R.string.rate_48_hint }),
                style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 6.dp),
            )

            SectionTitle(stringResource(R.string.save_format))
            val formats = ClipFormat.entries
            Segmented(
                listOf("WAV", "M4A (AAC)", "OPUS"), formats.indexOf(settings.format),
                enabled = listOf(true, true, settings.sampleRate != 44_100),
            ) { i -> onChange { it.copy(format = formats[i]) } }
            Text(stringResource(R.string.format_hint), style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 6.dp))

            SectionTitle(stringResource(R.string.processing))
            ToggleRow(stringResource(R.string.normalize), stringResource(R.string.normalize_sub), settings.normalize) { v -> onChange { it.copy(normalize = v) } }
            Hairline()
            ToggleRow(stringResource(R.string.raw_mic), stringResource(R.string.raw_mic_sub), settings.rawMic) { v -> onChange { it.copy(rawMic = v) } }
            Hairline()
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.input_gain), style = body(size = 15.sp, weight = FontWeight.Medium))
                Text(String.format(java.util.Locale.US, "%+d dB", settings.gainDb), style = mono(14.sp))
            }
            TickSlider(settings.gainDb, -12, 24) { v -> onChange { it.copy(gainDb = v) } }
            Hairline()
            ToggleRow(stringResource(R.string.noise_gate), stringResource(R.string.noise_gate_sub), settings.gateEnabled) { v -> onChange { it.copy(gateEnabled = v) } }
            if (settings.gateEnabled) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label(stringResource(R.string.threshold))
                    Text("${settings.gateDb} dBFS", style = mono(14.sp))
                }
                TickSlider(settings.gateDb, -70, -20) { v -> onChange { it.copy(gateDb = v) } }
            }

            SectionTitle(stringResource(R.string.triggers))
            ToggleRow(stringResource(R.string.shake), stringResource(R.string.shake_sub), settings.shakeToSave) { v -> onChange { it.copy(shakeToSave = v) } }
            if (BuildConfig.VOLUME_COMBO) {
                Hairline()
                ToggleRow(stringResource(R.string.volume_combo), stringResource(R.string.volume_combo_sub), settings.volumeCombo) { v -> onChange { it.copy(volumeCombo = v) } }
                if (settings.volumeCombo) {
                    val on = isVolumeServiceEnabled(ctx)
                    KeyButton(stringResource(if (on) R.string.service_enabled else R.string.enable_service), Modifier.fillMaxWidth().padding(bottom = 8.dp), filled = !on) {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            Hairline()
            Text(stringResource(R.string.triggers_hint), style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 10.dp))

            SectionTitle(stringResource(R.string.storage))
            ToggleRow(stringResource(R.string.auto_export), stringResource(R.string.auto_export_sub), settings.autoExport) { v -> onChange { it.copy(autoExport = v) } }

            SectionTitle(stringResource(R.string.memory))
            Text(Fmt.ramLine(settings), style = mono(13.sp))

            SectionTitle(stringResource(R.string.about))
            Text("Precord ${BuildConfig.VERSION_NAME}", style = mono(13.sp))
            Text(stringResource(R.string.language_hint), style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 6.dp))
            KeyButton(stringResource(R.string.privacy_policy), Modifier.fillMaxWidth().padding(top = 12.dp)) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
