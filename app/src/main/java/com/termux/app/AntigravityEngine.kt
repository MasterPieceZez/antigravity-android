package com.termux.app

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom

data class AntigravityHealth(
    val engineReady: Boolean,
    val bridgeReady: Boolean,
    val agyInstalled: Boolean,
    val hubReady: Boolean,
    val hubUrl: String?,
    val autoUpdateDisabled: Boolean = true
)

data class PendingNotification(
    val title: String,
    val body: String
)

object AntigravityEngine {
    const val FILES_DIR = "/data/data/com.termux/files"
    const val PREFIX_DIR = "$FILES_DIR/usr"
    const val HOME_DIR = "$FILES_DIR/home"
    const val BRIDGE_DIR = "$HOME_DIR/.agy_bridge"
    const val AGY_BIN = "$HOME_DIR/.local/bin/agy"
    const val AGY_LOG = "$BRIDGE_DIR/agy.log"
    const val NOTIFY_FILE = "$BRIDGE_DIR/pending_notify.json"
    const val TOKEN_FILE = "$BRIDGE_DIR/bridge.token"
    const val BASH_BIN = "$PREFIX_DIR/bin/bash"
    const val HUB_PORT = 4400
    const val BRIDGE_PORT = 4401

    private val HUB_URL_REGEX = Regex("https?://(?:localhost|127\\.0\\.0\\.1):4400/#t=[A-Za-z0-9._-]+")


    fun isBootstrapReady(): Boolean = File(BASH_BIN).exists()

    fun isAgyInstalled(): Boolean = File(AGY_BIN).exists()

