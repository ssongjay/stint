package dev.stint.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.stint.app.ui.Clay
import dev.stint.app.ui.Ink
import dev.stint.app.ui.InkMuted
import dev.stint.app.ui.Paper
import dev.stint.app.ui.Rule
import dev.stint.app.ui.Forest
import dev.stint.app.ui.focusSubjectChoice
import dev.stint.app.ui.formatDuration
import dev.stint.app.ui.parseSubjectColor
import dev.stint.app.ui.TimerClockVisual
import dev.stint.app.ui.WorkbenchTheme
import dev.stint.shared.ClientController
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.TimerClockStyle
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import dev.stint.shared.Subject
import dev.stint.shared.countdownClockReadingMillis
import dev.stint.shared.elapsedMillis
import dev.stint.shared.stopwatchClockReadingMillis
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private const val POPOVER_WIDTH = 330
private const val POPOVER_HEIGHT = 458
private const val POPOVER_GAP = 10

internal data class DesktopScreenBounds(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun timerPopoverLocation(
    anchor: Point,
    popupWidth: Int,
    popupHeight: Int,
    screens: List<DesktopScreenBounds>,
): Point {
    val screen = screens.firstOrNull {
        anchor.x in it.x until it.x + it.width && anchor.y in it.y until it.y + it.height
    } ?: screens.minByOrNull {
        val nearestX = anchor.x.coerceIn(it.x, it.x + it.width)
        val nearestY = anchor.y.coerceIn(it.y, it.y + it.height)
        val dx = anchor.x.toLong() - nearestX
        val dy = anchor.y.toLong() - nearestY
        dx * dx + dy * dy
    } ?: DesktopScreenBounds(0, 0, popupWidth, popupHeight)
    val minX = screen.x
    val maxX = (screen.x + screen.width - popupWidth).coerceAtLeast(minX)
    val minY = screen.y
    val maxY = (screen.y + screen.height - popupHeight).coerceAtLeast(minY)
    val below = anchor.y + POPOVER_GAP
    val y = if (below <= maxY) below else anchor.y - popupHeight - POPOVER_GAP
    return Point((anchor.x - popupWidth / 2).coerceIn(minX, maxX), y.coerceIn(minY, maxY))
}

private fun usableDesktopBounds(): List<DesktopScreenBounds> =
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val configuration = device.defaultConfiguration
        val bounds = configuration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        DesktopScreenBounds(
            x = bounds.x + insets.left,
            y = bounds.y + insets.top,
            width = (bounds.width - insets.left - insets.right).coerceAtLeast(1),
            height = (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
        )
    }

@Composable
internal fun DesktopTimerPopover(
    controller: ClientController,
    anchor: Point,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onQuit: () -> Unit,
    selectedSubjectId: String?,
    onSelectSubject: (String) -> Unit,
) {
    val location = remember(anchor) {
        timerPopoverLocation(anchor, POPOVER_WIDTH, POPOVER_HEIGHT, usableDesktopBounds())
    }
    val windowState = rememberWindowState(
        size = DpSize(POPOVER_WIDTH.dp, POPOVER_HEIGHT.dp),
        position = WindowPosition.Absolute(location.x.dp, location.y.dp),
    )
    Window(
        onCloseRequest = onDismiss,
        state = windowState,
        title = "Stint 타이머",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onDismiss()
                true
            } else false
        },
    ) {
        var receivedFocus by remember(window) { mutableStateOf(window.isFocused) }
        DisposableEffect(window) {
            val listener = object : WindowAdapter() {
                override fun windowGainedFocus(event: WindowEvent) {
                    receivedFocus = true
                }

                override fun windowLostFocus(event: WindowEvent) {
                    if (receivedFocus) onDismiss()
                }
            }
            val escapeListener = object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    if (event.keyCode == KeyEvent.VK_ESCAPE) onDismiss()
                }
            }
            window.addWindowFocusListener(listener)
            window.addKeyListener(escapeListener)
            onDispose {
                window.removeWindowFocusListener(listener)
                window.removeKeyListener(escapeListener)
            }
        }
        LaunchedEffect(window, location) {
            // AWT reports tray and screen coordinates in the same desktop coordinate space.
            window.setLocation(location.x, location.y)
            window.requestFocus()
            window.toFront()
        }
        WorkbenchTheme {
            TimerPopoverContent(controller, onDismiss, onOpen, onQuit, selectedSubjectId, onSelectSubject)
        }
    }
}

