package com.precord.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.precord.app.R

/** Industrial palette. Signal orange is reserved for "recording is live" and the SAVE trigger. */
object PC {
    val Black = Color(0xFF000000)
    val Graphite = Color(0xFF121212)
    val Graphite2 = Color(0xFF1C1C1C)
    val Line = Color(0xFF2E2E2E)
    val Faint = Color(0xFF515151)
    val Dim = Color(0xFF8E8E8E)
    val Ink = Color(0xFFF2F2F2)
    val Signal = Color(0xFFFF3B00)
    val SignalDeep = Color(0xFF8F2100)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** Tabular, monospaced numerals for every timer / counter / size. */
fun mono(size: TextUnit, color: Color = PC.Ink, weight: FontWeight = FontWeight.Medium) =
    TextStyle(fontFamily = Mono, fontSize = size, color = color, fontWeight = weight, fontFeatureSettings = "tnum", lineHeight = size * 1.2f)

/** Small spaced-out uppercase grotesque label. */
fun label(color: Color = PC.Dim, size: TextUnit = 11.sp) =
    TextStyle(fontFamily = Inter, fontSize = size, color = color, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp)

fun body(color: Color = PC.Ink, size: TextUnit = 14.sp, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Inter, fontSize = size, color = color, fontWeight = weight)

private val scheme = darkColorScheme(
    primary = PC.Ink,
    onPrimary = PC.Black,
    secondary = PC.Signal,
    onSecondary = PC.Black,
    background = PC.Black,
    onBackground = PC.Ink,
    surface = PC.Graphite,
    onSurface = PC.Ink,
    surfaceVariant = PC.Graphite2,
    onSurfaceVariant = PC.Dim,
    outline = PC.Line,
    outlineVariant = PC.Line,
    surfaceContainer = PC.Graphite,
    surfaceContainerHigh = PC.Graphite,
    surfaceContainerHighest = PC.Graphite2,
    scrim = PC.Black,
)

private val typography = Typography().run {
    fun TextStyle.inter() = copy(fontFamily = Inter)
    copy(
        bodyLarge = bodyLarge.inter(), bodyMedium = bodyMedium.inter(), bodySmall = bodySmall.inter(),
        titleLarge = titleLarge.inter(), titleMedium = titleMedium.inter(), titleSmall = titleSmall.inter(),
        labelLarge = labelLarge.inter(), labelMedium = labelMedium.inter(), labelSmall = labelSmall.inter(),
    )
}

@Composable
fun PrecordTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val ignoreSystem = isSystemInDarkTheme() // Precord is always black.
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(2.dp), medium = RoundedCornerShape(2.dp),
            large = RoundedCornerShape(2.dp), extraLarge = RoundedCornerShape(2.dp),
        ),
        content = content,
    )
}