    fun buildTermuxEnv(): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["PREFIX"] = PREFIX_DIR
        env["HOME"] = HOME_DIR
        env["PATH"] = "$HOME_DIR/.local/bin:$PREFIX_DIR/bin:/system/bin:/system/xbin"
        env["LD_LIBRARY_PATH"] = "$PREFIX_DIR/lib"
        env["TMPDIR"] = "$PREFIX_DIR/tmp"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        env["AGY_ENABLE_HUB"] = "1"
        env["AGY_CLI_DISABLE_AUTO_UPDATE"] = "1"
        return env
    }

    fun getOrCreateBridgeToken(): String {
        val tokenFile = File(TOKEN_FILE)
        if (tokenFile.isFile) {
            val existing = runCatching { tokenFile.readText().trim() }.getOrNull()
            if (!existing.isNullOrEmpty() && existing.length >= 32) return existing
        }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        tokenFile.parentFile?.mkdirs()
        tokenFile.writeText(token)
        runCatching { Os.chmod(tokenFile.absolutePath, 0b110000000) } // 0600
        return token
    }

    fun provisionBridgeAssets(context: Context) {
        val bridgeDir = File(BRIDGE_DIR)
        if (!bridgeDir.exists()) bridgeDir.mkdirs()

        getOrCreateBridgeToken()

        val tmpDir = File("$PREFIX_DIR/tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()

        val storageDir = File("$HOME_DIR/storage")
        if (!storageDir.exists()) storageDir.mkdirs()
        val sharedLink = File(storageDir, "shared")
        if (!sharedLink.exists()) {
            runCatching { Os.symlink("/storage/emulated/0", sharedLink.absolutePath) }
        }

        val assetFiles = listOf("start_hub.sh", "bridge_server.py")
        for (name in assetFiles) {
            val outFile = File(bridgeDir, name)
            context.assets.open("agy_bridge/$name").use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.setReadable(true, false)
            outFile.setExecutable(true, false)
            runCatching { Os.chmod(outFile.absolutePath, 0b111101101) } // 0755
        }
    }

    fun isPortOpen(port: Int, timeoutMs: Int = 300): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun parseHubUrlFromLog(): String? {
        val logFile = File(AGY_LOG)
        if (!logFile.isFile) return null
        return try {
            val text = logFile.readText()
            HUB_URL_REGEX.findAll(text).lastOrNull()?.value
                ?.replace("127.0.0.1:4400", "localhost:4400")
        } catch (_: Exception) {
            null
        }
    }

    fun consumePendingNotification(): PendingNotification? {
        val file = File(NOTIFY_FILE)
        if (!file.isFile) return null
        return try {
            val raw = file.readText()
            file.delete()
            val json = JSONObject(raw)
            PendingNotification(
                title = json.optString("title", "Antigravity").ifBlank { "Antigravity" },
                body = json.optString("body", "Agent task finished").ifBlank { "Agent task finished" }
            )
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    suspend fun startHubAndBridge(context: Context) = withContext(Dispatchers.IO) {
        if (!isBootstrapReady()) return@withContext
        provisionBridgeAssets(context)
        val pb = ProcessBuilder(BASH_BIN, "$BRIDGE_DIR/start_hub.sh")
        pb.directory(File(HOME_DIR))
        pb.environment().putAll(buildTermuxEnv())
        pb.redirectErrorStream(true)
        runCatching {
            val proc = pb.start()
            proc.waitFor()
        }
    }

    suspend fun getHealth(): AntigravityHealth = withContext(Dispatchers.IO) {
        val engineReady = isBootstrapReady()
        val bridgeReady = isPortOpen(BRIDGE_PORT)
        if (bridgeReady) {
            val remote = fetchBridgeHealth()
            if (remote != null) return@withContext remote.copy(engineReady = engineReady)
        }
        val agyInstalled = isAgyInstalled()
        val hubUrl = parseHubUrlFromLog()
        val hubReady = hubUrl != null && isPortOpen(HUB_PORT)
        AntigravityHealth(
            engineReady = engineReady,
            bridgeReady = bridgeReady,
            agyInstalled = agyInstalled,
            hubReady = hubReady,
            hubUrl = hubUrl,
            autoUpdateDisabled = true
        )
    }

    private fun fetchBridgeHealth(): AntigravityHealth? {
        return try {
            val token = getOrCreateBridgeToken()
            val conn = URL("http://127.0.0.1:$BRIDGE_PORT/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Bridge-Token", token)
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val rawUrl = if (json.isNull("hub_url")) null else json.optString("hub_url", "").ifBlank { null }
                AntigravityHealth(
                    engineReady = true,
                    bridgeReady = json.optBoolean("bridge", true),
                    agyInstalled = json.optBoolean("agy_installed", isAgyInstalled()),
                    hubReady = json.optBoolean("hub_ready", false),
                    hubUrl = rawUrl,
                    autoUpdateDisabled = json.optBoolean("auto_update_disabled", true)
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun pollForHubUrl(timeoutMs: Long = 20000L): String? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val url = parseHubUrlFromLog()
            if (url != null && isPortOpen(HUB_PORT)) {
                return@withContext url
            }
            delay(350L)
        }
        parseHubUrlFromLog()
    }

    suspend fun restartHub(context: Context): String? = withContext(Dispatchers.IO) {
        if (isPortOpen(BRIDGE_PORT)) {
            val bridgeResult = try {
                val token = getOrCreateBridgeToken()
                val conn = URL("http://127.0.0.1:$BRIDGE_PORT/restart-agy").openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 15000
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-Bridge-Token", token)
                conn.doOutput = true
                conn.outputStream.use { it.write(ByteArray(0)) }
                if (conn.responseCode in 200..299) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    if (json.isNull("hub_url")) null else json.optString("hub_url", "").ifBlank { null }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
            if (bridgeResult != null) return@withContext bridgeResult
        }

        stopBackgroundProcesses()
        delay(450L)
        File(AGY_LOG).apply {
            parentFile?.mkdirs()
            writeText("")
        }
        startHubAndBridge(context)
        pollForHubUrl(15000L)
    }


    suspend fun installAntigravityCli(
        context: Context,
        onStatus: (percent: Int, message: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!isBootstrapReady()) {
                return@withContext Result.failure(IllegalStateException("Embedded Linux bootstrap is not ready"))
            }
            onStatus(10, "Updating package index & installing curl + python...")
            val script = """
                set -e
                export DEBIAN_FRONTEND=noninteractive
                pkg update -y
                pkg install -y curl python
                curl -fsSL https://antigravity.google/cli/install.sh | bash
            """.trimIndent()
            val pb = ProcessBuilder(BASH_BIN, "-c", script)
            pb.directory(File(HOME_DIR))
            pb.environment().putAll(buildTermuxEnv())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            var step = 15
            proc.inputStream.bufferedReader().forEachLine { line ->
                val clean = line.trim()
                if (clean.isNotEmpty()) {
                    step = (step + 1).coerceAtMost(92)
                    onStatus(step, clean.take(90))
                }
            }
            val code = proc.waitFor()
            if (code == 0 && isAgyInstalled()) {
                provisionBridgeAssets(context)
                onStatus(100, "Antigravity CLI installed!")
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Installer exited with code $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun stopBackgroundProcesses() {
        val pkill = File("$PREFIX_DIR/bin/pkill")
        val env = buildTermuxEnv()
        if (pkill.isFile) {
            for (pattern in listOf("agy --hub", "bridge_server.py")) {
                runCatching {
                    val pb = ProcessBuilder(pkill.absolutePath, "-f", pattern)
                    pb.environment().putAll(env)
                    pb.start().waitFor()
                }
            }
        }
    }
}
