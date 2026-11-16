package com.precord.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.precord.app.core.MicMode
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.precord.app.core.Monitor
import com.precord.app.service.ServiceController
import com.precord.app.ui.PrecordRoot
import com.precord.app.ui.PrecordTheme

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.RECORD_AUDIO) && !Monitor.state.value.running) startBuffer()
    }

    /** Android's "allow Precord to capture audio" dialog, needed for playback subtraction. */
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) runCatching { ServiceController.start(this, r.resultCode, r.data) }
        else if (!Monitor.state.value.running) runCatching { ServiceController.start(this) }
    }

    /** Called from the UI when playback subtraction is selected but not yet permitted. */
    fun requestPlaybackCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching { capture.launch(mgr.createScreenCaptureIntent()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        setContent { PrecordTheme { PrecordRoot(onArm = ::arm, onAllowCapture = ::requestPlaybackCapture) } }
        if (savedInstanceState == null) {
            handle(intent)
            // The system (or a task killer) stopped a buffer the user had armed: bring it back.
            if (precord.settings.wasArmed && precord.settings.value.autoRearm && !Monitor.state.value.running &&
                granted(Manifest.permission.RECORD_AUDIO)) {
                runCatching { ServiceController.start(this) } // capture consent, if needed, is offered on the deck
                Toast.makeText(this, R.string.banner_rearmed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        Monitor.uiVisible.value = true
    }

    override fun onStop() {
        Monitor.uiVisible.value = false
        super.onStop()
    }

    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_AUTOSTART, false) == true && !Monitor.state.value.running) arm()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Asks for the microphone (+ notifications on 13+), then starts the foreground service. */
    private fun arm() {
        val needNotif = Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            startBuffer()
            if (needNotif) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            val ask = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (needNotif) ask += Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(ask.toTypedArray())
        }
    }

    private fun startBuffer() {
        if (precord.settings.value.micMode == MicMode.PLAYBACK) requestPlaybackCapture()
        else runCatching { ServiceController.start(this) }
    }

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }
}
