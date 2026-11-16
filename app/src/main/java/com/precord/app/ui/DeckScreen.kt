package com.precord.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.precord.app.R
import com.precord.app.core.Fmt
import com.precord.app.core.MonitorState
import com.precord.app.core.PrecordSettings
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

private val PRESETS = listOf(30, 60, 300, 900, 1800, 3600)

/** Custom buffer lengths: 10 s steps up to 5 min, then 1 min steps up to 2 h. */
val CUSTOM_STEPS: List<Int> = (10..300 step 10).toList() + (6..120).map { it * 60 }

/**
 * The deck never needs scrolling on a normal phone: the scope and the SAVE key are flexible
 * (weights) and the ring is sized from the available height. Only very short windows
 * (landscape phones, split-screen) fall back to a scrolling column with fixed sizes.
 * Tablets / wide windows (>= 720 dp) use two columns.
 */
@Composable
fun DeckScreen(
    state: MonitorState,
    settings: PrecordSettings,
    onArmToggle: () -> Unit,
    onSave: (lastMs: Long?) -> Unit,
    onBufferSec: (Int) -> Unit,
    onAllowCapture: () -> Unit,
) {
    var showCustom by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val fit = maxHeight >= (if (wide) 330.dp else 430.dp)
        val compact = maxHeight < 620.dp
        val h = maxHeight
        val ring = when {
            !fit -> 150.dp
            wide -> (h * 0.42f).coerceIn(96.dp, 220.dp)
            else -> minOf((h * 0.23f).coerceIn(92.dp, 184.dp), maxWidth * 0.46f)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (wide) {
                Row(Modifier.widthIn(max = 1040.dp).fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f).fillMaxSize().then(if (fit) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(bottom = 12.dp)) {
                        Meters(state, settings, ring, fit, compact, onAllowCapture)
                    }
                    Column(Modifier.weight(1f).fillMaxSize().then(if (fit) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(top = 12.dp, bottom = 12.dp)) {
                        Controls(state, settings, fit, compact, onArmToggle, onSave, onBufferSec) { showCustom = true }
                    }
                }
            } else {
                Column(
                    Modifier.widthIn(max = 600.dp).fillMaxSize()
                        .then(if (fit) Modifier else Modifier.verticalScroll(rememberScrollState()))
                        .padding(horizontal = 16.dp).padding(bottom = 12.dp),
                ) {
                    Meters(state, settings, ring, fit, compact, onAllowCapture)
                    Gap(h = if (compact) 10 else 16)
                    Controls(state, settings, fit, compact, onArmToggle, onSave, onBufferSec) { showCustom = true }
                }
            }
        }
    }
    if (showCustom) CustomBufferDialog(settings.bufferSec, settings, onDismiss = { showCustom = false }) { onBufferSec(it); showCustom = false }
}

@Composable
private fun ColumnScope.Meters(state: MonitorState, settings: PrecordSettings, ringSize: Dp, fit: Boolean, compact: Boolean, onAllowCapture: () -> Unit) {
    val armed = state.running
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LedDot(armed)
        Gap(8)
        Label(stringResource(if (armed) R.string.status_armed else R.string.status_standby), color = if (armed) PC.Ink else PC.Dim)
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Text(
            if (state.cancelling) stringResource(R.string.capture_on) else "${Fmt.rate(settings.sampleRate)} ${settings.format.label}",
            style = mono(11.sp, if (state.cancelling) PC.Ink else PC.Dim),
        )
    }
    state.errorRes?.let {
        Text(stringResource(it, state.errorArg ?: ""), style = body(PC.Ink, 13.sp), modifier = Modifier.padding(top = 8.dp).fillMaxWidth().border(1.dp, PC.Danger).padding(10.dp))
    }
    if (state.needsCapture) {
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().border(1.dp, PC.Dim).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.capture_needed), style = body(PC.Ink, 12.sp), modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            KeyButton(stringResource(R.string.capture_allow), filled = true, onClick = onAllowCapture)
        }
    }
    Gap(h = 10)
    ScopeView(state.scope, armed, if (fit) Modifier.fillMaxWidth().weight(1f).heightIn(min = 36.dp) else Modifier.fillMaxWidth().height(110.dp))
    Gap(h = 8)
    VuMeter(state.rms, state.peak, armed)

    Gap(h = if (compact) 10 else 16)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val windowMs = if (armed) state.windowMs else settings.windowMs
        val frac = if (windowMs > 0) (state.bufferedMs.toFloat() / windowMs).coerceIn(0f, 1f) else 0f
        BufferRing(frac, armed, Fmt.clock(state.bufferedMs), "/ ${Fmt.clock(windowMs)}", Modifier.size(ringSize))
        Gap(20)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
            Readout(stringResource(R.string.fill), "${(frac * 100).toInt()}%")
            Readout(stringResource(R.string.ram), Fmt.bytes(settings.ramBytes))
            if (ringSize >= 130.dp) Readout(stringResource(R.string.format), "${settings.format.label} ${Fmt.rate(settings.sampleRate)}")
        }
    }
}

