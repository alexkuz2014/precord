package com.precord.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.precord.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.precord.app.core.Fmt
import com.precord.app.core.Monitor
import com.precord.app.core.SaveEvent
import com.precord.app.precord
import com.precord.app.service.ServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PrecordRoot(onArm: () -> Unit, onAllowCapture: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.precord
    val state by Monitor.state.collectAsState()
    val settings by app.settings.flow.collectAsState()
    val clips by app.clips.clips.collectAsState()

    var tab by rememberSaveable { mutableStateOf(0) }
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { app.clips.refresh() } }
    LaunchedEffect(Unit) {
        Monitor.events.collect { e ->
            banner = when (e) {
                is SaveEvent.Saved -> buildString {
                    append(ctx.getString(R.string.banner_saved, Fmt.clock(e.clip.durationMs), Fmt.bytes(e.clip.sizeBytes)))
                    e.fellBackFrom?.let { append("\n").append(ctx.getString(R.string.banner_fallback, it.label)) }
                    if (e.exportFailed) append("\n").append(ctx.getString(R.string.banner_export_failed))
                }
                is SaveEvent.Failed -> ctx.getString(R.string.banner_failed, e.reason)
            }
        }
    }
    LaunchedEffect(banner) { if (banner != null) { delay(4000); banner = null } }

    val open = openName?.let { n -> clips.firstOrNull { it.file.name == n } }

    Box(Modifier.fillMaxSize().background(PC.Black).windowInsetsPadding(WindowInsets.systemBars)) {
        if (open != null) {
            ClipDetailScreen(open) { openName = null }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(armed = state.running) { showSettings = true }
                Hairline()
                Box(Modifier.weight(1f)) {
                    if (tab == 0) {
                        DeckScreen(
                            state = state, settings = settings,
                            onArmToggle = { if (state.running) ServiceController.stop(ctx) else onArm() },
                            onSave = { last -> ServiceController.save(ctx, last) },
                            onBufferSec = { sec -> app.settings.update { it.copy(bufferSec = sec) } },
                            onAllowCapture = onAllowCapture,
                        )
                    } else {
                        LibraryScreen(clips) { openName = it.file.name }
                    }
                }
                Hairline()
                BottomNav(tab, clips.size) { tab = it }
            }
        }

        banner?.let {
            Text(
                it,
                style = mono(12.sp, PC.Black),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .fillMaxWidth()
                    .background(PC.Ink)
                    .clickable { banner = null; tab = 1 }
                    .padding(12.dp),
            )
        }
    }

    BackHandler(enabled = open == null && tab == 1) { tab = 0 }

    if (showSettings) SettingsSheet(settings, state.running, onChange = { f -> app.settings.update(f) }, onAllowCapture = onAllowCapture) { showSettings = false }
}

@Composable
private fun TopBar(armed: Boolean, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.app_title), style = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 15.sp, color = PC.Ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = 3.sp))
        Gap(10)
        if (armed) Text(stringResource(R.string.rec), style = mono(11.sp, PC.Signal, androidx.compose.ui.text.font.FontWeight.Bold))
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Box(Modifier.border(1.dp, PC.Line).clickable(role = Role.Button, onClick = onSettings).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.settings_btn), style = label(PC.Ink, 11.sp))
        }
    }
}

@Composable
private fun BottomNav(tab: Int, clipCount: Int, onTab: (Int) -> Unit) {
    val tick = rememberTick()
    Row(Modifier.fillMaxWidth().height(56.dp)) {
        listOf(stringResource(R.string.tab_deck), stringResource(R.string.tab_library, clipCount)).forEachIndexed { i, t ->
            Box(
                Modifier.weight(1f).fillMaxSize().clickable { if (tab != i) { tick(); onTab(i) } },
                contentAlignment = Alignment.Center,
            ) {
                Text(t, style = label(if (tab == i) PC.Ink else PC.Faint, 12.sp))
                if (tab == i) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(PC.Ink))
            }
        }
    }
}
