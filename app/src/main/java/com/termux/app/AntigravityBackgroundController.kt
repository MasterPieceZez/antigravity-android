package com.termux.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Service-scoped background controller owned by TermuxService so the local HTTP bridge
 * (127.0.0.1:4401) and kernel inotify FileObserver stay active even when AntigravityMainActivity
 * is closed or destroyed.
 */
object AntigravityBackgroundController {
    private val lock = Any()
    private var bridgeServer: LocalBridgeServer? = null
    private var notifyFileObserver: FileObserver? = null
    private var hubMonitorScope: CoroutineScope? = null
    private var hubMonitorJob: Job? = null
    @Volatile
    private var hubStateListener: Runnable? = null

    @JvmStatic
    fun setHubStateListener(listener: Runnable?) {
        hubStateListener = listener
    }

    @JvmStatic
    fun isRunning(): Boolean {
        synchronized(lock) {
            return bridgeServer?.isListening() == true
        }
    }

    @JvmStatic
    fun start(context: Context) {
        synchronized(lock) {
            val appCtx = context.applicationContext
            AntigravityNotifier.createChannel(appCtx)

            if (bridgeServer == null) {
                bridgeServer = LocalBridgeServer(appCtx) { title, body ->
                    AntigravityNotifier.showAgentDoneNotification(appCtx, title, body, force = false)
                }.also { it.start() }
            }

            if (notifyFileObserver == null) {
                startFileObserverLocked(appCtx)
            }

            if (hubMonitorJob?.isActive != true) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                hubMonitorScope = scope
                hubMonitorJob = scope.launch {
                    var lastState: Boolean? = null
                    while (isActive) {
                        val alive = AntigravityEngine.isPortOpen(AntigravityEngine.HUB_PORT, 250)
                        AntigravityEngine.setHubListeningCached(alive)
                        if (lastState != alive) {
                            lastState = alive
                            hubStateListener?.run()
                        }
                        delay(5000L)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun restartFileObserver(context: Context) {
        synchronized(lock) {
            runCatching { notifyFileObserver?.stopWatching() }
            notifyFileObserver = null
            startFileObserverLocked(context.applicationContext)
        }
    }

    private fun startFileObserverLocked(appCtx: Context) {
        val bridgeDir = File(AntigravityEngine.BRIDGE_DIR).apply { if (!exists()) mkdirs() }
        val handleEvent: (String?) -> Unit = { path ->
            if (path == "pending_notify.json") {
                val pending = AntigravityEngine.consumePendingNotification()
                if (pending != null) {
                    AntigravityNotifier.showAgentDoneNotification(
                        appCtx,
                        pending.title,
                        pending.body,
                        force = false
                    )
                }
            } else if (path == "pending_open_url" && !AntigravityEngine.externalUrlHandlerActive) {
                val url = AntigravityEngine.consumePendingOpenUrl()
                if (url != null) {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        appCtx.startActivity(intent)
                    }
                }
            }
        }
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(bridgeDir, mask) {
                override fun onEvent(event: Int, path: String?) = handleEvent(path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(bridgeDir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = handleEvent(path)
            }
        }
        runCatching { observer.startWatching() }
        notifyFileObserver = observer
    }

    @JvmStatic
    fun stop() {
        synchronized(lock) {
            hubMonitorJob?.cancel()
            hubMonitorJob = null
            hubMonitorScope?.cancel()
            hubMonitorScope = null
            runCatching { bridgeServer?.stop() }
            bridgeServer = null
            runCatching { notifyFileObserver?.stopWatching() }
            notifyFileObserver = null
            AntigravityEngine.setHubListeningCached(false)
        }
    }
}
