package com.precord.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Single place that builds the intents understood by [PrecordService]. */
object ServiceController {
    const val ACTION_START = "com.precord.app.START"
    const val ACTION_STOP = "com.precord.app.STOP"
    const val ACTION_SAVE = "com.precord.app.SAVE"
    const val EXTRA_LAST_MS = "last_ms"
    const val EXTRA_PROJ_CODE = "proj_code"
    const val EXTRA_PROJ_DATA = "proj_data"

    fun intent(context: Context, action: String) = Intent(context, PrecordService::class.java).setAction(action)

    /** Needs RECORD_AUDIO granted and the app visible (or a tile/notification gesture). */
    fun start(context: Context, projectionCode: Int = 0, projectionData: Intent? = null) {
        val i = intent(context, ACTION_START)
        if (projectionData != null) i.putExtra(EXTRA_PROJ_CODE, projectionCode).putExtra(EXTRA_PROJ_DATA, projectionData)
        ContextCompat.startForegroundService(context, i)
    }

    fun stop(context: Context) {
        context.startService(intent(context, ACTION_STOP))
    }

    /** [lastMs] = null saves the entire buffer. Only valid while the service is running. */
    fun save(context: Context, lastMs: Long? = null) {
        val i = intent(context, ACTION_SAVE)
        if (lastMs != null) i.putExtra(EXTRA_LAST_MS, lastMs)
        context.startService(i)
    }
}
