package dev.stint.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.Subject
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import dev.stint.shared.elapsedMillis

@Composable
internal fun TimeSettingsPanel(
    state: ClientState,
    subject: Subject?,
    nowEpochMs: Long,
    onPause: () -> Unit,
    onSaveCurrent: (Int) -> Unit,
    onSaveDefaults: (Subject) -> Unit,
    onClose: () -> Unit,
) {
    val timer = state.snapshot?.timer ?: TimerState()
    val canMutate = state.connection == ConnectionStatus.CONNECTED && !state.working
    val currentMaxMinutes = when (timer.phase) {
        TimerPhase.FOCUS -> 240
        TimerPhase.SHORT_BREAK -> 60
        TimerPhase.LONG_BREAK -> 120
    }
    var currentMinutes by rememberSaveable(timer.id, timer.status) {
        mutableStateOf((timer.targetSeconds / 60).coerceAtLeast(1).toString())
    }
    var focusMinutes by rememberSaveable(subject?.id) {
        mutableStateOf(((subject?.focusSeconds ?: 1500) / 60).toString())
    }
    var shortBreakMinutes by rememberSaveable(subject?.id) {
        mutableStateOf(((subject?.shortBreakSeconds ?: 300) / 60).toString())
    }
    var longBreakMinutes by rememberSaveable(subject?.id) {
        mutableStateOf(((subject?.longBreakSeconds ?: 900) / 60).toString())
    }
    var longBreakEvery by rememberSaveable(subject?.id) {
        mutableStateOf((subject?.longBreakEvery ?: 4).toString())
    }
    var currentError by rememberSaveable(timer.id) { mutableStateOf<String?>(null) }
    var defaultsError by rememberSaveable(subject?.id) { mutableStateOf<String?>(null) }
    var pendingCurrentSeconds by rememberSaveable(timer.id) { mutableStateOf<Int?>(null) }
    var pendingDefaults by remember(subject?.id) { mutableStateOf<Subject?>(null) }

    LaunchedEffect(pendingCurrentSeconds, timer.targetSeconds, state.working) {
        val pending = pendingCurrentSeconds
        if (pending != null && !state.working && timer.targetSeconds == pending) onClose()
    }
    LaunchedEffect(pendingDefaults, subject, state.working) {
        val pending = pendingDefaults
        if (pending != null && !state.working && subject?.hasSameTimeDefaults(pending) == true) onClose()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    SectionEyebrow("시간 설정")
                    Spacer(Modifier.height(6.dp))
                    Text(subject?.name ?: "현재 주제", style = MaterialTheme.typography.headlineMedium)
                }
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("닫기") }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "이번 타이머와 앞으로 사용할 기본 시간을 따로 바꿀 수 있어요.",
                color = InkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(28.dp))
            Text("이번 타이머", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            CurrentTimerSettings(
                timer = timer,
                nowEpochMs = nowEpochMs,
                minutes = currentMinutes,
                maxMinutes = currentMaxMinutes,
                canMutate = canMutate,
                saving = pendingCurrentSeconds != null && state.working,
                error = currentError,
                onMinutes = {
                    currentMinutes = durationDigits(it)
                    currentError = null
                },
                onPause = onPause,
                onSave = {
                    val minutes = currentMinutes.toIntOrNull()
                    currentError = currentDurationError(minutes, currentMaxMinutes, timer.elapsedMillis(nowEpochMs))
                    if (currentError == null) {
                        val seconds = minutes!! * 60
                        if (seconds == timer.targetSeconds) onClose()
                        else {
                            pendingCurrentSeconds = seconds
                            onSaveCurrent(seconds)
                        }
                    }
                },
            )

            Spacer(Modifier.height(28.dp))
            ThinRule()
            Spacer(Modifier.height(24.dp))
            Text("주제 기본 시간", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "새 집중과 다음 자동 휴식부터 적용돼요. 진행 중인 타이머는 그대로 유지해요.",
                color = InkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))
            Presets(
                values = listOf(15, 25, 45, 60),
                selected = focusMinutes.toIntOrNull(),
                enabled = canMutate,
                onSelect = { focusMinutes = it.toString(); defaultsError = null },
            )
            Spacer(Modifier.height(10.dp))
            MinuteInput("집중", focusMinutes, { focusMinutes = durationDigits(it); defaultsError = null }, enabled = canMutate)
            Spacer(Modifier.height(12.dp))
            MinuteInput("짧은 휴식", shortBreakMinutes, { shortBreakMinutes = durationDigits(it); defaultsError = null }, enabled = canMutate)
            Spacer(Modifier.height(12.dp))
            MinuteInput("긴 휴식", longBreakMinutes, { longBreakMinutes = durationDigits(it); defaultsError = null }, enabled = canMutate)
            Spacer(Modifier.height(12.dp))
            MinuteInput(
                label = "긴 휴식 간격",
                value = longBreakEvery,
                onValue = { longBreakEvery = durationDigits(it); defaultsError = null },
                suffix = "회",
                enabled = canMutate,
            )
            defaultsError?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val focus = focusMinutes.toIntOrNull()
                    val short = shortBreakMinutes.toIntOrNull()
                    val long = longBreakMinutes.toIntOrNull()
                    val every = longBreakEvery.toIntOrNull()
                    defaultsError = defaultDurationError(focus, short, long, every)
                    if (defaultsError == null && subject != null) {
                        val requested = subject.copy(
                            focusSeconds = focus!! * 60,
                            shortBreakSeconds = short!! * 60,
                            longBreakSeconds = long!! * 60,
                            longBreakEvery = every!!,
                        )
                        if (subject.hasSameTimeDefaults(requested)) onClose()
                        else {
                            pendingDefaults = requested
                            onSaveDefaults(requested)
                        }
                    }
                },
                enabled = canMutate && subject != null,
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text(if (pendingDefaults != null && state.working) "저장 중…" else "기본 시간 저장") }

            if (!canMutate && !state.working) {
                Spacer(Modifier.height(12.dp))
                Text("연결이 복구되면 시간을 저장할 수 있어요.", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CurrentTimerSettings(
    timer: TimerState,
    nowEpochMs: Long,
    minutes: String,
    maxMinutes: Int,
    canMutate: Boolean,
    saving: Boolean,
    error: String?,
    onMinutes: (String) -> Unit,
    onPause: () -> Unit,
    onSave: () -> Unit,
) {
    when {
        timer.status == TimerStatus.IDLE -> Text(
            "아직 시작하지 않은 타이머는 아래 기본 집중 시간을 사용해요.",
            color = InkMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        timer.mode == TimerMode.STOPWATCH -> Text(
            "스톱워치는 목표 시간 없이 경과 시간을 기록해요.",
            color = InkMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        timer.status == TimerStatus.RUNNING -> {
            Text(
                "경과 시간을 정확히 유지하려면 먼저 타이머를 잠시 멈춰 주세요.",
                color = InkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onPause,
                enabled = canMutate,
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("먼저 일시정지") }
        }
        else -> {
            Text(
                "지금까지 지난 시간은 유지하고, 이번 타이머의 전체 목표만 바꿔요.",
                color = InkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Presets(
                values = presetsFor(timer.phase),
                selected = minutes.toIntOrNull(),
                enabled = canMutate,
                onSelect = { onMinutes(it.toString()) },
            )
            Spacer(Modifier.height(10.dp))
            MinuteInput("전체 목표", minutes, onMinutes, enabled = canMutate)
            Text(
                "경과 ${formatTimer((timer.elapsedMillis(nowEpochMs) / 1000L).toInt())}",
                color = InkMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSave,
                enabled = canMutate,
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text(if (saving) "저장 중…" else "이번 목표 저장") }
            Text(
                "${maxMinutes}분까지 설정할 수 있어요.",
                color = InkMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun Presets(values: List<Int>, selected: Int?, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { minutes ->
            OutlinedButton(
                onClick = { onSelect(minutes) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(50),
            ) { Text(if (selected == minutes) "${minutes}분 ✓" else "${minutes}분") }
        }
    }
}

@Composable
private fun MinuteInput(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    suffix: String = "분",
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

internal fun currentDurationError(minutes: Int?, maxMinutes: Int, elapsedMillis: Long): String? = when {
    minutes == null || minutes !in 1..maxMinutes -> "전체 목표는 1분부터 ${maxMinutes}분까지 입력할 수 있어요."
    minutes * 60_000L <= elapsedMillis -> "이미 지난 시간보다 길게 설정해 주세요."
    else -> null
}

internal fun defaultDurationError(focus: Int?, short: Int?, long: Int?, every: Int?): String? = when {
    focus == null || focus !in 1..240 -> "집중 시간은 1분부터 240분까지 입력할 수 있어요."
    short == null || short !in 1..60 -> "짧은 휴식은 1분부터 60분까지 입력할 수 있어요."
    long == null || long !in 1..120 -> "긴 휴식은 1분부터 120분까지 입력할 수 있어요."
    every == null || every !in 2..12 -> "긴 휴식 간격은 2회부터 12회까지 입력할 수 있어요."
    else -> null
}

private fun durationDigits(value: String): String = value.filter(Char::isDigit).take(3)

private fun presetsFor(phase: TimerPhase): List<Int> = when (phase) {
    TimerPhase.FOCUS -> listOf(15, 25, 45, 60)
    TimerPhase.SHORT_BREAK -> listOf(5, 10, 15, 20)
    TimerPhase.LONG_BREAK -> listOf(10, 15, 20, 30)
}

private fun Subject.hasSameTimeDefaults(other: Subject): Boolean =
    focusSeconds == other.focusSeconds &&
        shortBreakSeconds == other.shortBreakSeconds &&
        longBreakSeconds == other.longBreakSeconds &&
        longBreakEvery == other.longBreakEvery
