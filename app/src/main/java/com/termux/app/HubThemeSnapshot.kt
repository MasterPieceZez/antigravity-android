package com.termux.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.termux.R
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Snapshots the live Hub WebView CSS theme (surface, card, bevel border, radius,
 * drop shadow, text, accent) and caches it in SharedPreferences so the floating
 * pillbox and Control Center always match the Hub even before boot or offline.
 */
data class HubThemeSnapshot(
    val surfaceBg: Int,
    val cardBg: Int,
    val borderColor: Int,
    val borderWidthDp: Float,
    val cornerRadiusDp: Float,
    val shadowDp: Float,
    val accentColor: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val isDark: Boolean
) {
    fun applyToFloatingPill(
        pill: LinearLayout,
        controlsBtn: ImageButton,
        collapseBtn: TextView
    ) {
        val dp = pill.resources.displayMetrics.density
        val translucentBg = withAlpha(cardBg, 0.90f)
        val bevelBorder = withAlpha(borderColor, 0.85f)
        val radiusPx = 999f * dp
        val strokePx = (borderWidthDp.coerceIn(1f, 2f) * dp).roundToInt().coerceAtLeast(1)

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(translucentBg)
            setStroke(strokePx, bevelBorder)
        }
        pill.background = bg
        pill.clipToOutline = true
        val elevPx = shadowDp.coerceIn(8f, 18f) * dp
        pill.elevation = elevPx
        pill.translationZ = 2f * dp
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadowTint = if (isDark) Color.argb(190, 0, 0, 0) else Color.argb(90, 0, 0, 0)
            pill.outlineAmbientShadowColor = shadowTint
            pill.outlineSpotShadowColor = shadowTint
        }
        controlsBtn.imageTintList = ColorStateList.valueOf(textPrimary)
        collapseBtn.setTextColor(textSecondary)
        pill.findViewById<View>(com.termux.R.id.pill_divider)?.setBackgroundColor(withAlpha(borderColor, 0.70f))
    }

    fun applyToControlSheet(root: View) {
        val dp = root.resources.displayMetrics.density
        val sheetRadius = (cornerRadiusDp.coerceIn(12f, 24f)) * dp
        val strokePx = (borderWidthDp.coerceIn(1f, 2f) * dp).roundToInt().coerceAtLeast(1)

        val sheetBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                sheetRadius, sheetRadius,
                sheetRadius, sheetRadius,
                0f, 0f, 0f, 0f
            )
            setColor(withAlpha(surfaceBg, 0.96f))
            setStroke(strokePx, withAlpha(borderColor, 0.55f))
        }
        root.background = sheetBg
        root.clipToOutline = true
        root.elevation = shadowDp.coerceIn(8f, 20f) * dp

        val firstChild = (root as? ViewGroup)?.getChildAt(0) ?: root
        if (firstChild !== root) {
            firstChild.background = null
        }
        if (firstChild is ViewGroup && firstChild.childCount > 0) {
            val dragHandle = firstChild.getChildAt(0)
            if (dragHandle !is TextView && dragHandle !is ViewGroup) {
                dragHandle.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f * dp
                    setColor(effectiveBorderColor(0.85f))
                }
            }
        }
        styleControlViewsRecursively(firstChild, dp, strokePx)
    }

    fun applyToAccountRow(row: LinearLayout, label: TextView, isActive: Boolean) {
        val dp = row.resources.displayMetrics.density
        val strokePx = (borderWidthDp.coerceIn(1f, 2f) * dp).roundToInt().coerceAtLeast(1)
        row.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp.coerceIn(8f, 14f) * dp
            setColor(effectiveSecondarySurfaceColor())
            setStroke(strokePx, effectiveBorderColor(0.75f))
        }
        label.setTextColor(if (isActive) accentColor else textPrimary)
    }

    fun applyToPrimaryButton(btn: TextView) {
        val dp = btn.resources.displayMetrics.density
        btn.background = buildPrimaryButtonDrawable(dp)
        btn.setTextColor(if (isColorDark(accentColor)) Color.WHITE else Color.parseColor("#0F172A"))
    }

    private fun effectiveBorderColor(alphaFrac: Float): Int {
        val base = if (!isDark && colorDistance(borderColor, surfaceBg) < 28) {
            blendColors(surfaceBg, textPrimary, 0.16f)
        } else {
            borderColor
        }
        return withAlpha(base, alphaFrac)
    }

    private fun effectiveSecondarySurfaceColor(): Int {
        return if (!isDark) {
            if (colorDistance(cardBg, surfaceBg) < 18) {
                blendColors(surfaceBg, accentColor, 0.06f)
            } else {
                blendColors(cardBg, textPrimary, 0.04f)
            }
        } else {
            if (colorDistance(cardBg, surfaceBg) < 12) {
                blendColors(cardBg, Color.WHITE, 0.06f)
            } else {
                withAlpha(cardBg, 0.94f)
            }
        }
    }

    private fun buildSecondaryButtonDrawable(dp: Float, strokePx: Int): Drawable {
        val radius = cornerRadiusDp.coerceIn(8f, 14f) * dp
        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(effectiveSecondarySurfaceColor())
            setStroke(strokePx, effectiveBorderColor(0.85f))
        }
        val rippleColor = withAlpha(if (isDark) Color.WHITE else textPrimary, 0.12f)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null)
    }

    private fun buildPrimaryButtonDrawable(dp: Float): Drawable {
        val radius = cornerRadiusDp.coerceIn(8f, 14f) * dp
        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(accentColor)
        }
        val rippleColor = withAlpha(if (isColorDark(accentColor)) Color.WHITE else Color.BLACK, 0.20f)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null)
    }

    private fun styleControlViewsRecursively(view: View, dp: Float, strokePx: Int) {
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            when {
                child is LinearLayout && child.background != null && child.paddingLeft > 0 -> {
                    val cardSurface = if (!isDark && colorDistance(cardBg, surfaceBg) < 10) {
                        blendColors(surfaceBg, Color.BLACK, 0.025f)
                    } else {
                        withAlpha(cardBg, 0.92f)
                    }
                    child.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = cornerRadiusDp.coerceIn(10f, 18f) * dp
                        setColor(cardSurface)
                        setStroke(strokePx, effectiveBorderColor(0.70f))
                    }
                    child.elevation = (shadowDp * 0.4f).coerceIn(2f, 8f) * dp
                }
                child is Switch -> {
                    val isSubtle = child.id == R.id.switch_notify_foreground
                    child.setTextColor(if (isSubtle) textSecondary else textPrimary)
                    val checkedThumb = accentColor
                    val uncheckedThumb = if (isDark) Color.parseColor("#CBD5E1") else Color.parseColor("#FFFFFF")
                    val checkedTrack = withAlpha(accentColor, 0.45f)
                    val uncheckedTrack = effectiveBorderColor(0.90f)
                    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                    child.thumbTintList = ColorStateList(states, intArrayOf(checkedThumb, uncheckedThumb))
                    child.trackTintList = ColorStateList(states, intArrayOf(checkedTrack, uncheckedTrack))
                }
                child is TextView && child.id in PRIMARY_BUTTON_IDS -> {
                    applyToPrimaryButton(child)
                }
                child is TextView && child.id in SECONDARY_BUTTON_IDS -> {
                    child.background = buildSecondaryButtonDrawable(dp, strokePx)
                    val btnTextColor = when (child.id) {
                        R.id.sheet_btn_check_update -> accentColor
                        R.id.sheet_btn_clear_cache,
                        R.id.sheet_btn_reinstall_cli,
                        R.id.sheet_btn_phantom_fix -> textSecondary
                        else -> textPrimary
                    }
                    child.setTextColor(btnTextColor)
                }
                child is TextView -> {
                    val current = child.currentTextColor
                    val lum = (0.299 * Color.red(current) + 0.587 * Color.green(current) + 0.114 * Color.blue(current)) / 255.0
                    val isSecondary = child.id == R.id.sheet_backup_status || lum < 0.78
                    child.setTextColor(if (isSecondary) textSecondary else textPrimary)
                }
            }
            styleControlViewsRecursively(child, dp, strokePx)
        }
    }

    companion object {
        private const val PREFS_NAME = "antigravity_prefs"
        private const val KEY_THEME_JSON = "hub_theme_snapshot_json"

        private val PRIMARY_BUTTON_IDS = setOf(
            R.id.sheet_btn_add_account,
            R.id.sheet_btn_restore
        )

        private val SECONDARY_BUTTON_IDS = setOf(
            R.id.sheet_btn_reload,
            R.id.sheet_btn_restart,
            R.id.sheet_btn_terminal,
            R.id.sheet_btn_logs,
            R.id.sheet_btn_clear_cache,
            R.id.sheet_btn_check_update,
            R.id.sheet_btn_reinstall_cli,
            R.id.sheet_btn_test_notif,
            R.id.sheet_btn_phantom_fix,
            R.id.sheet_btn_backup_full,
            R.id.sheet_btn_backup_packages
        )

        fun load(context: Context): HubThemeSnapshot? {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_THEME_JSON, null) ?: return null
            return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
        }

        fun saveAndParse(context: Context, json: JSONObject): HubThemeSnapshot? {
            val parsed = runCatching { fromJson(json) }.getOrNull() ?: return null
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME_JSON, json.toString())
                .apply()
            return parsed
        }

        private fun fromJson(json: JSONObject): HubThemeSnapshot {
            val surface = parseCssColor(json.optString("surfaceBg")) ?: Color.parseColor("#11141A")
            val dark = json.optBoolean("isDark", isColorDark(surface))
            val defaultCard = if (dark) Color.parseColor("#1A1F29") else Color.parseColor("#F8FAFC")
            val defaultBorder = if (dark) Color.parseColor("#2E3646") else Color.parseColor("#CBD5E1")
            val defaultTextPri = if (dark) Color.parseColor("#F2F5FA") else Color.parseColor("#0F172A")
            val defaultTextSec = if (dark) Color.parseColor("#9AA4B2") else Color.parseColor("#475569")

            val card = parseCssColor(json.optString("cardBg")) ?: defaultCard
            val border = parseCssColor(json.optString("borderColor")) ?: defaultBorder
            val rawAccent = parseCssColor(json.optString("accentColor"))
            val accent = if (rawAccent != null && isUsableAccent(rawAccent, surface, card)) {
                rawAccent
            } else {
                Color.parseColor("#3B82F6")
            }
            val rawTextPri = parseCssColor(json.optString("textPrimary"))
            val textPri = if (rawTextPri != null && isColorDark(rawTextPri) != dark) rawTextPri else defaultTextPri
            val rawTextSec = parseCssColor(json.optString("textSecondary"))
            val textSec = if (rawTextSec != null && isColorDark(rawTextSec) != dark) rawTextSec else defaultTextSec
            val borderW = json.optDouble("borderWidthDp", 1.0).toFloat().coerceIn(0.5f, 3f)
            val radius = json.optDouble("cornerRadiusDp", 18.0).toFloat().coerceIn(8f, 28f)
            val shadow = json.optDouble("shadowDp", 12.0).toFloat().coerceIn(4f, 24f)
            return HubThemeSnapshot(
                surfaceBg = surface,
                cardBg = card,
                borderColor = border,
                borderWidthDp = borderW,
                cornerRadiusDp = radius,
                shadowDp = shadow,
                accentColor = accent,
                textPrimary = textPri,
                textSecondary = textSec,
                isDark = dark
            )
        }

        private fun isUsableAccent(color: Int, surfaceBg: Int, cardBg: Int): Boolean {
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val chroma = maxOf(r, g, b) - minOf(r, g, b)
            if (chroma < 35) return false
            return colorDistance(color, surfaceBg) >= 75 && colorDistance(color, cardBg) >= 60
        }

        private fun blendColors(base: Int, tint: Int, tintRatio: Float): Int {
            val t = tintRatio.coerceIn(0f, 1f)
            val inv = 1f - t
            val r = (Color.red(base) * inv + Color.red(tint) * t).roundToInt().coerceIn(0, 255)
            val g = (Color.green(base) * inv + Color.green(tint) * t).roundToInt().coerceIn(0, 255)
            val b = (Color.blue(base) * inv + Color.blue(tint) * t).roundToInt().coerceIn(0, 255)
            return Color.rgb(r, g, b)
        }

        private fun colorDistance(a: Int, b: Int): Int {
            return kotlin.math.abs(Color.red(a) - Color.red(b)) +
                kotlin.math.abs(Color.green(a) - Color.green(b)) +
                kotlin.math.abs(Color.blue(a) - Color.blue(b))
        }

        private fun withAlpha(color: Int, alphaFrac: Float): Int {
            val a = (alphaFrac.coerceIn(0f, 1f) * 255f).roundToInt()
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }

        private fun isColorDark(color: Int): Boolean {
            val lum = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
            return lum < 0.5
        }

        private fun parseCssColor(raw: String?): Int? {
            val s = raw?.trim()?.lowercase() ?: return null
            if (s.isEmpty() || s == "transparent" || s == "none") return null
            if (s.startsWith("#")) {
                return runCatching { Color.parseColor(s) }.getOrNull()
            }
            if (s.startsWith("rgb")) {
                val inner = s.substringAfter("(").substringBefore(")")
                val parts = inner.split(",", "/").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 3) {
                    val r = parts[0].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
                    val g = parts[1].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
                    val b = parts[2].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
                    val a = if (parts.size >= 4) {
                        ((parts[3].toFloatOrNull() ?: 1f).coerceIn(0f, 1f) * 255f).roundToInt()
                    } else 255
                    if (a == 0) return null
                    return Color.argb(a, r, g, b)
                }
            }
            return null
        }
    }
}
