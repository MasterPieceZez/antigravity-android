package com.termux.app.acp

import android.os.Build
import com.termux.app.AntigravityEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class LocalAcpTransport {

    private val _stdoutLines = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val stdoutLines: SharedFlow<String> = _stdoutLines.asSharedFlow()

    private val _stderrLines = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val stderrLines: SharedFlow<String> = _stderrLines.asSharedFlow()

    private val writeMutex = Mutex()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    private var ioScope: CoroutineScope? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null

    val isRunning: Boolean
        get() {
            val proc = process ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                proc.isAlive
            } else {
                try {
                    proc.exitValue()
                    false
                } catch (_: IllegalThreadStateException) {
                    true
                }
            }
        }

    suspend fun start(
        workingDir: String = AntigravityEngine.HOME_DIR,
        onExit: ((Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        stop()

        val agyFile = File(AntigravityEngine.AGY_BIN)
        if (!agyFile.exists()) {
            throw IllegalStateException("Antigravity binary not found at ${AntigravityEngine.AGY_BIN}")
        }

        val dir = File(workingDir).let { candidate ->
            if (candidate.isDirectory) candidate else File(AntigravityEngine.HOME_DIR)
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val pb = ProcessBuilder(AntigravityEngine.AGY_BIN, "acp")
        pb.directory(dir)
        val env = pb.environment()
        env.putAll(AntigravityEngine.buildTermuxEnv())
        env["NO_COLOR"] = "1"
        pb.redirectErrorStream(false)

        val proc = pb.start()
        process = proc
        stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ioScope = scope

        stdoutJob = scope.launch {
            try {
                BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        _stdoutLines.emit(line)
                    }
                }
            } catch (_: Exception) {
                // Stream closed on process exit or stop()
            }
        }

        stderrJob = scope.launch {
            try {
                BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        _stderrLines.emit(line)
                    }
                }
            } catch (_: Exception) {
                // Stream closed on process exit or stop()
            }
        }

        waitJob = scope.launch {
            val exitCode = try {
                proc.waitFor()
            } catch (_: InterruptedException) {
                -1
            } catch (_: Exception) {
                -1
            }
            if (process === proc) {
                process = null
                stdinWriter = null
                onExit?.invoke(exitCode)
            }
        }
    }

    suspend fun sendLine(jsonLine: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val writer = stdinWriter
                ?: throw IllegalStateException("ACP transport is not connected")
            writer.write(jsonLine)
            writer.newLine()
            writer.flush()
        }
    }

    fun stop() {
        val proc = process
        process = null

        val writer = stdinWriter
        stdinWriter = null
        runCatching { writer?.close() }

        stdoutJob?.cancel()
        stderrJob?.cancel()
        waitJob?.cancel()
        stdoutJob = null
        stderrJob = null
        waitJob = null

        ioScope?.cancel()
        ioScope = null

        if (proc != null) {
            runCatching { proc.destroy() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    if (proc.isAlive) {
                        proc.destroyForcibly()
                    }
                }
            }
        }
    }
}
