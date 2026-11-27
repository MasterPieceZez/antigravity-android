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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.termux.BuildConfig
import com.termux.R
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

class AntigravityMainActivity : AppCompatActivity(), ServiceConnection {

    companion object {
        private const val LOG_TAG = "AntigravityMain"
        private const val PREFS_NAME = "antigravity_prefs"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_CPU_WAKELOCK = "cpu_wakelock"
        private const val KEY_SETUP_DISMISSED = "setup_dismissed"
        private const val STATE_TERMINAL_VISIBLE = "state_terminal_visible"
        private const val MAX_LOCAL_DOWNLOAD_BYTES = 250L * 1024L * 1024L
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

    private val hubViewModel: AntigravityHubViewModel by viewModels()
    private var termuxService: TermuxService? = null
    private var isServiceBound = false
    private var pendingTerminalRestore = false
    private val accountManager = AntigravityAccountManager(cipher = AndroidKeystoreCredentialCipher())
    @Volatile
    private var isHubRestartInFlight = false
    private var lastRegionPromptAt = 0L
    private var isRegionDialogShowing = false
    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logger.logDebug(LOG_TAG, "Received service stop broadcast, finishing activity")
            finishAffinity()
        }
    }
    private val hubUrlUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val newUrl = intent?.getStringExtra("hub_url") ?: return
            if (AntigravityFileHelper.isTrustedHubUri(newUrl) && !isFinishing && !isDestroyed) {
                hubViewModel.setCurrentHubUrl(newUrl)
                if (webView.visibility == View.VISIBLE) {
                    webView.loadUrl(newUrl)
                }
            }
        }
    }
    private var addAccountJob: kotlinx.coroutines.Job? = null
    private var lastBackPressedMs = 0L
    private lateinit var pillController: FloatingPillController
    private lateinit var terminalController: EmbeddedTerminalController
    private lateinit var controlSheetController: ControlCenterSheetController
    private var isActivityForeground = true

    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingRestoreSheetProgress: ProgressBar? = null
    private var pendingRestoreSheetStatus: TextView? = null
    private var pendingRestoreDialog: BottomSheetDialog? = null

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
        AntigravityEngine.initPaths(this)
        window.decorView.setBackgroundColor(Color.parseColor("#0B0D10"))
        setContentView(R.layout.activity_antigravity_main)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        hubViewModel.setSetupDismissed(prefs.getBoolean(KEY_SETUP_DISMISSED, false))
        pendingTerminalRestore = savedInstanceState?.getBoolean(STATE_TERMINAL_VISIBLE, false) == true
        applyKeepScreenOn(prefs.getBoolean(KEY_KEEP_SCREEN_ON, false))

        AntigravityNotifier.isActivityForeground = true
        AntigravityNotifier.createChannel(this)
        requestNotificationPermissionIfNeeded()

        bindViews()
        setupControllers()
        setupWebView()
        setupButtons()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })
        handleIncomingShareIntent(intent)

        val serviceIntent = Intent(this, TermuxService::class.java)
        runCatching { ContextCompat.startForegroundService(this, serviceIntent) }
        bindService(serviceIntent, this, Context.BIND_AUTO_CREATE)

        if (prefs.getBoolean(KEY_CPU_WAKELOCK, false)) {
            applyCpuWakeLock(true)
        }

        val serviceStopFilter = IntentFilter("com.agy.os.ACTION_SERVICE_STOPPED")
        val hubUpdateFilter = IntentFilter("com.agy.os.ACTION_HUB_URL_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStoppedReceiver, serviceStopFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(hubUrlUpdatedReceiver, hubUpdateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceStoppedReceiver, serviceStopFilter)
            registerReceiver(hubUrlUpdatedReceiver, hubUpdateFilter)
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
        runCatching { ContextCompat.startForegroundService(this, intent) }
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

    private fun injectAgentNotificationBridge() {
        webView.evaluateJavascript(AntigravityJsBridge.AGENT_BRIDGE_JS, null)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.offscreenPreRaster = true
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val allowedOrigins = setOf(
                "http://localhost:${AntigravityEngine.HUB_PORT}",
                "http://127.0.0.1:${AntigravityEngine.HUB_PORT}"
            )
            WebViewCompat.addWebMessageListener(webView, "AntigravityBridge", allowedOrigins) { _, message, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame || !AntigravityFileHelper.isTrustedHubUri(sourceOrigin.toString())) return@addWebMessageListener
                val raw = message.data ?: return@addWebMessageListener
                lifecycleScope.launch(Dispatchers.Default) {
                    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
                    when (json.optString("action")) {
                        "notify" -> withContext(Dispatchers.Main) {
                            showAgentDoneNotification(
                                title = json.optString("title", "Antigravity").ifBlank { "Antigravity" },
                                body = json.optString("body", "Agent task finished").ifBlank { "Agent task finished" },
                                force = false
                            )
                        }
                        "agent_done" -> withContext(Dispatchers.Main) {
                            showAgentDoneNotification(
                                title = "Antigravity",
                                body = json.optString("summary", "Agent finished its task").ifBlank { "Agent finished its task" },
                                force = false
                            )
                        }
                        "save_base64" -> {
                            val fname = json.optString("filename", "download")
                            val b64 = json.optString("data", "")
                            if (b64.isNotBlank()) {
                                val res = withContext(Dispatchers.IO) {
                                    AntigravityFileHelper.saveBase64ToDownloads(this@AntigravityMainActivity, fname, b64)
                                }
                                withContext(Dispatchers.Main) {
                                    res.onSuccess { displayPath ->
                                        Toast.makeText(this@AntigravityMainActivity, "Saved to $displayPath", Toast.LENGTH_LONG).show()
                                    }.onFailure { err ->
                                        Toast.makeText(this@AntigravityMainActivity, "Download failed: ${err.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                        "download_too_large" -> withContext(Dispatchers.Main) {
                            Toast.makeText(this@AntigravityMainActivity, "Blob download exceeds 25 MB limit", Toast.LENGTH_LONG).show()
                        }
                        "quota_exhausted" -> {
                            val reason = json.optString("reason", "quota_exhausted")
                            withContext(Dispatchers.Main) { handleQuotaExhaustedSignal(reason) }
                        }
                        "region_blocked" -> {
                            val reason = json.optString("reason", "region_blocked")
                            val isIpBlock = json.optBoolean("isIpBlock", false)
                            withContext(Dispatchers.Main) { handleRegionBlockedSignal(reason, isIpBlock) }
                        }
                        "theme_snapshot" -> {
                            val snapshotJson = json.toString()
                            withContext(Dispatchers.Main) { handleThemeSnapshot(snapshotJson) }
                        }
                    }
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                webView.evaluateJavascript(AntigravityJsBridge.buildBlobDownloadJs(url, guessedName), null)
            } else if (AntigravityFileHelper.isTrustedHubUri(url)) {
                val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                lifecycleScope.launch(Dispatchers.IO) {
                    var conn: java.net.HttpURLConnection? = null
                    val saveResult = runCatching {
                        conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 15000
                            instanceFollowRedirects = false
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
                            conn!!.inputStream.use { src ->
                                val buf = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val n = src.read(buf)
                                    if (n <= 0) break
                                    total += n
                                    if (total > MAX_LOCAL_DOWNLOAD_BYTES) {
                                        throw IllegalStateException("Download exceeds 250 MB safety limit")
                                    }
                                    dst.write(buf, 0, n)
                                }
                            }
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
                    val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle(guessedName)
                        .setMimeType(mimeType)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, AntigravityFileHelper.sanitizeFileName(guessedName, "download"))
                    if (!cookie.isNullOrBlank()) req.addRequestHeader("Cookie", cookie)
                    if (!userAgent.isNullOrBlank()) req.addRequestHeader("User-Agent", userAgent)
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
                    val scheme = uri.scheme?.lowercase(Locale.US)
                    val host = uri.host?.lowercase(Locale.US)
                    val isAllowedSubframe = scheme == "https" && host != null && (
                        host == "accounts.google.com" ||
                        host == "apis.google.com" ||
                        host.endsWith(".google.com") ||
                        host.endsWith(".gstatic.com") ||
                        host.endsWith(".googleusercontent.com")
                    )
                    return !isAllowedSubframe
                }
                openExternalUri(uri)
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    reconnectBanner.visibility = View.VISIBLE
                    txtReconnectBanner.text = "Antigravity Hub unavailable. Reconnecting..."
                    triggerHubReconnectIfNeeded()
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = detail?.didCrash() ?: false
                Logger.logError(LOG_TAG, "WebView render process gone. Crashed: $didCrash")
                val crashCount = hubViewModel.recordRenderCrash(System.currentTimeMillis())

                val parent = view?.parent as? ViewGroup
                val index = if (parent != null && view != null) parent.indexOfChild(view) else 0
                val lp = view?.layoutParams
                view?.let {
                    it.stopLoading()
                    it.webChromeClient = null
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        runCatching { WebViewCompat.removeWebMessageListener(it, "AntigravityBridge") }
                    }
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

                if (crashCount >= 3) {
                    reconnectBanner.visibility = View.GONE
                    webView.visibility = View.GONE
                    splashContainer.visibility = View.VISIBLE
                    splashActions.visibility = View.VISIBLE
                    splashStatus.text = "WebView renderer crashed repeatedly.\nCheck logs, retry, or open the terminal."
                    return true
                }

                reconnectBanner.visibility = View.VISIBLE
                txtReconnectBanner.text = "Render process restarted. Reloading..."
                val targetUrl = hubViewModel.currentState.currentHubUrl ?: AntigravityEngine.parseHubUrlFromLog()
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
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
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
                if (!AntigravityFileHelper.isTrustedHubUri(webView?.url)) {
                    filePathCallback?.onReceiveValue(null)
                    return false
                }
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                val intent = try {
                    (fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }).apply {
                        if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }
                } catch (_: Exception) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
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

    private fun setupControllers() {
        terminalController = EmbeddedTerminalController(
            activity = this,
            prefsName = PREFS_NAME,
            terminalContainer = terminalContainer,
            terminalView = terminalView,
            termExtraKeysRow = termExtraKeysRow,
            serviceProvider = { termuxService },
            onCopyText = { label, text -> copyToClipboard(label, text) },
            onRequestClose = { hideTerminalView() }
        ).also { it.setup() }

        pillController = FloatingPillController(
            context = this,
            prefsName = PREFS_NAME,
            rootView = rootView,
            floatingPill = floatingPill,
            pillControlsBtn = pillControlsBtn,
            pillCollapseBtn = pillCollapseBtn,
            onOpenControls = { controlSheetController.show() }
        ).also {
            it.setup()
            HubThemeSnapshot.load(this)?.let { snap -> it.applyTheme(snap) }
        }

        controlSheetController = ControlCenterSheetController(
            activity = this,
            prefsName = PREFS_NAME,
            keyKeepScreenOn = KEY_KEEP_SCREEN_ON,
            keyCpuWakeLock = KEY_CPU_WAKELOCK,
            accountManager = accountManager,
            callbacks = object : ControlCenterSheetController.Callbacks {
                override fun onApplyKeepScreenOn(enabled: Boolean) = applyKeepScreenOn(enabled)
                override fun onApplyCpuWakeLock(enabled: Boolean) = applyCpuWakeLock(enabled)
                override fun onSendTestNotification() {
                    showAgentDoneNotification("Antigravity", "Agent task finished — ready for review.", force = true)
                    Toast.makeText(this@AntigravityMainActivity, "Test notification sent", Toast.LENGTH_SHORT).show()
                }
                override fun onAddAccount() = startAddAccountFlow()
                override fun onSwitchAccount(account: AccountProfile) = switchAccountNow(account)
                override fun onReloadWebView() {
                    val url = hubViewModel.currentState.currentHubUrl ?: AntigravityEngine.parseHubUrlFromLog()
                    if (url != null) {
                        hubViewModel.setCurrentHubUrl(url)
                        webView.loadUrl(url)
                    } else {
                        bootAntigravityHub()
                    }
                }
                override fun onRestartHub() {
                    if (isHubRestartInFlight || hubViewModel.currentState.isSwitchingAccount) return
                    isHubRestartInFlight = true
                    txtReconnectBanner.text = "Restarting Antigravity Hub..."
                    reconnectBanner.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        try {
                            val newUrl = AntigravityEngine.restartHub(this@AntigravityMainActivity) { oauthUrl ->
                                openOAuthUrlOnUi(oauthUrl, txtReconnectBanner)
                            }
                            if (isFinishing || isDestroyed) return@launch
                            if (newUrl != null) {
                                hubViewModel.setCurrentHubUrl(newUrl)
                                splashContainer.visibility = View.GONE
                                webView.visibility = View.VISIBLE
                                if (!terminalController.isVisible()) {
                                    pillController.setVisible(true)
                                }
                                webView.loadUrl(newUrl)
                            } else {
                                reconnectBanner.visibility = View.GONE
                                Toast.makeText(this@AntigravityMainActivity, "Failed to restart Hub", Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            isHubRestartInFlight = false
                        }
                    }
                }
                override fun onToggleTerminal() {
                    if (terminalController.isVisible()) hideTerminalView() else showTerminalView()
                }
                override fun onShowLogs() = showHubLogsDialog()
                override fun onClearWebViewCache() {
                    webView.clearCache(true)
                    Toast.makeText(this@AntigravityMainActivity, "WebView cache cleared — reloading...", Toast.LENGTH_SHORT).show()
                    val url = hubViewModel.currentState.currentHubUrl ?: AntigravityEngine.parseHubUrlFromLog()
                    if (url != null) webView.loadUrl(url) else bootAntigravityHub()
                }
                override fun onReinstallCliConfirmed() {
                    lifecycleScope.launch {
                        AntigravityEngine.stopBackgroundProcessesSuspend()
                        if (isFinishing || isDestroyed) return@launch
                        showOnboardingScreen()
                        btnOnboardingAutoInstall.performClick()
                    }
                }
                override fun onRunBackup(mode: BackupMode, progressBar: ProgressBar, statusText: TextView) {
                    runBackupFlow(mode, progressBar, statusText)
                }
                override fun onPromptRestore(progressBar: ProgressBar, statusText: TextView, dialog: BottomSheetDialog) {
                    promptRestoreArchive(progressBar, statusText, dialog)
                }
                override fun onCopyText(label: String, text: String) = copyToClipboard(label, text)
                override fun onSheetDismissed(dialog: BottomSheetDialog) {
                    if (pendingRestoreDialog === dialog) {
                        pendingRestoreDialog = null
                        pendingRestoreSheetProgress = null
                        pendingRestoreSheetStatus = null
                    }
                }
            }
        )
    }

    private fun openOAuthUrlOnUi(oauthUrl: String, statusTarget: TextView? = null) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                statusTarget?.text = "Complete Google Sign-In in browser..."
                openExternalUri(Uri.parse(oauthUrl))
            }
        }
    }

    private fun openExternalUri(uri: Uri) {
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return
        if (scheme != "http" && scheme != "https" && scheme != "mailto") return
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

    private fun setupButtons() {
        btnGrantStorage.setOnClickListener { requestAllFilesPermission() }
        btnGrantBattery.setOnClickListener { requestBatteryOptimizationExclusion() }
        findViewById<View>(R.id.btn_setup_skip).setOnClickListener {
            hubViewModel.setSetupDismissed(true)
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_SETUP_DISMISSED, true).apply()
            proceedAfterSetup()
        }
        findViewById<View>(R.id.btn_setup_continue).setOnClickListener {
            hubViewModel.setSetupDismissed(true)
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
        if (!hubViewModel.currentState.bootstrapComplete) {
            runBootstrapSequence()
        } else if (terminalController.isVisible()) {
            terminalController.ensureSession()
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        isServiceBound = false
        termuxService = null
    }

    private fun runBootstrapSequence() {
        if (BuildConfig.AGY_LITE_UPDATE && !AntigravityEngine.isBootstrapReady()) {
            findViewById<View>(R.id.agy_splash_progress).visibility = View.GONE
            splashStatus.text = "This is a Lite Update APK.\nInstall the full AntigravityStandalone.apk first, then apply this update."
            return
        }
        splashStatus.text = "Preparing embedded Linux engine..."
        TermuxInstaller.setupBootstrapIfNeeded(this) {
            if (isFinishing || isDestroyed) return@setupBootstrapIfNeeded
            hubViewModel.markBootstrapComplete()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    AntigravityEngine.provisionBridgeAssets(this@AntigravityMainActivity)
                }
                checkPermissionsOrProceed()
            }
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
        if ((!storageOk || !batteryOk) && !hubViewModel.currentState.setupDismissed) {
            hubViewModel.showSetup()
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

    override fun onStart() {
        super.onStart()
        if (hubViewModel.currentState.bootstrapComplete) {
            val serviceIntent = Intent(this, TermuxService::class.java)
            runCatching { ContextCompat.startForegroundService(this, serviceIntent) }
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityForeground = true
        AntigravityNotifier.isActivityForeground = true
        val state = hubViewModel.currentState
        if (state.bootstrapComplete && setupContainer.visibility == View.VISIBLE) {
            updateSetupButtons()
            if (hasAllFilesAccess() && isIgnoringBatteryOptimizations()) {
                proceedAfterSetup()
            }
        } else if (state.bootstrapComplete && onboardingContainer.visibility == View.VISIBLE) {
            refreshDetectedBackupBanner()
            if (AntigravityEngine.isAgyInstalled() && !AntigravityEngine.isInstallRunning() && !BackupRestoreEngine.isBusy()) {
                bootAntigravityHub()
            }
        } else if (state.bootstrapComplete && webView.visibility == View.VISIBLE) {
            triggerHubReconnectIfNeeded(reloadIfSameUrl = false)
        }
    }

    private fun triggerHubReconnectIfNeeded(reloadIfSameUrl: Boolean = true) {
        if (BackupRestoreEngine.isBusy() || isHubRestartInFlight || !hubViewModel.tryBeginReconnect()) return
        lifecycleScope.launch {
            var reconnectedUrl: String? = null
            try {
                val alive = withContext(Dispatchers.IO) { AntigravityEngine.isHubHttpReady() }
                if (alive) {
                    withContext(Dispatchers.IO) { accountManager.captureActiveAccount() }
                    if (reconnectBanner.visibility == View.VISIBLE) {
                        reconnectBanner.visibility = View.GONE
                        val targetUrl = hubViewModel.currentState.currentHubUrl ?: AntigravityEngine.parseHubUrlFromLog()
                        if ((webView.url.isNullOrBlank() || reloadIfSameUrl) && targetUrl != null) {
                            webView.loadUrl(targetUrl)
                        }
                    }
                } else if (AntigravityEngine.isAgyInstalled()) {
                    txtReconnectBanner.text = "Reconnecting to Antigravity Hub..."
                    reconnectBanner.visibility = View.VISIBLE
                    AntigravityEngine.startHubAndBridge(this@AntigravityMainActivity)
                    val newUrl = AntigravityEngine.pollForHubUrl(12000L) { oauthUrl ->
                        openOAuthUrlOnUi(oauthUrl, txtReconnectBanner)
                    }
                    if (isFinishing || isDestroyed) return@launch
                    if (newUrl != null) {
                        reconnectedUrl = newUrl
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
                hubViewModel.finishReconnect(reconnectedUrl)
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
        hubViewModel.showOnboarding()
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
        if (!hubViewModel.tryBeginBoot()) return
        onboardingContainer.visibility = View.GONE
        setupContainer.visibility = View.GONE
        splashContainer.visibility = View.VISIBLE
        splashActions.visibility = View.GONE
        splashStatus.text = "Starting Antigravity Hub..."

        lifecycleScope.launch {
            AntigravityEngine.externalUrlHandlerActive = true
            var resolvedHubUrl: String? = null
            try {
                AntigravityEngine.startHubAndBridge(this@AntigravityMainActivity)
                val hubUrl = AntigravityEngine.pollForHubUrl(20000L) { oauthUrl ->
                    openOAuthUrlOnUi(oauthUrl, splashStatus)
                }
                if (isFinishing || isDestroyed) return@launch
                resolvedHubUrl = hubUrl
                if (hubUrl != null) {
                    splashContainer.visibility = View.GONE
                    splashActions.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    if (!terminalController.isVisible()) {
                        floatingPill.visibility = View.VISIBLE
                        pillController.restorePositionIfNeeded()
                    }
                    webView.loadUrl(hubUrl)
                    if (pendingTerminalRestore) {
                        pendingTerminalRestore = false
                        showTerminalView()
                    }
                } else {
                    splashStatus.text = "Antigravity Hub did not start in time.\nCheck logs, retry, or open the terminal."
                    splashActions.visibility = View.VISIBLE
                    floatingPill.visibility = View.VISIBLE
                    pillController.restorePositionIfNeeded()
                    Toast.makeText(
                        this@AntigravityMainActivity,
                        "Hub startup timed out — check Control Center or Built-in Terminal",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                AntigravityEngine.externalUrlHandlerActive = false
                hubViewModel.finishBoot(resolvedHubUrl)
            }
        }
    }

    private fun showTerminalView() {
        terminalController.show()
        pillController.setVisible(false)
    }

    private fun hideTerminalView() {
        terminalController.hide()
        if (webView.visibility == View.VISIBLE) {
            pillController.setVisible(true)
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
                        val prevFile = File("${AntigravityEngine.AGY_LOG}.prev")
                        val prevText = runCatching { prevFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
                        if (!prevText.isNullOrEmpty()) {
                            append("(current agy.log empty — showing previous log)\n")
                            append(prevText.lineSequence().toList().takeLast(80).joinToString("\n"))
                        } else {
                            append("(no log yet — hubListening=${AntigravityEngine.isPortOpen(AntigravityEngine.HUB_PORT, 150)}, agyInstalled=${AntigravityEngine.isAgyInstalled()})")
                        }
                    }
                    val installTail = AntigravityEngine.readInstallLogTail(16384).trim()
                    if (installTail.isNotEmpty()) {
                        append("\n\n=== install.log (last 40 lines) ===\n")
                        append(installTail.lineSequence().toList().takeLast(40).joinToString("\n"))
                    }
                }
            }
            if (isFinishing || isDestroyed) return@launch
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

    private suspend fun clearWebViewSessionData() {
        withContext(Dispatchers.Main) {
            runCatching { WebStorage.getInstance().deleteAllData() }
        }
        suspendCancellableCoroutine<Unit> { cont ->
            runOnUiThread {
                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies {
                        runCatching { cm.flush() }
                        if (cont.isActive) cont.resume(Unit)
                    }
                }.onFailure {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private suspend fun performAccountSwitch(
        target: AccountProfile,
        autoReason: String? = null,
        alreadySwitched: Boolean = false
    ): String? {
        val switched = if (alreadySwitched) {
            target
        } else {
            // Stop the Hub before swapping OAuth files so a shutting-down Hub cannot clobber them
            AntigravityEngine.stopBackgroundProcessesSuspend()
            withContext(Dispatchers.IO) {
                accountManager.switchToAccount(target.id, clearCooldown = autoReason == null)
            }
        }
        if (switched == null) {
            reconnectBanner.visibility = View.GONE
            Toast.makeText(this@AntigravityMainActivity, "Failed to load credentials for ${target.email}", Toast.LENGTH_LONG).show()
            return null
        }
        clearWebViewSessionData()
        val newUrl = AntigravityEngine.restartHub(this@AntigravityMainActivity)
        if (newUrl != null) {
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
        return newUrl
    }

    private fun switchAccountNow(
        target: AccountProfile,
        autoReason: String? = null,
        alreadySwitched: Boolean = false
    ) {
        if (!hubViewModel.tryBeginAccountSwitch()) return
        val bannerMsg = if (autoReason != null) {
            "Quota drained — switching to ${target.email}..."
        } else {
            "Switching to ${target.email}..."
        }
        txtReconnectBanner.text = bannerMsg
        reconnectBanner.visibility = View.VISIBLE

        lifecycleScope.launch {
            var newUrl: String? = null
            try {
                newUrl = performAccountSwitch(target, autoReason, alreadySwitched)
            } finally {
                hubViewModel.finishAccountSwitch(newUrl)
            }
        }
    }

    private fun startAddAccountFlow() {
        if (!hubViewModel.tryBeginAccountSwitch()) return
        txtReconnectBanner.text = "Preparing Google sign-in... (tap to cancel)"
        reconnectBanner.visibility = View.VISIBLE

        lateinit var job: kotlinx.coroutines.Job
        job = lifecycleScope.launch {
            var previousAccount: AccountProfile? = null
            var resolvedUrl: String? = null
            try {
                // Stop Hub first so it cannot re-write active tokens while we snapshot & clear them
                AntigravityEngine.stopBackgroundProcessesSuspend()
                previousAccount = withContext(Dispatchers.IO) {
                    accountManager.prepareForNewAccountLogin()
                }
                reconnectBanner.setOnClickListener {
                    job.cancel()
                }
                clearWebViewSessionData()
                AntigravityEngine.externalUrlHandlerActive = true
                val newUrl = AntigravityEngine.restartHub(this@AntigravityMainActivity, skipStop = true) { oauthUrl ->
                    openOAuthUrlOnUi(oauthUrl, txtReconnectBanner)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            txtReconnectBanner.text = "Complete Google Sign-In in browser (tap to cancel)"
                        }
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                if (newUrl != null) {
                    resolvedUrl = newUrl
                    splashContainer.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    floatingPill.visibility = View.VISIBLE
                    webView.loadUrl(newUrl)
                    Toast.makeText(this@AntigravityMainActivity, "Sign in with your next Google account", Toast.LENGTH_LONG).show()
                } else {
                    if (previousAccount != null) {
                        withContext(Dispatchers.IO) {
                            accountManager.switchToAccount(previousAccount.id, clearCooldown = false)
                        }
                    }
                    reconnectBanner.visibility = View.GONE
                    Toast.makeText(
                        this@AntigravityMainActivity,
                        "Failed to restart Hub for login — check Hub Logs in Control Center",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (previousAccount != null) {
                        withContext(Dispatchers.IO) {
                            accountManager.switchToAccount(previousAccount.id, clearCooldown = false)
                        }
                    }
                    val restoredUrl = AntigravityEngine.restartHub(this@AntigravityMainActivity)
                    if (!isFinishing && !isDestroyed) {
                        reconnectBanner.visibility = View.GONE
                        if (restoredUrl != null) {
                            resolvedUrl = restoredUrl
                            webView.loadUrl(restoredUrl)
                        }
                        Toast.makeText(this@AntigravityMainActivity, "Account sign-in cancelled", Toast.LENGTH_SHORT).show()
                    }
                }
                throw ce
            } finally {
                AntigravityEngine.externalUrlHandlerActive = false
                reconnectBanner.setOnClickListener(null)
                addAccountJob = null
                hubViewModel.finishAccountSwitch(resolvedUrl)
            }
        }
        addAccountJob = job
    }

    private fun handleRegionBlockedSignal(reason: String?, isIpBlock: Boolean) {
        val now = System.currentTimeMillis()
        if (isFinishing || isDestroyed || isRegionDialogShowing || now - lastRegionPromptAt < 15_000L) return
        lastRegionPromptAt = now
        isRegionDialogShowing = true

        val title = if (isIpBlock) "Location Blocked by Google" else "Account Region Not Supported"
        val message = if (isIpBlock) {
            "Google blocked this request because your current IP or location is not supported by Google Antigravity.\n\n" +
                "Connect to a supported region and reload."
        } else {
            "Google reported that this account's region is not eligible for Google Antigravity.\n\n" +
                "Try signing in with an eligible account or using a supported region."
        }
        AlertDialog.Builder(this)
            .setOnDismissListener { isRegionDialogShowing = false }
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun handleThemeSnapshot(rawJson: String) {
        val snap = runCatching {
            HubThemeSnapshot.saveAndParse(this, org.json.JSONObject(rawJson))
        }.getOrNull() ?: return
        pillController.applyTheme(snap)
    }

    private fun handleQuotaExhaustedSignal(reason: String?) {
        if (!accountManager.isAutoSwitchEnabled(this)) return
        if (!hubViewModel.tryBeginQuotaFailover(System.currentTimeMillis())) return

        lifecycleScope.launch {
            var newUrl: String? = null
            try {
                val next = withContext(Dispatchers.IO) {
                    accountManager.markCurrentQuotaExhaustedAndSelectNext()
                }
                if (isFinishing || isDestroyed) return@launch
                if (next != null) {
                    txtReconnectBanner.text = "Quota drained — switching to ${next.email}..."
                    reconnectBanner.visibility = View.VISIBLE
                    newUrl = performAccountSwitch(
                        target = next,
                        autoReason = reason ?: "quota_exhausted",
                        alreadySwitched = false
                    )
                } else {
                    Toast.makeText(
                        this@AntigravityMainActivity,
                        "Quota drained — no other available account to switch to",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                hubViewModel.finishAccountSwitch(newUrl)
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
                    String.format(Locale.US, "Saved %s (%.1f MB) — sign-in tokens excluded for security", res.displayName, mb),
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
        var expectedSize = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (idx >= 0) displayName = cursor.getString(idx) ?: displayName
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) expectedSize = cursor.getLong(sizeIdx)
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
                displayName = displayName,
                expectedBytes = expectedSize
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
        if (isFinishing) {
            AntigravityEngine.cancelInstall()
        }
        runCatching { unregisterReceiver(serviceStoppedReceiver) }
        runCatching { unregisterReceiver(hubUrlUpdatedReceiver) }
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        pendingWebPermissionRequest?.let { runCatching { it.deny() } }
        pendingWebPermissionRequest = null
        pendingRestoreDialog?.let { if (it.isShowing) runCatching { it.dismiss() } }
        pendingRestoreDialog = null
        runCatching {
            webView.stopLoading()
            webView.webChromeClient = null
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching { WebViewCompat.removeWebMessageListener(webView, "AntigravityBridge") }
            }
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        if (isServiceBound) {
            runCatching { unbindService(this) }
            isServiceBound = false
        }
    }
}
