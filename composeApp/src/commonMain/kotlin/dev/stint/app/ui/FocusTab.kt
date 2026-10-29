package dev.stint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import dev.stint.shared.ClientController
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.FocusSession
import dev.stint.shared.Subject
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerClockStyle
import dev.stint.shared.countdownClockReadingMillis
import dev.stint.shared.stopwatchClockReadingMillis
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import dev.stint.shared.elapsedMillis

@Composable
internal fun FocusTab(
    controller: ClientController,
    state: ClientState,
    nowEpochMs: Long,
    selectedSubjectId: String?,
    onSelectSubject: (String) -> Unit,
    onAddSubject: () -> Unit,
    onEditSubject: () -> Unit,
    onTimeSettings: () -> Unit,
    wide: Boolean,
    foldPosture: FoldPosture,
) {
    val snapshot = state.snapshot
    val subjects = snapshot?.subjects.orEmpty().filterNot { it.archived }
    val timer = snapshot?.timer ?: TimerState()
    val activeSubject = subjects.firstOrNull { timer.status != TimerStatus.IDLE && it.id == timer.subjectId }
        ?: subjects.firstOrNull { it.id == selectedSubjectId }
    var selectedModeName by rememberSaveable { mutableStateOf(TimerMode.COUNTDOWN.name) }
    val selectedMode = TimerMode.entries.firstOrNull { it.name == selectedModeName } ?: TimerMode.COUNTDOWN
    val connected = state.connection == ConnectionStatus.CONNECTED && !state.working
    var dialAdjusting by remember { mutableStateOf(false) }

    if (subjects.isEmpty()) {
        EmptySubjects(onAddSubject)
        return
    }

    val mainContent: @Composable (Modifier, Boolean, Dp) -> Unit = { modifier, scrollable, faceHeight ->
        Column(
            modifier
                .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
                .padding(horizontal = if (wide) 34.dp else 20.dp,
                    vertical = if (foldPosture.isBook) 12.dp else if (wide) 24.dp else 20.dp),
        ) {
            SectionEyebrow("작업대")
            Spacer(Modifier.height(8.dp))
            SubjectRail(subjects, activeSubject?.id, onSelectSubject, onAddSubject)
            Spacer(Modifier.height(if (foldPosture.isBook) 12.dp else if (wide) 28.dp else 18.dp))
            TimerWorkbench(
                timer = timer,
                subject = activeSubject,
                nowEpochMs = nowEpochMs,
                selectedMode = selectedMode,
                clockStyle = state.clockStyle,
                onClockStyle = controller::setClockStyle,
                clockHeight = faceHeight,
                canMutate = connected && !dialAdjusting,
                dialSession = timeDialSession(state, activeSubject, selectedMode),
                dialBusy = state.working,
                dialFeedback = state.message,
                onDialCommit = { minutes, key -> commitTimeDial(controller, activeSubject?.id, selectedMode, minutes, key) },
                onDialAdjustingChange = { dialAdjusting = it },
                onMode = { selectedModeName = it.name },
                onStart = { activeSubject?.let { controller.start(it.id, selectedMode, if (selectedMode == TimerMode.COUNTDOWN) it.focusSeconds else null) } },
                onPause = controller::pause,
                onResume = controller::resume,
                onFinish = controller::finish,
                onSkipBreak = controller::skipBreak,
                onTimeSettings = onTimeSettings,
            )
            Spacer(Modifier.height(24.dp))
            ThinRule()
            Spacer(Modifier.height(18.dp))
            SubjectSummary(activeSubject, onEditSubject)
        }
    }

    val density = LocalDensity.current
    var contentOriginX by remember { mutableStateOf(0f) }
    var contentOriginY by remember { mutableStateOf(0f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            with(density) {
                contentOriginX = bounds.left.toDp().value
                contentOriginY = bounds.top.toDp().value
            }
        },
    ) {
        val hingeLeft = (foldPosture.hingeLeftDp - contentOriginX).coerceAtLeast(0f)
        val hingeTop = (foldPosture.hingeTopDp - contentOriginY).coerceAtLeast(0f)
        val validBook = foldPosture.isBook && hingeLeft > 120f && hingeLeft + foldPosture.hingeWidthDp < maxWidth.value - 120f
        val validTabletop = foldPosture.isTabletop && hingeTop > 120f && hingeTop + foldPosture.hingeHeightDp < maxHeight.value - 120f
        val bookClockHeight = (maxHeight.value - 320f).coerceIn(132f, 240f).dp

        when {
            validTabletop -> TabletopFocus(
                controller = controller,
                state = state,
                nowEpochMs = nowEpochMs,
                subjects = subjects,
                activeSubject = activeSubject,
                selectedMode = selectedMode,
                onMode = { selectedModeName = it.name },
                onSelectSubject = onSelectSubject,
                onAddSubject = onAddSubject,
                onEditSubject = onEditSubject,
                onTimeSettings = onTimeSettings,
                hingeTopDp = hingeTop,
                hingeHeightDp = foldPosture.hingeHeightDp,
                dialAdjusting = dialAdjusting,
                onDialAdjustingChange = { dialAdjusting = it },
            )

            validBook -> Row(Modifier.fillMaxSize()) {
                mainContent(Modifier.width(hingeLeft.dp).fillMaxHeight(), true, bookClockHeight)
                Spacer(Modifier.width(foldPosture.hingeWidthDp.dp))
                TodayLedger(
                    subjects = subjects,
                    sessions = snapshot?.sessions.orEmpty(),
                    nowEpochMs = nowEpochMs,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onMemo = controller::saveMemo,
                    canEdit = connected,
                )
            }

            wide -> Row(Modifier.fillMaxSize()) {
                mainContent(Modifier.weight(1.3f).fillMaxHeight(), true, 316.dp)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Rule.copy(alpha = 0.75f)))
                TodayLedger(
                    subjects = subjects,
                    sessions = snapshot?.sessions.orEmpty(),
                    nowEpochMs = nowEpochMs,
                    modifier = Modifier.weight(0.8f).fillMaxHeight(),
                    onMemo = controller::saveMemo,
                    canEdit = connected,
                )
            }

            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                mainContent(Modifier.fillMaxWidth(), false, 262.dp)
                ThinRule(Modifier.padding(horizontal = 20.dp))
                TodayLedger(
                    subjects = subjects,
                    sessions = snapshot?.sessions.orEmpty(),
                    nowEpochMs = nowEpochMs,
                    modifier = Modifier.fillMaxWidth(),
                    onMemo = controller::saveMemo,
                    nestedScroll = false,
                    canEdit = connected,
                )
            }
        }
    }
}

