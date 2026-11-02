package dev.stint.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.stint.shared.Subject

/** Server-backed subject preferences; changing them never restarts the current timer. */
@Composable
internal fun PomodoroControls(
    subject: Subject,
    enabled: Boolean,
    countdown: Boolean,
    onSave: (Subject) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        FlowSwitch(
            title = "뽀모도로 모드",
            description = if (subject.pomodoroEnabled) "집중과 휴식을 한 흐름으로" else "집중이 끝나면 타이머 종료",
            checked = subject.pomodoroEnabled,
            enabled = enabled && countdown,
            onChecked = { onSave(subject.copy(pomodoroEnabled = it)) },
        )
        FlowSwitch(
            title = "휴식 자동 시작",
            description = when {
                !subject.pomodoroEnabled -> "뽀모도로 모드를 켜면 사용할 수 있어요"
                subject.autoStartBreak -> "집중이 끝나면 바로 쉬어요"
                else -> "휴식 시작을 눌렀을 때 쉬어요"
            },
            checked = subject.autoStartBreak,
            enabled = enabled && countdown && subject.pomodoroEnabled,
            onChecked = { onSave(subject.copy(autoStartBreak = it)) },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (countdown) "이 주제에 저장돼요 · 집중 종료 후 적용"
            else "카운트다운에서 사용할 수 있어요",
            color = InkMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun FlowSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked)
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = if (enabled) Ink else InkMuted, style = MaterialTheme.typography.bodyMedium)
            Text(description, color = InkMuted, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
