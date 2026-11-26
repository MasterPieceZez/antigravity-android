package com.termux.app

import android.content.Context
import android.os.StatFs
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class BackupMode(val slug: String, val label: String) {
    FULL("full", "Full Backup (Personal)"),
    PACKAGES_ONLY("packages", "Packages-Only (Shareable)")
}

object BackupRestoreEngine {

    private const val MIN_RESTORE_FREE_BYTES = 128L * 1024L * 1024L

    fun getDownloadsDir(): File = AntigravityFileHelper.getDownloadsDir()
    fun getDefaultMasterBackupFile(): File = File(getDownloadsDir(), "termux-agy-master.tar.zst")

    fun isSafeTarEntryPath(entry: String): Boolean {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) return false
        val normalized = trimmed.replace('\\', '/')
        return normalized.split('/').none { it == ".." }
    }

    fun isSafeArchiveLinkTarget(entryPath: String, linkTarget: String, isHardLink: Boolean = false): Boolean {
        val trimmed = linkTarget.trim()
        if (trimmed.isEmpty() || trimmed.contains('\\')) return false
        if (isHardLink) return isSafeTarEntryPath(trimmed)

        if (trimmed.startsWith("/")) {
            return trimmed.startsWith("/data/data/com.termux/") ||
                trimmed.startsWith("/data/data/com.agy.os/") ||
                trimmed.startsWith("/storage/") ||
                trimmed.startsWith("/sdcard/")
        }

        val entryParts = ArrayDeque<String>()
        val normalizedEntry = entryPath.trim().replace('\\', '/')
        val lastSlash = normalizedEntry.lastIndexOf('/')
        if (lastSlash > 0) {
            for (seg in normalizedEntry.substring(0, lastSlash).split('/')) {
                if (seg == "..") return false
                if (seg.isNotEmpty() && seg != ".") entryParts.addLast(seg)
            }
        }
        for (seg in trimmed.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> {
                    if (entryParts.isEmpty()) return false
                    entryParts.removeLast()
                }
                else -> entryParts.addLast(seg)
            }
        }
        return true
    }

    private fun validateArchive(context: Context, archiveFile: File): Result<Unit> {
        return try {
            val zstd = resolveZstdBinary(context)
            val patchBin = File(context.applicationInfo.nativeLibraryDir, "libagy_patch.so")
            val env = AntigravityEngine.buildTermuxEnv()
            val zstdProc = ProcessBuilder(zstd.absolutePath, "-dc", archiveFile.absolutePath).apply {
                environment().putAll(env)
            }.start()

            if (patchBin.isFile) {
                val checkProc = ProcessBuilder(patchBin.absolutePath, "--check-tar").apply {
                    environment().putAll(env)
                    redirectErrorStream(true)
                }.start()
                val zstdErr = StringBuilder()
                val zstdErrThread = Thread {
                    runCatching {
                        zstdProc.errorStream.bufferedReader().forEachLine {
                            if (zstdErr.length < 1000) zstdErr.append(it).append('\n')
                        }
                    }
                }.apply { start() }
                val checkErr = StringBuilder()
                val checkErrThread = Thread {
                    runCatching {
                        checkProc.inputStream.bufferedReader().forEachLine {
                            if (checkErr.length < 1000) checkErr.append(it).append('\n')
                        }
                    }
                }.apply { start() }
                val pipe = Thread {
                    runCatching {
                        zstdProc.inputStream.use { zOut ->
                            checkProc.outputStream.use { cIn -> zOut.copyTo(cIn) }
                        }
                    }
                }.apply { start() }
                pipe.join()
                zstdErrThread.join()
                checkErrThread.join()
                val zExit = zstdProc.waitFor()
                val cExit = checkProc.waitFor()
                return when {
                    cExit != 0 -> Result.failure(
                        SecurityException(checkErr.toString().trim().ifBlank { "Archive safety check failed (exit $cExit)" })
                    )
                    zExit != 0 -> Result.failure(
                        IllegalStateException("zstd integrity check failed (exit $zExit): ${zstdErr.toString().trim()}")
                    )
                    else -> Result.success(Unit)
                }
            }

            val tar = resolveTarBinary()
            val tarProc = ProcessBuilder(tar.absolutePath, "-tf", "-").apply {
                environment().putAll(env)
                redirectErrorStream(true)
            }.start()
            val pipe = Thread {
                runCatching {
                    zstdProc.inputStream.use { zOut ->
                        tarProc.outputStream.use { tIn -> zOut.copyTo(tIn) }
                    }
                }
            }.apply { start() }
            var unsafeEntry: String? = null
            tarProc.inputStream.bufferedReader().forEachLine { line ->
                if (unsafeEntry == null && !isSafeTarEntryPath(line)) {
                    unsafeEntry = line.trim()
                }
            }
            pipe.join()
            val zExit = zstdProc.waitFor()
            val tExit = tarProc.waitFor()
            if (unsafeEntry != null) {
                Result.failure(SecurityException("Rejected unsafe archive path: $unsafeEntry"))
            } else if (zExit != 0 || tExit != 0) {
                Result.failure(IllegalStateException("Archive listing failed (zstd=$zExit, tar=$tExit)"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private val operationInProgress = AtomicBoolean(false)

    fun isBusy(): Boolean = operationInProgress.get()

    fun resolveZstdBinary(context: Context): File {
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libzstd_cli.so")
        if (nativeLib.isFile) return nativeLib
        val termuxZstd = File("${AntigravityEngine.PREFIX_DIR}/bin/zstd")
        if (termuxZstd.isFile) return termuxZstd
        throw IllegalStateException("Bundled zstd binary not found")
    }

    fun resolveTarBinary(): File {
        val termuxTar = File("${AntigravityEngine.PREFIX_DIR}/bin/tar")
        if (termuxTar.isFile) return termuxTar
        val systemTar = File("/system/bin/tar")
        if (systemTar.isFile) return systemTar
        throw IllegalStateException("tar binary not found in embedded Linux environment")
    }

    fun findDownloadBackups(): List<File> {
        val dir = getDownloadsDir()
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".tar.zst", ignoreCase = true) }
            ?.toList()
            ?: emptyList()
        return files.sortedWith(
            compareByDescending<File> { it.name == "termux-port.tar.zst" || it.name == "termux-agy-master.tar.zst" }
                .thenByDescending { it.lastModified() }
        )
    }

    suspend fun verifyArchive(context: Context, archiveFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!archiveFile.isFile || archiveFile.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("Archive file is missing or empty"))
            }
            val zstd = resolveZstdBinary(context)
            val pb = ProcessBuilder(zstd.absolutePath, "-t", archiveFile.absolutePath)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("zstd integrity check failed: ${output.takeLast(200)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreBackup(
        context: Context,
        archiveFile: File,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!operationInProgress.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Another backup or restore is already running"))
        }
        try {
            restoreBackupInternal(context, archiveFile, onProgress)
        } finally {
            operationInProgress.set(false)
        }
    }

    private suspend fun restoreBackupInternal(
        context: Context,
        archiveFile: File,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<Unit> {
        return try {
            if (!archiveFile.isFile || archiveFile.length() == 0L) {
                return Result.failure(IllegalArgumentException("Archive file is missing or empty"))
            }
            val totalBytes = archiveFile.length().coerceAtLeast(1L)
            val totalMb = totalBytes / (1024.0 * 1024.0)

            val filesRoot = File(AntigravityEngine.FILES_DIR).apply { mkdirs() }
            val availBytes = runCatching { StatFs(filesRoot.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            val requiredBytes = (totalBytes * 2L).coerceAtLeast(MIN_RESTORE_FREE_BYTES)
            if (availBytes < requiredBytes) {
                val availMb = availBytes / (1024.0 * 1024.0)
                val reqMb = requiredBytes / (1024.0 * 1024.0)
                return Result.failure(
                    IllegalStateException(String.format(Locale.US, "Not enough internal storage (%.0f MB free, need >= %.0f MB)", availMb, reqMb))
                )
            }

            onProgress(4, String.format(Locale.US, "Verifying & inspecting archive (%.1f MB)...", totalMb))
            val checkResult = validateArchive(context, archiveFile)
            if (checkResult.isFailure) {
                return checkResult
            }

            onProgress(8, "Stopping background processes...")
            AntigravityEngine.stopBackgroundProcesses()

            val zstd = resolveZstdBinary(context)
            val tar = resolveTarBinary()
            val patchBin = File(context.applicationInfo.nativeLibraryDir, "libagy_patch.so")
            val env = AntigravityEngine.buildTermuxEnv()

            onProgress(10, "Extracting Linux environment & Antigravity...")

            val zstdProc = ProcessBuilder(zstd.absolutePath, "-dc").apply {
                environment().putAll(env)
            }.start()

            val patchProc = if (patchBin.isFile) {
                ProcessBuilder(patchBin.absolutePath, "--tar").apply {
                    environment().putAll(env)
                }.start()
            } else {
                null
            }

            val tarProc = ProcessBuilder(
                tar.absolutePath,
                "-xf", "-",
                "-C", AntigravityEngine.FILES_DIR,
                "--recursive-unlink",
                "--preserve-permissions"
            ).apply {
                environment().putAll(env)
                redirectErrorStream(true)
            }.start()

            val pipe1Thread = if (patchProc != null) {
                Thread {
                    try {
                        zstdProc.inputStream.use { zOut ->
                            patchProc.outputStream.use { pIn ->
                                val buf = ByteArray(128 * 1024)
                                while (true) {
                                    val read = zOut.read(buf)
                                    if (read <= 0) break
                                    pIn.write(buf, 0, read)
                                }
                                pIn.flush()
                            }
                        }
                    } catch (_: Exception) {
                    }
                }.apply { start() }
            } else {
                null
            }

            val pipeThread = Thread {
                try {
                    val srcStream = patchProc?.inputStream ?: zstdProc.inputStream
                    srcStream.use { sOut ->
                        tarProc.outputStream.use { tIn ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val read = sOut.read(buf)
                                if (read <= 0) break
                                tIn.write(buf, 0, read)
                            }
                            tIn.flush()
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { start() }

            val tarLog = StringBuilder()
            val tarLogThread = Thread {
                runCatching {
                    tarProc.inputStream.bufferedReader().forEachLine {
                        if (tarLog.length < 4000) tarLog.append(it).append('\n')
                    }
                }
            }.apply { start() }

            val zstdErr = StringBuilder()
            val zstdErrThread = Thread {
                runCatching {
                    zstdProc.errorStream.bufferedReader().forEachLine {
                        if (zstdErr.length < 2000) zstdErr.append(it).append('\n')
                    }
                }
            }.apply { start() }

            var bytesRead = 0L
            var lastUpdate = 0L
            FileInputStream(archiveFile).use { fis ->
                zstdProc.outputStream.use { zIn ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = fis.read(buf)
                        if (n <= 0) break
                        zIn.write(buf, 0, n)
                        bytesRead += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 150L) {
                            lastUpdate = now
                            val pct = 10 + ((bytesRead * 82) / totalBytes).toInt().coerceIn(0, 82)
                            val readMb = bytesRead / (1024.0 * 1024.0)
                            onProgress(
                                pct,
                                String.format(Locale.US, "Restoring %.1f / %.1f MB (%d%%)", readMb, totalMb, pct)
                            )
                        }
                    }
                    zIn.flush()
                }
            }

            pipe1Thread?.join()
            pipeThread.join()
            tarLogThread.join()
            zstdErrThread.join()

            val zstdExit = zstdProc.waitFor()
            val patchExit = patchProc?.waitFor() ?: 0
            val tarExit = tarProc.waitFor()

            if (zstdExit != 0 || patchExit != 0 || tarExit != 0) {
                return Result.failure(
                    IllegalStateException("Restore failed (zstd=$zstdExit, patch=$patchExit, tar=$tarExit): $zstdErr $tarLog".trim())
                )
            }

            onProgress(95, "Fixing permissions & provisioning bridge...")
            fixPostRestorePermissions(context)
            onProgress(100, "Restore complete")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreFromInputStream(
        context: Context,
        inputStreamProvider: () -> InputStream?,
        displayName: String,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!operationInProgress.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Another backup or restore is already running"))
        }
        val tempFile = File(context.cacheDir, "import_backup.tar.zst")
        try {
            val cacheAvail = runCatching { StatFs(context.cacheDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (cacheAvail < MIN_RESTORE_FREE_BYTES) {
                return@withContext Result.failure(
                    IllegalStateException("Not enough free internal storage in cache to stage backup")
                )
            }
            onProgress(2, "Copying $displayName...")
            val input = inputStreamProvider() ?: return@withContext Result.failure(
                IllegalStateException("Unable to open selected file")
            )
            var copied = 0L
            input.use { src ->
                tempFile.outputStream().use { dst ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        dst.write(buf, 0, n)
                        copied += n
                        val mb = copied / (1024.0 * 1024.0)
                        onProgress(5, String.format(Locale.US, "Staging backup (%.1f MB)...", mb))
                    }
                }
            }
            restoreBackupInternal(context, tempFile, onProgress)
        } finally {
            runCatching { tempFile.delete() }
            operationInProgress.set(false)
        }
    }

    private fun fixPostRestorePermissions(context: Context) {
        val agyBin = File(AntigravityEngine.AGY_BIN)
        if (agyBin.isFile) {
            agyBin.setExecutable(true, false)
            runCatching { Os.chmod(agyBin.absolutePath, 0b111101101) }
        }
        val usrBin = File("${AntigravityEngine.PREFIX_DIR}/bin")
        if (usrBin.isDirectory) {
            usrBin.listFiles()?.forEach { f ->
                if (f.isFile) f.setExecutable(true, false)
            }
        }
        AntigravityEngine.provisionBridgeAssets(context)
    }

    suspend fun createBackup(
        context: Context,
        mode: BackupMode,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!operationInProgress.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Another backup or restore is already running"))
        }
        try {
            if (!AntigravityFileHelper.hasPublicDownloadsWriteAccess(context)) {
                return@withContext Result.failure(
                    IllegalStateException("Grant All Files Access in Android Settings to save backups to Downloads")
                )
            }
            val zstd = resolveZstdBinary(context)
            val tar = resolveTarBinary()
            val env = AntigravityEngine.buildTermuxEnv()
            val filesRoot = File(AntigravityEngine.FILES_DIR)

            val candidates = if (mode == BackupMode.FULL) {
                listOf("./usr", "./home")
            } else {
                listOf("./usr", "./home/.local", "./home/.agents")
            }

            val targets = candidates.filter { rel ->
                File(filesRoot, rel.removePrefix("./")).exists()
            }
            if (targets.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No environment directories found to back up"))
            }

            val downloads = getDownloadsDir()
            if (!downloads.exists()) downloads.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val outFile = AntigravityFileHelper.uniqueFile(downloads, "antigravity-${mode.slug}-$stamp.tar.zst")

            onProgress(5, "Archiving & compressing (${mode.label})...")

            val tarCmd = mutableListOf(
                tar.absolutePath,
                "-cf", "-",
                "-C", AntigravityEngine.FILES_DIR,
                "--exclude=./usr/var/cache/*",
                "--exclude=./usr/tmp/*",
                "--exclude=./home/.cargo",
                "--exclude=./home/.cache",
                "--exclude=./home/.npm",
                "--exclude=./home/.agy_bridge/agy.log*",
                "--exclude=./home/storage"
            )
            tarCmd.addAll(targets)

            val tarProc = ProcessBuilder(tarCmd).apply {
                environment().putAll(env)
            }.start()

            val zstdProc = ProcessBuilder(
                zstd.absolutePath,
                "-6", "-T2", "-f",
                "-o", outFile.absolutePath
            ).apply {
                environment().putAll(env)
                redirectErrorStream(true)
            }.start()

            val tarErr = StringBuilder()
            val tarErrThread = Thread {
                runCatching {
                    tarProc.errorStream.bufferedReader().forEachLine {
                        if (tarErr.length < 2000) tarErr.append(it).append('\n')
                    }
                }
            }.apply { start() }

            val zstdOut = StringBuilder()
            val zstdOutThread = Thread {
                runCatching {
                    zstdProc.inputStream.bufferedReader().forEachLine {
                        if (zstdOut.length < 2000) zstdOut.append(it).append('\n')
                    }
                }
            }.apply { start() }

            var streamedBytes = 0L
            var lastUpdate = 0L
            tarProc.inputStream.use { tOut ->
                zstdProc.outputStream.use { zIn ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = tOut.read(buf)
                        if (n <= 0) break
                        zIn.write(buf, 0, n)
                        streamedBytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 200L) {
                            lastUpdate = now
                            val rawMb = streamedBytes / (1024.0 * 1024.0)
                            val outMb = outFile.length() / (1024.0 * 1024.0)
                            val fakePct = (10 + (rawMb / 10.0).toInt()).coerceIn(10, 88)
                            onProgress(
                                fakePct,
                                String.format(Locale.US, "Packed %.1f MB -> %.1f MB (.zst)", rawMb, outMb)
                            )
                        }
                    }
                    zIn.flush()
                }
            }

            tarErrThread.join()
            zstdOutThread.join()

            val tarExit = tarProc.waitFor()
            val zstdExit = zstdProc.waitFor()
            if (zstdExit != 0 || (tarExit != 0 && tarExit != 1)) {
                runCatching { outFile.delete() }
                return@withContext Result.failure(
                    IllegalStateException("Backup failed (tar=$tarExit, zstd=$zstdExit): $tarErr $zstdOut".trim())
                )
            }

            onProgress(92, "Verifying archive with zstd -t...")
            val verify = verifyArchive(context, outFile)
            if (verify.isFailure) {
                runCatching { outFile.delete() }
                return@withContext Result.failure(verify.exceptionOrNull() ?: IllegalStateException("Verification failed"))
            }

            val finalMb = outFile.length() / (1024.0 * 1024.0)
            onProgress(100, String.format(Locale.US, "Verified: %s (%.1f MB)", outFile.name, finalMb))
            Result.success(outFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            operationInProgress.set(false)
        }
    }
}