@Composable
private fun TabletopFocus(
    controller: ClientController,
    state: ClientState,
    nowEpochMs: Long,
    subjects: List<Subject>,
    activeSubject: Subject?,
    selectedMode: TimerMode,
    onMode: (TimerMode) -> Unit,
    onSelectSubject: (String) -> Unit,
    onAddSubject: () -> Unit,
    onEditSubject: () -> Unit,
    onTimeSettings: () -> Unit,
    hingeTopDp: Float,
    hingeHeightDp: Float,
    dialAdjusting: Boolean,
    onDialAdjustingChange: (Boolean) -> Unit,
) {
    val timer = state.snapshot?.timer ?: TimerState()
    val canMutate = state.connection == ConnectionStatus.CONNECTED && !state.working
    val start: () -> Unit = {
        if (activeSubject != null) {
            controller.start(
                activeSubject.id,
                selectedMode,
                if (selectedMode == TimerMode.COUNTDOWN) activeSubject.focusSeconds else null,
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(hingeTopDp.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 18.dp),
        ) {
            SectionEyebrow("테이블탑 · 보기")
            Spacer(Modifier.height(6.dp))
            TimerReadout(
                timer = timer,
                subject = activeSubject,
                nowEpochMs = nowEpochMs,
                selectedMode = selectedMode,
                onMode = onMode,
                clockStyle = state.clockStyle,
                onClockStyle = controller::setClockStyle,
                clockHeight = (hingeTopDp - 116f).coerceIn(72f, 180f).dp,
                showModeSelector = false,
                showStylePicker = false,
                showTimeSettings = false,
                dialSession = timeDialSession(state, activeSubject, selectedMode),
                dialBusy = state.working,
                dialFeedback = state.message,
                onDialCommit = { minutes, key -> commitTimeDial(controller, activeSubject?.id, selectedMode, minutes, key) },
                onDialAdjustingChange = onDialAdjustingChange,
            )
        }
        Spacer(Modifier.height(hingeHeightDp.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionEyebrow("손이 닿는 곳") }
                    ClockStylePicker(state.clockStyle, controller::setClockStyle)
                }
                Spacer(Modifier.height(8.dp))
                SubjectRail(subjects, activeSubject?.id, onSelectSubject, onAddSubject)
                if (timer.status == TimerStatus.IDLE && timer.phase == TimerPhase.FOCUS) {
                    Spacer(Modifier.height(8.dp))
                    ModeSwitch(selectedMode, onMode)
                }
                Spacer(Modifier.height(14.dp))
                TimerActions(
                    timer = timer,
                    subject = activeSubject,
                    selectedMode = selectedMode,
                    canMutate = canMutate && !dialAdjusting,
                    onStart = start,
                    onPause = controller::pause,
                    onResume = controller::resume,
                    onFinish = controller::finish,
                    onSkipBreak = controller::skipBreak,
                )
                TextButton(
                    onClick = onTimeSettings,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("시간 설정") }
                Spacer(Modifier.height(22.dp))
                ThinRule()
                Spacer(Modifier.height(14.dp))
                SubjectSummary(activeSubject, onEditSubject)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Rule.copy(alpha = 0.75f)))
            TodayLedger(
                subjects = subjects,
                sessions = state.snapshot?.sessions.orEmpty(),
                nowEpochMs = nowEpochMs,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onMemo = controller::saveMemo,
                canEdit = canMutate,
            )
        }
    }
}

