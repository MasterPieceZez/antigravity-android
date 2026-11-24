package com.termux.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.termux.BuildConfig
import com.termux.R
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

class AntigravityMainActivity : AppCompatActivity(), ServiceConnection {

    companion object {
        private const val LOG_TAG = "AntigravityMain"
        private const val PREFS_NAME = "antigravity_prefs"
        private const val KEY_NOTIFY_DONE = "notify_agent_done"
        private const val KEY_NOTIFY_FOREGROUND = "notify_when_foreground"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_CPU_WAKELOCK = "cpu_wakelock"
        private const val KEY_PILL_X_FRAC = "pill_x_frac"
        private const val KEY_PILL_Y_FRAC = "pill_y_frac"
        private const val KEY_PILL_COLLAPSED = "pill_collapsed"
        private const val KEY_TERM_TEXT_SIZE = "term_text_size"
        private const val NOTIF_CHANNEL_ID = "antigravity_agent_done"
        private const val NOTIF_ID_AGENT_DONE = 4402
        private const val SHARE_INBOX_DIR = "/storage/emulated/0/Download/AntigravityInbox"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var rootView: View
    private lateinit var webView: WebView
    private lateinit var reconnectBanner: View
    private lateinit var txtReconnectBanner: TextView
    private lateinit var terminalContainer: View
    private lateinit var terminalView: TerminalView
    private lateinit var termExtraKeysRow: LinearLayout
    private lateinit var splashContainer: View
    private lateinit var splashStatus: TextView
    private lateinit var setupContainer: View
    private lateinit var onboardingContainer: View
    private lateinit var floatingPill: LinearLayout
    private lateinit var pillControlsBtn: ImageButton
    private lateinit var pillCollapseBtn: TextView

    private lateinit var btnGrantStorage: TextView
    private lateinit var btnGrantBattery: TextView
    private lateinit var txtDetectedBackup: TextView
    private lateinit var onboardingProgressBar: ProgressBar
    private lateinit var txtOnboardingProgress: TextView

    private var termuxService: TermuxService? = null
    private var isServiceBound = false
    private var bootstrapComplete = false
    private var setupDismissedThisSession = false
    private var currentHubUrl: String? = null
    private var lastBackPressedMs = 0L
    private var pillCollapsed = false
    private var pillRestored = false
    private var terminalTextSize = 28
    private var ctrlArmed = false
    private var altArmed = false
    private var ctrlKeyBtn: TextView? = null
    private var altKeyBtn: TextView? = null
    private var isActivityForeground = true

    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingRestoreSheetProgress: ProgressBar? = null
    private var pendingRestoreSheetStatus: TextView? = null
    private var pendingRestoreDialog: BottomSheetDialog? = null
    private var notifyPollerJob: Job? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback
        fileUploadCallback = null
        if (callback == null) return@registerForActivityResult
        if (result.resultCode != RESULT_OK || result.data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val data = result.data!!
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        } else {
            data.data?.let { uris.add(it) }
        }
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private val backupFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            restoreFromContentUri(uri)
        }
    }

    private val webMicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val req = pendingWebPermissionRequest
        pendingWebPermissionRequest = null
        if (req != null) {
            if (granted) {
                runCatching { req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) }
            } else {
                runCatching { req.deny() }
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.parseColor("#0B0D10"))
        setContentView(R.layout.activity_antigravity_main)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        terminalTextSize = prefs.getInt(KEY_TERM_TEXT_SIZE, 28).coerceIn(16, 56)
        applyKeepScreenOn(prefs.getBoolean(KEY_KEEP_SCREEN_ON, false))

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        bindViews()
        setupWebView()
        setupEmbeddedTerminalView()
        setupFloatingPill()
        setupButtons()
        startNotificationPoller()
        handleIncomingShareIntent(intent)

        val serviceIntent = Intent(this, TermuxService::class.java)
        runCatching { startService(serviceIntent) }
        bindService(serviceIntent, this, Context.BIND_AUTO_CREATE)

        if (prefs.getBoolean(KEY_CPU_WAKELOCK, false)) {
            applyCpuWakeLock(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        if (type.startsWith("text/")) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!sharedText.isNullOrEmpty()) {
                copyToClipboard("Antigravity Share", sharedText)
                Toast.makeText(this, "Shared text copied — paste into Antigravity", Toast.LENGTH_LONG).show()
            }
            return
        }
        @Suppress("DEPRECATION")
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: return

        scope.launch {
            val savedPath = withContext(Dispatchers.IO) { saveSharedUriToInbox(uri) }
            if (savedPath != null) {
                copyToClipboard("Antigravity File", savedPath)
                Toast.makeText(this@AntigravityMainActivity, "Saved to $savedPath (path copied)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@AntigravityMainActivity, "Could not import shared file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveSharedUriToInbox(uri: Uri): String? {
        return try {
            var name = "shared_${System.currentTimeMillis()}"
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    c.getString(idx)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
            }
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(SHARE_INBOX_DIR)
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, safeName)
            contentResolver.openInputStream(uri)?.use { src ->
                outFile.outputStream().use { dst -> src.copyTo(dst) }
            } ?: return null
            "~/storage/shared/Download/AntigravityInbox/$safeName"
        } catch (_: Exception) {
            null
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyCpuWakeLock(enabled: Boolean) {
        val action = if (enabled) TERMUX_SERVICE.ACTION_WAKE_LOCK else TERMUX_SERVICE.ACTION_WAKE_UNLOCK
        val intent = Intent(this, TermuxService::class.java).setAction(action)
        runCatching { startService(intent) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Antigravity Agent Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when an Antigravity agent finishes a task"
                enableVibration(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun isNotifyDoneEnabled(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_NOTIFY_DONE, true)

    private fun setNotifyDoneEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_NOTIFY_DONE, enabled).apply()
    }

    private fun isNotifyForegroundEnabled(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_NOTIFY_FOREGROUND, false)

    private fun setNotifyForegroundEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_NOTIFY_FOREGROUND, enabled).apply()
    }

    private fun showAgentDoneNotification(title: String, body: String, force: Boolean = false) {
        if (!force) {
            if (!isNotifyDoneEnabled()) return
            if (isActivityForeground && !isNotifyForegroundEnabled()) return
        }
        val intent = Intent(this, AntigravityMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, piFlags)

        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_antigravity_notif)
            .setColor(Color.parseColor("#3B82F6"))
            .setContentTitle(title.ifBlank { "Antigravity" })
            .setContentText(body.ifBlank { "Agent task finished" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { "Agent task finished" }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID_AGENT_DONE, notif)
    }

    private fun startNotificationPoller() {
        notifyPollerJob?.cancel()
        notifyPollerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pending = AntigravityEngine.consumePendingNotification()
                if (pending != null) {
                    withContext(Dispatchers.Main) {
                        showAgentDoneNotification(pending.title, pending.body, force = false)
                    }
                }
                delay(1500L)
            }
        }
    }

    private inner class WebNotificationBridge {
        @JavascriptInterface
        fun notify(title: String?, body: String?) {
            runOnUiThread {
                showAgentDoneNotification(
                    title = title?.takeIf { it.isNotBlank() } ?: "Antigravity",
                    body = body?.takeIf { it.isNotBlank() } ?: "Agent task finished",
                    force = false
                )
            }
        }

        @JavascriptInterface
        fun onAgentJobFinished(summary: String?) {
            runOnUiThread {
                showAgentDoneNotification(
                    title = "Antigravity",
                    body = summary?.takeIf { it.isNotBlank() } ?: "Agent finished its task",
                    force = false
                )
            }
        }
    }

    private fun injectAgentNotificationBridge() {
        val js = """
            (function() {
                if (window.__agyNotifBridgeInstalled) return;
                window.__agyNotifBridgeInstalled = true;

                try {
                    function AgyNotification(title, options) {
                        var body = (options && options.body) ? String(options.body) : '';
                        if (window.AntigravityAndroid) {
                            window.AntigravityAndroid.notify(String(title || 'Antigravity'), body);
                        }
                    }
                    AgyNotification.permission = 'granted';
                    AgyNotification.requestPermission = function(cb) {
                        if (typeof cb === 'function') cb('granted');
                        return Promise.resolve('granted');
                    };
                    window.Notification = AgyNotification;
                } catch (e) {}

                var busySince = 0;
                var idleTimer = null;
                var lastNotifAt = 0;

                function isAgentBusy() {
                    if (document.querySelector('[aria-busy="true"]')) return true;
                    var btns = document.querySelectorAll('button, [role="button"]');
                    for (var i = 0; i < btns.length; i++) {
                        var b = btns[i];
                        if (!b.offsetParent) continue;
                        var label = ((b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.innerText || '')).trim().toLowerCase();
                        if (label === 'stop' || label === 'cancel' || label.indexOf('stop generating') !== -1 || label.indexOf('stop agent') !== -1) {
                            return true;
                        }
                    }
                    return false;
                }

                function checkState() {
                    var busy = isAgentBusy();
                    var now = Date.now();
                    if (busy) {
                        if (busySince === 0) busySince = now;
                        if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; }
                    } else if (busySince > 0) {
                        var runDuration = now - busySince;
                        if (runDuration >= 2500 && !idleTimer) {
                            idleTimer = setTimeout(function() {
                                idleTimer = null;
                                if (!isAgentBusy() && busySince > 0) {
                                    busySince = 0;
                                    if (Date.now() - lastNotifAt > 5000 && window.AntigravityAndroid) {
                                        lastNotifAt = Date.now();
                                        window.AntigravityAndroid.onAgentJobFinished('Agent finished its task');
                                    }
                                }
                            }, 2200);
                        } else if (runDuration < 2500) {
                            busySince = 0;
                        }
                    }
                }

                var observer = new MutationObserver(checkState);
                function attachObserver() {
                    if (document.body) {
                        observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['aria-busy', 'aria-label', 'title', 'disabled', 'class'] });
                    } else {
                        setTimeout(attachObserver, 500);
                    }
                }
                attachObserver();
                setInterval(checkState, 2000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }


    private fun bindViews() {
        rootView = findViewById(R.id.agy_root)
        webView = findViewById(R.id.agy_webview)
        reconnectBanner = findViewById(R.id.agy_reconnect_banner)
        txtReconnectBanner = findViewById(R.id.txt_reconnect_banner)
        terminalContainer = findViewById(R.id.agy_terminal_container)
        terminalView = findViewById(R.id.agy_terminal_view)
        termExtraKeysRow = findViewById(R.id.term_extra_keys_row)
        splashContainer = findViewById(R.id.agy_splash_container)
        splashStatus = findViewById(R.id.agy_splash_status)
        setupContainer = findViewById(R.id.agy_setup_container)
        onboardingContainer = findViewById(R.id.agy_onboarding_container)
        floatingPill = findViewById(R.id.agy_floating_pill)
        pillControlsBtn = findViewById(R.id.pill_btn_controls)
        pillCollapseBtn = findViewById(R.id.pill_btn_collapse)

        btnGrantStorage = findViewById(R.id.btn_grant_storage)
        btnGrantBattery = findViewById(R.id.btn_grant_battery)
        txtDetectedBackup = findViewById(R.id.txt_detected_backup)
        onboardingProgressBar = findViewById(R.id.onboarding_progress_bar)
        txtOnboardingProgress = findViewById(R.id.txt_onboarding_progress)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.parseColor("#0B0D10"))
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.addJavascriptInterface(WebNotificationBridge(), "AntigravityAndroid")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host?.lowercase(Locale.US) ?: ""
                if (host == "localhost" || host == "127.0.0.1") {
                    return false
                }
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && (url.contains("localhost:4400") || url.contains("127.0.0.1:4400"))) {
                    reconnectBanner.visibility = View.GONE
                    injectAgentNotificationBridge()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread {
                    val host = request.origin?.host?.lowercase(Locale.US) ?: ""
                    if (host != "localhost" && host != "127.0.0.1") {
                        runCatching { request.deny() }
                        return@runOnUiThread
                    }
                    val resources = request.resources ?: emptyArray()
                    if (!resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        runCatching { request.deny() }
                        return@runOnUiThread
                    }
                    val audioOnly = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (ContextCompat.checkSelfPermission(
                            this@AntigravityMainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        runCatching { request.grant(audioOnly) }
                    } else {
                        pendingWebPermissionRequest?.let { runCatching { it.deny() } }
                        pendingWebPermissionRequest = request
                        webMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                val intent = try {
                    fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                } catch (_: Exception) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                }
                runCatching {
                    fileChooserLauncher.launch(intent)
                }.onFailure {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                }
                return true
            }
        }
    }

    private fun setupEmbeddedTerminalView() {
        terminalView.setTextSize(terminalTextSize)
        terminalView.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                val next = (terminalTextSize * scale).toInt().coerceIn(16, 56)
                updateTerminalFontSize(next)
                return scale
            }

            override fun onSingleTapUp(e: MotionEvent?) {
                terminalView.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = terminalContainer.visibility == View.VISIBLE
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
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

        findViewById<View>(R.id.btn_term_zoom_out).setOnClickListener {
            updateTerminalFontSize((terminalTextSize - 3).coerceIn(16, 56))
        }
        findViewById<View>(R.id.btn_term_zoom_in).setOnClickListener {
            updateTerminalFontSize((terminalTextSize + 3).coerceIn(16, 56))
        }
        findViewById<View>(R.id.btn_close_terminal).setOnClickListener {
            hideTerminalView()
        }

        buildTerminalExtraKeys()
    }

    private fun updateTerminalFontSize(size: Int) {
        terminalTextSize = size
        terminalView.setTextSize(size)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(KEY_TERM_TEXT_SIZE, size).apply()
    }

    private fun buildTerminalExtraKeys() {
        termExtraKeysRow.removeAllViews()
        val dp = resources.displayMetrics.density
        val keys = listOf(
            "ESC" to "\u001b",
            "TAB" to "\t",
            "CTRL" to "__CTRL__",
            "ALT" to "__ALT__",
            "↑" to "\u001b[A",
            "↓" to "\u001b[B",
            "←" to "\u001b[D",
            "→" to "\u001b[C",
            "/" to "/",
            "-" to "-",
            "|" to "|",
            "~" to "~"
        )
        for ((label, seq) in keys) {
            val btn = TextView(this).apply {
                text = label
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#E6EAF0"))
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.agy_btn_secondary)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (30 * dp).toInt()
                ).apply {
                    marginEnd = (6 * dp).toInt()
                }
                layoutParams = lp
                minWidth = (36 * dp).toInt()
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
                btn.setOnClickListener {
                    terminalView.currentSession?.write(seq)
                }
            }
            termExtraKeysRow.addView(btn)
        }
    }

    private fun updateModifierStyles() {
        ctrlKeyBtn?.setBackgroundResource(if (ctrlArmed) R.drawable.agy_btn_primary else R.drawable.agy_btn_secondary)
        altKeyBtn?.setBackgroundResource(if (altArmed) R.drawable.agy_btn_primary else R.drawable.agy_btn_secondary)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingPill() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var dX = 0f
        var dY = 0f
        var dragging = false

        val dragListener = View.OnTouchListener { _, event ->
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
                        snapPillToEdge(savePos = true)
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

        pillControlsBtn.setOnClickListener { openControlBottomSheet() }
        pillCollapseBtn.setOnClickListener { togglePillCollapsed() }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        pillCollapsed = prefs.getBoolean(KEY_PILL_COLLAPSED, false)
        pillControlsBtn.visibility = if (pillCollapsed) View.GONE else View.VISIBLE
        pillCollapseBtn.text = if (pillCollapsed) "‹" else "›"
    }

    private fun restorePillPositionIfNeeded() {
        if (pillRestored) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.contains(KEY_PILL_Y_FRAC)) return
        floatingPill.post {
            val w = rootView.width
            val h = rootView.height
            if (w <= 0 || h <= 0) return@post
            pillRestored = true
            val xFrac = prefs.getFloat(KEY_PILL_X_FRAC, 1f)
            val yFrac = prefs.getFloat(KEY_PILL_Y_FRAC, 0.5f)
            val margin = 10 * resources.displayMetrics.density
            val targetX = if (xFrac < 0.5f) margin else (w - floatingPill.width - margin).coerceAtLeast(margin)
            val targetY = (yFrac * h).coerceIn(margin, (h - floatingPill.height - margin).coerceAtLeast(margin))
            floatingPill.x = targetX
            floatingPill.y = targetY
        }
    }

    private fun snapPillToEdge(savePos: Boolean = false) {
        val parentW = rootView.width
        val parentH = rootView.height
        if (parentW <= 0) return
        val margin = (10 * resources.displayMetrics.density)
        val center = floatingPill.x + floatingPill.width / 2f
        val onLeft = center < parentW / 2f
        val targetX = if (onLeft) {
            margin
        } else {
            (parentW - floatingPill.width - margin).coerceAtLeast(margin)
        }
        floatingPill.animate().x(targetX).setDuration(180).start()
        if (savePos && parentH > 0) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putFloat(KEY_PILL_X_FRAC, if (onLeft) 0f else 1f)
                .putFloat(KEY_PILL_Y_FRAC, (floatingPill.y / parentH).coerceIn(0.05f, 0.92f))
                .apply()
        }
    }

    private fun togglePillCollapsed() {
        pillCollapsed = !pillCollapsed
        pillControlsBtn.visibility = if (pillCollapsed) View.GONE else View.VISIBLE
        pillCollapseBtn.text = if (pillCollapsed) "‹" else "›"
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_PILL_COLLAPSED, pillCollapsed)
            .apply()
        floatingPill.post { snapPillToEdge(savePos = true) }
    }

    private fun setupButtons() {
        btnGrantStorage.setOnClickListener { requestAllFilesPermission() }
        btnGrantBattery.setOnClickListener { requestBatteryOptimizationExclusion() }
        findViewById<View>(R.id.btn_setup_skip).setOnClickListener {
            setupDismissedThisSession = true
            proceedAfterSetup()
        }
        findViewById<View>(R.id.btn_setup_continue).setOnClickListener {
            setupDismissedThisSession = true
            proceedAfterSetup()
        }

        findViewById<View>(R.id.btn_onboarding_auto_install).setOnClickListener {
            onboardingProgressBar.visibility = View.VISIBLE
            txtOnboardingProgress.visibility = View.VISIBLE
            onboardingProgressBar.progress = 5
            txtOnboardingProgress.text = "Starting automatic Antigravity CLI install..."
            scope.launch {
                val res = AntigravityEngine.installAntigravityCli(this@AntigravityMainActivity) { pct, msg ->
                    runOnUiThread {
                        onboardingProgressBar.progress = pct
                        txtOnboardingProgress.text = msg
                    }
                }
                res.onSuccess {
                    Toast.makeText(this@AntigravityMainActivity, "Installed! Booting Antigravity...", Toast.LENGTH_SHORT).show()
                    bootAntigravityHub()
                }.onFailure { err ->
                    txtOnboardingProgress.text = "Install failed: ${err.message}"
                    Toast.makeText(this@AntigravityMainActivity, "Auto-install failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        findViewById<View>(R.id.btn_copy_port_cmd).setOnClickListener {
            val cmd = "pkg install -y zstd tar && tar -cf - -C /data/data/com.termux/files --exclude='./usr/var/cache/*' --exclude='./usr/tmp/*' ./usr ./home | zstd -6 -T2 -o /sdcard/Download/termux-port.tar.zst && zstd -t /sdcard/Download/termux-port.tar.zst"
            copyToClipboard("Termux Port Command", cmd)
            Toast.makeText(this, "Copied Termux port command to clipboard", Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.btn_onboarding_import).setOnClickListener {
            promptRestoreArchive(
                progressBar = onboardingProgressBar,
                statusView = txtOnboardingProgress,
                sheetToDismiss = null
            )
        }
        findViewById<View>(R.id.btn_onboarding_terminal).setOnClickListener {
            showTerminalView()
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        isServiceBound = true
        termuxService = (service as TermuxService.LocalBinder).service
        if (!bootstrapComplete) {
            runBootstrapSequence()
        } else {
            ensureBackgroundTerminalSession()
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        isServiceBound = false
        termuxService = null
    }

    private fun runBootstrapSequence() {
        if (BuildConfig.AGY_LITE_UPDATE && !AntigravityEngine.isBootstrapReady()) {
            splashStatus.text = "This is a Lite Update APK.\nInstall the full AntigravityStandalone.apk first, then apply this update."
            return
        }
        splashStatus.text = "Preparing embedded Linux engine..."
        TermuxInstaller.setupBootstrapIfNeeded(this) {
            if (isFinishing || isDestroyed) return@setupBootstrapIfNeeded
            bootstrapComplete = true
            ensureBackgroundTerminalSession()
            scope.launch {
                withContext(Dispatchers.IO) {
                    AntigravityEngine.provisionBridgeAssets(this@AntigravityMainActivity)
                }
                checkPermissionsOrProceed()
            }
        }
    }

    private fun ensureBackgroundTerminalSession() {
        val svc = termuxService ?: return
        val session = if (svc.isTermuxSessionsEmpty) {
            svc.createTermuxSession(null, null, null, null, false, null)?.terminalSession
        } else {
            svc.getTermuxSession(0)?.terminalSession
        }
        if (session != null) {
            terminalView.attachSession(session)
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun checkPermissionsOrProceed() {
        val storageOk = hasAllFilesAccess()
        val batteryOk = isIgnoringBatteryOptimizations()
        if ((!storageOk || !batteryOk) && !setupDismissedThisSession) {
            splashContainer.visibility = View.GONE
            onboardingContainer.visibility = View.GONE
            webView.visibility = View.GONE
            floatingPill.visibility = View.GONE
            setupContainer.visibility = View.VISIBLE
            updateSetupButtons()
        } else {
            proceedAfterSetup()
        }
    }

    private fun updateSetupButtons() {
        val storageOk = hasAllFilesAccess()
        val batteryOk = isIgnoringBatteryOptimizations()
        btnGrantStorage.text = if (storageOk) "All Files Access Granted" else "Grant All Files Access"
        btnGrantStorage.alpha = if (storageOk) 0.65f else 1.0f
        btnGrantBattery.text = if (batteryOk) "Battery Optimization Disabled" else "Disable Battery Optimization"
        btnGrantBattery.alpha = if (batteryOk) 0.65f else 1.0f
    }

    override fun onResume() {
        super.onResume()
        isActivityForeground = true
        if (bootstrapComplete && setupContainer.visibility == View.VISIBLE) {
            updateSetupButtons()
            if (hasAllFilesAccess() && isIgnoringBatteryOptimizations()) {
                proceedAfterSetup()
            }
        } else if (bootstrapComplete && onboardingContainer.visibility == View.VISIBLE) {
            refreshDetectedBackupBanner()
            if (AntigravityEngine.isAgyInstalled()) {
                bootAntigravityHub()
            }
        } else if (bootstrapComplete && webView.visibility == View.VISIBLE && !BackupRestoreEngine.isBusy()) {
            scope.launch {
                val alive = withContext(Dispatchers.IO) { AntigravityEngine.isPortOpen(AntigravityEngine.HUB_PORT) }
                if (!alive && AntigravityEngine.isAgyInstalled()) {
                    txtReconnectBanner.text = "Reconnecting to Antigravity Hub..."
                    reconnectBanner.visibility = View.VISIBLE
                    AntigravityEngine.startHubAndBridge(this@AntigravityMainActivity)
                    val newUrl = AntigravityEngine.pollForHubUrl(12000L)
                    if (newUrl != null) {
                        currentHubUrl = newUrl
                        webView.loadUrl(newUrl)
                    } else {
                        txtReconnectBanner.text = "Hub offline — tap Control Center to restart"
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityForeground = false
    }

    private fun requestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                101
            )
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExclusion() {
        runCatching {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun proceedAfterSetup() {
        setupContainer.visibility = View.GONE
        if (AntigravityEngine.isAgyInstalled()) {
            bootAntigravityHub()
        } else {
            showOnboardingScreen()
        }
    }

    private fun showOnboardingScreen() {
        splashContainer.visibility = View.GONE
        setupContainer.visibility = View.GONE
        webView.visibility = View.GONE
        floatingPill.visibility = View.GONE
        onboardingContainer.visibility = View.VISIBLE
        refreshDetectedBackupBanner()
    }

    private fun refreshDetectedBackupBanner() {
        val master = File(BackupRestoreEngine.DEFAULT_MASTER_BACKUP)
        if (master.isFile) {
            val mb = master.length() / (1024.0 * 1024.0)
            txtDetectedBackup.visibility = View.VISIBLE
            txtDetectedBackup.text = String.format(
                Locale.US,
                "Detected master backup: %s (%.1f MB)",
                master.name,
                mb
            )
        } else {
            val others = BackupRestoreEngine.findDownloadBackups()
            if (others.isNotEmpty()) {
                val f = others.first()
                val mb = f.length() / (1024.0 * 1024.0)
                txtDetectedBackup.visibility = View.VISIBLE
                txtDetectedBackup.text = String.format(Locale.US, "Detected backup: %s (%.1f MB)", f.name, mb)
            } else {
                txtDetectedBackup.visibility = View.GONE
            }
        }
    }

    private fun bootAntigravityHub() {
        onboardingContainer.visibility = View.GONE
        setupContainer.visibility = View.GONE
        splashContainer.visibility = View.VISIBLE
        splashStatus.text = "Starting Antigravity Hub (port 4400)..."

        scope.launch {
            AntigravityEngine.startHubAndBridge(this@AntigravityMainActivity)
            val hubUrl = AntigravityEngine.pollForHubUrl(20000L)
            if (hubUrl != null) {
                currentHubUrl = hubUrl
                splashContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                floatingPill.visibility = View.VISIBLE
                restorePillPositionIfNeeded()
                webView.loadUrl(hubUrl)
            } else {
                splashStatus.text = "Antigravity Hub did not respond on port 4400.\nOpening controls or built-in terminal..."
                floatingPill.visibility = View.VISIBLE
                restorePillPositionIfNeeded()
                Toast.makeText(
                    this@AntigravityMainActivity,
                    "Hub startup timed out — check Control Center or Built-in Terminal",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showTerminalView() {
        ensureBackgroundTerminalSession()
        terminalContainer.visibility = View.VISIBLE
        floatingPill.visibility = View.GONE
        terminalView.requestFocus()
    }

    private fun hideTerminalView() {
        terminalContainer.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(terminalView.windowToken, 0)
        if (webView.visibility == View.VISIBLE) {
            floatingPill.visibility = View.VISIBLE
        } else if (AntigravityEngine.isAgyInstalled()) {
            bootAntigravityHub()
        } else {
            showOnboardingScreen()
        }
    }

    private fun showHubLogsDialog() {
        scope.launch {
            val logContent = withContext(Dispatchers.IO) {
                buildString {
                    val agyLog = File(AntigravityEngine.AGY_LOG)
                    val bridgeLog = File("${AntigravityEngine.BRIDGE_DIR}/bridge.log")
                    append("=== agy.log (last 80 lines) ===\n")
                    if (agyLog.isFile) {
                        append(agyLog.readLines().takeLast(80).joinToString("\n"))
                    } else {
                        append("(no log yet)")
                    }
                    append("\n\n=== bridge.log (last 40 lines) ===\n")
                    if (bridgeLog.isFile) {
                        append(bridgeLog.readLines().takeLast(40).joinToString("\n"))
                    } else {
                        append("(no log yet)")
                    }
                }
            }
            val tv = TextView(this@AntigravityMainActivity).apply {
                text = logContent
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(Color.parseColor("#E6EAF0"))
                setBackgroundColor(Color.parseColor("#0B0D10"))
                setPadding(32, 24, 32, 24)
                setTextIsSelectable(true)
            }
            val scroll = ScrollView(this@AntigravityMainActivity).apply { addView(tv) }
            AlertDialog.Builder(this@AntigravityMainActivity)
                .setTitle("Antigravity Hub Logs")
                .setView(scroll)
                .setPositiveButton("Copy") { _, _ ->
                    copyToClipboard("Antigravity Logs", logContent)
                    Toast.makeText(this@AntigravityMainActivity, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun openControlBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.Theme_Antigravity_BottomSheet)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_antigravity_controls, null)
        dialog.setContentView(view)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val dotEngine = view.findViewById<View>(R.id.dot_engine)
        val txtEngine = view.findViewById<TextView>(R.id.txt_status_engine)
        val dotHub = view.findViewById<View>(R.id.dot_hub)
        val txtHub = view.findViewById<TextView>(R.id.txt_status_hub)

        val switchScreenOn = view.findViewById<Switch>(R.id.switch_keep_screen_on)
        val switchCpuWake = view.findViewById<Switch>(R.id.switch_cpu_wakelock)
        val switchNotifyDone = view.findViewById<Switch>(R.id.switch_notify_done)
        val switchNotifyFg = view.findViewById<Switch>(R.id.switch_notify_foreground)

        switchScreenOn.isChecked = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        switchCpuWake.isChecked = prefs.getBoolean(KEY_CPU_WAKELOCK, false)
        switchNotifyDone.isChecked = isNotifyDoneEnabled()
        switchNotifyFg.isChecked = isNotifyForegroundEnabled()

        switchScreenOn.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, checked).apply()
            applyKeepScreenOn(checked)
        }
        switchCpuWake.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_CPU_WAKELOCK, checked).apply()
            applyCpuWakeLock(checked)
        }
        switchNotifyDone.setOnCheckedChangeListener { _, checked -> setNotifyDoneEnabled(checked) }
        switchNotifyFg.setOnCheckedChangeListener { _, checked -> setNotifyForegroundEnabled(checked) }

        view.findViewById<View>(R.id.sheet_btn_test_notif).setOnClickListener {
            showAgentDoneNotification("Antigravity", "Agent task finished — ready for review.", force = true)
            Toast.makeText(this, "Test notification sent", Toast.LENGTH_SHORT).show()
        }

        val progressBar = view.findViewById<ProgressBar>(R.id.sheet_backup_progress)
        val statusText = view.findViewById<TextView>(R.id.sheet_backup_status)

        scope.launch {
            val health = AntigravityEngine.getHealth()
            dotEngine.setBackgroundResource(if (health.engineReady) R.drawable.agy_dot_ok else R.drawable.agy_dot_off)
            txtEngine.text = if (health.engineReady) "Embedded Engine: Active (${Build.SUPPORTED_ABIS.firstOrNull() ?: "Linux"})" else "Embedded Engine: Initializing"

            dotHub.setBackgroundResource(if (health.hubReady) R.drawable.agy_dot_ok else R.drawable.agy_dot_off)
            txtHub.text = if (health.hubReady) "Antigravity Hub (4400): Online" else "Antigravity Hub (4400): Offline"
        }

        view.findViewById<View>(R.id.sheet_btn_native_acp).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, com.termux.app.acp.AcpNativeActivity::class.java))
        }

        view.findViewById<View>(R.id.sheet_btn_reload).setOnClickListener {
            dialog.dismiss()
            val url = currentHubUrl ?: AntigravityEngine.parseHubUrlFromLog()
            if (url != null) {
                currentHubUrl = url
                webView.loadUrl(url)
            } else {
                bootAntigravityHub()
            }
        }

        view.findViewById<View>(R.id.sheet_btn_restart).setOnClickListener {
            dialog.dismiss()
            txtReconnectBanner.text = "Restarting Antigravity Hub..."
            reconnectBanner.visibility = View.VISIBLE
            scope.launch {
                val newUrl = AntigravityEngine.restartHub(this@AntigravityMainActivity)
                if (newUrl != null) {
                    currentHubUrl = newUrl
                    splashContainer.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    floatingPill.visibility = View.VISIBLE
                    webView.loadUrl(newUrl)
                } else {
                    reconnectBanner.visibility = View.GONE
                    Toast.makeText(this@AntigravityMainActivity, "Failed to restart Hub", Toast.LENGTH_LONG).show()
                }
            }
        }

        view.findViewById<View>(R.id.sheet_btn_terminal).setOnClickListener {
            dialog.dismiss()
            if (terminalContainer.visibility == View.VISIBLE) {
                hideTerminalView()
            } else {
                showTerminalView()
            }
        }

        view.findViewById<View>(R.id.sheet_btn_logs).setOnClickListener {
            dialog.dismiss()
            showHubLogsDialog()
        }

        view.findViewById<View>(R.id.sheet_btn_backup_full).setOnClickListener {
            runBackupFlow(BackupMode.FULL, progressBar, statusText)
        }

        view.findViewById<View>(R.id.sheet_btn_backup_packages).setOnClickListener {
            runBackupFlow(BackupMode.PACKAGES_ONLY, progressBar, statusText)
        }

        view.findViewById<View>(R.id.sheet_btn_restore).setOnClickListener {
            promptRestoreArchive(progressBar, statusText, dialog)
        }

        dialog.show()
    }

    private fun runBackupFlow(
        mode: BackupMode,
        progressBar: ProgressBar,
        statusText: TextView
    ) {
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        progressBar.progress = 2
        statusText.text = "Starting ${mode.label}..."

        scope.launch {
            val result = BackupRestoreEngine.createBackup(
                context = this@AntigravityMainActivity,
                mode = mode
            ) { pct, msg ->
                runOnUiThread {
                    progressBar.progress = pct
                    statusText.text = msg
                }
            }
            result.onSuccess { outFile ->
                val mb = outFile.length() / (1024.0 * 1024.0)
                Toast.makeText(
                    this@AntigravityMainActivity,
                    String.format(Locale.US, "Saved %s (%.1f MB)", outFile.name, mb),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { err ->
                statusText.text = "Error: ${err.message}"
                Toast.makeText(this@AntigravityMainActivity, "Backup failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptRestoreArchive(
        progressBar: ProgressBar,
        statusView: TextView,
        sheetToDismiss: BottomSheetDialog?
    ) {
        val found = BackupRestoreEngine.findDownloadBackups()
        val labels = found.map { f ->
            val mb = f.length() / (1024.0 * 1024.0)
            String.format(Locale.US, "%s (%.1f MB)", f.name, mb)
        }.toMutableList()
        labels.add("Browse file picker...")

        AlertDialog.Builder(this)
            .setTitle("Select Offline Backup (.tar.zst)")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < found.size) {
                    executeRestoreFile(found[which], progressBar, statusView, sheetToDismiss)
                } else {
                    pendingRestoreSheetProgress = progressBar
                    pendingRestoreSheetStatus = statusView
                    pendingRestoreDialog = sheetToDismiss
                    backupFilePickerLauncher.launch(arrayOf("*/*"))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeRestoreFile(
        archiveFile: File,
        progressBar: ProgressBar,
        statusView: TextView,
        sheetToDismiss: BottomSheetDialog?
    ) {
        progressBar.visibility = View.VISIBLE
        statusView.visibility = View.VISIBLE
        progressBar.progress = 1
        statusView.text = "Preparing restore..."

        scope.launch {
            val result = BackupRestoreEngine.restoreBackup(
                context = this@AntigravityMainActivity,
                archiveFile = archiveFile
            ) { pct, msg ->
                runOnUiThread {
                    progressBar.progress = pct
                    statusView.text = msg
                }
            }
            result.onSuccess {
                sheetToDismiss?.dismiss()
                Toast.makeText(this@AntigravityMainActivity, "Restore complete! Booting Antigravity...", Toast.LENGTH_SHORT).show()
                bootAntigravityHub()
            }.onFailure { err ->
                statusView.text = "Restore failed: ${err.message}"
                Toast.makeText(this@AntigravityMainActivity, "Restore failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restoreFromContentUri(uri: Uri) {
        val progressBar = pendingRestoreSheetProgress ?: onboardingProgressBar
        val statusView = pendingRestoreSheetStatus ?: txtOnboardingProgress
        val sheet = pendingRestoreDialog

        var displayName = "backup.tar.zst"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                displayName = cursor.getString(idx) ?: displayName
            }
        }

        progressBar.visibility = View.VISIBLE
        statusView.visibility = View.VISIBLE
        progressBar.progress = 1
        statusView.text = "Reading $displayName..."

        scope.launch {
            val result = BackupRestoreEngine.restoreFromInputStream(
                context = this@AntigravityMainActivity,
                inputStreamProvider = { contentResolver.openInputStream(uri) },
                displayName = displayName
            ) { pct, msg ->
                runOnUiThread {
                    progressBar.progress = pct
                    statusView.text = msg
                }
            }
            result.onSuccess {
                sheet?.dismiss()
                Toast.makeText(this@AntigravityMainActivity, "Restore complete! Booting Antigravity...", Toast.LENGTH_SHORT).show()
                bootAntigravityHub()
            }.onFailure { err ->
                statusView.text = "Restore failed: ${err.message}"
                Toast.makeText(this@AntigravityMainActivity, "Restore failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (terminalContainer.visibility == View.VISIBLE) {
            hideTerminalView()
            return
        }
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPressedMs < 2000L) {
            super.onBackPressed()
        } else {
            lastBackPressedMs = now
            Toast.makeText(this, "Press back again to exit Antigravity", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        notifyPollerJob?.cancel()
        pendingWebPermissionRequest?.let { runCatching { it.deny() } }
        pendingWebPermissionRequest = null
        if (isServiceBound) {
            runCatching { unbindService(this) }
            isServiceBound = false
        }
        scope.cancel()
    }
}
