package com.termux.app.acp

import com.termux.app.AntigravityEngine

enum class AcpConnectionState {
    DISCONNECTED,
    CONNECTING,
    READY,
    ERROR
}

enum class ToolCallStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

data class PermissionOption(
    val optionId: String,
    val name: String,
    val kind: String = ""
)

data class PendingPermissionRequest(
    val rpcId: String,
    val sessionId: String,
    val title: String,
    val description: String = "",
    val options: List<PermissionOption> = emptyList()
)

data class SessionSummary(
    val sessionId: String,
    val title: String,
    val cwd: String = "",
    val updatedAt: String = ""
)

sealed class TimelineItem {
    abstract val id: String

    data class UserMessage(
        override val id: String,
        val text: String
    ) : TimelineItem()

    data class AssistantMessage(
        override val id: String,
        val markdown: String,
        val isStreaming: Boolean = false
    ) : TimelineItem()

    data class ThinkingBlock(
        override val id: String,
        val text: String,
        val isStreaming: Boolean = false
    ) : TimelineItem()

    data class ToolCallCard(
        override val id: String,
        val title: String,
        val kind: String = "",
        val status: ToolCallStatus = ToolCallStatus.PENDING,
        val output: String = "",
        val diffPath: String? = null,
        val oldText: String? = null,
        val newText: String? = null
    ) : TimelineItem()

    data class SystemNotice(
        override val id: String,
        val text: String,
        val isError: Boolean = false
    ) : TimelineItem()
}

data class AcpUiState(
    val connectionState: AcpConnectionState = AcpConnectionState.DISCONNECTED,
    val statusMessage: String = "Disconnected",
    val activeSessionId: String? = null,
    val workingDir: String = AntigravityEngine.HOME_DIR,
    val timeline: List<TimelineItem> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val pendingPermission: PendingPermissionRequest? = null,
    val isTurnRunning: Boolean = false,
    val stderrLogs: List<String> = emptyList(),
    val agentName: String = "Antigravity"
)