@Composable
private fun TimerPopoverContent(
    controller: ClientController,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onQuit: () -> Unit,
    selectedSubjectId: String?,
    onSelectSubject: (String) -> Unit,
) {
    val state by controller.state.collectAsState()
    val snapshot = state.snapshot
    val timer = snapshot?.timer ?: TimerState()
    val idle = timer.status == TimerStatus.IDLE
    val subjects = snapshot?.subjects.orEmpty()
    val subject = focusSubjectChoice(subjects, timer, selectedSubjectId)
    val availableSubjects = subjects.filterNot { it.archived }
    var choosingSubject by remember { mutableStateOf(false) }
    LaunchedEffect(timer.status, state.working, availableSubjects) {
        if (!idle || state.working || availableSubjects.isEmpty()) choosingSubject = false
    }
    val shownMode = if (idle) TimerMode.COUNTDOWN else timer.mode
    val targetSeconds = if (idle) subject?.focusSeconds ?: timer.targetSeconds else timer.targetSeconds
    val elapsed = if (idle) 0L else timer.elapsedMillis(state.serverNowEpochMs)
    val reading = if (shownMode == TimerMode.STOPWATCH) {
        stopwatchClockReadingMillis(elapsed)
    } else {
        val targetMillis = targetSeconds.coerceAtLeast(0) * 1_000L
        countdownClockReadingMillis((targetMillis - elapsed).coerceAtLeast(0), targetMillis)
    }
    val phaseLabel = when {
        idle -> "다음 집중"
        timer.phase == TimerPhase.FOCUS -> if (timer.status == TimerStatus.PAUSED) "집중 · 일시정지" else "집중 중"
        timer.phase == TimerPhase.SHORT_BREAK -> if (timer.isBreakWaitingToStart()) "짧은 휴식 · 시작 대기" else "짧은 휴식"
        else -> if (timer.isBreakWaitingToStart()) "긴 휴식 · 시작 대기" else "긴 휴식"
    }
    val canMutate = state.connection == ConnectionStatus.CONNECTED && !state.working
    Surface(
        modifier = Modifier.fillMaxSize().padding(5.dp),
        shape = RoundedCornerShape(22.dp),
        color = Paper,
        contentColor = Ink,
        border = BorderStroke(1.dp, Rule.copy(alpha = .8f)),
        shadowElevation = 10.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(phaseLabel, color = Clay, style = MaterialTheme.typography.labelLarge)
                    if (idle && availableSubjects.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                                TextButton(
                                    onClick = { choosingSubject = !choosingSubject },
                                    enabled = !state.working,
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.offset(x = (-8).dp).widthIn(max = 200.dp).height(32.dp).semantics {
                                        contentDescription = "주제 선택: ${subject?.name.orEmpty()}"
                                        stateDescription = if (choosingSubject) "목록 열림" else "목록 닫힘"
                                    },
                                ) {
                                    Text(subject?.name ?: "주제를 고르세요",
                                        modifier = Modifier.widthIn(max = 160.dp).clipToBounds(),
                                        color = Ink, style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.width(6.dp))
                                    SubjectPickerChevron(choosingSubject)
                                }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                            Text(
                                subject?.name ?: if (idle) "주제를 고르세요" else "알 수 없는 주제",
                                color = InkMuted,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(
                    when (state.connection) {
                        ConnectionStatus.CONNECTED -> "동기화됨"
                        ConnectionStatus.CONNECTING -> "동기화 중"
                        ConnectionStatus.OFFLINE -> "오프라인"
                        ConnectionStatus.SETUP -> "연결 필요"
                    },
                    color = InkMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            if (choosingSubject) {
                PopoverSubjectList(availableSubjects, subject?.id) { id ->
                    // Recheck the live controller before changing a draft selected from an older frame.
                    if (controller.state.value.snapshot?.timer?.status == TimerStatus.IDLE && !controller.state.value.working) {
                        onSelectSubject(id)
                    }
                    choosingSubject = false
                }
            } else if (idle && subject == null) {
                Box(
                    Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "주제를 만들면 타이머가 여기에 표시돼요",
                        color = InkMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                TimerClockVisual(
                    style = TimerClockStyle.ORBIT,
                    reading = reading,
                    running = !idle && timer.status == TimerStatus.RUNNING,
                    height = 240.dp,
                    sizingReferenceSeconds = targetSeconds,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                when (timer.status) {
                    TimerStatus.IDLE -> Button(
                        onClick = { subject?.let { controller.start(it.id, TimerMode.COUNTDOWN) } },
                        enabled = canMutate && subject != null && !choosingSubject,
                    ) { Text("집중 시작") }
                    TimerStatus.RUNNING -> Button(onClick = controller::pause, enabled = canMutate) {
                        Text("일시정지")
                    }
                    TimerStatus.PAUSED -> Button(onClick = controller::resume, enabled = canMutate) {
                        Text(if (timer.isBreakWaitingToStart()) "휴식 시작" else "계속하기")
                    }
                }
                if (!idle) {
                    OutlinedButton(
                        onClick = if (timer.phase == TimerPhase.FOCUS) controller::finish else controller::skipBreak,
                        enabled = canMutate,
                    ) { Text(if (timer.phase == TimerPhase.FOCUS) "마치기" else "휴식 건너뛰기") }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onDismiss(); onOpen() }) { Text("Stint 열기") }
                TextButton(onClick = { onDismiss(); onQuit() }) { Text("종료") }
            }
        }
    }
}

@Composable
private fun SubjectPickerChevron(expanded: Boolean) {
    Canvas(Modifier.size(12.dp)) {
        val outerY = size.height * if (expanded) .65f else .35f
        val innerY = size.height * if (expanded) .35f else .65f
        val middle = Offset(size.width * .5f, innerY)
        drawLine(Clay, Offset(size.width * .2f, outerY), middle, 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(Clay, middle, Offset(size.width * .8f, outerY), 1.5.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun PopoverSubjectList(subjects: List<Subject>, selectedId: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().height(240.dp).verticalScroll(rememberScrollState())) {
        Text("주제 선택", color = Clay, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 6.dp))
        subjects.forEach { subject ->
            TextButton(
                onClick = { onSelect(subject.id) },
                modifier = Modifier.fillMaxWidth().semantics { selected = subject.id == selectedId },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            ) {
                Box(Modifier.size(8.dp).background(parseSubjectColor(subject.colorHex), CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(subject.name, color = Ink, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatDuration(subject.focusSeconds), color = InkMuted,
                        style = MaterialTheme.typography.labelSmall)
                }
                if (subject.id == selectedId) Text("✓", color = Forest, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}
