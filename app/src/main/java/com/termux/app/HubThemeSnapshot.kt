package com.termux.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
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
        val translucentBg = withAlpha(cardBg, 0.88f)
        val bevelBorder = withAlpha(borderColor, 0.75f)
        val radiusPx = (cornerRadiusDp.coerceIn(12f, 28f)) * dp
        val strokePx = (borderWidthDp.coerceIn(1f, 2f) * dp).roundToInt().coerceAtLeast(1)

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(translucentBg)
            setStroke(strokePx, bevelBorder)
        }
        pill.background = bg
        pill.clipToOutline = true
        val elevPx = shadowDp.coerceIn(6f, 18f) * dp
        pill.elevation = elevPx
        pill.translationZ = 2f * dp
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadowTint = if (isDark) Color.argb(190, 0, 0, 0) else Color.argb(90, 0, 0, 0)
            pill.outlineAmbientShadowColor = shadowTint
            pill.outlineSpotShadowColor = shadowTint
        }
        controlsBtn.imageTintList = ColorStateList.valueOf(textPrimary)
        collapseBtn.setTextColor(textPrimary)
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
        val firstChild = (root as? ViewGroup)?.getChildAt(0) ?: root
        firstChild.background = sheetBg
        firstChild.elevation = shadowDp.coerceIn(8f, 20f) * dp

        styleCardsRecursively(firstChild, dp, strokePx)
    }

    private fun styleCardsRecursively(view: View, dp: Float, strokePx: Int) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val bg = child.background
                if (child is LinearLayout && bg != null && child.paddingLeft > 0) {
                    val cardDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = cornerRadiusDp.coerceIn(10f, 18f) * dp
                        setColor(withAlpha(cardBg, 0.90f))
                        setStroke(strokePx, withAlpha(borderColor, 0.65f))
                    }
                    child.background = cardDrawable
                    child.elevation = (shadowDp * 0.4f).coerceIn(2f, 8f) * dp
                }
                styleCardsRecursively(child, dp, strokePx)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "antigravity_prefs"
        private const val KEY_THEME_JSON = "hub_theme_snapshot_json"

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
            val card = parseCssColor(json.optString("cardBg")) ?: Color.parseColor("#1A1F29")
            val border = parseCssColor(json.optString("borderColor")) ?: Color.parseColor("#2E3646")
            val accent = parseCssColor(json.optString("accentColor")) ?: Color.parseColor("#4F8CFF")
            val textPri = parseCssColor(json.optString("textPrimary")) ?: Color.parseColor("#F2F5FA")
            val textSec = parseCssColor(json.optString("textSecondary")) ?: Color.parseColor("#9AA4B2")
            val borderW = json.optDouble("borderWidthDp", 1.0).toFloat().coerceIn(0.5f, 3f)
            val radius = json.optDouble("cornerRadiusDp", 18.0).toFloat().coerceIn(8f, 28f)
            val shadow = json.optDouble("shadowDp", 12.0).toFloat().coerceIn(4f, 24f)
            val dark = json.optBoolean("isDark", isColorDark(surface))
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
