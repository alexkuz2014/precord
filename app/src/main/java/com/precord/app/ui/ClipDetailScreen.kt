package com.precord.app.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.precord.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.precord.app.core.Clip
import com.precord.app.core.ClipEditor
import com.precord.app.core.ClipExporter
import com.precord.app.core.Fmt
import com.precord.app.core.TranscribeException
import com.precord.app.core.Transcriber
import androidx.compose.foundation.text.selection.SelectionContainer
import com.precord.app.precord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private const val BUCKETS = 320

@Composable
fun ClipDetailScreen(clip: Clip, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.precord.clips
    val scope = rememberCoroutineScope()
    val duration = clip.durationMs.coerceAtLeast(1)

    var title by remember(clip.file.name) { mutableStateOf(clip.title) }
    var tags by remember(clip.file.name) { mutableStateOf(clip.tags.joinToString(", ")) }
    var start by remember(clip.file.name, clip.sizeBytes) { mutableFloatStateOf(0f) }
    var end by remember(clip.file.name, clip.sizeBytes) { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var playFrac by remember { mutableFloatStateOf(-1f) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val peaks by produceState(FloatArray(BUCKETS), clip.file.name, clip.sizeBytes) {
        value = withContext(Dispatchers.IO) { ClipEditor.peaks(clip.file, BUCKETS) }
    }

    val player = remember(clip.file.name, clip.sizeBytes) {
        runCatching { MediaPlayer().apply { setDataSource(clip.file.path); prepare() } }.getOrNull()
    }
    DisposableEffect(player) { onDispose { runCatching { player?.release() } } }

    fun startMs() = (start * duration).toLong()
    fun endMs() = (end * duration).toLong()

    LaunchedEffect(playing, player) {
        val p = player ?: return@LaunchedEffect
        if (!playing) { runCatching { if (p.isPlaying) p.pause() }; return@LaunchedEffect }
        if (p.currentPosition < startMs() || p.currentPosition >= endMs() - 30) p.seekTo(startMs().toInt())
        p.start()
        while (playing) {
            val pos = p.currentPosition.toLong()
            playFrac = pos.toFloat() / duration
            if (pos >= endMs() || !p.isPlaying) {
                p.pause(); p.seekTo(startMs().toInt()); playing = false; playFrac = -1f
            }
            delay(25)
        }
    }

    fun commit() {
        val list = tags.split(',', ' ', '#').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (title.trim() != clip.title || list != clip.tags) repo.updateMeta(clip.file, title.ifBlank { clip.file.nameWithoutExtension }, list)
    }
    val commitNow by rememberUpdatedState(::commit)
    DisposableEffect(Unit) { onDispose { commitNow() } }
    BackHandler { commit(); onClose() }

    fun trim(replace: Boolean) {
        if (busy) return
        busy = true
        playing = false
        scope.launch {
            val s = startMs(); val e = endMs()
            val ok = withContext(Dispatchers.IO) { repo.trimClip(clip, s, e, replace) }
            busy = false
            Toast.makeText(ctx, ctx.getString(if (ok) (if (replace) R.string.toast_trimmed else R.string.toast_trim_copy) else R.string.toast_trim_failed), Toast.LENGTH_SHORT).show()
            if (ok && replace) onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(PC.Black), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            KeyButton(stringResource(R.string.back)) { commit(); onClose() }
            Text("${clip.format.label}  ${Fmt.bytes(clip.sizeBytes)}", style = mono(12.sp, PC.Dim), modifier = Modifier.padding(top = 14.dp))
        }
        Gap(h = 20)
        Label(stringResource(R.string.title))
        BasicTextField(
            title, { title = it },
            textStyle = body(PC.Ink, 20.sp, androidx.compose.ui.text.font.FontWeight.SemiBold),
            cursorBrush = SolidColor(PC.Ink), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Hairline()
        Gap(h = 12)
        Label(stringResource(R.string.tags))
        BasicTextField(
            tags, { tags = it },
            textStyle = mono(14.sp, PC.Ink), cursorBrush = SolidColor(PC.Ink), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Hairline()
        Gap(h = 4)
        Text(clip.file.name, style = mono(11.sp, PC.Faint), modifier = Modifier.padding(top = 6.dp))

        Gap(h = 24)
        TrimWaveform(
            peaks, start, end, playFrac, Modifier.fillMaxWidth().height(140.dp),
            onSeek = { f ->
                val t = f.coerceIn(start, end)
                playFrac = t
                runCatching { player?.seekTo((t * duration).toLong().toInt()) }
            },
        ) { s, e -> start = s; end = e; playing = false; playFrac = -1f }
        Text(stringResource(R.string.seek_hint), style = body(PC.Faint, 12.sp), modifier = Modifier.padding(top = 6.dp))
        Gap(h = 8)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Label(stringResource(R.string.start), color = PC.Faint); Text(Fmt.clockTenths(startMs()), style = mono(18.sp)) }
            Column { Label(stringResource(R.string.length), color = PC.Faint); Text(Fmt.clockTenths(endMs() - startMs()), style = mono(18.sp)) }
            Column { Label(stringResource(R.string.end), color = PC.Faint); Text(Fmt.clockTenths(endMs()), style = mono(18.sp)) }
        }

        Gap(h = 16)
        KeyButton(stringResource(if (playing) R.string.pause else R.string.play_selection), Modifier.fillMaxWidth(), enabled = player != null, filled = true) { playing = !playing }
        Gap(h = 8)
        val trimmed = start > 0.002f || end < 0.998f
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton(stringResource(R.string.trim_new), Modifier.weight(1f), enabled = trimmed && !busy) { trim(false) }
            KeyButton(stringResource(R.string.trim_replace), Modifier.weight(1f), enabled = trimmed && !busy) { trim(true) }
        }

        Gap(h = 24)
        Hairline()
        Gap(h = 16)
        Label(stringResource(R.string.transcript))
        Gap(h = 8)
        val supported = remember { Transcriber.isSupported(ctx) }
        when {
            transcribing != null -> Text(stringResource(R.string.transcribing, transcribing!!.first, transcribing!!.second), style = mono(13.sp, PC.Dim))
            clip.transcript.isNotBlank() -> {
                SelectionContainer { Text(clip.transcript, style = body(PC.Ink, 15.sp)) }
                Gap(h = 8)
                KeyButton(stringResource(R.string.transcript_copy)) {
                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText(clip.title, clip.transcript))
                    Toast.makeText(ctx, R.string.transcript_copied, Toast.LENGTH_SHORT).show()
                }
            }
            !supported -> Text(stringResource(R.string.transcript_na), style = body(PC.Dim, 13.sp))
            else -> KeyButton(stringResource(R.string.transcribe), Modifier.fillMaxWidth()) {
                scope.launch {
                    transcribing = 0 to 1
                    try {
                        val text = Transcriber.transcribe(ctx, clip.file) { d, t -> transcribing = d to t }
                        repo.setTranscript(clip.file, text.ifBlank { ctx.getString(R.string.transcript_empty) })
                    } catch (e: TranscribeException) {
                        Toast.makeText(ctx, if (e.languagePackMissing) R.string.transcript_pack else R.string.transcript_fail, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, R.string.transcript_fail, Toast.LENGTH_LONG).show()
                    } finally {
                        transcribing = null
                    }
                }
            }
        }
        Text(stringResource(R.string.transcript_hint), style = body(PC.Faint, 12.sp), modifier = Modifier.padding(top = 6.dp))

        Gap(h = 24)
        Hairline()
        Gap(h = 16)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton(stringResource(R.string.share), Modifier.weight(1f)) { ctx.startActivity(ClipExporter.shareIntent(ctx, clip.file)) }
            KeyButton(stringResource(R.string.export), Modifier.weight(1f)) {
                scope.launch {
                    val uri = withContext(Dispatchers.IO) { ClipExporter.exportToRecordings(ctx, clip.file) }
                    Toast.makeText(ctx, ctx.getString(if (uri != null) R.string.toast_exported else R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
            KeyButton(stringResource(if (confirmDelete) R.string.sure else R.string.delete), Modifier.weight(1f)) {
                if (!confirmDelete) { confirmDelete = true } else { playing = false; repo.delete(clip.file); onClose() }
            }
        }
        if (confirmDelete) LaunchedEffect(Unit) { delay(3000); confirmDelete = false }
    }
    }
}

/** Waveform with two draggable trim handles. Fractions are 0..1 of the clip. */
@Composable
fun TrimWaveform(peaks: FloatArray, start: Float, end: Float, playhead: Float, modifier: Modifier = Modifier, onSeek: (Float) -> Unit, onTrim: (Float, Float) -> Unit) {
    val tick = rememberTick()
    val startNow by rememberUpdatedState(start)
    val endNow by rememberUpdatedState(end)
    val trimNow by rememberUpdatedState(onTrim)
    val seekNow by rememberUpdatedState(onSeek)
    var widthPx by remember { mutableFloatStateOf(1f) }
    Canvas(
        modifier
            .background(PC.Graphite)
            .border(1.dp, PC.Line)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { o -> seekNow((o.x / widthPx).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                var which = 0 // 1 = start handle, 2 = end handle
                detectDragGestures(
                    onDragStart = { o ->
                        val sx = startNow * widthPx
                        val ex = endNow * widthPx
                        which = if (kotlin.math.abs(o.x - sx) <= kotlin.math.abs(o.x - ex)) 1 else 2
                        tick()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / widthPx).coerceIn(0f, 1f)
                        if (which == 1) trimNow(min(f, endNow - 0.01f).coerceAtLeast(0f), endNow)
                        else trimNow(startNow, max(f, startNow + 0.01f).coerceAtMost(1f))
                    },
                )
            },
    ) {
        val n = peaks.size
        val bw = size.width / n
        val mid = size.height / 2
        for (i in 0 until n) {
            val f = (i + 0.5f) / n
            val inside = f in start..end
            val h = max(Math.pow(peaks[i].toDouble(), 0.6).toFloat() * (mid - 6.dp.toPx()), 1.dp.toPx())
            val x = i * bw + bw / 2
            drawLine(if (inside) PC.Ink else PC.Faint, Offset(x, mid - h), Offset(x, mid + h), strokeWidth = max(bw * 0.7f, 1f))
        }
        // dim the trimmed-away regions
        drawRect(PC.Black.copy(alpha = 0.55f), Offset.Zero, Size(start * size.width, size.height))
        drawRect(PC.Black.copy(alpha = 0.55f), Offset(end * size.width, 0f), Size((1f - end) * size.width, size.height))
        for (f in listOf(start, end)) {
            val x = f * size.width
            drawLine(PC.Ink, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            drawRect(PC.Ink, Offset(x - 5.dp.toPx(), 0f), Size(10.dp.toPx(), 8.dp.toPx()))
            drawRect(PC.Ink, Offset(x - 5.dp.toPx(), size.height - 8.dp.toPx()), Size(10.dp.toPx(), 8.dp.toPx()))
        }
        if (playhead in 0f..1f) {
            val x = playhead * size.width
            drawLine(PC.Ink, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
        }
    }
}
