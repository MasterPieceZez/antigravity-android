package com.termux.app.acp

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.termux.R
import java.io.File
import com.termux.app.AntigravityMainActivity
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.launch
import java.util.Locale

private val AgyBg = Color(0xFF0B0D10)
private val AgySurface = Color(0xFF14171D)
private val AgyElevated = Color(0xFF1B2028)
private val AgyBorder = Color(0xFF2A2F38)
private val AgyTextPrimary = Color(0xFFE6EAF0)
private val AgyTextSecondary = Color(0xFF94A3B8)
private val AgyAccent = Color(0xFF3B82F6)
private val AgyOk = Color(0xFF22C55E)
private val AgyWarn = Color(0xFFF59E0B)
private val AgyDanger = Color(0xFFEF4444)

private val AgyColorScheme = darkColorScheme(
    primary = AgyAccent,
    onPrimary = Color.White,
    background = AgyBg,
    onBackground = AgyTextPrimary,
    surface = AgySurface,
    onSurface = AgyTextPrimary,
    surfaceVariant = AgyElevated,
    onSurfaceVariant = AgyTextSecondary,
    error = AgyDanger,
    onError = Color.White,
    outline = AgyBorder
)

private val QUICK_PROMPTS = listOf(
    "Git status" to "Run git status and summarize any modified or untracked files.",
    "Explain diff" to "Inspect the current git diff and explain the key changes.",
    "Run tests" to "Run the project's test suite and report any failures."
)

class AcpNativeActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "antigravity_prefs"
        private const val KEY_NOTIFY_DONE = "notify_agent_done"
        private const val NOTIF_CHANNEL_ID = "antigravity_agent_done"
        private const val NOTIF_ID_ACP = 4403
    }

    private lateinit var controller: AcpSessionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = AndroidColor.parseColor("#0B0D10")
        window.navigationBarColor = AndroidColor.parseColor("#0B0D10")

        ensureNotificationChannel()

        controller = AcpSessionController(
            scope = lifecycleScope,
            onNotify = { title, body -> postBackgroundNotification(title, body) }
        )
        controller.connectAndStart()

        setContent {
            MaterialTheme(colorScheme = AgyColorScheme) {
                AcpNativeScreen(
                    controller = controller,
                    onBackToWebHub = {
                    val i = Intent(this@AcpNativeActivity, AntigravityMainActivity::class.java).apply {
                        putExtra(AntigravityMainActivity.EXTRA_FORCE_WEB_HUB, true)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(i)
                    finish()
                }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.setForeground(true)
    }

    override fun onPause() {
        super.onPause()
        controller.setForeground(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.close()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Antigravity Agent Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when an Antigravity agent finishes a task or needs approval"
                enableVibration(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun postBackgroundNotification(title: String, body: String) {
        val enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_NOTIFY_DONE, true)
        if (!enabled) return

        val openIntent = Intent(this, AcpNativeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, NOTIF_ID_ACP, openIntent, piFlags)

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_antigravity_notif)
            .setColor(AndroidColor.parseColor("#3B82F6"))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID_ACP, notification)
    }
}

@Composable
private fun AcpNativeScreen(
    controller: AcpSessionController,
    onBackToWebHub: () -> Unit
) {
    val state by controller.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val uiScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var inputText by remember { mutableStateOf("") }
    var showStderrDialog by remember { mutableStateOf(false) }
    var showCwdDialog by remember { mutableStateOf(false) }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                inputText = if (inputText.isBlank()) spoken else "$inputText $spoken"
            }
        }
    }

    LaunchedEffect(state.timeline.size, state.timeline.lastOrNull()) {
        if (state.timeline.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearBottom = state.timeline.size - lastVisible <= 3
            if (nearBottom || state.timeline.size <= 2) {
                listState.animateScrollToItem(state.timeline.lastIndex)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AcpDrawerContent(
                state = state,
                onSelectSession = { id ->
                    controller.selectSession(id)
                    uiScope.launch { drawerState.close() }
                },
                onNewSession = {
                    controller.createNewSession()
                    uiScope.launch { drawerState.close() }
                },
                onRefreshSessions = { controller.refreshSessions() },
                onEditWorkingDir = {
                    showCwdDialog = true
                    uiScope.launch { drawerState.close() }
                },
                onOpenStderr = {
                    showStderrDialog = true
                    uiScope.launch { drawerState.close() }
                },
                onSwitchToWebHub = {
                    uiScope.launch { drawerState.close() }
                    onBackToWebHub()
                }
            )
        }
    ) {
        Scaffold(
            containerColor = AgyBg,
            topBar = {
                AcpTopBar(
                    state = state,
                    onOpenDrawer = {
                        controller.refreshSessions()
                        uiScope.launch { drawerState.open() }
                    },
                    onNewSession = { controller.createNewSession() },
                    onReconnect = { controller.reconnect() },
                    onClose = onBackToWebHub
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                var showQuickPrompts by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (state.timeline.isEmpty()) {
                        AcpHubEmptyHero(
                            connectionState = state.connectionState,
                            statusMessage = state.statusMessage
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(state.timeline, key = { it.id }) { item ->
                                Box(
                                    modifier = Modifier.animateContentSize(
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                    )
                                ) {
                                    TimelineItemRow(item)
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = state.pendingPermission != null,
                    enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
                ) {
                    state.pendingPermission?.let { permission ->
                        PermissionCard(
                            request = permission,
                            onSelectOption = { optionId -> controller.respondToPermission(optionId) }
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showQuickPrompts,
                    enter = fadeIn(tween(180)) + slideInVertically { it / 3 },
                    exit = fadeOut(tween(150)) + slideOutVertically { it / 3 }
                ) {
                    QuickPromptRow(
                        enabled = state.connectionState == AcpConnectionState.READY,
                        onSelectPrompt = { prompt ->
                            showQuickPrompts = false
                            controller.sendPrompt(prompt)
                        }
                    )
                }

                ComposerBar(
                    text = inputText,
                    onTextChange = { inputText = it },
                    isConnected = state.connectionState == AcpConnectionState.READY,
                    isTurnRunning = state.isTurnRunning,
                    workingDirLabel = File(state.workingDir).name.ifBlank { "home" },
                    onToggleQuickPrompts = { showQuickPrompts = !showQuickPrompts },
                    onEditWorkingDir = { showCwdDialog = true },
                    onMicClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Antigravity")
                        }
                        try {
                            speechLauncher.launch(intent)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                "Speech recognition is not available on this device",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onSend = {
                        val payload = inputText.trim()
                        if (payload.isNotEmpty()) {
                            inputText = ""
                            controller.sendPrompt(payload)
                        }
                    },
                    onCancel = { controller.cancelTurn() }
                )
            }
        }
    }

    if (showStderrDialog) {
        StderrLogDialog(
            logs = state.stderrLogs,
            onClear = { controller.clearStderrLogs() },
            onDismiss = { showStderrDialog = false }
        )
    }

    if (showCwdDialog) {
        WorkingDirDialog(
            currentDir = state.workingDir,
            onConfirm = { dir ->
                showCwdDialog = false
                controller.setWorkingDir(dir)
            },
            onDismiss = { showCwdDialog = false }
        )
    }
}

@Composable
private fun AcpTopBar(
    state: AcpUiState,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit
) {
    val dotColor = when (state.connectionState) {
        AcpConnectionState.READY -> AgyOk
        AcpConnectionState.CONNECTING -> AgyWarn
        AcpConnectionState.ERROR -> AgyDanger
        AcpConnectionState.DISCONNECTED -> AgyTextSecondary
    }

    Surface(color = AgySurface, tonalElevation = 0.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Rounded.Menu, contentDescription = "Sessions", tint = AgyTextPrimary)
                }

                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${state.agentName} ACP",
                        color = AgyTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = when {
                        state.connectionState != AcpConnectionState.READY -> state.statusMessage
                        state.isTurnRunning -> "Running turn..."
                        state.activeSessionId != null -> "Session ${state.activeSessionId.take(8)}"
                        else -> "Ready"
                    }
                    Text(
                        text = subtitle,
                        color = AgyTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onNewSession) {
                    Icon(Icons.Rounded.Add, contentDescription = "New Session", tint = AgyTextPrimary)
                }
                IconButton(onClick = onReconnect) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Reconnect", tint = AgyTextPrimary)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Back to Web Hub", tint = AgyTextSecondary)
                }
            }
            HorizontalDivider(color = AgyBorder, thickness = 1.dp)
        }
    }
}