@Composable
private fun ColumnScope.Controls(
    state: MonitorState,
    settings: PrecordSettings,
    fit: Boolean,
    compact: Boolean,
    onArmToggle: () -> Unit,
    onSave: (Long?) -> Unit,
    onBufferSec: (Int) -> Unit,
    onCustom: () -> Unit,
) {
    val armed = state.running
    var confirmStop by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PRESETS.forEach { sec -> Pill(Fmt.duration(sec), settings.bufferSec == sec) { onBufferSec(sec) } }
        val custom = settings.bufferSec !in PRESETS
        Pill(if (custom) Fmt.duration(settings.bufferSec) else stringResource(R.string.custom), custom, onClick = onCustom)
    }
    Text(
        if (settings.ramBytes > 200_000_000L) stringResource(R.string.ram_heavy) else Fmt.ramLine(settings),
        style = mono(11.sp, PC.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
    )

    Gap(h = if (compact) 10 else 16)
    val canSave = armed && state.bufferedMs > 500 && !state.saving
    SaveKey(
        enabled = canSave,
        busy = state.saving,
        compact = compact,
        caption = if (armed) stringResource(R.string.last_x, Fmt.clock(state.bufferedMs)) else stringResource(R.string.arm_first),
        modifier = if (fit) Modifier.fillMaxWidth().weight(1.15f).heightIn(min = 64.dp, max = 170.dp) else Modifier.fillMaxWidth().height(120.dp),
    ) { onSave(null) }

    Gap(h = if (compact) 8 else 12)
    // one row: quick "save last N" keys + the arm switch
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(30, 60, 300).filter { it < settings.bufferSec }.forEach { sec ->
            KeyButton(Fmt.duration(sec), enabled = canSave) { onSave(sec * 1000L) }
        }
        KeyButton(stringResource(if (armed) R.string.stop_buffer else R.string.arm_buffer), Modifier.weight(1f), filled = !armed, danger = armed) {
            // stopping throws the buffered audio away - ask once there is something to lose
            if (armed && state.bufferedMs > 10_000) confirmStop = true else onArmToggle()
        }
    }
    if (confirmStop) {
        ConfirmDialog(
            stringResource(R.string.confirm_stop_title), stringResource(R.string.confirm_stop_msg, Fmt.clock(state.bufferedMs)),
            stringResource(R.string.stop_buffer), destructive = true, onDismiss = { confirmStop = false },
        ) { confirmStop = false; onArmToggle() }
    }
}

@Composable
private fun Readout(name: String, value: String) {
    Column {
        Label(name, color = PC.Faint)
        Text(value, style = mono(18.sp, PC.Ink))
    }
}

/** Mirrored amplitude trace of the last ~3 s, fed by Rust peak windows. */
@Composable
fun ScopeView(points: FloatArray, active: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.background(PC.Graphite).border(1.dp, PC.Line)) {
        val mid = size.height / 2
        drawLine(PC.Line, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        for (q in listOf(0.25f, 0.75f)) drawLine(PC.Graphite2, Offset(0f, size.height * q), Offset(size.width, size.height * q), 1.dp.toPx())
        val n = points.size
        val step = size.width / n
        val color = if (active) PC.Signal else PC.Faint
        for (i in 0 until n) {
            val a = Math.pow(points[i].coerceIn(0f, 1f).toDouble(), 0.55).toFloat() // lift quiet detail
            val h = max(a * (size.height / 2 - 4.dp.toPx()), 1.dp.toPx())
            val x = step * i + step / 2
            // older samples fade out towards the left edge
            drawLine(color.copy(alpha = 0.25f + 0.75f * i / n), Offset(x, mid - h), Offset(x, mid + h), strokeWidth = (step * 0.62f).coerceAtLeast(1f), cap = StrokeCap.Butt)
        }
    }
}

private fun dbPos(v: Float): Float = ((20f * log10(max(v, 1e-5f)) + 60f) / 60f).coerceIn(0f, 1f)

