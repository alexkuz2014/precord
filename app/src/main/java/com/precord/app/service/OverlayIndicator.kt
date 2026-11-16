package com.precord.app.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small always-on-top "recording" pill (orange dot + buffered time) like the iOS recording indicator.
 * Needs the user to grant "Display over other apps". Tapping it saves a replay.
 */
class OverlayIndicator(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var dot: View? = null
    private var label: TextView? = null
    private var blink = false

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show() = ui.post {
        if (root != null || !canShow()) return@post
        val dotView = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF3B00.toInt()) }
            layoutParams = LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt()).apply { marginEnd = dp(8f).toInt() }
        }
        val text = TextView(context).apply {
            setTextColor(0xFFF2F2F2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            text = "--:--"
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(0xE6000000.toInt())
                setStroke(dp(1f).toInt(), 0xFF2E2E2E.toInt())
            }
            contentDescription = "Precord recording buffer. Tap to save replay."
            addView(dotView)
            addView(text)
            setOnClickListener { ServiceController.save(context) }
        }
        val sbId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (sbId > 0) context.resources.getDimensionPixelSize(sbId) else dp(24f).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBar + dp(2f).toInt()
        }
        runCatching { wm.addView(pill, lp) }.onSuccess {
            root = pill; dot = dotView; label = text
        }
    }

    /** Called ~1x/s from any thread. */
    fun update(timeText: String) {
        ui.post {
            label?.text = timeText
            blink = !blink
            dot?.alpha = if (blink) 1f else 0.35f
        }
    }

    fun hide() = ui.post {
        root?.let { runCatching { wm.removeView(it) } }
        root = null; dot = null; label = null
    }
}
