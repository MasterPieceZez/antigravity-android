package com.termux.app

import android.content.Context
import android.os.StatFs
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class BackupResult(val displayName: String, val sizeBytes: Long)

enum class BackupMode(val slug: String, val label: String) {
    FULL("full", "Full Backup (Personal)"),
    PACKAGES_ONLY("packages", "Environment & Agents (No Home Files)")
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
            val normalizedAbs = trimmed.replace('\\', '/')
            if (normalizedAbs.split('/').any { it == ".." }) return false
            return normalizedAbs.startsWith("/data/data/com.termux/files/") ||
                normalizedAbs.startsWith("/data/data/com.agy.os/files/") ||
                normalizedAbs.startsWith("/storage/") ||
                normalizedAbs.startsWith("/sdcard/")
        }

        val rawEntry = entryPath.trim().replace('\\', '/')
        if (rawEntry.endsWith("/") || rawEntry.endsWith("/.") || rawEntry == ".") return false

        val entryParts = ArrayDeque<String>()
        val normalizedEntry = rawEntry.trimStart('/').removePrefix("./")
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

    private fun validateArchiveAndHash(context: Context, archiveFile: File): Result<String> {
        var zstdProc: Process? = null
        var checkProc: Process? = null
        return try {
            val zstd = resolveZstdBinary(context)
            val patchBin = File(context.applicationInfo.nativeLibraryDir, "libagy_patch.so")
            if (!patchBin.isFile) {
                return Result.failure(IllegalStateException("Required libagy_patch.so binary not found"))
            }
            val env = AntigravityEngine.buildTermuxEnv()
            val zp = ProcessBuilder(zstd.absolutePath, "-dc").apply {
                environment().putAll(env)
            }.start()
            zstdProc = zp

            val cp = ProcessBuilder(patchBin.absolutePath, "--check-tar").apply {
                environment().putAll(env)
                redirectErrorStream(true)
            }.start()
            checkProc = cp

            val zstdErr = StringBuilder()
            val zstdErrThread = Thread {
                runCatching {
                    zp.errorStream.bufferedReader().forEachLine {
                        if (zstdErr.length < 1000) zstdErr.append(it).append('\n')
                    }
                }
            }.apply { start() }
            val checkErr = StringBuilder()
            val checkErrThread = Thread {
                runCatching {
                    cp.inputStream.bufferedReader().forEachLine {
                        if (checkErr.length < 1000) checkErr.append(it).append('\n')
                    }
                }
            }.apply { start() }
            val pipe = Thread {
                runCatching {
                    zp.inputStream.use { zOut ->
                        cp.outputStream.use { cIn -> zOut.copyTo(cIn) }
                    }
                }
            }.apply { start() }

            val digest = java.security.MessageDigest.getInstance("SHA-256")
            runCatching {
                FileInputStream(archiveFile).use { fis ->
                    zp.outputStream.use { zIn ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = fis.read(buf)
                            if (n <= 0) break
                            digest.update(buf, 0, n)
                            zIn.write(buf, 0, n)
                        }
                        zIn.flush()
                    }
                }
            }

            pipe.join()
            zstdErrThread.join()
            checkErrThread.join()
            val zExit = zp.waitFor()
            val cExit = cp.waitFor()
            val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
            when {
                cExit != 0 -> Result.failure(
                    SecurityException(checkErr.toString().trim().ifBlank { "Archive safety check failed (exit $cExit)" })
                )
                zExit != 0 -> Result.failure(
                    IllegalStateException("zstd integrity check failed (exit $zExit): ${zstdErr.toString().trim()}")
                )
                else -> Result.success(shaHex)
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { zstdProc?.destroyForcibly() }
            runCatching { checkProc?.destroyForcibly() }
        }
    }

    private val operationInProgress = AtomicBoolean(false)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progressFlow = MutableStateFlow<Pair<Int, String>?>(null)
    val progressFlow: StateFlow<Pair<Int, String>?> = _progressFlow.asStateFlow()

    @JvmStatic
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
        throw IllegalStateException("GNU tar binary not found in embedded Linux environment")
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
    ): Result<Unit> {
        val appCtx = context.applicationContext
        return appScope.async {
            if (!operationInProgress.compareAndSet(false, true)) {
                return@async Result.failure(IllegalStateException("Another backup or restore is already running"))
            }
            val report: (Int, String) -> Unit = { pct, msg ->
                _progressFlow.value = pct to msg
                onProgress(pct, msg)
            }
            try {
                restoreBackupInternal(appCtx, archiveFile, report)
            } finally {
                _progressFlow.value = null
                operationInProgress.set(false)
            }
        }.await()
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
            val requiredBytes = (totalBytes * 4L).coerceAtLeast(MIN_RESTORE_FREE_BYTES)
            if (availBytes < requiredBytes) {
                val availMb = availBytes / (1024.0 * 1024.0)
                val reqMb = requiredBytes / (1024.0 * 1024.0)
                return Result.failure(
                    IllegalStateException(String.format(Locale.US, "Not enough internal storage (%.0f MB free, need >= %.0f MB)", availMb, reqMb))
                )
            }

            onProgress(4, String.format(Locale.US, "Verifying & inspecting archive (%.1f MB)...", totalMb))
            val checkResult = validateArchiveAndHash(context, archiveFile)
            val expectedSha = checkResult.getOrElse { return Result.failure(it) }

            onProgress(8, "Stopping background processes...")
            AntigravityEngine.stopBackgroundProcessesSuspend()

            val zstd = resolveZstdBinary(context)
            val tar = resolveTarBinary()
            val patchBin = File(context.applicationInfo.nativeLibraryDir, "libagy_patch.so")
            if (!patchBin.isFile) {
                return Result.failure(IllegalStateException("Required libagy_patch.so binary not found"))
            }
            val env = AntigravityEngine.buildTermuxEnv()

            val stagingDir = File(filesRoot, "restore-staging")
            if (stagingDir.exists()) deleteRecursivelySafe(stagingDir)
            stagingDir.mkdirs()

            onProgress(10, "Extracting Linux environment & Antigravity...")

            var zstdProc: Process? = null
            var patchProc: Process? = null
            var tarProc: Process? = null
            try {
                val zp = ProcessBuilder(zstd.absolutePath, "-dc").apply {
                    environment().putAll(env)
                }.start()
                zstdProc = zp

                val pp = ProcessBuilder(patchBin.absolutePath, "--tar").apply {
                    environment().putAll(env)
                }.start()
                patchProc = pp

                val tp = ProcessBuilder(
                    tar.absolutePath,
                    "-xf", "-",
                    "-C", stagingDir.absolutePath,
                    "--preserve-permissions"
                ).apply {
                    environment().putAll(env)
                    redirectErrorStream(true)
                }.start()
                tarProc = tp

                val pipe1Thread = Thread {
                    try {
                        zp.inputStream.use { zOut ->
                            pp.outputStream.use { pIn ->
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

                val pipeThread = Thread {
                    try {
                        pp.inputStream.use { sOut ->
                            tp.outputStream.use { tIn ->
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
                        tp.inputStream.bufferedReader().forEachLine {
                            if (tarLog.length < 4000) tarLog.append(it).append('\n')
                        }
                    }
                }.apply { start() }

                val zstdErr = StringBuilder()
                val zstdErrThread = Thread {
                    runCatching {
                        zp.errorStream.bufferedReader().forEachLine {
                            if (zstdErr.length < 2000) zstdErr.append(it).append('\n')
                        }
                    }
                }.apply { start() }

                val patchErr = StringBuilder()
                val patchErrThread = Thread {
                    runCatching {
                        pp.errorStream.bufferedReader().forEachLine {
                            if (patchErr.length < 2000) patchErr.append(it).append('\n')
                        }
                    }
                }.apply { start() }

                var bytesRead = 0L
                var lastUpdate = 0L
                val streamDigest = java.security.MessageDigest.getInstance("SHA-256")
                var readFailure: Throwable? = null
                try {
                    FileInputStream(archiveFile).use { fis ->
                        zp.outputStream.use { zIn ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = fis.read(buf)
                                if (n <= 0) break
                                streamDigest.update(buf, 0, n)
                                zIn.write(buf, 0, n)
                                bytesRead += n
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 150L) {
                                    lastUpdate = now
                                    val pct = 10 + ((bytesRead * 80) / totalBytes).toInt().coerceIn(0, 80)
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    readFailure = e
                }

                pipe1Thread.join()
                pipeThread.join()
                tarLogThread.join()
                zstdErrThread.join()
                patchErrThread.join()

                val zstdExit = zp.waitFor()
                val patchExit = pp.waitFor()
                val tarExit = tp.waitFor()
                val actualSha = streamDigest.digest().joinToString("") { "%02x".format(it) }

                if (readFailure != null && zstdExit == 0 && patchExit == 0 && tarExit == 0) {
                    return Result.failure(readFailure)
                }
                if (readFailure != null && (zstdExit != 0 || patchExit != 0 || tarExit != 0)) {
                    return Result.failure(
                        IllegalStateException("Restore failed (zstd=$zstdExit, patch=$patchExit, tar=$tarExit): ${readFailure.message} $zstdErr $patchErr $tarLog".trim())
                    )
                }

                if (actualSha != expectedSha) {
                    return Result.failure(SecurityException("Archive changed during restore (TOCTOU detected)"))
                }

                if (zstdExit != 0 || patchExit != 0 || tarExit != 0) {
                    return Result.failure(
                        IllegalStateException("Restore failed (zstd=$zstdExit, patch=$patchExit, tar=$tarExit): $zstdErr $patchErr $tarLog".trim())
                    )
                }

                onProgress(92, "Swapping staged environment...")
                val swapRes = swapStagedDirs(filesRoot, stagingDir)
                if (swapRes.isFailure) {
                    return swapRes
                }
            } finally {
                runCatching { zstdProc?.destroyForcibly() }
                runCatching { patchProc?.destroyForcibly() }
                runCatching { tarProc?.destroyForcibly() }
                runCatching { deleteRecursivelySafe(stagingDir) }
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
        expectedBytes: Long = -1L,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<Unit> {
        val appCtx = context.applicationContext
        return appScope.async {
            if (!operationInProgress.compareAndSet(false, true)) {
                return@async Result.failure(IllegalStateException("Another backup or restore is already running"))
            }
            val report: (Int, String) -> Unit = { pct, msg ->
                _progressFlow.value = pct to msg
                onProgress(pct, msg)
            }
            val tempFile = File(appCtx.cacheDir, "import_backup.tar.zst")
            try {
                val neededCache = if (expectedBytes > 0L) expectedBytes + MIN_RESTORE_FREE_BYTES else MIN_RESTORE_FREE_BYTES
                val cacheAvail = runCatching { StatFs(appCtx.cacheDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
                if (cacheAvail < neededCache) {
                    return@async Result.failure(
                        IllegalStateException("Not enough free internal storage in cache to stage backup")
                    )
                }
                report(2, "Copying $displayName...")
                val input = inputStreamProvider() ?: return@async Result.failure(
                    IllegalStateException("Unable to open selected file")
                )
                var copied = 0L
                input.use { src ->
                    tempFile.outputStream().use { dst ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = src.read(buf)
                            if (n <= 0) break
                            dst.write(buf, 0, n)
                            copied += n
                            val mb = copied / (1024.0 * 1024.0)
                            report(5, String.format(Locale.US, "Staging backup (%.1f MB)...", mb))
                        }
                    }
                }
                restoreBackupInternal(appCtx, tempFile, report)
            } finally {
                runCatching { tempFile.delete() }
                _progressFlow.value = null
                operationInProgress.set(false)
            }
        }.await()
    }

    private val PACKAGES_ONLY_HOME_SUBDIRS = setOf(".local", ".agents")

    private fun deleteRecursivelySafe(target: File): Boolean {
        if (!target.exists()) return true
        runCatching {
            target.walkBottomUp().forEach { f ->
                val isLnk = runCatching {
                    val st = Os.lstat(f.absolutePath)
                    (st.st_mode and 0b1111000000000000) == 0b1010000000000000 // S_IFLNK
                }.getOrDefault(false)
                if (!isLnk) {
                    if (f.isDirectory) {
                        f.setReadable(true, true)
                        f.setWritable(true, true)
                        f.setExecutable(true, true)
                    } else {
                        f.setWritable(true, true)
                    }
                }
            }
        }
        return target.deleteRecursively()
    }

    internal fun swapStagedDirs(filesRoot: File, stagingDir: File): Result<Unit> {
        val swapped = mutableListOf<Pair<File, File>>()
        val created = mutableListOf<File>()
        try {
            val children = stagingDir.listFiles() ?: emptyArray()
            for (stagedChild in children) {
                val target = File(filesRoot, stagedChild.name)
                if (stagedChild.name == "home" && stagedChild.isDirectory && target.isDirectory) {
                    val homeEntries = stagedChild.listFiles() ?: emptyArray()
                    val isPackagesOnlyHome = homeEntries.isNotEmpty() &&
                        homeEntries.all { it.name in PACKAGES_ONLY_HOME_SUBDIRS }
                    if (isPackagesOnlyHome) {
                        for (sub in homeEntries) {
                            val subTarget = File(target, sub.name)
                            val subBackup = File(target, "${sub.name}.restore-bak")
                            if (subBackup.exists()) deleteRecursivelySafe(subBackup)
                            if (subTarget.exists()) {
                                if (!subTarget.renameTo(subBackup)) {
                                    throw IllegalStateException("Failed to stage backup of home/${sub.name}")
                                }
                                swapped.add(subTarget to subBackup)
                            } else {
                                created.add(subTarget)
                            }
                            if (!sub.renameTo(subTarget)) {
                                throw IllegalStateException("Failed to move restored home/${sub.name} into place")
                            }
                        }
                        continue
                    }
                }
                val backup = File(filesRoot, "${stagedChild.name}.restore-bak")
                if (backup.exists()) deleteRecursivelySafe(backup)
                if (target.exists()) {
                    if (!target.renameTo(backup)) {
                        throw IllegalStateException("Failed to stage backup of ${target.name}")
                    }
                    swapped.add(target to backup)
                } else {
                    created.add(target)
                }
                if (!stagedChild.renameTo(target)) {
                    throw IllegalStateException("Failed to move restored ${stagedChild.name} into place")
                }
            }
            for ((_, backup) in swapped) {
                runCatching { deleteRecursivelySafe(backup) }
            }
            return Result.success(Unit)
        } catch (e: Exception) {
            for (c in created.asReversed()) {
                runCatching { if (c.exists()) deleteRecursivelySafe(c) }
            }
            for ((target, backup) in swapped.asReversed()) {
                runCatching {
                    if (target.exists()) deleteRecursivelySafe(target)
                    backup.renameTo(target)
                }
            }
            return Result.failure(e)
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
        AntigravityBackgroundController.restartFileObserver(context)
    }

    suspend fun createBackup(
        context: Context,
        mode: BackupMode,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<BackupResult> {
        val appCtx = context.applicationContext
        return appScope.async {
            createBackupInternal(appCtx, mode) { pct, msg ->
                _progressFlow.value = pct to msg
                onProgress(pct, msg)
            }.also {
                _progressFlow.value = null
            }
        }.await()
    }

    private suspend fun createBackupInternal(
        context: Context,
        mode: BackupMode,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<BackupResult> = withContext(Dispatchers.IO) {
        if (!operationInProgress.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("Another backup or restore is already running"))
        }
        val canDirectDownloads = AntigravityFileHelper.hasPublicDownloadsWriteAccess(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val baseFileName = "antigravity-${mode.slug}-$stamp.tar.zst"
        val outFile = if (canDirectDownloads) {
            val downloads = getDownloadsDir()
            if (!downloads.exists()) downloads.mkdirs()
            val dlAvail = runCatching { StatFs(downloads.absolutePath).availableBytes }
                .getOrDefault(Long.MAX_VALUE)
            if (dlAvail < MIN_RESTORE_FREE_BYTES) {
                operationInProgress.set(false)
                return@withContext Result.failure(
                    IllegalStateException("Not enough free storage in Downloads to create backup")
                )
            }
            AntigravityFileHelper.uniqueFile(downloads, baseFileName)
        } else {
            val cacheAvail = runCatching { StatFs(context.cacheDir.absolutePath).availableBytes }
                .getOrDefault(Long.MAX_VALUE)
            if (cacheAvail < MIN_RESTORE_FREE_BYTES) {
                operationInProgress.set(false)
                return@withContext Result.failure(
                    IllegalStateException("Not enough free internal storage in cache to stage backup")
                )
            }
            File(context.cacheDir, baseFileName)
        }
        var tarProc: Process? = null
        var zstdProc: Process? = null
        try {
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
                "--exclude=./home/.agy_bridge/bridge.token",
                "--exclude=./home/.agy_bridge/hub.url",
                "--exclude=./home/.agy_bridge/hub.pid",
                "--exclude=./home/.agy_bridge/accounts",
                "--exclude=./home/.gemini/antigravity-cli/antigravity-oauth-token",
                "--exclude=./home/.gemini/jetski-standalone-oauth-token",
                "--exclude=./home/.config/antigravity/credential-user-scope",
                "--exclude=./home/storage"
            )
            tarCmd.addAll(targets)

            val tp = ProcessBuilder(tarCmd).apply {
                environment().putAll(env)
            }.start()
            tarProc = tp

            val zp = ProcessBuilder(
                zstd.absolutePath,
                "-6", "-T2", "-f",
                "-o", outFile.absolutePath
            ).apply {
                environment().putAll(env)
                redirectErrorStream(true)
            }.start()
            zstdProc = zp

            val tarErr = StringBuilder()
            val tarErrThread = Thread {
                runCatching {
                    tp.errorStream.bufferedReader().forEachLine {
                        if (tarErr.length < 2000) tarErr.append(it).append('\n')
                    }
                }
            }.apply { start() }

            val zstdOut = StringBuilder()
            val zstdOutThread = Thread {
                runCatching {
                    zp.inputStream.bufferedReader().forEachLine {
                        if (zstdOut.length < 2000) zstdOut.append(it).append('\n')
                    }
                }
            }.apply { start() }

            var streamedBytes = 0L
            var lastUpdate = 0L
            tp.inputStream.use { tOut ->
                zp.outputStream.use { zIn ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = tOut.read(buf)
                        if (n <= 0) break
                        zIn.write(buf, 0, n)
                        streamedBytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 200L) {
                            lastUpdate = now
                            val rawMb = streamedBytes / (1024.0 * 1024.0)
                            val outMb = outFile.length() / (1024.0 * 1024.0)
                            val fakePct = (10 + ((78.0 * rawMb) / (rawMb + 250.0)).toInt()).coerceIn(10, 88)
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

            val tarExit = tp.waitFor()
            val zstdExit = zp.waitFor()
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

            val finalBytes = outFile.length()
            val finalMb = finalBytes / (1024.0 * 1024.0)
            val displayLoc = if (canDirectDownloads) {
                outFile.name
            } else {
                val saved = AntigravityFileHelper.saveToDownloadsWithFallback(
                    context,
                    outFile.name,
                    "application/zstd"
                ) { dst ->
                    FileInputStream(outFile).use { src -> src.copyTo(dst) }
                }
                runCatching { outFile.delete() }
                saved.getOrElse { return@withContext Result.failure(it) }
            }
            onProgress(100, String.format(Locale.US, "Verified: %s (%.1f MB)", displayLoc, finalMb))
            Result.success(BackupResult(displayLoc, finalBytes))
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { outFile.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { outFile.delete() }
            Result.failure(e)
        } finally {
            runCatching { tarProc?.destroyForcibly() }
            runCatching { zstdProc?.destroyForcibly() }
            operationInProgress.set(false)
        }
    }
}