/** 40-segment RMS meter with peak-hold segment; last segments (> -6 dBFS) glow signal orange. */
@Composable
fun VuMeter(rms: Float, peak: Float, active: Boolean) {
    var hold by remember { mutableFloatStateOf(0f) }
    val peakPos = dbPos(peak)
    LaunchedEffect(peak, rms) { hold = max(peakPos, hold - 0.012f) }
    val rmsPos = dbPos(rms)
    Column {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val n = 40
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            val holdIdx = (hold * n).toInt().coerceIn(0, n - 1)
            for (i in 0 until n) {
                val lit = active && i < (rmsPos * n).toInt()
                val hot = i >= (0.9f * n).toInt()
                val c = when {
                    active && i == holdIdx && hold > 0.02f -> if (hot) PC.Signal else PC.Ink
                    lit -> if (hot) PC.Signal else PC.Ink
                    else -> PC.Line
                }
                drawRect(c, Offset(i * (w + gap), 0f), Size(w, size.height))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("-60", style = mono(10.sp, PC.Faint))
            Text(if (active) stringResource(R.string.vu_live, db(rms), db(peak)) else stringResource(R.string.vu_idle), style = mono(10.sp, PC.Dim))
            Text("0", style = mono(10.sp, PC.Faint))
        }
    }
}

private fun db(v: Float): String = if (v < 1e-5f) "-inf" else String.format(java.util.Locale.US, "%.1f", 20f * log10(v))

/** 72-tick ring showing how much of the circular buffer is filled. */
@Composable
fun BufferRing(fraction: Float, armed: Boolean, big: String, small: String, modifier: Modifier = Modifier) {
    val desc = stringResource(R.string.cd_ring, big, small.removePrefix("/ "))
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val ticks = 72
            val r = size.minDimension / 2
            val inner = r - 16.dp.toPx()
            val c = Offset(size.width / 2, size.height / 2)
            val lit = (fraction * ticks).toInt()
            for (i in 0 until ticks) {
                val a = (-90.0 + 360.0 * i / ticks) * Math.PI / 180.0
                val major = i % 6 == 0
                val r0 = if (major) inner - 4.dp.toPx() else inner
                val col = when {
                    armed && i == lit && lit < ticks -> PC.Ink // write head
                    i < lit -> if (armed) PC.Signal else PC.Dim
                    else -> PC.Line
                }
                drawLine(
                    col,
                    Offset(c.x + (r0 * cos(a)).toFloat(), c.y + (r0 * sin(a)).toFloat()),
                    Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat()),
                    strokeWidth = (if (major) 3 else 2).dp.toPx(),
                    cap = StrokeCap.Butt,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(big, style = mono(30.sp, PC.Ink, FontWeight.Bold))
            Text(small, style = mono(13.sp, PC.Dim))
        }
    }
}

/** The big orange SAVE REPLAY key: flat base plate + cap that physically sinks when pressed. */
@Composable
fun SaveKey(enabled: Boolean, busy: Boolean, compact: Boolean, caption: String, modifier: Modifier = Modifier, onSave: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val travel by animateDpAsState(if (pressed && enabled) 7.dp else 0.dp, label = "keyTravel")
    val view = LocalView.current
    val title = stringResource(if (busy) R.string.saving else R.string.save_replay)
    Box(modifier) {
        Box(Modifier.fillMaxSize().padding(top = 7.dp).background(if (enabled) PC.SignalDeep else PC.Graphite))
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = 7.dp)
                .offset(y = travel)
                .background(if (enabled) PC.Signal else PC.Graphite2)
                .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onSave()
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = mono(if (compact) 24.sp else 30.sp, if (enabled) PC.Black else PC.Faint, FontWeight.Bold), textAlign = TextAlign.Center, maxLines = 1)
                Gap(h = if (compact) 2 else 6)
                Text(caption, style = mono(if (compact) 11.sp else 13.sp, if (enabled) PC.Black else PC.Faint), maxLines = 1)
            }
        }
    }
}

@Composable
private fun CustomBufferDialog(current: Int, settings: PrecordSettings, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var idx by remember { mutableStateOf(CUSTOM_STEPS.indexOfFirst { it >= current }.let { if (it < 0) CUSTOM_STEPS.lastIndex else it }) }
    val sec = CUSTOM_STEPS[idx]
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(PC.Graphite).border(1.dp, PC.Line).padding(20.dp)) {
            Label(stringResource(R.string.custom_buffer_title))
            Gap(h = 12)
            Text(Fmt.duration(sec), style = mono(40.sp, PC.Ink, FontWeight.Bold))
            Text(Fmt.ramLine(settings.copy(bufferSec = sec)), style = mono(12.sp, PC.Dim), modifier = Modifier.padding(top = 4.dp))
            Gap(h = 12)
            TickSlider(idx, 0, CUSTOM_STEPS.lastIndex) { idx = it }
            Gap(h = 16)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton(stringResource(R.string.cancel), Modifier.weight(1f), onClick = onDismiss)
                KeyButton(stringResource(R.string.apply), Modifier.weight(1f), filled = true) { onApply(sec) }
            }
        }
    }
}
