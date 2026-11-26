package com.termux.app

import android.content.Context
import android.os.FileObserver
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
                val bridgeDir = File(AntigravityEngine.BRIDGE_DIR).apply { if (!exists()) mkdirs() }
                @Suppress("DEPRECATION")
                val observer = object : FileObserver(bridgeDir.absolutePath, CLOSE_WRITE or MOVED_TO) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == "pending_notify.json") {
                            val pending = AntigravityEngine.consumePendingNotification() ?: return
                            AntigravityNotifier.showAgentDoneNotification(
                                appCtx,
                                pending.title,
                                pending.body,
                                force = false
                            )
                        }
                    }
                }
                runCatching { observer.startWatching() }
                notifyFileObserver = observer
            }
        }
    }

    @JvmStatic
    fun stop() {
        synchronized(lock) {
            runCatching { bridgeServer?.stop() }
            bridgeServer = null
            runCatching { notifyFileObserver?.stopWatching() }
            notifyFileObserver = null
        }
    }
}
