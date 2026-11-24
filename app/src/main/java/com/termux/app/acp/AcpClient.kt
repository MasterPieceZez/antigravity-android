package com.termux.app.acp

import com.termux.app.AntigravityEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AcpRpcException(
    val code: Int,
    override val message: String,
    val data: Any? = null
) : Exception("ACP RPC error ($code): $message")

class AcpClient(
    private val transport: LocalAcpTransport = LocalAcpTransport()
) {
    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val PROMPT_TIMEOUT_MS = 30 * 60 * 1000L
        private const val ERR_METHOD_NOT_FOUND = -32601
    }

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val inboundRequestRawIds = ConcurrentHashMap<String, Any>()

    private var readerScope: CoroutineScope? = null
    private var readerJob: Job? = null

    val stderrLines: SharedFlow<String>
        get() = transport.stderrLines

    val isConnected: Boolean
        get() = transport.isRunning

    var onSessionUpdate: ((JSONObject) -> Unit)? = null
    var onPermissionRequest: ((rpcId: String, params: JSONObject) -> Unit)? = null

    suspend fun connect(
        workingDir: String = AntigravityEngine.HOME_DIR,
        onExit: ((Int) -> Unit)? = null
    ) {
        disconnect()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        readerScope = scope
        readerJob = scope.launch {
            transport.stdoutLines.collect { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.isNotEmpty() && trimmed.startsWith("{")) {
                    handleIncomingLine(trimmed)
                }
            }
        }

        transport.start(workingDir) { exitCode ->
            failAllPending(IllegalStateException("agy acp process exited with code $exitCode"))
            onExit?.invoke(exitCode)
        }
    }

    fun disconnect() {
        failAllPending(IllegalStateException("ACP client disconnected"))
        inboundRequestRawIds.clear()
        readerJob?.cancel()
        readerJob = null
        readerScope?.cancel()
        readerScope = null
        transport.stop()
    }

    suspend fun initialize(): JSONObject {
        val params = JSONObject().apply {
            put("protocolVersion", 1)
            put("clientCapabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", "antigravity-android")
                put("version", "1.0.1")
            })
        }
        return sendRequest("initialize", params, DEFAULT_TIMEOUT_MS)
    }

    suspend fun newSession(cwd: String = AntigravityEngine.HOME_DIR): String {
        val params = JSONObject().apply {
            put("cwd", cwd)
            put("mcpServers", JSONArray())
        }
        val result = sendRequest("session/new", params, DEFAULT_TIMEOUT_MS)
        return result.optString("sessionId", "").ifBlank {
            throw IllegalStateException("session/new did not return a sessionId")
        }
    }

    suspend fun resumeSession(sessionId: String, cwd: String = AntigravityEngine.HOME_DIR): JSONObject {
        val resumeParams = JSONObject().apply {
            put("sessionId", sessionId)
            put("cwd", cwd)
            put("mcpServers", JSONArray())
        }
        return try {
            sendRequest("session/resume", resumeParams, DEFAULT_TIMEOUT_MS)
        } catch (_: Exception) {
            val loadParams = JSONObject().apply {
                put("sessionId", sessionId)
                put("cwd", cwd)
                put("mcpServers", JSONArray())
            }
            sendRequest("session/load", loadParams, DEFAULT_TIMEOUT_MS)
        }
    }

    suspend fun listSessions(cwd: String? = null): List<SessionSummary> {
        val params = JSONObject().apply {
            if (!cwd.isNullOrBlank()) {
                put("cwd", cwd)
            }
        }
        val result = sendRequest("session/list", params, DEFAULT_TIMEOUT_MS)
        val arr = result.optJSONArray("sessions")
            ?: result.optJSONArray("items")
            ?: return emptyList()

        val list = ArrayList<SessionSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("sessionId", obj.optString("id", "")).trim()
            if (id.isEmpty()) continue
            val title = obj.optString("title", "")
                .ifBlank { obj.optString("name", "") }
                .ifBlank { "Session ${id.take(8)}" }
            val sessionCwd = obj.optString("cwd", "")
            val updatedAt = obj.optString("updatedAt", obj.optString("updated_at", ""))
            list.add(
                SessionSummary(
                    sessionId = id,
                    title = title,
                    cwd = sessionCwd,
                    updatedAt = updatedAt
                )
            )
        }
        return list
    }

    suspend fun sendPrompt(sessionId: String, text: String): JSONObject {
        val promptBlocks = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        }
        val params = JSONObject().apply {
            put("sessionId", sessionId)
            put("prompt", promptBlocks)
        }
        return sendRequest("session/prompt", params, PROMPT_TIMEOUT_MS)
    }

    suspend fun cancelTurn(sessionId: String) {
        val params = JSONObject().apply {
            put("sessionId", sessionId)
        }
        sendNotification("session/cancel", params)
    }

    suspend fun replyPermission(rpcId: String, optionId: String?) {
        val rawId = inboundRequestRawIds.remove(rpcId) ?: rpcId.toIntOrNull() ?: rpcId
        val outcome = JSONObject().apply {
            if (!optionId.isNullOrBlank()) {
                put("outcome", "selected")
                put("optionId", optionId)
            } else {
                put("outcome", "cancelled")
            }
        }
        val result = JSONObject().apply {
            put("outcome", outcome)
        }
        val response = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", rawId)
            put("result", result)
        }
        transport.sendLine(response.toString())
    }

    private suspend fun sendRequest(
        method: String,
        params: JSONObject,
        timeoutMs: Long
    ): JSONObject {
        val idInt = nextId.getAndIncrement()
        val idKey = idInt.toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[idKey] = deferred

        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", idInt)
            put("method", method)
            put("params", params)
        }

        try {
            transport.sendLine(payload.toString())
            return withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: Exception) {
            pending.remove(idKey)
            throw e
        }
    }

    private suspend fun sendNotification(method: String, params: JSONObject) {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        transport.sendLine(payload.toString())
    }

    private suspend fun sendErrorReply(rawId: Any, code: Int, message: String) {
        val response = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", rawId)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }
        runCatching { transport.sendLine(response.toString()) }
    }

    private suspend fun handleIncomingLine(line: String) {
        val msg = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        }

        val hasId = msg.has("id") && !msg.isNull("id")
        val method = msg.optString("method", "").takeIf { it.isNotEmpty() }

        // 1. Response to an outbound request
        if (hasId && method == null) {
            val idKey = msg.opt("id")?.toString() ?: return
            val deferred = pending.remove(idKey) ?: return
            if (msg.has("error") && !msg.isNull("error")) {
                val err = msg.optJSONObject("error")
                val code = err?.optInt("code", -32000) ?: -32000
                val errMsg = err?.optString("message", "Unknown JSON-RPC error") ?: "Unknown JSON-RPC error"
                deferred.completeExceptionally(AcpRpcException(code, errMsg, err?.opt("data")))
            } else {
                val result = msg.optJSONObject("result") ?: JSONObject()
                deferred.complete(result)
            }
            return
        }

        // 2. Server-initiated bidirectional request (has both id and method)
        if (hasId && method != null) {
            val rawId = msg.opt("id") ?: return
            val rpcIdKey = rawId.toString()
            val params = msg.optJSONObject("params") ?: JSONObject()
            when (method) {
                "session/request_permission" -> {
                    inboundRequestRawIds[rpcIdKey] = rawId
                    val handler = onPermissionRequest
                    if (handler != null) {
                        handler.invoke(rpcIdKey, params)
                    } else {
                        replyPermission(rpcIdKey, null)
                    }
                }
                else -> {
                    sendErrorReply(rawId, ERR_METHOD_NOT_FOUND, "Method not supported by client: $method")
                }
            }
            return
        }

        // 3. Server notification (method without id)
        if (!hasId && method != null) {
            val params = msg.optJSONObject("params") ?: JSONObject()
            if (method == "session/update") {
                onSessionUpdate?.invoke(params)
            }
        }
    }

    private fun failAllPending(cause: Throwable) {
        val entries = pending.entries.toList()
        pending.clear()
        for ((_, deferred) in entries) {
            if (!deferred.isCompleted) {
                deferred.completeExceptionally(cause)
            }
        }
    }
}
