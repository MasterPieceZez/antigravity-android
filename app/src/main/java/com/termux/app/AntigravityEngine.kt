package com.termux.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.system.Os
import com.termux.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class AntigravityHealth(
    val engineReady: Boolean,
    val bridgeReady: Boolean,
    val agyInstalled: Boolean,
    val hubReady: Boolean,
    val hubUrl: String?,
    val autoUpdateDisabled: Boolean = true
)

data class UpdateReleaseInfo(
    val tagName: String,
    val releaseUrl: String,
    val downloadApkUrl: String?,
    val isLiteApk: Boolean = false
)

data class PendingNotification(
    val title: String,
    val body: String
)

object AntigravityEngine {
    const val FILES_DIR = "/data/data/com.agy.os/files"
    const val PREFIX_DIR = "$FILES_DIR/usr"
    const val HOME_DIR = "$FILES_DIR/home"
    const val BRIDGE_DIR = "$HOME_DIR/.agy_bridge"
    const val AGY_BIN = "$HOME_DIR/.local/bin/agy"
    private const val PREFS_ENGINE = "antigravity_prefs"
    private const val KEY_REGION_BYPASS = "region_eligibility_bypass"
    private val BYTES_INELIGIBLE = "ineligible".toByteArray(Charsets.US_ASCII)
    private val BYTES_INEXIGIBLE = "inexigible".toByteArray(Charsets.US_ASCII)
    const val AGY_LOG = "$BRIDGE_DIR/agy.log"
    const val INSTALL_LOG = "$BRIDGE_DIR/install.log"
    const val HUB_URL_FILE = "$BRIDGE_DIR/hub.url"
    const val HUB_PID_FILE = "$BRIDGE_DIR/hub.pid"
    const val NOTIFY_FILE = "$BRIDGE_DIR/pending_notify.json"
    const val PENDING_OPEN_URL_FILE = "$BRIDGE_DIR/pending_open_url"
    const val TOKEN_FILE = "$BRIDGE_DIR/bridge.token"
    const val BASH_BIN = "$PREFIX_DIR/bin/bash"
    const val HUB_PORT = 4400
    const val BRIDGE_PORT = 4401

    private val startMutex = Mutex()
    private val restartMutex = Mutex()
    private val provisionLock = Any()
    private val tokenLock = Any()
    private val installInProgress = AtomicBoolean(false)
    private val activeInstallProcess = AtomicReference<Process?>(null)
    private val hubTransitionCount = AtomicInteger(0)
    @Volatile
    private var assetsProvisioned = false
    private const val MIN_INSTALL_FREE_BYTES = 150L * 1024L * 1024L
    @Volatile
    private var cachedBridgeToken: String? = null
    @Volatile
    private var cachedHubListening: Boolean = false
    private val HUB_URL_REGEX = Regex("https?://(?:localhost|127\\.0\\.0\\.1):4400/#t=[A-Za-z0-9._-]+")
    private val HUB_BASE_URL_REGEX = Regex("https?://(?:localhost|127\\.0\\.0\\.1):4400(?:/[^\\s\"'<>]*)?")
    private val OAUTH_URL_REGEX = Regex("https://accounts\\.google\\.com/[^\\s\"'<>]+")
    @Volatile
    var externalUrlHandlerActive: Boolean = false


    fun isBootstrapReady(): Boolean = File(BASH_BIN).exists()

    fun isAgyInstalled(): Boolean = File(AGY_BIN).let { it.isFile && it.length() > 0 }

    fun isInstallRunning(): Boolean = installInProgress.get()

    fun cancelInstall() {
        activeInstallProcess.getAndSet(null)?.destroyForcibly()
    }

    @JvmStatic
    fun isHubTransitionInProgress(): Boolean = hubTransitionCount.get() > 0

    @JvmStatic
    fun isHubListeningCached(): Boolean = cachedHubListening

    @JvmStatic
    fun setHubListeningCached(listening: Boolean) {
        cachedHubListening = listening
    }

    @JvmStatic
    fun isHubPortListening(): Boolean = isPortOpen(HUB_PORT).also { cachedHubListening = it }

    fun isDeviceAbiSupportedByAgy(): Boolean {
        val primary = Build.SUPPORTED_ABIS.firstOrNull() ?: return true
        return primary == "arm64-v8a" || primary == "x86_64"
    }

