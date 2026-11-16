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
import com.precord.app.core.MicMode
import com.precord.app.core.PackState
import com.precord.app.core.PrecordSettings
import com.precord.app.core.Transcriber
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

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
fun SettingsSheet(
    settings: PrecordSettings,
    running: Boolean,
    onChange: ((PrecordSettings) -> PrecordSettings) -> Unit,
    onAllowCapture: () -> Unit,
    onDismiss: () -> Unit,
) {
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

            // ---------------------------------------------------------------- capture
            SectionTitle(stringResource(R.string.sec_capture))
            Label(stringResource(R.string.sample_rate), color = PC.Faint)
            Gap(h = 6)
            Segmented(RATES.map { Fmt.rate(it) }, RATES.indexOf(settings.sampleRate).coerceAtLeast(0)) { i -> onChange { it.copy(sampleRate = RATES[i]) } }
            Text(
                stringResource(when (settings.sampleRate) { 16_000 -> R.string.rate_16_hint; 44_100 -> R.string.rate_44_hint; else -> R.string.rate_48_hint }),
                style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 6.dp),
            )
            Gap(h = 16)
            Label(stringResource(R.string.mic_mode), color = PC.Faint)
            Gap(h = 6)
            // One choice instead of independent switches: these modes cannot be combined.
            Column(Modifier.fillMaxWidth().border(1.dp, PC.Line)) {
                listOf(
                    Triple(MicMode.STANDARD, R.string.mic_standard, R.string.mic_standard_sub),
                    Triple(MicMode.DENOISE, R.string.mic_denoise, R.string.mic_denoise_sub),
                    Triple(MicMode.PLAYBACK, R.string.mic_playback, R.string.mic_playback_sub),
                    Triple(MicMode.RAW, R.string.mic_raw, R.string.mic_raw_sub),
                ).forEachIndexed { n, (mode, title, sub) ->
                    if (n > 0) Hairline()
                    ChoiceRow(stringResource(title), stringResource(sub), settings.micMode == mode) {
                        onChange { it.copy(micMode = mode) }
                        if (mode == MicMode.PLAYBACK && running) onAllowCapture()
                    }
                }
            }
            Text(stringResource(R.string.mode_restart), style = body(PC.Faint, 12.sp), modifier = Modifier.padding(top = 6.dp))

            // ---------------------------------------------------------------- sound
            SectionTitle(stringResource(R.string.sec_sound))
            ToggleRow(stringResource(R.string.voice_boost), stringResource(R.string.voice_boost_sub), settings.voiceBoost) { v -> onChange { it.copy(voiceBoost = v) } }
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

            // ---------------------------------------------------------------- saving
            SectionTitle(stringResource(R.string.sec_saving))
            val formats = ClipFormat.entries
            Segmented(
                listOf("WAV", "M4A (AAC)", "OPUS"), formats.indexOf(settings.format),
                enabled = listOf(true, true, settings.sampleRate != 44_100),
            ) { i -> onChange { it.copy(format = formats[i]) } }
            Text(
                stringResource(R.string.format_hint) + if (settings.sampleRate == 44_100) " " + stringResource(R.string.opus_rate_note) else "",
                style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 6.dp),
            )
            ToggleRow(stringResource(R.string.normalize), stringResource(R.string.normalize_sub), settings.normalize) { v -> onChange { it.copy(normalize = v) } }
            Hairline()
            ToggleRow(stringResource(R.string.auto_export), stringResource(R.string.auto_export_sub), settings.autoExport) { v -> onChange { it.copy(autoExport = v) } }

            // ---------------------------------------------------------------- triggers
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
            Text(stringResource(R.string.triggers_hint) + " " + stringResource(R.string.chip_hint), style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 10.dp))

            // ---------------------------------------------------------------- transcription
            SectionTitle(stringResource(R.string.sec_transcription))
            TranscriptionSettings(settings, onChange)

            // ---------------------------------------------------------------- keep alive
            SectionTitle(stringResource(R.string.keepalive))
            ToggleRow(stringResource(R.string.auto_rearm), stringResource(R.string.auto_rearm_sub), settings.autoRearm) { v -> onChange { it.copy(autoRearm = v) } }
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            KeyButton(stringResource(if (unrestricted) R.string.battery_ok else R.string.battery_restricted), Modifier.fillMaxWidth().padding(top = 4.dp), filled = !unrestricted) {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Text(stringResource(R.string.keepalive_hint), style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 8.dp))

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

/** Radio-style row: square marker, title, explanation. */
@Composable
private fun ChoiceRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    val tick = rememberTick()
    Row(
        Modifier.fillMaxWidth().background(if (selected) PC.Graphite2 else PC.Graphite)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton) { tick(); onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).border(1.dp, if (selected) PC.Ink else PC.Dim).padding(4.dp).background(if (selected) PC.Ink else PC.Graphite))
        Gap(12)
        Column(Modifier.weight(1f)) {
            Text(title, style = body(size = 15.sp, weight = FontWeight.Medium))
            Text(sub, style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Language choice, offline-pack status with a download button, and the opt-in online fallback. */
@Composable
private fun TranscriptionSettings(settings: PrecordSettings, onChange: ((PrecordSettings) -> PrecordSettings) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tag = Transcriber.tag(settings)
    var refresh by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<Int?>(null) }
    val pack by produceState(PackState.UNKNOWN, tag, refresh) { value = Transcriber.packState(ctx, tag) }

    Label(stringResource(R.string.stt_language), color = PC.Faint)
    Gap(h = 6)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Transcriber.LANGUAGES.forEach { l ->
            val name = if (l.isEmpty()) stringResource(R.string.stt_system)
            else java.util.Locale.forLanguageTag(l).let { it.getDisplayLanguage(it) }.replaceFirstChar { c -> c.uppercase() }
            Pill(name, settings.sttLang == l) { onChange { it.copy(sttLang = l) } }
        }
    }
    Gap(h = 10)
    when {
        progress != null -> Text(stringResource(R.string.stt_pack_progress, progress!!), style = mono(13.sp))
        pack == PackState.INSTALLED -> Text(stringResource(R.string.stt_pack_installed), style = mono(13.sp))
        pack == PackState.DOWNLOADING -> Text(stringResource(R.string.stt_pack_downloading), style = mono(13.sp, PC.Dim))
        pack == PackState.UNSUPPORTED -> Text(stringResource(R.string.stt_pack_unsupported), style = body(PC.Dim, 13.sp))
        else -> KeyButton(
            stringResource(if (pack == PackState.DOWNLOADABLE) R.string.stt_pack_download else R.string.stt_pack_unknown),
            Modifier.fillMaxWidth(), filled = pack == PackState.DOWNLOADABLE,
        ) {
            scope.launch {
                progress = 0
                val ok = Transcriber.downloadPack(ctx, tag) { progress = it }
                progress = null
                refresh++
                if (!ok) Toast.makeText(ctx, R.string.stt_pack_scheduled, Toast.LENGTH_LONG).show()
            }
        }
    }
    ToggleRow(stringResource(R.string.stt_online), stringResource(R.string.stt_online_sub), settings.sttOnline) { v -> onChange { it.copy(sttOnline = v) } }
    Text(stringResource(R.string.transcript_hint), style = body(PC.Faint, 12.sp))
}
