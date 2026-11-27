package com.termux.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import kotlin.math.abs

/**
 * Manages the draggable, edge-snapping, collapsible floating control pill overlay.
 */
class FloatingPillController(
    private val context: Context,
    private val prefsName: String,
    private val rootView: View,
    private val floatingPill: LinearLayout,
    private val pillControlsBtn: ImageButton,
    private val pillCollapseBtn: TextView,
    private val onOpenControls: () -> Unit
) {
    companion object {
        private const val KEY_PILL_X_FRAC = "pill_x_frac"
        private const val KEY_PILL_Y_FRAC = "pill_y_frac"
        private const val KEY_PILL_COLLAPSED = "pill_collapsed"
    }

    private var pillCollapsed = false
    private var pillRestored = false

    @SuppressLint("ClickableViewAccessibility")
    fun setup() {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var dX = 0f
        var dY = 0f
        var dragging = false

        val dragListener = View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dX = floatingPill.x - event.rawX
                    dY = floatingPill.y - event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val movedX = abs(event.rawX - downRawX)
                    val movedY = abs(event.rawY - downRawY)
                    if (!dragging && (movedX > touchSlop || movedY > touchSlop)) {
                        dragging = true
                        if (v !== floatingPill) {
                            val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            v.onTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                    }
                    if (dragging) {
                        val maxX = (rootView.width - floatingPill.width).coerceAtLeast(0).toFloat()
                        val maxY = (rootView.height - floatingPill.height).coerceAtLeast(0).toFloat()
                        floatingPill.x = (event.rawX + dX).coerceIn(0f, maxX)
                        floatingPill.y = (event.rawY + dY).coerceIn(0f, maxY)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        snapToEdge(savePos = true)
                        dragging = false
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        floatingPill.setOnTouchListener(dragListener)
        pillControlsBtn.setOnTouchListener(dragListener)
        pillCollapseBtn.setOnTouchListener(dragListener)

        pillControlsBtn.setOnClickListener { onOpenControls() }
        pillCollapseBtn.setOnClickListener { toggleCollapsed() }

        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        pillCollapsed = prefs.getBoolean(KEY_PILL_COLLAPSED, false)
        pillControlsBtn.visibility = if (pillCollapsed) View.GONE else View.VISIBLE
        pillCollapseBtn.text = if (pillCollapsed) "‹" else "›"

        rootView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            val newW = right - left
            val newH = bottom - top
            if ((oldW != newW || oldH != newH || oldW == 0) && newW > 0 && newH > 0 && floatingPill.visibility == View.VISIBLE) {
                floatingPill.post {
                    val margin = 10 * context.resources.displayMetrics.density
                    val maxY = (rootView.height - floatingPill.height - margin).coerceAtLeast(margin)
                    val curYFrac = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                        .getFloat(KEY_PILL_Y_FRAC, 0.5f)
                    floatingPill.y = (curYFrac * rootView.height).coerceIn(margin, maxY)
                    snapToEdge(savePos = false)
                }
            }
        }
        floatingPill.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            if (r > l && b > t) {
                ViewCompat.setSystemGestureExclusionRects(v, listOf(Rect(0, 0, r - l, b - t)))
            }
        }
    }

    fun applyTheme(snapshot: HubThemeSnapshot) {
        snapshot.applyToFloatingPill(floatingPill, pillControlsBtn, pillCollapseBtn)
    }

    fun setVisible(visible: Boolean) {
        floatingPill.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            restorePositionIfNeeded()
        }
    }

    fun restorePositionIfNeeded() {
        if (pillRestored) return
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PILL_Y_FRAC)) return
        floatingPill.post {
            val w = rootView.width
            val h = rootView.height
            if (w <= 0 || h <= 0) return@post
            pillRestored = true
            val xFrac = prefs.getFloat(KEY_PILL_X_FRAC, 1f)
            val yFrac = prefs.getFloat(KEY_PILL_Y_FRAC, 0.5f)
            val margin = 10 * context.resources.displayMetrics.density
            val targetX = if (xFrac < 0.5f) margin else (w - floatingPill.width - margin).coerceAtLeast(margin)
            val targetY = (yFrac * h).coerceIn(margin, (h - floatingPill.height - margin).coerceAtLeast(margin))
            floatingPill.x = targetX
            floatingPill.y = targetY
        }
    }

    private fun snapToEdge(savePos: Boolean = false) {
        val parentW = rootView.width
        val parentH = rootView.height
        if (parentW <= 0) return
        val margin = 10 * context.resources.displayMetrics.density
        val center = floatingPill.x + floatingPill.width / 2f
        val onLeft = center < parentW / 2f
        val targetX = if (onLeft) {
            margin
        } else {
            (parentW - floatingPill.width - margin).coerceAtLeast(margin)
        }
        floatingPill.animate().x(targetX).setDuration(180).start()
        if (savePos && parentH > 0) {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_PILL_X_FRAC, if (onLeft) 0f else 1f)
                .putFloat(KEY_PILL_Y_FRAC, (floatingPill.y / parentH).coerceIn(0.05f, 0.92f))
                .apply()
        }
    }

    private fun toggleCollapsed() {
        pillCollapsed = !pillCollapsed
        pillControlsBtn.visibility = if (pillCollapsed) View.GONE else View.VISIBLE
        pillCollapseBtn.text = if (pillCollapsed) "‹" else "›"
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PILL_COLLAPSED, pillCollapsed)
            .apply()
        floatingPill.post { snapToEdge(savePos = true) }
    }
}