    fun buildTermuxEnv(): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["PREFIX"] = PREFIX_DIR
        env["HOME"] = HOME_DIR
        env["PATH"] = "$HOME_DIR/.local/bin:$PREFIX_DIR/bin:/system/bin:/system/xbin"
        env["TMPDIR"] = "$PREFIX_DIR/tmp"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        env["AGY_ENABLE_HUB"] = "1"
        env["AGY_CLI_DISABLE_AUTO_UPDATE"] = "1"
        env["BROWSER"] = "$HOME_DIR/.local/bin/xdg-open"
        return env
    }

    fun getOrCreateBridgeToken(): String {
        cachedBridgeToken?.let { return it }
        synchronized(tokenLock) {
            cachedBridgeToken?.let { return it }
            val tokenFile = File(TOKEN_FILE)
            if (tokenFile.isFile) {
                val existing = runCatching { tokenFile.readText().trim() }.getOrNull()
                if (!existing.isNullOrEmpty() && existing.length >= 32) {
                    runCatching { Os.chmod(tokenFile.absolutePath, 0b110000000) } // 0600
                    cachedBridgeToken = existing
                    return existing
                }
            }
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val token = bytes.joinToString("") { "%02x".format(it) }
            tokenFile.parentFile?.mkdirs()
            val tmpFile = File(tokenFile.parentFile, "${tokenFile.name}.tmp")
            tmpFile.writeText("")
            runCatching { Os.chmod(tmpFile.absolutePath, 0b110000000) } // 0600 before writing secret
            tmpFile.writeText(token)
            runCatching { Os.chmod(tmpFile.absolutePath, 0b110000000) }
            if (!tmpFile.renameTo(tokenFile)) {
                tokenFile.writeText(token)
                runCatching { Os.chmod(tokenFile.absolutePath, 0b110000000) }
                tmpFile.delete()
            }
            cachedBridgeToken = token
            return token
        }
    }

    fun provisionBridgeAssets(context: Context) {
        synchronized(provisionLock) {
            val bridgeDir = File(BRIDGE_DIR)
            if (!bridgeDir.exists()) bridgeDir.mkdirs()

            getOrCreateBridgeToken()

            val tmpDir = File("$PREFIX_DIR/tmp")
            if (!tmpDir.exists()) tmpDir.mkdirs()

            val storageDir = File("$HOME_DIR/storage")
            if (!storageDir.exists()) storageDir.mkdirs()
            val sharedLink = File(storageDir, "shared")
            val sharedLinkExists = runCatching { Os.lstat(sharedLink.absolutePath); true }.getOrDefault(false)
            if (!sharedLinkExists) {
                runCatching { Os.symlink("/storage/emulated/0", sharedLink.absolutePath) }
            }

            val localBinDir = File("$HOME_DIR/.local/bin")
            if (!localBinDir.exists()) localBinDir.mkdirs()
            val xdgOut = File(localBinDir, "xdg-open")
            val xdgTmp = File(localBinDir, "xdg-open.tmp")
            runCatching {
                context.assets.open("agy_bridge/xdg-open").use { input ->
                    FileOutputStream(xdgTmp).use { output -> input.copyTo(output) }
                }
                xdgTmp.setReadable(true, false)
                xdgTmp.setExecutable(true, false)
                runCatching { Os.chmod(xdgTmp.absolutePath, 0b111101101) } // 0755
                if (!xdgTmp.renameTo(xdgOut)) {
                    xdgTmp.copyTo(xdgOut, overwrite = true)
                    xdgTmp.delete()
                    runCatching { Os.chmod(xdgOut.absolutePath, 0b111101101) }
                }
            }

            val assetFiles = listOf("start_hub.sh", "dpkg_wrap.sh")
            for (name in assetFiles) {
                val outFile = File(bridgeDir, name)
                val tmpFile = File(bridgeDir, "$name.tmp")
                context.assets.open("agy_bridge/$name").use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tmpFile.setReadable(true, false)
                tmpFile.setExecutable(true, false)
                runCatching { Os.chmod(tmpFile.absolutePath, 0b111101101) } // 0755
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.copyTo(outFile, overwrite = true)
                    tmpFile.delete()
                    runCatching { Os.chmod(outFile.absolutePath, 0b111101101) }
                }
            }

            val patchSo = File(context.applicationInfo.nativeLibraryDir, "libagy_patch.so")
            val patchLink = File(bridgeDir, "agy_patch")
            if (patchSo.isFile) {
                runCatching {
                    val tmpLink = File(bridgeDir, "agy_patch.tmp")
                    runCatching { Os.remove(tmpLink.absolutePath) }
                    Os.symlink(patchSo.absolutePath, tmpLink.absolutePath)
                    tmpLink.renameTo(patchLink)
                }
            }

            val aptConfDir = File("$PREFIX_DIR/etc/apt/apt.conf.d")
            if (File("$PREFIX_DIR/etc/apt").isDirectory) {
                aptConfDir.mkdirs()
                val dpkgWrap = File(bridgeDir, "dpkg_wrap.sh").absolutePath
                File(aptConfDir, "99agy-dpkg").writeText("Dir::Bin::dpkg \"$dpkgWrap\";\n")
            }

            val dpkgBin = File("$PREFIX_DIR/bin/dpkg")
            val dpkgReal = File("$PREFIX_DIR/bin/dpkg.real")
            val dpkgWrapFile = File(bridgeDir, "dpkg_wrap.sh")
            if (dpkgWrapFile.exists() && dpkgBin.exists()) {
                val target = runCatching { Os.readlink(dpkgBin.absolutePath) }.getOrNull()
                if (target != dpkgWrapFile.absolutePath) {
                    runCatching {
                        if (target == null) {
                            // Real binary (initial install or overwritten by `apt upgrade dpkg`)
                            if (dpkgReal.exists()) dpkgReal.delete()
                            if (dpkgBin.renameTo(dpkgReal)) {
                                try {
                                    Os.symlink(dpkgWrapFile.absolutePath, dpkgBin.absolutePath)
                                } catch (e: Exception) {
                                    runCatching { dpkgReal.renameTo(dpkgBin) }
                                    throw e
                                }
                            }
                        } else {
                            dpkgBin.delete()
                            Os.symlink(dpkgWrapFile.absolutePath, dpkgBin.absolutePath)
                        }
                    }
                }
            }
            assetsProvisioned = true
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


    fun isHubHttpReady(timeoutMs: Int = 1000): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$HUB_PORT/").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            val ready = code in 100..499
            cachedHubListening = ready
            ready
        } catch (_: Exception) {
            cachedHubListening = false
            false
        }
    }

    fun compareSemver(a: String, b: String): Int {
        val (pa, preA) = parseSemver(a)
        val (pb, preB) = parseSemver(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        if (preA == null && preB == null) return 0
        if (preA == null) return 1
        if (preB == null) return -1
        val partsA = preA.split(".")
        val partsB = preB.split(".")
        for (i in 0 until maxOf(partsA.size, partsB.size)) {
            if (i >= partsA.size) return -1
            if (i >= partsB.size) return 1
            val sa = partsA[i]
            val sb = partsB[i]
            val na = sa.toIntOrNull()
            val nb = sb.toIntOrNull()
            val cmp = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> sa.compareTo(sb)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun parseSemver(ver: String): Pair<List<Int>, String?> {
        val cleaned = ver.trim().removePrefix("v").removePrefix("V").substringBefore("+")
        if (cleaned.isBlank()) return emptyList<Int>() to null
        val dashIdx = cleaned.indexOf('-')
        val core = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
        val pre = if (dashIdx >= 0 && dashIdx + 1 < cleaned.length) cleaned.substring(dashIdx + 1) else null
        val nums = if (core.isBlank()) emptyList() else core.split(".").map { it.toIntOrNull() ?: 0 }
        return nums to pre
    }

    fun isNewerVersion(candidateTag: String, currentTag: String): Boolean {
        if (candidateTag.isBlank()) return false
        return compareSemver(candidateTag, currentTag) > 0
    }

    suspend fun checkLatestRelease(): Result<UpdateReleaseInfo> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://api.github.com/repos/MasterPieceZez/antigravity-android/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "AntigravityAndroid/${BuildConfig.VERSION_NAME}")
            if (conn.responseCode !in 200..299) {
                return@withContext Result.failure(IllegalStateException("HTTP ${conn.responseCode}"))
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name", "").trim()
            val htmlUrl = json.optString("html_url", "https://github.com/MasterPieceZez/antigravity-android/releases")
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            var universalLiteUrl: String? = null
            var abiLiteUrl: String? = null
            var abiStandaloneUrl: String? = null
            var universalStandaloneUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    val dlUrl = a.optString("browser_download_url", "").ifBlank { null } ?: continue
                    val lower = name.lowercase()
                    when {
                        lower.contains("universal") && lower.contains("lite") -> universalLiteUrl = dlUrl
                        lower.contains(primaryAbi.lowercase()) && lower.contains("lite") -> abiLiteUrl = dlUrl
                        lower.contains(primaryAbi.lowercase()) -> abiStandaloneUrl = dlUrl
                        lower.contains("universal") -> universalStandaloneUrl = dlUrl
                    }
                }
            }
            val bestLite = universalLiteUrl ?: abiLiteUrl
            val bestApk = bestLite ?: abiStandaloneUrl ?: universalStandaloneUrl
            Result.success(UpdateReleaseInfo(tag, htmlUrl, bestApk, isLiteApk = bestLite != null))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    fun parseHubUrlFromText(text: String): String? {
        return HUB_URL_REGEX.findAll(text).lastOrNull()?.value
            ?.replace("127.0.0.1:4400", "localhost:4400")
    }

    fun readInstallLogTail(maxBytes: Int = 16384): String {
        val logFile = File(INSTALL_LOG)
        if (!logFile.isFile) return ""
        return try {
            RandomAccessFile(logFile, "r").use { raf ->
                val len = raf.length()
                val start = (len - maxBytes.toLong()).coerceAtLeast(0L)
                val toRead = (len - start).toInt()
                raf.seek(start)
                val buf = ByteArray(toRead)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun readLogTail(maxBytes: Int = 65536): String {
        val logFile = File(AGY_LOG)
        if (!logFile.isFile) return ""
        return try {
            RandomAccessFile(logFile, "r").use { raf ->
                val len = raf.length()
                val start = (len - maxBytes.toLong()).coerceAtLeast(0L)
                val toRead = (len - start).toInt()
                raf.seek(start)
                val buf = ByteArray(toRead)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun readLogHead(maxBytes: Int = 32768): String {
        val logFile = File(AGY_LOG)
        if (!logFile.isFile) return ""
        return try {
            RandomAccessFile(logFile, "r").use { raf ->
                val toRead = minOf(raf.length(), maxBytes.toLong()).toInt()
                val buf = ByteArray(toRead)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun parseHubUrlFromLog(): String? {
        val urlFile = File(HUB_URL_FILE)
        if (urlFile.isFile) {
            val persisted = runCatching { urlFile.readText().trim() }.getOrNull()
            val valid = persisted?.let { parseHubUrlFromText(it) }
            if (valid != null) return valid
        }
        val tail = readLogTail(65536)
        val fromTail = if (tail.isNotEmpty()) parseHubUrlFromText(tail) else null
        val resolved = fromTail ?: run {
            val head = readLogHead(32768)
            if (head.isNotEmpty()) parseHubUrlFromText(head) else null
        }
        if (resolved != null) {
            persistHubUrl(resolved)
        }
        return resolved
    }

    private fun persistHubUrl(url: String) {
        runCatching {
            val file = File(HUB_URL_FILE)
            file.parentFile?.mkdirs()
            file.writeText(url)
            Os.chmod(file.absolutePath, 0b110000000) // 0600
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

    suspend fun startHubAndBridge(
        context: Context,
        forceProvision: Boolean = false,
        forceRestart: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (!isBootstrapReady()) return@withContext
        hubTransitionCount.incrementAndGet()
        try {
            startMutex.withLock {
                if (forceProvision || !assetsProvisioned || !File("$BRIDGE_DIR/start_hub.sh").isFile) {
                    provisionBridgeAssets(context)
                }
                val pb = ProcessBuilder(BASH_BIN, "$BRIDGE_DIR/start_hub.sh")
                pb.directory(File(HOME_DIR))
                pb.environment().putAll(buildTermuxEnv())
                if (forceRestart) {
                    pb.environment()["AGY_FORCE_RESTART"] = "1"
                }
                pb.redirectErrorStream(true)
                runCatching {
                    val proc = pb.start()
                    proc.outputStream.close()
                    proc.inputStream.bufferedReader().use { it.readText() }
                    proc.waitFor()
                }
            }
        } finally {
            hubTransitionCount.decrementAndGet()
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
        var conn: HttpURLConnection? = null
        return try {
            val token = getOrCreateBridgeToken()
            conn = URL("http://127.0.0.1:$BRIDGE_PORT/health").openConnection() as HttpURLConnection
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
        } finally {
            conn?.disconnect()
        }
    }

    fun parseAnyHubUrlFromLog(): String? {
        val tail = readLogTail(65536)
        val fromTail = if (tail.isNotEmpty()) HUB_BASE_URL_REGEX.findAll(tail).lastOrNull()?.value else null
        val resolved = fromTail ?: run {
            val head = readLogHead(32768)
            if (head.isNotEmpty()) HUB_BASE_URL_REGEX.findAll(head).lastOrNull()?.value else null
        }
        return resolved?.replace("127.0.0.1:4400", "localhost:4400")
    }

    fun consumePendingOpenUrl(): String? {
        val file = File(PENDING_OPEN_URL_FILE)
        if (!file.isFile) return null
        return try {
            val raw = file.readText().trim()
            file.delete()
            if (raw.startsWith("http://") || raw.startsWith("https://")) raw else null
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    fun parseOAuthUrlFromLog(): String? {
        val tail = readLogTail(32768)
        if (tail.isEmpty()) return null
        return OAUTH_URL_REGEX.findAll(tail).lastOrNull()?.value
    }

    suspend fun pollForHubUrl(
        timeoutMs: Long = 20000L,
        onExternalUrl: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        var deadline = System.currentTimeMillis() + timeoutMs
        var httpReadyWithoutTokenSince = 0L
        var lastOpenedOAuthUrl: String? = null
        if (onExternalUrl != null) externalUrlHandlerActive = true
        try {
            while (System.currentTimeMillis() < deadline) {
                if (onExternalUrl != null) {
                    val pendingUrl = consumePendingOpenUrl()
                    val oauthUrl = pendingUrl ?: parseOAuthUrlFromLog()
                    if (oauthUrl != null && oauthUrl != lastOpenedOAuthUrl && !AntigravityFileHelper.isTrustedHubUri(oauthUrl)) {
                        lastOpenedOAuthUrl = oauthUrl
                        // Give user up to 3 minutes to complete Google OAuth in Chrome
                        deadline = maxOf(deadline, System.currentTimeMillis() + 180_000L)
                        runCatching { onExternalUrl(oauthUrl) }
                    }
                }
                val url = parseHubUrlFromLog()
                val httpReady = isHubHttpReady()
                if (url != null && httpReady) {
                    return@withContext url
                }
                if (httpReady) {
                    val now = System.currentTimeMillis()
                    if (httpReadyWithoutTokenSince == 0L) {
                        httpReadyWithoutTokenSince = now
                    } else if (now - httpReadyWithoutTokenSince >= 4000L) {
                        return@withContext parseAnyHubUrlFromLog() ?: "http://localhost:$HUB_PORT/"
                    }
                } else {
                    httpReadyWithoutTokenSince = 0L
                }
                delay(150L)
            }
            val fallback = parseHubUrlFromLog()
            if (fallback != null && isHubHttpReady()) {
                fallback
            } else if (isHubHttpReady()) {
                parseAnyHubUrlFromLog() ?: "http://localhost:$HUB_PORT/"
            } else {
                null
            }
        } finally {
            if (onExternalUrl != null) externalUrlHandlerActive = false
        }
    }

    suspend fun restartHub(
        context: Context,
        skipStop: Boolean = false,
        onExternalUrl: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        hubTransitionCount.incrementAndGet()
        try {
            restartMutex.withLock {
                if (!skipStop || isPortOpen(HUB_PORT, 50) || isHubProcessAlive()) {
                    stopBackgroundProcessesSuspend()
                }
                if (isPortOpen(HUB_PORT, 50) || isHubProcessAlive()) {
                    delay(200L)
                }
                runCatching { File(HUB_URL_FILE).delete() }
                runCatching { File(PENDING_OPEN_URL_FILE).delete() }
                File(AGY_LOG).apply {
                    parentFile?.mkdirs()
                    writeText("")
                }
                startHubAndBridge(context, forceProvision = false, forceRestart = true)
                pollForHubUrl(35000L, onExternalUrl = onExternalUrl)
            }
        } finally {
            hubTransitionCount.decrementAndGet()
        }
    }


    fun hasInternetConnection(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    fun isNetworkErrorOutput(output: String?): Boolean {
        if (output.isNullOrBlank()) return false
        val lower = output.lowercase()
        return lower.contains("could not resolve host") ||
            lower.contains("temporary failure in name resolution") ||
            lower.contains("name or service not known") ||
            lower.contains("failed to connect") ||
            lower.contains("network is unreachable") ||
            lower.contains("no route to host") ||
            lower.contains("connection timed out") ||
            lower.contains("operation timed out") ||
            lower.contains("connection reset by peer") ||
            lower.contains("ssl connect error") ||
            lower.contains("curl: (6)") ||
            lower.contains("curl: (7)") ||
            lower.contains("curl: (28)") ||
            lower.contains("curl: (35)") ||
            lower.contains("curl: (56)") ||
            lower.contains("could not fetch") ||
            lower.contains("unable to fetch some archives") ||
            lower.contains("failed to fetch")
    }

    suspend fun installAntigravityCli(
        context: Context,
        onStatus: (percent: Int, message: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!installInProgress.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Installation is already in progress"))
        }
        try {
            if (!isBootstrapReady()) {
                return@withContext Result.failure(IllegalStateException("Embedded Linux bootstrap is not ready"))
            }
            if (!isDeviceAbiSupportedByAgy()) {
                val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                return@withContext Result.failure(
                    IllegalStateException("Unsupported device ABI ($abi). Google Antigravity CLI requires a 64-bit arm64-v8a or x86_64 Android device.")
                )
            }
            if (!hasInternetConnection(context)) {
                return@withContext Result.failure(
                    IllegalStateException("No internet connection. Please connect to Wi-Fi or mobile data and try again.")
                )
            }
            val availBytes = runCatching { StatFs(FILES_DIR).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (availBytes < MIN_INSTALL_FREE_BYTES) {
                val availMb = availBytes / (1024.0 * 1024.0)
                return@withContext Result.failure(
                    IllegalStateException(String.format(java.util.Locale.US, "Not enough free internal storage (%.0f MB free, need >= 150 MB)", availMb))
                )
            }
            provisionBridgeAssets(context)
            onStatus(10, "Downloading Antigravity CLI installer...")
            val script = """
                set -e
                export DEBIAN_FRONTEND=noninteractive
                export APT_LISTCHANGES_FRONTEND=none
                INSTALL_SH="${'$'}TMPDIR/agy_install.sh"
                trap 'rm -f "${'$'}INSTALL_SH"' EXIT
                if ! command -v curl >/dev/null 2>&1 || ! curl -fsSL --proto '=https' --tlsv1.2 --connect-timeout 15 --max-time 60 -o "${'$'}INSTALL_SH" https://antigravity.google/cli/install.sh; then
                    dpkg --configure -a >/dev/null 2>&1 || true
                    apt-get update -y
                    apt-get install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" curl libcurl libngtcp2 libnghttp3 libnghttp2 libssh2 openssl zlib ca-certificates
                    curl -fsSL --proto '=https' --tlsv1.2 --connect-timeout 15 --max-time 180 -o "${'$'}INSTALL_SH" https://antigravity.google/cli/install.sh
                fi
                if [ ! -s "${'$'}INSTALL_SH" ] || ! head -n 1 "${'$'}INSTALL_SH" | grep -q '^#!'; then
                    echo "Downloaded installer script failed basic sanity check" >&2
                    exit 1
                fi
                bash "${'$'}INSTALL_SH"
            """.trimIndent()
            val pb = ProcessBuilder(BASH_BIN, "-c", script)
            pb.directory(File(HOME_DIR))
            pb.environment().putAll(buildTermuxEnv())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            activeInstallProcess.set(proc)
            proc.outputStream.close()
            var step = 15
            val recentLines = ArrayDeque<String>(12)
            val installLogFile = File(INSTALL_LOG).apply { parentFile?.mkdirs() }
            val logWriter = runCatching { installLogFile.bufferedWriter() }.getOrNull()
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    runCatching {
                        logWriter?.write(line)
                        logWriter?.newLine()
                    }
                    val clean = line.trim()
                    if (clean.isNotEmpty()) {
                        if (recentLines.size >= 10) recentLines.removeFirst()
                        recentLines.addLast(clean)
                        step = (step + 1).coerceAtMost(92)
                        onStatus(step, clean.take(90))
                    }
                }
            } finally {
                runCatching { logWriter?.flush() }
                runCatching { logWriter?.close() }
            }
            val code = proc.waitFor()
            if (code == 0 && isAgyInstalled()) {
                provisionBridgeAssets(context)
                if (isRegionBypassEnabled(context)) {
                    applyRegionEligibilityPatch(true)
                }
                onStatus(100, "Antigravity CLI installed!")
                Result.success(Unit)
            } else {
                val combinedTail = recentLines.joinToString("\n")
                if (!hasInternetConnection(context) || isNetworkErrorOutput(combinedTail)) {
                    Result.failure(
                        IllegalStateException("No internet connection (or unstable network). Please check your connection and try again.")
                    )
                } else {
                    val tailErr = recentLines.takeLast(4).joinToString(" | ").take(240)
                    val detail = if (tailErr.isNotBlank()) "Installer exited with code $code: $tailErr" else "Installer exited with code $code"
                    Result.failure(IllegalStateException(detail))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            activeInstallProcess.getAndSet(null)?.let { p ->
                if (p.isAlive) runCatching { p.destroyForcibly() }
            }
            installInProgress.set(false)
        }
    }

    private fun isPidAntigravityHub(pid: Int): Boolean {
        val selfPid = runCatching { Os.getpid() }.getOrDefault(-1)
        if (pid <= 1 || pid == selfPid) return false
        val cmdline = runCatching {
            File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
        }.getOrNull().orEmpty()
        val exeTarget = runCatching { Os.readlink("/proc/$pid/exe") }.getOrNull().orEmpty()
        if (cmdline.contains("bridge_server.py")) return true
        if ((cmdline.contains("--hub") || cmdline.contains("hub-port=4400")) &&
            (cmdline.contains("agy") || exeTarget.endsWith("/agy") || exeTarget.contains("/.local/"))
        ) {
            return true
        }
        return exeTarget == AGY_BIN
    }

    private fun findMatchingHubPidsInProc(): List<Int> {
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return emptyList()
        val matched = ArrayList<Int>()
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (isPidAntigravityHub(pid)) {
                matched.add(pid)
            }
        }
        return matched
    }

    private fun sendInitialStopSignals(): Pair<Int?, File> {
        cachedHubListening = false
        runCatching { File(HUB_URL_FILE).delete() }
        val pidFile = File(HUB_PID_FILE)
        val recordedPid = runCatching { pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() }.getOrNull()
        runCatching { pidFile.delete() }
        if (recordedPid != null && isPidAntigravityHub(recordedPid)) {
            runCatching { Os.kill(-recordedPid, 15) } // SIGTERM process group
            runCatching { Os.kill(recordedPid, 15) } // SIGTERM
        }
        for (pid in findMatchingHubPidsInProc()) {
            runCatching { Os.kill(-pid, 15) }
            runCatching { Os.kill(pid, 15) }
        }
        val pkill = File("$PREFIX_DIR/bin/pkill")
        val env = buildTermuxEnv()
        if (pkill.isFile) {
            for (pattern in listOf("agy.*--hub", "hub-port=4400", "bridge_server.py")) {
                runCatching {
                    val pb = ProcessBuilder(pkill.absolutePath, "-f", pattern)
                    pb.environment().putAll(env)
                    pb.start().waitFor()
                }
            }
        }
        return recordedPid to pkill
    }

    private fun isHubProcessAlive(): Boolean = findMatchingHubPidsInProc().isNotEmpty()

    private fun forceKillHubIfListening(recordedPid: Int?, pkill: File) {
        if (isPortOpen(HUB_PORT, 100) || isHubProcessAlive()) {
            if (recordedPid != null && isPidAntigravityHub(recordedPid)) {
                runCatching { Os.kill(-recordedPid, 9) } // SIGKILL process group
                runCatching { Os.kill(recordedPid, 9) } // SIGKILL
            }
            for (pid in findMatchingHubPidsInProc()) {
                runCatching { Os.kill(-pid, 9) }
                runCatching { Os.kill(pid, 9) }
            }
            if (pkill.isFile) {
                val env = buildTermuxEnv()
                for (pattern in listOf("agy.*--hub", "hub-port=4400")) {
                    runCatching {
                        val pb = ProcessBuilder(pkill.absolutePath, "-9", "-f", pattern)
                        pb.environment().putAll(env)
                        pb.start().waitFor()
                    }
                }
            }
        }
        cachedHubListening = false
    }

    suspend fun stopBackgroundProcessesSuspend() = withContext(Dispatchers.IO) {
        val (recordedPid, pkill) = sendInitialStopSignals()
        val deadline = System.currentTimeMillis() + 900L
        while (System.currentTimeMillis() < deadline && (isPortOpen(HUB_PORT, 50) || isHubProcessAlive())) {
            delay(50L)
        }
        forceKillHubIfListening(recordedPid, pkill)
        val killDeadline = System.currentTimeMillis() + 800L
        while (System.currentTimeMillis() < killDeadline && (isPortOpen(HUB_PORT, 50) || isHubProcessAlive())) {
            delay(50L)
        }
        cachedHubListening = false
    }

    fun isRegionBypassEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_ENGINE, Context.MODE_PRIVATE)
            .getBoolean(KEY_REGION_BYPASS, false)
    }

    fun setRegionBypassEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_ENGINE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REGION_BYPASS, enabled)
            .apply()
    }

    private fun findRegionPatchTargets(): List<File> {
        val targets = LinkedHashSet<File>()
        val agy = File(AGY_BIN)
        if (agy.isFile && agy.length() > 4096L) {
            targets.add(agy)
        }
        for (base in listOf("$HOME_DIR/.local", "$HOME_DIR/.antigravity")) {
            val dir = File(base)
            if (!dir.isDirectory) continue
            dir.walkTopDown().maxDepth(5).forEach { f ->
                if (f.isFile && f.length() > 4096L && (f.name == "agy" || f.name.startsWith("language_server"))) {
                    targets.add(f)
                }
            }
        }
        return targets.toList()
    }

    private fun indexOfBytes(haystack: ByteArray, length: Int, needle: ByteArray, start: Int = 0): Int {
        val limit = length - needle.size
        var i = start
        while (i <= limit) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
            i++
        }
        return -1
    }

    private fun replaceSameLengthInFile(file: File, from: ByteArray, to: ByteArray): Boolean {
        if (!file.isFile || from.size != to.size || from.isEmpty()) return false
        val chunkSize = 1024 * 1024
        val overlap = from.size - 1
        val buf = ByteArray(chunkSize + overlap)
        var found = false

        file.inputStream().use { input ->
            var carry = 0
            while (true) {
                val read = input.read(buf, carry, chunkSize)
                if (read <= 0) break
                val total = carry + read
                if (indexOfBytes(buf, total, from) >= 0) {
                    found = true
                    break
                }
                if (total >= overlap) {
                    System.arraycopy(buf, total - overlap, buf, 0, overlap)
                    carry = overlap
                } else {
                    carry = total
                }
            }
        }
        if (!found) return false

        val tmp = File(file.parentFile, "${file.name}.agypatch.${Os.getpid()}.tmp")
        var patched = false
        try {
            val origMode = runCatching { Os.stat(file.absolutePath).st_mode and 0b111111111111 }.getOrDefault(0b111101101)
            file.inputStream().use { input ->
                tmp.outputStream().use { output ->
                    var carry = 0
                    while (true) {
                        val read = input.read(buf, carry, chunkSize)
                        if (read <= 0) break
                        val total = carry + read
                        var pos = 0
                        while (pos <= total - from.size) {
                            val idx = indexOfBytes(buf, total, from, pos)
                            if (idx < 0) break
                            System.arraycopy(to, 0, buf, idx, to.size)
                            patched = true
                            pos = idx + to.size
                        }
                        val emitLen = if (read < chunkSize) total else (total - overlap).coerceAtLeast(0)
                        if (emitLen > 0) {
                            output.write(buf, 0, emitLen)
                        }
                        val rem = total - emitLen
                        if (rem > 0) {
                            System.arraycopy(buf, emitLen, buf, 0, rem)
                        }
                        carry = rem
                    }
                    if (carry > 0) {
                        output.write(buf, 0, carry)
                    }
                    output.flush()
                }
            }
            runCatching { Os.chmod(tmp.absolutePath, origMode) }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return false
            }
            return patched
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            return false
        }
    }

    suspend fun applyRegionEligibilityPatch(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val from = if (enable) BYTES_INELIGIBLE else BYTES_INEXIGIBLE
        val to = if (enable) BYTES_INEXIGIBLE else BYTES_INELIGIBLE
        var anyPatched = false
        for (target in findRegionPatchTargets()) {
            if (replaceSameLengthInFile(target, from, to)) {
                anyPatched = true
            }
        }
        anyPatched
    }

    @JvmStatic
    fun stopBackgroundProcesses() {
        val (recordedPid, pkill) = sendInitialStopSignals()
        val deadline = System.currentTimeMillis() + 2500L
        while (System.currentTimeMillis() < deadline && (isPortOpen(HUB_PORT, 100) || isHubProcessAlive())) {
            Thread.sleep(100)
        }
        forceKillHubIfListening(recordedPid, pkill)
        val killDeadline = System.currentTimeMillis() + 1000L
        while (System.currentTimeMillis() < killDeadline && (isPortOpen(HUB_PORT, 100) || isHubProcessAlive())) {
            Thread.sleep(100)
        }
        cachedHubListening = false
    }
}
