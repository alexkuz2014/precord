package com.precord.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.precord.app.R
import com.precord.app.core.Clip
import com.precord.app.core.Fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(clips: List<Clip>, onOpen: (Clip) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            if (clips.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.lib_empty), style = mono(20.sp, PC.Dim))
                        Gap(h = 8)
                        Text(stringResource(R.string.lib_empty_hint), style = body(PC.Faint, 13.sp))
                    }
                }
                return@Column
            }
            val q = query.trim().lowercase()
            val shown = if (q.isEmpty()) clips else clips.filter { c ->
                c.title.lowercase().contains(q) || c.file.name.lowercase().contains(q) || c.tags.any { it.lowercase().contains(q) }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Label(stringResource(R.string.lib_count, shown.size))
                Text(Fmt.bytes(shown.sumOf { it.sizeBytes }), style = mono(11.sp, PC.Dim))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).border(1.dp, PC.Line).padding(12.dp)) {
                if (query.isEmpty()) Text(stringResource(R.string.lib_search), style = body(PC.Faint, 14.sp))
                BasicTextField(query, { query = it }, singleLine = true, textStyle = body(PC.Ink, 14.sp), cursorBrush = SolidColor(PC.Ink), modifier = Modifier.fillMaxWidth())
            }
            Gap(h = 8)
            Hairline()
            if (shown.isEmpty()) {
                Text(stringResource(R.string.lib_no_match), style = mono(14.sp, PC.Dim), modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.file.name }) { clip ->
                    ClipRow(clip) { onOpen(clip) }
                    Hairline()
                }
            }
        }
    }
}

private val rowDate = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)

@Composable
private fun ClipRow(clip: Clip, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clip.title, style = body(PC.Ink, 16.sp, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Gap(12)
            Text(Fmt.clock(clip.durationMs), style = mono(20.sp, PC.Ink, FontWeight.Bold))
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
