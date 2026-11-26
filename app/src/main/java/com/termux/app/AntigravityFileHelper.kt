package com.termux.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Base64InputStream
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object AntigravityFileHelper {
    private const val MAX_BASE64_DECODED_BYTES = 25L * 1024L * 1024L // 25 MB safety cap

    fun hasPublicDownloadsWriteAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

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

    fun getInternalInboxDir(): File {
        val dir = File(AntigravityEngine.HOME_DIR, "inbox")
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
            FileOutputStream(outFile).use { dst ->
                decodeBase64Stream(cleanB64, dst)
            }
            Result.success(outFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Context-aware download saver that falls back to MediaStore.Downloads (Android 10+)
     * or app-external Downloads when All Files Access was not granted.
     */
    fun saveBase64ToDownloads(
        context: Context,
        rawFilename: String?,
        base64Data: String,
        mimeType: String? = null
    ): Result<String> {
        val cleanB64 = base64Data.substringAfter("base64,", base64Data).trim()
        val estimatedBytes = (cleanB64.length.toLong() * 3L) / 4L
        if (estimatedBytes > MAX_BASE64_DECODED_BYTES) {
            return Result.failure(IllegalArgumentException("Download exceeds 25 MB limit"))
        }
        return saveToDownloadsWithFallback(context, rawFilename, mimeType) { dst ->
            decodeBase64Stream(cleanB64, dst)
        }
    }

    fun saveToDownloadsWithFallback(
        context: Context,
        rawFilename: String?,
        mimeType: String? = null,
        writer: (OutputStream) -> Unit
    ): Result<String> {
        val safeName = sanitizeFileName(rawFilename, "download")
        if (hasPublicDownloadsWriteAccess(context)) {
            return runCatching {
                val outFile = uniqueFile(getDownloadsDir(), safeName)
                FileOutputStream(outFile).use(writer)
                runCatching {
                    MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
                }
                "Download/${outFile.name}"
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var insertedUri: Uri? = null
            return try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType?.ifBlank { null } ?: "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.failure(IllegalStateException("Unable to create entry in Downloads"))
                insertedUri = uri
                resolver.openOutputStream(uri)?.use(writer)
                    ?: return Result.failure(IllegalStateException("Unable to open Downloads output stream"))
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Result.success("Download/$safeName")
            } catch (e: Exception) {
                insertedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                Result.failure(e)
            }
        }
        return runCatching {
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads").apply { mkdirs() }
            val outFile = uniqueFile(fallbackDir, safeName)
            FileOutputStream(outFile).use(writer)
            outFile.absolutePath
        }
    }

    private fun decodeBase64Stream(cleanB64: String, dst: OutputStream) {
        val b64Stream = Base64InputStream(
            ByteArrayInputStream(cleanB64.toByteArray(Charsets.US_ASCII)),
            Base64.DEFAULT
        )
        var totalWritten = 0L
        b64Stream.use { src ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n <= 0) break
                totalWritten += n
                if (totalWritten > MAX_BASE64_DECODED_BYTES) {
                    throw IllegalArgumentException("Download exceeds 25 MB limit")
                }
                dst.write(buf, 0, n)
            }
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
            val usePublic = hasPublicDownloadsWriteAccess(context)
            val targetDir = if (usePublic) getShareInboxDir() else getInternalInboxDir()
            val outFile = uniqueFile(targetDir, safeName)
            context.contentResolver.openInputStream(uri)?.use { src ->
                outFile.outputStream().use { dst -> src.copyTo(dst) }
            } ?: return null
            if (usePublic) {
                "~/storage/shared/Download/AntigravityInbox/${outFile.name}"
            } else {
                "~/inbox/${outFile.name}"
            }
        } catch (_: Exception) {
            null
        }
    }
}
