package dev.stint.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stint.shared.ClientController
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.Subject
import dev.stint.shared.TimerStatus
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

private enum class MainTab { FOCUS, RECORDS }
internal enum class SidePanel { NONE, SUBJECT, TIME, CONNECTION }

/**
 * Folding feature bounds in density-independent pixels, relative to the application window.
 * Responsive content measures its own position in that window before reserving the hinge gap.
 */
data class FoldPosture(
    val isTabletop: Boolean = false,
    val isBook: Boolean = false,
    val hingeLeftDp: Float = 0f,
    val hingeTopDp: Float = 0f,
    val hingeWidthDp: Float = 0f,
    val hingeHeightDp: Float = 0f,
)

@Composable
fun StintApp(controller: ClientController, foldPosture: FoldPosture = FoldPosture()) {
    val state by controller.state.collectAsState()

    WorkbenchTheme {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            if (state.connection == ConnectionStatus.SETUP || state.settings.baseUrl.isBlank()) {
                ConnectionSetup(
                    initialUrl = state.settings.baseUrl,
                    initialToken = state.settings.token,
                    isConnecting = state.connection == ConnectionStatus.CONNECTING,
                    message = state.message,
                    onConnect = controller::configure,
                    onDismissMessage = controller::dismissMessage,
                )
            } else {
                Workbench(controller, state, foldPosture)
            }
        }
    }
}

@Composable
private fun Workbench(controller: ClientController, state: ClientState, foldPosture: FoldPosture) {
    var tabName by rememberSaveable { mutableStateOf(MainTab.FOCUS.name) }
    var panelName by rememberSaveable { mutableStateOf(SidePanel.NONE.name) }
    var selectedSubjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSubjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var tickNow by remember { mutableLongStateOf(state.serverNowEpochMs) }

    val tab = MainTab.entries.firstOrNull { it.name == tabName } ?: MainTab.FOCUS
    val panel = SidePanel.entries.firstOrNull { it.name == panelName } ?: SidePanel.NONE

    val subjects = state.snapshot?.subjects.orEmpty().filterNot { it.archived }
    val timerSubjectId = state.snapshot?.timer?.subjectId
    LaunchedEffect(subjects, timerSubjectId) {
        selectedSubjectId = when {
            timerSubjectId != null && subjects.any { it.id == timerSubjectId } -> timerSubjectId
            selectedSubjectId != null && subjects.any { it.id == selectedSubjectId } -> selectedSubjectId
            else -> subjects.firstOrNull()?.id
        }
    }
    LaunchedEffect(state.serverNowEpochMs) {
        val base = state.serverNowEpochMs
        val mark = TimeSource.Monotonic.markNow()
        while (true) {
            tickNow = base + mark.elapsedNow().inWholeMilliseconds
            delay(250)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize()) {
            WorkbenchHeader(
                tab = tab,
                state = state,
                onTab = { tabName = it.name },
                onRefresh = controller::refresh,
                onConnection = {
                    panelName = (if (panel == SidePanel.CONNECTION) SidePanel.NONE else SidePanel.CONNECTION).name
                    editingSubjectId = null
                },
            )
            ThinRule()

            when {
                panel == SidePanel.CONNECTION -> ConnectionSettingsPanel(
                    state = state,
                    onSave = controller::configure,
                    onClose = { panelName = SidePanel.NONE.name },
                )

                panel == SidePanel.SUBJECT -> SubjectEditor(
                    original = subjects.firstOrNull { it.id == editingSubjectId },
                    nowEpochMs = tickNow,
                    onSave = {
                        controller.saveSubject(it)
                        selectedSubjectId = it.id
                        panelName = SidePanel.NONE.name
                    },
                    onClose = { panelName = SidePanel.NONE.name },
                )

                panel == SidePanel.TIME -> {
                    val timer = state.snapshot?.timer
                    val timeSubject = subjects.firstOrNull {
                        timer?.status != TimerStatus.IDLE && it.id == timer?.subjectId
                    } ?: subjects.firstOrNull { it.id == selectedSubjectId }
                    TimeSettingsPanel(
                        state = state,
                        subject = timeSubject,
                        nowEpochMs = tickNow,
                        onPause = controller::pause,
                        onSaveCurrent = { controller.setTimerDuration(it, state.snapshot?.revision) },
                        onSaveDefaults = controller::saveSubject,
                        onClose = { panelName = SidePanel.NONE.name },
                    )
                }

                tab == MainTab.FOCUS -> FocusTab(
                    controller = controller,
                    state = state,
                    nowEpochMs = tickNow,
                    selectedSubjectId = selectedSubjectId,
                    onSelectSubject = { selectedSubjectId = it },
                    onAddSubject = {
                        editingSubjectId = null
                        panelName = SidePanel.SUBJECT.name
                    },
                    onEditSubject = {
                        editingSubjectId = selectedSubjectId
                        panelName = SidePanel.SUBJECT.name
                    },
                    onTimeSettings = { panelName = SidePanel.TIME.name },
                    wide = wide,
                    foldPosture = foldPosture,
                )

                else -> RecordsTab(
                    controller = controller,
                    state = state,
                    nowEpochMs = tickNow,
                    selectedSubjectId = selectedSubjectId,
                    onSelectSubject = { selectedSubjectId = it },
                    wide = wide,
                    foldPosture = foldPosture,
                )
            }
        }

        state.message?.let { message ->
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(18.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ink)
                    .padding(start = 18.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, color = PaperRaised, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = controller::dismissMessage) { Text("닫기", color = PaperRaised) }
            }
        }
    }
}

