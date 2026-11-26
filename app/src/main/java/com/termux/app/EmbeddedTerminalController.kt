package com.termux.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.R
import com.termux.shared.logger.Logger
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.shared.termux.terminal.io.BellHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Manages the embedded Termux terminal overlay, extra-keys row, sticky CTRL/ALT modifiers,
 * font zoom persistence, and session attachment.
 */
class EmbeddedTerminalController(
    private val activity: Activity,
    private val prefsName: String,
    private val terminalContainer: View,
    private val terminalView: TerminalView,
    private val termExtraKeysRow: LinearLayout,
    private val serviceProvider: () -> TermuxService?,
    private val onCopyText: (label: String, text: String) -> Unit,
    private val onRequestClose: () -> Unit
) {
    companion object {
        private const val LOG_TAG = "EmbeddedTerminal"
        private const val KEY_TERM_TEXT_SIZE = "term_text_size"
    }

    private var terminalTextSize = 28
    private var ctrlArmed = false
    private var altArmed = false
    private var ctrlKeyBtn: TextView? = null
    private var altKeyBtn: TextView? = null

    private val sessionClient = object : TermuxTerminalSessionClientBase() {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (terminalView.currentSession == changedSession) {
                terminalView.onScreenUpdated()
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            activity.runOnUiThread {
                val exitStatus = finishedSession.exitStatus
                if (exitStatus == 0 || exitStatus == 130) {
                    serviceProvider()?.removeTermuxSession(finishedSession)
                }
                if (terminalView.currentSession == finishedSession) {
                    terminalView.onScreenUpdated()
                }
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (!text.isNullOrEmpty()) {
                onCopyText("Antigravity Terminal", text)
            }
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.coerceToText(activity)?.toString()
                if (!text.isNullOrEmpty()) {
                    terminalView.mEmulator?.paste(text)
                }
            }
        }

        override fun onBell(session: TerminalSession) {
            BellHandler.getInstance(activity).doBell()
        }

        override fun onColorsChanged(changedSession: TerminalSession) {
            if (terminalView.currentSession == changedSession) {
                terminalView.invalidate()
            }
        }

        override fun onTerminalCursorStateChange(enabled: Boolean) {
            terminalView.setTerminalCursorBlinkerState(enabled, false)
        }

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            serviceProvider()?.getTermuxSessionForTerminalSession(session)?.executionCommand?.mPid = pid
        }
    }

    fun isVisible(): Boolean = terminalContainer.visibility == View.VISIBLE

    fun setup() {
        val prefs = activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        terminalTextSize = prefs.getInt(KEY_TERM_TEXT_SIZE, 28).coerceIn(16, 56)
        terminalView.setTextSize(terminalTextSize)
        terminalView.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                val clampedScale = scale.coerceIn(0.5f, 2.0f)
                val next = Math.round(terminalTextSize * clampedScale).coerceIn(16, 56)
                return if (next != terminalTextSize) {
                    updateFontSize(next)
                    1.0f
                } else {
                    clampedScale
                }
            }

            override fun onSingleTapUp(e: MotionEvent?) {
                terminalView.requestFocus()
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = isVisible()
            override fun copyModeChanged(copyMode: Boolean) {}

            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
                if (session != null && !session.isRunning && keyCode == KeyEvent.KEYCODE_ENTER) {
                    ensureSession()
                    return true
                }
                return false
            }

            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
            override fun onLongPress(event: MotionEvent?): Boolean = false

            override fun readControlKey(): Boolean {
                val v = ctrlArmed
                if (v) {
                    ctrlArmed = false
                    updateModifierStyles()
                }
                return v
            }

            override fun readAltKey(): Boolean {
                val v = altArmed
                if (v) {
                    altArmed = false
                    updateModifierStyles()
                }
                return v
            }

            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
            override fun onEmulatorSet() {}
            override fun logError(tag: String?, message: String?) { Logger.logError(tag ?: LOG_TAG, message ?: "") }
            override fun logWarn(tag: String?, message: String?) { Logger.logWarn(tag ?: LOG_TAG, message ?: "") }
            override fun logInfo(tag: String?, message: String?) { Logger.logInfo(tag ?: LOG_TAG, message ?: "") }
            override fun logDebug(tag: String?, message: String?) { Logger.logDebug(tag ?: LOG_TAG, message ?: "") }
            override fun logVerbose(tag: String?, message: String?) { Logger.logVerbose(tag ?: LOG_TAG, message ?: "") }
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
                Logger.logStackTraceWithMessage(tag ?: LOG_TAG, message ?: "", e)
            }
            override fun logStackTrace(tag: String?, e: Exception?) { Logger.logStackTrace(tag ?: LOG_TAG, e) }
        })

        activity.findViewById<View>(R.id.btn_term_zoom_out).setOnClickListener {
            updateFontSize((terminalTextSize - 3).coerceIn(16, 56))
        }
        activity.findViewById<View>(R.id.btn_term_zoom_in).setOnClickListener {
            updateFontSize((terminalTextSize + 3).coerceIn(16, 56))
        }
        activity.findViewById<View>(R.id.btn_close_terminal).setOnClickListener {
            onRequestClose()
        }

        buildExtraKeys()
    }

    fun ensureSession() {
        val svc = serviceProvider() ?: return
        val existing = (0 until svc.termuxSessionsSize)
            .asSequence()
            .mapNotNull { svc.getTermuxSession(it)?.terminalSession }
            .lastOrNull { it.isRunning }
        val session = existing ?: svc.createTermuxSession(null, null, null, null, false, null)?.terminalSession
        if (session != null) {
            session.updateTerminalSessionClient(sessionClient)
            terminalView.attachSession(session)
            terminalView.onScreenUpdated()
        }
    }

    fun show() {
        ensureSession()
        terminalContainer.visibility = View.VISIBLE
        terminalView.requestFocus()
    }

    fun hide() {
        terminalContainer.visibility = View.GONE
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(terminalView.windowToken, 0)
    }

    private fun updateFontSize(size: Int) {
        terminalTextSize = size
        terminalView.setTextSize(size)
        activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TERM_TEXT_SIZE, size).apply()
    }

    private fun buildExtraKeys() {
        termExtraKeysRow.removeAllViews()
        val dp = activity.resources.displayMetrics.density
        val specialKeyCodes = mapOf(
            "__ESC__" to KeyEvent.KEYCODE_ESCAPE,
            "__TAB__" to KeyEvent.KEYCODE_TAB,
            "__UP__" to KeyEvent.KEYCODE_DPAD_UP,
            "__DOWN__" to KeyEvent.KEYCODE_DPAD_DOWN,
            "__LEFT__" to KeyEvent.KEYCODE_DPAD_LEFT,
            "__RIGHT__" to KeyEvent.KEYCODE_DPAD_RIGHT
        )
        val keys = listOf(
            "ESC" to "__ESC__",
            "TAB" to "__TAB__",
            "CTRL" to "__CTRL__",
            "ALT" to "__ALT__",
            "↑" to "__UP__",
            "↓" to "__DOWN__",
            "←" to "__LEFT__",
            "→" to "__RIGHT__",
            "/" to "/",
            "-" to "-",
            "|" to "|",
            "~" to "~"
        )
        for ((label, seq) in keys) {
            val btn = TextView(activity).apply {
                text = label
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#E6EAF0"))
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.agy_btn_secondary)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (32 * dp).toInt()
                ).apply {
                    marginEnd = (6 * dp).toInt()
                }
                layoutParams = lp
                minWidth = (38 * dp).toInt()
                setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            }
            if (seq == "__CTRL__") {
                ctrlKeyBtn = btn
                btn.setOnClickListener {
                    ctrlArmed = !ctrlArmed
                    updateModifierStyles()
                }
            } else if (seq == "__ALT__") {
                altKeyBtn = btn
                btn.setOnClickListener {
                    altArmed = !altArmed
                    updateModifierStyles()
                }
            } else {
                val mappedKeyCode = specialKeyCodes[seq]
                btn.setOnClickListener {
                    if (mappedKeyCode != null) {
                        terminalView.onKeyDown(mappedKeyCode, KeyEvent(KeyEvent.ACTION_DOWN, mappedKeyCode))
                    } else {
                        terminalView.inputCodePoint(
                            TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD,
                            seq.codePointAt(0),
                            false,
                            false
                        )
                    }
                }
            }
            termExtraKeysRow.addView(btn)
        }
    }

    private fun updateModifierStyles() {
        ctrlKeyBtn?.setBackgroundResource(if (ctrlArmed) R.drawable.agy_btn_primary else R.drawable.agy_btn_secondary)
        altKeyBtn?.setBackgroundResource(if (altArmed) R.drawable.agy_btn_primary else R.drawable.agy_btn_secondary)
    }
}
