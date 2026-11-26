package com.termux.app

import android.content.Context
import android.os.Build
import android.system.Os
import com.termux.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    const val AGY_LOG = "$BRIDGE_DIR/agy.log"
    const val HUB_URL_FILE = "$BRIDGE_DIR/hub.url"
    const val HUB_PID_FILE = "$BRIDGE_DIR/hub.pid"
    const val NOTIFY_FILE = "$BRIDGE_DIR/pending_notify.json"
    const val TOKEN_FILE = "$BRIDGE_DIR/bridge.token"
    const val BASH_BIN = "$PREFIX_DIR/bin/bash"
    const val HUB_PORT = 4400
    const val BRIDGE_PORT = 4401

    private val startMutex = Mutex()
    private val restartMutex = Mutex()
    private val provisionLock = Any()
    private val tokenLock = Any()
    private val installInProgress = AtomicBoolean(false)
    @Volatile
    private var cachedBridgeToken: String? = null
    @Volatile
    private var cachedHubListening: Boolean = false
    private val HUB_URL_REGEX = Regex("https?://(?:localhost|127\\.0\\.0\\.1):4400/#t=[A-Za-z0-9._-]+")


    fun isBootstrapReady(): Boolean = File(BASH_BIN).exists()

    fun isAgyInstalled(): Boolean = File(AGY_BIN).let { it.isFile && it.length() > 0 }

    fun isInstallRunning(): Boolean = installInProgress.get()

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
                                Os.symlink(dpkgWrapFile.absolutePath, dpkgBin.absolutePath)
                            }
                        } else {
                            dpkgBin.delete()
                            Os.symlink(dpkgWrapFile.absolutePath, dpkgBin.absolutePath)
                        }
                    }
                }
            }
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


    fun isHubHttpReady(timeoutMs: Int = 350): Boolean {
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

    suspend fun startHubAndBridge(context: Context) = withContext(Dispatchers.IO) {
        if (!isBootstrapReady()) return@withContext
        startMutex.withLock {
            provisionBridgeAssets(context)
            val pb = ProcessBuilder(BASH_BIN, "$BRIDGE_DIR/start_hub.sh")
            pb.directory(File(HOME_DIR))
            pb.environment().putAll(buildTermuxEnv())
            pb.redirectErrorStream(true)
            runCatching {
                val proc = pb.start()
                proc.outputStream.close()
                proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
            }
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

    suspend fun pollForHubUrl(timeoutMs: Long = 20000L): String? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val url = parseHubUrlFromLog()
            if (url != null && isHubHttpReady()) {
                return@withContext url
            }
            delay(150L)
        }
        val fallback = parseHubUrlFromLog()
        if (fallback != null && isHubHttpReady()) fallback else null
    }

    suspend fun restartHub(context: Context): String? = withContext(Dispatchers.IO) {
        restartMutex.withLock {
            stopBackgroundProcessesSuspend()
            delay(450L)
            File(AGY_LOG).apply {
                parentFile?.mkdirs()
                writeText("")
            }
            startHubAndBridge(context)
            pollForHubUrl(15000L)
        }
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
            provisionBridgeAssets(context)
            onStatus(10, "Updating package index & installing curl...")
            val script = """
                set -e
                export DEBIAN_FRONTEND=noninteractive
                export APT_LISTCHANGES_FRONTEND=none
                dpkg --configure -a >/dev/null 2>&1 || true
                apt-get update -y
                apt-get install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" curl ca-certificates
                INSTALL_SH="${'$'}TMPDIR/agy_install.sh"
                trap 'rm -f "${'$'}INSTALL_SH"' EXIT
                curl -fsSL --proto '=https' --tlsv1.2 -o "${'$'}INSTALL_SH" https://antigravity.google/cli/install.sh
                bash "${'$'}INSTALL_SH"
            """.trimIndent()
            val pb = ProcessBuilder(BASH_BIN, "-c", script)
            pb.directory(File(HOME_DIR))
            pb.environment().putAll(buildTermuxEnv())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.outputStream.close()
            var step = 15
            val recentLines = ArrayDeque<String>(12)
            proc.inputStream.bufferedReader().forEachLine { line ->
                val clean = line.trim()
                if (clean.isNotEmpty()) {
                    if (recentLines.size >= 10) recentLines.removeFirst()
                    recentLines.addLast(clean)
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
                val tailErr = recentLines.takeLast(4).joinToString(" | ").take(240)
                val detail = if (tailErr.isNotBlank()) "Installer exited with code $code: $tailErr" else "Installer exited with code $code"
                Result.failure(IllegalStateException(detail))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            installInProgress.set(false)
        }
    }

    private fun sendInitialStopSignals(): Pair<Int?, File> {
        cachedHubListening = false
        runCatching { File(HUB_URL_FILE).delete() }
        val pidFile = File(HUB_PID_FILE)
        val recordedPid = runCatching { pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() }.getOrNull()
        runCatching { pidFile.delete() }
        if (recordedPid != null && recordedPid > 0) {
            runCatching { Os.kill(-recordedPid, 15) } // SIGTERM process group
            runCatching { Os.kill(recordedPid, 15) } // SIGTERM
        }
        val pkill = File("$PREFIX_DIR/bin/pkill")
        val env = buildTermuxEnv()
        if (pkill.isFile) {
            for (pattern in listOf("[/]agy --hub", "bridge_server.py")) {
                runCatching {
                    val pb = ProcessBuilder(pkill.absolutePath, "-f", pattern)
                    pb.environment().putAll(env)
                    pb.start().waitFor()
                }
            }
        }
        return recordedPid to pkill
    }

    private fun forceKillHubIfListening(recordedPid: Int?, pkill: File) {
        if (isPortOpen(HUB_PORT, 100)) {
            if (recordedPid != null && recordedPid > 0) {
                runCatching { Os.kill(-recordedPid, 9) } // SIGKILL process group
                runCatching { Os.kill(recordedPid, 9) } // SIGKILL
            }
            if (pkill.isFile) {
                val env = buildTermuxEnv()
                runCatching {
                    val pb = ProcessBuilder(pkill.absolutePath, "-9", "-f", "[/]agy --hub")
                    pb.environment().putAll(env)
                    pb.start().waitFor()
                }
            }
        }
        cachedHubListening = false
    }

    suspend fun stopBackgroundProcessesSuspend() = withContext(Dispatchers.IO) {
        val (recordedPid, pkill) = sendInitialStopSignals()
        if (pkill.isFile) {
            val deadline = System.currentTimeMillis() + 800L
            while (System.currentTimeMillis() < deadline && isPortOpen(HUB_PORT, 100)) {
                delay(100L)
            }
            forceKillHubIfListening(recordedPid, pkill)
        } else {
            cachedHubListening = false
        }
    }

    @JvmStatic
    fun stopBackgroundProcesses() {
        val (recordedPid, pkill) = sendInitialStopSignals()
        if (pkill.isFile) {
            val deadline = System.currentTimeMillis() + 800L
            while (System.currentTimeMillis() < deadline && isPortOpen(HUB_PORT, 100)) {
                Thread.sleep(100)
            }
            forceKillHubIfListening(recordedPid, pkill)
        } else {
            cachedHubListening = false
        }
    }
}
