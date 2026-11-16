package com.precord.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.precord.app.core.Clip
import com.precord.app.core.Fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(clips: List<Clip>, onOpen: (Clip) -> Unit) {
    if (clips.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NO CLIPS", style = mono(20.sp, PC.Dim))
                Gap(h = 8)
                Text("Arm the buffer on the deck, then hit SAVE REPLAY.", style = body(PC.Faint, 13.sp))
            }
        }
        return
    }
    val total = clips.sumOf { it.sizeBytes }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Label("${clips.size} clips")
                Text(Fmt.bytes(total), style = mono(11.sp, PC.Dim))
            }
            Hairline()
        }
        items(clips, key = { it.file.name }) { clip ->
            ClipRow(clip) { onOpen(clip) }
            Hairline()
        }
    }
}

private val rowDate = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)

@Composable
private fun ClipRow(clip: Clip, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clip.title, style = body(PC.Ink, 16.sp, androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Gap(12)
            Text(Fmt.clock(clip.durationMs), style = mono(20.sp, PC.Ink, androidx.compose.ui.text.font.FontWeight.Bold))
        }
        Gap(h = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(rowDate.format(Date(clip.createdAt)), style = mono(11.sp, PC.Dim))
            Text(Fmt.bytes(clip.sizeBytes), style = mono(11.sp, PC.Dim))
            Text(clip.format.label, style = mono(11.sp, PC.Dim))
        }
        if (clip.tags.isNotEmpty()) {
            Gap(h = 6)
            Text(clip.tags.joinToString("  ") { "#$it" }, style = mono(11.sp, PC.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