@Composable
private fun TimerWorkbench(
    timer: TimerState,
    subject: Subject?,
    nowEpochMs: Long,
    selectedMode: TimerMode,
    clockStyle: TimerClockStyle,
    onClockStyle: (TimerClockStyle) -> Unit,
    clockHeight: Dp,
    canMutate: Boolean,
    dialSession: TimeDialSession,
    dialBusy: Boolean,
    dialFeedback: String?,
    onDialCommit: (Int, String) -> Boolean,
    onDialAdjustingChange: (Boolean) -> Unit,
    onMode: (TimerMode) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onSkipBreak: () -> Unit,
    onTimeSettings: () -> Unit,
) {
    TimerReadout(
        timer,
        subject,
        nowEpochMs,
        selectedMode,
        onMode,
        clockStyle,
        onClockStyle,
        clockHeight,
        onTimeSettings = onTimeSettings,
        dialSession = dialSession,
        dialBusy = dialBusy,
        dialFeedback = dialFeedback,
        onDialCommit = onDialCommit,
        onDialAdjustingChange = onDialAdjustingChange,
    )
    Spacer(Modifier.height(16.dp))
    TimerActions(
        timer = timer,
        subject = subject,
        selectedMode = selectedMode,
        canMutate = canMutate,
        onStart = onStart,
        onPause = onPause,
        onResume = onResume,
        onFinish = onFinish,
        onSkipBreak = onSkipBreak,
    )
}

