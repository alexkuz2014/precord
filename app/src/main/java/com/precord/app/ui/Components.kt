package com.precord.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Haptic helpers bound to the current view. */
@Composable
fun rememberTick(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); Unit } }
}

@Composable
fun rememberClick(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); Unit } }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = PC.Dim) {
    Text(text.uppercase(), style = label(color), modifier = modifier)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(PC.Line))
}

@Composable
fun LedDot(on: Boolean, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(if (on) PC.Signal else PC.Faint))
}

/** Rounded, tactile selector chip: inverted (off-white) when selected. */
@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val tick = rememberTick()
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) PC.Ink else PC.Black)
            .border(1.dp, if (selected) PC.Ink else PC.Line, RoundedCornerShape(50))
            .clickable(enabled = enabled) { tick(); onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = mono(13.sp, if (selected) PC.Black else if (enabled) PC.Ink else PC.Faint))
    }
}

/** Flat rectangular button. [filled] = inverted. */
@Composable
fun KeyButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    val click = rememberClick()
    val fg = when {
        !enabled -> PC.Faint
        filled -> PC.Black
        else -> PC.Ink
    }
    Box(
        modifier
            .height(44.dp)
            .background(if (filled && enabled) PC.Ink else PC.Black)
            .border(1.dp, if (enabled) (if (filled) PC.Ink else PC.Dim) else PC.Line)
            .clickable(enabled = enabled) { click(); onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = label(fg, 12.sp))
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, enabled: List<Boolean>? = null, onSelect: (Int) -> Unit) {
    val tick = rememberTick()
    Row(modifier.fillMaxWidth().border(1.dp, PC.Line)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val ok = enabled?.getOrNull(i) ?: true
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(if (on) PC.Ink else PC.Black)
                    .clickable(enabled = ok) { tick(); onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = mono(13.sp, if (on) PC.Black else if (ok) PC.Ink else PC.Faint))
            }
        }
    }
}

/** Square sliding switch. Off = outline, on = off-white. */
@Composable
fun SquareToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val click = rememberClick()
    Box(
        Modifier
            .size(width = 46.dp, height = 26.dp)
            .background(if (checked) PC.Ink else PC.Black)
            .border(1.dp, if (checked) PC.Ink else PC.Dim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { click(); onChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = if (checked) 22.dp else 2.dp, y = 2.dp)
                .size(20.dp)
                .background(if (checked) PC.Black else PC.Dim),
        )
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = body(size = 15.sp, weight = androidx.compose.ui.text.font.FontWeight.Medium))
            if (subtitle != null) Text(subtitle, style = body(PC.Dim, 12.sp), modifier = Modifier.padding(top = 2.dp))
        }
        SquareToggle(checked, onChange)
    }
}

/**
 * Discrete scrubber with a tick mark per step. Fires a haptic tick each time the value changes
 * while dragging, like a detented hardware dial.
 */
@Composable
fun TickSlider(value: Int, min: Int, max: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    val tick = rememberTick()
    val current by rememberUpdatedState(value)
    val onChangeNow by rememberUpdatedState(onChange)
    var widthPx by remember { mutableIntStateOf(1) }
    fun idxAt(x: Float): Int {
        val f = (x / widthPx).coerceIn(0f, 1f)
        return min + (f * (max - min)).roundToInt()
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(min, max) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun apply(x: Float) {
                        val i = idxAt(x)
                        if (i != current) { tick(); onChangeNow(i) }
                    }
                    apply(down.position.x)
                    drag(down.id) { change -> apply(change.position.x); change.consume() }
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val steps = (max - min).coerceAtLeast(1)
            val drawn = steps.coerceAtMost(60)
            for (i in 0..drawn) {
                val x = size.width * i / drawn
                val major = i % 5 == 0
                drawLine(PC.Faint, Offset(x, size.height * (if (major) 0.30f else 0.42f)), Offset(x, size.height * 0.70f), strokeWidth = 1.dp.toPx())
            }
            val tx = size.width * (value - min).toFloat() / steps
            drawLine(PC.Ink, Offset(tx, 2.dp.toPx()), Offset(tx, size.height - 2.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Square)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Label(text)
    }
}

@Composable
fun Gap(w: Int = 0, h: Int = 0) = androidx.compose.foundation.layout.Spacer(Modifier.width(w.dp).height(h.dp))
