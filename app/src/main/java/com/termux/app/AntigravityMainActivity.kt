package com.termux.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Message
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.termux.BuildConfig
import com.termux.R
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.shared.termux.terminal.io.BellHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

class AntigravityMainActivity : AppCompatActivity(), ServiceConnection {

    companion object {
        private const val LOG_TAG = "AntigravityMain"
        private const val PREFS_NAME = "antigravity_prefs"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_CPU_WAKELOCK = "cpu_wakelock"
        private const val KEY_PILL_X_FRAC = "pill_x_frac"
        private const val KEY_PILL_Y_FRAC = "pill_y_frac"
        private const val KEY_PILL_COLLAPSED = "pill_collapsed"
        private const val KEY_TERM_TEXT_SIZE = "term_text_size"
        private const val KEY_SETUP_DISMISSED = "setup_dismissed"
        private const val STATE_TERMINAL_VISIBLE = "state_terminal_visible"
    }


    private lateinit var rootView: View
    private lateinit var webView: WebView
    private lateinit var reconnectBanner: View
    private lateinit var txtReconnectBanner: TextView
    private lateinit var terminalContainer: View
    private lateinit var terminalView: TerminalView
    private lateinit var termExtraKeysRow: LinearLayout
    private lateinit var splashContainer: View
    private lateinit var splashStatus: TextView
    private lateinit var splashActions: View
    private lateinit var btnSplashRetry: View
    private lateinit var btnSplashTerminal: View
    private lateinit var btnSplashLogs: View
    private lateinit var setupContainer: View
    private lateinit var onboardingContainer: View
    private lateinit var floatingPill: LinearLayout
    private lateinit var pillControlsBtn: ImageButton
    private lateinit var pillCollapseBtn: TextView

    private lateinit var btnGrantStorage: TextView
    private lateinit var btnGrantBattery: TextView
    private lateinit var txtDetectedBackup: TextView
    private lateinit var btnOnboardingAutoInstall: TextView
    private lateinit var btnOnboardingImport: TextView
    private lateinit var onboardingProgressBar: ProgressBar
    private lateinit var txtOnboardingProgress: TextView