@Composable
private fun TimerReadout(
    timer: TimerState,
    subject: Subject?,
    nowEpochMs: Long,
    selectedMode: TimerMode,
    onMode: (TimerMode) -> Unit,
    clockStyle: TimerClockStyle,
    onClockStyle: (TimerClockStyle) -> Unit,
    clockHeight: Dp,
    showModeSelector: Boolean = true,
    showStylePicker: Boolean = true,
    showTimeSettings: Boolean = true,
    onTimeSettings: () -> Unit = {},
    dialSession: TimeDialSession,
    dialBusy: Boolean,
    dialFeedback: String?,
    onDialCommit: (Int, String) -> Boolean,
    onDialAdjustingChange: (Boolean) -> Unit,
) {
    val status = timer.status
    val shownMode = if (status == TimerStatus.IDLE) selectedMode else timer.mode
    val elapsed = if (status == TimerStatus.IDLE) 0L else timer.elapsedMillis(nowEpochMs)
    val phaseLabel = when (timer.phase) {
        TimerPhase.FOCUS -> if (status == TimerStatus.IDLE) "다음 집중" else "집중 중"
        TimerPhase.SHORT_BREAK -> "짧은 휴식"
        TimerPhase.LONG_BREAK -> "긴 휴식"
    }

    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(phaseLabel, color = Clay, style = MaterialTheme.typography.labelLarge)
            Text(subject?.name ?: "주제를 고르세요", color = InkMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showStylePicker) ClockStylePicker(clockStyle, onClockStyle)
    }
    if (showModeSelector && status == TimerStatus.IDLE && timer.phase == TimerPhase.FOCUS) {
        Spacer(Modifier.height(8.dp))
        ModeSwitch(selectedMode, onMode)
    }
    Spacer(Modifier.height(8.dp))
    val target = if (status == TimerStatus.IDLE) subject?.focusSeconds ?: timer.targetSeconds else timer.targetSeconds
    val targetMillis = target.coerceAtLeast(0) * 1000L
    val reading = if (shownMode == TimerMode.STOPWATCH) {
        stopwatchClockReadingMillis(elapsed)
    } else {
        countdownClockReadingMillis((targetMillis - elapsed).coerceAtLeast(0), targetMillis)
    }
    if (shownMode == TimerMode.COUNTDOWN) {
        ClockTimeDial(
            valueMinutes = dialSession.valueMinutes,
            minimumMinutes = dialSession.minimumMinutes,
            maximumMinutes = dialSession.maximumMinutes,
            enabled = dialSession.enabled,
            interactionKey = dialSession.interactionKey,
            busy = dialBusy,
            feedbackMessage = dialFeedback,
            onCommit = onDialCommit,
            height = clockHeight,
            compact = !showTimeSettings,
        onAdjustingChange = onDialAdjustingChange,
        ) { previewMinutes, faceHeight ->
            val previewTarget = previewMinutes?.times(60)
            TimerClockVisual(
                style = clockStyle,
                reading = if (previewTarget != null) countdownClockReadingMillis(
                    previewTarget * 1000L, previewTarget * 1000L,
                ) else reading,
                running = previewTarget == null && status == TimerStatus.RUNNING,
                height = faceHeight,
                sizingReferenceSeconds = maxOf(target, previewTarget ?: 0),
                readoutLabel = if (previewTarget != null) "전체 목표" else null,
            )
        }
    } else {
        TimerClockVisual(
            style = clockStyle,
            reading = reading,
            running = status == TimerStatus.RUNNING,
            height = clockHeight,
            sizingReferenceSeconds = reading.valueSeconds,
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth()) {
        Text(
            when {
                timer.phase != TimerPhase.FOCUS -> "휴식이 끝나면 다음 집중을 직접 시작해요"
                shownMode == TimerMode.STOPWATCH -> "경과 시간을 기록해요"
                status == TimerStatus.IDLE -> "기본 ${formatDuration(subject?.focusSeconds ?: timer.targetSeconds)}"
                else -> "목표 ${formatDuration(timer.targetSeconds)}"
            },
            color = InkMuted,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.weight(1f))
        if (showTimeSettings) {
            TextButton(
                onClick = onTimeSettings,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("시간 설정") }
        }
        if (status != TimerStatus.IDLE) {
            Text(
                when (status) {
                    TimerStatus.RUNNING -> "진행 중"
                    TimerStatus.PAUSED -> "잠시 멈춤"
                    else -> ""
                },
                color = if (status == TimerStatus.RUNNING) Forest else Clay,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ClockStylePicker(style: TimerClockStyle, onStyle: (TimerClockStyle) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (style == TimerClockStyle.ORBIT) "오빗 링" else "모래시계"
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "시계 모양: $label" },
        ) { Text("$label ⌄", style = MaterialTheme.typography.labelMedium) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TimerClockStyle.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == TimerClockStyle.ORBIT) "오빗 링" else "모래시계") },
                    onClick = { onStyle(option); expanded = false },
                    trailingIcon = { if (option == style) Text("✓", color = Forest) },
                )
            }
        }
    }
}

@Composable
private fun TimerActions(
    timer: TimerState,
    subject: Subject?,
    selectedMode: TimerMode,
    canMutate: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onSkipBreak: () -> Unit,
) {
    val status = timer.status
    val shownMode = if (status == TimerStatus.IDLE) selectedMode else timer.mode
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            status == TimerStatus.IDLE && timer.phase == TimerPhase.FOCUS -> {
                Button(
                    onClick = onStart,
                    enabled = canMutate && subject != null,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Clay),
                ) { Text(if (shownMode == TimerMode.COUNTDOWN) "집중 시작" else "측정 시작") }
            }
            status == TimerStatus.RUNNING -> {
                Button(onClick = onPause, enabled = canMutate, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(8.dp)) { Text("일시정지") }
                OutlinedButton(onClick = onFinish, enabled = canMutate, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(8.dp)) { Text("마치기") }
            }
            status == TimerStatus.PAUSED -> {
                Button(onClick = onResume, enabled = canMutate, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(8.dp)) { Text("이어서 하기") }
                OutlinedButton(onClick = onFinish, enabled = canMutate, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(8.dp)) { Text("마치기") }
            }
            else -> Unit
        }
        if (timer.phase != TimerPhase.FOCUS) {
            TextButton(onClick = onSkipBreak, enabled = canMutate, modifier = Modifier.height(52.dp)) { Text("휴식 건너뛰기") }
        }
    }
    if (!canMutate) {
        Spacer(Modifier.height(14.dp))
        Text("오프라인에서는 시간을 계속 보여주지만 조작은 연결 후 가능해요.", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
    }
    if (timer.completedFocusCount > 0) {
        Spacer(Modifier.height(28.dp))
        CycleMarks(timer.completedFocusCount, subject?.longBreakEvery ?: 4)
    }
}

