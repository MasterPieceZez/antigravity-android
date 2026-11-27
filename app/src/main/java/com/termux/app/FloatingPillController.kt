package com.termux.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.termux.R
import kotlin.math.abs

/**
 * Manages the draggable, edge-snapping, collapsible floating capsule control pill.
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
        private const val ALPHA_EXPANDED_REST = 0.92f
        private const val ALPHA_COLLAPSED_REST = 0.72f
    }

    private var pillCollapsed = false
    private var pillRestored = false
    private var dockedOnLeft = false
    private var statusDotView: View? = null
    private var dividerView: View? = null

    @SuppressLint("ClickableViewAccessibility")
    fun setup() {
        statusDotView = floatingPill.findViewById(R.id.pill_status_dot)
        dividerView = floatingPill.findViewById(R.id.pill_divider)

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
                    floatingPill.animate().alpha(1f).setDuration(120).start()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val movedX = abs(event.rawX - downRawX)
                    val movedY = abs(event.rawY - downRawY)
                    if (!dragging && (movedX > touchSlop || movedY > touchSlop)) {
                        dragging = true
                        floatingPill.animate().scaleX(1.04f).scaleY(1.04f).alpha(1f).setDuration(100).start()
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
                        floatingPill.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                        snapToEdge(savePos = true)
                        dragging = false
                        true
                    } else {
                        restoreRestingAlpha()
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
        dockedOnLeft = prefs.getFloat(KEY_PILL_X_FRAC, 1f) < 0.5f
        applyCollapsedVisualState()

        rootView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            val newW = right - left
            val newH = bottom - top
            if ((oldW != newW || oldH != newH || oldW == 0) && newW > 0 && newH > 0 && floatingPill.visibility == View.VISIBLE) {
                floatingPill.post {
                    val margin = edgeMarginPx()
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
            dockedOnLeft = xFrac < 0.5f
            val margin = edgeMarginPx()
            val targetX = if (dockedOnLeft) margin else (w - floatingPill.width - margin).coerceAtLeast(margin)
            val targetY = (yFrac * h).coerceIn(margin, (h - floatingPill.height - margin).coerceAtLeast(margin))
            floatingPill.x = targetX
            floatingPill.y = targetY
            applyCollapsedVisualState()
        }
    }

    private fun edgeMarginPx(): Float {
        val baseDp = if (pillCollapsed) 6f else 10f
        return baseDp * context.resources.displayMetrics.density
    }

    private fun restoreRestingAlpha() {
        val targetAlpha = if (pillCollapsed) ALPHA_COLLAPSED_REST else ALPHA_EXPANDED_REST
        floatingPill.animate().alpha(targetAlpha).setDuration(180).start()
    }

    private fun applyCollapsedVisualState() {
        val contentVis = if (pillCollapsed) View.GONE else View.VISIBLE
        statusDotView?.visibility = contentVis
        pillControlsBtn.visibility = contentVis
        dividerView?.visibility = contentVis

        val dp = context.resources.displayMetrics.density
        if (pillCollapsed) {
            floatingPill.setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        } else {
            floatingPill.setPadding((8 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        }
        // Chevron points toward the screen edge when expanded ("tuck away"),
        // and inward toward content when collapsed ("expand").
        pillCollapseBtn.text = when {
            dockedOnLeft && pillCollapsed -> "›"
            dockedOnLeft && !pillCollapsed -> "‹"
            !dockedOnLeft && pillCollapsed -> "‹"
            else -> "›"
        }
        floatingPill.alpha = if (pillCollapsed) ALPHA_COLLAPSED_REST else ALPHA_EXPANDED_REST
    }

    private fun snapToEdge(savePos: Boolean = false) {
        val parentW = rootView.width
        val parentH = rootView.height
        if (parentW <= 0) return
        val margin = edgeMarginPx()
        val center = floatingPill.x + floatingPill.width / 2f
        val onLeft = center < parentW / 2f
        dockedOnLeft = onLeft
        applyCollapsedVisualState()
        val targetX = if (onLeft) {
            margin
        } else {
            (parentW - floatingPill.width - margin).coerceAtLeast(margin)
        }
        floatingPill.animate()
            .x(targetX)
            .alpha(if (pillCollapsed) ALPHA_COLLAPSED_REST else ALPHA_EXPANDED_REST)
            .setInterpolator(DecelerateInterpolator(1.3f))
            .setDuration(200)
            .start()
        if (savePos && parentH > 0) {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_PILL_X_FRAC, if (onLeft) 0f else 1f)
                .putFloat(KEY_PILL_Y_FRAC, (floatingPill.y / parentH).coerceIn(0.05f, 0.92f))
                .apply()
        }
    }

    private fun toggleCollapsed() {
        pillCollapsed = !pillCollapsed
        applyCollapsedVisualState()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PILL_COLLAPSED, pillCollapsed)
            .apply()
        floatingPill.post { snapToEdge(savePos = true) }
    }
}
