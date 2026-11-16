package com.precord.app.service

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.precord.app.MainActivity
import com.precord.app.R
import com.precord.app.core.Fmt
import com.precord.app.core.Monitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings tile: tap toggles the replay buffer; the subtitle shows how much is buffered. */
class QuickTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job = scope.launch { Monitor.state.collect { render(it.running, it.bufferedMs) } }
    }

    override fun onStopListening() {
        job?.cancel()
        super.onStopListening()
    }

    private fun render(running: Boolean, bufferedMs: Long) {
        val t = qsTile ?: return
        t.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.tile_label)
        t.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (running) getString(R.string.tile_armed, Fmt.clock(bufferedMs)) else getString(R.string.tile_off)
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (Monitor.state.value.running) {
            ServiceController.stop(this)
            return
        }
        val go = {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                try {
                    ServiceController.start(this)
                } catch (e: Exception) {
                    openApp()
                }
            } else {
                openApp()
            }
        }
        if (isLocked) unlockAndRun(go) else go()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_AUTOSTART, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