@Composable
private fun AcpDrawerContent(
    state: AcpUiState,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onRefreshSessions: () -> Unit,
    onEditWorkingDir: () -> Unit,
    onOpenStderr: () -> Unit,
    onSwitchToWebHub: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = AgySurface,
        drawerContentColor = AgyTextPrimary,
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ACP Sessions",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = AgyTextPrimary
                )
                IconButton(onClick = onRefreshSessions) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = AgyTextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AgyElevated)
                    .border(1.dp, AgyBorder, RoundedCornerShape(8.dp))
                    .clickable { onEditWorkingDir() }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = AgyAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Working Directory", color = AgyTextSecondary, fontSize = 11.sp)
                    Text(
                        text = state.workingDir,
                        color = AgyTextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onNewSession,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AgyAccent),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("New Session", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = AgyBorder)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (state.sessions.isEmpty()) {
                    item {
                        Text(
                            text = "No saved sessions found.",
                            color = AgyTextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(state.sessions, key = { it.sessionId }) { session ->
                        val active = session.sessionId == state.activeSessionId
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) AgyElevated else Color.Transparent)
                                .border(
                                    width = 1.dp,
                                    color = if (active) AgyAccent else AgyBorder,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectSession(session.sessionId) }
                                .padding(10.dp)
                        ) {
                            Text(
                                text = session.title,
                                color = AgyTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (session.updatedAt.isNotBlank() || session.cwd.isNotBlank()) {
                                val meta = listOf(session.cwd, session.updatedAt)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                Text(
                                    text = meta,
                                    color = AgyTextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = AgyBorder)
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onOpenStderr,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Stderr Logs (${state.stderrLogs.size})", fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))


        val prefs = LocalContext.current.getSharedPreferences("antigravity_prefs", Context.MODE_PRIVATE)
        var defaultAcp by remember { mutableStateOf(prefs.getString(AntigravityMainActivity.KEY_DEFAULT_UI, "acp") == "acp") }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Default UI: Native ACP",
                color = AgyTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = defaultAcp,
                onCheckedChange = { checked ->
                    defaultAcp = checked
                    prefs.edit().putString(AntigravityMainActivity.KEY_DEFAULT_UI, if (checked) "acp" else "web").apply()
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AgyAccent)
            )
        }

            OutlinedButton(
                onClick = onSwitchToWebHub,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Switch to Web Hub", color = AgyTextPrimary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TimelineItemRow(item: TimelineItem) {
    when (item) {
        is TimelineItem.UserMessage -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                        .background(Color(0xFF1D3557))
                        .border(1.dp, Color(0xFF2B4C7E), RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = item.text,
                        color = AgyTextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        is TimelineItem.AssistantMessage -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AgySurface)
                    .border(1.dp, AgyBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                AcpMarkdownView(markdown = item.markdown)
                if (item.isStreaming) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Streaming...",
                        color = AgyAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        is TimelineItem.ThinkingBlock -> {
            var expanded by remember(item.id) { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF11141B))
                    .border(1.dp, AgyBorder, RoundedCornerShape(10.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Psychology,
                        contentDescription = null,
                        tint = AgyTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (item.isStreaming) "Thinking..." else "Reasoning",
                        color = AgyTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = AgyTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (expanded) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.text,
                        color = AgyTextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        is TimelineItem.ToolCallCard -> {
            var expanded by remember(item.id) {
                mutableStateOf(item.diffPath != null || item.newText != null)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AgyElevated)
                    .border(1.dp, AgyBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (item.status) {
                        ToolCallStatus.IN_PROGRESS -> CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = AgyAccent
                        )
                        ToolCallStatus.COMPLETED -> Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = AgyOk,
                            modifier = Modifier.size(16.dp)
                        )
                        ToolCallStatus.FAILED -> Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = AgyDanger,
                            modifier = Modifier.size(16.dp)
                        )
                        ToolCallStatus.PENDING -> Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = AgyWarn,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Rounded.Build,
                        contentDescription = null,
                        tint = AgyTextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.title,
                        color = AgyTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.kind.isNotBlank()) {
                        Text(
                            text = item.kind,
                            color = AgyTextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AgySurface)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = AgyTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (expanded) {
                    if (item.diffPath != null || item.oldText != null || item.newText != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        UnifiedDiffBlock(
                            path = item.diffPath,
                            oldText = item.oldText,
                            newText = item.newText
                        )
                    }
                    if (item.output.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(AgyBg)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp)
                        ) {
                            Text(
                                text = item.output,
                                color = AgyTextSecondary,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }

        is TimelineItem.SystemNotice -> {
            val bg = if (item.isError) Color(0xFF2B1418) else AgySurface
            val border = if (item.isError) AgyDanger else AgyBorder
            val fg = if (item.isError) Color(0xFFFCA5A5) else AgyTextSecondary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = item.text,
                    color = fg,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    request: PendingPermissionRequest,
    onSelectOption: (String?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1F1A12))
            .border(1.dp, AgyWarn, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = "Approval Required",
            color = AgyWarn,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = request.title,
            color = AgyTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (request.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = request.description,
                color = AgyTextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (opt in request.options) {
                val isReject = opt.kind.contains("reject", ignoreCase = true) ||
                    opt.name.contains("deny", ignoreCase = true)
                Button(
                    onClick = { onSelectOption(opt.optionId) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isReject) AgyDanger else AgyAccent
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(opt.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            OutlinedButton(
                onClick = { onSelectOption(null) },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Cancel", color = AgyTextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun QuickPromptRow(
    enabled: Boolean,
    onSelectPrompt: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((label, prompt) in QUICK_PROMPTS) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(AgySurface)
                    .border(1.dp, AgyBorder, RoundedCornerShape(16.dp))
                    .clickable(enabled = enabled) { onSelectPrompt(prompt) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    color = if (enabled) AgyTextPrimary else AgyTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun AcpHubEmptyHero(
    connectionState: AcpConnectionState,
    statusMessage: String
) {
    val pulse = rememberInfiniteTransition(label = "heroPulse")
    val logoAlpha by pulse.animateFloat(
        initialValue = 0.34f,
        targetValue = 0.56f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "logoAlpha"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_antigravity_notif),
            contentDescription = "Antigravity",
            tint = Color(0xFF8B949E).copy(alpha = logoAlpha),
            modifier = Modifier.size(62.dp)
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "What are we working on today?",
            color = Color(0xFFCBD5E1),
            fontSize = 19.sp,
            fontWeight = FontWeight.Medium
        )
        if (connectionState != AcpConnectionState.READY) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = statusMessage,
                color = AgyTextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    isConnected: Boolean,
    isTurnRunning: Boolean,
    workingDirLabel: String,
    onToggleQuickPrompts: () -> Unit,
    onEditWorkingDir: () -> Unit,
    onMicClick: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val canSend = isConnected && text.isNotBlank()
    val sendBg by animateColorAsState(
        targetValue = if (canSend) Color(0xFFE6EAF0) else AgyElevated,
        animationSpec = tween(160),
        label = "sendBg"
    )
    val sendTint by animateColorAsState(
        targetValue = if (canSend) Color(0xFF0B0D10) else AgyTextSecondary,
        animationSpec = tween(160),
        label = "sendTint"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AgyBg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(AgySurface)
                .border(1.dp, AgyBorder, RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = if (isTurnRunning) "Steer active turn..." else "Ask anything, @ to mention, / for actions",
                        color = AgyTextSecondary,
                        fontSize = 15.sp
                    )
                },
                maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AgyTextPrimary,
                    unfocusedTextColor = AgyTextPrimary,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = AgyAccent
                )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleQuickPrompts,
                    modifier = Modifier.size(34.dp)
                ) {
                    Text(
                        text = "+",
                        color = AgyTextSecondary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Light
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AgyBg)
                        .border(1.dp, AgyBorder, RoundedCornerShape(10.dp))
                        .clickable(onClick = onEditWorkingDir)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = workingDirLabel,
                        color = AgyTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = onMicClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.Mic,
                        contentDescription = "Voice Input",
                        tint = AgyTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                if (isTurnRunning) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(AgyDanger)
                    ) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = "Stop Turn",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(sendBg)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = if (isTurnRunning) "Steer" else "Send",
                        tint = sendTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StderrLogDialog(
    logs: List<String>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val joined = remember(logs) {
        if (logs.isEmpty()) "(no stderr output yet)" else logs.joinToString("\n")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AgySurface,
        titleContentColor = AgyTextPrimary,
        textContentColor = AgyTextSecondary,
        title = { Text("agy acp stderr Logs", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 360.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AgyBg)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(
                    text = joined,
                    color = AgyTextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("acp_stderr", joined))
                Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
            }) {
                Text("Copy", color = AgyAccent)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) {
                    Text("Clear", color = AgyTextSecondary)
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = AgyTextPrimary)
                }
            }
        }
    )
}

@Composable
private fun WorkingDirDialog(
    currentDir: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(currentDir) { mutableStateOf(currentDir) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AgySurface,
        titleContentColor = AgyTextPrimary,
        title = { Text("Working Directory", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { Text("Directory path") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AgyTextPrimary,
                    unfocusedTextColor = AgyTextPrimary,
                    focusedBorderColor = AgyAccent,
                    unfocusedBorderColor = AgyBorder
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) {
                Text("Apply & Reconnect", color = AgyAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AgyTextSecondary)
            }
        }
    )
}
