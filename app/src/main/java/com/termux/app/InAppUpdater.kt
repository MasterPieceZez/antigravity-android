package com.termux.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.termux.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

object InAppUpdater {

    private const val ALLOWED_RELEASE_PATH_PREFIX = "/MasterPieceZez/antigravity-android/releases/download/"
    private val SHA256_LINE_REGEX = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")

    fun isAllowedUpdateUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (host != "github.com") return false
        val rawPath = uri.rawPath ?: return false
        if (rawPath.contains("..") || rawPath.contains('\\')) return false
        return rawPath.startsWith(ALLOWED_RELEASE_PATH_PREFIX)
    }

    internal fun isAllowedRedirectUrl(url: String?): Boolean {
        if (isAllowedUpdateUrl(url)) return true
        if (url.isNullOrBlank()) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host == "objects.githubusercontent.com" ||
            host == "release-assets.githubusercontent.com" ||
            host == "github-releases.githubusercontent.com"
    }

    fun parseSha256ForAsset(checksumsText: String, assetFileName: String): String? {
        if (checksumsText.isBlank() || assetFileName.isBlank()) return null
        val targetName = assetFileName.trim().removePrefix("./")
        for (rawLine in checksumsText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = SHA256_LINE_REGEX.matchEntire(line) ?: continue
            val hex = match.groupValues[1].lowercase(Locale.US)
            val file = match.groupValues[2].trim().removePrefix("./")
            if (file == targetName) {
                return hex
            }
        }
        return null
    }

    fun cleanupCachedUpdates(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "updates")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun downloadAndVerifyApk(
        context: Context,
        info: UpdateReleaseInfo,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val apkUrl = info.downloadApkUrl
        if (!isAllowedUpdateUrl(apkUrl)) {
            return@withContext Result.failure(SecurityException("Untrusted update download URL"))
        }

        val checksumsUrl = info.checksumsUrl
        val assetName = info.apkFileName
        if (checksumsUrl.isNullOrBlank() || assetName.isNullOrBlank()) {
            return@withContext Result.failure(
                SecurityException("Release is missing SHA256SUMS.txt verification metadata")
            )
        }
        if (!isAllowedUpdateUrl(checksumsUrl)) {
            return@withContext Result.failure(SecurityException("Untrusted checksums URL"))
        }

        val updatesDir = File(context.cacheDir, "updates")
        if (updatesDir.exists()) {
            updatesDir.deleteRecursively()
        }
        if (!updatesDir.mkdirs() && !updatesDir.isDirectory) {
            return@withContext Result.failure(IllegalStateException("Unable to create update cache directory"))
        }

        val safeName = AntigravityFileHelper.sanitizeFileName(assetName, "antigravity-update.apk")
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        val targetFile = File(updatesDir, safeName)
        val partFile = File(updatesDir, "$safeName.part")

        var conn: HttpURLConnection? = null
        try {
            onProgress(0, "Connecting to GitHub Releases...")
            conn = openFollowingRedirects(apkUrl!!)
            val status = conn.responseCode
            if (status !in 200..299) {
                return@withContext Result.failure(IllegalStateException("Download failed (HTTP $status)"))
            }

            val totalBytes = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            val startMs = System.currentTimeMillis()
            var lastEmitMs = 0L

            conn.inputStream.use { input ->
                partFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= 150L || (totalBytes > 0 && downloaded >= totalBytes)) {
                            lastEmitMs = now
                            val elapsedSec = ((now - startMs).coerceAtLeast(1L)) / 1000.0
                            val speedBytesPerSec = downloaded / elapsedSec
                            val speedStr = formatRate(speedBytesPerSec)
                            val dlMb = downloaded / (1024.0 * 1024.0)
                            if (totalBytes > 0L) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                val totMb = totalBytes / (1024.0 * 1024.0)
                                onProgress(
                                    pct,
                                    String.format(Locale.US, "Downloading... %d%% (%.1f / %.1f MB • %s)", pct, dlMb, totMb, speedStr)
                                )
                            } else {
                                onProgress(0, String.format(Locale.US, "Downloading... %.1f MB • %s", dlMb, speedStr))
                            }
                        }
                    }
                }
            }

            val computedSha = digest.digest().joinToString("") { "%02x".format(it) }
            onProgress(100, "Verifying SHA-256...")
            val checksumsText = fetchTextFollowingRedirects(checksumsUrl)
            val expectedSha = parseSha256ForAsset(checksumsText, assetName)
            if (expectedSha == null || !expectedSha.equals(computedSha, ignoreCase = true)) {
                partFile.delete()
                return@withContext Result.failure(SecurityException("APK SHA-256 checksum mismatch"))
            }

            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.delete()
                return@withContext Result.failure(IllegalStateException("Failed to finalize downloaded APK"))
            }
            Result.success(targetFile)
        } catch (e: Exception) {
            runCatching { partFile.delete() }
            runCatching { targetFile.delete() }
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun openFollowingRedirects(startUrl: String, maxRedirects: Int = 5): HttpURLConnection {
        var currentUrl = startUrl
        for (hop in 0..maxRedirects) {
            val allowed = if (hop == 0) isAllowedUpdateUrl(currentUrl) else isAllowedRedirectUrl(currentUrl)
            if (!allowed) {
                throw SecurityException("Untrusted update host or path: $currentUrl")
            }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AntigravityAndroid/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 ||
                code == 308
            ) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) {
                    throw IllegalStateException("Redirect ($code) missing Location header")
                }
                currentUrl = URL(URL(currentUrl), loc).toString()
                continue
            }
            return conn
        }
        throw IllegalStateException("Too many redirects while downloading update")
    }

    private fun fetchTextFollowingRedirects(url: String): String {
        val conn = openFollowingRedirects(url)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Failed to fetch SHA256SUMS.txt (HTTP $code)")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun formatRate(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024.0 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }
}