    private var termuxService: TermuxService? = null
    private var isServiceBound = false
    private var bootstrapComplete = false
    private var setupDismissed = false
    private var currentHubUrl: String? = null
    private var lastRenderCrashMs = 0L
    private var renderCrashCount = 0
    private var pendingTerminalRestore = false
    private val accountManager = AntigravityAccountManager()
    @Volatile
    private var isSwitchingAccount = false
    @Volatile
    private var lastQuotaTriggerAt = 0L
    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logger.logDebug(LOG_TAG, "Received service stop broadcast, finishing activity")
            finishAffinity()
        }
    }
    private var isHubReconnectInFlight = false
    private var isBootInFlight = false
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

    private val embeddedSessionClient = object : TermuxTerminalSessionClientBase() {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (terminalView.currentSession == changedSession) {
                terminalView.onScreenUpdated()
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            runOnUiThread {
                val exitStatus = finishedSession.exitStatus
                if (exitStatus == 0 || exitStatus == 130) {
                    termuxService?.removeTermuxSession(finishedSession)
                }
                if (terminalView.currentSession == finishedSession) {
                    terminalView.onScreenUpdated()
                }
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (!text.isNullOrEmpty()) {
                copyToClipboard("Antigravity Terminal", text)
            }
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.coerceToText(this@AntigravityMainActivity)?.toString()
                if (!text.isNullOrEmpty()) {
                    terminalView.mEmulator?.paste(text)
                }
            }
        }

        override fun onBell(session: TerminalSession) {
            BellHandler.getInstance(this@AntigravityMainActivity).doBell()
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
            termuxService?.getTermuxSessionForTerminalSession(session)?.executionCommand?.mPid = pid
        }
    }

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
        setupDismissed = prefs.getBoolean(KEY_SETUP_DISMISSED, false)
        pendingTerminalRestore = savedInstanceState?.getBoolean(STATE_TERMINAL_VISIBLE, false) == true
        applyKeepScreenOn(prefs.getBoolean(KEY_KEEP_SCREEN_ON, false))

        AntigravityNotifier.createChannel(this)
        requestNotificationPermissionIfNeeded()

        bindViews()
        setupWebView()
        setupEmbeddedTerminalView()
        setupFloatingPill()
        setupButtons()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })
        handleIncomingShareIntent(intent)

        val serviceIntent = Intent(this, TermuxService::class.java)
        runCatching { startService(serviceIntent) }
        bindService(serviceIntent, this, Context.BIND_AUTO_CREATE)

        if (prefs.getBoolean(KEY_CPU_WAKELOCK, false)) {
            applyCpuWakeLock(true)
        }

        val serviceStopFilter = IntentFilter("com.agy.os.ACTION_SERVICE_STOPPED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStoppedReceiver, serviceStopFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceStoppedReceiver, serviceStopFilter)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)

    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val type = intent.type ?: "*/*"

        val uris = mutableListOf<Uri>()
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            val list = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (list != null) uris.addAll(list)
        } else {
            @Suppress("DEPRECATION")
            val singleUri = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (singleUri != null) uris.add(singleUri)
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (uris.isEmpty()) {
            if (type.startsWith("text/") || !sharedText.isNullOrEmpty()) {
                if (!sharedText.isNullOrEmpty()) {
                    copyToClipboard("Antigravity Share", sharedText)
                    Toast.makeText(this, "Shared text copied — paste into Antigravity", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        lifecycleScope.launch {
            val savedPaths = withContext(Dispatchers.IO) {
                uris.mapNotNull { AntigravityFileHelper.saveSharedUriToInbox(this@AntigravityMainActivity, it) }
            }
            if (savedPaths.isNotEmpty()) {
                val joined = savedPaths.joinToString("\n")
                copyToClipboard("Antigravity File", joined)
                val msg = if (savedPaths.size == 1) {
                    "Saved to ${savedPaths.first()} (path copied)"
                } else {
                    "Saved ${savedPaths.size} files to inbox (paths copied)"
                }
                Toast.makeText(this@AntigravityMainActivity, msg, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@AntigravityMainActivity, "Could not import shared file", Toast.LENGTH_SHORT).show()
            }
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showAgentDoneNotification(title: String, body: String, force: Boolean = false) {
        AntigravityNotifier.showAgentDoneNotification(this, title, body, force)
    }

    private inner class WebNotificationBridge {
        @JavascriptInterface
        fun notify(title: String?, body: String?) {
            runOnUiThread {
                if (!AntigravityFileHelper.isTrustedHubUri(webView.url)) return@runOnUiThread
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
                if (!AntigravityFileHelper.isTrustedHubUri(webView.url)) return@runOnUiThread
                showAgentDoneNotification(
                    title = "Antigravity",
                    body = summary?.takeIf { it.isNotBlank() } ?: "Agent finished its task",
                    force = false
                )
            }
        }

        @JavascriptInterface
        fun saveDownloadedBase64(filename: String?, base64Data: String?) {
            if (base64Data.isNullOrBlank()) return
            lifecycleScope.launch {
                if (!AntigravityFileHelper.isTrustedHubUri(webView.url)) return@launch
                val res = withContext(Dispatchers.IO) {
                    AntigravityFileHelper.saveBase64ToDownloads(this@AntigravityMainActivity, filename, base64Data)
                }
                res.onSuccess { displayPath ->
                    Toast.makeText(this@AntigravityMainActivity, "Saved to $displayPath", Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    Toast.makeText(this@AntigravityMainActivity, "Download failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun notifyDownloadTooLarge() {
            runOnUiThread {
                if (!AntigravityFileHelper.isTrustedHubUri(webView.url)) return@runOnUiThread
                Toast.makeText(this@AntigravityMainActivity, "Blob download exceeds 25 MB limit", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun onQuotaExhausted(reason: String?) {
            if (!AntigravityFileHelper.isTrustedHubUri(webView.url)) return
            handleQuotaExhaustedSignal(reason)
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
                var checkScheduled = false;
                var lastNotifAt = 0;

                function isAgentBusy() {
                    if (document.querySelector('[aria-busy="true"]')) return true;
                    var btns = document.querySelectorAll('button, [role="button"]');
                    for (var i = 0; i < btns.length; i++) {
                        var b = btns[i];
                        var label = ((b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.textContent || '')).trim().toLowerCase();
                        if (label === 'stop' || label.indexOf('stop generating') !== -1 || label.indexOf('stop agent') !== -1 || label.indexOf('stop response') !== -1) {
                            if (b.offsetParent !== null) return true;
                        }
                    }
                    return false;
                }

                function checkState() {
                    checkScheduled = false;
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

                function scheduleCheck() {
                    if (!checkScheduled) {
                        checkScheduled = true;
                        setTimeout(checkState, 300);
                    }
                }

                var observer = new MutationObserver(scheduleCheck);
                function attachObserver() {
                    if (document.body) {
                        observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['aria-busy', 'aria-label', 'title', 'disabled'] });
                    } else {
                        setTimeout(attachObserver, 500);
                    }
                }
                attachObserver();
                setInterval(checkState, 2000);

                // Quota exhaustion detection (fetch, XHR, and visible error banners)
                var lastQuotaSignalAt = 0;
                function isQuotaText(txt) {
                    if (!txt) return false;
                    var lower = String(txt).toLowerCase();
                    return lower.indexOf('resource_exhausted') !== -1 ||
                        lower.indexOf('quota exhausted') !== -1 ||
                        lower.indexOf('quota exceeded') !== -1 ||
                        lower.indexOf('exceeded your current quota') !== -1 ||
                        lower.indexOf('you have reached your') !== -1 ||
                        lower.indexOf('usage limit reached') !== -1 ||
                        lower.indexOf('rate limit exceeded') !== -1 ||
                        lower.indexOf('model quota') !== -1 ||
                        lower.indexOf('capacity exhausted') !== -1 ||
                        (lower.indexOf('429') !== -1 && (lower.indexOf('quota') !== -1 || lower.indexOf('rate') !== -1 || lower.indexOf('limit') !== -1));
                }
                function signalQuota(reason) {
                    var now = Date.now();
                    if (now - lastQuotaSignalAt < 15000) return;
                    lastQuotaSignalAt = now;
                    if (window.AntigravityAndroid && typeof window.AntigravityAndroid.onQuotaExhausted === 'function') {
                        window.AntigravityAndroid.onQuotaExhausted(String(reason || 'quota_exhausted').slice(0, 240));
                    }
                }

                if (window.fetch && !window.__agyFetchWrapped) {
                    window.__agyFetchWrapped = true;
                    var origFetch = window.fetch;
                    window.fetch = function() {
                        return origFetch.apply(this, arguments).then(function(resp) {
                            try {
                                if (resp && resp.status === 429) {
                                    signalQuota('HTTP 429 rate limit');
                                } else if (resp && !resp.ok && resp.status >= 400) {
                                    resp.clone().text().then(function(body) {
                                        if (isQuotaText(body)) signalQuota(body);
                                    }).catch(function() {});
                                }
                            } catch (e) {}
                            return resp;
                        });
                    };
                }

                if (window.XMLHttpRequest && !window.__agyXhrWrapped) {
                    window.__agyXhrWrapped = true;
                    var origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function() {
                        this.addEventListener('load', function() {
                            try {
                                if (this.status === 429) {
                                    signalQuota('XHR 429 rate limit');
                                } else if (this.status >= 400 && typeof this.responseText === 'string' && isQuotaText(this.responseText)) {
                                    signalQuota(this.responseText);
                                }
                            } catch (e) {}
                        });
                        return origOpen.apply(this, arguments);
                    };
                }

                setInterval(function() {
                    try {
                        var alerts = document.querySelectorAll('[role="alert"], [class*="error"], [class*="quota"], [class*="banner"], [class*="toast"]');
                        for (var i = 0; i < alerts.length; i++) {
                            var el = alerts[i];
                            if (el && el.offsetParent !== null) {
                                var txt = (el.textContent || '').trim();
                                if (txt.length > 0 && txt.length < 600 && isQuotaText(txt)) {
                                    signalQuota(txt);
                                    break;
                                }
                            }
                        }
                    } catch (e) {}
                }, 3000);
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
        splashActions = findViewById(R.id.agy_splash_actions)
        btnSplashRetry = findViewById(R.id.btn_splash_retry)
        btnSplashTerminal = findViewById(R.id.btn_splash_terminal)
        btnSplashLogs = findViewById(R.id.btn_splash_logs)
        setupContainer = findViewById(R.id.agy_setup_container)
        onboardingContainer = findViewById(R.id.agy_onboarding_container)
        floatingPill = findViewById(R.id.agy_floating_pill)
        pillControlsBtn = findViewById(R.id.pill_btn_controls)
        pillCollapseBtn = findViewById(R.id.pill_btn_collapse)

        btnGrantStorage = findViewById(R.id.btn_grant_storage)
        btnGrantBattery = findViewById(R.id.btn_grant_battery)
        txtDetectedBackup = findViewById(R.id.txt_detected_backup)
        btnOnboardingAutoInstall = findViewById(R.id.btn_onboarding_auto_install)
        btnOnboardingImport = findViewById(R.id.btn_onboarding_import)
        onboardingProgressBar = findViewById(R.id.onboarding_progress_bar)
        txtOnboardingProgress = findViewById(R.id.txt_onboarding_progress)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.parseColor("#0B0D10"))
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isVerticalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        val settings = webView.settings
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        @Suppress("DEPRECATION")
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.offscreenPreRaster = true
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        webView.addJavascriptInterface(WebNotificationBridge(), "AntigravityAndroid")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val qUrl = org.json.JSONObject.quote(url)
                val qName = org.json.JSONObject.quote(guessedName)
                val js = """
                    (function() {
                        fetch($qUrl).then(function(r){ return r.blob(); }).then(function(b){
                            if (b.size > 25 * 1024 * 1024) {
                                if (window.AntigravityAndroid) {
                                    window.AntigravityAndroid.notifyDownloadTooLarge();
                                }
                                return;
                            }
                            var fr = new FileReader();
                            fr.onloadend = function() {
                                if (window.AntigravityAndroid && fr.result) {
                                    window.AntigravityAndroid.saveDownloadedBase64($qName, String(fr.result));
                                }
                            };
                            fr.readAsDataURL(b);
                        }).catch(function(){});
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            } else if (AntigravityFileHelper.isLocalhostUri(url)) {
                val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                lifecycleScope.launch(Dispatchers.IO) {
                    var conn: java.net.HttpURLConnection? = null
                    val saveResult = runCatching {
                        conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 15000
                            setRequestProperty("User-Agent", userAgent)
                            if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                        }
                        val status = conn!!.responseCode
                        if (status !in 200..299) {
                            throw IllegalStateException("HTTP $status")
                        }
                        val resolvedMime = conn!!.contentType?.substringBefore(';')?.trim()?.ifBlank { null } ?: mimeType
                        AntigravityFileHelper.saveToDownloadsWithFallback(
                            this@AntigravityMainActivity,
                            guessedName,
                            resolvedMime
                        ) { dst ->
                            conn!!.inputStream.use { src -> src.copyTo(dst) }
                        }.getOrThrow()
                    }.also { conn?.disconnect() }
                    withContext(Dispatchers.Main) {
                        val toastMsg = saveResult.fold(
                            onSuccess = { loc -> "Saved to $loc" },
                            onFailure = { err -> "Download failed: ${err.message}" }
                        )
                        Toast.makeText(this@AntigravityMainActivity, toastMsg, Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                runCatching {
                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle(guessedName)
                        .setMimeType(mimeType)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, AntigravityFileHelper.sanitizeFileName(guessedName, "download"))
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    dm?.enqueue(req)
                    Toast.makeText(this@AntigravityMainActivity, "Downloading $guessedName...", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (AntigravityFileHelper.isTrustedHubUri(uri.toString())) {
                    return false
                }
                if (request != null && !request.isForMainFrame) {
                    return false
                }
                openExternalUri(uri)
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    reconnectBanner.visibility = View.VISIBLE
                    txtReconnectBanner.text = "Antigravity Hub unavailable. Reconnecting..."
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = detail?.didCrash() ?: false
                Logger.logError(LOG_TAG, "WebView render process gone. Crashed: $didCrash")
                val now = System.currentTimeMillis()
                if (now - lastRenderCrashMs < 15_000L) {
                    renderCrashCount++
                } else {
                    renderCrashCount = 1
                }
                lastRenderCrashMs = now

                val parent = view?.parent as? ViewGroup
                val index = if (parent != null && view != null) parent.indexOfChild(view) else 0
                val lp = view?.layoutParams
                view?.let {
                    it.stopLoading()
                    it.webChromeClient = null
                    it.removeJavascriptInterface("AntigravityAndroid")
                    parent?.removeView(it)
                    it.destroy()
                }
                if (parent != null) {
                    val fresh = WebView(this@AntigravityMainActivity).apply {
                        id = R.id.agy_webview
                        if (lp != null) layoutParams = lp
                    }
                    parent.addView(fresh, index)
                    webView = fresh
                    setupWebView()
                }

                if (renderCrashCount >= 3) {
                    reconnectBanner.visibility = View.GONE
                    webView.visibility = View.GONE
                    splashContainer.visibility = View.VISIBLE
                    splashActions.visibility = View.VISIBLE
                    splashStatus.text = "WebView renderer crashed repeatedly.\nCheck logs, retry, or open the terminal."
                    return true
                }

                reconnectBanner.visibility = View.VISIBLE
                txtReconnectBanner.text = "Render process restarted. Reloading..."
                val targetUrl = currentHubUrl ?: AntigravityEngine.parseHubUrlFromLog()
                if (targetUrl != null) {
                    webView.loadUrl(targetUrl)
                } else {
                    bootAntigravityHub()
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (AntigravityFileHelper.isTrustedHubUri(url)) {
                    reconnectBanner.visibility = View.GONE
                    injectAgentNotificationBridge()
                    lifecycleScope.launch(Dispatchers.IO) {
                        accountManager.captureActiveAccount()
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tempWebView = WebView(this@AntigravityMainActivity).apply {
                    settings.javaScriptEnabled = true
                }
                var destroyed = false
                fun cleanupTemp() {
                    if (!destroyed) {
                        destroyed = true
                        runCatching {
                            tempWebView.stopLoading()
                            tempWebView.destroy()
                        }
                    }
                }
                tempWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: run { v?.post { cleanupTemp() }; return true }
                        if (AntigravityFileHelper.isTrustedHubUri(uri.toString())) {
                            webView.loadUrl(uri.toString())
                        } else {
                            openExternalUri(uri)
                        }
                        v?.post { cleanupTemp() }
                        return true
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        if (!url.isNullOrBlank() && url != "about:blank") {
                            v?.post { cleanupTemp() }
                        }
                    }
                }
                tempWebView.postDelayed({ cleanupTemp() }, 8000L)
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread {
                    if (!AntigravityFileHelper.isTrustedHubUri(request.origin?.toString())) {
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
                val clampedScale = scale.coerceIn(0.5f, 2.0f)
                val next = Math.round(terminalTextSize * clampedScale).coerceIn(16, 56)
                return if (next != terminalTextSize) {
                    updateTerminalFontSize(next)
                    1.0f
                } else {
                    clampedScale
                }
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
            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
                if (session != null && !session.isRunning && keyCode == KeyEvent.KEYCODE_ENTER) {
                    ensureBackgroundTerminalSession()
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
            val btn = TextView(this).apply {
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

        rootView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val oldW = oldRight - oldLeft
            val newW = right - left
            val newH = bottom - top
            if ((oldW != newW || oldW == 0) && newW > 0 && newH > 0 && floatingPill.visibility == View.VISIBLE) {
                floatingPill.post {
                    val margin = 10 * resources.displayMetrics.density
                    val maxY = (rootView.height - floatingPill.height - margin).coerceAtLeast(margin)
                    val curYFrac = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getFloat(KEY_PILL_Y_FRAC, 0.5f)
                    floatingPill.y = (curYFrac * rootView.height).coerceIn(margin, maxY)
                    snapPillToEdge(savePos = false)
                }
            }
        }
        floatingPill.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            if (r > l && b > t) {
                ViewCompat.setSystemGestureExclusionRects(v, listOf(Rect(0, 0, r - l, b - t)))
            }
        }
    }

    private fun openExternalUri(uri: Uri) {
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme == "http" || scheme == "https") {
            val customTabs = runCatching {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
            }.getOrNull()
            if (customTabs != null && runCatching { customTabs.launchUrl(this, uri) }.isSuccess) {
                return
            }
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
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
            setupDismissed = true
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_SETUP_DISMISSED, true).apply()
            proceedAfterSetup()
        }
        findViewById<View>(R.id.btn_setup_continue).setOnClickListener {
            setupDismissed = true
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_SETUP_DISMISSED, true).apply()
            proceedAfterSetup()
        }

        btnOnboardingAutoInstall.setOnClickListener {
            if (AntigravityEngine.isInstallRunning()) {
                Toast.makeText(this@AntigravityMainActivity, "Installation already in progress...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnOnboardingAutoInstall.isEnabled = false
            btnOnboardingAutoInstall.alpha = 0.6f
            onboardingProgressBar.visibility = View.VISIBLE
            txtOnboardingProgress.visibility = View.VISIBLE
            onboardingProgressBar.progress = 5
            txtOnboardingProgress.text = "Starting automatic Antigravity CLI install..."
            lifecycleScope.launch {
                try {
                    val res = AntigravityEngine.installAntigravityCli(this@AntigravityMainActivity) { pct, msg ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                onboardingProgressBar.progress = pct
                                txtOnboardingProgress.text = msg
                            }
                        }
                    }
                    if (isFinishing || isDestroyed) return@launch
                    res.onSuccess {
                        Toast.makeText(this@AntigravityMainActivity, "Installed! Booting Antigravity...", Toast.LENGTH_SHORT).show()
                        bootAntigravityHub()
                    }.onFailure { err ->
                        txtOnboardingProgress.text = "Install failed: ${err.message}"
                        Toast.makeText(this@AntigravityMainActivity, "Auto-install failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    if (!isFinishing && !isDestroyed) {
                        btnOnboardingAutoInstall.isEnabled = true
                        btnOnboardingAutoInstall.alpha = 1f
                    }
                }
            }
        }
        findViewById<View>(R.id.btn_copy_port_cmd).setOnClickListener {
            val cmd = "pkg install -y zstd tar && tar -cf - -C /data/data/com.termux/files --exclude='./usr/var/cache/*' --exclude='./usr/tmp/*' ./usr ./home | zstd -6 -T2 -o /sdcard/Download/termux-port.tar.zst && zstd -t /sdcard/Download/termux-port.tar.zst"
            copyToClipboard("Termux Port Command", cmd)
            Toast.makeText(this, "Copied Termux port command to clipboard", Toast.LENGTH_LONG).show()
        }
        btnOnboardingImport.setOnClickListener {
            promptRestoreArchive(
                progressBar = onboardingProgressBar,
                statusView = txtOnboardingProgress,
                sheetToDismiss = null
            )
        }
        findViewById<View>(R.id.btn_onboarding_terminal).setOnClickListener {
            showTerminalView()
        }
        btnSplashRetry.setOnClickListener {
            bootAntigravityHub()
        }
        btnSplashTerminal.setOnClickListener {
            showTerminalView()
        }
        btnSplashLogs.setOnClickListener {
            showHubLogsDialog()
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        isServiceBound = true
        termuxService = (service as TermuxService.LocalBinder).service
        if (!bootstrapComplete) {
            runBootstrapSequence()
        } else if (terminalContainer.visibility == View.VISIBLE) {
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
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    AntigravityEngine.provisionBridgeAssets(this@AntigravityMainActivity)
                }
                checkPermissionsOrProceed()
            }
        }
    }

    private fun ensureBackgroundTerminalSession() {
        val svc = termuxService ?: return
        val existing = (0 until svc.termuxSessionsSize)
            .asSequence()
            .mapNotNull { svc.getTermuxSession(it)?.terminalSession }
            .lastOrNull { it.isRunning }
        val session = existing ?: svc.createTermuxSession(null, null, null, null, false, null)?.terminalSession
        if (session != null) {
            session.updateTerminalSessionClient(embeddedSessionClient)
            terminalView.attachSession(session)
            terminalView.onScreenUpdated()
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
        if ((!storageOk || !batteryOk) && !setupDismissed) {
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
        AntigravityNotifier.isActivityForeground = true
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
        } else if (bootstrapComplete && webView.visibility == View.VISIBLE && !BackupRestoreEngine.isBusy() && !isHubReconnectInFlight && !isBootInFlight) {
            isHubReconnectInFlight = true
            lifecycleScope.launch {
                try {
                    val alive = withContext(Dispatchers.IO) { AntigravityEngine.isHubHttpReady() }
                    if (alive) {
                        if (reconnectBanner.visibility == View.VISIBLE) {
                            reconnectBanner.visibility = View.GONE
                            val targetUrl = currentHubUrl ?: AntigravityEngine.parseHubUrlFromLog()
                            if (webView.url.isNullOrBlank() && targetUrl != null) {
                                webView.loadUrl(targetUrl)
                            }
                        }
                    } else if (AntigravityEngine.isAgyInstalled()) {
                        txtReconnectBanner.text = "Reconnecting to Antigravity Hub..."
                        reconnectBanner.visibility = View.VISIBLE
                        AntigravityEngine.startHubAndBridge(this@AntigravityMainActivity)
                        val newUrl = AntigravityEngine.pollForHubUrl(12000L)
                        if (newUrl != null) {
                            currentHubUrl = newUrl
                            reconnectBanner.visibility = View.GONE
                            if (webView.url.isNullOrBlank() || webView.url != newUrl) {
                                webView.loadUrl(newUrl)
                            } else {
                                webView.reload()
                            }
                        } else {
                            txtReconnectBanner.text = "Hub offline — tap Control Center to restart"
                        }
                    }
                } finally {
                    isHubReconnectInFlight = false
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityForeground = false
        AntigravityNotifier.isActivityForeground = false
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
        val master = BackupRestoreEngine.getDefaultMasterBackupFile()
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
        if (isBootInFlight) return
        isBootInFlight = true
        onboardingContainer.visibility = View.GONE
        setupContainer.visibility = View.GONE
        splashContainer.visibility = View.VISIBLE
        splashActions.visibility = View.GONE
        splashStatus.text = "Starting Antigravity Hub (port 4400)..."

        lifecycleScope.launch {
            try {
                AntigravityEngine.startHubAndBridge(this@AntigravityMainActivity)
                val hubUrl = AntigravityEngine.pollForHubUrl(20000L)
                if (isFinishing || isDestroyed) return@launch
                if (hubUrl != null) {
                    currentHubUrl = hubUrl
                    splashContainer.visibility = View.GONE
                    splashActions.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    floatingPill.visibility = View.VISIBLE
                    restorePillPositionIfNeeded()
                    webView.loadUrl(hubUrl)
                    if (pendingTerminalRestore) {
                        pendingTerminalRestore = false
                        showTerminalView()
                    }
                } else {
                    splashStatus.text = "Antigravity Hub did not respond on port 4400.\nCheck logs, retry, or open the terminal."
                    splashActions.visibility = View.VISIBLE
                    floatingPill.visibility = View.VISIBLE
                    restorePillPositionIfNeeded()
                    Toast.makeText(
                        this@AntigravityMainActivity,
                        "Hub startup timed out — check Control Center or Built-in Terminal",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                isBootInFlight = false
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
        lifecycleScope.launch {
            val logContent = withContext(Dispatchers.IO) {
                buildString {
                    append("=== agy.log (last 120 lines) ===\n")
                    val tail = AntigravityEngine.readLogTail(65536)
                    if (tail.isNotEmpty()) {
                        append(tail.lineSequence().toList().takeLast(120).joinToString("\n"))
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

        val txtActiveAccount = view.findViewById<TextView>(R.id.txt_active_account)
        val btnAddAccount = view.findViewById<View>(R.id.sheet_btn_add_account)
        val accountsListContainer = view.findViewById<LinearLayout>(R.id.sheet_accounts_list)
        val switchAutoSwitch = view.findViewById<Switch>(R.id.switch_auto_switch_quota)

        switchAutoSwitch.isChecked = accountManager.isAutoSwitchEnabled(this)
        switchAutoSwitch.setOnCheckedChangeListener { _, checked ->
            accountManager.setAutoSwitchEnabled(this, checked)
        }
        btnAddAccount.setOnClickListener {
            dialog.dismiss()
            startAddAccountFlow()
        }

        val switchScreenOn = view.findViewById<Switch>(R.id.switch_keep_screen_on)
        val switchCpuWake = view.findViewById<Switch>(R.id.switch_cpu_wakelock)
        val switchNotifyDone = view.findViewById<Switch>(R.id.switch_notify_done)
        val switchNotifyFg = view.findViewById<Switch>(R.id.switch_notify_foreground)

        switchScreenOn.isChecked = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        switchCpuWake.isChecked = prefs.getBoolean(KEY_CPU_WAKELOCK, false)
        switchNotifyDone.isChecked = AntigravityNotifier.isNotifyDoneEnabled(this)
        switchNotifyFg.isChecked = AntigravityNotifier.isNotifyForegroundEnabled(this)

        switchScreenOn.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, checked).apply()
            applyKeepScreenOn(checked)
        }
        switchCpuWake.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_CPU_WAKELOCK, checked).apply()
            applyCpuWakeLock(checked)
        }
        switchNotifyDone.setOnCheckedChangeListener { _, checked -> AntigravityNotifier.setNotifyDoneEnabled(this, checked) }
        switchNotifyFg.setOnCheckedChangeListener { _, checked -> AntigravityNotifier.setNotifyForegroundEnabled(this, checked) }

        view.findViewById<View>(R.id.sheet_btn_test_notif).setOnClickListener {
            showAgentDoneNotification("Antigravity", "Agent task finished — ready for review.", force = true)
            Toast.makeText(this, "Test notification sent", Toast.LENGTH_SHORT).show()
        }

        val progressBar = view.findViewById<ProgressBar>(R.id.sheet_backup_progress)
        val statusText = view.findViewById<TextView>(R.id.sheet_backup_status)

        var sheetDismissed = false
        dialog.setOnDismissListener {
            sheetDismissed = true
            if (pendingRestoreDialog === dialog) {
                pendingRestoreDialog = null
                pendingRestoreSheetProgress = null
                pendingRestoreSheetStatus = null
            }
        }

        lifecycleScope.launch {
            val health = AntigravityEngine.getHealth()
            val accounts = withContext(Dispatchers.IO) { accountManager.listAccounts() }
            if (isFinishing || isDestroyed || sheetDismissed) return@launch
            dotEngine.setBackgroundResource(if (health.engineReady) R.drawable.agy_dot_ok else R.drawable.agy_dot_off)
            txtEngine.text = if (health.engineReady) "Embedded Engine: Active (${Build.SUPPORTED_ABIS.firstOrNull() ?: "Linux"})" else "Embedded Engine: Initializing"

            dotHub.setBackgroundResource(if (health.hubReady) R.drawable.agy_dot_ok else R.drawable.agy_dot_off)
            txtHub.text = if (health.hubReady) "Antigravity Hub (4400): Online" else "Antigravity Hub (4400): Offline"

            renderAccountsInSheet(txtActiveAccount, accountsListContainer, accounts, dialog)
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
            lifecycleScope.launch {
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

        view.findViewById<View>(R.id.sheet_btn_clear_cache).setOnClickListener {
            dialog.dismiss()
            webView.clearCache(true)
            Toast.makeText(this, "WebView cache cleared — reloading...", Toast.LENGTH_SHORT).show()
            val url = currentHubUrl ?: AntigravityEngine.parseHubUrlFromLog()
            if (url != null) webView.loadUrl(url) else bootAntigravityHub()
        }

        view.findViewById<View>(R.id.sheet_btn_reinstall_cli).setOnClickListener {
            dialog.dismiss()
            if (AntigravityEngine.isInstallRunning()) {
                Toast.makeText(this, "Installation already in progress...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Reinstall / Update CLI")
                .setMessage("Stop Antigravity Hub and re-run the official Antigravity CLI installer?")
                .setPositiveButton("Reinstall") { _, _ ->
                    AntigravityEngine.stopBackgroundProcesses()
                    showOnboardingScreen()
                    btnOnboardingAutoInstall.performClick()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        view.findViewById<View>(R.id.sheet_btn_check_update).setOnClickListener {
            Toast.makeText(this, "Checking GitHub Releases...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val res = AntigravityEngine.checkLatestRelease()
                if (isFinishing || isDestroyed) return@launch
                res.onSuccess { info ->
                    val current = "v${BuildConfig.VERSION_NAME}"
                    if (info.tagName.isNotBlank() && AntigravityEngine.isNewerVersion(info.tagName, current)) {
                        val dlUrl = info.downloadApkUrl ?: info.releaseUrl
                        val msg = if (info.isLiteApk) {
                            "Installed: $current\nLatest: ${info.tagName}\n\nDownload the lightweight universal-lite updater APK?"
                        } else {
                            "Installed: $current\nLatest: ${info.tagName}\n\nDownload the updated APK from GitHub Releases?"
                        }
                        AlertDialog.Builder(this@AntigravityMainActivity)
                            .setTitle("Update Available (${info.tagName})")
                            .setMessage(msg)
                            .setPositiveButton("Download") { _, _ ->
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dlUrl))) }
                            }
                            .setNegativeButton("Later", null)
                            .show()
                    } else {
                        Toast.makeText(this@AntigravityMainActivity, "You're up to date ($current)", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    Toast.makeText(this@AntigravityMainActivity, "Update check failed: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        view.findViewById<View>(R.id.sheet_btn_phantom_fix).setOnClickListener {
            val cmd = "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\""
            AlertDialog.Builder(this)
                .setTitle("Android 12+ Phantom Process Fix")
                .setMessage("If Android kills heavy builds (npm/cargo/gradle) after >32 child processes, run this once via ADB or Shizuku:\n\n$cmd")
                .setPositiveButton("Copy Command") { _, _ ->
                    copyToClipboard("Phantom Process Fix", cmd)
                    Toast.makeText(this, "Copied ADB command to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
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

    private fun renderAccountsInSheet(
        txtActiveAccount: TextView,
        container: LinearLayout,
        accounts: List<AccountProfile>,
        dialog: BottomSheetDialog
    ) {
        container.removeAllViews()
        val active = accounts.firstOrNull { it.isActive }
        txtActiveAccount.text = if (active != null) "Active: ${active.email}" else "No active account signed in"

        if (accounts.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Sign in to Antigravity, then tap '+ Add Account' to save multiple accounts for 1-tap switching."
                setTextColor(getColor(R.color.agy_text_secondary))
                textSize = 12f
            }
            container.addView(emptyTv)
            return
        }

        val dp = resources.displayMetrics.density
        val now = System.currentTimeMillis()
        for (acct in accounts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.agy_btn_secondary)
                val padH = (12 * dp).toInt()
                val padV = (8 * dp).toInt()
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * dp).toInt() }
            }

            val label = TextView(this).apply {
                val statusSuffix = when {
                    acct.isActive -> "  • Active"
                    acct.isInCooldown(now) -> "  • Cooldown"
                    else -> ""
                }
                text = "${acct.email}$statusSuffix"
                setTextColor(getColor(if (acct.isActive) R.color.agy_accent else R.color.agy_text_primary))
                textSize = 12.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)

            if (!acct.isActive) {
                val btnSwitch = TextView(this).apply {
                    text = "Switch"
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundResource(R.drawable.agy_btn_primary)
                    textSize = 11.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
                    setOnClickListener {
                        dialog.dismiss()
                        switchAccountNow(acct)
                    }
                }
                row.addView(btnSwitch)

                val btnDelete = TextView(this).apply {
                    text = "✕"
                    setTextColor(getColor(R.color.agy_text_secondary))
                    textSize = 13f
                    setPadding((10 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                    setOnClickListener {
                        lifecycleScope.launch {
                            val removed = withContext(Dispatchers.IO) { accountManager.removeAccount(acct.id) }
                            if (removed) {
                                val updated = withContext(Dispatchers.IO) { accountManager.listAccounts() }
                                renderAccountsInSheet(txtActiveAccount, container, updated, dialog)
                                Toast.makeText(this@AntigravityMainActivity, "Removed ${acct.email}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                row.addView(btnDelete)
            }
            container.addView(row)
        }
    }

    private fun switchAccountNow(target: AccountProfile, autoReason: String? = null) {
        if (isSwitchingAccount) return
        isSwitchingAccount = true
        val bannerMsg = if (autoReason != null) {
            "Quota drained — switching to ${target.email}..."
        } else {
            "Switching to ${target.email}..."
        }
        txtReconnectBanner.text = bannerMsg
        reconnectBanner.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val switched = withContext(Dispatchers.IO) {
                    accountManager.switchToAccount(target.id, clearCooldown = autoReason == null)
                }
                if (switched == null) {
                    reconnectBanner.visibility = View.GONE
                    Toast.makeText(this@AntigravityMainActivity, "Failed to load credentials for ${target.email}", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val newUrl = AntigravityEngine.restartHub(this@AntigravityMainActivity)
                if (newUrl != null) {
                    currentHubUrl = newUrl
                    splashContainer.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    floatingPill.visibility = View.VISIBLE
                    webView.loadUrl(newUrl)
                    val msg = if (autoReason != null) {
                        "Quota drained — auto-switched to ${switched.email}"
                    } else {
                        "Switched to ${switched.email}"
                    }
                    Toast.makeText(this@AntigravityMainActivity, msg, Toast.LENGTH_LONG).show()
                    if (autoReason != null) {
                        showAgentDoneNotification("Account Auto-Switched", msg, force = true)
                    }
                } else {
                    reconnectBanner.visibility = View.GONE
                    Toast.makeText(this@AntigravityMainActivity, "Switched credentials, but Hub failed to restart", Toast.LENGTH_LONG).show()
                }
            } finally {
                isSwitchingAccount = false
            }
        }
    }

    private fun startAddAccountFlow() {
        if (isSwitchingAccount) return
        isSwitchingAccount = true
        txtReconnectBanner.text = "Preparing Google sign-in for new account..."
        reconnectBanner.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    accountManager.prepareForNewAccountLogin()
                }
                runCatching {
                    val cm = android.webkit.CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    cm.flush()
                }
                val newUrl = AntigravityEngine.restartHub(this@AntigravityMainActivity)
                if (newUrl != null) {
                    currentHubUrl = newUrl
                    splashContainer.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    floatingPill.visibility = View.VISIBLE
                    webView.loadUrl(newUrl)
                    Toast.makeText(this@AntigravityMainActivity, "Sign in with your next Google account", Toast.LENGTH_LONG).show()
                } else {
                    reconnectBanner.visibility = View.GONE
                    Toast.makeText(this@AntigravityMainActivity, "Failed to restart Hub for login", Toast.LENGTH_LONG).show()
                }
            } finally {
                isSwitchingAccount = false
            }
        }
    }

    private fun handleQuotaExhaustedSignal(reason: String?) {
        val now = System.currentTimeMillis()
        if (isSwitchingAccount || now - lastQuotaTriggerAt < 15_000L) return
        if (!accountManager.isAutoSwitchEnabled(this)) return
        lastQuotaTriggerAt = now

        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) {
                accountManager.markCurrentQuotaExhaustedAndPickNext()
            }
            if (isFinishing || isDestroyed) return@launch
            if (next != null) {
                switchAccountNow(next, autoReason = reason ?: "quota_exhausted")
            } else {
                Toast.makeText(
                    this@AntigravityMainActivity,
                    "Quota drained — no other available account to switch to",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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

        lifecycleScope.launch {
            val result = BackupRestoreEngine.createBackup(
                context = this@AntigravityMainActivity,
                mode = mode
            ) { pct, msg ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        progressBar.progress = pct
                        statusText.text = msg
                    }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess { res ->
                val mb = res.sizeBytes / (1024.0 * 1024.0)
                Toast.makeText(
                    this@AntigravityMainActivity,
                    String.format(Locale.US, "Saved %s (%.1f MB)", res.displayName, mb),
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

        lifecycleScope.launch {
            val result = BackupRestoreEngine.restoreBackup(
                context = this@AntigravityMainActivity,
                archiveFile = archiveFile
            ) { pct, msg ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        progressBar.progress = pct
                        statusView.text = msg
                    }
                }
            }
            result.onSuccess {
                if (isFinishing || isDestroyed) return@onSuccess
                if (sheetToDismiss?.isShowing == true) sheetToDismiss.dismiss()
                pendingRestoreDialog = null
                Toast.makeText(this@AntigravityMainActivity, "Restore complete! Booting Antigravity...", Toast.LENGTH_SHORT).show()
                bootAntigravityHub()
            }.onFailure { err ->
                if (isFinishing || isDestroyed) return@onFailure
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

        lifecycleScope.launch {
            val result = BackupRestoreEngine.restoreFromInputStream(
                context = this@AntigravityMainActivity,
                inputStreamProvider = { contentResolver.openInputStream(uri) },
                displayName = displayName
            ) { pct, msg ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        progressBar.progress = pct
                        statusView.text = msg
                    }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess {
                if (sheet?.isShowing == true) sheet.dismiss()
                pendingRestoreDialog = null
                Toast.makeText(this@AntigravityMainActivity, "Restore complete! Booting Antigravity...", Toast.LENGTH_SHORT).show()
                bootAntigravityHub()
            }.onFailure { err ->
                statusView.text = "Restore failed: ${err.message}"
                Toast.makeText(this@AntigravityMainActivity, "Restore failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleBackNavigation() {
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
            finish()
        } else {
            lastBackPressedMs = now
            Toast.makeText(this, "Press back again to exit Antigravity", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TERMINAL_VISIBLE, terminalContainer.visibility == View.VISIBLE)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(serviceStoppedReceiver) }
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        pendingWebPermissionRequest?.let { runCatching { it.deny() } }
        pendingWebPermissionRequest = null
        pendingRestoreDialog?.let { if (it.isShowing) runCatching { it.dismiss() } }
        pendingRestoreDialog = null
        runCatching {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.removeJavascriptInterface("AntigravityAndroid")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        if (isServiceBound) {
            runCatching { unbindService(this) }
            isServiceBound = false
        }
    }
}
