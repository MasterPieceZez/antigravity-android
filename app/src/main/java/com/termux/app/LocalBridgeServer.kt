package com.termux.app

import android.content.Context
import com.termux.shared.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest

/**
 * Lightweight pure-Kotlin HTTP bridge on 127.0.0.1:4401.
 * Eliminates the ~30 MB idle Python bridge daemon while preserving
 * X-Bridge-Token auth, Origin checks, /health, /restart-agy, and /notify.
 */
class LocalBridgeServer(
    private val context: Context,
    private val onNotify: (title: String, body: String) -> Unit
) {
    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val clientSemaphore = Semaphore(16)

    fun start() {
        if (acceptJob?.isActive == true) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        acceptJob = s.launch {
            try {
                val ss = ServerSocket(
                    AntigravityEngine.BRIDGE_PORT,
                    16,
                    InetAddress.getByName("127.0.0.1")
                ).apply { reuseAddress = true }
                serverSocket = ss
                while (isActive && !ss.isClosed) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    launch {
                        clientSemaphore.withPermit {
                            runCatching { handleClient(client) }
                                .onFailure { Logger.logDebug("LocalBridgeServer", "Client error: ${it.message}") }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Logger.logError("LocalBridgeServer", "Bridge failed on port ${AntigravityEngine.BRIDGE_PORT}: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        scope?.cancel()
        scope = null
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 5000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()

            val headerBytes = readUntilHeadersEnd(input) ?: return
            val headerText = headerBytes.toString(Charsets.UTF_8)
            val lines = headerText.split(Regex("\r?\n"))
            if (lines.isEmpty()) return

            val reqParts = lines[0].trim().split(Regex("\\s+"))
            if (reqParts.size < 2) {
                writeJson(out, 400, JSONObject().put("ok", false).put("error", "bad_request"))
                return
            }
            val method = reqParts[0].uppercase()
            val route = reqParts[1].substringBefore("?")

            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            // Origin validation
            if (!isAllowedOrigin(headers["origin"])) {
                writeJson(out, 403, JSONObject().put("ok", false).put("error", "forbidden_origin"))
                return
            }

            // Constant-time X-Bridge-Token check
            val expectedToken = AntigravityEngine.getOrCreateBridgeToken()
            if (!isValidToken(headers["x-bridge-token"], expectedToken)) {
                writeJson(out, 403, JSONObject().put("ok", false).put("error", "unauthorized"))
                return
            }

            val transferEncoding = headers["transfer-encoding"]?.lowercase()
            if (transferEncoding != null && transferEncoding.contains("chunked")) {
                writeJson(out, 501, JSONObject().put("ok", false).put("error", "chunked_unsupported"))
                return
            }

            val rawLength = headers["content-length"]
            val contentLength = if (rawLength != null) {
                val parsed = rawLength.toIntOrNull()
                if (parsed == null || parsed < 0) {
                    writeJson(out, 400, JSONObject().put("ok", false).put("error", "invalid_content_length"))
                    return
                }
                if (parsed > MAX_BODY_BYTES) {
                    writeJson(out, 413, JSONObject().put("ok", false).put("error", "payload_too_large"))
                    return
                }
                parsed
            } else {
                0
            }
            val bodyBytes = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)

            when {
                method == "GET" && route == "/health" -> {
                    val hubUrl = AntigravityEngine.parseHubUrlFromLog()
                    val hubReady = hubUrl != null && AntigravityEngine.isPortOpen(AntigravityEngine.HUB_PORT)
                    val payload = JSONObject()
                        .put("bridge", true)
                        .put("agy_installed", AntigravityEngine.isAgyInstalled())
                        .put("hub_ready", hubReady)
                        .put("hub_url", hubUrl ?: JSONObject.NULL)
                        .put("auto_update_disabled", true)
                    writeJson(out, 200, payload)
                }
                method == "POST" && route == "/restart-agy" -> {
                    val newUrl = AntigravityEngine.restartHub(context)
                    val payload = JSONObject()
                        .put("ok", newUrl != null)
                        .put("hub_url", newUrl ?: JSONObject.NULL)
                    writeJson(out, if (newUrl != null) 200 else 503, payload)
                }
                method == "POST" && route == "/notify" -> {
                    val json = runCatching { JSONObject(bodyBytes.toString(Charsets.UTF_8)) }
                        .getOrDefault(JSONObject())
                    val title = json.optString("title", "Antigravity").take(120).ifBlank { "Antigravity" }
                    val body = json.optString("body", "Agent task finished").take(500).ifBlank { "Agent task finished" }
                    onNotify(title, body)
                    writeJson(out, 200, JSONObject().put("ok", true))
                }
                else -> {
                    writeJson(out, 404, JSONObject().put("ok", false).put("error", "not_found"))
                }
            }
        }
    }

    private fun readUntilHeadersEnd(input: BufferedInputStream): ByteArray? {
        val baos = ByteArrayOutputStream()
        var matched = 0
        while (baos.size() < 16384) {
            val b = input.read()
            if (b == -1) return null
            baos.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return baos.toByteArray()
        }
        return null
    }

    private fun readExact(input: BufferedInputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val read = input.read(buf, offset, len - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == len) buf else buf.copyOf(offset)
    }

    private fun writeJson(out: java.io.OutputStream, status: Int, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            501 -> "Not Implemented"
            else -> "Service Unavailable"
        }
        val resp = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(resp.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    companion object {
        private const val MAX_BODY_BYTES = 32768

        fun isAllowedOrigin(origin: String?): Boolean {
            if (origin.isNullOrBlank()) return true
            val uri = runCatching { URI(origin) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            return host == "localhost" || host == "127.0.0.1"
        }

        fun isValidToken(supplied: String?, expected: String): Boolean {
            if (supplied.isNullOrEmpty() || expected.isEmpty()) return false
            return MessageDigest.isEqual(
                supplied.toByteArray(Charsets.UTF_8),
                expected.toByteArray(Charsets.UTF_8)
            )
        }
    }
}
