package com.termux.app.acp

import com.termux.app.AntigravityEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class AcpSessionController(
    private val scope: CoroutineScope,
    private val client: AcpClient = AcpClient(),
    private val onNotify: ((title: String, body: String) -> Unit)? = null
) {
    companion object {
        private const val FLUSH_INTERVAL_MS = 60L
        private const val MAX_STDERR_LINES = 300
    }

    private val _uiState = MutableStateFlow(AcpUiState())
    val uiState: StateFlow<AcpUiState> = _uiState.asStateFlow()

    private val itemSeq = AtomicInteger(1)
    private val activePrompts = AtomicInteger(0)

    private val bufferLock = Any()
    private val pendingMessageDelta = StringBuilder()
    private val pendingThoughtDelta = StringBuilder()

    @Volatile
    private var isForeground: Boolean = true

    private var flushJob: Job? = null
    private var stderrJob: Job? = null
    private var connectJob: Job? = null

    init {
        client.onSessionUpdate = { params -> handleSessionUpdate(params) }
        client.onPermissionRequest = { rpcId, params -> handlePermissionRequest(rpcId, params) }
        startBufferFlusher()
        startStderrCollector()
    }

    fun setForeground(foreground: Boolean) {
        isForeground = foreground
    }

    fun connectAndStart(workingDir: String = _uiState.value.workingDir) {
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            clearPendingBuffers()
            _uiState.update {
                it.copy(
                    connectionState = AcpConnectionState.CONNECTING,
                    statusMessage = "Starting local agy acp...",
                    workingDir = workingDir,
                    pendingPermission = null,
                    isTurnRunning = false
                )
            }

            if (!AntigravityEngine.isBootstrapReady()) {
                _uiState.update {
                    it.copy(
                        connectionState = AcpConnectionState.ERROR,
                        statusMessage = "Embedded Linux bootstrap is not ready yet."
                    )
                }
                appendSystemNotice("Embedded Linux bootstrap is not initialized. Open Web Hub first to finish setup.", isError = true)
                return@launch
            }

            if (!AntigravityEngine.isAgyInstalled()) {
                _uiState.update {
                    it.copy(
                        connectionState = AcpConnectionState.ERROR,
                        statusMessage = "Antigravity CLI (agy) is not installed."
                    )
                }
                appendSystemNotice("Antigravity CLI (agy) was not found at ${AntigravityEngine.AGY_BIN}. Install it from the Web Hub onboarding screen.", isError = true)
                return@launch
            }

            try {
                client.connect(workingDir) { exitCode ->
                    scope.launch(Dispatchers.Default) {
                        flushBuffersNow(finalizeStreaming = true)
                        activePrompts.set(0)
                        _uiState.update { state ->
                            state.copy(
                                connectionState = AcpConnectionState.DISCONNECTED,
                                statusMessage = "agy acp exited (code $exitCode)",
                                isTurnRunning = false,
                                pendingPermission = null
                            )
                        }
                        appendSystemNotice("Local agy acp process exited with code $exitCode. Tap Reconnect to restart.", isError = exitCode != 0)
                    }
                }

                _uiState.update { it.copy(statusMessage = "Handshaking (ACP v1)...") }
                val initResult = client.initialize()
                val agentInfo = initResult.optJSONObject("agentInfo")
                val agentName = agentInfo?.optString("name", "")?.ifBlank { null } ?: "Antigravity"

                val sessions = runCatching { client.listSessions(workingDir) }.getOrDefault(emptyList())

                _uiState.update { it.copy(statusMessage = "Creating session...") }
                val sessionId = client.newSession(workingDir)

                _uiState.update {
                    it.copy(
                        connectionState = AcpConnectionState.READY,
                        statusMessage = "Connected",
                        activeSessionId = sessionId,
                        sessions = sessions,
                        agentName = agentName,
                        timeline = listOf(
                            TimelineItem.SystemNotice(
                                id = nextItemId("sys"),
                                text = "Connected to local $agentName ACP in $workingDir",
                                isError = false
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to connect to agy acp"
                _uiState.update {
                    it.copy(
                        connectionState = AcpConnectionState.ERROR,
                        statusMessage = msg,
                        isTurnRunning = false
                    )
                }
                appendSystemNotice("Connection error: $msg", isError = true)
            }
        }
    }

    fun reconnect() {
        connectAndStart(_uiState.value.workingDir)
    }

    fun setWorkingDir(newDir: String) {
        val cleaned = newDir.trim().ifBlank { AntigravityEngine.HOME_DIR }
        if (cleaned == _uiState.value.workingDir && _uiState.value.connectionState == AcpConnectionState.READY) {
            return
        }
        connectAndStart(cleaned)
    }

    fun refreshSessions() {
        if (_uiState.value.connectionState != AcpConnectionState.READY) return
        scope.launch(Dispatchers.IO) {
            val sessions = runCatching { client.listSessions(_uiState.value.workingDir) }.getOrNull()
            if (sessions != null) {
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
    }

    fun createNewSession() {
        if (_uiState.value.connectionState != AcpConnectionState.READY) {
            reconnect()
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                flushBuffersNow(finalizeStreaming = true)
                clearPendingBuffers()
                val cwd = _uiState.value.workingDir
                val sessionId = client.newSession(cwd)
                val sessions = runCatching { client.listSessions(cwd) }.getOrDefault(_uiState.value.sessions)
                _uiState.update {
                    it.copy(
                        activeSessionId = sessionId,
                        sessions = sessions,
                        pendingPermission = null,
                        isTurnRunning = false,
                        timeline = listOf(
                            TimelineItem.SystemNotice(
                                id = nextItemId("sys"),
                                text = "Started new session (${sessionId.take(8)})",
                                isError = false
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                appendSystemNotice("Failed to create session: ${e.message}", isError = true)
            }
        }
    }

    fun selectSession(sessionId: String) {
        if (_uiState.value.connectionState != AcpConnectionState.READY) return
        if (sessionId == _uiState.value.activeSessionId) return

        scope.launch(Dispatchers.IO) {
            try {
                flushBuffersNow(finalizeStreaming = true)
                clearPendingBuffers()
                val cwd = _uiState.value.workingDir
                _uiState.update {
                    it.copy(
                        activeSessionId = sessionId,
                        pendingPermission = null,
                        isTurnRunning = false,
                        timeline = listOf(
                            TimelineItem.SystemNotice(
                                id = nextItemId("sys"),
                                text = "Resuming session ${sessionId.take(8)}...",
                                isError = false
                            )
                        )
                    )
                }
                client.resumeSession(sessionId, cwd)
                flushBuffersNow(finalizeStreaming = true)
            } catch (e: Exception) {
                appendSystemNotice("Failed to resume session: ${e.message}", isError = true)
            }
        }
    }

    fun sendPrompt(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val sessionId = _uiState.value.activeSessionId
        if (_uiState.value.connectionState != AcpConnectionState.READY || sessionId == null) {
            appendSystemNotice("Not connected to an active ACP session.", isError = true)
            return
        }

        flushBuffersNow(finalizeStreaming = false)
        _uiState.update { state ->
            state.copy(
                timeline = state.timeline + TimelineItem.UserMessage(
                    id = nextItemId("user"),
                    text = text
                ),
                isTurnRunning = true
            )
        }

        activePrompts.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                val result = client.sendPrompt(sessionId, text)
                val stopReason = result.optString("stopReason", "")
                if (stopReason == "cancelled") {
                    appendSystemNotice("Turn cancelled.", isError = false)
                }
            } catch (e: Exception) {
                appendSystemNotice("Prompt failed: ${e.message ?: "unknown error"}", isError = true)
            } finally {
                val remaining = activePrompts.decrementAndGet().coerceAtLeast(0)
                if (remaining == 0) {
                    flushBuffersNow(finalizeStreaming = true)
                    _uiState.update { it.copy(isTurnRunning = false) }
                    if (!isForeground) {
                        val preview = lastAssistantSnippet() ?: "Agent turn completed."
                        onNotify?.invoke("Antigravity ACP", preview)
                    }
                    refreshSessions()
                }
            }
        }
    }

    fun cancelTurn() {
        val sessionId = _uiState.value.activeSessionId ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { client.cancelTurn(sessionId) }
        }
    }

    fun respondToPermission(optionId: String?) {
        val pending = _uiState.value.pendingPermission ?: return
        _uiState.update { it.copy(pendingPermission = null) }
        scope.launch(Dispatchers.IO) {
            try {
                client.replyPermission(pending.rpcId, optionId)
            } catch (e: Exception) {
                appendSystemNotice("Failed to send permission reply: ${e.message}", isError = true)
            }
        }
    }

    fun clearStderrLogs() {
        _uiState.update { it.copy(stderrLogs = emptyList()) }
    }

    fun close() {
        connectJob?.cancel()
        flushJob?.cancel()
        stderrJob?.cancel()
        client.disconnect()
    }

    private fun startBufferFlusher() {
        flushJob?.cancel()
        flushJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushBuffersNow(finalizeStreaming = false)
            }
        }
    }

    private fun startStderrCollector() {
        stderrJob?.cancel()
        stderrJob = scope.launch(Dispatchers.Default) {
            client.stderrLines.collect { line ->
                _uiState.update { state ->
                    val next = if (state.stderrLogs.size >= MAX_STDERR_LINES) {
                        state.stderrLogs.drop(state.stderrLogs.size - MAX_STDERR_LINES + 1) + line
                    } else {
                        state.stderrLogs + line
                    }
                    state.copy(stderrLogs = next)
                }
            }
        }
    }

    private fun handleSessionUpdate(params: JSONObject) {
        val updateSessionId = params.optString("sessionId", "")
        val activeId = _uiState.value.activeSessionId
        if (updateSessionId.isNotEmpty() && activeId != null && updateSessionId != activeId) {
            return
        }

        val update = params.optJSONObject("update") ?: params
        val kind = update.optString("sessionUpdate", update.optString("type", "")).trim()

        when (kind) {
            "agent_message_chunk" -> {
                val chunk = extractChunkText(update)
                if (chunk.isNotEmpty()) {
                    synchronized(bufferLock) {
                        pendingMessageDelta.append(chunk)
                    }
                }
            }
            "agent_thought_chunk" -> {
                val chunk = extractChunkText(update)
                if (chunk.isNotEmpty()) {
                    synchronized(bufferLock) {
                        pendingThoughtDelta.append(chunk)
                    }
                }
            }
            "user_message_chunk" -> {
                val chunk = extractChunkText(update)
                if (chunk.isNotEmpty()) {
                    flushBuffersNow(finalizeStreaming = true)
                    _uiState.update { state ->
                        val items = state.timeline.toMutableList()
                        val last = items.lastOrNull()
                        if (last is TimelineItem.UserMessage) {
                            items[items.lastIndex] = last.copy(text = last.text + chunk)
                        } else {
                            items.add(TimelineItem.UserMessage(nextItemId("user"), chunk))
                        }
                        state.copy(timeline = items)
                    }
                }
            }
            "tool_call", "tool_call_update" -> {
                flushBuffersNow(finalizeStreaming = false)
                reduceToolCallUpdate(update)
            }
            else -> {
                // Ignore unknown update types (plan, available_commands_update, etc.)
            }
        }
    }

    private fun handlePermissionRequest(rpcId: String, params: JSONObject) {
        val sessionId = params.optString("sessionId", _uiState.value.activeSessionId ?: "")
        val toolCall = params.optJSONObject("toolCall")
        val title = toolCall?.optString("title", "")?.ifBlank { null }
            ?: params.optString("title", "").ifBlank { null }
            ?: "Permission Required"
        val description = buildString {
            val toolKind = toolCall?.optString("kind", "").orEmpty()
            if (toolKind.isNotEmpty()) append("[$toolKind] ")
            val rawInput = toolCall?.opt("rawInput")
            if (rawInput != null && rawInput != JSONObject.NULL) {
                append(rawInput.toString().take(240))
            } else {
                val desc = params.optString("description", "")
                if (desc.isNotEmpty()) append(desc)
            }
        }.trim()

        val optionsArr = params.optJSONArray("options")
        val options = ArrayList<PermissionOption>()
        if (optionsArr != null) {
            for (i in 0 until optionsArr.length()) {
                val opt = optionsArr.optJSONObject(i) ?: continue
                val optId = opt.optString("optionId", opt.optString("id", "")).trim()
                if (optId.isEmpty()) continue
                val name = opt.optString("name", opt.optString("label", optId))
                val kind = opt.optString("kind", "")
                options.add(PermissionOption(optionId = optId, name = name, kind = kind))
            }
        }

        val request = PendingPermissionRequest(
            rpcId = rpcId,
            sessionId = sessionId,
            title = title,
            description = description,
            options = options
        )
        _uiState.update { it.copy(pendingPermission = request) }

        if (!isForeground) {
            onNotify?.invoke("Approval Needed", title)
        }
    }

    private fun reduceToolCallUpdate(update: JSONObject) {
        val callId = update.optString("toolCallId", update.optString("id", "")).ifBlank {
            nextItemId("tool")
        }
        val title = update.optString("title", "").trim()
        val kind = update.optString("kind", "").trim()
        val statusStr = update.optString("status", "").lowercase().trim()
        val parsedStatus = when (statusStr) {
            "pending" -> ToolCallStatus.PENDING
            "in_progress", "running" -> ToolCallStatus.IN_PROGRESS
            "completed", "done", "success" -> ToolCallStatus.COMPLETED
            "failed", "error", "cancelled" -> ToolCallStatus.FAILED
            else -> null
        }

        var diffPath: String? = null
        var oldText: String? = null
        var newText: String? = null
        val outputBuilder = StringBuilder()

        val contentArr = update.optJSONArray("content")
        if (contentArr != null) {
            extractToolContent(contentArr, outputBuilder) { path, oldT, newT ->
                diffPath = path
                oldText = oldT
                newText = newT
            }
        }

        val rawOutput = update.opt("rawOutput")
        if (outputBuilder.isEmpty() && rawOutput != null && rawOutput != JSONObject.NULL) {
            val rawStr = when (rawOutput) {
                is JSONObject -> rawOutput.optString("stdout", "").ifBlank {
                    rawOutput.optString("output", "").ifBlank { rawOutput.toString(2) }
                }
                else -> rawOutput.toString()
            }
            if (rawStr.isNotBlank()) {
                outputBuilder.append(rawStr)
            }
        }

        val newOutput = outputBuilder.toString().trim()

        _uiState.update { state ->
            val items = state.timeline.toMutableList()
            val existingIdx = items.indexOfFirst { it is TimelineItem.ToolCallCard && it.id == callId }
            if (existingIdx >= 0) {
                val existing = items[existingIdx] as TimelineItem.ToolCallCard
                items[existingIdx] = existing.copy(
                    title = title.ifBlank { existing.title },
                    kind = kind.ifBlank { existing.kind },
                    status = parsedStatus ?: existing.status,
                    output = if (newOutput.isNotEmpty()) newOutput else existing.output,
                    diffPath = diffPath ?: existing.diffPath,
                    oldText = oldText ?: existing.oldText,
                    newText = newText ?: existing.newText
                )
            } else {
                // Close any currently streaming thinking/assistant blocks cleanly before inserting tool card
                for (i in items.indices) {
                    when (val item = items[i]) {
                        is TimelineItem.ThinkingBlock -> if (item.isStreaming) items[i] = item.copy(isStreaming = false)
                        is TimelineItem.AssistantMessage -> if (item.isStreaming) items[i] = item.copy(isStreaming = false)
                        else -> Unit
                    }
                }
                items.add(
                    TimelineItem.ToolCallCard(
                        id = callId,
                        title = title.ifBlank { "Tool Call" },
                        kind = kind,
                        status = parsedStatus ?: ToolCallStatus.IN_PROGRESS,
                        output = newOutput,
                        diffPath = diffPath,
                        oldText = oldText,
                        newText = newText
                    )
                )
            }
            state.copy(timeline = items)
        }
    }

    private fun extractToolContent(
        contentArr: JSONArray,
        outputBuilder: StringBuilder,
        onDiff: (path: String?, oldText: String?, newText: String?) -> Unit
    ) {
        for (i in 0 until contentArr.length()) {
            val item = contentArr.optJSONObject(i) ?: continue
            val type = item.optString("type", "")
            when (type) {
                "diff" -> {
                    val path = item.optString("path", "").ifBlank { null }
                    val oldT = if (item.isNull("oldText")) null else item.optString("oldText", "")
                    val newT = if (item.isNull("newText")) null else item.optString("newText", "")
                    onDiff(path, oldT, newT)
                }
                "content" -> {
                    val inner = item.optJSONObject("content")
                    val text = inner?.optString("text", "").orEmpty()
                    if (text.isNotEmpty()) {
                        if (outputBuilder.isNotEmpty()) outputBuilder.append('\n')
                        outputBuilder.append(text)
                    }
                }
                "text" -> {
                    val text = item.optString("text", "")
                    if (text.isNotEmpty()) {
                        if (outputBuilder.isNotEmpty()) outputBuilder.append('\n')
                        outputBuilder.append(text)
                    }
                }
                "terminal" -> {
                    val termId = item.optString("terminalId", "")
                    val out = item.optString("output", "")
                    if (out.isNotEmpty()) {
                        if (outputBuilder.isNotEmpty()) outputBuilder.append('\n')
                        outputBuilder.append(out)
                    } else if (termId.isNotEmpty() && outputBuilder.isEmpty()) {
                        outputBuilder.append("Terminal: $termId")
                    }
                }
            }
        }
    }

    private fun extractChunkText(update: JSONObject): String {
        val contentObj = update.optJSONObject("content")
        if (contentObj != null) {
            return contentObj.optString("text", "")
        }
        val contentStr = update.optString("content", "")
        if (contentStr.isNotEmpty() && !contentStr.startsWith("{")) {
            return contentStr
        }
        return update.optString("text", "")
    }

    private fun flushBuffersNow(finalizeStreaming: Boolean) {
        val thoughtDelta: String
        val messageDelta: String
        synchronized(bufferLock) {
            thoughtDelta = pendingThoughtDelta.toString()
            pendingThoughtDelta.setLength(0)
            messageDelta = pendingMessageDelta.toString()
            pendingMessageDelta.setLength(0)
        }

        if (thoughtDelta.isEmpty() && messageDelta.isEmpty() && !finalizeStreaming) {
            return
        }

        _uiState.update { state ->
            val items = state.timeline.toMutableList()
            var changed = false

            if (thoughtDelta.isNotEmpty()) {
                val last = items.lastOrNull()
                if (last is TimelineItem.ThinkingBlock && last.isStreaming) {
                    items[items.lastIndex] = last.copy(text = last.text + thoughtDelta)
                } else {
                    items.add(
                        TimelineItem.ThinkingBlock(
                            id = nextItemId("think"),
                            text = thoughtDelta,
                            isStreaming = !finalizeStreaming
                        )
                    )
                }
                changed = true
            }

            if (messageDelta.isNotEmpty()) {
                val last = items.lastOrNull()
                if (last is TimelineItem.ThinkingBlock && last.isStreaming) {
                    items[items.lastIndex] = last.copy(isStreaming = false)
                }
                val updatedLast = items.lastOrNull()
                if (updatedLast is TimelineItem.AssistantMessage && updatedLast.isStreaming) {
                    items[items.lastIndex] = updatedLast.copy(markdown = updatedLast.markdown + messageDelta)
                } else {
                    items.add(
                        TimelineItem.AssistantMessage(
                            id = nextItemId("asst"),
                            markdown = messageDelta,
                            isStreaming = !finalizeStreaming
                        )
                    )
                }
                changed = true
            }

            if (finalizeStreaming) {
                for (i in items.indices) {
                    when (val item = items[i]) {
                        is TimelineItem.ThinkingBlock -> if (item.isStreaming) {
                            items[i] = item.copy(isStreaming = false)
                            changed = true
                        }
                        is TimelineItem.AssistantMessage -> if (item.isStreaming) {
                            items[i] = item.copy(isStreaming = false)
                            changed = true
                        }
                        else -> Unit
                    }
                }
            }

            if (changed) state.copy(timeline = items) else state
        }
    }

    private fun clearPendingBuffers() {
        synchronized(bufferLock) {
            pendingMessageDelta.setLength(0)
            pendingThoughtDelta.setLength(0)
        }
    }

    private fun appendSystemNotice(text: String, isError: Boolean) {
        _uiState.update { state ->
            state.copy(
                timeline = state.timeline + TimelineItem.SystemNotice(
                    id = nextItemId("sys"),
                    text = text,
                    isError = isError
                )
            )
        }
    }

    private fun lastAssistantSnippet(): String? {
        val last = _uiState.value.timeline.lastOrNull { it is TimelineItem.AssistantMessage }
            as? TimelineItem.AssistantMessage ?: return null
        val cleaned = last.markdown.replace(Regex("\\s+"), " ").trim()
        return cleaned.takeIf { it.isNotEmpty() }?.take(140)
    }

    private fun nextItemId(prefix: String): String = "${prefix}_${itemSeq.getAndIncrement()}"
}