@Composable
private fun ModeSwitch(mode: TimerMode, onMode: (TimerMode) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(PaperDeep).padding(3.dp)) {
        listOf(TimerMode.COUNTDOWN to "카운트다운", TimerMode.STOPWATCH to "스톱워치").forEach { (value, label) ->
            TextButton(
                onClick = { onMode(value) },
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (mode == value) PaperRaised else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (mode == value) Ink else InkMuted,
                ),
                shape = RoundedCornerShape(50),
            ) { Text(label, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun CycleMarks(completed: Int, interval: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("이번 흐름", color = InkMuted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(12.dp))
        repeat(interval.coerceIn(2, 8)) { index ->
            val filled = index < completed % interval
            Box(Modifier.padding(end = 7.dp).size(9.dp).clip(CircleShape).background(if (filled) Forest else Rule))
        }
        Text("${completed}회 완료", color = InkMuted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SubjectSummary(subject: Subject?, onEdit: () -> Unit) {
    if (subject == null) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("${formatDuration(subject.focusSeconds)} 집중", style = MaterialTheme.typography.titleMedium)
            Text("짧은 휴식 ${formatDuration(subject.shortBreakSeconds)} · ${subject.longBreakEvery}회마다 긴 휴식", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onEdit) { Text("주제 설정") }
    }
}

@Composable
private fun EmptySubjects(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("첫 주제를 놓아볼까요", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("공부, 독서, 만들기처럼 시간을 모아볼 대상을 적어 주세요.", color = InkMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, shape = RoundedCornerShape(8.dp)) { Text("주제 만들기") }
    }
}

@Composable
internal fun TodayLedger(
    subjects: List<Subject>,
    sessions: List<FocusSession>,
    nowEpochMs: Long,
    modifier: Modifier = Modifier,
    onMemo: (String, String) -> Unit,
    nestedScroll: Boolean = true,
    canEdit: Boolean = true,
) {
    val today = localDate(nowEpochMs)
    val todaySessions = sessions.filter { it.localDay() == today }.sortedByDescending { it.startedAtEpochMs }
    val duration = todaySessions.sumOf { it.durationSeconds }
    val content = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)
        .let { if (nestedScroll) it.verticalScroll(rememberScrollState()) else it }
    Column(modifier.then(content)) {
        SectionEyebrow("오늘")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(formatDuration(duration), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            Text("${todaySessions.size}번의 집중", color = InkMuted, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(22.dp))
        ThinRule()
        if (todaySessions.isEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text("아직 기록이 없어요.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("첫 집중을 마치면 시간순으로 이곳에 쌓여요.", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
        } else {
            todaySessions.forEach { session ->
                LedgerRow(session, subjects.firstOrNull { it.id == session.subjectId }, nowEpochMs, onMemo, canEdit)
                ThinRule()
            }
        }
    }
}

@Composable
internal fun LedgerRow(session: FocusSession, subject: Subject?, nowEpochMs: Long, onMemo: (String, String) -> Unit, canEdit: Boolean) {
    var editing by rememberSaveable(session.id) { mutableStateOf(false) }
    var memo by rememberSaveable(session.id, session.memo) { mutableStateOf(session.memo) }
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatLocalTime(session.startedAtEpochMs), style = MaterialTheme.typography.labelMedium, color = InkMuted)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(parseSubjectColor(subject?.colorHex ?: "466B57")))
            Spacer(Modifier.width(8.dp))
            Text(subject?.name ?: "보관된 주제", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(session.durationSeconds), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(6.dp))
        if (editing) {
            androidx.compose.material3.OutlinedTextField(
                value = memo,
                onValueChange = { if (it.length <= 4000) memo = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("회고") },
                minLines = 2,
            )
            Row {
                TextButton(onClick = { onMemo(session.id, memo.trim()); editing = false }) { Text("저장") }
                TextButton(onClick = { memo = session.memo; editing = false }) { Text("취소") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.memo.ifBlank { "회고를 남겨보세요" },
                    color = if (session.memo.isBlank()) InkMuted else Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { editing = true }, enabled = canEdit) { Text(if (session.memo.isBlank()) "쓰기" else "수정") }
            }
        }
        Text(relativeTime(session.endedAtEpochMs, nowEpochMs), color = InkMuted, style = MaterialTheme.typography.labelSmall)
    }
}