@Composable
private fun WorkbenchHeader(
    tab: MainTab,
    state: ClientState,
    onTab: (MainTab) -> Unit,
    onRefresh: () -> Unit,
    onConnection: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        Row(
            modifier = Modifier.fillMaxWidth().height(if (compact) 64.dp else 72.dp).padding(horizontal = if (compact) 12.dp else 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(if (compact) 12.dp else 15.dp)) { drawCircle(Clay) }
                if (!compact) {
                    Spacer(Modifier.width(10.dp))
                    Text("STINT", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(if (compact) 6.dp else 32.dp))
            HeaderTab("집중", tab == MainTab.FOCUS, compact) { onTab(MainTab.FOCUS) }
            HeaderTab("기록", tab == MainTab.RECORDS, compact) { onTab(MainTab.RECORDS) }
            Spacer(Modifier.weight(1f))
            ConnectionBadge(state.connection, state.snapshot?.timer?.controllingDeviceName, compact, onConnection)
            TextButton(onClick = onRefresh, enabled = !state.working) {
                Text(if (compact) "새로" else "새로고침", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun HeaderTab(label: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(if (compact) 64.dp else 84.dp)
            .height(if (compact) 64.dp else 72.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (compact) 9.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Ink else InkMuted,
            style = MaterialTheme.typography.labelLarge,
        )
        if (selected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Clay))
    }
}

@Composable
private fun ConnectionBadge(status: ConnectionStatus, deviceName: String?, compact: Boolean, onClick: () -> Unit) {
    val (color, label) = when (status) {
        ConnectionStatus.CONNECTED -> Forest to (deviceName?.let { "$it · 동기화됨" } ?: "동기화됨")
        ConnectionStatus.CONNECTING -> Clay to "연결 중"
        ConnectionStatus.OFFLINE -> InkMuted to "오프라인"
        ConnectionStatus.SETUP -> InkMuted to "연결 설정"
    }
    Row(
        Modifier
            .widthIn(max = if (compact) 88.dp else 180.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Spacer(Modifier.width(7.dp))
        Text(
            if (compact) when (status) {
                ConnectionStatus.CONNECTED -> "연결"
                ConnectionStatus.CONNECTING -> "연결 중"
                ConnectionStatus.OFFLINE -> "오프라인"
                ConnectionStatus.SETUP -> "설정"
            } else label,
            modifier = Modifier.weight(1f, fill = false),
            color = InkMuted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConnectionSetup(
    initialUrl: String,
    initialToken: String,
    isConnecting: Boolean,
    message: String?,
    onConnect: (String, String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember(initialToken) { mutableStateOf(initialToken) }
    var localError by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val compact = maxWidth < 700.dp
        Row(Modifier.fillMaxSize()) {
            if (!compact) {
                Column(
                    Modifier.weight(0.85f).fillMaxHeight().background(Ink).padding(52.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("STINT", color = Paper, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Column {
                        Text("집중은 기기를\n옮겨도 이어져야 하니까.", color = PaperRaised, style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(22.dp))
                        Text("Mac에서 시작하고 Android에서 이어 보세요.", color = PaperDeep, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("개인 연결 · iCloud 없이", color = Rule, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(if (compact) 28.dp else 56.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (compact) {
                    Text("STINT", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(48.dp))
                }
                Text("작업대 연결", style = MaterialTheme.typography.headlineMedium, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text("같은 서버 주소와 연결 키를 두 기기에 입력하세요.", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(30.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; localError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("서버 주소") },
                    placeholder = { Text("https://focus.example.com") },
                    singleLine = true,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; localError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("개인 연결 키") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                val error = localError ?: message
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(error, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        val normalized = url.trim().trimEnd('/')
                        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
                            localError = "http:// 또는 https://로 시작하는 주소를 입력해 주세요."
                        } else if (token.isBlank()) {
                            localError = "연결 키를 입력해 주세요."
                        } else {
                            onDismissMessage()
                            onConnect(normalized, token.trim())
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !isConnecting,
                    shape = RoundedCornerShape(8.dp),
                ) { Text(if (isConnecting) "연결하는 중" else "연결하기") }
                Spacer(Modifier.height(16.dp))
                Text("연결 키는 이 기기에만 저장돼요.", color = InkMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
@Suppress("DEPRECATION")
private fun ConnectionSettingsPanel(state: ClientState, onSave: (String, String) -> Unit, onClose: () -> Unit) {
    var url by remember(state.settings.baseUrl) { mutableStateOf(state.settings.baseUrl) }
    var token by remember(state.settings.token) { mutableStateOf(state.settings.token) }
    var copiedMessage by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(copiedMessage) {
        if (copiedMessage != null) {
            delay(2_500)
            copiedMessage = null
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = 620.dp)) {
            SectionEyebrow("연결")
            Text("기기와 서버", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            Text("서버를 바꾸면 새 스냅샷을 받아와요. 진행 중인 조작은 먼저 끝내는 편이 안전해요.", color = InkMuted)
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("서버 주소") }, singleLine = true)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("개인 연결 키") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onSave(url.trim().trimEnd('/'), token.trim()) }, shape = RoundedCornerShape(8.dp)) { Text("저장하고 연결") }
                OutlinedButton(onClick = onClose, shape = RoundedCornerShape(8.dp)) { Text("돌아가기") }
            }
            Spacer(Modifier.height(36.dp))
            ThinRule()
            Spacer(Modifier.height(18.dp))
            Text("이 기기", style = MaterialTheme.typography.labelMedium, color = InkMuted)
            Text(state.settings.deviceName.ifBlank { "이름 없음" }, style = MaterialTheme.typography.titleMedium)
            Text(state.settings.deviceId, style = MaterialTheme.typography.labelSmall, color = InkMuted)
            Spacer(Modifier.height(24.dp))
            Text("다른 기기 연결", style = MaterialTheme.typography.labelMedium, color = InkMuted)
            Spacer(Modifier.height(8.dp))
            Text("새 기기에 같은 서버 주소와 연결 키를 붙여 넣으세요.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(state.settings.baseUrl))
                        copiedMessage = "서버 주소를 복사했어요."
                    },
                    enabled = state.settings.baseUrl.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("서버 주소 복사") }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(state.settings.token))
                        copiedMessage = "연결 키를 복사했어요."
                    },
                    enabled = state.settings.token.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("연결 키 복사") }
            }
            if (copiedMessage != null) {
                Spacer(Modifier.height(10.dp))
                Text(copiedMessage.orEmpty(), color = Forest, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun ThinRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Rule.copy(alpha = 0.75f)))
}

@Composable
internal fun SectionEyebrow(text: String) {
    Text(text.uppercase(), color = Clay, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
}

@Composable
internal fun SubjectRail(
    subjects: List<Subject>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onAdd: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        subjects.forEach { subject ->
            val selected = subject.id == selectedId
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Ink else Color.Transparent)
                    .border(1.dp, if (selected) Ink else Rule, RoundedCornerShape(50))
                    .clickable { onSelect(subject.id) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(8.dp)) { drawCircle(parseSubjectColor(subject.colorHex)) }
                Spacer(Modifier.width(8.dp))
                Text(subject.name, color = if (selected) PaperRaised else Ink, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (onAdd != null) {
            TextButton(onClick = onAdd, modifier = Modifier.height(48.dp)) { Text("+ 새 주제") }
        }
    }
}

internal fun parseSubjectColor(value: String): Color = runCatching {
    val normalized = value.removePrefix("#")
    Color(("FF$normalized").toLong(16))
}.getOrElse { Forest }
