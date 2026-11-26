package com.termux.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Base64InputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

object AntigravityFileHelper {
    private const val MAX_BASE64_DECODED_BYTES = 25L * 1024L * 1024L // 25 MB safety cap

    fun getDownloadsDir(): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getShareInboxDir(): File {
        val dir = File(getDownloadsDir(), "AntigravityInbox")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Strips path separators, parent-traversal segments, and unsafe characters.
     */
    fun sanitizeFileName(rawName: String?, fallbackPrefix: String = "download"): String {
        val base = rawName.orEmpty()
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("\\.{2,}"), "_")
            .trimStart('.')
        return if (base.isBlank()) "${fallbackPrefix}_${System.currentTimeMillis()}" else base.take(120)
    }

    /**
     * Returns a non-colliding File inside [dir] without overwriting existing files.
     */
    fun uniqueFile(dir: File, safeName: String): File {
        if (!dir.exists()) dir.mkdirs()
        var candidate = File(dir, safeName)
        if (!candidate.exists()) return candidate

        val dotIdx = safeName.lastIndexOf('.')
        val stem = if (dotIdx > 0) safeName.substring(0, dotIdx) else safeName
        val ext = if (dotIdx > 0) safeName.substring(dotIdx) else ""
        var counter = 1
        while (candidate.exists() && counter < 10_000) {
            candidate = File(dir, "$stem ($counter)$ext")
            counter++
        }
        return candidate
    }

    fun isLocalhostUri(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "localhost" || host == "127.0.0.1"
    }

    /**
     * Streams Base64 decoding directly to disk in 64 KB chunks with a 25 MB cap
     * so large blob:/data: downloads never OOM and never overwrite existing files.
     */
    fun saveBase64ToDownloads(rawFilename: String?, base64Data: String): Result<File> {
        return try {
            val cleanB64 = base64Data.substringAfter("base64,", base64Data).trim()
            val estimatedBytes = (cleanB64.length.toLong() * 3L) / 4L
            if (estimatedBytes > MAX_BASE64_DECODED_BYTES) {
                return Result.failure(IllegalArgumentException("Download exceeds 25 MB limit"))
            }
            val safeName = sanitizeFileName(rawFilename, "download")
            val outFile = uniqueFile(getDownloadsDir(), safeName)
            val b64Stream = Base64InputStream(
                ByteArrayInputStream(cleanB64.toByteArray(Charsets.US_ASCII)),
                Base64.DEFAULT
            )
            var totalWritten = 0L
            b64Stream.use { src ->
                FileOutputStream(outFile).use { dst ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        totalWritten += n
                        if (totalWritten > MAX_BASE64_DECODED_BYTES) {
                            runCatching { outFile.delete() }
                            return Result.failure(IllegalArgumentException("Download exceeds 25 MB limit"))
                        }
                        dst.write(buf, 0, n)
                    }
                }
            }
            Result.success(outFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun saveSharedUriToInbox(context: Context, uri: Uri): String? {
        return try {
            var rawName: String? = null
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    rawName = c.getString(idx)
                }
            }
            val safeName = sanitizeFileName(rawName, "shared")
            val outFile = uniqueFile(getShareInboxDir(), safeName)
            context.contentResolver.openInputStream(uri)?.use { src ->
                outFile.outputStream().use { dst -> src.copyTo(dst) }
            } ?: return null
            "~/storage/shared/Download/AntigravityInbox/${outFile.name}"
        } catch (_: Exception) {
            null
        }
    }
}
